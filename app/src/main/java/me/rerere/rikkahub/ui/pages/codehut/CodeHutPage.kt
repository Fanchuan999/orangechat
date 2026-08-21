/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.codehut

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.codehut.CodeHutTask
import me.rerere.rikkahub.data.codehut.CodeHutTaskStatus
import me.rerere.rikkahub.data.codehut.redactCodeHutUiText
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun CodeHutPage(
    onOpenWorkbench: () -> Unit,
    onOpenHarnessSettings: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    vm: CodeHutVM = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("代码小屋") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TaskCard(
                    state = state,
                    onDraftChange = vm::updateDraft,
                    onPrepare = vm::prepareTaskForWorkbench,
                    onReset = vm::resetTask,
                    onCopyTask = { prompt ->
                        clipboard.setText(AnnotatedString(prompt))
                        toaster.show("任务内容已复制；请在完整工作台中手动粘贴运行。")
                    },
                    onOpenWorkbench = onOpenWorkbench,
                    onOpenHarnessSettings = onOpenHarnessSettings,
                )
            }

            item {
                WorkbenchCard(
                    presentation = state.workbench,
                    onOpenWorkbench = onOpenWorkbench,
                    onOpenHarnessSettings = onOpenHarnessSettings,
                    onRefresh = vm::refreshHarness,
                )
            }

            item {
                EnvironmentCard(environment = state.environment)
            }

            item {
                PermissionCard(onOpenPermissionSettings = onOpenPermissionSettings)
            }

            if (!state.error.isNullOrBlank()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = redactCodeHutUiText(state.error.orEmpty()),
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = vm::clearError) {
                                Text("知道了")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    state: CodeHutUiState,
    onDraftChange: (CodeHutTaskDraft) -> Unit,
    onPrepare: () -> Unit,
    onReset: () -> Unit,
    onCopyTask: (String) -> Unit,
    onOpenWorkbench: () -> Unit,
    onOpenHarnessSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("任务", style = MaterialTheme.typography.titleLarge)
            Text(
                "只把任务、显式文件、目录和约束交给 Harness；不会带入聊天人设、世界书、Ombre 或记忆。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val draft = state.draft
            OutlinedTextField(
                value = draft.taskText,
                onValueChange = { onDraftChange(draft.copy(taskText = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("任务") },
                minLines = 3,
            )
            OutlinedTextField(
                value = draft.selectedFilesText,
                onValueChange = { onDraftChange(draft.copy(selectedFilesText = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("显式文件，每行一个相对路径") },
                minLines = 2,
            )
            OutlinedTextField(
                value = draft.workingDirectory,
                onValueChange = { onDraftChange(draft.copy(workingDirectory = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("工作目录") },
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.constraintsText,
                onValueChange = { onDraftChange(draft.copy(constraintsText = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("约束，每行一个") },
                minLines = 2,
            )

            TaskResultCard(
                task = state.activeTask,
                workbench = state.workbench,
                onOpenWorkbench = onOpenWorkbench,
                onOpenHarnessSettings = onOpenHarnessSettings,
                onCopyTask = onCopyTask,
                onReset = onReset,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onPrepare,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("准备任务")
                }
                FilledTonalButton(
                    onClick = if (state.workbench.canOpen) onOpenWorkbench else onOpenHarnessSettings,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.workbench.canOpen) "去工作台继续" else "安装 / 启动 Harness")
                }
            }
            if (!state.workbench.canOpen) {
                Text(
                    state.workbench.guidance,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TaskResultCard(
    task: CodeHutTask?,
    workbench: CodeHutWorkbenchPresentation,
    onOpenWorkbench: () -> Unit,
    onOpenHarnessSettings: () -> Unit,
    onCopyTask: (String) -> Unit,
    onReset: () -> Unit,
) {
    if (task == null) {
        Text(
            "暂无任务。准备后不会自动提交；请复制任务内容，再在完整工作台中手动粘贴运行。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = when (task.status) {
                CodeHutTaskStatus.QUEUED -> "任务已排队"
                CodeHutTaskStatus.RUNNING -> "任务进行中"
                CodeHutTaskStatus.SUCCEEDED -> "任务结果"
                CodeHutTaskStatus.FAILED -> "任务失败"
                CodeHutTaskStatus.PREPARED -> "任务已准备（尚未提交）"
                CodeHutTaskStatus.UNSUPPORTED -> "需要转到完整工作台"
                CodeHutTaskStatus.DRAFT -> "草稿"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        SelectionContainer {
            Text(
                text = redactCodeHutUiText(task.result?.summary ?: task.ticket.prompt),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (task.result == null) FontFamily.Monospace else FontFamily.Default,
            )
        }
        val changedFiles = task.result?.changedFiles.orEmpty()
        if (changedFiles.isNotEmpty()) {
            Text(
                "改动文件：${redactCodeHutUiText(changedFiles.joinToString())}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val verification = task.result?.verification.orEmpty()
        if (verification.isNotBlank()) {
            Text(
                "验证：${redactCodeHutUiText(verification)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (task.status == CodeHutTaskStatus.PREPARED) {
                FilledTonalButton(onClick = { onCopyTask(task.ticket.prompt) }) {
                    Text(codeHutTaskActionLabel(task))
                }
            }
            if (task.status in setOf(CodeHutTaskStatus.PREPARED, CodeHutTaskStatus.UNSUPPORTED)) {
                FilledTonalButton(
                    onClick = if (workbench.canOpen) onOpenWorkbench else onOpenHarnessSettings,
                ) {
                    Text(if (workbench.canOpen) codeHutTaskActionLabel(task) else workbench.actionLabel)
                }
            }
            TextButton(onClick = onReset) {
                Text("清空")
            }
        }
    }
}

@Composable
private fun WorkbenchCard(
    presentation: CodeHutWorkbenchPresentation,
    onOpenWorkbench: () -> Unit,
    onOpenHarnessSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("工作台", style = MaterialTheme.typography.titleLarge)
            Text(
                "完整 Harness WebView 只在这里打开，用于深入操作和未文档化任务提交。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("当前服务：${presentation.status}", style = MaterialTheme.typography.bodyMedium)
            if (!presentation.canOpen) {
                Text(
                    presentation.guidance,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpenWorkbench,
                    enabled = presentation.canOpen,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(presentation.actionLabel)
                }
                FilledTonalButton(
                    onClick = onOpenHarnessSettings,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Harness 设置")
                }
                FilledTonalButton(onClick = onRefresh) {
                    Text("刷新")
                }
            }
        }
    }
}

@Composable
private fun EnvironmentCard(environment: CodeHutEnvironmentPresentation) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("环境", style = MaterialTheme.typography.titleLarge)
            environment.items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            item.status,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(onOpenPermissionSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("权限", style = MaterialTheme.typography.titleLarge)
            Text(
                "这里预留给后续权限策略合并。当前入口只跳到安全/权限相关设置，不在代码小屋里扩大权限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = onOpenPermissionSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开权限入口")
            }
        }
    }
}
