# Daddy Proactive & Provider Stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复主动消息重复/思考泄漏、MiniMax 思考分离、MiMo v2.5 参数与 SSE 错误兼容、Coil 单例崩溃和思考昵称混用，并产出可覆盖更新的 arm64 APK。

**Architecture:** 保留现有 OpenAI Provider、主动消息服务和思考显示结构，在边界上增加少量供应商兼容逻辑。模型返回先被规范化为 `UIMessagePart.Reasoning` 与 `UIMessagePart.Text`，主动消息完成后再统一做最终转换、正文提取和去重；Android 图片加载器改由 Application 级工厂提供。

**Tech Stack:** Kotlin, Android/Compose, Coil 3, kotlinx.serialization, OkHttp SSE, JUnit 4, Gradle Android Plugin

## Global Constraints

- 保持包名 `me.rerere.orangechat.companion` 和现有签名，支持覆盖更新。
- 不修改助手人格、聊天历史、主动消息频率、情绪引擎、晚安守夜规则或 Ombre 数据。
- 思考昵称替换只影响 `Reasoning` 显示，不修改模型原始返回、最终回复或长期记忆。
- 保留现有 15 分钟会话级主动消息正文去重窗口。
- 版本升级到 `versionCode = 209`、`versionName = "2.5.23"`。
- 所有行为修复先写失败测试，确认按预期失败后才改生产代码。

---

### Task 1: MiniMax 思考分离与累计流增量

**Files:**
- Create: `ai/src/test/java/me/rerere/ai/provider/providers/openai/MiniMaxCompatibilityTest.kt`
- Modify: `ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt`

**Interfaces:**
- Produces: `internal fun extractReasoningText(message: JsonObject): String?`
- Produces: `internal class MiniMaxStreamDeltaNormalizer` with `fun normalize(message: JsonObject): JsonObject`
- Consumes: existing `ChatCompletionsAPI.buildChatCompletionRequest` and `parseMessage`

- [ ] **Step 1: Write failing request and response tests**

Add tests that invoke the private request/message methods by reflection and exercise the real JSON boundary:

```kotlin
private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())

private fun buildRequest(
    baseUrl: String,
    model: Model,
    reasoningLevel: ReasoningLevel,
    temperature: Float? = 0.8f,
    topP: Float? = 0.9f,
    maxTokens: Int? = 4096,
): JsonObject {
    val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
        "buildChatCompletionRequest",
        List::class.java,
        TextGenerationParams::class.java,
        ProviderSetting.OpenAI::class.java,
        Boolean::class.javaPrimitiveType,
    ).apply { isAccessible = true }
    return method.invoke(
        api,
        listOf(UIMessage.user("你好")),
        TextGenerationParams(model, temperature, topP, maxTokens, reasoningLevel = reasoningLevel),
        ProviderSetting.OpenAI(baseUrl = baseUrl),
        true,
    ) as JsonObject
}

private fun parseMessage(input: JsonObject): UIMessage {
    val method = ChatCompletionsAPI::class.java.getDeclaredMethod("parseMessage", JsonObject::class.java)
        .apply { isAccessible = true }
    return method.invoke(api, input) as UIMessage
}

@Test
fun `MiniMax request asks API to split reasoning from content`() {
    val body = buildRequest(
        baseUrl = "https://api.minimaxi.com/v1",
        model = Model(
            modelId = "MiniMax-M2.7",
            abilities = listOf(ModelAbility.REASONING),
        ),
        reasoningLevel = ReasoningLevel.HIGH,
    )

    assertEquals(true, body["reasoning_split"]?.jsonPrimitive?.boolean)
}

@Test
fun `MiniMax reasoning_details becomes reasoning while content stays reply`() {
    val message = parseMessage(
        buildJsonObject {
            put("role", "assistant")
            put("content", "还有多远？买瓶冰水降降温。")
            putJsonArray("reasoning_details") {
                add(buildJsonObject {
                    put("type", "reasoning.text")
                    put("text", "小乖还在走路，我应该简短关心。")
                })
            }
        },
    )

    assertEquals("小乖还在走路，我应该简短关心。", message.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
    assertEquals("还有多远？买瓶冰水降降温。", message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
}
```

