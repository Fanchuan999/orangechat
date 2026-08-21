/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WorkModelBinding
import me.rerere.rikkahub.data.datastore.WorkProtocol
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.uuid.Uuid

data class BridgeLease(
    val bindingId: Uuid,
    val token: String,
    val baseUrl: String,
    val expiresAtMillis: Long,
)

data class ResolvedWorkProvider(
    val binding: WorkModelBinding,
    val provider: ProviderSetting,
    val model: Model,
)

fun interface CodeHutProviderResolver {
    fun resolve(bindingId: Uuid): ResolvedWorkProvider?
}

class SettingsCodeHutProviderResolver(
    private val settingsStore: SettingsStore,
) : CodeHutProviderResolver {
    override fun resolve(bindingId: Uuid): ResolvedWorkProvider? {
        val settings = settingsStore.settingsFlow.value
        val binding = settings.codeHutSetting.bindings.firstOrNull { it.id == bindingId } ?: return null
        val provider = settings.providers.firstOrNull { it.id == binding.providerId } ?: return null
        val model = provider.models.firstOrNull { it.id == binding.modelId } ?: return null
        return ResolvedWorkProvider(
            binding = binding,
            provider = model.providerOverwrite ?: provider,
            model = model,
        )
    }
}

data class BridgeUpstreamRequest(
    val url: String,
    val headers: Map<String, String>,
)

sealed interface BridgeForwardDecision {
    data object Unauthorized : BridgeForwardDecision
    data class Rejected(val reason: String) : BridgeForwardDecision
    data class Forward(val request: BridgeUpstreamRequest) : BridgeForwardDecision
}

/**
 * A short-lived loopback-only proxy for the one model selected by Code Hut.
 * Harness receives only [BridgeLease.token]; the stored provider API key is
 * added directly to the outbound request and never written to Termux or logs.
 */
