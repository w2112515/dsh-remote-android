package dev.dshremote.gate0c.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun V2NotificationsPanel(
    notifications: List<V2Notification>,
    onOpen: (V2Notification) -> Unit,
) {
    val v2 = LocalV2.current
    if (notifications.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp, vertical = 54.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("☾", color = v2.tx3, fontSize = 26.sp)
            Text("没有通知", color = v2.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("SILENCE IS GOLDEN", color = v2.tx3, fontSize = 11.sp)
        }
        return
    }
    // 原型 P7 N2：扁平行 + 分隔线（去卡片化），方形图标徽章，红色未读点，
    // mono 时间。计数与「全部已读」由 V2App 头部承载。
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(notifications, key = V2Notification::id) { notification ->
            val tint = when (notification.kind) {
                V2NotificationKind.APPROVAL_ARRIVED -> v2.amber
                V2NotificationKind.APPROVAL_SETTLED -> v2.green
                V2NotificationKind.INPUT_WAITING -> v2.cyan
                V2NotificationKind.CONNECTION_LOST -> v2.red
                V2NotificationKind.REPAIR_REQUIRED -> v2.red
                V2NotificationKind.ARTIFACT_REGISTERED -> v2.blue
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpen(notification) }
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(tint.copy(alpha = 0.13f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(notification.kind.icon, color = tint, fontSize = 12.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        notification.text,
                        color = if (notification.unread) v2.tx else v2.tx2,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontWeight = if (notification.unread) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        clockTimeZh(notification.timeMs),
                        color = v2.tx3,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (notification.unread) {
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .size(6.dp)
                            .background(v2.red, CircleShape),
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(1.dp)
                    .background(v2.line),
            )
        }
    }
}
