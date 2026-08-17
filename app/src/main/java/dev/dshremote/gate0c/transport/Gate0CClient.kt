package dev.dshremote.gate0c.transport

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.protobuf.ByteString
import dev.dshremote.protocol.v1alpha.Ack
import dev.dshremote.protocol.v1alpha.AcquireControl
import dev.dshremote.protocol.v1alpha.ApprovalDecision
import dev.dshremote.protocol.v1alpha.ApprovalInteraction
import dev.dshremote.protocol.v1alpha.ApprovalRisk as ProtoApprovalRisk
import dev.dshremote.protocol.v1alpha.BlobFetchErrorCode
import dev.dshremote.protocol.v1alpha.BlobFetchOpen
import dev.dshremote.protocol.v1alpha.BlobFetchRequest
import dev.dshremote.protocol.v1alpha.BlobFetchResult
import dev.dshremote.protocol.v1alpha.BlobTransferAction
import dev.dshremote.protocol.v1alpha.BlobTransferBegin
import dev.dshremote.protocol.v1alpha.BlobTransferChunk
import dev.dshremote.protocol.v1alpha.BlobTransferControl
import dev.dshremote.protocol.v1alpha.BlobTransferErrorCode
import dev.dshremote.protocol.v1alpha.BlobTransferResult
import dev.dshremote.protocol.v1alpha.ClientFrame
import dev.dshremote.protocol.v1alpha.Command
import dev.dshremote.protocol.v1alpha.ControlFence
import dev.dshremote.protocol.v1alpha.ControlRequest
import dev.dshremote.protocol.v1alpha.CreateSession
import dev.dshremote.protocol.v1alpha.DecideApproval
import dev.dshremote.protocol.v1alpha.ErrorCode
import dev.dshremote.protocol.v1alpha.ForkSession
import dev.dshremote.protocol.v1alpha.Heartbeat
import dev.dshremote.protocol.v1alpha.PolicyChanged as ProtoPolicyChanged
import dev.dshremote.protocol.v1alpha.ProjectedEvent
import dev.dshremote.protocol.v1alpha.ResumeCursor
import dev.dshremote.protocol.v1alpha.RevokeApprovalRule
import dev.dshremote.protocol.v1alpha.SelectAgentPreset
import dev.dshremote.protocol.v1alpha.SelectModel
import dev.dshremote.protocol.v1alpha.SendInput
import dev.dshremote.protocol.v1alpha.ServerFrame
import dev.dshremote.protocol.v1alpha.SecureErrorCode
import dev.dshremote.protocol.v1alpha.SetSessionBudget
import dev.dshremote.protocol.v1alpha.StopActive
import dev.dshremote.protocol.v1alpha.Subscribe
import dev.dshremote.security.DeviceIdentityStore
import dev.dshremote.security.PairedHostLockedException
import dev.dshremote.security.PairedHostStore
import dev.dshremote.security.PendingHostRecoveryRecord
import dev.dshremote.security.PairingProtocol
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One Host connection owner. S-multi-host: `hostId` (the store's lowercase
 * SHA-256 hex Host key) binds this client to one paired Host record and its
 * own offline/pending-command files; null keeps the legacy single-Host shape
 * (also used by transient pairing-ceremony clients). `onPairedHostSaved` fires
 * when a ceremony durably confirms a new pin, so a fleet can adopt the Host.
 */
