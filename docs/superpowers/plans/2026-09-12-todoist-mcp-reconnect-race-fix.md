# Todoist MCP Planned-Reconnect Race Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop Todoist MCP tool calls from failing with `McpException: Connection closed` when Daddy refreshes an OAuth token and deliberately replaces its MCP transport.

**Architecture:** Keep the existing OAuth-refresh → new-transport → retry-once design. Fix the lifecycle ordering in `McpManager`: a deliberate client close must first put that server into a non-connected status, so the existing `onClose`/`onError` callbacks do not queue a second reconnect that closes the newly created client. Preserve reconnects for genuine, unexpected transport loss.

**Tech Stack:** Kotlin, coroutines/StateFlow, Ktor, `io.modelcontextprotocol:kotlin-sdk` 0.14.0, JUnit 4, Android/Gradle.

**Spec:** This is a self-contained incident plan based on the real device failure reported on 2026-09-12. It supplements `docs/superpowers/plans/2026-09-11-claude-code-handoff.md`; read both before editing.

## Global Constraints

- Work only in `D:\small progect\ai_chat\orangechat-minimal\.worktrees\daddy-unified-lounge-go` on branch `codex/daddy-unified-lounge-go`.
- Begin with `git status --short`. Do not use `git reset`, `git clean`, `git checkout --`, or any bulk restore command.
- At the time this plan was written, the following **unrelated, user-owned PC Bridge changes are already dirty**. Do not edit, revert, stage, commit, or format them as part of this Todoist fix:
  - `app/src/main/java/me/rerere/rikkahub/data/ai/tools/PcBridgeTaskTools.kt`
  - `app/src/main/java/me/rerere/rikkahub/data/pcbridge/PcBridgeTask.kt`
  - `app/src/main/java/me/rerere/rikkahub/data/pcbridge/PcBridgeTaskBoardStore.kt`
  - `app/src/main/java/me/rerere/rikkahub/data/pcbridge/PcBridgeTaskService.kt`
  - `app/src/test/java/me/rerere/rikkahub/data/ai/tools/PcBridgeTaskToolsTest.kt`
  - `app/src/test/java/me/rerere/rikkahub/data/pcbridge/PcBridgeTaskServiceTest.kt`
- There is also an untracked user-owned plan: `docs/superpowers/plans/2026-09-09-autonomous-social-activity.md`. Preserve it and do not include it in a commit.
- Never put an OAuth access token, refresh token, full authorization callback URL, API key, or user Todoist data into source, logs, tests, commits, screenshots, or this document.
- Do not remove or weaken the existing automatic OAuth refresh. Do not tell the user to re-authorize as a workaround for this error: the supplied stack trace has no HTTP 401, `UNAUTHORIZED`, or invalid-token evidence.
- Do not add a generic retry for `Connection closed`. A Todoist write tool may have reached the server before the connection died; blindly replaying it can create duplicate tasks. The intended fix prevents the local duplicate reconnect that causes this particular closure.
- Do not change the dedicated Visitor Lounge decision, Android alarm behavior, generic MCP permissions, or PC Bridge in this task.
- Keep the public behavior: genuine remote disconnects while status is `McpStatus.Connected` still use the existing exponential-backoff reconnect (1 s, 2 s, 4 s, …; maximum five attempts).

---

## 1. Incident evidence and verified root cause

### What the user saw

While using Todoist after it had appeared to drop offline, the tool result shown in chat contained this shape of exception:

```text
[io.modelcontextprotocol.kotlin.sdk.types.McpException] Connection closed
… Protocol.doClose(Protocol.kt:262)
… StreamableHttpClientTransport.close(StreamableHttpClientTransport.kt:218)
… McpManager$reconnectClient$2.invokeSuspend(McpManager.kt:590)
```

This is not just Logcat noise. `GenerationHandler` catches an exception from `toolDef.execute(...)` and deliberately serializes it into the JSON-shaped tool error visible in chat. The exception therefore escaped a real MCP tool invocation.

### The current code path

At commit `bf6e9e6` (`feat: unify MCP activity and companion experience`), the new Todoist recovery logic is in:

```text
app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt
  callToolDetailed()                         around lines 217–316
  addClient()                                around lines 383–437
  removeClient()                             around lines 510–524
  scheduleReconnect()                        around lines 526–571
  reconnectClient()                          around lines 585–639
  ensureFreshToken(forceRefresh = ...)       around lines 805–840

app/src/main/java/me/rerere/rikkahub/data/ai/mcp/transport/
  StreamableHttpClientTransport.kt
  SseClientTransport.kt

app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt
  tool execution exception → visible JSON error handling
```

