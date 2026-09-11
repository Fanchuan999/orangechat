package me.rerere.rikkahub.data.ai.mcp.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class McpTransportLogFormatterTest {
    @Test
    fun `outgoing diagnostics omit raw MCP JSON and endpoint credentials`() {
        val secret = "outgoing-secret"
        val log = formatMcpRequestLog(
            url = "https://mcp.example/tools?access_token=$secret",
            payload = "{\"arguments\":{\"token\":\"$secret\"}}",
        )

        assertEquals("Client sending MCP message via POST", log)
        assertFalse(log.contains(secret))
    }

    @Test
    fun `incoming diagnostics omit raw SSE data`() {
        val secret = "incoming-secret"
        val log = formatMcpSseLog(
            event = "message",
            data = "{\"access_token\":\"$secret\"}",
            id = "event-42",
        )

        assertEquals("Client received SSE event: event=message, id=event-42", log)
        assertFalse(log.contains(secret))
    }

    @Test
    fun `SSE error diagnostics omit remote data`() {
        val secret = "sse-error-secret"
        val log = formatMcpSseErrorLog("{\"access_token\":\"$secret\"}")

        assertEquals("MCP SSE server reported an error", log)
        assertFalse(log.contains(secret))
    }

    @Test
    fun `transport failure diagnostics omit exception details`() {
        val secret = "transport-error-secret"
        val log = formatMcpTransportFailureLog(
            operation = "starting SSE session",
            error = IllegalStateException("Remote error: $secret"),
        )

        assertEquals("MCP transport failed while starting SSE session (IllegalStateException)", log)
        assertFalse(log.contains(secret))
    }
}
