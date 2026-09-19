# Autonomous Social Activity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let Daddy use the existing low-frequency idle opportunity to choose one safe, user-authorized activity family (public web reading, configured forum MCP activity, or a consented Visitor Lounge visit), while exposing a real normal-chat tool for an explicit user-requested Visitor Lounge visit.

**Architecture:** Keep `IdleExploreWorker` as the sole scheduler and let `ProactiveMessageTriggerService` create one per-opportunity `AutonomousActivityToolSurface`. That surface wraps web, allowlisted MCP, and Visitor Lounge tools in one runtime family guard and writes redacted activity records. A narrow Visitor Lounge tool adapter is shared by normal chat and idle code; it never exposes endpoints or credentials. The settings model owns the explicit per-MCP-tool opt-in and the settings page makes the grant and activity history auditable.

**Tech Stack:** Kotlin, Jetpack Compose, Kotlin serialization/DataStore, Room, WorkManager, MCP SDK, Koin, JUnit.

**Spec:** `docs/superpowers/specs/2026-09-09-autonomous-social-activity-design.md`

## Global constraints

- Preserve `IdleExploreWorker` / `IdleExploreScheduler` as the one source of idle opportunities; do not add a second worker, alarm, or retry loop.
- Default all new autonomous external-activity permissions to off. Existing normal MCP enablement and normal-chat approval settings must not be changed.
- A model never receives or returns a forum password, cookie, OAuth token, Visitor Key, endpoint, QR code, CAPTCHA, or one-time code. Activity records must contain redacted, bounded summaries only.
- An idle opportunity may execute tools from only one family: `WEB`, `FORUM`, or `VISITOR_LOUNGE`. A forum family may use the existing three tool steps; a lounge launch occupies its opportunity.
- The user-selected daily idle opportunity cap (1--3) remains global across all three families. Direct user-requested chat visits do not consume it. Existing Visitor Lounge consent, cooldown, and daily cap still apply.
- An automatic MCP login is only a selected tool call over the service's existing configured/session authentication. Any request needing interactive browser/QR/password/CAPTCHA/OTP must fail once, record a safe result, and wait for the next scheduled opportunity.
- Remove the old hidden `[[VISIT_LOUNGE:friendId|topic]]` normal-chat directive path after the real tool is in place, so it cannot duplicate or fabricate a visit.

## Context and existing integration points

- `app/src/main/java/me/rerere/rikkahub/data/datastore/ProactiveMessageSetting.kt` owns idle settings and is serialized with defaults, so adding a defaulted nested setting is migration-safe for DataStore.
- `app/src/main/java/me/rerere/rikkahub/data/service/IdleExploreScheduler.kt` schedules the daily 1--3 opportunities and launches `ProactiveMessageTriggerService` with `EXTRA_IDLE_EXPLORE_TRIGGER`.
- `app/src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt` currently exposes read-only web tools through `buildIdleExploreTools` and already limits idle runs to three tool steps and bounds tool output.
- `app/src/main/java/me/rerere/rikkahub/data/lounge/VisitorLoungeVisitCoordinator.kt` already enforces a single outgoing visit, credentials, proactive consent/cooldown/daily limits, and completion report cards.
- `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt` exposes only enabled discovered tools and performs calls using the stored MCP connection/authentication state.
- `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt` is at Room version 30; adding activity records requires an explicit 30-to-31 migration.

---

### Task 1: Add a default-off autonomous permission model and pure policy tests

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/ProactiveMessageSetting.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/service/AutonomousActivityPolicy.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/datastore/ProactiveMessageSettingTest.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/data/service/AutonomousActivityPolicyTest.kt`

- [ ] **Step 1: Write failing setting/default tests.**

  Add tests that decode legacy JSON (`{"enabled":true}`) and assert autonomous activity remains disabled with an empty allowlist. Add tests that duplicated/blank `serverId + toolName` entries normalize to unique, nonblank keys and that a configured normal MCP tool is not autonomous merely because it is normally enabled.

  ```kotlin
  @Test fun `legacy settings leave autonomous external activity disabled`() {
      val setting = Json.decodeFromString<ProactiveMessageSetting>("{\"enabled\":true}")
      assertFalse(setting.autonomousActivity.enabled)
      assertTrue(setting.autonomousActivity.allowedMcpTools.isEmpty())
  }
  ```

- [ ] **Step 2: Run the focused tests and confirm they fail for the missing model/policy.**

  Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.datastore.ProactiveMessageSettingTest" --tests "me.rerere.rikkahub.data.service.AutonomousActivityPolicyTest"`

  Expected: compilation/test failure mentioning `autonomousActivity` or the missing policy classes.