The relevant flow is:

1. `callToolDetailed()` receives a normal MCP tool error that contains an OAuth failure, or catches an OAuth-style exception.
2. It calls `ensureFreshToken(..., forceRefresh = true)`, which exchanges the refresh token and persists the rotated access token (and, if present, refresh token).
3. A transport captures HTTP headers at construction time, so `requiresMcpTransportReconnect()` correctly says the old transport cannot use the fresh bearer token.
4. `callToolDetailed()` calls `reconnectClient(refreshedConfig)` before retrying the original tool once.
5. `reconnectClient()` currently closes the old `Client` at line ~590 **while that MCP server still has `McpStatus.Connected`**. It only changes the status to `Connecting` later, after the close.
6. Both built-in transport types call `invokeOnCloseCallback()` synchronously during their `close()` implementation. A failed Streamable HTTP session termination can also invoke `onError` during that same intentional close.
7. `McpManager` registered both `transport.onClose` and `transport.onError` to call `scheduleReconnect(config)` whenever the server status is `Connected`.
8. Therefore, a deliberate old-client close incorrectly queues an automatic reconnect with the 1-second delay. The direct renewal path then creates a fresh client, but the delayed job later calls `reconnectClient()` again and can close that fresh client while the Todoist retry/tool call is using it.
9. The MCP SDK completes the in-flight request with `McpException: Connection closed`; `GenerationHandler` renders the stack as the chat-visible JSON error.

### Why the previous fix made this visible

The previous patch was correct that a refreshed bearer token requires a new transport, and correct that Todoist can return OAuth failure inside a successful MCP `CallToolResult`. What it did not account for was that the pre-existing automatic on-close reconnect mechanism treats a **planned replacement** like a network failure.

This is a local connection-lifecycle race, not a reason to change the OAuth server URL, permissions, Todoist account, network/VPN, or model provider.

## 2. Desired behavior and non-goals

### Must happen

- A normal Todoist OAuth recovery refreshes and persists the new token, replaces the transport exactly once, and retries the original tool exactly once.
- During an intentional replacement/removal, neither `onClose` nor `onError` may schedule a backoff reconnect.
- If a server disconnects unexpectedly while really connected, existing automatic reconnection still starts.
- A second `reconnectClient()` must not close the fresh replacement client as a delayed side effect of the first one.
- An actual OAuth failure after the one retry still transitions to `McpStatus.NeedsAuthorization` and displays the existing authorization-required tool result.

### Explicit non-goals

- No new token format, OAuth scope, dynamic registration, or callback behavior.
- No hidden reauthorization, no token logging, and no direct Todoist REST API integration.
- No change to `StreamableHttpClientTransport.close()` that suppresses `invokeOnCloseCallback()` globally. The callback is required for genuine disconnections.
- No automatic repeat of a write request after arbitrary `Connection closed` errors.
- No version bump or APK installation until the isolated patch is tested and the user asks for a new installable build. If an APK is requested and the Companion version is still code `239`, coordinate a single version bump rather than overwriting/reusing the existing version.

## 3. File map and implementation boundary

| File | Change | Reason |
| --- | --- | --- |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt` | Modify | Move intentional-close status transition ahead of close; route close/error callbacks through one reconnect eligibility predicate; ensure intentional `removeClient` also cannot schedule reconnect. |
| `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpReconnectPolicyTest.kt` | Create | Pin the reconnect decision table so planned replacement/removal never regresses into auto-reconnect. |
| `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthTransportRefreshPolicyTest.kt` | Optionally modify only if a small shared fixture is useful | Preserve existing token-refresh regression cases. Do not turn it into an integration test with real OAuth. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/transport/StreamableHttpClientTransport.kt` | Read only | Confirms `close()` invokes close callbacks synchronously. Do not change it for this fix. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/transport/SseClientTransport.kt` | Read only | Confirms its intentional close also invokes close callbacks synchronously. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt` | Read only | Explains why an uncaught tool exception appears as JSON in chat. Do not hide the error there. |

Keep the source change focused in `McpManager.kt`. Do not introduce a second MCP manager, a Todoist-specific class, or an unrelated coroutine framework.

## 4. Required reconnect policy

