package dev.dshremote.gate0c.transport

import dev.dshremote.protocol.v1alpha.AgentPresetEntry as ProtoAgentPresetEntry
import dev.dshremote.protocol.v1alpha.ApprovalRule as ProtoApprovalRule
import dev.dshremote.protocol.v1alpha.ArtifactSummary as ProtoArtifactSummary
import dev.dshremote.protocol.v1alpha.AssistantMessage
import dev.dshremote.protocol.v1alpha.MessageSource as ProtoMessageSource
import dev.dshremote.protocol.v1alpha.ModelCatalogFailure as ProtoModelCatalogFailure
import dev.dshremote.protocol.v1alpha.ModelChanged as ProtoModelChanged
import dev.dshremote.protocol.v1alpha.ModelProviderGroup as ProtoModelProviderGroup
import dev.dshremote.protocol.v1alpha.ModelSelection as ProtoModelSelection
import dev.dshremote.protocol.v1alpha.ProjectedImageAttachment as ProtoImageAttachment
import dev.dshremote.protocol.v1alpha.SessionBudget as ProtoSessionBudget
import dev.dshremote.protocol.v1alpha.SessionStatusChanged
import dev.dshremote.protocol.v1alpha.SessionSummary as ProtoSessionSummary
import dev.dshremote.protocol.v1alpha.SessionUsage as ProtoSessionUsage
import dev.dshremote.protocol.v1alpha.SubagentView as ProtoSubagentView
import dev.dshremote.protocol.v1alpha.ToolPresentation
import dev.dshremote.protocol.v1alpha.UsageChanged as ProtoUsageChanged

enum class ConnectionPhase(val label: String) {
    DISCONNECTED("Disconnected"),
    UNPAIRED("Pair a Host"),
    PAIRING("Pairing"),
    AWAITING_HOST_CONFIRMATION("Confirm on Host"),
    RECONCILING_PAIRING("Confirming access"),
    CONNECTING("Connecting"),
    HELLO("Hello accepted"),
    SYNCHRONIZING("Synchronizing"),
    READY("Ready"),
    GAP_DETECTED("Sequence gap"),
    SNAPSHOT_REQUIRED("Snapshot required"),
    RECONCILED("Reconciled"),
    OFFLINE("Offline"),
    INCOMPATIBLE("Update required"),
    CLOSED("Closed"),
    FAILED("Failed"),
}

enum class TimelineKind(val label: String) {
    USER("You"),
    ASSISTANT("DSH"),
    TOOL_GENERIC("Tool"),
    TOOL_TERMINAL("Terminal"),
    TOOL_DIFF("Changes"),
    TOOL_UNSUPPORTED("Unsupported tool"),
    // S-vocab-ext: a tool call that delegates to a sub-agent (tool_name == "subagent").
    SUBAGENT("Sub-agent"),
    // S-vocab-ext: a user-role message whose source is a plugin (injected context).
    INJECT("Inject"),
    SESSION("Session"),
    UNSUPPORTED("Unsupported event"),
}

/** Bounded provenance for user-role messages (S-vocab-ext inject honesty). */
data class MessageSourceProjection(
    val kind: String,
    val plugin: String? = null,
    val form: String? = null,
) {
    val isInjected: Boolean
        get() = kind == "plugin"
}

/**
 * One image reference on a user message (S-blob, ADR-005): the reference
 * crosses in the projection; bytes are fetched through the blob channel under
 * the session-log-reference ACL. Absent optional dimensions stay null — an
 * unknown dimension is never rendered as zero.
 */
data class ImageAttachmentProjection(
    val attachmentId: String,
    val mediaType: String,
    val bytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val name: String? = null,
)

/** Deployment image-intake bounds from the hello (S-blob); absent ⟺ the Host accepts no attachments. */
data class AttachmentLimitsProjection(
    val maxImageBytes: Long,
    val maxImagesPerMessage: Int,
    val mediaTypes: List<String>,
)

/**
 * One composer-staged image (S-blob): a local intake the next send uploads
 * through the blob channel before the command is reserved. The thumbnail
 * reads the content uri directly — bytes never cross before commit.
 */
data class ComposerImage(
    val key: String,
    val previewUri: String,
    val displayName: String?,
    val mediaType: String,
)

/** Progress of the upload phase inside one send (S-blob); null while idle. */
data class AttachmentSendProgress(
    val completed: Int,
    val total: Int,
    val resuming: Boolean = false,
)

/** An interrupted staged upload survives in the encrypted journal (S-blob); the user resumes or abandons it explicitly. */
data class StagedUploadNotice(
    val displayName: String?,
)

/**
 * An image already committed Host-side, waiting to ride the next send
 * (S-blob). Only the reference survives — the local preview bytes were
 * cleaned at commit, so this never renders a thumbnail.
 */
data class CommittedImage(
    val attachmentId: String,
    val displayName: String?,
)