- [ ] **Step 3: Implement the serializable settings and immutable permission key.**

  In `ProactiveMessageSetting.kt`, add a defaulted `autonomousActivity: AutonomousActivitySetting = AutonomousActivitySetting()`. Define `@Serializable` `AutonomousActivitySetting(enabled: Boolean = false, allowedMcpTools: List<AutonomousMcpToolPermission> = emptyList())` and `AutonomousMcpToolPermission(serverId: String, toolName: String)`. Add helpers that trim values, remove invalid/duplicate keys, and test membership using both server UUID string and original MCP tool name. Keep the setting independent of `McpTool.enable` and `McpTool.needsApproval`.

- [ ] **Step 4: Implement the family guard in a pure, unit-testable class.**

  In `AutonomousActivityPolicy.kt`, define `enum class AutonomousActivityFamily { WEB, FORUM, VISITOR_LOUNGE }` and `AutonomousActivityFamilyGuard`. `tryClaim(family)` returns `true` for the first claimed family and later only for the same family; it returns `false` for another family. Add `AutonomousActivityPolicy` helpers that decide whether a discovered `(serverId, toolName)` is selectable/executable only when the master switch and normalized allowlist both permit it.

- [ ] **Step 5: Make the tests pass.**

  Cover: default-off legacy decode; blank/duplicate permissions; an unselected MCP tool rejected; a selected enabled tool accepted; master switch off rejects it; same-family multi-step allowed; cross-family second call rejected; and no family claim for an idle opportunity that chooses no tool.

- [ ] **Step 6: Run focused verification.**

  Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.datastore.ProactiveMessageSettingTest" --tests "me.rerere.rikkahub.data.service.AutonomousActivityPolicyTest"`

- [ ] **Step 7: Commit.**

  ```bash
  git add app/src/main/java/me/rerere/rikkahub/data/datastore/ProactiveMessageSetting.kt app/src/main/java/me/rerere/rikkahub/data/service/AutonomousActivityPolicy.kt app/src/test/java/me/rerere/rikkahub/data/datastore/ProactiveMessageSettingTest.kt app/src/test/java/me/rerere/rikkahub/data/service/AutonomousActivityPolicyTest.kt
  git commit -m "feat: add autonomous activity permission policy"
  ```

### Task 2: Persist bounded, redacted idle activity records with a Room migration

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/entity/VisitorLoungeEntities.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/dao/VisitorLoungeDao.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_30_31.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/service/AutonomousActivityRepository.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/data/service/AutonomousActivityRepositoryTest.kt`

- [ ] **Step 1: Write failing redaction/bounding mapper tests.**

  Use an in-memory/fake record store behind the repository, mirroring `VisitorLoungeRecordStore` tests. Assert that raw JSON arguments, `Bearer token-value`, URL query secrets, and values supplied as credential-like text do not survive into the persisted/displayed summary; assert newest-first retention keeps at most 50 records.

- [ ] **Step 2: Run the repository tests to establish failure.**

  Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.service.AutonomousActivityRepositoryTest"`

- [ ] **Step 3: Add Room entity, DAO operations, and migration 30-to-31.**

  Add `AutonomousActivityEntity` to the existing entity file with table name `autonomous_activity_records`, fields `id`, `family`, nullable `server_id`, nullable `tool_name`, `status`, `summary`, and `created_at`. Index `created_at` and `(family, created_at)`. Add DAO APIs to observe the newest 50, insert, and delete rows not in the newest 50. Add `Migration_30_31` with matching `CREATE TABLE` and indexes; register it in `AppDatabase`, add the entity/DAO, advance version to 31, and expose the DAO from `DataSourceModule`.

- [ ] **Step 4: Implement a narrow repository that cannot accept raw arguments.**

  `AutonomousActivityRepository.recordAttempt(...)` accepts only family, optional display-safe server/tool labels, status, and a summary. Normalize enum/status values, apply the existing lounge redactor where appropriate plus token-pattern redaction, trim summary to 300 characters, and call the DAO trim query after each insert. It must not have an API parameter for MCP JSON args or credential values.

