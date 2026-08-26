package me.rerere.rikkahub.data.pcbridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Serializable
data class PcBridgeInvitation(
    val version: Int,
    val endpoint: String,
    val bridgeId: String,
    val pcDeviceId: String,
    val pcPublicKey: String,
    val pairingSecret: String,
    val expiresAt: Long,
)

object PcBridgeInvitationCodec {
    private const val PREFIX = "DADDY-PC2:"
    private const val MAX_CODE_LENGTH = 4096
    private const val MAX_LIFETIME_MILLIS = 5 * 60 * 1000L
    private val identifierPattern = Regex("[A-Za-z0-9_-]{1,128}")
    private val base64UrlPattern = Regex("[A-Za-z0-9_-]+")
    private val requiredFields = setOf(
        "version",
        "endpoint",
        "bridgeId",
        "pcDeviceId",
        "pcPublicKey",
        "pairingSecret",
        "expiresAt",
    )
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun decode(code: String, nowMillis: Long = System.currentTimeMillis()): PcBridgeInvitation {
        require(code.startsWith(PREFIX) && code.length <= MAX_CODE_LENGTH) { "Invalid PC bridge invitation" }
        val encoded = code.removePrefix(PREFIX)
        val payload = PcBridgeCrypto.decodeBase64Url(encoded)
        val payloadJson = try {
            payload.toString(Charsets.UTF_8)
        } finally {
            payload.fill(0)
        }
        val invitation = try {
            require(json.parseToJsonElement(payloadJson).jsonObject.keys == requiredFields) {
                "Invalid PC bridge invitation fields"
            }
            json.decodeFromString<PcBridgeInvitation>(payloadJson)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid PC bridge invitation", error)
        }

        require(invitation.version == 2) { "Unsupported PC bridge invitation version" }
        PcBridgeEndpointPolicy.requireExactRelayEndpoint(invitation.endpoint)
        require(identifierPattern.matches(invitation.bridgeId)) { "Invalid PC bridge bridge ID" }
        require(identifierPattern.matches(invitation.pcDeviceId)) { "Invalid PC bridge device ID" }
        require(invitation.pcPublicKey.isNotEmpty() && invitation.pcPublicKey.length <= 512) {
            "Invalid PC bridge public key"
        }
        try {
            PcBridgeCrypto.requireP256Spki(invitation.pcPublicKey)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid PC bridge public key", error)
        }
        require(invitation.pairingSecret.length in 32..128 && base64UrlPattern.matches(invitation.pairingSecret)) {
            "Invalid PC bridge pairing secret"
        }
        val pairingSecret = try {
            PcBridgeCrypto.decodeBase64Url(invitation.pairingSecret)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid PC bridge pairing secret", error)
        }
        pairingSecret.fill(0)
        require(invitation.expiresAt > nowMillis && invitation.expiresAt <= nowMillis + MAX_LIFETIME_MILLIS) {
            "PC bridge invitation has expired or is not valid yet"
        }
        require(code == encodeCanonical(invitation)) { "Noncanonical PC bridge invitation" }

        return invitation
    }

    private fun encodeCanonical(invitation: PcBridgeInvitation): String {
        val payload = buildString {
            append("{\"version\":")
            append(invitation.version)
            append(",\"endpoint\":")
            append(json.encodeToString(invitation.endpoint))
            append(",\"bridgeId\":")
            append(json.encodeToString(invitation.bridgeId))
            append(",\"pcDeviceId\":")
            append(json.encodeToString(invitation.pcDeviceId))
            append(",\"pcPublicKey\":")
            append(json.encodeToString(invitation.pcPublicKey))
            append(",\"pairingSecret\":")
            append(json.encodeToString(invitation.pairingSecret))
            append(",\"expiresAt\":")
            append(invitation.expiresAt)
            append('}')
        }
        return PREFIX + PcBridgeCrypto.encodeBase64Url(payload.toByteArray(Charsets.UTF_8))
    }
}
