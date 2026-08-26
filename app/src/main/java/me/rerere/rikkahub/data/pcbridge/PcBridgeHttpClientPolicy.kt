package me.rerere.rikkahub.data.pcbridge

import okhttp3.OkHttpClient

/**
 * PC bridge requests carry pairing material and relay credentials. Share the application's
 * transport resources while dropping all application and network interceptors before use.
 */
internal fun buildPcBridgeRelayHttpClient(source: OkHttpClient): OkHttpClient = source.newBuilder()
    .apply {
        interceptors().clear()
        networkInterceptors().clear()
        followRedirects(false)
        followSslRedirects(false)
    }
    .build()
