# Daddy 思考沉浸化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a global, cache-stable Chinese thinking convention and display-only role aliases so Daddy’s reasoning calls the user “宝宝” and itself “Daddy”.

**Architecture:** Store the global switch and aliases in `DisplaySetting`, so existing preference serialization and backup remain compatible. A pure helper builds the fixed request instruction and formats only on-screen reasoning; `GenerationHandler` owns instruction insertion and `ChatMessageReasoning` owns display formatting.

**Tech Stack:** Kotlin, Kotlin serialization, Jetpack Compose, JUnit, Android Gradle.

## Global Constraints

- Default state is enabled with user alias `宝宝` and assistant alias `Daddy`.
- Never mutate persisted `UIMessagePart.Reasoning`, conversation history, tool calls, exports, or Ombre content.
- No translation-model call; only prompt guidance plus deterministic role-label fallback.
- The request instruction must be stable and inserted before memory/tool/dynamic prompt content.
- `time_reminder` remains historical metadata; `get_time_info` remains the only live local time tool.

---

### Task 1: Define and test pure thinking-immersion behavior

**Files:**

- Create: `app/src/main/java/me/rerere/rikkahub/data/datastore/ThinkingImmersion.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt:DisplaySetting`
- Create: `app/src/test/java/me/rerere/rikkahub/data/datastore/ThinkingImmersionTest.kt`

**Interfaces:**