/** UI-facing blob fetch result (S-blob); the file is verified before Ready. */
sealed interface BlobFetchView {
    data class Ready(val file: java.io.File, val totalBytes: Long) : BlobFetchView
    data class Retryable(val detail: String) : BlobFetchView
    data class Failed(val detail: String) : BlobFetchView
}

internal fun imageAttachmentProjectionOf(attachment: ProtoImageAttachment): ImageAttachmentProjection =
    ImageAttachmentProjection(
        attachmentId = attachment.attachmentId,
        mediaType = attachment.mediaType,
        bytes = attachment.bytes.toLong(),
        width = if (attachment.hasWidth()) attachment.width else null,
        height = if (attachment.hasHeight()) attachment.height else null,
        name = if (attachment.hasName()) attachment.name else null,
    )

data class TimelineEntry(
    val id: String,
    val sourceSequence: Long,
    val kind: TimelineKind,
    val text: String,
    val final: Boolean = true,
    val callId: String? = null,
    val toolName: String? = null,
    val boundedContent: String? = null,
    val truncated: Boolean = false,
    val source: MessageSourceProjection? = null,
    val attachments: List<ImageAttachmentProjection> = emptyList(),
) {
    val contentType: String
        get() = kind.name
}

internal fun messageSourceProjectionOf(source: ProtoMessageSource): MessageSourceProjection =
    MessageSourceProjection(
        kind = source.kind,
        plugin = if (source.hasPlugin()) source.plugin else null,
        form = if (source.hasForm()) source.form else null,
    )

/**
 * Readable label for the durable per-turn terminal fact (S-vocab-ext); null
 * when the transition carries no mapped reason. Plugin-extended reason kinds
 * arrive as UNSPECIFIED/UNRECOGNIZED and stay honestly absent.
 */
internal fun turnEndReasonLabel(change: SessionStatusChanged): String? {
    if (!change.hasTurnEndReason()) return null
    return when (change.turnEndReason) {
        SessionStatusChanged.TurnEndReason.TURN_END_REASON_COMPLETED -> "completed"
        SessionStatusChanged.TurnEndReason.TURN_END_REASON_ABORTED -> "aborted"
        SessionStatusChanged.TurnEndReason.TURN_END_REASON_BLOCKED -> "blocked"
        SessionStatusChanged.TurnEndReason.TURN_END_REASON_ERROR -> "error"
        SessionStatusChanged.TurnEndReason.TURN_END_REASON_MAX_TOKENS -> "max-tokens"
        SessionStatusChanged.TurnEndReason.TURN_END_REASON_INTERRUPTED -> "interrupted"
        SessionStatusChanged.TurnEndReason.TURN_END_REASON_UNSPECIFIED,
        SessionStatusChanged.TurnEndReason.UNRECOGNIZED,
        -> null
    }
}

internal fun userTimelineEntry(
    eventId: String,
    sourceSequence: Long,
    text: String,
    messageId: String? = null,
    source: MessageSourceProjection? = null,
    attachments: List<ImageAttachmentProjection> = emptyList(),
): TimelineEntry = TimelineEntry(
    id = "user:${messageId?.takeIf(String::isNotBlank) ?: eventId}",
    sourceSequence = sourceSequence,
    // S-vocab-ext: injected context is a distinct honest row, not a human prompt.
    kind = if (source?.isInjected == true) TimelineKind.INJECT else TimelineKind.USER,
    text = text,
    source = source,
    attachments = attachments,
)

internal fun assistantTimelineEntry(
    eventId: String,
    sourceSequence: Long,
    message: AssistantMessage,
): TimelineEntry = TimelineEntry(
    id = "assistant:${message.messageId.takeIf(String::isNotBlank) ?: eventId}",
    sourceSequence = sourceSequence,
    kind = TimelineKind.ASSISTANT,
    text = message.text,
    final = message.final,
)

internal fun toolTimelineEntry(
    eventId: String,
    sourceSequence: Long,
    presentation: ToolPresentation,
): TimelineEntry {
    val kind = when {
        // S-vocab-ext: delegation calls render as sub-agent cards (real tool identity).
        presentation.toolName == "subagent" -> TimelineKind.SUBAGENT
        else -> when (presentation.kind) {
            ToolPresentation.Kind.KIND_GENERIC -> TimelineKind.TOOL_GENERIC
            ToolPresentation.Kind.KIND_TERMINAL -> TimelineKind.TOOL_TERMINAL
            ToolPresentation.Kind.KIND_DIFF -> TimelineKind.TOOL_DIFF
            ToolPresentation.Kind.KIND_UNSUPPORTED,
            ToolPresentation.Kind.KIND_UNSPECIFIED,
            ToolPresentation.Kind.UNRECOGNIZED,
            -> TimelineKind.TOOL_UNSUPPORTED
        }
    }
    val summary = listOf(presentation.toolName, presentation.summary)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(" · ")
        .ifEmpty { "No projected tool summary" }
    return TimelineEntry(
        id = "tool:${presentation.callId.takeIf(String::isNotBlank) ?: eventId}",
        sourceSequence = sourceSequence,
        kind = kind,
        text = summary,
        callId = presentation.callId.takeIf(String::isNotBlank),
        toolName = presentation.toolName.takeIf(String::isNotBlank),
        boundedContent = presentation.boundedContent.takeIf(String::isNotBlank),
        truncated = presentation.truncated,
    )
}

