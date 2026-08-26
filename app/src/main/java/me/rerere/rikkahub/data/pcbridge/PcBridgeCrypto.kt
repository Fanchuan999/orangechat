package me.rerere.rikkahub.data.pcbridge

import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class PcBridgeEphemeralKeyPair(
    val privateKey: PrivateKey,
    val publicKeySpki: String,
)

object PcBridgeCrypto {
    private const val HKDF_INFO = "Daddy-PC-Bridge/v2"
    private const val AES_KEY_LENGTH = 32
    private val base64UrlPattern = Regex("[A-Za-z0-9_-]+")

    fun generateEphemeralKeyPair(): PcBridgeEphemeralKeyPair {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return PcBridgeEphemeralKeyPair(
            privateKey = pair.private,
            publicKeySpki = encodeBase64Url(pair.public.encoded),
        )
    }

    fun deriveAesBytes(privateKey: PrivateKey, peerSpki: String, pairingSecret: String): ByteArray {
        var sharedSecret: ByteArray? = null
        var salt: ByteArray? = null
        try {
            require(privateKey is ECPrivateKey && sameCurve(privateKey.params, p256Parameters())) {
                "Private key must use P-256"
            }
            val peer = importP256PublicKey(peerSpki)
            val agreement = KeyAgreement.getInstance("ECDH").apply {
                init(privateKey)
                doPhase(peer, true)
            }
            sharedSecret = agreement.generateSecret()
            salt = decodeBase64Url(pairingSecret)
            return hkdfSha256(sharedSecret, salt, HKDF_INFO.toByteArray(Charsets.UTF_8), AES_KEY_LENGTH)
        } finally {
            sharedSecret?.fill(0)
            salt?.fill(0)
        }
    }

    fun encodeBase64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun requireP256Spki(peerSpki: String) {
        importP256PublicKey(peerSpki)
    }

    fun decodeBase64Url(value: String): ByteArray {
        require(value.isNotEmpty() && base64UrlPattern.matches(value)) { "Invalid base64url value" }
        val decoded = try {
            Base64.getUrlDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid base64url value", error)
        }
        if (encodeBase64Url(decoded) != value) {
            decoded.fill(0)
            throw IllegalArgumentException("Invalid base64url value")
        }
        return decoded
    }

    private fun importP256PublicKey(peerSpki: String): PublicKey {
        val encoded = decodeBase64Url(peerSpki)
        try {
            val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
            require(peer is ECPublicKey && sameCurve(peer.params, p256Parameters())) { "Peer key must use P-256" }
            return peer
        } catch (error: GeneralSecurityException) {
            throw IllegalArgumentException("Invalid P-256 SPKI public key", error)
        } finally {
            encoded.fill(0)
        }
    }

    private fun p256Parameters(): ECParameterSpec {
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }
        return parameters.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun sameCurve(left: ECParameterSpec, right: ECParameterSpec): Boolean =
        left.curve == right.curve &&
            left.generator == right.generator &&
            left.order == right.order &&
            left.cofactor == right.cofactor

    private fun hkdfSha256(secret: ByteArray, salt: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        var prk: ByteArray? = null
        var previous = ByteArray(0)
        try {
            prk = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(salt, "HmacSHA256"))
                doFinal(secret)
            }
            val output = ByteArray(outputLength)
            var written = 0
            var counter = 1
            while (written < outputLength) {
                val block = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(requireNotNull(prk), "HmacSHA256"))
                    update(previous)
                    update(info)
                    update(counter.toByte())
                    doFinal()
                }
                previous.fill(0)
                previous = block
                val toCopy = minOf(block.size, outputLength - written)
                block.copyInto(output, written, endIndex = toCopy)
                written += toCopy
                counter += 1
            }
            return output
        } finally {
            prk?.fill(0)
            previous.fill(0)
        }
    }
}
