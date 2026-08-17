# Daddy Harness Stable Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a stable, independently configured DeepSeek Harness Web workspace to Daddy, managed through Termux with install fallback, health states, and automatic recovery, without touching Daddy chat generation or memory.

**Architecture:** The Android app owns a small `HarnessManager` and state UI; the official `@deepseek-ai/dsh` package stays in Termux under `$HOME/daddy-harness`. Pure Kotlin script builders generate idempotent install/start/watchdog/stop/boot scripts, `TermuxConfigBridge` executes them, and an allowlisted WebView displays `http://127.0.0.1:3080`. A WorkManager fallback and app-start check recover the service only when auto-keep-running is enabled and no manual-stop marker exists.

**Tech Stack:** Kotlin, Jetpack Compose, DataStore, Koin, WorkManager, OkHttp, Android WebView, Termux RunCommand/local bridge, Bash, JUnit.

## Global Constraints

- Preserve package name `me.rerere.orangechat.companion` and the existing companion signing key so the APK remains an in-place update.
- Pin `@deepseek-ai/dsh` to `0.1.0-rc.5`; never install floating `latest`.
- Require Node `22.19+` on major 22 or any major `24+`, matching the official package engine floor.
- Keep Harness on `127.0.0.1:3080`; never bind it to LAN interfaces.
- Keep Harness data under `$HOME/daddy-harness`, with `DSH_HOME=$HOME/daddy-harness/dsh-home`.
- Do not read, copy, display, or synchronize Harness API keys in Daddy.
- Do not add Daddy-to-Harness task delegation, native approval bridging, plugin management, updates, rollback, or linked backup in this phase.
- Do not modify Daddy chat generation, Ombre, proactive messaging, Amap port 8001, Ombre port 8000, or Termux Bridge port 8080.
- Do not add another Android foreground service; use the Termux watchdog, app-start recovery, and a conservative WorkManager fallback.

---

### Task 1: Persist Harness user intent and status vocabulary

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/datastore/HarnessSetting.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/datastore/HarnessSettingTest.kt`

**Interfaces:**
- Produces: `HarnessSetting(autoKeepRunning, manuallyStopped, installedVersion)` persisted as part of `Settings`.
- Produces: `HarnessStatus` and `HarnessSnapshot` shared by the manager, UI, and recovery worker.

- [ ] **Step 1: Write failing default and serialization tests**

```kotlin
class HarnessSettingTest {
    @Test fun `defaults keep Harness alive without claiming it is installed`() {
        assertEquals(HarnessSetting(true, false, ""), HarnessSetting())
    }

    @Test fun `setting survives JsonInstant round trip`() {
        val expected = HarnessSetting(false, true, "0.1.0-rc.5")
        val encoded = JsonInstant.encodeToString(expected)
        assertEquals(expected, JsonInstant.decodeFromString<HarnessSetting>(encoded))
    }
}
```

- [ ] **Step 2: Run the test and verify it fails because the types do not exist**

Run: `./gradlew :app:testDebugUnitTest --tests '*HarnessSettingTest'`

- [ ] **Step 3: Add the serializable setting and runtime state types**

```kotlin
@Serializable
data class HarnessSetting(
    val autoKeepRunning: Boolean = true,
    val manuallyStopped: Boolean = false,
    val installedVersion: String = "",
)

enum class HarnessStatus { NOT_INSTALLED, STOPPED, RUNNING, ERROR }

data class HarnessSnapshot(
    val status: HarnessStatus = HarnessStatus.NOT_INSTALLED,
    val installedVersion: String = "",
    val detail: String = "",
    val logTail: String = "",
)
```

- [ ] **Step 4: Add `HARNESS_SETTING`, decode it in `settingsFlowRaw`, encode it in `update`, and add `val harnessSetting: HarnessSetting = HarnessSetting()` to `Settings`**

Use the same JSON DataStore pattern as `SYSTEM_TOOLS_SETTING`; do not store runtime logs in DataStore.

- [ ] **Step 5: Run the focused test and existing settings regression tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*HarnessSettingTest' --tests '*SystemToolsSettingTest'`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/datastore/HarnessSetting.kt app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt app/src/test/java/me/rerere/rikkahub/data/datastore/HarnessSettingTest.kt
git commit -m "feat: persist Harness lifecycle preferences"
```

### Task 2: Generate idempotent Termux scripts

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessScriptsTest.kt`

**Interfaces:**
- Produces: `HarnessScripts.bootstrapCommands(resultPath)`, `startCommand()`, `stopCommand()`, `restartCommand()`, and `setAutoKeepRunningCommand(enabled)`.
- Consumes: the fixed paths and version in Global Constraints.

