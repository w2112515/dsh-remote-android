package dev.dshremote.gate0c.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshremote.gate0c.transport.ApprovalRuleState
import dev.dshremote.gate0c.transport.Gate0CState
import dev.dshremote.gate0c.transport.hasCapabilities
import java.text.DateFormat
import java.util.Date

/**
 * S-policy 会话策略 sheet（ADR-006）：列出 Host 策略引擎为本会话铸造的同类
 * 放行规则（可逐条撤销），并展示/设置会话 token 预算。两类事实都来自 Host
 * 的持久 policy fold——空列表就是"无规则"，预算缺席就是"无预算"，绝不猜测。
 */
@Composable
internal fun V2PolicySheet(
    state: Gate0CState,
    onRevokeRule: (String) -> Unit,
    onSetBudget: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val v2 = LocalV2.current
    val ready = state.isReady()
    val stale = state.isStaleView()
    val pending = state.pendingCommand != null
    val revokeAuthorized = hasCapabilities(state.grantedCapabilities, 16uL)
    val budgetAuthorized = hasCapabilities(state.grantedCapabilities, 68uL)
    val mutable = ready && !stale && !pending
    var budgetInput by rememberSaveable(state.sessionId) { mutableStateOf("") }
    val parsedBudget = budgetInput.toLongOrNull()?.takeIf { it > 0 }

    V2Sheet(
        title = "会话策略",
        subtitle = "S-POLICY · 仅本会话 · 规则与预算持久于 Host 日志",
        onDismiss = onDismiss,
    ) {
        // --- 同类放行规则 ---------------------------------------------------
        Text("同类放行规则", color = v2.tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (state.approvalRules.isEmpty()) {
            Text(
                "无同类放行规则 · 在审批卡上选择「放行同类操作」后出现",
                color = v2.tx3,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        } else {
            state.approvalRules.forEach { rule ->
                V2PolicyRuleRow(
                    rule = rule,
                    revokeEnabled = mutable && revokeAuthorized,
                    onRevoke = { onRevokeRule(rule.ruleId) },
                )
            }
            if (!revokeAuthorized) {
                Text(
                    "本机可查看但未获审批授权（需要能力 16），无法撤销规则",
                    color = v2.tx3,
                    fontSize = 10.sp,
                )
            }
        }

        // --- 会话预算 -------------------------------------------------------
        Text(
            "会话预算",
            color = v2.tx,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp),
        )
        val budget = state.sessionBudget
        if (budget == null) {
            Text("未设置 · 无预算即不限额", color = v2.tx3, fontSize = 11.sp)
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "上限 ${compactTokenCount(budget.maxTotalTokens)} tokens",
                    color = v2.tx,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("policy-budget-current"),
                )
                if (budget.exhausted) {
                    Text(
                        "已用尽",
                        color = v2.red,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(v2.red.copy(alpha = 0.12f), RoundedCornerShape(99.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = budgetInput,
                onValueChange = { next -> budgetInput = next.filter(Char::isDigit).take(15) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("policy-budget-input"),
                enabled = mutable && budgetAuthorized,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = v2.tx,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                placeholder = {
                    Text(
                        if (budget == null) "token 上限，如 200000" else "新上限（替换当前值）",
                        color = v2.tx3,
                        fontSize = 12.sp,
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = v2.blue,
                    unfocusedBorderColor = v2.line,
                ),
            )
            Button(
                onClick = {
                    parsedBudget?.let {
                        onSetBudget(it)
                        budgetInput = ""
                    }
                },
                enabled = mutable && budgetAuthorized && parsedBudget != null,
                modifier = Modifier.testTag("policy-set-budget"),
                colors = ButtonDefaults.buttonColors(containerColor = v2.blue),
            ) { Text(if (budget == null) "设置" else "替换") }
        }
        Text(
            when {
                stale -> "STALE 只读 · 恢复连接后才能更改策略"
                pending -> "命令结算中 · 完成后可继续策略操作"
                !budgetAuthorized -> "只读授权 · 需要会话控制能力（68）才能设置预算"
                else -> "上限按累计 token 用量执行；达到后 Host 拒绝新输入，可随时提高。"
            },
            color = v2.tx3,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun V2PolicyRuleRow(
    rule: ApprovalRuleState,
    revokeEnabled: Boolean,
    onRevoke: () -> Unit,
) {
    val v2 = LocalV2.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(v2.card, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)
            .testTag("policy-rule-row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                rule.classLabel,
                color = v2.tx,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                buildString {
                    append(if (rule.grantedBy == "user") "用户授权" else "运维配置")
                    append(" · ")
                    append(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(rule.grantedAtMs)),
                    )
                },
                color = v2.tx3,
                fontSize = 10.sp,
            )
        }
        TextButton(
            onClick = onRevoke,
            enabled = revokeEnabled,
            modifier = Modifier.testTag("policy-revoke-rule"),
        ) {
            Text("撤销", color = if (revokeEnabled) v2.red else v2.tx3, fontSize = 12.sp)
        }
    }
}
