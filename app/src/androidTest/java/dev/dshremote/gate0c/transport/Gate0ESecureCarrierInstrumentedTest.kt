package dev.dshremote.gate0c.transport

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshremote.security.DeviceIdentityStore
import dev.dshremote.security.PairedHostStore
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Gate0ESecureCarrierInstrumentedTest {
    private val context by lazy { ApplicationProvider.getApplicationContext<android.content.Context>() }
    private val identityStore by lazy { DeviceIdentityStore(context) }
    private val hostStore by lazy { PairedHostStore(context) }
    private val offlineStore by lazy { OfflineProjectionStore(context) }
    private val pendingCommandStore by lazy { PendingCommandStore(context) }

    @Before
    fun cleanIdentity() {
        if (
            physicalCommandRecoveryStage() == "recover" ||
            physicalStopRecoveryStage() == "recover" ||
            physicalApprovalRecoveryStage() == "recover" ||
            physicalTwoDeviceStage() == "revoked-reconnect" ||
            physicalRepairStage() == "replay"
        ) return
        hostStore.delete()
        identityStore.delete()
        offlineStore.clear()
        pendingCommandStore.clear()
    }

    @After
    fun cleanUp() {
        if (
            physicalCommandRecoveryStage() == "stage" ||
            physicalStopRecoveryStage() == "stage" ||
            physicalApprovalRecoveryStage() == "stage" ||
            physicalTwoDeviceStage() == "first-controller" ||
            physicalRepairStage() == "stage"
        ) return
        hostStore.delete()
        identityStore.delete()
        offlineStore.clear()
        pendingCommandStore.clear()
    }

    @Test
    fun pairsPersistsReconnectsAndRejectsEffects() {
        val invitation = InstrumentationRegistry.getArguments().getString("pairingInvitation")
        assumeTrue("Gate 0E invitation argument not supplied", !invitation.isNullOrBlank())

        val pairingClient = Gate0CClient(context)
        try {
            pairingClient.pair(requireNotNull(invitation))
            val awaiting = waitForState(pairingClient) {
                it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION
            }
            assertTrue(awaiting.pairingVerificationCode?.matches(Regex("\\d{8}")) == true)

            val ready = waitForState(pairingClient) { it.phase == ConnectionPhase.READY }
            assertEquals("gate-0e-android-host", ready.hostInstanceId)
            assertEquals("android-secure-e2e", ready.sessionId)
            assertNotNull(hostStore.loadSole())

            pairingClient.runDisabledCommandProbe()
            val rejected = waitForState(pairingClient) { it.commandReceipts.isNotEmpty() }
                .commandReceipts.last()
            assertEquals("REJECTED", rejected.outcome)
            assertTrue(rejected.detail.contains("disabled", ignoreCase = true))
        } finally {
            pairingClient.close()
        }

        val reconnectingClient = Gate0CClient(context)
        try {
            reconnectingClient.connect()
            val reconnected = waitForState(reconnectingClient) { it.phase == ConnectionPhase.RECONCILED }
            assertEquals("gate-0e-android-host", reconnected.hostInstanceId)
            assertEquals("android-secure-e2e", reconnected.sessionId)
            assertTrue(reconnected.events.any { it.contains("Retained projection") })
        } finally {
            reconnectingClient.close()
        }
    }

    @Test
    fun recoversCommittedPairingAfterFinalReceiptIsLostAndClientRestarts() {
        val invitation = InstrumentationRegistry.getArguments().getString("settlementLossInvitation")
        assumeTrue("Gate 0E settlement-loss invitation argument not supplied", !invitation.isNullOrBlank())

        val interruptedClient = Gate0CClient(context)
        try {
            interruptedClient.pair(requireNotNull(invitation))
            waitForState(interruptedClient) { it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION }
            assertNull(hostStore.loadSole())
            assertNotNull(hostStore.loadPendingRecovery())
            val reconciling = waitForState(interruptedClient) {
                it.phase == ConnectionPhase.RECONCILING_PAIRING
            }
            assertTrue(reconciling.pairingRecoveryPending)
        } finally {
            interruptedClient.close()
        }

        assertNull(hostStore.loadSole())
        assertNotNull(hostStore.loadPendingRecovery())
        val restartedClient = Gate0CClient(context)
        try {
            restartedClient.connect()
            val ready = waitForState(restartedClient, timeoutSeconds = 35) {
                it.phase == ConnectionPhase.READY
            }
            assertEquals("gate-0e-android-host", ready.hostInstanceId)
            assertEquals("android-secure-e2e", ready.sessionId)
            assertNotNull(hostStore.loadSole())
            assertNull(hostStore.loadPendingRecovery())

            restartedClient.runDisabledCommandProbe()
            val rejected = waitForState(restartedClient) { it.commandReceipts.isNotEmpty() }
                .commandReceipts.last()
            assertEquals("REJECTED", rejected.outcome)
        } finally {
            restartedClient.close()
        }
    }

    @Test
    fun readsLoaderComposedColdDshSessionAndReconnects() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("loaderPairingInvitation")
        val expectedSessionId = arguments.getString("loaderExpectedSessionId")
        assumeTrue(
            "Loader-composed Gate 0E arguments not supplied",
            !invitation.isNullOrBlank() && !expectedSessionId.isNullOrBlank(),
        )

        val pairingClient = Gate0CClient(context)
        try {
            pairingClient.pair(requireNotNull(invitation))
            val awaiting = waitForState(pairingClient) {
                it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION
            }
            assertTrue(awaiting.pairingVerificationCode?.matches(Regex("\\d{8}")) == true)

            val ready = waitForState(pairingClient) { it.phase == ConnectionPhase.READY }
            assertEquals(expectedSessionId, ready.sessionId)
            assertTrue(ready.hostInstanceId?.isNotBlank() == true)
            assertTrue(ready.sessions.any { it.sessionId == expectedSessionId })
            assertTrue(ready.timeline.any {
                it.kind == TimelineKind.USER && it.text == "Loader-backed Android acceptance"
            })
            assertTrue(ready.timeline.any {
                it.kind == TimelineKind.ASSISTANT &&
                    it.final &&
                    it.text == "Real DSH cold history reached Android."
            })
            assertTrue(ready.timeline.any {
                it.kind == TimelineKind.TOOL_TERMINAL && it.callId == "loader-terminal-call"
            })
            val unsupported = ready.timeline.single {
                it.kind == TimelineKind.TOOL_UNSUPPORTED &&
                    it.callId == "loader-unsupported-call"
            }
            assertEquals("unregistered-secretless-tool", unsupported.toolName)
            assertNull(unsupported.boundedContent)
            assertTrue(ready.timeline.none {
                it.text.contains("must-not-cross-the-remote-boundary") ||
                    it.boundedContent?.contains("must-not-cross-the-remote-boundary") == true
            })

            pairingClient.runDisabledCommandProbe()
            val rejected = waitForState(pairingClient) { it.commandReceipts.isNotEmpty() }
                .commandReceipts.last()
            assertEquals("REJECTED", rejected.outcome)
        } finally {
            pairingClient.close()
        }

        val reconnectingClient = Gate0CClient(context)
        try {
            reconnectingClient.connect()
            val reconnected = waitForState(reconnectingClient) { it.phase == ConnectionPhase.RECONCILED }
            assertEquals(expectedSessionId, reconnected.sessionId)
            assertTrue(reconnected.timeline.any { it.text == "Real DSH cold history reached Android." })
            assertTrue(reconnected.timeline.any {
                it.kind == TimelineKind.TOOL_UNSUPPORTED &&
                    it.callId == "loader-unsupported-call" &&
                    it.boundedContent == null
            })
        } finally {
            reconnectingClient.close()
        }
    }

    @Test
    fun sendsDurableInstructionThroughLoaderComposedHost() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("commandLoaderPairingInvitation")
        val expectedSessionId = arguments.getString("commandLoaderExpectedSessionId")
        assumeTrue(
            "Loader-composed command arguments not supplied",
            !invitation.isNullOrBlank() && !expectedSessionId.isNullOrBlank(),
        )
        val instruction = "Commit this instruction exactly once from Android"

        val client = Gate0CClient(context)
        try {
            Log.i(COMMAND_TEST_TAG, "pair_start")
            client.pair(requireNotNull(invitation))
            waitForState(client) { it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION }
            Log.i(COMMAND_TEST_TAG, "pair_awaiting_confirmation")
            val ready = waitForState(client, timeoutSeconds = 30) { it.phase == ConnectionPhase.READY }
            Log.i(COMMAND_TEST_TAG, "snapshot_ready")
            assertEquals(expectedSessionId, ready.sessionId)
            assertEquals(71uL, ready.grantedCapabilities)

            client.updateLocalDraft(instruction)
            Log.i(COMMAND_TEST_TAG, "control_request_start")
            client.acquireControl()
            Log.i(COMMAND_TEST_TAG, "control_request_written")
            val controlled = waitForState(client) { it.controlLease?.isUsable() == true }
            Log.i(COMMAND_TEST_TAG, "control_acquired")
            assertEquals(expectedSessionId, controlled.controlLease?.sessionId)

            Log.i(COMMAND_TEST_TAG, "command_send_start")
            client.sendDraft()
            Log.i(COMMAND_TEST_TAG, "command_send_returned")
            val committed = waitForState(client, timeoutSeconds = 30) { state ->
                state.commandReceipts.any { it.outcome == "COMMITTED" }
            }
            Log.i(COMMAND_TEST_TAG, "command_committed")
            assertTrue(committed.commandReceipts.any {
                it.outcome == "COMMITTED" && !it.replayed
            })
            assertNull(committed.pendingCommand)
            assertEquals("", committed.localDraft)
            val projected = waitForState(client) { state ->
                state.timeline.count { it.kind == TimelineKind.USER && it.text == instruction } == 1
            }
            Log.i(COMMAND_TEST_TAG, "projection_observed")
            assertEquals(
                1,
                projected.timeline.count { it.kind == TimelineKind.USER && it.text == instruction },
            )
        } finally {
            Log.i(COMMAND_TEST_TAG, "client_close_start")
            client.close()
            Log.i(COMMAND_TEST_TAG, "client_close_complete")
        }
    }

    @Test
    fun settlesProtectedApprovalThroughLoaderComposedHost() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("approvalLoaderPairingInvitation")
        val expectedSessionId = arguments.getString("approvalLoaderExpectedSessionId")
        assumeTrue(
            "Loader-composed approval arguments not supplied",
            !invitation.isNullOrBlank() && !expectedSessionId.isNullOrBlank(),
        )

        val client = Gate0CClient(context)
        try {
            Log.i(COMMAND_TEST_TAG, "approval_pair_start")
            client.pair(requireNotNull(invitation))
            waitForState(client) { it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION }
            val ready = waitForState(client, timeoutSeconds = 30) {
                it.phase == ConnectionPhase.READY && it.approvals.size == 1
            }
            Log.i(COMMAND_TEST_TAG, "approval_snapshot_ready")
            assertEquals(expectedSessionId, ready.sessionId)
            assertEquals(19uL, ready.grantedCapabilities)
            val approval = ready.approvals.single()
            assertEquals("acceptance.protected.write", approval.toolName)
            assertEquals(ApprovalRisk.DESTRUCTIVE, approval.evidence.risk)
            assertEquals("android-loader-host-policy", approval.evidence.source)
            assertEquals(listOf("acceptance/protected-effect.txt"), approval.evidence.resources)
            assertTrue(approval.allowOnce)
            assertTrue(approval.deny)

            client.decideApproval(approval.approvalId, PendingApprovalDecision.ALLOW_ONCE)
            Log.i(COMMAND_TEST_TAG, "approval_decision_written")
            val committed = waitForState(client, timeoutSeconds = 30) { state ->
                state.commandReceipts.any { it.outcome == "COMMITTED" } &&
                    state.approvals.none { it.approvalId == approval.approvalId }
            }
            assertNull(committed.pendingCommand)
            assertTrue(committed.commandReceipts.any {
                it.outcome == "COMMITTED" && !it.replayed
            })
            Log.i(COMMAND_TEST_TAG, "approval_settled")
        } finally {
            client.close()
        }
    }

    @Test
    fun reconcilesSameApprovalAfterTerminalLossAndHostRestart() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("approvalRecoveryPairingInvitation")
        val expectedSessionId = arguments.getString("approvalRecoveryExpectedSessionId")
        assumeTrue(
            "Loader-composed approval recovery arguments not supplied",
            !invitation.isNullOrBlank() && !expectedSessionId.isNullOrBlank(),
        )
        lateinit var commandId: String

        val interrupted = Gate0CClient(context)
        try {
            interrupted.pair(requireNotNull(invitation))
            waitForState(interrupted) { it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION }
            val ready = waitForState(interrupted, timeoutSeconds = 30) {
                it.phase == ConnectionPhase.READY && it.approvals.size == 1
            }
            assertEquals(expectedSessionId, ready.sessionId)
            assertEquals(19uL, ready.grantedCapabilities)
            val approval = ready.approvals.single()
            interrupted.decideApproval(approval.approvalId, PendingApprovalDecision.ALLOW_ONCE)
            val received = waitForState(interrupted) {
                it.pendingCommand?.operation == PendingCommandOperation.DECIDE_APPROVAL &&
                    it.pendingCommand.progress == PendingCommandProgress.RECEIVED
            }
            commandId = requireNotNull(received.pendingCommand).commandId
            assertTrue(received.commandReceipts.any {
                it.commandId == commandId && it.outcome == "RECEIVED"
            })
            Log.i(COMMAND_TEST_TAG, "approval_recovery_received")
        } finally {
            interrupted.close()
        }

        // The runner settles the protected operation after this client closes and
        // restarts the complete Loader before the same protected ID is reconciled.
        Thread.sleep(3_000)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        var recovering: Gate0CClient? = null
        var committed: Gate0CState? = null
        while (System.nanoTime() < deadline && committed == null) {
            val candidate = Gate0CClient(context)
            candidate.connect()
            val attemptDeadline = minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))
            while (System.nanoTime() < attemptDeadline) {
                val state = candidate.state.value
                if (state.commandReceipts.any {
                        it.commandId == commandId && it.outcome == "COMMITTED" && it.replayed
                    }
                ) {
                    recovering = candidate
                    committed = state
                    break
                }
                if (state.phase == ConnectionPhase.FAILED || state.phase == ConnectionPhase.CLOSED) break
                Thread.sleep(25)
            }
            if (committed == null) {
                candidate.close()
                Thread.sleep(250)
            }
        }
        try {
            val recovered = requireNotNull(committed) { "approval did not reconcile after Host restart" }
            assertNull(recovered.pendingCommand)
            assertTrue(recovered.approvals.isEmpty())
            assertTrue(recovered.commandReceipts.any {
                it.commandId == commandId && it.outcome == "COMMITTED" && it.replayed
            })
            Log.i(COMMAND_TEST_TAG, "approval_recovery_replayed_committed")
        } finally {
            recovering?.close()
        }
    }

    @Test
    fun stagesReceivedApprovalForPhysicalProcessReclaim() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("approvalProcessDeathPairingInvitation")
        val expectedSessionId = arguments.getString("approvalProcessDeathExpectedSessionId")
        assumeTrue(
            "Physical approval-recovery staging arguments not supplied",
            physicalApprovalRecoveryStage() == "stage" &&
                !invitation.isNullOrBlank() &&
                !expectedSessionId.isNullOrBlank(),
        )

        val client = Gate0CClient(context)
        client.pair(requireNotNull(invitation))
        waitForState(client) { it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION }
        val ready = waitForState(client, timeoutSeconds = 30) {
            it.phase == ConnectionPhase.READY && it.approvals.size == 1
        }
        assertEquals(expectedSessionId, ready.sessionId)
        assertEquals(19uL, ready.grantedCapabilities)
        val approval = ready.approvals.single()
        client.decideApproval(approval.approvalId, PendingApprovalDecision.ALLOW_ONCE)
        val received = waitForState(client) {
            it.pendingCommand?.operation == PendingCommandOperation.DECIDE_APPROVAL &&
                it.pendingCommand.progress == PendingCommandProgress.RECEIVED
        }
        val pending = requireNotNull(received.pendingCommand)
        val persisted = protectedPendingCommand()
        try {
            assertEquals(pending.commandId, persisted.commandId)
            assertEquals(PendingCommandPhase.RECEIVED, persisted.phase)
        } finally {
            persisted.authorityBinding.fill(0)
            persisted.requestFingerprint.fill(0)
        }
        Log.i(
            COMMAND_TEST_TAG,
            "approval_process_death_received command_id=${pending.commandId} approval_id=${pending.approvalId}",
        )

        // Kill the owner directly after the durable marker. Returning or closing
        // would exercise orderly teardown instead of OS process loss.
        android.os.Process.killProcess(android.os.Process.myPid())
        throw AssertionError("Android process survived killProcess")
    }

    @Test
    fun reconcilesApprovalAfterPhysicalProcessReclaim() {
        val expectedSessionId = InstrumentationRegistry.getArguments()
            .getString("approvalProcessDeathExpectedSessionId")
        assumeTrue(
            "Physical approval-recovery continuation arguments not supplied",
            physicalApprovalRecoveryStage() == "recover" && !expectedSessionId.isNullOrBlank(),
        )

        val stored = protectedPendingCommand()
        val commandId = stored.commandId
        try {
            assertEquals(PendingCommandOperation.DECIDE_APPROVAL, stored.operation)
            assertEquals(PendingCommandPhase.RECEIVED, stored.phase)
            assertEquals(expectedSessionId, stored.sessionId)
            assertNotNull(stored.approvalId)
            assertNotNull(stored.approvalRevision)
        } finally {
            stored.authorityBinding.fill(0)
            stored.requestFingerprint.fill(0)
        }

        val client = Gate0CClient(context)
        try {
            client.connect()
            val recovered = waitForState(client, timeoutSeconds = 45) { state ->
                state.commandReceipts.any {
                    it.commandId == commandId && it.outcome == "COMMITTED" && it.replayed
                }
            }
            assertNull(recovered.pendingCommand)
            assertEquals(expectedSessionId, recovered.sessionId)
            assertTrue(recovered.approvals.isEmpty())
            assertEquals(
                1,
                recovered.commandReceipts.count {
                    it.commandId == commandId && it.outcome == "COMMITTED" && it.replayed
                },
            )
            Log.i(COMMAND_TEST_TAG, "approval_process_death_replayed_committed command_id=$commandId")
        } finally {
            client.close()
        }
    }

    @Test
    fun rejectsApprovalAfterPhysicalRevocation() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("approvalRevocationPairingInvitation")
        val expectedSessionId = arguments.getString("approvalRevocationExpectedSessionId")
        assumeTrue(
            "Physical approval-revocation arguments not supplied",
            !invitation.isNullOrBlank() && !expectedSessionId.isNullOrBlank(),
        )

        val client = Gate0CClient(context)
        try {
            client.pair(requireNotNull(invitation))
            waitForState(client) { it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION }
            val ready = waitForState(client, timeoutSeconds = 30) {
                it.phase == ConnectionPhase.READY && it.approvals.size == 1
            }
            assertEquals(expectedSessionId, ready.sessionId)
            assertEquals(19uL, ready.grantedCapabilities)
            val approval = ready.approvals.single()
            Log.i(COMMAND_TEST_TAG, "approval_revocation_pending approval_id=${approval.approvalId}")

            val revoked = waitForStateAllowingFailure(client, timeoutSeconds = 45) { state ->
                state.phase == ConnectionPhase.FAILED || state.phase == ConnectionPhase.OFFLINE
            }
            val receiptsBefore = revoked.commandReceipts
            client.decideApproval(approval.approvalId, PendingApprovalDecision.ALLOW_ONCE)
            Thread.sleep(300)
            val blocked = client.state.value
            assertNull(blocked.pendingCommand)
            assertEquals(receiptsBefore, blocked.commandReceipts)
            assertTrue(blocked.commandReceipts.none { it.outcome == "COMMITTED" })
            Log.i(COMMAND_TEST_TAG, "approval_revoked_device_blocked")
        } finally {
            client.close()
        }
    }

    @Test
    fun settlesApprovalBeforePhysicalRepairCeremony() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("approvalRepairPairingInvitation")
        val expectedSessionId = arguments.getString("approvalRepairExpectedSessionId")
        assumeTrue(
            "Physical approval-repair arguments not supplied",
            physicalRepairStage() == "stage" &&
                !invitation.isNullOrBlank() &&
                !expectedSessionId.isNullOrBlank(),
        )

        val client = Gate0CClient(context)
        try {
            client.pair(requireNotNull(invitation))
            waitForState(client) { it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION }
            val ready = waitForState(client, timeoutSeconds = 30) {
                it.phase == ConnectionPhase.READY && it.approvals.size == 1
            }
            assertEquals(expectedSessionId, ready.sessionId)
            assertEquals(19uL, ready.grantedCapabilities)
            val approval = ready.approvals.single()
            assertEquals("acceptance.protected.write", approval.toolName)
            assertEquals(ApprovalRisk.DESTRUCTIVE, approval.evidence.risk)

            client.decideApproval(approval.approvalId, PendingApprovalDecision.ALLOW_ONCE)
            val committed = waitForState(client, timeoutSeconds = 30) { state ->
                state.commandReceipts.any { it.outcome == "COMMITTED" && !it.replayed } &&
                    state.approvals.none { it.approvalId == approval.approvalId }
            }
            val receipt = committed.commandReceipts.single { it.outcome == "COMMITTED" && !it.replayed }
            assertNull(committed.pendingCommand)
            Log.i(
                COMMAND_TEST_TAG,
                "approval_repair_settled command_id=${receipt.commandId} " +
                    "approval_id=${approval.approvalId} revision=${approval.revision}",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun rejectsReplayedApprovalCommandAfterRepairCeremony() {
        val arguments = InstrumentationRegistry.getArguments()
        val commandId = arguments.getString("repairReplayCommandId")
        val approvalId = arguments.getString("repairReplayApprovalId")
        val approvalRevision = arguments.getString("repairReplayApprovalRevision")
        val expectedSessionId = arguments.getString("repairReplayExpectedSessionId")
        assumeTrue(
            "Physical repair-replay arguments not supplied",
            physicalRepairStage() == "replay" &&
                !commandId.isNullOrBlank() &&
                !approvalId.isNullOrBlank() &&
                !approvalRevision.isNullOrBlank() &&
                !expectedSessionId.isNullOrBlank(),
        )

        val pairedHost = requireNotNull(hostStore.loadSole())
        val identity = identityStore.loadOrCreate()
        val binding = try {
            val devicePublicKey = identity.publicKey
            try {
                OfflineProjectionStore.hostBinding(
                    pairedHost.hostPublicKey,
                    devicePublicKey,
                    pairedHost.capabilities,
                )
            } finally {
                devicePublicKey.fill(0)
            }
        } finally {
            identity.close()
        }
        val replay = try {
            PendingRemoteCommand.createApprovalDecision(
                authorityBinding = binding,
                pairedAtMs = pairedHost.pairedAtMs,
                commandId = requireNotNull(commandId),
                sessionId = requireNotNull(expectedSessionId),
                approvalId = requireNotNull(approvalId),
                approvalRevision = requireNotNull(approvalRevision),
                approvalDecision = PendingApprovalDecision.ALLOW_ONCE,
                createdAtMs = System.currentTimeMillis(),
            )
        } finally {
            binding.fill(0)
            pairedHost.hostPublicKey.fill(0)
        }
        try {
            pendingCommandStore.save(replay)
        } finally {
            replay.authorityBinding.fill(0)
            replay.requestFingerprint.fill(0)
        }
        Log.i(COMMAND_TEST_TAG, "approval_repair_replay_staged command_id=$commandId")

        val client = Gate0CClient(context)
        try {
            client.connect()
            val rejected = waitForState(client, timeoutSeconds = 45) { state ->
                state.commandReceipts.any { it.commandId == commandId && it.outcome == "REJECTED" }
            }
            val receipt = rejected.commandReceipts.single { it.commandId == commandId }
            assertTrue(receipt.errorCode.contains("COMMAND_ID_REUSED"))
            assertNull(rejected.pendingCommand)
            assertEquals(expectedSessionId, rejected.sessionId)
            assertEquals(19uL, rejected.grantedCapabilities)
            assertTrue(rejected.approvals.isEmpty())
            assertTrue(
                rejected.commandReceipts.none {
                    it.commandId == commandId && it.outcome == "COMMITTED"
                },
            )
            Log.i(
                COMMAND_TEST_TAG,
                "approval_repair_replay_rejected command_id=$commandId error_code=${receipt.errorCode}",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun stopsExactActiveTurnThroughLoaderComposedHost() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("stopLoaderPairingInvitation")
        val expectedSessionId = arguments.getString("stopLoaderExpectedSessionId")
        assumeTrue(
            "Loader-composed Stop arguments not supplied",
            !invitation.isNullOrBlank() && !expectedSessionId.isNullOrBlank(),
        )

        val client = Gate0CClient(context)
        try {
            Log.i(COMMAND_TEST_TAG, "stop_pair_start")
            client.pair(requireNotNull(invitation))
            waitForState(client) { it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION }
            Log.i(COMMAND_TEST_TAG, "stop_pair_awaiting_confirmation")
            val ready = waitForState(client, timeoutSeconds = 30) { it.phase == ConnectionPhase.READY }
            Log.i(COMMAND_TEST_TAG, "stop_snapshot_ready")
            assertEquals(expectedSessionId, ready.sessionId)
            assertEquals(79uL, ready.grantedCapabilities)

            client.acquireControl()
            Log.i(COMMAND_TEST_TAG, "stop_control_request_written")
            waitForState(client) { it.controlLease?.isUsable() == true }
            Log.i(COMMAND_TEST_TAG, "stop_control_acquired")
            client.updateLocalDraft("Run until Android stops this exact turn")
            client.sendDraft()
            Log.i(COMMAND_TEST_TAG, "stop_start_command_written")
            waitForState(client, timeoutSeconds = 30) { state ->
                state.commandReceipts.any { it.outcome == "COMMITTED" } &&
                    state.sessionRunning == true &&
                    state.activityRevision?.let { it > 0 } == true
            }
            Log.i(COMMAND_TEST_TAG, "stop_target_running")

            client.stopActive()
            Log.i(COMMAND_TEST_TAG, "stop_request_written")
            val requested = waitForState(client) {
                it.pendingCommand?.operation == PendingCommandOperation.STOP &&
                    it.pendingCommand.progress == PendingCommandProgress.REQUESTED
            }
            val targetRevision = requireNotNull(requested.pendingCommand?.expectedActivityRevision)
            assertEquals(targetRevision, requested.activityRevision)
            Log.i(COMMAND_TEST_TAG, "stop_requested")

            val stopped = waitForState(client, timeoutSeconds = 30) { state ->
                state.commandReceipts.any { it.outcome == "STOPPED" }
            }
            assertNull(stopped.pendingCommand)
            assertEquals(false, stopped.sessionRunning)
            assertTrue(stopped.commandReceipts.any { it.outcome == "STOPPED" && !it.replayed })
            Log.i(COMMAND_TEST_TAG, "stop_settled")
        } finally {
            Log.i(COMMAND_TEST_TAG, "stop_client_close")
            client.close()
        }
    }

    @Test
    fun stagesRequestedStopForPhysicalProcessReclaim() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("stopProcessDeathPairingInvitation")
        val expectedSessionId = arguments.getString("stopProcessDeathExpectedSessionId")
        assumeTrue(
            "Physical Stop-recovery staging arguments not supplied",
            physicalStopRecoveryStage() == "stage" &&
                !invitation.isNullOrBlank() &&
                !expectedSessionId.isNullOrBlank(),
        )

        val client = Gate0CClient(context)
        client.pair(requireNotNull(invitation))
        waitForState(client) { it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION }
        val ready = waitForState(client, timeoutSeconds = 30) { it.phase == ConnectionPhase.READY }
        assertEquals(expectedSessionId, ready.sessionId)
        assertEquals(79uL, ready.grantedCapabilities)
        client.acquireControl()
        waitForState(client) { it.controlLease?.isUsable() == true }
        client.updateLocalDraft("Stop this exact turn across Android process reclaim")
        client.sendDraft()
        waitForState(client, timeoutSeconds = 30) { state ->
            state.commandReceipts.any { it.outcome == "COMMITTED" } &&
                state.sessionRunning == true &&
                state.activityRevision?.let { it > 0 } == true
        }

        client.stopActive()
        val requested = waitForState(client) {
            it.pendingCommand?.operation == PendingCommandOperation.STOP &&
                it.pendingCommand.progress == PendingCommandProgress.REQUESTED
        }
        val pending = requireNotNull(requested.pendingCommand)
        Log.i(
            COMMAND_TEST_TAG,
            "stop_process_death_requested command_id=${pending.commandId} " +
                "revision=${pending.expectedActivityRevision}",
        )

        // The acceptance owner force-stops the package after this marker. Returning
        // or closing would exercise an orderly teardown instead of OS process loss.
        while (true) Thread.sleep(1_000)
    }

    @Test
    fun reconcilesRequestedStopAfterPhysicalProcessReclaim() {
        val expectedSessionId = InstrumentationRegistry.getArguments()
            .getString("stopProcessDeathExpectedSessionId")
        assumeTrue(
            "Physical Stop-recovery continuation arguments not supplied",
            physicalStopRecoveryStage() == "recover" && !expectedSessionId.isNullOrBlank(),
        )

        val pairedHost = requireNotNull(hostStore.loadSole())
        val identity = identityStore.loadOrCreate()
        val binding = try {
            val devicePublicKey = identity.publicKey
            try {
                OfflineProjectionStore.hostBinding(
                    pairedHost.hostPublicKey,
                    devicePublicKey,
                    pairedHost.capabilities,
                )
            } finally {
                devicePublicKey.fill(0)
            }
        } finally {
            identity.close()
        }
        val stored = try {
            requireNotNull(pendingCommandStore.load(binding, pairedHost.pairedAtMs).command)
        } finally {
            binding.fill(0)
            pairedHost.hostPublicKey.fill(0)
        }
        val commandId = stored.commandId
        try {
            assertEquals(PendingCommandOperation.STOP, stored.operation)
            assertEquals(PendingCommandPhase.REQUESTED, stored.phase)
            assertEquals(expectedSessionId, stored.sessionId)
            assertTrue(requireNotNull(stored.expectedActivityRevision) > 0)
        } finally {
            stored.authorityBinding.fill(0)
            stored.requestFingerprint.fill(0)
        }

        val client = Gate0CClient(context)
        try {
            client.connect()
            val recovered = waitForState(client, timeoutSeconds = 45) { state ->
                state.commandReceipts.any {
                    it.commandId == commandId && it.outcome == "STOPPED" && it.replayed
                }
            }
            assertNull(recovered.pendingCommand)
            assertEquals(expectedSessionId, recovered.sessionId)
            assertEquals(false, recovered.sessionRunning)
            assertEquals(
                1,
                recovered.commandReceipts.count {
                    it.commandId == commandId && it.outcome == "STOPPED" && it.replayed
                },
            )
            Log.i(COMMAND_TEST_TAG, "stop_process_death_replayed_stopped command_id=$commandId")
        } finally {
            client.close()
        }
    }

    @Test
    fun blocksExpiredStopBeforeEffect() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("stopExpiryPairingInvitation")
        val expectedSessionId = arguments.getString("stopExpiryExpectedSessionId")
        assumeTrue(
            "Physical Stop-expiry arguments not supplied",
            !invitation.isNullOrBlank() && !expectedSessionId.isNullOrBlank(),
        )

        val client = Gate0CClient(context)
        try {
            client.pair(requireNotNull(invitation))
            waitForState(client) { it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION }
            val ready = waitForState(client, timeoutSeconds = 30) { it.phase == ConnectionPhase.READY }
            assertEquals(expectedSessionId, ready.sessionId)
            assertEquals(79uL, ready.grantedCapabilities)
            client.acquireControl()
            val controlled = waitForState(client) { it.controlLease?.isUsable() == true }
            val firstLease = requireNotNull(controlled.controlLease)
            Log.i(
                COMMAND_TEST_TAG,
                "stop_expiry_control_acquired expires_at=${firstLease.expiresAtMs} " +
                    "now=${System.currentTimeMillis()}",
            )
            client.updateLocalDraft("Keep this turn active across control expiry")
            client.sendDraft()
            waitForState(client, timeoutSeconds = 30) { state ->
                state.commandReceipts.any { it.outcome == "COMMITTED" } &&
                state.sessionRunning == true &&
                    state.activityRevision?.let { it > 0 } == true
            }
            Log.i(COMMAND_TEST_TAG, "stop_expiry_target_running")

            val untilExpiryMs = (firstLease.expiresAtMs - System.currentTimeMillis() + 250)
                .coerceIn(250, 35_000)
            Log.i(COMMAND_TEST_TAG, "stop_expiry_wait_ms=$untilExpiryMs")
            Thread.sleep(untilExpiryMs)
            Log.i(COMMAND_TEST_TAG, "stop_expiry_wait_complete now=${System.currentTimeMillis()}")
            assertTrue(client.state.value.controlLease?.isUsable() == false)
            val receiptsBefore = client.state.value.commandReceipts
            client.stopActive()
            Log.i(COMMAND_TEST_TAG, "stop_expiry_blocked_attempt_returned")
            Thread.sleep(300)
            val blocked = client.state.value
            assertNull(blocked.pendingCommand)
            assertEquals(receiptsBefore, blocked.commandReceipts)
            assertEquals(true, blocked.sessionRunning)
            Log.i(COMMAND_TEST_TAG, "stop_expired_control_blocked")
        } finally {
            client.close()
        }
    }

    @Test
    fun rejectsStopAfterPhysicalRevocation() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("stopRevocationPairingInvitation")
        val expectedSessionId = arguments.getString("stopRevocationExpectedSessionId")
        assumeTrue(
            "Physical Stop-revocation arguments not supplied",
            !invitation.isNullOrBlank() && !expectedSessionId.isNullOrBlank(),
        )

        val client = Gate0CClient(context)
        try {
            client.pair(requireNotNull(invitation))
            waitForState(client) { it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION }
            val ready = waitForState(client, timeoutSeconds = 30) { it.phase == ConnectionPhase.READY }
            assertEquals(expectedSessionId, ready.sessionId)
            assertEquals(79uL, ready.grantedCapabilities)
            client.acquireControl()
            waitForState(client) { it.controlLease?.isUsable() == true }
            client.updateLocalDraft("Keep this exact turn active while the device is revoked")
            client.sendDraft()
            waitForState(client, timeoutSeconds = 30) { state ->
                state.commandReceipts.any { it.outcome == "COMMITTED" } &&
                    state.sessionRunning == true &&
                    state.activityRevision?.let { it > 0 } == true
            }
            Log.i(COMMAND_TEST_TAG, "stop_revocation_target_running")

            val revoked = waitForStateAllowingFailure(client, timeoutSeconds = 45) { state ->
                state.phase == ConnectionPhase.FAILED || state.phase == ConnectionPhase.OFFLINE
            }
            assertTrue(revoked.phase != ConnectionPhase.READY && revoked.phase != ConnectionPhase.RECONCILED)
            val receiptsBefore = revoked.commandReceipts
            client.stopActive()
            Thread.sleep(300)
            val blocked = client.state.value
            assertNull(blocked.pendingCommand)
            assertEquals(receiptsBefore, blocked.commandReceipts)
            assertTrue(blocked.commandReceipts.none { it.outcome == "STOPPED" })
            Log.i(COMMAND_TEST_TAG, "stop_revoked_device_blocked")
        } finally {
            client.close()
        }
    }

    @Test
    fun reconcilesSameCommandAfterHostDropsTerminalAndRestarts() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("commandRecoveryPairingInvitation")
        val expectedSessionId = arguments.getString("commandRecoveryExpectedSessionId")
        assumeTrue(
            "Loader-composed recovery arguments not supplied",
            !invitation.isNullOrBlank() && !expectedSessionId.isNullOrBlank(),
        )
        val instruction = "Recover this exact Android command after Host restart"
        lateinit var commandId: String

        val interrupted = Gate0CClient(context)
        try {
            Log.i(COMMAND_TEST_TAG, "recovery_pair_start")
            interrupted.pair(requireNotNull(invitation))
            waitForState(interrupted) { it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION }
            val ready = waitForState(interrupted, timeoutSeconds = 30) { it.phase == ConnectionPhase.READY }
            Log.i(COMMAND_TEST_TAG, "recovery_ready")
            assertEquals(expectedSessionId, ready.sessionId)
            assertEquals(71uL, ready.grantedCapabilities)
            interrupted.updateLocalDraft(instruction)
            Thread.sleep(250)
            Log.i(COMMAND_TEST_TAG, "recovery_control_request_start")
            interrupted.acquireControl()
            Log.i(COMMAND_TEST_TAG, "recovery_control_request_written")
            waitForState(interrupted) { it.controlLease?.isUsable() == true }
            Log.i(COMMAND_TEST_TAG, "recovery_control_acquired")
            interrupted.sendDraft()
            val received = waitForState(interrupted) {
                it.pendingCommand?.progress == PendingCommandProgress.RECEIVED
            }
            Log.i(COMMAND_TEST_TAG, "recovery_received")
            commandId = requireNotNull(received.pendingCommand).commandId
            assertTrue(received.commandReceipts.any {
                it.commandId == commandId && it.outcome == "RECEIVED"
            })
        } finally {
            // Lose the terminal frame after RECEIVED while the Host owner continues to durability.
            interrupted.close()
            Log.i(COMMAND_TEST_TAG, "recovery_interrupted_client_closed")
        }

        val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        var recovering: Gate0CClient? = null
        var committed: Gate0CState? = null
        while (System.nanoTime() < recoveryDeadline && committed == null) {
            val candidate = Gate0CClient(context)
            Log.i(COMMAND_TEST_TAG, "recovery_reconnect_attempt")
            candidate.connect()
            val attemptDeadline = minOf(
                recoveryDeadline,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
            )
            while (System.nanoTime() < attemptDeadline) {
                val state = candidate.state.value
                if (state.commandReceipts.any { it.outcome == "COMMITTED" && it.replayed }) {
                    Log.i(COMMAND_TEST_TAG, "recovery_replayed_committed")
                    recovering = candidate
                    committed = state
                    break
                }
                if (
                    state.phase == ConnectionPhase.FAILED ||
                    state.phase == ConnectionPhase.CLOSED ||
                    (state.phase == ConnectionPhase.OFFLINE && state.events.isNotEmpty())
                ) {
                    break
                }
                Thread.sleep(25)
            }
            if (committed == null) {
                candidate.close()
                Thread.sleep(250)
            }
        }

        assertNotNull("Recovery never reached a replayed COMMITTED outcome", recovering)
        val recoveredClient = requireNotNull(recovering)
        try {
            val recovered = requireNotNull(committed)
            assertNull(recovered.pendingCommand)
            assertEquals("", recovered.localDraft)
            assertEquals(
                1,
                recovered.commandReceipts.count {
                    it.commandId == commandId && it.outcome == "COMMITTED" && it.replayed
                },
            )
        } finally {
            recoveredClient.close()
        }

        val projectedClient = Gate0CClient(context)
        try {
            Log.i(COMMAND_TEST_TAG, "recovery_projection_connect")
            projectedClient.connect()
            val projected = waitForState(projectedClient, timeoutSeconds = 30) { state ->
                state.sessionId == expectedSessionId &&
                (state.phase == ConnectionPhase.READY || state.phase == ConnectionPhase.RECONCILED)
            }
            assertTrue(
                "A durable inbox command must never project more than once after recovery",
                projected.timeline.count { it.kind == TimelineKind.USER && it.text == instruction } <= 1,
            )
            Log.i(COMMAND_TEST_TAG, "recovery_projection_restored")
        } finally {
            projectedClient.close()
        }
    }

    @Test
    fun stagesReceivedCommandForPhysicalProcessReclaim() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("commandProcessDeathPairingInvitation")
        val expectedSessionId = arguments.getString("commandProcessDeathExpectedSessionId")
        assumeTrue(
            "Physical command-recovery staging arguments not supplied",
            physicalCommandRecoveryStage() == "stage" &&
                !invitation.isNullOrBlank() &&
                !expectedSessionId.isNullOrBlank(),
        )
        val instruction = "Recover this exact command after Android process reclaim"
        val client = Gate0CClient(context)
        client.pair(requireNotNull(invitation))
        waitForState(client) { it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION }
        val ready = waitForState(client, timeoutSeconds = 30) { it.phase == ConnectionPhase.READY }
        assertEquals(expectedSessionId, ready.sessionId)
        assertEquals(71uL, ready.grantedCapabilities)
        client.updateLocalDraft(instruction)
        client.acquireControl()
        waitForState(client) { it.controlLease?.isUsable() == true }
        client.sendDraft()
        val received = waitForState(client) {
            it.pendingCommand?.operation == PendingCommandOperation.SEND_INPUT &&
                it.pendingCommand.progress == PendingCommandProgress.RECEIVED
        }
        val commandId = requireNotNull(received.pendingCommand).commandId
        Log.i(COMMAND_TEST_TAG, "process_death_received command_id=$commandId")

        // The physical acceptance owner force-stops the package after observing the
        // marker. Returning or closing here would only prove an orderly client teardown.
        while (true) Thread.sleep(1_000)
    }

    @Test
    fun reconcilesReceivedCommandAfterPhysicalProcessReclaim() {
        val expectedSessionId = InstrumentationRegistry.getArguments()
            .getString("commandProcessDeathExpectedSessionId")
        assumeTrue(
            "Physical command-recovery continuation arguments not supplied",
            physicalCommandRecoveryStage() == "recover" && !expectedSessionId.isNullOrBlank(),
        )

        val pairedHost = requireNotNull(hostStore.loadSole())
        val identity = identityStore.loadOrCreate()
        val binding = try {
            val devicePublicKey = identity.publicKey
            try {
                OfflineProjectionStore.hostBinding(
                    pairedHost.hostPublicKey,
                    devicePublicKey,
                    pairedHost.capabilities,
                )
            } finally {
                devicePublicKey.fill(0)
            }
        } finally {
            identity.close()
        }
        val stored = try {
            requireNotNull(pendingCommandStore.load(binding, pairedHost.pairedAtMs).command)
        } finally {
            binding.fill(0)
            pairedHost.hostPublicKey.fill(0)
        }
        val commandId = stored.commandId
        try {
            assertEquals(PendingCommandOperation.SEND_INPUT, stored.operation)
            assertEquals(PendingCommandPhase.RECEIVED, stored.phase)
            assertEquals(expectedSessionId, stored.sessionId)
            assertEquals(
                "Recover this exact command after Android process reclaim",
                stored.text,
            )
        } finally {
            stored.authorityBinding.fill(0)
            stored.requestFingerprint.fill(0)
        }

        val client = Gate0CClient(context)
        try {
            client.connect()
            val recovered = waitForState(client, timeoutSeconds = 45) { state ->
                state.commandReceipts.any {
                    it.commandId == commandId && it.outcome == "COMMITTED" && it.replayed
                }
            }
            assertNull(recovered.pendingCommand)
            assertEquals("", recovered.localDraft)
            assertEquals(expectedSessionId, recovered.sessionId)
            assertEquals(
                1,
                recovered.commandReceipts.count {
                    it.commandId == commandId && it.outcome == "COMMITTED" && it.replayed
                },
            )
            Log.i(COMMAND_TEST_TAG, "process_death_replayed_committed command_id=$commandId")
        } finally {
            client.close()
        }
    }

    @Test
    fun holdsFirstDeviceLeaseForPhysicalTwoDeviceAcceptance() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("twoDeviceFirstInvitation")
        val expectedSessionId = arguments.getString("twoDeviceExpectedSessionId")
        assumeTrue(
            "First-device lease acceptance arguments not supplied",
            physicalTwoDeviceStage() == "first-controller" &&
                !invitation.isNullOrBlank() &&
                !expectedSessionId.isNullOrBlank(),
        )

        val controller = Gate0CClient(context)
        try {
            controller.pair(requireNotNull(invitation))
            waitForState(controller) { it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION }
            val ready = waitForState(controller, timeoutSeconds = 30) {
                it.phase == ConnectionPhase.READY
            }
            assertEquals(expectedSessionId, ready.sessionId)
            assertEquals(71uL, ready.grantedCapabilities)
            controller.acquireControl()
            waitForState(controller) { it.controlLease?.isUsable() == true }
            Log.i(COMMAND_TEST_TAG, "two_device_first_control_acquired")
            while (true) Thread.sleep(1_000)
        } catch (error: Throwable) {
            controller.close()
            throw error
        }
    }

    @Test
    fun rejectsRevokedFirstDeviceAfterPhysicalTwoDeviceAcceptance() {
        assumeTrue(
            "Revoked first-device continuation argument not supplied",
            physicalTwoDeviceStage() == "revoked-reconnect",
        )
        offlineStore.clear()
        val denied = Gate0CClient(context)
        try {
            denied.connect()
            val rejected = waitForStateAllowingFailure(denied, timeoutSeconds = 30) {
                it.phase == ConnectionPhase.FAILED
            }
            assertNull(rejected.hostInstanceId)
            assertNull(rejected.sessionId)
            assertTrue(rejected.sessions.isEmpty())
            assertTrue(rejected.timeline.isEmpty())
            assertTrue(rejected.commandReceipts.isEmpty())
            Log.i(COMMAND_TEST_TAG, "two_device_first_reconnect_denied")
        } finally {
            denied.close()
        }
    }

    @Test
    fun secondDeviceWaitsForExpiryThenCommits() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("twoDeviceSecondInvitation")
        val expectedSessionId = arguments.getString("twoDeviceExpectedSessionId")
        assumeTrue(
            "Second-device lease acceptance arguments not supplied",
            !invitation.isNullOrBlank() && !expectedSessionId.isNullOrBlank(),
        )
        val instruction = "Second Android device committed after lease expiry"

        val observer = Gate0CClient(context)
        try {
            observer.pair(requireNotNull(invitation))
            waitForState(observer) { it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION }
            val ready = waitForState(observer, timeoutSeconds = 30) {
                it.phase == ConnectionPhase.READY
            }
            assertEquals(expectedSessionId, ready.sessionId)
            assertEquals(71uL, ready.grantedCapabilities)
            observer.acquireControl()
            val refused = waitForState(observer) { state ->
                state.events.any { it.contains("ERROR_CODE_CONTROL_HELD_BY_OTHER") }
            }
            assertNull(refused.controlLease)
            Log.i(COMMAND_TEST_TAG, "two_device_second_held_by_other")

            Thread.sleep(30_500)
            observer.acquireControl()
            waitForState(observer) { it.controlLease?.isUsable() == true }
            Log.i(COMMAND_TEST_TAG, "two_device_second_control_acquired_after_expiry")
            observer.updateLocalDraft(instruction)
            observer.sendDraft()
            val committed = waitForState(observer, timeoutSeconds = 30) { state ->
                state.commandReceipts.any { it.outcome == "COMMITTED" && !it.replayed }
            }
            assertNull(committed.pendingCommand)
            assertEquals("", committed.localDraft)
            val projected = waitForState(observer) { state ->
                state.timeline.count { it.kind == TimelineKind.USER && it.text == instruction } == 1
            }
            assertEquals(
                1,
                projected.timeline.count { it.kind == TimelineKind.USER && it.text == instruction },
            )
            Log.i(COMMAND_TEST_TAG, "two_device_second_committed")
        } finally {
            observer.close()
        }
    }

    @Test
    fun pairsThroughWebAdminThenRejectsRevokedReconnect() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("webAdminPairingInvitation")
        val expectedSessionId = arguments.getString("webAdminExpectedSessionId")
        assumeTrue(
            "Shipped Web-admin acceptance arguments not supplied",
            !invitation.isNullOrBlank() && !expectedSessionId.isNullOrBlank(),
        )

        val pairingClient = Gate0CClient(context)
        try {
            pairingClient.pair(requireNotNull(invitation))
            val awaiting = waitForState(pairingClient, timeoutSeconds = 60) {
                it.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION
            }
            assertTrue(awaiting.pairingVerificationCode?.matches(Regex("\\d{8}")) == true)

            val ready = waitForState(pairingClient, timeoutSeconds = 120) {
                it.phase == ConnectionPhase.READY
            }
            assertEquals(expectedSessionId, ready.sessionId)
            assertTrue(ready.hostInstanceId?.isNotBlank() == true)
            assertTrue(ready.sessions.any { it.sessionId == expectedSessionId })
            assertTrue(ready.timeline.any {
                it.kind == TimelineKind.USER && it.text == "Web-confirmed Android acceptance"
            })
            assertTrue(ready.timeline.any {
                it.kind == TimelineKind.ASSISTANT &&
                    it.final &&
                    it.text == "Shipped Host Settings authorized this read."
            })

            val revoked = waitForStateAllowingFailure(pairingClient, timeoutSeconds = 120) {
                it.phase == ConnectionPhase.CLOSED || it.phase == ConnectionPhase.FAILED
            }
            assertTrue(
                revoked.phase == ConnectionPhase.CLOSED || revoked.phase == ConnectionPhase.FAILED,
            )
        } finally {
            pairingClient.close()
        }

        val deniedClient = Gate0CClient(context)
        try {
            deniedClient.connect()
            val denied = waitForStateAllowingFailure(deniedClient, timeoutSeconds = 30) {
                it.phase == ConnectionPhase.FAILED
            }
            assertEquals(ConnectionPhase.FAILED, denied.phase)
            assertNull(denied.hostInstanceId)
            assertNull(denied.sessionId)
            assertTrue(denied.sessions.isEmpty())
            assertTrue(denied.timeline.isEmpty())
            assertTrue(denied.commandReceipts.isEmpty())
        } finally {
            deniedClient.close()
        }
    }

    private fun waitForState(
        client: Gate0CClient,
        timeoutSeconds: Long = 20,
        predicate: (Gate0CState) -> Boolean,
    ): Gate0CState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            val state = client.state.value
            if (state.phase == ConnectionPhase.FAILED) {
                throw AssertionError("Secure carrier failed: ${state.failure ?: state.events.lastOrNull()}")
            }
            if (predicate(state)) return state
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for secure carrier state; last=${client.state.value}")
    }

    private fun physicalCommandRecoveryStage(): String? =
        InstrumentationRegistry.getArguments().getString("physicalCommandRecoveryStage")

    private fun physicalStopRecoveryStage(): String? =
        InstrumentationRegistry.getArguments().getString("physicalStopRecoveryStage")

    private fun physicalApprovalRecoveryStage(): String? =
        InstrumentationRegistry.getArguments().getString("physicalApprovalRecoveryStage")

    private fun physicalRepairStage(): String? =
        InstrumentationRegistry.getArguments().getString("physicalRepairStage")

    private fun protectedPendingCommand(): PendingRemoteCommand {
        val pairedHost = requireNotNull(hostStore.loadSole())
        val identity = identityStore.loadOrCreate()
        val binding = try {
            val devicePublicKey = identity.publicKey
            try {
                OfflineProjectionStore.hostBinding(
                    pairedHost.hostPublicKey,
                    devicePublicKey,
                    pairedHost.capabilities,
                )
            } finally {
                devicePublicKey.fill(0)
            }
        } finally {
            identity.close()
        }
        return try {
            val load = pendingCommandStore.load(binding, pairedHost.pairedAtMs)
            requireNotNull(load.command) {
                "Pending command unavailable: warning=${load.warning} blocked=${load.blocked}"
            }
        } finally {
            binding.fill(0)
            pairedHost.hostPublicKey.fill(0)
        }
    }

    private fun physicalTwoDeviceStage(): String? =
        InstrumentationRegistry.getArguments().getString("physicalTwoDeviceStage")

    private fun waitForStateAllowingFailure(
        client: Gate0CClient,
        timeoutSeconds: Long,
        predicate: (Gate0CState) -> Boolean,
    ): Gate0CState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            val state = client.state.value
            if (predicate(state)) return state
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for terminal secure carrier state; last=${client.state.value}")
    }

    private companion object {
        const val COMMAND_TEST_TAG = "DSHRemoteCommandTest"
    }
}