- [ ] **Step 1: Write failing script-contract tests**

```kotlin
class HarnessScriptsTest {
    @Test fun `install is version pinned and loopback only`() {
        val text = HarnessScripts.bootstrapCommands("/sdcard/result").joinToString("\n")
        assertTrue(text.contains("@deepseek-ai/dsh@0.1.0-rc.5"))
        assertTrue(text.contains("127.0.0.1"))
        assertTrue(text.contains("3080"))
        assertFalse(text.contains("@deepseek-ai/dsh@latest"))
        assertFalse(text.contains("0.0.0.0"))
    }

    @Test fun `stop creates manual marker before killing processes`() {
        val text = HarnessScripts.stopCommand()
        assertTrue(text.indexOf(".manual-stop") < text.indexOf("kill"))
    }

    @Test fun `watchdog respects manual stop and waits before restart`() {
        val text = HarnessScripts.watchdogScript()
        assertTrue(text.contains(".manual-stop"))
        assertTrue(text.contains("sleep 3"))
    }

    @Test fun `logs never print credential files`() {
        val text = HarnessScripts.allScriptBodiesForTest().joinToString("\n")
        assertFalse(text.contains("cat \"$DSH_HOME/.credentials.yaml\""))
    }
}
```

- [ ] **Step 2: Run the test and verify the missing object fails compilation**

Run: `./gradlew :app:testDebugUnitTest --tests '*HarnessScriptsTest'`

- [ ] **Step 3: Implement the pure script builder**

Use these exact constants and commands:

```kotlin
internal object HarnessScripts {
    const val VERSION = "0.1.0-rc.5"
    const val BASE = "\$HOME/daddy-harness"
    const val WEB_URL = "http://127.0.0.1:3080"

    fun startCommand(): String = "rm -f \"$BASE/.manual-stop\" && " +
        "touch \"$BASE/.auto-keep-running\" && " +
        "nohup \"$BASE/watchdog.sh\" > \"$BASE/watchdog.log\" 2>&1 &"

    fun stopCommand(): String = "mkdir -p \"$BASE\" && touch \"$BASE/.manual-stop\" && " +
        "rm -f \"$BASE/.auto-keep-running\" && \"$BASE/stop.sh\""

    fun restartCommand(): String = "\"$BASE/stop.sh\" && " +
        "rm -f \"$BASE/.manual-stop\" && touch \"$BASE/.auto-keep-running\" && " +
        "nohup \"$BASE/watchdog.sh\" > \"$BASE/watchdog.log\" 2>&1 &"
}
```

Generate `install.sh`, `run.sh`, `watchdog.sh`, `stop.sh`, `setup.sh`, and `$HOME/.termux/boot/start-daddy-harness.sh` as Base64-decoded files, following `AmapMcpService.writeScriptCommand` so long scripts are not sent as one bridge payload.

The install script must:

1. Run `pkg install -y nodejs` only when `node` or `npm` is absent.
2. Reject Node 23 and Node 22 below 22.19 with a readable final log line.
3. Run `npm install --prefix "$base/runtime" "@deepseek-ai/dsh@$VERSION"` only when the recorded version differs or the executable is missing.
4. Write `0.1.0-rc.5` to `$base/VERSION` only after npm succeeds.
5. Preserve `$base/dsh-home` on every repeated installation.
6. Start `dsh web --port 3080` with `DSH_HOME="$base/dsh-home"` and working directory `$HOME`.
7. Write `ready` or `error: <last setup.log line>` to the Android-visible result path.

- [ ] **Step 4: Run tests and inspect generated scripts for forbidden paths and floating versions**

Run: `./gradlew :app:testDebugUnitTest --tests '*HarnessScriptsTest'`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessScriptsTest.kt
git commit -m "feat: define pinned Harness Termux runtime"
```

### Task 3: Add a testable Harness manager and dual-channel command execution

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/TermuxConfigBridge.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessManager.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessManagerPolicyTest.kt`

**Interfaces:**
- Produces: `HarnessManager.inspect()`, `install()`, `start()`, `stop()`, `restart()`, `setAutoKeepRunning(Boolean)`, `recoverIfNeeded()`, `fallbackInstallCommand()`, and `redactedLogTail()`.
- Produces: `TermuxConfigBridge.executeCommandsAndWait(commands, completionFile, timeoutMessage, waitAttempts)`.

- [ ] **Step 1: Write failing status and redaction policy tests**

