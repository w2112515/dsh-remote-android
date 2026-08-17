package dev.dshremote.gate0c.ui.v2

import dev.dshremote.gate0c.transport.Gate0CState
import dev.dshremote.gate0c.transport.PendingCommandOperation
import dev.dshremote.gate0c.transport.PendingCommandProgress
import dev.dshremote.gate0c.transport.hasCapabilities

internal enum class ComposerPrimaryKind {
    SEND,
    RECONCILE,
    RECONNECT,
    PROBE,
    BLOCKED,
}

internal data class ComposerPrimary(
    val label: String,
    val enabled: Boolean,
    val kind: ComposerPrimaryKind,
    val settlement: String,
    val showSettlement: Boolean,
    val stopEnabled: Boolean,
    val modelSelectable: Boolean,
)

/**
 * Composer write affordance. Viewing never asks for a lock; typing/send
 * prepare write-right silently. Another writer is an honest status, not a CTA.
 */
internal fun composerPrimary(
    state: Gate0CState,
    nowMs: Long = System.currentTimeMillis(),
): ComposerPrimary {
    val ready = state.isReady()
    val stale = state.isStaleView()
    val writeAuthorized = hasCapabilities(state.grantedCapabilities, 68uL)
    val stopAuthorized = hasCapabilities(state.grantedCapabilities, 72uL)
    val pending = state.pendingCommand
    val leaseUsable = state.controlLease?.let { lease ->
        lease.sessionId == state.sessionId && lease.isUsable(nowMs)
    } == true
    val heldByOther = state.controlHeldByOther && !leaseUsable

    val kind: ComposerPrimaryKind
    val label: String
    val enabled: Boolean
    when {
        state.commandRecoveryBlocked -> {
            kind = ComposerPrimaryKind.BLOCKED
            label = "修复受阻"
            enabled = false
        }
        pending != null -> {
            kind = ComposerPrimaryKind.RECONCILE
            label = when {
                pending.progress == PendingCommandProgress.UNKNOWN -> "对账"
                pending.operation == PendingCommandOperation.STOP -> "查看 Stop"
                pending.operation == PendingCommandOperation.DECIDE_APPROVAL -> "查看审批"
                else -> "查看状态"
            }
            enabled = ready && hasCapabilities(
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
        }
        stale -> {
            kind = ComposerPrimaryKind.RECONNECT
            label = "重新连接"
            enabled = true
        }
        !writeAuthorized -> {
            kind = ComposerPrimaryKind.PROBE
            label = "验证锁定"
            enabled = ready
        }
        state.sessionBudget?.exhausted == true -> {
            kind = ComposerPrimaryKind.BLOCKED
            label = "预算已用尽"
            enabled = false
        }
        heldByOther -> {
            kind = ComposerPrimaryKind.SEND
            label = "发送"
            enabled = false
        }
        else -> {
            kind = ComposerPrimaryKind.SEND
            label = "发送"
            enabled = ready && state.localDraft.isNotBlank()
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
        stale -> "草稿加密保存在本机 · 恢复连接后再发送"
        !writeAuthorized -> "只读授权 · 在 Host 上选择会话控制 profile 后才能发送"
        heldByOther -> "电脑上的网页正在发送这条会话…"
        state.sessionBudget?.exhausted == true -> "会话预算已用尽 · 在会话策略中提高上限后才能继续发送"
        leaseUsable -> "控制 epoch ${state.controlLease!!.epoch} · 发送先于传输在本地持久化"
        else -> "开始输入后会写入这台电脑上的会话"
    }

    val showSettlement = when {
        kind != ComposerPrimaryKind.SEND -> true
        heldByOther -> true
        state.sessionBudget?.exhausted == true -> true
        !leaseUsable && state.localDraft.isNotBlank() -> true
        else -> false
    }

    val stopEnabled = stopAuthorized &&
        state.sessionRunning == true &&
        !stale &&
        ready &&
        !heldByOther &&
        pending == null &&
        state.activityRevision?.let { it > 0 } == true

    val modelSelectable = ready &&
        !stale &&
        pending == null &&
        !heldByOther &&
        writeAuthorized &&
        state.modelCatalog.isNotEmpty()

    return ComposerPrimary(
        label = label,
        enabled = enabled,
        kind = kind,
        settlement = settlement,
        showSettlement = showSettlement,
        stopEnabled = stopEnabled,
        modelSelectable = modelSelectable,
    )
}
