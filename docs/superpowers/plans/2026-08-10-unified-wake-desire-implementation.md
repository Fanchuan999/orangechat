# Daddy Unified Wake and Desire System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate night watch, ordinary proactive messages, aggressive device events, local mood/desire signals, a bound primary conversation, and optional idle web exploration into one low-token wake system.

**Architecture:** Keep Android scheduling and device observation in their existing services, but route every trigger through small pure policy functions before a model call. Persist user-facing choices in `ProactiveMessageSetting`, keep transient night-watch runtime in private preferences, and let `ProactiveMessageTriggerService` be the single generation path. Add a separate nine-axis desire state beside the existing mood state for backward-compatible backups.

**Tech Stack:** Kotlin, kotlinx.serialization, Android foreground services, WorkManager/AlarmManager, Jetpack Compose, JUnit 4, Gradle.

## Global Constraints

- No new third-party dependency.
- Night-watch reminders have no count limit and remain at least 10 minutes apart.
- Night watch expires at the next Beijing-time 06:00 boundary.
- Only actual user messages can arm or disarm night watch.
- Idle web exploration defaults to disabled, runs 1–3 times per day, and caps raw page input at 20k tokens per run.
- A skipped local wake spends zero model tokens.
- Ordinary, aggressive, and exploration messages target the configured primary conversation; night watch targets the conversation where bedtime was said.
- Existing backup data must deserialize with defaults.

---

### Task 1: Make Night-Watch Policy Pure and Correct

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/service/NightWatchPolicy.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/service/NightWatchService.kt`
- Replace test: `app/src/test/java/me/rerere/rikkahub/data/service/NightWatchMessageClassifierTest.kt`
- Create test: `app/src/test/java/me/rerere/rikkahub/data/service/NightWatchPolicyTest.kt`

**Interfaces:**
- Produces: `NightWatchMessageClassifier.classify(text: String): Action`
- Produces: `nightWatchExpiryAt(nowMillis: Long, timeZone: TimeZone = BEIJING_TIME_ZONE): Long`
- Consumed by: `NightWatchManager.arm()`

- [ ] **Step 1: Write failing classifier tests for every confirmed wake-up phrase**

```kotlin
@Test
fun `morning phrases disarm the watch`() {
    listOf("早上好", "起床啦", "我起床啦", "睡醒", "我不睡了", "我起床了", "别管我了")
        .forEach { phrase ->
            assertEquals(
                NightWatchMessageClassifier.Action.Disarm,
                NightWatchMessageClassifier.classify(phrase),
            )
        }
}
```

- [ ] **Step 2: Write failing expiry tests for before and after 06:00**

```kotlin
@Test
fun `two am expires at six am the same date`() {
    assertEquals(atBeijing(2026, 8, 11, 6, 0), nightWatchExpiryAt(atBeijing(2026, 8, 11, 2, 0)))
}

@Test
fun `ten pm expires at six am the next date`() {
    assertEquals(atBeijing(2026, 8, 12, 6, 0), nightWatchExpiryAt(atBeijing(2026, 8, 11, 22, 0)))
}
```

- [ ] **Step 3: Run focused tests and confirm both new behaviours fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*NightWatch*"`

Expected: failures for the new phrases and the same-date 06:00 case.

- [ ] **Step 4: Extract classifier and expiry calculation into `NightWatchPolicy.kt`**

```kotlin
internal val BEIJING_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

internal fun nightWatchExpiryAt(nowMillis: Long, timeZone: TimeZone = BEIJING_TIME_ZONE): Long =
    Calendar.getInstance(timeZone).run {
        timeInMillis = nowMillis
        val beforeSix = get(Calendar.HOUR_OF_DAY) < 6
        set(Calendar.HOUR_OF_DAY, 6)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (!beforeSix) add(Calendar.DAY_OF_YEAR, 1)
        timeInMillis
    }
```

The disarm phrases are the seven confirmed phrases plus the existing stop variants. Disarm is checked before bedtime phrases, so “我不睡了” never arms the watch.

- [ ] **Step 5: Replace `nextSixAmAfterToday(now)` with `nightWatchExpiryAt(now)`**

- [ ] **Step 6: Run focused tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*NightWatch*"`

Expected: PASS.

