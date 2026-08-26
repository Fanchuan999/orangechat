package me.rerere.rikkahub.data.pcbridge

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPrivateKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcBridgeCryptoTest {
    @Test
    fun `P-256 derivation matches the fixed Node v2 vector`() {
        val result = PcBridgeCrypto.deriveAesBytes(
            privateKeyFixture(),
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEo7krUM-SMzVB3Bn6gzvmJO3NN9CXMpuZDSdA7_YTLXiBNLPn9E0ZTKCl1BgmCvFV0aR3WhUmkrmDXnCfK4q2ww",
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
        )

        assertArrayEquals(
            PcBridgeCrypto.decodeBase64Url("XrbSui3xgsqbnRodn9Ynti15GfVv6qV8sdnrU228GHc"),
            MessageDigest.getInstance("SHA-256").digest(result),
        )
    }

    @Test
    fun `ephemeral keys export SPKI and derive the same key`() {
        val phone = PcBridgeCrypto.generateEphemeralKeyPair()
        val pc = PcBridgeCrypto.generateEphemeralKeyPair()
        val pairingSecret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

        val phoneKey = PcBridgeCrypto.deriveAesBytes(phone.privateKey, pc.publicKeySpki, pairingSecret)
        val pcKey = PcBridgeCrypto.deriveAesBytes(pc.privateKey, phone.publicKeySpki, pairingSecret)

        assertArrayEquals(phoneKey, pcKey)
    }

    @Test
    fun `base64url helpers reject noncanonical input`() {
        assertThrows(IllegalArgumentException::class.java) { PcBridgeCrypto.decodeBase64Url("abc=") }
        assertThrows(IllegalArgumentException::class.java) { PcBridgeCrypto.decodeBase64Url("not/base64") }
    }

    private fun privateKeyFixture(): PrivateKey {
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }
        val spec = parameters.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val d = PcBridgeCrypto.decodeBase64Url("6uM1UgDuUNNj5BxuzTcCS6o3bUsnCH5gP5lEI5Ct-aM")
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, d), spec))
    }
}
