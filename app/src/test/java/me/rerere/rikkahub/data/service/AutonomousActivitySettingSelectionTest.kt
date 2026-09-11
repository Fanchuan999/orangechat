package me.rerere.rikkahub.data.service

import me.rerere.rikkahub.data.datastore.AutonomousActivitySetting
import me.rerere.rikkahub.data.datastore.withAutonomousMcpServerEnabled
import org.junit.Assert.assertEquals
import org.junit.Test

class AutonomousActivitySettingSelectionTest {
    @Test
    fun `disabling one MCP service preserves every other service default access`() {
        val updated = AutonomousActivitySetting(enabled = true).withAutonomousMcpServerEnabled(
            serverId = "forum-a",
            enabled = false,
        )

        assertEquals(listOf("forum-a"), updated.normalizedDisabledMcpServerIds())
    }

    @Test
    fun `reenabling one MCP service leaves other disabled services intact`() {
        val current = AutonomousActivitySetting(
            enabled = true,
            disabledMcpServerIds = listOf("forum-a", "forum-b"),
        )

        val updated = current.withAutonomousMcpServerEnabled(
            serverId = "forum-a",
            enabled = true,
        )

        assertEquals(listOf("forum-b"), updated.normalizedDisabledMcpServerIds())
    }
}
