package me.rerere.rikkahub.data.pcbridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CancellationException

interface PcBridgeRelayTransport {
    suspend fun post(endpoint: HttpUrl, body: String, headers: Map<String, String>): String
}

class PcBridgeRelayClient(
    private val transport: PcBridgeRelayTransport,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val nonceFactory: () -> String = ::newNonce,
) {
    constructor(httpClient: OkHttpClient) : this(OkHttpPcBridgeRelayTransport(httpClient))

    suspend fun pairJoin(
        invitation: PcBridgeInvitation,
        phoneDeviceId: String,
        phonePublicKey: String,
        relayToken: String,
    ): Boolean {
        val endpoint = PcBridgeEndpointPolicy.requireExactRelayEndpoint(invitation.endpoint)
        val body = Json.encodeToString(
            PairJoinRequest(
                operation = "pairJoin",
                join = PairJoin(
                    version = 1,
                    bridgeId = invitation.bridgeId,
                    pcDeviceId = invitation.pcDeviceId,
                    pairingSecret = invitation.pairingSecret,
                    phoneDeviceId = phoneDeviceId,
                    phonePublicKey = phonePublicKey,
                    phoneRelayTokenHash = relayTokenHash(relayToken),
                ),
            ),
        )
        return parseObject(post(endpoint, body, emptyMap())).isStrictBooleanTrue("paired")
    }

    suspend fun refreshStatus(credentials: PcBridgeCredentials): String? =
        authenticated(credentials, "{\"operation\":\"bridgeStatus\"}").let { response ->
            parseObject(response)["status"]?.jsonObject?.get("state")?.jsonPrimitive?.content
        }

    suspend fun revokeBridge(credentials: PcBridgeCredentials): Boolean =
        authenticated(credentials, "{\"operation\":\"revokeBridge\"}").let { response ->
            parseObject(response).isStrictBooleanTrue("revoked")
        }

    /** Sends only routing metadata plus AES-GCM ciphertext to the private relay. */
    internal suspend fun enqueueEnvelope(credentials: PcBridgeCredentials, envelope: PcBridgeRelayEnvelope) {
        validateEnvelope(envelope)
        val response = authenticated(
            credentials,
            "{\"operation\":\"enqueue\",\"envelope\":${Json.encodeToString(envelope)}}",
        )
        if (!parseObject(response).isStrictBooleanTrue("accepted")) throw PcBridgeRelayException()
    }

    internal suspend fun claimNextEnvelope(
        credentials: PcBridgeCredentials,
        leaseId: String,
    ): PcBridgeRelayEnvelope? {
        requireIdentifier(leaseId, "lease id")
        val response = authenticated(credentials, "{\"operation\":\"claimNext\",\"leaseId\":\"$leaseId\"}")
        val claimed = parseObject(response)["claimed"] ?: return null
        return try {
            Json.decodeFromJsonElement<PcBridgeRelayEnvelope>(claimed)
        } catch (_: Exception) {
            throw PcBridgeRelayException()
        }
    }

    internal suspend fun markEnvelopeReceived(
        credentials: PcBridgeCredentials,
        envelopeId: String,
        leaseId: String,
    ) {
        requireIdentifier(envelopeId, "envelope id")
        requireIdentifier(leaseId, "lease id")
        val response = authenticated(
            credentials,
            "{\"operation\":\"markReceived\",\"envelopeId\":\"$envelopeId\",\"leaseId\":\"$leaseId\"}",
        )
        if (!parseObject(response).isStrictBooleanTrue("received")) throw PcBridgeRelayException()
    }

    private suspend fun authenticated(credentials: PcBridgeCredentials, body: String): String {
        val endpoint = PcBridgeEndpointPolicy.requireExactRelayEndpoint(credentials.endpoint)
        val proof = PcBridgeRelayProof.create(
            credentials = credentials,
            body = body,
            nowMillis = nowMillis(),
            nonce = nonceFactory(),
            path = endpoint.encodedPath,
        )
        val proofBytes = Json.encodeToString(proof).toByteArray(Charsets.UTF_8)
        val proofHeader = try {
            PcBridgeCrypto.encodeBase64Url(proofBytes)
        } finally {
            proofBytes.fill(0)
        }
        return post(
            endpoint,
            body,
            mapOf(
                "x-daddy-device-id" to credentials.phoneDeviceId,
                "x-daddy-relay-token" to credentials.relayToken,
                "x-daddy-relay-proof" to proofHeader,
            ),
        )
    }

    private suspend fun post(endpoint: HttpUrl, body: String, headers: Map<String, String>): String = try {
        transport.post(endpoint, body, headers)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw PcBridgeRelayException()
    }

    private fun parseObject(value: String) = try {
        Json.parseToJsonElement(value).jsonObject
    } catch (_: Exception) {
        throw PcBridgeRelayException()
    }

    private fun JsonObject.isStrictBooleanTrue(field: String): Boolean {
        val value = this[field] as? JsonPrimitive ?: return false
        return !value.isString && value.booleanOrNull == true
    }

    private fun validateEnvelope(envelope: PcBridgeRelayEnvelope) {
        requireIdentifier(envelope.envelopeId, "envelope id")
        requireIdentifier(envelope.taskId, "task id")
        requireIdentifier(envelope.attemptId, "attempt id")
        requireIdentifier(envelope.targetDeviceId, "target device id")
        require(envelope.sequence >= 0) { "Invalid PC bridge envelope sequence" }
        require(envelope.expiresAt > 0) { "Invalid PC bridge envelope expiry" }
        require(envelope.encryption.version == 1) { "Unsupported PC bridge envelope version" }
        require(envelope.encryption.iv.isNotBlank() && envelope.encryption.ciphertext.isNotBlank()) {
            "Invalid PC bridge encrypted envelope"
        }
    }

    private fun requireIdentifier(value: String, field: String) {
        require(IDENTIFIER.matches(value)) { "Invalid PC bridge $field" }
    }

    companion object {
        private val IDENTIFIER = Regex("[A-Za-z0-9_-]{1,128}")

        fun newRelayToken(): String = ByteArray(32).also(SecureRandom()::nextBytes).let(PcBridgeCrypto::encodeBase64Url)

        private fun newNonce(): String = ByteArray(24)
            .also(SecureRandom()::nextBytes)
            .let(PcBridgeCrypto::encodeBase64Url)

        private fun relayTokenHash(relayToken: String): String {
            val tokenBytes = relayToken.toByteArray(Charsets.UTF_8)
            return try {
                PcBridgeCrypto.encodeBase64Url(MessageDigest.getInstance("SHA-256").digest(tokenBytes))
            } finally {
                tokenBytes.fill(0)
            }
        }
    }
}

class PcBridgeRelayException : Exception("PC bridge relay request failed")

private class OkHttpPcBridgeRelayTransport(
    private val client: OkHttpClient,
) : PcBridgeRelayTransport {
    override suspend fun post(endpoint: HttpUrl, body: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw PcBridgeRelayException()
            response.body.string()
        }
        }
}

@Serializable
private data class PairJoinRequest(
    val operation: String,
    val join: PairJoin,
)

@Serializable
private data class PairJoin(
    val version: Int,
    val bridgeId: String,
    val pcDeviceId: String,
    val pairingSecret: String,
    val phoneDeviceId: String,
    val phonePublicKey: String,
    val phoneRelayTokenHash: String,
)
