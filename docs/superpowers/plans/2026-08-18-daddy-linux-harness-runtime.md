# Daddy Linux Harness Runtime Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the failed Android-native Harness runtime with a staged, recoverable Debian/glibc runtime while preserving Daddy’s existing chat, Ombre, Termux Bridge, Amap, signing identity, and Harness user data.

**Architecture:** Daddy continues to call fixed scripts through `TermuxConfigBridge`. Termux becomes the orchestration layer; a PRoot-Distro container named `daddy-linux` runs pinned Linux ARM64 Node.js and pinned DeepSeek Harness. Rebuildable runtime files live under `$HOME/daddy-linux/services`, persistent Harness data lives under `$HOME/daddy-linux/data`, and every long installation phase writes an atomic state file that Android can inspect without replaying commands.

**Tech Stack:** Kotlin, Jetpack Compose, DataStore serialization, WorkManager, Bash, Termux RunCommand/local bridge, PRoot-Distro, Debian Bookworm ARM64, Node.js v24.19.0 linux-arm64, `@deepseek-ai/dsh@0.1.0-rc.7`, JUnit 4, Gradle Android plugin, ADB.

---

## Constraints shared by every task

- Work only in `D:\small progect\ai_chat\orangechat-minimal\.worktrees\harness-stable-workspace`.
- Never stage or commit `tmp/`, phone logs, API keys, credentials, generated APKs, or local diagnostic patches.
- Do not push without a fresh explicit user authorization.
- Keep application ID `me.rerere.orangechat.companion` and the existing signing chain so the APK covers v215.
- Do not modify Ombre-Brain, Daddy Amap, Termux Bridge service code, proactive messaging, memory, or chat generation except for the already-present no-command-replay fix in `TermuxConfigBridge`.
- Follow red-green-refactor for every production behavior: add or update a focused test, run it and observe the expected failure, implement the minimum change, then rerun it.
- Preserve `$HOME/daddy-harness` on the phone until the Linux runtime passes device acceptance and the user separately approves cleanup.

## Task 1: Checkpoint the verified v215 native-runtime baseline

**Files:**

- Stage: `app/build.gradle.kts`
- Stage: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessManager.kt`
- Stage: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt`
- Stage: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/TermuxConfigBridge.kt`
- Stage: `app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessPage.kt`
- Stage: `app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessManagerPolicyTest.kt`
- Stage: `app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessScriptsTest.kt`
- Stage: `docs/testing/daddy-harness-termux-smoke.md`
- Never stage: `tmp/`

**Step 1: Confirm scope before the checkpoint**

Run:

```powershell
git status --short
git diff --check -- app/build.gradle.kts app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessManager.kt app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt app/src/main/java/me/rerere/rikkahub/data/sync/companion/TermuxConfigBridge.kt app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessPage.kt app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessManagerPolicyTest.kt app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessScriptsTest.kt docs/testing/daddy-harness-termux-smoke.md
```

Expected: only the known v215 files plus untracked `tmp/`; no whitespace errors in the staged scope.

**Step 2: Re-run the focused baseline tests**

Run:

```powershell
.\gradlew.bat :app:testCompanionUnitTest --tests "me.rerere.rikkahub.data.sync.companion.HarnessScriptsTest" --tests "me.rerere.rikkahub.data.sync.companion.HarnessManagerPolicyTest" --tests "me.rerere.rikkahub.data.sync.companion.HarnessRecoveryPolicyTest"
```

Expected: PASS. If the task name differs, run `\.\gradlew.bat :app:tasks --all | Select-String test.*Companion` and use the exact discovered unit-test task.

**Step 3: Commit only the baseline**

Run:

```powershell
git add -- app/build.gradle.kts app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessManager.kt app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt app/src/main/java/me/rerere/rikkahub/data/sync/companion/TermuxConfigBridge.kt app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessPage.kt app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessManagerPolicyTest.kt app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessScriptsTest.kt docs/testing/daddy-harness-termux-smoke.md
git commit -m "fix: checkpoint native Harness v215"
```

Expected: the checkpoint contains only the listed files; `tmp/` remains untracked.

## Task 2: Add a typed Linux runtime and install-stage contract

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessRuntimeContract.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/HarnessSetting.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessRuntimeContractTest.kt`

**Step 1: Write failing contract tests**

Add tests that require:

