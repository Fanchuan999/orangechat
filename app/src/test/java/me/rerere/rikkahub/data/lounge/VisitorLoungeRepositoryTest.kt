package me.rerere.rikkahub.data.lounge

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitorLoungeRepositoryTest {
    @Test
    fun `stored transcript is redacted capped and limited to twenty rows`() = runBlocking {
        val store = FakeVisitorLoungeRecordStore()
        val repository = VisitorLoungeRepository(store, clock = { Instant.ofEpochMilli(1000) })
        repository.saveFriend(friend())
        val visit = repository.createVisit("friend-1", null, VisitorLoungeVisitMode.MANUAL, "Say hello")

        repeat(21) { index ->
            repository.appendTranscript(
                visit.id,
                VisitorLoungeTranscriptKind.INBOUND,
                "Authorization: Bearer visitor-key-$index https://friend.example/mcp?key=visitor-key-$index " + "x".repeat(900),
            )
        }

        val entries = repository.observeTranscript(visit.id).first()
        assertEquals(20, entries.size)
        assertTrue(entries.all { it.text.length <= 800 })
        assertFalse(entries.any { it.text.contains("visitor-key") })
    }

    @Test
    fun `startup recovery marks an incomplete visit interrupted`() = runBlocking {
        val store = FakeVisitorLoungeRecordStore()
        val repository = VisitorLoungeRepository(store, clock = { Instant.ofEpochMilli(1000) })
        repository.saveFriend(friend())
        val visit = repository.createVisit("friend-1", null, VisitorLoungeVisitMode.MANUAL, "Say hello")
        repository.updateVisitStatus(visit.id, VisitorLoungeVisitStatus.VISITING)

        repository.recoverIncompleteVisits()

        assertEquals(VisitorLoungeVisitStatus.INTERRUPTED, store.requireVisit(visit.id).status)
    }

    @Test
    fun `deleting a friend deletes its public record before removing its credential`() = runBlocking {
        val store = FakeVisitorLoungeRecordStore()
        val events = mutableListOf<String>()
        val repository = VisitorLoungeRepository(
            store = store,
            removeCredential = { friendId ->
                assertEquals(null, store.getFriend(friendId))
                events += friendId
            },
        )
        repository.saveFriend(friend())

        repository.deleteFriend("friend-1")

        assertEquals(listOf("friend-1"), events)
    }

    private fun friend() = FriendPublicRecord(
        id = "friend-1",
        displayName = "Alice",
        endpoint = "https://friend.example/mcp",
    )
}

private class FakeVisitorLoungeRecordStore : VisitorLoungeRecordStore {
    private val friends = MutableStateFlow<List<FriendPublicRecord>>(emptyList())
    private val visits = MutableStateFlow<List<VisitorLoungeVisit>>(emptyList())
    private val entries = mutableMapOf<String, MutableStateFlow<List<VisitorLoungeTranscriptEntry>>>()

    override fun observeFriends(): Flow<List<FriendPublicRecord>> = friends

    override suspend fun getFriend(friendId: String): FriendPublicRecord? = friends.value.firstOrNull { it.id == friendId }

    override suspend fun upsertFriend(friend: FriendPublicRecord) {
        friends.value = friends.value.filterNot { it.id == friend.id } + friend
    }

    override suspend fun deleteFriend(friendId: String) {
        friends.value = friends.value.filterNot { it.id == friendId }
        visits.value = visits.value.filterNot { it.friendId == friendId }
    }

    override fun observeVisits(): Flow<List<VisitorLoungeVisit>> = visits

    override suspend fun visits(): List<VisitorLoungeVisit> = visits.value

    override suspend fun getVisit(visitId: String): VisitorLoungeVisit? = visits.value.firstOrNull { it.id == visitId }

    override suspend fun upsertVisit(visit: VisitorLoungeVisit) {
        visits.value = visits.value.filterNot { it.id == visit.id } + visit
    }

    override suspend fun updateVisit(visit: VisitorLoungeVisit) = upsertVisit(visit)

    override fun observeTranscript(visitId: String): Flow<List<VisitorLoungeTranscriptEntry>> =
        entries.getOrPut(visitId) { MutableStateFlow(emptyList()) }

    override suspend fun transcript(visitId: String): List<VisitorLoungeTranscriptEntry> =
        entries.getOrPut(visitId) { MutableStateFlow(emptyList()) }.value

    override suspend fun appendTranscript(entry: VisitorLoungeTranscriptEntry) {
        val flow = entries.getOrPut(entry.visitId) { MutableStateFlow(emptyList()) }
        flow.value = flow.value + entry
    }

    override suspend fun trimTranscript(visitId: String, keep: Int) {
        val flow = entries.getOrPut(visitId) { MutableStateFlow(emptyList()) }
        flow.value = flow.value.takeLast(keep)
    }

    fun requireVisit(id: String): VisitorLoungeVisit = requireNotNull(visits.value.firstOrNull { it.id == id })
}
