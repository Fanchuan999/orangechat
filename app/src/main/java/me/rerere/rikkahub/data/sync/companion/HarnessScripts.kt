/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import java.util.Base64

internal data class HarnessScriptFile(
    val path: String,
    val body: String,
)

internal object HarnessScripts {
    const val VERSION = "0.1.0-rc.7"
    const val BASE = "\$HOME/daddy-harness"
    const val WEB_URL = "http://127.0.0.1:3080"

    fun bootstrapCommands(resultPath: String): List<String> {
        val files = scriptFiles(resultPath)
        return buildList {
            add("mkdir -p \"$BASE\" \"\$HOME/.termux/boot\"")
            files.forEach { script -> add(writeScriptCommand(script)) }
            add(files.joinToString(prefix = "chmod 700 ", separator = " ") { expandableHomePath(it.path) })
            add("nohup \"$BASE/setup.sh\" ${shellQuote(resultPath)} >/dev/null 2>&1 &")
        }
    }

    fun startCommand(): String =
        "mkdir -p \"$BASE\" && rm -f \"$BASE/.manual-stop\" && " +
            "touch \"$BASE/.auto-keep-running\" && " +
            "nohup \"$BASE/watchdog.sh\" > \"$BASE/watchdog.log\" 2>&1 &"

    fun stopCommand(): String =
        "mkdir -p \"$BASE\" && touch \"$BASE/.manual-stop\" && " +
            "rm -f \"$BASE/.auto-keep-running\" && \"$BASE/stop.sh\""

    fun restartCommand(): String =
        "mkdir -p \"$BASE\" && touch \"$BASE/.manual-stop\" && " +
            "\"$BASE/stop.sh\" && rm -f \"$BASE/.manual-stop\" && " +
            "touch \"$BASE/.auto-keep-running\" && " +
            "nohup \"$BASE/watchdog.sh\" > \"$BASE/watchdog.log\" 2>&1 &"

    fun setAutoKeepRunningCommand(enabled: Boolean): String = if (enabled) {
        "mkdir -p \"$BASE\" && touch \"$BASE/.auto-keep-running\""
    } else {
        "rm -f \"$BASE/.auto-keep-running\""
    }

    fun scriptFiles(resultPath: String): List<HarnessScriptFile> = listOf(
        HarnessScriptFile("$BASE/run.sh", runScript()),
        HarnessScriptFile("$BASE/install.sh", installScript()),
        HarnessScriptFile("$BASE/watchdog.sh", watchdogScript()),
        HarnessScriptFile("$BASE/stop.sh", stopScript()),
        HarnessScriptFile("$BASE/setup.sh", setupScript()),
        HarnessScriptFile("\$HOME/.termux/boot/start-daddy-harness.sh", bootScript()),
    ).also {
        require(resultPath.isNotBlank()) { "Harness setup result path is required." }
    }

    private fun runScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -eu
        base="${'$'}HOME/daddy-harness"
        export DSH_HOME="${'$'}base/dsh-home"
        dsh="${'$'}base/runtime/node_modules/.bin/dsh"
        [ -x "${'$'}dsh" ] || { echo 'Harness executable is missing.'; exit 41; }
        cd "${'$'}HOME"
        exec node "${'$'}dsh" web --port 3080
    """.trimIndent() + "\n"

    private fun installScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -eu
        base="${'$'}HOME/daddy-harness"
        version="$VERSION"
        mkdir -p "${'$'}base/runtime" "${'$'}base/dsh-home"

        if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
          pkg install -y nodejs
        fi

        node_version="${'$'}(node -p 'process.versions.node')"
        node_major="${'$'}{node_version%%.*}"
        node_rest="${'$'}{node_version#*.}"
        node_minor="${'$'}{node_rest%%.*}"
        if (( node_major < 22 || node_major == 23 || (node_major == 22 && node_minor < 19) )); then
          echo "Unsupported Node ${'$'}node_version; Harness needs Node 22.19+ or 24+."
          exit 42
        fi

        dsh="${'$'}base/runtime/node_modules/.bin/dsh"
        installed_version="${'$'}(cat "${'$'}base/VERSION" 2>/dev/null || true)"
        if [ "${'$'}installed_version" != "${'$'}version" ] || [ ! -x "${'$'}dsh" ]; then
          npm install --prefix "${'$'}base/runtime" "@deepseek-ai/dsh@$VERSION"
          printf '%s' "${'$'}version" > "${'$'}base/VERSION"
        fi
    """.trimIndent() + "\n"