- `CONTAINER_NAME == "daddy-linux"`.
- Debian reference is pinned to Bookworm and ARM64 rather than `latest`.
- Node is pinned to `24.19.0` and Harness to `0.1.0-rc.7`.
- Wire values parse into ordered stages: `PRECHECK`, `INSTALL_PROOT`, `INSTALL_DEBIAN`, `INSTALL_NODE`, `INSTALL_HARNESS`, `WRITE_SCRIPTS`, `START_AND_HEALTHCHECK`, `READY`, `FAILED`.
- Unknown/missing stage values safely map to `UNKNOWN`.
- A Linux marker is distinct from an Android-native legacy marker.

Run:

```powershell
.\gradlew.bat :app:testCompanionUnitTest --tests "me.rerere.rikkahub.data.sync.companion.HarnessRuntimeContractTest"
```

Expected: FAIL because the contract and stage model do not exist.

**Step 2: Implement the minimum contract**

Create `HarnessRuntimeContract.kt` with pinned constants, safe wire parsing, and data paths. Extend `HarnessSnapshot` with:

```kotlin
val installStage: HarnessInstallStage = HarnessInstallStage.UNKNOWN
val legacyRuntimeFound: Boolean = false
val installProgressPercent: Int = 0
```

Extend `HarnessStatus` with the lifecycle states needed by the approved design:

```kotlin
NOT_INSTALLED, INSTALLING, STOPPED, STARTING, RUNNING,
MANUALLY_STOPPED, BACKING_OFF, REPAIRING, ERROR
```

Keep `HarnessSetting` serialization backward compatible by only adding fields with defaults if persistent state is needed.

**Step 3: Run the contract tests**

Run the focused command from Step 1.

Expected: PASS.

**Step 4: Compile affected Kotlin code**

Run:

```powershell
.\gradlew.bat :app:compileCompanionKotlin
```

Expected: FAIL only at exhaustive `when` sites that still need the new statuses. Update only status mappings required to compile; UI wording is completed in Task 6.

**Step 5: Commit**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessRuntimeContract.kt app/src/main/java/me/rerere/rikkahub/data/datastore/HarnessSetting.kt app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessRuntimeContractTest.kt app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessPage.kt
git commit -m "feat: define Daddy Linux Harness runtime contract"
```

## Task 3: Replace native Android scripts with staged Debian installation

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessScriptsTest.kt`

**Step 1: Replace native-runtime expectations with failing Linux-runtime tests**

Update/add tests that require generated scripts to:

- Use `$HOME/daddy-linux` for the new runtime and only probe `$HOME/daddy-harness` as legacy.
- Install `proot-distro` only when absent.
- Prefer `proot-distro install debian:bookworm --name daddy-linux` and contain an `--override-alias daddy-linux` compatibility fallback.
- Pin `linux/arm64`, Node `v24.19.0`, and `@deepseek-ai/dsh@0.1.0-rc.7`; contain no floating `latest`.
- Download Node and `SHASUMS256.txt`, then run `sha256sum -c` before extraction.
- Execute Node/npm/dsh inside `proot-distro login daddy-linux`, not with Termux’s Android `node`.
- Bind the four approved paths to `/opt/daddy-harness`, `/data/daddy-harness`, `/host/termux`, and `/host/storage`.
- Set `DSH_HOME=/data/daddy-harness/dsh-home` and start Web UI only on port 3080/loopback.
- Atomically write every install stage and preserve completed stages on rerun.
- Never remove `$HOME/daddy-linux/data`, `$HOME/daddy-harness`, unrelated PRoot containers, or user workspaces.
- Never disable `node-pty`, `koffi`, `sharp`, or official Harness plugins.

Run:

```powershell
.\gradlew.bat :app:testCompanionUnitTest --tests "me.rerere.rikkahub.data.sync.companion.HarnessScriptsTest"
```

Expected: FAIL against the current direct-Termux scripts.

**Step 2: Implement staged script generation**

Rewrite `HarnessScripts` to generate these Daddy-owned scripts under `$HOME/daddy-linux/scripts`:

```text
install.sh
install-proot.sh
install-debian.sh
install-node.sh
install-harness.sh
run-harness.sh
watchdog-harness.sh
stop-harness.sh
status-harness.sh
setup.sh
~/.termux/boot/start-daddy-harness.sh
```

