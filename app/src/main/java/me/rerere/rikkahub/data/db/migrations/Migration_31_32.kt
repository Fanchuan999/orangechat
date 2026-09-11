package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Removes the retired dedicated Visitor Lounge records. Configured MCP servers are unaffected. */
object Migration_31_32 : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM `autonomous_activity_records` WHERE `family` = 'VISITOR_LOUNGE'")
        db.execSQL("DROP TABLE IF EXISTS `visitor_lounge_messages`")
        db.execSQL("DROP TABLE IF EXISTS `visitor_lounge_visits`")
        db.execSQL("DROP TABLE IF EXISTS `visitor_lounge_friends`")
    }
}