- [ ] **Step 2: Run the MiniMax tests and verify RED**

Run:

```powershell
.\gradlew.bat :ai:testDebugUnitTest --tests "me.rerere.ai.provider.providers.openai.MiniMaxCompatibilityTest"
```

Expected: FAIL because `reasoning_split` is absent and `reasoning_details` is not parsed.

- [ ] **Step 3: Add cumulative stream tests**

```kotlin
private fun delta(reasoning: String, content: String): JsonObject = buildJsonObject {
    put("role", "assistant")
    put("content", content)
    putJsonArray("reasoning_details") {
        add(buildJsonObject {
            put("type", "reasoning.text")
            put("text", reasoning)
        })
    }
}

@Test
fun `MiniMax cumulative reasoning and content emit only new suffix`() {
    val normalizer = MiniMaxStreamDeltaNormalizer()

    val first = normalizer.normalize(delta(reasoning = "先判断", content = "还"))
    val second = normalizer.normalize(delta(reasoning = "先判断，再回应", content = "还有多远？"))

    assertEquals("先判断", extractReasoningText(first))
    assertEquals("，再回应", extractReasoningText(second))
    assertEquals("还", first["content"]?.jsonPrimitive?.content)
    assertEquals("有多远？", second["content"]?.jsonPrimitive?.content)
}

@Test
fun `MiniMax incremental chunks remain incremental`() {
    val normalizer = MiniMaxStreamDeltaNormalizer()

    normalizer.normalize(delta(reasoning = "先判断", content = "还"))
    val next = normalizer.normalize(delta(reasoning = "再回应", content = "有多远？"))

    assertEquals("再回应", extractReasoningText(next))
    assertEquals("有多远？", next["content"]?.jsonPrimitive?.content)
}
```

- [ ] **Step 4: Run the new tests and verify RED**

Expected: compilation FAIL because `MiniMaxStreamDeltaNormalizer` and `extractReasoningText` do not exist.

- [ ] **Step 5: Implement MiniMax compatibility**

In `ChatCompletionsAPI.kt`:

```kotlin
internal fun extractReasoningText(message: JsonObject): String? =
    message["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: message["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: message["reasoning_details"]?.jsonArrayOrNull
            ?.mapNotNull { detail -> detail.jsonObjectOrNull?.get("text")?.jsonPrimitiveOrNull?.contentOrNull }
            ?.joinToString("")
            ?.takeIf { it.isNotBlank() }

internal class MiniMaxStreamDeltaNormalizer {
    private var reasoningSoFar = ""
    private var contentSoFar = ""

    private fun suffix(previous: String, candidate: String): Pair<String, String> =
        if (candidate.startsWith(previous)) {
            candidate.removePrefix(previous) to candidate
        } else {
            candidate to (previous + candidate)
        }

    fun normalize(message: JsonObject): JsonObject {
        val reasoning = extractReasoningText(message).orEmpty()
        val content = message["content"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
        val (reasoningDelta, nextReasoning) = suffix(reasoningSoFar, reasoning)
        val (contentDelta, nextContent) = suffix(contentSoFar, content)
        reasoningSoFar = nextReasoning
        contentSoFar = nextContent
        return buildJsonObject {
            message.forEach { (key, value) ->
                if (key !in setOf("reasoning_content", "reasoning", "reasoning_details", "content")) put(key, value)
            }
            if (reasoningDelta.isNotEmpty()) put("reasoning_content", reasoningDelta)
            if (contentDelta.isNotEmpty()) put("content", contentDelta)
        }
    }
}
```

Add `reasoning_split=true` for `api.minimaxi.com`, use the normalizer only for that host inside one stream, and make `parseMessage` call `extractReasoningText`.

- [ ] **Step 6: Run MiniMax tests and the existing OpenAI message suite**

