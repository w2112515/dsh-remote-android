package dev.dshremote.gate0c.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCommandCodecTest {
    @Test
    fun roundTripsExactAuthorityCommandAndControlBinding() {
        val command = command()
        val decoded = PendingCommandCodec.decode(PendingCommandCodec.encode(command))

        assertArrayEquals(command.authorityBinding, decoded.authorityBinding)
        assertArrayEquals(command.requestFingerprint, decoded.requestFingerprint)
        assertEquals(command.commandId, decoded.commandId)
        assertEquals(command.sessionId, decoded.sessionId)
        assertEquals(PendingCommandOperation.SEND_INPUT, decoded.operation)
        assertEquals(command.text, decoded.text)
        assertEquals(command.controlEpoch, decoded.controlEpoch)
        assertEquals(command.controlToken, decoded.controlToken)
        assertEquals(PendingCommandPhase.PREPARED, decoded.phase)
    }

    @Test
    fun roundTripsExactTurnStopWithoutDraftContent() {
        val stop = PendingRemoteCommand.createStop(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-stop-1",
            sessionId = "session-1",
            expectedActivityRevision = 19,
            controlEpoch = "7",
            controlToken = "S".repeat(43),
            controlExpiresAtMs = 31_000,
            createdAtMs = 1_100,
        ).withPhase(PendingCommandPhase.REQUESTED)

        val decoded = PendingCommandCodec.decode(PendingCommandCodec.encode(stop))

        assertEquals(PendingCommandOperation.STOP, decoded.operation)
        assertEquals(19L, decoded.expectedActivityRevision)
        assertEquals(null, decoded.text)
        assertEquals(PendingCommandPhase.REQUESTED, decoded.phase)
        assertArrayEquals(stop.requestFingerprint, decoded.requestFingerprint)
    }

    @Test
    fun roundTripsExactApprovalRevisionWithoutInventingAControlLease() {
        val decision = PendingRemoteCommand.createApprovalDecision(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-approval-1",
            sessionId = "session-1",
            approvalId = "approval-1",
            approvalRevision = "revision-1",
            approvalDecision = PendingApprovalDecision.ALLOW_ONCE,
            createdAtMs = 1_100,
        ).withPhase(PendingCommandPhase.RECEIVED)

        val decoded = PendingCommandCodec.decode(PendingCommandCodec.encode(decision))

        assertEquals(PendingCommandOperation.DECIDE_APPROVAL, decoded.operation)
        assertEquals("approval-1", decoded.approvalId)
        assertEquals("revision-1", decoded.approvalRevision)
        assertEquals(PendingApprovalDecision.ALLOW_ONCE, decoded.approvalDecision)
        assertEquals(null, decoded.controlEpoch)
        assertEquals(null, decoded.controlToken)
        assertEquals(null, decoded.controlExpiresAtMs)
        assertArrayEquals(decision.requestFingerprint, decoded.requestFingerprint)
    }

    @Test
    fun roundTripsLeaseFreeSessionAdminCommandsWithTheirExactPresetHalf() {
        // S-mode-select: creation carries an optional preset; selection a
        // required one; neither invents a control lease.
        val create = PendingRemoteCommand.createSession(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-create-1",
            sessionId = "android-preallocated-1",
            agentPreset = "code",
            createdAtMs = 1_100,
        ).withPhase(PendingCommandPhase.RECEIVED)
        val createDefault = PendingRemoteCommand.createSession(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-create-2",
            sessionId = "android-preallocated-2",
            agentPreset = null,
            createdAtMs = 1_101,
        )
        val select = PendingRemoteCommand.createSelectAgentPreset(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-preset-1",
            sessionId = "android-preallocated-1",
            agentPreset = "minimal",
            createdAtMs = 1_102,
        ).withPhase(PendingCommandPhase.UNKNOWN)

        val decodedCreate = PendingCommandCodec.decode(PendingCommandCodec.encode(create))
        assertEquals(PendingCommandOperation.CREATE_SESSION, decodedCreate.operation)
        assertEquals("android-preallocated-1", decodedCreate.sessionId)
        assertEquals("code", decodedCreate.agentPreset)
        assertEquals(null, decodedCreate.controlEpoch)
        assertEquals(null, decodedCreate.controlToken)
        assertArrayEquals(create.requestFingerprint, decodedCreate.requestFingerprint)

        val decodedDefault = PendingCommandCodec.decode(PendingCommandCodec.encode(createDefault))
        assertEquals(null, decodedDefault.agentPreset)
        assertEquals(null, decodedDefault.workspaceId)
        assertEquals(null, decodedDefault.newWorkspaceName)
        assertArrayEquals(createDefault.requestFingerprint, decodedDefault.requestFingerprint)
        // The preset half participates in identity: a different preset is a
        // different command, so a reused command id conflicts instead of replaying.
        assertFalse(create.requestFingerprint.contentEquals(createDefault.requestFingerprint))

        val bound = PendingRemoteCommand.createSession(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-create-3",
            sessionId = "android-preallocated-3",
            agentPreset = null,
            createdAtMs = 1_103,
            workspaceId = "ws-parent",
            newWorkspaceName = "notes",
        )
        val decodedBound = PendingCommandCodec.decode(PendingCommandCodec.encode(bound))
        assertEquals("ws-parent", decodedBound.workspaceId)
        assertEquals("notes", decodedBound.newWorkspaceName)
        assertArrayEquals(bound.requestFingerprint, decodedBound.requestFingerprint)
        assertFalse(bound.requestFingerprint.contentEquals(createDefault.requestFingerprint))

        val decodedSelect = PendingCommandCodec.decode(PendingCommandCodec.encode(select))
        assertEquals(PendingCommandOperation.SELECT_AGENT_PRESET, decodedSelect.operation)
        assertEquals("minimal", decodedSelect.agentPreset)
        assertEquals(PendingCommandPhase.UNKNOWN, decodedSelect.phase)
        assertArrayEquals(select.requestFingerprint, decodedSelect.requestFingerprint)
    }

    @Test
    fun sessionAdminCommandsRejectUnboundedPresetsAndFabricatedLeases() {
        assertThrows(IllegalArgumentException::class.java) {
            PendingRemoteCommand.createSelectAgentPreset(
                authorityBinding = ByteArray(32),
                pairedAtMs = 1,
                commandId = "command",
                sessionId = "session",
                agentPreset = "not a valid id",
                createdAtMs = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PendingRemoteCommand.createSelectAgentPreset(
                authorityBinding = ByteArray(32),
                pairedAtMs = 1,
                commandId = "command",
                sessionId = "session",
                agentPreset = "x".repeat(101),
                createdAtMs = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PendingRemoteCommand.createSession(
                authorityBinding = ByteArray(32),
                pairedAtMs = 1,
                commandId = "command",
                sessionId = "session",
                agentPreset = "tab\tid",
                createdAtMs = 1,
            )
        }
        // A lease must never appear on a lease-free operation.
        assertThrows(IllegalArgumentException::class.java) {
            PendingRemoteCommand.createSession(
                authorityBinding = ByteArray(32),
                pairedAtMs = 1,
                commandId = "command",
                sessionId = "session",
                agentPreset = null,
                createdAtMs = 1,
            ).copy(controlEpoch = "1", controlToken = "A".repeat(43), controlExpiresAtMs = 2)
        }
        // And a send must never acquire a preset half.
        assertThrows(IllegalArgumentException::class.java) {
            command().copy(agentPreset = "code")
        }
    }

    @Test
    fun roundTripsFencedModelSelectionWithItsExactTriple() {
        // S-session-admin: the triple and the control fence both survive; an
        // absent effort stays absent (it clears inherited effort at the owner).
        val selection = ModelSelectionProjection("deepseek", "deepseek-chat", "high")
        val command = PendingRemoteCommand.createSelectModel(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-model-1",
            sessionId = "session-1",
            modelSelection = selection,
            controlEpoch = "7",
            controlToken = "M".repeat(43),
            controlExpiresAtMs = 31_000,
            createdAtMs = 1_100,
        ).withPhase(PendingCommandPhase.RECEIVED)
        val noEffort = PendingRemoteCommand.createSelectModel(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-model-2",
            sessionId = "session-1",
            modelSelection = selection.copy(reasoningEffort = null),
            controlEpoch = "7",
            controlToken = "M".repeat(43),
            controlExpiresAtMs = 31_000,
            createdAtMs = 1_101,
        )

        val decoded = PendingCommandCodec.decode(PendingCommandCodec.encode(command))
        assertEquals(PendingCommandOperation.SELECT_MODEL, decoded.operation)
        assertEquals(selection, decoded.modelSelection)
        assertEquals("7", decoded.controlEpoch)
        assertEquals("M".repeat(43), decoded.controlToken)
        assertEquals(31_000L, decoded.controlExpiresAtMs)
        assertEquals(PendingCommandPhase.RECEIVED, decoded.phase)
        assertArrayEquals(command.requestFingerprint, decoded.requestFingerprint)

        val decodedNoEffort = PendingCommandCodec.decode(PendingCommandCodec.encode(noEffort))
        assertEquals(selection.copy(reasoningEffort = null), decodedNoEffort.modelSelection)
        assertNull(decodedNoEffort.modelSelection?.reasoningEffort)
        // The effort half participates in identity: a different triple is a
        // different command, so a reused command id conflicts instead of replaying.
        assertFalse(command.requestFingerprint.contentEquals(noEffort.requestFingerprint))
    }

    @Test
    fun roundTripsLeaseFreeForkWithPreallocatedChildAndOptionalAnchor() {
        // S-session-admin: the preallocated child id is the convergence key; no
        // control lease ever crosses for a fork.
        val fork = PendingRemoteCommand.createForkSession(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-fork-1",
            sessionId = "session-source",
            childSessionId = "android-child-1",
            forkAtSeq = 41,
            createdAtMs = 1_100,
        ).withPhase(PendingCommandPhase.UNKNOWN)
        val forkTail = PendingRemoteCommand.createForkSession(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-fork-2",
            sessionId = "session-source",
            childSessionId = "android-child-2",
            forkAtSeq = null,
            createdAtMs = 1_101,
        )

        val decoded = PendingCommandCodec.decode(PendingCommandCodec.encode(fork))
        assertEquals(PendingCommandOperation.FORK_SESSION, decoded.operation)
        assertEquals("session-source", decoded.sessionId)
        assertEquals("android-child-1", decoded.childSessionId)
        assertEquals(41L, decoded.forkAtSeq)
        assertNull(decoded.controlEpoch)
        assertNull(decoded.controlToken)
        assertEquals(PendingCommandPhase.UNKNOWN, decoded.phase)
        assertArrayEquals(fork.requestFingerprint, decoded.requestFingerprint)

        val decodedTail = PendingCommandCodec.decode(PendingCommandCodec.encode(forkTail))
        assertNull(decodedTail.forkAtSeq)
        assertFalse(fork.requestFingerprint.contentEquals(forkTail.requestFingerprint))
    }

    @Test
    fun modelAndForkCommandsRejectMisplacedFields() {
        // A fenced select_model must never go out lease-free...
        assertThrows(IllegalArgumentException::class.java) {
            PendingRemoteCommand.createSelectModel(
                authorityBinding = ByteArray(32),
                pairedAtMs = 1,
                commandId = "command",
                sessionId = "session",
                modelSelection = ModelSelectionProjection("p", "m"),
                controlEpoch = "1",
                controlToken = "A".repeat(43),
                controlExpiresAtMs = 2,
                createdAtMs = 1,
            ).copy(controlEpoch = null, controlToken = null, controlExpiresAtMs = null)
        }
        // ...and a lease must never appear on a lease-free fork.
        assertThrows(IllegalArgumentException::class.java) {
            PendingRemoteCommand.createForkSession(
                authorityBinding = ByteArray(32),
                pairedAtMs = 1,
                commandId = "command",
                sessionId = "session",
                childSessionId = "child",
                forkAtSeq = null,
                createdAtMs = 1,
            ).copy(controlEpoch = "1", controlToken = "A".repeat(43), controlExpiresAtMs = 2)
        }
        // Adapter-exact ids only: provider/effort bounded at 100, model at 200.
        assertThrows(IllegalArgumentException::class.java) {
            PendingRemoteCommand.createSelectModel(
                authorityBinding = ByteArray(32),
                pairedAtMs = 1,
                commandId = "command",
                sessionId = "session",
                modelSelection = ModelSelectionProjection("not a valid id", "m"),
                controlEpoch = "1",
                controlToken = "A".repeat(43),
                controlExpiresAtMs = 2,
                createdAtMs = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PendingRemoteCommand.createSelectModel(
                authorityBinding = ByteArray(32),
                pairedAtMs = 1,
                commandId = "command",
                sessionId = "session",
                modelSelection = ModelSelectionProjection("p", "x".repeat(201)),
                controlEpoch = "1",
                controlToken = "A".repeat(43),
                controlExpiresAtMs = 2,
                createdAtMs = 1,
            )
        }
        // A send must never acquire a model half; a fork never loses its child id.
        assertThrows(IllegalArgumentException::class.java) {
            command().copy(modelSelection = ModelSelectionProjection("p", "m"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PendingRemoteCommand.createForkSession(
                authorityBinding = ByteArray(32),
                pairedAtMs = 1,
                commandId = "command",
                sessionId = "session",
                childSessionId = "child",
                forkAtSeq = null,
                createdAtMs = 1,
            ).copy(childSessionId = null)
        }
    }

    @Test
    fun legacyV4SessionAdminCommandsMigrateToTheCurrentFormat() {
        // The v5 layout only ADDS arms; a v4 create/preset payload is byte
        // identical apart from the version int, so patching it back exercises
        // the real migration path (fingerprint re-derived, phase preserved).
        fun asV4(command: PendingRemoteCommand): ByteArray =
            PendingCommandCodec.encode(command).also { it[3] = 4 }

        val select = PendingRemoteCommand.createSelectAgentPreset(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-preset-legacy",
            sessionId = "android-preallocated-legacy",
            agentPreset = "minimal",
            createdAtMs = 1_101,
        ).withPhase(PendingCommandPhase.UNKNOWN)
        val migratedSelect = PendingCommandCodec.decode(asV4(select))
        assertEquals(PendingCommandOperation.SELECT_AGENT_PRESET, migratedSelect.operation)
        assertEquals("minimal", migratedSelect.agentPreset)
        assertEquals(PendingCommandPhase.UNKNOWN, migratedSelect.phase)
        assertArrayEquals(select.requestFingerprint, migratedSelect.requestFingerprint)
    }

    @Test
    fun phaseChangesKeepTheSameCommandIdentityAndFingerprint() {
        val command = command()
        val unknown = command.withPhase(PendingCommandPhase.UNKNOWN)

        assertEquals(command.commandId, unknown.commandId)
        assertArrayEquals(command.requestFingerprint, unknown.requestFingerprint)
        assertEquals(PendingCommandPhase.UNKNOWN, unknown.phase)
    }

    @Test
    fun rejectsFingerprintMutationTrailingDataAndInvalidControlTokens() {
        val command = command()
        val fingerprint = command.requestFingerprint.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            command.copy(requestFingerprint = fingerprint)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PendingCommandCodec.decode(PendingCommandCodec.encode(command) + byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PendingRemoteCommand.create(
                authorityBinding = ByteArray(32),
                pairedAtMs = 1,
                commandId = "command",
                sessionId = "session",
                text = "hello",
                controlEpoch = "1",
                controlToken = "not-a-token",
                controlExpiresAtMs = 2,
                createdAtMs = 1,
            )
        }
    }

    @Test
    fun capabilityAndLeasePredicatesFailClosed() {
        assertTrue(hasCapabilities(71uL, 68uL))
        assertFalse(hasCapabilities(3uL, 68uL))
        assertTrue(hasCapabilities(79uL, 72uL))
        assertFalse(hasCapabilities(71uL, 72uL))
        assertTrue(hasCapabilities(19uL, 16uL))
        assertTrue(hasCapabilities(95uL, 16uL))
        assertFalse(hasCapabilities(79uL, 16uL))
        assertTrue(ControlLeaseStatus("session", "1", 2_000).isUsable(nowMs = 1_999))
        assertFalse(ControlLeaseStatus("session", "1", 2_000).isUsable(nowMs = 2_000))
    }

    @Test
    fun roundTripsSendInputWithCommittedAttachmentIds() {
        // S-blob: the protected command records only committed content
        // addresses; the ids survive the encrypted round trip byte-exact.
        val ids = listOf("sha256:${"ab".repeat(32)}", "sha256:${"cd".repeat(32)}")
        val command = PendingRemoteCommand.create(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-command-attach",
            sessionId = "session-1",
            text = "look at these",
            controlEpoch = "7",
            controlToken = "A".repeat(43),
            controlExpiresAtMs = 31_000,
            createdAtMs = 1_100,
            attachmentIds = ids,
        )
        val decoded = PendingCommandCodec.decode(PendingCommandCodec.encode(command))

        assertEquals(ids, decoded.attachmentIds)
        assertArrayEquals(command.requestFingerprint, decoded.requestFingerprint)
    }

    @Test
    fun rejectsAttachmentIdsThatAreNotCommittedContentAddresses() {
        assertThrows(IllegalArgumentException::class.java) {
            PendingRemoteCommand.create(
                authorityBinding = ByteArray(32),
                pairedAtMs = 1,
                commandId = "command",
                sessionId = "session",
                text = "hello",
                controlEpoch = "1",
                controlToken = "A".repeat(43),
                controlExpiresAtMs = 2,
                createdAtMs = 1,
                attachmentIds = listOf("not-a-content-address"),
            )
        }
        // A different operation never carries attachments.
        assertThrows(IllegalArgumentException::class.java) {
            PendingRemoteCommand.createStop(
                authorityBinding = ByteArray(32),
                pairedAtMs = 1,
                commandId = "command",
                sessionId = "session",
                expectedActivityRevision = 3,
                controlEpoch = "1",
                controlToken = "A".repeat(43),
                controlExpiresAtMs = 2,
                createdAtMs = 1,
            ).copy(attachmentIds = listOf("sha256:${"ab".repeat(32)}"))
        }
    }

    @Test
    fun legacyV5SendInputMigratesWithoutInventingAttachments() {
        // A v5 payload has no attachment count; hand-encode that exact layout
        // so the migration path proves it never invents references.
        val command = command()
        val v5 = java.io.ByteArrayOutputStream().use { output ->
            java.io.DataOutputStream(output).use { data ->
                data.writeInt(5)
                data.write(command.authorityBinding)
                data.writeLong(command.pairedAtMs)
                data.writeBounded(command.commandId)
                data.writeBounded(command.sessionId)
                data.writeBounded(command.operation.name)
                data.writeBounded(requireNotNull(command.text))
                data.writeBoolean(true)
                data.writeBounded(requireNotNull(command.controlEpoch))
                data.writeBounded(requireNotNull(command.controlToken))
                data.writeLong(requireNotNull(command.controlExpiresAtMs))
                data.writeLong(command.createdAtMs)
                data.writeBounded(command.phase.name)
                data.write(command.requestFingerprint)
            }
            output.toByteArray()
        }
        val migrated = PendingCommandCodec.decode(v5)

        assertEquals(PendingCommandOperation.SEND_INPUT, migrated.operation)
        assertNull(migrated.attachmentIds)
        assertEquals(command.text, migrated.text)
    }

    @Test
    fun roundTripsLeaseFreePolicyCommandsWithTheirExactBinding() {
        // S-policy: revocation binds one exact rule id; the budget one exact
        // ceiling. Both are lease-free durable policy mutations.
        val revoke = PendingRemoteCommand.createRevokeRule(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-revoke-1",
            sessionId = "session-1",
            ruleId = "9c1e04d548f7bda3",
            createdAtMs = 1_100,
        ).withPhase(PendingCommandPhase.RECEIVED)
        val budget = PendingRemoteCommand.createSetSessionBudget(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-budget-1",
            sessionId = "session-1",
            maxTotalTokens = 200_000,
            createdAtMs = 1_101,
        ).withPhase(PendingCommandPhase.UNKNOWN)

        val decodedRevoke = PendingCommandCodec.decode(PendingCommandCodec.encode(revoke))
        assertEquals(PendingCommandOperation.REVOKE_APPROVAL_RULE, decodedRevoke.operation)
        assertEquals("9c1e04d548f7bda3", decodedRevoke.ruleId)
        assertNull(decodedRevoke.maxTotalTokens)
        assertNull(decodedRevoke.controlEpoch)
        assertNull(decodedRevoke.controlToken)
        assertEquals(PendingCommandPhase.RECEIVED, decodedRevoke.phase)
        assertArrayEquals(revoke.requestFingerprint, decodedRevoke.requestFingerprint)

        val decodedBudget = PendingCommandCodec.decode(PendingCommandCodec.encode(budget))
        assertEquals(PendingCommandOperation.SET_SESSION_BUDGET, decodedBudget.operation)
        assertEquals(200_000L, decodedBudget.maxTotalTokens)
        assertNull(decodedBudget.ruleId)
        assertNull(decodedBudget.controlEpoch)
        assertEquals(PendingCommandPhase.UNKNOWN, decodedBudget.phase)
        assertArrayEquals(budget.requestFingerprint, decodedBudget.requestFingerprint)

        // The bound half participates in identity: a different rule/ceiling is
        // a different command, so a reused command id conflicts, not replays.
        val otherRule = PendingRemoteCommand.createRevokeRule(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-revoke-1",
            sessionId = "session-1",
            ruleId = "aaaa04d548f7bda3",
            createdAtMs = 1_100,
        )
        assertFalse(revoke.requestFingerprint.contentEquals(otherRule.requestFingerprint))
        val otherCeiling = PendingRemoteCommand.createSetSessionBudget(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-budget-1",
            sessionId = "session-1",
            maxTotalTokens = 300_000,
            createdAtMs = 1_101,
        )
        assertFalse(budget.requestFingerprint.contentEquals(otherCeiling.requestFingerprint))
    }

    @Test
    fun policyCommandsRejectMisboundFieldsAndFabricatedLeases() {
        // A rule id must be canonical hex (Host fence: 16..64 chars).
        assertThrows(IllegalArgumentException::class.java) {
            PendingRemoteCommand.createRevokeRule(
                authorityBinding = ByteArray(32),
                pairedAtMs = 1,
                commandId = "command",
                sessionId = "session",
                ruleId = "not-a-rule-id",
                createdAtMs = 1,
            )
        }
        // A ceiling must be positive — zero is "no budget", never a command.
        assertThrows(IllegalArgumentException::class.java) {
            PendingRemoteCommand.createSetSessionBudget(
                authorityBinding = ByteArray(32),
                pairedAtMs = 1,
                commandId = "command",
                sessionId = "session",
                maxTotalTokens = 0,
                createdAtMs = 1,
            )
        }
        // A lease must never appear on a lease-free policy mutation.
        assertThrows(IllegalArgumentException::class.java) {
            PendingRemoteCommand.createRevokeRule(
                authorityBinding = ByteArray(32),
                pairedAtMs = 1,
                commandId = "command",
                sessionId = "session",
                ruleId = "9c1e04d548f7bda3",
                createdAtMs = 1,
            ).copy(controlEpoch = "1", controlToken = "A".repeat(43), controlExpiresAtMs = 2)
        }
        // No other operation may acquire a policy half.
        assertThrows(IllegalArgumentException::class.java) {
            command().copy(ruleId = "9c1e04d548f7bda3")
        }
        assertThrows(IllegalArgumentException::class.java) {
            command().copy(maxTotalTokens = 1_000)
        }
    }

    @Test
    fun sameKindDecisionRoundTripsAndAltersCommandIdentity() {
        // S-policy: ALLOW_SAME_KIND settles as allowed-once plus a minted rule,
        // so it is a DIFFERENT command than ALLOW_ONCE for the same revision.
        fun decision(kind: PendingApprovalDecision, id: String) = PendingRemoteCommand.createApprovalDecision(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = id,
            sessionId = "session-1",
            approvalId = "approval-1",
            approvalRevision = "revision-1",
            approvalDecision = kind,
            createdAtMs = 1_100,
        )

        val sameKind = decision(PendingApprovalDecision.ALLOW_SAME_KIND, "android-approval-sk")
        val decoded = PendingCommandCodec.decode(PendingCommandCodec.encode(sameKind))
        assertEquals(PendingApprovalDecision.ALLOW_SAME_KIND, decoded.approvalDecision)
        assertArrayEquals(sameKind.requestFingerprint, decoded.requestFingerprint)

        val once = decision(PendingApprovalDecision.ALLOW_ONCE, "android-approval-sk")
        assertFalse(sameKind.requestFingerprint.contentEquals(once.requestFingerprint))
    }

    @Test
    fun legacyV6SendInputMigratesKeepingItsCommittedAttachments() {
        // A v6 send with committed attachment ids must migrate to the current
        // format with the ids intact — dropping them would change the command.
        val ids = listOf("sha256:${"ab".repeat(32)}")
        val command = PendingRemoteCommand.create(
            authorityBinding = ByteArray(32) { it.toByte() },
            pairedAtMs = 1_000,
            commandId = "android-command-v6",
            sessionId = "session-1",
            text = "with image",
            controlEpoch = "7",
            controlToken = "A".repeat(43),
            controlExpiresAtMs = 31_000,
            createdAtMs = 1_100,
            attachmentIds = ids,
        ).withPhase(PendingCommandPhase.RECEIVED)
        // The v7 layout only ADDS arms; a v6 send payload is byte identical
        // apart from the version int, so patching it back exercises the real
        // migration path.
        val v6 = PendingCommandCodec.encode(command).also { it[3] = 6 }

        val migrated = PendingCommandCodec.decode(v6)
        assertEquals(ids, migrated.attachmentIds)
        assertEquals(PendingCommandPhase.RECEIVED, migrated.phase)
        assertArrayEquals(command.requestFingerprint, migrated.requestFingerprint)
    }

    private fun java.io.DataOutputStream.writeBounded(value: String) {
        val bytes = value.encodeToByteArray()
        writeInt(bytes.size)
        write(bytes)
    }

    private fun command(): PendingRemoteCommand = PendingRemoteCommand.create(
        authorityBinding = ByteArray(32) { it.toByte() },
        pairedAtMs = 1_000,
        commandId = "android-command-1",
        sessionId = "session-1",
        text = "persist before sending",
        controlEpoch = "7",
        controlToken = "A".repeat(43),
        controlExpiresAtMs = 31_000,
        createdAtMs = 1_100,
    )
}
