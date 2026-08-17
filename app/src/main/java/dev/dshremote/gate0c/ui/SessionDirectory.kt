package dev.dshremote.gate0c

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshremote.gate0c.transport.ConnectionPhase
import dev.dshremote.gate0c.transport.Gate0CState
import dev.dshremote.gate0c.transport.SessionDirectoryEntry
import dev.dshremote.gate0c.ui.DshColors
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SessionDirectoryScreen(
    state: Gate0CState,
    onSessionSelected: (String) -> Unit,
    onReconnect: () -> Unit,
) {
    val stale = state.phase == ConnectionPhase.DISCONNECTED ||
        state.phase == ConnectionPhase.CLOSED ||
        state.phase == ConnectionPhase.FAILED ||
        state.phase == ConnectionPhase.OFFLINE ||
        state.phase == ConnectionPhase.INCOMPATIBLE ||
        state.offlineSnapshot
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            DirectoryHeader(state)
            if (state.phase == ConnectionPhase.INCOMPATIBLE && state.sessions.isNotEmpty()) {
                IncompatibleDirectoryNotice()
            } else if (stale && state.sessions.isNotEmpty()) {
                StaleDirectoryNotice(state.offlineCacheSavedAtMs, state.cacheWarning, onReconnect)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            when {
                state.sessions.isNotEmpty() -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.sessions, key = SessionDirectoryEntry::sessionId) { session ->
                        SessionDirectoryRow(
                            session = session,
                            hostLabel = state.hostDisplayName ?: state.hostInstanceId ?: state.endpoint,
                            stale = stale,
                            onClick = { onSessionSelected(session.sessionId) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 50.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.72f),
                        )
                    }
                }
                state.phase == ConnectionPhase.FAILED ||
                    state.phase == ConnectionPhase.CLOSED ||
                    state.phase == ConnectionPhase.OFFLINE ->
                    EmptyDirectory(
                        title = "Host unavailable",
                        detail = state.failure ?: "Reconnect to request the current Session directory.",
                        action = onReconnect,
                    )
                state.phase == ConnectionPhase.INCOMPATIBLE ->
                    EmptyDirectory(
                        title = "Update required",
                        detail = "This app and Host use incompatible protocol versions. Update both endpoints together.",
                    )
                state.phase == ConnectionPhase.HELLO || state.phase == ConnectionPhase.READY ->
                    EmptyDirectory(
                        title = "No Sessions yet",
                        detail = "Start work in DSH on the Host, then reconnect to refresh this list.",
                        action = onReconnect,
                    )
                else -> EmptyDirectory(
                    title = "Connecting to DSH",
                    detail = "Requesting the current read-only Session directory from the Host.",
                )
            }
        }
    }
}

@Composable
private fun IncompatibleDirectoryNotice() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DshColors.Warning.copy(alpha = 0.09f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("Update required", color = DshColors.Warning, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(
            "Cached Sessions are stale. Update DSH Remote and the Host integration together before synchronizing.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun DirectoryHeader(state: Gate0CState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "DSH REMOTE",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            PhaseStatus(state.phase)
        }
        Text(
            text = "Sessions",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = buildString {
                append(state.hostDisplayName ?: state.hostInstanceId ?: "Waiting for Host identity")
                if (state.sessions.isNotEmpty()) append(" · ${state.sessions.size} available")
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SessionDirectoryRow(
    session: SessionDirectoryEntry,
    hostLabel: String,
    stale: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(8.dp)
                .background(
                    color = if (stale) {
                        DshColors.Warning
                    } else if (session.running) {
                        DshColors.Success
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = CircleShape,
                ),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = session.title ?: "Untitled Session",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        stale -> "STALE"
                        session.pendingInputCount > 0 -> "${session.pendingInputCount} NEEDS INPUT"
                        session.pendingApprovalCount > 0 -> "${session.pendingApprovalCount} NEEDS REVIEW"
                        else -> relativeActivity(session.updatedAtMs)
                    },
                    color = if (stale || session.pendingInputCount > 0 || session.pendingApprovalCount > 0) {
                        DshColors.Warning
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 11.sp,
                    fontWeight = if (stale || session.pendingInputCount > 0 || session.pendingApprovalCount > 0) {
                        FontWeight.Bold
                    } else {
                        FontWeight.Normal
                    },
                )
            }
            Text(
                text = listOf(
                    if (session.running) "Running" else "Idle",
                    if (session.pendingInputCount > 0) "Input waiting" else null,
                    if (session.pendingApprovalCount > 0) "Approval waiting" else null,
                    session.workspaceLabel ?: "Workspace unavailable",
                    hostLabel,
                ).filterNotNull().joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StaleDirectoryNotice(savedAtMs: Long?, cacheWarning: String?, onReconnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DshColors.Warning.copy(alpha = 0.09f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildString {
                append("Offline snapshot · reconnect before acting on Session state")
                savedAtMs?.let { savedAt ->
                    append(" · synced ")
                    append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(savedAt)))
                }
                cacheWarning?.let { warning -> append("\n$warning") }
            },
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Button(onClick = onReconnect) { Text("Reconnect") }
    }
}

@Composable
private fun EmptyDirectory(
    title: String,
    detail: String,
    action: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp, vertical = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        if (action != null) Button(onClick = action) { Text("Reconnect") }
    }
}

private fun relativeActivity(updatedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (updatedAtMs <= 0L) return "Unknown"
    val elapsed = (nowMs - updatedAtMs).coerceAtLeast(0L)
    return when {
        elapsed < 60_000 -> "Now"
        elapsed < 3_600_000 -> "${elapsed / 60_000}m"
        elapsed < 86_400_000 -> "${elapsed / 3_600_000}h"
        elapsed < 604_800_000 -> "${elapsed / 86_400_000}d"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(updatedAtMs))
    }
}