Implementation requirements:

- Use `set -eu` for install phases and atomic temporary-file-to-final-file moves for state.
- Make the installer resume from validated artifacts, not merely trust an old stage string.
- If the container exists, run a basic Debian command before skipping creation.
- Keep runtime/cache/log/run and data directories separate.
- Use shell quoting and base64 script writes exactly as the existing secure bootstrap does.
- Submit one background `setup.sh`; do not keep the HTTP bridge request open for the entire install.

**Step 3: Run the focused script tests**

Expected: PASS.

**Step 4: Review generated scripts as text**

Run:

```powershell
rg -n "latest|rm -rf.*data|rm -rf.*daddy-harness|0\.0\.0\.0|node-pty|koffi|sharp" app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt
```

Expected: no unsafe delete, floating version, public bind, or native-plugin bypass. Positive mentions in test assertions or explanatory error text must be reviewed manually.

**Step 5: Commit**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessScriptsTest.kt
git commit -m "feat: install Harness in pinned Debian runtime"
```

## Task 4: Implement stop markers and progressive watchdog recovery

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessScriptsTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessRecoveryPolicyTest.kt`

**Step 1: Write failing watchdog policy tests**

Require:

- Manual stop marker is written before stopping watchdog/container children.
- Boot and watchdog both refuse to start while the marker exists.
- First crash delay is 3 seconds, then 10 seconds, 30 seconds, and 300 seconds.
- A 15-minute stable run resets the failure counter.
- A singleton lock/PID prevents duplicate watchdogs and duplicate Harness sessions.
- Stop verifies port 3080 is no longer served by the managed process; it never kills an unknown port owner.

Run the two focused test classes.

Expected: FAIL because the current watchdog always sleeps 3 seconds and manages an Android child PID.

**Step 2: Implement the minimum lifecycle changes**

- Keep watchdog in Termux, outside Debian.
- Record failure count and next eligible restart in `$HOME/daddy-linux/services/harness/run`.
- Use the PRoot session/container process contract to terminate only Daddy’s Harness children.
- Reset counters only after 15 minutes of stable health.
- Make `start`, `restart`, WorkManager recovery, and Termux:Boot all converge on the same watchdog entry point.

**Step 3: Run focused tests**

Expected: PASS.

**Step 4: Commit**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessScriptsTest.kt app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessRecoveryPolicyTest.kt
git commit -m "fix: make Harness recovery bounded and stoppable"
```

## Task 5: Teach HarnessManager to resume installs and report Linux state

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessManager.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessManagerPolicyTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/TermuxConfigBridge.kt` only if a test exposes a remaining replay bug

**Step 1: Write failing manager policy tests**

Add tests for:

- Linux READY requires a valid Linux marker, managed process/session, and healthy HTTP response.
- Native `$HOME/daddy-harness` alone reports `legacyRuntimeFound=true`, not installed Linux Harness.
- Each wire stage maps to the correct status and progress percentage.
- A failed stage returns the failing stage and sanitized detail.
- Install wait duration is at least 30 minutes.
- Start/restart action timeout allows Debian startup rather than assuming 20 seconds.
- A submitted background install that times out is inspected/resumed and never submitted again automatically.
- Redaction handles case-insensitive bearer tokens, quoted JSON secrets, cookies, passwords, and common API-key names.

Run:

```powershell
.\gradlew.bat :app:testCompanionUnitTest --tests "me.rerere.rikkahub.data.sync.companion.HarnessManagerPolicyTest"
```

Expected: FAIL.

**Step 2: Implement Linux-aware probing and progress**

- Change probe paths from `$HOME/daddy-harness` to the new manifest/state/run paths.
- Include `stage`, `runtime`, `legacy`, `manual_stop`, `backoff`, `version`, and a one-line detail in the result file.
- Parse with pure functions covered by tests.
- Increase the bounded first-install wait to at least 30 minutes while keeping UI cancellation safe.
- Preserve `shouldFallbackToRunCommand(bridgeSubmitted = true) == false`.
- Update persisted `installedVersion` only after Linux `READY` and HTTP health both succeed.

**Step 3: Run manager, script, and bridge tests**

