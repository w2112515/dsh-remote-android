package dev.dshremote.gate0c.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshremote.gate0c.transport.TimelineEntry
import dev.dshremote.gate0c.transport.TimelineKind
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SessionTimeline(
    timeline: List<TimelineEntry>,
    historyTruncated: Boolean,
    listState: LazyListState,
    followTail: Boolean,
    onFollowTailChanged: (Boolean) -> Unit,
    onReadingPositionChanged: (String?, Int) -> Unit,
    onToolSelected: (TimelineEntry) -> Unit,
    modifier: Modifier = Modifier,
    attentionContent: (@Composable () -> Unit)? = null,
    offlineSnapshot: Boolean = false,
    offlineCacheSavedAtMs: Long? = null,
    offlineCacheTruncated: Boolean = false,
    readingAnchorUnavailable: Boolean = false,
    cacheWarning: String? = null,
) {
    val attentionCount = if (attentionContent == null) 0 else 1
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            !listState.isScrollInProgress || lastVisible >= lastIndex - 1
        }.distinctUntilChanged().collect { atTail ->
            if (listState.isScrollInProgress) onFollowTailChanged(atTail)
        }
    }
    LaunchedEffect(timeline.size, timeline.lastOrNull()?.text?.length) {
        if (followTail && timeline.isNotEmpty()) {
            val noticeCount = listOf(
                offlineSnapshot,
                offlineCacheTruncated,
                readingAnchorUnavailable,
                cacheWarning != null,
                historyTruncated,
            ).count { it }
            listState.scrollToItem(attentionCount + noticeCount + timeline.lastIndex)
        }
    }
    LaunchedEffect(listState, timeline) {
        snapshotFlow {
            val first = listState.layoutInfo.visibleItemsInfo
                .firstOrNull {
                    it.key != "attention" &&
                        it.key != "history-truncated" &&
                        it.key.toString().startsWith("notice:").not()
                }
            Triple(listState.isScrollInProgress, first?.key?.toString(), (-1 * (first?.offset ?: 0)).coerceAtLeast(0))
        }.distinctUntilChanged().collect { (scrolling, anchor, offset) ->
            if (!scrolling) onReadingPositionChanged(anchor, offset)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .testTag("session-timeline")
            .semantics { testTagsAsResourceId = true },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        attentionContent?.let { content ->
            item(key = "attention", contentType = "attention") {
                content()
            }
        }
        if (offlineSnapshot) {
            item(key = "notice:offline", contentType = "notice") {
                InlineNotice(
                    buildString {
                        append("Offline snapshot — read-only and potentially stale")
                        offlineCacheSavedAtMs?.let { savedAt ->
                            append(" · synced ")
                            append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(savedAt)))
                        }
                        append(". Reconnect for authoritative state.")
                    },
                )
            }
        }
        if (offlineCacheTruncated) {
            item(key = "notice:cache-truncated", contentType = "notice") {
                InlineNotice("The local cache is bounded and omits older or oversized content. Host history was not changed.")
            }
        }
        if (readingAnchorUnavailable) {
            item(key = "notice:anchor-unavailable", contentType = "notice") {
                InlineNotice("Your previous reading position is outside this snapshot. Showing the earliest available item.")
            }
        }
        cacheWarning?.let { warning ->
            item(key = "notice:cache-warning", contentType = "notice") {
                InlineNotice(warning)
            }
        }
        if (historyTruncated) {
            item(key = "history-truncated", contentType = "notice") {
                InlineNotice("Earlier history is not included in this synchronized projection.")
            }
        }
        items(
            items = timeline,
            key = TimelineEntry::id,
            contentType = TimelineEntry::contentType,
        ) { entry ->
            TimelineRow(entry, onToolSelected)
        }
        if (timeline.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptyTimeline()
            }
        }
    }
}

@Composable
private fun TimelineRow(entry: TimelineEntry, onToolSelected: (TimelineEntry) -> Unit) {
    when (entry.kind) {
        TimelineKind.USER -> UserRow(entry)
        TimelineKind.INJECT -> UserRow(entry)
        TimelineKind.ASSISTANT -> AssistantRow(entry)
        TimelineKind.TOOL_GENERIC,
        TimelineKind.TOOL_TERMINAL,
        TimelineKind.TOOL_DIFF,
        TimelineKind.TOOL_UNSUPPORTED,
        TimelineKind.SUBAGENT,
        -> ToolRow(entry, onClick = { onToolSelected(entry) })
        TimelineKind.SESSION -> SessionFact(entry.text)
        TimelineKind.UNSUPPORTED -> InlineNotice(entry.text)
    }
}

@Composable
private fun UserRow(entry: TimelineEntry) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                Eyebrow("YOU", MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                BodyText(entry.text)
            }
        }
    }
}

@Composable
private fun AssistantRow(entry: TimelineEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(width = 2.dp, height = if (entry.final) 20.dp else 42.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (entry.final) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("DSH", MaterialTheme.colorScheme.primary)
                if (!entry.final) {
                    Text(
                        text = "  STREAMING",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            BodyText(entry.text.ifEmpty { "Waiting for output…" })
            if (!entry.final) {
                Spacer(Modifier.height(9.dp))
                Text(
                    text = "Partial output — completion has not been reported by the Host.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun ToolRow(entry: TimelineEntry, onClick: () -> Unit) {
    val unsupported = entry.kind == TimelineKind.TOOL_UNSUPPORTED
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (entry.kind) {
                        TimelineKind.TOOL_TERMINAL -> ">_"
                        TimelineKind.TOOL_DIFF -> "±"
                        TimelineKind.TOOL_UNSUPPORTED -> "?"
                        else -> "◆"
                    },
                    color = if (unsupported) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
            Column(Modifier.weight(1f)) {
                Eyebrow(entry.kind.label.uppercase(), MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(5.dp))
                Text(
                    text = entry.text,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
                entry.boundedContent?.let { content ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = content,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.truncated) {
                    Spacer(Modifier.height(7.dp))
                    Text("CONTENT TRUNCATED BY HOST", color = DshColors.Warning, fontSize = 10.sp, letterSpacing = 0.7.sp)
                }
                Spacer(Modifier.height(9.dp))
                Text(
                    "OPEN DETAILS",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp,
                )
            }
        }
    }
}

@Composable
private fun SessionFact(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(99.dp))
                .padding(horizontal = 11.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun InlineNotice(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
    )
}

@Composable
private fun EmptyTimeline() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Synchronizing session", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text("No projected events are available yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun Eyebrow(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text = text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
}
