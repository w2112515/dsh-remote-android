package dev.dshremote.gate0c

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshremote.gate0c.transport.TimelineEntry
import dev.dshremote.gate0c.transport.TimelineKind
import dev.dshremote.gate0c.ui.DshColors

@Composable
internal fun ToolDetailScreen(entry: TimelineEntry, onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onBack) {
                        Text(
                            "SESSION",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp,
                        )
                    }
                    Text(
                        text = entry.kind.detailLabel(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
                Text(
                    text = entry.text,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 23.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = listOfNotNull(entry.toolName, entry.callId).joinToString(" · ")
                        .ifEmpty { "Host-projected tool evidence" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (entry.truncated) {
                    item(key = "truncated") {
                        EvidenceNotice(
                            title = "HOST-BOUNDED CONTENT",
                            detail = "This projection is truncated. The app does not claim the omitted evidence is available.",
                            color = DshColors.Warning,
                        )
                    }
                }
                item(key = "content") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                text = entry.boundedContent ?: "No bounded detail was supplied by the Host presenter.",
                                color = if (entry.boundedContent == null) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
                if (entry.kind == TimelineKind.TOOL_UNSUPPORTED) {
                    item(key = "unsupported") {
                        EvidenceNotice(
                            title = "UNSUPPORTED PRESENTATION",
                            detail = "Only the tool identity crossed the carrier; raw arguments and results remain unavailable.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(
                text = "Read-only Host projection",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun EvidenceNotice(title: String, detail: String, color: androidx.compose.ui.graphics.Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(title, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

private fun TimelineKind.detailLabel(): String = when (this) {
    TimelineKind.TOOL_TERMINAL -> "TERMINAL EVIDENCE"
    TimelineKind.TOOL_DIFF -> "DIFF EVIDENCE"
    TimelineKind.TOOL_GENERIC -> "TOOL EVIDENCE"
    TimelineKind.TOOL_UNSUPPORTED -> "UNSUPPORTED TOOL"
    else -> "PROJECTED EVIDENCE"
}
