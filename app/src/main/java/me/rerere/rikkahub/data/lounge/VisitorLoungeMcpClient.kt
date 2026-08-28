package me.rerere.rikkahub.data.lounge

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.net.URI
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.mcp.transport.StreamableHttpClientTransport

class VisitorLoungeProtocolException(message: String) : IllegalStateException(VisitorLoungeRedactor.redact(message))

interface VisitorLoungeMcpSession {
    val tools: Set<String>

    suspend fun call(name: String, arguments: JsonObject): String

    suspend fun close()
}

interface VisitorLoungeMcpSessionFactory {
    /** The Visitor Key is provided only to this short-lived connect call. */
    suspend fun connect(endpoint: URI, visitorKey: String): VisitorLoungeMcpSession
}

class VisitorLoungeMcpClient(
    private val factory: VisitorLoungeMcpSessionFactory,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun open(rawEndpoint: String, visitorKey: String): VisitorLoungeMcpSession {
        if (visitorKey.isBlank()) throw VisitorLoungeProtocolException("Visitor Key is unavailable")
        val endpoint = VisitorLoungeEndpointPolicy.normalize(rawEndpoint)
        val session = try {
            factory.connect(endpoint, visitorKey)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw VisitorLoungeProtocolException("Could not connect to the visitor lounge")
        }
        if (!session.tools.containsAll(allowedTools)) {
            session.close()
            throw VisitorLoungeProtocolException("Visitor lounge does not provide the required protocol tools")
        }
        return session
    }

    suspend fun call(
        session: VisitorLoungeMcpSession,
        name: String,
        arguments: JsonObject,
        deadline: Instant? = null,
    ): String {
        if (name !in allowedTools) throw VisitorLoungeProtocolException("Visitor lounge tool is not allowed")
        if (deadline != null && !now().isBefore(deadline)) {
            throw VisitorLoungeProtocolException("Visitor lounge visit has timed out")
        }
        return try {
            withTimeout(PER_CALL_TIMEOUT_SECONDS * 1_000) {
                VisitorLoungeRedactor.redact(session.call(name, arguments)).take(MAX_DISPLAYED_REMOTE_CHARACTERS)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: VisitorLoungeProtocolException) {
            throw error
        } catch (_: Exception) {
            throw VisitorLoungeProtocolException("Visitor lounge tool call failed")
        }
    }

    suspend fun getLoungeInfo(session: VisitorLoungeMcpSession, deadline: Instant? = null): String =
        call(session, "get_lounge_info", buildJsonObject { }, deadline)

    suspend fun claimIdentity(
        session: VisitorLoungeMcpSession,
        displayName: String,
        deadline: Instant? = null,
    ): String = call(session, "claim_identity", buildJsonObject { put("display_name", displayName.take(100)) }, deadline)

    suspend fun beginVisit(
        session: VisitorLoungeMcpSession,
        requestId: String,
        topic: String,
        deadline: Instant? = null,
    ): String = call(
        session,
        "begin_visit",
        buildJsonObject {
            put("request_id", requestId)
            put("topic", topic.take(MAX_OUTBOUND_CHARACTERS))
        },
        deadline,
    )

    suspend fun talkToHost(
        session: VisitorLoungeMcpSession,
        visitId: String,
        message: String,
        deadline: Instant? = null,
    ): String {
        if (message.length > MAX_OUTBOUND_CHARACTERS) {
            throw VisitorLoungeProtocolException("Visitor lounge message exceeds the allowed length")
        }
        return call(
            session,
            "talk_to_host",
            buildJsonObject {
                put("visit_id", visitId)
                put("message", message)
            },
            deadline,
        )
    }

    suspend fun getVisitState(
        session: VisitorLoungeMcpSession,
        requestId: String,
        deadline: Instant? = null,
    ): String = call(session, "get_visit_state", buildJsonObject { put("request_id", requestId) }, deadline)

    suspend fun endVisit(
        session: VisitorLoungeMcpSession,
        visitId: String,
        deadline: Instant? = null,
    ): String = call(session, "end_visit", buildJsonObject { put("visit_id", visitId) }, deadline)

    companion object {
        val allowedTools = setOf(
            "get_lounge_info",
            "claim_identity",
            "begin_visit",
            "talk_to_host",
            "get_visit_state",
            "end_visit",
        )
        const val PER_CALL_TIMEOUT_SECONDS = 120L
        const val MAX_OUTBOUND_CHARACTERS = 500
        const val MAX_DISPLAYED_REMOTE_CHARACTERS = 800
    }
}

/**
 * A short-lived Streamable HTTP session. It is intentionally not registered with [McpManager].
 */
class StreamableVisitorLoungeMcpSessionFactory : VisitorLoungeMcpSessionFactory {
    override suspend fun connect(endpoint: URI, visitorKey: String): VisitorLoungeMcpSession {
        val httpClient = HttpClient(OkHttp) {
            install(SSE)
        }
        val transport = StreamableHttpClientTransport(
            client = httpClient,
            url = endpoint.toString(),
            requestBuilder = {
                headers.append(HttpHeaders.Authorization, "Bearer $visitorKey")
            },
        )
        val client = Client(
            clientInfo = Implementation(name = "Daddy Visitor Lounge", version = "2.5.45"),
        )
        return try {
            client.connect(transport)
            val tools = client.listTools().tools.map { it.name }.toSet()
            SdkVisitorLoungeMcpSession(client, transport, httpClient, tools)
        } catch (error: CancellationException) {
            runCatching { transport.close() }
            httpClient.close()
            throw error
        } catch (error: Exception) {
            runCatching { transport.close() }
            httpClient.close()
            throw VisitorLoungeProtocolException("Could not initialize the visitor lounge protocol")
        }
    }
}

private class SdkVisitorLoungeMcpSession(
    private val client: Client,
    private val transport: StreamableHttpClientTransport,
    private val httpClient: HttpClient,
    override val tools: Set<String>,
) : VisitorLoungeMcpSession {
    override suspend fun call(name: String, arguments: JsonObject): String {
        val result = client.callTool(
            request = CallToolRequest(
                params = CallToolRequestParams(name = name, arguments = arguments),
            ),
            options = RequestOptions(timeout = VisitorLoungeMcpClient.PER_CALL_TIMEOUT_SECONDS.seconds),
        )
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        if (result.isError == true) throw VisitorLoungeProtocolException(text.ifBlank { "Visitor lounge tool failed" })
        return VisitorLoungeRedactor.redact(text)
    }

    override suspend fun close() {
        try {
            client.close()
        } finally {
            runCatching { transport.close() }
            httpClient.close()
        }
    }
}
