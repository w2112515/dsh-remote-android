package dev.dshremote.gate0c.ui.v2

import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshremote.gate0c.transport.ArtifactEntryState
import dev.dshremote.gate0c.transport.BlobFetchView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Prototype KIND_META: the icon family is a presentation fact derived from the file extension. */
internal enum class V2ArtifactKind(val tag: String) {
    DIFF("DIFF"),
    CODE("CODE"),
    DOC("DOC"),
    LOG("LOG"),
}

internal fun artifactKindOf(path: String): V2ArtifactKind =
    when (path.substringAfterLast('.', "").lowercase()) {
        "diff", "patch" -> V2ArtifactKind.DIFF
        "md", "markdown" -> V2ArtifactKind.DOC
        "log" -> V2ArtifactKind.LOG
        else -> V2ArtifactKind.CODE
    }

/** One applied-hunk triple from the registry's bounded content JSON. */
internal data class V2ArtifactHunk(
    val path: String,
    val oldText: String?,
    val newText: String,
)

/**
 * Parse the bounded whole-hunk JSON the Host registered. Malformed content is
 * treated as absent — it is never rendered as an empty change.
 */
internal fun parseArtifactHunks(content: String?): List<V2ArtifactHunk>? {
    if (content == null) return null
    val parsed = runCatching {
        val array = JSONArray(content)
        List(array.length()) { index ->
            val hunk = array.getJSONObject(index)
            V2ArtifactHunk(
                path = hunk.getString("path"),
                oldText = if (hunk.isNull("oldText")) null else hunk.getString("oldText"),
                newText = hunk.getString("newText"),
            )
        }
    }.getOrNull() ?: return null
    return parsed.takeIf { it.isNotEmpty() }
}

/**
 * Lines of one hunk text for rendering/counting: a final newline is line
 * punctuation, not a phantom extra line; internal blank lines stay.
 */
private fun diffLines(text: String): List<String> {
    val trimmed = text.removeSuffix("\n")
    return if (trimmed.isEmpty()) emptyList() else trimmed.lines()
}

/**
 * Prototype `a.size` ("+12/−4"): derived from the registered hunks, never
 * asserted. Null when no hunk content crossed, so the row shows the honest
 * "no content projected" wording instead of fabricated counts.
 */
internal fun artifactSizeLabel(hunks: List<V2ArtifactHunk>?): String? {
    if (hunks == null) return null
    val added = hunks.sumOf { hunk -> diffLines(hunk.newText).size }
    val removed = hunks.sumOf { hunk -> hunk.oldText?.let(::diffLines)?.size ?: 0 }
    return "+$added/−$removed"
}

internal data class V2ArtifactRow(
    val face: V2HostFace,
    val artifact: ArtifactEntryState,
)

/** Project filter key for one row: the directory label of its producing session. */
internal fun artifactProjectKey(row: V2ArtifactRow): String? =
    row.face.state.sessions.find { it.sessionId == row.artifact.sessionId }?.projectLabel

