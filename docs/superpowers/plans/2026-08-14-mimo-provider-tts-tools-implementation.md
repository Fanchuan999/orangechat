# MiMo Provider, Optional TTS Style, and Tool Deconflict Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let Daddy use MiMo v2.5 as a built-in chat provider, keep MiniMax untouched while exposing a conservative optional MiMo TTS style instruction, and prevent duplicate tool names from breaking requests.

**Architecture:** MiMo chat is another editable `ProviderSetting.OpenAI` preset with stable IDs and model capabilities taken from the existing `ModelRegistry`. MiMo TTS stores an optional instruction alongside existing settings and prepends it only to that provider’s synthesis text, preserving old backup decoding. A single pure final-name filter is shared by interactive chat, proactive messages, workflow tool surfaces, and idle exploration.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, JUnit 4, Android Gradle.

## Global Constraints

- Keep the user’s existing MiniMax TTS provider and selected free voice unchanged.
- Do not alter cache-friendly context truncation, Ombre/Termux backup, proactive timing, night watch, widget behavior, or Daddy’s companion modules.
- MiMo chat defaults are `https://api.xiaomimimo.com/v1`, `/chat/completions`, `mimo-v2.5`, and `mimo-v2.5-pro`; every field remains user-editable.
- `mimo-v2.5` is text + image capable; `mimo-v2.5-pro` is text-only by default; both expose tool/reasoning capability.
- Tool collisions retain the first emitted tool and quietly discard later same-name tools; names are never rewritten.
- Work in the current branch because current v206 implementation changes are intentionally uncommitted; creating a fresh worktree would omit them.

---

### Task 1: Register MiMo chat with correct model capabilities

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/DefaultProviders.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/datastore/DefaultProvidersTest.kt`
- Modify: `ai/src/test/java/me/rerere/ai/ModelRegistryTest.kt`

**Interfaces:**
- Consumes: `ProviderSetting.OpenAI`, `Model`, `Modality`, `ModelAbility`, and existing `ModelRegistry` data lookups.
- Produces: one enabled built-in provider named `MiMo` with two selectable models.

- [ ] **Step 1: Write failing provider and registry tests**

```kotlin
@Test
fun `default providers should include MiMo with expected chat models`() {
    val provider = DEFAULT_PROVIDERS
        .filterIsInstance<ProviderSetting.OpenAI>()
        .single { it.name == "MiMo" }

    assertTrue(provider.builtIn)
    assertEquals("https://api.xiaomimimo.com/v1", provider.baseUrl)
    assertEquals("/chat/completions", provider.chatCompletionsPath)
    assertEquals(listOf("mimo-v2.5", "mimo-v2.5-pro"), provider.models.map { it.modelId })
}

@Test
fun `MiMo v2_5 modalities and abilities are available`() {
    assertEquals(listOf(Modality.TEXT, Modality.IMAGE), ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2.5"))
    assertEquals(listOf(Modality.TEXT), ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2.5-pro"))
    assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), ModelRegistry.MODEL_ABILITIES.getData("mimo-v2.5"))
}
```

- [ ] **Step 2: Run tests to verify they fail because the provider is absent**

Run: `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.datastore.DefaultProvidersTest :ai:test --tests me.rerere.ai.ModelRegistryTest`

Expected: provider test fails because no provider named `MiMo` exists; the registry expectation documents the existing capability contract.

- [ ] **Step 3: Add the minimal built-in MiMo provider**

```kotlin
ProviderSetting.OpenAI(
    id = Uuid.parse("<stable MiMo provider UUID>"),
    name = "MiMo",
    baseUrl = "https://api.xiaomimimo.com/v1",
    apiKey = "",
    enabled = true,
    builtIn = true,
    models = listOf(
        Model(
            id = Uuid.parse("<stable MiMo v2.5 model UUID>"),
            modelId = "mimo-v2.5",
            displayName = "MiMo v2.5",
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            outputModalities = listOf(Modality.TEXT),
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
        ),
        Model(
            id = Uuid.parse("<stable MiMo v2.5 Pro model UUID>"),
            modelId = "mimo-v2.5-pro",
            displayName = "MiMo v2.5 Pro",
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.TEXT),
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
        ),
    ),
)
```

- [ ] **Step 4: Run focused tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.datastore.DefaultProvidersTest :ai:test --tests me.rerere.ai.ModelRegistryTest`

Expected: both test classes pass.