internal object TimelineReducer {
    fun assistantDelta(
        timeline: List<TimelineEntry>,
        eventId: String,
        sourceSequence: Long,
        messageId: String,
        delta: String,
    ): List<TimelineEntry> {
        val stableId = "assistant:${messageId.ifBlank { eventId }}"
        val index = timeline.indexOfLast { it.id == stableId && it.kind == TimelineKind.ASSISTANT }
        if (index < 0) {
            return timeline + TimelineEntry(
                id = stableId,
                sourceSequence = sourceSequence,
                kind = TimelineKind.ASSISTANT,
                text = delta,
                final = false,
            )
        }
        val current = timeline[index]
        if (current.final) return timeline
        return timeline.replaceAt(
            index,
            current.copy(
                sourceSequence = sourceSequence,
                text = current.text + delta,
            ),
        )
    }

    fun assistantCompleted(
        timeline: List<TimelineEntry>,
        eventId: String,
        sourceSequence: Long,
        messageId: String,
        text: String,
    ): List<TimelineEntry> {
        val stableId = "assistant:${messageId.ifBlank { eventId }}"
        val index = timeline.indexOfLast { it.id == stableId && it.kind == TimelineKind.ASSISTANT }
        val finalEntry = TimelineEntry(
            id = stableId,
            sourceSequence = sourceSequence,
            kind = TimelineKind.ASSISTANT,
            text = text,
            final = true,
        )
        return if (index < 0) timeline + finalEntry else timeline.replaceAt(index, finalEntry)
    }

    fun toolChanged(timeline: List<TimelineEntry>, entry: TimelineEntry): List<TimelineEntry> {
        val index = timeline.indexOfLast { it.id == entry.id && it.kind.name.startsWith("TOOL_") }
        return if (index < 0) timeline + entry else timeline.replaceAt(index, entry)
    }

    private fun List<TimelineEntry>.replaceAt(index: Int, entry: TimelineEntry): List<TimelineEntry> =
        toMutableList().also { it[index] = entry }
}

data class CommandReceipt(
    val commandId: String,
    val outcome: String,
    val replayed: Boolean,
    val errorCode: String,
    val detail: String,
)

enum class PendingCommandProgress(val label: String) {
    PREPARED("Queued securely"),
    RECEIVED("Received by Host"),
    REQUESTED("Stop requested"),
    UNKNOWN("Outcome unknown"),
}

data class PendingCommandStatus(
    val commandId: String,
    val sessionId: String,
    val operation: PendingCommandOperation,
    val expectedActivityRevision: Long?,
    val approvalId: String? = null,
    val approvalDecision: PendingApprovalDecision? = null,
    val agentPreset: String? = null,
    val modelSelection: ModelSelectionProjection? = null,
    /** Exact rule bound by a pending revoke (S-policy). */
    val ruleId: String? = null,
    /** Exact ceiling bound by a pending budget set (S-policy). */
    val maxTotalTokens: Long? = null,
    val progress: PendingCommandProgress,
    val createdAtMs: Long,
)

enum class ApprovalRisk(val label: String) {
    ROUTINE("Routine"),
    SENSITIVE("Sensitive"),
    DESTRUCTIVE("Destructive"),
    UNCLASSIFIED("Unclassified"),
}

data class ApprovalEvidence(
    val available: Boolean,
    val summary: String,
    val risk: ApprovalRisk,
    val resources: List<String>,
    val consequence: String,
    val source: String,
    val unavailableReason: String?,
)

data class ApprovalInteractionState(
    val approvalId: String,
    val revision: String,
    val sessionId: String,
    val toolName: String,
    val callId: String?,
    val reason: String?,
    val workspaceLabel: String?,
    val allowOnce: Boolean,
    val deny: Boolean,
    /**
     * True only when the Host offered APPROVAL_DECISION_ALLOW_SAME_KIND
     * (S-policy): an honest rule class is derivable for this ask. Absence
     * hides the affordance — the client never invents a policy decision.
     */
    val allowSameKind: Boolean = false,
    val evidence: ApprovalEvidence,
)

/**
 * One active session-scoped auto-grant rule from the Host policy fold
 * (S-policy). Every field is a durable `approval/rule` event fact; the list
 * itself is exact — an empty list means no rules, never "unknown".
 */
data class ApprovalRuleState(
    val ruleId: String,
    /** "escalate" (sandbox escalation, narrowed by [classMode]) or "tool". */
    val classKind: String,
    val toolName: String,
    /** Escalation target mode; null for tool-level rules. */
    val classMode: String?,
    /** "user" (authenticated decision) or "operator" (Host config). */
    val grantedBy: String,
    val grantedAtMs: Long,
) {
    /** Chinese-facing class label: what this rule auto-grants. */
    val classLabel: String
        get() = if (classKind == "escalate" && classMode != null) {
            "$toolName · 升级到 $classMode"
        } else {
            toolName
        }
}

