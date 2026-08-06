# Smart Tool Throttling Implementation Plan

> **For Codex:** Execute this plan inline in the current workspace. The task is intentionally kept in one implementation stream because the chat-generation call chain and its Compose controls must evolve together.

**Goal:** Add an assistant-level “Smart Tool Throttling” setting that reduces manual-chat input tokens by sending only scene-relevant MCP and plugin tools, while preserving all existing conversation context and supporting a one-send full-tools override.

**Architecture:** Keep local, system, workspace, skill, and memory plumbing unchanged. Add a pure `SmartToolRouter` which derives scenes from the user’s just-sent text, then use it only in the normal manual `ChatService` generation path to filter MCP tools and plugin tools/prompts. Persist the master switch in `Assistant`; expose the switch and the one-send override in the chat attachment panel.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx serialization, JUnit, Gradle Android app module.

---

### Task 1: Establish router behavior with unit tests

**Files:**
- Create: `app/src/test/java/me/rerere/rikkahub/data/ai/tools/SmartToolRouterTest.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/SmartToolRouter.kt`

**Step 1: Write failing tests**

Cover these cases:
- Disabled throttling and a one-send override both allow the legacy full tool surface.
- A plain chat message selects no optional plugin scene but retains core Ombre-style memory MCP names.
- Health wording selects health tool names.
- Route wording selects map/navigation tool names.
- Reading wording selects only the co-reading plugin ID.
- Multiple scenes are unioned and unknown plugin IDs are excluded in throttled mode.

**Step 2: Run the focused test and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*SmartToolRouterTest"`

**Step 3: Implement the smallest pure router**

Create a self-contained router with scene keyword matching, safe core MCP matching, and explicit plugin ID mapping. Do not make API calls or modify application state.

**Step 4: Run the focused test and confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*SmartToolRouterTest"`

### Task 2: Make plugin tool and prompt injection selection possible

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/plugin/provider/PluginToolProvider.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/tools/SmartToolRouterTest.kt`

**Step 1: Extend the provider API compatibly**

Allow callers to supply an optional set of plugin IDs. `null` must retain exact current behavior; a supplied set must filter both tool definitions and prompt injections from the same plugins.

**Step 2: Verify existing callers remain source-compatible**

Use default parameters or overloads so workflow and other legacy callers still receive all plugins.

### Task 3: Persist the assistant master switch

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`

**Step 1: Add the persisted Boolean**

Add `smartToolThrottlingEnabled: Boolean = false` near assistant tool configuration, ensuring old backups/configurations remain compatible and legacy behavior stays full-tools by default.

**Step 2: Verify serialization/build compilation**

Run the focused JVM test suite from Task 1 after compiling the model change.

### Task 4: Filter only normal manual generation requests

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`

**Step 1: Thread a one-send `forceFullTools` flag through manual send only**

Give `sendMessage` and its normal generation continuation a defaulted flag. Preserve it for tool-result continuation within that one generation, but do not change regenerate, workflow, proactive, aggressive, or night-watch code paths.

**Step 2: Apply the router in `ChatService`**

When the assistant switch is on and the one-send flag is off:
- keep existing local/system/workspace/skill tools;
- filter MCP tools by raw tool name through `SmartToolRouter`;
- filter plugin tools and plugin prompt injections by selected plugin IDs.

When either is off/overridden, preserve the exact existing full list.

**Step 3: Compile the app module**

Run: `./gradlew :app:compileCompanionDebugKotlin`

### Task 5: Add user controls to the attachment panel

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`

**Step 1: Add a persisted “智能工具节流” switch**

Place it in the normal chat attachment/tool panel and save it by copying the current assistant. Its disabled state explicitly restores full tool injection.

**Step 2: Add a temporary “仅本条全部工具” control**

Keep it as compose state tied to the current conversation. On send, pass the flag then reset it immediately; never persist it to the assistant or future messages.

**Step 3: Make the labels clear**

Explain concisely that context-message count is untouched and only MCP/plugin tools are selected for this send.

### Task 6: Verify, package, and hand off

**Files:**
- Modify: `app/build.gradle.kts` (version bump only if current companion packaging convention requires it)

**Step 1: Run focused tests and compile**

Run:
```
./gradlew :app:testDebugUnitTest --tests "*SmartToolRouterTest"
./gradlew :app:compileCompanionDebugKotlin
```

**Step 2: Build the arm64 companion APK**

Run the existing companion arm64 Gradle task and inspect the APK output exists.

**Step 3: Copy the resulting APK to `D:\Daddy-安装包`**

Use the current version name in a descriptive filename. This requires user-approved elevated filesystem access if the folder is outside the workspace.

**Step 4: Commit and push**

Inspect `git diff`, commit only the throttling implementation and plan, then push `master` to the existing `fork` remote. Report the exact APK path and quick phone validation steps.
