package me.rerere.rikkahub.data.sync.companion

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SystemToolsSetting
import me.rerere.rikkahub.data.model.ExternalMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseBackupClientTest {
    @Test
    fun `collects only actively written configured tables`() {
        val settings = Settings(
            systemToolsSetting = SystemToolsSetting(
                supabaseEnabled = true,
                supabaseUrl = "https://project.supabase.co",
                supabaseApiKey = "device-key",
                supabaseTableName = "device_data",
            ),
            externalMemories = listOf(
                ExternalMemory(
                    enabled = true,
                    supabaseUrl = "https://project.supabase.co",
                    supabaseKey = "memory-key",
                    tableName = "chat_messages",
                    summariesTableName = "memory_summaries",
                    autoSaveMessages = true,
                    autoSaveDiarySummary = true,
                ),
                ExternalMemory(
                    enabled = false,
                    supabaseUrl = "https://project.supabase.co",
                    supabaseKey = "disabled-key",
                    tableName = "ignored_messages",
                ),
            ),
        )

        val targets = SupabaseBackupClient().collectTargets(settings)

        assertEquals(
            listOf("device_data", "chat_messages", "memory_summaries"),
            targets.map { it.tableName },
        )
        assertTrue(targets.all { it.baseUrl == "https://project.supabase.co" })
    }

    @Test
    fun `deduplicates a table shared by multiple configured memories`() {
        val settings = Settings(
            externalMemories = listOf(
                ExternalMemory(
                    supabaseUrl = "https://project.supabase.co/",
                    supabaseKey = "first-key",
                    tableName = "chat_messages",
                ),
                ExternalMemory(
                    supabaseUrl = "https://project.supabase.co",
                    supabaseKey = "second-key",
                    tableName = "chat_messages",
                ),
            ),
        )

        val targets = SupabaseBackupClient().collectTargets(settings)

        assertEquals(1, targets.size)
        assertEquals("first-key", targets.single().apiKey)
    }
}
