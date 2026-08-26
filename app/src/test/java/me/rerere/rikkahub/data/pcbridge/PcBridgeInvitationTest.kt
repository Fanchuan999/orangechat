package me.rerere.rikkahub.data.pcbridge

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

class PcBridgeInvitationTest {
    private val nowMillis = 1_800_000_000_000L
    private val expiresAt = nowMillis + 60_000L
    private val validCode = invitationCode(
        """{"version":2,"endpoint":"https://project.supabase.co/functions/v1/daddy-pc-bridge","bridgeId":"bridge_123","pcDeviceId":"pc_456","pcPublicKey":"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE-ISiHsBNFE6rfX6KQSfgXEoY5av4bK-yxm2ZWT8yNBnDttb6YxL997EOi7l8TydqBHJ6T-KNQ4V2pRtKjQY3mQ","pairingSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","expiresAt":$expiresAt}""",
    )

    @Test
    fun `decoder accepts valid v2 code and rejects expiry`() {
        assertEquals(2, PcBridgeInvitationCodec.decode(validCode, nowMillis).version)
        assertFailsWith<IllegalArgumentException> {
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

        assertFailsWith<IllegalArgumentException> { PcBridgeInvitationCodec.decode(unknown, nowMillis) }
        assertFailsWith<IllegalArgumentException> { PcBridgeInvitationCodec.decode(missing, nowMillis) }
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

        assertFailsWith<IllegalArgumentException> { PcBridgeInvitationCodec.decode(reordered, nowMillis) }
        assertFailsWith<IllegalArgumentException> { PcBridgeInvitationCodec.decode(whitespace, nowMillis) }
        assertFailsWith<IllegalArgumentException> { PcBridgeInvitationCodec.decode(duplicate, nowMillis) }
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

        assertFailsWith<IllegalArgumentException> { PcBridgeInvitationCodec.decode(invalidBridgeId, nowMillis) }
        assertFailsWith<IllegalArgumentException> { PcBridgeInvitationCodec.decode(invalidSecret, nowMillis) }
        assertFailsWith<IllegalArgumentException> { PcBridgeInvitationCodec.decode(blankPublicKey, nowMillis) }
    }

    @Test
    fun `decoder rejects a public key that is not P-256 SPKI`() {
        val malformedSpki = invitationCode(
            """{"version":2,"endpoint":"https://project.supabase.co/functions/v1/daddy-pc-bridge","bridgeId":"bridge_123","pcDeviceId":"pc_456","pcPublicKey":"a2V5","pairingSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","expiresAt":$expiresAt}""",
        )

        assertFailsWith<IllegalArgumentException> { PcBridgeInvitationCodec.decode(malformedSpki, nowMillis) }
    }

    @Test
    fun `decoder rejects non PC2 and invitations beyond five minute lifetime`() {
        val tooFar = validCode.replace(expiresAt.toString(), (nowMillis + 300_001L).toString())

        assertFailsWith<IllegalArgumentException> { PcBridgeInvitationCodec.decode(validCode.removePrefix("DADDY-PC2:"), nowMillis) }
        assertFailsWith<IllegalArgumentException> { PcBridgeInvitationCodec.decode(tooFar, nowMillis) }
    }

    private fun invitationCode(json: String): String =
        "DADDY-PC2:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
}
