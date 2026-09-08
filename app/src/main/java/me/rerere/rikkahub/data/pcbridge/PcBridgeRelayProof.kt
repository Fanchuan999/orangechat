package me.rerere.rikkahub.data.pcbridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
data class PcBridgeRelayProof(
    val version: Int,
    val deviceId: String,
    val timestampMs: Long,
    val nonce: String,
    val method: String,
    val path: String,
    val bodyDigest: String,
    val signature: String,
) {
    companion object {
        fun create(
            credentials: PcBridgeCredentials,
            body: String,
            nowMillis: Long,
            nonce: String,
            method: String = "POST",
            path: String = "/functions/v1/daddy-pc-bridge",
        ): PcBridgeRelayProof {
            require(nonce.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid relay nonce" }
            require(path.startsWith('/')) { "Invalid relay path" }
            val normalizedMethod = method.uppercase()
            val bodyDigest = sha256Base64Url(canonicalJson(body).toByteArray(Charsets.UTF_8))
            val unsigned = listOf(
                "1",
                credentials.phoneDeviceId,
                nowMillis.toString(),
                nonce,
                normalizedMethod,
                path,
                bodyDigest,
            )
                .joinToString("\n")
            val signature = hmacBase64Url(credentials.relayToken, unsigned)
            return PcBridgeRelayProof(
                version = 1,
                deviceId = credentials.phoneDeviceId,
                timestampMs = nowMillis,
                nonce = nonce,
                method = normalizedMethod,
                path = path,
                bodyDigest = bodyDigest,
                signature = signature,
            )
        }

        fun matchesBody(proof: PcBridgeRelayProof, body: String): Boolean =
            constantTimeEquals(proof.bodyDigest, sha256Base64Url(canonicalJson(body).toByteArray(Charsets.UTF_8)))

        fun canonicalJson(body: String): String = canonicalJson(Json.parseToJsonElement(body))

        private fun canonicalJson(value: JsonElement): String = when (value) {
            JsonNull -> "null"
            is JsonArray -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { canonicalJson(it) }
            is JsonObject -> {
                value.entries.sortedBy { it.key }.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, element) ->
                    "${Json.encodeToString(JsonPrimitive(key))}:${canonicalJson(element)}"
                }
            }
            is JsonPrimitive -> if (value.isString) Json.encodeToString(value) else value.content
            else -> error("Unsupported JSON value")
        }

        private fun sha256Base64Url(value: ByteArray): String = try {
            PcBridgeCrypto.encodeBase64Url(MessageDigest.getInstance("SHA-256").digest(value))
        } finally {
            value.fill(0)
        }

        private fun hmacBase64Url(key: String, message: String): String {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            try {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
                return PcBridgeCrypto.encodeBase64Url(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
            } finally {
                keyBytes.fill(0)
            }
        }

        private fun constantTimeEquals(left: String, right: String): Boolean {
            val leftBytes = left.toByteArray(Charsets.UTF_8)
            val rightBytes = right.toByteArray(Charsets.UTF_8)
            return try {
                var difference = leftBytes.size xor rightBytes.size
                for (index in 0 until maxOf(leftBytes.size, rightBytes.size)) {
                    difference = difference or ((leftBytes.getOrElse(index) { 0 }.toInt()) xor
                        rightBytes.getOrElse(index) { 0 }.toInt())
                }
                difference == 0
            } finally {
                leftBytes.fill(0)
                rightBytes.fill(0)
            }
        }
    }
}
