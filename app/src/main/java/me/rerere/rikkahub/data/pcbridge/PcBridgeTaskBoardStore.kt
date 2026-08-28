/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.pcbridge

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PC_BRIDGE_TASK_BOARD_STORE_NAME = "pc_bridge_task_board"
private val Context.pcBridgeTaskBoardStore by preferencesDataStore(name = PC_BRIDGE_TASK_BOARD_STORE_NAME)

/** Compact local board state. It is deliberately separate from Supabase, chat history and Ombre. */
data class PcBridgeTaskBoardSnapshot(
    val cards: List<PcBridgeTaskCard>,
    val handledEventIds: List<String>,
)

interface PcBridgeTaskBoardRepository {
    suspend fun read(): PcBridgeTaskBoardSnapshot?
    suspend fun write(snapshot: PcBridgeTaskBoardSnapshot)
}

/**
 * The task board can mention private local work, so it is encrypted with the same Android
 * Keystore-backed wrapping primitive as the pairing record. No board content is uploaded.
 */
class PcBridgeTaskBoardStore internal constructor(
    private val storage: PcBridgeTaskBoardEncryptedStorage,
    private val cipher: PcBridgeWrappingCipher,
    private val json: Json = Json,
) : PcBridgeTaskBoardRepository {
    constructor(context: Context) : this(
        storage = DataStorePcBridgeTaskBoardStorage(context),
        cipher = AndroidKeystorePcBridgeWrappingCipher(),
    )

    override suspend fun read(): PcBridgeTaskBoardSnapshot? {
        val record = storage.read() ?: return null
        if (record.version != 1) return null
        return try {
            val iv = PcBridgeCrypto.decodeBase64Url(record.iv)
            val ciphertext = PcBridgeCrypto.decodeBase64Url(record.ciphertext)
            val plaintext = try {
                cipher.decrypt(PcBridgeWrappedBytes(iv, ciphertext))
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
            try {
                json.decodeFromString<PcBridgeTaskBoardPrivateRecord>(plaintext.toString(Charsets.UTF_8)).toSnapshot()
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun write(snapshot: PcBridgeTaskBoardSnapshot) {
        val plaintext = json.encodeToString(PcBridgeTaskBoardPrivateRecord.from(snapshot)).toByteArray(Charsets.UTF_8)
        val wrapped = try {
            cipher.encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }
        try {
            storage.write(
                PcBridgeTaskBoardEncryptedRecord(
                    version = 1,
                    iv = PcBridgeCrypto.encodeBase64Url(wrapped.iv),
                    ciphertext = PcBridgeCrypto.encodeBase64Url(wrapped.ciphertext),
                ),
            )
        } finally {
            wrapped.iv.fill(0)
            wrapped.ciphertext.fill(0)
        }
    }
}

internal interface PcBridgeTaskBoardEncryptedStorage {
    suspend fun read(): PcBridgeTaskBoardEncryptedRecord?
    suspend fun write(record: PcBridgeTaskBoardEncryptedRecord)
}

private class DataStorePcBridgeTaskBoardStorage(context: Context) : PcBridgeTaskBoardEncryptedStorage {
    private val store = context.pcBridgeTaskBoardStore
    private val recordKey = stringPreferencesKey("encrypted_record")

    override suspend fun read(): PcBridgeTaskBoardEncryptedRecord? = store.data.first()[recordKey]?.let {
        runCatching { Json.decodeFromString<PcBridgeTaskBoardEncryptedRecord>(it) }.getOrNull()
    }

    override suspend fun write(record: PcBridgeTaskBoardEncryptedRecord) {
        store.edit { preferences ->
            preferences[recordKey] = Json.encodeToString(record)
        }
    }
}

@Serializable
internal data class PcBridgeTaskBoardEncryptedRecord(
    val version: Int,
    val iv: String,
    val ciphertext: String,
)

@Serializable
private data class PcBridgeTaskBoardPrivateRecord(
    val version: Int = 1,
    val cards: List<PcBridgeTaskBoardCardRecord>,
    val handledEventIds: List<String>,
) {
    fun toSnapshot(): PcBridgeTaskBoardSnapshot = PcBridgeTaskBoardSnapshot(
        cards = cards.mapNotNull(PcBridgeTaskBoardCardRecord::toCard),
        handledEventIds = handledEventIds.filter { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }.takeLast(256),
    )

    companion object {
        fun from(snapshot: PcBridgeTaskBoardSnapshot) = PcBridgeTaskBoardPrivateRecord(
            cards = snapshot.cards.takeLast(80).map(PcBridgeTaskBoardCardRecord::from),
            handledEventIds = snapshot.handledEventIds.takeLast(256),
        )
    }
}

@Serializable
private data class PcBridgeTaskBoardCardRecord(
    val taskId: String,
    val attemptId: String,
    val workspaceId: String,
    val relativeScope: String,
    val brief: String,
    val state: String,
    val summary: String,
    val sequence: Int,
    val updatedAtMillis: Long,
) {
    fun toCard(): PcBridgeTaskCard? = runCatching {
        PcBridgeTaskCard(
            taskId = taskId,
            attemptId = attemptId,
            workspaceId = workspaceId,
            relativeScope = relativeScope,
            brief = brief,
            state = PcBridgeTaskCardState.valueOf(state),
            summary = summary,
            sequence = sequence,
            updatedAtMillis = updatedAtMillis,
        )
    }.getOrNull()

    companion object {
        fun from(card: PcBridgeTaskCard) = PcBridgeTaskBoardCardRecord(
            taskId = card.taskId,
            attemptId = card.attemptId,
            workspaceId = card.workspaceId,
            relativeScope = card.relativeScope,
            brief = card.brief,
            state = card.state.name,
            summary = card.summary,
            sequence = card.sequence,
            updatedAtMillis = card.updatedAtMillis,
        )
    }
}
