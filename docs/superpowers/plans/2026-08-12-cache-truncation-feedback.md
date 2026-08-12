# Cache Truncation Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a short in-chat Android toast only when cache-friendly context selection actually removes older messages.

**Architecture:** Expose one small selection result from the assistant context policy so generation can distinguish a real truncation from an enabled-but-not-yet-needed setting. Keep the existing list-returning API for all current callers, and show the toast once per generated reply.

**Tech Stack:** Kotlin, JUnit 4, Android Toast, Gradle JVM tests.

## Global Constraints

- Preserve the user's selected context limit and all persisted conversation messages.
- Do not show this feedback for an enabled setting that has not truncated any message.
- `Toast.LENGTH_SHORT` is the requested roughly two-second duration.

---

### Task 1: Describe whether a selection actually truncated history

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/model/AssistantContextTruncationTest.kt`

- [ ] Write a failing test that enables a 400-message cache-friendly limit with 401 messages and expects the selection metadata to report truncation.
- [ ] Run the targeted test and confirm it fails because the metadata API does not exist.
- [ ] Add a minimal `ContextMessageSelection` result that returns the chosen messages and whether their count is lower than the original history.
- [ ] Run the targeted test and confirm it passes.

### Task 2: Surface one feedback toast per generated reply

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`

- [ ] Use the new context selection result immediately before request construction.
- [ ] When and only when the result reports truncation, show `缓存截断生效中` once with `Toast.LENGTH_SHORT`.
- [ ] Keep subsequent tool-loop requests from showing duplicate toasts for the same reply.
- [ ] Run the targeted JVM tests and assemble the companion APK.