```kotlin
class HarnessManagerPolicyTest {
    @Test fun `status requires both process and http health`() {
        assertEquals(HarnessStatus.RUNNING, classifyHarness(true, true, true, ""))
        assertEquals(HarnessStatus.ERROR, classifyHarness(true, true, false, "web failed"))
        assertEquals(HarnessStatus.STOPPED, classifyHarness(true, false, false, ""))
        assertEquals(HarnessStatus.NOT_INSTALLED, classifyHarness(false, false, false, ""))
    }

    @Test fun `recovery honors manual stop`() {
        assertFalse(shouldRecover(autoKeepRunning = true, manuallyStopped = true, running = false))
        assertTrue(shouldRecover(autoKeepRunning = true, manuallyStopped = false, running = false))
    }

    @Test fun `log redaction removes common secrets`() {
        val text = redactHarnessLog("Authorization: Bearer abc\nAPI_KEY=secret\nCookie: sid=x")
        assertFalse(text.contains("abc"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("sid=x"))
    }
}
```

- [ ] **Step 2: Run the test and verify the pure policy functions are missing**

Run: `./gradlew :app:testDebugUnitTest --tests '*HarnessManagerPolicyTest'`

- [ ] **Step 3: Extend `TermuxConfigBridge` with one ordered-command API**

Try every short command through `127.0.0.1:8080`; if that channel fails, pass the same commands joined with ` && ` to the documented Termux RunCommand service. Wait for the same completion file in both paths. Expose no arbitrary UI-entered shell string through this API.

- [ ] **Step 4: Implement `HarnessManager`**

Use an OkHttp client with 2-second connect and read timeouts for `GET http://127.0.0.1:3080/`. `inspect()` also sends a short Termux-owned probe that writes `installed`, `process`, `version`, and the final non-secret log line to a result file. Merge both signals with `classifyHarness`.

Every lifecycle action updates `settings.harnessSetting` only after its command succeeds. `fallbackInstallCommand()` returns a single copyable command that creates the same scripts and invokes `setup.sh`; it must not contain an API key.

Limit `redactedLogTail()` to 200 lines and 24 KiB, replacing values after `Authorization:`, `Cookie:`, `API_KEY=`, `apiKey:`, and `token:` with `[REDACTED]`.

- [ ] **Step 5: Register one `TermuxConfigBridge` singleton and one `HarnessManager` singleton in Koin**

Replace the private `TermuxConfigBridge(get())` created inside `AmapMcpService` with the shared singleton so Amap, backup, and Harness use the same bridge behavior.

- [ ] **Step 6: Run manager and existing companion-sync tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*HarnessManagerPolicyTest' --tests '*OmbreBackupAuthTest' --tests '*SupabaseBackupClientTest'`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/sync/companion/TermuxConfigBridge.kt app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessManager.kt app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessManagerPolicyTest.kt
git commit -m "feat: manage Harness through Termux bridge"
```

### Task 4: Add non-foreground automatic recovery

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessRecoveryWorker.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessRecoveryScheduler.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessRecoveryPolicyTest.kt`

**Interfaces:**
- Consumes: `HarnessManager.recoverIfNeeded()` and `HarnessSetting`.
- Produces: unique periodic work name `daddy_harness_recovery` with a 15-minute interval.

- [ ] **Step 1: Write failing recovery-decision tests**

Test these truth-table rows: disabled never recovers; manual stop never recovers; enabled + stopped recovers; enabled + running is a no-op; an exception returns WorkManager retry without changing manual-stop state.

- [ ] **Step 2: Implement the scheduler and worker**

Use `ExistingPeriodicWorkPolicy.UPDATE` and `PeriodicWorkRequestBuilder<HarnessRecoveryWorker>(15, TimeUnit.MINUTES)`. The worker calls only `recoverIfNeeded()` and returns `Result.success()` when no recovery is required, `Result.retry()` for transient Termux/HTTP failures.

- [ ] **Step 3: Add app-start recovery after Koin initialization**

In `RikkaHubApp.onCreate`, call a new `recoverHarnessIfEnabled()` that launches on `AppScope + Dispatchers.IO`, invokes `HarnessRecoveryScheduler.sync`, then invokes `HarnessManager.recoverIfNeeded()`. It must catch and log failures without crashing application startup.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*HarnessRecoveryPolicyTest' --tests '*HarnessManagerPolicyTest'`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessRecoveryWorker.kt app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessRecoveryScheduler.kt app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessRecoveryPolicyTest.kt
git commit -m "feat: recover Harness without foreground service"
```

### Task 5: Build the Harness status page and allowlisted workspace WebView

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessVM.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessPage.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessWebViewPage.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessUrlPolicy.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/harness/HarnessUrlPolicyTest.kt`

