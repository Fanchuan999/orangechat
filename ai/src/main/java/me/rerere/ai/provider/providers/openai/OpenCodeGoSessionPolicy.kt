package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.CustomHeader
import java.util.UUID

/** Adds the routing header required by the official OpenCode Go endpoint only. */
object OpenCodeGoSessionPolicy {
    private const val OPEN_CODE_GO_BASE_URL = "https://opencode.ai/zen/go/v1"
    private const val SESSION_HEADER = "x-opencode-session"

    fun apply(
        baseUrl: String,
        headers: List<CustomHeader>,
        requestSessionId: String?,
        sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    ): List<CustomHeader> {
        if (baseUrl.trim().trimEnd('/') != OPEN_CODE_GO_BASE_URL) return headers

        val sessionId = requestSessionId?.takeIf(String::isNotBlank) ?: sessionIdFactory()
        return headers.filterNot { it.name.equals(SESSION_HEADER, ignoreCase = true) } +
            CustomHeader(SESSION_HEADER, sessionId)
    }
}
