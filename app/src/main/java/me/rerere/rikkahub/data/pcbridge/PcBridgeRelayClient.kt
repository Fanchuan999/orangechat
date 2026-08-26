package me.rerere.rikkahub.data.pcbridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom

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
        return parseObject(post(endpoint, body, emptyMap()))["paired"]?.jsonPrimitive?.content == "true"
    }

    suspend fun refreshStatus(credentials: PcBridgeCredentials): String? =
        authenticated(credentials, "{\"operation\":\"bridgeStatus\"}").let { response ->
            parseObject(response)["status"]?.jsonObject?.get("state")?.jsonPrimitive?.content
        }

    suspend fun revokeBridge(credentials: PcBridgeCredentials): Boolean =
        authenticated(credentials, "{\"operation\":\"revokeBridge\"}").let { response ->
            parseObject(response)["revoked"]?.jsonPrimitive?.content == "true"
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
        val proofHeader = PcBridgeCrypto.encodeBase64Url(Json.encodeToString(proof).toByteArray(Charsets.UTF_8))
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
    } catch (_: Exception) {
        throw PcBridgeRelayException()
    }

    private fun parseObject(value: String) = try {
        Json.parseToJsonElement(value).jsonObject
    } catch (_: Exception) {
        throw PcBridgeRelayException()
    }

    companion object {
        fun newRelayToken(): String = ByteArray(32).also(SecureRandom()::nextBytes).let(PcBridgeCrypto::encodeBase64Url)

        private fun newNonce(): String = ByteArray(24)
            .also(SecureRandom()::nextBytes)
            .let(PcBridgeCrypto::encodeBase64Url)

        private fun relayTokenHash(relayToken: String): String =
            PcBridgeCrypto.encodeBase64Url(
                MessageDigest.getInstance("SHA-256").digest(relayToken.toByteArray(Charsets.UTF_8)),
            )
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