- [ ] **Step 7: Commit Task 1**

```text
fix: correct night watch expiry and wake phrases
```

### Task 2: Persist and Select the Primary Proactive Conversation

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/ProactiveMessageSetting.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProactiveMessagePage.kt`
- Create test: `app/src/test/java/me/rerere/rikkahub/data/datastore/ProactiveMessageSettingTest.kt`

**Interfaces:**
- Produces: `ProactiveMessageSetting.primaryConversationId: String`
- Produces: `ProactiveMessageSetting.primaryConversationTitle: String`
- Produces: `ChatVM.setPrimaryProactiveConversation(conversation: Conversation)`

- [ ] **Step 1: Add a serialization compatibility test**

```kotlin
@Test
fun `old proactive settings default to no primary conversation`() {
    val decoded = Json { ignoreUnknownKeys = true }
        .decodeFromString<ProactiveMessageSetting>("{\"enabled\":true}")
    assertEquals("", decoded.primaryConversationId)
    assertEquals("", decoded.primaryConversationTitle)
}
```

- [ ] **Step 2: Run the test and confirm it fails because the fields do not exist**

- [ ] **Step 3: Add backward-compatible fields**

```kotlin
val primaryConversationId: String = "",
val primaryConversationTitle: String = "",
```

- [ ] **Step 4: Add `ChatVM.setPrimaryProactiveConversation`**

```kotlin
fun setPrimaryProactiveConversation(conversation: Conversation) {
    updateSettings(
        settings.value.copy(
            proactiveMessageSetting = settings.value.proactiveMessageSetting.copy(
                assistantId = conversation.assistantId.toString(),
                primaryConversationId = conversation.id.toString(),
                primaryConversationTitle = conversation.title.ifBlank { "新聊天" },
            ),
        ),
    )
}
```

- [ ] **Step 5: Add a top-bar action with selected/unselected state**

The action shows “设为主动消息主窗口” when unselected and “已是主动消息主窗口” when the current conversation ID matches. Tapping it persists the current assistant and conversation; it does not navigate or generate a message.

- [ ] **Step 6: Show the bound title and a clear button on the proactive settings page**

- [ ] **Step 7: Run the serialization test and compile Compose sources**

Run: `./gradlew :app:testDebugUnitTest --tests "*ProactiveMessageSettingTest" :app:compileDebugKotlin`

Expected: PASS.

- [ ] **Step 8: Commit Task 2**

```text
feat: bind proactive messages to a primary conversation
```

### Task 3: Route All Non-Night Triggers Through the Primary Conversation

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/service/ProactiveTargetPolicy.kt`
- Create test: `app/src/test/java/me/rerere/rikkahub/data/service/ProactiveTargetPolicyTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/service/DeviceEventAiTriggerService.kt`

**Interfaces:**
- Produces: `requestedProactiveConversationId(explicitId: Uuid?, setting: ProactiveMessageSetting): Uuid?`
- Rule: explicit night-watch target wins; otherwise use the configured primary conversation.

- [ ] **Step 1: Write failing precedence and malformed-ID tests**

```kotlin
@Test
fun `explicit night watch conversation wins`() {
    assertEquals(explicit, requestedProactiveConversationId(explicit, settingWithPrimary))
}

@Test
fun `malformed configured id safely returns null`() {
    assertNull(requestedProactiveConversationId(null, settingWithPrimary.copy(primaryConversationId = "bad")))
}
```

- [ ] **Step 2: Implement the pure target policy**

```kotlin
internal fun requestedProactiveConversationId(
    explicitId: Uuid?,
    setting: ProactiveMessageSetting,
): Uuid? = explicitId ?: setting.primaryConversationId
    .takeIf(String::isNotBlank)
    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
```

- [ ] **Step 3: Apply the policy before loading a conversation**

Validate that the requested conversation belongs to the chosen assistant. If missing, fall back to that assistant’s most recent conversation and leave the stored setting intact for the UI to report.

- [ ] **Step 4: Preserve night-watch exact targeting**

Night watch continues to pass explicit assistant and conversation extras; the policy therefore never redirects it to the ordinary primary window.

- [ ] **Step 5: Run target and night-watch tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*ProactiveTargetPolicyTest" --tests "*NightWatch*"`

