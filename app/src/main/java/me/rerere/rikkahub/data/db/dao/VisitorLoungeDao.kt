package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.VisitorLoungeFriendEntity
import me.rerere.rikkahub.data.db.entity.VisitorLoungeMessageEntity
import me.rerere.rikkahub.data.db.entity.VisitorLoungeVisitEntity

@Dao
interface VisitorLoungeDao {
    @Query("SELECT * FROM visitor_lounge_friends ORDER BY display_name COLLATE NOCASE ASC")
    fun observeFriends(): Flow<List<VisitorLoungeFriendEntity>>

    @Query("SELECT * FROM visitor_lounge_friends WHERE id = :friendId LIMIT 1")
    suspend fun getFriend(friendId: String): VisitorLoungeFriendEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFriend(friend: VisitorLoungeFriendEntity)

    @Query("DELETE FROM visitor_lounge_friends WHERE id = :friendId")
    suspend fun deleteFriend(friendId: String)

    @Query("SELECT * FROM visitor_lounge_visits ORDER BY started_at DESC")
    fun observeVisits(): Flow<List<VisitorLoungeVisitEntity>>

    @Query("SELECT * FROM visitor_lounge_visits ORDER BY started_at DESC")
    suspend fun visits(): List<VisitorLoungeVisitEntity>

    @Query("SELECT * FROM visitor_lounge_visits WHERE id = :visitId LIMIT 1")
    suspend fun getVisit(visitId: String): VisitorLoungeVisitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVisit(visit: VisitorLoungeVisitEntity)

    @Query("SELECT * FROM visitor_lounge_messages WHERE visit_id = :visitId ORDER BY created_at ASC, id ASC")
    fun observeTranscript(visitId: String): Flow<List<VisitorLoungeMessageEntity>>

    @Query("SELECT * FROM visitor_lounge_messages WHERE visit_id = :visitId ORDER BY created_at ASC, id ASC")
    suspend fun transcript(visitId: String): List<VisitorLoungeMessageEntity>

    @Insert
    suspend fun appendTranscript(entry: VisitorLoungeMessageEntity)

    @Query(
        "DELETE FROM visitor_lounge_messages WHERE visit_id = :visitId AND id NOT IN " +
            "(SELECT id FROM visitor_lounge_messages WHERE visit_id = :visitId ORDER BY created_at DESC, id DESC LIMIT :keep)",
    )
    suspend fun trimTranscript(visitId: String, keep: Int)
}
