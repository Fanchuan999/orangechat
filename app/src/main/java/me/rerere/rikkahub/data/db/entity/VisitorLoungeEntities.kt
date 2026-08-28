package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "visitor_lounge_friends",
    indices = [Index(value = ["display_name"])],
)
data class VisitorLoungeFriendEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "relationship") val relationship: String,
    @ColumnInfo(name = "endpoint") val endpoint: String,
    @ColumnInfo(name = "consent") val consent: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_proactive_visit_at") val lastProactiveVisitAt: Long? = null,
)

@Entity(
    tableName = "visitor_lounge_visits",
    foreignKeys = [
        ForeignKey(
            entity = VisitorLoungeFriendEntity::class,
            parentColumns = ["id"],
            childColumns = ["friend_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["friend_id"]),
        Index(value = ["source_conversation_id"]),
        Index(value = ["status"]),
        Index(value = ["started_at"]),
    ],
)
data class VisitorLoungeVisitEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "friend_id") val friendId: String,
    @ColumnInfo(name = "source_conversation_id") val sourceConversationId: String?,
    @ColumnInfo(name = "mode") val mode: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "topic") val topic: String,
    @ColumnInfo(name = "summary", defaultValue = "''") val summary: String = "",
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
)

@Entity(
    tableName = "visitor_lounge_messages",
    foreignKeys = [
        ForeignKey(
            entity = VisitorLoungeVisitEntity::class,
            parentColumns = ["id"],
            childColumns = ["visit_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["visit_id", "created_at"])],
)
data class VisitorLoungeMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "visit_id") val visitId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
