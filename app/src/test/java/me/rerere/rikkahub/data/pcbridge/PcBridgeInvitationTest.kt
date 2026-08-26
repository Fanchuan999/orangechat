package me.rerere.rikkahub.data.pcbridge

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcBridgeInvitationTest {
    private val nowMillis = 1_800_000_000_000L
    private val expiresAt = nowMillis + 60_000L
    private val validCode = invitationCode(
        """{"version":2,"endpoint":"https://project.supabase.co/functions/v1/daddy-pc-bridge","bridgeId":"bridge_123","pcDeviceId":"pc_456","pcPublicKey":"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE-ISiHsBNFE6rfX6KQSfgXEoY5av4bK-yxm2ZWT8yNBnDttb6YxL997EOi7l8TydqBHJ6T-KNQ4V2pRtKjQY3mQ","pairingSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","expiresAt":$expiresAt}""",
    )

    @Test
    fun `decoder accepts valid v2 code and rejects expiry`() {
        assertEquals(2, PcBridgeInvitationCodec.decode(validCode, nowMillis).version)
        assertThrows(IllegalArgumentException::class.java) {
            PcBridgeInvitationCodec.decode(validCode, expiresAt + 1)
        }
    }

    @Test
    fun `decoder rejects codes with unknown or missing fields`() {
        val unknown = invitationCode(
            """{"version":2,"endpoint":"https://project.supabase.co/functions/v1/daddy-pc-bridge","bridgeId":"bridge_123","pcDeviceId":"pc_456","pcPublicKey":"key","pairingSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","expiresAt":$expiresAt,"extra":true}""",
        )
        val missing = invitationCode(
            """{"version":2,"endpoint":"https://project.supabase.co/functions/v1/daddy-pc-bridge","bridgeId":"bridge_123","pcDeviceId":"pc_456","pcPublicKey":"key","pairingSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"}""",
        )

        assertThrows(IllegalArgumentException::class.java) { PcBridgeInvitationCodec.decode(unknown, nowMillis) }
        assertThrows(IllegalArgumentException::class.java) { PcBridgeInvitationCodec.decode(missing, nowMillis) }
    }

    @Test
    fun `decoder rejects noncanonical JSON representations`() {
        val reordered = invitationCode(
            """{"endpoint":"https://project.supabase.co/functions/v1/daddy-pc-bridge","version":2,"bridgeId":"bridge_123","pcDeviceId":"pc_456","pcPublicKey":"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE-ISiHsBNFE6rfX6KQSfgXEoY5av4bK-yxm2ZWT8yNBnDttb6YxL997EOi7l8TydqBHJ6T-KNQ4V2pRtKjQY3mQ","pairingSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","expiresAt":$expiresAt}""",
        )
        val whitespace = invitationCode(
            """{ "version":2,"endpoint":"https://project.supabase.co/functions/v1/daddy-pc-bridge","bridgeId":"bridge_123","pcDeviceId":"pc_456","pcPublicKey":"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE-ISiHsBNFE6rfX6KQSfgXEoY5av4bK-yxm2ZWT8yNBnDttb6YxL997EOi7l8TydqBHJ6T-KNQ4V2pRtKjQY3mQ","pairingSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","expiresAt":$expiresAt}""",
        )
        val duplicate = invitationCode(
            """{"version":2,"version":2,"endpoint":"https://project.supabase.co/functions/v1/daddy-pc-bridge","bridgeId":"bridge_123","pcDeviceId":"pc_456","pcPublicKey":"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE-ISiHsBNFE6rfX6KQSfgXEoY5av4bK-yxm2ZWT8yNBnDttb6YxL997EOi7l8TydqBHJ6T-KNQ4V2pRtKjQY3mQ","pairingSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","expiresAt":$expiresAt}""",
        )

        assertThrows(IllegalArgumentException::class.java) { PcBridgeInvitationCodec.decode(reordered, nowMillis) }
        assertThrows(IllegalArgumentException::class.java) { PcBridgeInvitationCodec.decode(whitespace, nowMillis) }
        assertThrows(IllegalArgumentException::class.java) { PcBridgeInvitationCodec.decode(duplicate, nowMillis) }
    }

    @Test
    fun `decoder rejects unsafe identifiers secret and public key`() {
        val invalidBridgeId = invitationCode(
            """{"version":2,"endpoint":"https://project.supabase.co/functions/v1/daddy-pc-bridge","bridgeId":"bridge/123","pcDeviceId":"pc_456","pcPublicKey":"key","pairingSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","expiresAt":$expiresAt}""",
        )
        val invalidSecret = invitationCode(
            """{"version":2,"endpoint":"https://project.supabase.co/functions/v1/daddy-pc-bridge","bridgeId":"bridge_123","pcDeviceId":"pc_456","pcPublicKey":"key","pairingSecret":"not-base64!","expiresAt":$expiresAt}""",
        )
        val blankPublicKey = invitationCode(
            """{"version":2,"endpoint":"https://project.supabase.co/functions/v1/daddy-pc-bridge","bridgeId":"bridge_123","pcDeviceId":"pc_456","pcPublicKey":"","pairingSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","expiresAt":$expiresAt}""",
        )

        assertThrows(IllegalArgumentException::class.java) { PcBridgeInvitationCodec.decode(invalidBridgeId, nowMillis) }
        assertThrows(IllegalArgumentException::class.java) { PcBridgeInvitationCodec.decode(invalidSecret, nowMillis) }
        assertThrows(IllegalArgumentException::class.java) { PcBridgeInvitationCodec.decode(blankPublicKey, nowMillis) }
    }

    @Test
    fun `decoder rejects a public key that is not P-256 SPKI`() {
        val malformedSpki = invitationCode(
            """{"version":2,"endpoint":"https://project.supabase.co/functions/v1/daddy-pc-bridge","bridgeId":"bridge_123","pcDeviceId":"pc_456","pcPublicKey":"a2V5","pairingSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","expiresAt":$expiresAt}""",
        )

        assertThrows(IllegalArgumentException::class.java) { PcBridgeInvitationCodec.decode(malformedSpki, nowMillis) }
    }

    @Test
    fun `decoder rejects non PC2 and invitations beyond five minute lifetime`() {
        val tooFar = invitationCode(
            """{"version":2,"endpoint":"https://project.supabase.co/functions/v1/daddy-pc-bridge","bridgeId":"bridge_123","pcDeviceId":"pc_456","pcPublicKey":"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE-ISiHsBNFE6rfX6KQSfgXEoY5av4bK-yxm2ZWT8yNBnDttb6YxL997EOi7l8TydqBHJ6T-KNQ4V2pRtKjQY3mQ","pairingSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","expiresAt":${nowMillis + 300_001L}}""",
        )

        assertThrows(IllegalArgumentException::class.java) {
            PcBridgeInvitationCodec.decode(validCode.removePrefix("DADDY-PC2:"), nowMillis)
        }
        assertThrows(IllegalArgumentException::class.java) { PcBridgeInvitationCodec.decode(tooFar, nowMillis) }
    }

    private fun invitationCode(json: String): String =
        "DADDY-PC2:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
}