class CodeHutCredentialBridge(
    private val providerResolver: CodeHutProviderResolver,
    private val upstreamClient: () -> OkHttpClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val serverFactory: (CodeHutCredentialBridge) -> CodeHutBridgeServer = { bridge ->
        KtorCodeHutBridgeServer(bridge)
    },
) : AutoCloseable {
    private val lock = Any()
    private val audit = ArrayDeque<String>()
    private var server: CodeHutBridgeServer? = null
    private var port: Int = 0
    private var activeLease: ActiveLease? = null

    suspend fun startLease(bindingId: Uuid): BridgeLease {
        ensureServer()
        return synchronized(lock) {
            val token = randomToken()
            val expiresAtMillis = nowMillis() + LEASE_DURATION_MILLIS
            activeLease = ActiveLease(bindingId, token, expiresAtMillis)
            appendAudit("lease started for binding=$bindingId")
            BridgeLease(
                bindingId = bindingId,
                token = token,
                baseUrl = "http://127.0.0.1:$port/providers/$bindingId",
                expiresAtMillis = expiresAtMillis,
            )
        }
    }

    fun revokeLease() = synchronized(lock) {
        activeLease?.let { appendAudit("lease revoked for binding=${it.bindingId}") }
        activeLease = null
    }

    internal fun prepareForward(
        leaseToken: String?,
        bindingId: String?,
        route: String,
        requestHeaders: Map<String, String>,
    ): BridgeForwardDecision {
        val parsedBindingId = runCatching { bindingId?.let(Uuid::parse) }.getOrNull()
            ?: return BridgeForwardDecision.Unauthorized
        val lease = synchronized(lock) { activeLease } ?: return BridgeForwardDecision.Unauthorized
        if (
            nowMillis() >= lease.expiresAtMillis ||
            lease.bindingId != parsedBindingId ||
            leaseToken == null ||
            !sameToken(lease.token, leaseToken)
        ) {
            return BridgeForwardDecision.Unauthorized
        }

        val resolved = providerResolver.resolve(parsedBindingId)
            ?: return BridgeForwardDecision.Rejected("找不到代码小屋的工作模型。")
        if (resolved.binding.protocol != protocolForRoute(route)) {
            return BridgeForwardDecision.Rejected("请求路径与所选工作模型的协议不匹配。")
        }
        val provider = resolved.provider
        val apiKey = providerApiKey(provider)
            ?: return BridgeForwardDecision.Rejected("所选提供商尚未配置 API Key。")
        val baseUrl = providerBaseUrl(provider)
            ?: return BridgeForwardDecision.Rejected("所选提供商没有兼容的工作协议。")

        val upstreamPath = when (resolved.binding.protocol) {
            WorkProtocol.OPENAI_CHAT_COMPLETIONS -> when (provider) {
                is ProviderSetting.OpenAI -> provider.chatCompletionsPath
                else -> "/chat/completions"
            }

            WorkProtocol.ANTHROPIC_MESSAGES -> "/messages"
            WorkProtocol.OPENAI_RESPONSES -> "/responses"
        }
        val headers = forwardedHeaders(requestHeaders, resolved.binding.protocol, apiKey)
        return BridgeForwardDecision.Forward(
            BridgeUpstreamRequest(
                url = joinUrl(baseUrl, upstreamPath),
                headers = headers,
            ),
        )
    }

    fun redactedAuditLog(): String = synchronized(lock) {
        audit.joinToString(separator = "\n").let(::redact)
    }

    override fun close() = synchronized(lock) {
        revokeLease()
        server?.close()
        server = null
        port = 0
    }

    private suspend fun ensureServer() {
        val created = synchronized(lock) {
            server ?: serverFactory(this).also { server = it }
        }
        val resolvedPort = created.start()
        require(resolvedPort > 0) { "代码小屋凭据桥没有分配本机端口。" }
        synchronized(lock) { port = resolvedPort }
    }

    internal fun configureBridgeRoutes(application: Application) {
        application.routing {
            post("/providers/{bindingId}/chat/completions") {
                call.proxy(call.request.headers.firstValueMap(), call.parameters["bindingId"], "chat/completions")
            }
            post("/providers/{bindingId}/messages") {
                call.proxy(call.request.headers.firstValueMap(), call.parameters["bindingId"], "messages")
            }
            post("/providers/{bindingId}/responses") {
                call.proxy(call.request.headers.firstValueMap(), call.parameters["bindingId"], "responses")
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.proxy(
        requestHeaders: Map<String, String>,
        bindingId: String?,
        route: String,
    ) {
        val leaseToken = request.headers[LEASE_HEADER] ?: request.headers[HttpHeaders.Authorization]
            ?.removePrefix("Bearer ")
        when (val decision = prepareForward(leaseToken, bindingId, route, requestHeaders)) {
            BridgeForwardDecision.Unauthorized -> respondText(
                text = "Unauthorized",
                contentType = ContentType.Text.Plain,
                status = HttpStatusCode.Unauthorized,
            )

            is BridgeForwardDecision.Rejected -> respondText(
                text = decision.reason,
                contentType = ContentType.Text.Plain,
                status = HttpStatusCode.BadRequest,
            )

            is BridgeForwardDecision.Forward -> forward(decision.request)
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.forward(
        request: BridgeUpstreamRequest,
    ) {
        val contentType = this@forward.request.headers[HttpHeaders.ContentType]?.toMediaTypeOrNull()
        val body = receiveText().toRequestBody(contentType)
        val upstreamRequest = Request.Builder()
            .url(request.url)
            .post(body)
            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        withContext(Dispatchers.IO) {
            upstreamClient().newCall(upstreamRequest).execute().use { upstream ->
                response.status(HttpStatusCode.fromValue(upstream.code))
                upstream.header(HttpHeaders.ContentType)?.let { response.headers.append(HttpHeaders.ContentType, it) }
                upstream.header(HttpHeaders.CacheControl)?.let { response.headers.append(HttpHeaders.CacheControl, it) }
                respondOutputStream {
                    upstream.body.byteStream().copyTo(this)
                }
            }
        }
    }

    private fun io.ktor.http.Headers.firstValueMap(): Map<String, String> = buildMap {
        entries().forEach { (name, values) -> values.firstOrNull()?.let { put(name, it) } }
    }

    private fun protocolForRoute(route: String): WorkProtocol? = when (route.trim('/')) {
        "chat/completions" -> WorkProtocol.OPENAI_CHAT_COMPLETIONS
        "messages" -> WorkProtocol.ANTHROPIC_MESSAGES
        "responses" -> WorkProtocol.OPENAI_RESPONSES
        else -> null
    }

    private fun providerApiKey(provider: ProviderSetting): String? = when (provider) {
        is ProviderSetting.OpenAI -> provider.apiKey.takeIf { it.isNotBlank() }
        is ProviderSetting.Claude -> provider.apiKey.takeIf { it.isNotBlank() }
        is ProviderSetting.Google -> null
    }

    private fun providerBaseUrl(provider: ProviderSetting): String? = when (provider) {
        is ProviderSetting.OpenAI -> provider.baseUrl.takeIf { it.isNotBlank() }
        is ProviderSetting.Claude -> provider.baseUrl.takeIf { it.isNotBlank() }
        is ProviderSetting.Google -> null
    }

    private fun forwardedHeaders(
        requestHeaders: Map<String, String>,
        protocol: WorkProtocol,
        apiKey: String,
    ): Map<String, String> = buildMap {
        requestHeaders.entries.firstOrNull { it.key.equals(HttpHeaders.Accept, ignoreCase = true) }?.let {
            put(HttpHeaders.Accept, it.value)
        }
        requestHeaders.entries.firstOrNull { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }?.let {
            put(HttpHeaders.ContentType, it.value)
        }
        when (protocol) {
            WorkProtocol.OPENAI_CHAT_COMPLETIONS,
            WorkProtocol.OPENAI_RESPONSES -> put(HttpHeaders.Authorization, "Bearer $apiKey")

            WorkProtocol.ANTHROPIC_MESSAGES -> {
                put("x-api-key", apiKey)
                val version = requestHeaders.entries.firstOrNull {
                    it.key.equals("anthropic-version", ignoreCase = true)
                }?.value ?: ANTHROPIC_VERSION
                put("anthropic-version", version)
                requestHeaders.entries.firstOrNull {
                    it.key.equals("anthropic-beta", ignoreCase = true)
                }?.let { put("anthropic-beta", it.value) }
            }
        }
    }

    private fun joinUrl(baseUrl: String, path: String): String =
        "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    private fun randomToken(): String = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    private fun sameToken(expected: String, actual: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        actual.toByteArray(Charsets.UTF_8),
    )

    private fun appendAudit(value: String) {
        audit.addLast(redact(value))
        while (audit.size > MAX_AUDIT_LINES) audit.removeFirst()
    }

    private fun redact(text: String): String = SECRET_PATTERN.replace(text) { match ->
        "${match.groupValues[1]}[REDACTED]"
    }

    private data class ActiveLease(
        val bindingId: Uuid,
        val token: String,
        val expiresAtMillis: Long,
    )

    private companion object {
        const val LEASE_HEADER = "X-Daddy-CodeHut-Lease"
        const val LEASE_DURATION_MILLIS = 2 * 60 * 60 * 1_000L
        const val TOKEN_BYTES = 32
        const val MAX_AUDIT_LINES = 50
        const val ANTHROPIC_VERSION = "2023-06-01"
        val secureRandom = SecureRandom()
        val SECRET_PATTERN = Regex(
            "(?i)([\\\"']?(?:api[_-]?key|token|authorization|secret)[\\\"']?\\s*[:=]\\s*)[^\\s,;]+",
        )
    }
}

interface CodeHutBridgeServer : AutoCloseable {
    suspend fun start(): Int
}

private class KtorCodeHutBridgeServer(
    private val bridge: CodeHutCredentialBridge,
) : CodeHutBridgeServer {
    private var server: EmbeddedServer<*, *>? = null

    override suspend fun start(): Int {
        val running = server ?: embeddedServer(CIO, host = LOOPBACK_HOST, port = 0) {
            bridge.configureBridgeRoutes(this)
        }.start(wait = false).also { server = it }
        return running.engine.resolvedConnectors().firstOrNull()?.port
            ?: error("代码小屋凭据桥没有分配本机端口。")
    }

    override fun close() {
        server?.stop(500, 1_000)
        server = null
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
    }
}
