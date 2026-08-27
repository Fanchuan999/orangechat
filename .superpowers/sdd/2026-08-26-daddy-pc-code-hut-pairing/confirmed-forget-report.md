# Confirmed local-forget recovery — verification report

## Scope

Added the explicitly confirmed, phone-local recovery path for a persisted **confirmed** PC Bridge record when refresh or unlink cannot be verified. The path clears only the encrypted local record; it does not call the relay, PC, Supabase, or a model. Pending records retain the separate pending-abandon path.

The manual now invokes the pinned companion TypeScript CLI directly with Node 24's `--experimental-strip-types` option and an explicit `$BridgeCli` path.

## TDD — RED evidence

1. Before production changes, ran:

   ```powershell
   $env:JAVA_HOME='D:\ebbingflow\jdk-17.0.19+10'
   $env:PATH="$env:JAVA_HOME\bin;$env:PATH"
   $env:GRADLE_USER_HOME='D:\Daddy-Gradle'
   & 'D:\Daddy-Gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgePairingServiceTest' --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgePairingManualTest'
   ```

   The run completed with the intended three failing assertions: failed confirmed refresh, failed confirmed unlink, and the first-pairing manual invocation. These represented the missing confirmed-record recovery state/action and the stale launcher-only manual.

2. After adding the VM/Page-focused tests but before the UI production branch, compilation failed as intended because `PcBridgeUiState.ConfirmedRecovery` had no `PcBridgeCard` `when` branch. This demonstrated the missing explicit UI wiring rather than relying on a string match.

## GREEN evidence

After the minimal implementation, the following fresh focused JVM run completed successfully:

```powershell
$env:JAVA_HOME='D:\ebbingflow\jdk-17.0.19+10'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_USER_HOME='D:\Daddy-Gradle'
& 'D:\Daddy-Gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgePairingServiceTest' --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgeUiPolicyTest' --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgePairingManualTest' --tests 'me.rerere.rikkahub.ui.pages.codehut.CodeHutVMActionSafetyTest'
```

Result: `BUILD SUCCESSFUL in 14s` (`176 actionable tasks: 3 executed, 173 up-to-date`).

The Compose instrumentation source was also freshly compiled without installing it on a device:

```powershell
& 'D:\Daddy-Gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' :app:compileDebugAndroidTestKotlin
```

Result: `BUILD SUCCESSFUL in 7s` (`160 actionable tasks: 160 up-to-date`).

No Gradle output lock occurred in these final two runs. No cache was cleared and no process was terminated.

## Coverage added

- failed refresh/unlink of a confirmed credential exposes only confirmed local-forget recovery;
- pending and absent credentials cannot expose that action;
- confirmed local forget clears only the local record, with no relay call;
- a record that changed to pending is preserved and enters pending recovery;
- policy and VM gate delegation to the exact `ConfirmedRecovery` state;
- the Compose dialog requires explicit confirmation and contains no secret text;
- the manual checks every first-pairing command uses the pinned `main.ts` CLI.

## Deliberate scope boundaries

- No Edge Function, Supabase, relay, APK installation, deployment, push, or memory/Ombre changes were made.
- Existing user-owned Code Hut/Harness changes remain untouched. The shared `CodeHutVM.kt` and `CodeHutPage.kt` PC Bridge hunks intentionally remain **unstaged** because each file already contains unrelated user work; final release assembly must include those precise hunks after a separate shared-file review.

## Follow-up review — queued refresh/forget race

### Root cause

The ViewModel already gates the confirmed local-forget action to `ConfirmedRecovery`, but a delayed refresh may hold `pairingOperationMutex` while the UI is still showing that state. If the refresh then receives authoritative `active` status, it restores `Paired`; a forget request that was queued while the old UI was visible must not clear that newly healthy credential after it acquires the mutex.

### TDD — RED evidence

Added `confirmed forget queued behind active refresh preserves the recovered pairing` using the real service, encrypted store, mutex, and an ordered delayed relay transport. With the new service-side `ConfirmedRecovery` recheck temporarily removed, the focused command failed exactly as expected: one test, one assertion failure at the final `Paired` assertion because the queued forget cleared the healthy local record.

```powershell
$env:JAVA_HOME='D:\ebbingflow\jdk-17.0.19+10'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_USER_HOME='D:\Daddy-Gradle'
& 'D:\Daddy-Gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgePairingServiceTest.confirmed forget queued behind active refresh preserves the recovered pairing'
```

### Minimal fix

`forgetUnavailableConfirmedPairing()` now rechecks `mutableState` **after** it obtains `pairingOperationMutex`. It clears a confirmed credential only while the current state is still `ConfirmedRecovery`. If a serialized operation has already moved the service elsewhere, it reloads the local record without clearing it: an absent record becomes `Unpaired`, a pending record becomes `PendingRecovery`, and an already recovered confirmed record leaves its authoritative state intact.

### GREEN evidence

The normal worktree output JAR was held by another Windows process (`bundleDebugClassesToCompileJar/classes.jar`); it was not deleted and no process/cache was changed. A temporary, ignored Gradle init script isolated this verification in `.pcbridge-race-test-build/` and was not committed.

```powershell
& 'D:\Daddy-Gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' --console=plain -I '.superpowers\sdd\2026-08-26-daddy-pc-code-hut-pairing\pcbridge-race-test.init.gradle' :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgePairingServiceTest' --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgeUiPolicyTest' --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgePairingManualTest' --tests 'me.rerere.rikkahub.ui.pages.codehut.CodeHutVMActionSafetyTest'
```

Result: `BUILD SUCCESSFUL`; 36 focused JVM tests, 0 failures, 0 errors.

```powershell
& 'D:\Daddy-Gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' --console=plain -I '.superpowers\sdd\2026-08-26-daddy-pc-code-hut-pairing\pcbridge-race-test.init.gradle' :app:compileDebugAndroidTestKotlin
```

Result: `BUILD SUCCESSFUL in 13s`. The only output was the existing unresolved opt-in marker warning for `ExperimentalNavigation3Api`; no source or dependency changes were made for it.
