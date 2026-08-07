package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.plugin.provider.PluginToolProvider

@Composable
internal fun ManualToolPickerSheet(
    assistant: Assistant,
    servers: List<McpServerConfig>,
    plugins: List<PluginToolProvider.PluginToolDetail>,
    onUpdateAssistant: (Assistant) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "选择要发送给 Daddy 的工具",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "只会把勾选项目的工具与提示词发送给模型；不勾选 Ombre 时，它也不会读取或写入记忆。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ManualToolPicker(
                assistant = assistant,
                servers = servers,
                plugins = plugins,
                onUpdateAssistant = onUpdateAssistant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun ManualToolPicker(
    assistant: Assistant,
    servers: List<McpServerConfig>,
    plugins: List<PluginToolProvider.PluginToolDetail>,
    onUpdateAssistant: (Assistant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toolPlugins = plugins
        .filter { it.toolCount > 0 }
        .sortedBy { it.pluginName.lowercase() }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                text = "MCP 服务",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp, start = 16.dp),
            )
        }
        if (servers.isEmpty()) {
            item {
                Text(
                    text = "还没有配置 MCP 服务。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            items(
                items = servers.sortedBy { it.commonOptions.name.lowercase() },
                key = { it.id.toString() },
            ) { server ->
                val selected = server.id in assistant.manualToolMcpServerIds
                val available = server.commonOptions.enable
                val name = server.commonOptions.name.ifBlank { "未命名 MCP 服务" }
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = {
                        Text(
                            if (available) {
                                "${server.commonOptions.tools.count { it.enable }} 个已启用工具"
                            } else {
                                "该服务本身未启用，请先在 MCP 设置中开启"
                            }
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = selected,
                            enabled = available,
                            onCheckedChange = { onUpdateAssistant(assistant.toggleManualMcp(server.id)) },
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "toggle-mcp-${server.id}" }
                        .clickable(enabled = available) {
                            onUpdateAssistant(assistant.toggleManualMcp(server.id))
                        },
                )
            }
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item {
            Text(
                text = "工具插件",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        if (toolPlugins.isEmpty()) {
            item {
                Text(
                    text = "当前没有已加载的工具插件。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            items(
                items = toolPlugins,
                key = { it.pluginId },
            ) { plugin ->
                val selected = plugin.pluginId in assistant.manualToolPluginIds
                ListItem(
                    headlineContent = { Text(plugin.pluginName) },
                    supportingContent = { Text("${plugin.toolCount} 个工具") },
                    trailingContent = {
                        Switch(
                            checked = selected,
                            onCheckedChange = { onUpdateAssistant(assistant.toggleManualPlugin(plugin.pluginId)) },
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "toggle-plugin-${plugin.pluginId}" }
                        .clickable { onUpdateAssistant(assistant.toggleManualPlugin(plugin.pluginId)) },
                )
            }
        }
    }
}

private fun Assistant.toggleManualMcp(serverId: kotlin.uuid.Uuid): Assistant = copy(
    manualToolMcpServerIds = manualToolMcpServerIds.toggle(serverId),
)

private fun Assistant.toggleManualPlugin(pluginId: String): Assistant = copy(
    manualToolPluginIds = manualToolPluginIds.toggle(pluginId),
)

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
