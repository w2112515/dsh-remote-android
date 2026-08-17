package dev.dshremote.gate0c.ui.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import android.util.Log
import dev.dshremote.gate0c.transport.ApprovalInteractionState
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshremote.gate0c.transport.BlobFetchView
import dev.dshremote.gate0c.transport.Gate0CState
import dev.dshremote.gate0c.transport.ImageAttachmentProjection
import dev.dshremote.gate0c.transport.PendingApprovalDecision
import dev.dshremote.gate0c.transport.SessionDirectoryEntry
import dev.dshremote.gate0c.transport.SupervisorLinkView

internal data class V2Callbacks(
    val onOpenSession: (String) -> Unit,
    val onReconnect: () -> Unit,
    val onProbe: () -> Unit,
    val onAcquireControl: () -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onApprovalDecision: (String, PendingApprovalDecision) -> Unit,
    val onReconcile: () -> Unit,
    val onClearLocalCopy: () -> Unit,
    val onStartNewPairing: () -> Unit,
    val onDraftChanged: (String) -> Unit,
    val onReadingPositionChanged: (String?, Int, Boolean) -> Unit,
    // S-mode-select: returns the preallocated Session id when the creation was
    // durably queued, null when gated (phase/capability/pending command).
    val onCreateSession: (String?) -> String?,
    val onSelectAgentPreset: (String) -> Unit,
    // S-session-admin: model triple for the open session's subsequent requests.
    val onSelectModel: (String, String, String?) -> Unit,
    // S-session-admin: returns the preallocated fork-child Session id when the
    // fork was durably queued, null when gated.
    val onForkSession: (Long?) -> String?,
    // S-policy: revoke one exact auto-grant rule / set the session's token
    // budget ceiling; both durably queued like every other command.
    val onRevokeRule: (String) -> Unit,
    val onSetBudget: (Long) -> Unit,
    // S-blob: composer 图片入口、已提交引用移除、中断上传的显式恢复/撤销。
    val onAttachImage: (String) -> Unit,
    val onRemoveComposerImage: (String) -> Unit,
    val onRemoveCommittedImage: (String) -> Unit,
    val onResumeStagedUpload: () -> Unit,
    val onAbandonStagedUpload: () -> Unit,
    // S-blob: 时间线图片与截断产物全文的 blob 通道抓取。
    val fetchImage: suspend (String, ImageAttachmentProjection) -> BlobFetchView,
    val fetchArtifact: suspend (String, String) -> BlobFetchView,
)

/**
 * S-multi-host: one Host's live face — short row label, full sheet detail, the
 * host's own state, and callbacks already bound to its client. A single-Host
 * deployment is a fleet of one; nothing in the panels special-cases it beyond
 * hiding the host chrome.
 */
internal data class V2HostFace(
    val hostId: String,
    val label: String,
    val detail: String,
    val state: Gate0CState,
    val callbacks: V2Callbacks,
    // S-supervisor (ADR-007): the Host's resident-supervisor management link.
    // Null only in fixtures that predate the channel; production always
    // passes a face so the sheet can act on the process even while dsh is down.
    val supervisor: V2SupervisorFace? = null,
    /** Drop this Host's pin and local cache. Null in fixtures. */
    val onForget: (() -> Unit)? = null,
)

/**
 * S-supervisor (ADR-007): one Host's management-link face. [ensure]/[release]
 * scope the link to the Host sheet's lifetime; the verbs are the sheet's
 * restart/stop/start actions, refused honestly by the Host when the paired
 * profile lacks the supervise-host capability.
 */
internal data class V2SupervisorFace(
    val link: SupervisorLinkView,
    val ensure: () -> Unit,
    val release: () -> Unit,
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onRestart: () -> Unit,
)

