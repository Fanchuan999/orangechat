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