- [ ] **Step 6: Commit Task 3**

```text
feat: unify proactive conversation routing
```

### Task 4: Add a Nine-Axis Desire State and Wake Arbitration

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/CompanionMoodSetting.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/service/CompanionMoodEngine.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/service/WakeCoordinator.kt`
- Modify test: `app/src/test/java/me/rerere/rikkahub/data/datastore/CompanionMoodSettingTest.kt`
- Create test: `app/src/test/java/me/rerere/rikkahub/data/service/WakeCoordinatorTest.kt`

**Interfaces:**
- Produces: `CompanionDesireState`
- Produces: `WakeSource` (`NightWatch`, `Aggressive`, `Scheduled`, `Explore`)
- Produces: `WakeDecision` (`Contact`, `FindActivity`, `Rest`, `Quiet`)
- Produces: `WakeCoordinator.decide(input: WakeInput): WakeDecision`

- [ ] **Step 1: Write failing tests for deterministic desire evolution**

```kotlin
@Test
fun `silence increases longing and curiosity but not all axes`() {
    val evolved = evolveCompanionDesire(CompanionDesireState(updatedAtMillis = 0L), 6 * HOUR)
    assertTrue(evolved.longing > 0.2f)
    assertTrue(evolved.curiosity > 0.1f)
    assertEquals(0f, evolved.irritation, 0.001f)
}
```

- [ ] **Step 2: Add the serializable nine-axis state**

```kotlin
@Serializable
data class CompanionDesireState(
    val longing: Float = 0.2f,
    val closeness: Float = 0.2f,
    val curiosity: Float = 0.12f,
    val expression: Float = 0.12f,
    val care: Float = 0.2f,
    val wander: Float = 0.08f,
    val agency: Float = 0.08f,
    val irritation: Float = 0f,
    val fatigue: Float = 0.08f,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)
```

Keep `CompanionMoodState` unchanged for backup compatibility. `CompanionMoodSetting` gains `desireEnabled` and `desireState` with defaults.

- [ ] **Step 3: Update real conversation event handlers**

User replies lower longing and irritation, raise closeness and curiosity. Assistant replies lower expression and agency. A successful proactive message lowers longing but does not simulate a user reply.

- [ ] **Step 4: Write wake-priority tests**

```kotlin
@Test
fun `night watch outranks scheduled contact`() {
    assertEquals(WakeDecision.Contact, coordinator.decide(input.copy(source = WakeSource.NightWatch)))
}

@Test
fun `ordinary wake remains quiet when desire is low`() {
    assertEquals(WakeDecision.Quiet, coordinator.decide(lowSignalScheduledInput))
}
```

- [ ] **Step 5: Implement the local coordinator and connect scheduled wakes**

Scheduled wakes use the coordinator before model generation. Night watch remains mandatory once its phone-side conditions are met. Aggressive events still reach the model but share the same per-conversation generation claim and deduplication path.

- [ ] **Step 6: Keep prompt injection short and natural**

Map the strongest two axes to a one-sentence cue; never send raw values or axis names to the model.

- [ ] **Step 7: Run mood, desire, coordinator, and night-watch tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*CompanionMoodSettingTest" --tests "*WakeCoordinatorTest" --tests "*NightWatch*"`

- [ ] **Step 8: Commit Task 4**

```text
feat: add nine-axis desire wake decisions
```

