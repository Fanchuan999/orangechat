# Code Hut Single-Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** Deliver one isolated Code Hut that always executes through the existing DeepSeek Harness and can select compatible models such as OpenCode Go MiniMax M3.

**Architecture:** Existing Daddy providers remain the only credential source. A localhost bridge translates the protocol path required by the selected model and injects its existing provider key only in memory. Code Hut's task data and default model are independent from daily conversations; DeepSeek Harness remains the sole terminal/tool executor and retains the existing risk gate.

**Tech Stack:** Kotlin, Compose, DataStore/Kotlin serialization, Koin, Ktor CIO, OkHttp MockWebServer, existing Termux/Debian Harness, JUnit.

**Spec:** \`docs/superpowers/specs/2026-08-20-code-hut-single-harness-design.md\`

## Global Constraints

- Do not alter \`Settings.providers\`, daily assistant selection, daily model switching, or daily chat generation.
- Do not create an OpenCode executor, a second visible Code Hut, or WebView DOM automation.
- API keys only reside in existing \`ProviderSetting\`; no Harness script, DSH config, Termux file, log, task or bridge file contains one.
- Work tickets contain only approved work text, explicit files, working directory and constraints. They never carry persona, injections, lorebooks, Ombre, mood/desire state, proactive state or full chat history.
- Existing risk-gate confirmation remains mandatory for delete, overwrite, bulk move and high-risk Shell.
- If no documented Harness task/session API is available, return an explicit unsupported result and link to the current Harness workbench.
- Never commit \`tmp/\`. Preserve companion package and signing compatibility.

---

### Task 1: Persist Code Hut settings without touching chat settings

**Files:**
- Create: \`app/src/main/java/me/rerere/rikkahub/data/datastore/CodeHutSetting.kt\`
- Modify: \`app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt\`
- Test: \`app/src/test/java/me/rerere/rikkahub/data/datastore/CodeHutSettingTest.kt\`

**Interfaces:** produces \`CodeHutSetting\`, \`WorkExecutor.HARNESS\`, \`WorkProtocol\`, \`WorkCapability\`, \`WorkModelBinding\`, and \`Settings.codeHutSetting\`. A binding stores only provider/model UUIDs, protocol and probe state.

- [ ] **Step 1: Write failing tests**

~~~kotlin
@Test fun freshSettingLeavesDailyChatUntouched() {
    val settings = Settings(chatModelId = chatModel, codeHutSetting = CodeHutSetting())
    assertEquals(WorkExecutor.HARNESS, settings.codeHutSetting.executor)
    assertNull(settings.codeHutSetting.defaultBindingId)
    assertEquals(chatModel, settings.chatModelId)
}
@Test fun taskOverrideDoesNotChangeDefault() {
    assertEquals(bindingA.id, CodeHutSetting(defaultBindingId = bindingA.id).defaultBindingId)
    assertEquals(bindingB.id, CodeHutTask.newTicket("inspect", bindingOverrideId = bindingB.id).bindingOverrideId)
}
~~~

- [ ] **Step 2: Run failing test**

~~~powershell
$env:GRADLE_USER_HOME = 'D:\DaddyGradleHome'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests me.rerere.rikkahub.data.datastore.CodeHutSettingTest
~~~

Expected: compilation fails because Code Hut setting types do not exist.

- [ ] **Step 3: Implement settings model and DataStore wiring**

~~~kotlin
@Serializable data class CodeHutSetting(
    val executor: WorkExecutor = WorkExecutor.HARNESS,
    val defaultBindingId: Uuid? = null,
    val bindings: List<WorkModelBinding> = emptyList(),
)
@Serializable enum class WorkProtocol {
    OPENAI_CHAT_COMPLETIONS, ANTHROPIC_MESSAGES, OPENAI_RESPONSES
}
~~~

Add \`CODE_HUT_SETTING\` to \`SettingsStore\`, decode it in \`settingsFlowRaw\`, add \`codeHutSetting\` to \`Settings\`, and encode it in \`update\`. Do not modify existing model/provider fields.

- [ ] **Step 4: Re-run test**

Run Step 2. Expected: both tests pass.

- [ ] **Step 5: Commit**

~~~powershell
git add app/src/main/java/me/rerere/rikkahub/data/datastore/CodeHutSetting.kt app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt app/src/test/java/me/rerere/rikkahub/data/datastore/CodeHutSettingTest.kt
git commit -m "feat: persist isolated Code Hut settings"
~~~

### Task 2: Reuse existing providers to construct work-model candidates

**Files:**
- Create: \`app/src/main/java/me/rerere/rikkahub/data/codehut/WorkModelPolicy.kt\`
- Test: \`app/src/test/java/me/rerere/rikkahub/data/codehut/WorkModelPolicyTest.kt\`

**Interfaces:** consumes \`ProviderSetting\` and \`Model\`; produces \`WorkModelCandidate\`, \`candidateFor\`, and \`resolveExecutableCandidates\`.

- [ ] **Step 1: Write failing mapping tests**

~~~kotlin
@Test fun m3UsesAnthropicMessages() {
    assertEquals(WorkProtocol.ANTHROPIC_MESSAGES, WorkModelPolicy.candidateFor(goProvider, m3).protocol)
}
@Test fun modelWithoutToolsIsConsultOnly() {
    assertEquals(WorkCapability.CONSULT_ONLY, WorkModelPolicy.candidateFor(provider, plainChat).capability)
}
~~~

- [ ] **Step 2: Run failing test**

~~~powershell
$env:GRADLE_USER_HOME = 'D:\DaddyGradleHome'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests me.rerere.rikkahub.data.codehut.WorkModelPolicyTest
~~~

Expected: compilation fails because \`WorkModelPolicy\` is absent.

- [ ] **Step 3: Implement protocol and capability policy**

~~~kotlin
fun candidateFor(provider: ProviderSetting, model: Model): WorkModelCandidate =
    WorkModelCandidate(
        providerId = provider.id,
        modelId = model.id,
        protocol = protocolFromProviderAndModel(provider, model),
        capability = if (ModelAbility.TOOL in model.abilities) WorkCapability.NEEDS_PROBE else WorkCapability.CONSULT_ONLY,
    )
~~~

Map DeepSeek V4 Flash/Pro and MiMo to OpenAI Chat Completions, OpenCode Go M3/M2.7 to Anthropic Messages, and configured GPT Responses models to OpenAI Responses. Disabled/blank-key providers are unavailable. Never copy a key.

- [ ] **Step 4: Re-run test**

Run Step 2. Expected: protocol, blank-key and consult-only tests pass.

- [ ] **Step 5: Commit**

~~~powershell
git add app/src/main/java/me/rerere/rikkahub/data/codehut/WorkModelPolicy.kt app/src/test/java/me/rerere/rikkahub/data/codehut/WorkModelPolicyTest.kt
git commit -m "feat: derive Code Hut model candidates"
~~~

### Task 3: Token-gated loopback credential bridge

**Files:**
- Create: \`app/src/main/java/me/rerere/rikkahub/data/codehut/CodeHutCredentialBridge.kt\`
- Modify: \`app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt\`
- Test: \`app/src/test/java/me/rerere/rikkahub/data/codehut/CodeHutCredentialBridgeTest.kt\`

**Interfaces:** produces \`BridgeLease(port, token, baseUrl)\`, \`startLease(bindingId)\`, and \`revokeLease()\`. Only the random lease token and loopback URL are handed to Harness.

- [ ] **Step 1: Write failing bridge tests**

~~~kotlin
@Test fun tokenIsRequiredBeforeProviderLookup() = runTest {
    assertEquals(401, bridge.requestWithoutToken("/providers/go/messages").code)
    assertEquals(0, upstream.requestCount)
}
@Test fun bridgeForwardsSavedKeyButNeverLogsIt() = runTest {
    val lease = bridge.startLease(m3Binding.id)
    assertEquals(200, bridge.requestWithToken(lease, "/providers/go/messages", body).code)
    assertEquals("Bearer saved-go-key", upstream.takeRequest().getHeader("Authorization"))
    assertFalse(bridge.redactedAuditLog().contains("saved-go-key"))
}
~~~

- [ ] **Step 2: Run failing test**

~~~powershell
$env:GRADLE_USER_HOME = 'D:\DaddyGradleHome'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests me.rerere.rikkahub.data.codehut.CodeHutCredentialBridgeTest
~~~

Expected: compilation fails because the bridge is missing.

- [ ] **Step 3: Implement only localhost stream forwarding**

Bind Ktor CIO to \`127.0.0.1\` and an available high port. Keep the lease token in memory; invalidate it when the job ends, Harness stops or bridge restarts. On a valid token, resolve the referenced existing provider and proxy its key only in the outgoing request.

~~~kotlin
when (binding.protocol) {
    WorkProtocol.OPENAI_CHAT_COMPLETIONS -> forward("/chat/completions")
    WorkProtocol.ANTHROPIC_MESSAGES -> forward("/messages")
    WorkProtocol.OPENAI_RESPONSES -> forward("/responses")
}
~~~

Pass request body and SSE response unchanged. Redact authorization, API-key, token and secret data from failures and audit logs.

- [ ] **Step 4: Re-run test**

Run Step 2. Expected: authentication, expiry, protocol routes, SSE and redaction tests pass.

- [ ] **Step 5: Commit**

~~~powershell
git add app/src/main/java/me/rerere/rikkahub/data/codehut/CodeHutCredentialBridge.kt app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt app/src/test/java/me/rerere/rikkahub/data/codehut/CodeHutCredentialBridgeTest.kt
git commit -m "feat: add Code Hut credential bridge"
~~~

### Task 4: Configure custom Harness providers safely

**Files:**
- Create: \`app/src/main/java/me/rerere/rikkahub/data/codehut/HarnessProviderConfig.kt\`
- Modify: \`app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt\`
- Modify: \`app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessManager.kt\`
- Test: \`app/src/test/java/me/rerere/rikkahub/data/codehut/HarnessProviderConfigTest.kt\`

**Interfaces:** produces \`HarnessProviderPatch(yaml, environment)\`; adds \`HarnessManager.configureWorkProvider(bindingId)\`.

- [ ] **Step 1: Write failing config tests**

~~~kotlin
@Test fun m3PatchUsesAnthropicAndLeaseNotProviderKey() {
    val patch = factory.create(m3Binding, lease)
    assertContains(patch.yaml, "api: anthropic-messages")
    assertContains(patch.yaml, "apiKeyEnv: DADDY_CODE_HUT_LEASE")
    assertFalse(patch.yaml.contains("saved-go-key"))
}
@Test fun v4PatchHasThinkingCompatibility() {
    val patch = factory.create(v4ProBinding, lease)
    assertContains(patch.yaml, "supportsDeveloperRole: false")
    assertContains(patch.yaml, "maxTokensField: max_tokens")
}
~~~

- [ ] **Step 2: Run failing test**

~~~powershell
$env:GRADLE_USER_HOME = 'D:\DaddyGradleHome'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests me.rerere.rikkahub.data.codehut.HarnessProviderConfigTest
~~~

Expected: compilation fails because the patch factory is missing.

- [ ] **Step 3: Implement idempotent DSH patch generation**

Write a managed \`code-hut-provider.patch.yml\` next to the existing risk-gate patch. It contains provider ID, model ID, selected protocol, bridge URL and the lease environment-variable name; never a real key. Update \`run-harness.sh\` to include both patches while preserving port 3080, watchdog and risk-gate behavior. \`configureWorkProvider\` must inspect Harness, acquire a lease, write config through \`TermuxConfigBridge\`, restart with existing lifecycle semantics and revoke on failure.

- [ ] **Step 4: Re-run test**

Run Step 2. Expected: mapping, DeepSeek compatibility, no-key and repeated-apply tests pass.

- [ ] **Step 5: Commit**

~~~powershell
git add app/src/main/java/me/rerere/rikkahub/data/codehut/HarnessProviderConfig.kt app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessScripts.kt app/src/main/java/me/rerere/rikkahub/data/sync/companion/HarnessManager.kt app/src/test/java/me/rerere/rikkahub/data/codehut/HarnessProviderConfigTest.kt
git commit -m "feat: configure Harness work providers"
~~~

### Task 5: Add isolated tickets and stable task gateway

**Files:**
- Create: \`app/src/main/java/me/rerere/rikkahub/data/codehut/CodeHutTask.kt\`
- Create: \`app/src/main/java/me/rerere/rikkahub/data/codehut/CodeHutTaskPolicy.kt\`
- Create: \`app/src/main/java/me/rerere/rikkahub/data/codehut/HarnessTaskGateway.kt\`
- Tests: \`app/src/test/java/me/rerere/rikkahub/data/codehut/CodeHutTaskPolicyTest.kt\`, \`app/src/test/java/me/rerere/rikkahub/data/codehut/HarnessTaskGatewayTest.kt\`

**Interfaces:** \`TaskTicket\` contains only approved task text, explicit files, working directory and constraints; \`HarnessGatewayResult\` is \`Success\`, \`UnsupportedApi\` or \`Failure\`.

- [ ] **Step 1: Write failing isolation tests**

~~~kotlin
@Test fun ticketRejectsDaddyContext() {
    val ticket = CodeHutTaskPolicy.createTicket("repair parser", listOf("src/Parser.kt"))
    assertEquals(listOf("src/Parser.kt"), ticket.selectedFiles)
    assertFalse(ticket.prompt.contains("Ombre"))
}
@Test fun noPublicApiReturnsExplicitFallback() = runTest {
    assertIs<HarnessGatewayResult.UnsupportedApi>(gateway.submit(ticket))
}
~~~

- [ ] **Step 2: Run failing tests**

~~~powershell
$env:GRADLE_USER_HOME = 'D:\DaddyGradleHome'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests me.rerere.rikkahub.data.codehut.CodeHutTaskPolicyTest --tests me.rerere.rikkahub.data.codehut.HarnessTaskGatewayTest
~~~

Expected: compilation fails because ticket and gateway types are missing.

- [ ] **Step 3: Implement sanitization and documented API probe**

Reject serialized \`Conversation\`, \`Assistant\`, injection, lorebook, memory, mood, desire and proactive objects. Probe only a documented Harness session/task API with a short timeout. If it exists, submit typed DTOs and cap the Daddy-facing \`TaskResultSummary\`. If absent, return \`UnsupportedApi\` and link to the existing workbench. Do not add JavaScript injection, DOM scraping or accessibility automation.

- [ ] **Step 4: Re-run tests**

Run Step 2. Expected: sanitization, cap, timeout and fallback tests pass.

- [ ] **Step 5: Commit**

~~~powershell
git add app/src/main/java/me/rerere/rikkahub/data/codehut/CodeHutTask.kt app/src/main/java/me/rerere/rikkahub/data/codehut/CodeHutTaskPolicy.kt app/src/main/java/me/rerere/rikkahub/data/codehut/HarnessTaskGateway.kt app/src/test/java/me/rerere/rikkahub/data/codehut/CodeHutTaskPolicyTest.kt app/src/test/java/me/rerere/rikkahub/data/codehut/HarnessTaskGatewayTest.kt
git commit -m "feat: add isolated Code Hut tasks"
~~~

### Task 6: Build the one native Code Hut screen and verify it

**Files:**
- Create: \`app/src/main/java/me/rerere/rikkahub/ui/pages/codehut/CodeHutVM.kt\`
- Create: \`app/src/main/java/me/rerere/rikkahub/ui/pages/codehut/CodeHutPage.kt\`
- Create: \`app/src/main/java/me/rerere/rikkahub/ui/pages/codehut/CodeHutSettingPage.kt\`
- Modify: \`app/src/main/java/me/rerere/rikkahub/RouteActivity.kt\`, \`app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawer.kt\`, \`app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt\`
- Test: \`app/src/test/java/me/rerere/rikkahub/ui/pages/codehut/CodeHutVMTest.kt\`
- Create: \`docs/superpowers/verification/2026-08-20-code-hut-harness-matrix.md\`

**Interfaces:** one \`Screen.CodeHut\`; its UI state holds default binding, temporary task override, candidates, tasks, Harness status and user notice.

- [ ] **Step 1: Write failing UI-state tests**

~~~kotlin
@Test fun taskModelOverrideLeavesDefaultAlone() = runTest {
    viewModel.selectTaskBinding(task.id, alternate.id)
    assertEquals(default.id, viewModel.state.value.defaultBindingId)
    assertEquals(alternate.id, viewModel.state.value.tasks.single().bindingOverrideId)
}
@Test fun unsupportedGatewayShowsWorkbenchAction() = runTest {
    viewModel.submit(task.id)
    assertTrue(viewModel.state.value.notice.contains("工作台"))
}
~~~

- [ ] **Step 2: Run failing test**

~~~powershell
$env:GRADLE_USER_HOME = 'D:\DaddyGradleHome'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests me.rerere.rikkahub.ui.pages.codehut.CodeHutVMTest
~~~

Expected: compilation fails because Code Hut UI is absent.

- [ ] **Step 3: Implement phone-first native UI**

Add exactly one Code Hut route, black/wine-red/pink vertical cards, default-model settings, a review/confirm task-ticket sheet from Daddy chat, per-task temporary model selection, compact result card and link to full Harness logs. The review sheet only uses selected task text/files; it does not read daily history. Never alter the currently selected daily assistant or chat model.

- [ ] **Step 4: Run focused unit tests and build**

~~~powershell
$env:GRADLE_USER_HOME = 'D:\DaddyGradleHome'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests me.rerere.rikkahub.data.datastore.CodeHutSettingTest --tests me.rerere.rikkahub.data.codehut.* --tests me.rerere.rikkahub.ui.pages.codehut.CodeHutVMTest --tests me.rerere.rikkahub.data.sync.companion.HarnessManagerPolicyTest
.\gradlew.bat --no-daemon :app:assembleCompanion
~~~

Expected: focused tests pass and the companion APK builds.

- [ ] **Step 5: Run device matrix and commit**

Using a disposable folder, record redacted results for each configured model: normal conversation, read-only Shell, file read/create, existing-file confirmation, two tool rounds and reasoning continuity. Assert overwrite/delete/bulk-move/high-risk Shell prompt via the existing Harness risk gate. Confirm no API-key prefix appears in managed Harness files.

~~~powershell
git add app/src/main/java/me/rerere/rikkahub/ui/pages/codehut app/src/main/java/me/rerere/rikkahub/RouteActivity.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawer.kt app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt app/src/test/java/me/rerere/rikkahub/ui/pages/codehut/CodeHutVMTest.kt docs/superpowers/verification/2026-08-20-code-hut-harness-matrix.md
git commit -m "feat: add native single-Harness Code Hut"
~~~

## Plan Self-Review

- Tasks 1–2 cover isolated settings and exact protocol/eligibility labels.
- Tasks 3–4 cover one stored credential, one Harness executor and every requested protocol.
- Task 5 prevents daily-context leakage and forbids DOM automation.
- Task 6 covers the one visible UI, per-task model switch and the requested device matrix.

