package dev.dshremote.gate0c

import android.Manifest
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import dev.dshremote.discovery.LanDiscoveryClient
import dev.dshremote.discovery.LanDiscoveryPhase
import dev.dshremote.discovery.LanDiscoveryState
import dev.dshremote.gate0c.transport.ConnectionPhase
import dev.dshremote.gate0c.transport.ApprovalInteractionState
import dev.dshremote.gate0c.transport.ApprovalRisk
import dev.dshremote.gate0c.transport.Gate0CClient
import dev.dshremote.gate0c.transport.Gate0CState
import dev.dshremote.gate0c.transport.HostFleet
import dev.dshremote.gate0c.transport.PendingCommandOperation
import dev.dshremote.gate0c.transport.PendingCommandProgress
import dev.dshremote.gate0c.transport.PendingApprovalDecision
import dev.dshremote.gate0c.transport.SupervisorLinkView
import dev.dshremote.gate0c.transport.SupervisorStatusView
import dev.dshremote.gate0c.transport.hasCapabilities
import dev.dshremote.security.PairedHostLockedException
import dev.dshremote.security.PairedHostStore
import dev.dshremote.gate0c.ui.DshColors
import dev.dshremote.gate0c.ui.DshRemoteTheme
import dev.dshremote.gate0c.ui.RendererFixture
import dev.dshremote.gate0c.ui.SessionTimeline
import dev.dshremote.gate0c.ui.v2.V2App
import dev.dshremote.gate0c.ui.v2.V2Callbacks
import dev.dshremote.gate0c.ui.v2.V2HostFace
import dev.dshremote.gate0c.ui.v2.V2Notification
import dev.dshremote.gate0c.ui.v2.V2NotificationCenter
import dev.dshremote.gate0c.ui.v2.V2NotificationKind
import dev.dshremote.gate0c.ui.v2.V2SupervisorFace
import dev.dshremote.gate0c.ui.v2.V2Tab
import dev.dshremote.gate0c.ui.v2.V2Theme
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val fleet by lazy { HostFleet(applicationContext) }
    private val discovery by lazy { LanDiscoveryClient(applicationContext) }
    private val nearbyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) discovery.start() else discovery.permissionDenied()
    }
    private var rendererFixture = false
    // S-multi-host: activity-level ceremony/resync signals (compose-safe state).
    private val resyncTrigger = mutableIntStateOf(0)
    private val pendingInvitation = mutableStateOf<String?>(null)
    private val ceremonySaved = mutableStateOf(false)
    private val transientClients = mutableListOf<Gate0CClient>()

    private fun newCeremonyClient(onSaved: () -> Unit): Gate0CClient =
        Gate0CClient(applicationContext) {
            ceremonySaved.value = true
            onSaved()
        }.also(transientClients::add)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        rendererFixture = BuildConfig.ENABLE_RENDERER_FIXTURE &&
            intent.getStringExtra(RENDERER_FIXTURE_EXTRA) in setOf(
                RENDERER_FIXTURE_LONG,
                RENDERER_FIXTURE_DIRECTORY,
                RENDERER_FIXTURE_OFFLINE,
                RENDERER_FIXTURE_INCOMPATIBLE,
                RENDERER_FIXTURE_COMMAND_UNKNOWN,
                RENDERER_FIXTURE_STOP_REQUESTED,
                RENDERER_FIXTURE_APPROVAL_SENSITIVE,
                RENDERER_FIXTURE_APPROVAL_DESTRUCTIVE,
                RENDERER_FIXTURE_NEW_PAIRING,
                RENDERER_FIXTURE_DISCOVERY,
                RENDERER_FIXTURE_V2_HOME,
                RENDERER_FIXTURE_V2_CHAT,
                RENDERER_FIXTURE_V2_APPROVALS,
                RENDERER_FIXTURE_V2_OFFLINE,
                RENDERER_FIXTURE_V2_BLANK,
                RENDERER_FIXTURE_V2_FLEET,
                RENDERER_FIXTURE_V2_BLOB,
            )
        setContent {
            DshRemoteTheme {
                val fixtureMode = intent.getStringExtra(RENDERER_FIXTURE_EXTRA)
                var showDirectory by rememberSaveable { mutableStateOf(fixtureMode == RENDERER_FIXTURE_DIRECTORY) }
                val stateHolder = rememberSaveableStateHolder()
                if (rendererFixture) {
                    if (fixtureMode in V2_FIXTURE_MODES) {
                        val fixtureCenter = remember { V2NotificationCenter() }
                        // S-blob audit fixture: a deterministic local PNG stands
                        // in for picked/fetched image bytes so the composer
                        // thumbnail, timeline image and artifact full-fetch all
                        // exercise their Ready renderings.
                        val fixtureBlob = remember { generateFixtureBlobAssets(applicationContext) }
                        val fixtureCallbacks = V2Callbacks(
                            onOpenSession = {},
                            onReconnect = {},
                            onProbe = {},
                            onAcquireControl = {},
                            onSend = {},
                            onStop = {},
                            onApprovalDecision = { _, _ -> },
                            onReconcile = {},
                            onClearLocalCopy = {},
                            onStartNewPairing = {},
                            onDraftChanged = {},
                            onReadingPositionChanged = { _, _, _ -> },
                            onCreateSession = { null },
                            onSelectAgentPreset = {},
                            onSelectModel = { _, _, _ -> },
                            onForkSession = { null },
                            onRevokeRule = {},
                            onSetBudget = {},
                            onAttachImage = {},
                            onRemoveComposerImage = {},
                            onRemoveCommittedImage = {},
                            onResumeStagedUpload = {},
                            onAbandonStagedUpload = {},
                            fetchImage = { _, _ ->
                                dev.dshremote.gate0c.transport.BlobFetchView.Ready(
                                    fixtureBlob.imageFile,
                                    fixtureBlob.imageFile.length(),
                                )
                            },
                            fetchArtifact = { _, _ ->
                                dev.dshremote.gate0c.transport.BlobFetchView.Ready(
                                    fixtureBlob.textFile,
                                    fixtureBlob.textFile.length(),
                                )
                            },
                        )
                        V2Theme {
                            V2App(
                                hosts = when (fixtureMode) {
                                    // S-multi-host fixture: two faces — one ready
                                    // fleet member, one offline — so the aggregated
                                    // directory/filter/headers are auditable. The
                                    // supervisor faces stage both ADR-007 shapes:
                                    // running (restart/stop) and operator-stopped
                                    // (已停止 · 可启动).
                                    RENDERER_FIXTURE_V2_FLEET -> listOf(
                                        V2HostFace(
                                            hostId = "fixture-studio",
                                            label = "studio-host",
                                            detail = "studio-host · 在线",
                                            state = RendererFixture.directory,
                                            callbacks = fixtureCallbacks,
                                            supervisor = V2SupervisorFace(
                                                link = SupervisorLinkView.Online(
                                                    hostInstanceId = "studio-host",
                                                    canManage = true,
                                                    status = SupervisorStatusView(
                                                        state = "running",
                                                        downReason = null,
                                                        consecutiveCrashes = 0,
                                                        nextRestartAtMs = null,
                                                        childPid = 4242,
                                                        childSinceMs = System.currentTimeMillis() - 3_600_000,
                                                        lastExitCode = null,
                                                        lastExitSignal = null,
                                                    ),
                                                    pendingVerb = null,
                                                    lastRefusal = null,
                                                ),
                                                ensure = {},
                                                release = {},
                                                onStart = {},
                                                onStop = {},
                                                onRestart = {},
                                            ),
                                        ),
                                        V2HostFace(
                                            hostId = "fixture-devbox",
                                            label = "dev-box",
                                            detail = "dev-box · 离线",
                                            state = RendererFixture.offlineSession,
                                            callbacks = fixtureCallbacks,
                                            supervisor = V2SupervisorFace(
                                                link = SupervisorLinkView.Online(
                                                    hostInstanceId = "dev-box",
                                                    canManage = true,
                                                    status = SupervisorStatusView(
                                                        state = "down",
                                                        downReason = "operator",
                                                        consecutiveCrashes = 0,
                                                        nextRestartAtMs = null,
                                                        childPid = null,
                                                        childSinceMs = null,
                                                        lastExitCode = 0,
                                                        lastExitSignal = null,
                                                    ),
                                                    pendingVerb = null,
                                                    lastRefusal = null,
                                                ),
                                                ensure = {},
                                                release = {},
                                                onStart = {},
                                                onStop = {},
                                                onRestart = {},
                                            ),
                                        ),
                                    )
                                    else -> listOf(
                                        V2HostFace(
                                            hostId = "fixture",
                                            label = "fixture-host",
                                            detail = "fixture-host",
                                            state = when (fixtureMode) {
                                                RENDERER_FIXTURE_V2_CHAT -> RendererFixture.longSession
                                                RENDERER_FIXTURE_V2_APPROVALS -> RendererFixture.approvalDestructiveSession.copy(
                                                    sessions = RendererFixture.directory.sessions.map { session ->
                                                        if (session.pendingApprovalCount > 0) {
                                                            session.copy(pendingApprovalCount = 1)
                                                        } else {
                                                            session
                                                        }
                                                    },
                                                )
                                                RENDERER_FIXTURE_V2_OFFLINE -> RendererFixture.offlineSession
                                                RENDERER_FIXTURE_V2_BLANK -> RendererFixture.blankSession
                                                RENDERER_FIXTURE_V2_BLOB -> RendererFixture.blobSession(
                                                    previewUri = android.net.Uri.fromFile(fixtureBlob.imageFile).toString(),
                                                    fetchedBytes = fixtureBlob.imageFile.length(),
                                                )
                                                else -> RendererFixture.directory
                                            },
                                            callbacks = fixtureCallbacks,
                                        ),
                                    )
                                },
                                notifications = fixtureNotifications,
                                notificationCenter = fixtureCenter,
                                initialChatSessionId = when (fixtureMode) {
                                    RENDERER_FIXTURE_V2_CHAT -> RendererFixture.longSession.sessionId
                                    RENDERER_FIXTURE_V2_BLANK -> RendererFixture.blankSession.sessionId
                                    RENDERER_FIXTURE_V2_BLOB -> "fixture-blob-session"
                                    else -> null
                                },
                                initialTab = if (fixtureMode == RENDERER_FIXTURE_V2_APPROVALS) {
                                    V2Tab.APPROVALS
                                } else {
                                    V2Tab.SESSIONS
                                },
                                // Fleet fixture only: render the add-host affordance
                                // (header chip + host-sheet row) for the audit.
                                onAddHost = if (fixtureMode == RENDERER_FIXTURE_V2_FLEET) {
                                    {}
                                } else {
                                    null
                                },
                            )
                        }
                    } else if (fixtureMode == RENDERER_FIXTURE_DISCOVERY) {
                        PairingScreen(
                            state = Gate0CState(phase = ConnectionPhase.UNPAIRED),
                            discovery = RendererFixture.discovery,
                            onDiscover = {},
                            onPair = {},
                            onRetryRecovery = {},
                            onStartNewPairing = {},
                        )
                    } else if (showDirectory) {
                        SessionDirectoryScreen(
                            state = RendererFixture.directory,
                            onSessionSelected = { showDirectory = false },
                            onReconnect = {},
                        )
                    } else {
                        stateHolder.SaveableStateProvider("fixture-long-session") {
                            SessionScreen(
                                state = when (fixtureMode) {
                                    RENDERER_FIXTURE_OFFLINE -> RendererFixture.offlineSession
                                    RENDERER_FIXTURE_INCOMPATIBLE -> RendererFixture.incompatibleSession
                                    RENDERER_FIXTURE_COMMAND_UNKNOWN -> RendererFixture.commandUnknownSession
                                    RENDERER_FIXTURE_STOP_REQUESTED -> RendererFixture.stopRequestedSession
                                    RENDERER_FIXTURE_APPROVAL_SENSITIVE -> RendererFixture.approvalSensitiveSession
                                    RENDERER_FIXTURE_APPROVAL_DESTRUCTIVE -> RendererFixture.approvalDestructiveSession
                                    RENDERER_FIXTURE_NEW_PAIRING -> RendererFixture.offlineSession.copy(
                                        newPairingRequired = true,
                                        readingAnchorId = null,
                                        readingOffsetPx = 0,
                                        followTail = false,
                                    )
                                    else -> RendererFixture.longSession
                                },
                                onBack = { showDirectory = true },
                                onConnect = {},
                                onProbe = {},
                                onAcquireControl = {},
                                onSend = {},
                                onStop = {},
                                onApprovalDecision = { _, _ -> },
                                onReconcile = {},
                                onClearLocalCopy = {},
                                onStartNewPairing = {},
                                onDraftChanged = {},
                                onReadingPositionChanged = { _, _, _ -> },
                            )
                        }
                    }
                } else {
                    // S-multi-host: the fleet owns one client per paired Host. A
                    // pairing ceremony (first Host or 添加主机) runs on a transient
                    // client; its confirmed record joins the fleet via resync.
                    val slices by fleet.slices.collectAsStateWithLifecycle()
                    val supervisorViews by fleet.supervisorViews.collectAsStateWithLifecycle()
                    val discoveryState by discovery.state.collectAsStateWithLifecycle()
                    var ceremony by remember { mutableStateOf<Gate0CClient?>(null) }
                    var fleetFailure by remember { mutableStateOf<String?>(null) }
                    val ceremonyState by (ceremony?.state ?: kotlinx.coroutines.flow.flowOf(null))
                        .collectAsStateWithLifecycle(null)

                    val resync: () -> Unit = {
                        fleetFailure = try {
                            fleet.syncHosts()
                            null
                        } catch (error: PairedHostLockedException) {
                            "配对记录被设备锁封存 · 解锁后自动恢复，无需修复"
                        } catch (error: Throwable) {
                            "配对记录无法认证 · 需要显式修复"
                        }
                    }
                    LaunchedEffect(resyncTrigger.intValue) { resync() }

                    // A pending invitation deep link starts a ceremony client.
                    LaunchedEffect(pendingInvitation.value) {
                        val invitation = pendingInvitation.value ?: return@LaunchedEffect
                        pendingInvitation.value = null
                        val client = newCeremonyClient { resyncTrigger.intValue++ }
                        ceremony = client
                        client.pair(invitation)
                    }
                    // An unfinished recovery ceremony resumes on a transient client.
                    LaunchedEffect(resyncTrigger.intValue) {
                        if (ceremony == null && fleetFailure == null) {
                            val pending = runCatching { PairedHostStore(applicationContext).loadPendingRecovery() }
                                .getOrNull()
                            if (pending != null) {
                                val client = newCeremonyClient { resyncTrigger.intValue++ }
                                ceremony = client
                                client.connect()
                            }
                        }
                    }
                    // Ceremony finished (record saved): adopt into the fleet.
                    LaunchedEffect(resyncTrigger.intValue, ceremony) {
                        if (ceremony != null && ceremonySaved.value) {
                            ceremonySaved.value = false
                            resync()
                            ceremony?.close()
                            ceremony = null
                        }
                    }

                    val showPairing = fleetFailure == null &&
                        (ceremony != null || (slices.isEmpty() && pendingInvitation.value == null))
                    when {
                        fleetFailure != null -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .padding(horizontal = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(fleetFailure!!, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                            TextButton(onClick = { resyncTrigger.intValue++ }) { Text("重试") }
                        }
                        showPairing -> PairingScreen(
                            state = ceremonyState ?: Gate0CState(phase = ConnectionPhase.UNPAIRED),
                            discovery = discoveryState,
                            onDiscover = ::startLanDiscovery,
                            onPair = { invitation ->
                                val client = newCeremonyClient { resyncTrigger.intValue++ }
                                ceremony = client
                                client.pair(invitation)
                            },
                            onRetryRecovery = { ceremony?.connect() },
                            onStartNewPairing = {},
                        )
                        else -> {
                            val notificationCenter = remember { V2NotificationCenter() }
                            val notifications by notificationCenter.notifications.collectAsStateWithLifecycle()
                            val prevStates = remember { mutableStateOf(mapOf<String, Gate0CState>()) }
                            LaunchedEffect(slices) {
                                val prev = prevStates.value
                                slices.forEach { slice ->
                                    prev[slice.hostId]?.let {
                                        notificationCenter.reduce(
                                            it,
                                            slice.state,
                                            slice.hostId,
                                            if (slices.size > 1) slice.label() else null,
                                        )
                                    }
                                }
                                prevStates.value = slices.associate { it.hostId to it.state }
                            }
                            val hostFaces = slices.map { slice ->
                                val base = slice.label()
                                val colliding = slices.count { it.label() == base } > 1
                                V2HostFace(
                                    hostId = slice.hostId,
                                    label = if (colliding) {
                                        "$base · ${slice.hostId.take(8).uppercase()}"
                                    } else {
                                        base
                                    },
                                    detail = "${slice.state.endpoint} · ${slice.hostId.take(8).uppercase()}",
                                    state = slice.state,
                                    callbacks = V2Callbacks(
                                        onOpenSession = slice.client::selectSession,
                                        onReconnect = slice.client::connect,
                                        onProbe = slice.client::runDisabledCommandProbe,
                                        onAcquireControl = slice.client::acquireControl,
                                        onSend = slice.client::sendDraft,
                                        onStop = slice.client::stopActive,
                                        onApprovalDecision = slice.client::decideApproval,
                                        onReconcile = slice.client::reconcilePendingCommand,
                                        onClearLocalCopy = slice.client::clearOfflineWorkspace,
                                        onStartNewPairing = {
                                            slice.client.startNewPairingCeremony()
                                            resyncTrigger.intValue++
                                        },
                                        onDraftChanged = slice.client::updateLocalDraft,
                                        onReadingPositionChanged = slice.client::updateReadingPosition,
                                        onCreateSession = slice.client::createSession,
                                        onSelectAgentPreset = slice.client::selectAgentPreset,
                                        onSelectModel = slice.client::selectModel,
                                        onForkSession = slice.client::forkSession,
                                        onRevokeRule = slice.client::revokeApprovalRule,
                                        onSetBudget = slice.client::setSessionBudget,
                                        onAttachImage = slice.client::attachImage,
                                        onRemoveComposerImage = slice.client::removeComposerImage,
                                        onRemoveCommittedImage = slice.client::removeCommittedImage,
                                        onResumeStagedUpload = slice.client::resumeStagedUpload,
                                        onAbandonStagedUpload = slice.client::abandonStagedUpload,
                                        fetchImage = slice.client::fetchAttachmentImage,
                                        fetchArtifact = slice.client::fetchArtifactContent,
                                    ),
                                    // S-supervisor (ADR-007): the resident
                                    // supervisor's management link — the sheet
                                    // scopes its lifetime via ensure/release.
                                    supervisor = slice.hostId.let { hostId ->
                                        V2SupervisorFace(
                                            link = supervisorViews[hostId] ?: SupervisorLinkView.Idle,
                                            ensure = { fleet.supervisorFor(hostId).ensure() },
                                            release = { fleet.supervisorFor(hostId).release() },
                                            onStart = { fleet.supervisorFor(hostId).start() },
                                            onStop = { fleet.supervisorFor(hostId).stop() },
                                            onRestart = { fleet.supervisorFor(hostId).restart() },
                                        )
                                    },
                                    onForget = {
                                        slice.client.startNewPairingCeremony()
                                        resyncTrigger.intValue++
                                    },
                                )
                            }
                            V2Theme {
                                V2App(
                                    hosts = hostFaces,
                                    notifications = notifications,
                                    notificationCenter = notificationCenter,
                                    voiceEnabled = true,
                                    onAddHost = {
                                        ceremony = newCeremonyClient { resyncTrigger.intValue++ }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (!rendererFixture) {
            intent.data?.toString()
                ?.takeIf { it.startsWith("dsh-remote://pair/v1#") }
                ?.let { pendingInvitation.value = it }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.toString()
            ?.takeIf { it.startsWith("dsh-remote://pair/v1#") }
            ?.let { pendingInvitation.value = it }
    }

    override fun onResume() {
        super.onResume()
        // An activity paused behind the keyguard resumes when the keyguard is dismissed;
        // that is the reliable unlock signal on devices that skip USER_PRESENT. Retry
        // every fleet member's sealed storage, then resync the registry itself.
        if (!rendererFixture) {
            fleet.slices.value.forEach { it.client.retryIfStorageSealed("resume") }
            resyncTrigger.intValue++
        }
    }

    override fun onDestroy() {
        if (!rendererFixture) {
            discovery.close()
            transientClients.forEach { it.close() }
            transientClients.clear()
            fleet.close()
        }
        super.onDestroy()
    }

    private fun startLanDiscovery() {
        if (Build.VERSION.SDK_INT !in 33..36 || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            discovery.start()
        } else {
            nearbyPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    companion object {
        const val RENDERER_FIXTURE_EXTRA = "renderer_fixture"
        const val RENDERER_FIXTURE_LONG = "long"
        const val RENDERER_FIXTURE_DIRECTORY = "directory"
        const val RENDERER_FIXTURE_OFFLINE = "offline"
        const val RENDERER_FIXTURE_INCOMPATIBLE = "incompatible"
        const val RENDERER_FIXTURE_COMMAND_UNKNOWN = "command-unknown"
        const val RENDERER_FIXTURE_STOP_REQUESTED = "stop-requested"
        const val RENDERER_FIXTURE_APPROVAL_SENSITIVE = "approval-sensitive"
        const val RENDERER_FIXTURE_APPROVAL_DESTRUCTIVE = "approval-destructive"
        const val RENDERER_FIXTURE_NEW_PAIRING = "new-pairing"
        const val RENDERER_FIXTURE_DISCOVERY = "discovery"
        const val RENDERER_FIXTURE_V2_HOME = "v2-home"
        const val RENDERER_FIXTURE_V2_CHAT = "v2-chat"
        const val RENDERER_FIXTURE_V2_APPROVALS = "v2-approvals"
        const val RENDERER_FIXTURE_V2_OFFLINE = "v2-offline"
        const val RENDERER_FIXTURE_V2_BLANK = "v2-blank"
        const val RENDERER_FIXTURE_V2_FLEET = "v2-fleet"
        const val RENDERER_FIXTURE_V2_BLOB = "v2-blob"
        private val V2_FIXTURE_MODES = setOf(
            RENDERER_FIXTURE_V2_HOME,
            RENDERER_FIXTURE_V2_CHAT,
            RENDERER_FIXTURE_V2_APPROVALS,
            RENDERER_FIXTURE_V2_OFFLINE,
            RENDERER_FIXTURE_V2_BLANK,
            RENDERER_FIXTURE_V2_FLEET,
            RENDERER_FIXTURE_V2_BLOB,
        )
    }
}

private val fixtureNotifications = listOf(
    V2Notification(
        id = 1,
        kind = V2NotificationKind.APPROVAL_ARRIVED,
        text = "「Review the source-backed Host carrier」请求审批：delete_workspace",
        timeMs = System.currentTimeMillis() - 5 * 60_000,
        tab = V2Tab.APPROVALS,
        sessionId = "renderer-review",
        hostId = "fixture",
    ),
    V2Notification(
        id = 2,
        kind = V2NotificationKind.INPUT_WAITING,
        text = "「Build the DSH Remote Android experience」等待输入",
        timeMs = System.currentTimeMillis() - 12 * 60_000,
        tab = V2Tab.SESSIONS,
        sessionId = "renderer-active",
        hostId = "fixture",
    ),
    V2Notification(
        id = 3,
        kind = V2NotificationKind.APPROVAL_SETTLED,
        text = "审批已结算：apply_patch",
        timeMs = System.currentTimeMillis() - 47 * 60_000,
        unread = false,
        tab = V2Tab.APPROVALS,
        sessionId = "renderer-review",
        hostId = "fixture",
    ),
)

/**
 * Fleet face label: the Host's operator-facing display name (stable across
 * restarts), else the per-boot instance id an older Host reports, else the
 * endpoint, else the pin head.
 */
private fun HostFleet.HostSlice.label(): String =
    state.hostDisplayName
        ?: state.hostInstanceId
        ?: state.endpoint.ifBlank { hostId.take(8).uppercase() }

/** Deterministic local files backing the v2-blob audit fixture (image + artifact text). */
private class FixtureBlobAssets(val imageFile: java.io.File, val textFile: java.io.File)

/**
 * Renders a fixed 480×320 quadrant PNG (no randomness — the audit compares
 * pixels) plus a bounded TypeScript file for the artifact full-fetch view.
 * Regeneration is skipped once present; content is deterministic anyway.
 */
private fun generateFixtureBlobAssets(context: android.content.Context): FixtureBlobAssets {
    val dir = java.io.File(context.cacheDir, "fixture-blob").apply { mkdirs() }
    val imageFile = java.io.File(dir, "design-mock.png")
    if (!imageFile.isFile) {
        val bitmap = androidx.core.graphics.createBitmap(480, 320)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(16, 20, 28))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.rgb(59, 130, 246)
        canvas.drawRect(24f, 24f, 222f, 150f, paint)
        paint.color = android.graphics.Color.rgb(34, 197, 94)
        canvas.drawRect(238f, 24f, 456f, 150f, paint)
        paint.color = android.graphics.Color.rgb(245, 158, 11)
        canvas.drawRect(24f, 166f, 222f, 296f, paint)
        paint.color = android.graphics.Color.rgb(239, 68, 68)
        canvas.drawRect(238f, 166f, 456f, 296f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 26f
        canvas.drawText("fixture 480x320", 120f, 96f, paint)
        imageFile.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    val textFile = java.io.File(dir, "dark-palette-full.ts")
    if (!textFile.isFile) {
        textFile.writeText(
            buildString {
                appendLine("/** Full file recovered through the blob channel (fixture). */")
                appendLine("export const palette = {")
                appendLine("  bg: '#0B0E14',")
                appendLine("  bg2: '#12161F',")
                appendLine("  card: '#171C27',")
                appendLine("  line: '#242B3A',")
                appendLine("  tx: '#E6EAF2',")
                appendLine("  tx2: '#9AA4B8',")
                appendLine("  tx3: '#5D6680',")
                appendLine("  blue: '#3B82F6',")
                appendLine("  green: '#22C55E',")
                appendLine("  amber: '#F59E0B',")
                appendLine("  red: '#EF4444',")
                appendLine("  cyan: '#22D3EE',")
                appendLine("} as const")
                appendLine("")
                appendLine("export type Palette = typeof palette")
            },
        )
    }
    return FixtureBlobAssets(imageFile = imageFile, textFile = textFile)
}

@Composable
private fun PairingScreen(
    state: Gate0CState,
    discovery: LanDiscoveryState,
    onDiscover: () -> Unit,
    onPair: (String) -> Unit,
    onRetryRecovery: () -> Unit,
    onStartNewPairing: () -> Unit,
) {
    var invitation by rememberSaveable { mutableStateOf("") }
    var selectedHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmNewPairing by rememberSaveable { mutableStateOf(false) }
    val waiting = state.phase == ConnectionPhase.PAIRING ||
        state.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION ||
        state.phase == ConnectionPhase.RECONCILING_PAIRING
    val reconciling = state.pairingRecoveryPending &&
        state.phase != ConnectionPhase.AWAITING_HOST_CONFIRMATION
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text(
                when {
                    state.newPairingRequired -> "Authorization ended"
                    reconciling -> "Confirming Host access"
                    else -> "Pair DSH Host"
                },
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 29.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (state.newPairingRequired) {
                    "The old device identity is fenced. Review the local data removal below before starting a new pairing ceremony."
                } else if (reconciling) {
                    "The final pairing receipt was interrupted. DSH Remote will show no Session metadata " +
                        "until a fresh authenticated connection proves that the Host committed access."
                } else {
                    "Scan or paste a five-minute invitation created on your computer. " +
                        "No Session metadata is available before both devices authenticate."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
            if (state.newPairingRequired) {
                NewPairingRequiredCard(onStart = { confirmNewPairing = true })
            }
            if (!state.pairingRecoveryPending && !state.newPairingRequired) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Nearby Hosts",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Android's private network picker · no Session metadata",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                )
                            }
                            OutlinedButton(
                                onClick = onDiscover,
                                enabled = discovery.phase != LanDiscoveryPhase.DISCOVERING && !waiting,
                            ) {
                                if (discovery.phase == LanDiscoveryPhase.DISCOVERING) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Find")
                                }
                            }
                        }
                        discovery.hosts.forEach { host ->
                            Surface(
                                color = if (selectedHostId == host.hostId) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selectedHostId == host.hostId) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                    },
                                ),
                            ) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            host.displayName,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            host.pairingState.label,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                    Text(
                                        "${host.platform.uppercase()} · HOST ID ${host.hostId.chunked(4).take(4).joinToString(" ")}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp,
                                        lineHeight = 15.sp,
                                    )
                                    OutlinedButton(
                                        onClick = { selectedHostId = host.hostId },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(if (selectedHostId == host.hostId) "Selected" else "Use this Host")
                                    }
                                }
                            }
                        }
                        discovery.explanation?.let { explanation ->
                            Text(
                                explanation,
                                color = if (discovery.phase == LanDiscoveryPhase.MANUAL_RECOVERY) {
                                    DshColors.Warning
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            )
                        }
                        if (selectedHostId != null) {
                            Text(
                                "Selected identity only. Create a five-minute invitation on this Host, then scan or paste it below. Session names and activity remain hidden until Noise pairing succeeds.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            )
                        }
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        OutlinedTextField(
                            value = invitation,
                            onValueChange = { invitation = it },
                            enabled = !waiting,
                            label = { Text("QR invitation or manual recovery") },
                            placeholder = { Text("dsh-remote://pair/v1#…") },
                            minLines = 3,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onPair(invitation.trim()) },
                            enabled = invitation.isNotBlank() && !waiting,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Text("Authenticate Host")
                        }
                    }
                }
            }
            if (state.phase == ConnectionPhase.AWAITING_HOST_CONFIRMATION) {
                Surface(
                    color = DshColors.Warning.copy(alpha = 0.09f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, DshColors.Warning.copy(alpha = 0.45f)),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "COMPARE ON YOUR COMPUTER",
                            color = DshColors.Warning,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp,
                        )
                        Text(
                            state.pairingVerificationCode ?: "────────",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 3.sp,
                        )
                        Text(
                            "Confirm only if the same eight digits appear in the Host-local pairing panel.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }
            if (state.pairingRecoveryPending && state.phase != ConnectionPhase.AWAITING_HOST_CONFIRMATION) {
                Surface(
                    color = DshColors.Warning.copy(alpha = 0.09f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, DshColors.Warning.copy(alpha = 0.45f)),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.phase == ConnectionPhase.RECONCILING_PAIRING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = DshColors.Warning,
                                )
                            }
                            Text(
                                if (state.phase == ConnectionPhase.FAILED) {
                                    "HOST UNREACHABLE · ACCESS NOT ASSUMED"
                                } else {
                                    "VERIFYING HOST AUTHORIZATION"
                                },
                                color = DshColors.Warning,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.0.sp,
                            )
                        }
                        Text(
                            "A Noise IK response from the pinned Host is required before this device is marked paired.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                        state.pairedHostFingerprint?.let { fingerprint ->
                            Text(
                                fingerprint,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        if (state.phase == ConnectionPhase.FAILED) {
                            Button(
                                onClick = onRetryRecovery,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            ) {
                                Text("Retry authorization check")
                            }
                        }
                    }
                }
            }
            state.failure?.let { failure ->
                Text(
                    failure,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
            Text(
                when {
                    hasCapabilities(state.grantedCapabilities, 351uL) ->
                        "HOST SUPERVISOR · Session supervisor + dsh start/stop/restart"
                    hasCapabilities(state.grantedCapabilities, 95uL) ->
                        "SESSION SUPERVISOR · Read + Send + Stop + one-time approvals"
                    hasCapabilities(state.grantedCapabilities, 72uL) ->
                        "SESSION OPERATOR · Read + Send input + exact-turn Stop"
                    hasCapabilities(state.grantedCapabilities, 68uL) ->
                        "SESSION CONTROL · Read + Send input · Stop/approvals excluded"
                    hasCapabilities(state.grantedCapabilities, 19uL) ->
                        "APPROVAL REVIEWER · Read + one-time approvals · Send/Stop excluded"
                    else -> "READ-ONLY · Host observe + Session read"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
        }
    }
    if (confirmNewPairing) {
        NewPairingConfirmationDialog(
            onDismiss = { confirmNewPairing = false },
            onConfirm = {
                confirmNewPairing = false
                onStartNewPairing()
            },
        )
    }
}

@Composable
private fun NewPairingRequiredCard(onStart: () -> Unit) {
    Surface(
        color = DshColors.Danger.copy(alpha = 0.08f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, DshColors.Danger.copy(alpha = 0.45f)),
        modifier = Modifier.testTag("new-pairing-required"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "AUTHORIZATION ENDED",
                color = DshColors.Danger,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Text(
                "This device identity can no longer authenticate. A new Host invitation alone is not enough.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("start-new-pairing"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Start new pairing")
            }
        }
    }
}

@Composable
internal fun NewPairingConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a new device identity?") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "This permanently removes the old device identity, Host pin, cached Sessions, local drafts, " +
                        "control lease and protected pending commands from this phone. It does not revoke or erase " +
                        "records on the Host.",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = acknowledged,
                            role = Role.Checkbox,
                            onValueChange = { acknowledged = it },
                        )
                        .semantics {
                            stateDescription = if (acknowledged) "Acknowledged" else "Not acknowledged"
                        }
                        .testTag("new-pairing-acknowledgement")
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = null)
                    Text("I understand old offline data and pending actions cannot be recovered on this phone.")
                }
                Text(
                    "After reset, create a new invitation on the Host and compare the new eight-digit code.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep current local identity") }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = acknowledged,
                modifier = Modifier.testTag("confirm-new-pairing"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Remove identity and pair again")
            }
        },
    )
}

