package me.rerere.rikkahub.data.pcbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PcBridgeMailboxCipherTest {
    @Test
    fun `delivery envelope decrypts only with the original task metadata`() {
        val key = ByteArray(32) { 9 }
        val payload = "{\"version\":1,\"taskId\":\"task-readme\"}".toByteArray()
        val envelope = PcBridgeMailboxCipher.encrypt(
            key = key,
            plaintext = payload,
            associatedData = PcBridgeMailboxCipher.deliveryAssociatedData(
                taskId = "task-readme",
                attemptId = "attempt-readme",
                sequence = 0,
            ),
            iv = ByteArray(12) { 4 },
        )

        val plaintext = PcBridgeMailboxCipher.decrypt(
            key = key,
            envelope = envelope,
            associatedData = PcBridgeMailboxCipher.deliveryAssociatedData(
                taskId = "task-readme",
                attemptId = "attempt-readme",
                sequence = 0,
            ),
        )
        assertEquals(String(payload), String(plaintext))

        try {
            PcBridgeMailboxCipher.decrypt(
                key = key,
                envelope = envelope,
                associatedData = PcBridgeMailboxCipher.deliveryAssociatedData(
                    taskId = "different-task",
                    attemptId = "attempt-readme",
                    sequence = 0,
                ),
            )
            fail("task-bound associated data must reject a ciphertext replay")
        } catch (_: Exception) {
            // Expected: AES-GCM authentication failed.
        } finally {
            key.fill(0)
            payload.fill(0)
            plaintext.fill(0)
        }
    }
}