/**
 * The session's token budget (S-policy). Null at the state level means no
 * budget is set — never a zero ceiling. [exhausted] is the Host's own
 * admission verdict, not a client-side recomputation.
 */
data class SessionBudgetState(
    val maxTotalTokens: Long,
    val exhausted: Boolean,
)

internal fun approvalRuleStateOf(rule: ProtoApprovalRule): ApprovalRuleState = ApprovalRuleState(
    ruleId = rule.ruleId,
    classKind = rule.classKind,
    toolName = rule.toolName,
    classMode = if (rule.hasClassMode()) rule.classMode else null,
    grantedBy = rule.grantedBy,
    grantedAtMs = rule.grantedAtMs,
)

internal fun sessionBudgetStateOf(budget: ProtoSessionBudget): SessionBudgetState = SessionBudgetState(
    maxTotalTokens = budget.maxTotalTokens,
    exhausted = budget.exhausted,
)

internal object ApprovalInteractionReducer {
    fun upsert(
        approvals: List<ApprovalInteractionState>,
        approval: ApprovalInteractionState,
    ): List<ApprovalInteractionState> = approvals.filterNot {
        it.approvalId == approval.approvalId
    } + approval

    fun resolve(
        approvals: List<ApprovalInteractionState>,
        approvalId: String,
        revision: String,
    ): List<ApprovalInteractionState> = approvals.filterNot {
        it.approvalId == approvalId && it.revision == revision
    }
}

data class ControlLeaseStatus(
    val sessionId: String,
    val epoch: String,
    val expiresAtMs: Long,
) {
    fun isUsable(nowMs: Long = System.currentTimeMillis()): Boolean = expiresAtMs > nowMs
}

internal object CommandReceiptReducer {
    fun upsert(
        receipts: List<CommandReceipt>,
        receipt: CommandReceipt,
        maxEntries: Int = 6,
    ): List<CommandReceipt> {
        require(maxEntries > 0)
        return (receipts.filterNot { it.commandId == receipt.commandId } + receipt).takeLast(maxEntries)
    }
}

data class SessionDirectoryEntry(
    val sessionId: String,
    val title: String?,
    val running: Boolean,
    val updatedAtMs: Long,
    val workspaceLabel: String?,
    val pendingApprovalCount: Int = 0,
    val pendingInputCount: Int = 0,
    val usage: SessionUsageProjection? = null,
    val parentSessionId: String? = null,
    val origin: String? = null,
    val subagent: SubagentProjection? = null,
    val agentPreset: String? = null,
    val model: ModelSelectionProjection? = null,
    /**
     * Operator-configured project registry label (S-project). Absent when the
     * Host registry matches nothing — never the basename restated as a
     * project; grouping falls back to the workspace label the row already
     * holds, labeled for what it is.
     */
    val projectLabel: String? = null,
) {
    val isSubagentChild: Boolean
        get() = origin == "subagent"
}

/**
 * One Agent preset the Host deployment can compose a session from
 * (S-mode-select). The roster rides ServerHello as a connect-time snapshot;
 * a stale row fails honestly with AGENT_PRESET_NOT_FOUND at selection time.
 * `broken` rows stay visible but can never be offered for selection.
 */
data class AgentPresetProjection(
    val id: String,
    val userTrust: Boolean,
    val isDefault: Boolean,
    val name: String? = null,
    val description: String? = null,
    val broken: String? = null,
) {
    /** The published display name, falling back to the stable id — never a second identity. */
    val displayName: String
        get() = name?.takeIf(String::isNotBlank) ?: id

    val selectable: Boolean
        get() = broken == null
}

internal fun agentPresetProjectionOf(entry: ProtoAgentPresetEntry): AgentPresetProjection =
    AgentPresetProjection(
        id = entry.id,
        userTrust = entry.trust == ProtoAgentPresetEntry.Trust.TRUST_USER,
        isDefault = entry.isDefault,
        name = if (entry.hasName()) entry.name else null,
        description = if (entry.hasDescription()) entry.description else null,
        broken = if (entry.hasBroken()) entry.broken else null,
    )

internal fun agentPresetProjections(entries: List<ProtoAgentPresetEntry>): List<AgentPresetProjection> =
    entries.map(::agentPresetProjectionOf)

/** Display label for a preset id: the roster's published name when known, else the raw id. */
internal fun agentPresetLabel(presets: List<AgentPresetProjection>, presetId: String?): String? =
    presetId?.let { id -> presets.find { it.id == id }?.displayName ?: id }