```powershell
.\gradlew.bat :app:testCompanionUnitTest --tests "me.rerere.rikkahub.data.sync.companion.HarnessManagerPolicyTest" --tests "me.rerere.rikkahub.data.sync.companion.HarnessScriptsTest" --tests "me.rerere.rikkahub.data.sync.companion.TermuxConfigBridge*"
```

Expected: PASS.

**Step 4: Commit**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessManager.kt app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessManagerPolicyTest.kt app/src/main/java/me/rerere/rikkahub/data/sync/companion/TermuxConfigBridge.kt
git commit -m "feat: resume and inspect Linux Harness installs"
```

Do not stage `TermuxConfigBridge.kt` if it did not change in this task.

## Task 6: Show installation phase and migration state in Daddy

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessVM.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessUiPolicy.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/harness/HarnessUiPolicyTest.kt`

**Step 1: Write failing UI policy tests**

Extract pure mappings and test:

- Every `HarnessStatus` has a Chinese label and appropriate severity.
- Every install stage has a Chinese label in the approved order.
- Progress never decreases within one install attempt and READY is 100%.
- “打开工作台” is enabled only for RUNNING.
- Legacy-only state says “发现旧运行时，需要迁移” and does not enable the workbench.
- Manual stop and backoff have distinct user-facing explanations.

Run:

```powershell
.\gradlew.bat :app:testCompanionUnitTest --tests "me.rerere.rikkahub.ui.pages.harness.HarnessUiPolicyTest"
```

Expected: FAIL because the policy file does not exist.

**Step 2: Implement UI policy and phase display**

- Replace the indefinite busy bar with determinate progress when a stage is known.
- Show: `检查环境 → 安装 Debian → 安装 Node → 安装 Harness → 启动并检查`.
- Keep completed stages visible after Activity/process recreation by reading the manager snapshot.
- Show failure stage and a repair action without clearing user data.
- Keep the existing fallback command, log panel, and WebView behavior.
- Update auto-recovery description to mention progressive backoff rather than “always 3 seconds”.

**Step 3: Run UI policy tests and compile**

```powershell
.\gradlew.bat :app:testCompanionUnitTest --tests "me.rerere.rikkahub.ui.pages.harness.HarnessUiPolicyTest"
.\gradlew.bat :app:compileCompanionKotlin
```

Expected: PASS.

