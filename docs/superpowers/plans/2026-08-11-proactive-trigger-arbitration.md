# 主动消息触发仲裁 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让守夜独占夜间自动消息，并阻止短时间内相同的主动文案重复投递。

**Architecture:** 在纯 Kotlin 规则文件中定义触发优先级和文案指纹去重，先用 JVM 测试锁定行为。`ProactiveMessageTriggerService` 在调用模型前执行守夜仲裁，在投递前执行跨触发源去重；所有自动触发使用手机注入的北京时间并禁止时间工具与用户发言归因。

**Tech Stack:** Kotlin、JUnit、Android SharedPreferences、前台 Service。

## Global Constraints

- 不删除聊天、分支、Ombre 或 Supabase 数据。
- 守夜有效期间，普通定时、激进感知、空闲探索均不能调用模型。
- 同一会话 15 分钟内完全相同的主动文案只投递一次。
- 自动触发不能把内部指令、设备事件、记忆或推理当成用户新回复。

---

### Task 1: 可测试的仲裁与去重规则

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/service/ProactiveTriggerPolicy.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/data/service/ProactiveTriggerPolicyTest.kt`

**Interfaces:**
- Produces: `shouldSuppressForNightWatch(kind, nightWatchArmed): Boolean`
- Produces: `proactiveReplyFingerprint(text): String`
- Produces: `shouldSuppressDuplicateProactiveReply(previousFingerprint, previousAtMillis, candidate, nowMillis): Boolean`

- [x] **Step 1: Write the failing test**

```kotlin
assertTrue(shouldSuppressForNightWatch(ProactiveTriggerKind.Scheduled, true))
assertFalse(shouldSuppressForNightWatch(ProactiveTriggerKind.NightWatch, true))
assertTrue(shouldSuppressDuplicateProactiveReply(fingerprint, now - 10.minutes, sameText, now))
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.service.ProactiveTriggerPolicyTest`

- [x] **Step 3: Write minimal implementation**

```kotlin
internal fun shouldSuppressForNightWatch(kind: ProactiveTriggerKind, armed: Boolean) =
    armed && kind != ProactiveTriggerKind.NightWatch
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.service.ProactiveTriggerPolicyTest`

### Task 2: 接入统一仲裁与事实边界

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt`

**Interfaces:**
- Consumes: Task 1 policy functions.

- [x] **Step 1: 在模型调用前检查守夜状态**

```kotlin
if (shouldSuppressForNightWatch(kind, NightWatchManager.isArmed(this))) {
    stopSelf()
    return@launch
}
```

- [x] **Step 2: 在保存/通知前认领文案指纹**

```kotlin
if (claimProactiveReply(conversationId, replyText)) {
    saveProactiveMessage(...)
}
```

- [x] **Step 3: 为每一种自动触发追加最终事实规则**

```text
本轮不是用户新消息；禁止把任何内容归因为用户刚刚说过。
手机提供的北京时间是唯一有效时间；不得调用时间或日期工具。
```

- [ ] **Step 4: 运行 Task 1 测试与构建**

Run: `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.service.ProactiveTriggerPolicyTest :app:assembleCompanion`
