/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ai

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolThrottleModeTest {
    @Test
    fun booleansMapToOneStableMode() {
        assertEquals(ToolThrottleMode.OFF, Assistant().toolThrottleMode())
        assertEquals(
            ToolThrottleMode.SMART,
            Assistant(smartToolThrottlingEnabled = true).toolThrottleMode(),
        )
        assertEquals(
            ToolThrottleMode.MANUAL,
            Assistant(manualToolSelectionEnabled = true).toolThrottleMode(),
        )
        assertEquals(
            ToolThrottleMode.MANUAL,
            Assistant(
                smartToolThrottlingEnabled = true,
                manualToolSelectionEnabled = true,
            ).toolThrottleMode(),
        )
    }

    @Test
    fun transitionChangesOnlyModeBooleansAndPreservesSelections() {
        val mcpIds = setOf(Uuid.random(), Uuid.random())
        val pluginIds = setOf("memory", "forum")
        val assistant = Assistant(
            smartToolThrottlingEnabled = true,
            manualToolSelectionEnabled = true,
            manualToolMcpServerIds = mcpIds,
            manualToolPluginIds = pluginIds,
        )

        val smart = assistant.withToolThrottleMode(ToolThrottleMode.SMART)
        assertTrue(smart.smartToolThrottlingEnabled)
        assertFalse(smart.manualToolSelectionEnabled)
        assertSame(mcpIds, smart.manualToolMcpServerIds)
        assertSame(pluginIds, smart.manualToolPluginIds)

        val manual = smart.withToolThrottleMode(ToolThrottleMode.MANUAL)
        assertFalse(manual.smartToolThrottlingEnabled)
        assertTrue(manual.manualToolSelectionEnabled)
        assertSame(mcpIds, manual.manualToolMcpServerIds)
        assertSame(pluginIds, manual.manualToolPluginIds)

        val off = manual.withToolThrottleMode(ToolThrottleMode.OFF)
        assertFalse(off.smartToolThrottlingEnabled)
        assertFalse(off.manualToolSelectionEnabled)
        assertSame(mcpIds, off.manualToolMcpServerIds)
        assertSame(pluginIds, off.manualToolPluginIds)
    }
}