class Gate0CClient(
    context: Context,
    private val hostId: String? = null,
    private val onPairedHostSaved: (() -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionEpoch = AtomicLong(0)
    private val connectStartedAtNanos = AtomicLong(0)
    private val firstLoggedAssistantDeltaEpoch = AtomicLong(0)
    private val firstLoggedAuthorizationProbeEpoch = AtomicLong(0)
    private val frameNumber = AtomicLong(0)
    private val logNumber = AtomicLong(0)
    private val writeLock = Any()
    private val identityStore = DeviceIdentityStore(context.applicationContext)
    private val pairedHostStore = PairedHostStore(context.applicationContext)
    private val offlineStore = OfflineProjectionStore(context.applicationContext, hostId)
    private val pendingCommandStore = PendingCommandStore(context.applicationContext, hostId)
    private val mutableState = MutableStateFlow(Gate0CState())
    private val cacheLock = Any()
    private val commandLock = Any()
    private val heartbeatLock = Any()
    // S-blob (ADR-005): in-flight blob round-trips, correlated by the
    // client-minted transfer/fetch id, plus the fetch sessions opened on this
    // carrier. All three die with the transport — a stale fetch id would only
    // earn UNKNOWN_FETCH after a reconnect anyway.
    private val blobLock = Any()
    private val pendingTransfers = mutableMapOf<String, CompletableDeferred<BlobTransferResult>>()
    private val pendingFetches = mutableMapOf<String, CompletableDeferred<BlobFetchResult>>()
    private val openFetchSessions = mutableMapOf<String, String>()

    private var transport: SecureRemoteTransport? = null
    private var reconciliationTransport: SecureRemoteTransport? = null
    private var pairingClient: HostPairingClient? = null
    private var recoveryPending = false
    private var selectedSessionId: String? = null
    private var pendingResume: ResumePlan? = null
    private var resumeReplayTarget: Long? = null
    private var authorityBinding: ByteArray? = null
    private var pairedAtMs: Long? = null
    private var expectedGrantedCapabilities: ULong? = null
    private var cachedWorkspace: OfflineWorkspaceCache? = null
    private var cacheWriteJob: Job? = null
    private var authorizationProbeJob: Job? = null
    private var pendingHeartbeatNonce: String? = null
    private var activeControl: ActiveControl? = null
    private var pendingCommand: PendingRemoteCommand? = null
    private var commandRecoveryBlocked = false
    private val awaitingUnlockRetry = java.util.concurrent.atomic.AtomicBoolean(false)
    private var unlockReceiverRegistered = false
    private var unlockPollJob: Job? = null

    val state: StateFlow<Gate0CState> = mutableState.asStateFlow()

    fun connect() {
        val epoch = connectionEpoch.incrementAndGet()
        connectStartedAtNanos.set(SystemClock.elapsedRealtimeNanos())
        awaitingUnlockRetry.set(false)
        Log.i(LOG_TAG, "connect_start epoch=$epoch")
        closeTransport()
        recoveryPending = false
        pendingResume = null
        resumeReplayTarget = null
        scope.launch {
            val pairedHost = try {
                if (hostId == null) pairedHostStore.loadSole() else pairedHostStore.load(hostId)
            } catch (error: Throwable) {
                if (connectionEpoch.get() == epoch) failCarrier(epoch, error)
                return@launch
            }
            if (pairedHost == null) {
                val pending = try {
                    pairedHostStore.loadPendingRecovery()
                } catch (error: Throwable) {
                    if (connectionEpoch.get() == epoch) failCarrier(epoch, error)
                    return@launch
                }
                if (pending != null) {
                    recoverPendingPairing(epoch, pending)
                    return@launch
                }
                if (connectionEpoch.get() == epoch) {
                    mutableState.value = Gate0CState(
                        phase = ConnectionPhase.UNPAIRED,
                        events = listOf("00 · No confirmed Host pin is stored on this device."),
                    )
                }
                return@launch
            }
            pairedHostStore.clearPendingRecovery()
            val binding = try {
                val identity = identityStore.loadOrCreate()
                try {
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
            } catch (error: Throwable) {
                pairedHost.hostPublicKey.fill(0)
                if (connectionEpoch.get() == epoch) failCarrier(epoch, error)
                return@launch
            }
            val cacheLoad = offlineStore.load(binding)
            val commandLoad = pendingCommandStore.load(binding, pairedHost.pairedAtMs)
            if (connectionEpoch.get() != epoch) {
                binding.fill(0)
                commandLoad.command?.authorityBinding?.fill(0)
                commandLoad.command?.requestFingerprint?.fill(0)
                return@launch
            }
            synchronized(cacheLock) {
                authorityBinding?.fill(0)
                authorityBinding = binding
                cachedWorkspace = cacheLoad.workspace
            }
            synchronized(commandLock) {
                pendingCommand?.authorityBinding?.fill(0)
                pendingCommand?.requestFingerprint?.fill(0)
                pendingCommand = commandLoad.command
                commandRecoveryBlocked = commandLoad.blocked
                pairedAtMs = pairedHost.pairedAtMs
                expectedGrantedCapabilities = pairedHost.capabilities.toULong()
                activeControl = null
            }
            val endpoint = "${pairedHost.endpointHost}:${pairedHost.endpointPort}"
            val fingerprint = PairingProtocol.fingerprint(pairedHost.hostPublicKey)
            mutableState.value = restoredConnectingState(
                workspace = cacheLoad.workspace,
                endpoint = endpoint,
                fingerprint = fingerprint,
                warning = cacheLoad.warning,
            ).copy(
                pendingCommand = commandLoad.command?.toStatus(),
                commandWarning = commandLoad.warning,
                commandRecoveryBlocked = commandLoad.blocked,
                newPairingRequired = commandLoad.blocked,
            )
            val next = SecureRemoteTransport(
                host = pairedHost.secureTarget(),
                identityStore = identityStore,
                onFrame = { frame ->
                    if (connectionEpoch.get() == epoch) handleFrame(epoch, frame)
                },
                onError = { error ->
                    if (connectionEpoch.get() == epoch) {
                        stopAuthorizationProbes()
                        failCarrier(epoch, error)
                    }
                },
                onCompleted = {
                    if (connectionEpoch.get() == epoch) {
                        stopAuthorizationProbes()
                        Log.i(
                            LOG_TAG,
                            "carrier_closed epoch=$epoch elapsed_ms=${elapsedSinceConnectMs()} " +
                                "cursor=${state.value.cursor ?: -1}",
                        )
                        captureProjection(persistImmediately = false)
                        val hasOfflineData = state.value.sessions.isNotEmpty() || state.value.timeline.isNotEmpty()
                        update(
                            if (hasOfflineData) ConnectionPhase.OFFLINE else ConnectionPhase.CLOSED,
                            "Host closed the authenticated stream.",
                        ) {
                            copy(offlineSnapshot = sessions.isNotEmpty() || timeline.isNotEmpty())
                        }
                    }
                },
            )
            pairedHost.hostPublicKey.fill(0)
            transport = next
            runCatching(next::connect).onFailure { error ->
                if (connectionEpoch.get() == epoch) failCarrier(epoch, error)
            }
        }
    }

    fun pair(invitationUri: String) {
        val epoch = connectionEpoch.incrementAndGet()
        closeTransport()
        synchronized(commandLock) {
            pendingCommandStore.clear()
            pendingCommand?.authorityBinding?.fill(0)
            pendingCommand?.requestFingerprint?.fill(0)
            pendingCommand = null
            commandRecoveryBlocked = false
            activeControl = null
            pairedAtMs = null
            expectedGrantedCapabilities = null
        }
        mutableState.value = Gate0CState(
            phase = ConnectionPhase.PAIRING,
            events = listOf("00 · Validating the one-time Host invitation."),
        )
        scope.launch {
            pairedHostStore.clearPendingRecovery()
            val client = runCatching {
                HostPairingClient(
                    invitationUri = invitationUri,
                    deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .joinToString(" ")
                        .take(80)
                        .ifBlank { "Android device" },
                    identityStore = identityStore,
                    onAwaitingConfirmation = { pending ->
                        try {
                            pairedHostStore.savePendingRecovery(pending)
                            if (connectionEpoch.get() == epoch) {
                                update(
                                    ConnectionPhase.AWAITING_HOST_CONFIRMATION,
                                    "Noise pairing authenticated both endpoints; compare the code on Host.",
                                ) {
                                    copy(
                                        endpoint = "${pending.endpointHost}:${pending.endpointPort}",
                                        pairingVerificationCode = pending.verificationCode,
                                        pairedHostFingerprint = PairingProtocol.fingerprint(pending.hostPublicKey),
                                        grantedCapabilities = pending.capabilities.toULong(),
                                        pairingRecoveryPending = true,
                                        failure = null,
                                    )
                                }
                            }
                        } catch (error: Throwable) {
                            pairingClient?.close()
                            if (connectionEpoch.get() == epoch) failCarrier(epoch, error)
                        } finally {
                            pending.hostPublicKey.fill(0)
                        }
                    },
                    onConfirmed = { record ->
                        scope.launch {
                            try {
                                pairedHostStore.save(record)
                                pairedHostStore.clearPendingRecovery()
                                onPairedHostSaved?.invoke()
                                if (connectionEpoch.get() == epoch) connect()
                            } catch (error: Throwable) {
                                if (connectionEpoch.get() == epoch) failCarrier(epoch, error)
                            } finally {
                                record.hostPublicKey.fill(0)
                            }
                        }
                    },
                    onRejected = {
                        pairedHostStore.clearPendingRecovery()
                        if (connectionEpoch.get() == epoch) {
                            update(ConnectionPhase.UNPAIRED, "Host rejected the pairing request.") {
                                copy(
                                    pairingVerificationCode = null,
                                    pairedHostFingerprint = null,
                                    pairingRecoveryPending = false,
                                    failure = "Pairing rejected by Host",
                                )
                            }
                        }
                    },
                    onSettlementUnknown = { pending ->
                        scope.launch { recoverPendingPairing(epoch, pending) }
                    },
                    onError = { error ->
                        if (connectionEpoch.get() == epoch) failCarrier(epoch, error)
                    },
                )
            }.getOrElse { error ->
                if (connectionEpoch.get() == epoch) failCarrier(epoch, error)
                return@launch
            }
            pairingClient = client
            runCatching(client::connect).onFailure { error ->
                if (connectionEpoch.get() == epoch) failCarrier(epoch, error)
            }
        }
    }

    fun runDisabledCommandProbe() {
        if (state.value.phase != ConnectionPhase.READY && state.value.phase != ConnectionPhase.RECONCILED) return
        val sessionId = selectedSessionId ?: return
        val commandId = "android-${UUID.randomUUID()}"
        val command = Command.newBuilder()
            .setCommandId(commandId)
            .setSessionId(sessionId)
            .setSendInput(SendInput.newBuilder().setText("continue"))
            .build()
        val frame = ClientFrame.newBuilder()
            .setFrameId(nextFrameId())
            .setCommand(command)
            .build()
        write(frame)
        update(log = "Sent one command probe; the read-only Host must reject it before any DSH effect.")
    }

    fun acquireControl() {
        val current = state.value
        if (current.phase != ConnectionPhase.READY && current.phase != ConnectionPhase.RECONCILED) return
        if (!hasCapabilities(current.grantedCapabilities, CONTROL_CAPABILITIES)) return
        if (current.pendingCommand != null) return
        if (current.commandRecoveryBlocked) return
        val sessionId = selectedSessionId ?: return
        val requestId = "control-${UUID.randomUUID()}"
        val frame = ClientFrame.newBuilder()
            .setFrameId(nextFrameId())
            .setControlRequest(
                ControlRequest.newBuilder()
                    .setRequestId(requestId)
                    .setSessionId(sessionId)
                    .setAcquire(AcquireControl.getDefaultInstance()),
            )
            .build()
        if (write(frame)) {
            update(log = "Requested Session control from the authenticated Host.")
        }
    }

    fun sendDraft() {
        scope.launch {
            // S-blob: staged images upload BEFORE the protected reservation —
            // the command records only committed content addresses, and an
            // interrupted upload leaves the draft and thumbnails untouched.
            val uploadedIds = uploadComposerImages() ?: return@launch
            val attachmentIds = uploadedIds.ifEmpty { null }
            val command = try {
                synchronized(commandLock) {
                    if (pendingCommand != null) return@launch
                    if (commandRecoveryBlocked) return@launch
                    val current = state.value
                    if (current.phase != ConnectionPhase.READY && current.phase != ConnectionPhase.RECONCILED) {
                        return@launch
                    }
                    if (!hasCapabilities(current.grantedCapabilities, SEND_CONTROL_CAPABILITIES)) return@launch
                    val sessionId = selectedSessionId ?: return@launch
                    val lease = activeControl?.takeIf {
                        it.sessionId == sessionId && it.expiresAtMs > System.currentTimeMillis()
                    } ?: return@launch
                    val binding = authorityBinding?.copyOf() ?: return@launch
                    try {
                        val ceremony = pairedAtMs ?: return@launch
                        val text = current.localDraft.take(PendingRemoteCommand.MAX_TEXT_CHARS)
                        if (text.isBlank()) return@launch
                        val created = PendingRemoteCommand.create(
                            authorityBinding = binding,
                            pairedAtMs = ceremony,
                            commandId = "android-${UUID.randomUUID()}",
                            sessionId = sessionId,
                            text = text,
                            controlEpoch = lease.epoch,
                            controlToken = lease.token,
                            controlExpiresAtMs = lease.expiresAtMs,
                            createdAtMs = System.currentTimeMillis(),
                            attachmentIds = attachmentIds,
                        )
                        try {
                            pendingCommandStore.save(created)
                        } catch (error: Throwable) {
                            created.authorityBinding.fill(0)
                            created.requestFingerprint.fill(0)
                            throw error
                        }
                        pendingCommand = created
                        created
                    } finally {
                        binding.fill(0)
                    }
                }
            } catch (error: Throwable) {
                update(log = "Command was not sent because its protected local reservation failed.") {
                    copy(commandWarning = error.message ?: "Unable to protect the pending command.")
                }
                return@launch
            }
            mutableState.update { current ->
                current.copy(
                    pendingCommand = command.toStatus(),
                    commandWarning = null,
                )
            }
            sendPendingCommand(command, "Sent a durably recorded command to the authenticated Host.")
        }
    }

    /**
     * S-blob send 上传阶段：把 composer 暂存图逐张经 blob 通道提交，返回本次
     * 发送要引用的全部已提交 id（含先前续传提交的；无附件时为空列表）。
     * 任何失败都诚实呈现并返回 null——命令不落盘、草稿与缩略图原样保留，
     * 重试从未完成的一张续传。
     */
    private suspend fun uploadComposerImages(): List<String>? {
        val current = state.value
        val images = current.composerImages
        val committed = current.committedAttachments
        if (images.isEmpty() && committed.isEmpty()) return emptyList()
        if (current.stagedUpload != null) {
            update(log = "Send is blocked by an interrupted upload.") {
                copy(commandWarning = "上次上传未完成——先续传或放弃，再发送。")
            }
            return null
        }
        val limits = current.attachmentLimits
        if (limits == null) {
            update(log = "Send with attachments refused: the deployment accepts none.") {
                copy(commandWarning = "此 Host 部署不接受附件。")
            }
            return null
        }
        if (images.size + committed.size > limits.maxImagesPerMessage) {
            update(log = "Send refused by the deployment attachment count bound.") {
                copy(commandWarning = "每条消息最多 ${limits.maxImagesPerMessage} 张图片。")
            }
            return null
        }
        // 与预约块同一组前置条件的廉价预检（权威复核仍在 commandLock 内）：
        // 不满足时不上传，避免无意义占用 Host 传输预算。
        val eligible = synchronized(commandLock) {
            pendingCommand == null && !commandRecoveryBlocked &&
                (current.phase == ConnectionPhase.READY || current.phase == ConnectionPhase.RECONCILED) &&
                hasCapabilities(current.grantedCapabilities, SEND_CONTROL_CAPABILITIES) &&
                current.localDraft.isNotBlank() &&
                selectedSessionId?.let { id ->
                    activeControl?.takeIf { it.sessionId == id && it.expiresAtMs > System.currentTimeMillis() }
                } != null
        }
        if (!eligible) return null
        val ids = committed.map { it.attachmentId }.toMutableList()
        for ((index, image) in images.withIndex()) {
            mutableState.update {
                it.copy(attachmentSend = AttachmentSendProgress(completed = index, total = images.size))
            }
            when (val resolution = blobIntake.resolve(image.key)) {
                is BlobSourceResolution.Unavailable -> {
                    mutableState.update {
                        it.copy(attachmentSend = null, commandWarning = resolution.detail)
                    }
                    return null
                }
                is BlobSourceResolution.Resolved -> when (
                    val outcome = blobUploadPipeline.stageAndUpload(
                        resolution.openSource,
                        resolution.displayName,
                        resolution.mediaType,
                    )
                ) {
                    is BlobUploadOutcome.Success -> {
                        // 内容寻址去重：同一图片重复添加只引用一次。
                        if (outcome.blobId !in ids) ids += outcome.blobId
                    }
                    is BlobUploadOutcome.Retryable -> {
                        refreshStagedUpload()
                        mutableState.update {
                            it.copy(
                                attachmentSend = null,
                                commandWarning = "连接中断：已完成 $index/${images.size} 张上传，恢复后重新发送将续传未完成的一张。",
                            )
                        }
                        return null
                    }
                    is BlobUploadOutcome.Failed -> {
                        refreshStagedUpload()
                        mutableState.update {
                            it.copy(attachmentSend = null, commandWarning = outcome.detail)
                        }
                        return null
                    }
                }
            }
        }
        mutableState.update { it.copy(attachmentSend = null) }
        update(log = "Committed ${ids.size} image attachment(s) for the next send.")
        return ids
    }

    fun stopActive() {
        scope.launch {
            val command = try {
                synchronized(commandLock) {
                    if (pendingCommand != null || commandRecoveryBlocked) return@launch
                    val current = state.value
                    if (current.phase != ConnectionPhase.READY && current.phase != ConnectionPhase.RECONCILED) {
                        return@launch
                    }
                    if (!hasCapabilities(current.grantedCapabilities, STOP_CONTROL_CAPABILITIES)) return@launch
                    if (current.sessionRunning != true) return@launch
                    val targetRevision = current.activityRevision?.takeIf { it > 0 } ?: return@launch
                    val sessionId = selectedSessionId ?: return@launch
                    val lease = activeControl?.takeIf {
                        it.sessionId == sessionId && it.expiresAtMs > System.currentTimeMillis()
                    } ?: return@launch
                    val binding = authorityBinding?.copyOf() ?: return@launch
                    try {
                        val ceremony = pairedAtMs ?: return@launch
                        val created = PendingRemoteCommand.createStop(
                            authorityBinding = binding,
                            pairedAtMs = ceremony,
                            commandId = "android-stop-${UUID.randomUUID()}",
                            sessionId = sessionId,
                            expectedActivityRevision = targetRevision,
                            controlEpoch = lease.epoch,
                            controlToken = lease.token,
                            controlExpiresAtMs = lease.expiresAtMs,
                            createdAtMs = System.currentTimeMillis(),
                        )
                        try {
                            pendingCommandStore.save(created)
                        } catch (error: Throwable) {
                            created.authorityBinding.fill(0)
                            created.requestFingerprint.fill(0)
                            throw error
                        }
                        pendingCommand = created
                        created
                    } finally {
                        binding.fill(0)
                    }
                }
            } catch (error: Throwable) {
                update(log = "Stop was not requested because its protected local reservation failed.") {
                    copy(commandWarning = error.message ?: "Unable to protect the pending Stop request.")
                }
                return@launch
            }
            mutableState.update { current ->
                current.copy(pendingCommand = command.toStatus(), commandWarning = null)
            }
            sendPendingCommand(command, "Sent a durably recorded exact-turn Stop request.")
        }
    }

    fun decideApproval(approvalId: String, decision: PendingApprovalDecision) {
        scope.launch {
            val command = try {
                synchronized(commandLock) {
                    if (pendingCommand != null || commandRecoveryBlocked) return@launch
                    val current = state.value
                    if (current.phase != ConnectionPhase.READY && current.phase != ConnectionPhase.RECONCILED) {
                        return@launch
                    }
                    if (!hasCapabilities(current.grantedCapabilities, APPROVAL_CAPABILITIES)) return@launch
                    val approval = current.approvals.find { it.approvalId == approvalId } ?: return@launch
                    // Each decision is gated by the Host's own offer for this
                    // exact ask; the client never invents an affordance.
                    val allowed = when (decision) {
                        PendingApprovalDecision.ALLOW_ONCE -> approval.allowOnce
                        PendingApprovalDecision.DENY -> approval.deny
                        PendingApprovalDecision.ALLOW_SAME_KIND -> approval.allowSameKind
                    }
                    if (!allowed || approval.sessionId != current.sessionId) return@launch
                    val binding = authorityBinding?.copyOf() ?: return@launch
                    try {
                        val ceremony = pairedAtMs ?: return@launch
                        val created = PendingRemoteCommand.createApprovalDecision(
                            authorityBinding = binding,
                            pairedAtMs = ceremony,
                            commandId = "android-approval-${UUID.randomUUID()}",
                            sessionId = approval.sessionId,
                            approvalId = approval.approvalId,
                            approvalRevision = approval.revision,
                            approvalDecision = decision,
                            createdAtMs = System.currentTimeMillis(),
                        )
                        pendingCommandStore.save(created)
                        pendingCommand = created
                        created.copyForUse()
                    } finally {
                        binding.fill(0)
                    }
                }
            } catch (error: Throwable) {
                update(log = "Approval decision was not sent because its protected local reservation failed.") {
                    copy(commandWarning = error.message ?: "Unable to protect the pending approval decision.")
                }
                return@launch
            }
            mutableState.update { current ->
                current.copy(pendingCommand = command.toStatus(), commandWarning = null)
            }
            sendPendingCommand(command, "Sent a protected exact-revision approval decision.")
        }
    }

    /**
     * S-mode-select: durably queue a Session creation and return the
     * caller-preallocated Session id so the UI can open it; null when the
     * command was not queued (gated by phase, capability, or a pending command).
     * The same id retries to the same Session Host-side, so reconciliation can
     * never create a duplicate.
     */
    fun createSession(agentPreset: String?): String? {
        // Cheap gates run synchronously so the returned id honestly means
        // "queued"; the protected reservation itself stays off the caller thread.
        val current = state.value
        if (current.phase != ConnectionPhase.READY && current.phase != ConnectionPhase.RECONCILED) return null
        if (!hasCapabilities(current.grantedCapabilities, SEND_CONTROL_CAPABILITIES)) return null
        synchronized(commandLock) {
            if (pendingCommand != null || commandRecoveryBlocked) return null
        }
        val newSessionId = "android-${UUID.randomUUID()}"
        scope.launch {
            val command = try {
                synchronized(commandLock) {
                    if (pendingCommand != null || commandRecoveryBlocked) return@launch
                    val binding = authorityBinding?.copyOf() ?: return@launch
                    try {
                        val ceremony = pairedAtMs ?: return@launch
                        val created = PendingRemoteCommand.createSession(
                            authorityBinding = binding,
                            pairedAtMs = ceremony,
                            commandId = "android-create-${UUID.randomUUID()}",
                            sessionId = newSessionId,
                            agentPreset = agentPreset,
                            createdAtMs = System.currentTimeMillis(),
                        )
                        pendingCommandStore.save(created)
                        pendingCommand = created
                        created.copyForUse()
                    } finally {
                        binding.fill(0)
                    }
                }
            } catch (error: Throwable) {
                update(log = "Session creation was not sent because its protected local reservation failed.") {
                    copy(commandWarning = error.message ?: "Unable to protect the pending Session creation.")
                }
                return@launch
            }
            mutableState.update { value ->
                value.copy(pendingCommand = command.toStatus(), commandWarning = null)
            }
            sendPendingCommand(command, "Sent a durably recorded Session creation with its preallocated id.")
        }
        return newSessionId
    }

    /** S-mode-select: durably queue a preset selection against the open blank Session. */
    fun selectAgentPreset(agentPreset: String) {
        scope.launch {
            val command = try {
                synchronized(commandLock) {
                    if (pendingCommand != null || commandRecoveryBlocked) return@launch
                    val current = state.value
                    if (current.phase != ConnectionPhase.READY && current.phase != ConnectionPhase.RECONCILED) {
                        return@launch
                    }
                    if (!hasCapabilities(current.grantedCapabilities, SEND_CONTROL_CAPABILITIES)) return@launch
                    if (!current.sessionBlank) return@launch
                    val sessionId = selectedSessionId ?: return@launch
                    val binding = authorityBinding?.copyOf() ?: return@launch
                    try {
                        val ceremony = pairedAtMs ?: return@launch
                        val created = PendingRemoteCommand.createSelectAgentPreset(
                            authorityBinding = binding,
                            pairedAtMs = ceremony,
                            commandId = "android-preset-${UUID.randomUUID()}",
                            sessionId = sessionId,
                            agentPreset = agentPreset,
                            createdAtMs = System.currentTimeMillis(),
                        )
                        pendingCommandStore.save(created)
                        pendingCommand = created
                        created.copyForUse()
                    } finally {
                        binding.fill(0)
                    }
                }
            } catch (error: Throwable) {
                update(log = "Preset selection was not sent because its protected local reservation failed.") {
                    copy(commandWarning = error.message ?: "Unable to protect the pending preset selection.")
                }
                return@launch
            }
            mutableState.update { current ->
                current.copy(pendingCommand = command.toStatus(), commandWarning = null)
            }
            sendPendingCommand(command, "Sent a durably recorded Agent preset selection.")
        }
    }

    /**
     * S-session-admin: durably queue a provider/model/effort selection against
     * the open Session. Unlike preset selection this is legal mid-session — the
     * running step keeps its assembled selection and the NEXT assembled request
     * uses the new triple — so it presents the control fence exactly like
     * send_input. Adapter-exact validation rejects an unserved triple with
     * MODEL_UNAVAILABLE before any mutation.
     */
    fun selectModel(provider: String, model: String, reasoningEffort: String?) {
        scope.launch {
            val command = try {
                synchronized(commandLock) {
                    if (pendingCommand != null || commandRecoveryBlocked) return@launch
                    val current = state.value
                    if (current.phase != ConnectionPhase.READY && current.phase != ConnectionPhase.RECONCILED) {
                        return@launch
                    }
                    if (!hasCapabilities(current.grantedCapabilities, SEND_CONTROL_CAPABILITIES)) return@launch
                    val sessionId = selectedSessionId ?: return@launch
                    val lease = activeControl?.takeIf {
                        it.sessionId == sessionId && it.expiresAtMs > System.currentTimeMillis()
                    } ?: return@launch
                    val binding = authorityBinding?.copyOf() ?: return@launch
                    try {
                        val ceremony = pairedAtMs ?: return@launch
                        val created = PendingRemoteCommand.createSelectModel(
                            authorityBinding = binding,
                            pairedAtMs = ceremony,
                            commandId = "android-model-${UUID.randomUUID()}",
                            sessionId = sessionId,
                            modelSelection = ModelSelectionProjection(provider, model, reasoningEffort),
                            controlEpoch = lease.epoch,
                            controlToken = lease.token,
                            controlExpiresAtMs = lease.expiresAtMs,
                            createdAtMs = System.currentTimeMillis(),
                        )
                        try {
                            pendingCommandStore.save(created)
                        } catch (error: Throwable) {
                            created.authorityBinding.fill(0)
                            created.requestFingerprint.fill(0)
                            throw error
                        }
                        pendingCommand = created
                        created.copyForUse()
                    } finally {
                        binding.fill(0)
                    }
                }
            } catch (error: Throwable) {
                update(log = "Model selection was not sent because its protected local reservation failed.") {
                    copy(commandWarning = error.message ?: "Unable to protect the pending model selection.")
                }
                return@launch
            }
            mutableState.update { current ->
                current.copy(pendingCommand = command.toStatus(), commandWarning = null)
            }
            sendPendingCommand(command, "Sent a durably recorded model selection.")
        }
    }

    /**
     * S-session-admin: durably queue a fork of the open Session, cut at the
     * first completed turn at or after [atSeq] (null = the last completed
     * turn), and return the caller-preallocated child Session id so the UI can
     * open it; null when the command was not queued. Lease-free — the source
     * log is never mutated — and the same command id retries to the same
     * child Host-side, so reconciliation can never fork a twin.
     */
    fun forkSession(atSeq: Long?): String? {
        // Cheap gates run synchronously so the returned id honestly means
        // "queued"; the protected reservation itself stays off the caller thread.
        val current = state.value
        if (current.phase != ConnectionPhase.READY && current.phase != ConnectionPhase.RECONCILED) return null
        if (!hasCapabilities(current.grantedCapabilities, SEND_CONTROL_CAPABILITIES)) return null
        val sourceSessionId = current.sessionId ?: return null
        synchronized(commandLock) {
            if (pendingCommand != null || commandRecoveryBlocked) return null
        }
        val childSessionId = "android-${UUID.randomUUID()}"
        scope.launch {
            val command = try {
                synchronized(commandLock) {
                    if (pendingCommand != null || commandRecoveryBlocked) return@launch
                    if (selectedSessionId != sourceSessionId) return@launch
                    val binding = authorityBinding?.copyOf() ?: return@launch
                    try {
                        val ceremony = pairedAtMs ?: return@launch
                        val created = PendingRemoteCommand.createForkSession(
                            authorityBinding = binding,
                            pairedAtMs = ceremony,
                            commandId = "android-fork-${UUID.randomUUID()}",
                            sessionId = sourceSessionId,
                            childSessionId = childSessionId,
                            forkAtSeq = atSeq,
                            createdAtMs = System.currentTimeMillis(),
                        )
                        pendingCommandStore.save(created)
                        pendingCommand = created
                        created.copyForUse()
                    } finally {
                        binding.fill(0)
                    }
                }
            } catch (error: Throwable) {
                update(log = "Session fork was not sent because its protected local reservation failed.") {
                    copy(commandWarning = error.message ?: "Unable to protect the pending Session fork.")
                }
                return@launch
            }
            mutableState.update { value ->
                value.copy(pendingCommand = command.toStatus(), commandWarning = null)
            }
            sendPendingCommand(command, "Sent a durably recorded Session fork with its preallocated child id.")
        }
        return childSessionId
    }

    /**
     * S-policy: durably queue the revocation of one exact auto-grant rule of
     * the open Session. Shares the approval trust domain (the same authority
     * that creates rules); lease-free — a durable policy fact changes, not the
     * in-flight input stream — and a retry replays Host-side.
     */
    fun revokeApprovalRule(ruleId: String) {
        scope.launch {
            val command = try {
                synchronized(commandLock) {
                    if (pendingCommand != null || commandRecoveryBlocked) return@launch
                    val current = state.value
                    if (current.phase != ConnectionPhase.READY && current.phase != ConnectionPhase.RECONCILED) {
                        return@launch
                    }
                    if (!hasCapabilities(current.grantedCapabilities, APPROVAL_CAPABILITIES)) return@launch
                    // Only rules the Host projected for the open Session are
                    // revocable; the client never invents a rule id.
                    if (current.approvalRules.none { it.ruleId == ruleId }) return@launch
                    val sessionId = selectedSessionId ?: return@launch
                    val binding = authorityBinding?.copyOf() ?: return@launch
                    try {
                        val ceremony = pairedAtMs ?: return@launch
                        val created = PendingRemoteCommand.createRevokeRule(
                            authorityBinding = binding,
                            pairedAtMs = ceremony,
                            commandId = "android-revoke-${UUID.randomUUID()}",
                            sessionId = sessionId,
                            ruleId = ruleId,
                            createdAtMs = System.currentTimeMillis(),
                        )
                        pendingCommandStore.save(created)
                        pendingCommand = created
                        created.copyForUse()
                    } finally {
                        binding.fill(0)
                    }
                }
            } catch (error: Throwable) {
                update(log = "Rule revocation was not sent because its protected local reservation failed.") {
                    copy(commandWarning = error.message ?: "Unable to protect the pending rule revocation.")
                }
                return@launch
            }
            mutableState.update { current ->
                current.copy(pendingCommand = command.toStatus(), commandWarning = null)
            }
            sendPendingCommand(command, "Sent a durably recorded approval rule revocation.")
        }
    }

    /**
     * S-policy: durably queue setting the open Session's token budget to one
     * exact ceiling (set-valued — a retry converges Host-side). Lease-free
     * session administration on the send/control trust set.
     */
    fun setSessionBudget(maxTotalTokens: Long) {
        if (maxTotalTokens <= 0) return
        scope.launch {
            val command = try {
                synchronized(commandLock) {
                    if (pendingCommand != null || commandRecoveryBlocked) return@launch
                    val current = state.value
                    if (current.phase != ConnectionPhase.READY && current.phase != ConnectionPhase.RECONCILED) {
                        return@launch
                    }
                    if (!hasCapabilities(current.grantedCapabilities, SEND_CONTROL_CAPABILITIES)) return@launch
                    val sessionId = selectedSessionId ?: return@launch
                    val binding = authorityBinding?.copyOf() ?: return@launch
                    try {
                        val ceremony = pairedAtMs ?: return@launch
                        val created = PendingRemoteCommand.createSetSessionBudget(
                            authorityBinding = binding,
                            pairedAtMs = ceremony,
                            commandId = "android-budget-${UUID.randomUUID()}",
                            sessionId = sessionId,
                            maxTotalTokens = maxTotalTokens,
                            createdAtMs = System.currentTimeMillis(),
                        )
                        pendingCommandStore.save(created)
                        pendingCommand = created
                        created.copyForUse()
                    } finally {
                        binding.fill(0)
                    }
                }
            } catch (error: Throwable) {
                update(log = "Budget setting was not sent because its protected local reservation failed.") {
                    copy(commandWarning = error.message ?: "Unable to protect the pending budget setting.")
                }
                return@launch
            }
            mutableState.update { current ->
                current.copy(pendingCommand = command.toStatus(), commandWarning = null)
            }
            sendPendingCommand(command, "Sent a durably recorded session budget ceiling.")
        }
    }

    fun reconcilePendingCommand() {
        val command = synchronized(commandLock) { pendingCommand?.copyForUse() } ?: return
        scope.launch {
            try {
                sendPendingCommand(command, "Reconciled the existing command with the same command id.")
            } finally {
                command.authorityBinding.fill(0)
                command.requestFingerprint.fill(0)
            }
        }
    }

    fun updateLocalDraft(value: String) {
        val sessionId = selectedSessionId ?: return
        val bounded = value.take(OfflineWorkspaceCache.MAX_DRAFT_CHARS)
        mutableState.update { current ->
            if (current.sessionId != sessionId) current else current.copy(localDraft = bounded)
        }
        updateCachedPreferences(sessionId, draft = bounded)
    }

    fun updateReadingPosition(anchorEntryId: String?, offsetPx: Int, followTail: Boolean) {
        val sessionId = selectedSessionId ?: return
        val boundedOffset = offsetPx.coerceIn(0, OfflineWorkspaceCache.MAX_READING_OFFSET_PX)
        mutableState.update { current ->
            if (current.sessionId != sessionId) current else current.copy(
                readingAnchorId = anchorEntryId,
                readingOffsetPx = boundedOffset,
                followTail = followTail,
                readingAnchorUnavailable = false,
            )
        }
        updateCachedPreferences(
            sessionId = sessionId,
            readingPosition = CachedReadingPosition(anchorEntryId, boundedOffset, followTail),
        )
    }

    fun selectSession(sessionId: String) {
        val session = state.value.sessions.find { it.sessionId == sessionId } ?: return
        selectedSessionId = sessionId
        synchronized(commandLock) {
            if (activeControl?.sessionId != sessionId) activeControl = null
        }
        recoveryPending = false
        val workspace = synchronized(cacheLock) { cachedWorkspace }
        val cached = workspace?.projection(sessionId)
        val reading = workspace?.readingPositions?.get(sessionId)
        val anchorUnavailable = reading?.anchorEntryId != null &&
            cached?.timeline?.none { it.id == reading.anchorEntryId } == true
        val resume = resumePlanFor(
            cachedHostInstanceId = workspace?.hostInstanceId,
            connectedHostInstanceId = state.value.hostInstanceId,
            sessionId = sessionId,
            projection = cached,
            expectedProjectionVersion = PROJECTION_VERSION,
        )
        update(ConnectionPhase.SYNCHRONIZING, "Opening ${session.title ?: "untitled session"}.") {
            copy(
                sessionId = session.sessionId,
                sessionTitle = cached?.title ?: session.title,
                sessionRunning = cached?.running ?: session.running,
                sessionUsage = cached?.usage ?: session.usage,
                sessionSubagent = cached?.subagent ?: session.subagent,
                sessionOrigin = session.origin,
                sessionAgentPreset = cached?.agentPreset ?: session.agentPreset,
                sessionModel = cached?.model ?: session.model,
                activityRevision = null,
                streamId = null,
                projectionVersion = cached?.projectionVersion,
                cursor = cached?.cursor,
                timeline = cached?.timeline.orEmpty(),
                approvals = emptyList(),
                approvalRules = cached?.approvalRules.orEmpty(),
                sessionBudget = cached?.sessionBudget,
                historyTruncated = cached?.historyTruncated ?: false,
                offlineSnapshot = cached != null,
                offlineCacheSavedAtMs = cached?.savedAtMs ?: workspace?.savedAtMs,
                offlineCacheTruncated = cached?.cacheTruncated ?: false,
                localDraft = workspace?.drafts?.get(sessionId).orEmpty(),
                readingAnchorId = when {
                    anchorUnavailable -> cached.timeline.firstOrNull()?.id
                    else -> reading?.anchorEntryId
                },
                readingOffsetPx = if (anchorUnavailable) 0 else reading?.offsetPx ?: 0,
                followTail = reading?.followTail ?: true,
                readingAnchorUnavailable = anchorUnavailable,
                controlLease = synchronized(commandLock) { activeControl?.toStatus() },
                failure = null,
            )
        }
        updateCachedDirectory(state.value.hostInstanceId)
        if (resume == null) {
            subscribeFresh("Synchronizing the selected Session from a fresh Host snapshot.")
        } else {
            subscribeResume(resume, "Resuming the selected Session from cursor ${resume.sequence}.")
        }
    }

    /**
     * S-mode-select: open a Session this device just created. It is blank, so
     * the directory hides it and there is nothing cached to resume — reset the
     * view to an honest empty Session and subscribe for a fresh snapshot.
     */
    private fun openCreatedSession(sessionId: String, agentPreset: String?) {
        selectedSessionId = sessionId
        synchronized(commandLock) {
            if (activeControl?.sessionId != sessionId) activeControl = null
        }
        recoveryPending = false
        update(ConnectionPhase.SYNCHRONIZING, "Opening the newly created Session.") {
            copy(
                sessionId = sessionId,
                sessionTitle = null,
                sessionRunning = false,
                sessionUsage = null,
                sessionSubagent = null,
                sessionOrigin = null,
                sessionAgentPreset = agentPreset,
                sessionModel = null,
                activityRevision = null,
                streamId = null,
                projectionVersion = null,
                cursor = null,
                timeline = emptyList(),
                approvals = emptyList(),
                approvalRules = emptyList(),
                sessionBudget = null,
                historyTruncated = false,
                offlineSnapshot = false,
                offlineCacheTruncated = false,
                localDraft = "",
                readingAnchorId = null,
                readingOffsetPx = 0,
                followTail = true,
                readingAnchorUnavailable = false,
                controlLease = synchronized(commandLock) { activeControl?.toStatus() },
                failure = null,
            )
        }
        subscribeFresh("Synchronizing the new Session from a fresh Host snapshot.")
    }

    fun close() {
        connectionEpoch.incrementAndGet()
        captureProjection(persistImmediately = true)
        closeTransport()
        scope.cancel()
        update(ConnectionPhase.CLOSED, "Android lifecycle disposed the stream and channel.")
        synchronized(cacheLock) {
            authorityBinding?.fill(0)
            authorityBinding = null
            cachedWorkspace = null
        }
        synchronized(commandLock) {
            pendingCommand?.authorityBinding?.fill(0)
            pendingCommand?.requestFingerprint?.fill(0)
            pendingCommand = null
            commandRecoveryBlocked = false
            activeControl = null
            pairedAtMs = null
            expectedGrantedCapabilities = null
        }
    }

    private fun handleFrame(epoch: Long, frame: ServerFrame) {
        when (frame.payloadCase) {
            ServerFrame.PayloadCase.HELLO -> {
                val hello = frame.hello
                val granted = runCatching { hello.grantedCapabilities.toULong() }.getOrElse {
                    update(ConnectionPhase.FAILED, "Host returned an invalid capability grant.") {
                        copy(failure = "Invalid Host capability grant")
                    }
                    closeTransport()
                    return
                }
                val expected = synchronized(commandLock) { expectedGrantedCapabilities }
                if (expected != null && granted != expected) {
                    update(ConnectionPhase.FAILED, "Host authorization changed; pending commands were not sent.") {
                        copy(
                            failure = "Re-pair this device to review the changed Host access profile.",
                            commandWarning = "A pending command remains protected but cannot cross an authorization change.",
                            newPairingRequired = true,
                        )
                    }
                    closeTransport()
                    return
                }
                val previousHostInstanceId = state.value.hostInstanceId
                if (previousHostInstanceId != null && previousHostInstanceId != hello.hostInstanceId) {
                    synchronized(commandLock) { activeControl = null }
                }
                val sessions = sessionDirectoryEntries(hello.sessionsList)
                // S-mode-select: the connect-time preset roster; authoring never
                // crosses, so a stale row fails honestly at selection time.
                val presets = agentPresetProjections(hello.agentPresetsList)
                // S-session-admin: the connect-time model catalog. Advisory like
                // the preset roster — a stale row fails selection honestly with
                // MODEL_UNAVAILABLE; failed providers stay visible as failures.
                val catalog = modelProviderGroupProjections(hello.modelCatalogList)
                val catalogFailures = modelCatalogFailureProjections(hello.modelCatalogFailuresList)
                // S-artifacts: the connect-time roster replaces wholesale — the
                // Host merges remembered live registrations into every roster,
                // so nothing this device saw live is lost across a reconnect.
                val artifacts = hello.artifactsList.map(::artifactEntryStateOf)
                // S-blob: deployment intake bounds; absent ⟺ the Host accepts
                // no attachments, and the composer affordance stays hidden.
                val attachmentLimits = if (hello.hasAttachmentLimits()) {
                    AttachmentLimitsProjection(
                        maxImageBytes = hello.attachmentLimits.maxImageBytes.toLong(),
                        maxImagesPerMessage = hello.attachmentLimits.maxImagesPerMessage,
                        mediaTypes = hello.attachmentLimits.mediaTypesList
                            .filter { it.isNotBlank() && it.length <= 64 }
                            .take(16),
                    )
                } else {
                    null
                }
                val selected = sessions.find { it.sessionId == selectedSessionId } ?: sessions.firstOrNull()
                selectedSessionId = selected?.sessionId
                val workspace = synchronized(cacheLock) { cachedWorkspace }
                val cached = workspace?.projection(selected?.sessionId)
                val resume = resumePlanFor(
                    cachedHostInstanceId = workspace?.hostInstanceId,
                    connectedHostInstanceId = hello.hostInstanceId,
                    sessionId = selected?.sessionId,
                    projection = cached,
                    expectedProjectionVersion = PROJECTION_VERSION,
                )
                val reading = selected?.sessionId?.let { workspace?.readingPositions?.get(it) }
                val anchorUnavailable = reading?.anchorEntryId != null &&
                    cached?.timeline?.none { it.id == reading.anchorEntryId } == true
                update(ConnectionPhase.HELLO, "Hello accepted by ${hello.hostInstanceId}.") {
                    copy(
                        hostInstanceId = hello.hostInstanceId,
                        // Bounded like the mDNS instance name; blank ⟺ an older
                        // Host that predates the field, and labels fall back.
                        hostDisplayName = hello.hostDisplayName.trim().take(63).takeIf { it.isNotEmpty() },
                        connectionId = hello.connectionId,
                        grantedCapabilities = granted,
                        controlLease = synchronized(commandLock) { activeControl?.toStatus() },
                        sessions = sessions,
                        agentPresets = presets,
                        modelCatalog = catalog,
                        modelCatalogFailures = catalogFailures,
                        artifacts = artifacts,
                        attachmentLimits = attachmentLimits,
                        sessionId = selected?.sessionId,
                        sessionTitle = cached?.title ?: selected?.title,
                        sessionRunning = cached?.running ?: selected?.running,
                        sessionUsage = cached?.usage ?: selected?.usage,
                        sessionSubagent = cached?.subagent ?: selected?.subagent,
                        sessionOrigin = selected?.origin,
                        sessionAgentPreset = cached?.agentPreset ?: selected?.agentPreset,
                        sessionModel = cached?.model ?: selected?.model,
                        activityRevision = null,
                        projectionVersion = cached?.projectionVersion,
                        cursor = cached?.cursor,
                        timeline = cached?.timeline.orEmpty(),
                        approvals = emptyList(),
                        approvalRules = cached?.approvalRules.orEmpty(),
                        sessionBudget = cached?.sessionBudget,
                        historyTruncated = cached?.historyTruncated ?: false,
                        offlineSnapshot = cached != null,
                        offlineCacheSavedAtMs = cached?.savedAtMs ?: workspace?.savedAtMs,
                        offlineCacheTruncated = cached?.cacheTruncated ?: false,
                        localDraft = selected?.sessionId?.let { workspace?.drafts?.get(it) }.orEmpty(),
                        readingAnchorId = when {
                            anchorUnavailable -> cached.timeline.firstOrNull()?.id
                            else -> reading?.anchorEntryId
                        },
                        readingOffsetPx = if (anchorUnavailable) 0 else reading?.offsetPx ?: 0,
                        followTail = reading?.followTail ?: true,
                        readingAnchorUnavailable = anchorUnavailable,
                    )
                }
                updateCachedDirectory(hello.hostInstanceId)
                // S-blob: an interrupted upload survives in the encrypted
                // journal; surface it so the user resumes or abandons explicitly.
                refreshStagedUpload()
                if (selected != null) {
                    if (resume == null) {
                        subscribeFresh("Negotiating a fresh projection snapshot.")
                    } else {
                        subscribeResume(resume, "Negotiating retained events after cursor ${resume.sequence}.")
                    }
                }
                val pendingRequired = synchronized(commandLock) { pendingCommand?.requiredCapabilities() }
                if (pendingRequired != null && hasCapabilities(granted, pendingRequired)) {
                    reconcilePendingCommand()
                }
            }

            ServerFrame.PayloadCase.SNAPSHOT -> applySnapshot(epoch, frame)
            ServerFrame.PayloadCase.RESUME_ACCEPTED -> applyResumeAccepted(frame)
            ServerFrame.PayloadCase.EVENT -> applyEvent(frame.event)
            ServerFrame.PayloadCase.HEARTBEAT_ACK -> {
                val accepted = synchronized(heartbeatLock) {
                    if (pendingHeartbeatNonce == frame.heartbeatAck.nonce) {
                        pendingHeartbeatNonce = null
                        true
                    } else {
                        false
                    }
                }
                if (!accepted) {
                    stopAuthorizationProbes()
                    failCarrier(epoch, IllegalStateException("Host returned an unexpected heartbeat acknowledgement"))
                    closeTransport()
                }
            }
            ServerFrame.PayloadCase.COMMAND_RESULT -> {
                val result = frame.commandResult
                val outcome = result.outcome.name.removePrefix("COMMAND_OUTCOME_")
                val receipt = CommandReceipt(
                    commandId = result.commandId,
                    outcome = outcome,
                    replayed = result.replayed,
                    errorCode = result.errorCode.name,
                    detail = result.detail,
                )
                update(log = "Command ${outcome.lowercase()}${if (result.replayed) " · replayed" else ""}.") {
                    copy(
                        commandReceipts = CommandReceiptReducer.upsert(commandReceipts, receipt),
                    )
                }
                settlePendingCommand(receipt)
            }

            ServerFrame.PayloadCase.STOP_RESULT -> {
                val result = frame.stopResult
                val outcome = result.outcome.name.removePrefix("STOP_OUTCOME_")
                val receipt = CommandReceipt(
                    commandId = result.commandId,
                    outcome = outcome,
                    replayed = result.replayed,
                    errorCode = result.errorCode.name,
                    detail = result.detail,
                )
                update(log = "Stop ${outcome.lowercase()}${if (result.replayed) " · replayed" else ""}.") {
                    copy(
                        commandReceipts = CommandReceiptReducer.upsert(commandReceipts, receipt),
                        sessionRunning = if (result.currentRunningKnown) result.currentRunning else sessionRunning,
                    )
                }
                settlePendingCommand(receipt)
            }

            ServerFrame.PayloadCase.CONTROL_RESULT -> {
                val result = frame.controlResult
                val outcome = result.outcome.name.removePrefix("CONTROL_OUTCOME_")
                synchronized(commandLock) {
                    activeControl = when (outcome) {
                        "ACQUIRED", "RENEWED" -> if (result.hasControl()) {
                            ActiveControl(
                                sessionId = result.sessionId,
                                epoch = result.control.epoch,
                                token = result.control.token,
                                expiresAtMs = result.expiresAtMs,
                            )
                        } else {
                            null
                        }
                        "RELEASED" -> null
                        else -> activeControl?.takeUnless {
                            result.errorCode in setOf(
                                ErrorCode.ERROR_CODE_CONTROL_EXPIRED,
                                ErrorCode.ERROR_CODE_CONTROL_STALE_FENCE,
                                ErrorCode.ERROR_CODE_CONTROL_UNHELD,
                                ErrorCode.ERROR_CODE_AUTHORIZATION_DENIED,
                            )
                        }
                    }
                }
                update(log = "Control ${outcome.lowercase()} · ${result.errorCode.name}.") {
                    copy(controlLease = synchronized(commandLock) { activeControl?.toStatus() })
                }
            }

            ServerFrame.PayloadCase.ERROR -> {
                val error = frame.error
                if (error.code == ErrorCode.ERROR_CODE_SNAPSHOT_REQUIRED) {
                    pendingResume = null
                    resumeReplayTarget = null
                    recoveryPending = true
                    update(ConnectionPhase.SNAPSHOT_REQUIRED, "Host rejected the stale cursor; a fresh snapshot is required.")
                    scope.launch {
                        if (connectionEpoch.get() == epoch) subscribeFresh("Requesting an explicit fresh snapshot.")
                    }
                } else if (isIncompatibleProtocolError(error.code)) {
                    update(ConnectionPhase.INCOMPATIBLE, "The Host projection protocol is incompatible with this app.") {
                        copy(
                            failure = "Update DSH Remote and the Host integration before reconnecting.",
                            offlineSnapshot = sessions.isNotEmpty() || timeline.isNotEmpty(),
                        )
                    }
                } else {
                    update(ConnectionPhase.FAILED, "Protocol error: ${error.code.name} · ${error.detail}") {
                        copy(failure = error.detail)
                    }
                }
            }

            // S-blob (ADR-005): results correlate by transfer/fetch id and
            // complete the waiting channel op. A blob frame with no local
            // waiter is still a protocol violation and fails the carrier
            // closed, never silently ignored.
            ServerFrame.PayloadCase.BLOB_TRANSFER_RESULT -> {
                val result = frame.blobTransferResult
                val pending = synchronized(blobLock) { pendingTransfers[result.transferId] }
                if (pending == null) {
                    update(ConnectionPhase.FAILED, "Received a blob frame no client transfer opened.") {
                        copy(failure = "Unexpected blob frame")
                    }
                } else {
                    pending.complete(result)
                }
            }

            ServerFrame.PayloadCase.BLOB_FETCH_RESULT -> {
                val result = frame.blobFetchResult
                val pending = synchronized(blobLock) { pendingFetches[result.fetchId] }
                if (pending == null) {
                    update(ConnectionPhase.FAILED, "Received a blob frame no client fetch opened.") {
                        copy(failure = "Unexpected blob frame")
                    }
                } else {
                    pending.complete(result)
                }
            }

            ServerFrame.PayloadCase.PAYLOAD_NOT_SET,
            null,
            -> update(ConnectionPhase.FAILED, "Received an empty server frame.") {
                copy(failure = "Empty server frame")
            }
        }
    }

    private fun applySnapshot(epoch: Long, frame: ServerFrame) {
        pendingResume = null
        resumeReplayTarget = null
        val snapshot = frame.snapshot
        val entries = snapshot.session.timelineList.map { node ->
            when (node.contentCase) {
                dev.dshremote.protocol.v1alpha.TimelineNode.ContentCase.USER_MESSAGE ->
                    userTimelineEntry(
                        node.eventId,
                        node.sourceSequence,
                        node.userMessage.text,
                        source = if (node.userMessage.hasSource()) {
                            messageSourceProjectionOf(node.userMessage.source)
                        } else {
                            null
                        },
                        attachments = node.userMessage.attachmentsList
                            .take(MAX_MESSAGE_ATTACHMENTS)
                            .map(::imageAttachmentProjectionOf),
                    )
                dev.dshremote.protocol.v1alpha.TimelineNode.ContentCase.ASSISTANT_MESSAGE ->
                    assistantTimelineEntry(node.eventId, node.sourceSequence, node.assistantMessage)
                dev.dshremote.protocol.v1alpha.TimelineNode.ContentCase.TOOL_PRESENTATION ->
                    toolTimelineEntry(node.eventId, node.sourceSequence, node.toolPresentation)
                else -> TimelineEntry(
                    id = node.eventId,
                    sourceSequence = node.sourceSequence,
                    kind = TimelineKind.UNSUPPORTED,
                    text = "No projected content",
                )
            }
        }
        val assistantFinalCount = snapshot.session.timelineList.count { node ->
            node.contentCase == dev.dshremote.protocol.v1alpha.TimelineNode.ContentCase.ASSISTANT_MESSAGE &&
                node.assistantMessage.final
        }
        val assistantPartialCount = snapshot.session.timelineList.count { node ->
            node.contentCase == dev.dshremote.protocol.v1alpha.TimelineNode.ContentCase.ASSISTANT_MESSAGE &&
                !node.assistantMessage.final
        }
        val phase = if (recoveryPending) ConnectionPhase.RECONCILED else ConnectionPhase.READY
        val log = if (phase == ConnectionPhase.RECONCILED) {
            "Reconciled from a fresh snapshot through sequence ${snapshot.snapshotEndSequence}."
        } else {
            "Applied snapshot through sequence ${snapshot.snapshotEndSequence}."
        }
        recoveryPending = false
        val currentReading = state.value
        val anchorUnavailable = !currentReading.followTail &&
            currentReading.readingAnchorId != null &&
            entries.none { it.id == currentReading.readingAnchorId }
        val synchronizedAtMs = System.currentTimeMillis()
        val snapshotUsage = if (snapshot.session.hasUsage()) {
            sessionUsageProjectionOf(snapshot.session.usage)
        } else {
            null
        }
        val snapshotSubagent = if (snapshot.session.hasSubagent()) {
            subagentProjectionOf(snapshot.session.subagent)
        } else {
            null
        }
        // S-mode-select: the log-resolved preset this Session runs, absent when
        // the Host log records none (absence is never rendered as a default).
        val snapshotAgentPreset = if (snapshot.session.hasAgentPreset()) {
            snapshot.session.agentPreset
        } else {
            null
        }
        // S-session-admin: the log-resolved model triple this Session requests
        // with; absent while the Host log records no request header.
        val snapshotModel = if (snapshot.session.hasModel()) {
            modelSelectionOf(snapshot.session.model)
        } else {
            null
        }
        // S-policy: the durable policy fold — the rule list is exact (empty ⟺
        // no rules) and an absent budget means none is set.
        val snapshotRules = snapshot.session.approvalRulesList.map(::approvalRuleStateOf)
        val snapshotBudget = if (snapshot.session.hasBudget()) {
            sessionBudgetStateOf(snapshot.session.budget)
        } else {
            null
        }
        Log.i(
            LOG_TAG,
            "snapshot_ready epoch=$epoch ready_ms=${elapsedSinceConnectMs()} " +
                "timeline_count=${entries.size} assistant_final_count=$assistantFinalCount " +
                "assistant_partial_count=$assistantPartialCount cursor=${snapshot.snapshotEndSequence}",
        )
        update(phase, log) {
            copy(
                streamId = snapshot.streamId,
                projectionVersion = snapshot.projectionVersion,
                cursor = snapshot.snapshotEndSequence,
                sessionId = snapshot.session.sessionId,
                sessionTitle = snapshot.session.title.ifEmpty { null },
                sessionRunning = snapshot.session.running,
                sessionUsage = snapshotUsage,
                sessionSubagent = snapshotSubagent,
                sessionAgentPreset = snapshotAgentPreset,
                sessionModel = snapshotModel,
                activityRevision = snapshot.session.activityRevision.takeIf { it > 0 },
                sessions = sessions.updateSession(
                    sessionId = snapshot.session.sessionId,
                    title = snapshot.session.title.takeIf(String::isNotBlank),
                    running = snapshot.session.running,
                    pendingInputCount = snapshot.session.pendingInputCount,
                ).replaceSessionUsage(snapshot.session.sessionId, snapshotUsage)
                    .replaceSessionSubagent(snapshot.session.sessionId, snapshotSubagent)
                    .replaceSessionAgentPreset(snapshot.session.sessionId, snapshotAgentPreset)
                    .replaceSessionModel(snapshot.session.sessionId, snapshotModel),
                timeline = entries,
                approvals = snapshot.session.approvalsList.map(::approvalStateOf),
                approvalRules = snapshotRules,
                sessionBudget = snapshotBudget,
                historyTruncated = snapshot.session.historyTruncated,
                offlineSnapshot = false,
                offlineCacheSavedAtMs = synchronizedAtMs,
                offlineCacheTruncated = false,
                readingAnchorId = if (anchorUnavailable) entries.firstOrNull()?.id else readingAnchorId,
                readingOffsetPx = if (anchorUnavailable) 0 else readingOffsetPx,
                readingAnchorUnavailable = anchorUnavailable,
                failure = null,
            )
        }
        captureProjection(persistImmediately = true)
        acknowledge(snapshot.streamId, snapshot.projectionVersion, snapshot.snapshotEndSequence)
        startAuthorizationProbes()
    }

    private fun applyResumeAccepted(frame: ServerFrame) {
        val accepted = frame.resumeAccepted
        val resume = pendingResume
        val current = state.value
        if (
            resume == null ||
            current.sessionId != resume.sessionId ||
            current.cursor != resume.sequence ||
            !isValidResumeAcceptance(
                plan = resume,
                streamId = accepted.streamId,
                projectionVersion = accepted.projectionVersion,
                resumedAfterSequence = accepted.resumedAfterSequence,
                latestSequence = accepted.latestSequence,
            )
        ) {
            pendingResume = null
            resumeReplayTarget = null
            update(ConnectionPhase.FAILED, "Host returned an invalid resume acceptance; refusing the replay domain.") {
                copy(failure = "Invalid retained projection resume acceptance")
            }
            return
        }

        pendingResume = null
        val caughtUp = accepted.latestSequence == accepted.resumedAfterSequence
        resumeReplayTarget = accepted.latestSequence.takeUnless { caughtUp }
        recoveryPending = false
        val synchronizedAtMs = System.currentTimeMillis()
        update(
            if (caughtUp) ConnectionPhase.RECONCILED else ConnectionPhase.SYNCHRONIZING,
            if (caughtUp) {
                "Retained projection was already current at sequence ${accepted.resumedAfterSequence}."
            } else {
                "Resume accepted after sequence ${accepted.resumedAfterSequence}; replaying through ${accepted.latestSequence}."
            },
        ) {
            copy(
                streamId = accepted.streamId,
                projectionVersion = accepted.projectionVersion,
                cursor = accepted.resumedAfterSequence,
                offlineSnapshot = !caughtUp,
                offlineCacheSavedAtMs = if (caughtUp) synchronizedAtMs else offlineCacheSavedAtMs,
                failure = null,
            )
        }
        if (caughtUp) captureProjection(persistImmediately = true)
        acknowledge(accepted.streamId, accepted.projectionVersion, accepted.resumedAfterSequence)
        startAuthorizationProbes()
    }

    private fun applyEvent(event: ProjectedEvent) {
        val currentState = state.value
        val current = currentState.cursor ?: return
        if (
            !isExpectedProjectedEventDomain(
                streamId = currentState.streamId,
                projectionVersion = currentState.projectionVersion,
                sessionId = currentState.sessionId,
                eventStreamId = event.streamId,
                eventProjectionVersion = event.projectionVersion,
                eventSessionId = event.sessionId,
            )
        ) {
            resumeReplayTarget = null
            update(ConnectionPhase.FAILED, "Projected event escaped the accepted stream domain; refusing it.") {
                copy(failure = "Invalid projected event stream domain")
            }
            return
        }
        when (val decision = CursorPolicy.decide(current, event.sequence)) {
            ApplyDecision.Duplicate -> update(log = "Suppressed duplicate sequence ${event.sequence}.")
            ApplyDecision.Contiguous -> {
                val replayTarget = resumeReplayTarget
                val completesResume = replayTarget != null && event.sequence >= replayTarget
                if (completesResume) resumeReplayTarget = null
                val resultingPhase = when {
                    replayTarget != null && !completesResume -> ConnectionPhase.SYNCHRONIZING
                    completesResume || state.value.phase == ConnectionPhase.RECONCILED -> ConnectionPhase.RECONCILED
                    else -> ConnectionPhase.READY
                }
                update(resultingPhase, "Applied contiguous sequence ${event.sequence} and ACKed it.") {
                    copy(
                        cursor = event.sequence,
                        offlineSnapshot = if (completesResume) false else offlineSnapshot,
                        offlineCacheSavedAtMs = if (completesResume) System.currentTimeMillis() else offlineCacheSavedAtMs,
                        timeline = when (event.payloadCase) {
                            ProjectedEvent.PayloadCase.ASSISTANT_DELTA -> TimelineReducer.assistantDelta(
                                timeline = timeline,
                                eventId = event.eventId,
                                sourceSequence = event.sourceSequence,
                                messageId = event.assistantDelta.messageId,
                                delta = event.assistantDelta.textDelta,
                            )
                            ProjectedEvent.PayloadCase.ASSISTANT_COMPLETED -> TimelineReducer.assistantCompleted(
                                timeline = timeline,
                                eventId = event.eventId,
                                sourceSequence = event.sourceSequence,
                                messageId = event.assistantCompleted.messageId,
                                text = event.assistantCompleted.text,
                            )
                            ProjectedEvent.PayloadCase.SESSION_STATUS_CHANGED -> timeline + TimelineEntry(
                                id = event.eventId,
                                sourceSequence = event.sourceSequence,
                                kind = TimelineKind.SESSION,
                                text = if (event.sessionStatusChanged.running) {
                                    "Running"
                                } else {
                                    // S-vocab-ext: durable per-turn terminal fact; absent when
                                    // the transition is not a turn end or the kind is unmapped.
                                    val reason = turnEndReasonLabel(event.sessionStatusChanged)
                                    if (reason != null) "Idle · $reason" else "Idle"
                                },
                            )
                            ProjectedEvent.PayloadCase.USER_MESSAGE_ADDED -> timeline + userTimelineEntry(
                                eventId = event.eventId,
                                sourceSequence = event.sourceSequence,
                                text = event.userMessageAdded.text,
                                messageId = event.userMessageAdded.messageId,
                                source = if (event.userMessageAdded.hasSource()) {
                                    messageSourceProjectionOf(event.userMessageAdded.source)
                                } else {
                                    null
                                },
                                attachments = event.userMessageAdded.attachmentsList
                                    .take(MAX_MESSAGE_ATTACHMENTS)
                                    .map(::imageAttachmentProjectionOf),
                            )
                            ProjectedEvent.PayloadCase.TOOL_PRESENTATION_CHANGED -> TimelineReducer.toolChanged(
                                timeline,
                                toolTimelineEntry(
                                    event.eventId,
                                    event.sourceSequence,
                                    event.toolPresentationChanged.presentation,
                                ),
                            )
                            ProjectedEvent.PayloadCase.SESSION_TITLE_CHANGED -> timeline
                            ProjectedEvent.PayloadCase.APPROVAL_CHANGED -> timeline
                            ProjectedEvent.PayloadCase.INPUT_ATTENTION_CHANGED -> timeline
                            ProjectedEvent.PayloadCase.USAGE_CHANGED -> timeline
                            ProjectedEvent.PayloadCase.SUBAGENT_CHANGED -> timeline
                            ProjectedEvent.PayloadCase.AGENT_PRESET_CHANGED -> timeline + TimelineEntry(
                                id = event.eventId,
                                sourceSequence = event.sourceSequence,
                                kind = TimelineKind.SESSION,
                                // S-mode-select: durable selection fact; the label comes
                                // from the roster, falling back to the raw id.
                                text = "模式 · " + (
                                    agentPresetLabel(agentPresets, event.agentPresetChanged.agentPreset)
                                        ?: event.agentPresetChanged.agentPreset
                                    ),
                            )
                            ProjectedEvent.PayloadCase.MODEL_CHANGED -> timeline + TimelineEntry(
                                id = event.eventId,
                                sourceSequence = event.sourceSequence,
                                kind = TimelineKind.SESSION,
                                // S-session-admin: durable next-request selection fact
                                // (request/header reason="change"); names resolve against
                                // the hello catalog, falling back to raw ids.
                                text = "模型 · " + modelDisplayLabel(
                                    modelCatalog,
                                    modelSelectionOf(event.modelChanged),
                                ),
                            )
                            // S-artifacts: roster-only fact — the timeline entry would
                            // duplicate the registering tool card one frame earlier.
                            ProjectedEvent.PayloadCase.ARTIFACT_REGISTERED -> timeline
                            // S-policy: the frame carries the whole fold; the entry
                            // narrates the one durable change it crossed with (grant,
                            // revoke, or budget set). A no-op replay adds nothing.
                            ProjectedEvent.PayloadCase.POLICY_CHANGED -> {
                                val label = policyChangeLabel(
                                    oldRules = approvalRules,
                                    oldBudget = sessionBudget,
                                    changed = event.policyChanged,
                                )
                                if (label == null) {
                                    timeline
                                } else {
                                    timeline + TimelineEntry(
                                        id = event.eventId,
                                        sourceSequence = event.sourceSequence,
                                        kind = TimelineKind.SESSION,
                                        text = label,
                                    )
                                }
                            }
                            else -> timeline + TimelineEntry(
                                id = event.eventId,
                                sourceSequence = event.sourceSequence,
                                kind = TimelineKind.UNSUPPORTED,
                                text = "No projected event payload",
                            )
                        },
                        sessionTitle = if (event.payloadCase == ProjectedEvent.PayloadCase.SESSION_TITLE_CHANGED) {
                            event.sessionTitleChanged.title
                        } else {
                            sessionTitle
                        },
                        sessionRunning = if (event.payloadCase == ProjectedEvent.PayloadCase.SESSION_STATUS_CHANGED) {
                            event.sessionStatusChanged.running
                        } else {
                            sessionRunning
                        },
                        sessionUsage = if (event.payloadCase == ProjectedEvent.PayloadCase.USAGE_CHANGED) {
                            sessionUsage.mergedWith(event.usageChanged)
                        } else {
                            sessionUsage
                        },
                        sessionSubagent = if (event.payloadCase == ProjectedEvent.PayloadCase.SUBAGENT_CHANGED) {
                            sessionSubagent.mergedWith(event.subagentChanged.subagent)
                        } else {
                            sessionSubagent
                        },
                        sessionAgentPreset = if (event.payloadCase == ProjectedEvent.PayloadCase.AGENT_PRESET_CHANGED) {
                            event.agentPresetChanged.agentPreset
                        } else {
                            sessionAgentPreset
                        },
                        sessionModel = if (event.payloadCase == ProjectedEvent.PayloadCase.MODEL_CHANGED) {
                            modelSelectionOf(event.modelChanged)
                        } else {
                            sessionModel
                        },
                        activityRevision = if (event.payloadCase == ProjectedEvent.PayloadCase.SESSION_STATUS_CHANGED) {
                            event.sessionStatusChanged.activityRevision.takeIf { it > 0 }
                        } else {
                            activityRevision
                        },
                        sessions = when (event.payloadCase) {
                            ProjectedEvent.PayloadCase.SESSION_STATUS_CHANGED -> sessions.updateSession(
                                sessionId = event.sessionId,
                                running = event.sessionStatusChanged.running,
                            )
                            ProjectedEvent.PayloadCase.SESSION_TITLE_CHANGED -> sessions.updateSession(
                                sessionId = event.sessionId,
                                title = event.sessionTitleChanged.title,
                            )
                            ProjectedEvent.PayloadCase.APPROVAL_CHANGED -> sessions.updateSession(
                                sessionId = event.sessionId,
                                pendingApprovalCount = if (event.approvalChanged.hasPending()) {
                                    approvals.count { it.approvalId != event.approvalChanged.approvalId } + 1
                                } else {
                                    approvals.count {
                                        it.approvalId != event.approvalChanged.approvalId ||
                                            it.revision != event.approvalChanged.revision
                                    }
                                },
                            )
                            ProjectedEvent.PayloadCase.INPUT_ATTENTION_CHANGED -> sessions.updateSession(
                                sessionId = event.sessionId,
                                pendingInputCount = event.inputAttentionChanged.pendingCount,
                            )
                            ProjectedEvent.PayloadCase.USAGE_CHANGED -> sessions.replaceSessionUsage(
                                sessionId = event.sessionId,
                                usage = sessions.find { it.sessionId == event.sessionId }
                                    ?.usage.mergedWith(event.usageChanged),
                            )
                            ProjectedEvent.PayloadCase.SUBAGENT_CHANGED -> sessions.replaceSessionSubagent(
                                sessionId = event.sessionId,
                                subagent = sessions.find { it.sessionId == event.sessionId }
                                    ?.subagent.mergedWith(event.subagentChanged.subagent),
                            )
                            ProjectedEvent.PayloadCase.AGENT_PRESET_CHANGED -> sessions.replaceSessionAgentPreset(
                                sessionId = event.sessionId,
                                agentPreset = event.agentPresetChanged.agentPreset,
                            )
                            ProjectedEvent.PayloadCase.MODEL_CHANGED -> sessions.replaceSessionModel(
                                sessionId = event.sessionId,
                                model = modelSelectionOf(event.modelChanged),
                            )
                            else -> sessions
                        },
                        approvals = if (event.payloadCase == ProjectedEvent.PayloadCase.APPROVAL_CHANGED) {
                            val changed = event.approvalChanged
                            if (changed.hasPending()) {
                                ApprovalInteractionReducer.upsert(approvals, approvalStateOf(changed.pending))
                            } else {
                                ApprovalInteractionReducer.resolve(approvals, changed.approvalId, changed.revision)
                            }
                        } else {
                            approvals
                        },
                        // S-policy: replace, never merge — each frame carries the
                        // complete new fold, so the lists stay exact.
                        approvalRules = if (event.payloadCase == ProjectedEvent.PayloadCase.POLICY_CHANGED) {
                            event.policyChanged.rulesList.map(::approvalRuleStateOf)
                        } else {
                            approvalRules
                        },
                        sessionBudget = if (event.payloadCase == ProjectedEvent.PayloadCase.POLICY_CHANGED) {
                            if (event.policyChanged.hasBudget()) {
                                sessionBudgetStateOf(event.policyChanged.budget)
                            } else {
                                null
                            }
                        } else {
                            sessionBudget
                        },
                        artifacts = if (event.payloadCase == ProjectedEvent.PayloadCase.ARTIFACT_REGISTERED) {
                            artifacts.withArtifactRegistered(
                                artifactEntryStateOf(event.artifactRegistered.artifact),
                            )
                        } else {
                            artifacts
                        },
                    )
                }
                val epoch = connectionEpoch.get()
                val shouldLog = event.payloadCase != ProjectedEvent.PayloadCase.ASSISTANT_DELTA ||
                    firstLoggedAssistantDeltaEpoch.getAndSet(epoch) != epoch
                if (shouldLog) {
                    Log.i(
                        LOG_TAG,
                        "event_applied epoch=$epoch sequence=${event.sequence} " +
                            "kind=${event.payloadCase.name} elapsed_ms=${elapsedSinceConnectMs()}",
                    )
                }
                captureProjection(persistImmediately = false)
                acknowledge(event.streamId, event.projectionVersion, event.sequence)
            }
            is ApplyDecision.Gap -> {
                update(
                    ConnectionPhase.GAP_DETECTED,
                    "Detected sequence gap: expected ${decision.expected}, received ${decision.actual}; refusing to skip.",
                )
                val currentState = state.value
                val streamId = currentState.streamId ?: return
                val sessionId = selectedSessionId ?: return
                subscribeResume(
                    ResumePlan(
                        sessionId = sessionId,
                        streamId = streamId,
                        projectionVersion = currentState.projectionVersion ?: PROJECTION_VERSION,
                        sequence = current,
                    ),
                    "Requesting the retained suffix after sequence $current.",
                )
            }
        }
    }

    private fun subscribeFresh(log: String) {
        val sessionId = selectedSessionId ?: return
        pendingResume = null
        resumeReplayTarget = null
        update(ConnectionPhase.SYNCHRONIZING, log)
        write(
            ClientFrame.newBuilder()
                .setFrameId(nextFrameId())
                .setSubscribe(
                    Subscribe.newBuilder()
                        .setSessionId(sessionId)
                        .setForceFreshSnapshot(true),
                )
                .build(),
        )
    }

    private fun subscribeResume(resume: ResumePlan, log: String) {
        pendingResume = resume
        update(ConnectionPhase.SYNCHRONIZING, log)
        write(
            ClientFrame.newBuilder()
                .setFrameId(nextFrameId())
                .setSubscribe(
                    Subscribe.newBuilder()
                        .setSessionId(resume.sessionId)
                        .setResume(
                            ResumeCursor.newBuilder()
                                .setStreamId(resume.streamId)
                                .setProjectionVersion(resume.projectionVersion)
                                .setHighestContiguousSequence(resume.sequence),
                        ),
                )
                .build(),
        )
    }

    private fun acknowledge(streamId: String, projectionVersion: Int, sequence: Long) {
        write(
            ClientFrame.newBuilder()
                .setFrameId(nextFrameId())
                .setAck(
                    Ack.newBuilder()
                        .setStreamId(streamId)
                        .setProjectionVersion(projectionVersion)
                        .setHighestContiguousSequence(sequence),
                )
                .build(),
        )
    }

    private fun sendPendingCommand(command: PendingRemoteCommand, log: String) {
        val commandBuilder = Command.newBuilder()
            .setCommandId(command.commandId)
            .setSessionId(command.sessionId)
        when (command.operation) {
            PendingCommandOperation.SEND_INPUT -> commandBuilder
                .setControl(
                    ControlFence.newBuilder()
                        .setEpoch(requireNotNull(command.controlEpoch))
                        .setToken(requireNotNull(command.controlToken)),
                )
                .setSendInput(
                    SendInput.newBuilder().apply {
                        setText(requireNotNull(command.text))
                        // S-blob: committed content addresses only — every id
                        // passed the blob channel before the reservation.
                        command.attachmentIds?.forEach(::addAttachmentIds)
                    },
                )
            PendingCommandOperation.STOP -> commandBuilder
                .setControl(
                    ControlFence.newBuilder()
                        .setEpoch(requireNotNull(command.controlEpoch))
                        .setToken(requireNotNull(command.controlToken)),
                )
                .setStopActive(
                    StopActive.newBuilder().setExpectedActivityRevision(requireNotNull(command.expectedActivityRevision)),
                )
            PendingCommandOperation.DECIDE_APPROVAL -> commandBuilder.setDecideApproval(
                DecideApproval.newBuilder()
                    .setApprovalId(requireNotNull(command.approvalId))
                    .setRevision(requireNotNull(command.approvalRevision))
                    .setDecision(
                        when (requireNotNull(command.approvalDecision)) {
                            PendingApprovalDecision.ALLOW_ONCE -> ApprovalDecision.APPROVAL_DECISION_ALLOW_ONCE
                            PendingApprovalDecision.DENY -> ApprovalDecision.APPROVAL_DECISION_DENY
                            PendingApprovalDecision.ALLOW_SAME_KIND ->
                                ApprovalDecision.APPROVAL_DECISION_ALLOW_SAME_KIND
                        },
                    ),
            )
            // S-mode-select: lease-free — no control fence crosses for either.
            PendingCommandOperation.CREATE_SESSION -> commandBuilder.setCreateSession(
                CreateSession.newBuilder().apply {
                    command.agentPreset?.let(::setAgentPreset)
                },
            )
            PendingCommandOperation.SELECT_AGENT_PRESET -> commandBuilder.setSelectAgentPreset(
                SelectAgentPreset.newBuilder().setAgentPreset(requireNotNull(command.agentPreset)),
            )
            // S-session-admin: fenced like send_input (mid-session next-request
            // effect); an absent effort clears any inherited effort at the owner.
            PendingCommandOperation.SELECT_MODEL -> commandBuilder
                .setControl(
                    ControlFence.newBuilder()
                        .setEpoch(requireNotNull(command.controlEpoch))
                        .setToken(requireNotNull(command.controlToken)),
                )
                .setSelectModel(
                    SelectModel.newBuilder().apply {
                        val selection = requireNotNull(command.modelSelection)
                        setProvider(selection.provider)
                        setModel(selection.model)
                        selection.reasoningEffort?.let(::setReasoningEffort)
                    },
                )
            // S-session-admin: lease-free — the source log is never mutated, and
            // the preallocated child id makes a retry converge Host-side.
            PendingCommandOperation.FORK_SESSION -> commandBuilder.setForkSession(
                ForkSession.newBuilder().apply {
                    setChildSessionId(requireNotNull(command.childSessionId))
                    command.forkAtSeq?.let(::setAtSeq)
                },
            )
            // S-policy: both are lease-free durable policy mutations.
            PendingCommandOperation.REVOKE_APPROVAL_RULE -> commandBuilder.setRevokeApprovalRule(
                RevokeApprovalRule.newBuilder().setRuleId(requireNotNull(command.ruleId)),
            )
            PendingCommandOperation.SET_SESSION_BUDGET -> commandBuilder.setSetSessionBudget(
                SetSessionBudget.newBuilder().setMaxTotalTokens(requireNotNull(command.maxTotalTokens)),
            )
        }
        val frame = ClientFrame.newBuilder()
            .setFrameId(nextFrameId())
            .setCommand(commandBuilder)
            .build()
        if (write(frame)) {
            update(log = log) {
                copy(pendingCommand = command.toStatus(), commandWarning = null)
            }
        } else {
            update(log = "The command remains protected locally until authenticated transport is ready.") {
                copy(
                    pendingCommand = command.toStatus(),
                    commandWarning = "Command not written; reconnect or reconcile with the same command id.",
                )
            }
        }
    }

    private fun settlePendingCommand(receipt: CommandReceipt) {
        val current = synchronized(commandLock) {
            pendingCommand?.takeIf { it.commandId == receipt.commandId } ?: return
        }
        when (receipt.outcome) {
            "RECEIVED", "REQUESTED", "UNKNOWN" -> {
                val next = current.withPhase(
                    when (receipt.outcome) {
                        "RECEIVED" -> PendingCommandPhase.RECEIVED
                        "REQUESTED" -> PendingCommandPhase.REQUESTED
                        else -> PendingCommandPhase.UNKNOWN
                    },
                )
                val saved = runCatching { pendingCommandStore.save(next) }
                synchronized(commandLock) {
                    pendingCommand?.authorityBinding?.fill(0)
                    pendingCommand?.requestFingerprint?.fill(0)
                    pendingCommand = next
                }
                mutableState.update { state ->
                    state.copy(
                        pendingCommand = next.toStatus(),
                        commandWarning = saved.exceptionOrNull()?.let {
                            "Could not advance protected command state; the original same-ID record remains authoritative."
                        },
                    )
                }
            }
            "COMMITTED", "STOPPED", "REJECTED" -> {
                val cleared = runCatching { pendingCommandStore.clear() }
                synchronized(commandLock) {
                    if (cleared.isSuccess) {
                        pendingCommand?.authorityBinding?.fill(0)
                        pendingCommand?.requestFingerprint?.fill(0)
                        pendingCommand = null
                    } else {
                        commandRecoveryBlocked = true
                    }
                    if (receipt.outcome == "REJECTED" && receipt.errorCode.contains("CONTROL_")) {
                        activeControl = null
                    }
                }
                if (
                    receipt.outcome == "COMMITTED" &&
                    current.operation == PendingCommandOperation.SEND_INPUT &&
                    state.value.sessionId == current.sessionId
                ) {
                    // S-blob: the staged images rode this send; only the durable
                    // COMMITTED clears them (a REJECTED send keeps them staged).
                    mutableState.update { state ->
                        state.copy(
                            localDraft = "",
                            composerImages = emptyList(),
                            committedAttachments = emptyList(),
                            attachmentSend = null,
                        )
                    }
                    updateCachedPreferences(current.sessionId, draft = "")
                }
                // S-mode-select: a committed selection converges the local view at
                // once; the Host-logged agent-preset/selected event confirms it.
                if (
                    receipt.outcome == "COMMITTED" &&
                    current.operation == PendingCommandOperation.SELECT_AGENT_PRESET
                ) {
                    mutableState.update { state ->
                        state.copy(
                            sessionAgentPreset = if (state.sessionId == current.sessionId) {
                                current.agentPreset
                            } else {
                                state.sessionAgentPreset
                            },
                            sessions = state.sessions.replaceSessionAgentPreset(
                                current.sessionId,
                                current.agentPreset,
                            ),
                        )
                    }
                }
                // S-session-admin: a committed model selection converges the local
                // view at once; the Host-logged request/header (change) event
                // confirms it.
                if (
                    receipt.outcome == "COMMITTED" &&
                    current.operation == PendingCommandOperation.SELECT_MODEL
                ) {
                    mutableState.update { state ->
                        state.copy(
                            sessionModel = if (state.sessionId == current.sessionId) {
                                current.modelSelection
                            } else {
                                state.sessionModel
                            },
                            sessions = state.sessions.replaceSessionModel(
                                current.sessionId,
                                current.modelSelection,
                            ),
                        )
                    }
                }
                // S-policy: a BUDGET_EXHAUSTED refusal IS the owner's admission
                // verdict — fold it into the gate state so the UI pre-warns
                // instead of inviting another refused send. (Policy commits need
                // no local convergence: the Host pushes policy_changed itself.)
                if (
                    receipt.outcome == "REJECTED" &&
                    receipt.errorCode == "ERROR_CODE_BUDGET_EXHAUSTED" &&
                    current.operation == PendingCommandOperation.SEND_INPUT
                ) {
                    mutableState.update { state ->
                        if (state.sessionId == current.sessionId) {
                            state.copy(sessionBudget = state.sessionBudget?.copy(exhausted = true))
                        } else {
                            state
                        }
                    }
                }
                mutableState.update { state ->
                    state.copy(
                        pendingCommand = if (cleared.isSuccess) null else current.toStatus(),
                        controlLease = synchronized(commandLock) { activeControl?.toStatus() },
                        commandRecoveryBlocked = cleared.isFailure,
                        commandWarning = cleared.exceptionOrNull()?.let {
                            "Terminal receipt arrived, but protected command cleanup failed; sending remains blocked until explicit re-pairing."
                        },
                    )
                }
                // S-mode-select: a committed creation opens the fresh blank
                // Session — hidden from the directory until its first turn, but
                // resolvable for subscription.
                if (
                    receipt.outcome == "COMMITTED" &&
                    current.operation == PendingCommandOperation.CREATE_SESSION
                ) {
                    openCreatedSession(current.sessionId, current.agentPreset)
                }
                // S-session-admin: a committed fork opens the seeded child
                // Session; the fresh snapshot carries the inherited prefix.
                if (
                    receipt.outcome == "COMMITTED" &&
                    current.operation == PendingCommandOperation.FORK_SESSION
                ) {
                    openCreatedSession(requireNotNull(current.childSessionId), null)
                }
            }
            else -> {
                val next = current.withPhase(PendingCommandPhase.UNKNOWN)
                runCatching { pendingCommandStore.save(next) }
                synchronized(commandLock) {
                    pendingCommand?.authorityBinding?.fill(0)
                    pendingCommand?.requestFingerprint?.fill(0)
                    pendingCommand = next
                }
                mutableState.update { state ->
                    state.copy(
                        pendingCommand = next.toStatus(),
                        commandWarning = "Host returned an unknown command outcome; reconcile with the same id.",
                    )
                }
            }
        }
    }

    private fun write(frame: ClientFrame): Boolean =
        synchronized(writeLock) {
            val written = transport?.send(frame) == true
            if (!written) {
                update(log = "Ignored an application frame before authenticated transport readiness.")
            }
            written
        }

    // --- S-blob channel seams (ADR-005) -------------------------------------
    // Thin mappings from the pipeline channel interfaces onto blob frames.
    // Every op is one request/one correlated result; the assembler/fetch
    // server on the Host keeps failures transfer/fetch-scoped, and this side
    // mirrors that: carrier faults surface as IOException (the pipelines hold
    // the durable resume state), wire refusals as BlobTransferWireException.

    /** Upload channel bound to this client's authenticated carrier. */
    internal fun blobUploadChannel(): BlobUploadChannel = uploadChannel

    /** Fetch channel bound to this client's authenticated carrier. */
    internal fun blobFetchChannel(): BlobFetchChannel = fetchChannel

    private fun blobFrame(build: ClientFrame.Builder.() -> Unit): ClientFrame =
        ClientFrame.newBuilder().setFrameId(nextFrameId()).apply(build).build()

    private suspend fun transferRoundTrip(transferId: String, frame: ClientFrame): BlobTransferResult {
        val deferred = CompletableDeferred<BlobTransferResult>()
        val registered = synchronized(blobLock) {
            if (pendingTransfers.containsKey(transferId)) {
                false
            } else {
                pendingTransfers[transferId] = deferred
                true
            }
        }
        if (!registered) {
            throw BlobTransferWireException(
                BlobTransferErrorCode.BLOB_TRANSFER_ERROR_DECLARATION_CONFLICT.name,
                "A transfer with this id is already in flight",
            )
        }
        try {
            if (!write(frame)) throw IOException("Host transport is not connected")
            return withTimeout(BLOB_ROUND_TRIP_TIMEOUT_MS) { deferred.await() }
        } catch (error: TimeoutCancellationException) {
            throw IOException("Host blob transfer round trip timed out")
        } finally {
            synchronized(blobLock) { pendingTransfers.remove(transferId) }
        }
    }

    private suspend fun fetchRoundTrip(fetchId: String, frame: ClientFrame): BlobFetchResult {
        val deferred = CompletableDeferred<BlobFetchResult>()
        val registered = synchronized(blobLock) {
            if (pendingFetches.containsKey(fetchId)) {
                false
            } else {
                pendingFetches[fetchId] = deferred
                true
            }
        }
        if (!registered) {
            throw BlobTransferWireException(
                BlobFetchErrorCode.BLOB_FETCH_ERROR_CONFLICT.name,
                "A fetch with this id is already in flight",
            )
        }
        try {
            if (!write(frame)) throw IOException("Host transport is not connected")
            return withTimeout(BLOB_ROUND_TRIP_TIMEOUT_MS) { deferred.await() }
        } catch (error: TimeoutCancellationException) {
            throw IOException("Host blob fetch round trip timed out")
        } finally {
            synchronized(blobLock) { pendingFetches.remove(fetchId) }
        }
    }

    private val uploadChannel = object : BlobUploadChannel {
        override suspend fun begin(declaration: BlobUploadDeclaration): Long {
            val begin = BlobTransferBegin.newBuilder()
                .setTransferId(declaration.transferId)
                .setSha256Hex(declaration.sha256Hex)
                .setTotalBytes(declaration.totalBytes)
            declaration.mediaType?.let(begin::setMediaType)
            val result = transferRoundTrip(declaration.transferId, blobFrame { setBlobBegin(begin) })
            if (result.hasError()) {
                throw BlobTransferWireException(result.error.code.name, result.error.detail)
            }
            return result.receivedBytes
        }

        override suspend fun status(transferId: String): Long? {
            val result = transferRoundTrip(
                transferId,
                blobFrame {
                    setBlobControl(
                        BlobTransferControl.newBuilder()
                            .setTransferId(transferId)
                            .setAction(BlobTransferAction.BLOB_TRANSFER_ACTION_STATUS),
                    )
                },
            )
            if (result.hasError()) {
                if (result.error.code == BlobTransferErrorCode.BLOB_TRANSFER_ERROR_UNKNOWN_TRANSFER) return null
                throw BlobTransferWireException(result.error.code.name, result.error.detail)
            }
            return result.receivedBytes
        }

        override suspend fun chunk(transferId: String, offset: Long, data: ByteArray): Long {
            val result = transferRoundTrip(
                transferId,
                blobFrame {
                    setBlobChunk(
                        BlobTransferChunk.newBuilder()
                            .setTransferId(transferId)
                            .setOffset(offset)
                            .setData(ByteString.copyFrom(data)),
                    )
                },
            )
            if (result.hasError()) {
                if (result.error.code == BlobTransferErrorCode.BLOB_TRANSFER_ERROR_OFFSET_MISMATCH) {
                    throw BlobUploadOffsetException(
                        if (result.error.hasResumeOffset()) result.error.resumeOffset else 0L,
                    )
                }
                throw BlobTransferWireException(result.error.code.name, result.error.detail)
            }
            return result.receivedBytes
        }

        override suspend fun complete(transferId: String): String {
            val result = transferRoundTrip(
                transferId,
                blobFrame {
                    setBlobControl(
                        BlobTransferControl.newBuilder()
                            .setTransferId(transferId)
                            .setAction(BlobTransferAction.BLOB_TRANSFER_ACTION_COMPLETE),
                    )
                },
            )
            if (result.hasError()) {
                throw BlobTransferWireException(result.error.code.name, result.error.detail)
            }
            if (!result.hasBlobId()) {
                throw BlobTransferWireException(
                    BlobTransferErrorCode.BLOB_TRANSFER_ERROR_UNSPECIFIED.name,
                    "Host completed the transfer without a blob id",
                )
            }
            return result.blobId
        }

        override suspend fun abort(transferId: String) {
            val result = transferRoundTrip(
                transferId,
                blobFrame {
                    setBlobControl(
                        BlobTransferControl.newBuilder()
                            .setTransferId(transferId)
                            .setAction(BlobTransferAction.BLOB_TRANSFER_ACTION_ABORT),
                    )
                },
            )
            if (result.hasError() && result.error.code != BlobTransferErrorCode.BLOB_TRANSFER_ERROR_UNKNOWN_TRANSFER) {
                throw BlobTransferWireException(result.error.code.name, result.error.detail)
            }
        }
    }

    private val fetchChannel = object : BlobFetchChannel {
        override suspend fun chunk(source: BlobFetchSource, offset: Long, maxBytes: Int): ByteArray? {
            val key = fetchSourceKey(source)
            val fetchId = synchronized(blobLock) { openFetchSessions[key] } ?: openFetch(source, key)
            return try {
                fetchChunk(fetchId, offset)
            } catch (error: BlobTransferWireException) {
                // The Host sweeps idle fetch sessions: re-open once (the ACL is
                // re-proved) and retry the same offset before giving up.
                if (error.code != BlobFetchErrorCode.BLOB_FETCH_ERROR_UNKNOWN_FETCH.name) throw error
                val reopened = openFetch(source, key)
                fetchChunk(reopened, offset)
            }
        }
    }

    private fun fetchSourceKey(source: BlobFetchSource): String = when (source) {
        is BlobFetchSource.Attachment -> "attachment:${source.sessionId}:${source.attachmentId}"
        is BlobFetchSource.Artifact -> "artifact:${source.sessionId}:${source.artifactId}"
    }

    private suspend fun openFetch(source: BlobFetchSource, key: String): String {
        val fetchId = UUID.randomUUID().toString().replace("-", "")
        val open = BlobFetchOpen.newBuilder().setSessionId(source.sessionId)
        when (source) {
            is BlobFetchSource.Attachment -> open.setAttachmentId(source.attachmentId)
            is BlobFetchSource.Artifact -> open.setArtifactId(source.artifactId)
        }
        val result = fetchRoundTrip(
            fetchId,
            blobFrame { setBlobFetch(BlobFetchRequest.newBuilder().setFetchId(fetchId).setOpen(open)) },
        )
        when {
            result.hasOpened() -> {
                synchronized(blobLock) { openFetchSessions[key] = fetchId }
                return fetchId
            }
            result.hasError() -> throw BlobTransferWireException(result.error.code.name, result.error.detail)
            else -> throw BlobTransferWireException(
                BlobFetchErrorCode.BLOB_FETCH_ERROR_UNSPECIFIED.name,
                "Host answered a fetch open with neither facts nor error",
            )
        }
    }

    private suspend fun fetchChunk(fetchId: String, offset: Long): ByteArray? {
        val result = fetchRoundTrip(
            fetchId,
            blobFrame { setBlobFetch(BlobFetchRequest.newBuilder().setFetchId(fetchId).setChunkOffset(offset)) },
        )
        return when (result.outcomeCase) {
            BlobFetchResult.OutcomeCase.CHUNK -> {
                val chunk = result.chunk
                if (chunk.offset != offset) {
                    throw BlobTransferWireException(
                        BlobFetchErrorCode.BLOB_FETCH_ERROR_UNSPECIFIED.name,
                        "Host served a chunk at the wrong offset",
                    )
                }
                // Empty data marks source exhaustion (offset reached the total).
                if (chunk.data.isEmpty) null else chunk.data.toByteArray()
            }
            BlobFetchResult.OutcomeCase.ERROR ->
                throw BlobTransferWireException(result.error.code.name, result.error.detail)
            else -> throw BlobTransferWireException(
                BlobFetchErrorCode.BLOB_FETCH_ERROR_UNSPECIFIED.name,
                "Host answered a chunk request with no chunk",
            )
        }
    }

    // --- S-blob composer and fetch seams (ADR-005) ---------------------------

    private val blobIntake by lazy { BlobSourceIntake(ContentResolverBlobUriGateway(appContext.contentResolver)) }
    private val blobUploadPipeline by lazy {
        BlobUploadPipeline(
            stagingDir = File(appContext.cacheDir, "blob-uploads"),
            journal = BlobUploadStore(appContext, hostId),
            channel = uploadChannel,
        )
    }
    private val blobFetchPipeline by lazy {
        BlobFetchPipeline(cacheDir = File(appContext.cacheDir, "blob-cache"), channel = fetchChannel)
    }

    /**
     * 相册/拍照选择落点：入口即解析（MIME 复核、魔数嗅探、声明大小、部署
     * 界限与所选模型声明的模态），任何一项不满足都诚实拒绝，绝不先占位。
     */
    fun attachImage(uri: String) {
        scope.launch {
            val current = state.value
            val limits = current.attachmentLimits ?: return@launch
            if (current.stagedUpload != null) {
                update(log = "Attachment intake is blocked by an interrupted upload.") {
                    copy(commandWarning = "上次上传未完成——先续传或放弃，再添加新图片。")
                }
                return@launch
            }
            if (current.composerImages.any { it.key == uri }) return@launch
            if (current.composerImages.size + current.committedAttachments.size >= limits.maxImagesPerMessage) {
                update(log = "Attachment intake refused by the deployment count bound.") {
                    copy(commandWarning = "每条消息最多 ${limits.maxImagesPerMessage} 张图片。")
                }
                return@launch
            }
            // 模态门：仅当目录行明确声明了模态且不含 image 才在入口拒绝；
            // 未声明（空列表）保持未知，准入仍由 Host 重新围栏。
            val selection = current.sessionModel
            val row = selection?.let { sel ->
                current.modelCatalog.firstOrNull { it.id == sel.provider }
                    ?.models?.firstOrNull { it.id == sel.model }
            }
            if (row?.acceptsImages == false) {
                update(log = "Attachment intake refused: the selected model declares no image input.") {
                    copy(commandWarning = "当前模型未声明图片输入能力。")
                }
                return@launch
            }
            when (val resolution = blobIntake.resolve(uri)) {
                is BlobSourceResolution.Unavailable ->
                    update(log = "Attachment intake refused at the gateway.") {
                        copy(commandWarning = resolution.detail)
                    }
                is BlobSourceResolution.Resolved -> {
                    if (limits.mediaTypes.isNotEmpty() && resolution.mediaType !in limits.mediaTypes) {
                        update(log = "Attachment intake refused by the deployment media-type list.") {
                            copy(commandWarning = "此部署不接受 ${resolution.mediaType} 类型的图片。")
                        }
                        return@launch
                    }
                    mutableState.update {
                        it.copy(
                            composerImages = it.composerImages + ComposerImage(
                                key = uri,
                                previewUri = uri,
                                displayName = resolution.displayName,
                                mediaType = resolution.mediaType,
                            ),
                            commandWarning = null,
                        )
                    }
                    update(log = "Staged an image for the next send.")
                }
            }
        }
    }

    fun removeComposerImage(key: String) {
        mutableState.update {
            it.copy(composerImages = it.composerImages.filterNot { image -> image.key == key })
        }
    }

    fun removeCommittedImage(attachmentId: String) {
        mutableState.update {
            it.copy(committedAttachments = it.committedAttachments.filterNot { image -> image.attachmentId == attachmentId })
        }
    }

    /** 把加密日志里的中断上传反映到状态（hello 后、结算后、发送失败路径调用）。 */
    fun refreshStagedUpload() {
        if (state.value.storageSealedByLock) return
        val declaration = runCatching { blobUploadPipeline.stagedDeclaration() }.getOrNull()
        mutableState.update {
            it.copy(stagedUpload = declaration?.let { d -> StagedUploadNotice(displayName = d.displayName) })
        }
    }

    /**
     * 用户选择续传中断的上传：成功后该图片成为下一次发送的已提交附件。
     * 本地预览随提交清除，UI 以无缩略图的"已上传"行呈现——绝不伪装还有字节。
     */
    fun resumeStagedUpload() {
        scope.launch {
            val notice = state.value.stagedUpload ?: return@launch
            mutableState.update {
                it.copy(attachmentSend = AttachmentSendProgress(completed = 0, total = 1, resuming = true))
            }
            when (val outcome = blobUploadPipeline.resumeStaged()) {
                is BlobUploadOutcome.Success -> mutableState.update {
                    it.copy(
                        attachmentSend = null,
                        stagedUpload = null,
                        committedAttachments = it.committedAttachments + CommittedImage(
                            attachmentId = outcome.blobId,
                            displayName = notice.displayName,
                        ),
                        commandWarning = null,
                    )
                }
                is BlobUploadOutcome.Retryable -> mutableState.update {
                    it.copy(attachmentSend = null, commandWarning = outcome.detail)
                }
                is BlobUploadOutcome.Failed -> mutableState.update {
                    it.copy(attachmentSend = null, stagedUpload = null, commandWarning = outcome.detail)
                }
            }
            update(log = "Resumed the interrupted upload.")
        }
    }

    /** 用户撤销中断的上传：尽力中止 Host 侧并清理本地暂存。 */
    fun abandonStagedUpload() {
        scope.launch {
            blobUploadPipeline.abandon()
            mutableState.update { it.copy(stagedUpload = null) }
            update(log = "Abandoned the interrupted upload.")
        }
    }

    /** 时间线图片字节：投影声明（id 即摘要、声明大小）交叉核验后落缓存（S-blob）。 */
    suspend fun fetchAttachmentImage(sessionId: String, attachment: ImageAttachmentProjection): BlobFetchView =
        blobFetchPipeline.fetch(
            cacheKey = BlobFetchPipeline.cacheKeyForAttachment(attachment.attachmentId),
            source = BlobFetchSource.Attachment(attachment.attachmentId, sessionId),
            sha256Hex = attachment.attachmentId.removePrefix("sha256:"),
            expectedBytes = attachment.bytes.takeIf { it > 0 },
        ).toView()

    /** 截断产物的全文（S-blob）：Host 按会话 ACL 把 artifact id 解析为注册表路径。 */
    suspend fun fetchArtifactContent(sessionId: String, artifactId: String): BlobFetchView =
        blobFetchPipeline.fetch(
            cacheKey = BlobFetchPipeline.cacheKeyForArtifact(artifactId),
            source = BlobFetchSource.Artifact(artifactId, sessionId),
        ).toView()

    private fun BlobFetchOutcome.toView(): BlobFetchView = when (this) {
        is BlobFetchOutcome.Ready -> BlobFetchView.Ready(file, totalBytes)
        is BlobFetchOutcome.Retryable -> BlobFetchView.Retryable(detail)
        is BlobFetchOutcome.Failed -> BlobFetchView.Failed(detail)
    }

    private fun closeTransport() {
        synchronized(writeLock) {
            authorizationProbeJob?.cancel()
            authorizationProbeJob = null
            synchronized(heartbeatLock) { pendingHeartbeatNonce = null }
            pairingClient?.close()
            pairingClient = null
            transport?.close()
            transport = null
            reconciliationTransport?.close()
            reconciliationTransport = null
        }
        // Blob waits are transfer/fetch-scoped: a dying carrier retries them
        // (the pipelines hold the durable resume state), never fails the upload
        // or fetch itself.
        synchronized(blobLock) {
            val interruption = IOException("Host transport closed")
            pendingTransfers.values.forEach { it.completeExceptionally(interruption) }
            pendingTransfers.clear()
            pendingFetches.values.forEach { it.completeExceptionally(interruption) }
            pendingFetches.clear()
            openFetchSessions.clear()
        }
    }

    private fun startAuthorizationProbes() {
        val epoch = connectionEpoch.get()
        synchronized(writeLock) {
            authorizationProbeJob?.cancel()
            authorizationProbeJob = scope.launch {
                while (connectionEpoch.get() == epoch) {
                    delay(AUTHORIZATION_PROBE_INTERVAL_MS)
                    val current = state.value
                    if (current.streamId == null || current.projectionVersion == null) continue
                    val cursor = current.cursor ?: continue
                    if (
                        current.phase != ConnectionPhase.READY &&
                        current.phase != ConnectionPhase.RECONCILED &&
                        current.phase != ConnectionPhase.SYNCHRONIZING
                    ) continue
                    val nonce = UUID.randomUUID().toString()
                    synchronized(heartbeatLock) { pendingHeartbeatNonce = nonce }
                    val written = write(
                        ClientFrame.newBuilder()
                            .setFrameId(nextFrameId())
                            .setHeartbeat(Heartbeat.newBuilder().setNonce(nonce))
                            .build(),
                    )
                    if (firstLoggedAuthorizationProbeEpoch.getAndSet(epoch) != epoch) {
                        Log.i(LOG_TAG, "authorization_probe epoch=$epoch cursor=$cursor written=$written")
                    }
                    if (!written) {
                        synchronized(heartbeatLock) { pendingHeartbeatNonce = null }
                        failCarrier(epoch, IllegalStateException("Host heartbeat could not be sent"))
                        closeTransport()
                        return@launch
                    }
                    delay(AUTHORIZATION_PROBE_TIMEOUT_MS)
                    val timedOut = synchronized(heartbeatLock) {
                        if (pendingHeartbeatNonce == nonce) {
                            pendingHeartbeatNonce = null
                            true
                        } else {
                            false
                        }
                    }
                    if (timedOut) {
                        failCarrier(epoch, IllegalStateException("Host heartbeat timed out"))
                        closeTransport()
                        return@launch
                    }
                }
            }
        }
    }

    private fun stopAuthorizationProbes() {
        synchronized(writeLock) {
            authorizationProbeJob?.cancel()
            authorizationProbeJob = null
            synchronized(heartbeatLock) { pendingHeartbeatNonce = null }
        }
    }

    private suspend fun recoverPendingPairing(epoch: Long, pending: PendingHostRecoveryRecord) {
        try {
            if (connectionEpoch.get() != epoch) return
            pairingClient?.close()
            pairingClient = null
            mutableState.value = Gate0CState(
                phase = ConnectionPhase.RECONCILING_PAIRING,
                endpoint = "${pending.endpointHost}:${pending.endpointPort}",
                pairingVerificationCode = pending.verificationCode,
                pairedHostFingerprint = PairingProtocol.fingerprint(pending.hostPublicKey),
                pairingRecoveryPending = true,
                events = listOf(
                    "00 · Host confirmation was interrupted; proving the Host authorization commit with Noise IK.",
                ),
            )
            Log.i(
                LOG_TAG,
                "pairing_reconcile_start epoch=$epoch host=${PairingProtocol.fingerprint(pending.hostPublicKey)}",
            )
            var lastError: Throwable = IllegalStateException("Host authorization reconciliation did not complete")
            for ((attempt, delayMs) in RECOVERY_RETRY_DELAYS_MS.withIndex()) {
                if (connectionEpoch.get() != epoch) return
                if (delayMs > 0) delay(delayMs)
                if (connectionEpoch.get() != epoch) return
                update(
                    ConnectionPhase.RECONCILING_PAIRING,
                    "Authorization check ${attempt + 1} of ${RECOVERY_RETRY_DELAYS_MS.size}.",
                )
                val settlement = CompletableDeferred<Result<Unit>>()
                val probe = SecureRemoteTransport(
                    host = pending.secureTarget(),
                    identityStore = identityStore,
                    onFrame = { frame ->
                        if (frame.payloadCase == ServerFrame.PayloadCase.HELLO) {
                            settlement.complete(Result.success(Unit))
                        }
                    },
                    onError = { error -> settlement.complete(Result.failure(error)) },
                    onCompleted = {
                        settlement.complete(
                            Result.failure(IllegalStateException("Host closed the authorization check")),
                        )
                    },
                )
                reconciliationTransport = probe
                val result = try {
                    probe.connect()
                    withTimeoutOrNull(RECOVERY_ATTEMPT_TIMEOUT_MS) { settlement.await() }
                        ?: Result.failure(IllegalStateException("Host authorization check timed out"))
                } catch (error: Throwable) {
                    Result.failure(error)
                } finally {
                    probe.close()
                    if (reconciliationTransport === probe) reconciliationTransport = null
                }
                if (result.isSuccess) {
                    Log.i(LOG_TAG, "pairing_reconcile_proved epoch=$epoch attempt=${attempt + 1}")
                    if (connectionEpoch.get() != epoch) return
                    val confirmed = pending.confirmedHost(System.currentTimeMillis())
                    try {
                        pairedHostStore.save(confirmed)
                        pairedHostStore.clearPendingRecovery()
                        onPairedHostSaved?.invoke()
                    } finally {
                        confirmed.hostPublicKey.fill(0)
                    }
                    if (connectionEpoch.get() == epoch) connect()
                    return
                }
                lastError = result.exceptionOrNull() ?: lastError
                Log.w(
                    LOG_TAG,
                    "pairing_reconcile_retry epoch=$epoch attempt=${attempt + 1} " +
                        "type=${lastError.javaClass.simpleName} detail=${lastError.message}",
                )
            }
            if (connectionEpoch.get() == epoch) {
                update(
                    ConnectionPhase.FAILED,
                    "Could not prove whether the Host committed access; the protected recovery record was retained.",
                ) {
                    copy(
                        pairingRecoveryPending = true,
                        failure = lastError.message ?: lastError.javaClass.simpleName,
                    )
                }
            }
        } catch (error: Throwable) {
            if (connectionEpoch.get() == epoch) failCarrier(epoch, error)
        } finally {
            pending.hostPublicKey.fill(0)
        }
    }

    private fun failCarrier(epoch: Long, error: Throwable) {
        Log.w(
            LOG_TAG,
            "carrier_failed epoch=$epoch elapsed_ms=${elapsedSinceConnectMs()} " +
                "cursor=${state.value.cursor ?: -1} type=${error.javaClass.simpleName} " +
                "cause_chain=${causeChainNames(error)}",
        )
        captureProjection(persistImmediately = false)
        val hasOfflineData = state.value.sessions.isNotEmpty() || state.value.timeline.isNotEmpty()
        val phase = connectionPhaseForCarrierFailure(error, hasOfflineData)
        val incompatible = phase == ConnectionPhase.INCOMPATIBLE
        update(phase, carrierFailureEventCopy(error, incompatible)) {
            copy(
                failure = carrierFailureDetail(error, incompatible),
                newPairingRequired = requiresNewPairing(error),
                storageSealedByLock = error is PairedHostLockedException,
                offlineSnapshot = sessions.isNotEmpty() || timeline.isNotEmpty(),
            )
        }
        if (error is PairedHostLockedException) scheduleReconnectOnUnlock()
    }

    /**
     * Public retry entry for signals that the device lock may have cleared (app resume,
     * keyguard dismissal). Only acts while the last carrier failure was the locked-storage
     * seal; a genuine repair failure never auto-retries.
     */
    fun retryIfStorageSealed(trigger: String) {
        if (!awaitingUnlockRetry.getAndSet(false)) return
        if (!state.value.storageSealedByLock) return
        Log.i(LOG_TAG, "device_unlock_reconnect trigger=$trigger")
        connect()
    }

    /**
     * The Keystore wrapping key is sealed while the device is locked; the pairing itself is
     * intact. Instead of reporting a repair-style failure, reconnect automatically as soon
     * as the device is usable again. USER_PRESENT is the primary signal, but some OEMs
     * skip it for non-secure keyguards, so SCREEN_ON + keyguard state is the fallback.
     */
    private fun scheduleReconnectOnUnlock() {
        awaitingUnlockRetry.set(true)
        val keyguard = appContext.getSystemService(KeyguardManager::class.java)
        if (!unlockReceiverRegistered) {
            unlockReceiverRegistered = true
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_USER_PRESENT -> retryIfStorageSealed("user_present")
                        Intent.ACTION_SCREEN_ON ->
                            if (keyguard?.isKeyguardLocked == false) retryIfStorageSealed("screen_on")
                    }
                }
            }
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT).apply {
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
        }
        // Lifecycle signals can race the Keystore unlock transition (a resume may fire
        // while the key is still sealed). Poll briefly so the seal clears deterministically.
        if (unlockPollJob?.isActive != true) {
            unlockPollJob = scope.launch {
                repeat(UNLOCK_POLL_ATTEMPTS) {
                    delay(UNLOCK_POLL_INTERVAL_MS)
                    if (!awaitingUnlockRetry.get() || !state.value.storageSealedByLock) return@launch
                    if (keyguard?.isKeyguardLocked == false) {
                        retryIfStorageSealed("poll")
                        return@launch
                    }
                }
            }
        }
    }

    private fun restoredConnectingState(
        workspace: OfflineWorkspaceCache?,
        endpoint: String,
        fingerprint: String,
        warning: String?,
    ): Gate0CState {
        val selectedId = workspace?.selectedSessionId
            ?.takeIf { candidate -> workspace.sessions.any { it.sessionId == candidate } }
            ?: workspace?.sessions?.firstOrNull()?.sessionId
        selectedSessionId = selectedId
        val directoryEntry = workspace?.sessions?.find { it.sessionId == selectedId }
        val projection = workspace?.projection(selectedId)
        val reading = selectedId?.let { workspace?.readingPositions?.get(it) }
        val anchorUnavailable = reading?.anchorEntryId != null &&
            projection?.timeline?.none { it.id == reading.anchorEntryId } == true
        val hasOfflineData = workspace?.sessions?.isNotEmpty() == true || projection != null
        return Gate0CState(
            phase = ConnectionPhase.CONNECTING,
            endpoint = endpoint,
            hostInstanceId = workspace?.hostInstanceId,
            hostDisplayName = workspace?.hostDisplayName,
            sessions = workspace?.sessions.orEmpty(),
            agentPresets = workspace?.agentPresets.orEmpty(),
            modelCatalog = workspace?.modelCatalog.orEmpty(),
            modelCatalogFailures = workspace?.modelCatalogFailures.orEmpty(),
            artifacts = workspace?.artifacts.orEmpty(),
            sessionId = selectedId,
            sessionTitle = projection?.title ?: directoryEntry?.title,
            sessionRunning = projection?.running ?: directoryEntry?.running,
            sessionUsage = projection?.usage ?: directoryEntry?.usage,
            sessionSubagent = projection?.subagent ?: directoryEntry?.subagent,
            sessionOrigin = directoryEntry?.origin,
            sessionAgentPreset = projection?.agentPreset ?: directoryEntry?.agentPreset,
            sessionModel = projection?.model ?: directoryEntry?.model,
            projectionVersion = projection?.projectionVersion,
            cursor = projection?.cursor,
            timeline = projection?.timeline.orEmpty(),
            approvalRules = projection?.approvalRules.orEmpty(),
            sessionBudget = projection?.sessionBudget,
            historyTruncated = projection?.historyTruncated ?: false,
            pairedHostFingerprint = fingerprint,
            offlineSnapshot = hasOfflineData,
            offlineCacheSavedAtMs = projection?.savedAtMs ?: workspace?.savedAtMs,
            offlineCacheTruncated = projection?.cacheTruncated ?: false,
            localDraft = selectedId?.let { workspace?.drafts?.get(it) }.orEmpty(),
            readingAnchorId = when {
                anchorUnavailable -> projection.timeline.firstOrNull()?.id
                else -> reading?.anchorEntryId
            },
            readingOffsetPx = if (anchorUnavailable) 0 else reading?.offsetPx ?: 0,
            followTail = reading?.followTail ?: true,
            readingAnchorUnavailable = anchorUnavailable,
            cacheWarning = warning,
            events = listOf(
                if (hasOfflineData) {
                    "00 · Restored an encrypted stale snapshot while authenticating the Host."
                } else {
                    "00 · Opening Noise IK authenticated gRPC stream over ADB loopback."
                },
            ),
        )
    }

    private fun updateCachedDirectory(hostInstanceId: String?) {
        val current = state.value
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val binding = authorityBinding ?: return
            val existing = cachedWorkspace
            cachedWorkspace = if (existing == null) {
                OfflineWorkspaceCache(
                    hostBinding = binding.copyOf(),
                    hostInstanceId = hostInstanceId,
                    hostDisplayName = current.hostDisplayName,
                    savedAtMs = now,
                    sessions = current.sessions,
                    selectedSessionId = selectedSessionId,
                    projections = emptyList(),
                    drafts = emptyMap(),
                    readingPositions = emptyMap(),
                    agentPresets = current.agentPresets,
                    modelCatalog = current.modelCatalog,
                    modelCatalogFailures = current.modelCatalogFailures,
                    artifacts = current.artifacts,
                )
            } else {
                existing.copy(
                    hostInstanceId = hostInstanceId ?: existing.hostInstanceId,
                    hostDisplayName = current.hostDisplayName ?: existing.hostDisplayName,
                    savedAtMs = now,
                    sessions = current.sessions,
                    selectedSessionId = selectedSessionId,
                    agentPresets = current.agentPresets,
                    modelCatalog = current.modelCatalog,
                    modelCatalogFailures = current.modelCatalogFailures,
                    artifacts = current.artifacts,
                )
            }
        }
        scheduleCacheWrite()
    }

    fun clearOfflineWorkspace() {
        if (!state.value.offlineSnapshot && state.value.phase != ConnectionPhase.OFFLINE) return
        cacheWriteJob?.cancel()
        cacheWriteJob = null
        offlineStore.clear()
        synchronized(cacheLock) {
            cachedWorkspace?.hostBinding?.fill(0)
            cachedWorkspace = null
        }
        mutableState.update { current ->
            current.copy(
                phase = ConnectionPhase.OFFLINE,
                hostInstanceId = null,
                hostDisplayName = null,
                connectionId = null,
                sessions = emptyList(),
                agentPresets = emptyList(),
                modelCatalog = emptyList(),
                modelCatalogFailures = emptyList(),
                sessionId = null,
                sessionTitle = null,
                sessionRunning = null,
                sessionUsage = null,
                sessionSubagent = null,
                sessionOrigin = null,
                sessionAgentPreset = null,
                sessionModel = null,
                streamId = null,
                projectionVersion = null,
                cursor = null,
                timeline = emptyList(),
                approvals = emptyList(),
                approvalRules = emptyList(),
                sessionBudget = null,
                artifacts = emptyList(),
                historyTruncated = false,
                commandReceipts = emptyList(),
                events = current.events + "Local offline workspace cleared; Host pairing retained.",
                failure = "Host is unavailable. The local offline copy was cleared.",
                offlineSnapshot = false,
                offlineCacheSavedAtMs = null,
                offlineCacheTruncated = false,
                localDraft = "",
                readingAnchorId = null,
                readingOffsetPx = 0,
                followTail = true,
                readingAnchorUnavailable = false,
                cacheWarning = null,
            )
        }
    }

    fun startNewPairingCeremony() {
        connectionEpoch.incrementAndGet()
        closeTransport()
        cacheWriteJob?.cancel()
        cacheWriteJob = null
        recoveryPending = false
        selectedSessionId = null
        pendingResume = null
        resumeReplayTarget = null

        synchronized(cacheLock) {
            authorityBinding?.fill(0)
            authorityBinding = null
            cachedWorkspace?.hostBinding?.fill(0)
            cachedWorkspace = null
        }
        synchronized(commandLock) {
            pendingCommand?.authorityBinding?.fill(0)
            pendingCommand?.requestFingerprint?.fill(0)
            pendingCommand = null
            commandRecoveryBlocked = false
            activeControl = null
            pairedAtMs = null
            expectedGrantedCapabilities = null
        }

        val reset = runCatching {
            pendingCommandStore.clear()
            offlineStore.clear()
            if (hostId == null) {
                // Legacy single-Host shape: the whole local authority is replaced.
                pairedHostStore.delete()
                identityStore.delete()
            } else {
                // S-multi-host: only this Host's record is removed. The device
                // identity and the other Hosts' pins stay valid for them.
                pairedHostStore.delete(hostId)
            }
        }
        mutableState.value = if (reset.isSuccess) {
            Gate0CState(
                phase = ConnectionPhase.UNPAIRED,
                events = listOf(
                    "00 · Old device identity and authority-bound local state removed; a new pairing invitation is required.",
                ),
            )
        } else {
            Gate0CState(
                phase = ConnectionPhase.FAILED,
                newPairingRequired = true,
                failure = "Could not remove the old local authority safely. Retry before pairing again.",
                events = listOf("00 · New pairing reset failed closed; no new ceremony was started."),
            )
        }
    }

    private fun updateCachedPreferences(
        sessionId: String,
        draft: String? = null,
        readingPosition: CachedReadingPosition? = null,
    ) {
        synchronized(cacheLock) {
            val existing = cachedWorkspace ?: return
            cachedWorkspace = existing.copy(
                selectedSessionId = sessionId,
                drafts = if (draft == null) {
                    existing.drafts
                } else {
                    existing.drafts.toMutableMap().also { drafts ->
                        if (draft.isEmpty()) drafts.remove(sessionId) else drafts[sessionId] = draft
                    }
                },
                readingPositions = if (readingPosition == null) {
                    existing.readingPositions
                } else {
                    existing.readingPositions + (sessionId to readingPosition)
                },
            )
        }
        scheduleCacheWrite()
    }

    private fun captureProjection(persistImmediately: Boolean) {
        val current = state.value
        if (current.offlineSnapshot) return
        val sessionId = current.sessionId ?: return
        val streamId = current.streamId ?: return
        val projectionVersion = current.projectionVersion ?: return
        val cursor = current.cursor ?: return
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val binding = authorityBinding ?: return
            val existing = cachedWorkspace
            val projection = CachedSessionProjection(
                sessionId = sessionId,
                title = current.sessionTitle,
                running = current.sessionRunning ?: false,
                streamId = streamId,
                projectionVersion = projectionVersion,
                cursor = cursor,
                timeline = current.timeline,
                historyTruncated = current.historyTruncated,
                cacheTruncated = false,
                savedAtMs = now,
                usage = current.sessionUsage,
                subagent = current.sessionSubagent,
                agentPreset = current.sessionAgentPreset,
                model = current.sessionModel,
                approvalRules = current.approvalRules,
                sessionBudget = current.sessionBudget,
            )
            val projections = (existing?.projections.orEmpty().filterNot { it.sessionId == sessionId } + projection)
                .sortedByDescending(CachedSessionProjection::savedAtMs)
            cachedWorkspace = OfflineWorkspaceCache(
                hostBinding = existing?.hostBinding?.copyOf() ?: binding.copyOf(),
                hostInstanceId = current.hostInstanceId ?: existing?.hostInstanceId,
                hostDisplayName = current.hostDisplayName ?: existing?.hostDisplayName,
                savedAtMs = now,
                sessions = current.sessions,
                selectedSessionId = sessionId,
                projections = projections,
                drafts = existing?.drafts.orEmpty().toMutableMap().also { drafts ->
                    if (current.localDraft.isEmpty()) drafts.remove(sessionId)
                    else drafts[sessionId] = current.localDraft
                },
                readingPositions = existing?.readingPositions.orEmpty() + (
                    sessionId to CachedReadingPosition(
                        current.readingAnchorId,
                        current.readingOffsetPx,
                        current.followTail,
                    )
                ),
                agentPresets = current.agentPresets,
                modelCatalog = current.modelCatalog,
                modelCatalogFailures = current.modelCatalogFailures,
                artifacts = current.artifacts,
            )
        }
        mutableState.update { state -> state.copy(offlineCacheSavedAtMs = now, cacheWarning = null) }
        if (persistImmediately) flushCachedWorkspace() else scheduleCacheWrite()
    }

    private fun scheduleCacheWrite() {
        val snapshot = synchronized(cacheLock) { cachedWorkspace?.boundedForStorage() } ?: return
        cacheWriteJob?.cancel()
        cacheWriteJob = scope.launch {
            delay(CACHE_WRITE_DEBOUNCE_MS)
            val stillCurrent = synchronized(cacheLock) {
                authorityBinding?.contentEquals(snapshot.hostBinding) == true
            }
            if (!stillCurrent) return@launch
            runCatching { offlineStore.save(snapshot) }
                .onFailure { markCacheWriteFailure() }
        }
    }

    private fun flushCachedWorkspace() {
        cacheWriteJob?.cancel()
        cacheWriteJob = null
        val snapshot = synchronized(cacheLock) { cachedWorkspace?.boundedForStorage() } ?: return
        val stillCurrent = synchronized(cacheLock) {
            authorityBinding?.contentEquals(snapshot.hostBinding) == true
        }
        if (!stillCurrent) return
        runCatching { offlineStore.save(snapshot) }
            .onFailure { markCacheWriteFailure() }
    }

    private fun markCacheWriteFailure() {
        mutableState.update { current ->
            current.copy(
                cacheWarning = "Offline recovery could not be updated; live Host state remains authoritative.",
            )
        }
    }

    private fun nextFrameId(): String = "android-frame-${frameNumber.incrementAndGet()}-${UUID.randomUUID()}"

    private fun elapsedSinceConnectMs(): Long {
        val startedAt = connectStartedAtNanos.get()
        if (startedAt == 0L) return 0L
        return TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - startedAt)
    }

    private fun update(
        phase: ConnectionPhase? = null,
        log: String,
        transform: Gate0CState.() -> Gate0CState = { this },
    ) {
        val eventNumber = logNumber.incrementAndGet().toString().padStart(2, '0')
        mutableState.update { current ->
            val next = current.transform().copy(
                phase = phase ?: current.phase,
                events = (current.events + "$eventNumber · $log").takeLast(14),
            )
            if (next.phase == ConnectionPhase.READY || next.phase == ConnectionPhase.RECONCILED) {
                next.copy(storageSealedByLock = false)
            } else {
                next
            }
        }
    }

    private companion object {
        // S-artifacts: v7 adds the connect-time artifact roster to the hello
        // and the live artifact_registered frame.
        // S-policy: v8 adds approval_rules/budget to the session snapshot, the
        // live policy_changed frame, and the ALLOW_SAME_KIND decision. A stale
        // version makes the Host answer SNAPSHOT_REQUIRED on every resume.
        const val PROJECTION_VERSION = 8
        const val CONTROL_CAPABILITIES = 64uL
        const val SEND_CONTROL_CAPABILITIES = 68uL
        const val STOP_CONTROL_CAPABILITIES = 72uL
        const val APPROVAL_CAPABILITIES = 16uL
        const val LOG_TAG = "DSHRemoteGate0C"
        const val RECOVERY_ATTEMPT_TIMEOUT_MS = 5_000L
        const val BLOB_ROUND_TRIP_TIMEOUT_MS = 30_000L
        /** Mirrors the Host carrier's per-message attachment bound (fence at the projection seam). */
        const val MAX_MESSAGE_ATTACHMENTS = 16
        const val AUTHORIZATION_PROBE_INTERVAL_MS = 5_000L
        const val AUTHORIZATION_PROBE_TIMEOUT_MS = 5_000L
        const val CACHE_WRITE_DEBOUNCE_MS = 400L
        const val UNLOCK_POLL_INTERVAL_MS = 2_000L
        const val UNLOCK_POLL_ATTEMPTS = 6
        val RECOVERY_RETRY_DELAYS_MS = longArrayOf(0, 250, 750, 1_500, 3_000)
    }
}

