/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.HarnessStatus
import me.rerere.rikkahub.data.sync.companion.HarnessScripts
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun HarnessPage(
    onOpenWorkspace: () -> Unit,
    vm: HarnessVM = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("DeepSeek Harness") },
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
            if (state.isBusy) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "正在${state.busyAction}…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item {
                StatusCard(state)
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("自动复活", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "崩溃后约 3 秒拉起，Android 后台每 15 分钟兜底检查。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = state.autoKeepRunning,
                                onCheckedChange = vm::setAutoKeepRunning,
                                enabled = !state.isBusy,
                            )
                        }

                        HorizontalDivider()

                        Button(
                            onClick = onOpenWorkspace,
                            enabled = !state.isBusy && state.snapshot.status == HarnessStatus.RUNNING,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("打开 Harness 工作台")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = vm::install,
                                enabled = !state.isBusy,
                                modifier = Modifier.weight(1f),
                            ) { Text("安装 / 修复") }
                            FilledTonalButton(
                                onClick = vm::refresh,
                                enabled = !state.isBusy,
                                modifier = Modifier.weight(1f),
                            ) { Text("刷新") }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = vm::start,
                                enabled = !state.isBusy && state.snapshot.status != HarnessStatus.RUNNING,
                                modifier = Modifier.weight(1f),
                            ) { Text("启动") }
                            FilledTonalButton(
                                onClick = vm::restart,
                                enabled = !state.isBusy && state.snapshot.status != HarnessStatus.NOT_INSTALLED,
                                modifier = Modifier.weight(1f),
                            ) { Text("重启") }
                            FilledTonalButton(
                                onClick = vm::stop,
                                enabled = !state.isBusy && state.snapshot.status != HarnessStatus.NOT_INSTALLED,
                                modifier = Modifier.weight(1f),
                            ) { Text("停止") }
                        }
                    }
                }
            }

            if (!state.error.isNullOrBlank() || !state.message.isNullOrBlank()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = state.error ?: state.message.orEmpty(),
                                color = if (state.error != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            TextButton(onClick = vm::clearNotice) { Text("知道了") }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("脱敏日志", style = MaterialTheme.typography.titleMedium)
                        SelectionContainer {
                            Text(
                                text = state.snapshot.logTail.ifBlank { "暂无日志。" },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            item {
                FilledTonalButton(
                    onClick = vm::copyFallbackCommand,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("复制 Termux 备用安装命令")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: HarnessUiState) {
    val statusText = when (state.snapshot.status) {
        HarnessStatus.NOT_INSTALLED -> "未安装"
        HarnessStatus.STOPPED -> "已停止"
        HarnessStatus.RUNNING -> "运行中"
        HarnessStatus.ERROR -> "异常"
    }
    val statusColor = when (state.snapshot.status) {
        HarnessStatus.RUNNING -> MaterialTheme.colorScheme.primary
        HarnessStatus.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(statusText, color = statusColor, style = MaterialTheme.typography.headlineSmall)
            Text("固定版本：${HarnessScripts.VERSION}")
            Text("已安装版本：${state.snapshot.installedVersion.ifBlank { "—" }}")
            Text("本机端口：127.0.0.1:3080")
            if (state.snapshot.detail.isNotBlank()) {
                Text(
                    state.snapshot.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