Create one small internal, unit-testable predicate in `McpManager.kt` (or a tightly scoped same-package Kotlin file if that reads better):

```kotlin
internal fun shouldScheduleMcpReconnect(status: McpStatus?): Boolean =
    status == McpStatus.Connected
```

Use that predicate in every existing `transport.onClose` and `transport.onError` callback instead of open-coding the condition. This is intentionally simple: planned replacement/removal is represented by changing the status **before** closing, not by a global `ignore all closes` switch.

For a planned replacement, the required ordering is:

```kotlin
val config = ensureFreshToken(configInput)
setStatus(config, McpStatus.Connecting) // must happen before oldClient.close()

val oldEntry = clients[config.id]
if (oldEntry != null) {
    runCatching { oldEntry.second.close() }
        .onFailure { error ->
            Log.e(TAG, formatMcpTransportErrorLog(config.commonOptions.name, error))
        }
    clients.remove(config.id)
}

// construct and connect the new transport/client as the existing code does
```

For an intentional removal (including `addClient()` replacing an existing client), the required ordering is:

```kotlin
cancelReconnect(config.id)
val entry = clients.remove(config.id)
if (entry != null) {
    setStatus(entry.first, McpStatus.Idle) // must happen before entry.second.close()
    runCatching { entry.second.close() }
        .onFailure { error ->
            Log.e(TAG, formatMcpTransportErrorLog(entry.first.commonOptions.name, error))
        }
    syncingStatus.emit(syncingStatus.value.toMutableMap().apply { remove(config.id) })
}
```

Notes for the implementer:

- Do not leave the original later `setStatus(config, McpStatus.Connecting)` in `reconnectClient()` if it becomes redundant. The important property is not two assignments; it is that `Connecting` is already observable before `close()` invokes the transport callbacks.
- Leave `scheduleReconnect()` and its backoff values unchanged unless a test demonstrates another duplicate job can survive this status ordering. Do not call `cancelReconnect()` from inside the reconnect job in a way that cancels the job that is currently executing.
- The `onError` callback needs the same eligibility check as `onClose`: Streamable HTTP `close()` can produce an error while trying to terminate a server session, and that error is still part of a planned shutdown.
- Preserve `runCatching` around `Client.close()`. The SDK can report `McpException: Connection closed` while closing outstanding protocol state. The change is to prevent a second reconnect from interrupting the replacement tool call, not to pretend every close cannot throw.
- Do not remove the later `clients.remove()`/status cleanup semantics unless an exact test requires it. The manager currently expects disabled servers to disappear from `syncingStatus`.

## 5. Step-by-step implementation plan

### Task 1: Capture the reconnect policy with failing unit tests

**Files:**

- Create: `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpReconnectPolicyTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt`

**Interfaces:**

- Consumes: `McpStatus` from `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpStatus.kt`.
- Produces: `internal fun shouldScheduleMcpReconnect(status: McpStatus?): Boolean`.
- Later tasks rely on every transport callback using this predicate.

- [ ] **Step 1: Create the failing test class with the exact policy table.**

```kotlin
package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpReconnectPolicyTest {
    @Test
    fun `unexpected close from a connected server schedules reconnect`() {
        assertTrue(shouldScheduleMcpReconnect(McpStatus.Connected))
    }

    @Test
    fun `planned replacement while connecting does not schedule reconnect`() {
        assertFalse(shouldScheduleMcpReconnect(McpStatus.Connecting))
    }

    @Test
    fun `intentional removal while idle does not schedule reconnect`() {
        assertFalse(shouldScheduleMcpReconnect(McpStatus.Idle))
    }

    @Test
    fun `authorization state does not schedule reconnect`() {
        assertFalse(shouldScheduleMcpReconnect(McpStatus.NeedsAuthorization))
    }
}
```

- [ ] **Step 2: Run just this test before adding production code.**

```powershell
$taskTemp = Join-Path (Get-Location) '.cache\java-temp'
New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null
$env:TEMP = $taskTemp
$env:TMP = $taskTemp
$env:GRADLE_USER_HOME = 'D:\Daddy-Gradle'
$env:JAVA_HOME = 'D:\ebbingflow\jdk-17.0.19+10'
.\gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 --console=plain :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.ai.mcp.McpReconnectPolicyTest'
```

Expected result: compilation failure because `shouldScheduleMcpReconnect` does not exist yet. This establishes the test is exercising new behavior rather than passing accidentally.

