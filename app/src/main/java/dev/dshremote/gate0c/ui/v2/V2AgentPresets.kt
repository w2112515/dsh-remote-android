package dev.dshremote.gate0c.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshremote.gate0c.transport.AgentPresetProjection
import dev.dshremote.gate0c.transport.Gate0CState
import dev.dshremote.gate0c.transport.PendingCommandOperation
import dev.dshremote.gate0c.transport.agentPresetLabel
import dev.dshremote.gate0c.transport.hasCapabilities

/**
 * S-mode-select：DeepSeek 模式选择 = DSH Agent presets。
 * 名与描述全部来自 Host roster（ServerHello 快照）；本地创作的 preset 标注
 * "本地"，不可组合的行展示 broken 原因并禁用。缺失从不编造。
 */
@Composable
internal fun V2AgentPresetPickerDialog(
    presets: List<AgentPresetProjection>,
    title: String,
    confirmLabel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val v2 = LocalV2.current
    // 原型底部 sheet 形态（P7 G2）。
    V2Sheet(
        title = title,
        subtitle = "仅会话首轮前可选择 · 会话开始后模式锁定",
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text(confirmLabel, color = v2.tx3, fontSize = 12.sp) }
        },
    ) {
        presets.forEach { preset ->
            V2AgentPresetRow(preset = preset, onClick = { onSelect(preset.id) })
        }
    }
}

@Composable
private fun V2AgentPresetRow(preset: AgentPresetProjection, onClick: () -> Unit) {
    val v2 = LocalV2.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (preset.selectable) v2.card else v2.card.copy(alpha = 0.45f))
            .clickable(enabled = preset.selectable, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag("agent-preset-option-${preset.id}"),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                preset.displayName,
                color = if (preset.selectable) v2.tx else v2.tx3,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(preset.id, color = v2.tx3, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            if (preset.isDefault) V2PresetBadge("默认", v2.blue)
            if (preset.userTrust) V2PresetBadge("本地", v2.amber)
        }
        preset.description?.let { description ->
            Text(description, color = v2.tx2, fontSize = 11.sp, lineHeight = 15.sp)
        }
        preset.broken?.let { broken ->
            Text("不可用 · $broken", color = v2.amber, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun V2PresetBadge(label: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        label,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        color = color,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * Composer 模式 chip（原型 STD MODE 位的真实实现）。空白会话（首轮前）且已
 * 授权时可点开选择；会话已开始则只读锁定。Host 未配置任何 preset 且当前
 * 会话无记录时整条不渲染——没有可选择的对象。
 */
@Composable
internal fun V2AgentPresetChip(
    state: Gate0CState,
    onSelectAgentPreset: (String) -> Unit,
) {
    val v2 = LocalV2.current
    val presets = state.agentPresets
    val currentId = state.sessionAgentPreset
    if (presets.isEmpty() && currentId == null) return
    val blank = state.sessionBlank
    val pendingSelect = state.pendingCommand?.operation == PendingCommandOperation.SELECT_AGENT_PRESET
    val switchable = blank &&
        state.isReady() &&
        !state.isStaleView() &&
        state.pendingCommand == null &&
        hasCapabilities(state.grantedCapabilities, 68uL) &&
        presets.isNotEmpty()
    var pickerOpen by remember { mutableStateOf(false) }
    if (pickerOpen) {
        V2AgentPresetPickerDialog(
            presets = presets,
            title = "选择模式",
            confirmLabel = "取消",
            onSelect = { id ->
                pickerOpen = false
                onSelectAgentPreset(id)
            },
            onDismiss = { pickerOpen = false },
        )
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(v2.card)
            .clickable(enabled = switchable) { pickerOpen = true }
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("agent-preset-chip"),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("◈", color = if (switchable) v2.blue else v2.tx3, fontSize = 10.sp)
        Text(
            when {
                pendingSelect -> "模式切换中…"
                currentId != null -> agentPresetLabel(presets, currentId) ?: currentId
                blank -> "选择模式"
                else -> "模式 未投影"
            },
            color = if (switchable || currentId != null) v2.tx else v2.tx3,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        when {
            pendingSelect -> Unit
            switchable -> Text("▾", color = v2.tx3, fontSize = 9.sp)
            !blank -> Text("· 已锁定", color = v2.tx3, fontSize = 9.sp)
        }
    }
}