/** Catalog bounds, mirroring the Host carrier (32 groups × 64 models × 16 efforts). */
internal const val MAX_MODEL_PROVIDER_GROUPS = 32
internal const val MAX_MODELS_PER_PROVIDER = 64
internal const val MAX_REASONING_EFFORTS_PER_MODEL = 16
internal const val MAX_INPUT_MODALITIES_PER_MODEL = 8

/**
 * Exact provider/model/effort triple a session's subsequent requests use
 * (S-session-admin). Log-resolved Host-side; absent while the session never
 * recorded a request header — absence is never rendered as a default.
 */
data class ModelSelectionProjection(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)

/** One catalog model row: adapter-advertised identity plus the efforts it serves. */
data class ModelEntryProjection(
    val id: String,
    val name: String? = null,
    val reasoningEfforts: List<String> = emptyList(),
    val defaultReasoningEffort: String? = null,
    /**
     * Adapter-advertised input modalities (S-blob); empty means the adapter
     * declared none — unknown, never a text-only claim. The composer gates
     * image intake on this only when the row actually advertises.
     */
    val inputModalities: List<String> = emptyList(),
) {
    /** The published display name, falling back to the stable id — never a second identity. */
    val displayName: String
        get() = name?.takeIf(String::isNotBlank) ?: id

    /** True only when the adapter explicitly advertised image input; null when it declared nothing. */
    val acceptsImages: Boolean?
        get() = if (inputModalities.isEmpty()) null else inputModalities.any { it == "image" }
}

/**
 * One provider group of the connect-time model catalog (S-session-admin).
 * Membership is advisory: a row removed since hello fails selection honestly
 * with ERROR_CODE_MODEL_UNAVAILABLE rather than pretending to exist.
 */
data class ModelProviderGroupProjection(
    val id: String,
    val name: String? = null,
    val models: List<ModelEntryProjection> = emptyList(),
) {
    val displayName: String
        get() = name?.takeIf(String::isNotBlank) ?: id
}

/** A provider whose catalog could not be built; the sound groups stay usable. */
data class ModelCatalogFailureProjection(
    val providerId: String,
    val detail: String? = null,
)

internal fun modelSelectionOf(selection: ProtoModelSelection): ModelSelectionProjection =
    ModelSelectionProjection(
        provider = selection.provider,
        model = selection.model,
        reasoningEffort = if (selection.hasReasoningEffort()) selection.reasoningEffort else null,
    )

internal fun modelSelectionOf(change: ProtoModelChanged): ModelSelectionProjection =
    ModelSelectionProjection(
        provider = change.provider,
        model = change.model,
        reasoningEffort = if (change.hasReasoningEffort()) change.reasoningEffort else null,
    )

internal fun modelProviderGroupProjections(
    groups: List<ProtoModelProviderGroup>,
): List<ModelProviderGroupProjection> = groups.take(MAX_MODEL_PROVIDER_GROUPS).map { group ->
    ModelProviderGroupProjection(
        id = group.id,
        name = if (group.hasName()) group.name else null,
        models = group.modelsList.take(MAX_MODELS_PER_PROVIDER).map { entry ->
            ModelEntryProjection(
                id = entry.id,
                name = if (entry.hasName()) entry.name else null,
                reasoningEfforts = entry.reasoningEffortsList.take(MAX_REASONING_EFFORTS_PER_MODEL),
                defaultReasoningEffort = if (entry.hasDefaultReasoningEffort()) {
                    entry.defaultReasoningEffort
                } else {
                    null
                },
                inputModalities = entry.inputModalitiesList
                    .filter { it.isNotBlank() && it.length <= 32 }
                    .take(MAX_INPUT_MODALITIES_PER_MODEL),
            )
        },
    )
}

internal fun modelCatalogFailureProjections(
    failures: List<ProtoModelCatalogFailure>,
): List<ModelCatalogFailureProjection> = failures.take(MAX_MODEL_PROVIDER_GROUPS).map { failure ->
    ModelCatalogFailureProjection(
        providerId = failure.providerId,
        detail = if (failure.hasDetail()) failure.detail else null,
    )
}

/**
 * Display label for a selection: the catalog's published model name when
 * known, else the raw model id; the effort rides after a middle dot.
 */
internal fun modelDisplayLabel(
    catalog: List<ModelProviderGroupProjection>,
    selection: ModelSelectionProjection,
): String {
    val name = catalog.firstOrNull { it.id == selection.provider }
        ?.models?.firstOrNull { it.id == selection.model }
        ?.displayName ?: selection.model
    return selection.reasoningEffort?.let { "$name · $it" } ?: name
}


/**
 * Minimized usage views (REMOTE_PROTOCOL S-usage). A null unit means the Host
 * composition provides no such projection unit; null is never rendered as zero.
 */
data class SessionUsageProjection(
    val tokens: TokenUsageProjection?,
    val pressure: ContextPressureProjection?,
    val stats: SessionStatsProjection?,
)

data class TokenUsageProjection(
    val uncachedInputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
) {
    val totalTokens: Long
        get() = uncachedInputTokens + outputTokens + cacheReadTokens + cacheWriteTokens
    val cacheTokens: Long
        get() = cacheReadTokens + cacheWriteTokens
}

