package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AutonomousActivityEntity

@Dao
interface AutonomousActivityDao {
    @Query("SELECT * FROM autonomous_activity_records ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AutonomousActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: AutonomousActivityEntity)

    @Query(
        "DELETE FROM autonomous_activity_records WHERE id NOT IN " +
            "(SELECT id FROM autonomous_activity_records ORDER BY created_at DESC, id DESC LIMIT :keep)",
    )
    suspend fun trimToLatest(keep: Int)
}
