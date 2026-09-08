package me.rerere.rikkahub.data.pcbridge

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcBridgeRelayClientTest {
    @Test
    fun `HTTP 401 identifies a saved credential mismatch without exposing relay data`() = runBlocking {
        val client = PcBridgeRelayClient(
            transport = object : PcBridgeRelayTransport {
                override suspend fun post(endpoint: HttpUrl, body: String, headers: Map<String, String>): String {
                    throw PcBridgeRelayHttpException(
                        statusCode = 401,
                        rejection = PcBridgeRelayRejection.RELAY_TOKEN_MISMATCH,
                    )
                }
            },
            nowMillis = { 1_000L },
            nonceFactory = { "nonce-main" },
        )

        val failure = try {
            client.refreshStatus(testCredentials())
            throw AssertionError("Expected the relay request to fail")
        } catch (error: PcBridgeRelayException) {
            error
        }

        assertEquals("PC bridge relay rejected the saved phone credential (HTTP 401).", failure.message)
    }

    @Test
    fun `claim next treats a null relay result as an empty mailbox`() = runBlocking {
        val client = PcBridgeRelayClient(
            transport = object : PcBridgeRelayTransport {
                override suspend fun post(endpoint: HttpUrl, body: String, headers: Map<String, String>): String =
                    """{"claimed":null}"""
            },
            nowMillis = { 1_000L },
            nonceFactory = { "nonce-main" },
        )

        assertNull(client.claimNextEnvelope(testCredentials(), "lease-main"))
    }

    private fun testCredentials() = PcBridgeCredentials(
        endpoint = "https://project.supabase.co/functions/v1/daddy-pc-bridge",
        bridgeId = "bridge-main",
        phoneDeviceId = "phone-main",
        pcDeviceId = "pc-main",
        relayToken = "relay-token-value-for-tests",
        envelopeKey = ByteArray(32),
    )
}