**Interfaces:**
- Produces: status actions and a workspace route callback.
- Produces: `HarnessUrlPolicy.isInternal(url)` allowing only HTTP URLs whose host is exactly `127.0.0.1` and port is exactly `3080`.

- [ ] **Step 1: Write failing URL policy tests**

```kotlin
@Test fun `only exact Harness loopback origin stays internal`() {
    assertTrue(HarnessUrlPolicy.isInternal("http://127.0.0.1:3080/"))
    assertTrue(HarnessUrlPolicy.isInternal("http://127.0.0.1:3080/session/1"))
    assertFalse(HarnessUrlPolicy.isInternal("http://localhost:3080/"))
    assertFalse(HarnessUrlPolicy.isInternal("http://127.0.0.1:8000/"))
    assertFalse(HarnessUrlPolicy.isInternal("https://example.com/"))
    assertFalse(HarnessUrlPolicy.isInternal("javascript:alert(1)"))
}
```

- [ ] **Step 2: Implement `HarnessVM` with a single `StateFlow<HarnessUiState>`**

Expose `refresh`, `install`, `start`, `stop`, `restart`, `setAutoKeepRunning`, and `copyFallbackCommand`. Serialize operations with one `Mutex`; disable conflicting buttons while an action runs; refresh the snapshot after every action.

- [ ] **Step 3: Implement the status page**

Show the four status labels, pinned/installed version, port, auto-keep-running switch, install/repair, start, stop, restart, refresh, copy fallback command, redacted logs, and “打开工作台”. Use a scrollable `LazyColumn` so the iQOO screen and font scaling cannot bury controls below the viewport.

- [ ] **Step 4: Implement a dedicated WebView page**

Enable JavaScript, DOM storage, and normal file upload required by the official UI. Do not add a JavaScript interface. Keep only exact-origin navigation inside WebView; send HTTP(S) external links to `Intent.ACTION_VIEW`; reject all other schemes. Show native back, refresh, open-in-browser, full-screen, and load-error actions. Destroy the WebView on disposal.

- [ ] **Step 5: Register `HarnessVM` and run tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*HarnessUrlPolicyTest' --tests '*HarnessManagerPolicyTest'`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/harness app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt app/src/test/java/me/rerere/rikkahub/ui/pages/harness/HarnessUrlPolicyTest.kt
git commit -m "feat: add Harness status and workspace pages"
```

### Task 6: Wire navigation and both entry points

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawer.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt`

**Interfaces:**
- Produces: `Screen.Harness` and `Screen.HarnessWorkspace`.
- Consumes: `HarnessManager` status flow and `HarnessPage`/`HarnessWebViewPage`.

- [ ] **Step 1: Add the two serializable routes and route entries**

`Screen.Harness` renders `HarnessPage(onOpenWorkspace = { nav.navigate(Screen.HarnessWorkspace) })`; `Screen.HarnessWorkspace` renders `HarnessWebViewPage()`.

- [ ] **Step 2: Add a fixed Harness surface below History in `DrawerActions`**

Display “DeepSeek Harness” and one of “未安装 / 未启动 / 运行中 / 异常”. Clicking always opens `Screen.Harness`; it must not silently install or start from the drawer.

- [ ] **Step 3: Add the same Harness row to `FilesPicker` between tool controls and Extensions**

Use the same status vocabulary and route. Dismiss the plus panel before navigating so returning from Harness does not reopen a stale sheet.

- [ ] **Step 4: Compile the companion variant**

Run: `./gradlew :app:compileCompanionKotlin`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/RouteActivity.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawer.kt app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt
git commit -m "feat: expose Harness from chat navigation"
```

### Task 7: Consolidate the existing tool-throttling UI without changing behavior

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ToolThrottleMode.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ToolThrottleSheet.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/ai/ToolThrottleModeTest.kt`

**Interfaces:**
- Produces: `ToolThrottleMode.OFF`, `SMART`, and `MANUAL` mapped to the existing two Assistant booleans.
- Preserves: existing mutual exclusion, selected MCP IDs, selected plugin IDs, context, memory, and world-book behavior.

- [ ] **Step 1: Write failing mapping tests**

Verify `(false,false) -> OFF`, `(true,false) -> SMART`, `(false,true) -> MANUAL`, and normalize the invalid legacy `(true,true)` state to `MANUAL` without deleting any selected IDs.

- [ ] **Step 2: Implement pure mode mapping and transitions**

