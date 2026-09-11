package me.rerere.rikkahub.data.service

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.AutonomousActivitySetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousActivityPolicyTest {
    @Test
    fun `every tool from an enabled MCP server is available by default`() {
        val setting = AutonomousActivitySetting(enabled = true)

        assertTrue(AutonomousActivityPolicy.allowsMcpTool(setting, "forum-server", "publish_post"))
        assertTrue(AutonomousActivityPolicy.allowsMcpTool(setting, "forum-server", "like_post"))
        assertTrue(AutonomousActivityPolicy.allowsMcpTool(setting, "another-server", "publish_post"))
    }

    @Test
    fun `disabled master switch blocks selected MCP tool`() {
        val setting = AutonomousActivitySetting(enabled = false)

        assertFalse(AutonomousActivityPolicy.allowsMcpTool(setting, "forum-server", "publish_post"))
    }

    @Test
    fun `service disabled in saved settings blocks every tool from that service`() {
        val setting = Json { ignoreUnknownKeys = true }.decodeFromString<AutonomousActivitySetting>(
            """{"enabled":true,"disabledMcpServerIds":[" social-server "]}""",
        )

        assertFalse(AutonomousActivityPolicy.allowsMcpTool(setting, "social-server", "publish_post"))
        assertFalse(AutonomousActivityPolicy.allowsMcpTool(setting, "social-server", "like_post"))
        assertTrue(AutonomousActivityPolicy.allowsMcpTool(setting, "notes-server", "write_note"))
    }

    @Test
    fun `first idle activity family prevents a different family in same opportunity`() {
        val guard = AutonomousActivityFamilyGuard()

        assertTrue(guard.tryClaim(AutonomousActivityFamily.FORUM))
        assertTrue(guard.tryClaim(AutonomousActivityFamily.FORUM))
        assertFalse(guard.tryClaim(AutonomousActivityFamily.WEB))
    }

    @Test
    fun `blank server or tool name is never allowed`() {
        val setting = AutonomousActivitySetting(enabled = true)

        assertFalse(AutonomousActivityPolicy.allowsMcpTool(setting, "", "publish_post"))
        assertFalse(AutonomousActivityPolicy.allowsMcpTool(setting, "forum-server", " "))
    }
}