@Composable
internal fun V2ArtifactsPanel(
    hosts: List<V2HostFace>,
    seenIds: Set<String>,
    onOpen: (hostId: String, artifactId: String) -> Unit,
) {
    val v2 = LocalV2.current
    val multiHost = hosts.size > 1
    val rows = remember(hosts) {
        hosts.flatMap { face -> face.state.artifacts.map { V2ArtifactRow(face, it) } }
            .sortedByDescending { it.artifact.registeredAtMs }
    }
    if (rows.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp, vertical = 54.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("▣", color = v2.tx3, fontSize = 26.sp)
            Text("还没有产出物", color = v2.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "任务完成后产物会汇总到这里",
                color = v2.tx2,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
        return
    }

    // 原型 chip-row 的 v2 形态：与会话面一致的下拉 chip + 筛选表。项目标签是会话
    // 目录的事实；产物经 session_id 关联，目录外的会话归入「其他」。
    val projectKeys = rows.mapTo(linkedSetOf()) { artifactProjectKey(it) }
    val hasUngrouped = null in projectKeys
    var filterLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var filterOther by rememberSaveable { mutableStateOf(false) }
    val filtered = rows.filter { row ->
        when {
            filterOther -> artifactProjectKey(row) == null
            filterLabel != null -> artifactProjectKey(row) == filterLabel
            else -> true
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 原型 P7 R5：平铺项目 chips（非下拉）——全部项目 + 各项目 + 其他。
        if (projectKeys.filterNotNull().isNotEmpty() || hasUngrouped) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                V2ArtifactFilterChip(
                    label = "全部项目",
                    selected = filterLabel == null && !filterOther,
                    onClick = { filterLabel = null; filterOther = false },
                )
                projectKeys.filterNotNull().forEach { key ->
                    V2ArtifactFilterChip(
                        label = key,
                        selected = filterLabel == key && !filterOther,
                        onClick = { filterLabel = key; filterOther = false },
                    )
                }
                if (hasUngrouped) {
                    V2ArtifactFilterChip(
                        label = "其他",
                        selected = filterOther,
                        onClick = { filterLabel = null; filterOther = true },
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            items(filtered, key = { "${it.face.hostId}/${it.artifact.artifactId}" }) { row ->
                V2ArtifactRowView(
                    row = row,
                    multiHost = multiHost,
                    unseen = "${row.face.hostId}/${row.artifact.artifactId}" !in seenIds,
                    onClick = { onOpen(row.face.hostId, row.artifact.artifactId) },
                )
            }
        }
    }
}

@Composable
private fun V2ArtifactRowView(
    row: V2ArtifactRow,
    multiHost: Boolean,
    unseen: Boolean,
    onClick: () -> Unit,
) {
    val v2 = LocalV2.current
    val artifact = row.artifact
    val kind = artifactKindOf(artifact.path)
    val hunks = remember(artifact.artifactId) { parseArtifactHunks(artifact.content) }
    val sessionTitle = row.face.state.sessions.find { it.sessionId == artifact.sessionId }?.title
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 20.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (unseen) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(v2.blue, CircleShape),
            )
        }
        // 原型 P7 R4：实心色块 kind 徽章（深色标签文字）。
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(kindColor(v2, kind), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                kind.tag,
                color = v2.bg,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                artifact.path,
                color = v2.tx,
                fontSize = 12.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                artifactMetaLabel(row, hunks, sessionTitle, multiHost),
                color = v2.tx3,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Text("›", color = v2.tx3, fontSize = 15.sp)
    }
}

private fun kindColor(v2: V2Palette, kind: V2ArtifactKind) = when (kind) {
    V2ArtifactKind.DIFF -> v2.cyan
    V2ArtifactKind.CODE -> v2.blue
    V2ArtifactKind.DOC -> v2.green
    V2ArtifactKind.LOG -> v2.amber
}

/** 原型产出面板的平铺筛选 chip（块状描边，选中蓝底）。 */
@Composable
private fun V2ArtifactFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val v2 = LocalV2.current
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) v2.blue.copy(alpha = 0.16f) else v2.card)
            .border(1.dp, if (selected) v2.blue.copy(alpha = 0.5f) else v2.line, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = if (selected) v2.blue else v2.tx2,
        fontSize = 10.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        softWrap = false,
    )
}

/** Prototype meta `${size} · ${time} · ${session title}`, extended only with registered facts. */
private fun artifactMetaLabel(
    row: V2ArtifactRow,
    hunks: List<V2ArtifactHunk>?,
    sessionTitle: String?,
    multiHost: Boolean,
): String = buildString {
    val artifact = row.artifact
    val facts = mutableListOf<String>()
    if (multiHost) facts += row.face.label
    if (artifact.isNewFile) facts += "新建"
    facts += artifactSizeLabel(hunks) ?: "无内容投影"
    if (artifact.truncated) facts += "已截断"
    if (artifact.outsideWorkspace) facts += "工作区外"
    facts += relativeTimeZh(artifact.registeredAtMs)
    if (row.face.state.isStaleView()) facts += "离线缓存"
    append(facts.joinToString(" · "))
    sessionTitle?.let { append(" · 「$it」") }
}

/**
 * 产出查看器：复制/导出/来源会话都是真实动作。导出只在内容完整且恰好一个
 * 文件时开放（多文件与截断都如实禁用）；截断产物可经 blob 通道（S-blob）
 * 按会话 ACL 抓取完整内容，抓取结果核验后另行呈现，绝不与投影 hunk 混排。
 */
