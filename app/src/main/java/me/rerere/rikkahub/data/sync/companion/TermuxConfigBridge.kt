/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Uses the installed local termux-bridge server first, then Termux's documented RunCommand bridge as a fallback.
 * Personal complete backups intentionally include the user's local service configuration and credentials.
 */
class TermuxConfigBridge(
    private val context: Context,
) {
    /**
     * Runs a small, Daddy-owned setup job in Termux and waits for its result file.
     * The job itself is normally backgrounded, so long package installs do not hold
     * the local bridge HTTP request open.
     */
    suspend fun executeAndWait(
        command: String,
        completionFile: File,
        timeoutMessage: String,
        waitAttempts: Int,
    ) = withContext(Dispatchers.IO) {
        executeWithFallback(
            command = command,
            completionFile = completionFile,
            timeoutMessage = timeoutMessage,
            bridgeWaitAttempts = waitAttempts,
            fallbackWaitAttempts = waitAttempts,
        )
    }

    /**
     * Uses Termux's documented RunCommand entry point directly. This is more
     * reliable than the tiny local bridge for a first-time setup command that
     * creates several files and installs an isolated Python environment.
     */
    suspend fun executeDirectlyAndWait(
        command: String,
        completionFile: File,
        timeoutMessage: String,
        waitAttempts: Int,
    ) = withContext(Dispatchers.IO) {
        require(isTermuxInstalled()) { "没有检测到可用的 Termux。" }
        launchCommand(command = command, workDir = TERMUX_HOME)
        waitForFile(completionFile, timeoutMessage, waitAttempts)
    }

    suspend fun exportConfigArchive(): File = withContext(Dispatchers.IO) {
        val output = sharedFile("termux_config_${UUID.randomUUID()}.tar.gz")
        executeWithFallback(
            command = exportCommand(output),
            completionFile = output,
            timeoutMessage = "Termux 配置归档未在 60 秒内生成。请确认 termux-bridge 服务正在运行。",
        )
        output
    }

    suspend fun restoreConfigArchive(source: File) = withContext(Dispatchers.IO) {
        require(source.exists() && source.length() > 0L) { "Termux 配置归档不存在或为空。" }

        val id = UUID.randomUUID().toString()
        val sharedArchive = sharedFile("termux_restore_$id.tar.gz")
        val completion = sharedFile("termux_restore_$id.done")
        source.copyTo(sharedArchive, overwrite = false)

        try {
            executeWithFallback(
                command = restoreCommand(sharedArchive, completion, id),
                completionFile = completion,
                timeoutMessage = "Termux 配置未在 60 秒内恢复。请确认 termux-bridge 服务正在运行。",
            )
        } finally {
            sharedArchive.delete()
            completion.delete()
        }
    }

    private fun isTermuxInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
    }.isSuccess

    private fun sharedFile(name: String): File {
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val directory = File(documents, "OrangeChat/companion")
        check(directory.exists() || directory.mkdirs()) { "无法创建 Documents/OrangeChat/companion。" }
        return File(directory, name)
    }

    private suspend fun executeWithFallback(
        command: String,
        completionFile: File,
        timeoutMessage: String,
        bridgeWaitAttempts: Int = BRIDGE_WAIT_ATTEMPTS,
        fallbackWaitAttempts: Int = FILE_WAIT_ATTEMPTS,
    ) {
        val bridgeFailure = runCatching {
            runWithLocalBridge(command)
            waitForFile(completionFile, timeoutMessage, bridgeWaitAttempts)
        }.exceptionOrNull()
        if (bridgeFailure == null) return

        require(isTermuxInstalled()) {
            "termux-bridge 不可用，且未检测到可回退的 Termux。${bridgeFailure.message.orEmpty()}"
        }
        launchCommand(command = command, workDir = TERMUX_HOME)
        waitForFile(
            file = completionFile,
            timeoutMessage = "$timeoutMessage 同时，Termux 官方命令接口也未能完成。",
            attempts = fallbackWaitAttempts,
        )
    }

    private fun runWithLocalBridge(command: String) {
        val connection = (URL("$LOCAL_BRIDGE_BASE/run").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/plain")
            connectTimeout = BRIDGE_CONNECT_TIMEOUT_MS
            readTimeout = BRIDGE_READ_TIMEOUT_MS
            doOutput = true
        }
        try {
            connection.outputStream.bufferedWriter().use { writer ->
                writer.write(JSONObject().put("cmd", command).toString())
            }
            if (connection.responseCode !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("termux-bridge 请求失败（HTTP ${connection.responseCode}）：${body.take(200)}")
            }
            connection.inputStream.close()
        } finally {
            connection.disconnect()
        }
    }

    private fun launchCommand(command: String, workDir: String) {
        val intent = Intent(ACTION_RUN_COMMAND).apply {
            component = ComponentName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_WORKDIR, workDir)
            putExtra(EXTRA_BACKGROUND, true)
        }
        try {
            context.startService(intent)
        } catch (exception: SecurityException) {
            throw IllegalStateException(
                "Termux 拒绝了外部命令。请在 Termux 的 ~/.termux/termux.properties 添加 " +
                    "allow-external-apps=true，重启 Termux，并在系统设置中允许橘瓣运行 Termux 命令。",
                exception,
            )
        }
    }

    private suspend fun waitForFile(file: File, timeoutMessage: String, attempts: Int) {
        repeat(attempts) {
            if (file.exists() && file.length() > 0L) return
            delay(FILE_WAIT_INTERVAL_MS)
        }
        throw IllegalStateException(timeoutMessage)
    }

    private fun exportCommand(output: File): String = """
        set -eu
        output='${output.absolutePath}'
        temporary="${'$'}output.tmp"
        mkdir -p "${'$'}(dirname "${'$'}output")"
        items=""
        for directory in .termux .shortcuts bin termux-mcp daddy-amap; do
          if [ -e "${'$'}HOME/${'$'}directory" ]; then
            if [ "${'$'}directory" = termux-mcp ]; then
              links="${'$'}(find "${'$'}HOME/${'$'}directory" \
                -path "${'$'}HOME/termux-mcp/node_modules/.bin" -prune -o -type l -print -quit)"
            else
              links="${'$'}(find "${'$'}HOME/${'$'}directory" -type l -print -quit)"
            fi
            if [ -n "${'$'}links" ]; then
              echo "Refusing to archive symbolic links in ${'$'}directory" >&2
              exit 12
            fi
            items="${'$'}items ${'$'}directory"
          fi
        done
        for file in .Ombre-Brain/.env Ombre-Brain/config.yaml termux_bridge.py start_services.sh; do
          if [ -f "${'$'}HOME/${'$'}file" ]; then
            if [ -L "${'$'}HOME/${'$'}file" ]; then
              echo "Refusing to archive symbolic link ${'$'}file" >&2
              exit 13
            fi
            items="${'$'}items ${'$'}file"
          fi
        done
        [ -n "${'$'}items" ] || { echo "No Termux files found for backup" >&2; exit 14; }
        tar -C "${'$'}HOME" \
          --exclude='termux-mcp/node_modules/.bin' \
          -czf "${'$'}temporary" ${'$'}items
        mv "${'$'}temporary" "${'$'}output"
    """.trimIndent()

    private fun restoreCommand(archive: File, completion: File, id: String): String = """
        set -eu
        archive='${archive.absolutePath}'
        completion='${completion.absolutePath}'
        staging="${'$'}HOME/.cache/orangechat-restore-$id"
        tar -tzf "${'$'}archive" | while IFS= read -r path; do
          case "${'$'}path" in
            .termux|.termux/*|.shortcuts|.shortcuts/*|bin|bin/*|termux-mcp|termux-mcp/*|daddy-amap|daddy-amap/*|\
            .Ombre-Brain/.env|Ombre-Brain/config.yaml|termux_bridge.py|start_services.sh) ;;
            *) echo "Unsafe Termux archive path: ${'$'}path" >&2; exit 21 ;;
          esac
        done
        tar -tvzf "${'$'}archive" | awk '${'$'}1 !~ /^[-d]/ { exit 1 }'
        mkdir -p "${'$'}staging"
        tar -xzf "${'$'}archive" -C "${'$'}staging"
        if find "${'$'}staging" -type l -print -quit | grep -q .; then
          echo "Unsafe symbolic link in Termux archive" >&2
          exit 22
        fi
        for directory in .termux .shortcuts bin termux-mcp daddy-amap; do
          if [ -d "${'$'}staging/${'$'}directory" ]; then
            mkdir -p "${'$'}HOME/${'$'}directory"
            cp -R "${'$'}staging/${'$'}directory/." "${'$'}HOME/${'$'}directory/"
          fi
        done
        if [ -f "${'$'}staging/.Ombre-Brain/.env" ]; then
          mkdir -p "${'$'}HOME/.Ombre-Brain"
          cp "${'$'}staging/.Ombre-Brain/.env" "${'$'}HOME/.Ombre-Brain/.env"
        fi
        if [ -f "${'$'}staging/Ombre-Brain/config.yaml" ]; then
          mkdir -p "${'$'}HOME/Ombre-Brain"
          cp "${'$'}staging/Ombre-Brain/config.yaml" "${'$'}HOME/Ombre-Brain/config.yaml"
        fi
        for file in termux_bridge.py start_services.sh; do
          if [ -f "${'$'}staging/${'$'}file" ]; then
            cp "${'$'}staging/${'$'}file" "${'$'}HOME/${'$'}file"
          fi
        done
        if [ -x "${'$'}HOME/daddy-amap/install-amap-mcp.sh" ]; then
          nohup "${'$'}HOME/daddy-amap/install-amap-mcp.sh" \
            > "${'$'}HOME/daddy-amap/restore.log" 2>&1 &
        fi
        rm -rf "${'$'}staging"
        : > "${'$'}completion"
    """.trimIndent()

    private companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"
        const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"

        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

        const val FILE_WAIT_INTERVAL_MS = 750L
        const val FILE_WAIT_ATTEMPTS = 80
        const val BRIDGE_WAIT_ATTEMPTS = 12
        const val BRIDGE_CONNECT_TIMEOUT_MS = 3_000
        const val BRIDGE_READ_TIMEOUT_MS = 30_000
        const val LOCAL_BRIDGE_BASE = "http://127.0.0.1:8080"
    }
}
