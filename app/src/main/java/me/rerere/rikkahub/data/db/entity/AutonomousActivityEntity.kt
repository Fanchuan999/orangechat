package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "autonomous_activity_records",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["family", "created_at"]),
    ],
)
data class AutonomousActivityEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "family") val family: String,
    @ColumnInfo(name = "server_name") val serverName: String? = null,
    @ColumnInfo(name = "tool_name") val toolName: String? = null,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
