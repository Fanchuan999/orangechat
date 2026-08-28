package me.rerere.rikkahub.data.lounge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitorLoungeEndpointPolicyTest {
    @Test
    fun `normalizes a public HTTPS MCP entrance without changing its path`() {
        val endpoint = VisitorLoungeEndpointPolicy.normalize("https://friend.example/mcp")

        assertEquals("https://friend.example/mcp", endpoint.toString())
    }

    @Test
    fun `rejects entrances that could disclose credentials or change protocol`() {
        assertRejected("http://friend.example/mcp")
        assertRejected("https://visitor-key@friend.example/mcp")
        assertRejected("https://friend.example/mcp?key=visitor-key")
        assertRejected("https://friend.example/mcp#invite")
        assertRejected("https:///mcp")
    }

    @Test
    fun `redactor removes endpoint query values and authorization values from display text`() {
        val redacted = VisitorLoungeRedactor.redact(
            "Request https://friend.example/mcp?key=visitor-key Authorization: Bearer visitor-key failed",
        )

        assertTrue(redacted.contains("[redacted endpoint query]"))
        assertTrue(redacted.contains("Authorization: Bearer [redacted]"))
        assertTrue(!redacted.contains("visitor-key"))
    }

    private fun assertRejected(raw: String) {
        val result = runCatching { VisitorLoungeEndpointPolicy.normalize(raw) }
        assertTrue("Expected $raw to be rejected", result.exceptionOrNull() is IllegalArgumentException)
    }
}
