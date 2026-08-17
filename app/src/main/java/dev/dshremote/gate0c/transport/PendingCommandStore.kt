package dev.dshremote.gate0c.transport

import android.content.Context
import android.util.AtomicFile
import dev.dshremote.security.SealedWrappingKeys
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class PendingCommandPhase {
    PREPARED,
    RECEIVED,
    REQUESTED,
    UNKNOWN,
}

enum class PendingCommandOperation {
    SEND_INPUT,
    STOP,
    DECIDE_APPROVAL,
    CREATE_SESSION,
    SELECT_AGENT_PRESET,
    SELECT_MODEL,
    FORK_SESSION,
    REVOKE_APPROVAL_RULE,
    SET_SESSION_BUDGET,
}

enum class PendingApprovalDecision {
    ALLOW_ONCE,
    DENY,

    /**
     * S-policy: settle this ask as allowed-once and mint a same-kind
     * auto-grant rule in the same durable Host commit.
     */
    ALLOW_SAME_KIND,
}

internal data class PendingRemoteCommand(
    val authorityBinding: ByteArray,
    val pairedAtMs: Long,
    val commandId: String,
    val sessionId: String,
    val operation: PendingCommandOperation,
    val text: String?,
    val expectedActivityRevision: Long?,
    val approvalId: String?,
    val approvalRevision: String?,
    val approvalDecision: PendingApprovalDecision?,
    val agentPreset: String?,
    val workspaceId: String? = null,
    val newWorkspaceName: String? = null,
    val modelSelection: ModelSelectionProjection?,
    val childSessionId: String?,
    val forkAtSeq: Long?,
    /** Exact rule bound by REVOKE_APPROVAL_RULE (S-policy); null elsewhere. */
    val ruleId: String? = null,
    /** Exact ceiling bound by SET_SESSION_BUDGET (S-policy); null elsewhere. */
    val maxTotalTokens: Long? = null,
    val controlEpoch: String?,
    val controlToken: String?,
    val controlExpiresAtMs: Long?,
    val createdAtMs: Long,
    val phase: PendingCommandPhase,
    val requestFingerprint: ByteArray,
    /**
     * Committed image attachment ids (`sha256:<hex>`) the send carries
     * (S-blob); only SEND_INPUT may hold them, each already committed through
     * the blob channel before the command is reserved. Null ⟺ none.
     */
    val attachmentIds: List<String>? = null,
) {
    init {
        require(authorityBinding.size == AUTHORITY_BINDING_BYTES)
        require(pairedAtMs >= 0 && createdAtMs >= 0)
        require(commandId.matches(ASCII_ID))
        require(sessionId.isNotBlank() && sessionId.encodeToByteArray().size <= MAX_ID_BYTES)
        if (operation == PendingCommandOperation.SEND_INPUT) {
            require(attachmentIds == null || attachmentIds.size <= MAX_ATTACHMENT_IDS)
            attachmentIds?.forEach { require(it.matches(ATTACHMENT_ID)) { "Invalid attachment id" } }
        } else {
            require(attachmentIds == null) { "Only send_input carries attachments" }
        }
        if (operation != PendingCommandOperation.REVOKE_APPROVAL_RULE) {
            require(ruleId == null) { "Only revoke_approval_rule carries a rule id" }
        }
        if (operation != PendingCommandOperation.SET_SESSION_BUDGET) {
            require(maxTotalTokens == null) { "Only set_session_budget carries a ceiling" }
        }
        when (operation) {
            PendingCommandOperation.SEND_INPUT -> {
                require(!text.isNullOrBlank() && text.length <= MAX_TEXT_CHARS)
                require(expectedActivityRevision == null)
                require(approvalId == null && approvalRevision == null && approvalDecision == null)
                requireNoSessionAdmin()
                requireValidControl()
            }
            PendingCommandOperation.STOP -> {
                require(text == null)
                require(expectedActivityRevision != null && expectedActivityRevision > 0)
                require(approvalId == null && approvalRevision == null && approvalDecision == null)
                requireNoSessionAdmin()
                requireValidControl()
            }
            PendingCommandOperation.DECIDE_APPROVAL -> {
                require(text == null && expectedActivityRevision == null)
                require(!approvalId.isNullOrBlank() && approvalId.encodeToByteArray().size <= MAX_ID_BYTES)
                require(!approvalRevision.isNullOrBlank() && approvalRevision.encodeToByteArray().size <= MAX_ID_BYTES)
                require(approvalDecision != null)
                requireNoSessionAdmin()
                requireLeaseFree()
            }
            // S-mode-select: both admin commands are lease-free (no in-flight
            // effect exists to fence) and naturally idempotent Host-side.
            PendingCommandOperation.CREATE_SESSION -> {
                require(text == null && expectedActivityRevision == null)
                require(approvalId == null && approvalRevision == null && approvalDecision == null)
                require(agentPreset == null || agentPreset.matches(ASCII_IDENTIFIER))
                require(workspaceId == null || workspaceId.matches(ASCII_IDENTIFIER))
                require(newWorkspaceName == null || RemoteWorkspaceName.sanitize(newWorkspaceName) == newWorkspaceName)
                require(newWorkspaceName == null || workspaceId != null)
                require(modelSelection == null && childSessionId == null && forkAtSeq == null)
                requireLeaseFree()
            }
            PendingCommandOperation.SELECT_AGENT_PRESET -> {
                require(text == null && expectedActivityRevision == null)
                require(approvalId == null && approvalRevision == null && approvalDecision == null)
                require(agentPreset != null && agentPreset.matches(ASCII_IDENTIFIER))
                require(workspaceId == null && newWorkspaceName == null)
                require(modelSelection == null && childSessionId == null && forkAtSeq == null)
                requireLeaseFree()
            }
            // S-session-admin: a mid-session next-request mutation, so it
            // presents the session control fence exactly like send_input.
            PendingCommandOperation.SELECT_MODEL -> {
                require(text == null && expectedActivityRevision == null)
                require(approvalId == null && approvalRevision == null && approvalDecision == null)
                require(agentPreset == null && childSessionId == null && forkAtSeq == null)
                require(workspaceId == null && newWorkspaceName == null)
                requireValidModelSelection()
                requireValidControl()
            }
            // S-session-admin: lease-free — the source log is never mutated, and
            // the caller-preallocated child id makes a retry converge Host-side.
            PendingCommandOperation.FORK_SESSION -> {
                require(text == null && expectedActivityRevision == null)
                require(approvalId == null && approvalRevision == null && approvalDecision == null)
                require(agentPreset == null && modelSelection == null)
                require(workspaceId == null && newWorkspaceName == null)
                require(
                    !childSessionId.isNullOrBlank() &&
                        childSessionId.encodeToByteArray().size <= MAX_ID_BYTES,
                )
                require(forkAtSeq == null || forkAtSeq >= 0)
                requireLeaseFree()
            }
            // S-policy: both policy mutations are lease-free like fork/create —
            // they change durable policy facts, not the in-flight input stream.
            PendingCommandOperation.REVOKE_APPROVAL_RULE -> {
                require(text == null && expectedActivityRevision == null)
                require(approvalId == null && approvalRevision == null && approvalDecision == null)
                requireNoSessionAdmin()
                require(ruleId != null && ruleId.matches(RULE_ID))
                requireLeaseFree()
            }
            PendingCommandOperation.SET_SESSION_BUDGET -> {
                require(text == null && expectedActivityRevision == null)
                require(approvalId == null && approvalRevision == null && approvalDecision == null)
                requireNoSessionAdmin()
                require(maxTotalTokens != null && maxTotalTokens > 0)
                requireLeaseFree()
            }
        }
        require(requestFingerprint.size == FINGERPRINT_BYTES)
        require(
            MessageDigest.isEqual(
                requestFingerprint,
                fingerprint(
                    authorityBinding = authorityBinding,
                    pairedAtMs = pairedAtMs,
                    commandId = commandId,
                    sessionId = sessionId,
                    operation = operation,
                    text = text,
                    expectedActivityRevision = expectedActivityRevision,
                    approvalId = approvalId,
                    approvalRevision = approvalRevision,
                    approvalDecision = approvalDecision,
                    agentPreset = agentPreset,
                    workspaceId = workspaceId,
                    newWorkspaceName = newWorkspaceName,
                    modelSelection = modelSelection,
                    childSessionId = childSessionId,
                    forkAtSeq = forkAtSeq,
                    ruleId = ruleId,
                    maxTotalTokens = maxTotalTokens,
                    controlEpoch = controlEpoch,
                    attachmentIds = attachmentIds,
                ),
            ),
        ) { "Pending command fingerprint mismatch" }
    }

    fun copyForUse(): PendingRemoteCommand = copy(
        authorityBinding = authorityBinding.copyOf(),
        requestFingerprint = requestFingerprint.copyOf(),
    )

    fun withPhase(next: PendingCommandPhase): PendingRemoteCommand = copy(
        phase = next,
        authorityBinding = authorityBinding.copyOf(),
        requestFingerprint = requestFingerprint.copyOf(),
    )

    private fun requireValidControl() {
        require(controlEpoch?.toULongOrNull() != null)
        require(controlToken?.matches(CONTROL_TOKEN) == true)
        require(controlExpiresAtMs != null && controlExpiresAtMs >= 0)
    }

    private fun requireLeaseFree() {
        require(controlEpoch == null && controlToken == null && controlExpiresAtMs == null)
    }

    private fun requireNoSessionAdmin() {
        require(agentPreset == null && modelSelection == null)
        require(workspaceId == null && newWorkspaceName == null)
        require(childSessionId == null && forkAtSeq == null)
    }

    private fun requireValidModelSelection() {
        require(modelSelection != null)
        require(modelSelection.provider.matches(ASCII_IDENTIFIER))
        require(modelSelection.model.matches(MODEL_ID))
        require(modelSelection.reasoningEffort?.matches(ASCII_IDENTIFIER) ?: true)
    }

    companion object {
        const val AUTHORITY_BINDING_BYTES = 32
        const val FINGERPRINT_BYTES = 32
        const val MAX_TEXT_CHARS = OfflineWorkspaceCache.MAX_DRAFT_CHARS
        private const val MAX_ID_BYTES = 512
        private val ASCII_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        private val CONTROL_TOKEN = Regex("[A-Za-z0-9_-]{43}")

        /** Mirrors the Host transport bound: printable ASCII, 1..100 chars. */
        private val ASCII_IDENTIFIER = Regex("[\\x21-\\x7e]{1,100}")

        /** Mirrors the Host transport model-id bound: printable ASCII, 1..200 chars. */
        private val MODEL_ID = Regex("[\\x21-\\x7e]{1,200}")

        /** Mirrors the Host transport fence: committed content addresses only. */
        private val ATTACHMENT_ID = Regex("sha256:[0-9a-f]{64}")
        private const val MAX_ATTACHMENT_IDS = 16

        /** Mirrors the Host transport rule-id bound: canonical hex, 16..64 chars. */
        private val RULE_ID = Regex("[0-9a-f]{16,64}")

        fun create(
            authorityBinding: ByteArray,
            pairedAtMs: Long,
            commandId: String,
            sessionId: String,
            text: String,
            controlEpoch: String,
            controlToken: String,
            controlExpiresAtMs: Long,
            createdAtMs: Long,
            attachmentIds: List<String>? = null,
        ): PendingRemoteCommand = createSend(
            authorityBinding = authorityBinding,
            pairedAtMs = pairedAtMs,
            commandId = commandId,
            sessionId = sessionId,
            text = text,
            controlEpoch = controlEpoch,
            controlToken = controlToken,
            controlExpiresAtMs = controlExpiresAtMs,
            createdAtMs = createdAtMs,
            attachmentIds = attachmentIds,
        )

        fun createSend(
            authorityBinding: ByteArray,
            pairedAtMs: Long,
            commandId: String,
            sessionId: String,
            text: String,
            controlEpoch: String,
            controlToken: String,
            controlExpiresAtMs: Long,
            createdAtMs: Long,
            attachmentIds: List<String>? = null,
        ): PendingRemoteCommand = PendingRemoteCommand(
            authorityBinding = authorityBinding.copyOf(),
            pairedAtMs = pairedAtMs,
            commandId = commandId,
            sessionId = sessionId,
            operation = PendingCommandOperation.SEND_INPUT,
            text = text,
            expectedActivityRevision = null,
            approvalId = null,
            approvalRevision = null,
            approvalDecision = null,
            agentPreset = null,
            modelSelection = null,
            childSessionId = null,
            forkAtSeq = null,
            controlEpoch = controlEpoch,
            controlToken = controlToken,
            controlExpiresAtMs = controlExpiresAtMs,
            createdAtMs = createdAtMs,
            phase = PendingCommandPhase.PREPARED,
            attachmentIds = attachmentIds,
            requestFingerprint = fingerprint(
                authorityBinding = authorityBinding,
                pairedAtMs = pairedAtMs,
                commandId = commandId,
                sessionId = sessionId,
                operation = PendingCommandOperation.SEND_INPUT,
                text = text,
                expectedActivityRevision = null,
                approvalId = null,
                approvalRevision = null,
                approvalDecision = null,
                agentPreset = null,
                modelSelection = null,
                childSessionId = null,
                forkAtSeq = null,
                controlEpoch = controlEpoch,
                attachmentIds = attachmentIds,
            ),
        )

        fun createStop(
            authorityBinding: ByteArray,
            pairedAtMs: Long,
            commandId: String,
            sessionId: String,
            expectedActivityRevision: Long,
            controlEpoch: String,
            controlToken: String,
            controlExpiresAtMs: Long,
            createdAtMs: Long,
        ): PendingRemoteCommand = PendingRemoteCommand(
            authorityBinding = authorityBinding.copyOf(),
            pairedAtMs = pairedAtMs,
            commandId = commandId,
            sessionId = sessionId,
            operation = PendingCommandOperation.STOP,
            text = null,
            expectedActivityRevision = expectedActivityRevision,
            approvalId = null,
            approvalRevision = null,
            approvalDecision = null,
            agentPreset = null,
            modelSelection = null,
            childSessionId = null,
            forkAtSeq = null,
            controlEpoch = controlEpoch,
            controlToken = controlToken,
            controlExpiresAtMs = controlExpiresAtMs,
            createdAtMs = createdAtMs,
            phase = PendingCommandPhase.PREPARED,
            requestFingerprint = fingerprint(
                authorityBinding = authorityBinding,
                pairedAtMs = pairedAtMs,
                commandId = commandId,
                sessionId = sessionId,
                operation = PendingCommandOperation.STOP,
                text = null,
                expectedActivityRevision = expectedActivityRevision,
                approvalId = null,
                approvalRevision = null,
                approvalDecision = null,
                agentPreset = null,
                modelSelection = null,
                childSessionId = null,
                forkAtSeq = null,
                controlEpoch = controlEpoch,
            ),
        )

        fun createApprovalDecision(
            authorityBinding: ByteArray,
            pairedAtMs: Long,
            commandId: String,
            sessionId: String,
            approvalId: String,
            approvalRevision: String,
            approvalDecision: PendingApprovalDecision,
            createdAtMs: Long,
        ): PendingRemoteCommand = PendingRemoteCommand(
            authorityBinding = authorityBinding.copyOf(),
            pairedAtMs = pairedAtMs,
            commandId = commandId,
            sessionId = sessionId,
            operation = PendingCommandOperation.DECIDE_APPROVAL,
            text = null,
            expectedActivityRevision = null,
            approvalId = approvalId,
            approvalRevision = approvalRevision,
            approvalDecision = approvalDecision,
            agentPreset = null,
            modelSelection = null,
            childSessionId = null,
            forkAtSeq = null,
            controlEpoch = null,
            controlToken = null,
            controlExpiresAtMs = null,
            createdAtMs = createdAtMs,
            phase = PendingCommandPhase.PREPARED,
            requestFingerprint = fingerprint(
                authorityBinding = authorityBinding,
                pairedAtMs = pairedAtMs,
                commandId = commandId,
                sessionId = sessionId,
                operation = PendingCommandOperation.DECIDE_APPROVAL,
                text = null,
                expectedActivityRevision = null,
                approvalId = approvalId,
                approvalRevision = approvalRevision,
                approvalDecision = approvalDecision,
                agentPreset = null,
                modelSelection = null,
                childSessionId = null,
                forkAtSeq = null,
                controlEpoch = null,
            ),
        )

        /**
         * S-mode-select: preallocate the Session id client-side so a retry with
         * the same command id replays to the same Session instead of creating a
         * second one. Lease-free — there is no in-flight effect to fence.
         */
        fun createSession(
            authorityBinding: ByteArray,
            pairedAtMs: Long,
            commandId: String,
            sessionId: String,
            agentPreset: String?,
            createdAtMs: Long,
            workspaceId: String? = null,
            newWorkspaceName: String? = null,
        ): PendingRemoteCommand = PendingRemoteCommand(
            authorityBinding = authorityBinding.copyOf(),
            pairedAtMs = pairedAtMs,
            commandId = commandId,
            sessionId = sessionId,
            operation = PendingCommandOperation.CREATE_SESSION,
            text = null,
            expectedActivityRevision = null,
            approvalId = null,
            approvalRevision = null,
            approvalDecision = null,
            agentPreset = agentPreset,
            workspaceId = workspaceId,
            newWorkspaceName = newWorkspaceName,
            modelSelection = null,
            childSessionId = null,
            forkAtSeq = null,
            controlEpoch = null,
            controlToken = null,
            controlExpiresAtMs = null,
            createdAtMs = createdAtMs,
            phase = PendingCommandPhase.PREPARED,
            requestFingerprint = fingerprint(
                authorityBinding = authorityBinding,
                pairedAtMs = pairedAtMs,
                commandId = commandId,
                sessionId = sessionId,
                operation = PendingCommandOperation.CREATE_SESSION,
                text = null,
                expectedActivityRevision = null,
                approvalId = null,
                approvalRevision = null,
                approvalDecision = null,
                agentPreset = agentPreset,
                workspaceId = workspaceId,
                newWorkspaceName = newWorkspaceName,
                modelSelection = null,
                childSessionId = null,
                forkAtSeq = null,
                controlEpoch = null,
            ),
        )

        /** S-mode-select: set-valued selection against one exact blank Session. */
        fun createSelectAgentPreset(
            authorityBinding: ByteArray,
            pairedAtMs: Long,
            commandId: String,
            sessionId: String,
            agentPreset: String,
            createdAtMs: Long,
        ): PendingRemoteCommand = PendingRemoteCommand(
            authorityBinding = authorityBinding.copyOf(),
            pairedAtMs = pairedAtMs,
            commandId = commandId,
            sessionId = sessionId,
            operation = PendingCommandOperation.SELECT_AGENT_PRESET,
            text = null,
            expectedActivityRevision = null,
            approvalId = null,
            approvalRevision = null,
            approvalDecision = null,
            agentPreset = agentPreset,
            modelSelection = null,
            childSessionId = null,
            forkAtSeq = null,
            controlEpoch = null,
            controlToken = null,
            controlExpiresAtMs = null,
            createdAtMs = createdAtMs,
            phase = PendingCommandPhase.PREPARED,
            requestFingerprint = fingerprint(
                authorityBinding = authorityBinding,
                pairedAtMs = pairedAtMs,
                commandId = commandId,
                sessionId = sessionId,
                operation = PendingCommandOperation.SELECT_AGENT_PRESET,
                text = null,
                expectedActivityRevision = null,
                approvalId = null,
                approvalRevision = null,
                approvalDecision = null,
                agentPreset = agentPreset,
                modelSelection = null,
                childSessionId = null,
                forkAtSeq = null,
                controlEpoch = null,
            ),
        )

        /**
         * S-session-admin: exact provider/model/effort triple for the session's
         * subsequent requests. Fenced like send_input — the running step keeps
         * its assembled selection.
         */
        fun createSelectModel(
            authorityBinding: ByteArray,
            pairedAtMs: Long,
            commandId: String,
            sessionId: String,
            modelSelection: ModelSelectionProjection,
            controlEpoch: String,
            controlToken: String,
            controlExpiresAtMs: Long,
            createdAtMs: Long,
        ): PendingRemoteCommand = PendingRemoteCommand(
            authorityBinding = authorityBinding.copyOf(),
            pairedAtMs = pairedAtMs,
            commandId = commandId,
            sessionId = sessionId,
            operation = PendingCommandOperation.SELECT_MODEL,
            text = null,
            expectedActivityRevision = null,
            approvalId = null,
            approvalRevision = null,
            approvalDecision = null,
            agentPreset = null,
            modelSelection = modelSelection,
            childSessionId = null,
            forkAtSeq = null,
            controlEpoch = controlEpoch,
            controlToken = controlToken,
            controlExpiresAtMs = controlExpiresAtMs,
            createdAtMs = createdAtMs,
            phase = PendingCommandPhase.PREPARED,
            requestFingerprint = fingerprint(
                authorityBinding = authorityBinding,
                pairedAtMs = pairedAtMs,
                commandId = commandId,
                sessionId = sessionId,
                operation = PendingCommandOperation.SELECT_MODEL,
                text = null,
                expectedActivityRevision = null,
                approvalId = null,
                approvalRevision = null,
                approvalDecision = null,
                agentPreset = null,
                modelSelection = modelSelection,
                childSessionId = null,
                forkAtSeq = null,
                controlEpoch = controlEpoch,
            ),
        )

        /**
         * S-session-admin: fork the source session at a completed-turn boundary
         * into the caller-preallocated child id, so a retry with the same
         * command id converges to the same child instead of forking a twin.
         * Lease-free — the source log is never mutated.
         */
        fun createForkSession(
            authorityBinding: ByteArray,
            pairedAtMs: Long,
            commandId: String,
            sessionId: String,
            childSessionId: String,
            forkAtSeq: Long?,
            createdAtMs: Long,
        ): PendingRemoteCommand = PendingRemoteCommand(
            authorityBinding = authorityBinding.copyOf(),
            pairedAtMs = pairedAtMs,
            commandId = commandId,
            sessionId = sessionId,
            operation = PendingCommandOperation.FORK_SESSION,
            text = null,
            expectedActivityRevision = null,
            approvalId = null,
            approvalRevision = null,
            approvalDecision = null,
            agentPreset = null,
            modelSelection = null,
            childSessionId = childSessionId,
            forkAtSeq = forkAtSeq,
            controlEpoch = null,
            controlToken = null,
            controlExpiresAtMs = null,
            createdAtMs = createdAtMs,
            phase = PendingCommandPhase.PREPARED,
            requestFingerprint = fingerprint(
                authorityBinding = authorityBinding,
                pairedAtMs = pairedAtMs,
                commandId = commandId,
                sessionId = sessionId,
                operation = PendingCommandOperation.FORK_SESSION,
                text = null,
                expectedActivityRevision = null,
                approvalId = null,
                approvalRevision = null,
                approvalDecision = null,
                agentPreset = null,
                modelSelection = null,
                childSessionId = childSessionId,
                forkAtSeq = forkAtSeq,
                controlEpoch = null,
            ),
        )

        /**
         * S-policy: revoke one exact auto-grant rule. Lease-free — a durable
         * policy fact changes, not the in-flight input stream — and naturally
         * idempotent Host-side (revoking an already-revoked rule replays).
         */
        fun createRevokeRule(
            authorityBinding: ByteArray,
            pairedAtMs: Long,
            commandId: String,
            sessionId: String,
            ruleId: String,
            createdAtMs: Long,
        ): PendingRemoteCommand = PendingRemoteCommand(
            authorityBinding = authorityBinding.copyOf(),
            pairedAtMs = pairedAtMs,
            commandId = commandId,
            sessionId = sessionId,
            operation = PendingCommandOperation.REVOKE_APPROVAL_RULE,
            text = null,
            expectedActivityRevision = null,
            approvalId = null,
            approvalRevision = null,
            approvalDecision = null,
            agentPreset = null,
            modelSelection = null,
            childSessionId = null,
            forkAtSeq = null,
            ruleId = ruleId,
            controlEpoch = null,
            controlToken = null,
            controlExpiresAtMs = null,
            createdAtMs = createdAtMs,
            phase = PendingCommandPhase.PREPARED,
            requestFingerprint = fingerprint(
                authorityBinding = authorityBinding,
                pairedAtMs = pairedAtMs,
                commandId = commandId,
                sessionId = sessionId,
                operation = PendingCommandOperation.REVOKE_APPROVAL_RULE,
                text = null,
                expectedActivityRevision = null,
                approvalId = null,
                approvalRevision = null,
                approvalDecision = null,
                agentPreset = null,
                modelSelection = null,
                childSessionId = null,
                forkAtSeq = null,
                controlEpoch = null,
                ruleId = ruleId,
            ),
        )

        /**
         * S-policy: set or replace the session's token budget with one exact
         * ceiling. Lease-free and set-valued — a retry converges Host-side.
         */
        fun createSetSessionBudget(
            authorityBinding: ByteArray,
            pairedAtMs: Long,
            commandId: String,
            sessionId: String,
            maxTotalTokens: Long,
            createdAtMs: Long,
        ): PendingRemoteCommand = PendingRemoteCommand(
            authorityBinding = authorityBinding.copyOf(),
            pairedAtMs = pairedAtMs,
            commandId = commandId,
            sessionId = sessionId,
            operation = PendingCommandOperation.SET_SESSION_BUDGET,
            text = null,
            expectedActivityRevision = null,
            approvalId = null,
            approvalRevision = null,
            approvalDecision = null,
            agentPreset = null,
            modelSelection = null,
            childSessionId = null,
            forkAtSeq = null,
            maxTotalTokens = maxTotalTokens,
            controlEpoch = null,
            controlToken = null,
            controlExpiresAtMs = null,
            createdAtMs = createdAtMs,
            phase = PendingCommandPhase.PREPARED,
            requestFingerprint = fingerprint(
                authorityBinding = authorityBinding,
                pairedAtMs = pairedAtMs,
                commandId = commandId,
                sessionId = sessionId,
                operation = PendingCommandOperation.SET_SESSION_BUDGET,
                text = null,
                expectedActivityRevision = null,
                approvalId = null,
                approvalRevision = null,
                approvalDecision = null,
                agentPreset = null,
                modelSelection = null,
                childSessionId = null,
                forkAtSeq = null,
                controlEpoch = null,
                maxTotalTokens = maxTotalTokens,
            ),
        )

        private fun fingerprint(
            authorityBinding: ByteArray,
            pairedAtMs: Long,
            commandId: String,
            sessionId: String,
            operation: PendingCommandOperation,
            text: String?,
            expectedActivityRevision: Long?,
            approvalId: String?,
            approvalRevision: String?,
            approvalDecision: PendingApprovalDecision?,
            agentPreset: String?,
            workspaceId: String? = null,
            newWorkspaceName: String? = null,
            modelSelection: ModelSelectionProjection?,
            childSessionId: String?,
            forkAtSeq: Long?,
            controlEpoch: String?,
            attachmentIds: List<String>? = null,
            ruleId: String? = null,
            maxTotalTokens: Long? = null,
        ): ByteArray {
            val canonical = ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(FORMAT_VERSION)
                    data.write(authorityBinding)
                    data.writeLong(pairedAtMs)
                    data.writeBoundedString(commandId)
                    data.writeBoundedString(sessionId)
                    data.writeBoundedString(operation.name)
                    when (operation) {
                        PendingCommandOperation.SEND_INPUT -> {
                            data.writeBoundedString(requireNotNull(text))
                            data.writeInt(attachmentIds?.size ?: 0)
                            attachmentIds?.forEach(data::writeBoundedString)
                        }
                        PendingCommandOperation.STOP -> data.writeLong(requireNotNull(expectedActivityRevision))
                        PendingCommandOperation.DECIDE_APPROVAL -> {
                            data.writeBoundedString(requireNotNull(approvalId))
                            data.writeBoundedString(requireNotNull(approvalRevision))
                            data.writeBoundedString(requireNotNull(approvalDecision).name)
                        }
                        PendingCommandOperation.CREATE_SESSION -> {
                            data.writeBoolean(agentPreset != null)
                            agentPreset?.let(data::writeBoundedString)
                            data.writeBoolean(workspaceId != null)
                            workspaceId?.let(data::writeBoundedString)
                            data.writeBoolean(newWorkspaceName != null)
                            newWorkspaceName?.let(data::writeBoundedString)
                        }
                        PendingCommandOperation.SELECT_AGENT_PRESET ->
                            data.writeBoundedString(requireNotNull(agentPreset))
                        PendingCommandOperation.SELECT_MODEL -> {
                            val selection = requireNotNull(modelSelection)
                            data.writeBoundedString(selection.provider)
                            data.writeBoundedString(selection.model)
                            data.writeBoolean(selection.reasoningEffort != null)
                            selection.reasoningEffort?.let(data::writeBoundedString)
                        }
                        PendingCommandOperation.FORK_SESSION -> {
                            data.writeBoundedString(requireNotNull(childSessionId))
                            data.writeBoolean(forkAtSeq != null)
                            forkAtSeq?.let(data::writeLong)
                        }
                        PendingCommandOperation.REVOKE_APPROVAL_RULE ->
                            data.writeBoundedString(requireNotNull(ruleId))
                        PendingCommandOperation.SET_SESSION_BUDGET ->
                            data.writeLong(requireNotNull(maxTotalTokens))
                    }
                    data.writeBoolean(controlEpoch != null)
                    controlEpoch?.let(data::writeBoundedString)
                }
                output.toByteArray()
            }
            return try {
                MessageDigest.getInstance("SHA-256").digest(canonical)
            } finally {
                canonical.fill(0)
            }
        }

        // v6: send_input may carry committed S-blob attachment ids.
        // v7: S-policy — revoke_approval_rule / set_session_budget operations
        //     and the ALLOW_SAME_KIND approval decision.
        // v8: create_session may bind workspaceId / newWorkspaceName.
        internal const val FORMAT_VERSION = 8
    }
}

