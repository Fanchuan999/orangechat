# Daddy Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add native Android home-screen widgets for Daddy status cards without model calls.

**Architecture:** Use AppWidgetProvider + RemoteViews. Store widget configuration in existing companion-space settings so normal backup can carry it. Widget rendering is resilient: missing data falls back to black-pink card defaults.

**Tech Stack:** Android AppWidget, RemoteViews, DataStore, Kotlin coroutines, existing SettingsStore and CompanionMoodSetting.

## Global Constraints

- Do not add token-spending model calls during widget updates.
- Preserve current package/signature behavior for debug APK updates.
- Keep UI copy in Chinese for Daddy-only custom pages.
- Use native Android widget XML layouts because Compose cannot directly render AppWidget UI here.

---

### Task 1: Widget settings model

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/CompanionSpaceSetting.kt`

**Interfaces:**
- Produces: `CompanionWidgetSetting(enabled: Boolean, backgroundImageUri: String, shortLine: String)`.
- Consumes: existing `CompanionSpaceSetting`.

- [ ] Add serializable settings fields.
- [ ] Verify existing JSON defaults remain backward compatible.

### Task 2: Widget renderer and provider

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/widget/DaddyWidgetProvider.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/widget/DaddyWidgetRenderer.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/widget/DaddyWidgetState.kt`
- Create: `app/src/main/res/layout/widget_daddy_status.xml`
- Create: `app/src/main/res/xml/daddy_widget_2x2.xml`
- Create: `app/src/main/res/xml/daddy_widget_2x4.xml`
- Create: `app/src/main/res/xml/daddy_widget_4x4.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `SettingsStore.settingsFlow`, `CompanionMoodSetting.currentSummary()`.
- Produces: three AppWidget receivers registered in manifest.

- [ ] Add provider classes for each size or one provider with size mode detection.
- [ ] Add RemoteViews layout with black-pink card fields.
- [ ] Add widget metadata XML for 2×2、2×4、4×4.
- [ ] Register receivers.

### Task 3: Companion-space settings UI

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/companion/CompanionSpacePage.kt`

**Interfaces:**
- Consumes: `CompanionWidgetSetting`.
- Produces: UI controls for enable/background/short line and refresh trigger.

- [ ] Add “桌面小组件” card in Daddy 小屋.
- [ ] Add enable switch.
- [ ] Add background image picker using existing photo/file pattern when available.
- [ ] Add short-line text field.
- [ ] Trigger widget refresh after save.

### Task 4: Verification and packaging

**Files:**
- Build output copied to `D:\Daddy-安装包`.

- [ ] Run focused unit checks if available.
- [ ] Run `.\gradlew.bat :app:compileDebugKotlin --no-daemon`.
- [ ] Run `.\gradlew.bat :app:assembleDebug --no-daemon`.
- [ ] Copy arm64 debug APK to Daddy install folder.
- [ ] Commit and push.
