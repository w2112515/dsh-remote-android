package dev.dshremote.gate0c.ui.v2

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import dev.dshremote.gate0c.transport.RemoteWorkspaceName
import dev.dshremote.gate0c.transport.WorkspaceProjection

internal data class CreateSessionChoice(
    val agentPreset: String? = null,
    val workspaceId: String? = null,
    val newWorkspaceName: String? = null,
)

private const val CREATE_PREFS = "dsh.remote.create"

internal fun lastWorkspaceId(context: Context, hostId: String): String? =
    context.getSharedPreferences(CREATE_PREFS, Context.MODE_PRIVATE)
        .getString("workspace:$hostId", null)

internal fun rememberWorkspaceId(context: Context, hostId: String, workspaceId: String) {
    context.getSharedPreferences(CREATE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString("workspace:$hostId", workspaceId)
        .apply()
}

@Composable
internal fun V2CreateProjectSheet(
    workspaces: List<WorkspaceProjection>,
    lastWorkspaceId: String?,
    onPickExisting: (WorkspaceProjection) -> Unit,
    onStartNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val v2 = LocalV2.current
    V2Sheet(
        title = "新建会话 · 选择项目",
        subtitle = "NEW SESSION · PICK PROJECT",
        onDismiss = onDismiss,
    ) {
        Text(
            "选已有项目，或在其中一个下面新建文件夹。完整路径不会离开电脑。",
            color = v2.tx2,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        workspaces.forEach { workspace ->
            V2ProjectFilterRow(
                label = workspace.label,
                detail = if (workspace.workspaceId == lastWorkspaceId) "上次使用" else "已有项目",
                selected = workspace.workspaceId == lastWorkspaceId,
                onClick = { onPickExisting(workspace) },
            )
        }
        V2ProjectFilterRow(
            label = "新建项目",
            detail = if (workspaces.size == 1) {
                "在「${workspaces.single().label}」下建文件夹"
            } else {
                "先选父项目，再输入文件夹名"
            },
            selected = false,
            onClick = onStartNew,
        )
    }
}

@Composable
internal fun V2CreateProjectParentSheet(
    workspaces: List<WorkspaceProjection>,
    onPick: (WorkspaceProjection) -> Unit,
    onDismiss: () -> Unit,
) {
    V2Sheet(
        title = "新建项目 · 选择父项目",
        subtitle = "NEW PROJECT · PICK PARENT",
        onDismiss = onDismiss,
    ) {
        workspaces.forEach { workspace ->
            V2ProjectFilterRow(
                label = workspace.label,
                detail = "新文件夹建在这里下面",
                selected = false,
                onClick = { onPick(workspace) },
            )
        }
    }
}

@Composable
internal fun V2CreateProjectNameSheet(
    parent: WorkspaceProjection,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val v2 = LocalV2.current
    var name by rememberSaveable { mutableStateOf("") }
    val sanitized = RemoteWorkspaceName.sanitize(name)
    V2Sheet(
        title = "新建项目",
        subtitle = "NEW PROJECT · ${parent.label}",
        onDismiss = onDismiss,
        actions = {
            TextButton(
                onClick = { sanitized?.let(onConfirm) },
                enabled = sanitized != null,
                modifier = Modifier.testTag("create-project-confirm"),
            ) { Text("创建") }
        },
    ) {
        Text(
            "新文件夹会建在「${parent.label}」下面，不是完整路径，也不是手机本地目录。",
            color = v2.tx2,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(64) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("create-project-name"),
            singleLine = true,
            placeholder = { Text("文件夹名", color = v2.tx3, fontSize = 12.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = v2.blue,
                unfocusedBorderColor = v2.line,
            ),
        )
    }
}
