package me.rerere.rikkahub.data.pcbridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PcBridgeTaskMailboxClientTest {
    @Test
    fun `task relay body contains routing metadata and ciphertext but never the task brief`() = runBlocking {
        val storage = MemoryRecordStorage()
        val secretStore = PcBridgeSecretStore(storage, XorWrappingCipher())
        secretStore.save(
            PcBridgeCredentials(
                endpoint = "https://project.supabase.co/functions/v1/daddy-pc-bridge",
                bridgeId = "bridge-main",
                phoneDeviceId = "phone-main",
                pcDeviceId = "pc-main",
                relayToken = "relay-token",
                envelopeKey = ByteArray(32) { 3 },
            ),
        )
        val transport = RecordingRelayTransport()
        val mailbox = PcBridgeTaskMailboxClient(
            secretStore = secretStore,
            relayClient = PcBridgeRelayClient(
                transport = transport,
                nowMillis = { 1_800_000_000_000L },
                nonceFactory = { "nonce-main" },
            ),
            nowMillis = { 1_800_000_000_000L },
            opaqueIdFactory = { "opaque-main" },
        )

        mailbox.enqueue(
            PcBridgeTaskRequest(
                taskId = "task-readme",
                attemptId = "attempt-readme",
                idempotencyKey = "idempotency-readme",
                executorType = PcBridgeExecutorType.DESKTOP_CLAUDE_CODE,
                workspaceId = "daddy-orangechat",
                relativeScope = ".",
                brief = "读取 README 标题，不修改任何文件。",
            ),
        )

        assertEquals(1, transport.bodies.size)
        val body = transport.bodies.single()
        assertFalse(body.contains("读取 README 标题"))
        assertFalse(body.contains("daddy-orangechat"))
        assertFalse(body.contains("idempotency-readme"))
        assertFalse(body.contains("relay-token"))
        assertFalse(body.contains("envelopeKey"))
        assertFalse(body.contains("task-readme\"}"))
    }

    private class RecordingRelayTransport : PcBridgeRelayTransport {
        val bodies = mutableListOf<String>()

        override suspend fun post(endpoint: okhttp3.HttpUrl, body: String, headers: Map<String, String>): String {
            bodies += body
            return "{\"accepted\":true}"
        }
    }

    private class MemoryRecordStorage : PcBridgeSecureRecordStorage {
        private var record: PcBridgeEncryptedRecord? = null

        override suspend fun read(): PcBridgeEncryptedRecord? = record
        override suspend fun write(record: PcBridgeEncryptedRecord) {
            this.record = record
        }
        override suspend fun clear() {
            record = null
        }
    }

    private class XorWrappingCipher : PcBridgeWrappingCipher {
        override fun encrypt(plaintext: ByteArray): PcBridgeWrappedBytes = PcBridgeWrappedBytes(
            iv = byteArrayOf(1, 2, 3),
            ciphertext = plaintext.map { (it.toInt() xor 0x55).toByte() }.toByteArray(),
        )

        override fun decrypt(wrapped: PcBridgeWrappedBytes): ByteArray =
            wrapped.ciphertext.map { (it.toInt() xor 0x55).toByte() }.toByteArray()
    }
}
