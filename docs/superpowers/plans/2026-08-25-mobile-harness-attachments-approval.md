# Mobile Harness Attachments and Approval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add safe native picture/file entry points to the mobile Harness workbench and expose three durable approval modes without weakening the existing high-risk gate.

**Architecture:** Daddy continues to host the official DSH web workbench in `HarnessWebViewPage`; the Android file chooser already exists and is reused. Managed Termux scripts deploy an idempotent, version-checked DSH patch. Images use the official `session.prompt` image contract; ordinary files enter an explicit local `uploads/` endpoint and never become pseudo-image messages.

**Tech Stack:** Kotlin/Compose, Android WebView, Termux shell, Debian/Node DSH runtime, JUnit, instrumented Android file picker smoke test.

**Spec:** `docs/superpowers/specs/2026-08-25-harness-mobile-desktop-relay-design.md`

## Global Constraints

- DSH is pinned to `HarnessRuntimeContract.HARNESS_VERSION`; no `latest` patching.
- Patch application must be atomic, idempotent, backed up, and refuse unknown source signatures.
- Image limit remains 5 MB and accepted MIME remains PNG/JPEG/WebP/GIF.
- Ordinary file imports are at most 25 MB, stored under current workspace `uploads/`, uniquely named, and never auto-executed.
- Android keeps `allowFileAccess = false`; selection only goes through `OpenDocument` content URIs.

---

### Task 1: Capture a reproducible DSH patch surface before changing runtime files

**Files:**
- Create: `docs/testing/2026-08-25-dsh-mobile-attachment-surface.md`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessRuntimeContract.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessRuntimeContractTest.kt`

**Interfaces:**
- Produces `HarnessRuntimeContract.MOBILE_ATTACHMENT_PATCH_REVISION` and a checked-in list of DSH package-relative targets plus SHA-256 signatures.

- [ ] **Step 1: Add a failing contract test for an explicit nonblank patch revision and target-signature list**
- [ ] **Step 2: On the phone, run a read-only script that records only DSH version, package-relative candidate bundle paths, SHA-256 hashes, and supported RPC contracts**
- [ ] **Step 3: Add the recorded signatures and patch revision to `HarnessRuntimeContract`**
- [ ] **Step 4: Run `./gradlew :app:testDebugUnitTest --tests '*HarnessRuntimeContractTest'`**
- [ ] **Step 5: Commit the patch-surface contract and evidence**

### Task 2: Implement managed patch backup, verification, apply, and rollback

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt`
- Create: `app/src/main/assets/harness-mobile-patch/inject-workbench-controls.mjs`
- Create: `app/src/main/assets/harness-mobile-patch/file-import-service.mjs`
- Test: `app/src/test/java/me/rerere/rikkahub/data/sync/companion/HarnessScriptsTest.kt`

**Interfaces:**
- Produces `apply-harness-mobile-patch.sh` and `rollback-harness-mobile-patch.sh` below `$HOME/daddy-linux/scripts`.
- Apply result is one of `applied`, `already_applied`, `incompatible`, or `rolled_back`; unknown input never produces `applied`.

- [ ] **Step 1: Add failing script-render tests for backup path, signature guard, temporary output, atomic rename, marker file, and rollback command**
- [ ] **Step 2: Add managed assets that inject Image/File buttons into the verified DSH input surface and register the local import route**
- [ ] **Step 3: Extend `HarnessScripts.scriptFiles()` and install flow so scripts/assets are written before DSH starts, then patch only after DSH version/signatures match**
- [ ] **Step 4: Make post-update setup run apply and health-check; on failure restore the backed-up target and keep the unmodified workbench reachable**
- [ ] **Step 5: Run Harness script tests and commit**

### Task 3: Wire native image/file chooser handling and enforce file policy

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessWebViewPage.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/harness/HarnessAttachmentChooserPolicy.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/harness/HarnessAttachmentChooserPolicyTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/harness/HarnessWebViewportPolicyTest.kt`

**Interfaces:**
- `HarnessAttachmentChooserPolicy.acceptTypes(params): Array<String>` returns only image MIME for image requests and `*/*` for ordinary files.
- `HarnessAttachmentChooserPolicy.validate(uriMetadata): AttachmentDecision` returns `Allow`, `RejectTooLarge`, or `RejectUnsupported` without reading a >25 MB ordinary file into memory.

- [ ] **Step 1: Write failing policy tests for image MIME selection, 5 MB image limit, 25 MB ordinary-file limit, null result, and unknown-size rejection**
- [ ] **Step 2: Implement metadata-first validation using `ContentResolver` before the WebView receives the URI**
- [ ] **Step 3: Update `onShowFileChooser` to cancel stale callbacks, launch exactly once, and return `null` for rejected/cancelled selections**
- [ ] **Step 4: Run targeted unit tests and an instrumented picker smoke test on the iQOO device**
- [ ] **Step 5: Commit the Android chooser safety layer**

### Task 4: Add three Harness approval modes without silently allowing high risk

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/CodeHutSetting.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/codehut/CodeHutShellPolicy.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/codehut/CodeHutPage.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/datastore/CodeHutSettingTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/codehut/CodeHutShellPolicyTest.kt`

**Interfaces:**
- Add enum values `SAFE_CONFIRMATION`, `DEVELOPER_SMOOTH`, `FULL_AUTO`.
- `classifyRisk(command, scope): RiskDecision` must always return `RequiresApproval` for delete, bulk overwrite, package install, credential, push/release, external submission, and cross-directory bulk operations regardless of selected mode.

- [ ] **Step 1: Add failing persistence and classification tests for all three modes and every mandatory high-risk category**
- [ ] **Step 2: Extend the managed risk-gate environment and UI selection with exact mode names and expiry/revision persistence**
- [ ] **Step 3: Implement mode-specific allowlists; do not use broad shell regex to whitelist arbitrary commands**
- [ ] **Step 4: Run all Code Hut approval tests and a phone smoke test for a benign edit plus a rejected delete**
- [ ] **Step 5: Commit approval modes and test evidence**

### Task 5: Release and recovery verification

**Files:**
- Modify: `docs/testing/2026-08-24-harness-session-rpc-smoke.md`
- Create: `docs/testing/2026-08-25-harness-mobile-attachment-release.md`

- [ ] **Step 1: Verify an image reaches an actual Harness session through the patched workbench**
- [ ] **Step 2: Verify a small PDF/text file lands in `uploads/` with a non-overwriting name and a textual path message**
- [ ] **Step 3: Verify a >25 MB selection is refused before byte upload and gives the `/host/storage/...` alternative**
- [ ] **Step 4: Re-run setup/repair and prove the patch is `already_applied`; simulate mismatched signature and prove rollback/incompatible behavior**
- [ ] **Step 5: Build `:app:assembleCompanion`, run focused tests, and commit evidence without publishing an APK until user approval**