- [ ] **Step 5: Make tests pass and add migration schema verification.**

  Add a Room migration test under `app/src/androidTest` if the project test fixtures already support migration helpers; otherwise keep the pure repository test and verify the explicit SQL statement matches the entity annotations through a `:app:assembleDebug` compile. Do not use destructive-database fallback.

- [ ] **Step 6: Verify.**

  Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.service.AutonomousActivityRepositoryTest"`

  Run: `./gradlew :app:assembleDebug`

- [ ] **Step 7: Commit.**

  ```bash
  git add app/src/main/java/me/rerere/rikkahub/data/db app/src/main/java/me/rerere/rikkahub/data/service/AutonomousActivityRepository.kt app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt app/src/test/java/me/rerere/rikkahub/data/service/AutonomousActivityRepositoryTest.kt app/src/androidTest
  git commit -m "feat: persist autonomous activity history"
  ```

### Task 3: Expose a real, narrow Visitor Lounge native tool for normal chat

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/lounge/VisitorLoungeToolGateway.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/VisitorLoungeTools.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolSurfaceBuilder.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/data/ai/tools/VisitorLoungeToolsTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/lounge/VisitorLoungeProactiveTest.kt`

- [ ] **Step 1: Write failing tool-surface tests.**

  Test that a saved friend causes exactly one tool named `visit_visitor_lounge` to be exposed; it accepts only `friend_id` and `topic`; its description says it requires an explicit user request; and its schema/returned output never includes an endpoint, `Visitor Key`, `Authorization`, or token. Test `Started`, busy, missing friend/credential, and policy rejection output as truthful JSON status rather than a false completion claim.

- [ ] **Step 2: Run the focused tool tests and confirm they fail.**

  Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.tools.VisitorLoungeToolsTest"`

- [ ] **Step 3: Introduce a testable gateway around the existing coordinator.**

  `VisitorLoungeToolGateway` owns the mapping from `VisitorLoungeStartResult` to a small public result DTO. It lists only saved public friend ids/display names, starts manual or proactive visits through `VisitorLoungeVisitCoordinator`, and preserves all current coordinator checks. It has no secret-store or raw endpoint method in its public interface.

- [ ] **Step 4: Implement `VisitorLoungeTools`.**

  Build the direct native `Tool` only when at least one saved friend is present. Use `InputSchema.Obj` for `friend_id` and bounded `topic`; validate friend ID against the gateway's current saved friends; return `started`, `visit_id`, `state`, and an honest Chinese message. It must invoke `startManual(sourceConversationId, ...)`, so the existing final report-card path remains authoritative. Mark the tool as normal chat available (not an idle-only tool) and do not embed a friend endpoint/key in the tool description.

- [ ] **Step 5: Wire the tool into all normal ChatService and ToolSurfaceBuilder paths.**

  Register gateway/tool singletons in `AppModule`. Add `VisitorLoungeTools` beside `PcBridgeTaskTools` in the direct `ChatService` tool list and `ToolSurfaceBuilder`. Pass `ToolInvocationContext.callerConversationId` into the tool factory so reports return to the correct conversation.

- [ ] **Step 6: Delete the obsolete hidden marker route.**

  Remove `visitorLoungeVisitCoordinator.proactivePrompt()` injection, `VisitorLoungeProactiveDirectiveParser.consume(...)`, marker stripping, and `startProactive` launched from `ChatService.onSuccess`. Retain existing coordinator/UI manual-visit support and proactive policy classes for idle use. Update tests so no normal-chat behavior relies on `[[VISIT_LOUNGE:friendId|topic]]`.

- [ ] **Step 7: Verify.**

  Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.tools.VisitorLoungeToolsTest" --tests "me.rerere.rikkahub.data.lounge.VisitorLoungeProactiveTest"`

- [ ] **Step 8: Commit.**

  ```bash
  git add app/src/main/java/me/rerere/rikkahub/data/lounge/VisitorLoungeToolGateway.kt app/src/main/java/me/rerere/rikkahub/data/ai/tools/VisitorLoungeTools.kt app/src/main/java/me/rerere/rikkahub/di/AppModule.kt app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolSurfaceBuilder.kt app/src/test/java/me/rerere/rikkahub/data/ai/tools/VisitorLoungeToolsTest.kt app/src/test/java/me/rerere/rikkahub/data/lounge/VisitorLoungeProactiveTest.kt
  git commit -m "feat: expose visitor lounge chat tool"
  ```

