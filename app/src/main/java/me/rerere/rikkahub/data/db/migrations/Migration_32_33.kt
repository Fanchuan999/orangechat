package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds a local-only table for rolling conversation context digests. */
object Migration_32_33 : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `conversation_digest` (" +
                "`conversation_id` TEXT NOT NULL, `summary` TEXT NOT NULL, " +
                "`covered_message_count` INTEGER NOT NULL, `covered_prefix_key` TEXT NOT NULL, " +
                "`last_failed_batch_key` TEXT, `updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`conversation_id`), FOREIGN KEY(`conversation_id`) REFERENCES " +
                "`ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
    }
}
