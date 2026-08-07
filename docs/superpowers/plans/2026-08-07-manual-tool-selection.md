# Manual Tool Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give each assistant a persisted manual MCP/plugin allow-list that can replace keyword-based tool throttling for normal chat sends.

**Architecture:** Extend `Assistant` with independent manual selection state. `SmartToolRouter` will select the MCP server IDs and plugin IDs for full, smart, or manual routing; `ChatService` will build only that surface. `FilesPicker` will expose mutually exclusive routing switches and a bottom-sheet selector, while its expanded panel gains a downward-dismiss gesture.

**Tech Stack:** Kotlin, kotlinx.serialization, Jetpack Compose Material 3, JUnit 4, Android Gradle Plugin.

## Global Constraints

- Manual decisions must be local and add zero model calls.
- Manual mode must not retain Ombre-Brain or any other MCP implicitly.
- Existing context size, memory configuration, lorebooks, skills, and MCP connection configuration must remain unchanged.
- Remove the one-send all-tools override rather than hiding it.
- Build the arm64 companion APK with `:app:assembleCompanion`.

---

### Task 1: Define and prove routing behavior

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/data/ai/tools/SmartToolRouterTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/SmartToolRouter.kt`

**Interfaces:**
- Consumes: `Assistant.mcpServers`, manual MCP/plugin ID sets supplied by `ChatService`.
- Produces: `SmartToolRouter.select(message, smartThrottlingEnabled, manualSelectionEnabled, normalMcpServerIds, manualMcpServerIds, manualPluginIds): SmartToolSelection`.
- `SmartToolSelection` exposes `allowedMcpServerIds`, `allowedPluginIds`, and `allowsMcpTool(serverId, rawToolName)`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `manual mode exposes only the chosen mcp server and plugin`() {
    val luto = Uuid.parse("00000000-0000-0000-0000-000000000001")
    val ombre = Uuid.parse("00000000-0000-0000-0000-000000000002")
    val selection = SmartToolRouter.select(
        message = "睡眠怎么样",
        smartThrottlingEnabled = true,
        manualSelectionEnabled = true,
        normalMcpServerIds = setOf(ombre),
        manualMcpServerIds = setOf(luto),
        manualPluginIds = setOf("com.daddy.luto"),
    )

    assertTrue(selection.allowsMcpTool(luto, "forum_search"))
    assertFalse(selection.allowsMcpTool(ombre, "breath"))
    assertTrue(selection.allowsPlugin("com.daddy.luto"))
    assertFalse(selection.allowsPlugin("com.daddy.weather"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.ai.tools.SmartToolRouterTest --offline --no-daemon --console=plain`

Expected: the test fails to compile because the manual routing arguments and server-aware predicate do not yet exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
val allowedMcpServerIds = if (manualSelectionEnabled) manualMcpServerIds else normalMcpServerIds

fun allowsMcpTool(serverId: Uuid, rawToolName: String): Boolean {
    if (serverId !in allowedMcpServerIds) return false
    return includeAllTools || manualSelectionEnabled || rawToolName.lowercase() in coreMemoryMcpTools
}
```

Keep the existing scene checks for smart mode and return only `manualPluginIds` for manual mode.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.ai.tools.SmartToolRouterTest --offline --no-daemon --console=plain`

Expected: all router tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/me/rerere/rikkahub/data/ai/tools/SmartToolRouterTest.kt app/src/main/java/me/rerere/rikkahub/data/ai/tools/SmartToolRouter.kt
git commit -m "feat: add manual tool routing"
```

### Task 2: Persist selections and apply them to normal chat sends

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`

**Interfaces:**
- Consumes: persisted `manualToolSelectionEnabled`, `manualToolMcpServerIds`, and `manualToolPluginIds`.
- Produces: a tools list and plugin prompt injection list matching the router selection for each ordinary user send.

- [ ] **Step 1: Write the failing test**

Extend the Task 1 test with a full-mode assertion that an MCP ID outside `normalMcpServerIds` is rejected. The production change that must be caught is accidentally bypassing the assistant's selected server list.

