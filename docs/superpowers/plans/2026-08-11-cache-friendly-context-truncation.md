# 缓存友好上下文截断：实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为每个 Daddy 助手提供一个仅在上下文上限不低于 200 条时可用的手动缓存友好截断开关，以 25% 阶梯裁剪保持请求前缀稳定。

**Architecture:** 在 `Assistant` 中保存默认关闭的布尔设置；把是否可用和阶梯裁剪规则收敛在 `ai` 模块的消息裁剪函数；所有会将历史发给模型的入口统一使用该函数。Compose 设置页只负责展示和保存开关，不在 UI 中重复裁剪算法。

**Tech Stack:** Kotlin、kotlinx.serialization、Jetpack Compose、JUnit、Gradle Android 项目。

## Global Constraints

- `contextMessageSize == 0` 仍表示无限制，绝不截断。
- 缓存友好模式只有 `contextMessageSize >= 200` 时才生效。
- 每个阶梯裁剪的步长为上限的 25%，裁剪后保留 75% 到 100% 的最近消息。
- 关闭开关必须保留旧的严格滑动窗口语义。
- 工具请求、工具结果与其发起用户消息不可拆开；安全边界可以略微超过名义上限。
- 不删除、摘要、改写聊天记录、Ombre、Supabase 或任何已有记忆。
- 不调整系统提示、工具提示、动态记忆召回或九维欲望的顺序。

---

### Task 1: 定义并测试缓存友好裁剪规则

**Files:**
- Modify: `ai/src/main/java/me/rerere/ai/ui/Message.kt:279-318`
- Modify: `ai/src/test/java/me/rerere/ai/ui/MessageTest.kt:17-127`

**Interfaces:**
- Produces: `fun isCacheFriendlyContextAvailable(limit: Int): Boolean`
- Produces: `fun List<UIMessage>.limitContext(limit: Int, cacheFriendly: Boolean = false): List<UIMessage>`
- Consumes: existing `UIMessage.getTools()`, `UIMessagePart.Tool.isExecuted`, and safe tool-boundary behavior.

- [ ] **Step 1: 先写会失败的阶梯裁剪测试**

```kotlin
@Test
fun `cache friendly 400 limit keeps 301 through 400 messages per step`() {
    val messages = createTestMessages(501)
    assertEquals(301, messages.take(401).limitContext(400, cacheFriendly = true).size)
    assertEquals(400, messages.take(500).limitContext(400, cacheFriendly = true).size)
    assertEquals(301, messages.limitContext(400, cacheFriendly = true).size)
}

@Test
fun `cache friendly mode is unavailable below 200`() {
    val messages = createTestMessages(201)
    assertFalse(isCacheFriendlyContextAvailable(199))
    assertEquals(100, messages.limitContext(100, cacheFriendly = true).size)
}
```

- [ ] **Step 2: 运行测试并确认因为缺少新接口或阶梯行为而失败**

Run: `$env:GRADLE_USER_HOME='D:\gradle-ascii'; .\gradlew :ai:testDebugUnitTest --tests me.rerere.ai.ui.MessageTest`

Expected: FAIL，失败点为 `limitContext` 尚不接受 `cacheFriendly` 参数或阶梯大小不符合预期。

- [ ] **Step 3: 实现最小可用的 25% 阶梯函数**

```kotlin
private const val CACHE_FRIENDLY_MIN_LIMIT = 200
private const val CACHE_FRIENDLY_TRIM_RATIO = 0.25f

fun isCacheFriendlyContextAvailable(limit: Int) = limit >= CACHE_FRIENDLY_MIN_LIMIT

fun List<UIMessage>.limitContext(limit: Int, cacheFriendly: Boolean = false): List<UIMessage> {
    if (limit <= 0 || size <= limit) return this
    val startIndex = if (cacheFriendly && isCacheFriendlyContextAvailable(limit)) {
        val stride = (limit * CACHE_FRIENDLY_TRIM_RATIO).roundToInt().coerceAtLeast(1)
        val overflow = size - limit
        ((overflow + stride - 1) / stride * stride).coerceAtMost(size - 1)
    } else {
        size - limit
    }
    return subList(alignContextStart(startIndex), size)
}
```

Move the existing tool-boundary loop into `alignContextStart(startIndex)` without changing its
semantics.

- [ ] **Step 4: 补齐边界与工具链测试**

Add tests for 200 (`201 -> 151`, `250 -> 200`, `251 -> 151`), 500 (`501 -> 376`,
`625 -> 500`, `626 -> 376`), disabled strict behavior, unlimited behavior, and an executed
tool-result boundary in cache-friendly mode.

- [ ] **Step 5: 运行 `ai` 模块测试并确认全部通过**

Run: `$env:GRADLE_USER_HOME='D:\gradle-ascii'; .\gradlew :ai:testDebugUnitTest --tests me.rerere.ai.ui.MessageTest`

Expected: PASS，所有旧测试和新增测试通过。

### Task 2: 保存每个助手的手动开关，并显示在设置页

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt:20-58`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantBasicPage.kt:410-451`
- Create: `app/src/test/java/me/rerere/rikkahub/data/model/AssistantContextTruncationTest.kt`