### Task 4: Build the guarded autonomous idle tool surface

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/service/AutonomousActivityToolSurface.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/data/service/AutonomousActivityToolSurfaceTest.kt`

- [ ] **Step 1: Write failing surface tests with fake MCP and lounge gateways.**

  Cover these cases:

  - unselected MCP tools are absent even when normally enabled;
  - selected but disconnected/disappeared tools are absent from the model surface and produce one `UNAVAILABLE` record when a previously selectable action is attempted through the broker;
  - selected MCP tools preserve their original schema but are `needsApproval = false` only in this user-authorized idle surface;
  - all web tools are wrapped as `WEB`, selected MCP tools as `FORUM`, and proactive lounge tool as `VISITOR_LOUNGE`;
  - after the first web call, a forum or lounge call returns a safe declined result without executing its backing action; same-family forum calls can execute up to the outer three-step limit;
  - no proactive lounge tool appears without an eligible consented friend, and a lounge start returns an honest queued/busy/rate-limited state.

- [ ] **Step 2: Run the focused test to establish failure.**

  Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.service.AutonomousActivityToolSurfaceTest"`

- [ ] **Step 3: Implement an opportunity-scoped surface builder.**

  `AutonomousActivityToolSurface` receives the already-built read-only web tools, `McpManager`, `VisitorLoungeToolGateway`, `AutonomousActivityRepository`, and settings. It creates a fresh `AutonomousActivityFamilyGuard` per idle generation. It wraps each `Tool.copy(execute = ...)` so the guard is claimed before invoking the underlying action, then writes one bounded/redacted activity record for every attempted action. Do not log the input `JsonElement`.

- [ ] **Step 4: Restrict MCP candidates precisely.**

  Pass the idle target `Assistant` into the surface builder and enumerate `mcpManager.getAllAvailableTools(serverIds = assistant.mcpServers)`. Retain a pair only if the serialized server id/tool original name is in the normalized autonomous allowlist. Build names using `ToolNaming.buildMcpToolName`, preserve `inputSchema`, and call `mcpManager.callTool(serverId, tool.name, input.jsonObject)`. Only expose selected tools that are currently available to that assistant; never connect a new server or override its configured authentication.

- [ ] **Step 5: Add proactive lounge candidate without weakening consent.**

  Build one idle-only `visit_visitor_lounge_proactive` tool only when the gateway returns currently policy-eligible, consented saved friends. Its input is friend id/topic only; it calls `startProactive` and returns a truthful start/rejection response. A launch claims `VISITOR_LOUNGE` and records it even though final outcome is reported by the existing visit record/report card.

- [ ] **Step 6: Replace `buildIdleExploreTools` integration.**

  In `ProactiveMessageTriggerService`, build exactly one `AutonomousActivityToolSurface` before constructing the idle system prompt, using the current `assistant` and current read-only web candidates (`createSearchTools` plus existing read-only plugin filtering). Reuse that same surface's `tools` for generation and its availability descriptor for the prompt; do not recreate the surface/guard later. Do not modify `buildTools` for non-idle proactive messages. Keep `rawToolTokenLimit` and `maxToolSteps = 3`; update no-tools log to say no authorized idle activity tool is available rather than falsely saying only web is missing.

- [ ] **Step 7: Verify.**

  Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.service.AutonomousActivityPolicyTest" --tests "me.rerere.rikkahub.data.service.AutonomousActivityToolSurfaceTest"`

- [ ] **Step 8: Commit.**

  ```bash
  git add app/src/main/java/me/rerere/rikkahub/data/service/AutonomousActivityToolSurface.kt app/src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt app/src/main/java/me/rerere/rikkahub/di/AppModule.kt app/src/test/java/me/rerere/rikkahub/data/service/AutonomousActivityToolSurfaceTest.kt
  git commit -m "feat: add guarded idle social activity tools"
  ```

