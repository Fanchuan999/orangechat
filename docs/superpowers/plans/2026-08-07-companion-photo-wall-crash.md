# Companion Photo Wall Crash Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent Daddy's little house from crashing when its photo wall contains saved photos.

**Architecture:** Move the horizontal photo list out of Material3 `ListItem` into a focused composable section. The new section owns only presentation and callbacks; `CompanionSpacePage` remains responsible for picker, persistence, captions, and snackbars.

**Tech Stack:** Kotlin, Jetpack Compose Material3, Coil 3, Android Compose UI testing.

## Global Constraints

- Preserve every existing `CompanionPhoto` and its backup format.
- Keep photo browsing lazy and horizontally scrollable.
- Do not use `IntrinsicSize` or place a `LazyRow` inside `CardGroup` / Material3 `ListItem`.
- Build the companion variant (`me.rerere.orangechat.companion`).

---

### Task 1: Render stored photos outside the intrinsic-measurement path

**Files:**
- Create: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/companion/PhotoWallSectionInstrumentedTest.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/companion/PhotoWallSection.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/companion/CompanionSpacePage.kt:278-313`

**Interfaces:**
- Consumes: `List<CompanionPhoto>` and the picker/caption/remove callbacks in `CompanionSpacePage`.
- Produces: `PhotoWallSection(photos, onAddPhoto, onEditCaption, onRemovePhoto)` for standalone rendering.

- [x] **Step 1: Write the failing instrumentation test**

```kotlin
composeRule.setContent {
    MaterialTheme {
        PhotoWallSection(
            photos = listOf(CompanionPhoto(uri = "file:///test.jpg", caption = "测试照片")),
            onAddPhoto = {},
            onEditCaption = {},
            onRemovePhoto = { removed = it },
        )
    }
}
composeRule.onNodeWithText("测试照片").assertExists()
composeRule.onNodeWithText("取下").performClick()
assertEquals("测试照片", removed?.caption)
```

- [x] **Step 2: Compile the test before implementation**

Run: `./gradlew :app:compileDebugAndroidTestKotlin --offline --no-daemon --console=plain`

Expected: compilation fails because `PhotoWallSection` does not exist.

- [x] **Step 3: Implement the minimal standalone section**

```kotlin
@Composable
internal fun PhotoWallSection(...) {
    Surface(...) {
        Column(...) {
            LazyRow(modifier = Modifier.height(228.dp)) { ... }
        }
    }
}
```

Keep `LazyRow` at a fixed cross-axis height and outside all Material3 `ListItem` content. Move the existing `PhotoWallCard` into this file as a private rendering detail so the section is self-contained.

- [x] **Step 4: Replace only the photo-wall `CardGroup` item**

In `CompanionSpacePage`, replace the current `CardGroup(title = { Text("照片墙") })` block with `PhotoWallSection`. Reuse the exact existing picker, caption, deletion, and snackbar callback behavior.

- [x] **Step 5: Verify compilation and regression test**

Run: `./gradlew :app:compileDebugAndroidTestKotlin :app:assembleCompanion --offline --no-daemon --console=plain`

Expected: both tasks finish successfully. When an Android test device is available, run `connectedCompanionAndroidTest` and confirm the new test passes.

- [ ] **Step 6: Commit and push**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/companion/CompanionSpacePage.kt \
        app/src/main/java/me/rerere/rikkahub/ui/pages/companion/PhotoWallSection.kt \
        app/src/androidTest/java/me/rerere/rikkahub/ui/pages/companion/PhotoWallSectionInstrumentedTest.kt \
        docs/superpowers/specs/2026-08-07-companion-photo-wall-crash-design.md \
        docs/superpowers/plans/2026-08-07-companion-photo-wall-crash.md
git commit -m "Fix companion photo wall layout crash"
git push fork master
```
