/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.WorkModelBinding
import me.rerere.rikkahub.data.datastore.WorkProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CodeHutCredentialBridgeTest {
    private val binding = WorkModelBinding(
        id = Uuid.parse("a23b4400-021a-46d6-a20f-3000e8980010"),
        providerId = Uuid.parse("a23b4400-021a-46d6-a20f-3000e8980011"),
        modelId = Uuid.parse("a23b4400-021a-46d6-a20f-3000e8980012"),
        protocol = WorkProtocol.ANTHROPIC_MESSAGES,
    )

    @Test
    fun missingLeaseTokenIsRejectedBeforeProviderLookup() = runBlocking {
        val resolver = RecordingResolver(binding)
        val bridge = newBridge(resolver)
        try {
            bridge.startLease(binding.id)

            val decision = bridge.prepareForward(
                leaseToken = null,
                bindingId = binding.id.toString(),
                route = "messages",
                requestHeaders = emptyMap(),
            )

            assertDecision<BridgeForwardDecision.Unauthorized>(decision)
            assertEquals(0, resolver.resolveCalls)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun validLeaseForwardsSavedKeyWithoutExposingItInAuditLog() = runBlocking {
        val resolver = RecordingResolver(binding)
        val bridge = newBridge(resolver)
        try {
            val lease = bridge.startLease(binding.id)

            val decision = bridge.prepareForward(
                leaseToken = lease.token,
                bindingId = binding.id.toString(),
                route = "messages",
                requestHeaders = mapOf("anthropic-version" to "2023-06-01"),
            )

            val forward = assertDecision<BridgeForwardDecision.Forward>(decision).request
            assertEquals("https://go.example.test/v1/messages", forward.url)
            assertEquals("saved-go-key", forward.headers["x-api-key"])
            assertEquals("2023-06-01", forward.headers["anthropic-version"])
            assertFalse(bridge.redactedAuditLog().contains("saved-go-key"))
        } finally {
            bridge.close()
        }
    }

    @Test
    fun leaseCannotCallOtherProtocolRouteOrSurviveRevocation() = runBlocking {
        val resolver = RecordingResolver(binding)
        val bridge = newBridge(resolver)
        try {
            val lease = bridge.startLease(binding.id)

            assertDecision<BridgeForwardDecision.Rejected>(
                bridge.prepareForward(lease.token, binding.id.toString(), "chat/completions", emptyMap()),
            )
            bridge.revokeLease()
            assertDecision<BridgeForwardDecision.Unauthorized>(
                bridge.prepareForward(lease.token, binding.id.toString(), "messages", emptyMap()),
            )
            Unit
        } finally {
            bridge.close()
        }
    }

    private class RecordingResolver(
        private val binding: WorkModelBinding,
    ) : CodeHutProviderResolver {
        var resolveCalls = 0

        override fun resolve(bindingId: Uuid): ResolvedWorkProvider? {
            resolveCalls += 1
            if (bindingId != binding.id) return null
            return ResolvedWorkProvider(
                binding = binding,
                provider = ProviderSetting.Claude(
                    id = binding.providerId,
                    apiKey = "saved-go-key",
                    baseUrl = "https://go.example.test/v1",
                ),
                model = Model(id = binding.modelId, modelId = "minimax-m3"),
            )
        }
    }

    private fun newBridge(resolver: CodeHutProviderResolver): CodeHutCredentialBridge =
        CodeHutCredentialBridge(
            providerResolver = resolver,
            upstreamClient = { error("Unit tests do not make an upstream request.") },
            serverFactory = { FixedPortServer },
        )

    private data object FixedPortServer : CodeHutBridgeServer {
        override suspend fun start(): Int = 38_181
        override fun close() = Unit
    }

    private inline fun <reified T> assertDecision(value: Any): T {
        assertTrue("Expected ${T::class.simpleName}, got ${value::class.simpleName}", value is T)
        return value as T
    }
}