**Step 4: Commit**

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessPage.kt app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessVM.kt app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessUiPolicy.kt app/src/test/java/me/rerere/rikkahub/ui/pages/harness/HarnessUiPolicyTest.kt
git commit -m "feat: show Linux Harness installation progress"
```

## Task 7: Version, documentation, and desktop verification

**Files:**

- Modify: `app/build.gradle.kts`
- Modify: `docs/testing/daddy-harness-termux-smoke.md`

**Step 1: Bump the cover-update build**

Set:

```kotlin
versionCode = 216
versionName = "2.5.30"
```

Keep the `companion` build type’s existing `.companion` application ID suffix, `-companion` version suffix, ARM64 packaging, and signing configuration unchanged.

**Step 2: Rewrite the smoke checklist for Debian runtime**

Record exact expected values and leave phone-only results as “待实测”:

- Package: `me.rerere.orangechat.companion`.
- Version: `2.5.30-companion` / 216.
- Container: `daddy-linux`, Debian Bookworm ARM64.
- Node: 24.19.0 Linux ARM64.
- Harness: rc.7.
- Paths and binds from the design.
- Stop/no-revive, first-crash revive, progressive backoff, Boot, Ombre/Amap/Bridge non-regression.

**Step 3: Run focused then full verification**

```powershell
.\gradlew.bat :app:testCompanionUnitTest --tests "*Harness*"
.\gradlew.bat test
.\gradlew.bat :app:packageCompanion
```

Expected: all new Harness tests and the Companion package pass. If unrelated historical tests fail, capture exact failing class/output and prove the same failure against the baseline before classifying it as unrelated.

**Step 4: Verify package identity and signature**

Use Android SDK tools to verify:

- package is `me.rerere.orangechat.companion`;
- versionCode is 216;
- versionName is `2.5.30-companion`;
- APK contains `arm64-v8a` only where native libraries exist;
- signing SHA-256 matches the v215 cover-update APK.

Expected: all identity checks match; otherwise do not install.

**Step 5: Copy the verified APK**

After obtaining filesystem approval for `D:\Daddy-安装包`, copy—not move—the verified APK to:

```text
D:\Daddy-安装包\Daddy-v2.5.30-Linux-Harness-v216-覆盖更新-arm64.apk
```

**Step 6: Commit version/docs**

```powershell
git add -- app/build.gradle.kts docs/testing/daddy-harness-termux-smoke.md
git commit -m "build: prepare Daddy Linux Harness v216"
```

## Task 8: Device installation and Linux Harness smoke test

**Files:**

- Update after testing: `docs/testing/daddy-harness-termux-smoke.md`

**Step 1: Obtain explicit device-install confirmation**

Before changing the phone, show the verified package name, version, signature match, and APK path. Install only after the user confirms.

**Step 2: Cover-update Daddy**

Connect to the user-provided current wireless-debug endpoint and run:

```powershell
& 'D:\Android\Sdk\platform-tools\adb.exe' install -r 'D:\Daddy-安装包\Daddy-v2.5.30-Linux-Harness-v216-覆盖更新-arm64.apk'
```

Expected: `Success`; existing Daddy data remains present.

**Step 3: Start installation from Daddy**

- Open Daddy → DeepSeek Harness.
- Tap “安装 / 修复”.
- Confirm visible progression through PRoot, Debian, Node, Harness, and health check.
- Allow 10–20 minutes on first install; only intervene after the stage-specific error or the 30-minute bound.

Expected: UI reaches RUNNING and 3080 opens.

**Step 4: Verify runtime without exposing secrets**

Through the existing local Termux bridge or Daddy’s status script, check only:

```text
container name = daddy-linux
uname architecture = aarch64
node version = v24.19.0
dsh version = 0.1.0-rc.7
3080 HTTP health = success
```

Do not read or print Harness credential files.

**Step 5: Verify paths and ordinary operations**

- Open the Harness Web UI.
- Select the default workspace.
- Confirm `/host/termux` and `/host/storage` are visible within Android permissions.
- Create, read, and delete only a dedicated smoke-test file in the Harness test workspace.

Expected: all pass; no writes to Ombre, Amap, Bridge, or unrelated storage.

**Step 6: Verify lifecycle**

- Tap Stop and observe at least 20 minutes: no revival.
- Tap Start: service returns.
- Simulate one managed Harness child crash: first recovery occurs after about 3 seconds.
- Simulate repeated managed crashes in a disposable test mode: delays progress through 10 seconds, 30 seconds, and 5 minutes.
- Stop the disposable crash loop and verify 15-minute stable reset only if practical; otherwise verify the counter reset function from unit tests and record phone validation as deferred.

**Step 7: Verify non-regression**

Check:

- normal Daddy chat sends and receives;
- Ombre `pulse`/`breath` still connects;
- Termux Bridge `echo alive` works;
- Amap MCP still connects;
- existing proactive-message and widget behavior is unchanged.

**Step 8: Record evidence and commit smoke results**

Update the smoke document with timestamps, observed versions, each pass/fail result, and sanitized failure snippets. Then run:

```powershell
git add -- docs/testing/daddy-harness-termux-smoke.md
git commit -m "test: record Daddy Linux Harness device smoke"
```

Do not delete `$HOME/daddy-harness`. Report its size and ask the user separately whether to remove it only after all acceptance checks pass.

## Task 9: Final review and delivery decision

**Files:** none unless review finds a defect.

**Step 1: Run final verification from a clean status scope**

```powershell
git status --short
git diff --check HEAD~1..HEAD
.\gradlew.bat :app:testCompanionUnitTest --tests "*Harness*"
.\gradlew.bat :app:packageCompanion
```

Expected: only intentional untracked local diagnostics remain; tests/build pass with fresh output.

**Step 2: Review security and destructive operations**

Confirm:

- no credentials or device logs are tracked;
- no script recursively deletes a broad/computed path;
- data paths are never removed by install/repair;
- only loopback 3080 is used;
- old native runtime remains untouched;
- package/signature still cover-update v215.

**Step 3: Request code review and address findings**

Use `superpowers:requesting-code-review`, fix verified findings with TDD, then rerun the affected checks.

**Step 4: Present completion choices**

Use `superpowers:finishing-a-development-branch`. Report commits, APK path, phone results, remaining known limitations, and the fact that nothing has been pushed. Ask for explicit authorization before pushing to `Fanchuan999/orangechat`.