private data class ActiveControl(
    val sessionId: String,
    val epoch: String,
    val token: String,
    val expiresAtMs: Long,
) {
    fun toStatus(): ControlLeaseStatus = ControlLeaseStatus(sessionId, epoch, expiresAtMs)
}

private fun PendingRemoteCommand.toStatus(): PendingCommandStatus = PendingCommandStatus(
    commandId = commandId,
    sessionId = sessionId,
    operation = operation,
    expectedActivityRevision = expectedActivityRevision,
    approvalId = approvalId,
    approvalDecision = approvalDecision,
    agentPreset = agentPreset,
    modelSelection = modelSelection,
    ruleId = ruleId,
    maxTotalTokens = maxTotalTokens,
    progress = when (phase) {
        PendingCommandPhase.PREPARED -> PendingCommandProgress.PREPARED
        PendingCommandPhase.RECEIVED -> PendingCommandProgress.RECEIVED
        PendingCommandPhase.REQUESTED -> PendingCommandProgress.REQUESTED
        PendingCommandPhase.UNKNOWN -> PendingCommandProgress.UNKNOWN
    },
    createdAtMs = createdAtMs,
)

private fun PendingRemoteCommand.requiredCapabilities(): ULong = when (operation) {
    PendingCommandOperation.SEND_INPUT -> 68uL
    PendingCommandOperation.STOP -> 72uL
    PendingCommandOperation.DECIDE_APPROVAL -> 16uL
    PendingCommandOperation.CREATE_SESSION -> 68uL
    PendingCommandOperation.SELECT_AGENT_PRESET -> 68uL
    PendingCommandOperation.SELECT_MODEL -> 68uL
    PendingCommandOperation.FORK_SESSION -> 68uL
    // S-policy: revocation shares the approval trust domain; the budget is
    // session administration on the send/control set (mirrors the Host).
    PendingCommandOperation.REVOKE_APPROVAL_RULE -> 16uL
    PendingCommandOperation.SET_SESSION_BUDGET -> 68uL
}