data class ContextPressureProjection(
    val pressureTokens: Long?,
    val projectedTokens: Long?,
    val contextWindow: Long?,
) {
    /** Remaining context against the newest projection; null when either side is unreported. */
    val contextLeft: Long?
        get() = if (contextWindow != null && projectedTokens != null) {
            (contextWindow - projectedTokens).coerceAtLeast(0L)
        } else {
            null
        }
}

data class SessionStatsProjection(
    val turns: Long,
    val steps: Long,
    val llmMs: Long,
    val toolMs: Long,
)

internal fun sessionUsageProjectionOf(usage: ProtoSessionUsage): SessionUsageProjection =
    SessionUsageProjection(
        tokens = if (usage.hasTokenUsage()) {
            val tokens = usage.tokenUsage
            TokenUsageProjection(
                uncachedInputTokens = tokens.uncachedInputTokens,
                outputTokens = tokens.outputTokens,
                cacheReadTokens = tokens.cacheReadTokens,
                cacheWriteTokens = tokens.cacheWriteTokens,
            )
        } else {
            null
        },
        pressure = if (usage.hasContextPressure()) {
            val pressure = usage.contextPressure
            ContextPressureProjection(
                pressureTokens = if (pressure.hasPressureTokens()) pressure.pressureTokens else null,
                projectedTokens = if (pressure.hasProjectedTokens()) pressure.projectedTokens else null,
                contextWindow = if (pressure.hasContextWindow()) pressure.contextWindow else null,
            )
        } else {
            null
        },
        stats = if (usage.hasStats()) {
            val stats = usage.stats
            SessionStatsProjection(
                turns = stats.turns,
                steps = stats.steps,
                llmMs = stats.llmMs,
                toolMs = stats.toolMs,
            )
        } else {
            null
        },
    )

/**
 * Merge one live UsageChanged into the retained usage. Only the units present
 * in the change are replaced; an absent unit keeps its previous view (it did
 * not change), so a partial update never reads as a reset.
 */
internal fun SessionUsageProjection?.mergedWith(change: ProtoUsageChanged): SessionUsageProjection {
    val base = this ?: SessionUsageProjection(tokens = null, pressure = null, stats = null)
    return SessionUsageProjection(
        tokens = if (change.hasTokenUsage()) {
            sessionUsageProjectionOf(ProtoSessionUsage.newBuilder().setTokenUsage(change.tokenUsage).build()).tokens
        } else {
            base.tokens
        },
        pressure = if (change.hasContextPressure()) {
            sessionUsageProjectionOf(
                ProtoSessionUsage.newBuilder().setContextPressure(change.contextPressure).build(),
            ).pressure
        } else {
            base.pressure
        },
        stats = if (change.hasStats()) {
            sessionUsageProjectionOf(ProtoSessionUsage.newBuilder().setStats(change.stats).build()).stats
        } else {
            base.stats
        },
    )
}

/**
 * Minimized sub-agent identity/timing halves (S-vocab-ext). Both identity
 * fields null ⟺ no valid descriptor; the whole view null ⟺ not a
 * descriptor-backed child session.
 */
data class SubagentProjection(
    val mode: String?,
    val label: String?,
    val settledMs: Long?,
    val activeSinceMs: Long?,
    val activeThroughMs: Long?,
) {
    val modeZh: String?
        get() = when (mode) {
            "one-shot" -> "一次性"
            "continuable" -> "可继续"
            else -> null
        }
}

internal fun subagentProjectionOf(view: ProtoSubagentView): SubagentProjection =
    SubagentProjection(
        mode = if (view.hasMode()) view.mode else null,
        label = if (view.hasLabel()) view.label else null,
        settledMs = if (view.hasSettledMs()) view.settledMs else null,
        activeSinceMs = if (view.hasActiveSinceMs()) view.activeSinceMs else null,
        activeThroughMs = if (view.hasActiveThroughMs()) view.activeThroughMs else null,
    )

/**
 * Merge one live SubagentChanged into the retained view. Only the half the
 * change carries is replaced; an absent half keeps its previous value.
 */
internal fun SubagentProjection?.mergedWith(change: ProtoSubagentView): SubagentProjection {
    val base = this ?: SubagentProjection(
        mode = null, label = null, settledMs = null, activeSinceMs = null, activeThroughMs = null,
    )
    val identity = change.hasMode() || change.hasLabel()
    val timing = change.hasSettledMs() || change.hasActiveSinceMs() || change.hasActiveThroughMs()
    return SubagentProjection(
        mode = if (identity) subagentProjectionOf(change).mode else base.mode,
        label = if (identity) subagentProjectionOf(change).label else base.label,
        settledMs = if (timing) subagentProjectionOf(change).settledMs else base.settledMs,
        activeSinceMs = if (timing) subagentProjectionOf(change).activeSinceMs else base.activeSinceMs,
        activeThroughMs = if (timing) subagentProjectionOf(change).activeThroughMs else base.activeThroughMs,
    )
}