- [ ] **Step 3: Add the minimal production predicate near the existing MCP helper functions.**

```kotlin
internal fun shouldScheduleMcpReconnect(status: McpStatus?): Boolean =
    status == McpStatus.Connected
```

Do not make the function Todoist-specific. It is a general MCP transport policy.

- [ ] **Step 4: Re-run the focused test.**

Run the same Gradle command from Step 2.

Expected result: `BUILD SUCCESSFUL` and four passing tests.

### Task 2: Make planned replacement/removal non-reconnectable

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpReconnectPolicyTest.kt`

**Interfaces:**

- Consumes: `shouldScheduleMcpReconnect(status)` from Task 1.
- Produces: a `McpManager` where intentional closes see `Connecting` or `Idle`, while unexpected closes still see `Connected`.
- Existing consumers remain unchanged: `callToolDetailed()`, settings MCP UI, idle activity, and normal chat continue using `McpManager` exactly as before.

- [ ] **Step 1: Replace each duplicated callback condition with the shared predicate.**

There are two callback pairs today: one in `addClient()` around lines 402–418, and one in `reconnectClient()` around lines 605–618. Change both callback types to follow this shape:

```kotlin
transport.onClose {
    Log.i(TAG, "Transport closed for ${config.commonOptions.name}")
    if (shouldScheduleMcpReconnect(syncingStatus.value[config.id])) {
        scheduleReconnect(config)
    }
}

transport.onError { error ->
    Log.e(TAG, formatMcpTransportErrorLog(config.commonOptions.name, error))
    if (shouldScheduleMcpReconnect(syncingStatus.value[config.id])) {
        scheduleReconnect(config)
    }
}
```

Keep existing log redaction helpers. Do not log URLs, headers, request bodies, or OAuth state.

- [ ] **Step 2: Move the replacement status transition before the old close.**

In `reconnectClient(configInput)`, retain the existing `ensureFreshToken` call. Immediately after it, set `McpStatus.Connecting`, then close/remove the old entry, then construct/connect the new transport. Delete the duplicate later `setStatus(config, McpStatus.Connecting)` if it has no remaining purpose.

The ordering to preserve is exactly:

```text
fresh config → status Connecting → old client close → remove old entry
→ create new transport/client → store new entry → connect/sync → status Connected
```

Do not move `ensureFreshToken` after the status transition: the status update must use the actual config being installed.

- [ ] **Step 3: Mark explicit removal non-connected before close.**

In `removeClient(config)`, after `clients.remove(config.id)` returns a non-null entry and before `entry.second.close()`, set `McpStatus.Idle`. Preserve the final map removal from `syncingStatus` after close.

This is not cosmetic: `addClient()` starts by calling `removeClient()`. Without this guard, editing a server configuration can create the same delayed reconnect race against the replacement client.

- [ ] **Step 4: Re-read both transport implementations; make no transport change unless the callback timing contradicts the assumptions.**

Verify these existing facts remain true:

```text
StreamableHttpClientTransport.close()
  finally { initialized.store(false); invokeOnCloseCallback() }

SseClientTransport.close()
  closeResources() → invokeOnCloseCallback()
```

If a future dependency upgrade makes callbacks asynchronous, stop and adjust the design rather than assuming status ordering alone is sufficient. For the pinned 0.14.0 code currently in this repository, the callbacks are invoked inside the close call.

- [ ] **Step 5: Run the focused reconnect-policy and OAuth-refresh tests.**

```powershell
.\gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 --console=plain :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.ai.mcp.McpReconnectPolicyTest' --tests 'me.rerere.rikkahub.data.ai.mcp.McpOAuthTransportRefreshPolicyTest'
```

Expected result: all existing four OAuth tests and all new reconnect-policy tests pass.

### Task 3: Regression review, build, and safe handoff

**Files:**

- Review: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt`
- Review: `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpReconnectPolicyTest.kt`
- Review: `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthTransportRefreshPolicyTest.kt`

**Interfaces:**

- Consumes: Tasks 1–2.
- Produces: one isolated MCP lifecycle fix ready for user-approved APK creation and device validation.

- [ ] **Step 1: Inspect only the Todoist/MCP diff.**

```powershell
git diff -- app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpReconnectPolicyTest.kt app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthTransportRefreshPolicyTest.kt
git status --short
```

Confirm no PC Bridge file listed in Global Constraints was modified by this task.

