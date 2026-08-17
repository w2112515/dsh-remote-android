package dev.dshremote.discovery

import android.content.Context
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.dshremote.security.PairedHostStore
import java.security.MessageDigest
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LanDiscoveryPhase {
    IDLE,
    DISCOVERING,
    COMPLETE,
    MANUAL_RECOVERY,
}

enum class NearbyHostPairingState(val label: String) {
    PAIRED("Paired on this phone"),
    NOT_PAIRED("Not paired"),
}

data class NearbyDshHost(
    val serviceName: String,
    val displayName: String,
    val hostId: String,
    val platform: String,
    val pairingState: NearbyHostPairingState,
)

data class LanDiscoveryState(
    val phase: LanDiscoveryPhase = LanDiscoveryPhase.IDLE,
    val hosts: List<NearbyDshHost> = emptyList(),
    val explanation: String? = null,
)

internal object DshDiscoveryRecord {
    const val SERVICE_TYPE = "_dsh-remote._tcp."

    fun parse(
        serviceName: String,
        attributes: Map<String, ByteArray>,
        pairedHostIds: Set<String>,
    ): NearbyDshHost? {
        val version = attributes.utf8("v") ?: return null
        val hostId = attributes.utf8("id") ?: return null
        val platform = attributes.utf8("platform") ?: return null
        val pairing = attributes.utf8("pairing") ?: return null
        if (version != "1" || pairing != "required") return null
        if (!hostId.matches(Regex("[0-9A-F]{64}"))) return null
        if (platform.isBlank() || platform.length > 32) return null
        val displayName = serviceName.trim()
        if (displayName.isBlank() || displayName.encodeToByteArray().size > 63) return null
        return NearbyDshHost(
            serviceName = serviceName,
            displayName = displayName,
            hostId = hostId,
            platform = platform,
            pairingState = if (hostId in pairedHostIds) {
                NearbyHostPairingState.PAIRED
            } else {
                NearbyHostPairingState.NOT_PAIRED
            },
        )
    }

    private fun Map<String, ByteArray>.utf8(key: String): String? {
        val bytes = this[key]?.takeIf { it.size in 1..128 } ?: return null
        val decoded = bytes.toString(Charsets.UTF_8)
        return decoded.takeIf { it.encodeToByteArray().contentEquals(bytes) }
    }
}

/** Foreground, user-initiated Android NSD owner with an always-available manual fallback. */
class LanDiscoveryClient(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val nsd = applicationContext.getSystemService(NsdManager::class.java)
    private val executor: Executor = applicationContext.mainExecutor
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(LanDiscoveryState())
    private var listener: NsdManager.DiscoveryListener? = null
    private val timeout = Runnable { finish("Nearby search ended. You can search again or use the QR invitation below.") }

    val state: StateFlow<LanDiscoveryState> = mutableState.asStateFlow()

    fun start() {
        stopListener()
        mutableState.value = LanDiscoveryState(phase = LanDiscoveryPhase.DISCOVERING)
        val discoveryListener = listener()
        listener = discoveryListener
        try {
            if (Build.VERSION.SDK_INT >= 37) {
                val request = DiscoveryRequest.Builder(DshDiscoveryRecord.SERVICE_TYPE)
                    .setFlags(DiscoveryRequest.FLAG_SHOW_PICKER)
                    .build()
                nsd.discoverServices(request, executor, discoveryListener)
            } else {
                @Suppress("DEPRECATION")
                nsd.discoverServices(
                    DshDiscoveryRecord.SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener,
                )
            }
            handler.postDelayed(timeout, DISCOVERY_WINDOW_MS)
        } catch (error: SecurityException) {
            permissionDenied()
        } catch (error: RuntimeException) {
            fail("Nearby discovery could not start (${error.message ?: "system error"}).")
        }
    }

    fun permissionDenied() {
        stopListener()
        mutableState.value = LanDiscoveryState(
            phase = LanDiscoveryPhase.MANUAL_RECOVERY,
            explanation = "Nearby-device access was not granted. No conclusion was made about whether Hosts exist; scan or paste the Host-local QR invitation instead.",
        )
    }

    override fun close() {
        stopListener()
        mutableState.value = LanDiscoveryState()
    }

    private fun listener(): NsdManager.DiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!serviceInfo.serviceType.startsWith("_dsh-remote._tcp")) return
            @Suppress("DEPRECATION")
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    mutableState.update {
                        it.copy(explanation = "A DSH Host was found but its identity record could not be resolved. Use its Host-local QR invitation.")
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = DshDiscoveryRecord.parse(
                        serviceName = serviceInfo.serviceName,
                        attributes = serviceInfo.attributes,
                        pairedHostIds = pairedHostIds(),
                    ) ?: return
                    mutableState.update { current ->
                        current.copy(
                            phase = LanDiscoveryPhase.COMPLETE,
                            hosts = (current.hosts.filterNot { it.hostId == host.hostId } + host)
                                .sortedBy { it.displayName.lowercase() },
                            explanation = null,
                        )
                    }
                }
            })
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            mutableState.update { current ->
                current.copy(hosts = current.hosts.filterNot { it.serviceName == serviceInfo.serviceName })
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            if (listener != null) {
                finish("Nearby selection closed. Search again or use the Host-local QR invitation.")
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            val permissionDenied = Build.VERSION.SDK_INT >= 37 &&
                errorCode == NsdManager.FAILURE_PERMISSION_DENIED
            if (permissionDenied) permissionDenied()
            else fail("Nearby discovery failed to start (system code $errorCode). Use the Host-local QR invitation.")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            fail("Nearby discovery stopped unexpectedly (system code $errorCode). Use the Host-local QR invitation.")
        }
    }

    private fun pairedHostIds(): Set<String> = runCatching {
        // S-multi-host: every confirmed Host is marked PAIRED, not just the first.
        PairedHostStore(applicationContext).list().mapTo(mutableSetOf()) { record ->
            MessageDigest.getInstance("SHA-256").digest(record.hostPublicKey)
                .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
        }
    }.getOrElse { emptySet() }

    private fun finish(explanation: String) {
        stopListener()
        mutableState.update { current ->
            current.copy(
                phase = if (current.hosts.isEmpty()) {
                    LanDiscoveryPhase.MANUAL_RECOVERY
                } else {
                    LanDiscoveryPhase.COMPLETE
                },
                explanation = if (current.hosts.isEmpty()) {
                    "No service answered this search. This may be Wi-Fi or multicast isolation, not proof that no DSH Host exists. $explanation"
                } else {
                    explanation
                },
            )
        }
    }

    private fun fail(message: String) {
        stopListener()
        mutableState.update {
            it.copy(phase = LanDiscoveryPhase.MANUAL_RECOVERY, explanation = message)
        }
    }

    private fun stopListener() {
        handler.removeCallbacks(timeout)
        val active = listener ?: return
        listener = null
        runCatching { nsd.stopServiceDiscovery(active) }
    }

    private companion object {
        const val DISCOVERY_WINDOW_MS = 15_000L
    }
}
