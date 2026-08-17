package dev.dshremote.gate0c.ui.v2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshremote.gate0c.transport.ApprovalInteractionState
import dev.dshremote.gate0c.transport.ApprovalRisk
import dev.dshremote.gate0c.transport.Gate0CState
import dev.dshremote.gate0c.transport.PendingApprovalDecision
import dev.dshremote.gate0c.transport.PendingCommandOperation
import dev.dshremote.gate0c.transport.SessionDirectoryEntry
import dev.dshremote.gate0c.transport.hasCapabilities

internal fun riskColorV2(v2: V2Palette, risk: ApprovalRisk) = when (risk) {
    ApprovalRisk.ROUTINE -> v2.green
    ApprovalRisk.SENSITIVE -> v2.amber
    ApprovalRisk.DESTRUCTIVE -> v2.red
    ApprovalRisk.UNCLASSIFIED -> v2.tx3
}

internal fun riskLabelV2(risk: ApprovalRisk) = when (risk) {
    ApprovalRisk.ROUTINE -> "ROUTINE"
    ApprovalRisk.SENSITIVE -> "SENSITIVE"
    ApprovalRisk.DESTRUCTIVE -> "DESTRUCTIVE"
    ApprovalRisk.UNCLASSIFIED -> "未分级"
}

@Composable
internal fun V2ApprovalsPanel(
    hosts: List<V2HostFace>,
    onOpenSession: (hostId: String, sessionId: String) -> Unit,
) {
    val v2 = LocalV2.current
    val multiHost = hosts.size > 1
    // S-multi-host: every Host's actionable cards with its own gating, then every
    // Host's waiting-elsewhere rows; a decision always routes to the owning Host.
    val actionable = hosts.flatMap { face -> face.state.approvals.map { face to it } }
    val waitingElsewhere = hosts.flatMap { face ->
        face.state.sessions.filter {
            it.pendingApprovalCount > 0 && it.sessionId != face.state.sessionId
        }.map { face to it }
    }
    if (actionable.isEmpty() && waitingElsewhere.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp, vertical = 54.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("✓", color = v2.green, fontSize = 26.sp)
            Text("没有待审批项", color = v2.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("ALL CLEAR · 一切尽在掌握", color = v2.tx3, fontSize = 11.sp)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(actionable, key = { "${it.first.hostId}/${it.second.approvalId}" }) { (face, approval) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (multiHost) {
                    Text(
                        face.label,
                        color = v2.tx3,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                V2ApprovalCard(
                    approval = approval,
                    pendingCommandOperation = face.state.pendingCommand?.operation,
                    decisionAuthorized = hasCapabilities(face.state.grantedCapabilities, 16uL) && face.state.isReady(),
                    offline = face.state.isStaleView(),
                    onDecision = face.callbacks.onApprovalDecision,
                )
            }
        }
        items(waitingElsewhere, key = { "${it.first.hostId}/${it.second.sessionId}" }) { (face, session) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(v2.card, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button) { onOpenSession(face.hostId, session.sessionId) }
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "「${session.title ?: "新会话"}」有 ${session.pendingApprovalCount} 项待审批",
                        color = v2.tx,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            face.state.isStaleView() && multiHost -> "${face.label} · 离线缓存 · 打开会话只读查看"
                            face.state.isStaleView() -> "离线缓存 · 打开会话只读查看"
                            multiHost -> "${face.label} · 打开会话后可决定"
                            else -> "打开会话后可决定"
                        },
                        color = v2.tx3,
                        fontSize = 11.sp,
                    )
                }
                Text("›", color = v2.tx3, fontSize = 16.sp)
            }
        }
    }
}

