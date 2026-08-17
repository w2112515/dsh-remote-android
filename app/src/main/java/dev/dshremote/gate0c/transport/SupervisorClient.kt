package dev.dshremote.gate0c.transport

import android.content.Context
import dev.dshremote.protocol.v1alpha.CommandOutcome
import dev.dshremote.protocol.v1alpha.ErrorCode
import dev.dshremote.protocol.v1alpha.SecureErrorCode
import dev.dshremote.protocol.v1alpha.SupervisorClientFrame
import dev.dshremote.protocol.v1alpha.SupervisorCommand
import dev.dshremote.protocol.v1alpha.SupervisorCommandResult
import dev.dshremote.protocol.v1alpha.SupervisorHello
import dev.dshremote.protocol.v1alpha.SupervisorRestart
import dev.dshremote.protocol.v1alpha.SupervisorServerFrame
import dev.dshremote.protocol.v1alpha.SupervisorStart
import dev.dshremote.protocol.v1alpha.SupervisorStatus
import dev.dshremote.protocol.v1alpha.SupervisorStop
import dev.dshremote.protocol.v1alpha.SupervisorTransportGrpc
import dev.dshremote.security.DeviceIdentityStore
import dev.dshremote.security.PairedHostStore
import dev.dshremote.security.PairingProtocol
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The supervisor's lifecycle snapshot, exactly as pushed (absent stays null). */
internal data class SupervisorStatusView(
    /** "down" | "running" | "stopping" | "backoff". */
    val state: String,
    /** Only while down: "never-started" | "operator" | "crash-loop". */
    val downReason: String?,
    val consecutiveCrashes: Int,
    val nextRestartAtMs: Long?,
    val childPid: Int?,
    val childSinceMs: Long?,
    val lastExitCode: Int?,
    val lastExitSignal: String?,
)

/**
 * One host's management-link face for the UI (S-supervisor, ADR-007).
 * [Idle] means nobody asked; [Unreachable] is the honest degradation the
 * Host sheet shows when the resident supervisor is not there to answer.
 */
internal sealed interface SupervisorLinkView {
    data object Idle : SupervisorLinkView
    data object Connecting : SupervisorLinkView
    data class Online(
        val hostInstanceId: String,
        /** The paired profile carries the supervise-host bit (ADR-007, 1<<8). */
        val canManage: Boolean,
        /** Null only between hello-ack and the first push (momentary). */
        val status: SupervisorStatusView?,
        /** Verb in flight: "start" | "stop" | "restart". */
        val pendingVerb: String?,
        /** The most recent verb refusal, already phrased for the sheet. */
        val lastRefusal: String?,
    ) : SupervisorLinkView

    data class Unreachable(val detail: String) : SupervisorLinkView
}

/**
 * Noise-authenticated client of the resident supervisor's management channel
 * (`SupervisorTransport`, ADR-007). Demand-scoped: the Host sheet calls
 * [ensure] on entry and [release] when it leaves, so the phone only holds a
 * link while someone is looking. Status is push-only (one after hello, one
 * per transition); the three lifecycle verbs correlate by client-minted
 * command id. The supervisor listens on its own fixed management port —
 * separate from the dsh child's remote port — which is exactly what keeps
 * stop/start reachable while the child is down.
 */
