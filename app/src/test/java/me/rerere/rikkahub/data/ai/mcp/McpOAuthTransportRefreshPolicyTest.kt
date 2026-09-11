package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthTransportRefreshPolicyTest {
    @Test
    fun `refreshed OAuth access token requires a new transport`() {
        val connected = oauthServer(accessToken = "expired-access-token", expiresAt = 1L)
        val refreshed = oauthServer(accessToken = "fresh-access-token", expiresAt = 2L)

        assertTrue(requiresMcpTransportReconnect(connected, refreshed))
    }

    @Test
    fun `unchanged OAuth access token keeps the existing transport`() {
        val connected = oauthServer(accessToken = "same-access-token", expiresAt = 1L)
        val refreshed = oauthServer(accessToken = "same-access-token", expiresAt = 2L)

        assertFalse(requiresMcpTransportReconnect(connected, refreshed))
    }

    @Test
    fun `Todoist unauthorized tool result needs OAuth recovery`() {
        val result = CallToolResult(
            content = listOf(TextContent("""{"error_tag":"UNAUTHORIZED","error_code":477}""")),
            isError = true,
        )

        assertTrue(isMcpToolAuthorizationFailure(result))
    }

    @Test
    fun `ordinary MCP tool errors do not trigger OAuth recovery`() {
        val result = CallToolResult(
            content = listOf(TextContent("The task title cannot be empty.")),
            isError = true,
        )

        assertFalse(isMcpToolAuthorizationFailure(result))
    }

    private fun oauthServer(accessToken: String, expiresAt: Long): McpServerConfig.StreamableHTTPServer =
        McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(
                oauth = McpOAuthState(
                    enabled = true,
                    accessToken = accessToken,
                    refreshToken = "refresh-token",
                    expiresAt = expiresAt,
                ),
            ),
            url = "https://mcp.example.test",
        )
}