@Composable
internal fun SessionScreen(
    state: Gate0CState,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onProbe: () -> Unit,
    onAcquireControl: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onApprovalDecision: (String, PendingApprovalDecision) -> Unit,
    onReconcile: () -> Unit,
    onClearLocalCopy: () -> Unit,
    onStartNewPairing: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onReadingPositionChanged: (String?, Int, Boolean) -> Unit,
) {
    val noticeCount = listOf(
        state.offlineSnapshot,
        state.offlineCacheTruncated,
        state.readingAnchorUnavailable,
        state.cacheWarning != null,
        state.historyTruncated,
    ).count { it }
    val attentionCount = 1
    val restoredIndex = state.readingAnchorId
        ?.let { anchor -> state.timeline.indexOfFirst { it.id == anchor } }
        ?.takeIf { it >= 0 }
        ?.plus(attentionCount + noticeCount)
        ?: 0
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = restoredIndex,
        initialFirstVisibleItemScrollOffset = state.readingOffsetPx,
    )
    var followTail by rememberSaveable(state.sessionId) {
        mutableStateOf(state.followTail && state.approvals.isEmpty())
    }
    var selectedToolId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedTool = state.timeline.find { it.id == selectedToolId }
    LaunchedEffect(state.approvals.firstOrNull()?.revision) {
        if (state.approvals.isNotEmpty()) {
            followTail = false
            listState.scrollToItem(0)
        }
    }
    if (selectedTool != null) {
        ToolDetailScreen(entry = selectedTool, onBack = { selectedToolId = null })
        return
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            SessionHeader(state, onBack)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            SessionTimeline(
                timeline = state.timeline,
                historyTruncated = state.historyTruncated,
                attentionContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        AccessBoundary(state, onProbe, onClearLocalCopy, onStartNewPairing)
                        if (state.approvals.isNotEmpty()) {
                            ApprovalPanel(
                                approvals = state.approvals,
                                pendingCommandOperation = state.pendingCommand?.operation,
                                decisionAuthorized = hasCapabilities(state.grantedCapabilities, 16uL) &&
                                    (state.phase == ConnectionPhase.READY ||
                                        state.phase == ConnectionPhase.RECONCILED),
                                onDecision = onApprovalDecision,
                            )
                        }
                    }
                },
                listState = listState,
                followTail = followTail,
                onFollowTailChanged = { followTail = it },
                onReadingPositionChanged = { anchor, offset ->
                    onReadingPositionChanged(anchor, offset, followTail)
                },
                onToolSelected = { selectedToolId = it.id },
                offlineSnapshot = state.offlineSnapshot,
                offlineCacheSavedAtMs = state.offlineCacheSavedAtMs,
                offlineCacheTruncated = state.offlineCacheTruncated,
                readingAnchorUnavailable = state.readingAnchorUnavailable,
                cacheWarning = state.cacheWarning,
                modifier = Modifier.weight(1f),
            )
            if (!state.newPairingRequired && (state.phase == ConnectionPhase.FAILED ||
                state.phase == ConnectionPhase.CLOSED ||
                state.phase == ConnectionPhase.OFFLINE)
            ) {
                RecoveryBar(state.failure, onConnect)
            } else if (state.phase == ConnectionPhase.INCOMPATIBLE) {
                CompatibilityBar()
            }
            if (state.approvals.isEmpty()) {
                InstructionBar(
                    state = state,
                    onDraftChanged = onDraftChanged,
                    onAcquireControl = onAcquireControl,
                    onSend = onSend,
                    onStop = onStop,
                    onReconcile = onReconcile,
                )
            }
        }
    }
}

