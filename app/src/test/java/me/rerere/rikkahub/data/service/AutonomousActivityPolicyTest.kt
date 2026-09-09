package me.rerere.rikkahub.data.service

import me.rerere.rikkahub.data.datastore.AutonomousActivitySetting
import me.rerere.rikkahub.data.datastore.AutonomousMcpToolPermission
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousActivityPolicyTest {
    @Test
    fun `only explicitly selected MCP tool is available for autonomous activity`() {
        val setting = AutonomousActivitySetting(
            enabled = true,
            allowedMcpTools = listOf(
                AutonomousMcpToolPermission(serverId = "forum-server", toolName = "publish_post"),
            ),
        )

        assertTrue(AutonomousActivityPolicy.allowsMcpTool(setting, "forum-server", "publish_post"))
        assertFalse(AutonomousActivityPolicy.allowsMcpTool(setting, "forum-server", "like_post"))
        assertFalse(AutonomousActivityPolicy.allowsMcpTool(setting, "another-server", "publish_post"))
    }

    @Test
    fun `disabled master switch blocks selected MCP tool`() {
        val setting = AutonomousActivitySetting(
            enabled = false,
            allowedMcpTools = listOf(
                AutonomousMcpToolPermission(serverId = "forum-server", toolName = "publish_post"),
            ),
        )

        assertFalse(AutonomousActivityPolicy.allowsMcpTool(setting, "forum-server", "publish_post"))
    }

    @Test
    fun `first idle activity family prevents a different family in same opportunity`() {
        val guard = AutonomousActivityFamilyGuard()

        assertTrue(guard.tryClaim(AutonomousActivityFamily.FORUM))
        assertTrue(guard.tryClaim(AutonomousActivityFamily.FORUM))
        assertFalse(guard.tryClaim(AutonomousActivityFamily.WEB))
        assertFalse(guard.tryClaim(AutonomousActivityFamily.VISITOR_LOUNGE))
    }

    @Test
    fun `malformed and duplicate permissions do not broaden access`() {
        val setting = AutonomousActivitySetting(
            enabled = true,
            allowedMcpTools = listOf(
                AutonomousMcpToolPermission(serverId = " forum-server ", toolName = " publish_post "),
                AutonomousMcpToolPermission(serverId = "forum-server", toolName = "publish_post"),
                AutonomousMcpToolPermission(serverId = "", toolName = "like_post"),
                AutonomousMcpToolPermission(serverId = "forum-server", toolName = " "),
            ),
        )

        assertTrue(AutonomousActivityPolicy.allowsMcpTool(setting, "forum-server", "publish_post"))
        assertFalse(AutonomousActivityPolicy.allowsMcpTool(setting, "forum-server", "like_post"))
        assertTrue(setting.normalizedAllowedMcpTools().size == 1)
    }
}
