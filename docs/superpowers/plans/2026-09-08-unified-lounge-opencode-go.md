# Unified Visitor Lounge and OpenCode Go Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one arm64 companion APK that retains the PC bridge, includes Visitor Lounge, and automatically supplies OpenCode Go conversation sessions.

**Architecture:** Start from the verified Visitor Lounge branch, apply the current PC bridge patch into this isolated worktree, and resolve `ChatService` so both flows remain present. Add a narrow OpenAI-provider policy which injects `x-opencode-session` only for the exact OpenCode Go base URL, using the existing persistent conversation UUID.

**Tech Stack:** Kotlin, Android, Compose, Room, Koin, OkHttp, JUnit, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-08-unified-lounge-opencode-go-design.md`

## Global Constraints

- Work only in `D:\small progect\ai_chat\orangechat-minimal\.worktrees\daddy-unified-lounge-go`.
- Never modify, clear, or reset the original PC bridge or Visitor Lounge worktrees.
- Never log or persist API keys, visitor keys, raw visitor payloads, or OpenCode session identifiers.
- Add a session header only when the base URL, after removing one trailing slash, equals `https://opencode.ai/zen/go/v1`.
- Preserve the Visitor Lounge migration `29 -> 30`, consent/cooldown rules, and the PC task-board terminal-state behavior.

---

### Task 1: Bring the existing PC bridge fixes into the Visitor Lounge branch

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/pcbridge/PcBridgeRelayClient.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/pcbridge/PcBridgeRelayProof.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/pcbridge/PcBridgeTaskService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/pcbridge/PcBridgeTaskServiceTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/pcbridge/PcBridgeRelayClientTest.kt`

**Interfaces:**
- Consumes: the current uncommitted patch in `daddy-code-hut-publish`.
- Produces: `ChatService` containing both Visitor Lounge visit coordination and `pcBridgeTaskToolsProvider().getTools()`.

- [ ] **Step 1: Snapshot the source patch without touching its worktree**

```powershell
git -C 'D:\small progect\ai_chat\orangechat-minimal\.worktrees\daddy-code-hut-publish' diff --binary > "$env:TEMP\daddy-pc-bridge-integration.patch"
```

- [ ] **Step 2: Apply the patch only to this isolated worktree**

```powershell
git apply --3way "$env:TEMP\daddy-pc-bridge-integration.patch"
git status --short
```

If `ChatService.kt` conflicts, its resolved constructor retains both Visitor Lounge dependencies and the lazy `pcBridgeTaskToolsProvider: () -> PcBridgeTaskTools` parameter.

- [ ] **Step 3: Add or retain the terminal-board regression test**

```kotlin
@Test
fun `refresh drains queued progress so a failed task is no longer active`() = runBlocking {
    val latest = service.refreshFromPc()
    assertEquals(PcBridgeRemoteTaskState.FAILED, latest?.state)
    assertEquals(PcBridgeTaskCardState.FAILED, service.cards.first().single().state)
}
```

- [ ] **Step 4: Verify focused bridge behavior**

```powershell
.\gradlew.bat --gradle-user-home '.cache\gradle-user-home' :app:testDebugUnitTest --tests me.rerere.rikkahub.data.pcbridge.PcBridgeTaskServiceTest --tests me.rerere.rikkahub.data.pcbridge.PcBridgeRelayClientTest --no-daemon --no-parallel --max-workers=1 --console=plain
```

Expected: all selected tests pass and a queued failure leaves no active card.

- [ ] **Step 5: Commit the integrated bridge behavior**

```powershell
git add app/build.gradle.kts app/src/main/java/me/rerere/rikkahub/data/pcbridge app/src/main/java/me/rerere/rikkahub/di/AppModule.kt app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/test/java/me/rerere/rikkahub/data/pcbridge
git commit -m "fix: retain PC bridge lifecycle in unified build"
```

### Task 2: Automatically add a per-conversation OpenCode Go session header

**Files:**
- Modify: `ai/src/main/java/me/rerere/ai/provider/Provider.kt`
- Create: `ai/src/main/java/me/rerere/ai/provider/providers/openai/OpenCodeGoSessionPolicy.kt`
- Modify: `ai/src/main/java/me/rerere/ai/provider/providers/OpenAIProvider.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`
- Test: `ai/src/test/java/me/rerere/ai/provider/providers/openai/OpenCodeGoSessionPolicyTest.kt`

**Interfaces:**
- Consumes: `TextGenerationParams.requestSessionId: String?` and `ProviderSetting.OpenAI.baseUrl`.
- Produces: `OpenCodeGoSessionPolicy.apply(baseUrl, headers, requestSessionId): List<CustomHeader>`.

- [ ] **Step 1: Write the failing policy tests**

```kotlin
@Test
fun `Go provider replaces a static session header with the conversation session`() {
    val result = OpenCodeGoSessionPolicy.apply(
        "https://opencode.ai/zen/go/v1",
        listOf(CustomHeader("x-opencode-session", "static-value")),
        "conversation-uuid",
    )
    assertEquals(listOf(CustomHeader("x-opencode-session", "conversation-uuid")), result)
}

