package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.ConversationDigestEntity

@Dao
interface ConversationDigestDao {
    @Query("SELECT * FROM conversation_digest WHERE conversation_id = :conversationId")
    suspend fun getByConversationId(conversationId: String): ConversationDigestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(digest: ConversationDigestEntity)
}