@Composable
private fun SessionHeader(state: Gate0CState, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text(
                    text = "SESSIONS",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
            }
            PhaseStatus(state.phase)
        }
        Text(
            text = state.sessionTitle ?: "Connecting to DSH",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 23.sp,
            lineHeight = 29.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val running = state.sessionRunning
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        color = when (running) {
                            true -> DshColors.Success
                            false -> MaterialTheme.colorScheme.onSurfaceVariant
                            null -> DshColors.Warning
                        },
                        shape = CircleShape,
                    ),
            )
            Text(
                text = if (state.offlineSnapshot) {
                    when (running) {
                        true -> "Last known: running"
                        false -> "Last known: idle"
                        null -> "Last known state unavailable"
                    }
                } else {
                    when (running) {
                        true -> "Running"
                        false -> "Idle"
                        null -> "State pending"
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Text("·", color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
            Text(
                text = listOfNotNull(state.hostInstanceId, state.cursor?.let { "cursor $it" }).joinToString(" · ")
                    .ifEmpty { state.endpoint },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun PhaseStatus(phase: ConnectionPhase) {
    val color = when (phase) {
        ConnectionPhase.READY, ConnectionPhase.RECONCILED -> DshColors.Success
        ConnectionPhase.GAP_DETECTED,
        ConnectionPhase.SNAPSHOT_REQUIRED,
        ConnectionPhase.SYNCHRONIZING,
        ConnectionPhase.RECONCILING_PAIRING,
        ConnectionPhase.OFFLINE,
        ConnectionPhase.INCOMPATIBLE,
        -> DshColors.Warning
        ConnectionPhase.FAILED -> DshColors.Danger
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(99.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(phase.label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AccessBoundary(
    state: Gate0CState,
    onProbe: () -> Unit,
    onClearLocalCopy: () -> Unit,
    onStartNewPairing: () -> Unit,
) {
    val ready = state.phase == ConnectionPhase.READY || state.phase == ConnectionPhase.RECONCILED
    val incompatible = state.phase == ConnectionPhase.INCOMPATIBLE
    val stale = state.offlineSnapshot || state.phase == ConnectionPhase.OFFLINE || incompatible
    val sessionControl = hasCapabilities(state.grantedCapabilities, 68uL)
    val sessionOperator = hasCapabilities(state.grantedCapabilities, 72uL)
    val approvalReviewer = hasCapabilities(state.grantedCapabilities, 19uL)
    val sessionSupervisor = hasCapabilities(state.grantedCapabilities, 95uL)
    val hostSupervisor = hasCapabilities(state.grantedCapabilities, 351uL)
    val boundaryColor = when {
        state.newPairingRequired -> DshColors.Danger
        (sessionControl || approvalReviewer) && !stale -> DshColors.Success
        else -> DshColors.Warning
    }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmNewPairing by rememberSaveable { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear this offline copy?") },
            text = {
                Text(
                    "This permanently removes cached Sessions, timeline rows, local drafts and reading positions. " +
                        "Your device identity, Host pairing and any separately protected pending command stay intact.",
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Keep copy") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearLocalCopy()
                    },
                ) { Text("Clear local copy", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    if (confirmNewPairing) {
        NewPairingConfirmationDialog(
            onDismiss = { confirmNewPairing = false },
            onConfirm = {
                confirmNewPairing = false
                onStartNewPairing()
            },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(boundaryColor.copy(alpha = 0.08f))
            .padding(start = 20.dp, top = 9.dp, end = 10.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    state.newPairingRequired -> "AUTHORIZATION ENDED · NEW PAIRING REQUIRED"
                    incompatible -> "INCOMPATIBLE · UPDATE REQUIRED"
                    stale -> "OFFLINE · ENCRYPTED SNAPSHOT"
                    hostSupervisor -> "AUTHENTICATED · HOST SUPERVISOR"
                    sessionSupervisor -> "AUTHENTICATED · SESSION SUPERVISOR"
                    sessionOperator -> "AUTHENTICATED · SESSION OPERATOR"
                    sessionControl -> "AUTHENTICATED · SESSION CONTROL"
                    approvalReviewer -> "AUTHENTICATED · APPROVAL REVIEWER"
                    else -> "AUTHENTICATED · READ-ONLY"
                },
                color = boundaryColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            Text(
                if (state.newPairingRequired) {
                    "Old authority is fenced · cached content is not current · pairing again creates a new device identity"
                } else if (incompatible) {
                    if (state.offlineSnapshot) {
                        "Cached content remains stale and read-only · update both endpoints before synchronizing"
                    } else {
                        "This app and Host use incompatible protocol versions"
                    }
                } else if (stale) {
                    buildString {
                        append("Reconnect for authoritative state")
                        state.offlineCacheSavedAtMs?.let { savedAt ->
                            append(" · synced ")
                            append(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(savedAt)),
                            )
                        }
                        append(" · draft remains local")
                    }
                } else if (hostSupervisor) {
                    "Host-authorized Send + Stop + approvals + dsh start/stop/restart"
                } else if (sessionSupervisor) {
                    "Host-authorized Send + exact-turn Stop + one-time approvals"
                } else if (sessionOperator) {
                    "Host-authorized Send + exact-turn Stop · approvals unavailable"
                } else if (sessionControl) {
                    "Host-authorized Send + Session control · Stop and approvals remain unavailable"
                } else if (approvalReviewer) {
                    "Host-authorized one-time approvals · Send and Stop remain locked"
                } else {
                    "Host projection · effects remain locked"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        when {
            state.newPairingRequired -> TextButton(onClick = { confirmNewPairing = true }) {
                Text("Pair again", fontSize = 11.sp)
            }
            state.offlineSnapshot -> TextButton(onClick = { confirmClear = true }) {
                Text("Clear copy", fontSize = 11.sp)
            }
            !incompatible && !sessionControl && !approvalReviewer -> TextButton(onClick = onProbe, enabled = ready) {
                Text("Verify lock", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ApprovalPanel(
    approvals: List<ApprovalInteractionState>,
    pendingCommandOperation: PendingCommandOperation?,
    decisionAuthorized: Boolean,
    onDecision: (String, PendingApprovalDecision) -> Unit,
) {
    val approval = approvals.first()
    var confirmationId by rememberSaveable { mutableStateOf<String?>(null) }
    var destructiveAcknowledged by rememberSaveable(confirmationId) { mutableStateOf(false) }
    val confirmation = approvals.find { it.approvalId == confirmationId }
    val decisionPending = pendingCommandOperation == PendingCommandOperation.DECIDE_APPROVAL
    val riskColor = when (approval.evidence.risk) {
        ApprovalRisk.ROUTINE -> DshColors.Success
        ApprovalRisk.SENSITIVE, ApprovalRisk.UNCLASSIFIED -> DshColors.Warning
        ApprovalRisk.DESTRUCTIVE -> DshColors.Danger
    }
    if (confirmation != null) {
        val destructive = confirmation.evidence.risk == ApprovalRisk.DESTRUCTIVE
        AlertDialog(
            onDismissRequest = { confirmationId = null },
            title = { Text(if (destructive) "Confirm destructive approval" else "Confirm one-time approval") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("approval-confirmation-content"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        confirmation.evidence.summary,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        confirmation.evidence.consequence,
                        color = if (destructive) DshColors.Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (destructive) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = destructiveAcknowledged,
                                    role = Role.Checkbox,
                                    onValueChange = { destructiveAcknowledged = it },
                                )
                                .semantics(mergeDescendants = true) {}
                                .testTag("approval-destructive-acknowledgement"),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = destructiveAcknowledged,
                                onCheckedChange = null,
                            )
                            Text(
                                "I reviewed the affected resources and understand this cannot be undone by DSH Remote.",
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                    Text(
                        "This grants this request once. It does not create a standing permission.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmationId = null }) { Text("Keep blocked") }
            },
            confirmButton = {
                Button(
                    enabled = !destructive || destructiveAcknowledged,
                    modifier = Modifier.testTag("approval-confirm-allow-once"),
                    onClick = {
                        confirmationId = null
                        onDecision(confirmation.approvalId, PendingApprovalDecision.ALLOW_ONCE)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (destructive) DshColors.Danger else MaterialTheme.colorScheme.primary,
                    ),
                ) { Text("Allow once") }
            },
        )
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("approval-attention")
            .semantics {
                testTagsAsResourceId = true
                stateDescription = "${approvals.size} approval request${if (approvals.size == 1) "" else "s"} waiting"
            },
        color = riskColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, riskColor.copy(alpha = 0.42f)),
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "APPROVAL · ${approval.evidence.risk.label.uppercase()}",
                        color = riskColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        approval.evidence.summary,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (approvals.size > 1) {
                    Text("+${approvals.size - 1}", color = riskColor, fontWeight = FontWeight.Bold)
                }
            }
            if (!approval.evidence.available) {
                Text(
                    "Host evidence unavailable · treated as sensitive, never routine",
                    color = DshColors.Warning,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (approval.evidence.resources.isNotEmpty()) {
                Text(
                    approval.evidence.resources.joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                approval.evidence.consequence,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onDecision(approval.approvalId, PendingApprovalDecision.DENY) },
                    enabled = decisionAuthorized && approval.deny && !decisionPending,
                    modifier = Modifier.testTag("approval-deny"),
                ) { Text("Deny") }
                Button(
                    onClick = {
                        if (approval.evidence.risk == ApprovalRisk.ROUTINE) {
                            onDecision(approval.approvalId, PendingApprovalDecision.ALLOW_ONCE)
                        } else {
                            confirmationId = approval.approvalId
                        }
                    },
                    enabled = decisionAuthorized && approval.allowOnce && !decisionPending,
                    modifier = Modifier.testTag("approval-allow-once"),
                ) { Text(if (decisionPending) "Settling…" else "Allow once") }
            }
            Text(
                when {
                    decisionPending -> "Decision recorded locally · waiting for durable Host settlement"
                    !decisionAuthorized -> "This phone can review the request but is not authorized to decide it"
                    else -> "Exact revision ${approval.revision.take(8)} · no always-allow option"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun RecoveryBar(failure: String?, onConnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = failure ?: "The Host stream is closed.",
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Button(
            onClick = onConnect,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("Reconnect")
        }
    }
}

@Composable
private fun CompatibilityBar() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DshColors.Warning.copy(alpha = 0.10f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            "Update required",
            color = DshColors.Warning,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Update DSH Remote and the Host integration together. Reconnect cannot resolve a protocol mismatch.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun InstructionBar(
    state: Gate0CState,
    onDraftChanged: (String) -> Unit,
    onAcquireControl: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onReconcile: () -> Unit,
) {
    val ready = state.phase == ConnectionPhase.READY || state.phase == ConnectionPhase.RECONCILED
    val writeAuthorized = hasCapabilities(state.grantedCapabilities, 68uL)
    val stopAuthorized = hasCapabilities(state.grantedCapabilities, 72uL)
    val pending = state.pendingCommand
    val leaseUsable = state.controlLease?.let { lease ->
        lease.sessionId == state.sessionId && lease.isUsable()
    } == true
    val actionLabel: String
    val actionEnabled: Boolean
    val action: () -> Unit
    when {
        state.commandRecoveryBlocked -> {
            actionLabel = "Repair required"
            actionEnabled = false
            action = {}
        }
        pending != null -> {
            actionLabel = when {
                pending.progress == PendingCommandProgress.UNKNOWN -> "Reconcile"
                pending.operation == PendingCommandOperation.STOP -> "Check Stop"
                pending.operation == PendingCommandOperation.DECIDE_APPROVAL -> "Check approval"
                else -> "Check status"
            }
            actionEnabled = ready && hasCapabilities(
                state.grantedCapabilities,
                when (pending.operation) {
                    PendingCommandOperation.SEND_INPUT -> 68uL
                    PendingCommandOperation.STOP -> 72uL
                    PendingCommandOperation.DECIDE_APPROVAL -> 16uL
                    PendingCommandOperation.CREATE_SESSION -> 68uL
                    PendingCommandOperation.SELECT_AGENT_PRESET -> 68uL
                    PendingCommandOperation.SELECT_MODEL -> 68uL
                    PendingCommandOperation.FORK_SESSION -> 68uL
                    PendingCommandOperation.REVOKE_APPROVAL_RULE -> 16uL
                    PendingCommandOperation.SET_SESSION_BUDGET -> 68uL
                },
            )
            action = onReconcile
        }
        !writeAuthorized -> {
            actionLabel = "Send"
            actionEnabled = false
            action = {}
        }
        !leaseUsable -> {
            actionLabel = "Take control"
            actionEnabled = ready
            action = onAcquireControl
        }
        else -> {
            actionLabel = "Send"
            actionEnabled = ready && state.localDraft.isNotBlank()
            action = onSend
        }
    }
    val settlement = when {
        state.commandRecoveryBlocked ->
            "Protected command state is unreadable · sending is blocked to prevent a duplicate effect"
        pending?.progress == PendingCommandProgress.PREPARED ->
            "Securely recorded before send · reconnect/check status uses the same command id"
        pending?.progress == PendingCommandProgress.RECEIVED ->
            "Host received this command · waiting for durable COMMITTED or definitive REJECTED"
        pending?.progress == PendingCommandProgress.REQUESTED ->
            "Stop reached the exact turn owner · waiting for durable user-abort and Agent quiescence"
        pending?.progress == PendingCommandProgress.UNKNOWN ->
            if (pending.operation == PendingCommandOperation.STOP) {
                "Stop outcome unknown · reconcile the same command id; never target a newer turn"
            } else if (pending.operation == PendingCommandOperation.DECIDE_APPROVAL) {
                "Approval outcome unknown · reconcile the same command id; never decide a newer revision"
            } else {
                "Outcome unknown · reconcile the same command id; do not create a replacement"
            }
        !writeAuthorized -> "Read-only Host grant · choose Session control on the Host to enable Send"
        !ready -> "Draft stays encrypted locally · reconnect before taking control or sending"
        !leaseUsable -> "Take Session control before Send · lease expiry fails closed"
        else -> "Active control epoch ${state.controlLease.epoch} · Send persists locally before transport"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("command-settlement")
            .semantics {
                testTagsAsResourceId = true
                stateDescription = settlement
            },
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (stopAuthorized && state.sessionRunning == true) {
                val stopPending = pending?.operation == PendingCommandOperation.STOP
                val stopEnabled = ready && leaseUsable && pending == null &&
                    state.activityRevision?.let { it > 0 } == true
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (stopPending) "Stopping turn ${pending.expectedActivityRevision}" else
                                "Active turn ${state.activityRevision ?: "pending"}",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Stop preserves queued input and settles only after the target turn is durably aborted.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                    }
                    OutlinedButton(
                        onClick = onStop,
                        enabled = stopEnabled,
                        modifier = Modifier.testTag("stop-active-turn"),
                    ) {
                        Text("Stop turn", color = if (stopEnabled) DshColors.Danger else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.localDraft,
                    onValueChange = onDraftChanged,
                    enabled = pending?.operation != PendingCommandOperation.SEND_INPUT,
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 4,
                    label = { Text(if (pending == null) "Local draft" else "Command locked for settlement") },
                    placeholder = { Text("Tell DSH what to do next") },
                    shape = RoundedCornerShape(12.dp),
                )
                Button(
                    onClick = action,
                    enabled = actionEnabled,
                    modifier = Modifier.testTag("command-action"),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(actionLabel)
                }
            }
            Text(
                settlement,
                color = if (pending?.progress == PendingCommandProgress.UNKNOWN) {
                    DshColors.Warning
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            state.commandWarning?.let { warning ->
                Text(warning, color = DshColors.Warning, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}
