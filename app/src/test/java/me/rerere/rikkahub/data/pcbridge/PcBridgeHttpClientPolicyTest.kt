package me.rerere.rikkahub.data.pcbridge

import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PcBridgeHttpClientPolicyTest {
    @Test
    fun `relay client shares transport resources without request or header logging`() {
        val sharedClient = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor())
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .build()

        val relayClient = buildPcBridgeRelayHttpClient(sharedClient)

        assertSame(sharedClient.connectionPool, relayClient.connectionPool)
        assertTrue(sharedClient.interceptors.any { it is HttpLoggingInterceptor })
        assertTrue(sharedClient.networkInterceptors.any { it is RequestLoggingInterceptor })
        assertFalse(relayClient.interceptors.any { it is HttpLoggingInterceptor || it is RequestLoggingInterceptor })
        assertFalse(relayClient.networkInterceptors.any { it is HttpLoggingInterceptor || it is RequestLoggingInterceptor })
    }
}