private fun approvalStateOf(interaction: ApprovalInteraction): ApprovalInteractionState {
    val evidence = when (interaction.evidenceCase) {
        ApprovalInteraction.EvidenceCase.PRESENTATION -> ApprovalEvidence(
            available = true,
            summary = interaction.presentation.summary,
            risk = when (interaction.presentation.risk) {
                ProtoApprovalRisk.APPROVAL_RISK_ROUTINE -> ApprovalRisk.ROUTINE
                ProtoApprovalRisk.APPROVAL_RISK_SENSITIVE -> ApprovalRisk.SENSITIVE
                ProtoApprovalRisk.APPROVAL_RISK_DESTRUCTIVE -> ApprovalRisk.DESTRUCTIVE
                ProtoApprovalRisk.APPROVAL_RISK_UNCLASSIFIED,
                ProtoApprovalRisk.APPROVAL_RISK_UNSPECIFIED,
                ProtoApprovalRisk.UNRECOGNIZED,
                -> ApprovalRisk.UNCLASSIFIED
            },
            resources = interaction.presentation.resourcesList,
            consequence = interaction.presentation.consequence,
            source = interaction.presentation.source,
            unavailableReason = null,
        )
        ApprovalInteraction.EvidenceCase.PRESENTATION_UNAVAILABLE,
        ApprovalInteraction.EvidenceCase.EVIDENCE_NOT_SET,
        null,
        -> ApprovalEvidence(
            available = false,
            summary = "Host did not provide a safe operation summary.",
            risk = ApprovalRisk.UNCLASSIFIED,
            resources = emptyList(),
            consequence = "The consequence is unavailable. Treat this request as sensitive.",
            source = "Unavailable",
            unavailableReason = interaction.presentationUnavailable.reason.takeIf(String::isNotBlank)
                ?: "not-provided",
        )
    }
    return ApprovalInteractionState(
        approvalId = interaction.approvalId,
        revision = interaction.revision,
        sessionId = interaction.sessionId,
        toolName = interaction.toolName,
        callId = interaction.callId.takeIf(String::isNotBlank),
        reason = interaction.reason.takeIf(String::isNotBlank),
        workspaceLabel = interaction.workspaceLabel.takeIf(String::isNotBlank),
        allowOnce = interaction.allowedDecisionsList.contains(ApprovalDecision.APPROVAL_DECISION_ALLOW_ONCE),
        deny = interaction.allowedDecisionsList.contains(ApprovalDecision.APPROVAL_DECISION_DENY),
        allowSameKind = interaction.allowedDecisionsList
            .contains(ApprovalDecision.APPROVAL_DECISION_ALLOW_SAME_KIND),
        evidence = evidence,
    )
}