@Composable
internal fun V2App(
    hosts: List<V2HostFace>,
    notifications: List<V2Notification>,
    notificationCenter: V2NotificationCenter,
    initialChatSessionId: String? = null,
    initialTab: V2Tab = V2Tab.SESSIONS,
    voiceEnabled: Boolean = false,
    // S-multi-host: pairing a further Host is an Android-owned ceremony (no Host
    // owner projects it), surfaced as a quiet action chip beside the fleet pill.
    onAddHost: (() -> Unit)? = null,
) {
    val v2 = LocalV2.current
    var tab by rememberSaveable { mutableStateOf(initialTab.name) }
    // A fixture/deep-link initial chat binds the first Host face — production
    // never passes initialChatSessionId, so this default never fires live.
    var chatHostId by rememberSaveable {
        mutableStateOf(if (initialChatSessionId != null) hosts.firstOrNull()?.hostId else null)
    }
    var chatSessionId by rememberSaveable { mutableStateOf(initialChatSessionId) }
    val multiHost = hosts.size > 1

    // US-07: carrier-driven TalkBack arrival/settlement announcements + foreground banner.
    // announceForAccessibility is a no-op unless a screen reader is active; the logcat
    // marker carries no approval/session identifiers or projected content. S-multi-host:
    // arrivals are tracked per Host so two Hosts' ids never alias each other.
    val view = LocalView.current
    var bannerApproval by remember { mutableStateOf<Pair<V2HostFace, ApprovalInteractionState>?>(null) }
    val seenApprovalIds = remember { mutableStateOf(mapOf<String, Set<String>>()) }
    val approvalsByHost = hosts.map { face -> face.hostId to face.state.approvals.map { it.approvalId }.toSet() }
    LaunchedEffect(approvalsByHost) {
        val seen = seenApprovalIds.value
        hosts.forEach { face ->
            val current = face.state.approvals.map { it.approvalId }.toSet()
            val arrived = face.state.approvals.filter { it.approvalId !in (seen[face.hostId] ?: emptySet()) }
            val settledCount = ((seen[face.hostId] ?: emptySet()) - current).size
            arrived.forEach { approval ->
                view.announceForAccessibility(
                    "审批到达：${approval.toolName}，风险 ${riskLabelV2(approval.evidence.risk)}。" +
                        "打开放行一次或拒绝。",
                )
                bannerApproval = face to approval
                Log.i("DSHRemoteV2", "approval arrival announced")
            }
            repeat(settledCount) {
                view.announceForAccessibility("审批已结算。")
                Log.i("DSHRemoteV2", "approval settlement announced")
            }
        }
        seenApprovalIds.value = approvalsByHost.toMap()
    }
    LaunchedEffect(bannerApproval) {
        if (bannerApproval != null) {
            delay(7_000)
            bannerApproval = null
        }
    }

    val openSession: (String, String) -> Unit = openSession@{ hostId, sessionId ->
        val face = hosts.firstOrNull { it.hostId == hostId } ?: return@openSession
        if (sessionId != face.state.sessionId) face.callbacks.onOpenSession(sessionId)
        chatHostId = hostId
        chatSessionId = sessionId
    }
    // S-mode-select: creation queues the durable command and returns the
    // preallocated id; the COMMITTED receipt subscribes it Host-side, and the
    // chat opens on the id at once (blank until the snapshot lands).
    val createSession: (String, String?) -> Unit = create@{ hostId, preset ->
        val face = hosts.firstOrNull { it.hostId == hostId } ?: return@create
        val newId = face.callbacks.onCreateSession(preset)
        if (newId != null) {
            chatHostId = hostId
            chatSessionId = newId
        }
    }
    // P7 H11/H12：新建入口 = 头部圆形 +（原型流程：直建默认组合的空白会话，
    // 模式在首轮前由 composer 模式 chip 更换）。多台可创建 Host 时先选主机。
    val createEligible = hosts.filter { createAuthorized(it.state) }
    var createHostSheetOpen by rememberSaveable { mutableStateOf(false) }
    if (createHostSheetOpen) {
        V2Sheet(
            title = "新建会话 · 选择主机",
            subtitle = "NEW SESSION · PICK HOST",
            onDismiss = { createHostSheetOpen = false },
        ) {
            createEligible.forEach { face ->
                V2ProjectFilterRow(
                    label = face.label,
                    detail = face.detail,
                    selected = false,
                    onClick = {
                        createHostSheetOpen = false
                        createSession(face.hostId, null)
                    },
                )
            }
        }
    }
    val startCreate: (() -> Unit)? = when {
        createEligible.size == 1 -> ({ createSession(createEligible.single().hostId, null) })
        createEligible.isNotEmpty() -> ({ createHostSheetOpen = true })
        else -> null
    }
    var createBlockedOpen by rememberSaveable { mutableStateOf(false) }
    val onCreateClick: () -> Unit = {
        startCreate?.invoke() ?: run { createBlockedOpen = true }
    }
    if (createBlockedOpen) {
        V2Sheet(
            title = "现在不能新建会话",
            subtitle = "CREATE SESSION",
            onDismiss = { createBlockedOpen = false },
        ) {
            Text(
                createBlockedReason(hosts.map { it.state }),
                color = v2.tx2,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }

    val chatFace = hosts.firstOrNull { it.hostId == chatHostId }

    // 原型 #hosts-btn 的主机管理 sheet：每台 Host 一张事实卡（在线/在跑聚合只
    // 断言在线事实），重连是真实动作；重启/停止/启动经守护进程管理通道执行
    //（S-supervisor，ADR-007），守护不在场时诚实降级为不可达说明。
    var hostMgmtOpen by rememberSaveable { mutableStateOf(false) }
    if (hostMgmtOpen) {
        V2HostSheet(hosts = hosts, onAddHost = onAddHost, onDismiss = { hostMgmtOpen = false })
    }

    // S-artifacts: the viewer is client-local navigation like the chat view.
    // "未验收" is this device's review marker (in-memory, like the notification
    // center) — never a Host fact, never persisted as one.
    var seenArtifactIds by remember { mutableStateOf(setOf<String>()) }
    var viewingArtifact by remember { mutableStateOf<Pair<String, String>?>(null) }
    val openArtifact: (String, String) -> Unit = { hostId, artifactId ->
        seenArtifactIds = seenArtifactIds + "$hostId/$artifactId"
        viewingArtifact = hostId to artifactId
    }
    val viewing = viewingArtifact
    if (viewing != null) {
        val vFace = hosts.firstOrNull { it.hostId == viewing.first }
        val vArtifact = vFace?.state?.artifacts?.firstOrNull { it.artifactId == viewing.second }
        if (vFace != null && vArtifact != null) {
            V2ArtifactViewer(
                face = vFace,
                artifact = vArtifact,
                fetchArtifact = vFace.callbacks.fetchArtifact,
                onClose = { viewingArtifact = null },
                onOpenSource = { hostId, sessionId ->
                    viewingArtifact = null
                    openSession(hostId, sessionId)
                },
            )
            return
        }
        // The roster entry vanished (a fresh hello re-scanned beyond its
        // bound): fall back to the panel rather than strand the viewer.
        LaunchedEffect(viewing, hosts) {
            val stillThere = hosts.firstOrNull { it.hostId == viewing.first }
                ?.state?.artifacts?.any { it.artifactId == viewing.second } == true
            if (!stillThere) viewingArtifact = null
        }
    }

    if (chatFace != null && chatSessionId != null) {
        // S-session-admin: same navigation contract for a fork — the preallocated
        // child id opens at once; COMMITTED subscribes the seeded child Host-side.
        val forkSession: (Long?) -> Unit = { atSeq ->
            val childId = chatFace.callbacks.onForkSession(atSeq)
            if (childId != null) chatSessionId = childId
        }
        V2ChatView(
            state = chatFace.state,
            hostLabel = if (multiHost) chatFace.label else null,
            onBack = {
                chatHostId = null
                chatSessionId = null
            },
            onReconnect = chatFace.callbacks.onReconnect,
            onProbe = chatFace.callbacks.onProbe,
            onAcquireControl = chatFace.callbacks.onAcquireControl,
            onSend = chatFace.callbacks.onSend,
            onStop = chatFace.callbacks.onStop,
            onApprovalDecision = chatFace.callbacks.onApprovalDecision,
            onReconcile = chatFace.callbacks.onReconcile,
            onClearLocalCopy = chatFace.callbacks.onClearLocalCopy,
            onStartNewPairing = chatFace.callbacks.onStartNewPairing,
            onDraftChanged = chatFace.callbacks.onDraftChanged,
            onReadingPositionChanged = chatFace.callbacks.onReadingPositionChanged,
            onSelectAgentPreset = chatFace.callbacks.onSelectAgentPreset,
            onSelectModel = chatFace.callbacks.onSelectModel,
            onForkSession = forkSession,
            onRevokeRule = chatFace.callbacks.onRevokeRule,
            onSetBudget = chatFace.callbacks.onSetBudget,
            onAttachImage = chatFace.callbacks.onAttachImage,
            onRemoveComposerImage = chatFace.callbacks.onRemoveComposerImage,
            onRemoveCommittedImage = chatFace.callbacks.onRemoveCommittedImage,
            onResumeStagedUpload = chatFace.callbacks.onResumeStagedUpload,
            onAbandonStagedUpload = chatFace.callbacks.onAbandonStagedUpload,
            fetchImage = chatFace.callbacks.fetchImage,
            voiceEnabled = voiceEnabled,
        )
        return
    }

    // Live-only: cached (STALE) approvals must not badge the tab or assert "等待你的
    // 决定" — same rule the Now bar already applies to running sessions.
    val approvalBadge = hosts.filter { !it.state.isStaleView() }.sumOf { face ->
        face.state.sessions.sumOf(SessionDirectoryEntry::pendingApprovalCount)
            .coerceAtLeast(face.state.approvals.size)
    }
    val staleApprovalCount = hosts.filter { it.state.isStaleView() }.sumOf { face ->
        face.state.sessions.sumOf(SessionDirectoryEntry::pendingApprovalCount)
    }
    val unreadBadge = notifications.count { it.unread }
    // Live-only like the approval badge: a STALE roster is read-only review,
    // not pending acceptance.
    val artifactBadge = hosts.filter { !it.state.isStaleView() }.sumOf { face ->
        face.state.artifacts.count { "${face.hostId}/${it.artifactId}" !in seenArtifactIds }
    }
    val totalArtifacts = hosts.sumOf { it.state.artifacts.size }
    val running = hosts.firstNotNullOfOrNull { face ->
        face.state.sessions.firstOrNull { it.running && !face.state.isStaleView() }?.let { face to it }
    }
    val totalSessions = hosts.sumOf { it.state.sessions.size }
    val onlineHosts = hosts.count { it.state.isReady() }

    Column(
        Modifier
            .fillMaxSize()
            .background(v2.bg)
            .statusBarsPadding()
            // Expose v2 testTags as uiautomator resource-ids (v1 surfaces set the
            // same flag per subtree); audits and instrumented tests key on them.
            .semantics { testTagsAsResourceId = true },
    ) {
        // Panel header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (V2Tab.valueOf(tab)) {
                        V2Tab.SESSIONS -> "SESSIONS · DSH REMOTE"
                        V2Tab.APPROVALS -> "APPROVALS · DSH REMOTE"
                        V2Tab.ARTIFACTS -> "ARTIFACTS · DSH REMOTE"
                        V2Tab.NOTIFS -> "NOTIFICATIONS · DSH REMOTE"
                    },
                    color = v2.tx3,
                    fontSize = 8.5.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    letterSpacing = 1.6.sp,
                )
                Text(
                    when (V2Tab.valueOf(tab)) {
                        V2Tab.SESSIONS -> "会话"
                        V2Tab.APPROVALS -> "审批"
                        V2Tab.ARTIFACTS -> "产出"
                        V2Tab.NOTIFS -> "通知"
                    },
                    color = v2.tx,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when (V2Tab.valueOf(tab)) {
                        V2Tab.SESSIONS -> if (multiHost) {
                            // 原型 sess-count：跨主机统一计数 + 在线率。
                            "$totalSessions 个会话 · $onlineHosts/${hosts.size} 台主机在线"
                        } else {
                            val only = hosts.firstOrNull()?.state
                            "$totalSessions 个会话 · ${
                                only?.hostDisplayName ?: only?.hostInstanceId ?: only?.endpoint ?: ""
                            }"
                        }
                        V2Tab.APPROVALS -> when {
                            approvalBadge > 0 -> "$approvalBadge 项等待你的决定"
                            staleApprovalCount > 0 -> "在线没有积压 · $staleApprovalCount 项离线缓存待审"
                            else -> "没有积压"
                        }
                        V2Tab.ARTIFACTS -> if (artifactBadge > 0) {
                            "$totalArtifacts 件产出 · $artifactBadge 件未验收"
                        } else {
                            "$totalArtifacts 件产出"
                        }
                        // 原型：未读/总计计数在标题下承载（P7 N2）。
                        V2Tab.NOTIFS -> "$unreadBadge 条未读 · ${notifications.size} 条总计"
                    },
                    color = v2.tx3,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 会话页 = 带「主机」标签的管理入口 + 新建 ＋（空点击改为说明原因）；
            // 通知页 = 全部已读 + 清除已读/清空。
            when (V2Tab.valueOf(tab)) {
                V2Tab.SESSIONS -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val fleet = fleetPhase(hosts, v2)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(v2.card)
                            .border(1.dp, v2.line, RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { hostMgmtOpen = true }
                            .padding(start = 8.dp, end = 10.dp, top = 7.dp, bottom = 7.dp)
                            .semantics { contentDescription = "主机管理 · ${fleet.first}" }
                            .testTag("fleet-pill"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            V2HostGlyph(color = v2.tx2)
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 2.dp, y = (-2).dp)
                                    .size(8.dp)
                                    .background(v2.bg, CircleShape)
                                    .padding(1.5.dp)
                                    .background(fleet.second, CircleShape),
                            )
                        }
                        Text(
                            "主机",
                            color = v2.tx,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    V2HeaderIconButton(
                        onClick = onCreateClick,
                        enabled = true,
                        tag = "create-session",
                        description = if (startCreate != null) "新建会话" else "新建会话 · 当前不可用",
                    ) {
                        Text(
                            "＋",
                            color = if (startCreate != null) v2.tx else v2.tx3,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                V2Tab.NOTIFS -> {
                    val hasRead = notifications.any { !it.unread }
                    val allRead = notifications.isNotEmpty() && unreadBadge == 0
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "全部已读",
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(v2.card)
                                .clickable(enabled = unreadBadge > 0, role = Role.Button) {
                                    notificationCenter.markAllRead()
                                }
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                            color = if (unreadBadge > 0) v2.tx2 else v2.tx3,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (allRead) "清空" else "清除已读",
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(v2.card)
                                .clickable(
                                    enabled = hasRead || allRead,
                                    role = Role.Button,
                                ) {
                                    if (allRead) notificationCenter.clearAll()
                                    else notificationCenter.clearRead()
                                }
                                .padding(horizontal = 11.dp, vertical = 6.dp)
                                .testTag(if (allRead) "notif-clear-all" else "notif-clear-read"),
                            color = if (hasRead || allRead) v2.tx2 else v2.tx3,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                else -> Unit
            }
        }

        // Foreground approval-arrival banner (transient; chat shows the inline card instead)
        bannerApproval?.let { (face, arrived) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .background(v2.amber.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚠", color = v2.amber, fontSize = 13.sp)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (multiHost) "需要审批 · ${face.label}" else "需要审批",
                        color = v2.amber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "$ ${arrived.toolName}",
                        color = v2.tx2,
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "去审批",
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(v2.amber)
                        .clickable(role = Role.Button) {
                            bannerApproval = null
                            tab = V2Tab.APPROVALS.name
                        }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    color = v2.bg,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "稍后",
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { bannerApproval = null }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    color = v2.tx3,
                    fontSize = 11.sp,
                )
            }
        }

        // Panel body
        Box(Modifier.weight(1f)) {
            when (V2Tab.valueOf(tab)) {
                V2Tab.SESSIONS -> V2SessionsPanel(hosts, openSession, onCreateClick, onAddHost)
                V2Tab.APPROVALS -> V2ApprovalsPanel(hosts, openSession)
                V2Tab.ARTIFACTS -> V2ArtifactsPanel(hosts, seenArtifactIds, openArtifact)
                V2Tab.NOTIFS -> V2NotificationsPanel(
                    notifications = notifications,
                    onOpen = { notification ->
                        notificationCenter.markRead(notification.id)
                        notification.tab?.let { tab = it.name }
                        if (notification.artifactId != null && notification.hostId != null) {
                            openArtifact(notification.hostId, notification.artifactId)
                        } else if (notification.hostId != null && notification.sessionId != null) {
                            openSession(notification.hostId, notification.sessionId)
                        }
                    },
                    onDismiss = notificationCenter::dismiss,
                )
            }
        }

        // Now Running bar
        if (running != null) {
            val (face, session) = running
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .background(v2.card2, RoundedCornerShape(13.dp))
                    .clickable(role = Role.Button) { openSession(face.hostId, session.sessionId) }
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 13.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(v2.cyan, CircleShape),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        buildString {
                            append("运行中 · ${relativeTimeZh(session.updatedAtMs)}")
                            // Prototype Now-bar shows the active tool; derivable client-side
                            // only when the running session is the selected one (its timeline
                            // is loaded). No timestamp exists on entries, so no fake elapsed.
                            if (session.sessionId == face.state.sessionId) {
                                face.state.timeline.lastOrNull { entry ->
                                    entry.kind.name.startsWith("TOOL_") && !entry.final
                                }?.toolName?.let { append(" · ▸ $it") }
                            }
                            session.usage?.tokens?.let { tokens ->
                                append(" · ${compactTokenCount(tokens.totalTokens)} tok")
                            }
                        },
                        color = v2.cyan,
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    Text(
                        buildString {
                            if (multiHost) append("${face.label} · ")
                            append(session.title ?: "新会话")
                        },
                        color = v2.tx2,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("›", color = v2.tx3, fontSize = 15.sp)
            }
        }

        // Tab bar（原型：线形图标 + 文字 + 彩色徽章，P7 G1）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .background(v2.bg2)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            V2Tab.entries.forEach { option ->
                val on = tab == option.name
                val badge = when (option) {
                    V2Tab.APPROVALS -> approvalBadge
                    V2Tab.ARTIFACTS -> artifactBadge
                    V2Tab.NOTIFS -> unreadBadge
                    else -> 0
                }
                val color = if (on) v2.blue else v2.tx3
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .clickable(role = Role.Tab) { tab = option.name }
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("tab-${option.name.lowercase()}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box {
                        V2TabIcon(option, color)
                        if (badge > 0) {
                            if (option == V2Tab.APPROVALS) {
                                Text(
                                    if (badge > 99) "99+" else badge.toString(),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 7.dp, y = (-5).dp)
                                        .background(v2.amber, RoundedCornerShape(99.dp))
                                        .padding(horizontal = 4.dp, vertical = 0.dp),
                                    color = v2.bg,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 11.sp,
                                )
                            } else {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 3.dp, y = (-1).dp)
                                        .size(7.dp)
                                        .background(
                                            if (option == V2Tab.NOTIFS) v2.red else v2.blue,
                                            CircleShape,
                                        ),
                                )
                            }
                        }
                    }
                    Text(
                        when (option) {
                            V2Tab.SESSIONS -> "会话"
                            V2Tab.APPROVALS -> "审批"
                            V2Tab.ARTIFACTS -> "产出"
                            V2Tab.NOTIFS -> "通知"
                        },
                        color = color,
                        fontSize = 10.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** 原型 tab 栏线形图标：气泡 / 对勾圆 / 立方体 / 铃铛（统一 1.6dp 圆头笔触）。 */
@Composable
private fun V2TabIcon(tab: V2Tab, color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(19.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (tab) {
            V2Tab.SESSIONS -> {
                // 对话气泡：圆角矩形 + 左下尾巴。
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.08f, h * 0.12f),
                    size = Size(w * 0.84f, h * 0.62f),
                    cornerRadius = CornerRadius(w * 0.18f),
                    style = stroke,
                )
                val tail = Path().apply {
                    moveTo(w * 0.30f, h * 0.74f)
                    lineTo(w * 0.24f, h * 0.94f)
                    lineTo(w * 0.48f, h * 0.74f)
                }
                drawPath(tail, color, style = stroke)
            }
            V2Tab.APPROVALS -> {
                // 审批：对勾圆。
                drawCircle(color, radius = w * 0.40f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                val check = Path().apply {
                    moveTo(w * 0.32f, h * 0.52f)
                    lineTo(w * 0.45f, h * 0.65f)
                    lineTo(w * 0.70f, h * 0.36f)
                }
                drawPath(check, color, style = stroke)
            }
            V2Tab.ARTIFACTS -> {
                // 产出：等距立方体。
                val hex = Path().apply {
                    moveTo(w * 0.50f, h * 0.06f)
                    lineTo(w * 0.90f, h * 0.28f)
                    lineTo(w * 0.90f, h * 0.72f)
                    lineTo(w * 0.50f, h * 0.94f)
                    lineTo(w * 0.10f, h * 0.72f)
                    lineTo(w * 0.10f, h * 0.28f)
                    close()
                }
                drawPath(hex, color, style = stroke)
                val innerY = Path().apply {
                    moveTo(w * 0.10f, h * 0.28f)
                    lineTo(w * 0.50f, h * 0.50f)
                    lineTo(w * 0.90f, h * 0.28f)
                    moveTo(w * 0.50f, h * 0.50f)
                    lineTo(w * 0.50f, h * 0.94f)
                }
                drawPath(innerY, color, style = stroke)
            }
            V2Tab.NOTIFS -> {
                // 通知：铃铛 + 铃舌。
                val bell = Path().apply {
                    moveTo(w * 0.18f, h * 0.70f)
                    cubicTo(w * 0.18f, h * 0.30f, w * 0.30f, h * 0.14f, w * 0.50f, h * 0.14f)
                    cubicTo(w * 0.70f, h * 0.14f, w * 0.82f, h * 0.30f, w * 0.82f, h * 0.70f)
                    close()
                }
                drawPath(bell, color, style = stroke)
                drawLine(
                    color,
                    start = Offset(w * 0.10f, h * 0.70f),
                    end = Offset(w * 0.90f, h * 0.70f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
                val clapper = Path().apply {
                    moveTo(w * 0.42f, h * 0.82f)
                    quadraticBezierTo(w * 0.50f, h * 0.92f, w * 0.58f, h * 0.82f)
                }
                drawPath(clapper, color, style = stroke)
            }
        }
    }
}

/** Fleet-wide phase summary: every Host's carrier summarized without inventing one. */
private fun fleetPhase(
    hosts: List<V2HostFace>,
    v2: V2Palette,
): Pair<String, androidx.compose.ui.graphics.Color> = when {
    hosts.isEmpty() -> "未配对" to v2.tx3
    hosts.any { it.state.newPairingRequired } -> "需要重新配对" to v2.red
    hosts.all { it.state.isReady() } ->
        (if (hosts.size > 1) "已连接 ${hosts.size} 台" else "已连接") to v2.green
    hosts.all { it.state.isStaleView() } -> "离线" to v2.amber
    else -> {
        val online = hosts.count { it.state.isReady() }
        (if (online > 0) "$online/${hosts.size} 台在线" else hosts.first().state.phase.label) to v2.blue
    }
}

/** 原型头部 36dp 圆角图标按钮（卡片底 + 1px 描边）。 */
@Composable
private fun V2HeaderIconButton(
    onClick: () -> Unit,
    tag: String,
    description: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val v2 = LocalV2.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(v2.card)
            .border(1.dp, v2.line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 主机管理入口的服务器栈线形图标（与 tab 图标同笔触语言）。 */
@Composable
private fun V2HostGlyph(color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(17.dp)) {
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        val corner = CornerRadius(2.2.dp.toPx())
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.06f, h * 0.10f),
            size = Size(w * 0.88f, h * 0.34f),
            cornerRadius = corner,
            style = stroke,
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.06f, h * 0.56f),
            size = Size(w * 0.88f, h * 0.34f),
            cornerRadius = corner,
            style = stroke,
        )
        drawCircle(color, radius = 1.1.dp.toPx(), center = Offset(w * 0.24f, h * 0.27f))
        drawCircle(color, radius = 1.1.dp.toPx(), center = Offset(w * 0.24f, h * 0.73f))
    }
}

