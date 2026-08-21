/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import java.util.Base64
import me.rerere.rikkahub.data.datastore.CodeHutApprovalMode
import me.rerere.rikkahub.data.codehut.HarnessProviderPatch

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
    private const val WORK_PROVIDER_PATCH = "$SERVICES/config/code-hut-provider.patch.yml"
    private const val WORK_PROVIDER_ENV = "$RUN/code-hut.env"
    private const val APPROVAL_MODE_ENV = "$RUN/code-hut-approval.env"

    fun bootstrapCommands(resultPath: String): List<String> {
        val files = scriptFiles(resultPath)
        return buildList {
            add(
                "mkdir -p \"$SCRIPTS\" \"$SERVICES/runtime\" \"$SERVICES/cache\" " +
                    "\"$SERVICES/logs\" \"$SERVICES/risk-gate\" \"$SERVICES/config\" " +
                    "\"$RUN\" \"$DATA/dsh-home\" \"\$HOME/.termux/boot\"",
            )
            files.forEach { script -> add(writeScriptCommand(script)) }
            add(
                "if [ ! -f \"$WORK_PROVIDER_PATCH\" ]; then " +
                    "printf '%s' '[]' > \"$WORK_PROVIDER_PATCH\"; fi",
            )
            add(files.joinToString(prefix = "chmod 700 ", separator = " ") { expandableHomePath(it.path) })
            add("nohup \"$SCRIPTS/setup.sh\" ${shellQuote(resultPath)} >/dev/null 2>&1 &")
        }
    }

    fun startCommand(): String =
        "mkdir -p \"$RUN\" && rm -f \"$RUN/.manual-stop\" && " +
            "touch \"$RUN/.auto-keep-running\" && " +
            "nohup \"$SCRIPTS/watchdog-harness.sh\" > \"$SERVICES/logs/watchdog.log\" 2>&1 &"

    fun stopCommand(clearCodeHutEnvironment: Boolean = false): String =
        (
            "mkdir -p \"$RUN\" && touch \"$RUN/.manual-stop\" && " +
                "rm -f \"$RUN/.auto-keep-running\" && " +
                (if (clearCodeHutEnvironment) "rm -f \"$WORK_PROVIDER_ENV\" \"$APPROVAL_MODE_ENV\" && " else "") +
                "\"$SCRIPTS/stop-harness.sh\""
        )

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

    fun configureWorkProviderCommands(patch: HarnessProviderPatch): List<String> = listOf(
        writeManagedFileCommand(patch.yaml, WORK_PROVIDER_PATCH),
        writeManagedFileCommand(environmentFile(patch.environment), WORK_PROVIDER_ENV),
        "chmod 600 \"$WORK_PROVIDER_PATCH\" \"$WORK_PROVIDER_ENV\"",
    )

    fun configureApprovalModeCommands(mode: CodeHutApprovalMode): List<String> = listOf(
        writeManagedFileCommand(approvalModeEnvironmentFile(mode), APPROVAL_MODE_ENV),
        "chmod 600 \"$APPROVAL_MODE_ENV\"",
    )

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
        HarnessScriptFile("$SERVICES/risk-gate/index.mjs", riskGatePluginScript()),
        HarnessScriptFile("$SERVICES/risk-gate/package.json", riskGatePackageJson()),
        HarnessScriptFile("$SERVICES/config/daddy-risk-gate.patch.yml", riskGatePatch()),
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
        install_log="${'$'}services/logs/debian-install.log"
        mkdir -p "${'$'}services/logs" "${'$'}data" "${'$'}HOME"

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

        install_image() {
          image="${'$'}1"
          proot-distro install --name daddy-linux --architecture aarch64 "${'$'}image"
        }

        is_network_failure() {
          grep -Eqi 'Network error|Network is unreachable|Connection timed out|timed out' "${'$'}1"
        }

        if proot-distro install --help 2>&1 | grep -q -- '--name'; then
          if install_image "${HarnessRuntimeContract.DEBIAN_IMAGE}" > "${'$'}install_log" 2>&1; then
            cat "${'$'}install_log"
          else
            official_status="${'$'}?"
            if ! is_network_failure "${'$'}install_log"; then
              cat "${'$'}install_log"
              exit "${'$'}official_status"
            fi
            printf '%s\n' '[*] Official Docker Hub network failure; trying fallback mirror...' >> "${'$'}install_log"
            if install_image "${HarnessRuntimeContract.DEBIAN_FALLBACK_IMAGE}" >> "${'$'}install_log" 2>&1; then
              :
            else
              fallback_status="${'$'}?"
              cat "${'$'}install_log"
              exit "${'$'}fallback_status"
            fi
            cat "${'$'}install_log"
          fi
        else
          proot-distro install --override-alias daddy-linux --architecture aarch64 debian
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
            prefix="/opt/daddy-harness/runtime/harness"
            export HOME="/data/daddy-harness/home"
            export DSH_HOME="/data/daddy-harness/dsh-home"
            export PATH="/opt/daddy-harness/runtime/node-current/bin:${'$'}prefix/node_modules/.bin:${'$'}PATH"
            dsh="${'$'}prefix/node_modules/.bin/dsh"
            risk_source="/opt/daddy-harness/risk-gate"
            install_risk_gate() {
              test -r "${'$'}risk_source/index.mjs"
              test -r "${'$'}risk_source/package.json"
              if [ "${'$'}(pnpm --version 2>/dev/null || true)" != "${HarnessRuntimeContract.PNPM_VERSION}" ]; then
                npm install --prefix "${'$'}prefix" "pnpm@${HarnessRuntimeContract.PNPM_VERSION}"
              fi
              "${'$'}dsh" plugin --profile web add "${'$'}risk_source"
              "${'$'}prefix/../node-current/bin/node" "${'$'}risk_source/index.mjs" --self-test
            }
            installed="${'$'}(cat "${'$'}prefix/VERSION" 2>/dev/null || true)"
            if [ "${'$'}installed" = "${HarnessRuntimeContract.HARNESS_VERSION}" ] && [ -x "${'$'}dsh" ]; then
              "${'$'}dsh" --version >/dev/null
              install_risk_gate
              exit 0
            fi
            npm install --prefix "${'$'}prefix" "@deepseek-ai/dsh@${HarnessRuntimeContract.HARNESS_VERSION}"
            install_risk_gate
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
        work_provider_env="${'$'}services/run/code-hut.env"
        if [ -r "${'$'}work_provider_env" ]; then
          set -a
          . "${'$'}work_provider_env"
          set +a
        fi
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
            /opt/daddy-harness/runtime/harness/node_modules/.bin/dsh \
            --patch /opt/daddy-harness/config/daddy-risk-gate.patch.yml \
            --patch /opt/daddy-harness/config/code-hut-provider.patch.yml \
            --profile web \
            --port 3080
    """.trimIndent() + "\n"

    private fun riskGatePackageJson(): String = """
        {
          "name": "@daddy/harness-risk-gate",
          "version": "1.0.0",
          "type": "module",
          "exports": "./index.mjs"
        }
    """.trimIndent() + "\n"

    private fun riskGatePatch(): String = """
        - insert:
            - id: daddy-harness-risk-gate
              name: '@daddy/harness-risk-gate'
    """.trimIndent() + "\n"

    private fun riskGatePluginScript(): String = """
        import { existsSync, readFileSync } from 'node:fs'
        import { isAbsolute, resolve } from 'node:path'

        export const name = 'daddy-harness-risk-gate'
        const APPROVAL_MODE_PATH = '/opt/daddy-harness/run/code-hut-approval.env'
        const APPROVAL_MODE_ASK = 'ASK_EVERY_TIME'
        const APPROVAL_MODE_HELP = 'HELP_ME_APPROVE'

        const LOW_RISK_TOOLS = new Set([
          'read', 'read_image', 'glob', 'grep',
          'session_event_read', 'session_event_search', 'session_event_trace',
          'session_search', 'session_trace', 'job_list', 'job_output',
        ])
        const REASONS = {
          'low-risk': '当前策略要求对普通低风险操作逐次确认',
          delete: '涉及删除或清空数据',
          overwrite: '会覆盖或改写已有内容',
          'bulk-move': '会批量移动或重命名文件',
          'package-install': '会安装、更新或移除第三方包/插件',
          credential: '涉及登录、令牌、密钥或其他敏感凭据',
          'external-submit': '会向外部服务提交表单、发送消息或写入远端数据',
          'git-push-or-release': '会把改动发布到外部 Git 远端或 Release',
          privileged: '会请求 root、系统级或宿主高权限操作',
          'android-system': '会修改 Android 系统、应用权限或设备级配置',
          'high-risk-shell': '这是一条高风险 Shell 操作',
        }

        function objectArgs(value) {
          return value !== null && typeof value === 'object' ? value : {}
        }

        function readApprovalMode() {
          try {
            const lines = readFileSync(APPROVAL_MODE_PATH, 'utf8').split(/\r?\n/)
            for (const line of lines) {
              const match = line.match(/^DADDY_CODE_HUT_APPROVAL_MODE=(['"]?)([A-Z_]+)\1$/)
              if (match === null) continue
              return match[2] === APPROVAL_MODE_HELP ? APPROVAL_MODE_HELP : APPROVAL_MODE_ASK
            }
            return APPROVAL_MODE_ASK
          } catch {
            return APPROVAL_MODE_ASK
          }
        }

        function stringArg(args, ...names) {
          for (const key of names) {
            if (typeof args[key] === 'string' && args[key].trim().length > 0) return args[key]
          }
          return undefined
        }

        function targetExists(exec, args) {
          const path = stringArg(args, 'path', 'file_path', 'target', 'destination', 'dest')
          if (path === undefined) return undefined
          const cwd = exec.agent?.session?.header?.cwd
          if (!isAbsolute(path) && (typeof cwd !== 'string' || !isAbsolute(cwd))) return undefined
          const absolute = isAbsolute(path) ? path : resolve(cwd, path)
          try {
            return existsSync(absolute)
          } catch {
            return undefined
          }
        }

        function tokenize(command) {
          return command.match(/"[^"\n]*"|'[^'\n]*'|[^\s]+/g)?.map(value => value.replace(/^['"]|['"]${'$'}/g, '')) ?? []
        }

        function compactSnippet(value, max = 96) {
          const normalized = String(value ?? '').trim().replace(/\s+/g, ' ')
          if (normalized.length <= max) return normalized || '未提供'
          return normalized.slice(0, max - 1) + '…'
        }

        function redactSensitiveText(value) {
          return compactSnippet(value)
            .replace(/(authorization\s*[:=]\s*(?:bearer\s+)?)\S+/gi, '$1[REDACTED]')
            .replace(/((?:api[_ -]?key|token|access[_ -]?token|refresh[_ -]?token|secret|password|passwd)\s*[:=]\s*)\S+/gi, '$1[REDACTED]')
        }

        function summarizeExternalTarget(command) {
          const url = command.match(/https?:\/\/[^\s'"]+/i)?.[0]
          if (url !== undefined) {
            try {
              return '外部目标 ' + new URL(url).origin
            } catch {
            }
          }
          return '外部提交命令（内容已隐藏）'
        }

        function describeTarget(exec, args, risk) {
          const path = stringArg(args, 'path', 'file_path', 'target', 'destination', 'dest', 'url', 'uri')
          if (path !== undefined) return redactSensitiveText(path)
          const command = stringArg(args, 'command', 'cmd', 'script')
          if (command !== undefined) {
            if (risk === 'credential') return '敏感凭据命令（内容已隐藏）'
            if (risk === 'external-submit') return summarizeExternalTarget(command)
            return redactSensitiveText(command)
          }
          return compactSnippet(exec.name ?? '未知目标')
        }

        function describeAction(exec, risk) {
          switch (risk) {
            case 'delete': return '删除或清理数据'
            case 'overwrite': return '覆盖或改写内容'
            case 'bulk-move': return '批量移动或重命名'
            case 'package-install': return '安装/更新第三方包'
            case 'credential': return '登录或处理敏感凭据'
            case 'external-submit': return '向外部服务提交数据'
            case 'git-push-or-release': return '推送 Git 或发布 Release'
            case 'privileged': return '申请系统级高权限'
            case 'android-system': return '修改 Android 系统配置'
            default: return compactSnippet(exec.name ?? '高风险操作')
          }
        }

        function riskReason(exec, risk) {
          const args = objectArgs(exec.arguments)
          return [
            'Daddy 安全确认',
            '动作：' + describeAction(exec, risk),
            '目标：' + describeTarget(exec, args, risk),
            '原因：' + (REASONS[risk] ?? REASONS['high-risk-shell']),
          ].join('\n')
        }

        function shellRisk(command) {
          const normalized = command.trim().replace(/\s+/g, ' ')
          if (normalized.length === 0) return null
          const tokens = tokenize(normalized)
          const first = tokens[0]?.toLowerCase() ?? ''
          const second = tokens[1]?.toLowerCase() ?? ''
          if (['curl', 'wget', 'http', 'httpie', 'invoke-webrequest', 'iwr'].includes(first) &&
              /(?:\s-X\s*(?:POST|PUT|PATCH|DELETE)\b|--request\s+(?:POST|PUT|PATCH|DELETE)\b|--data(?:-raw|-binary)?\b|--form\b|-d\s)/i.test(normalized)) {
            return 'external-submit'
          }
          if (/\b(?:gh\s+auth\s+login|docker\s+login|npm\s+login|pnpm\s+login|yarn\s+login|aws\s+configure|gcloud\s+auth\s+login|az\s+login|op\s+signin|pass\s+insert|vault\s+login|ssh-keygen|api[_ -]?key|token|secret|password|passwd|authorization\s*[:=]\s*(?:bearer\s+)?)\b/i.test(normalized)) {
            return 'credential'
          }
          if (['pwd', 'ls', 'dir', 'cat', 'head', 'tail', 'find', 'grep'].includes(first)) return 'low-risk'
          if (first === 'git' && ['status', 'diff', 'log', 'pull'].includes(second)) return 'low-risk'
          if (['unzip', 'tar'].includes(first) && /(?:-x|xf|\s+x[fv]?)/i.test(normalized)) return 'low-risk'
          if (first === 'mkdir') return 'low-risk'
          if (['./gradlew', 'gradlew', 'gradle', 'mvn', './mvnw', 'npm', 'pnpm', 'yarn', 'bun', 'cargo', 'go'].includes(first) &&
              /\b(?:test|build|check|lint|assemble|verify)\b/i.test(normalized)) {
            return 'low-risk'
          }
          if (['curl', 'wget', 'http', 'httpie', 'invoke-webrequest', 'iwr'].includes(first) &&
              !/(?:\s-X\s*(?:POST|PUT|PATCH|DELETE)\b|--request\s+(?:POST|PUT|PATCH|DELETE)\b|--data(?:-raw|-binary)?\b|--form\b|-d\s)/i.test(normalized)) {
            return 'low-risk'
          }
          if (['ssh', 'scp', 'sftp', 'rsync', 'nc', 'ncat', 'telnet'].includes(first)) return 'external-submit'

          if (/(^|[;&|()\s])(?:rm|rmdir|unlink|shred)\s/i.test(normalized)) return 'delete'
          if (/\bfind\b[^\n]*(?:-delete|-exec\s+(?:rm|rmdir|unlink|shred)\b)/i.test(normalized)) return 'delete'
          if (/\brsync\b[^\n]*--delete(?:-|\s|${'$'})/i.test(normalized)) return 'delete'
          if (/\bgit\s+clean\b/i.test(normalized)) return 'delete'
          if (/\bgit\s+reset\s+--hard\b/i.test(normalized)) return 'high-risk-shell'
          if (/\bgit\s+(?:checkout|restore)\b[^\n]*(?:--\s+|\s--source=)/i.test(normalized)) return 'overwrite'

          const move = normalized.match(/(?:^|[;&|()\s])mv\s+([^;&|\n]+)/i)
          if (move !== null) {
            const operands = tokenize(move[1]).filter(token => !token.startsWith('-'))
            return operands.length > 2 ? 'bulk-move' : 'overwrite'
          }

          if (/(^|[^>])>(?!>)/.test(normalized)) return 'overwrite'
          if (/\b(?:truncate|tee|dd)\b/i.test(normalized)) return 'overwrite'
          if (/\b(?:sed\s+-[^\s]*i|perl\s+-[^\s]*i)\b/i.test(normalized)) return 'overwrite'
          if (/\b(?:cp|install)\b[^\n]*(?:-f|--force)\b/i.test(normalized)) return 'overwrite'

          if (/\b(?:npm|pnpm)\b[^\n]*(?:\bi\b|\binstall\b|\bupdate\b|\bupgrade\b|\bremove\b|\buninstall\b)|\byarn\b[^\n]*(?:\badd\b|\binstall\b|\bremove\b|\bupgrade\b)|\b(?:bun|pip(?:3)?|uv|poetry|gem|bundle|cargo|go|brew|apt(?:-get)?|apk|dnf|yum|pacman|pkg)\b[^\n]*(?:install|add|update|upgrade|remove|uninstall)\b/i.test(normalized)) {
            return 'package-install'
          }
          if (/\b(?:npm|pnpm|yarn|cargo)\b[^\n]*\bpublish\b|\btwine\b[^\n]*\bupload\b/i.test(normalized)) return 'external-submit'
          if (/\b(?:git\s+push|gh\s+release|gh\s+pr\s+merge)\b/i.test(normalized)) return 'git-push-or-release'
          if (/\b(?:adb\s+shell\s+)?(?:pm\s+(?:grant|revoke|disable-user|clear|uninstall)|appops|settings\s+put|svc\s+|setprop|cmd\s+package)\b/i.test(normalized)) {
            return 'android-system'
          }
          if (['touch', 'cp', 'install'].includes(first)) return 'overwrite'
          if (/\b(?:mkfs(?:\.[a-z0-9]+)?|fdisk|parted|wipefs|mount|umount)\b/i.test(normalized)) return 'privileged'
          if (/\b(?:shutdown|reboot|poweroff|halt)\b/i.test(normalized)) return 'high-risk-shell'
          if (/\b(?:chmod|chown|chgrp)\b[^\n]*(?:-R|--recursive)\b/i.test(normalized)) return 'privileged'
          if (/\b(?:sudo|su)\b/i.test(normalized)) return 'privileged'
          if (/\beval\b/i.test(normalized)) return 'high-risk-shell'
          if (/\b(?:python(?:3)?\s+-c|node\s+-e|bash\s+-c|sh\s+-c|xargs)\b/i.test(normalized)) return 'high-risk-shell'
          return null
        }

        export function classifyToolCall(exec) {
          const tool = String(exec.name ?? '')
          const lower = tool.toLowerCase()
          const args = objectArgs(exec.arguments)
          if (LOW_RISK_TOOLS.has(lower)) return 'low-risk'

          if (lower === 'write' || /(?:^|[_-])write(?:[_-]|${'$'})/.test(lower)) {
            return targetExists(exec, args) === false ? 'low-risk' : 'overwrite'
          }
          if (lower === 'edit' || lower === 'patch' || /(?:^|[_-])(?:edit|replace|patch)(?:[_-]|${'$'})/.test(lower)) {
            return 'overwrite'
          }
          if (lower === 'str_replace_editor') {
            const command = stringArg(args, 'command')?.toLowerCase()
            if (command === 'view') return 'low-risk'
            if (command === 'create' && targetExists(exec, args) === false) return 'low-risk'
            return 'overwrite'
          }
          if (/(?:^|[_-])(?:delete|remove|unlink|trash)(?:[_-]|${'$'})/.test(lower)) return 'delete'
          if (/(?:^|[_-])(?:move|rename)(?:[_-]|${'$'})/.test(lower)) {
            const sources = args.sources ?? args.paths ?? args.files
            return Array.isArray(sources) && sources.length > 1 ? 'bulk-move' : 'overwrite'
          }
          if (lower === 'terminal_send') return 'high-risk-shell'
          if (lower === 'cordis_define' || lower === 'cordis_run' || lower === 'cordis_undefine') return 'high-risk-shell'
          if (lower === 'bash' || lower === 'pwsh' || lower === 'shell' || lower.endsWith('_shell') || lower.endsWith('_exec')) {
            return shellRisk(stringArg(args, 'command', 'cmd', 'script') ?? '')
          }
          return null
        }

        export function apply(ctx) {
          ctx.on('tools/pre-execute', async (exec, next) => {
            const risk = classifyToolCall(exec)
            if (risk === null) return next()
            if (risk === 'low-risk' && readApprovalMode() === APPROVAL_MODE_HELP) return next()
            return { kind: 'ask', reason: riskReason(exec, risk) }
          })
        }

        export function runSelfTest() {
          const cases = [
            [{ name: 'read', arguments: { path: 'x' } }, 'low-risk'],
            [{ name: 'bash', arguments: { command: 'ls -la' } }, 'low-risk'],
            [{ name: 'bash', arguments: { command: 'rm -rf build' } }, 'delete'],
            [{ name: 'bash', arguments: { command: 'git clean -fd' } }, 'delete'],
            [{ name: 'bash', arguments: { command: 'git reset --hard HEAD' } }, 'high-risk-shell'],
            [{ name: 'bash', arguments: { command: 'mv a b archive/' } }, 'bulk-move'],
            [{ name: 'bash', arguments: { command: './gradlew test' } }, 'low-risk'],
            [{ name: 'bash', arguments: { command: 'git pull --ff-only' } }, 'low-risk'],
            [{ name: 'bash', arguments: { command: 'npm i eslint' } }, 'package-install'],
            [{ name: 'bash', arguments: { command: 'yarn add react' } }, 'package-install'],
            [{ name: 'bash', arguments: { command: 'npm install vite' } }, 'package-install'],
            [{ name: 'bash', arguments: { command: 'cargo publish' } }, 'external-submit'],
            [{ name: 'bash', arguments: { command: 'gh auth login' } }, 'credential'],
            [{ name: 'bash', arguments: { command: 'git push origin HEAD' } }, 'git-push-or-release'],
            [{ name: 'bash', arguments: { command: 'curl -X POST https://example.com -d x=1' } }, 'external-submit'],
            [{ name: 'bash', arguments: { command: 'adb shell pm grant app android.permission.POST_NOTIFICATIONS' } }, 'android-system'],
            [{ name: 'bash', arguments: { command: 'sudo systemctl restart ssh' } }, 'privileged'],
            [{ name: 'edit', arguments: { path: 'x' } }, 'overwrite'],
            [{ name: 'terminal_send', arguments: { chars: 'x' } }, 'high-risk-shell'],
          ]
          for (const [exec, expected] of cases) {
            const actual = classifyToolCall(exec)
            if (actual !== expected) throw new Error('risk-gate self-test failed for ' + JSON.stringify(exec))
          }
        }

        if (process.argv.includes('--self-test')) {
          runSelfTest()
          process.stdout.write('Daddy Harness risk gate self-test OK\n')
        }
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

        port_active=0
        if command -v curl >/dev/null 2>&1 && curl -fsS "${'$'}web_url" >/dev/null 2>&1; then
          port_active=1
        elif (echo > /dev/tcp/127.0.0.1/3080) >/dev/null 2>&1; then
          port_active=1
        fi
        if [ "${'$'}port_active" = 1 ]; then
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
        setup_pid="${'$'}(cat "${'$'}services/run/setup.pid" 2>/dev/null || true)"
        backoff_until="${'$'}(cat "${'$'}services/run/next-restart-at" 2>/dev/null || echo 0)"
        printf 'stage=%s\nruntime=%s\nlegacy=%s\nmanual_stop=%s\nmanaged_pid=%s\nsetup_pid=%s\nbackoff_until=%s\n' \
          "${'$'}stage" "${'$'}marker" \
          "${'$'}([ -d "${'$'}HOME/daddy-harness" ] && echo 1 || echo 0)" \
          "${'$'}([ -f "${'$'}services/run/.manual-stop" ] && echo 1 || echo 0)" \
          "${'$'}child" "${'$'}setup_pid" "${'$'}backoff_until"
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

        setup_pid_file="${'$'}run/setup.pid"
        setup_start_file="${'$'}run/setup-start-ticks"
        old_setup_pid="${'$'}(cat "${'$'}setup_pid_file" 2>/dev/null || true)"
        old_setup_expected="${'$'}(cat "${'$'}setup_start_file" 2>/dev/null || true)"
        old_setup_actual="${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}old_setup_pid/stat" 2>/dev/null || true)"
        if [ -n "${'$'}old_setup_pid" ] && [ -n "${'$'}old_setup_expected" ] &&
           [ "${'$'}old_setup_actual" = "${'$'}old_setup_expected" ] && kill -0 "${'$'}old_setup_pid" 2>/dev/null; then
          printf %s in_progress > "${'$'}result"
          exit 0
        fi
        printf %s "${'$'}${'$'}" > "${'$'}setup_pid_file"
        setup_start="${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}${'$'}/stat" 2>/dev/null || true)"
        printf %s "${'$'}setup_start" > "${'$'}setup_start_file"
        trap 'rm -f "${'$'}setup_pid_file" "${'$'}setup_start_file"' EXIT

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
        return writeManagedFileCommand(script.body, script.path)
    }

    private fun writeManagedFileCommand(body: String, path: String): String {
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        return "printf %s ${shellQuote(encoded)} | base64 -d > ${expandableHomePath(path)}"
    }

    private fun environmentFile(environment: Map<String, String>): String = buildString {
        environment.forEach { (name, value) ->
            require(name.matches(Regex("^[A-Z0-9_]+$"))) { "Harness 环境变量名不安全。" }
            require(value.isNotBlank()) { "Harness 环境变量值不能为空。" }
            append(name)
            append("=")
            append(shellQuote(value))
            append('\n')
        }
    }

    private fun approvalModeEnvironmentFile(mode: CodeHutApprovalMode): String = buildString {
        append("DADDY_CODE_HUT_APPROVAL_MODE=")
        append(shellQuote(mode.name))
        append('\n')
    }

    private fun expandableHomePath(path: String): String {
        require(path.matches(Regex("""^\${'$'}HOME/[A-Za-z0-9._/-]+${'$'}"""))) {
            "Harness script path must be a safe path below \$HOME"
        }
        return "\"$path\""
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