- Produces `DisplaySetting.thinkingImmersionPrompt(): String?`.
- Produces `DisplaySetting.formatThinkingForDisplay(raw: String): String`.
- Consumes `thinkingImmersionEnabled`, `thinkingUserAlias`, and `thinkingAssistantAlias` from `DisplaySetting`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `enabled immersion uses aliases and keeps technical phrases`() {
    val setting = DisplaySetting()
    assertTrue(setting.thinkingImmersionPrompt().orEmpty().contains("仅用简体中文"))
    assertEquals(
        "宝宝说完后，Daddy 应该回复。用户界面与 AI 模型不改。",
        setting.formatThinkingForDisplay("user说完后，assistant 应该回复。用户界面与 AI 模型不改。"),
    )
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```powershell
.\gradlew.bat -g D:\Daddy-GradleHome :app:testDebugUnitTest --tests me.rerere.rikkahub.data.datastore.ThinkingImmersionTest --no-daemon --console=plain
```

Expected: compilation failure because the new setting fields and helper functions do not exist.

- [ ] **Step 3: Add the minimal setting fields and helper**

```kotlin
val thinkingImmersionEnabled: Boolean = true,
val thinkingUserAlias: String = "宝宝",
val thinkingAssistantAlias: String = "Daddy",
```

Implement the helper with safe default aliases, a fixed Chinese instruction, English standalone-word replacements, and Chinese replacements that exclude technical suffixes such as `界面` and `模型`.

- [ ] **Step 4: Extend tests for disabled and custom settings**

```kotlin
@Test
fun `disabled immersion leaves prompt absent and text unchanged`() {
    val setting = DisplaySetting(thinkingImmersionEnabled = false)
    val raw = "user said hello to assistant"
    assertNull(setting.thinkingImmersionPrompt())
    assertEquals(raw, setting.formatThinkingForDisplay(raw))
}

@Test
fun `custom aliases replace generic roles`() {
    val setting = DisplaySetting(thinkingUserAlias = "帆帆", thinkingAssistantAlias = "小D")
    assertEquals("帆帆 和 小D", setting.formatThinkingForDisplay("User 和 assistant"))
}
```

- [ ] **Step 5: Run the focused test to verify it passes**

Run the command from Step 2. Expected: all `ThinkingImmersionTest` cases pass.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/datastore/ThinkingImmersion.kt app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt app/src/test/java/me/rerere/rikkahub/data/datastore/ThinkingImmersionTest.kt
git commit -m "feat: add immersive thinking aliases"
```

### Task 2: Wire prompt generation and reasoning display

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt:system prompt builder`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageReasoning.kt:ReasoningContent and title`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingDisplayMessagePage.kt:message-display card`
- Test: `app/src/test/java/me/rerere/rikkahub/data/datastore/ThinkingImmersionTest.kt`

**Interfaces:**

- Consumes `DisplaySetting.thinkingImmersionPrompt()` while building the stable system prefix.
- Consumes `DisplaySetting.formatThinkingForDisplay(raw)` only in Compose rendering.
- Produces a global settings UI with toggle, user alias, and Daddy alias fields.

- [ ] **Step 1: Write the failing request-guidance assertion**

```kotlin
@Test
fun `prompt explicitly asks for Chinese thinking and immersive aliases`() {
    val prompt = DisplaySetting().thinkingImmersionPrompt().orEmpty()
    assertTrue(prompt.contains("简体中文"))
    assertTrue(prompt.contains("宝宝"))
    assertTrue(prompt.contains("Daddy"))
}
```

- [ ] **Step 2: Run focused tests and verify the assertion fails before wiring**

Run the focused `ThinkingImmersionTest` command. Expected: failure until the helper is implemented.

- [ ] **Step 3: Insert the fixed request instruction**

Immediately after `effectiveSystemPrompt`, append one separated line containing `settings.displaySetting.thinkingImmersionPrompt()` when nonblank. Do not add it after the mood or life-line dynamic suffix.

- [ ] **Step 4: Apply display-only formatting to titles and content**

Create a local `displayReasoning` value in `ChatMessageReasoning`. Use it for `extractThinkingTitle()` and `MarkdownBlock`, but retain the original `reasoning` object for state, timing, selection, and persistence.

- [ ] **Step 5: Add the settings controls**

In the existing “消息显示” card, add “思考沉浸化” with a switch and two text fields. Explain that it affects only the thought panel and asks future thoughts to use Chinese; do not expose raw prompt syntax.

- [ ] **Step 6: Run focused tests and compile**

Run:

```powershell
.\gradlew.bat -g D:\Daddy-GradleHome :app:testDebugUnitTest --tests me.rerere.rikkahub.data.datastore.ThinkingImmersionTest --tests me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformerTest --no-daemon --console=plain
```

Expected: all focused tests pass and Kotlin compilation succeeds.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageReasoning.kt app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingDisplayMessagePage.kt app/src/test/java/me/rerere/rikkahub/data/datastore/ThinkingImmersionTest.kt
git commit -m "feat: apply immersive thinking display"
```

### Task 3: Verify companion packaging and provide time-rule handoff

**Files:**

- Verify only: `app/build.gradle.kts`
- Verify only: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt`
- Verify only: `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/TimeReminderTransformer.kt`

**Interfaces:**

- Produces a companion-flavor APK with package `me.rerere.orangechat.companion` and the established signing certificate.
- Produces user-facing instruction to replace the obsolete world-book tool name.

- [ ] **Step 1: Run selected regression tests**

```powershell
.\gradlew.bat -g D:\Daddy-GradleHome :app:testDebugUnitTest --tests me.rerere.rikkahub.data.datastore.ThinkingImmersionTest --tests me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformerTest --tests me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformerTest --no-daemon --console=plain
```

Expected: selected tests pass.

- [ ] **Step 2: Build the covering companion APK**

```powershell
.\gradlew.bat -g D:\Daddy-GradleHome :app:assembleCompanion --no-daemon --console=plain
```

Expected: `app/build/outputs/apk/companion/app-companion.apk` exists.

- [ ] **Step 3: Verify package, version, and signer**

```powershell
D:\Android\Sdk\build-tools\37.0.0\aapt.exe dump badging app\build\outputs\apk\companion\app-companion.apk
D:\Android\Sdk\build-tools\37.0.0\apksigner.bat verify --print-certs app\build\outputs\apk\companion\app-companion.apk
```

Expected: package is `me.rerere.orangechat.companion` and signer matches v207.

- [ ] **Step 4: Hand off the corrected world-book rule**

Tell the user to replace `get_current_beijing_time` with `get_time_info`, and not treat `time_reminder` as a live clock. Do not add a duplicate time tool.

- [ ] **Step 5: Commit final code state**

```powershell
git status --short
git add app/src/main/java/me/rerere/rikkahub/data/datastore/ThinkingImmersion.kt app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageReasoning.kt app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingDisplayMessagePage.kt app/src/test/java/me/rerere/rikkahub/data/datastore/ThinkingImmersionTest.kt
git commit -m "feat: complete thinking immersion"
```
