package dev.dshremote.gate0c.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshremote.gate0c.transport.AgentPresetProjection
import dev.dshremote.gate0c.transport.Gate0CState
import dev.dshremote.gate0c.transport.SessionDirectoryEntry
import dev.dshremote.gate0c.transport.agentPresetLabel
import dev.dshremote.gate0c.transport.hasCapabilities
import java.text.DateFormat
import java.util.Date

/** S-mode-select 新建会话门控（per Host）。 */
internal fun createAuthorized(state: Gate0CState): Boolean =
    state.isReady() && !state.isStaleView() &&
        hasCapabilities(state.grantedCapabilities, 68uL) &&
        state.pendingCommand == null

@Composable
internal fun V2SessionsPanel(
    hosts: List<V2HostFace>,
    onOpenSession: (hostId: String, sessionId: String) -> Unit,
    // S-mode-select：新建入口收拢到头部圆形 +（P7 H11/H12，owner 在 V2App）；
    // 面板只在空态复用同一动作。null = 当前没有可创建的 Host。
    onStartCreate: (() -> Unit)? = null,
    // S-multi-host: pairing a further Host is an Android-owned ceremony; with one
    // Host the chip is a direct add action, with several it lives in the host sheet.
    onAddHost: (() -> Unit)? = null,
) {
    val v2 = LocalV2.current
    val multiHost = hosts.size > 1

    Column(Modifier.fillMaxSize()) {
        // 每台离线主机一条诚实横幅（不写"全部离线"——逐台陈述事实）。
        hosts.filter { it.state.isStaleView() }.forEach { face ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(v2.amber.copy(alpha = 0.09f))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        if (multiHost) append("${face.label} · ")
                        append("离线 · 以下为只读缓存（STALE）")
                        face.state.offlineCacheSavedAtMs?.let { savedAt ->
                            append(" · 同步于 ")
                            append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(savedAt)))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    color = v2.amber,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                TextButton(onClick = face.callbacks.onReconnect) { Text("重新连接", color = v2.blue, fontSize = 12.sp) }
            }
        }

        val allSessions = hosts.flatMap { face -> face.state.sessions.map { face to it } }
        if (allSessions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 36.dp, vertical = 54.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("还没有会话", color = v2.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (onStartCreate != null) {
                        "新建一个空白会话，或在 Host 上的 DSH 里开始工作。"
                    } else {
                        "在 Host 上的 DSH 里开始工作后，会话会出现在这里。"
                    },
                    color = v2.tx2,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                if (onStartCreate != null) {
                    Button(
                        onClick = onStartCreate,
                        modifier = Modifier.testTag("create-session-empty"),
                    ) { Text("新建会话") }
                }
                Button(onClick = { hosts.forEach { it.callbacks.onReconnect() } }) { Text("刷新") }
            }
            return@Column
        }
        // S-project：项目分组与筛选的真实现。分组键 = Host 注册表标签，缺席时
        // 回退到行已持有的 workspace basename（真实事实，不编造项目身份）。
        var projectFilter by remember { mutableStateOf<String?>(null) }
        var hostFilter by remember { mutableStateOf<String?>(null) }
        fun groupKey(row: Pair<V2HostFace, SessionDirectoryEntry>): String =
            row.second.projectLabel ?: row.second.workspaceLabel ?: "未分组"
        val groups = allSessions.groupBy(::groupKey)
        if (projectFilter != null && projectFilter !in groups.keys) projectFilter = null
        if (hostFilter != null && hosts.none { it.hostId == hostFilter }) hostFilter = null
        val visible = allSessions.filter { row ->
            (projectFilter == null || groupKey(row) == projectFilter) &&
                (hostFilter == null || row.first.hostId == hostFilter)
        }
        var projectSheetOpen by remember { mutableStateOf(false) }
        var hostSheetOpen by remember { mutableStateOf(false) }
        if (projectSheetOpen) {
            V2Sheet(
                title = "按项目筛选",
                subtitle = "FILTER BY PROJECT",
                onDismiss = { projectSheetOpen = false },
            ) {
                V2ProjectFilterRow(
                    label = "全部项目",
                    detail = "${allSessions.size} 个会话",
                    selected = projectFilter == null,
                    onClick = { projectFilter = null; projectSheetOpen = false },
                )
                groups.forEach { (name, members) ->
                    V2ProjectFilterRow(
                        label = name,
                        detail = "${members.size} 个会话",
                        selected = projectFilter == name,
                        onClick = { projectFilter = name; projectSheetOpen = false },
                    )
                }
            }
        }
        if (hostSheetOpen) {
            V2Sheet(
                title = "按主机筛选",
                subtitle = "FILTER BY HOST",
                onDismiss = { hostSheetOpen = false },
            ) {
                V2ProjectFilterRow(
                    label = "全部主机",
                    detail = "${allSessions.size} 个会话",
                    selected = hostFilter == null,
                    onClick = { hostFilter = null; hostSheetOpen = false },
                )
                hosts.forEach { face ->
                    V2ProjectFilterRow(
                        label = face.label,
                        detail = buildString {
                            append(if (face.state.isReady()) "在线" else "离线")
                            append(" · ${face.state.sessions.size} 个会话")
                        },
                        selected = hostFilter == face.hostId,
                        onClick = { hostFilter = face.hostId; hostSheetOpen = false },
                    )
                }
                if (onAddHost != null) {
                    V2ProjectFilterRow(
                        label = "＋ 配对新主机",
                        detail = "PAIR",
                        selected = false,
                        onClick = {
                            hostSheetOpen = false
                            onAddHost()
                        },
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${projectFilter ?: "全部项目"} ▾",
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(v2.card)
                    .clickable { projectSheetOpen = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag("project-filter-chip"),
                color = if (projectFilter == null) v2.tx2 else v2.blue,
                fontSize = 10.sp,
                maxLines = 1,
                softWrap = false,
            )
            // S-multi-host：主机筛选的真实现（单主机时无筛选对象，筛选 chip 不渲染；
            // 但「配对新主机」在单主机时是一个直接动作 chip，不需要先开筛选表）。
            if (multiHost) {
                Text(
                    "${hosts.firstOrNull { it.hostId == hostFilter }?.label ?: "全部主机"} ▾",
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(v2.card)
                        .clickable { hostSheetOpen = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("host-filter-chip"),
                    color = if (hostFilter == null) v2.tx2 else v2.blue,
                    fontSize = 10.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            } else if (onAddHost != null) {
                Text(
                    "＋ 配对新主机",
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(v2.card)
                        .clickable(role = Role.Button) { onAddHost() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("add-host-panel"),
                    color = v2.tx3,
                    fontSize = 10.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            if (visible.isEmpty()) {
                item(key = "empty-filter") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 36.dp, vertical = 54.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("该筛选下没有会话", color = v2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("CHANGE FILTER", color = v2.tx3, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            val visibleGroups = visible.groupBy(::groupKey)
            visibleGroups.forEach { (name, members) ->
                if (visibleGroups.size > 1) {
                    item(key = "project-$name") {
                        V2ProjectHeader(
                            name = name,
                            members = members.map { it.second },
                            // 在跑/待审批只聚合在线事实；离线主机的缓存计数留在行级
                            // STALE 标记里，不上升为分组级断言。
                            liveMembers = members.filter { !it.first.state.isStaleView() }.map { it.second },
                        )
                    }
                }
                items(members, key = { "${it.first.hostId}/${it.second.sessionId}" }) { (face, session) ->
                    V2SessionRow(
                        session = session,
                        presets = face.state.agentPresets,
                        stale = face.state.isStaleView(),
                        hostLabel = if (multiHost) face.label else null,
                        onClick = { onOpenSession(face.hostId, session.sessionId) },
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .height(1.dp)
                            .background(v2.line),
                    )
                }
            }
        }
    }
}

/** 原型 pg-head：项目名 + 聚合计数（在跑/待审批/会话数）。在跑与待审批只统计
 *  在线事实（liveMembers）；会话总数含离线缓存行（成员资格是稳定事实）。 */
@Composable
private fun V2ProjectHeader(
    name: String,
    members: List<SessionDirectoryEntry>,
    liveMembers: List<SessionDirectoryEntry> = members,
) {
    val v2 = LocalV2.current
    val running = liveMembers.count { it.running }
    val approvals = liveMembers.sumOf { it.pendingApprovalCount }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = v2.tx,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.weight(1f))
        Text(
            buildString {
                if (running > 0) append("$running 在跑 · ")
                if (approvals > 0) append("$approvals 待审批 · ")
                append("${members.size} 个会话")
            },
            color = v2.tx3,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
internal fun V2ProjectFilterRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val v2 = LocalV2.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) v2.blue.copy(alpha = 0.12f) else v2.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (selected) v2.blue else v2.line),
        )
        Text(
            label,
            modifier = Modifier.padding(start = 9.dp),
            color = v2.tx,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.weight(1f))
        Text(
            detail,
            color = v2.tx3,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun V2SessionRow(
    session: SessionDirectoryEntry,
    presets: List<AgentPresetProjection>,
    stale: Boolean,
    hostLabel: String?,
    onClick: () -> Unit,
) {
    val v2 = LocalV2.current
    val attention = session.pendingInputCount > 0 || session.pendingApprovalCount > 0
    val dotColor = when {
        stale -> v2.tx3
        attention -> v2.amber
        session.running -> v2.cyan
        else -> v2.tx3
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 20.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(7.dp)
                .background(dotColor, CircleShape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = session.title ?: "新会话",
                color = v2.tx,
                fontSize = 14.5.sp,
                lineHeight = 19.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(if (stale) "STALE" else sessionStatusZh(session))
                    // S-vocab-ext: lineage fact, present only for sub-agent children.
                    if (session.isSubagentChild) append(" · 子代理")
                    // S-mode-select: the log-resolved preset when the Host projects one.
                    agentPresetLabel(presets, session.agentPreset)?.let { append(" · $it") }
                    append(" · ")
                    append(session.workspaceLabel ?: "workspace 不可用")
                    // S-multi-host: the owning Host is a first-class row fact.
                    hostLabel?.let { append(" · $it") }
                    append(" · ")
                    append(relativeTimeZh(session.updatedAtMs))
                    if (session.pendingApprovalCount > 0) append(" · ${session.pendingApprovalCount} 项待审批")
                    if (session.pendingInputCount > 0) append(" · ${session.pendingInputCount} 项待输入")
                    // S-usage: real provider-reported totals when the Host projects them;
                    // an absent unit adds no segment (absence is never rendered as zero).
                    session.usage?.tokens?.let { tokens ->
                        append(" · ${compactTokenCount(tokens.totalTokens)} tok")
                    }
                },
                color = if (attention && !stale) v2.amber else v2.tx3,
                fontSize = 10.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