### Task 5: Consolidate the Proactive Settings UI

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/ProactiveMessageSetting.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProactiveMessagePage.kt`
- Create test: `app/src/test/java/me/rerere/rikkahub/data/datastore/WakeSettingValidationTest.kt`

**Interfaces:**
- Produces: `wakeRhythmEnabled`, `wakeIntervalMinutes`, `wakeRandomPercent`
- Produces: `idleExploreEnabled`, `idleExploreRunsPerDay`, `idleExploreRawTokenLimit`

- [ ] **Step 1: Write default and clamping tests**

```kotlin
@Test
fun `idle exploration defaults off and limits are clamped`() {
    val setting = ProactiveMessageSetting()
    assertFalse(setting.idleExploreEnabled)
    assertEquals(1, setting.validatedExploreRunsPerDay())
    assertEquals(20_000, setting.validatedExploreRawTokenLimit())
}
```

- [ ] **Step 2: Add serializable settings with safe defaults**

```kotlin
val wakeRhythmEnabled: Boolean = true,
val wakeIntervalMinutes: Int = 90,
val wakeRandomPercent: Int = 30,
val idleExploreEnabled: Boolean = false,
val idleExploreRunsPerDay: Int = 1,
val idleExploreRawTokenLimit: Int = 20_000,
```

- [ ] **Step 3: Reorganize the page into independent sections**

Sections: primary window, ordinary proactive messages, mood/desire decision, aggressive awareness, night watch, idle exploration, and Android permissions. Each switch affects only its own source.

- [ ] **Step 4: Update night-watch explanatory copy**

Copy states unlimited reminders, 10-minute minimum interval, the seven wake-up phrases, and automatic 06:00 expiry.

- [ ] **Step 5: Run settings tests and compile Compose**

Run: `./gradlew :app:testDebugUnitTest --tests "*WakeSettingValidationTest" :app:compileDebugKotlin`

- [ ] **Step 6: Commit Task 5**

```text
feat: consolidate wake and companion settings
```

### Task 6: Add Optional Low-Token Idle Web Exploration

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/service/IdleExplorePolicy.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/service/IdleExploreScheduler.kt`
- Create test: `app/src/test/java/me/rerere/rikkahub/data/service/IdleExplorePolicyTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt`

**Interfaces:**
- Produces: `IdleExplorePolicy.nextWindow(...)`
- Produces: `EXTRA_IDLE_EXPLORE_TRIGGER`
- Persists only counters, last-run timestamp, and URL fingerprints; no automatic Ombre write.

- [ ] **Step 1: Write failing tests for disabled, daily-cap, cooldown, and quiet-hour cases**

```kotlin
@Test
fun `disabled exploration never schedules`() {
    assertNull(policy.nextWindow(setting.copy(idleExploreEnabled = false), state, now))
}

@Test
fun `daily cap blocks an additional run`() {
    assertNull(policy.nextWindow(setting.copy(idleExploreRunsPerDay = 1), state.copy(runsToday = 1), now))
}
```

- [ ] **Step 2: Implement a local scheduler with randomized windows**

The scheduler creates at most the configured number of opportunities per Beijing calendar day and never wakes inside night watch or while a conversation is generating.

- [ ] **Step 3: Add a specialized exploration prompt**

Supply at most 12 recent text messages from the primary conversation, a short desire cue, and instructions to use only public read/search tools. The final user-visible message must be concise and include links when available.

- [ ] **Step 4: Restrict tools and enforce the raw-content budget**

Expose only the independent web search tool and enabled webpage-reader tools for this trigger. Limit tool steps and truncate accumulated webpage result text before the 20k raw-token budget. If no eligible tools exist, skip without generating a user message.

- [ ] **Step 5: Do not auto-save exploration into Ombre**

Only the final visible assistant message follows the normal conversation persistence path. No `hold`, `grow`, or external memory write is invoked by the exploration scheduler.

- [ ] **Step 6: Run exploration policy tests and service compilation**

Run: `./gradlew :app:testDebugUnitTest --tests "*IdleExplorePolicyTest" :app:compileDebugKotlin`

- [ ] **Step 7: Commit Task 6**

```text
feat: add optional low-token idle exploration
```

### Task 7: Regression Verification and APK

**Files:**
- Modify only if verification finds a scoped defect.
- Output: `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 1: Run all relevant unit tests**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 2: Run Android lint for the app module**

Run: `./gradlew :app:lintDebug`

Expected: no new fatal issue from this change.

- [ ] **Step 3: Build the arm64 debug APK**

Run: `./gradlew :app:assembleDebug`

Expected: APK produced successfully and signed with the existing Daddy debug key.

- [ ] **Step 4: Verify the APK metadata and checksum**

Record package ID, version name/code, file size, and SHA-256. Do not install or push without the user’s current authorization.

- [ ] **Step 5: Provide a phone test checklist**

Verify: same-day 06:00 expiry from 02:00; next-day expiry from 22:00; every wake phrase disarms; reminders can repeat after 10 minutes; primary-window delivery; night-watch override; each independent switch; exploration disabled by default; exploration daily cap.
