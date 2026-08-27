# Pending-pair local-abandon fix report

## Scope

Implemented the user-approved option A only:

- Pairing credentials saved before `pairJoin` carry a persistent `pendingConfirmation` marker.
- A refresh failure for that marker becomes `PendingRecovery`, not generic `Unavailable`.
- The pending state offers Refresh and a second-confirmed local-only `放弃本机待恢复配对` action.
- The action clears only the Android encrypted record. It never makes a relay, Supabase, PC, memory, Ombre, chat, or worldbook request.
- A confirmed active refresh rewrites the marker as confirmed when local persistence is available.

Old stored records deserialize with `pendingConfirmation = false`, so they are treated as confirmed and never show the abandon action.

## RED evidence

Before production edits, the focused command was run with the D-drive Gradle installation:

```powershell
$env:GRADLE_USER_HOME='D:\Daddy-Gradle'
& 'D:\Daddy-Gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' --console=plain --quiet :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgePairingServiceTest' --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgeUiPolicyTest' --tests 'me.rerere.rikkahub.ui.pages.codehut.CodeHutVMActionSafetyTest'
```

It failed as expected at `:app:compileDebugUnitTestKotlin` because `PendingRecovery`, `pendingConfirmation`, `abandonPendingPairing`, and `abandonPendingPcBridge` did not exist.

## GREEN evidence

```powershell
$env:GRADLE_USER_HOME='D:\Daddy-Gradle'
& 'D:\Daddy-Gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' --console=plain --quiet :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgePairingServiceTest' --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgeSecretStoreTest' --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgeUiPolicyTest' --tests 'me.rerere.rikkahub.ui.pages.codehut.CodeHutVMActionSafetyTest' :app:compileDebugAndroidTestKotlin
```

Result: `BUILD SUCCESSFUL` (quiet Gradle output). The XML reports show 24 focused JVM tests total, all with `failures="0"` and `errors="0"`:

- `PcBridgePairingServiceTest`: 13
- `PcBridgeSecretStoreTest`: 3
- `PcBridgeUiPolicyTest`: 4
- `CodeHutVMActionSafetyTest`: 4

The Android instrumentation source, including the explicit confirmation-dialog test, compiled under `:app:compileDebugAndroidTestKotlin`. It was not installed or executed, by the no-install constraint.

## Manual recovery copy

The manual now explains the rare response-loss tradeoff: after refresh fails, the user may clear only the phone-local pending record, then generate a fresh invitation. In the rare case that the PC/relay accepted the pairing while its response was lost, the old PC pairing remains for fixed-PC workbench cleanup.

## Review hardening — pending recovery races and relay states

### Added behavior

- A refresh captures a recovery operation generation. Local abandon increments that generation before clearing the encrypted record, so an older in-flight refresh may finish its network call but cannot re-save credentials or overwrite `Unpaired`.
- Pending credentials become `Paired` only when the relay explicitly returns `active`. Missing or unknown state remains `PendingRecovery`; confirmed records become generic `Unavailable` instead.
- The Code Hut VM invokes the local abandon action only when its current bridge state is `PendingRecovery`.
- The compatibility test now deserializes an actual stored-record JSON blob whose `pendingConfirmation` field is absent.

### RED evidence

Before the hardening implementation, the focused command below failed at `:app:compileDebugUnitTestKotlin` because the test could not access a serializable `PcBridgeStoredRecord` representation needed to decode an old JSON record. The same test batch also contained the new stale-refresh, unknown-state, and VM-gate regression tests.

```powershell
$env:GRADLE_USER_HOME='D:\Daddy-Gradle'
& 'D:\Daddy-Gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' --console=plain :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgePairingServiceTest' --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgeSecretStoreTest' --tests 'me.rerere.rikkahub.ui.pages.codehut.CodeHutVMActionSafetyTest'
```

### GREEN evidence

```powershell
$env:GRADLE_USER_HOME='D:\Daddy-Gradle'
& 'D:\Daddy-Gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' --console=plain --quiet :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgePairingServiceTest' --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgeSecretStoreTest' --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgeUiPolicyTest' --tests 'me.rerere.rikkahub.ui.pages.codehut.CodeHutVMActionSafetyTest' :app:compileDebugAndroidTestKotlin
```

Result: successful, with 28 focused JVM tests and no failures or errors:

- `PcBridgePairingServiceTest`: 16
- `PcBridgeSecretStoreTest`: 3
- `PcBridgeUiPolicyTest`: 4
- `CodeHutVMActionSafetyTest`: 5

Android test sources compiled successfully; no device install or deployment was performed.

## Review hardening — overlapping refreshes

### Added behavior

An `active` or `revoked` relay result is authoritative for the local pairing record. After either result is applied, the recovery operation generation advances. Any older refresh snapshot that completes later is ignored, so it cannot replace `Paired` or `Unpaired` with `PendingRecovery`.

### RED evidence

Before the generation advance was added, the following focused service test reliably failed: the first pending refresh returned `active`, then the older second snapshot returned `unknown` and overwrote the card state. The failure was at `PcBridgePairingServiceTest.kt:187`.

```powershell
$env:GRADLE_USER_HOME='D:\Daddy-Gradle'
& 'D:\Daddy-Gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' --console=plain :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgePairingServiceTest'
```

### GREEN evidence

```powershell
$env:GRADLE_USER_HOME='D:\Daddy-Gradle'
& 'D:\Daddy-Gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' --console=plain --quiet :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgePairingServiceTest' --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgeSecretStoreTest' --tests 'me.rerere.rikkahub.data.pcbridge.PcBridgeUiPolicyTest' --tests 'me.rerere.rikkahub.ui.pages.codehut.CodeHutVMActionSafetyTest' :app:compileDebugAndroidTestKotlin
```

Result: successful, 29 focused JVM tests with zero failures or errors (17 service, 3 secret-store, 4 policy, 5 VM). Android test sources also compiled successfully.
