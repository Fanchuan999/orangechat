package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_29_30 : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `visitor_lounge_friends` (" +
                "`id` TEXT NOT NULL, `display_name` TEXT NOT NULL, `relationship` TEXT NOT NULL, " +
                "`endpoint` TEXT NOT NULL, `consent` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, `last_proactive_visit_at` INTEGER, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_visitor_lounge_friends_display_name` ON `visitor_lounge_friends` (`display_name`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `visitor_lounge_visits` (" +
                "`id` TEXT NOT NULL, `friend_id` TEXT NOT NULL, `source_conversation_id` TEXT, " +
                "`mode` TEXT NOT NULL, `status` TEXT NOT NULL, `topic` TEXT NOT NULL, " +
                "`summary` TEXT NOT NULL DEFAULT '', `started_at` INTEGER NOT NULL, `finished_at` INTEGER, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`friend_id`) REFERENCES `visitor_lounge_friends`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_visitor_lounge_visits_friend_id` ON `visitor_lounge_visits` (`friend_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_visitor_lounge_visits_source_conversation_id` ON `visitor_lounge_visits` (`source_conversation_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_visitor_lounge_visits_status` ON `visitor_lounge_visits` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_visitor_lounge_visits_started_at` ON `visitor_lounge_visits` (`started_at`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `visitor_lounge_messages` (" +
                "`id` TEXT NOT NULL, `visit_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`visit_id`) REFERENCES " +
                "`visitor_lounge_visits`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_visitor_lounge_messages_visit_id_created_at` ON `visitor_lounge_messages` (`visit_id`, `created_at`)")
    }
}
