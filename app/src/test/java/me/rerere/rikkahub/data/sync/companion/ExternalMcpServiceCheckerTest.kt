package me.rerere.rikkahub.data.sync.companion

import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalMcpServiceCheckerTest {
    @Test
    fun `finds only enabled loopback MCP endpoints`() {
        val settings = Settings(
            mcpServers = listOf(
                McpServerConfig.StreamableHTTPServer(
                    commonOptions = McpCommonOptions(name = "love connect"),
                    url = "http://127.0.0.1:5000/mcp",
                ),
                McpServerConfig.SseTransportServer(
                    commonOptions = McpCommonOptions(name = "remote"),
                    url = "https://example.com/sse",
                ),
                McpServerConfig.StreamableHTTPServer(
                    commonOptions = McpCommonOptions(name = "disabled", enable = false),
                    url = "http://localhost:8000/mcp",
                ),
            ),
        )

        val endpoints = ExternalMcpServiceChecker().localEndpoints(settings)

        assertEquals(1, endpoints.size)
        assertEquals("love connect", endpoints.single().name)
        assertEquals("127.0.0.1", endpoints.single().host)
        assertEquals(5000, endpoints.single().port)
    }

    @Test
    fun `uses protocol default port for a local MCP endpoint`() {
        val settings = Settings(
            mcpServers = listOf(
                McpServerConfig.StreamableHTTPServer(
                    commonOptions = McpCommonOptions(name = "local"),
                    url = "https://localhost/mcp",
                ),
            ),
        )

        val endpoint = ExternalMcpServiceChecker().localEndpoints(settings).single()

        assertEquals(443, endpoint.port)
    }
}
