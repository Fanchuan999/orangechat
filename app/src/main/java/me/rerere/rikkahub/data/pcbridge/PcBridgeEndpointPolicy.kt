package me.rerere.rikkahub.data.pcbridge

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object PcBridgeEndpointPolicy {
    private const val RELAY_PATH = "/functions/v1/daddy-pc-bridge"

    fun requireExactRelayEndpoint(value: String): HttpUrl {
        val endpoint = value.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid PC bridge endpoint")

        require(endpoint.scheme == "https") { "PC bridge endpoint must use HTTPS" }
        require(endpoint.port == 443) { "PC bridge endpoint must use the default HTTPS port" }
        require(endpoint.host.endsWith(".supabase.co") && endpoint.host.removeSuffix(".supabase.co").isNotEmpty()) {
            "PC bridge endpoint must use a Supabase project host"
        }
        require(endpoint.encodedPath == RELAY_PATH) { "PC bridge endpoint path is not allowed" }
        require(endpoint.encodedUsername.isEmpty() && endpoint.encodedPassword.isEmpty()) {
            "PC bridge endpoint must not contain credentials"
        }
        require(endpoint.query == null && endpoint.fragment == null) {
            "PC bridge endpoint must not contain a query or fragment"
        }

        return endpoint
    }
}