@Test
fun `other provider preserves custom headers unchanged`() {
    val headers = listOf(CustomHeader("x-opencode-session", "user-value"))
    assertEquals(headers, OpenCodeGoSessionPolicy.apply("https://api.siliconflow.cn/v1", headers, "id"))
}
```

- [ ] **Step 2: Run the test and observe RED**

```powershell
.\gradlew.bat --gradle-user-home '.cache\gradle-user-home' :ai:testDebugUnitTest --tests me.rerere.ai.provider.providers.openai.OpenCodeGoSessionPolicyTest --no-daemon --no-parallel --max-workers=1 --console=plain
```

Expected: compilation fails because the policy is absent.

- [ ] **Step 3: Implement the narrow policy and parameter propagation**

```kotlin
data class TextGenerationParams(
    // existing fields stay unchanged
    val requestSessionId: String? = null,
)

object OpenCodeGoSessionPolicy {
    fun apply(baseUrl: String, headers: List<CustomHeader>, requestSessionId: String?): List<CustomHeader> {
        if (baseUrl.trimEnd('/') != "https://opencode.ai/zen/go/v1") return headers
        val value = requestSessionId ?: Uuid.random().toString()
        return headers.filterNot { it.name.equals("x-opencode-session", true) } +
            CustomHeader("x-opencode-session", value)
    }
}
```

`GenerationHandler` supplies `conversationId?.toString()` in `requestSessionId`. `OpenAIProvider` applies the policy before delegating to either `ResponseAPI` or `ChatCompletionsAPI`.

- [ ] **Step 4: Verify green and commit**

```powershell
.\gradlew.bat --gradle-user-home '.cache\gradle-user-home' :ai:testDebugUnitTest --tests me.rerere.ai.provider.providers.openai.OpenCodeGoSessionPolicyTest --tests me.rerere.ai.provider.providers.openai.ChatCompletionsAPIMessageTest --no-daemon --no-parallel --max-workers=1 --console=plain
git add ai/src/main/java/me/rerere/ai/provider/Provider.kt ai/src/main/java/me/rerere/ai/provider/providers/openai/OpenCodeGoSessionPolicy.kt ai/src/main/java/me/rerere/ai/provider/providers/OpenAIProvider.kt app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt ai/src/test/java/me/rerere/ai/provider/providers/openai/OpenCodeGoSessionPolicyTest.kt
git commit -m "fix: add per-conversation OpenCode Go session header"
```

### Task 3: Verify and package the unified companion APK

**Files:**
- Verify: `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/30.json`
- Verify: `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_29_30.kt`
- Output: `app/build/outputs/apk/companion/app-arm64-v8a-companion.apk`

**Interfaces:**
- Consumes: the committed bridge integration, OpenCode Go policy, and existing Visitor Lounge implementation.
- Produces: an updateable signed arm64 companion APK.

- [ ] **Step 1: Run full tests**

```powershell
.\gradlew.bat --gradle-user-home '.cache\gradle-user-home' :app:testDebugUnitTest --no-daemon --no-parallel --max-workers=1 --console=plain
```

Expected: zero failures.

- [ ] **Step 2: Build the companion variant**

```powershell
.\gradlew.bat --gradle-user-home '.cache\gradle-user-home' :app:assembleCompanion --no-daemon --no-parallel --max-workers=1 --console=plain
```

Expected: `BUILD SUCCESSFUL` and `app-arm64-v8a-companion.apk` exists.

- [ ] **Step 3: Validate before delivery**

```powershell
Get-Item '.\app\build\outputs\apk\companion\app-arm64-v8a-companion.apk' | Select-Object FullName,Length,LastWriteTime
git diff --check
git status --short
```

Expected: an arm64 APK with a fresh timestamp, no whitespace errors, and no staged build artifacts.
