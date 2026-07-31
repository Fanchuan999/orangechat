/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.mcp.serverUrl
import me.rerere.rikkahub.data.datastore.Settings
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/** Reachability result for an MCP service supplied by another locally installed app. */
data class ExternalMcpServiceStatus(
    val name: String,
    val url: String,
    val isReachable: Boolean,
    val detail: String,
)

/**
 * Checks only loopback MCP endpoints after restoring their configuration.
 *
 * A TCP connection intentionally verifies that the companion app is listening without sending an MCP request,
 * which avoids creating a session or invoking any tool during restore.
 */
internal class ExternalMcpServiceChecker {
    suspend fun check(settings: Settings): List<ExternalMcpServiceStatus> = withContext(Dispatchers.IO) {
        localEndpoints(settings).map(::checkEndpoint)
    }

    internal fun localEndpoints(settings: Settings): List<LocalMcpEndpoint> {
        return settings.mcpServers.mapNotNull { server ->
            if (!server.commonOptions.enable) return@mapNotNull null

            val url = server.serverUrl.trim()
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            val host = uri.host?.trim()?.trim('[', ']') ?: return@mapNotNull null
            if (uri.scheme !in setOf("http", "https") || host !in LOOPBACK_HOSTS) return@mapNotNull null

            LocalMcpEndpoint(
                name = server.commonOptions.name.ifBlank { "未命名本机 MCP" },
                url = url,
                host = host,
                port = uri.port.takeIf { it != -1 } ?: defaultPort(uri.scheme),
            )
        }
    }

    private fun checkEndpoint(endpoint: LocalMcpEndpoint): ExternalMcpServiceStatus {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
            }
        }.fold(
            onSuccess = {
                ExternalMcpServiceStatus(
                    name = endpoint.name,
                    url = endpoint.url,
                    isReachable = true,
                    detail = "本机服务端口已连通。",
                )
            },
            onFailure = {
                ExternalMcpServiceStatus(
                    name = endpoint.name,
                    url = endpoint.url,
                    isReachable = false,
                    detail = "本机服务未启动；请打开或安装对应的外部 APK 后重试。",
                )
            },
        )
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

    internal data class LocalMcpEndpoint(
        val name: String,
        val url: String,
        val host: String,
        val port: Int,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
    }
}
