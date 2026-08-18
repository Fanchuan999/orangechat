/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessScriptsTest {
    @Test
    fun installPinsOfficialHarnessAndKeepsTheWebServerOnLoopback() {
        val scripts = HarnessScripts.scriptFiles("/sdcard/result").joinToString("\n") { it.body }

        assertTrue(scripts.contains("@deepseek-ai/dsh@0.1.0-rc.7"))
        assertFalse(scripts.contains("@deepseek-ai/dsh@0.1.0-rc.5"))
        assertTrue(scripts.contains("runtime/node_modules/.bin/dsh"))
        assertTrue(scripts.contains("web --port 3080"))
        assertTrue(scripts.contains("http://127.0.0.1:3080"))
        assertFalse(scripts.contains("@deepseek-ai/dsh@latest"))
        assertFalse(scripts.contains("0.0.0.0"))
    }

    @Test
    fun runUsesTermuxNodeInsteadOfThePackagesUsrBinEnvShebang() {
        val run = HarnessScripts.scriptFiles("/sdcard/result")
            .single { it.path.endsWith("/run.sh") }
            .body

        assertTrue(run.contains("exec node \"\$dsh\" web --port 3080"))
        assertFalse(run.contains("exec \"\$dsh\" web --port 3080"))
    }

    @Test
    fun repeatedInstallPreservesHarnessHomeAndSkipsMatchingRuntime() {
        val install = HarnessScripts.scriptFiles("/sdcard/result")
            .single { it.path.endsWith("/install.sh") }
            .body

        assertTrue(install.contains("\"\$base/dsh-home\""))
        assertTrue(install.contains("installed_version"))
        assertTrue(install.contains("\"\$installed_version\" != \"\$version\""))
        assertFalse(install.contains("rm -rf \"\$base/dsh-home\""))
    }

    @Test
    fun installRejectsUnsupportedNodeMajorAndOldNode22() {
        val install = HarnessScripts.scriptFiles("/sdcard/result")
            .single { it.path.endsWith("/install.sh") }
            .body

        assertTrue(install.contains("node_major == 22 && node_minor < 19"))
        assertTrue(install.contains("node_major == 23"))
    }

    @Test
    fun stopWritesManualMarkerBeforeStoppingProcesses() {
        val command = HarnessScripts.stopCommand()

        assertTrue(command.indexOf(".manual-stop") < command.indexOf("stop.sh"))
    }

    @Test
    fun restartTemporarilyBlocksTheWatchdogBeforeStoppingProcesses() {
        val command = HarnessScripts.restartCommand()
        val markerIndex = command.indexOf("touch \"\$HOME/daddy-harness/.manual-stop\"")
        val stopIndex = command.indexOf("stop.sh")
        val clearIndex = command.indexOf("rm -f \"\$HOME/daddy-harness/.manual-stop\"")

        assertTrue(markerIndex >= 0)
        assertTrue(markerIndex < stopIndex)
        assertTrue(clearIndex > stopIndex)
    }

    @Test
    fun stopShutsDownTheWatchdogBeforeTheHarnessChild() {
        val stop = HarnessScripts.scriptFiles("/sdcard/result")
            .single { it.path.endsWith("/stop.sh") }
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
            .single { it.path.endsWith("/watchdog.sh") }
            .body

        assertTrue(watchdog.contains(".manual-stop"))
        assertTrue(watchdog.contains("sleep 3"))
    }

    @Test
    fun generatedLogsNeverReadHarnessCredentialFiles() {
        val text = HarnessScripts.scriptFiles("/sdcard/result").joinToString("\n") { it.body }

        assertFalse(text.contains("cat \"\$DSH_HOME/.credentials.yaml\""))
        assertFalse(text.contains("cat \"\$base/dsh-home/.credentials.yaml\""))
    }

    @Test
    fun bootstrapExpandsHomeWhenWritingAndMakingScriptsExecutable() {
        val commands = HarnessScripts.bootstrapCommands("/sdcard/result")
        val runScriptWrite = commands.single {
            it.contains("base64 -d") && it.contains("daddy-harness/run.sh")
        }
        val chmod = commands.single { it.startsWith("chmod 700 ") }

        assertTrue(runScriptWrite.contains("> \"\$HOME/daddy-harness/run.sh\""))
        assertFalse(runScriptWrite.contains("> '\$HOME/daddy-harness/run.sh'"))
        assertTrue(chmod.contains("\"\$HOME/daddy-harness/run.sh\""))
        assertFalse(chmod.contains("'\$HOME/daddy-harness/run.sh'"))
    }
}