### Task 5: Update idle prompts and output handling without exposing internal mechanics

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/service/ProactiveWakeMessageTest.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/data/service/AutonomousIdlePromptTest.kt`

- [ ] **Step 1: Write failing prompt tests.**

  Assert idle prompt text clearly permits three families only when the corresponding tool is present, says it may choose no action, instructs it not to mix families, says automatic forum actions may include the user-selected post/comment/reaction/login tool, and explicitly forbids requesting/revealing credentials or inventing a successful action. Assert direct/ordinary proactive prompts do not gain autonomous-external-action permission.

- [ ] **Step 2: Run prompt tests.**

  Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.service.ProactiveWakeMessageTest" --tests "me.rerere.rikkahub.data.service.AutonomousIdlePromptTest"`

- [ ] **Step 3: Parameterize the idle system prompt from the surface availability.**

  Return an `AutonomousActivityToolAvailability` descriptor from the one already-created surface and pass it into `buildSystemPrompt`/`buildProactiveWakeMessage` for idle only, before assembling `messages`. Replace the phrase “后台只读公开网页” with a user-authorized activity prompt that still defaults to public reading, conditionally mentions forum and lounge, and includes the no-action `[PASS]` path. Never display internal scheduling, token, MCP credential, endpoint, or raw tool output in user-facing text.

- [ ] **Step 4: Preserve quiet behavior and failure truthfulness.**

  Keep current `[PASS]` suppression and only append a normal proactive chat message when there is a worthwhile, non-sensitive result. A tool failure must not be paraphrased as a completed visit/post. The separate activity record is the audit trail when Daddy remains quiet.

- [ ] **Step 5: Verify.**

  Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.service.ProactiveWakeMessageTest" --tests "me.rerere.rikkahub.data.service.AutonomousIdlePromptTest" --tests "me.rerere.rikkahub.data.service.IdleExplorePolicyTest"`

- [ ] **Step 6: Commit.**

  ```bash
  git add app/src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt app/src/test/java/me/rerere/rikkahub/data/service/ProactiveWakeMessageTest.kt app/src/test/java/me/rerere/rikkahub/data/service/AutonomousIdlePromptTest.kt
  git commit -m "feat: describe authorized idle activity choices"
  ```

### Task 6: Add transparent permission controls and activity history to the settings page

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProactiveMessagePage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt` only if a read-only discovery snapshot API is needed
- Modify: `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/data/service/AutonomousActivitySelectionTest.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/setting/ManualToolPickerInstrumentedTest.kt` or create `AutonomousActivitySettingInstrumentedTest.kt`

- [ ] **Step 1: Write failing pure selection tests and, where feasible, one Compose instrumentation assertion.**

  Test that disabling a server/tool in normal MCP configuration does not add it to idle choices, that an allowlist item for a disappeared tool remains stored but is visibly unavailable and cannot be exposed, and that toggling a displayed permission preserves unrelated allowlist entries. Instrumentation should verify the master switch dialog and a visible selected-tool row without requiring a live MCP endpoint.

- [ ] **Step 2: Add master switch with explicit informed consent.**

  Under the existing “空闲时自己找点事” card, add `允许 Daddy 空闲时自动对外活动`. Turning it on opens `RiskConfirmDialog` explaining it may automatically post, comment, react, or invoke a selected service login with that service's already configured session; it cannot solve CAPTCHA/OTP or request/reveal credentials. Cancel keeps it off. Turning it off immediately prevents external forum/lounge tools at the next opportunity but retains the user's selected allowlist for later re-enable.

- [ ] **Step 3: Render per-server/per-tool explicit opt-ins.**

  Inject a read-only `McpManager` snapshot and enumerate currently enabled/discovered MCP tools grouped by server name. Add a checkbox/switch for each tool; update only `AutonomousActivitySetting.allowedMcpTools` using server UUID string plus original tool name. Keep normal MCP enable and normal tool `needsApproval` labels informational; do not mutate them. Show selected-but-unavailable entries as disabled “当前不可用，等待 MCP 重新发现” rows rather than silently deleting user intent.

- [ ] **Step 4: Render the latest 50 redacted activity records.**

  Collect `AutonomousActivityRepository.observeRecent()` with lifecycle awareness and show family, safe target/action label, timestamp, terminal status, and bounded summary. Do not expose raw args, tool JSON, endpoint, or secrets. Empty state should explain that the log starts after the feature is enabled and an idle action is attempted.

- [ ] **Step 5: Retain current scheduling controls.**

  The existing idle master switch and 1--3 daily slider continue to control the shared opportunity budget. Make the new permission section visible only when idle exploration is enabled; the external master switch alone must never schedule work. Calling `IdleExploreScheduler.sync` remains tied to the existing idle toggle and daily count changes.

- [ ] **Step 6: Verify.**

  Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.service.AutonomousActivitySelectionTest"`

  Run (when an emulator/device is available): `./gradlew :app:connectedDebugAndroidTest --tests "*AutonomousActivitySettingInstrumentedTest"`

