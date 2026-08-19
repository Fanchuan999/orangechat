/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessScriptsTest {
    @Test
    fun installUsesPinnedDebianArm64NodeAndHarnessWithoutFloatingVersions() {
        val scripts = HarnessScripts.scriptFiles("/sdcard/result").joinToString("\n") { it.body }

        assertTrue(scripts.contains("debian:bookworm"))
        assertTrue(scripts.contains("--architecture aarch64"))
        assertTrue(scripts.contains("node-v24.19.0-linux-arm64.tar.xz"))
        assertTrue(scripts.contains("SHASUMS256.txt"))
        assertTrue(scripts.contains("sha256sum -c"))
        assertTrue(scripts.contains("@deepseek-ai/dsh@0.1.0-rc.7"))
        assertFalse(scripts.contains("@deepseek-ai/dsh@0.1.0-rc.5"))
        assertTrue(scripts.contains("web --port 3080"))
        assertTrue(scripts.contains("http://127.0.0.1:3080"))
        assertFalse(scripts.contains("@latest"))
        assertFalse(scripts.contains("0.0.0.0"))
    }

    @Test
    fun installerPlacesProotDistroOptionsBeforeTheImage() {
        val scripts = HarnessScripts.scriptFiles("/sdcard/result").joinToString("\n") { it.body }

        assertTrue(scripts.contains("command -v proot-distro"))
        assertTrue(scripts.contains("pkg install -y proot-distro"))
        assertTrue(
            scripts.contains(
                "proot-distro install --name daddy-linux --architecture aarch64 \"\$image\""
            )
        )
        assertTrue(
            scripts.contains(
                "proot-distro install --override-alias daddy-linux --architecture aarch64 debian"
            )
        )
        assertFalse(scripts.contains("proot-distro install debian:bookworm --name daddy-linux"))
        assertFalse(scripts.contains("proot-distro install debian --override-alias daddy-linux"))
        assertTrue(scripts.contains("proot-distro login daddy-linux"))
    }

    @Test
    fun installerRetriesTheReachableMirrorOnlyAfterOfficialNetworkFailure() {
        val debianInstaller = HarnessScripts.scriptFiles("/sdcard/result")
            .single { it.path.endsWith("/install-debian.sh") }
            .body
        val official = "debian:bookworm"
        val fallback = "docker.m.daocloud.io/library/debian:bookworm"

        assertTrue(debianInstaller.contains(official))
        assertTrue(debianInstaller.contains(fallback))
        assertTrue(debianInstaller.indexOf(official) < debianInstaller.indexOf(fallback))
        assertTrue(debianInstaller.contains("is_network_failure"))
        assertTrue(debianInstaller.contains("Connection timed out"))
        assertTrue(debianInstaller.contains("Official Docker Hub network failure; trying fallback mirror"))
    }

    @Test
    fun guestCommandsUseOnlyApprovedExplicitBindsAndPersistentDshHome() {
        val scripts = HarnessScripts.scriptFiles("/sdcard/result").joinToString("\n") { it.body }

        assertTrue(scripts.contains("--bind \"\$services:/opt/daddy-harness\""))
        assertTrue(scripts.contains("--bind \"\$data:/data/daddy-harness\""))
        assertTrue(scripts.contains("--bind \"\$HOME:/host/termux\""))
        assertTrue(scripts.contains("--bind \"/sdcard:/host/storage\""))
        assertTrue(scripts.contains("DSH_HOME=/data/daddy-harness/dsh-home"))
        assertTrue(scripts.contains("/opt/daddy-harness/runtime/node-current/bin/node"))
        assertFalse(scripts.contains("pkg install -y nodejs"))
    }

    @Test
    fun setupWritesStagesAtomicallyAndValidatesArtifactsBeforeSkipping() {
        val scripts = HarnessScripts.scriptFiles("/sdcard/result").joinToString("\n") { it.body }

        listOf(
            "precheck",
            "install_proot",
            "install_debian",
            "install_node",
            "install_harness",
            "write_scripts",
            "start_and_healthcheck",
            "ready",
            "failed",
        ).forEach { stage -> assertTrue("Missing stage $stage", scripts.contains(stage)) }
        assertTrue(scripts.contains("mv \"\$tmp\" \"\$state_file\""))
        assertTrue(scripts.contains("proot-distro login daddy-linux"))
        assertTrue(scripts.contains("node --version"))
        assertTrue(scripts.contains("dsh --version"))
    }

    @Test
    fun repairNeverDeletesPersistentDataLegacyRuntimeOrOtherContainers() {
        val scripts = HarnessScripts.scriptFiles("/sdcard/result").joinToString("\n") { it.body }

        assertTrue(HarnessScripts.BASE.contains("daddy-linux"))
        assertTrue(scripts.contains("\$HOME/daddy-harness"))
        assertFalse(scripts.contains("rm -rf \"\$HOME/daddy-harness\""))
        assertFalse(scripts.contains("rm -rf \"\$data\""))
        assertFalse(scripts.contains("proot-distro remove"))
        assertFalse(scripts.contains("node-pty=false"))
        assertFalse(scripts.contains("koffi=false"))
        assertFalse(scripts.contains("sharp=false"))
    }

    @Test
    fun stopWritesManualMarkerBeforeStoppingProcesses() {
        val command = HarnessScripts.stopCommand()

        assertTrue(command.indexOf(".manual-stop") < command.indexOf("stop-harness.sh"))
    }

    @Test
    fun restartTemporarilyBlocksTheWatchdogBeforeStoppingProcesses() {
        val command = HarnessScripts.restartCommand()
        val markerIndex = command.indexOf("touch \"\$HOME/daddy-linux/services/harness/run/.manual-stop\"")
        val stopIndex = command.indexOf("stop-harness.sh")
        val clearIndex = command.indexOf("rm -f \"\$HOME/daddy-linux/services/harness/run/.manual-stop\"")

        assertTrue(markerIndex >= 0)
        assertTrue(markerIndex < stopIndex)
        assertTrue(clearIndex > stopIndex)
    }

    @Test
    fun stopShutsDownTheWatchdogBeforeTheHarnessChild() {
        val stop = HarnessScripts.scriptFiles("/sdcard/result")
            .single { it.path.endsWith("/stop-harness.sh") }
            .body
        val watchdogIndex = stop.indexOf("watchdog.pid")
        val harnessIndex = stop.indexOf("harness.pid")

        assertTrue(watchdogIndex >= 0)
        assertTrue(harnessIndex >= 0)
        assertTrue(watchdogIndex < harnessIndex)
    }

    @Test
    fun watchdogHonorsManualStopAndWaitsBeforeRestart() {
        val watchdog = HarnessScripts.scriptFiles("/sdcard/result")
            .single { it.path.endsWith("/watchdog-harness.sh") }
            .body

        assertTrue(watchdog.contains(".manual-stop"))
        assertTrue(watchdog.contains("failure-count"))
        assertTrue(watchdog.contains("next-restart-at"))
        assertTrue(watchdog.contains("3 10 30 300"))
        assertTrue(watchdog.contains("stable_seconds >= 900"))
        assertTrue(watchdog.contains("mkdir \"\$lock_dir\""))
    }

    @Test
    fun bootAndWatchdogBothRefuseToReviveAfterManualStop() {
        val files = HarnessScripts.scriptFiles("/sdcard/result")
        val watchdog = files.single { it.path.endsWith("/watchdog-harness.sh") }.body
        val boot = files.single { it.path.endsWith("start-daddy-harness.sh") }.body

        assertTrue(watchdog.contains("[ ! -f \"\$run/.manual-stop\" ]"))
        assertTrue(boot.contains("[ ! -f \"\$run/.manual-stop\" ]"))
    }

    @Test
    fun stopOnlySignalsTheRecordedManagedProcessGroup() {
        val stop = HarnessScripts.scriptFiles("/sdcard/result")
            .single { it.path.endsWith("/stop-harness.sh") }
            .body

        assertTrue(stop.contains("process-start-ticks"))
        assertTrue(stop.contains("kill -- \"-\$pid\""))
        assertTrue(stop.contains("http://127.0.0.1:3080"))
        assertTrue(stop.contains("/dev/tcp/127.0.0.1/3080"))
        assertFalse(stop.contains("pkill"))
        assertFalse(stop.contains("fuser"))
        assertFalse(stop.contains("killall"))
    }

    @Test
    fun generatedLogsNeverReadHarnessCredentialFiles() {
        val text = HarnessScripts.scriptFiles("/sdcard/result").joinToString("\n") { it.body }

        assertFalse(text.contains("cat \"\$DSH_HOME/.credentials.yaml\""))
        assertFalse(text.contains("cat \"/data/daddy-harness/dsh-home/.credentials.yaml\""))
    }

    @Test
    fun dangerousOperationsAreGatedByHarnessExecutionPolicyInsteadOfPromptText() {
        val files = HarnessScripts.scriptFiles("/sdcard/result")
        val gate = files.single { it.path.endsWith("/risk-gate/index.mjs") }.body
        val patch = files.single { it.path.endsWith("/daddy-risk-gate.patch.yml") }.body
        val runner = files.single { it.path.endsWith("/run-harness.sh") }.body

        assertTrue(gate.contains("ctx.on('tools/pre-execute'"))
        assertTrue(gate.contains("kind: 'ask'"))
        assertTrue(gate.contains("delete"))
        assertTrue(gate.contains("overwrite"))
        assertTrue(gate.contains("bulk-move"))
        assertTrue(gate.contains("high-risk-shell"))
        assertTrue(gate.contains("runSelfTest"))
        assertTrue(patch.contains("@daddy/harness-risk-gate"))
        assertTrue(runner.contains("--patch /opt/daddy-harness/config/daddy-risk-gate.patch.yml"))
        assertFalse(gate.contains("Please remember to ask the user"))
    }

    @Test
    fun riskGateAllowsReadOnlyCallsButAsksBeforeMutatingOrAmbiguousCalls() {
        val gate = HarnessScripts.scriptFiles("/sdcard/result")
            .single { it.path.endsWith("/risk-gate/index.mjs") }
            .body

        assertTrue(gate.contains("read"))
        assertTrue(gate.contains("read_image"))
        assertTrue(gate.contains("glob"))
        assertTrue(gate.contains("grep"))
        assertTrue(gate.contains("return next()"))
        assertTrue(gate.contains("str_replace_editor"))
        assertTrue(gate.contains("terminal_send"))
        assertTrue(gate.contains("rm"))
        assertTrue(gate.contains("git clean"))
        assertTrue(gate.contains("git reset --hard"))
    }

    @Test
    fun generatedRiskGatePassesItsNodeSelfTest() {
        val gate = HarnessScripts.scriptFiles("/sdcard/result")
            .single { it.path.endsWith("/risk-gate/index.mjs") }
            .body
        val temp = Files.createTempFile("daddy-harness-risk-gate", ".mjs")
        try {
            Files.writeString(temp, gate)
            val process = ProcessBuilder("node", temp.toString(), "--self-test")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertEquals(output, 0, process.waitFor())
            assertTrue(output, output.contains("risk gate self-test OK"))
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @Test
    fun riskGateAsksBeforeOverwritingAnExistingRelativePathFromTheSessionCwd() {
        val gate = HarnessScripts.scriptFiles("/sdcard/result")
            .single { it.path.endsWith("/risk-gate/index.mjs") }
            .body
        val tempDir = Files.createTempDirectory("daddy-harness-risk-gate-cwd")
        val gateFile = tempDir.resolve("risk-gate.mjs")
        val runnerFile = tempDir.resolve("relative-path-test.mjs")
        Files.writeString(tempDir.resolve("existing.txt"), "already here")
        Files.writeString(gateFile, gate)
        Files.writeString(
            runnerFile,
            """
                import { classifyToolCall } from './risk-gate.mjs'
                const risk = classifyToolCall({
                  name: 'write',
                  arguments: { path: 'existing.txt' },
                  agent: { session: { header: { cwd: process.cwd() } } },
                })
                if (risk !== 'overwrite') throw new Error(`expected overwrite, got ${'$'}{risk}`)
                process.stdout.write('relative overwrite gated\n')
            """.trimIndent(),
        )
        try {
            val process = ProcessBuilder("node", runnerFile.toString())
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertEquals(output, 0, process.waitFor())
            assertTrue(output, output.contains("relative overwrite gated"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun riskGateAsksWhenARelativeWriteHasNoTrustworthySessionCwd() {
        val gate = HarnessScripts.scriptFiles("/sdcard/result")
            .single { it.path.endsWith("/risk-gate/index.mjs") }
            .body
        val tempDir = Files.createTempDirectory("daddy-harness-risk-gate-unknown-cwd")
        val gateFile = tempDir.resolve("risk-gate.mjs")
        val runnerFile = tempDir.resolve("unknown-cwd-test.mjs")
        Files.writeString(gateFile, gate)
        Files.writeString(
            runnerFile,
            """
                import { classifyToolCall } from './risk-gate.mjs'
                const risk = classifyToolCall({
                  name: 'write',
                  arguments: { path: 'possibly-existing.txt' },
                })
                if (risk !== 'overwrite') throw new Error(`expected fail-closed overwrite, got ${'$'}{risk}`)
                process.stdout.write('unknown cwd gated\n')
            """.trimIndent(),
        )
        try {
            val process = ProcessBuilder("node", runnerFile.toString())
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertEquals(output, 0, process.waitFor())
            assertTrue(output, output.contains("unknown cwd gated"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun bootstrapExpandsHomeWhenWritingAndMakingScriptsExecutable() {
        val commands = HarnessScripts.bootstrapCommands("/sdcard/result")
        val runScriptWrite = commands.single {
            it.contains("base64 -d") && it.contains("daddy-linux/scripts/run-harness.sh")
        }
        val chmod = commands.single { it.startsWith("chmod 700 ") }

        assertTrue(runScriptWrite.contains("> \"\$HOME/daddy-linux/scripts/run-harness.sh\""))
        assertFalse(runScriptWrite.contains("> '\$HOME/daddy-linux/scripts/run-harness.sh'"))
        assertTrue(chmod.contains("\"\$HOME/daddy-linux/scripts/run-harness.sh\""))
        assertFalse(chmod.contains("'\$HOME/daddy-linux/scripts/run-harness.sh'"))
    }
}
