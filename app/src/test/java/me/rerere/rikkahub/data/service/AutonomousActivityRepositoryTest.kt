package me.rerere.rikkahub.data.service

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousActivityRepositoryTest {
    @Test
    fun `activity record redacts credentials before persisting summary`() = runBlocking {
        val store = FakeAutonomousActivityRecordStore()
        val repository = AutonomousActivityRepository(
            store = store,
            clock = { Instant.ofEpochMilli(1_000) },
            newId = { "record-1" },
        )

        repository.recordAttempt(
            family = AutonomousActivityFamily.FORUM,
            serverName = "My forum",
            toolName = "publish_post",
            status = AutonomousActivityStatus.FAILED,
            summary = "Authorization: Bearer forum-secret access_token=refresh-secret https://forum.example/mcp?key=url-secret",
        )

        val record = store.records.value.single()
        assertEquals(AutonomousActivityFamily.FORUM, record.family)
        assertFalse(record.summary.contains("forum-secret"))
        assertFalse(record.summary.contains("refresh-secret"))
        assertFalse(record.summary.contains("url-secret"))
    }

    @Test
    fun `activity history retains only newest fifty records`() = runBlocking {
        var instant = 0L
        val store = FakeAutonomousActivityRecordStore()
        val repository = AutonomousActivityRepository(
            store = store,
            clock = { Instant.ofEpochMilli(++instant) },
            newId = { "record-$instant" },
        )

        repeat(51) { index ->
            repository.recordAttempt(
                family = AutonomousActivityFamily.WEB,
                status = AutonomousActivityStatus.COMPLETED,
                summary = "Read item $index",
            )
        }

        val records = repository.observeRecent().first()
        assertEquals(50, records.size)
        assertEquals("record-51", records.first().id)
        assertTrue(records.none { it.id == "record-1" })
    }
}

private class FakeAutonomousActivityRecordStore : AutonomousActivityRecordStore {
    val records = MutableStateFlow<List<AutonomousActivityRecord>>(emptyList())

    override fun observeRecent(limit: Int): Flow<List<AutonomousActivityRecord>> = records

    override suspend fun insert(record: AutonomousActivityRecord) {
        records.value = (records.value + record).sortedByDescending { it.createdAt }
    }

    override suspend fun trimToLatest(keep: Int) {
        records.value = records.value.take(keep)
    }
}