**Interfaces:**
- Produces: `Assistant.cacheFriendlyContextTruncation: Boolean = false`
- Produces: `fun Assistant.selectContextMessages(messages: List<UIMessage>): List<UIMessage>`
- Consumes: `isCacheFriendlyContextAvailable(assistant.contextMessageSize)` for UI enabled state.

- [ ] **Step 1: 写会失败的默认值与序列化兼容测试**

```kotlin
@Test
fun `cache friendly truncation defaults to disabled`() {
    assertFalse(Assistant().cacheFriendlyContextTruncation)
}

@Test
fun `assistant without cache friendly field decodes as disabled`() {
    val decoded = json.decodeFromString<Assistant>("""{"name":"Daddy","contextMessageSize":400}""")
    assertFalse(decoded.cacheFriendlyContextTruncation)
}
```

- [ ] **Step 2: 运行测试并确认因为字段不存在而失败**

Run: `$env:GRADLE_USER_HOME='D:\gradle-ascii'; .\gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.model.AssistantContextTruncationTest`

Expected: FAIL，失败点为 `cacheFriendlyContextTruncation` 尚不存在。

- [ ] **Step 3: 为 Assistant 添加默认关闭字段**

```kotlin
val cacheFriendlyContextTruncation: Boolean = false,
```

Place it immediately after `contextMessageSize` so the two context options remain adjacent.

- [ ] **Step 4: 在上下文条数控件下方添加开关与不可用说明**

Use a `Switch` whose checked value is `assistant.cacheFriendlyContextTruncation`; update it via
`assistant.copy(cacheFriendlyContextTruncation = enabled)`. Disable interaction when the limit
is below 200. If the user lowers the slider below 200, persist `false` in the same update so the
stored setting accurately reflects the unavailable state.

The Chinese copy is:

```text
缓存友好截断
按 25% 阶梯裁剪旧消息，让连续聊天更容易复用缓存；仅在上下文上限达到 200 条时可开启。
```

- [ ] **Step 5: 运行助手设置测试并确认通过**

Run: `$env:GRADLE_USER_HOME='D:\gradle-ascii'; .\gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.model.AssistantContextTruncationTest`

Expected: PASS，旧配置与新助手都默认关闭。

### Task 3: 让所有聊天生成入口统一使用同一规则

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt:585`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:638`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt:605-607`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/model/AssistantContextTruncationTest.kt`

**Interfaces:**
- Produces: `Assistant.selectContextMessages(messages)`.
- Produces: identical context selection across ordinary chat, voice-call decline message, and proactive-message generation.

- [ ] **Step 1: 写会失败的助手上下文选择测试**

```kotlin
@Test
fun `assistant selects cache friendly context only when its switch is enabled`() {
    val messages = List(401) { UIMessage.user("message $it") }
    val enabled = Assistant(contextMessageSize = 400, cacheFriendlyContextTruncation = true)
    val disabled = enabled.copy(cacheFriendlyContextTruncation = false)

    assertEquals(301, enabled.selectContextMessages(messages).size)
    assertEquals(400, disabled.selectContextMessages(messages).size)
}
```

- [ ] **Step 2: 运行测试并确认因为选择函数不存在而失败**

Run: `$env:GRADLE_USER_HOME='D:\gradle-ascii'; .\gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.model.AssistantContextTruncationTest`

Expected: FAIL，失败点为 `selectContextMessages` 尚不存在。

- [ ] **Step 3: 在 Assistant 上实现唯一的选择函数，并让三个入口调用它**

```kotlin
fun Assistant.selectContextMessages(messages: List<UIMessage>): List<UIMessage> =
    messages.limitContext(contextMessageSize, cacheFriendly = cacheFriendlyContextTruncation)
```

Replace the `GenerationHandler` call and the two direct `takeLast` sites with
`assistant.selectContextMessages(messages)`. The extension preserves unlimited behavior through
`limitContext`; do not introduce a second local truncation algorithm.

- [ ] **Step 4: 运行目标测试及静态检查**

Run: `$env:GRADLE_USER_HOME='D:\gradle-ascii'; .\gradlew :ai:testDebugUnitTest :app:testDebugUnitTest --tests me.rerere.ai.ui.MessageTest --tests me.rerere.rikkahub.data.model.AssistantContextTruncationTest`

Expected: PASS.

### Task 4: 构建、人工验证与交付

**Files:**
- No additional production files expected.

- [ ] **Step 1: 检查差异与格式**

Run: `git diff --check`

Expected: no whitespace errors.

- [ ] **Step 2: 构建 Companion APK**

Run: `$env:GRADLE_USER_HOME='D:\gradle-ascii'; .\gradlew :app:assembleCompanion`

Expected: `BUILD SUCCESSFUL` and an APK with the existing Daddy companion package id/signing.

- [ ] **Step 3: 人工手机验证**

1. 把上下文设为无限制，确认开关不可用且正常聊天不变。
2. 把上下文设为 100，确认开关不可用。
3. 把上下文设为 200，确认可开启、退出设置后仍保存。
4. 使用 400 条上下文连续聊天，观察首次超过上限、第二个阶梯前后，以及 DeepSeek 页面显示的 cached tokens。
5. 用带工具调用的聊天确认工具请求、结果和回答没有被截断成半组。

- [ ] **Step 4: 仅在用户确认后提交与推送**

Do not stage, commit, push, or create an APK distribution copy until the user confirms the tested scope.