@Composable
internal fun V2ArtifactViewer(
    face: V2HostFace,
    artifact: ArtifactEntryState,
    fetchArtifact: suspend (String, String) -> BlobFetchView,
    onClose: () -> Unit,
    onOpenSource: (hostId: String, sessionId: String) -> Unit,
) {
    val v2 = LocalV2.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val kind = artifactKindOf(artifact.path)
    val hunks = remember(artifact.artifactId) { parseArtifactHunks(artifact.content) }
    val session = face.state.sessions.find { it.sessionId == artifact.sessionId }
    val exportable = hunks != null && hunks.size == 1 && !artifact.truncated && Build.VERSION.SDK_INT >= 29
    // S-blob：截断产物的完整内容抓取。三态如实：抓取中 / 不可用（可重试
    // 与否）/ 已核验全文。核验后的全文单独成视图，不回填 hunk 列表。
    var fullFetch by remember(artifact.artifactId) { mutableStateOf<FullFetch>(FullFetch.Idle) }
    val startFetch = {
        fullFetch = FullFetch.Fetching
        scope.launch {
            fullFetch = when (val outcome = fetchArtifact(artifact.sessionId, artifact.artifactId)) {
                is BlobFetchView.Ready -> withContext(Dispatchers.IO) {
                    runCatching { outcome.file.readText(Charsets.UTF_8) }.getOrNull()
                }?.let { FullFetch.Ready(it, outcome.totalBytes) }
                    ?: FullFetch.Unavailable("已核验内容不是有效 UTF-8 文本", retryable = false)
                is BlobFetchView.Retryable -> FullFetch.Unavailable(outcome.detail, retryable = true)
                is BlobFetchView.Failed -> FullFetch.Unavailable(outcome.detail, retryable = false)
            }
        }
        Unit
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(v2.bg)
            .statusBarsPadding()
            // The viewer is an early-return root outside V2App's panel Column, so it
            // exposes its own testTags as resource-ids (same per-subtree pattern as v1).
            .semantics { testTagsAsResourceId = true },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("‹", color = v2.tx, fontSize = 22.sp) }
            Column(Modifier.weight(1f)) {
                Text(
                    artifact.path,
                    color = v2.tx,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(artifactSizeLabel(hunks) ?: "无内容投影")
                        append(" · ")
                        append(relativeTimeZh(artifact.registeredAtMs))
                        append(" · 来自「${session?.title ?: "会话不在目录"}」")
                        if (face.state.isStaleView()) append(" · 离线缓存")
                    },
                    color = v2.tx3,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                kind.tag,
                color = v2.tx3,
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.6.sp,
            )
        }
        Box(Modifier.weight(1f)) {
            val fetched = fullFetch
            when {
                fetched is FullFetch.Ready -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "完整文件内容 · 经 blob 通道按会话 ACL 取回并核验（${fetched.totalBytes} B）",
                        color = v2.green,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "‹ 返回改动视图",
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .clickable { fullFetch = FullFetch.Idle }
                            .padding(vertical = 4.dp),
                        color = v2.blue,
                        fontSize = 11.sp,
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        item(key = "full-text") {
                            SelectionContainer {
                                Text(
                                    fetched.text,
                                    color = v2.tx,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                }
                hunks == null -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 36.dp, vertical = 54.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("▣", color = v2.tx3, fontSize = 26.sp)
                    Text(
                        "该产物未投影内容",
                        color = v2.tx,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "注册时没有 hunk 内容随行（全新写入或无 hunk 的编辑卡片）。\n缺席如实呈现，绝不渲染为空改动。",
                        color = v2.tx2,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    hunks.forEachIndexed { index, hunk ->
                        if (hunks.size > 1) {
                            item(key = "path-$index") {
                                Text(
                                    hunk.path,
                                    color = v2.tx2,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                )
                            }
                        }
                        item(key = "hunk-$index") {
                            // 原型 P7 C11：+/- 行带红/绿底色块（与证据卡 V2DiffBlock 统一）。
                            SelectionContainer {
                                Column(Modifier.fillMaxWidth()) {
                                    hunk.oldText?.let(::diffLines)?.forEach { line ->
                                        V2DiffLine("− $line", v2.red)
                                    }
                                    diffLines(hunk.newText).forEach { line ->
                                        V2DiffLine("+ $line", v2.green)
                                    }
                                }
                            }
                        }
                    }
                    if (artifact.truncated) {
                        item(key = "truncated") {
                            // S-blob：截断产物经 blob 通道抓取完整内容（按会话
                            // ACL）；离线或失败都如实呈现，绝不假装内容完整。
                            when (val fetch = fullFetch) {
                                FullFetch.Idle -> Column(
                                    modifier = Modifier.padding(top = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        "内容已按 Host 界限截断",
                                        color = v2.amber,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    if (face.state.isStaleView()) {
                                        Text(
                                            "离线缓存 · 恢复连接后可抓取完整内容",
                                            color = v2.tx3,
                                            fontSize = 10.sp,
                                        )
                                    } else {
                                        Text(
                                            "经 blob 通道抓取完整内容",
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(7.dp))
                                                .background(v2.blue.copy(alpha = 0.14f))
                                                .clickable { startFetch() }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                                .testTag("artifact-fetch-full"),
                                            color = v2.blue,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                FullFetch.Fetching -> Text(
                                    "正在经 blob 通道抓取完整内容…",
                                    color = v2.tx3,
                                    fontSize = 10.5.sp,
                                    modifier = Modifier.padding(top = 10.dp),
                                )
                                is FullFetch.Unavailable -> Column(
                                    modifier = Modifier
                                        .padding(top = 10.dp)
                                        .then(
                                            if (fetch.retryable) {
                                                Modifier.clickable { startFetch() }
                                            } else {
                                                Modifier
                                            },
                                        ),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        if (fetch.retryable) "完整内容未取回 · 点击重试" else "完整内容不可用",
                                        color = if (fetch.retryable) v2.amber else v2.red,
                                        fontSize = 10.5.sp,
                                    )
                                    Text(fetch.detail, color = v2.tx3, fontSize = 9.5.sp, lineHeight = 13.sp)
                                }
                                is FullFetch.Ready -> Unit
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .background(v2.bg2)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val fetched = fullFetch as? FullFetch.Ready
                    val text = when {
                        fetched != null -> fetched.text
                        hunks == null -> null
                        hunks.size == 1 -> hunks.first().newText
                        else -> artifact.content
                    }
                    if (text != null) {
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(context, "内容已复制", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = fullFetch is FullFetch.Ready || hunks != null,
                modifier = Modifier.weight(1f),
            ) { Text("复制", fontSize = 12.sp) }
            OutlinedButton(
                onClick = {
                    // Both enabled branches already require Q; this local guard
                    // restates the contract where the MediaStore call happens.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@OutlinedButton
                    val fetched = fullFetch as? FullFetch.Ready
                    val exportText = fetched?.text ?: hunks?.singleOrNull()?.newText ?: return@OutlinedButton
                    scope.launch {
                        val saved = exportArtifact(context, artifact.path.substringAfterLast('/'), exportText)
                        Toast.makeText(
                            context,
                            if (saved) "已导出到下载" else "导出失败",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                enabled = exportable || (fullFetch is FullFetch.Ready && Build.VERSION.SDK_INT >= 29),
                modifier = Modifier.weight(1f),
            ) { Text("导出", fontSize = 12.sp) }
            Button(
                onClick = { onOpenSource(face.hostId, artifact.sessionId) },
                enabled = session != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = v2.blue),
            ) { Text("来源会话", fontSize = 12.sp) }
        }
        val disabledReason = when {
            session == null -> "来源会话不在当前目录 · 仅名册可达"
            fullFetch is FullFetch.Ready -> null
            hunks == null -> "无内容投影 · 复制与导出不可用"
            artifact.truncated -> "内容已截断 · 导出不完整文件没有意义（可先经 blob 通道抓取完整内容）"
            hunks.size > 1 -> "一次调用改动多个文件 · 导出暂不支持"
            Build.VERSION.SDK_INT < 29 -> "导出需要 Android 10 及以上"
            else -> null
        }
        if (disabledReason != null) {
            Text(
                disabledReason,
                color = v2.tx3,
                fontSize = 10.sp,
                modifier = Modifier
                    .background(v2.bg2)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

/** 单行 diff：文字着色 + 同色淡底块（原型 P7 C11）。 */
@Composable
private fun V2DiffLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text.ifEmpty { " " },
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 4.dp, vertical = 0.5.dp),
        color = color,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = 16.sp,
    )
}

/** MediaStore 下载导出；API 29+ 无需权限。调用点均以 SDK 门槛如实禁用。 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
private suspend fun exportArtifact(
    context: android.content.Context,
    fileName: String,
    content: String,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(content.encodeToByteArray())
        } ?: return@runCatching false
        true
    }.getOrDefault(false)
}

/** S-blob 截断产物全文抓取的三态（查看器本地状态，绝不落盘为 Host 事实）。 */
private sealed interface FullFetch {
    data object Idle : FullFetch
    data object Fetching : FullFetch
    data class Ready(val text: String, val totalBytes: Long) : FullFetch
    data class Unavailable(val detail: String, val retryable: Boolean) : FullFetch
}

