package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.plugin.provider.PluginToolProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ManualToolPickerInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tappingPluginAddsOnlyThatPluginToManualSelection() {
        var updated = Assistant()
        val lutoId = Uuid.parse("00000000-0000-0000-0000-000000000001")

        composeRule.setContent {
            MaterialTheme {
                ManualToolPicker(
                    assistant = Assistant(manualToolSelectionEnabled = true),
                    servers = listOf(
                        McpServerConfig.SseTransportServer(
                            id = lutoId,
                            commonOptions = McpCommonOptions(name = "Luto Forum"),
                        )
                    ),
                    plugins = listOf(
                        PluginToolProvider.PluginToolDetail(
                            pluginId = "com.daddy.luto",
                            pluginName = "Luto Forum",
                            toolCount = 2,
                            toolNames = listOf("search", "open"),
                        )
                    ),
                    onUpdateAssistant = { updated = it },
                    modifier = Modifier.height(300.dp),
                )
            }
        }

        composeRule.onNodeWithContentDescription("toggle-plugin-com.daddy.luto").performClick()

        assertTrue("com.daddy.luto" in updated.manualToolPluginIds)
    }
}
