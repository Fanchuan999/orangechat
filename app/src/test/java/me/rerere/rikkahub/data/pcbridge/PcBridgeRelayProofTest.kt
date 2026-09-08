package me.rerere.rikkahub.data.pcbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcBridgeRelayProofTest {
    @Test
    fun `proof binds body digest`() {
        val credentials = PcBridgeCredentials(
            endpoint = "https://project.supabase.co/functions/v1/daddy-pc-bridge",
            bridgeId = "bridge-main",
            phoneDeviceId = "phone-main",
            pcDeviceId = "pc-main",
            relayToken = "relay-token-value-for-tests",
            envelopeKey = ByteArray(32),
        )
        val body = """{"operation":"bridgeStatus"}"""
        val changedBody = """{"operation":"revokeBridge"}"""

        val proof = PcBridgeRelayProof.create(credentials, body, 1_000L, "nonce-main")

        assertTrue(PcBridgeRelayProof.matchesBody(proof, body))
        assertFalse(PcBridgeRelayProof.matchesBody(proof, changedBody))
    }

    @Test
    fun `phone proof uses the Edge canonical digest for an enqueue envelope`() {
        val credentials = PcBridgeCredentials(
            endpoint = "https://project.supabase.co/functions/v1/daddy-pc-bridge",
            bridgeId = "bridge-main",
            phoneDeviceId = "phone-main",
            pcDeviceId = "pc-main",
            relayToken = "relay-token-value-for-tests",
            envelopeKey = ByteArray(32),
        )
        val body = """{"operation":"enqueue","envelope":{"envelopeId":"envelope-main","taskId":"task-main","attemptId":"attempt-main","targetDeviceId":"pc-main","sequence":0,"expiresAt":1800000600000,"encryption":{"version":1,"iv":"iv-main","ciphertext":"cipher-main"}}}"""

        val proof = PcBridgeRelayProof.create(
            credentials = credentials,
            body = body,
            nowMillis = 1_800_000_000_000L,
            nonce = "nonce-main",
        )

        assertEquals(
            "{\"envelope\":{\"attemptId\":\"attempt-main\",\"encryption\":{\"ciphertext\":\"cipher-main\",\"iv\":\"iv-main\",\"version\":1},\"envelopeId\":\"envelope-main\",\"expiresAt\":1800000600000,\"sequence\":0,\"targetDeviceId\":\"pc-main\",\"taskId\":\"task-main\"},\"operation\":\"enqueue\"}",
            PcBridgeRelayProof.canonicalJson(body),
        )
        assertEquals("KXqF6z0hISd0VByc28B3j1BZeEaozxhxXBmMJuqpa_I", proof.bodyDigest)
    }
}