internal fun sessionDirectoryEntries(sessions: List<ProtoSessionSummary>): List<SessionDirectoryEntry> =
    sessions.map { session ->
        SessionDirectoryEntry(
            sessionId = session.sessionId,
            title = session.title.takeIf(String::isNotBlank),
            running = session.running,
            updatedAtMs = session.updatedAtMs,
            workspaceLabel = session.workspaceLabel.takeIf(String::isNotBlank),
            pendingApprovalCount = session.pendingApprovalCount,
            pendingInputCount = session.pendingInputCount,
            usage = if (session.hasUsage()) sessionUsageProjectionOf(session.usage) else null,
            parentSessionId = if (session.hasParentSessionId()) session.parentSessionId else null,
            origin = if (session.hasOrigin()) session.origin else null,
            subagent = if (session.hasSubagent()) subagentProjectionOf(session.subagent) else null,
            agentPreset = if (session.hasAgentPreset()) session.agentPreset else null,
            model = if (session.hasModel()) modelSelectionOf(session.model) else null,
            projectLabel = if (session.hasProjectLabel()) {
                session.projectLabel.takeIf(String::isNotBlank)
            } else {
                null
            },
        )
    }.sortedWith(
        compareByDescending<SessionDirectoryEntry> { it.pendingInputCount > 0 }
            .thenByDescending { it.pendingApprovalCount > 0 }
            .thenByDescending { it.running }
            .thenByDescending { it.updatedAtMs },
    )

internal fun List<SessionDirectoryEntry>.updateSession(
    sessionId: String,
    title: String? = null,
    running: Boolean? = null,
    pendingApprovalCount: Int? = null,
    pendingInputCount: Int? = null,
): List<SessionDirectoryEntry> = map { entry ->
    if (entry.sessionId != sessionId) entry else entry.copy(
        title = title ?: entry.title,
        running = running ?: entry.running,
        pendingApprovalCount = pendingApprovalCount ?: entry.pendingApprovalCount,
        pendingInputCount = pendingInputCount ?: entry.pendingInputCount,
    )
}.sortedWith(
    compareByDescending<SessionDirectoryEntry> { it.pendingInputCount > 0 }
        .thenByDescending { it.pendingApprovalCount > 0 }
        .thenByDescending { it.running }
        .thenByDescending { it.updatedAtMs },
)

/** Explicitly set (or clear) one entry's usage view without touching the sort order. */
internal fun List<SessionDirectoryEntry>.replaceSessionUsage(
    sessionId: String,
    usage: SessionUsageProjection?,
): List<SessionDirectoryEntry> = map { entry ->
    if (entry.sessionId == sessionId) entry.copy(usage = usage) else entry
}

/** Explicitly set (or clear) one entry's sub-agent view without touching the sort order. */
internal fun List<SessionDirectoryEntry>.replaceSessionSubagent(
    sessionId: String,
    subagent: SubagentProjection?,
): List<SessionDirectoryEntry> = map { entry ->
    if (entry.sessionId == sessionId) entry.copy(subagent = subagent) else entry
}

/** Explicitly set (or clear) one entry's Agent preset without touching the sort order. */
internal fun List<SessionDirectoryEntry>.replaceSessionAgentPreset(
    sessionId: String,
    agentPreset: String?,
): List<SessionDirectoryEntry> = map { entry ->
    if (entry.sessionId == sessionId) entry.copy(agentPreset = agentPreset) else entry
}

/** Explicitly set (or clear) one entry's model selection without touching the sort order. */
internal fun List<SessionDirectoryEntry>.replaceSessionModel(
    sessionId: String,
    model: ModelSelectionProjection?,
): List<SessionDirectoryEntry> = map { entry ->
    if (entry.sessionId == sessionId) entry.copy(model = model) else entry
}

/**
 * S-artifacts: one Host-projected artifact roster row. Every field is a durable
 * journal fact carried by the wire — the minimized path, the derived create
 * marker, the bounded whole-hunk JSON and its truncation marker. `content` is
 * null when the mutation registered no hunk content; that absence is rendered
 * as "content not projected", never as an empty change.
 */
data class ArtifactEntryState(
    val artifactId: String,
    val sessionId: String,
    val path: String,
    val outsideWorkspace: Boolean,
    val isNewFile: Boolean,
    val content: String?,
    val truncated: Boolean,
    val registeredAtMs: Long,
)

/**
 * Register a live artifact frame into the roster: a new entry lands at the
 * front (the roster is newest-first), an already-known id keeps its position —
 * journal facts are immutable, so a repeated id is the same fact, never an
 * update.
 */
internal fun List<ArtifactEntryState>.withArtifactRegistered(
    artifact: ArtifactEntryState,
): List<ArtifactEntryState> =
    if (any { it.artifactId == artifact.artifactId }) this else listOf(artifact) + this