/**
 * Narrate the one durable policy change a policy_changed frame crossed with
 * (S-policy): a grant, a revocation, or a budget set, diffed against the
 * previous fold. Null when nothing changed (an idempotent replay).
 */
private fun policyChangeLabel(
    oldRules: List<ApprovalRuleState>,
    oldBudget: SessionBudgetState?,
    changed: ProtoPolicyChanged,
): String? {
    val newRules = changed.rulesList.map(::approvalRuleStateOf)
    val added = newRules.filter { rule -> oldRules.none { it.ruleId == rule.ruleId } }
    if (added.isNotEmpty()) {
        return "规则 · 同类放行 " + added.joinToString("、") { it.classLabel }
    }
    val removed = oldRules.filter { rule -> newRules.none { it.ruleId == rule.ruleId } }
    if (removed.isNotEmpty()) {
        return "规则 · 已撤销 " + removed.joinToString("、") { it.classLabel }
    }
    val newCeiling = if (changed.hasBudget()) changed.budget.maxTotalTokens else null
    if (newCeiling != oldBudget?.maxTotalTokens) {
        return if (newCeiling == null) "预算 · 已移除" else "预算 · ${compactTokens(newCeiling)} tokens"
    }
    return null
}

/** Transport-local compact count (mirrors the UI's compactTokenCount). */
private fun compactTokens(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 10_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1_000.0)
    value < 1_000_000 -> "${value / 1_000}k"
    else -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
}

