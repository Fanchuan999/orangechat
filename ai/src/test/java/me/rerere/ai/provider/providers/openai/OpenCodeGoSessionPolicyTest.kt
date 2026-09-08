package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.CustomHeader
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeGoSessionPolicyTest {
    @Test
    fun `Go provider replaces a static session header with the conversation session`() {
        val result = OpenCodeGoSessionPolicy.apply(
            baseUrl = "https://opencode.ai/zen/go/v1/",
            headers = listOf(
                CustomHeader("X-App", "OrangeChat"),
                CustomHeader("x-opencode-session", "static-value"),
            ),
            requestSessionId = "conversation-uuid",
        )

        assertEquals(
            listOf(
                CustomHeader("X-App", "OrangeChat"),
                CustomHeader("x-opencode-session", "conversation-uuid"),
            ),
            result,
        )
    }

    @Test
    fun `other provider preserves custom headers unchanged`() {
        val headers = listOf(CustomHeader("x-opencode-session", "user-value"))

        assertEquals(
            headers,
            OpenCodeGoSessionPolicy.apply(
                baseUrl = "https://api.siliconflow.cn/v1",
                headers = headers,
                requestSessionId = "conversation-uuid",
            ),
        )
    }

    @Test
    fun `Go provider generates a session for internal requests without one`() {
        val result = OpenCodeGoSessionPolicy.apply(
            baseUrl = "https://opencode.ai/zen/go/v1",
            headers = emptyList(),
            requestSessionId = null,
            sessionIdFactory = { "internal-request-id" },
        )

        assertEquals(listOf(CustomHeader("x-opencode-session", "internal-request-id")), result)
    }
}
