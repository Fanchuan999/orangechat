/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import android.content.Context
import android.os.Environment
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.AmapRouteSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * Installs the small Amap MCP process beside Ombre in Termux, always on the
 * loopback-only port 8001. The key intentionally remains part of the user's
 * local Daddy + Termux backup, matching the companion backup choice.
 */
class AmapMcpService(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val termuxConfigBridge: TermuxConfigBridge,
) {
    suspend fun saveAndInstall(apiKey: String): Result<Unit> = runCatching {
        val cleanedKey = apiKey.trim()
        require(cleanedKey.isValidAmapKey()) { "高德 Key 格式不正确，请粘贴“Web 服务”类型的 Key。" }

        settingsStore.update { settings ->
            val existing = settings.mcpServers.firstOrNull { server ->
                server.commonOptions.name == MCP_DISPLAY_NAME || serverUrl(server) == MCP_URL
            }
            val server = McpServerConfig.StreamableHTTPServer(
                id = existing?.id ?: kotlin.uuid.Uuid.random(),
                commonOptions = McpCommonOptions(enable = true, name = MCP_DISPLAY_NAME),
                url = MCP_URL,
            )
            val selectedAssistant = settings.assistants.firstOrNull { it.id == settings.assistantId }
            settings.copy(
                mcpServers = settings.mcpServers.filterNot { it.id == server.id } + server,
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == selectedAssistant?.id) {
                        assistant.copy(mcpServers = assistant.mcpServers + server.id)
                    } else {
                        assistant
                    }
                },
                companionSpaceSetting = settings.companionSpaceSetting.copy(
                    amapRouteSetting = AmapRouteSetting(
                        apiKey = cleanedKey,
                        configuredAtMillis = System.currentTimeMillis(),
                    )
                ),
            )
        }

        val resultFile = resultFile()
        resultFile.delete()
        termuxConfigBridge.executeViaLocalBridgeAndWait(
            commands = bridgeBootstrapCommands(cleanedKey, resultFile),
            completionFile = resultFile,
            timeoutMessage = "高德路线服务在四分钟内没有准备好。请打开 Termux 看看 ~/daddy-amap/setup.log。",
            waitAttempts = SETUP_WAIT_ATTEMPTS,
        )
        val result = resultFile.readText().trim()
        resultFile.delete()
        require(result == READY_MARKER) {
            if (result.startsWith("error:")) {
                "高德路线服务没有启动：${result.removePrefix("error:").trim()}"
            } else {
                "高德路线服务没有正确完成安装，请打开 Termux 查看 ~/daddy-amap/setup.log。"
            }
        }
    }

    private fun resultFile(): File {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "OrangeChat/companion",
        )
        check(directory.exists() || directory.mkdirs()) { "无法创建 Daddy 的联动配置目录。" }
        return File(directory, "amap_setup_${UUID.randomUUID()}.result")
    }

    /**
     * The older local bridge is dependable for short commands but silently
     * drops a very large one. Write the four tiny scripts separately, then
     * launch the long-running installer in the background.
     */
    private fun bridgeBootstrapCommands(apiKey: String, resultFile: File): List<String> {
        val base = "${'$'}HOME/daddy-amap"
        val boot = "${'$'}HOME/.termux/boot"
        return listOf(
            "mkdir -p \"$base\" \"$boot\"",
            "printf '%s\\n' ${shellQuote("AMAP_MAPS_API_KEY=$apiKey")} > \"$base/.env\" && chmod 600 \"$base/.env\"",
            writeScriptCommand("$base/start-amap-mcp.sh", startScript()),
            writeScriptCommand("$base/install-amap-mcp.sh", installScript()),
            writeScriptCommand("$base/setup-amap-mcp.sh", setupScript()),
            writeScriptCommand("$boot/start-daddy-amap.sh", bootScript()),
            "chmod 700 \"$base/start-amap-mcp.sh\" \"$base/install-amap-mcp.sh\" \"$base/setup-amap-mcp.sh\" \"$boot/start-daddy-amap.sh\"",
            "nohup \"$base/setup-amap-mcp.sh\" ${shellQuote(resultFile.absolutePath)} >/dev/null 2>&1 &",
        )
    }

    private fun writeScriptCommand(path: String, script: String): String {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        return "printf %s ${shellQuote(encoded)} | base64 -d > \"$path\""
    }

    private fun startScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -eu
        base="${'$'}HOME/daddy-amap"
        . "${'$'}base/.env"
        export FASTMCP_HOST=127.0.0.1
        export FASTMCP_PORT=8001
        exec "${'$'}base/venv/bin/python" -m amap_mcp_server streamable-http
    """.trimIndent() + "\n"

    private fun installScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -eu
        base="${'$'}HOME/daddy-amap"
        mkdir -p "${'$'}base"
        command -v python >/dev/null 2>&1 || { echo 'Termux 里没有 Python；请先安装 Python。'; exit 31; }
        if [ ! -x "${'$'}base/venv/bin/python" ]; then
          python -m venv "${'$'}base/venv"
        fi
        if ! "${'$'}base/venv/bin/python" -c 'import amap_mcp_server' >/dev/null 2>&1; then
          "${'$'}base/venv/bin/python" -m pip install --disable-pip-version-check amap-mcp-server
        fi
        if [ -f "${'$'}base/amap-mcp.pid" ] && kill -0 "${'$'}(cat "${'$'}base/amap-mcp.pid")" 2>/dev/null; then
          kill "${'$'}(cat "${'$'}base/amap-mcp.pid")" || true
          sleep 1
        fi
        nohup "${'$'}base/start-amap-mcp.sh" > "${'$'}base/amap-mcp.log" 2>&1 &
        echo "${'$'}!" > "${'$'}base/amap-mcp.pid"
    """.trimIndent() + "\n"

    private fun setupScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -u
        base="${'$'}HOME/daddy-amap"
        result="${'$'}{1:?Missing result file}"
        mkdir -p "${'$'}(dirname "${'$'}result")"
        if "${'$'}base/install-amap-mcp.sh" > "${'$'}base/setup.log" 2>&1; then
          for attempt in ${'$'}(seq 1 200); do
            if (echo > /dev/tcp/127.0.0.1/8001) >/dev/null 2>&1; then
              printf '%s' '$READY_MARKER' > "${'$'}result"
              exit 0
            fi
            sleep 1
          done
        fi
        message="${'$'}(tail -n 1 "${'$'}base/setup.log" 2>/dev/null || echo '请查看 setup.log')"
        printf 'error: %s' "${'$'}message" > "${'$'}result"
    """.trimIndent() + "\n"

    private fun bootScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -eu
        base="${'$'}HOME/daddy-amap"
        [ -x "${'$'}base/install-amap-mcp.sh" ] || exit 0
        nohup "${'$'}base/install-amap-mcp.sh" > "${'$'}base/boot.log" 2>&1 &
    """.trimIndent() + "\n"

    private fun serverUrl(server: McpServerConfig): String = when (server) {
        is McpServerConfig.SseTransportServer -> server.url
        is McpServerConfig.StreamableHTTPServer -> server.url
    }

    private fun String.isValidAmapKey(): Boolean = length in 16..128 && all { char ->
        char.isLetterOrDigit() || char == '_' || char == '-'
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"

    private companion object {
        const val MCP_DISPLAY_NAME = "Daddy · 高德路线"
        const val MCP_URL = "http://127.0.0.1:8001/mcp"
        const val READY_MARKER = "daddy-amap-ready"
        const val SETUP_WAIT_ATTEMPTS = 320
    }
}
