package dev.dshremote.gate0c.ui.v2

import dev.dshremote.gate0c.transport.ConnectionPhase
import dev.dshremote.gate0c.transport.Gate0CState
import dev.dshremote.gate0c.transport.SessionDirectoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.DateFormat
import java.util.Date

internal enum class V2Tab { SESSIONS, APPROVALS, ARTIFACTS, NOTIFS }

internal enum class V2NotificationKind(val icon: String) {
    APPROVAL_ARRIVED("⚠"),
    APPROVAL_SETTLED("✓"),
    INPUT_WAITING("◈"),
    CONNECTION_LOST("✕"),
    REPAIR_REQUIRED("!"),
    // S-artifacts: a live artifact_registered frame (prototype type 'art').
    ARTIFACT_REGISTERED("◈"),
}

internal data class V2Notification(
    val id: Long,
    val kind: V2NotificationKind,
    val text: String,
    val timeMs: Long,
    val unread: Boolean = true,
    val tab: V2Tab? = null,
    val sessionId: String? = null,
    // S-multi-host: the Host this transition came from; session ids are only
    // unique inside one Host, so navigation needs both halves.
    val hostId: String? = null,
    // S-artifacts: set when the notification deep-links into the artifact viewer.
    val artifactId: String? = null,
)

/**
 * Client-local notification derivation (design: ANDROID_V2_PRESENTATION §3).
 * Every notification is generated from a real projection transition; nothing
 * is persisted and nothing beyond the transition fact is claimed.
 * S-multi-host: reduce per Host so equal session/approval ids from two Hosts
 * never cancel or alias each other.
 */
internal class V2NotificationCenter {
    private val _notifications = MutableStateFlow<List<V2Notification>>(emptyList())
    val notifications: StateFlow<List<V2Notification>> = _notifications.asStateFlow()
    private var nextId = 0L

    fun reduce(prev: Gate0CState, next: Gate0CState, hostId: String? = null, hostLabel: String? = null) {
        val emitted = mutableListOf<V2Notification>()
        val now = System.currentTimeMillis()
        val prefix = if (hostLabel == null) "" else "$hostLabel · "

        // Approval arrival/settlement rising edges, keyed by session directory counts
        // (content-free) and by the watched approval directory (actionable).
        next.sessions.forEach { entry ->
            val before = prev.sessions.find { it.sessionId == entry.sessionId }
            if (entry.pendingApprovalCount > 0 && (before?.pendingApprovalCount ?: 0) == 0) {
                emitted += notification(
                    V2NotificationKind.APPROVAL_ARRIVED,
                    "$prefix「${entry.title ?: "新会话"}」请求审批",
                    now, V2Tab.APPROVALS, entry.sessionId, hostId,
                )
            }
            if (entry.pendingInputCount > 0 && (before?.pendingInputCount ?: 0) == 0) {
                emitted += notification(
                    V2NotificationKind.INPUT_WAITING,
                    "$prefix「${entry.title ?: "新会话"}」等待输入",
                    now, V2Tab.SESSIONS, entry.sessionId, hostId,
                )
            }
        }
        next.approvals.forEach { approval ->
            if (prev.approvals.none { it.approvalId == approval.approvalId }) {
                val title = next.sessions.find { it.sessionId == approval.sessionId }?.title ?: "当前会话"
                emitted += notification(
                    V2NotificationKind.APPROVAL_ARRIVED,
                    "$prefix「$title」请求审批：${approval.toolName}",
                    now, V2Tab.APPROVALS, approval.sessionId, hostId,
                )
            }
        }
        prev.approvals.forEach { approval ->
            if (next.approvals.none { it.approvalId == approval.approvalId }) {
                emitted += notification(
                    V2NotificationKind.APPROVAL_SETTLED,
                    "${prefix}审批已结算：${approval.toolName}",
                    now, V2Tab.APPROVALS, approval.sessionId, hostId,
                )
            }
        }

        val lostPhases = setOf(ConnectionPhase.OFFLINE, ConnectionPhase.CLOSED, ConnectionPhase.FAILED)
        if (next.phase in lostPhases && prev.phase !in lostPhases) {
            emitted += notification(
                V2NotificationKind.CONNECTION_LOST,
                "${prefix}连接断开 · 缓存转为只读，恢复前不会执行任何操作",
                now, V2Tab.SESSIONS, null, hostId,
            )
        }
        if (next.newPairingRequired && !prev.newPairingRequired) {
            emitted += notification(
                V2NotificationKind.REPAIR_REQUIRED,
                "${prefix}授权已结束 · 需要重新配对",
                now, V2Tab.SESSIONS, null, hostId,
            )
        }

        // S-artifacts: only a live artifact_registered frame notifies — the
        // connection id pins the pair to one carrier lifetime, so a hello
        // roster replace (which also carries offline-window registrations as
        // unseen badge rows) never re-notifies what the roster already shows.
        if (prev.connectionId != null && prev.connectionId == next.connectionId) {
            val known = prev.artifacts.mapTo(mutableSetOf()) { it.artifactId }
            next.artifacts.filter { it.artifactId !in known }.forEach { artifact ->
                val title = next.sessions.find { it.sessionId == artifact.sessionId }?.title ?: "会话"
                emitted += notification(
                    V2NotificationKind.ARTIFACT_REGISTERED,
                    "$prefix「$title」登记产出：${artifact.path}",
                    now, V2Tab.ARTIFACTS, artifact.sessionId, hostId, artifact.artifactId,
                )
            }
        }

        if (emitted.isNotEmpty()) {
            _notifications.update { (emitted + it).take(MAX_NOTIFICATIONS) }
        }
    }