- [ ] **Step 2: Run the complete JVM test suite.**

```powershell
.\gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 --console=plain :app:testDebugUnitTest
```

Expected result: `BUILD SUCCESSFUL`. Report any existing unrelated failures separately; do not “fix” PC Bridge or other dirty work just to get a green run.

- [ ] **Step 3: Build the Companion APK only after tests pass and only if the user asks for an installable test package.**

```powershell
.\gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 --console=plain :app:assembleCompanion
```

Expected artifact path:

```text
app\build\outputs\apk\companion\app-arm64-v8a-companion.apk
```

Before copying an APK, inspect `app/build.gradle.kts` and the generated package metadata. The previous user-installed package was `2.5.53-companion`, version code `239`; Android will report “same version” if this is not bumped. Do not overwrite the older known-good file in `D:\Daddy-安装包`; use a new descriptive filename and calculate SHA-256.

- [ ] **Step 4: Request explicit user approval before using wireless ADB, installing, or interacting with a device.**

Do not guess an ADB address or reuse an old pairing code. Device access and installation require the user’s current address and consent.

- [ ] **Step 5: Use a minimal real-device acceptance test after user approval.**

1. Leave Todoist connected until its access token has expired or use the normal failure path; do not alter tokens manually.
2. Ask Daddy to run one harmless **read-only** Todoist action, such as listing today’s tasks/projects, not creating/updating/completing a task.
3. Expect the first OAuth failure to refresh the token, replace the transport once, retry once, and return the tool result without the visible `Connection closed` JSON.
4. Immediately use a second read-only Todoist action. It should work through the same fresh client; it must not drop offline about one second later.
5. If it still fails, collect only a redacted error category and the MCP status transition (`Connected → Connecting → Connected`, etc.). Do not collect headers, token values, authorization URLs, or task content.

- [ ] **Step 6: Commit only after the user confirms the scope and all requested checks pass.**

Use path-specific staging; never run `git add .` in this dirty worktree:

```powershell
git add -- app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpReconnectPolicyTest.kt app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthTransportRefreshPolicyTest.kt
git diff --cached --check
git diff --cached --stat
git commit -m "fix: prevent planned MCP reconnect races"
```

If the optional OAuth test file was not changed, omit it from `git add`. Do not include this plan or unrelated PC Bridge files unless the user separately asks.

## 6. Completion checklist

- [ ] The reported `Connection closed` stack is explained as an intentional-close/duplicate-reconnect race, not mislabelled as expired authorization.
- [ ] Both Streamable HTTP and SSE callback registrations use the shared reconnect eligibility predicate.
- [ ] `reconnectClient()` makes `Connecting` observable before closing the old client.
- [ ] `removeClient()` makes `Idle` observable before closing an intentionally removed client.
- [ ] Unexpected close/error while `Connected` still schedules reconnect.
- [ ] No general replay is added for `Connection closed`.
- [ ] Focused tests and full JVM tests have concrete pass/fail output.
- [ ] Any APK has a new installable version code, a new friendly filename, and SHA-256 recorded.
- [ ] Real Todoist validation uses a read-only request and records no secrets.

## 7. First prompt to give Claude Code

Copy this whole block into Claude Code after it has received the earlier general handoff:

```text
Please fix the isolated Todoist MCP reconnection race in the Daddy / OrangeChat Android project.

Work only in:
D:\small progect\ai_chat\orangechat-minimal\.worktrees\daddy-unified-lounge-go

First read both documents in full:
1. docs\superpowers\plans\2026-09-11-claude-code-handoff.md
2. docs\superpowers\plans\2026-09-12-todoist-mcp-reconnect-race-fix.md

Then run `git status --short` and report the exact dirty files. There are known user-owned PC Bridge edits; preserve them. Do not reset, clean, checkout, delete files, run `git add .`, or commit anything until the Todoist-only diff has been reviewed and tests pass.

Implement Tasks 1–2 of the Todoist reconnect-race plan using test-first changes. The supplied user error is NOT a token/permission error: it is a planned MCP transport close being mistaken for an unexpected disconnect, which queues a second reconnect and closes the fresh client. Do not change OAuth endpoints, ask the user to reauthorize, or add a blind retry for `Connection closed`.

After focused and full JVM tests, stop and report: changed files, exact test results, whether an APK version bump is needed, and the proposed device acceptance steps. Do not use ADB or build/copy/install an APK unless I explicitly ask.
```