`Assistant.withToolThrottleMode(mode)` changes only `smartToolThrottlingEnabled` and `manualToolSelectionEnabled`. It must leave `manualToolMcpServerIds` and `manualToolPluginIds` byte-for-byte equivalent.

- [ ] **Step 3: Replace the two top-level rows with one “工具节流” row**

The row displays “已关闭 / 智能模式 / 手动模式”. Its bottom sheet contains the original descriptions, switches, and manual picker entry. Do not edit `ChatService` or `SmartToolRouter`.

- [ ] **Step 4: Run focused routing and mode tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*ToolThrottleModeTest' --tests '*SmartToolRouterTest'`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/ai/ToolThrottleMode.kt app/src/main/java/me/rerere/rikkahub/ui/components/ai/ToolThrottleSheet.kt app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt app/src/test/java/me/rerere/rikkahub/ui/components/ai/ToolThrottleModeTest.kt
git commit -m "refactor: consolidate tool throttling controls"
```

### Task 8: Verify Termux compatibility on the phone

**Files:**
- Create: `docs/testing/daddy-harness-termux-smoke.md`
- Modify only if the smoke test exposes a real compatibility defect: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt`

**Interfaces:**
- Consumes: the exact generated fallback command from the status page.
- Produces: a recorded pass/fail matrix for iQOO Neo 10, Android build, Termux version, Node version, npm version, and DSH `0.1.0-rc.5`.

- [ ] **Step 1: Install a debug APK on the paired phone without removing the current stable Daddy**

Use the same companion package/signature and an incremented versionCode so installation is an in-place update. Back up current Daddy settings before the test.

- [ ] **Step 2: Run both installation channels**

First run Daddy one-click install. If the OEM rejects RunCommand, copy the exact fallback command into Termux. Record which channel succeeded and the original error for the failed channel.

- [ ] **Step 3: Execute the acceptance probe**

Verify:

```text
GET http://127.0.0.1:3080/ succeeds
Harness Web UI loads inside Daddy
an independent provider/model can be saved
$HOME/daddy-harness/test/read.txt can be read
$HOME/daddy-harness/test/write.txt can be created
printf daddy-harness-alive returns the marker
Daddy model selection remains unchanged
```

- [ ] **Step 4: Test lifecycle recovery**

Kill only the Harness child process and confirm watchdog recovery within roughly 3 seconds. Press Daddy Stop and confirm no recovery for at least 30 seconds. Start again, reboot the phone, and confirm Termux:Boot restores the page. If iQOO blocks boot recovery, record and display the exact self-start/background-battery guidance rather than claiming success.

- [ ] **Step 5: Commit the smoke record and any minimal compatibility correction**

```bash
git add docs/testing/daddy-harness-termux-smoke.md app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt
git commit -m "test: verify Harness on Termux arm64"
```

### Task 9: Full regression, version bump, and signed APK

**Files:**
- Modify: `app/build.gradle.kts`
- Verify: all files changed by Tasks 1–8

**Interfaces:**
- Produces: one ARM64 companion APK that covers the current Daddy installation.

- [ ] **Step 1: Run the full JVM suite**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL` with no failed test task.

- [ ] **Step 2: Run Android lint and companion compilation**

Run: `./gradlew :app:lintCompanion :app:assembleCompanion --console=plain`
Expected: APK generation succeeds; any pre-existing lint infrastructure failure is recorded separately and no new Harness finding is ignored.

- [ ] **Step 3: Bump versionCode once and use a versionName suffix describing Harness workspace**

Do not change `applicationId` or signing configuration. Rebuild after the bump.

- [ ] **Step 4: Verify APK identity**

Use Android SDK `aapt dump badging` and `apksigner verify --print-certs`. Require:

```text
package: me.rerere.orangechat.companion
native-code: arm64-v8a
signer SHA-256: ea958214d61fac06047eb4a5316bebf621d7da5a6d21af74768d144cc4217b68
```

- [ ] **Step 5: Copy and hash the artifact**

Copy the verified APK to `D:\Daddy-安装包\` with a filename containing the new Daddy version, `Harness工作台`, versionCode, `覆盖更新`, and `arm64`. Confirm source and destination SHA-256 hashes match.

- [ ] **Step 6: Review scope and repository state**

Verify no Harness API key, `.credentials.yaml`, Termux home data, `tmp/`, or unrelated user files are staged. Do not push GitHub without fresh explicit user authorization.

- [ ] **Step 7: Commit the version bump**

```bash
git add app/build.gradle.kts
git commit -m "build: ship Harness workspace companion"
```