internal fun hasCapabilities(granted: ULong, required: ULong): Boolean = granted and required == required

internal fun connectionPhaseForCarrierFailure(
    error: Throwable,
    hasOfflineData: Boolean,
): ConnectionPhase = when {
    error is SecureRemoteProtocolException &&
        error.code == SecureErrorCode.SECURE_ERROR_CODE_INCOMPATIBLE_VERSION -> ConnectionPhase.INCOMPATIBLE
    hasOfflineData -> ConnectionPhase.OFFLINE
    else -> ConnectionPhase.FAILED
}

internal fun carrierFailureEventCopy(error: Throwable, incompatible: Boolean): String = when {
    incompatible -> "The Host secure protocol is incompatible with this app."
    error is PairedHostLockedException ->
        "Host storage is sealed while the device is locked; unlock the device to reconnect."
    else -> "Carrier failed: ${error.message ?: error.javaClass.simpleName}"
}

internal fun carrierFailureDetail(error: Throwable, incompatible: Boolean): String = when {
    incompatible -> "Update DSH Remote and the Host integration before reconnecting."
    error is PairedHostLockedException ->
        "The stored Host pin is intact and sealed by the device lock; no repair is needed. " +
            "The app reconnects automatically once the device is unlocked."
    else -> error.message ?: error.javaClass.simpleName
}

