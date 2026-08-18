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
    const val VERSION = HarnessRuntimeContract.HARNESS_VERSION
    const val BASE = HarnessRuntimeContract.BASE
    const val WEB_URL = "http://127.0.0.1:3080"

    private const val SCRIPTS = "$BASE/scripts"
    private const val SERVICES = HarnessRuntimeContract.SERVICES
    private const val DATA = HarnessRuntimeContract.DATA
    private const val RUN = "$SERVICES/run"

    fun bootstrapCommands(resultPath: String): List<String> {
        val files = scriptFiles(resultPath)
        return buildList {
            add(
                "mkdir -p \"$SCRIPTS\" \"$SERVICES/runtime\" \"$SERVICES/cache\" " +
                    "\"$SERVICES/logs\" \"$RUN\" \"$DATA/dsh-home\" \"\$HOME/.termux/boot\"",
            )
            files.forEach { script -> add(writeScriptCommand(script)) }
            add(files.joinToString(prefix = "chmod 700 ", separator = " ") { expandableHomePath(it.path) })
            add("nohup \"$SCRIPTS/setup.sh\" ${shellQuote(resultPath)} >/dev/null 2>&1 &")
        }
    }

    fun startCommand(): String =
        "mkdir -p \"$RUN\" && rm -f \"$RUN/.manual-stop\" && " +
            "touch \"$RUN/.auto-keep-running\" && " +
            "nohup \"$SCRIPTS/watchdog-harness.sh\" > \"$SERVICES/logs/watchdog.log\" 2>&1 &"

    fun stopCommand(): String =
        "mkdir -p \"$RUN\" && touch \"$RUN/.manual-stop\" && " +
            "rm -f \"$RUN/.auto-keep-running\" && \"$SCRIPTS/stop-harness.sh\""

    fun restartCommand(): String =
        "mkdir -p \"$RUN\" && touch \"$RUN/.manual-stop\" && " +
            "\"$SCRIPTS/stop-harness.sh\" && rm -f \"$RUN/.manual-stop\" && " +
            "touch \"$RUN/.auto-keep-running\" && " +
            "nohup \"$SCRIPTS/watchdog-harness.sh\" > \"$SERVICES/logs/watchdog.log\" 2>&1 &"

    fun setAutoKeepRunningCommand(enabled: Boolean): String = if (enabled) {
        "mkdir -p \"$RUN\" && touch \"$RUN/.auto-keep-running\""
    } else {
        "rm -f \"$RUN/.auto-keep-running\""
    }

    fun scriptFiles(resultPath: String): List<HarnessScriptFile> = listOf(
        HarnessScriptFile("$SCRIPTS/install-proot.sh", installProotScript()),
        HarnessScriptFile("$SCRIPTS/install-debian.sh", installDebianScript()),
        HarnessScriptFile("$SCRIPTS/install-node.sh", installNodeScript()),
        HarnessScriptFile("$SCRIPTS/install-harness.sh", installHarnessScript()),
        HarnessScriptFile("$SCRIPTS/install.sh", installScript()),
        HarnessScriptFile("$SCRIPTS/run-harness.sh", runHarnessScript()),
        HarnessScriptFile("$SCRIPTS/watchdog-harness.sh", watchdogScript()),
        HarnessScriptFile("$SCRIPTS/stop-harness.sh", stopScript()),
        HarnessScriptFile("$SCRIPTS/status-harness.sh", statusScript()),
        HarnessScriptFile("$SCRIPTS/setup.sh", setupScript()),
        HarnessScriptFile("\$HOME/.termux/boot/start-daddy-harness.sh", bootScript()),
    ).also {
        require(resultPath.isNotBlank()) { "Harness setup result path is required." }
    }

    private fun installProotScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -eu
        if ! command -v proot-distro >/dev/null 2>&1; then
          pkg install -y proot-distro
        fi
        command -v proot-distro >/dev/null 2>&1
    """.trimIndent() + "\n"

    private fun installDebianScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -eu
        base="${'$'}HOME/daddy-linux"
        services="${'$'}base/services/harness"
        data="${'$'}base/data/harness"
        mkdir -p "${'$'}services" "${'$'}data" "${'$'}HOME"

        login_check() {
          proot-distro login daddy-linux --isolated \
            --bind "${'$'}services:/opt/daddy-harness" \
            --bind "${'$'}data:/data/daddy-harness" \
            --bind "${'$'}HOME:/host/termux" \
            --bind "/sdcard:/host/storage" \
            -- /bin/sh -c 'test -r /etc/debian_version'
        }

        if login_check >/dev/null 2>&1; then
          exit 0
        fi

        if proot-distro list 2>/dev/null | grep -Eq '(^|[[:space:]])daddy-linux([[:space:]]|${'$'})'; then
          echo 'The daddy-linux container exists but failed its Debian validation.' >&2
          exit 45
        fi

        if proot-distro install --help 2>&1 | grep -q -- '--name'; then
          proot-distro install debian:bookworm --name daddy-linux --architecture aarch64
        else
          proot-distro install debian --override-alias daddy-linux --architecture aarch64
        fi
        login_check
    """.trimIndent() + "\n"

    private fun installNodeScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -eu
        base="${'$'}HOME/daddy-linux"
        services="${'$'}base/services/harness"
        data="${'$'}base/data/harness"
        mkdir -p "${'$'}services/runtime" "${'$'}services/cache" "${'$'}data"

        proot-distro login daddy-linux --isolated \
          --bind "${'$'}services:/opt/daddy-harness" \
          --bind "${'$'}data:/data/daddy-harness" \
          --bind "${'$'}HOME:/host/termux" \
          --bind "/sdcard:/host/storage" \
          -- /bin/bash -lc '
            set -eu
            version="v${HarnessRuntimeContract.NODE_VERSION}"
            archive="node-v${HarnessRuntimeContract.NODE_VERSION}-linux-arm64.tar.xz"
            runtime="/opt/daddy-harness/runtime"
            cache="/opt/daddy-harness/cache"
            node_dir="${'$'}runtime/node-v${HarnessRuntimeContract.NODE_VERSION}-linux-arm64"
            if [ -x "${'$'}runtime/node-current/bin/node" ] &&
               [ "${'$'}("${'$'}runtime/node-current/bin/node" --version)" = "${'$'}version" ]; then
              exit 0
            fi

            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y --no-install-recommends ca-certificates curl xz-utils
            mkdir -p "${'$'}runtime" "${'$'}cache"
            curl -fL --retry 3 -o "${'$'}cache/${'$'}archive" \
              "https://nodejs.org/dist/${'$'}version/${'$'}archive"
            curl -fL --retry 3 -o "${'$'}cache/SHASUMS256.txt" \
              "https://nodejs.org/dist/${'$'}version/SHASUMS256.txt"
            grep "  ${'$'}archive${'$'}" "${'$'}cache/SHASUMS256.txt" > "${'$'}cache/node.sha256"
            (cd "${'$'}cache" && sha256sum -c node.sha256)
            rm -rf "${'$'}node_dir"
            tar -xJf "${'$'}cache/${'$'}archive" -C "${'$'}runtime"
            rm -f "${'$'}runtime/node-current"
            ln -s "${'$'}node_dir" "${'$'}runtime/node-current"
            "${'$'}runtime/node-current/bin/node" --version
          '
    """.trimIndent() + "\n"

    private fun installHarnessScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -eu
        base="${'$'}HOME/daddy-linux"
        services="${'$'}base/services/harness"
        data="${'$'}base/data/harness"
        mkdir -p "${'$'}services/runtime/harness" "${'$'}data/dsh-home"

        proot-distro login daddy-linux --isolated \
          --bind "${'$'}services:/opt/daddy-harness" \
          --bind "${'$'}data:/data/daddy-harness" \
          --bind "${'$'}HOME:/host/termux" \
          --bind "/sdcard:/host/storage" \
          -- /bin/bash -lc '
            set -eu
            export PATH="/opt/daddy-harness/runtime/node-current/bin:${'$'}PATH"
            prefix="/opt/daddy-harness/runtime/harness"
            dsh="${'$'}prefix/node_modules/.bin/dsh"
            installed="${'$'}(cat "${'$'}prefix/VERSION" 2>/dev/null || true)"
            if [ "${'$'}installed" = "${HarnessRuntimeContract.HARNESS_VERSION}" ] && [ -x "${'$'}dsh" ]; then
              "${'$'}dsh" --version >/dev/null
              exit 0
            fi
            npm install --prefix "${'$'}prefix" "@deepseek-ai/dsh@${HarnessRuntimeContract.HARNESS_VERSION}"
            "${'$'}dsh" --version
            printf %s "${HarnessRuntimeContract.HARNESS_VERSION}" > "${'$'}prefix/VERSION"
          '
    """.trimIndent() + "\n"

    private fun installScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -Eeu
        base="${'$'}HOME/daddy-linux"
        scripts="${'$'}base/scripts"
        services="${'$'}base/services/harness"
        data="${'$'}base/data/harness"
        state_file="${'$'}services/install.state"
        current_stage="precheck"
        mkdir -p "${'$'}scripts" "${'$'}services/run" "${'$'}services/logs" "${'$'}data/dsh-home"

        write_stage() {
          stage="${'$'}1"
          detail="${'$'}2"
          tmp="${'$'}state_file.tmp.${'$'}${'$'}"
          printf 'stage=%s\nruntime=%s\ndetail=%s\nupdated_at=%s\n' \
            "${'$'}stage" "${HarnessRuntimeContract.LINUX_RUNTIME_MARKER}" "${'$'}detail" "${'$'}(date +%s)" > "${'$'}tmp"
          mv "${'$'}tmp" "${'$'}state_file"
        }
        on_error() {
          code="${'$'}?"
          trap - ERR
          write_stage failed "${'$'}current_stage exited with code ${'$'}code"
          exit "${'$'}code"
        }
        trap on_error ERR

        write_stage precheck 'Preparing isolated Daddy Linux runtime'
        current_stage="install_proot"
        write_stage "${'$'}current_stage" 'Checking PRoot-Distro'
        "${'$'}scripts/install-proot.sh"

        current_stage="install_debian"
        write_stage "${'$'}current_stage" 'Checking Debian Bookworm ARM64'
        "${'$'}scripts/install-debian.sh"

        current_stage="install_node"
        write_stage "${'$'}current_stage" 'Checking Node ${HarnessRuntimeContract.NODE_VERSION} linux-arm64'
        "${'$'}scripts/install-node.sh"

        current_stage="install_harness"
        write_stage "${'$'}current_stage" 'Checking DeepSeek Harness ${HarnessRuntimeContract.HARNESS_VERSION}'
        "${'$'}scripts/install-harness.sh"

        current_stage="write_scripts"
        write_stage "${'$'}current_stage" 'Validating managed scripts and runtime'
        test -x "${'$'}scripts/run-harness.sh"
        test -x "${'$'}scripts/watchdog-harness.sh"
        proot-distro login daddy-linux --isolated \
          --bind "${'$'}services:/opt/daddy-harness" \
          --bind "${'$'}data:/data/daddy-harness" \
          --bind "${'$'}HOME:/host/termux" \
          --bind "/sdcard:/host/storage" \
          -- /bin/bash -lc '
            set -eu
            export PATH="/opt/daddy-harness/runtime/node-current/bin:${'$'}PATH"
            node --version
            /opt/daddy-harness/runtime/harness/node_modules/.bin/dsh --version
          '
        printf %s "${HarnessRuntimeContract.LINUX_RUNTIME_MARKER}" > "${'$'}services/runtime.marker"
    """.trimIndent() + "\n"

    private fun runHarnessScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -eu
        base="${'$'}HOME/daddy-linux"
        services="${'$'}base/services/harness"
        data="${'$'}base/data/harness"
        mkdir -p "${'$'}services/logs" "${'$'}services/run" "${'$'}data/dsh-home" "${'$'}data/home"
        export PROOT_NO_SECCOMP=1
        exec proot-distro login daddy-linux --isolated \
          --bind "${'$'}services:/opt/daddy-harness" \
          --bind "${'$'}data:/data/daddy-harness" \
          --bind "${'$'}HOME:/host/termux" \
          --bind "/sdcard:/host/storage" \
          -- /usr/bin/env \
            HOME=/data/daddy-harness/home \
            DSH_HOME=/data/daddy-harness/dsh-home \
            PATH=/opt/daddy-harness/runtime/node-current/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
            /opt/daddy-harness/runtime/node-current/bin/node \
            /opt/daddy-harness/runtime/harness/node_modules/.bin/dsh web --port 3080
    """.trimIndent() + "\n"

    private fun watchdogScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -u
        base="${'$'}HOME/daddy-linux"
        services="${'$'}base/services/harness"
        run="${'$'}services/run"
        scripts="${'$'}base/scripts"
        pid_file="${'$'}run/watchdog.pid"
        child_file="${'$'}run/harness.pid"
        child_start_file="${'$'}run/process-start-ticks"
        child_group_file="${'$'}run/process-group-managed"
        failure_file="${'$'}run/failure-count"
        next_restart_file="${'$'}run/next-restart-at"
        lock_dir="${'$'}run/watchdog.lock"
        delays="3 10 30 300"
        mkdir -p "${'$'}run" "${'$'}services/logs"

        [ ! -f "${'$'}run/.manual-stop" ] || exit 0
        [ -f "${'$'}run/.auto-keep-running" ] || exit 0

        old_pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null || true)"
        if ! mkdir "${'$'}lock_dir" 2>/dev/null; then
          if [ -n "${'$'}old_pid" ] && [ "${'$'}old_pid" != "${'$'}${'$'}" ] && kill -0 "${'$'}old_pid" 2>/dev/null; then
            exit 0
          fi
          rmdir "${'$'}lock_dir" 2>/dev/null || exit 0
          mkdir "${'$'}lock_dir" 2>/dev/null || exit 0
        fi
        printf %s "${'$'}${'$'}" > "${'$'}pid_file"
        trap 'rm -f "${'$'}pid_file"; rmdir "${'$'}lock_dir" 2>/dev/null || true' EXIT

        process_start_ticks() {
          pid="${'$'}1"
          awk '{print ${'$'}22}' "/proc/${'$'}pid/stat" 2>/dev/null || true
        }
        managed_child_alive() {
          pid="${'$'}(cat "${'$'}child_file" 2>/dev/null || true)"
          expected="${'$'}(cat "${'$'}child_start_file" 2>/dev/null || true)"
          [ -n "${'$'}pid" ] && [ -n "${'$'}expected" ] && kill -0 "${'$'}pid" 2>/dev/null &&
            [ "${'$'}(process_start_ticks "${'$'}pid")" = "${'$'}expected" ]
        }
        restart_delay() {
          count="${'$'}1"
          case "${'$'}count" in
            0) echo 3 ;;
            1) echo 10 ;;
            2) echo 30 ;;
            *) echo 300 ;;
          esac
        }
        wait_until_restart() {
          target="${'$'}1"
          while [ "${'$'}(date +%s)" -lt "${'$'}target" ]; do
            [ -f "${'$'}run/.auto-keep-running" ] || return 1
            [ ! -f "${'$'}run/.manual-stop" ] || return 1
            sleep 1
          done
        }

        while [ -f "${'$'}run/.auto-keep-running" ] && [ ! -f "${'$'}run/.manual-stop" ]; do
          failure_count="${'$'}(cat "${'$'}failure_file" 2>/dev/null || echo 0)"
          next_restart="${'$'}(cat "${'$'}next_restart_file" 2>/dev/null || echo 0)"
          wait_until_restart "${'$'}next_restart" || break

          if ! managed_child_alive; then
            if command -v setsid >/dev/null 2>&1; then
              setsid "${'$'}scripts/run-harness.sh" >> "${'$'}services/logs/harness.log" 2>&1 &
              printf %s 1 > "${'$'}child_group_file"
            else
              nohup "${'$'}scripts/run-harness.sh" >> "${'$'}services/logs/harness.log" 2>&1 &
              printf %s 0 > "${'$'}child_group_file"
            fi
            child="${'$'}!"
            printf %s "${'$'}child" > "${'$'}child_file"
            for attempt in 1 2 3 4 5; do
              start_ticks="${'$'}(process_start_ticks "${'$'}child")"
              [ -n "${'$'}start_ticks" ] && break
              sleep 1
            done
            [ -n "${'$'}start_ticks" ] || { echo 'Managed Harness process did not start.' >&2; exit 47; }
            printf %s "${'$'}start_ticks" > "${'$'}child_start_file"
          fi
          started_at="${'$'}(date +%s)"
          while managed_child_alive &&
                [ -f "${'$'}run/.auto-keep-running" ] &&
                [ ! -f "${'$'}run/.manual-stop" ]; do
            sleep 3
            stable_seconds="${'$'}(( ${'$'}(date +%s) - started_at ))"
            if (( stable_seconds >= 900 )) && [ "${'$'}failure_count" -ne 0 ]; then
              failure_count=0
              printf %s 0 > "${'$'}failure_file"
              rm -f "${'$'}next_restart_file"
            fi
          done
          [ -f "${'$'}run/.auto-keep-running" ] || break
          [ ! -f "${'$'}run/.manual-stop" ] || break
          stable_seconds="${'$'}(( ${'$'}(date +%s) - started_at ))"
          if (( stable_seconds >= 900 )); then
            failure_count=0
          fi
          delay="${'$'}(restart_delay "${'$'}failure_count")"
          failure_count="${'$'}((failure_count + 1))"
          next_restart="${'$'}(( ${'$'}(date +%s) + delay ))"
          printf %s "${'$'}failure_count" > "${'$'}failure_file"
          printf %s "${'$'}next_restart" > "${'$'}next_restart_file"
          printf '%s Harness exited; retry %s in %ss.\n' \
            "${'$'}(date '+%F %T')" "${'$'}failure_count" "${'$'}delay" >> "${'$'}services/logs/watchdog.log"
        done
    """.trimIndent() + "\n"

    private fun stopScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -u
        base="${'$'}HOME/daddy-linux"
        run="${'$'}base/services/harness/run"
        web_url="http://127.0.0.1:3080"
        mkdir -p "${'$'}run"
        touch "${'$'}run/.manual-stop"

        watchdog="${'$'}(cat "${'$'}run/watchdog.pid" 2>/dev/null || true)"
        if [ -n "${'$'}watchdog" ] && kill -0 "${'$'}watchdog" 2>/dev/null; then
          kill "${'$'}watchdog" 2>/dev/null || true
        fi
        rm -f "${'$'}run/watchdog.pid"

        pid="${'$'}(cat "${'$'}run/harness.pid" 2>/dev/null || true)"
        expected="${'$'}(cat "${'$'}run/process-start-ticks" 2>/dev/null || true)"
        actual="${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}pid/stat" 2>/dev/null || true)"
        group_managed="${'$'}(cat "${'$'}run/process-group-managed" 2>/dev/null || echo 0)"
        if [ -n "${'$'}pid" ] && [ -n "${'$'}expected" ] && [ "${'$'}actual" = "${'$'}expected" ] &&
           kill -0 "${'$'}pid" 2>/dev/null; then
          if [ "${'$'}group_managed" = 1 ]; then
            kill -- "-${'$'}pid" 2>/dev/null || true
          else
            kill "${'$'}pid" 2>/dev/null || true
          fi
          for attempt in ${'$'}(seq 1 20); do
            kill -0 "${'$'}pid" 2>/dev/null || break
            sleep 1
          done
        fi
        rm -f "${'$'}run/harness.pid" "${'$'}run/process-start-ticks" "${'$'}run/process-group-managed"

        if command -v curl >/dev/null 2>&1 && curl -fsS "${'$'}web_url" >/dev/null 2>&1; then
          echo 'Port 3080 is still active; Daddy did not signal an unverified owner.' >&2
          exit 46
        fi
    """.trimIndent() + "\n"

    private fun statusScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -u
        base="${'$'}HOME/daddy-linux"
        services="${'$'}base/services/harness"
        stage="${'$'}(sed -n 's/^stage=//p' "${'$'}services/install.state" 2>/dev/null | head -n 1)"
        marker="${'$'}(cat "${'$'}services/runtime.marker" 2>/dev/null || true)"
        child="${'$'}(cat "${'$'}services/run/harness.pid" 2>/dev/null || true)"
        printf 'stage=%s\nruntime=%s\nlegacy=%s\nmanual_stop=%s\nmanaged_pid=%s\n' \
          "${'$'}stage" "${'$'}marker" \
          "${'$'}([ -d "${'$'}HOME/daddy-harness" ] && echo 1 || echo 0)" \
          "${'$'}([ -f "${'$'}services/run/.manual-stop" ] && echo 1 || echo 0)" \
          "${'$'}child"
    """.trimIndent() + "\n"

    private fun setupScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -u
        base="${'$'}HOME/daddy-linux"
        scripts="${'$'}base/scripts"
        services="${'$'}base/services/harness"
        run="${'$'}services/run"
        state_file="${'$'}services/install.state"
        result="${'$'}{1:?Missing result file}"
        web_url="$WEB_URL"
        mkdir -p "${'$'}(dirname "${'$'}result")" "${'$'}run" "${'$'}services/logs"

        write_stage() {
          stage="${'$'}1"
          detail="${'$'}2"
          tmp="${'$'}state_file.tmp.${'$'}${'$'}"
          printf 'stage=%s\nruntime=%s\ndetail=%s\nupdated_at=%s\n' \
            "${'$'}stage" "${HarnessRuntimeContract.LINUX_RUNTIME_MARKER}" "${'$'}detail" "${'$'}(date +%s)" > "${'$'}tmp"
          mv "${'$'}tmp" "${'$'}state_file"
        }

        if "${'$'}scripts/install.sh" > "${'$'}services/logs/setup.log" 2>&1; then
          write_stage start_and_healthcheck 'Starting Harness and checking loopback port 3080'
          rm -f "${'$'}run/.manual-stop"
          touch "${'$'}run/.auto-keep-running"
          nohup "${'$'}scripts/watchdog-harness.sh" > "${'$'}services/logs/watchdog.log" 2>&1 &
          for attempt in ${'$'}(seq 1 600); do
            if command -v curl >/dev/null 2>&1 && curl -fsS "${'$'}web_url" >/dev/null 2>&1; then
              write_stage ready 'Daddy Linux Harness is ready'
              printf %s ready > "${'$'}result"
              exit 0
            fi
            if (echo > /dev/tcp/127.0.0.1/3080) >/dev/null 2>&1; then
              write_stage ready 'Daddy Linux Harness is ready'
              printf %s ready > "${'$'}result"
              exit 0
            fi
            sleep 1
          done
        fi

        message="${'$'}(tail -n 1 "${'$'}services/logs/setup.log" 2>/dev/null || echo 'See setup.log')"
        write_stage failed "${'$'}message"
        printf 'error: %s' "${'$'}message" > "${'$'}result"
    """.trimIndent() + "\n"

    private fun bootScript(): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -u
        base="${'$'}HOME/daddy-linux"
        scripts="${'$'}base/scripts"
        services="${'$'}base/services/harness"
        run="${'$'}services/run"
        [ -x "${'$'}scripts/watchdog-harness.sh" ] || exit 0
        [ -f "${'$'}run/.auto-keep-running" ] || exit 0
        [ ! -f "${'$'}run/.manual-stop" ] || exit 0
        nohup "${'$'}scripts/watchdog-harness.sh" > "${'$'}services/logs/boot.log" 2>&1 &
    """.trimIndent() + "\n"

    private fun writeScriptCommand(script: HarnessScriptFile): String {
        val encoded = Base64.getEncoder().encodeToString(script.body.toByteArray(Charsets.UTF_8))
        return "printf %s ${shellQuote(encoded)} | base64 -d > ${expandableHomePath(script.path)}"
    }

    private fun expandableHomePath(path: String): String {
        require(path.matches(Regex("""^\${'$'}HOME/[A-Za-z0-9._/-]+${'$'}"""))) {
            "Harness script path must be a safe path below \$HOME"
        }
        return "\"$path\""
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
