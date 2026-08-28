/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.codehut

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.DateFormat
import java.util.Date
import me.rerere.rikkahub.data.codehut.HarnessImagePolicy
import me.rerere.rikkahub.data.codehut.HarnessInboxTask
import me.rerere.rikkahub.data.codehut.HarnessInboxTaskState
import me.rerere.rikkahub.data.codehut.redactCodeHutUiText
import me.rerere.rikkahub.data.pcbridge.PcBridgeUiPolicy
import me.rerere.rikkahub.data.pcbridge.PcBridgeUiState
import me.rerere.rikkahub.data.pcbridge.PcBridgeTaskCard
import me.rerere.rikkahub.data.pcbridge.PcBridgeTaskCardState
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
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.feedback?.id) {
        state.feedback?.let { feedback ->
            toaster.show(
                feedback.message,
                type = if (feedback.isError) ToastType.Error else ToastType.Success,
            )
            vm.consumeFeedback(feedback.id)
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use(::readImageAtMostHarnessLimit)
            }
            if (bytes == null) {
                toaster.show("无法读取这张图片，或图片超过 5 MB。")
            } else {
                vm.sendImageToInbox(context.contentResolver.getType(uri), bytes)
            }
        }
    }
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("代码小屋") },
                navigationIcon = { BackButton() },
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                colors = CustomColors.topBarColors,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { InboxBoardCard(state.activeInboxTasks, state.inboxNotice, vm::refreshInbox) }
            if (state.pcBridge is PcBridgeUiState.Paired) {
                item { PcTaskBoardCard(state.activePcTasks, vm::refreshPcTaskBoard) }
            }
            item {
                PcBridgeCard(
                    state = state.pcBridge,
                    policy = state.pcBridgePolicy,
                    onInvitationChange = vm::updatePcInvitation,
                    onConfirmPairing = { vm.confirmPcPairing() },
                    onRefresh = { vm.refreshPcBridge() },
                    onUnlink = { vm.unlinkPcBridge() },
                    onAbandonPendingPairing = { vm.abandonPendingPcBridge() },
                    onForgetUnavailableConfirmedPairing = { vm.forgetUnavailableConfirmedPcBridge() },
                )
            }
            item {
                WorkbenchCard(
                    state.workbench,
                    onOpenWorkbench,
                    onOpenHarnessSettings,
                    vm::refreshHarness,
                    onSelectImage = { imagePicker.launch("image/*") },
                )
            }
            item { EnvironmentCard(state.environment) }
            item { PermissionCard(onOpenPermissionSettings) }
            if (!state.error.isNullOrBlank()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                redactCodeHutUiText(state.error.orEmpty()),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = vm::clearError) { Text("知道了") }
                        }
                    }
                }
            }
        }
    }
}

