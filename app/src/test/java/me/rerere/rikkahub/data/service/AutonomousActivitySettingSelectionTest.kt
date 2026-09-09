package me.rerere.rikkahub.data.service

import me.rerere.rikkahub.data.datastore.AutonomousActivitySetting
import me.rerere.rikkahub.data.datastore.AutonomousMcpToolPermission
import me.rerere.rikkahub.data.datastore.withAutonomousMcpToolPermission
import org.junit.Assert.assertEquals
import org.junit.Test

class AutonomousActivitySettingSelectionTest {
    @Test
    fun `enabling one MCP tool preserves existing explicit selections`() {
        val current = AutonomousActivitySetting(
            enabled = true,
            allowedMcpTools = listOf(
                AutonomousMcpToolPermission(serverId = "forum-a", toolName = "publish_post"),
            ),
        )

        val updated = current.withAutonomousMcpToolPermission(
            serverId = "forum-b",
            toolName = "like_post",
            selected = true,
        )

        assertEquals(
            listOf(
                AutonomousMcpToolPermission(serverId = "forum-a", toolName = "publish_post"),
                AutonomousMcpToolPermission(serverId = "forum-b", toolName = "like_post"),
            ),
            updated.normalizedAllowedMcpTools(),
        )
    }

    @Test
    fun `disabling one MCP tool leaves other selections including unavailable ones intact`() {
        val current = AutonomousActivitySetting(
            enabled = true,
            allowedMcpTools = listOf(
                AutonomousMcpToolPermission(serverId = "forum-a", toolName = "publish_post"),
                AutonomousMcpToolPermission(serverId = "forum-b", toolName = "like_post"),
            ),
        )

        val updated = current.withAutonomousMcpToolPermission(
            serverId = "forum-a",
            toolName = "publish_post",
            selected = false,
        )

        assertEquals(
            listOf(AutonomousMcpToolPermission(serverId = "forum-b", toolName = "like_post")),
            updated.normalizedAllowedMcpTools(),
        )
    }
}