@Composable
internal fun V2ApprovalCard(
    approval: ApprovalInteractionState,
    pendingCommandOperation: PendingCommandOperation?,
    decisionAuthorized: Boolean,
    offline: Boolean,
    onDecision: (String, PendingApprovalDecision) -> Unit,
) {
    val v2 = LocalV2.current
    val risk = if (approval.evidence.available) approval.evidence.risk else ApprovalRisk.UNCLASSIFIED
    val riskColor = riskColorV2(v2, risk)
    val decisionPending = pendingCommandOperation == PendingCommandOperation.DECIDE_APPROVAL
    var confirming by rememberSaveable(approval.approvalId) { mutableStateOf(false) }
    var confirmingSameKind by rememberSaveable(approval.approvalId) { mutableStateOf(false) }
    var destructiveAcknowledged by rememberSaveable(approval.approvalId) { mutableStateOf(false) }

    if (confirming) {
        val destructive = risk == ApprovalRisk.DESTRUCTIVE
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(if (destructive) "确认破坏性放行" else "确认放行一次") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("approval-confirmation-content"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(approval.evidence.summary, fontWeight = FontWeight.SemiBold)
                    Text(
                        approval.evidence.consequence,
                        color = if (destructive) v2.red else v2.tx2,
                    )
                    if (destructive) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = destructiveAcknowledged,
                                    role = Role.Checkbox,
                                    onValueChange = { destructiveAcknowledged = it },
                                )
                                .semantics(mergeDescendants = true) {}
                                .testTag("approval-destructive-acknowledgement"),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = destructiveAcknowledged, onCheckedChange = null)
                            Text(
                                "我已查看受影响资源，理解此操作无法由 DSH Remote 撤销。",
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                    Text(
                        "本次放行仅生效一次，不会形成持久许可。",
                        color = v2.tx2,
                        fontSize = 11.sp,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("保持阻止") }
            },
            confirmButton = {
                Button(
                    enabled = !destructive || destructiveAcknowledged,
                    modifier = Modifier.testTag("approval-confirm-allow-once"),
                    onClick = {
                        confirming = false
                        onDecision(approval.approvalId, PendingApprovalDecision.ALLOW_ONCE)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (destructive) v2.red else v2.blue,
                    ),
                ) { Text("放行一次") }
            },
        )
    }

    // S-policy（ADR-006）：同类放行 = 一次放行 + 在 Host 策略引擎铸造本会话
    // 内的同类自动放行规则。sheet 里说清规则边界，规则可随时撤销。
    if (confirmingSameKind) {
        val destructive = risk == ApprovalRisk.DESTRUCTIVE
        V2Sheet(
            title = "放行同类操作",
            subtitle = "SAME-KIND RULE · 仅本会话 · 可撤销",
            onDismiss = { confirmingSameKind = false },
            actions = {
                TextButton(onClick = { confirmingSameKind = false }) { Text("保持逐次审批") }
                Button(
                    enabled = !destructive || destructiveAcknowledged,
                    modifier = Modifier.testTag("approval-confirm-same-kind"),
                    onClick = {
                        confirmingSameKind = false
                        onDecision(approval.approvalId, PendingApprovalDecision.ALLOW_SAME_KIND)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (destructive) v2.red else v2.blue,
                    ),
                ) { Text("放行并生成规则") }
            },
        ) {
            Text(approval.evidence.summary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp)
            if (approval.evidence.consequence.isNotBlank()) {
                Text(
                    approval.evidence.consequence,
                    color = if (destructive) v2.red else v2.tx2,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Text(
                "本次放行后，「${approval.toolName}」在本会话中的同类请求将自动放行，" +
                    "不再逐次确认。规则仅在本会话内生效，可在会话面板随时撤销。",
                color = v2.tx2,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            if (destructive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = destructiveAcknowledged,
                            role = Role.Checkbox,
                            onValueChange = { destructiveAcknowledged = it },
                        )
                        .semantics(mergeDescendants = true) {}
                        .testTag("approval-samekind-destructive-acknowledgement"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = destructiveAcknowledged, onCheckedChange = null)
                    Text(
                        "我理解同类破坏性操作此后将不再逐次确认，且无法由 DSH Remote 撤销已执行的效果。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }

    // 原型 P7 A4：卡片带风险色描边（destructive 光晕感 = 提升透明度）。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(riskColor.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .border(
                1.dp,
                riskColor.copy(alpha = if (risk == ApprovalRisk.DESTRUCTIVE) 0.55f else 0.30f),
                RoundedCornerShape(14.dp),
            )
            .testTag("approval-attention")
            .semantics {
                testTagsAsResourceId = true
                stateDescription = "approval waiting · ${riskLabelV2(risk)}"
            }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "● ${riskLabelV2(risk)}",
                color = riskColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Text(
                "revision ${approval.revision.take(8)}",
                color = v2.tx3,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        // 原型 P7 A4：命令行独立代码框（深底 + mono）。
        Text(
            "$ ${approval.toolName}",
            modifier = Modifier
                .fillMaxWidth()
                .background(v2.bg, RoundedCornerShape(8.dp))
                .border(1.dp, v2.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            color = v2.tx,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        approval.reason?.let {
            Text(it, color = v2.tx2, fontSize = 11.sp, lineHeight = 16.sp)
        }
        if (approval.evidence.available) {
            Text(approval.evidence.summary, color = v2.tx, fontSize = 12.sp, lineHeight = 17.sp)
            if (approval.evidence.resources.isNotEmpty()) {
                Text(
                    approval.evidence.resources.joinToString(" · "),
                    color = v2.tx2,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (approval.evidence.consequence.isNotBlank()) {
                Text(approval.evidence.consequence, color = v2.tx2, fontSize = 11.sp, lineHeight = 16.sp)
            }
        } else {
            Text(
                "Host 未提供风险说明 · 按敏感处理，绝不视为低风险",
                color = v2.amber,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        approval.workspaceLabel?.let {
            Text("来自 $it", color = v2.tx3, fontSize = 10.sp)
        }
        val firstSeenMs = androidx.compose.runtime.remember(approval.approvalId) {
            System.currentTimeMillis()
        }
        val waitedMin = (System.currentTimeMillis() - firstSeenMs) / 60_000
        Text(
            if (waitedMin < 1) "本次连接刚刚到达" else "本次连接已等待 $waitedMin 分钟",
            color = v2.tx3,
            fontSize = 10.sp,
        )
        if (offline) {
            Text(
                "离线 · 审批决定需恢复连接后进行",
                color = v2.tx3,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onDecision(approval.approvalId, PendingApprovalDecision.DENY) },
                    enabled = decisionAuthorized && approval.deny && !decisionPending,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("approval-deny"),
                    border = BorderStroke(1.dp, v2.red.copy(alpha = 0.5f)),
                ) { Text("拒绝", color = v2.red) }
                Button(
                    onClick = {
                        if (risk == ApprovalRisk.ROUTINE) {
                            onDecision(approval.approvalId, PendingApprovalDecision.ALLOW_ONCE)
                        } else {
                            confirming = true
                        }
                    },
                    enabled = decisionAuthorized && approval.allowOnce && !decisionPending,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("approval-allow-once"),
                    colors = ButtonDefaults.buttonColors(containerColor = v2.blue),
                ) { Text(if (decisionPending) "结算中…" else "放行一次") }
            }
        }
        Text(
            when {
                offline -> "STALE 只读 · 不产生任何决定"
                decisionPending -> "决定已安全记录 · 等待 Host 持久结算"
                !decisionAuthorized -> "本机可查看但未获审批授权（需要能力 16）"
                approval.allowSameKind -> "精确 revision 绑定 · 同类放行生成可撤销规则"
                else -> "精确 revision 绑定 · 无「总是允许」选项"
            },
            color = v2.tx3,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
        // S-policy（ADR-006）：仅当 Host 在 allowed_decisions 中真实提供
        // ALLOW_SAME_KIND（可导出诚实规则类）时才出现第三决定；客户端绝不
        // 自行发明持久许可。
        if (!offline && approval.allowSameKind) {
            val sameKindEnabled = decisionAuthorized && !decisionPending
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (sameKindEnabled) {
                            Modifier.clickable(role = Role.Button) { confirmingSameKind = true }
                        } else {
                            Modifier
                        },
                    )
                    .testTag("approval-allow-same-kind")
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "放行本会话中的同类操作 ›",
                    color = if (sameKindEnabled) v2.blue else v2.tx3,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
