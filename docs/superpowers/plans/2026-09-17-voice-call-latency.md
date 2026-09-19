# Voice Call Latency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce perceived phone-call latency without changing a user's chosen ASR/TTS provider or broadening microphone access.

**Architecture:** Keep normal text chat untouched. The voice-call service receives an explicit ready state from ASR, cancels the active chat generation when the user barges in, and requests a voice-specific low-context generation surface. The TTS controller gains a provider-agnostic streaming MP3 playback path, with its existing complete-audio path retained for formats or providers that cannot stream safely.

**Tech Stack:** Kotlin, coroutines/Flow, Android foreground services, Media3 ExoPlayer, JUnit.

**Spec:** User-approved in-thread design on 2026-09-17.

## Global Constraints

- Preserve existing ASR/TTS provider settings and secrets; no credentials in logs or tests.
- Do not start a persistent wake-word listener or access the microphone outside an accepted voice call.
- Do not modify Supabase, Ombre, PC Bridge, unrelated pending changes, Git history, deployment, or device state.
- Non-streaming audio formats must retain the existing complete-buffer playback fallback.
- Voice context must retain the assistant's system prompt and conversation identity while omitting external tool surfaces and using a bounded recent message tail.

---

### Task 1: Make ASR readiness and turn timing explicit

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/VoiceCallTurnPolicy.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/VoiceCallService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/voice/VoiceCallState.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/voice/VoiceCallPage.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/service/VoiceCallTurnPolicyTest.kt`

**Interfaces:**
- Produces `VoiceCallTurnPolicy.callStatusForAsr(ASRStatus): VoiceCallStatus?`.
- Produces `VoiceCallStatus.Connecting`, which is visible until the ASR controller reports `Listening`.

- [ ] **Step 1: Write failing unit tests** for connecting-to-listening transition, ASR error mapping, and no status change while Daddy is speaking.
- [ ] **Step 2: Run `:app:testDebugUnitTest --tests me.rerere.rikkahub.service.VoiceCallTurnPolicyTest`** and confirm the missing policy fails.
- [ ] **Step 3: Implement the pure policy and route the service's ASR state through it.** `startCall()` and ASR restarts begin in `Connecting`; `Listening` is entered only after the controller reports it.
- [ ] **Step 4: Render the connecting state** with non-listening copy and update all exhaustive `VoiceCallStatus` branches.
- [ ] **Step 5: Re-run the targeted test** and confirm it passes.

### Task 2: Add a testable generic streaming-audio buffer

**Files:**
- Create: `speech/src/main/java/me/rerere/tts/controller/StreamingAudioBuffer.kt`
- Create: `speech/src/test/java/me/rerere/tts/controller/StreamingAudioBufferTest.kt`

**Interfaces:**
- Produces `StreamingAudioBuffer.append(bytes)`, `finish()`, `fail(error)`, `read(destination, offset, length): Int`, and `close()`.
- `read` blocks until bytes, terminal completion, failure, or close; it returns `-1` only after a completed stream is drained.

- [ ] **Step 1: Write failing tests** proving incremental reads expose data before `finish()`, final drain yields `-1`, and close unblocks a waiting reader.
- [ ] **Step 2: Run `:speech:test --tests me.rerere.tts.controller.StreamingAudioBufferTest`** and confirm the class is absent.
- [ ] **Step 3: Implement the synchronized bounded-in-memory producer/consumer buffer** without recording files or provider metadata.
- [ ] **Step 4: Re-run the targeted tests** and confirm they pass.

### Task 3: Play streaming MP3 providers immediately with a safe fallback

**Files:**
- Modify: `speech/src/main/java/me/rerere/tts/controller/AudioPlayer.kt`
- Modify: `speech/src/main/java/me/rerere/tts/controller/TtsSynthesizer.kt`
- Modify: `speech/src/main/java/me/rerere/tts/controller/TtsController.kt`
- Test: `speech/src/test/java/me/rerere/tts/controller/TtsSynthesizerTest.kt`

**Interfaces:**
- Produces `TtsSynthesizer.synthesizeStreamingMp3(...)` that writes MP3 `AudioChunk`s into `StreamingAudioBuffer` and reports first-audio availability.
- Existing `synthesize(...)` remains the fallback for PCM and complete-audio providers.

- [ ] **Step 1: Write failing tests** proving an MP3 flow signals first audio before its terminal event and that a PCM flow selects the buffered fallback.
- [ ] **Step 2: Run `:speech:test --tests me.rerere.tts.controller.TtsSynthesizerTest`** and confirm the requested API is absent.
- [ ] **Step 3: Add a Media3 `DataSource` backed by `StreamingAudioBuffer` and start ExoPlayer on the first MP3 chunk.** Feed later chunks to the same source; end it only on provider completion; cancellation closes the source and stops playback.
- [ ] **Step 4: Update `TtsController`** to use streaming MP3 when available and retain existing prefetch/error behavior for all other formats.
- [ ] **Step 5: Re-run targeted speech tests** and confirm they pass.

### Task 4: Give voice turns a bounded, tool-free chat generation path

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/VoiceCallContextPolicy.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/VoiceCallService.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/VoiceCallContextPolicyTest.kt`

**Interfaces:**
- Produces `VoiceCallContextPolicy.select(messages, digest): List<UIMessage>` retaining the rolling digest when present and the newest 12 messages.
- Produces a voice-generation flag that excludes web, MCP, plugin, workspace, PC bridge, system, and local tool definitions without changing normal chat.

- [ ] **Step 1: Write failing tests** for retaining the digest plus 12 newest messages, never removing the current user turn, and excluding tool surfaces only for voice generation.
- [ ] **Step 2: Run the app targeted tests** and confirm the policy/API is absent.
- [ ] **Step 3: Thread a `voiceCall` generation option from `VoiceCallService` through `ChatService` to `GenerationHandler`.** Build the assistant/persona and input transformers as normal; replace only the history selection and tool list.
- [ ] **Step 4: Re-run targeted tests** and confirm they pass.

### Task 5: Cancel an obsolete generation at the start of a user barge-in

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/VoiceCallService.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/service/VoiceCallBargeInPolicyTest.kt`

**Interfaces:**
- Produces `ChatService.cancelGeneration(conversationId: Uuid)` which cancels only the current conversation job and does not append an error message.
- `VoiceCallService.interruptSpeaking()` calls `cancelGeneration` before returning to the connecting/listening turn.

- [ ] **Step 1: Write failing policy tests** for exactly one cancel on a speaking-to-listening barge-in and no cancel in other states.
- [ ] **Step 2: Run the app targeted tests** and confirm missing behavior fails.
- [ ] **Step 3: Implement the narrow cancellation API and call it from the existing barge-in path.** Preserve partial assistant text in the visible history and do not trigger post-generation side effects.
- [ ] **Step 4: Re-run targeted tests** and confirm they pass.

### Task 6: Verify the integrated build and package

**Files:**
- Modify only files above and any generated schema/resource required by compilation.

- [ ] **Step 1: Run `:speech:test`, `:app:testDebugUnitTest`, and `:app:compileDebugAndroidTestKotlin`.**
- [ ] **Step 2: Run `:app:assembleCompanion`.**
- [ ] **Step 3: Run `git diff --check` and `git status --short`; preserve every pre-existing change.**
- [ ] **Step 4: Report the APK path, test results, known limitations, and the need for real-device latency validation.**
