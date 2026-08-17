/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Assistant

@Composable
internal fun ToolThrottleSheet(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
    onOpenManualPicker: () -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = assistant.toolThrottleMode()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("工具节流", style = MaterialTheme.typography.titleLarge)
            Text(
                "只控制每次发送给模型的 MCP 和工具插件说明；聊天上下文、Ombre 记忆、世界书与已勾选清单都不会被清空。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            ThrottleModeRow(
                title = "关闭",
                description = "发送当前助手启用的全部 MCP 与工具插件。",
                checked = selected == ToolThrottleMode.OFF,
                onClick = { onUpdateAssistant(assistant.withToolThrottleMode(ToolThrottleMode.OFF)) },
            )
            ThrottleModeRow(
                title = "智能模式",
                description = "按本条内容只附加相关工具，适合日常省 token。",
                checked = selected == ToolThrottleMode.SMART,
                onClick = { onUpdateAssistant(assistant.withToolThrottleMode(ToolThrottleMode.SMART)) },
            )
            ThrottleModeRow(
                title = "手动模式",
                description = "只发送你在清单中勾选的 MCP 服务和工具插件。",
                checked = selected == ToolThrottleMode.MANUAL,
                onClick = { onUpdateAssistant(assistant.withToolThrottleMode(ToolThrottleMode.MANUAL)) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            FilledTonalButton(
                onClick = {
                    if (selected != ToolThrottleMode.MANUAL) {
                        onUpdateAssistant(assistant.withToolThrottleMode(ToolThrottleMode.MANUAL))
                    }
                    onOpenManualPicker()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("管理手动工具清单")
            }
        }
    }
}

@Composable
private fun ThrottleModeRow(
    title: String,
    description: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            RadioButton(
                selected = checked,
                onClick = onClick,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}
