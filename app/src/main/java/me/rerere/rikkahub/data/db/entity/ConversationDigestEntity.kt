package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Local-only rolling context digest. It never mirrors, replaces, or deletes conversation messages. */
@Entity(
    tableName = "conversation_digest",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ConversationDigestEntity(
    @PrimaryKey
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("summary")
    val summary: String,
    @ColumnInfo("covered_message_count")
    val coveredMessageCount: Int,
    @ColumnInfo("covered_prefix_key")
    val coveredPrefixKey: String,
    @ColumnInfo("last_failed_batch_key")
    val lastFailedBatchKey: String?,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
