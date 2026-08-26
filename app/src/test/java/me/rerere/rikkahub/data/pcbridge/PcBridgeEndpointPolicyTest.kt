package me.rerere.rikkahub.data.pcbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcBridgeEndpointPolicyTest {
    @Test
    fun `endpoint accepts exact Supabase relay URL`() {
        val endpoint = PcBridgeEndpointPolicy.requireExactRelayEndpoint(
            "https://project.supabase.co/functions/v1/daddy-pc-bridge",
        )

        assertEquals("project.supabase.co", endpoint.host)
    }

    @Test
    fun `endpoint forbids unsafe relay URLs`() {
        val unsafeUrls = listOf(
            "http://project.supabase.co/functions/v1/daddy-pc-bridge",
            "https://supabase.co/functions/v1/daddy-pc-bridge",
            "https://project.supabase.co/functions/v1/daddy-pc-bridge/",
            "https://project.supabase.co/functions/v1/daddy-pc-bridge?next=https://evil.example",
            "https://user:pass@project.supabase.co/functions/v1/daddy-pc-bridge",
            "https://project.supabase.co/functions/v1/daddy-pc-bridge#fragment",
            "https://project.supabase.co:444/functions/v1/daddy-pc-bridge",
            "https://project.supabase.co:443/functions/v1/daddy-pc-bridge",
            "https://project.supabase.co/functions/v1/%64addy-pc-bridge",
            "https://project.supabase.co/functions/v1/other/../daddy-pc-bridge",
            "https://project.supabase.co/functions/v1\\daddy-pc-bridge",
            "HTTPS://project.supabase.co/functions/v1/daddy-pc-bridge",
            "https://project.supabase.co.evil.example/functions/v1/daddy-pc-bridge",
        )

        unsafeUrls.forEach {
            assertThrows(IllegalArgumentException::class.java) { PcBridgeEndpointPolicy.requireExactRelayEndpoint(it) }
        }
    }
}
