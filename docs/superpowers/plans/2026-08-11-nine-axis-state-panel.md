# 九维欲望状态面板 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Daddy 的“主动消息与情绪”设置页中展示九维欲望的只读实时数值，不改变唤醒决策，也不产生额外 token 消耗。

**Architecture:** 数据层提供一个纯函数，把 `CompanionDesireState` 转换为固定九项的展示模型；UI 读取设置后用现有 `evolveCompanionDesire` 得到无副作用的当前值，再渲染九条进度条。该转换不写 DataStore、不调用模型。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、DataStore、JUnit 4、Gradle companion APK。

## Global Constraints

- 九维状态只读，禁止滑块、重置和手动编辑。
- 展示值使用现有 `evolveCompanionDesire`；不改变 `WakeCoordinator` 阈值、权重或演化规则。
- 关闭“九维欲望”时隐藏面板。
- 不调用模型、不写入 Ombre/Supabase、不新增后台定时器。
- 继续产出 arm64 的 `me.rerere.orangechat.companion` 可覆盖更新 APK。

---

### Task 1: 建立可测试的九维展示模型

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/CompanionMoodSetting.kt:54-94`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/datastore/CompanionMoodSettingTest.kt`

**Interfaces:**

- Consumes: `CompanionDesireState` 的九个 `Float` 字段，取值范围 0f..1f。
- Produces: `data class CompanionDesireDisplayItem(val label: String, val value: Float, val description: String?)` 与 `fun CompanionDesireState.displayItems(): List<CompanionDesireDisplayItem>`。

- [ ] **Step 1: 写失败测试，锁定九项顺序和数值转换**

```kotlin
@Test
fun desireDisplayItemsExposeAllNineAxesAsPercentages() {
    val items = CompanionDesireState(longing = 0f, closeness = 1f, curiosity = 0.5f).displayItems()

    assertEquals(9, items.size)
    assertEquals("想你", items[0].label)
    assertEquals("亲密", items[1].label)
    assertEquals("好奇", items[2].label)
    assertEquals(0f, items[0].value, 0.001f)
    assertEquals(1f, items[1].value, 0.001f)
    assertEquals(0.5f, items[2].value, 0.001f)
    assertEquals("累（闸门）", items.last().label)
}
```

- [ ] **Step 2: 运行测试，确认它因 `displayItems` 不存在而失败**

Run: `$env:GRADLE_USER_HOME='D:\gradle-ascii'; .\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests me.rerere.rikkahub.data.datastore.CompanionMoodSettingTest`

Expected: FAIL，错误指向未解析的 `displayItems`。

- [ ] **Step 3: 写最小展示模型实现**

```kotlin
data class CompanionDesireDisplayItem(
    val label: String,
    val value: Float,
    val description: String? = null,
)

fun CompanionDesireState.displayItems(): List<CompanionDesireDisplayItem> = listOf(
    CompanionDesireDisplayItem("想你", longing, "一阵子没有收到你的消息，正在慢慢累积。"),
    CompanionDesireDisplayItem("亲密", closeness),
    CompanionDesireDisplayItem("好奇", curiosity),
    CompanionDesireDisplayItem("想说", expression),
    CompanionDesireDisplayItem("记挂", care),
    CompanionDesireDisplayItem("想逛", wander),
    CompanionDesireDisplayItem("想动手", agency),
    CompanionDesireDisplayItem("烦", irritation),
    CompanionDesireDisplayItem("累（闸门）", fatigue, "数值较高时，Daddy 会主动收敛。"),
)
```

- [ ] **Step 4: 运行测试，确认九项模型通过**

Run: `$env:GRADLE_USER_HOME='D:\gradle-ascii'; .\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests me.rerere.rikkahub.data.datastore.CompanionMoodSettingTest`

Expected: PASS。

- [ ] **Step 5: 提交数据层与测试**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/datastore/CompanionMoodSetting.kt app/src/test/java/me/rerere/rikkahub/data/datastore/CompanionMoodSettingTest.kt
git commit -m "feat: expose nine-axis desire state"
```

### Task 2: 在主动消息设置页渲染只读状态面板

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProactiveMessagePage.kt:1-220`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/CompanionMoodSetting.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/datastore/CompanionMoodSettingTest.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**

- Consumes: `evolveCompanionDesire(moodSetting.desireState)` 与 `displayItems()`。
- Produces: `@Composable private fun NineAxisDesireStatePanel(state: CompanionDesireState)` 与 `fun desireDisplayPercent(value: Float): Int`。

- [ ] **Step 1: 写失败测试，锁定百分比显示转换**

```kotlin
@Test
fun desireDisplayPercentConvertsWholePercentAndClampsBounds() {
    assertEquals(0, desireDisplayPercent(-0.1f))
    assertEquals(50, desireDisplayPercent(0.5f))
    assertEquals(100, desireDisplayPercent(1.4f))
}
```

- [ ] **Step 2: 运行测试，确认它因 `desireDisplayPercent` 不存在而失败**

Run: `$env:GRADLE_USER_HOME='D:\gradle-ascii'; .\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests me.rerere.rikkahub.data.datastore.CompanionMoodSettingTest`

Expected: FAIL，错误指向未解析的 `desireDisplayPercent`。

- [ ] **Step 3: 实现显示转换与页面面板**

```kotlin
fun desireDisplayPercent(value: Float): Int = (value.coerceIn(0f, 1f) * 100).toInt()
```

在“九维欲望”开关下方仅当 `moodSetting.desireEnabled` 为 true 时添加一个 `item`，其 supportingContent 调用：

```kotlin
NineAxisDesireStatePanel(evolveCompanionDesire(moodSetting.desireState))
```

面板对每个 `displayItems()` 条目渲染名称、`desireDisplayPercent(item.value)`、Material 3 `LinearProgressIndicator(progress = { item.value.coerceIn(0f, 1f) })`。只为“想你”和“累（闸门）”显示描述；顶部显示“本地计算 · 不耗 token”，底部显示“数值是状态线索，不是命令或固定台词”。

- [ ] **Step 4: 运行测试，确认百分比转换与原测试通过**

Run: `$env:GRADLE_USER_HOME='D:\gradle-ascii'; .\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests me.rerere.rikkahub.data.datastore.CompanionMoodSettingTest`

Expected: PASS。

- [ ] **Step 5: 构建可覆盖更新的 companion APK**

将 `app/build.gradle.kts` 的 `versionCode` 更新为 `192`、`versionName` 更新为 `2.5.6`，然后运行：

Run: `$env:GRADLE_USER_HOME='D:\gradle-ascii'; .\gradlew.bat --no-daemon --console=plain :app:assembleCompanion`

Expected: `app/build/outputs/apk/companion/app-arm64-v8a-companion.apk` 生成成功，applicationId 仍为 `me.rerere.orangechat.companion`。

- [ ] **Step 6: 提交界面与版本更新**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProactiveMessagePage.kt app/src/main/java/me/rerere/rikkahub/data/datastore/CompanionMoodSetting.kt app/src/test/java/me/rerere/rikkahub/data/datastore/CompanionMoodSettingTest.kt app/build.gradle.kts
git commit -m "feat: show nine-axis desire state"
```