/**
 * 原型主机管理 sheet（P7 G2 底部 sheet 形态）：标题 +「N 台在线 · M 台已登记」
 * 聚合（只断言在线事实），每 Host 一卡——名称/端点详情/在线 pill/活动行
 * （在线报在跑数与在跑标题；离线诚实标注缓存与同步时刻），离线卡给真实
 * 「重新连接」。重启/停止/启动经守护进程管理通道执行（S-supervisor，
 * ADR-007）：链接随 sheet 打开建立、随 sheet 关闭释放，守护不在场时诚实
 * 降级为不可达说明。配对新主机是 Android 仪式，入口在 sheet 尾。
 */
@Composable
private fun V2HostSheet(
    hosts: List<V2HostFace>,
    onAddHost: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val v2 = LocalV2.current
    // The management link is demand-scoped to this sheet: ensured while it
    // shows (idempotent per recomposition of the fleet), released exactly
    // once when the sheet leaves composition. Release routes by hostId, so a
    // face rebuilt mid-flight still detaches the same link.
    val latestFaces = rememberUpdatedState(hosts)
    LaunchedEffect(hosts.map { it.hostId }) {
        hosts.forEach { face -> face.supervisor?.ensure?.invoke() }
    }
    DisposableEffect(Unit) {
        onDispose { latestFaces.value.forEach { face -> face.supervisor?.release?.invoke() } }
    }
    // While the operator is looking, a child that the supervisor reports as
    // running is enough to bring the projection back — restart/start/crash
    // recovery should not leave a live PID next to a manual reconnect chip.
    hosts.forEach { face ->
        key(face.hostId) {
            val runningPid = (face.supervisor?.link as? SupervisorLinkView.Online)
                ?.status
                ?.takeIf { it.state == "running" }
                ?.childPid
            val sessionReady = face.state.isReady()
            LaunchedEffect(runningPid, sessionReady) {
                if (runningPid != null && !sessionReady) {
                    face.callbacks.onReconnect()
                }
            }
        }
    }
    V2Sheet(
        title = "主机管理",
        subtitle = "${hosts.count { it.state.isReady() }} 台在线 · ${hosts.size} 台已登记",
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("关闭", color = v2.tx3, fontSize = 12.sp) }
        },
    ) {
        hosts.forEach { face ->
            val online = face.state.isReady()
            val running = face.state.sessions.filter { it.running }
            key(face.hostId) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(v2.card, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(face.label, color = v2.tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier
                            .background(
                                (if (online) v2.green else v2.tx3).copy(alpha = 0.12f),
                                RoundedCornerShape(99.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(5.dp)
                                .background(if (online) v2.green else v2.tx3, CircleShape),
                        )
                        Text(
                            if (online) "在线" else "离线",
                            color = if (online) v2.green else v2.tx3,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Text(
                    buildString {
                        append(face.detail)
                        append(" · ${face.state.sessions.size} 个会话")
                    },
                    color = v2.tx3,
                    fontSize = 9.5.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        // 原型主机卡活动行：在跑时给出正在做什么（真实会话标题）。
                        online && running.isNotEmpty() -> buildString {
                            append("${running.size} 个任务在跑")
                            running.firstOrNull()?.title?.let { append(" · 「$it」") }
                        }
                        online -> "空闲 · 无在跑会话"
                        else -> buildString {
                            append("离线 · 显示本机加密缓存")
                            face.state.offlineCacheSavedAtMs?.let {
                                append(" · 同步于 ${relativeTimeZh(it)}")
                            }
                        }
                    },
                    color = if (online && running.isNotEmpty()) v2.cyan else v2.tx3,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                face.supervisor?.let { supervisor -> V2SupervisorBlock(supervisor) }
                if (!online || face.onForget != null) {
                    var confirmForget by remember(face.hostId) { mutableStateOf(false) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!online) {
                            Text(
                                "重新连接",
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(v2.blue.copy(alpha = 0.14f))
                                    .clickable { face.callbacks.onReconnect() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                    .testTag("host-reconnect"),
                                color = v2.blue,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        face.onForget?.let { forget ->
                            Text(
                                if (confirmForget) "确认解除配对" else "解除配对",
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(v2.red.copy(alpha = 0.12f))
                                    .clickable {
                                        if (confirmForget) forget() else confirmForget = true
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                    .testTag("host-forget"),
                                color = v2.red,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            }
        }
        if (onAddHost != null) {
            Text(
                "配对新主机",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(v2.blue.copy(alpha = 0.12f))
                    .clickable(role = Role.Button) {
                        onDismiss()
                        onAddHost()
                    }
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .testTag("add-host"),
                color = v2.blue,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * 主机卡上的守护进程区（S-supervisor，ADR-007）：一行守护状态事实 + 按
 * 子进程状态给出的生命周期动作。链接中/不可达/无监督能力都如实陈述——
 * 绝不渲染一个点了也不会发生的按钮。
 */
@Composable
private fun V2SupervisorBlock(supervisor: V2SupervisorFace) {
    val v2 = LocalV2.current
    when (val link = supervisor.link) {
        SupervisorLinkView.Idle, SupervisorLinkView.Connecting -> Text(
            "守护通道连接中…",
            modifier = Modifier.testTag("sup-connecting"),
            color = v2.tx3,
            fontSize = 10.sp,
        )

        is SupervisorLinkView.Unreachable -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                "守护进程不在场 · ${link.detail}",
                modifier = Modifier.testTag("sup-unreachable"),
                color = v2.tx3,
                fontSize = 10.sp,
            )
            V2SupervisorChip("重试连接", v2.blue, "sup-retry") { supervisor.ensure() }
        }

        is SupervisorLinkView.Online -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val status = link.status
            Text(
                supervisorStatusLine(link),
                modifier = Modifier.testTag("sup-status"),
                color = when (status?.state) {
                    "running" -> v2.green
                    "stopping", "backoff" -> v2.amber
                    "down" -> if (status.downReason == "crash-loop") v2.red else v2.tx3
                    else -> v2.tx3
                },
                fontSize = 10.sp,
            )
            link.lastRefusal?.let { refusal ->
                Text(
                    refusal,
                    modifier = Modifier.testTag("sup-refusal"),
                    color = v2.red,
                    fontSize = 9.5.sp,
                )
            }
            when {
                link.pendingVerb != null -> Text(
                    "指令执行中…",
                    modifier = Modifier.testTag("sup-pending"),
                    color = v2.cyan,
                    fontSize = 10.sp,
                )

                !link.canManage -> Text(
                    "此设备的配对档案不含主机监督能力，仅可观察",
                    modifier = Modifier.testTag("sup-observe-only"),
                    color = v2.tx3,
                    fontSize = 9.5.sp,
                )

                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (status?.state) {
                        "running" -> {
                            V2SupervisorChip("重启 dsh", v2.blue, "sup-restart") { supervisor.onRestart() }
                            V2SupervisorChip("停止服务", v2.red, "sup-stop") { supervisor.onStop() }
                        }

                        "down" -> V2SupervisorChip("启动 dsh", v2.green, "sup-start") { supervisor.onStart() }

                        "backoff" -> {
                            V2SupervisorChip("立即重启", v2.blue, "sup-restart") { supervisor.onRestart() }
                            V2SupervisorChip("停止服务", v2.red, "sup-stop") { supervisor.onStop() }
                        }

                        // "stopping" and the pre-status moment offer no verb:
                        // the status line already tells the truth.
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun V2SupervisorChip(labelText: String, tint: androidx.compose.ui.graphics.Color, tag: String, onClick: () -> Unit) {
    Text(
        labelText,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag(tag),
        color = tint,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** 守护状态一行事实：状态 + 只在为真时陈述的细节（PID/退避/退出因由）。 */
private fun supervisorStatusLine(link: SupervisorLinkView.Online): String {
    val status = link.status ?: return "守护在线 · 读取状态…"
    return when (status.state) {
        "running" -> buildString {
            append("dsh 运行中")
            status.childPid?.let { append(" · PID $it") }
        }

        "stopping" -> "dsh 停止中…"

        "backoff" -> buildString {
            append("dsh 崩溃退避 · 连续 ${status.consecutiveCrashes} 次")
            status.nextRestartAtMs?.let { at ->
                val seconds = ((at - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                append(" · 约 ${seconds}s 后自动重启")
            }
        }

        "down" -> buildString {
            append("dsh 已停止")
            when (status.downReason) {
                "operator" -> append(" · 手动停止，可启动")
                "crash-loop" -> {
                    append(" · 连续崩溃后保持停止")
                    status.lastExitCode?.let { append("（code $it）") }
                    status.lastExitSignal?.let { append("（$it）") }
                }
                "never-started" -> append(" · 尚未启动")
            }
        }

        else -> "守护状态：${status.state}"
    }
}