/** S-artifacts: map one wire row verbatim; absence of content stays absence. */
internal fun artifactEntryStateOf(summary: ProtoArtifactSummary): ArtifactEntryState =
    ArtifactEntryState(
        artifactId = summary.artifactId,
        sessionId = summary.sessionId,
        path = summary.path,
        outsideWorkspace = summary.outsideWorkspace,
        isNewFile = summary.isNewFile,
        content = if (summary.hasContent()) summary.content else null,
        truncated = summary.truncated,
        registeredAtMs = summary.registeredAtMs,
    )

data class Gate0CState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val endpoint: String = "127.0.0.1:50051",
    val hostInstanceId: String? = null,
    /**
     * Operator-facing Host name from the hello — stable across Host restarts,
     * unlike the per-boot [hostInstanceId]. Null from older Hosts; labels fall
     * back to the instance id, then the endpoint.
     */
    val hostDisplayName: String? = null,
    val connectionId: String? = null,
    val grantedCapabilities: ULong = 0u,
    val sessions: List<SessionDirectoryEntry> = emptyList(),
    val agentPresets: List<AgentPresetProjection> = emptyList(),
    val modelCatalog: List<ModelProviderGroupProjection> = emptyList(),
    val modelCatalogFailures: List<ModelCatalogFailureProjection> = emptyList(),
    /**
     * Deployment image-intake bounds from the hello (S-blob); null ⟺ the Host
     * accepts no attachments, in which case the composer affordance stays
     * hidden — absence is never rendered as a zero bound.
     */
    val attachmentLimits: AttachmentLimitsProjection? = null,
    /** Composer-staged images the next send uploads first (S-blob); local only, never a Host fact. */
    val composerImages: List<ComposerImage> = emptyList(),
    /** Images already committed Host-side that the next send references (S-blob). */
    val committedAttachments: List<CommittedImage> = emptyList(),
    /** Non-null during the upload phase of a send (S-blob). */
    val attachmentSend: AttachmentSendProgress? = null,
    /** An interrupted staged upload awaiting an explicit resume/abandon (S-blob). */
    val stagedUpload: StagedUploadNotice? = null,
    val sessionId: String? = null,
    val sessionTitle: String? = null,
    val sessionRunning: Boolean? = null,
    val sessionUsage: SessionUsageProjection? = null,
    val sessionSubagent: SubagentProjection? = null,
    val sessionOrigin: String? = null,
    val sessionAgentPreset: String? = null,
    val sessionModel: ModelSelectionProjection? = null,
    val activityRevision: Long? = null,
    val streamId: String? = null,
    val projectionVersion: Int? = null,
    val cursor: Long? = null,
    val timeline: List<TimelineEntry> = emptyList(),
    val approvals: List<ApprovalInteractionState> = emptyList(),
    /** Active auto-grant rules of the open session (S-policy); exact, never partial. */
    val approvalRules: List<ApprovalRuleState> = emptyList(),
    /** The open session's token budget (S-policy); null ⟺ none set. */
    val sessionBudget: SessionBudgetState? = null,
    val artifacts: List<ArtifactEntryState> = emptyList(),
    val historyTruncated: Boolean = false,
    val commandReceipts: List<CommandReceipt> = emptyList(),
    val pendingCommand: PendingCommandStatus? = null,
    val controlLease: ControlLeaseStatus? = null,
    val commandWarning: String? = null,
    val commandRecoveryBlocked: Boolean = false,
    val events: List<String> = listOf("Waiting for the Android lifecycle to start the carrier."),
    val failure: String? = null,
    val pairingVerificationCode: String? = null,
    val pairedHostFingerprint: String? = null,
    val pairingRecoveryPending: Boolean = false,
    val newPairingRequired: Boolean = false,
    val storageSealedByLock: Boolean = false,
    val offlineSnapshot: Boolean = false,
    val offlineCacheSavedAtMs: Long? = null,
    val offlineCacheTruncated: Boolean = false,
    val localDraft: String = "",
    val readingAnchorId: String? = null,
    val readingOffsetPx: Int = 0,
    val followTail: Boolean = true,
    val readingAnchorUnavailable: Boolean = false,
    val cacheWarning: String? = null,
) {
    /**
     * True while the Host log holds no turn for the subscribed session — the
     * only window in which selecting an Agent preset is legal (S-mode-select).
     * A truncated or running view is never treated as blank.
     */
    val sessionBlank: Boolean
        get() = timeline.isEmpty() && !historyTruncated && sessionRunning == false
}

sealed interface ApplyDecision {
    data object Duplicate : ApplyDecision
    data object Contiguous : ApplyDecision
    data class Gap(val expected: Long, val actual: Long) : ApplyDecision
}

object CursorPolicy {
    fun decide(current: Long, incoming: Long): ApplyDecision = when {
        incoming <= current -> ApplyDecision.Duplicate
        incoming == current + 1 -> ApplyDecision.Contiguous
        else -> ApplyDecision.Gap(expected = current + 1, actual = incoming)
    }
}
