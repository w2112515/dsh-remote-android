package dev.dshremote.gate0c.transport

import android.content.Context
import dev.dshremote.security.PairedHostStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * S-multi-host fleet owner: one [Gate0CClient] per paired Host record, all
 * connected concurrently. Each client's stores are already per-Host (offline
 * cache, pending commands), so slices never share durable state. The UI reads
 * [slices] and derives the aggregated directory; effectful actions route to
 * the owning client via [clientFor] or the session/approval lookup helpers.
 */
class HostFleet(context: Context) {
    private val appContext = context.applicationContext
    private val pairedHostStore = PairedHostStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val clients = mutableMapOf<String, Gate0CClient>()
    private val observers = mutableMapOf<String, Job>()
    // S-supervisor (ADR-007): one demand-scoped management link per Host,
    // created lazily when the Host sheet first looks and kept until the
    // record is revoked. The link itself connects only between ensure() and
    // release(), so an unopened sheet costs nothing.
    private val supervisors = mutableMapOf<String, SupervisorClient>()
    private val supervisorObservers = mutableMapOf<String, Job>()

    private val mutableSlices = MutableStateFlow<List<HostSlice>>(emptyList())
    private val mutableSupervisorViews = MutableStateFlow<Map<String, SupervisorLinkView>>(emptyMap())

    /** One fleet member: stable store identity plus its live client state. */
    data class HostSlice(
        val hostId: String,
        val client: Gate0CClient,
        val state: Gate0CState,
    )

    val slices: StateFlow<List<HostSlice>> = mutableSlices.asStateFlow()

    /** Latest supervisor management-link face per Host id. */
    internal val supervisorViews: StateFlow<Map<String, SupervisorLinkView>> = mutableSupervisorViews.asStateFlow()

    /**
     * Reconcile the fleet with the store: add clients for new records, drop
     * clients whose record was revoked/reset. Safe to call after pairing or
     * revocation. Returns the current paired host ids.
     */
    fun syncHosts(): List<String> {
        val records = pairedHostStore.list()
        val ids = records.map(pairedHostStore::hostIdOf)
        synchronized(lock) {
            val removed = clients.keys - ids.toSet()
            removed.forEach { hostId ->
                observers.remove(hostId)?.cancel()
                clients.remove(hostId)?.close()
                supervisorObservers.remove(hostId)?.cancel()
                supervisors.remove(hostId)?.close()
                mutableSupervisorViews.value = mutableSupervisorViews.value - hostId
            }
            ids.forEach { hostId ->
                if (hostId !in clients) {
                    val client = Gate0CClient(appContext, hostId)
                    clients[hostId] = client
                    observers[hostId] = scope.launch {
                        client.state.collect { publish(hostId, it) }
                    }
                    scope.launch { client.connect() }
                }
            }
        }
        publishAll()
        return ids
    }

    fun clientFor(hostId: String): Gate0CClient? = synchronized(lock) { clients[hostId] }

    /** The Host's supervisor management link, created lazily (S-supervisor). */
    internal fun supervisorFor(hostId: String): SupervisorClient = synchronized(lock) {
        supervisors.getOrPut(hostId) {
            SupervisorClient(appContext, hostId, scope).also { client ->
                supervisorObservers[hostId] = scope.launch {
                    client.view.collect { view ->
                        mutableSupervisorViews.value = mutableSupervisorViews.value + (hostId to view)
                    }
                }
            }
        }
    }

    /** The client whose directory or open session currently names [sessionId]. */
    fun clientForSession(sessionId: String): Gate0CClient? = synchronized(lock) {
        clients.entries.firstOrNull { (_, client) ->
            client.state.value.sessionId == sessionId ||
                client.state.value.sessions.any { it.sessionId == sessionId }
        }?.value
    }

    /** The client whose live approvals currently name [approvalId]. */
    fun clientForApproval(approvalId: String): Gate0CClient? = synchronized(lock) {
        clients.entries.firstOrNull { (_, client) ->
            client.state.value.approvals.any { it.approvalId == approvalId }
        }?.value
    }

    fun reconnectAll() {
        synchronized(lock) { clients.values.forEach { it.connect() } }
    }

    fun close() {
        synchronized(lock) {
            observers.values.forEach { it.cancel() }
            observers.clear()
            clients.values.forEach { it.close() }
            clients.clear()
            supervisorObservers.values.forEach { it.cancel() }
            supervisorObservers.clear()
            supervisors.values.forEach { it.close() }
            supervisors.clear()
        }
        scope.cancel()
    }

    private fun publish(changedHostId: String, changed: Gate0CState) {
        mutableSlices.value = synchronized(lock) {
            clients.map { (hostId, client) ->
                HostSlice(hostId, client, if (hostId == changedHostId) changed else client.state.value)
            }
        }
    }

    private fun publishAll() {
        mutableSlices.value = synchronized(lock) {
            clients.map { (hostId, client) -> HostSlice(hostId, client, client.state.value) }
        }
    }
}
