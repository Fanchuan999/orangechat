package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartToolRouterTest {
    @Test
    fun `disabled throttling and one-send override keep legacy full surface`() {
        assertTrue(
            SmartToolRouter.select(
                message = "随便聊聊",
                throttlingEnabled = false,
                forceFullTools = false,
            ).includeAllTools,
        )
        assertTrue(
            SmartToolRouter.select(
                message = "随便聊聊",
                throttlingEnabled = true,
                forceFullTools = true,
            ).includeAllTools,
        )
    }

    @Test
    fun `plain chat keeps core memory MCP tools without optional plugin tools`() {
        val selection = SmartToolRouter.select(
            message = "今天有点累，想和你聊聊天。",
            throttlingEnabled = true,
            forceFullTools = false,
        )

        assertFalse(selection.includeAllTools)
        assertTrue(selection.allowsMcpTool("breath"))
        assertTrue(selection.allowsMcpTool("breath_search"))
        assertTrue(selection.allowsMcpTool("hold"))
        assertFalse(selection.allowsMcpTool("get_battery"))
        assertFalse(selection.allowsPlugin("com.daddy.weather"))
    }

    @Test
    fun `health wording enables health data tools`() {
        val selection = SmartToolRouter.select(
            message = "我昨晚睡眠和心率怎么样？",
            throttlingEnabled = true,
            forceFullTools = false,
        )

        assertTrue(selection.allowsMcpTool("get_health_data"))
        assertTrue(selection.allowsMcpTool("get_battery"))
        assertFalse(selection.allowsMcpTool("route_plan"))
    }

    @Test
    fun `route wording enables map tools`() {
        val selection = SmartToolRouter.select(
            message = "帮我规划从南山到宝安机场的路线。",
            throttlingEnabled = true,
            forceFullTools = false,
        )

        assertTrue(selection.allowsMcpTool("route_plan"))
        assertTrue(selection.allowsMcpTool("search_place"))
        assertFalse(selection.allowsMcpTool("get_health_data"))
    }

    @Test
    fun `reading wording enables only the co-reading plugin`() {
        val selection = SmartToolRouter.select(
            message = "我们继续共读这本书，聊聊这一段。",
            throttlingEnabled = true,
            forceFullTools = false,
        )

        assertTrue(selection.allowsPlugin("com.daddy.yingfan.coreading"))
        assertFalse(selection.allowsPlugin("com.daddy.weather"))
    }

    @Test
    fun `multiple scenes are unioned while unknown plugins stay excluded`() {
        val selection = SmartToolRouter.select(
            message = "看完书后提醒我喝水，再查一下明天的天气。",
            throttlingEnabled = true,
            forceFullTools = false,
        )

        assertTrue(selection.allowsPlugin("com.daddy.yingfan.coreading"))
        assertTrue(selection.allowsPlugin("com.daddy.yingfan.water"))
        assertTrue(selection.allowsPlugin("com.daddy.weather"))
        assertFalse(selection.allowsPlugin("com.example.unknown"))
    }
}