```powershell
.\gradlew.bat :ai:testDebugUnitTest --tests "me.rerere.ai.provider.providers.openai.MiniMaxCompatibilityTest" --tests "me.rerere.ai.provider.providers.openai.ChatCompletionsAPIMessageTest"
```

Expected: PASS.

- [ ] **Step 7: Commit Task 1**

```powershell
git add ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt ai/src/test/java/me/rerere/ai/provider/providers/openai/MiniMaxCompatibilityTest.kt
git commit -m "fix: separate MiniMax reasoning output"
```

### Task 2: MiMo v2.5 请求参数与 SSE 错误还原

**Files:**
- Create: `ai/src/test/java/me/rerere/ai/provider/providers/openai/MiMoCompatibilityTest.kt`
- Modify: `ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt`

**Interfaces:**
- Produces: `internal fun parseOpenAIErrorPayload(body: String): JsonElement`
- Produces: `internal fun parseOpenAISseEvents(body: String): List<String>` extracted from the existing member parser
- Consumes: Task 1 request builder test helper pattern

- [ ] **Step 1: Write failing MiMo request tests**

```kotlin
private fun buildMiMoRequest(reasoningLevel: ReasoningLevel): JsonObject = buildRequest(
    baseUrl = "https://api.xiaomimimo.com/v1",
    model = Model(
        modelId = "mimo-v2.5",
        abilities = listOf(ModelAbility.REASONING),
    ),
    reasoningLevel = reasoningLevel,
)

@Test
fun `MiMo xhigh maps to enabled thinking without reasoning_effort`() {
    val body = buildMiMoRequest(reasoningLevel = ReasoningLevel.XHIGH)

    assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    assertFalse(body.containsKey("reasoning_effort"))
    assertFalse(body.containsKey("temperature"))
    assertFalse(body.containsKey("top_p"))
    assertEquals(4096, body["max_completion_tokens"]?.jsonPrimitive?.int)
    assertFalse(body.containsKey("max_tokens"))
}

@Test
fun `MiMo off maps to disabled thinking and keeps sampling parameters`() {
    val body = buildMiMoRequest(reasoningLevel = ReasoningLevel.OFF)

    assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    assertEquals(0.8f, body["temperature"]?.jsonPrimitive?.float)
    assertEquals(0.9f, body["top_p"]?.jsonPrimitive?.float)
}
```

- [ ] **Step 2: Run and verify RED**

Expected: FAIL because the current default OpenAI branch emits `reasoning_effort=xhigh`, `max_tokens`, and sampling values in thinking mode.

- [ ] **Step 3: Write failing SSE error test**

```kotlin
@Test
fun `SSE wrapped MiMo error exposes server message`() {
    val payload = parseOpenAIErrorPayload(
        "data:{\"error\":{\"code\":\"400\",\"message\":\"Invalid request parameters\",\"type\":\"Bad Request\"}}\n\n",
    )

    val error = payload.jsonObject["error"]!!.jsonObject
    assertEquals("400", error["code"]?.jsonPrimitive?.content)
    assertEquals("Invalid request parameters", error["message"]?.jsonPrimitive?.content)
}
```

- [ ] **Step 4: Run and verify RED**

Expected: compilation FAIL because `parseOpenAIErrorPayload` does not exist.

- [ ] **Step 5: Implement MiMo host mapping and error parser**

For `api.xiaomimimo.com`:

```kotlin
put("thinking", buildJsonObject {
    put("type", if (level.isEnabled) "enabled" else "disabled")
})
```

Use `max_completion_tokens`; suppress `temperature` and `top_p` only while thinking is enabled; never add `reasoning_effort` for this host.

Add an SSE-aware parser:

```kotlin
internal fun parseOpenAIErrorPayload(body: String): JsonElement {
    val trimmed = body.trim()
    if (!trimmed.startsWith("data:")) return json.parseToJsonElement(trimmed)
    val event = parseOpenAISseEvents(trimmed).firstOrNull { it.isNotBlank() && it != "[DONE]" }
        ?: error("Empty SSE error response")
    return json.parseToJsonElement(event)
}
```

