package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class McpManagerLogFormatterTest {
    @Test
    fun `configuration diagnostics omit configured authorization values`() {
        val secret = "configured-secret"
        val config = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(
                headers = listOf("Authorization" to "Bearer $secret"),
                oauth = McpOAuthState(accessToken = secret, refreshToken = secret),
            ),
            url = "https://mcp.example?access_token=$secret",
        )

        val log = formatMcpConfigUpdateLog(listOf(config))

        assertEquals("MCP configuration update received for 1 server(s)", log)
        assertFalse(log.contains(secret))
    }

    @Test
    fun `transport error diagnostics omit remote error contents`() {
        val secret = "remote-error-secret"
        val log = formatMcpTransportErrorLog("Visitor MCP", IllegalStateException("Remote error: $secret"))

        assertEquals("MCP transport error for Visitor MCP (IllegalStateException)", log)
        assertFalse(log.contains(secret))
    }
}
