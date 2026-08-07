package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SmartToolRouterTest {
    private val defaultServer = Uuid.parse("00000000-0000-0000-0000-000000000010")

    @Test
    fun `manual mode exposes only the chosen mcp server and plugin`() {
        val luto = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val ombre = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val selection = SmartToolRouter.select(
            message = "sleep",
            smartThrottlingEnabled = true,
            manualSelectionEnabled = true,
            normalMcpServerIds = setOf(ombre),
            manualMcpServerIds = setOf(luto),
            manualPluginIds = setOf("com.daddy.luto"),
        )

        assertTrue(selection.allowsMcpTool(luto, "forum_search"))
        assertFalse(selection.allowsMcpTool(ombre, "breath"))
        assertTrue(selection.allowsPlugin("com.daddy.luto"))
        assertFalse(selection.allowsPlugin("com.daddy.weather"))
    }

    @Test
    fun `disabled throttling keeps the full surface within selected servers`() {
        val unselectedServer = Uuid.parse("00000000-0000-0000-0000-000000000011")
        val selection = SmartToolRouter.select(
            message = "chat",
            smartThrottlingEnabled = false,
            manualSelectionEnabled = false,
            normalMcpServerIds = setOf(defaultServer),
            manualMcpServerIds = emptySet(),
            manualPluginIds = emptySet(),
        )

        assertTrue(selection.includeAllTools)
        assertTrue(selection.allowsMcpTool(defaultServer, "any_tool"))
        assertFalse(selection.allowsMcpTool(unselectedServer, "any_tool"))
        assertTrue(selection.allowsPlugin("com.daddy.weather"))
    }

    @Test
    fun `plain smart chat keeps core memory but excludes optional plugins`() {
        val selection = smartSelection("chat")

        assertFalse(selection.includeAllTools)
        assertTrue(selection.allowsMcpTool(defaultServer, "breath"))
        assertTrue(selection.allowsMcpTool(defaultServer, "breath_search"))
        assertTrue(selection.allowsMcpTool(defaultServer, "hold"))
        assertFalse(selection.allowsMcpTool(defaultServer, "get_battery"))
        assertFalse(selection.allowsPlugin("com.daddy.weather"))
    }

    @Test
    fun `health wording enables health tools only`() {
        val selection = smartSelection("sleep heart rate")

        assertTrue(selection.allowsMcpTool(defaultServer, "get_health_data"))
        assertTrue(selection.allowsMcpTool(defaultServer, "get_battery"))
        assertFalse(selection.allowsMcpTool(defaultServer, "route_plan"))
    }

    @Test
    fun `route wording enables map tools only`() {
        val selection = smartSelection("route navigation")

        assertTrue(selection.allowsMcpTool(defaultServer, "route_plan"))
        assertTrue(selection.allowsMcpTool(defaultServer, "search_place"))
        assertFalse(selection.allowsMcpTool(defaultServer, "get_health_data"))
    }

    @Test
    fun `reading wording enables only the co-reading plugin`() {
        val selection = smartSelection("\u5171\u8bfb\u8fd9\u672c\u4e66")

        assertTrue(selection.allowsPlugin("com.daddy.yingfan.coreading"))
        assertFalse(selection.allowsPlugin("com.daddy.weather"))
    }

    @Test
    fun `multiple scenes are unioned while unknown plugins stay excluded`() {
        val selection = smartSelection("\u5171\u8bfb\u540e\u63d0\u9192\u6211\u559d\u6c34\uff0c\u518d\u67e5\u5929\u6c14")

        assertTrue(selection.allowsPlugin("com.daddy.yingfan.coreading"))
        assertTrue(selection.allowsPlugin("com.daddy.yingfan.water"))
        assertTrue(selection.allowsPlugin("com.daddy.weather"))
        assertFalse(selection.allowsPlugin("com.example.unknown"))
    }

    private fun smartSelection(message: String) = SmartToolRouter.select(
        message = message,
        smartThrottlingEnabled = true,
        manualSelectionEnabled = false,
        normalMcpServerIds = setOf(defaultServer),
        manualMcpServerIds = emptySet(),
        manualPluginIds = emptySet(),
    )
}