internal class SupervisorClient(
    context: Context,
    private val hostId: String,
    private val scope: CoroutineScope,
) : Closeable {
    private val appContext = context.applicationContext
    private val identityStore = DeviceIdentityStore(appContext)
    private val pairedHostStore = PairedHostStore(appContext)
    private val lock = Any()
    private var wanted = false
    private var envelope: SecureEnvelopeTransport? = null
    private var pendingCommandId: String? = null
    private var pendingTimeout: Job? = null
    private val mutableView = MutableStateFlow<SupervisorLinkView>(SupervisorLinkView.Idle)

    val view: StateFlow<SupervisorLinkView> = mutableView.asStateFlow()

    /** Open the management link unless it is already up. Idempotent. */
    fun ensure() {
        synchronized(lock) {
            wanted = true
            if (envelope != null) return
            mutableView.value = SupervisorLinkView.Connecting
        }
        scope.launch { open() }
    }

    /** Drop the link and return to [SupervisorLinkView.Idle]. */
    fun release() {
        val transport = synchronized(lock) {
            wanted = false
            clearPendingLocked()
            mutableView.value = SupervisorLinkView.Idle
            val current = envelope
            envelope = null
            current
        }
        transport?.close()
    }

    override fun close() {
        release()
    }

    fun start() {
        verb("start")
    }

    fun stop() {
        verb("stop")
    }

    fun restart() {
        verb("restart")
    }

    private fun open() {
        val record = try {
            pairedHostStore.load(hostId)
        } catch (error: Throwable) {
            settleUnreachable("配对记录不可读：${error.message ?: error.javaClass.simpleName}")
            return
        }
        if (record == null) {
            settleUnreachable("本机没有该主机的配对记录")
            return
        }
        val endpoint = "${record.endpointHost}:$SUPERVISOR_MANAGEMENT_PORT"
        val target = SecureHostTarget(
            hostPublicKey = record.hostPublicKey.copyOf(),
            endpointHost = record.endpointHost,
            endpointPort = SUPERVISOR_MANAGEMENT_PORT,
        )
        record.hostPublicKey.fill(0)
        val transport = SecureEnvelopeTransport(
            host = target,
            identityStore = identityStore,
            openCall = { channel, response -> SupervisorTransportGrpc.newStub(channel).secureConnect(response) },
            applicationHello = {
                SupervisorClientFrame.newBuilder()
                    .setFrameId(frameId())
                    .setHello(
                        SupervisorHello.newBuilder()
                            .setProtocolVersion(PairingProtocol.PROTOCOL_VERSION)
                            .setClientName("dsh-remote-android"),
                    )
                    .build()
                    .toByteArray()
            },
            onPlaintext = { plaintext -> onServerFrame(SupervisorServerFrame.parseFrom(plaintext)) },
            onError = { error -> onTransportDown(honestDetail(error, endpoint)) },
            onCompleted = { onTransportDown("守护进程结束了连接") },
        )
        val adopted = synchronized(lock) {
            if (!wanted || envelope != null) {
                false
            } else {
                envelope = transport
                true
            }
        }
        if (!adopted) {
            transport.close()
            return
        }
        try {
            transport.connect()
        } catch (error: Throwable) {
            onTransportDown(honestDetail(error, endpoint))
        }
    }

    private fun verb(name: String) {
        val payload: ByteArray
        val transport: SecureEnvelopeTransport
        synchronized(lock) {
            val current = mutableView.value
            if (current !is SupervisorLinkView.Online) return
            if (!current.canManage || pendingCommandId != null) return
            transport = envelope ?: return
            val commandId = "android-sup-${UUID.randomUUID()}"
            val command = SupervisorCommand.newBuilder().setCommandId(commandId)
            when (name) {
                "start" -> command.setStart(SupervisorStart.getDefaultInstance())
                "stop" -> command.setStop(SupervisorStop.getDefaultInstance())
                else -> command.setRestart(SupervisorRestart.getDefaultInstance())
            }
            payload = SupervisorClientFrame.newBuilder()
                .setFrameId(frameId())
                .setCommand(command)
                .build()
                .toByteArray()
            pendingCommandId = commandId
            pendingTimeout = scope.launch {
                delay(VERB_TIMEOUT_MS)
                expirePending(commandId)
            }
            mutableView.value = current.copy(pendingVerb = name, lastRefusal = null)
        }
        if (!transport.sendPlaintext(payload)) {
            // The transport died in between; its error callback runs the
            // down path, this only stops the timeout from firing later.
            synchronized(lock) { clearPendingLocked() }
        }
    }

    private fun expirePending(commandId: String) {
        synchronized(lock) {
            if (pendingCommandId != commandId) return
            pendingCommandId = null
            pendingTimeout = null
            val current = mutableView.value
            if (current is SupervisorLinkView.Online) {
                mutableView.value = current.copy(pendingVerb = null, lastRefusal = "指令超时，请重试")
            }
        }
    }

    private fun onServerFrame(frame: SupervisorServerFrame) {
        when (frame.payloadCase) {
            SupervisorServerFrame.PayloadCase.HELLO_ACK -> {
                val ack = frame.helloAck
                synchronized(lock) {
                    mutableView.value = SupervisorLinkView.Online(
                        hostInstanceId = ack.hostInstanceId,
                        canManage = ack.grantedCapabilities and SUPERVISE_HOST_BIT != 0L,
                        status = null,
                        pendingVerb = null,
                        lastRefusal = null,
                    )
                }
            }

            SupervisorServerFrame.PayloadCase.STATUS -> {
                val status = statusView(frame.status)
                updateOnline { it.copy(status = status) }
            }

            SupervisorServerFrame.PayloadCase.COMMAND_RESULT -> onCommandResult(frame.commandResult)

            SupervisorServerFrame.PayloadCase.HEARTBEAT_ACK -> Unit

            SupervisorServerFrame.PayloadCase.ERROR -> onTransportDown(
                "守护通道协议错误：${frame.error.detail.ifBlank { frame.error.code.name }}",
            )

            SupervisorServerFrame.PayloadCase.PAYLOAD_NOT_SET, null -> Unit
        }
    }

    private fun onCommandResult(result: SupervisorCommandResult) {
        synchronized(lock) {
            if (pendingCommandId != result.commandId) return
            clearPendingLocked()
            val current = mutableView.value
            if (current !is SupervisorLinkView.Online) return
            mutableView.value = if (result.outcome == CommandOutcome.COMMAND_OUTCOME_COMMITTED) {
                current.copy(pendingVerb = null, lastRefusal = null)
            } else {
                current.copy(pendingVerb = null, lastRefusal = refusalText(result))
            }
        }
    }

    private fun onTransportDown(detail: String) {
        val transport = synchronized(lock) {
            clearPendingLocked()
            val current = envelope
            envelope = null
            mutableView.value = if (wanted) SupervisorLinkView.Unreachable(detail) else SupervisorLinkView.Idle
            current
        }
        transport?.close()
    }

    private fun settleUnreachable(detail: String) {
        synchronized(lock) {
            mutableView.value = if (wanted) SupervisorLinkView.Unreachable(detail) else SupervisorLinkView.Idle
        }
    }

    private fun clearPendingLocked() {
        pendingCommandId = null
        pendingTimeout?.cancel()
        pendingTimeout = null
    }

    private fun updateOnline(transform: (SupervisorLinkView.Online) -> SupervisorLinkView.Online) {
        synchronized(lock) {
            val current = mutableView.value
            if (current is SupervisorLinkView.Online) mutableView.value = transform(current)
        }
    }

    private fun statusView(status: SupervisorStatus): SupervisorStatusView = SupervisorStatusView(
        state = status.state,
        downReason = if (status.hasDownReason()) status.downReason else null,
        consecutiveCrashes = status.consecutiveCrashes,
        nextRestartAtMs = if (status.hasNextRestartAtMs()) status.nextRestartAtMs else null,
        childPid = if (status.hasChildPid()) status.childPid else null,
        childSinceMs = if (status.hasChildSinceMs()) status.childSinceMs else null,
        lastExitCode = if (status.hasLastExitCode()) status.lastExitCode else null,
        lastExitSignal = if (status.hasLastExitSignal()) status.lastExitSignal else null,
    )

    private fun refusalText(result: SupervisorCommandResult): String = when (result.errorCode) {
        ErrorCode.ERROR_CODE_AUTHORIZATION_DENIED -> "主机侧拒绝：此设备已无主机监督能力"
        ErrorCode.ERROR_CODE_COMMAND_UNAVAILABLE ->
            "守护进程当前无法执行该指令${if (result.detail.isNotBlank()) "：${result.detail}" else ""}"
        else -> result.detail.ifBlank { "指令被拒绝（${result.errorCode.name}）" }
    }

    private fun honestDetail(error: Throwable, endpoint: String): String = when {
        error is SecureRemoteProtocolException -> when (error.code) {
            SecureErrorCode.SECURE_ERROR_CODE_UNAUTHORIZED_DEVICE -> "设备未被守护进程授权（配对可能已被撤销）"
            SecureErrorCode.SECURE_ERROR_CODE_INCOMPATIBLE_VERSION -> "守护通道协议版本不兼容"
            else -> "守护通道拒绝连接：${error.message ?: error.code.name}"
        }

        error is StatusRuntimeException && error.status.code == Status.Code.UNAVAILABLE ->
            "守护进程不可达（$endpoint）"

        else -> error.message ?: error.javaClass.simpleName
    }

    private fun frameId(): String = "android-sup-${UUID.randomUUID()}"

    companion object {
        /**
         * The `dsh supervisor` CLI's documented management-port default; the
         * paired record only pins the dsh child's remote endpoint, so the
         * management channel is reached on the same address at this port. A
         * custom `--management-port` host is honestly reported unreachable.
         */
        const val SUPERVISOR_MANAGEMENT_PORT = 50_052

        /** CAPABILITY_SUPERVISE_HOST = 1 shl 8 (ADR-007). */
        private const val SUPERVISE_HOST_BIT = 1L shl 8

        private const val VERB_TIMEOUT_MS = 20_000L
    }
}
