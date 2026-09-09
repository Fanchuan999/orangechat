package me.rerere.rikkahub.data.service

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.AutonomousActivityDao
import me.rerere.rikkahub.data.db.entity.AutonomousActivityEntity
import me.rerere.rikkahub.data.lounge.VisitorLoungeRedactor

enum class AutonomousActivityStatus {
    STARTED,
    COMPLETED,
    FAILED,
    DECLINED,
    UNAVAILABLE,
}

data class AutonomousActivityRecord(
    val id: String,
    val family: AutonomousActivityFamily,
    val serverName: String?,
    val toolName: String?,
    val status: AutonomousActivityStatus,
    val summary: String,
    val createdAt: Instant,
)

interface AutonomousActivityRecordStore {
    fun observeRecent(limit: Int): Flow<List<AutonomousActivityRecord>>

    suspend fun insert(record: AutonomousActivityRecord)

    suspend fun trimToLatest(keep: Int)
}

class AutonomousActivityRepository(
    private val store: AutonomousActivityRecordStore,
    private val clock: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun observeRecent(): Flow<List<AutonomousActivityRecord>> = store.observeRecent(MAX_RECORDS)

    suspend fun recordAttempt(
        family: AutonomousActivityFamily,
        serverName: String? = null,
        toolName: String? = null,
        status: AutonomousActivityStatus,
        summary: String,
    ) {
        val createdAt = clock()
        store.insert(
            AutonomousActivityRecord(
                id = newId(),
                family = family,
                serverName = serverName?.trim()?.take(MAX_SERVER_NAME_CHARACTERS)?.ifBlank { null },
                toolName = toolName?.trim()?.take(MAX_TOOL_NAME_CHARACTERS)?.ifBlank { null },
                status = status,
                summary = redactSummary(summary),
                createdAt = createdAt,
            ),
        )
        store.trimToLatest(MAX_RECORDS)
    }

    private fun redactSummary(value: String): String = VisitorLoungeRedactor.redact(value)
        .replace(bearerToken, "Bearer [redacted]")
        .replace(credentialAssignment, "\$1=[redacted]")
        .take(MAX_SUMMARY_CHARACTERS)

    companion object {
        const val MAX_RECORDS = 50
        private const val MAX_SERVER_NAME_CHARACTERS = 120
        private const val MAX_TOOL_NAME_CHARACTERS = 120
        private const val MAX_SUMMARY_CHARACTERS = 300
        private val bearerToken = Regex("""(?i)\bBearer\s+[^\s,;]+""")
        private val credentialAssignment = Regex(
            """(?i)\b(access_token|refresh_token|token|api[_-]?key|key|secret|password)\s*=\s*[^&\s,;]+""",
        )
    }
}

class RoomAutonomousActivityRecordStore(
    private val dao: AutonomousActivityDao,
) : AutonomousActivityRecordStore {
    override fun observeRecent(limit: Int): Flow<List<AutonomousActivityRecord>> =
        dao.observeRecent(limit).map { records -> records.map(AutonomousActivityEntity::toRecord) }

    override suspend fun insert(record: AutonomousActivityRecord) = dao.insert(record.toEntity())

    override suspend fun trimToLatest(keep: Int) = dao.trimToLatest(keep)
}

private fun AutonomousActivityEntity.toRecord() = AutonomousActivityRecord(
    id = id,
    family = runCatching { AutonomousActivityFamily.valueOf(family) }.getOrDefault(AutonomousActivityFamily.WEB),
    serverName = serverName,
    toolName = toolName,
    status = runCatching { AutonomousActivityStatus.valueOf(status) }.getOrDefault(AutonomousActivityStatus.FAILED),
    summary = summary,
    createdAt = Instant.ofEpochMilli(createdAt),
)

private fun AutonomousActivityRecord.toEntity() = AutonomousActivityEntity(
    id = id,
    family = family.name,
    serverName = serverName,
    toolName = toolName,
    status = status.name,
    summary = summary,
    createdAt = createdAt.toEpochMilli(),
)