internal fun requiresNewPairing(error: Throwable): Boolean =
    error is SecureRemoteProtocolException &&
        error.code == SecureErrorCode.SECURE_ERROR_CODE_UNAUTHORIZED_DEVICE

/** Class names only — never exception messages, which may embed endpoint detail. */
internal fun causeChainNames(error: Throwable, maxDepth: Int = 4): String {
    val names = mutableListOf<String>()
    var cursor: Throwable? = error.cause
    while (cursor != null && names.size < maxDepth) {
        names += cursor.javaClass.simpleName
        cursor = cursor.cause
    }
    return if (names.isEmpty()) "none" else names.joinToString("<")
}

internal fun isIncompatibleProtocolError(code: ErrorCode): Boolean =
    code == ErrorCode.ERROR_CODE_INCOMPATIBLE_VERSION

internal data class ResumePlan(
    val sessionId: String,
    val streamId: String,
    val projectionVersion: Int,
    val sequence: Long,
)

internal fun resumePlanFor(
    cachedHostInstanceId: String?,
    connectedHostInstanceId: String?,
    sessionId: String?,
    projection: CachedSessionProjection?,
    expectedProjectionVersion: Int,
): ResumePlan? {
    if (
        cachedHostInstanceId.isNullOrBlank() ||
        connectedHostInstanceId.isNullOrBlank() ||
        cachedHostInstanceId != connectedHostInstanceId ||
        sessionId.isNullOrBlank() ||
        projection == null ||
        projection.sessionId != sessionId ||
        projection.streamId.isBlank() ||
        projection.projectionVersion != expectedProjectionVersion ||
        projection.cursor < 0
    ) return null
    return ResumePlan(
        sessionId = sessionId,
        streamId = projection.streamId,
        projectionVersion = projection.projectionVersion,
        sequence = projection.cursor,
    )
}

internal fun isValidResumeAcceptance(
    plan: ResumePlan,
    streamId: String,
    projectionVersion: Int,
    resumedAfterSequence: Long,
    latestSequence: Long,
): Boolean =
    streamId == plan.streamId &&
        projectionVersion == plan.projectionVersion &&
        resumedAfterSequence == plan.sequence &&
        latestSequence >= resumedAfterSequence

internal fun isExpectedProjectedEventDomain(
    streamId: String?,
    projectionVersion: Int?,
    sessionId: String?,
    eventStreamId: String,
    eventProjectionVersion: Int,
    eventSessionId: String,
): Boolean =
    !streamId.isNullOrBlank() &&
        !sessionId.isNullOrBlank() &&
        streamId == eventStreamId &&
        projectionVersion == eventProjectionVersion &&
        sessionId == eventSessionId