- [ ] **Step 5: Commit the provider slice**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/datastore/DefaultProviders.kt app/src/test/java/me/rerere/rikkahub/data/datastore/DefaultProvidersTest.kt ai/src/test/java/me/rerere/ai/ModelRegistryTest.kt
git commit -m "feat: add MiMo chat provider"
```

### Task 2: Add optional MiMo TTS style instruction without changing MiniMax

**Files:**
- Modify: `speech/src/main/java/me/rerere/tts/provider/TTSProviderSetting.kt`
- Modify: `speech/src/main/java/me/rerere/tts/provider/providers/MiMoTTSProvider.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/TTSProviderConfigure.kt`
- Modify: `speech/src/test/java/me/rerere/tts/provider/TTSProviderSettingMiMoTest.kt`
- Modify: `speech/src/test/java/me/rerere/tts/provider/providers/MiMoTTSProviderTest.kt`

**Interfaces:**
- Consumes: `TTSProviderSetting.MiMo` and `TTSRequest.text`.
- Produces: `styleInstruction: String = ""`; when blank, outgoing synthesis text remains exactly the user-visible reply.

- [ ] **Step 1: Write failing default and composition tests**

```kotlin
@Test
fun mimo_style_instruction_defaults_to_blank_for_old_backups() {
    assertEquals("", TTSProviderSetting.MiMo().styleInstruction)
}

@Test
fun compose_mimo_speech_text_keeps_reply_when_style_is_blank() {
    assertEquals("晚安。", composeMiMoSpeechText("", "晚安。"))
}

@Test
fun compose_mimo_speech_text_prefixes_non_blank_style_once() {
    assertEquals("语气提示：温柔、低声。\n\n晚安。", composeMiMoSpeechText("语气提示：温柔、低声。", "晚安。"))
}
```

- [ ] **Step 2: Run tests to verify they fail because the field/helper is absent**

Run: `./gradlew :speech:test --tests me.rerere.tts.provider.TTSProviderSettingMiMoTest :speech:test --tests me.rerere.tts.provider.providers.MiMoTTSProviderTest`

Expected: compilation fails with unresolved `styleInstruction` and `composeMiMoSpeechText`.

- [ ] **Step 3: Add the backward-compatible field, helper, request use, and settings control**

```kotlin
data class MiMo(
    // existing fields unchanged
    val voice: String = "mimo_default",
    val styleInstruction: String = "",
)

internal fun composeMiMoSpeechText(styleInstruction: String, reply: String): String =
    styleInstruction.trim().takeIf { it.isNotEmpty() }
        ?.let { "$it\n\n$reply" }
        ?: reply
```

Use `composeMiMoSpeechText(providerSetting.styleInstruction, request.text)` as the MiMo chat-completions message content. Add a multiline editable MiMo-only field labelled `语气提示（可选）`, with copy explaining it is sent only when MiMo TTS is selected. Do not edit MiniMax configuration or defaults.

- [ ] **Step 4: Run MiMo TTS tests to verify they pass**

Run: `./gradlew :speech:test --tests me.rerere.tts.provider.TTSProviderSettingMiMoTest :speech:test --tests me.rerere.tts.provider.providers.MiMoTTSProviderTest`

Expected: both test classes pass; no MiniMax test or source file changes.

- [ ] **Step 5: Commit the optional TTS slice**

```bash
git add speech/src/main/java/me/rerere/tts/provider/TTSProviderSetting.kt speech/src/main/java/me/rerere/tts/provider/providers/MiMoTTSProvider.kt app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/TTSProviderConfigure.kt speech/src/test/java/me/rerere/tts/provider/TTSProviderSettingMiMoTest.kt speech/src/test/java/me/rerere/tts/provider/providers/MiMoTTSProviderTest.kt
git commit -m "feat: add optional MiMo TTS style instruction"
```

### Task 3: Enforce unique final tool names across every execution path

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolNaming.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolSurfaceBuilder.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/data/ai/tools/ToolNamingTest.kt`

**Interfaces:**
- Consumes: `List<Tool>` after all enabled sources have produced model-visible names.
- Produces: `deduplicateToolNames(tools: List<Tool>): List<Tool>` retaining first occurrence order.

- [ ] **Step 1: Write the failing pure-name collision test**

