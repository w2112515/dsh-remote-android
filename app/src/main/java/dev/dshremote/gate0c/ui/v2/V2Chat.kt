package dev.dshremote.gate0c.ui.v2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshremote.gate0c.NewPairingConfirmationDialog
import dev.dshremote.gate0c.transport.BlobFetchView
import dev.dshremote.gate0c.transport.ConnectionPhase
import dev.dshremote.gate0c.transport.Gate0CState
import dev.dshremote.gate0c.transport.ImageAttachmentProjection
import dev.dshremote.gate0c.transport.PendingApprovalDecision
import dev.dshremote.gate0c.transport.PendingCommandOperation
import dev.dshremote.gate0c.transport.PendingCommandProgress
import dev.dshremote.gate0c.transport.TimelineEntry
import dev.dshremote.gate0c.transport.TimelineKind
import dev.dshremote.gate0c.transport.hasCapabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.DateFormat
import java.util.Date

private enum class ChatView { CHAT, TRAJ }
private enum class TrajFilter(val label: String) {
    ALL("全部"), MESSAGES("消息"), TOOLS("工具"), SUB("子 Agent"), INJECT("注入"), SYSTEM("系统"),
}

@Composable
internal fun V2ChatView(
    state: Gate0CState,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
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
    onSelectAgentPreset: (String) -> Unit,
    onSelectModel: (String, String, String?) -> Unit,
    onForkSession: (Long?) -> Unit,
    // S-policy: 撤销同类规则 / 设置会话预算（都经持久命令通道）。
    onRevokeRule: (String) -> Unit,
    onSetBudget: (Long) -> Unit,
    // S-blob: composer 图片入口与中断上传的显式恢复/撤销。
    onAttachImage: (String) -> Unit,
    onRemoveComposerImage: (String) -> Unit,
    onRemoveCommittedImage: (String) -> Unit,
    onResumeStagedUpload: () -> Unit,
    onAbandonStagedUpload: () -> Unit,
    // S-blob: 时间线图片经 blob 通道核验取回。
    fetchImage: suspend (String, ImageAttachmentProjection) -> BlobFetchView,
    voiceEnabled: Boolean = false,
    // S-multi-host: owning Host label shown in the chat sub-line when the
    // fleet has more than one member (null in a single-Host deployment).
    hostLabel: String? = null,
) {
    val v2 = LocalV2.current
    var view by rememberSaveable { mutableStateOf(ChatView.CHAT.name) }
    var trajFilter by rememberSaveable { mutableStateOf(TrajFilter.ALL.name) }
    var selectedToolId by rememberSaveable { mutableStateOf<String?>(null) }
    var replaying by rememberSaveable { mutableStateOf(false) }
    var exportCopied by remember { mutableStateOf(false) }
    var policySheet by rememberSaveable { mutableStateOf(false) }
    val selectedTool = state.timeline.find { it.id == selectedToolId }
    val voice = if (voiceEnabled) {
        rememberVoiceInput { text ->
            onDraftChanged(
                if (state.localDraft.isBlank()) text else state.localDraft.trimEnd() + " " + text,
            )
        }
    } else {
        null
    }
    if (selectedTool != null) {
        V2ToolDetail(entry = selectedTool, onBack = { selectedToolId = null })
        return
    }
    if (replaying && state.timeline.isNotEmpty()) {
        V2ReplayView(
            timeline = state.timeline,
            sessionId = state.sessionId,
            fetchImage = fetchImage,
            onExit = { replaying = false },
        )
        return
    }
    if (policySheet) {
        V2PolicySheet(
            state = state,
            onRevokeRule = onRevokeRule,
            onSetBudget = onSetBudget,
            onDismiss = { policySheet = false },
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(v2.bg)
            .statusBarsPadding()
            // Chat view early-returns before the V2App root Column, so it must
            // re-declare the uiautomator tag bridge for its own subtree.
            .semantics { testTagsAsResourceId = true },
    ) {
        // Header: back / title+sub / 对话|轨迹 seg
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹", color = v2.tx, fontSize = 22.sp) }
            Column(Modifier.weight(1f)) {
                Text(
                    state.sessionTitle ?: if (state.isReady()) "新会话" else "连接中",
                    color = v2.tx,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    chatSubLabel(state, hostLabel),
                    color = v2.tx3,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier
                    .background(v2.card, RoundedCornerShape(9.dp))
                    .padding(2.dp),
            ) {
                ChatView.entries.forEach { option ->
                    val on = view == option.name
                    Text(
                        text = if (option == ChatView.CHAT) "对话" else "轨迹",
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (on) v2.card2 else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { view = option.name }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        color = if (on) v2.tx else v2.tx3,
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            if (state.timeline.isNotEmpty()) {
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                Text(
                    if (exportCopied) "已复制" else "导出",
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .clickable {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(buildProjectionExport(state)))
                            exportCopied = true
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .testTag("export-projection"),
                    color = if (exportCopied) v2.green else v2.tx3,
                    fontSize = 11.sp,
                )
            }
        }

        if (exportCopied) {
            Text(
                "投影导出已复制 · 客户端序列化，非 append-only 审计日志",
                modifier = Modifier.padding(horizontal = 14.dp),
                color = v2.tx3,
                fontSize = 9.sp,
            )
        }

        if (state.isStaleView()) {
            Text(
                staleStripLabel(state),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .background(v2.amber.copy(alpha = 0.07f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                color = v2.amber,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(6.dp))
        }

        V2UsageStrip(
            usage = state.sessionUsage,
            budget = state.sessionBudget,
            ruleCount = state.approvalRules.size,
            onOpenPolicy = { policySheet = true },
        )
        V2SubagentStrip(state.sessionSubagent, state.sessionOrigin)

        if (view == ChatView.TRAJ.name) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Filters scroll; the replay action keeps its full one-line pill.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    TrajFilter.entries.forEach { filter ->
                        val on = trajFilter == filter.name
                        Text(
                            filter.label,
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(if (on) v2.blue.copy(alpha = 0.16f) else v2.card)
                                .clickable { trajFilter = filter.name }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            color = if (on) v2.blue else v2.tx2,
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                if (state.timeline.isNotEmpty()) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "▶ 回放",
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(v2.card)
                            .clickable { replaying = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("replay-open"),
                        color = v2.cyan,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            V2TrajList(
                timeline = state.timeline,
                filter = TrajFilter.valueOf(trajFilter),
                onToolSelected = { selectedToolId = it.id },
                // S-session-admin: 以事件为界的分叉（原型「从此处分叉」的真实现）。
                // 分叉作用于 Host 日志，源会话保持不变。
                forkVisible = hasCapabilities(state.grantedCapabilities, 68uL),
                forkEnabled = state.isReady() && !state.isStaleView() &&
                    state.pendingCommand == null && state.sessionId != null,
                onFork = { atSeq -> onForkSession(atSeq) },
                modifier = Modifier.weight(1f),
            )
        } else {
            V2ChatList(
                state = state,
                onToolSelected = { selectedToolId = it.id },
                onApprovalDecision = onApprovalDecision,
                onClearLocalCopy = onClearLocalCopy,
                onStartNewPairing = onStartNewPairing,
                onDraftChanged = onDraftChanged,
                onReadingPositionChanged = onReadingPositionChanged,
                fetchImage = fetchImage,
                modifier = Modifier.weight(1f),
            )
        }

        V2Composer(
            state = state,
            voice = voice,
            onDraftChanged = onDraftChanged,
            onAcquireControl = onAcquireControl,
            onSend = onSend,
            onStop = onStop,
            onReconcile = onReconcile,
            onProbe = onProbe,
            onReconnect = onReconnect,
            onSelectAgentPreset = onSelectAgentPreset,
            onSelectModel = onSelectModel,
            onAttachImage = onAttachImage,
            onRemoveComposerImage = onRemoveComposerImage,
            onRemoveCommittedImage = onRemoveCommittedImage,
            onResumeStagedUpload = onResumeStagedUpload,
            onAbandonStagedUpload = onAbandonStagedUpload,
        )
    }
}

private fun chatSubLabel(state: Gate0CState, hostLabel: String? = null): String {
    val status = when {
        state.isStaleView() -> "STALE 只读"
        state.approvals.isNotEmpty() -> "已暂停 · 待审批"
        state.sessionRunning == true -> "运行中"
        state.sessionRunning == false -> "空闲"
        else -> "状态待定"
    }
    return listOfNotNull(
        hostLabel ?: state.hostDisplayName ?: state.hostInstanceId ?: state.endpoint,
        status,
        state.cursor?.let { "cursor $it" },
    ).joinToString(" · ")
}

private fun staleStripLabel(state: Gate0CState): String = buildString {
    append("离线 · 以下为只读缓存（STALE） · 草稿保存在本机，不会自动发送")
    state.offlineCacheSavedAtMs?.let { savedAt ->
        append(" · 同步于 ")
        append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(savedAt)))
    }
}

/**
 * S-usage 统计条：Steps / Cache / tok / 上下文余量。每个分段只呈现 Host 真实
 * 投影的值；整个用量缺失时显式标注"未提供"，单元缺失的分段同理——缺失从不
 * 渲染为零。S-policy：预算存在时追加预算分段（用尽标红），展开面板尾部给
 * 「会话策略」入口（规则与预算管理）。
 */
@Composable
private fun V2UsageStrip(
    usage: dev.dshremote.gate0c.transport.SessionUsageProjection?,
    budget: dev.dshremote.gate0c.transport.SessionBudgetState? = null,
    ruleCount: Int = 0,
    onOpenPolicy: (() -> Unit)? = null,
) {
    val v2 = LocalV2.current
    val empty = usage == null || (usage.tokens == null && usage.pressure == null && usage.stats == null)
    // 原型 P7 C10：点按展开大数字网格；仅渲染 Host 真实投影的单元，缺失即缺席。
    var expanded by rememberSaveable { mutableStateOf(false) }
    val expandable = !empty || onOpenPolicy != null
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = expandable) { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (empty) {
                Text(
                    "用量统计未提供 · Host 未加载用量投影单元",
                    color = v2.tx3,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                )
            } else {
                val segments = buildList {
                    usage.stats?.let { add("Steps ${it.steps}") }
                    usage.tokens?.let { add("Cache ${compactTokenCount(it.cacheTokens)}") }
                    usage.tokens?.let { add("tok ${compactTokenCount(it.totalTokens)}") }
                    usage.pressure?.let { pressure ->
                        add(pressure.contextLeft?.let { "余量 ${compactTokenCount(it)}" } ?: "余量 未提供")
                    } ?: add("余量 未提供")
                }
                segments.forEach { segment ->
                    Text(
                        segment,
                        color = v2.tx3,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            // S-policy：预算是 Host 的持久事实——存在才渲染，用尽即标红。
            budget?.let {
                Text(
                    if (it.exhausted) "预算 已用尽" else "预算 ${compactTokenCount(it.maxTotalTokens)}",
                    color = if (it.exhausted) v2.red else v2.tx3,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (it.exhausted) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag("budget-segment"),
                )
            }
            Spacer(Modifier.weight(1f))
            if (expandable) Text(if (expanded) "▾" else "▸", color = v2.tx3, fontSize = 9.sp)
        }
        if (expanded && expandable) {
            val cells = buildList {
                if (usage != null) {
                    usage.stats?.let {
                        add("STEPS" to it.steps.toString())
                        add("ELAPSED" to formatElapsedZh(it.llmMs + it.toolMs))
                    }
                    usage.tokens?.let {
                        add("CACHE" to compactTokenCount(it.cacheTokens))
                        add("IN" to compactTokenCount(it.uncachedInputTokens))
                        add("OUT" to compactTokenCount(it.outputTokens))
                    }
                    usage.pressure?.contextLeft?.let { add("CTX LEFT" to compactTokenCount(it)) }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .background(v2.card, RoundedCornerShape(10.dp))
                    .padding(10.dp)
                    .testTag("usage-grid"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                cells.chunked(3).forEach { rowCells ->
                    Row(Modifier.fillMaxWidth()) {
                        rowCells.forEach { (label, value) ->
                            Column(Modifier.weight(1f)) {
                                Text(
                                    value,
                                    color = v2.tx,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                Text(
                                    label,
                                    color = v2.tx3,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.8.sp,
                                )
                            }
                        }
                        repeat(3 - rowCells.size) { Box(Modifier.weight(1f)) }
                    }
                }
                // S-policy：规则与预算的管理入口（sheet）。
                if (onOpenPolicy != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onOpenPolicy() }
                            .padding(vertical = 4.dp)
                            .testTag("policy-open"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buildString {
                                append("会话策略 · 同类规则 $ruleCount")
                                append(
                                    budget?.let {
                                        " · 预算 ${compactTokenCount(it.maxTotalTokens)}" +
                                            if (it.exhausted) "（已用尽）" else ""
                                    } ?: " · 无预算",
                                )
                            },
                            color = v2.tx2,
                            fontSize = 10.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text("›", color = v2.tx3, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** 累计耗时的紧凑呈现：74_000ms → "1m14s"，9_300ms → "9s"。 */
private fun formatElapsedZh(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m${seconds}s" else "${seconds}s"
}

/**
 * S-vocab-ext 子代理身份条：只在 Host 投影了子代理血统/身份/计时时呈现。
 * 每个分段都来自真实投影；整视图缺失时不渲染任何内容（普通会话无此条）。
 */
@Composable
private fun V2SubagentStrip(
    subagent: dev.dshremote.gate0c.transport.SubagentProjection?,
    origin: String?,
) {
    if (subagent == null && origin != "subagent") return
    val v2 = LocalV2.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val segments = buildList {
            add("子代理")
            subagent?.label?.let { add(it) }
            subagent?.modeZh?.let { add(it) }
            subagent?.settledMs?.let { add("累计 ${it / 1000}s") }
            if (subagent?.activeSinceMs != null) add("运行中")
        }
        segments.forEach { segment ->
            Text(
                segment,
                color = v2.blue,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}


@Composable
private fun V2ChatList(
    state: Gate0CState,
    onToolSelected: (TimelineEntry) -> Unit,
    onApprovalDecision: (String, PendingApprovalDecision) -> Unit,
    onClearLocalCopy: () -> Unit,
    onStartNewPairing: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onReadingPositionChanged: (String?, Int, Boolean) -> Unit,
    fetchImage: suspend (String, ImageAttachmentProjection) -> BlobFetchView,
    modifier: Modifier = Modifier,
) {
    val v2 = LocalV2.current
    val noticeCount = listOf(
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
    LaunchedEffect(state.approvals.firstOrNull()?.revision) {
        if (state.approvals.isNotEmpty()) {
            followTail = false
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            !listState.isScrollInProgress || lastVisible >= lastIndex - 1
        }.distinctUntilChanged().collect { atTail ->
            if (listState.isScrollInProgress) followTail = atTail
        }
    }
    LaunchedEffect(state.timeline.size, state.timeline.lastOrNull()?.text?.length) {
        if (followTail && state.timeline.isNotEmpty()) {
            listState.scrollToItem(attentionCount + noticeCount + state.timeline.lastIndex)
        }
    }
    LaunchedEffect(listState, state.timeline) {
        snapshotFlow {
            val first = listState.layoutInfo.visibleItemsInfo
                .firstOrNull {
                    it.key != "attention" && it.key != "history-truncated" &&
                        it.key.toString().startsWith("notice:").not()
                }
            Triple(listState.isScrollInProgress, first?.key?.toString(), (-1 * (first?.offset ?: 0)).coerceAtLeast(0))
        }.distinctUntilChanged().collect { (scrolling, anchor, offset) ->
            if (!scrolling) onReadingPositionChanged(anchor, offset, followTail)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .testTag("session-timeline")
            .semantics { testTagsAsResourceId = true },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "attention", contentType = "attention") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                V2AccessStrip(state, onClearLocalCopy, onStartNewPairing)
                state.approvals.forEach { approval ->
                    V2ApprovalCard(
                        approval = approval,
                        pendingCommandOperation = state.pendingCommand?.operation,
                        decisionAuthorized = hasCapabilities(state.grantedCapabilities, 16uL) && state.isReady(),
                        offline = state.isStaleView(),
                        onDecision = onApprovalDecision,
                    )
                }
            }
        }
        if (state.offlineCacheTruncated) {
            item(key = "notice:cache-truncated", contentType = "notice") {
                V2Notice("本地缓存有界，省略了较早或超大内容 · Host 历史未受影响")
            }
        }
        if (state.readingAnchorUnavailable) {
            item(key = "notice:anchor-unavailable", contentType = "notice") {
                V2Notice("之前的阅读位置不在此快照内，已显示最早可用条目")
            }
        }
        state.cacheWarning?.let { warning ->
            item(key = "notice:cache-warning", contentType = "notice") { V2Notice(warning) }
        }
        if (state.historyTruncated) {
            item(key = "history-truncated", contentType = "notice") {
                V2Notice("较早的历史不在此次同步的投影中")
            }
        }
        items(state.timeline, key = TimelineEntry::id, contentType = TimelineEntry::contentType) { entry ->
            V2MessageRow(entry, onToolSelected, state.sessionId, fetchImage)
        }
        if (state.timeline.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // S-mode-select: a known-blank session is NOT "still syncing" — it is a
                    // resolved idle empty log; say so honestly and point at the mode chip.
                    if (state.sessionBlank) {
                        V2ChatEmpty(onSuggestion = onDraftChanged)
                    } else {
                        Text("同步会话中", color = v2.tx, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text("还没有可用的投影事件。", color = v2.tx2, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun V2Notice(text: String) {
    val v2 = LocalV2.current
    Text(
        text,
        color = v2.tx2,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(v2.card, RoundedCornerShape(8.dp))
            .padding(10.dp),
    )
}

/**
 * 原型 chat-empty 的真实现（空白会话首屏）：鲸鱼线稿 +「探索未至之境」+
 * NOISE IK 通道徽标 + 建议卡。建议文案是客户端本地提示——不含原型样例里的
 * 虚构主机名；点击只填入草稿，发送仍由用户显式触发（I4：草稿永不自动发送）。
 */
@Composable
private fun V2ChatEmpty(onSuggestion: (String) -> Unit) {
    val v2 = LocalV2.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        V2Whale()
        Text(
            "探索未至之境",
            color = v2.tx,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Text(
            "INTO THE UNKNOWN",
            color = v2.tx3,
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.6.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "预览版 · NOISE IK 端到端加密通道",
            color = v2.blue,
            fontSize = 7.5.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.4.sp,
            modifier = Modifier
                .padding(top = 12.dp)
                .border(1.dp, v2.blue.copy(alpha = 0.4f), RoundedCornerShape(5.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Text(
            "会话已就绪，还没有消息。选择模式后发出第一条指令。",
            color = v2.tx2,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 14.dp),
        )
        Column(
            modifier = Modifier.padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "巡查" to "把当前跑着的任务汇报一下",
                "代码" to "看看 main 落后远端多少，整理成变更清单",
                "排障" to "查一下最近的定时任务有没有失败",
            ).forEach { (label, prompt) ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(v2.card)
                        .border(1.dp, v2.line, RoundedCornerShape(12.dp))
                        .clickable { onSuggestion(prompt) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Text(
                        label,
                        color = v2.tx3,
                        fontSize = 7.5.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.2.sp,
                    )
                    Text(
                        prompt,
                        color = v2.tx2,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

/** 原型鲸鱼线稿（140×90 viewBox 的五条路径），渐变描边取自 v2 色板。 */
@Composable
private fun V2Whale() {
    val v2 = LocalV2.current
    val brush = Brush.linearGradient(
        colors = listOf(v2.blue, v2.cyan),
        start = Offset.Zero,
        end = Offset(140f, 90f),
    )
    Canvas(
        modifier = Modifier
            .padding(bottom = 16.dp)
            .width(128.dp)
            .height(82.dp),
    ) {
        val scaleX = size.width / 140f
        val scaleY = size.height / 90f
        withTransform({ scale(scaleX, scaleY, pivot = Offset.Zero) }) {
            val stroke = Stroke(width = 2.2f, cap = StrokeCap.Round)
            val body = Path().apply {
                moveTo(14f, 52f)
                cubicTo(22f, 26f, 52f, 14f, 84f, 18f)
                cubicTo(112f, 21f, 128f, 36f, 130f, 50f)
                cubicTo(112f, 66f, 74f, 74f, 44f, 66f)
                cubicTo(30f, 62f, 17f, 58f, 14f, 52f)
                close()
            }
            drawPath(body, brush = brush, style = stroke)
            val tailUp = Path().apply {
                moveTo(14f, 52f)
                cubicTo(6f, 48f, 3f, 38f, 8f, 28f)
            }
            drawPath(tailUp, brush = brush, style = stroke)
            val tailDown = Path().apply {
                moveTo(14f, 52f)
                cubicTo(7f, 57f, 6f, 66f, 11f, 72f)
            }
            drawPath(tailDown, brush = brush, style = stroke)
            drawCircle(brush = brush, radius = 2.6f, center = Offset(108f, 40f))
            val fin = Path().apply {
                moveTo(62f, 66f)
                cubicTo(66f, 74f, 76f, 76f, 85f, 72f)
            }
            drawPath(fin, brush = brush, style = Stroke(width = 2f, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun V2AccessStrip(
    state: Gate0CState,
    onClearLocalCopy: () -> Unit,
    onStartNewPairing: () -> Unit,
) {
    val v2 = LocalV2.current
    val ready = state.isReady()
    val stale = state.isStaleView()
    val sessionControl = hasCapabilities(state.grantedCapabilities, 68uL)
    val approvalReviewer = hasCapabilities(state.grantedCapabilities, 19uL)
    // 原型 P7 C9：常态（在线且可驱动会话）不渲染横幅——控制事实已在 composer
    // 脚注陈述；横幅只保留异常态（只读/离线/需重配对/受限授权）。
    if (ready && !stale && !state.newPairingRequired && sessionControl) return
    val color = when {
        state.newPairingRequired -> v2.red
        (sessionControl || approvalReviewer) && !stale -> v2.green
        else -> v2.amber
    }
    var confirmNewPairing by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    if (confirmNewPairing) {
        NewPairingConfirmationDialog(
            onDismiss = { confirmNewPairing = false },
            onConfirm = {
                confirmNewPairing = false
                onStartNewPairing()
            },
        )
    }
    if (confirmClear) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除这份离线副本？") },
            text = { Text("将永久删除缓存的会话、时间线、草稿和阅读位置；设备身份、Host 配对和受保护的待结算命令不受影响。") },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("保留") } },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearLocalCopy()
                }) { Text("清除本地副本", color = v2.red) }
            },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                state.newPairingRequired -> "授权已结束 · 需要重新配对"
                state.phase == ConnectionPhase.INCOMPATIBLE -> "版本不兼容 · 需要更新"
                stale -> "离线 · 加密快照只读"
                hasCapabilities(state.grantedCapabilities, 351uL) -> "已认证 · 主机监管"
                hasCapabilities(state.grantedCapabilities, 95uL) -> "已认证 · 会话主管"
                hasCapabilities(state.grantedCapabilities, 72uL) -> "已认证 · 会话操作员"
                sessionControl -> "已认证 · 会话控制"
                approvalReviewer -> "已认证 · 审批员"
                else -> "已认证 · 只读"
            },
            modifier = Modifier.weight(1f),
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
        when {
            state.newPairingRequired -> TextButton(onClick = { confirmNewPairing = true }) {
                Text("重新配对", color = v2.blue, fontSize = 11.sp)
            }
            state.offlineSnapshot -> TextButton(onClick = { confirmClear = true }) {
                Text("清除副本", color = v2.blue, fontSize = 11.sp)
            }
            !ready -> Unit
        }
    }
}

@Composable
private fun V2MessageRow(
    entry: TimelineEntry,
    onToolSelected: (TimelineEntry) -> Unit,
    sessionId: String? = null,
    fetchImage: (suspend (String, ImageAttachmentProjection) -> BlobFetchView)? = null,
) {
    val v2 = LocalV2.current
    when (entry.kind) {
        TimelineKind.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.88f),
                color = v2.blue.copy(alpha = 0.16f),
                shape = RoundedCornerShape(13.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (entry.text.isNotEmpty()) {
                        Text(
                            entry.text,
                            color = v2.tx,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                    }
                    // S-blob: 引用随投影到达，字节经 blob 通道核验后渲染；
                    // 抓取不可用时如实标注，绝不渲染空白。
                    entry.attachments.forEach { attachment ->
                        if (fetchImage != null) {
                            V2FetchedImage(
                                sessionId = sessionId,
                                attachment = attachment,
                                fetchImage = fetchImage,
                            )
                        } else {
                            Text(
                                "图片附件 · ${attachment.mediaType} · ${attachment.bytes} B（此视图无抓取通道）",
                                color = v2.tx3,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
        TimelineKind.ASSISTANT -> Column(Modifier.fillMaxWidth()) {
            Text(
                entry.text.ifEmpty { "等待输出…" },
                color = v2.tx,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
            if (!entry.final) {
                Spacer(Modifier.height(5.dp))
                Text(
                    "部分输出 · Host 尚未报告完成",
                    color = v2.tx3,
                    fontSize = 10.sp,
                )
            }
        }
        TimelineKind.TOOL_GENERIC, TimelineKind.TOOL_TERMINAL,
        TimelineKind.TOOL_DIFF, TimelineKind.TOOL_UNSUPPORTED,
        -> {
            val unsupported = entry.kind == TimelineKind.TOOL_UNSUPPORTED
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(v2.card, RoundedCornerShape(11.dp))
                    .clickable { onToolSelected(entry) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (entry.kind) {
                            TimelineKind.TOOL_TERMINAL -> ">_"
                            TimelineKind.TOOL_DIFF -> "±"
                            TimelineKind.TOOL_UNSUPPORTED -> "?"
                            else -> "◆"
                        },
                        color = if (unsupported) v2.red else v2.blue,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                    Text(
                        entry.kind.label,
                        color = v2.tx3,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    )
                }
                Text(entry.text, color = v2.tx, fontSize = 12.sp, lineHeight = 17.sp)
                entry.boundedContent?.let { content ->
                    Text(
                        content,
                        color = v2.tx2,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.truncated) {
                    Text("内容已被 Host 截断", color = v2.amber, fontSize = 9.sp, letterSpacing = 0.6.sp)
                }
            }
        }
        TimelineKind.INJECT -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(v2.amber.copy(alpha = 0.07f), RoundedCornerShape(11.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                buildString {
                    append("上下文注入")
                    entry.source?.plugin?.let { append(" · $it") }
                    entry.source?.form?.let { append(" · $it") }
                },
                color = v2.amber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            Text(entry.text, color = v2.tx2, fontSize = 12.sp, lineHeight = 17.sp)
            Text(
                "由 Host 插件注入的模型上下文 · 不是你的输入",
                color = v2.tx3,
                fontSize = 9.sp,
            )
        }
        TimelineKind.SUBAGENT -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(v2.card, RoundedCornerShape(11.dp))
                .clickable { onToolSelected(entry) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "⎇",
                    color = v2.blue,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(
                    "子代理 · ${entry.text}",
                    color = v2.tx,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            entry.boundedContent?.let { content ->
                Text(
                    content,
                    color = v2.tx2,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!entry.final) {
                Text("运行中", color = v2.cyan, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            if (entry.truncated) {
                Text("内容已被 Host 截断", color = v2.amber, fontSize = 9.sp, letterSpacing = 0.6.sp)
            }
        }
        TimelineKind.SESSION -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                entry.text,
                color = v2.tx3,
                fontSize = 10.sp,
                modifier = Modifier
                    .background(v2.card, RoundedCornerShape(99.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        TimelineKind.UNSUPPORTED -> V2Notice(entry.text)
    }
}

@Composable
private fun V2TrajList(
    timeline: List<TimelineEntry>,
    filter: TrajFilter,
    onToolSelected: (TimelineEntry) -> Unit,
    forkVisible: Boolean,
    forkEnabled: Boolean,
    onFork: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val v2 = LocalV2.current
    // S-session-admin: 分叉确认。cut 锚定在该事件处或之后首个完成的 turn；
    // 源日志永不被修改（lease-free 的 Host 语义）。
    var forkCandidate by remember { mutableStateOf<TimelineEntry?>(null) }
    forkCandidate?.let { candidate ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { forkCandidate = null },
            containerColor = v2.bg2,
            title = { Text("从此处分叉", color = v2.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "以事件 seq ${candidate.sourceSequence} 为界创建分支会话：该处或之后首个完成的 turn " +
                        "之前的轨迹与上下文将带入新会话，原会话保持不变。",
                    color = v2.tx2,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        forkCandidate = null
                        onFork(candidate.sourceSequence)
                    },
                    modifier = Modifier.testTag("fork-confirm"),
                ) { Text("创建分叉", color = v2.blue, fontSize = 12.sp) }
            },
            dismissButton = {
                TextButton(onClick = { forkCandidate = null }) { Text("取消", color = v2.tx3, fontSize = 12.sp) }
            },
        )
    }
    val rows = timeline.filter { entry ->
        when (filter) {
            TrajFilter.ALL -> true
            TrajFilter.MESSAGES -> entry.kind == TimelineKind.USER || entry.kind == TimelineKind.ASSISTANT
            TrajFilter.TOOLS -> entry.kind.name.startsWith("TOOL_")
            TrajFilter.SUB -> entry.kind == TimelineKind.SUBAGENT
            TrajFilter.INJECT -> entry.kind == TimelineKind.INJECT
            TrajFilter.SYSTEM -> entry.kind == TimelineKind.SESSION || entry.kind == TimelineKind.UNSUPPORTED
        }
    }.asReversed()
    if (rows.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 54.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("∅", color = v2.tx3, fontSize = 22.sp)
            Text("暂无该类型事件", color = v2.tx2, fontSize = 12.sp)
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(rows, key = TimelineEntry::id) { entry ->
            val typeColor = when {
                entry.kind == TimelineKind.USER -> v2.green
                entry.kind == TimelineKind.ASSISTANT -> v2.blue
                entry.kind.name.startsWith("TOOL_") -> v2.cyan
                entry.kind == TimelineKind.SUBAGENT -> v2.blue
                entry.kind == TimelineKind.INJECT -> v2.amber
                else -> v2.tx3
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(9.dp))
                    .background(v2.card)
                    .clickable(enabled = entry.kind.name.startsWith("TOOL_") || entry.kind == TimelineKind.SUBAGENT) { onToolSelected(entry) },
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 原型 P7 C7：行首类型色条。
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(3.dp)
                        .background(typeColor),
                )
                Text(
                    when {
                        entry.kind == TimelineKind.USER -> "YOU"
                        entry.kind == TimelineKind.ASSISTANT -> "DSH"
                        entry.kind.name.startsWith("TOOL_") -> "TOOL"
                        entry.kind == TimelineKind.SUBAGENT -> "SUB"
                        entry.kind == TimelineKind.INJECT -> "INJECT"
                        else -> "SYS"
                    },
                    modifier = Modifier.padding(vertical = 9.dp),
                    color = typeColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                )
                Text(
                    entry.text,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 9.dp),
                    color = v2.tx2,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (forkVisible) {
                    Text(
                        "⑂",
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = forkEnabled) { forkCandidate = entry }
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                            .testTag("fork-at-${entry.sourceSequence}"),
                        color = if (forkEnabled) v2.blue else v2.tx3,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    "seq ${entry.sourceSequence}",
                    modifier = Modifier.padding(end = 11.dp),
                    color = v2.tx3,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun V2ReplayView(
    timeline: List<TimelineEntry>,
    sessionId: String?,
    fetchImage: suspend (String, ImageAttachmentProjection) -> BlobFetchView,
    onExit: () -> Unit,
) {
    val v2 = LocalV2.current
    var index by rememberSaveable { mutableIntStateOf(0) }
    var playing by rememberSaveable { mutableStateOf(false) }
    val last = timeline.lastIndex
    val safeIndex = index.coerceIn(0, last)
    LaunchedEffect(playing, safeIndex) {
        if (playing) {
            if (safeIndex >= last) {
                playing = false
            } else {
                delay(900)
                index = safeIndex + 1
            }
        }
    }
    val entry = timeline[safeIndex]
    Column(
        Modifier
            .fillMaxSize()
            .background(v2.bg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onExit) { Text("‹", color = v2.tx, fontSize = 22.sp) }
            Column(Modifier.weight(1f)) {
                Text("回放", color = v2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${safeIndex + 1} / ${timeline.size} · seq ${entry.sourceSequence} · ${entry.kind.label}",
                    color = v2.tx3,
                    fontSize = 10.sp,
                )
            }
        }
        androidx.compose.material3.LinearProgressIndicator(
            progress = { (safeIndex + 1).toFloat() / timeline.size },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            color = v2.cyan,
            trackColor = v2.card,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            V2MessageRow(entry, {}, sessionId, fetchImage)
        }
        Text(
            "本地回放 · 仅重放本机已收到的投影事件，不产生任何 Host 请求",
            modifier = Modifier.padding(horizontal = 14.dp),
            color = v2.tx3,
            fontSize = 9.sp,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = { index = safeIndex - 1 },
                enabled = safeIndex > 0,
                modifier = Modifier.weight(1f),
            ) { Text("上一条", fontSize = 12.sp) }
            Button(
                onClick = {
                    if (safeIndex >= last) index = 0
                    playing = !playing
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("replay-toggle"),
                colors = ButtonDefaults.buttonColors(containerColor = v2.blue),
            ) { Text(if (playing) "暂停" else "播放", fontSize = 12.sp) }
            OutlinedButton(
                onClick = { index = safeIndex + 1 },
                enabled = safeIndex < last,
                modifier = Modifier.weight(1f),
            ) { Text("下一条", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun V2Composer(
    state: Gate0CState,
    voice: VoiceInputController?,
    onDraftChanged: (String) -> Unit,
    onAcquireControl: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onReconcile: () -> Unit,
    onProbe: () -> Unit,
    onReconnect: () -> Unit,
    onSelectAgentPreset: (String) -> Unit,
    onSelectModel: (String, String, String?) -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveComposerImage: (String) -> Unit,
    onRemoveCommittedImage: (String) -> Unit,
    onResumeStagedUpload: () -> Unit,
    onAbandonStagedUpload: () -> Unit,
) {
    val v2 = LocalV2.current
    val ready = state.isReady()
    val stale = state.isStaleView()
    val listening = voice?.listening?.collectAsStateWithLifecycle()?.value ?: false
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
            actionLabel = "修复受阻"
            actionEnabled = false
            action = {}
        }
        pending != null -> {
            actionLabel = when {
                pending.progress == PendingCommandProgress.UNKNOWN -> "对账"
                pending.operation == PendingCommandOperation.STOP -> "查看 Stop"
                pending.operation == PendingCommandOperation.DECIDE_APPROVAL -> "查看审批"
                else -> "查看状态"
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
                    // S-policy: 撤销与审批同信任域；预算与发送/控制同域。
                    PendingCommandOperation.REVOKE_APPROVAL_RULE -> 16uL
                    PendingCommandOperation.SET_SESSION_BUDGET -> 68uL
                },
            )
            action = onReconcile
        }
        stale -> {
            actionLabel = "重新连接"
            actionEnabled = true
            action = onReconnect
        }
        !writeAuthorized -> {
            actionLabel = "验证锁定"
            actionEnabled = ready
            action = onProbe
        }
        !leaseUsable -> {
            actionLabel = "取得控制"
            actionEnabled = ready
            action = onAcquireControl
        }
        // S-policy: exhausted 是 Host 自己断言的闸门状态（policy_changed /
        // BUDGET_EXHAUSTED 拒绝），据此预先关闭发送，避免必然被拒的往返。
        state.sessionBudget?.exhausted == true -> {
            actionLabel = "预算已用尽"
            actionEnabled = false
            action = {}
        }
        else -> {
            actionLabel = "发送"
            actionEnabled = ready && state.localDraft.isNotBlank()
            action = onSend
        }
    }
    val settlement = when {
        state.commandRecoveryBlocked ->
            "受保护命令状态不可读 · 已阻止发送以避免重复效果"
        pending?.progress == PendingCommandProgress.PREPARED ->
            "已在发送前安全记录 · 重连/对账使用同一 command id"
        pending?.progress == PendingCommandProgress.RECEIVED ->
            "Host 已收到该命令 · 等待持久的 COMMITTED 或明确的 REJECTED"
        pending?.progress == PendingCommandProgress.REQUESTED ->
            "Stop 已到达精确 turn owner · 等待持久 user-abort 与 Agent 静默"
        pending?.progress == PendingCommandProgress.UNKNOWN ->
            when (pending.operation) {
                PendingCommandOperation.STOP -> "Stop 结果未知 · 用同一 command id 对账，绝不指向更新的 turn"
                PendingCommandOperation.DECIDE_APPROVAL -> "审批结果未知 · 用同一 command id 对账，绝不决定更新的 revision"
                PendingCommandOperation.CREATE_SESSION -> "创建结果未知 · 用同一 command id 对账，同一 id 收敛到同一会话"
                PendingCommandOperation.SELECT_AGENT_PRESET -> "模式选择结果未知 · 用同一 command id 对账，绝不重复切换"
                PendingCommandOperation.SELECT_MODEL -> "模型选择结果未知 · 用同一 command id 对账，绝不重复切换"
                PendingCommandOperation.FORK_SESSION -> "分叉结果未知 · 用同一 command id 对账，同一 id 收敛到同一子会话"
                PendingCommandOperation.REVOKE_APPROVAL_RULE -> "撤销结果未知 · 用同一 command id 对账，重放收敛到同一规则"
                PendingCommandOperation.SET_SESSION_BUDGET -> "预算结果未知 · 用同一 command id 对账，同一 id 收敛到同一上限"
                else -> "结果未知 · 用同一 command id 对账，不要创建替代命令"
            }
        stale -> "草稿加密保存在本机 · 恢复连接后再取得控制或发送"
        !writeAuthorized -> "只读授权 · 在 Host 上选择会话控制 profile 后才能发送"
        !leaseUsable -> "发送前需取得会话控制 · 租约过期即失败关闭"
        state.sessionBudget?.exhausted == true -> "会话预算已用尽 · 在会话策略中提高上限后才能继续发送"
        else -> "控制 epoch ${state.controlLease.epoch} · 发送先于传输在本地持久化"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("command-settlement")
            .semantics {
                testTagsAsResourceId = true
                stateDescription = settlement
            },
        color = v2.bg2,
        border = BorderStroke(1.dp, v2.line),
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // 原型 P7 C8：活动 turn 行只留事实文案；停止动作移到 composer 右侧红色圆钮。
            if (stopAuthorized && state.sessionRunning == true && !stale) {
                val stopPending = pending?.operation == PendingCommandOperation.STOP
                Text(
                    if (stopPending) "正在停止 turn ${pending.expectedActivityRevision}" else
                        "活动 turn ${state.activityRevision ?: "待定"}",
                    modifier = Modifier.fillMaxWidth(),
                    color = v2.tx2,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // S-blob：中断上传的显式恢复行（先于一切新上传）。
            state.stagedUpload?.let { staged ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(v2.amber.copy(alpha = 0.07f), RoundedCornerShape(9.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "上次上传未完成${staged.displayName?.let { " · $it" } ?: ""} · 续传后随下一条消息发送",
                        modifier = Modifier.weight(1f),
                        color = v2.amber,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                    Text(
                        "续传",
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .clickable(onClick = onResumeStagedUpload)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("blob-upload-resume"),
                        color = v2.blue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "放弃",
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .clickable(onClick = onAbandonStagedUpload)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("blob-upload-abandon"),
                        color = v2.red,
                        fontSize = 11.sp,
                    )
                }
            }
            // S-blob：发送前的上传进度（含续传）。
            state.attachmentSend?.let { progress ->
                Text(
                    if (progress.resuming) {
                        "正在续传未完成的上传…"
                    } else {
                        "正在经 blob 通道上传图片 ${progress.completed}/${progress.total}…"
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("attachment-send-progress"),
                    color = v2.tx3,
                    fontSize = 10.sp,
                )
            }
            // S-blob：已提交附件（无本地预览——字节已随提交清除，如实只留引用）。
            state.committedAttachments.forEach { committed ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(v2.card, RoundedCornerShape(9.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已上传 · ${committed.displayName ?: committed.attachmentId.take(19) + "…"}",
                        modifier = Modifier.weight(1f),
                        color = v2.tx2,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "✕",
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onRemoveCommittedImage(committed.attachmentId) }
                            .padding(4.dp),
                        color = v2.tx3,
                        fontSize = 11.sp,
                    )
                }
            }
            // S-blob：本地暂存缩略图（字节尚未过载体，预览直读内容 Uri）。
            if (state.composerImages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag("composer-images"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.composerImages.forEach { image ->
                        Box {
                            val thumbnail = rememberUriBitmap(image.previewUri)
                            if (thumbnail.bitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = thumbnail.bitmap!!,
                                    contentDescription = image.displayName ?: "待发送图片",
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(9.dp)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .background(v2.card, RoundedCornerShape(9.dp))
                                        .semantics {
                                            contentDescription = thumbnail.failure ?: "缩略图加载中"
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("🖻", color = v2.tx3, fontSize = 16.sp)
                                }
                            }
                            Text(
                                "✕",
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 5.dp, y = (-5).dp)
                                    .clip(CircleShape)
                                    .background(v2.bg)
                                    .clickable { onRemoveComposerImage(image.key) }
                                    .padding(3.dp)
                                    .testTag("composer-image-remove"),
                                color = v2.tx2,
                                fontSize = 9.sp,
                            )
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // S-blob：原型 ＋ 附件位的真实现。仅在 hello 宣告了部署附件界限时
                // 出现（缺界 = 不接受附件 = 入口隐藏，绝不带 DEMO 标记）。
                if (state.attachmentLimits != null) {
                    V2AttachmentButton(
                        enabled = ready && !stale && writeAuthorized && pending == null && state.attachmentSend == null,
                        onAttachImage = onAttachImage,
                    )
                }
                OutlinedTextField(
                    value = state.localDraft,
                    onValueChange = onDraftChanged,
                    enabled = pending?.operation != PendingCommandOperation.SEND_INPUT,
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 4,
                    placeholder = {
                        Text(
                            when {
                                voice != null && listening -> "正在聆听…"
                                stale -> "离线 · 可留草稿，恢复后由你手动发送"
                                !writeAuthorized -> "只读 · 未获得该会话的控制授权"
                                pending != null -> "命令结算中，输入已锁定"
                                voice != null -> "发指令，或点麦克风说话…"
                                else -> "发指令…"
                            },
                            fontSize = 13.sp,
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = v2.blue,
                        unfocusedBorderColor = v2.line,
                    ),
                )
                if (voice != null) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { granted -> if (granted) voice.start() }
                    Text(
                        "🎙",
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (listening) v2.red.copy(alpha = 0.18f) else v2.card)
                            .clickable {
                                if (listening) {
                                    voice.stop()
                                } else if (ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO,
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    voice.start()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                            .padding(9.dp),
                        color = if (listening) v2.red else v2.tx2,
                        fontSize = 15.sp,
                    )
                }
                // 原型 P7 C8：运行态在 composer 右侧给红色圆形停止钮（■），
                // 与发送并存——发送在运行中仍可排队，停止只作用于精确 turn。
                if (stopAuthorized && state.sessionRunning == true && !stale) {
                    val stopEnabled = ready && leaseUsable && pending == null &&
                        state.activityRevision?.let { it > 0 } == true
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (stopEnabled) v2.red.copy(alpha = 0.16f) else v2.card)
                            .border(1.dp, v2.red.copy(alpha = if (stopEnabled) 0.55f else 0.25f), CircleShape)
                            .clickable(enabled = stopEnabled, role = Role.Button, onClick = onStop)
                            .testTag("stop-active-turn"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(11.dp)
                                .background(
                                    if (stopEnabled) v2.red else v2.tx3,
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                }
                Button(
                    onClick = action,
                    enabled = actionEnabled,
                    modifier = Modifier.testTag("command-action"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = v2.blue),
                ) {
                    Text(actionLabel, fontSize = 13.sp)
                }
            }
            // c-meta 行（原型第二行）：模型 chip 左置 + 模式 chip（STD MODE 位）
            // 右置——原型 c-hint 是 margin-left:auto 的右对齐位。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // S-session-admin：原型 model-chip 的真实现（会话中可切换）。
                V2ModelChip(state = state, onSelectModel = onSelectModel)
                Spacer(Modifier.weight(1f))
                // S-mode-select：原型 STD MODE 位的真实实现（空白会话可切换，开始后锁定）。
                V2AgentPresetChip(state = state, onSelectAgentPreset = onSelectAgentPreset)
            }
            Text(
                settlement,
                color = if (pending?.progress == PendingCommandProgress.UNKNOWN) v2.amber else v2.tx3,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            state.commandWarning?.let { warning ->
                Text(warning, color = v2.amber, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
    }
}

/**
 * 原型 ＋ 附件位（S-blob 真实现）：相册（SAF OpenDocument，授权持久化）与拍照
 * （TakePicture 经 FileProvider 暂存）。部分 OEM（实测 vivo）把相册读取挂在自有
 * 运行权限上——即便经系统选择器授权，MediaProvider 也拒绝打开流（openInputStream
 * 恒 null），因此选取前先按 API 请求 READ_MEDIA_IMAGES / 34+ 半授权，拒绝时如实
 * 弹出去设置说明。选择只暂存到 composer；字节在发送时经 blob 通道提交，任何失败
 * 都如实留在 composer 里。
 */
@Composable
private fun V2AttachmentButton(
    enabled: Boolean,
    onAttachImage: (String) -> Unit,
) {
    val v2 = LocalV2.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var chooserOpen by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var galleryDenied by remember { mutableStateOf(false) }
    fun galleryReadGranted(): Boolean {
        val images = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_IMAGES,
        ) == PackageManager.PERMISSION_GRANTED
        // 34+ 的“仅所选照片”半授权同样让 SAF 文档可读。
        val partial = android.os.Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED
        return when {
            android.os.Build.VERSION.SDK_INT >= 34 -> images || partial
            android.os.Build.VERSION.SDK_INT >= 33 -> images
            else -> true // SAF 文档授权在旧版本自足
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // SAF 文档授权持久化，进程重启后发送/预览仍可读取来源。
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onAttachImage(uri.toString())
        }
    }
    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted || galleryReadGranted()) {
            galleryLauncher.launch(arrayOf("image/*"))
        } else {
            galleryDenied = true
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { captured ->
        val uri = cameraUri
        if (captured && uri != null) onAttachImage(uri.toString())
    }
    if (chooserOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { chooserOpen = false },
            containerColor = v2.bg2,
            title = { Text("添加图片", color = v2.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "从相册选择",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .clickable {
                                chooserOpen = false
                                if (galleryReadGranted()) {
                                    galleryLauncher.launch(arrayOf("image/*"))
                                } else {
                                    galleryPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                            .testTag("attachment-pick-gallery"),
                        color = v2.tx,
                        fontSize = 13.sp,
                    )
                    Text(
                        "拍照",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .clickable {
                                chooserOpen = false
                                val file = java.io.File(
                                    context.cacheDir,
                                    "blob-camera/capture-${java.util.UUID.randomUUID()}.jpg",
                                ).apply { parentFile?.mkdirs() }
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "dev.dshremote.gate0c.blobcamera",
                                    file,
                                )
                                cameraUri = uri
                                cameraLauncher.launch(uri)
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                            .testTag("attachment-pick-camera"),
                        color = v2.tx,
                        fontSize = 13.sp,
                    )
                    Text(
                        "图片在发送时经 blob 通道上传；发送前只保留在本机。",
                        color = v2.tx3,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { chooserOpen = false }) { Text("取消", color = v2.blue, fontSize = 12.sp) }
            },
        )
    }
    if (galleryDenied) {
        // 相册读取被拒（含 OEM 权限管理静默拒绝）：如实说明并给出去向，绝不装作已选。
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { galleryDenied = false },
            containerColor = v2.bg2,
            title = { Text("无法读取相册", color = v2.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "系统拒绝了相册读取权限。前往系统设置允许“照片和视频”后重试；也可以改用拍照。",
                    color = v2.tx2,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    galleryDenied = false
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }
                }) { Text("去设置", color = v2.blue, fontSize = 12.sp) }
            },
            dismissButton = {
                TextButton(onClick = { galleryDenied = false }) { Text("取消", color = v2.tx2, fontSize = 12.sp) }
            },
        )
    }
    Text(
        "＋",
        modifier = Modifier
            .clip(CircleShape)
            .background(if (enabled) v2.card else v2.card.copy(alpha = 0.5f))
            .clickable(enabled = enabled) { chooserOpen = true }
            .padding(9.dp)
            .testTag("attachment-add"),
        color = if (enabled) v2.tx2 else v2.tx3,
        fontSize = 15.sp,
    )
}
