package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_30_31 : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `autonomous_activity_records` (" +
                "`id` TEXT NOT NULL, `family` TEXT NOT NULL, `server_name` TEXT, " +
                "`tool_name` TEXT, `status` TEXT NOT NULL, `summary` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_autonomous_activity_records_created_at` " +
                "ON `autonomous_activity_records` (`created_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_autonomous_activity_records_family_created_at` " +
                "ON `autonomous_activity_records` (`family`, `created_at`)",
        )
    }
}
