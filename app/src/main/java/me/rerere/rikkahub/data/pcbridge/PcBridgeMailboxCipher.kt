package me.rerere.rikkahub.data.pcbridge

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM envelope codec shared with the PC relay. The relay only sees the
 * encoded envelope; task text is encrypted before it leaves the phone.
 */
internal object PcBridgeMailboxCipher {
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    fun encrypt(
        key: ByteArray,
        plaintext: ByteArray,
        associatedData: String,
        iv: ByteArray = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes),
    ): PcBridgeEncryptedEnvelope {
        require(key.size == 32) { "PC bridge envelope key must be 32 bytes" }
        require(iv.size == IV_BYTES) { "PC bridge envelope IV must be 12 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)
        return try {
            PcBridgeEncryptedEnvelope(
                version = 1,
                iv = PcBridgeCrypto.encodeBase64Url(iv),
                ciphertext = PcBridgeCrypto.encodeBase64Url(ciphertext),
            )
        } finally {
            ciphertext.fill(0)
        }
    }

    fun decrypt(
        key: ByteArray,
        envelope: PcBridgeEncryptedEnvelope,
        associatedData: String,
    ): ByteArray {
        require(key.size == 32) { "PC bridge envelope key must be 32 bytes" }
        require(envelope.version == 1) { "Unsupported PC bridge envelope version" }
        val iv = PcBridgeCrypto.decodeBase64Url(envelope.iv)
        val ciphertext = PcBridgeCrypto.decodeBase64Url(envelope.ciphertext)
        try {
            require(iv.size == IV_BYTES) { "PC bridge envelope IV must be 12 bytes" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(ciphertext)
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    fun deliveryAssociatedData(taskId: String, attemptId: String, sequence: Int): String =
        requireMetadata(taskId, attemptId, sequence).let {
            "{\"version\":1,\"taskId\":\"$taskId\",\"attemptId\":\"$attemptId\",\"sequence\":$sequence,\"actionDigest\":\"task-delivery-v1\"}"
        }

    fun progressAssociatedData(
        sourceDeviceId: String,
        targetDeviceId: String,
        envelopeId: String,
        taskId: String,
        attemptId: String,
        sequence: Int,
    ): String {
        requireIdentifier(sourceDeviceId, "source device id")
        requireIdentifier(targetDeviceId, "target device id")
        requireIdentifier(envelopeId, "envelope id")
        requireMetadata(taskId, attemptId, sequence)
        return "{\"version\":1,\"sourceDeviceId\":\"$sourceDeviceId\",\"targetDeviceId\":\"$targetDeviceId\",\"envelopeId\":\"$envelopeId\",\"taskId\":\"$taskId\",\"attemptId\":\"$attemptId\",\"sequence\":$sequence,\"actionDigest\":\"task-progress-v1\"}"
    }

    private fun requireMetadata(taskId: String, attemptId: String, sequence: Int) {
        requireIdentifier(taskId, "task id")
        requireIdentifier(attemptId, "attempt id")
        require(sequence >= 0) { "PC bridge task sequence must not be negative" }
    }

    private fun requireIdentifier(value: String, field: String) {
        require(IDENTIFIER.matches(value)) { "Invalid PC bridge $field" }
    }

    private val IDENTIFIER = Regex("[A-Za-z0-9_-]{1,128}")
}
