package me.rerere.rikkahub.data.pcbridge

import org.junit.Assert.assertFalse
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
}