internal data class PendingCommandLoad(
    val command: PendingRemoteCommand?,
    val warning: String? = null,
    val blocked: Boolean = false,
)

internal object PendingCommandCodec {
    fun encode(command: PendingRemoteCommand): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(PendingRemoteCommand.FORMAT_VERSION)
            data.write(command.authorityBinding)
            data.writeLong(command.pairedAtMs)
            data.writeBoundedString(command.commandId)
            data.writeBoundedString(command.sessionId)
            data.writeBoundedString(command.operation.name)
            when (command.operation) {
                PendingCommandOperation.SEND_INPUT -> {
                    data.writeBoundedString(requireNotNull(command.text))
                    val ids = command.attachmentIds.orEmpty()
                    data.writeInt(ids.size)
                    ids.forEach(data::writeBoundedString)
                }
                PendingCommandOperation.STOP -> data.writeLong(requireNotNull(command.expectedActivityRevision))
                PendingCommandOperation.DECIDE_APPROVAL -> {
                    data.writeBoundedString(requireNotNull(command.approvalId))
                    data.writeBoundedString(requireNotNull(command.approvalRevision))
                    data.writeBoundedString(requireNotNull(command.approvalDecision).name)
                }
                PendingCommandOperation.CREATE_SESSION -> {
                    data.writeBoolean(command.agentPreset != null)
                    command.agentPreset?.let(data::writeBoundedString)
                    data.writeBoolean(command.workspaceId != null)
                    command.workspaceId?.let(data::writeBoundedString)
                    data.writeBoolean(command.newWorkspaceName != null)
                    command.newWorkspaceName?.let(data::writeBoundedString)
                }
                PendingCommandOperation.SELECT_AGENT_PRESET ->
                    data.writeBoundedString(requireNotNull(command.agentPreset))
                PendingCommandOperation.SELECT_MODEL -> {
                    val selection = requireNotNull(command.modelSelection)
                    data.writeBoundedString(selection.provider)
                    data.writeBoundedString(selection.model)
                    data.writeBoolean(selection.reasoningEffort != null)
                    selection.reasoningEffort?.let(data::writeBoundedString)
                }
                PendingCommandOperation.FORK_SESSION -> {
                    data.writeBoundedString(requireNotNull(command.childSessionId))
                    data.writeBoolean(command.forkAtSeq != null)
                    command.forkAtSeq?.let(data::writeLong)
                }
                PendingCommandOperation.REVOKE_APPROVAL_RULE ->
                    data.writeBoundedString(requireNotNull(command.ruleId))
                PendingCommandOperation.SET_SESSION_BUDGET ->
                    data.writeLong(requireNotNull(command.maxTotalTokens))
            }
            data.writeBoolean(command.controlEpoch != null)
            command.controlEpoch?.let(data::writeBoundedString)
            command.controlToken?.let(data::writeBoundedString)
            command.controlExpiresAtMs?.let(data::writeLong)
            data.writeLong(command.createdAtMs)
            data.writeBoundedString(command.phase.name)
            data.write(command.requestFingerprint)
        }
        output.toByteArray()
    }

    fun decode(bytes: ByteArray): PendingRemoteCommand = DataInputStream(ByteArrayInputStream(bytes)).use { data ->
        val version = data.readInt()
        require(version in 1..PendingRemoteCommand.FORMAT_VERSION) { "Unsupported pending command version" }
        val binding = ByteArray(PendingRemoteCommand.AUTHORITY_BINDING_BYTES).also(data::readFully)
        val pairedAtMs = data.readLong()
        val commandId = data.readBoundedString()
        val sessionId = data.readBoundedString()
        val operation: PendingCommandOperation
        val text: String?
        val expectedActivityRevision: Long?
        val approvalId: String?
        val approvalRevision: String?
        val approvalDecision: PendingApprovalDecision?
        val agentPreset: String?
        val modelSelection: ModelSelectionProjection?
        val childSessionId: String?
        val forkAtSeq: Long?
        val attachmentIds: List<String>?
        var ruleId: String? = null
        var maxTotalTokens: Long? = null
        var workspaceId: String? = null
        var newWorkspaceName: String? = null
        if (version == 1) {
            operation = PendingCommandOperation.SEND_INPUT
            text = data.readBoundedString(PendingRemoteCommand.MAX_TEXT_CHARS)
            expectedActivityRevision = null
            approvalId = null
            approvalRevision = null
            approvalDecision = null
            agentPreset = null
            modelSelection = null
            childSessionId = null
            forkAtSeq = null
            attachmentIds = null
        } else {
            operation = PendingCommandOperation.valueOf(data.readBoundedString())
            when (operation) {
                PendingCommandOperation.SEND_INPUT -> {
                    text = data.readBoundedString(PendingRemoteCommand.MAX_TEXT_CHARS)
                    expectedActivityRevision = null
                    approvalId = null
                    approvalRevision = null
                    approvalDecision = null
                    agentPreset = null
                    modelSelection = null
                    childSessionId = null
                    forkAtSeq = null
                    attachmentIds = if (version >= 6) {
                        List(data.readInt().also { require(it in 0..16) }) { data.readBoundedString(128) }
                            .takeIf { it.isNotEmpty() }
                    } else {
                        null
                    }
                }
                PendingCommandOperation.STOP -> {
                    text = null
                    expectedActivityRevision = data.readLong()
                    approvalId = null
                    approvalRevision = null
                    approvalDecision = null
                    agentPreset = null
                    modelSelection = null
                    childSessionId = null
                    forkAtSeq = null
                    attachmentIds = null
                }
                PendingCommandOperation.DECIDE_APPROVAL -> {
                    require(version >= 3) { "Approval command requires pending command version 3" }
                    text = null
                    expectedActivityRevision = null
                    approvalId = data.readBoundedString()
                    approvalRevision = data.readBoundedString()
                    approvalDecision = PendingApprovalDecision.valueOf(data.readBoundedString())
                    agentPreset = null
                    modelSelection = null
                    childSessionId = null
                    forkAtSeq = null
                    attachmentIds = null
                }
                PendingCommandOperation.CREATE_SESSION -> {
                    require(version >= 4) { "Session admin commands require pending command version 4" }
                    text = null
                    expectedActivityRevision = null
                    approvalId = null
                    approvalRevision = null
                    approvalDecision = null
                    agentPreset = if (data.readBoolean()) data.readBoundedString() else null
                    if (version >= 8) {
                        workspaceId = if (data.readBoolean()) data.readBoundedString() else null
                        newWorkspaceName = if (data.readBoolean()) data.readBoundedString() else null
                    }
                    modelSelection = null
                    childSessionId = null
                    forkAtSeq = null
                    attachmentIds = null
                }
                PendingCommandOperation.SELECT_AGENT_PRESET -> {
                    require(version >= 4) { "Session admin commands require pending command version 4" }
                    text = null
                    expectedActivityRevision = null
                    approvalId = null
                    approvalRevision = null
                    approvalDecision = null
                    agentPreset = data.readBoundedString()
                    modelSelection = null
                    childSessionId = null
                    forkAtSeq = null
                    attachmentIds = null
                }
                PendingCommandOperation.SELECT_MODEL -> {
                    require(version >= 5) { "Model selection requires pending command version 5" }
                    text = null
                    expectedActivityRevision = null
                    approvalId = null
                    approvalRevision = null
                    approvalDecision = null
                    agentPreset = null
                    modelSelection = ModelSelectionProjection(
                        provider = data.readBoundedString(),
                        model = data.readBoundedString(),
                        reasoningEffort = if (data.readBoolean()) data.readBoundedString() else null,
                    )
                    childSessionId = null
                    forkAtSeq = null
                    attachmentIds = null
                }
                PendingCommandOperation.FORK_SESSION -> {
                    require(version >= 5) { "Session fork requires pending command version 5" }
                    text = null
                    expectedActivityRevision = null
                    approvalId = null
                    approvalRevision = null
                    approvalDecision = null
                    agentPreset = null
                    modelSelection = null
                    childSessionId = data.readBoundedString()
                    forkAtSeq = if (data.readBoolean()) data.readLong() else null
                    attachmentIds = null
                }
                PendingCommandOperation.REVOKE_APPROVAL_RULE -> {
                    require(version >= 7) { "Policy commands require pending command version 7" }
                    text = null
                    expectedActivityRevision = null
                    approvalId = null
                    approvalRevision = null
                    approvalDecision = null
                    agentPreset = null
                    modelSelection = null
                    childSessionId = null
                    forkAtSeq = null
                    attachmentIds = null
                    ruleId = data.readBoundedString()
                }
                PendingCommandOperation.SET_SESSION_BUDGET -> {
                    require(version >= 7) { "Policy commands require pending command version 7" }
                    text = null
                    expectedActivityRevision = null
                    approvalId = null
                    approvalRevision = null
                    approvalDecision = null
                    agentPreset = null
                    modelSelection = null
                    childSessionId = null
                    forkAtSeq = null
                    attachmentIds = null
                    maxTotalTokens = data.readLong()
                }
            }
        }
        val hasControl = if (version >= 3) data.readBoolean() else true
        val controlEpoch = if (hasControl) data.readBoundedString() else null
        val controlToken = if (hasControl) data.readBoundedString() else null
        val expiresAtMs = if (hasControl) data.readLong() else null
        val createdAtMs = data.readLong()
        val phase = PendingCommandPhase.valueOf(data.readBoundedString())
        val storedFingerprint = ByteArray(PendingRemoteCommand.FINGERPRINT_BYTES).also(data::readFully)
        require(data.available() == 0) { "Trailing pending command data" }
        try {
            val decoded = if (version < PendingRemoteCommand.FORMAT_VERSION) {
                // Re-derive the integrity fingerprint at the current format. Only
                // operations whose retry converges Host-side (same preallocated
                // id / same set-valued selection) may migrate; an approval
                // decision never crosses a format change — it fails closed and
                // the user re-decides against a fresh revision.
                val migrated = when (operation) {
                    PendingCommandOperation.SEND_INPUT -> PendingRemoteCommand.createSend(
                        binding, pairedAtMs, commandId, sessionId, requireNotNull(text), requireNotNull(controlEpoch),
                        requireNotNull(controlToken), requireNotNull(expiresAtMs), createdAtMs, attachmentIds,
                    )
                    PendingCommandOperation.STOP -> PendingRemoteCommand.createStop(
                        binding, pairedAtMs, commandId, sessionId, requireNotNull(expectedActivityRevision),
                        requireNotNull(controlEpoch), requireNotNull(controlToken), requireNotNull(expiresAtMs), createdAtMs,
                    )
                    PendingCommandOperation.CREATE_SESSION -> PendingRemoteCommand.createSession(
                        binding, pairedAtMs, commandId, sessionId, agentPreset, createdAtMs,
                        workspaceId, newWorkspaceName,
                    )
                    PendingCommandOperation.SELECT_AGENT_PRESET -> PendingRemoteCommand.createSelectAgentPreset(
                        binding, pairedAtMs, commandId, sessionId, requireNotNull(agentPreset), createdAtMs,
                    )
                    // S-session-admin migrations converge Host-side; model
                    // selection re-fences anyway, so only these two migrate.
                    PendingCommandOperation.SELECT_MODEL -> PendingRemoteCommand.createSelectModel(
                        binding, pairedAtMs, commandId, sessionId, requireNotNull(modelSelection),
                        requireNotNull(controlEpoch), requireNotNull(controlToken), requireNotNull(expiresAtMs), createdAtMs,
                    )
                    PendingCommandOperation.FORK_SESSION -> PendingRemoteCommand.createForkSession(
                        binding, pairedAtMs, commandId, sessionId, requireNotNull(childSessionId), forkAtSeq, createdAtMs,
                    )
                    PendingCommandOperation.DECIDE_APPROVAL -> error("approval command cannot use a legacy format")
                    PendingCommandOperation.REVOKE_APPROVAL_RULE,
                    PendingCommandOperation.SET_SESSION_BUDGET,
                    -> error("policy command cannot use a legacy format")
                }.withPhase(phase)
                binding.fill(0)
                storedFingerprint.fill(0)
                migrated
            } else {
                PendingRemoteCommand(
                    authorityBinding = binding,
                    pairedAtMs = pairedAtMs,
                    commandId = commandId,
                    sessionId = sessionId,
                    operation = operation,
                    text = text,
                    expectedActivityRevision = expectedActivityRevision,
                    approvalId = approvalId,
                    approvalRevision = approvalRevision,
                    approvalDecision = approvalDecision,
                    agentPreset = agentPreset,
                    workspaceId = workspaceId,
                    newWorkspaceName = newWorkspaceName,
                    modelSelection = modelSelection,
                    childSessionId = childSessionId,
                    forkAtSeq = forkAtSeq,
                    ruleId = ruleId,
                    maxTotalTokens = maxTotalTokens,
                    controlEpoch = controlEpoch,
                    controlToken = controlToken,
                    controlExpiresAtMs = expiresAtMs,
                    createdAtMs = createdAtMs,
                    phase = phase,
                    requestFingerprint = storedFingerprint,
                    attachmentIds = attachmentIds,
                )
            }
            decoded
        } catch (error: Exception) {
            binding.fill(0)
            storedFingerprint.fill(0)
            throw error
        }
    }
}

