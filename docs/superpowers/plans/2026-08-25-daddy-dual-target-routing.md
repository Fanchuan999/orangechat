# Daddy Dual-Target Task Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let Daddy create, approve, cancel, and read minimal-status tasks for either local mobile Harness or a paired PC Relay, without mixing companion memory into coding work.

**Architecture:** Android owns user-visible task target choice, pairing state, encrypted mobile mailbox client, and confirmation cards. The existing Code Hut remains a lightweight local Harness board; this plan adds no project-management surface there. Task transport and status are separate from chat history and all chat-to-task payload construction is explicit/minimal.

**Tech Stack:** Kotlin, Compose, Android Keystore, OkHttp, kotlinx.serialization, existing SettingsStore/DataSourceModule, Supabase REST/RPC.

**Spec:** `docs/superpowers/specs/2026-08-25-harness-mobile-desktop-relay-design.md`

## Global Constraints

- Start only after PC Relay prototype and control-plane plans pass their stated gates.
- Never embed Supabase service credentials, Relay symmetric keys, Daddy prompts, Ombre memories, or complete chat history in UI state/logs.
- Code Hut UI remains unchanged except for safe status refresh integration where already present.
- Daddy must not automatically dispatch a task based only on the word “code”; explicit target choice/confirmation is required.

---

### Task 1: Add Android task-routing domain and encrypted pairing state

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/taskrelay/TaskRelayModels.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/taskrelay/TaskRelayCrypto.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/taskrelay/TaskRelayPairing.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/taskrelay/PairedRelayStore.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/taskrelay/TaskRelayCryptoTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/taskrelay/PairedRelayStoreTest.kt`

- [ ] **Step 1: Write failing tests for task IDs, one-time approval nonces, target enum (`MOBILE_HARNESS`, `DESKTOP_CLAUDE`), ciphertext authentication, QR pairing-offer expiry, and pairing revocation**
- [ ] **Step 2: Implement Android Keystore-backed X25519 device key handling and ciphertext-only payload model matching the Relay envelope contract**
- [ ] **Step 3: Implement `TaskRelayPairing`: scan/parse the one-time PC pairing offer, derive the shared key, send only encrypted challenge material, and reject reused/expired offers**
- [ ] **Step 4: Implement paired-device record storage with friendly name, public metadata, revocation timestamp, and no plaintext shared secret**
- [ ] **Step 5: Bind repository/store instances through `DataSourceModule` and run targeted JVM tests**
- [ ] **Step 6: Commit Android relay domain layer**

### Task 2: Implement mailbox client, target validation, and phone approvals

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/taskrelay/TaskRelayMailboxClient.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/taskrelay/TaskRelayApprovalPolicy.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/taskrelay/TaskRelayMailboxClientTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/taskrelay/TaskRelayApprovalPolicyTest.kt`

- [ ] **Step 1: Write failing tests for duplicate task submission, expired approval, action-digest mismatch, unknown workspace ID, and stale fencing token event**
- [ ] **Step 2: Implement encrypted task creation, atomic status polling, approve/deny/cancel calls, and seven-day client-visible cleanup semantics**
- [ ] **Step 3: Implement approval policy that combines initial task + declared risk once, but requires a new approval for a different runtime action digest**
- [ ] **Step 4: Run mailbox tests with mocked HTTP and confirm no plaintext task body enters request logs**
- [ ] **Step 5: Commit the mailbox client**

### Task 3: Add target choice and confirmation cards outside Code Hut

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/taskrelay/TaskRelayPage.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/taskrelay/TaskRelayVM.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/taskrelay/TaskRelayUiPolicy.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/taskrelay/TaskRelayUiPolicyTest.kt`

- [ ] **Step 1: Write UI policy tests for offline, awaiting-phone-approval, running, awaiting-risk-approval, interrupted, completed, failed, and cancelled presentations**
- [ ] **Step 2: Implement a compact task page with target, workspace label, relative scope, task summary, risk digest, status and Execute/Deny/Cancel actions**
- [ ] **Step 3: Add a navigation entry without moving or expanding existing Code Hut task-board content**
- [ ] **Step 4: Bind only sanitized relay state to Compose and test rotation/reload state restoration**
- [ ] **Step 5: Commit target and approval UI**

### Task 4: Connect explicit Daddy delegation and release safely

**Files:**
- Modify: `plugins/daddy-harness-bridge/manifest.json`
- Modify: `plugins/daddy-harness-bridge/main.js`
- Create: `plugins/daddy-harness-bridge/README.md`
- Test: `app/src/test/java/me/rerere/rikkahub/data/taskrelay/TaskRelayRoutingPolicyTest.kt`
- Create: `docs/testing/2026-08-25-dual-target-routing-release.md`

- [ ] **Step 1: Write routing-policy tests: explicit “手机 Harness” routes locally, explicit “电脑 Claude Code” opens the encrypted desktop-task path, vague coding requests show a target choice, and ordinary companionship creates no task**
- [ ] **Step 2: Extend the bridge prompt/tool surface so it can create only a minimal task summary and query/cancel existing task IDs; it must not read full chat/Ombre data**
- [ ] **Step 3: Package a new bridge version with migration-safe configuration and document the required Android/Relay pairing steps**
- [ ] **Step 4: Run end-to-end tests: phone offline, PC offline, one normal task, one declared high-risk task, one runtime risk denial, one crash/orphan recovery, and one cancellation**
- [ ] **Step 5: Build `:app:assembleCompanion`, verify cover-update signature/version, generate the bridge ZIP, and commit release evidence; do not push or distribute until user authorizes it**