- [ ] **Step 7: Commit.**

  ```bash
  git add app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProactiveMessagePage.kt app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt app/src/main/java/me/rerere/rikkahub/di/AppModule.kt app/src/test/java/me/rerere/rikkahub/data/service/AutonomousActivitySelectionTest.kt app/src/androidTest
  git commit -m "feat: add autonomous activity permissions UI"
  ```

### Task 7: Complete cross-feature regression coverage and build the companion artifact

**Files:**
- Modify as needed only for regressions found in previous tasks
- Modify: `app/build.gradle.kts` only if a version increment is required by the existing companion release convention
- Create/modify: `docs/superpowers/plans/2026-09-09-autonomous-social-activity.md` to record only verified test limitations

- [ ] **Step 1: Run focused regression tests.**

  Run:

  ```bash
  ./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.datastore.ProactiveMessageSettingTest" --tests "me.rerere.rikkahub.data.service.IdleExplorePolicyTest" --tests "me.rerere.rikkahub.data.service.AutonomousActivityPolicyTest" --tests "me.rerere.rikkahub.data.service.AutonomousActivityToolSurfaceTest" --tests "me.rerere.rikkahub.data.ai.tools.VisitorLoungeToolsTest" --tests "me.rerere.rikkahub.data.lounge.VisitorLoungeProactiveTest" --tests "me.rerere.rikkahub.data.lounge.VisitorLoungeRepositoryTest"
  ```

- [ ] **Step 2: Run the available application unit suite and distinguish environment failures.**

  Run: `./gradlew :app:testDebugUnitTest`

  If the known Gradle worker-executor bootstrap problem recurs before test execution, capture the exact failure and separately run the focused tests that do execute. Do not describe the complete app suite as passing unless Gradle reports it passed.

- [ ] **Step 3: Build and inspect the companion APK.**

  Run the project’s established offline companion build command. Verify with `aapt dump badging` or the repository's equivalent that the arm64 APK has the expected package, incremented version code/version name, and companion artifact name. Install only after the user approves an overwrite of an existing app.

- [ ] **Step 4: Copy only the verified arm64 artifact.**

  Copy the validated APK to `D:\Daddy-安装包` with a filename that identifies the autonomous-social/activity release. Do not delete old APKs. Report the exact source and destination paths plus test/build evidence.

- [ ] **Step 5: Review the final diff before final commit.**

  Run:

  ```bash
  git diff --check
  git status --short
  git diff --stat HEAD
  ```

  Confirm there are no source changes outside this worktree's intended feature and no secrets in the diff (`rg -n "Bearer |Visitor Key|accessToken|refreshToken|clientSecret"` should only match existing type names/comments and never concrete values).

- [ ] **Step 6: Commit the final validated changes.**

  ```bash
  git add app docs
  git commit -m "feat: let Daddy choose authorized idle social activity"
  ```

## Acceptance checklist

- [ ] With idle exploration off, no idle activity is scheduled and the new external permission cannot start work.
- [ ] With idle exploration on but autonomous activity off, behavior remains read-only public web exploration only.
- [ ] With autonomous activity on, an idle opportunity can see only web tools, explicitly selected currently available MCP tools, and consented policy-eligible lounge friends.
- [ ] The runtime blocks mixed web/forum/lounge activity in one opportunity; selected forum tool chains may use up to three steps.
- [ ] Direct normal chat has `visit_visitor_lounge`; it can start an explicit saved-friend visit and later produces the established report card without exposing the endpoint/key.
- [ ] Automatic selected forum tool actions may publish/comment/react/login only through the preconfigured MCP session; interactive auth failures are recorded once and never prompt the model for secrets.
- [ ] Activity history is bounded, redacted, timestamped, and visible in settings.
- [ ] Manual Visitor Lounge UI, existing PC bridge tools, Night Watch, normal proactive messages, and existing MCP configurations remain intact.