private fun readImageAtMostHarnessLimit(input: InputStream): ByteArray? {
    val output = ByteArrayOutputStream()
    var total = 0
    while (true) {
        val remaining = HarnessImagePolicy.MAX_BYTES + 1 - total
        val buffer = ByteArray(minOf(DEFAULT_BUFFER_SIZE, remaining))
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > HarnessImagePolicy.MAX_BYTES) return null
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

@Composable
private fun InboxBoardCard(tasks: List<HarnessInboxTask>, notice: String?, onRefresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("手机 Harness 任务", style = MaterialTheme.typography.titleLarge)
            Text(
                "这里仅显示待执行或执行中的收件箱任务；已完成和失败的任务会自动从首页消失。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tasks.isEmpty()) {
                Text(
                    notice ?: "目前没有进行中的工作。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                tasks.groupBy { it.state }.forEach { (taskState, group) ->
                    Text(taskState.label(), style = MaterialTheme.typography.titleMedium)
                    group.forEach { task ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(task.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${task.taskId} · ${task.createdAt.ifBlank { "等待工作台记录时间" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (task.statusSummary.isNotBlank()) {
                                Text(
                                    redactCodeHutUiText(task.statusSummary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            FilledTonalButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("刷新任务状态") }
        }
    }
}

@Composable
private fun PcTaskBoardCard(tasks: List<PcBridgeTaskCard>, onRefresh: () -> Unit) {
    val activeTasks = tasks.filter { it.state !in setOf(PcBridgeTaskCardState.COMPLETE, PcBridgeTaskCardState.FAILED, PcBridgeTaskCardState.CANCELED) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("电脑任务", style = MaterialTheme.typography.titleLarge)
            Text(
                "任务内容以加密信封发送给已配对电脑；这里仅保留进行中的简短状态，完成或失败后会从首页消失。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (activeTasks.isEmpty()) {
                Text(
                    "目前没有进行中的电脑任务。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                activeTasks.groupBy { it.state }.forEach { (taskState, group) ->
                    Text(taskState.label(), style = MaterialTheme.typography.titleMedium)
                    group.forEach { task ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(task.brief.take(160), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                task.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            FilledTonalButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("刷新电脑任务") }
        }
    }
}

private fun PcBridgeTaskCardState.label(): String = when (this) {
    PcBridgeTaskCardState.AWAITING_PC -> "待电脑接收"
    PcBridgeTaskCardState.RECEIVED -> "已接收"
    PcBridgeTaskCardState.RUNNING -> "执行中"
    PcBridgeTaskCardState.WAITING_PHONE_APPROVAL -> "等待你确认"
    PcBridgeTaskCardState.COMPLETE,
    PcBridgeTaskCardState.FAILED,
    PcBridgeTaskCardState.CANCELED -> "已结束"
}

private fun HarnessInboxTaskState.label(): String = when (this) {
    HarnessInboxTaskState.QUEUED -> "待执行"
    HarnessInboxTaskState.RUNNING -> "执行中"
    HarnessInboxTaskState.UNKNOWN -> "状态待确认"
}

@Composable
internal fun PcBridgeCard(
    state: PcBridgeUiState,
    policy: PcBridgeUiPolicy,
    onInvitationChange: (String) -> Unit,
    onConfirmPairing: () -> Unit,
    onRefresh: () -> Unit,
    onUnlink: () -> Unit,
    onAbandonPendingPairing: () -> Unit,
    onForgetUnavailableConfirmedPairing: () -> Unit,
) {
    var invitation by remember(state is PcBridgeUiState.Unpaired) { mutableStateOf("") }
    var unlinkConfirmationVisible by remember(state is PcBridgeUiState.Paired) { mutableStateOf(false) }
    var abandonConfirmationVisible by remember(state is PcBridgeUiState.PendingRecovery) { mutableStateOf(false) }
    var forgetConfirmationVisible by remember(state is PcBridgeUiState.ConfirmedRecovery) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(policy.title, style = MaterialTheme.typography.titleLarge)
            Text(policy.status, style = MaterialTheme.typography.titleMedium)
            Text(
                policy.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state) {
                PcBridgeUiState.Unpaired -> {
                    Text(
                        "请先在电脑上运行 daddy-pc-bridge pair-pc，然后将电脑邀请粘贴到这里。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = invitation,
                        onValueChange = {
                            invitation = it
                            onInvitationChange(it)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("pc-bridge-invitation"),
                        label = { Text("粘贴电脑邀请") },
                        minLines = 2,
                    )
                }

                is PcBridgeUiState.InvitationDraft -> {
                    state.preview?.let { preview ->
                        Text("电脑地址与有效期：$preview", style = MaterialTheme.typography.bodyMedium)
                    }
                    state.error?.let {
                        Text(
                            "邀请码无效或已过期。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = onConfirmPairing,
                        enabled = state.preview != null && state.error == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(policy.primaryAction ?: "确认连接")
                    }
                    TextButton(
                        onClick = {
                            invitation = ""
                            onInvitationChange("")
                        },
                    ) {
                        Text("重新粘贴邀请")
                    }
                }

                PcBridgeUiState.Pairing -> Unit

                is PcBridgeUiState.Paired -> {
                    Text(state.deviceLabel, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "上次刷新：${DateFormat.getDateTimeInstance().format(Date(state.refreshedAtEpochMillis))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                        Text(policy.primaryAction ?: "刷新状态")
                    }
                    TextButton(onClick = { unlinkConfirmationVisible = true }) {
                        Text(policy.dangerAction ?: "解除配对")
                    }
                }

                PcBridgeUiState.PendingRecovery -> {
                    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                        Text(policy.primaryAction ?: "刷新状态")
                    }
                    TextButton(onClick = { abandonConfirmationVisible = true }) {
                        Text(policy.dangerAction ?: "放弃本机待恢复配对")
                    }
                }

                PcBridgeUiState.ConfirmedRecovery -> {
                    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                        Text(policy.primaryAction ?: "刷新状态")
                    }
                    TextButton(onClick = { forgetConfirmationVisible = true }) {
                        Text(policy.dangerAction ?: "忘记本机电脑配对")
                    }
                }

                is PcBridgeUiState.Unavailable -> {
                    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                        Text(policy.primaryAction ?: "刷新状态")
                    }
                }
            }
        }
    }

    if (unlinkConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { unlinkConfirmationVisible = false },
            title = { Text("解除电脑配对？") },
            text = { Text("解除后，这台手机将不能再向该电脑发送命令。") },
            confirmButton = {
                Button(
                    onClick = {
                        unlinkConfirmationVisible = false
                        onUnlink()
                    },
                ) {
                    Text("确认解除配对")
                }
            },
            dismissButton = {
                TextButton(onClick = { unlinkConfirmationVisible = false }) { Text("取消") }
            },
        )
    }

    if (abandonConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { abandonConfirmationVisible = false },
            title = { Text("放弃本机待恢复配对？") },
            text = {
                Text(
                    "这只会清除手机本地保存的待恢复配对，不会联系电脑或服务器。极少数情况下，" +
                        "电脑端可能仍保留旧配对；之后重新连接即可。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        abandonConfirmationVisible = false
                        onAbandonPendingPairing()
                    },
                ) {
                    Text("确认放弃本机配对")
                }
            },
            dismissButton = {
                TextButton(onClick = { abandonConfirmationVisible = false }) { Text("取消") }
            },
        )
    }

    if (forgetConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { forgetConfirmationVisible = false },
            title = { Text("忘记本机电脑配对？") },
            text = {
                Text(
                    "这只会清除手机本地保存的电脑配对，不会联系电脑或服务器。远端配对可能仍存在；" +
                        "重新配对前，请先在固定 PC 工作台清理。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        forgetConfirmationVisible = false
                        onForgetUnavailableConfirmedPairing()
                    },
                ) {
                    Text("确认忘记本机配对")
                }
            },
            dismissButton = {
                TextButton(onClick = { forgetConfirmationVisible = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun WorkbenchCard(
    presentation: CodeHutWorkbenchPresentation,
    onOpenWorkbench: () -> Unit,
    onOpenHarnessSettings: () -> Unit,
    onRefresh: () -> Unit,
    onSelectImage: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("工作台", style = MaterialTheme.typography.titleLarge)
            Text(
                "进入完整 Harness 工作台，可查看收件箱过程、处理结果和需要你确认的危险操作。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("当前服务：${presentation.status}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onOpenWorkbench, enabled = presentation.canOpen, modifier = Modifier.weight(1f)) {
                    Text(presentation.actionLabel)
                }
                FilledTonalButton(onClick = onOpenHarnessSettings, modifier = Modifier.weight(1f)) { Text("Harness 设置") }
                FilledTonalButton(onClick = onRefresh) { Text("刷新") }
            }
            FilledTonalButton(onClick = onSelectImage, modifier = Modifier.fillMaxWidth()) {
                Text("发送图片到 Harness 收件箱")
            }
            Text(
                "Harness 当前只支持 PNG、JPEG、WebP、GIF 图片（单张不超过 5 MB）；普通文件请在工作台用文件工具处理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EnvironmentCard(environment: CodeHutEnvironmentPresentation) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("环境", style = MaterialTheme.typography.titleLarge)
            environment.items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(onOpenPermissionSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("权限设置", style = MaterialTheme.typography.titleLarge)
            Text(
                "安全确认由 Harness 风险门执行。这里不会扩大工作台权限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onOpenPermissionSettings, modifier = Modifier.fillMaxWidth()) {
                Text("打开权限入口")
            }
        }
    }
}