Move the existing SSE event-splitting implementation out of the class as
`internal fun parseOpenAISseEvents(body: String): List<String>` and call it from both non-stream response handling and
`parseOpenAIErrorPayload`. Call the error helper from `onFailure` instead of directly calling
`Json.parseToJsonElement(bodyRaw)`.

- [ ] **Step 6: Run MiMo and OpenAI tests**

```powershell
.\gradlew.bat :ai:testDebugUnitTest --tests "me.rerere.ai.provider.providers.openai.MiMoCompatibilityTest" --tests "me.rerere.ai.provider.providers.openai.ChatCompletionsAPIMessageTest"
```

Expected: PASS and the error assertion contains the real server message.

- [ ] **Step 7: Commit Task 2**

```powershell
git add ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt ai/src/test/java/me/rerere/ai/provider/providers/openai/MiMoCompatibilityTest.kt
git commit -m "fix: align MiMo chat completion parameters"
```

### Task 3: 主动消息最终转换与正文级去重

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/service/ProactiveReplyFinalizer.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/data/service/ProactiveReplyFinalizerTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/service/ProactiveTriggerPolicyTest.kt`

**Interfaces:**
- Produces: `internal suspend fun finalizeProactiveReply(...): UIMessage`
- Produces: `internal fun proactiveVisibleReplyText(message: UIMessage): String`
- Consumes: existing `OutputMessageTransformer.onGenerationFinish`

- [ ] **Step 1: Write failing finalization test**

Use a transformer whose normal `transform` is a no-op but whose `onGenerationFinish` separates reasoning, mirroring `ThinkTagTransformer`’s contract:

```kotlin
@Test
fun `proactive finalization runs finish transformer and exposes only reply text`() = runTest {
    val raw = UIMessage.assistant("<think>不同的内部思考</think>还有多远？")
    val finished = finalizeProactiveReply(
        message = raw,
        transformers = listOf(ThinkTagTransformer),
        context = MockContext(),
        model = Model(),
        assistant = Assistant(),
        settings = Settings(),
    )

    assertEquals("不同的内部思考", finished.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
    assertEquals("还有多远？", proactiveVisibleReplyText(finished))
}
```

- [ ] **Step 2: Run and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.service.ProactiveReplyFinalizerTest"
```

Expected: compilation FAIL because the finalizer functions do not exist.

- [ ] **Step 3: Write duplicate regression test**

```kotlin
private fun message(reasoning: String, text: String): UIMessage = UIMessage(
    role = MessageRole.ASSISTANT,
    parts = listOf(
        UIMessagePart.Reasoning(reasoning),
        UIMessagePart.Text(text),
    ),
)

@Test
fun `different reasoning with same final text shares one proactive fingerprint`() {
    val first = message(reasoning = "思考甲", text = "买瓶冰水降降温。")
    val second = message(reasoning = "思考乙", text = "买瓶冰水降降温。")

    assertEquals(
        proactiveReplyFingerprint(proactiveVisibleReplyText(first)),
        proactiveReplyFingerprint(proactiveVisibleReplyText(second)),
    )
}
```

- [ ] **Step 4: Implement finalizer and switch service call site**

```kotlin
internal suspend fun finalizeProactiveReply(
    message: UIMessage,
    transformers: List<OutputMessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): UIMessage = listOf(message).onGenerationFinish(
    transformers = transformers,
    context = context,
    model = model,
    assistant = assistant,
    settings = settings,
).single().let { finished ->
    val now = Clock.System.now()
    finished.copy(parts = finished.parts.map { part ->
        if (part is UIMessagePart.Reasoning && part.finishedAt == null) part.copy(finishedAt = now) else part
    })
}

internal fun proactiveVisibleReplyText(message: UIMessage): String =
    message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.trim()
```

Replace the existing `transforms(...)` call in `generateWithTools` with `finalizeProactiveReply(...)`. Derive `[PASS]`, `[JUMP]`, notification text and duplicate fingerprint only from the finalized visible text.

- [ ] **Step 5: Run targeted proactive tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.service.ProactiveReplyFinalizerTest" --tests "me.rerere.rikkahub.data.service.ProactiveTriggerPolicyTest"
```

Expected: PASS.

- [ ] **Step 6: Commit Task 3**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/service/ProactiveReplyFinalizer.kt app/src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt app/src/test/java/me/rerere/rikkahub/data/service/ProactiveReplyFinalizerTest.kt app/src/test/java/me/rerere/rikkahub/data/service/ProactiveTriggerPolicyTest.kt
git commit -m "fix: finalize proactive replies before delivery"
```

### Task 4: 思考旧称统一

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/ThinkingImmersion.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingDisplayMessagePage.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/datastore/ThinkingImmersionTest.kt`

**Interfaces:**
- Adds: `DisplaySetting.thinkingUserAlternateNames: String = ""`
- Adds: `internal fun String.parseThinkingAliases(): List<String>`

- [ ] **Step 1: Write failing alias tests**

```kotlin
@Test
fun `configured old names and global nickname display as thinking alias`() {
    val setting = DisplaySetting(
        userNickname = "帆帆",
        thinkingImmersionEnabled = true,
        thinkingUserAlias = "小乖",
        thinkingUserAlternateNames = "应帆，Yingfan\n小帆",
    )

    assertEquals(
        "小乖说小乖今天很热，小乖应该先喝水。",
        setting.formatThinkingForDisplay("应帆说帆帆今天很热，Yingfan应该先喝水。"),
    )
}

@Test
fun `old name replacement treats regex punctuation as literal text`() {
    val setting = DisplaySetting(
        thinkingUserAlias = "小乖",
        thinkingUserAlternateNames = "帆(测试)",
    )

    assertEquals("小乖来了", setting.formatThinkingForDisplay("帆(测试)来了"))
}
```

- [ ] **Step 2: Run and verify RED**

Expected: compilation FAIL because `thinkingUserAlternateNames` does not exist.

- [ ] **Step 3: Implement literal, longest-first alias replacement**

```kotlin
internal fun String.parseThinkingAliases(): List<String> =
    split(',', '，', '\n')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sortedByDescending(String::length)
```

In `formatThinkingForDisplay`, combine `userNickname` and parsed alternate names, exclude the target alias itself, then replace each old name with `Regex.escape(oldName)`.

- [ ] **Step 4: Add the settings field**

Under “思考里怎样称呼你”, add an `OutlinedTextField` labeled “需要统一替换的旧称”, supporting comma/Chinese comma/newline input and explaining the example `应帆, 帆帆`.

- [ ] **Step 5: Run alias tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.datastore.ThinkingImmersionTest"
```

Expected: PASS.

- [ ] **Step 6: Commit Task 4**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt app/src/main/java/me/rerere/rikkahub/data/datastore/ThinkingImmersion.kt app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingDisplayMessagePage.kt app/src/test/java/me/rerere/rikkahub/data/datastore/ThinkingImmersionTest.kt
git commit -m "feat: normalize thinking aliases"
```

### Task 5: Coil Application 级单例

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`
- Create: `app/src/androidTest/java/me/rerere/rikkahub/RikkaHubImageLoaderTest.kt`

**Interfaces:**
- `RikkaHubApp` implements `coil3.SingletonImageLoader.Factory`
- Produces: `override fun newImageLoader(context: Context): ImageLoader`

- [ ] **Step 1: Write the Application factory contract test**

```kotlin
@RunWith(AndroidJUnit4::class)
class RikkaHubImageLoaderTest {
    @Test
    fun applicationOwnsTheCoilSingletonFactory() {
        val app = ApplicationProvider.getApplicationContext<RikkaHubApp>()
        assertTrue(app is SingletonImageLoader.Factory)
        assertSame(SingletonImageLoader.get(app), SingletonImageLoader.get(app))
    }
}
```

- [ ] **Step 2: Compile the instrumentation test and verify RED**

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

Expected: FAIL because `RikkaHubApp` is not a `SingletonImageLoader.Factory`.

- [ ] **Step 3: Move the factory to Application**

Change the declaration and add the factory method:

```kotlin
class RikkaHubApp : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(true)
            .components {
                add(OkHttpNetworkFetcherFactory(
                    callFactory = { get<OkHttpClient>() },
                    cacheStrategy = { CacheControlCacheStrategy() },
                ))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AnimatedImageDecoder.Factory())
                else add(GifDecoder.Factory())
                add(SvgDecoder.Factory(scaleToDensity = true))
            }
            .build()
}
```

Remove `setSingletonImageLoaderFactory` and the builder block from `RouteActivity`; retain `RikkahubTheme { AppRoutes() }`.

- [ ] **Step 4: Compile main and instrumentation sources**

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
```

Expected: PASS. If a connected phone is available, also run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.RikkaHubImageLoaderTest
```

- [ ] **Step 5: Commit Task 5**

```powershell
git add app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt app/src/main/java/me/rerere/rikkahub/RouteActivity.kt app/src/androidTest/java/me/rerere/rikkahub/RikkaHubImageLoaderTest.kt
git commit -m "fix: initialize Coil from the application"
```

### Task 6: Version, regression verification and coverage-update APK

**Files:**
- Modify: `app/build.gradle.kts`
- Output: `D:/Daddy-安装包/Daddy-v2.5.23-主动消息模型稳定-v209-覆盖更新-arm64.apk`

**Interfaces:**
- Consumes all fixes from Tasks 1-5.
- Produces one signed arm64 APK with package `me.rerere.orangechat.companion`.

- [ ] **Step 1: Bump version**

```kotlin
versionCode = 209
versionName = "2.5.23"
```

- [ ] **Step 2: Run targeted unit suites**

```powershell
.\gradlew.bat :ai:testDebugUnitTest --tests "me.rerere.ai.provider.providers.openai.MiniMaxCompatibilityTest" --tests "me.rerere.ai.provider.providers.openai.MiMoCompatibilityTest" --tests "me.rerere.ai.provider.providers.openai.ChatCompletionsAPIMessageTest"
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.service.ProactiveReplyFinalizerTest" --tests "me.rerere.rikkahub.data.service.ProactiveTriggerPolicyTest" --tests "me.rerere.rikkahub.data.datastore.ThinkingImmersionTest"
```

Expected: all targeted tests PASS.

- [ ] **Step 3: Compile all affected Android sources from a clean task graph**

```powershell
.\gradlew.bat :ai:compileDebugKotlin :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --rerun-tasks
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Build arm64 APK**

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: arm64 debug APK is generated under `app/build/outputs/apk/debug/`.

- [ ] **Step 5: Verify package, version and signer**

Use Android SDK build tools to assert:

```text
package: me.rerere.orangechat.companion
versionCode: 209
versionName: 2.5.23
signer: identical to Daddy-v2.5.20-黑红档案小组件-主动tick降噪-v206-覆盖更新-arm64.apk and the v208 APK
ABI: arm64-v8a
```

- [ ] **Step 6: Copy the verified APK**

Copy the exact arm64 artifact to:

```text
D:/Daddy-安装包/Daddy-v2.5.23-主动消息模型稳定-v209-覆盖更新-arm64.apk
```

- [ ] **Step 7: Commit Task 6**

```powershell
git add app/build.gradle.kts
git commit -m "build: prepare Daddy v209"
```

- [ ] **Step 8: Final manual acceptance checklist**

On the phone:

1. Cover-install v209 over the current Daddy and confirm all chats/settings remain.
2. Trigger one normal MiniMax M2.7 reply; reasoning is collapsible and final text appears once.
3. Trigger proactive mode twice close together with the same final reply; only one bubble/notification remains.
4. Switch to MiMo v2.5 with XHIGH selected; it replies without JSON token error.
5. Enter `应帆` in “需要统一替换的旧称”; reasoning displays `小乖` for `用户 / user / 应帆`.
6. Open floating bubble, chat images and Daddy house in either order; no Coil singleton crash occurs.

Do not push to GitHub until the user explicitly authorizes the final commits.
