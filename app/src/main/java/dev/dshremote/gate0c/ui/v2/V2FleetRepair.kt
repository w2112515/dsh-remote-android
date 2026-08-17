package dev.dshremote.gate0c.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class FleetRegistryFailure {
    LOCKED,
    UNAUTHENTICATED,
}

internal data class FleetRepairCopy(
    val title: String,
    val detail: String,
    val action: String,
    val resetAction: String? = null,
)

internal data class FleetResetCopy(
    val title: String,
    val detail: String,
    val acknowledgement: String,
    val after: String,
    val dismiss: String,
    val confirm: String,
)

internal fun fleetRepairCopy(failure: FleetRegistryFailure): FleetRepairCopy = when (failure) {
    FleetRegistryFailure.LOCKED -> FleetRepairCopy(
        title = "配对记录被设备锁封存",
        detail = "解锁后会自动恢复，无需修复。",
        action = "重试",
    )
    FleetRegistryFailure.UNAUTHENTICATED -> FleetRepairCopy(
        title = "配对记录无法认证",
        detail = "先重试读取本机配对。仍失败再重新配对——那会清除这台手机上的配对，电脑上的 Host 记录不会被撤销。",
        action = "重试",
        resetAction = "重新配对",
    )
}

internal fun fleetResetCopy(): FleetResetCopy = FleetResetCopy(
    title = "重新配对？",
    detail = "将永久删除这台手机上的设备身份、主机配对、缓存会话、草稿和待发送命令。不会撤销或删除电脑上的 Host 记录。",
    acknowledgement = "我知道本机离线数据和未完成操作无法恢复。",
    after = "重置后，在电脑上创建新的邀请，并核对新的 8 位配对码。",
    dismiss = "保留本机配对",
    confirm = "清除本机配对并重新开始",
)

@Composable
internal fun V2FleetRepair(
    failure: FleetRegistryFailure,
    onRetry: () -> Unit,
    onReset: (() -> Unit)? = null,
) {
    val v2 = LocalV2.current
    val copy = fleetRepairCopy(failure)
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    if (confirmReset && onReset != null && copy.resetAction != null) {
        V2FleetResetDialog(
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                onReset()
            },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(v2.bg)
            .statusBarsPadding()
            .padding(horizontal = 36.dp)
            .testTag("fleet-repair"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            copy.title,
            color = v2.tx,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            copy.detail,
            color = v2.tx2,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(v2.blue)
                .clickable(role = Role.Button, onClick = onRetry)
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .testTag("fleet-repair-retry"),
        ) {
            Text(
                copy.action,
                color = v2.bg,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (copy.resetAction != null && onReset != null) {
            Text(
                copy.resetAction,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { confirmReset = true }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .testTag("fleet-repair-reset"),
                color = v2.red,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun V2FleetResetDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val v2 = LocalV2.current
    val copy = fleetResetCopy()
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(copy.title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(copy.detail)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = acknowledged,
                            role = Role.Checkbox,
                            onValueChange = { acknowledged = it },
                        )
                        .semantics {
                            stateDescription = if (acknowledged) "已确认" else "未确认"
                        }
                        .testTag("fleet-repair-reset-ack")
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = null)
                    Text(copy.acknowledgement)
                }
                Text(
                    copy.after,
                    color = v2.tx2,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(copy.dismiss) }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = acknowledged,
                modifier = Modifier.testTag("fleet-repair-reset-confirm"),
                colors = ButtonDefaults.textButtonColors(contentColor = v2.red),
            ) {
                Text(copy.confirm)
            }
        },
    )
}
