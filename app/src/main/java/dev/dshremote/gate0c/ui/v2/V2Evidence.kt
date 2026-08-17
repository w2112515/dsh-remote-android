package dev.dshremote.gate0c.ui.v2

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshremote.gate0c.transport.TimelineEntry
import dev.dshremote.gate0c.transport.TimelineKind

/**
 * US-08 v2 evidence detail. Renders exactly what the Host projection carried —
 * bounded content, truncation and absence disclosures — and adds only
 * client-local behavior (selection, copy). No evidence is invented.
 */
@Composable
internal fun V2ToolDetail(entry: TimelineEntry, onBack: () -> Unit) {
    val v2 = LocalV2.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
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
            TextButton(onClick = onBack) { Text("‹", color = v2.tx, fontSize = 22.sp) }
            Column(Modifier.weight(1f)) {
                Text(
                    entry.text,
                    color = v2.tx,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                Text(
                    listOfNotNull(
                        when (entry.kind) {
                            TimelineKind.TOOL_TERMINAL -> "终端证据"
                            TimelineKind.TOOL_DIFF -> "DIFF 证据"
                            TimelineKind.TOOL_GENERIC -> "工具证据"
                            TimelineKind.TOOL_UNSUPPORTED -> "不支持的工具呈现"
                            else -> "投影证据"
                        },
                        entry.toolName,
                        entry.callId,
                    ).joinToString(" · "),
                    color = v2.tx3,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
            if (entry.boundedContent != null) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(entry.boundedContent))
                    copied = true
                }) {
                    Text(if (copied) "已复制" else "复制", color = v2.blue, fontSize = 12.sp)
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (entry.truncated) {
                item(key = "truncated") {
                    V2EvidenceNotice(
                        title = "内容已被 Host 截断",
                        detail = "这份投影不完整。未呈现的证据不可用，本机不会声称拥有它。",
                        color = v2.amber,
                    )
                }
            }
            item(key = "content") {
                val content = entry.boundedContent
                if (content == null) {
                    V2EvidenceNotice(
                        title = "无有界详情",
                        detail = "Host 呈现方没有为此工具提供有界详情。",
                        color = v2.tx3,
                    )
                } else {
                    SelectionContainer {
                        when (entry.kind) {
                            TimelineKind.TOOL_TERMINAL -> V2TerminalBlock(content)
                            TimelineKind.TOOL_DIFF -> V2DiffBlock(content)
                            else -> V2MarkdownBlock(content)
                        }
                    }
                }
            }
            if (entry.kind == TimelineKind.TOOL_UNSUPPORTED) {
                item(key = "unsupported") {
                    V2EvidenceNotice(
                        title = "不支持的呈现",
                        detail = "只有工具身份跨过了载体；原始参数与结果不可用。",
                        color = v2.red,
                    )
                }
            }
        }
        Text(
            "只读 Host 投影 · 复制为本机行为，不影响 Host",
            color = v2.tx3,
            fontSize = 10.sp,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun V2EvidenceNotice(title: String, detail: String, color: androidx.compose.ui.graphics.Color) {
    val v2 = LocalV2.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(9.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(detail, color = v2.tx2, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun V2TerminalBlock(content: String) {
    val v2 = LocalV2.current
    Text(
        content,
        modifier = Modifier
            .fillMaxWidth()
            .background(v2.card, RoundedCornerShape(10.dp))
            .padding(14.dp),
        color = v2.green,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
}

@Composable
private fun V2DiffBlock(content: String) {
    val v2 = LocalV2.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(v2.card, RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp),
    ) {
        content.lines().forEach { line ->
            val (color, background) = when {
                line.startsWith("+++") || line.startsWith("---") -> v2.tx3 to androidx.compose.ui.graphics.Color.Transparent
                line.startsWith("+") -> v2.green to v2.green.copy(alpha = 0.10f)
                line.startsWith("-") -> v2.red to v2.red.copy(alpha = 0.10f)
                line.startsWith("@@") -> v2.blue to androidx.compose.ui.graphics.Color.Transparent
                else -> v2.tx2 to androidx.compose.ui.graphics.Color.Transparent
            }
            Text(
                line.ifEmpty { " " },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(background)
                    .padding(horizontal = 12.dp, vertical = 1.dp),
                color = color,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

/**
 * Minimal markdown surface for generic tool evidence: fenced code blocks and
 * headings get distinct treatment; everything else renders as plain text.
 * No HTML, no link handling — evidence stays inert.
 */
@Composable
private fun V2MarkdownBlock(content: String) {
    val v2 = LocalV2.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(v2.card, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        var inFence = false
        val fence = StringBuilder()
        val blocks = mutableListOf<Pair<Boolean, String>>()
        content.lines().forEach { line ->
            if (line.trimStart().startsWith("```")) {
                if (inFence) {
                    blocks += true to fence.toString().trimEnd('\n')
                    fence.clear()
                }
                inFence = !inFence
            } else if (inFence) {
                fence.append(line).append('\n')
            } else if (line.isNotBlank()) {
                blocks += false to line
            }
        }
        if (fence.isNotEmpty()) blocks += true to fence.toString().trimEnd('\n')
        blocks.forEach { (code, text) ->
            when {
                code -> Text(
                    text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(v2.bg2, RoundedCornerShape(7.dp))
                        .padding(10.dp),
                    color = v2.tx2,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                text.startsWith("#") -> Text(
                    text.trimStart('#').trim(),
                    color = v2.tx,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 20.sp,
                )
                else -> Text(
                    text,
                    color = v2.tx,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

/**
 * Honest client-side serialization of the current projection. The wording
 * never claims an append-only audit log; it states exactly what this is.
 */
internal fun buildProjectionExport(state: dev.dshremote.gate0c.transport.Gate0CState): String =
    buildString {
        appendLine("DSH Remote 投影导出（客户端序列化）")
        appendLine("这不是 append-only 审计日志；内容是当前投影快照，可能已被 Host 截断或被本地有界省略。")
        appendLine("会话: ${state.sessionTitle ?: state.sessionId ?: "未知"}")
        // The export keeps the per-boot instance id for precision; the stable
        // display name alone cannot distinguish two boots of the same Host.
        appendLine(
            "Host: ${
                listOfNotNull(state.hostDisplayName, state.hostInstanceId).joinToString(" · ")
                    .ifEmpty { state.endpoint }
            }",
        )
        state.cursor?.let { appendLine("cursor: $it") }
        appendLine("条目: ${state.timeline.size}")
        appendLine("---")
        state.timeline.forEach { entry ->
            appendLine("[seq ${entry.sourceSequence}] ${entry.kind} ${if (entry.final) "" else "(部分) "}${entry.text}")
            entry.boundedContent?.let { content ->
                content.lines().forEach { line -> appendLine("    $line") }
            }
            if (entry.truncated) appendLine("    …（Host 截断）")
        }
    }
