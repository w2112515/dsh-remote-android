package dev.dshremote.gate0c.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import dev.dshremote.gate0c.transport.Gate0CState
import dev.dshremote.gate0c.transport.ModelCatalogFailureProjection
import dev.dshremote.gate0c.transport.ModelEntryProjection
import dev.dshremote.gate0c.transport.ModelProviderGroupProjection
import dev.dshremote.gate0c.transport.ModelSelectionProjection
import dev.dshremote.gate0c.transport.PendingCommandOperation
import dev.dshremote.gate0c.transport.hasCapabilities
import dev.dshremote.gate0c.transport.modelDisplayLabel

/**
 * S-session-admin：模型与推理选择（原型 model-chip 托盘的真实现）。
 * 目录全部来自 ServerHello 快照；名称回退到稳定 id，缺失从不编造。目录构建
 * 失败的 provider 展示为诚实失败行，不可选。选择作用于之后组装的请求——
 * 进行中的步骤保持其原有选择（Host 语义，与 select_model 注释一致）。
 */
@Composable
internal fun V2ModelPickerDialog(
    catalog: List<ModelProviderGroupProjection>,
    failures: List<ModelCatalogFailureProjection>,
    current: ModelSelectionProjection?,
    onSelect: (provider: String, model: String, reasoningEffort: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val v2 = LocalV2.current
    var picked by remember {
        mutableStateOf(current?.let { selection -> selection.provider to selection.model })
    }
    var effort by remember { mutableStateOf(current?.reasoningEffort) }
    val pickedEntry: ModelEntryProjection? = picked?.let { (providerId, modelId) ->
        catalog.firstOrNull { it.id == providerId }?.models?.firstOrNull { it.id == modelId }
    }
    // 原型 #model-sheet 是底部 sheet（P7 G2）；显式「应用」保留——select_model
    // 是持久命令，选中即发不符合命令语义（审计 D3 记录的有意偏差）。
    V2Sheet(
        title = "模型与推理",
        subtitle = "MODEL & REASONING EFFORT",
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消", color = v2.tx3, fontSize = 12.sp) }
            TextButton(
                onClick = {
                    val (provider, model) = picked ?: return@TextButton
                    onSelect(provider, model, effort)
                },
                enabled = picked != null,
                modifier = Modifier.testTag("model-picker-apply"),
            ) { Text("应用", color = if (picked != null) v2.blue else v2.tx3, fontSize = 12.sp) }
        },
    ) {
        Text(
            "作用于之后组装的请求 · 进行中的步骤保持其原有选择",
            color = v2.tx3,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
        catalog.forEach { group ->
            Text(
                group.displayName.uppercase(),
                color = v2.tx3,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            group.models.forEach { entry ->
                V2ModelRow(
                    group = group,
                    entry = entry,
                    selected = picked == (group.id to entry.id),
                    onClick = {
                        picked = group.id to entry.id
                        effort = entry.defaultReasoningEffort
                    },
                )
            }
        }
        failures.forEach { failure ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(v2.amber.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    "目录不可用 · ${failure.providerId}",
                    color = v2.amber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                failure.detail?.let { detail ->
                    Text(detail, color = v2.tx2, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
        }
        if (pickedEntry != null && pickedEntry.reasoningEfforts.isNotEmpty()) {
            Text(
                "推理档位",
                color = v2.tx3,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            // 原型：通栏三分段，而非滚动 chips。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                // “默认” = 不携带显式档位：owner 清除继承档位，适配器默认生效。
                V2EffortChip(
                    label = "默认",
                    selected = effort == null,
                    onClick = { effort = null },
                    modifier = Modifier.weight(1f),
                )
                pickedEntry.reasoningEfforts.forEach { option ->
                    V2EffortChip(
                        label = option,
                        selected = effort == option,
                        onClick = { effort = option },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun V2ModelRow(
    group: ModelProviderGroupProjection,
    entry: ModelEntryProjection,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val v2 = LocalV2.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) v2.blue.copy(alpha = 0.12f) else v2.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag("model-option-${group.id}/${entry.id}"),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (selected) v2.blue else v2.line),
        )
        Column(Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.displayName,
                    color = v2.tx,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(entry.id, color = v2.tx3, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            if (entry.reasoningEfforts.isNotEmpty()) {
                Text(
                    "推理档位 · ${entry.reasoningEfforts.joinToString(" / ")}",
                    color = v2.tx2,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun V2EffortChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val v2 = LocalV2.current
    Text(
        label,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) v2.blue.copy(alpha = 0.16f) else v2.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("model-effort-$label"),
        color = if (selected) v2.blue else v2.tx2,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * Composer 模型 chip（原型 model-chip 的真实现）。显示当前会话的模型三元组；
 * 取得会话控制后可点开目录选择。Host 目录为空且当前会话无记录时整条不渲染
 * ——没有可选择的对象。目录缺失从不用部署默认值冒充。
 */
@Composable
internal fun V2ModelChip(
    state: Gate0CState,
    onSelectModel: (String, String, String?) -> Unit,
) {
    val v2 = LocalV2.current
    val catalog = state.modelCatalog
    val current = state.sessionModel
    if (catalog.isEmpty() && current == null) return
    val pendingSelect = state.pendingCommand?.operation == PendingCommandOperation.SELECT_MODEL
    val leaseUsable = state.controlLease?.let { lease ->
        lease.sessionId == state.sessionId && lease.isUsable()
    } == true
    // select_model 与 send_input 一样需要会话控制栅栏（会话中可切换）。
    val selectable = state.isReady() &&
        !state.isStaleView() &&
        state.pendingCommand == null &&
        leaseUsable &&
        hasCapabilities(state.grantedCapabilities, 68uL) &&
        catalog.isNotEmpty()
    var pickerOpen by remember { mutableStateOf(false) }
    if (pickerOpen) {
        V2ModelPickerDialog(
            catalog = catalog,
            failures = state.modelCatalogFailures,
            current = current,
            onSelect = { provider, model, reasoningEffort ->
                pickerOpen = false
                onSelectModel(provider, model, reasoningEffort)
            },
            onDismiss = { pickerOpen = false },
        )
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(v2.card)
            // 原型 .model-chip 的 1px 描边。
            .border(1.dp, v2.line, RoundedCornerShape(99.dp))
            .clickable(enabled = selectable) { pickerOpen = true }
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("model-chip"),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(if (selectable || current != null) v2.blue else v2.tx3),
        )
        Text(
            when {
                pendingSelect -> "模型切换中…"
                current != null -> modelDisplayLabel(catalog, current)
                catalog.isNotEmpty() -> "选择模型"
                else -> "模型 未投影"
            },
            color = if (selectable || current != null) v2.tx else v2.tx3,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
        if (selectable && !pendingSelect) {
            Text("▾", color = v2.tx3, fontSize = 9.sp)
        }
    }
}