```kotlin
assertFalse(
    fullSelection.allowsMcpTool(otherServer, "any_tool"),
)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.ai.tools.SmartToolRouterTest --offline --no-daemon --console=plain`

Expected: FAIL because full mode still treats every MCP server as available.

- [ ] **Step 3: Write minimal implementation**

```kotlin
@Serializable
data class Assistant(
    // existing properties
    val manualToolSelectionEnabled: Boolean = false,
    val manualToolMcpServerIds: Set<Uuid> = emptySet(),
    val manualToolPluginIds: Set<String> = emptySet(),
)
```

Pass `SmartToolSelection.allowedMcpServerIds` to a `McpManager.getAllAvailableTools(serverIds: Set<Uuid>)` overload, use `allowsMcpTool(serverId, tool.name)`, and use `allowedPluginIds` for both plugin tools and plugin prompt injections. Delete the force-full state and its send parameter.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.ai.tools.SmartToolRouterTest --offline --no-daemon --console=plain`

Expected: all router tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt
git commit -m "feat: apply manual tool selections to chat"
```

### Task 3: Add the selection interface and dismiss gesture

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ManualToolPicker.kt`
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/components/ai/ManualToolPickerInstrumentedTest.kt`

**Interfaces:**
- Consumes: `Assistant`, configured MCP servers from `LocalMcpServers`, and `PluginToolProvider.getToolStats()`.
- Produces: a mode switch, manual MCP/plugin toggles persisted through `onUpdateAssistant`, and an inline panel that invokes `onDismiss` after a 72dp downward drag.

- [ ] **Step 1: Write the failing test**

```kotlin
composeTestRule.setContent {
    ManualToolPicker(
        assistant = Assistant(manualToolSelectionEnabled = true),
        servers = listOf(lutoServer),
        plugins = listOf(PluginToolDetail("com.daddy.luto", "Luto 论坛", 2, listOf("search", "open"))),
        onUpdateAssistant = { updated = it },
        onDismiss = {},
    )
}

composeTestRule.onNodeWithText("Luto 论坛").performClick()
assertTrue("com.daddy.luto" in updated.manualToolPluginIds)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:compileDebugAndroidTestKotlin --offline --no-daemon --console=plain`

Expected: FAIL because `ManualToolPicker` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create a scrollable Material 3 bottom sheet with separate MCP and tool-plugin sections. In `FilesPicker`, make smart and manual switches mutually exclusive, remove the one-send full-tools row, and add a drag handle with a 72dp downward-dismiss threshold.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:compileDebugAndroidTestKotlin --offline --no-daemon --console=plain`

Expected: instrumentation test sources compile successfully.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt app/src/main/java/me/rerere/rikkahub/ui/components/ai/ManualToolPicker.kt app/src/androidTest/java/me/rerere/rikkahub/ui/components/ai/ManualToolPickerInstrumentedTest.kt
git commit -m "feat: add manual tool selection panel"
```

### Task 4: Ship the companion build

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `docs/superpowers/specs/2026-08-07-manual-tool-selection-design.md`
- Modify: `docs/superpowers/plans/2026-08-07-manual-tool-selection.md`

- [ ] **Step 1: Bump build identity**

Set `versionCode = 190` and `versionName = "2.5.4"`.

- [ ] **Step 2: Build and verify**

Run: `./gradlew :app:assembleCompanion --offline --no-daemon --console=plain`

Expected: BUILD SUCCESSFUL and `app-companion-arm64-v8a-debug.apk` is produced.

- [ ] **Step 3: Installable artifact check**

Run: `aapt dump badging app/build/outputs/apk/companion/arm64-v8a/debug/app-companion-arm64-v8a-debug.apk`

Expected: package `me.rerere.orangechat.companion`, versionCode `190`, versionName `2.5.4-companion`, and label `Daddy`.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts docs/superpowers/specs/2026-08-07-manual-tool-selection-design.md docs/superpowers/plans/2026-08-07-manual-tool-selection.md
git commit -m "feat: add manual tool selection controls"
```