    private fun watchdogScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -u
        base="${'$'}HOME/daddy-harness"
        pid_file="${'$'}base/watchdog.pid"
        child_file="${'$'}base/harness.pid"

        old_pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null || true)"
        if [ -n "${'$'}old_pid" ] && [ "${'$'}old_pid" != "${'$'}${'$'}" ] && kill -0 "${'$'}old_pid" 2>/dev/null; then
          exit 0
        fi
        printf '%s' "${'$'}${'$'}" > "${'$'}pid_file"
        trap 'rm -f "${'$'}pid_file"' EXIT

        while [ -f "${'$'}base/.auto-keep-running" ] && [ ! -f "${'$'}base/.manual-stop" ]; do
          child="${'$'}(cat "${'$'}child_file" 2>/dev/null || true)"
          if [ -z "${'$'}child" ] || ! kill -0 "${'$'}child" 2>/dev/null; then
            nohup "${'$'}base/run.sh" >> "${'$'}base/harness.log" 2>&1 &
            child="${'$'}!"
            printf '%s' "${'$'}child" > "${'$'}child_file"
          fi

          while kill -0 "${'$'}child" 2>/dev/null &&
                [ -f "${'$'}base/.auto-keep-running" ] &&
                [ ! -f "${'$'}base/.manual-stop" ]; do
            sleep 3
          done

          [ -f "${'$'}base/.auto-keep-running" ] || break
          [ ! -f "${'$'}base/.manual-stop" ] || break
          printf '%s Harness exited; restarting.\n' "${'$'}(date '+%F %T')" >> "${'$'}base/watchdog.log"
          sleep 3
        done
    """.trimIndent() + "\n"

    private fun stopScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -u
        base="${'$'}HOME/daddy-harness"
        for file in "${'$'}base/watchdog.pid" "${'$'}base/harness.pid"; do
          pid="${'$'}(cat "${'$'}file" 2>/dev/null || true)"
          if [ -n "${'$'}pid" ] && kill -0 "${'$'}pid" 2>/dev/null; then
            kill "${'$'}pid" 2>/dev/null || true
          fi
          rm -f "${'$'}file"
        done
    """.trimIndent() + "\n"

    private fun setupScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -u
        base="${'$'}HOME/daddy-harness"
        result="${'$'}{1:?Missing result file}"
        web_url="$WEB_URL"
        mkdir -p "${'$'}(dirname "${'$'}result")"

        if "${'$'}base/install.sh" > "${'$'}base/setup.log" 2>&1; then
          rm -f "${'$'}base/.manual-stop"
          touch "${'$'}base/.auto-keep-running"
          nohup "${'$'}base/watchdog.sh" > "${'$'}base/watchdog.log" 2>&1 &
          for attempt in ${'$'}(seq 1 180); do
            if command -v curl >/dev/null 2>&1 && curl -fsS "${'$'}web_url" >/dev/null 2>&1; then
              printf '%s' 'ready' > "${'$'}result"
              exit 0
            fi
            if (echo > /dev/tcp/127.0.0.1/3080) >/dev/null 2>&1; then
              printf '%s' 'ready' > "${'$'}result"
              exit 0
            fi
            sleep 1
          done
        fi

        message="${'$'}(tail -n 1 "${'$'}base/setup.log" 2>/dev/null || tail -n 1 "${'$'}base/harness.log" 2>/dev/null || echo 'See setup.log')"
        printf 'error: %s' "${'$'}message" > "${'$'}result"
    """.trimIndent() + "\n"

    private fun bootScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -u
        base="${'$'}HOME/daddy-harness"
        [ -x "${'$'}base/watchdog.sh" ] || exit 0
        [ -f "${'$'}base/.auto-keep-running" ] || exit 0
        [ ! -f "${'$'}base/.manual-stop" ] || exit 0
        nohup "${'$'}base/watchdog.sh" > "${'$'}base/boot.log" 2>&1 &
    """.trimIndent() + "\n"

    private fun writeScriptCommand(script: HarnessScriptFile): String {
        val encoded = Base64.getEncoder().encodeToString(script.body.toByteArray(Charsets.UTF_8))
        return "printf %s ${shellQuote(encoded)} | base64 -d > ${expandableHomePath(script.path)}"
    }

    private fun expandableHomePath(path: String): String {
        require(path.matches(Regex("""^\${'$'}HOME/[A-Za-z0-9._/-]+$"""))) {
            "Harness script path must be a safe path below \$HOME"
        }
        return "\"$path\""
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