```kotlin
@Test
fun deduplicate_tool_names_keeps_the_first_tool_and_original_order() {
    val first = tool("search_web", "first")
    val duplicate = tool("search_web", "later")
    val last = tool("read_webpage", "last")

    val result = deduplicateToolNames(listOf(first, duplicate, last))

    assertEquals(listOf("search_web", "read_webpage"), result.map(Tool::name))
    assertEquals("first", result.first().description)
}
```

The test fixture creates real `Tool` instances with no-op suspend `execute` lambdas; it must not mock tool construction.

- [ ] **Step 2: Run test to verify it fails because the helper is absent**

Run: `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.ai.tools.ToolNamingTest`

Expected: compilation fails with unresolved `deduplicateToolNames`.

- [ ] **Step 3: Implement and apply the final guard**

```kotlin
internal fun deduplicateToolNames(tools: List<Tool>): List<Tool> {
    val names = mutableSetOf<String>()
    return tools.filter { tool -> names.add(tool.name) }
}
```

Place the filter after the complete list is built in `ToolSurfaceBuilder`, interactive `ChatService`, proactive `buildTools`, and proactive `buildIdleExploreTools`. Log a warning for each discarded name without logging tool arguments, API keys, or message content. Keep the existing MCP/plugin namespacing unchanged.

- [ ] **Step 4: Run the tool test and targeted app tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.ai.tools.ToolNamingTest :app:testDebugUnitTest --tests me.rerere.rikkahub.data.datastore.DefaultProvidersTest`

Expected: both classes pass.

- [ ] **Step 5: Commit the deconflict slice**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolNaming.kt app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolSurfaceBuilder.kt app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt app/src/test/java/me/rerere/rikkahub/data/ai/tools/ToolNamingTest.kt
git commit -m "fix: guard duplicate tool names"
```

### Task 4: Verify an installable Daddy build

**Files:**
- No production-file changes required.

**Interfaces:**
- Consumes: Tasks 1–3 and existing v206 project configuration.
- Produces: a debug arm64 APK whose package/signing identity remain compatible with the user’s v206 installation.

- [ ] **Step 1: Run focused JVM test suite**

Run: `./gradlew :ai:test :speech:test :app:testDebugUnitTest`

Expected: all selected test suites pass; any pre-existing unrelated failure is reported separately and not attributed to this feature.

- [ ] **Step 2: Build the debug APK**

Run: `./gradlew :app:assembleDebug`

Expected: `app/build/outputs/apk/debug/` contains a new APK.

- [ ] **Step 3: Inspect packaging identity and hand off the APK**

Run: `aapt dump badging app/build/outputs/apk/debug/app-debug.apk`

Expected: package is `com.daddy.chat` and signing/build setup is compatible with the v206 release workflow; copy the artifact to `D:\Daddy-安装包` using the established naming convention only after confirming the build result.

- [ ] **Step 4: Commit only the feature source and tests**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/datastore/DefaultProviders.kt app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolNaming.kt app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolSurfaceBuilder.kt app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/TTSProviderConfigure.kt ai/src/test/java/me/rerere/ai/ModelRegistryTest.kt app/src/test/java/me/rerere/rikkahub/data/datastore/DefaultProvidersTest.kt app/src/test/java/me/rerere/rikkahub/data/ai/tools/ToolNamingTest.kt speech/src/main/java/me/rerere/tts/provider/TTSProviderSetting.kt speech/src/main/java/me/rerere/tts/provider/providers/MiMoTTSProvider.kt speech/src/test/java/me/rerere/tts/provider/TTSProviderSettingMiMoTest.kt speech/src/test/java/me/rerere/tts/provider/providers/MiMoTTSProviderTest.kt docs/superpowers/plans/2026-08-14-mimo-provider-tts-tools-implementation.md
git commit -m "feat: sync MiMo provider and tool safety"
```

## Self-Review

- Spec coverage: Task 1 covers the built-in editable MiMo chat provider and its registry-level modalities/abilities. Task 2 adds a conservative MiMo-only style instruction and preserves old TTS settings and MiniMax. Task 3 makes every live chat/proactive/workflow tool list unique after all sources. Task 4 verifies tests, build, and package identity.
- Placeholder scan: all implementation names, default values, source paths, test classes, and commands are specified. Stable UUID literals are generated during Task 1 and committed with the provider; they are not runtime placeholders.
- Type consistency: Task 2 defines `styleInstruction: String` and `composeMiMoSpeechText(styleInstruction: String, reply: String): String`; Task 3 defines `deduplicateToolNames(tools: List<Tool>): List<Tool` and every application point consumes/returns `List<Tool>`.
