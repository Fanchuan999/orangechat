package me.rerere.rikkahub.data.pcbridge

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Base64

class PcBridgeUiActionSafetyTest {
    @Test
    fun `pairing action publishes fixed redacted failure state`() = runBlocking {
        val service = PcBridgePairingService(
            secretStore = PcBridgeSecretStore(ActionRecordStorage(), ActionCipher()),
            relayClient = PcBridgeRelayClient(FailingTransport),
            phoneDeviceIdFactory = { "phone-test" },
            nowMillis = { NOW_MILLIS },
        )

        service.updateInvitationCode(validInvitationCode())
        service.confirmPairing()

        val state = service.state.value as PcBridgeUiState.Unavailable
        assertEquals("安全连接电脑失败，请重试。", state.message)
        assertFalse(PcBridgeUiPolicy.from(state).flattenText().contains("relay-secret"))
    }

    private fun validInvitationCode(): String = "DADDY-PC2:" + Base64.getUrlEncoder().withoutPadding().encodeToString(
        ("""{"version":2,"endpoint":"https://project.supabase.co/functions/v1/daddy-pc-bridge",""" +
            """"bridgeId":"bridge_123","pcDeviceId":"pc_456","pcPublicKey":"""" +
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE-ISiHsBNFE6rfX6KQSfgXEoY5av4bK-y" +
            "xm2ZWT8yNBnDttb6YxL997EOi7l8TydqBHJ6T-KNQ4V2pRtKjQY3mQ" +
            """","pairingSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","expiresAt":${NOW_MILLIS + 60_000L}}"""
        ).toByteArray(),
    )

    private data object FailingTransport : PcBridgeRelayTransport {
        override suspend fun post(endpoint: HttpUrl, body: String, headers: Map<String, String>): String =
            error("token=relay-secret")
    }

    private class ActionRecordStorage : PcBridgeSecureRecordStorage {
        override suspend fun read(): PcBridgeEncryptedRecord? = null
        override suspend fun write(record: PcBridgeEncryptedRecord) = Unit
        override suspend fun clear() = Unit
    }

    private class ActionCipher : PcBridgeWrappingCipher {
        override fun encrypt(plaintext: ByteArray): PcBridgeWrappedBytes = error("not used")
        override fun decrypt(wrapped: PcBridgeWrappedBytes): ByteArray = error("not used")
    }

    private companion object {
        const val NOW_MILLIS = 1_800_000_000_000L
    }
}