/** Keystore-authenticated command owner state, isolated from the projection cache. */
internal class PendingCommandStore(context: Context, hostScope: String? = null) {
    private val commandFile = AtomicFile(
        // S-multi-host: each Host owns its pending-command file; the legacy flat
        // path stays the single-Host (null scope) location so an in-flight
        // command survives the upgrade.
        if (hostScope == null) {
            File(context.noBackupFilesDir, "security/pending-command.bin").also {
                it.parentFile?.mkdirs()
            }
        } else {
            require(hostScope.matches(HOST_SCOPE_PATTERN)) { "Invalid pending command Host scope" }
            File(context.noBackupFilesDir, "security/hosts/pending-command-$hostScope.bin").also {
                it.parentFile?.mkdirs()
            }
        },
    )

    @Synchronized
    fun load(expectedAuthorityBinding: ByteArray, expectedPairedAtMs: Long): PendingCommandLoad {
        require(expectedAuthorityBinding.size == PendingRemoteCommand.AUTHORITY_BINDING_BYTES)
        if (!commandFile.baseFile.exists()) return PendingCommandLoad(null)
        var plaintext: ByteArray? = null
        return try {
            val (iv, ciphertext) = readEnvelope()
            try {
                val cipher = Cipher.getInstance(CIPHER)
                cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(AAD)
                plaintext = cipher.doFinal(ciphertext)
                val command = PendingCommandCodec.decode(plaintext)
                if (
                    !MessageDigest.isEqual(command.authorityBinding, expectedAuthorityBinding) ||
                    command.pairedAtMs != expectedPairedAtMs
                ) {
                    command.authorityBinding.fill(0)
                    command.requestFingerprint.fill(0)
                    commandFile.delete()
                    PendingCommandLoad(
                        null,
                        "Discarded a pending command from a different Host authorization ceremony.",
                    )
                } else {
                    PendingCommandLoad(command)
                }
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
        } catch (_: Exception) {
            PendingCommandLoad(
                command = null,
                warning = "A protected pending command is unreadable; sending is blocked until explicit re-pairing.",
                blocked = true,
            )
        } finally {
            plaintext?.fill(0)
        }
    }

    @Synchronized
    fun save(command: PendingRemoteCommand) {
        val plaintext = PendingCommandCodec.encode(command)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES)
        var ciphertext: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(AAD)
            ciphertext = cipher.doFinal(plaintext)
            writeEnvelope(cipher.iv, ciphertext)
        } finally {
            plaintext.fill(0)
            ciphertext?.fill(0)
        }
    }

    @Synchronized
    fun clear() {
        commandFile.delete()
    }

    internal fun encryptedFileForTest(): File = commandFile.baseFile

    private fun writeEnvelope(iv: ByteArray, ciphertext: ByteArray) {
        val envelope = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.write(MAGIC)
                data.writeInt(ENVELOPE_VERSION)
                data.writeInt(iv.size)
                data.write(iv)
                data.writeInt(ciphertext.size)
                data.write(ciphertext)
            }
            output.toByteArray()
        }
        var stream: java.io.FileOutputStream? = commandFile.startWrite()
        try {
            stream!!.write(envelope)
            commandFile.finishWrite(stream)
            stream = null
        } finally {
            stream?.let(commandFile::failWrite)
            envelope.fill(0)
        }
    }

    private fun readEnvelope(): Pair<ByteArray, ByteArray> {
        val bytes = commandFile.readFully()
        require(bytes.size <= MAX_ENVELOPE_BYTES)
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                val magic = ByteArray(MAGIC.size).also(data::readFully)
                require(magic.contentEquals(MAGIC) && data.readInt() == ENVELOPE_VERSION)
                val ivSize = data.readInt()
                require(ivSize in 12..16)
                val iv = ByteArray(ivSize).also(data::readFully)
                val ciphertextSize = data.readInt()
                require(ciphertextSize in 17..MAX_ENVELOPE_BYTES && ciphertextSize <= data.available())
                val ciphertext = ByteArray(ciphertextSize).also(data::readFully)
                require(data.available() == 0)
                return iv to ciphertext
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun existingKey(): SecretKey =
        SealedWrappingKeys.existing(KEY_ALIAS)
            ?: error("Pending command wrapping key is missing")

    private fun getOrCreateKey(): SecretKey = SealedWrappingKeys.getOrCreate(KEY_ALIAS)

    private companion object {
        const val KEY_ALIAS = "dsh_remote_pending_command_wrap_v1"
        const val CIPHER = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val ENVELOPE_VERSION = 1
        const val MAX_PLAINTEXT_BYTES = 32 * 1024
        const val MAX_ENVELOPE_BYTES = MAX_PLAINTEXT_BYTES + 1_024
        val MAGIC = "DSHRCMD1".encodeToByteArray()
        val AAD = "dsh-remote/pending-command/v1".encodeToByteArray()
        val HOST_SCOPE_PATTERN = Regex("[0-9a-f]{64}")
    }
}

private fun DataOutputStream.writeBoundedString(value: String) {
    val bytes = value.encodeToByteArray()
    require(bytes.size <= 16 * 1024)
    writeInt(bytes.size)
    write(bytes)
    bytes.fill(0)
}

private fun DataInputStream.readBoundedString(maxChars: Int = 16 * 1024): String {
    val size = readInt()
    require(size in 0..(16 * 1024) && size <= available())
    val bytes = ByteArray(size).also(::readFully)
    return try {
        bytes.decodeToString(throwOnInvalidSequence = true).also { require(it.length <= maxChars) }
    } finally {
        bytes.fill(0)
    }
}