    private fun notification(
        kind: V2NotificationKind,
        text: String,
        timeMs: Long,
        tab: V2Tab?,
        sessionId: String?,
        hostId: String?,
        artifactId: String? = null,
    ) = V2Notification(
        id = ++nextId,
        kind = kind,
        text = text,
        timeMs = timeMs,
        tab = tab,
        sessionId = sessionId,
        hostId = hostId,
        artifactId = artifactId,
    )

    fun markRead(id: Long) {
        _notifications.update { list -> list.map { if (it.id == id) it.copy(unread = false) else it } }
    }

    fun markAllRead() {
        _notifications.update { list -> list.map { it.copy(unread = false) } }
    }

    fun dismiss(id: Long) {
        _notifications.update { list -> list.filterNot { it.id == id } }
    }

    fun clearRead() {
        _notifications.update { list -> list.filter { it.unread } }
    }

    fun clearAll() {
        _notifications.value = emptyList()
    }

    private companion object {
        const val MAX_NOTIFICATIONS = 100
    }
}

internal fun relativeTimeZh(updatedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (updatedAtMs <= 0L) return "时间未知"
    val elapsed = (nowMs - updatedAtMs).coerceAtLeast(0L)
    return when {
        elapsed < 60_000 -> "刚刚"
        elapsed < 3_600_000 -> "${elapsed / 60_000} 分钟前"
        elapsed < 86_400_000 -> "${elapsed / 3_600_000} 小时前"
        elapsed < 604_800_000 -> "${elapsed / 86_400_000} 天前"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(updatedAtMs))
    }
}

// 原型 P7 N2：通知时间为 mono 24 小时制（固定 HH:mm，不随 locale 切 AM/PM）。
internal fun clockTimeZh(timeMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(Date(timeMs))

/** Compact token count for usage surfaces: 942 → "942", 12840 → "12.8k", 2_300_000 → "2.3M". */
internal fun compactTokenCount(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 10_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1_000.0)
    value < 1_000_000 -> "${value / 1_000}k"
    else -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
}

/** Honest session vocabulary (PROTOCOL §7): no session-level done/failed. */
internal fun sessionStatusZh(entry: SessionDirectoryEntry): String = when {
    entry.pendingInputCount > 0 -> "等待输入"
    entry.pendingApprovalCount > 0 -> "等待审批"
    entry.running -> "运行中"
    else -> "空闲"
}

internal fun Gate0CState.isStaleView(): Boolean =
    offlineSnapshot || phase == ConnectionPhase.OFFLINE ||
        phase == ConnectionPhase.CLOSED || phase == ConnectionPhase.FAILED ||
        phase == ConnectionPhase.INCOMPATIBLE

internal fun Gate0CState.isReady(): Boolean =
    phase == ConnectionPhase.READY || phase == ConnectionPhase.RECONCILED
