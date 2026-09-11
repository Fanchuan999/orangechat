package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_31_32_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate31To32_removesRetiredLoungeDataAndKeepsGenericMcpActivityHistory() {
        helper.createDatabase(TEST_DB, 31).apply {
            execSQL(
                """
                INSERT INTO autonomous_activity_records
                    (id, family, server_name, tool_name, status, summary, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>("generic-mcp-record", "FORUM", "General MCP", "greet", "COMPLETED", "Kept", 1L),
            )
            execSQL(
                """
                INSERT INTO autonomous_activity_records
                    (id, family, server_name, tool_name, status, summary, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>("retired-lounge-record", "VISITOR_LOUNGE", "Old Lounge", "visit", "COMPLETED", "Removed", 2L),
            )
            execSQL(
                """
                INSERT INTO visitor_lounge_friends
                    (id, display_name, relationship, endpoint, consent, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>("friend-1", "Old Friend", "FRIEND", "https://example.invalid", "GRANTED", 1L, 1L),
            )
            execSQL(
                """
                INSERT INTO visitor_lounge_visits
                    (id, friend_id, mode, status, topic, started_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>("visit-1", "friend-1", "MANUAL", "COMPLETED", "Old visit", 1L),
            )
            execSQL(
                """
                INSERT INTO visitor_lounge_messages
                    (id, visit_id, kind, text, created_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>("message-1", "visit-1", "USER", "Old message", 1L),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(TEST_DB, 32, true, Migration_31_32)
        try {
            assertEquals(
                listOf("generic-mcp-record"),
                database.query("SELECT id FROM autonomous_activity_records ORDER BY id").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                },
            )
            assertFalse(tableExists(database, "visitor_lounge_friends"))
            assertFalse(tableExists(database, "visitor_lounge_visits"))
            assertFalse(tableExists(database, "visitor_lounge_messages"))
        } finally {
            database.close()
        }
    }

    private fun tableExists(database: SupportSQLiteDatabase, tableName: String): Boolean =
        database.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName),
        ).use { cursor -> cursor.moveToFirst() }

    private companion object {
        const val TEST_DB = "migration-31-32-test"
    }
}
