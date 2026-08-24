# Harness 桥接任务看板 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用真实 Harness 收件箱事件替代代码小屋的旧手动任务表单，只展示仍活跃的桥接任务。

**Architecture:** `com.daddy.harness-bridge` 继续作为 Daddy 聊天的唯一派单器，并在每个任务消息前写入稳定任务头。原生 Code Hut 只读调用 127.0.0.1:3080 的会话 API，解析同一收件箱的事件；若会话发现或确认事件未被实测证实，则回退到“打开收件箱工作台”，不伪造状态。

**Tech Stack:** Kotlin、Compose、OkHttp、Koin、JUnit、OrangeChat JavaScript 插件、DeepSeek Harness 本机 RPC。

**Spec:** `docs/superpowers/specs/2026-08-24-harness-bridge-task-board-design.md`

## Global Constraints

- 基线是 `0a832c5`；保留 companion 包名、签名和现有 3080 Harness 生命周期。
- 不改日常聊天模型、助手、Ombre、世界书、上下文或风险门。
- 不做 WebView DOM 自动化；只调用实测的 Harness RPC。
- 任务不携带人设、私人记忆、密钥、完整聊天记录或无关上下文。
- 单一 `📥 Daddy收件箱` 会话串行执行；删除、覆盖、安装、登录、推送等仍由 Harness 风险门确认。
- 所有界面错误均经 `redactCodeHutUiText` 脱敏；不得显示命令、令牌、环境变量或 API Key。

---

### Task 1: 记录 Harness 会话 RPC 的真实只读契约

**Files:**
- Create: `docs/testing/2026-08-24-harness-session-rpc-smoke.md`
- Create: `app/src/test/resources/harness/session-list.json`
- Create: `app/src/test/resources/harness/session-history.json`

**Interfaces:** 产出一份已脱敏的 `session.list`、`session.history` 真实响应样本；后续解析器只能依赖该样本中存在的字段。

- [ ] **Step 1: 在手机 Termux 执行只读列会话请求**

```bash
curl -sS -X POST http://127.0.0.1:3080/api/session.list \
  -H 'Content-Type: application/json' \
  -d '{"type":"client-request","rpcId":"daddy-rpc-probe-list","method":"session.list","payload":{}}'
```

记录 HTTP 状态、响应顶层字段，以及标题为 `📥 Daddy收件箱` 的会话 ID；输出前替换会话 ID、路径、令牌和任何密钥为 `[REDACTED]`。

- [ ] **Step 2: 读取已知收件箱历史**

```bash
curl -sS -X POST http://127.0.0.1:3080/api/session.history \
  -H 'Content-Type: application/json' \
  -d '{"type":"client-request","rpcId":"daddy-rpc-probe-history","method":"session.history","payload":{"sessionId":"<收件箱ID>"}}'
```

保存只包含任务头、`user/message`、`turn/start`、`assistant/message`、`turn/end` 类型与必要字段的脱敏 JSON fixture。若 `session.list` 不存在或字段不同，记录实际方法与字段，停止本任务并更新规格；禁止猜测。

- [ ] **Step 3: 验证确认事件（若当前版本支持）**

从完整工作台发起一个会触发既有风险门的安全测试，例如“创建前先尝试覆盖已存在的测试文件”，只观察事件类型与确认字段，不批准操作。把事件字段写入 smoke 文档；若事件流没有确认事件，原生看板不实现“等待确认”分类。

- [ ] **Step 4: 审核 fixture**

确认 fixture 没有真实 session ID、绝对路径、Authorization、token、cookie、API Key 或用户聊天内容。

- [ ] **Step 5: Commit**

```powershell
git add docs/testing/2026-08-24-harness-session-rpc-smoke.md app/src/test/resources/harness
git commit -m "test: capture redacted Harness session RPC contract"
```

### Task 2: 版本化 Harness 工作台桥插件

**Files:**
- Create: `plugins/daddy-harness-bridge/main.js`
- Create: `plugins/daddy-harness-bridge/manifest.json`
- Create: `plugins/daddy-harness-bridge/README.md`
- Test: `plugins/daddy-harness-bridge/main.test.js`

**Interfaces:** 保留 `relay_to_harness(requirement)` 与 `check_harness_status()`；新增返回字段 `taskId`、`tasks`，不改变既有调用名称。

- [ ] **Step 1: 写失败测试**

```javascript
assert.match(buildTaskEnvelope('修复 README', '1700000000000-a'), /task_id: daddy-1700000000000-a/)
assert.equal(parseTasks(historyFixture).active[0].status, 'queued')
assert.equal(parseTasks(historyFixture).finished.length, 1)
```

另加断言：任务简报中若包含 `api_key`、`Authorization:`、`Ombre`、`世界书` 或 `完整聊天记录` 字样，`sanitizeRequirement` 返回错误而非发送。

- [ ] **Step 2: 运行测试确认失败**

```powershell
node plugins/daddy-harness-bridge/main.test.js
```

预期：插件目录或导出函数不存在。

- [ ] **Step 3: 实现 v1.1 插件**

实现以下稳定纯函数：

```javascript
function taskId(now, random) { return 'daddy-' + now + '-' + random }
function buildTaskEnvelope(id, title, createdAt, brief) {
  return '【Daddy任务】\n' +
    'task_id: ' + id + '\n' +
    'created_at: ' + createdAt + '\n' +
    'title: ' + title.slice(0, 80) + '\n' +
    '【任务简报】\n' + brief
}
```

`relay_to_harness` 仍以 `session.prompt({ mode: "queue" })` 写固定收件箱，返回 `{ success, taskId, sessionId, status: "queued" }`。`check_harness_status` 解析 Task 1 fixture 中实测事件：只把未结束任务列为 `queued`/`running`/已证实的 `awaiting_confirmation`；最终结束任务仅在工具结果中给出短摘要。manifest 的提示词改为“只交付任务必需信息”，禁止注入关系设定、Ombre、世界书、密钥和无关聊天。

- [ ] **Step 4: 运行测试并打包插件**

```powershell
node plugins/daddy-harness-bridge/main.test.js
Compress-Archive -Path plugins/daddy-harness-bridge/* -DestinationPath tmp/com.daddy.harness-bridge-v1.1.zip -Force
```

预期：测试通过；zip 根目录包含 `main.js`、`manifest.json`、`README.md`，不含测试、密钥或 `.git`。

- [ ] **Step 5: Commit**

```powershell
git add plugins/daddy-harness-bridge
git commit -m "feat: add versioned Harness inbox bridge"
```

### Task 3: 构建只读会话客户端与状态解析器

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/codehut/HarnessInboxClient.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/codehut/HarnessInboxParser.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/codehut/HarnessInboxParserTest.kt`

**Interfaces:** `suspend fun loadActiveTasks(): HarnessInboxLoadResult`；结果只允许 `Available(tasks)`, `Unavailable(message)`, `Failure(message)`。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun queuedHeaderBeforeTurnStartIsQueued() {
    assertEquals(HarnessInboxTaskState.QUEUED, parser.parse(history).single().state)
}

@Test fun endedTaskIsNotReturnedAsActive() {
    assertTrue(parser.parse(historyWithTurnEnd).isEmpty())
}

@Test fun unknownEventNeverBecomesAwaitingConfirmation() {
    assertEquals(HarnessInboxTaskState.UNKNOWN, parser.parse(historyWithUnknown).single().state)
}
```

- [ ] **Step 2: 运行失败测试**

```powershell
$env:GRADLE_USER_HOME = 'D:\DaddyGradleHome'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests me.rerere.rikkahub.data.codehut.HarnessInboxParserTest
```

预期：缺少 client/parser 类型而失败。

- [ ] **Step 3: 实现 loopback-only client**

客户端只接受 `http://127.0.0.1:3080`，以 Task 1 已证实的方法和 DTO 发 `client-request`。先列会话、精确匹配 `📥 Daddy收件箱`，再取 `session.history`。请求设置 5 秒连接/读取超时；任何网络或解析异常都返回 `Failure("暂时无法读取任务状态，请在工作台查看。")`，原始异常先经过 `redactCodeHutUiText`。

解析器仅信任 `【Daddy任务】` 头和实测事件序列：`turn/start` 为运行中，实测确认事件才是等待确认，`turn/end`/实测失败事件会从活跃列表剔除。最多返回 20 条活跃任务，标题 80 字、状态摘要 160 字。

- [ ] **Step 4: 重跑测试**

运行 Step 2。另加 parser fixture 测试：没有收件箱、坏 JSON、未知事件、多个串行任务、脱敏错误均通过。

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/codehut/HarnessInboxClient.kt app/src/main/java/me/rerere/rikkahub/data/codehut/HarnessInboxParser.kt app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt app/src/test/java/me/rerere/rikkahub/data/codehut/HarnessInboxParserTest.kt app/src/test/resources/harness
git commit -m "feat: read active Harness inbox tasks"
```

### Task 4: 用真实任务看板替换代码小屋表单

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/codehut/CodeHutVM.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/codehut/CodeHutPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/codehut/CodeHutShellPolicy.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/codehut/CodeHutShellPolicyTest.kt`

**Interfaces:** `CodeHutUiState.activeInboxTasks: List<HarnessInboxTask>` 与 `inboxNotice: String?`；不再保留 `draft`、`activeTask`、`prepareTaskForWorkbench` 或复制任务行为。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun environmentOnlyShowsServiceAndPermission() {
    assertEquals(listOf("服务", "权限"), presentation.items.map { it.title })
}

@Test fun completedAndFailedTasksAreAbsentFromBoard() {
    assertEquals(listOf("queued", "running"), board.visibleTasks.map { it.state.wireName })
}
```

- [ ] **Step 2: 运行失败测试**

```powershell
$env:GRADLE_USER_HOME = 'D:\DaddyGradleHome'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests me.rerere.rikkahub.ui.pages.codehut.CodeHutShellPolicyTest
```

预期：旧环境项目和旧任务表单断言仍存在，新的断言失败。

- [ ] **Step 3: 最小页面实现**

移除 `TaskCard`、`TaskResultCard`、所有 `OutlinedTextField`、剪贴板任务复制和“准备任务”文案。新增 `InboxBoardCard`：按“待执行”“执行中”“等待你确认”显示任务；无活跃项显示“目前没有进行中的工作”。加载失败显示脱敏提示与“刷新”。保留 Workbench 卡的“打开工作台 / Harness 设置 / 刷新”。环境呈现改为只有服务与权限两项；删除从 Daddy 设置推断模型、GitHub、Skills、MCP 的逻辑。

`CodeHutVM` 在初始加载和用户刷新时调用 `loadActiveTasks()`；不可用时清空看板，不保留陈旧的“执行中”状态。

- [ ] **Step 4: 重跑 UI policy 测试与构建**

```powershell
$env:GRADLE_USER_HOME = 'D:\DaddyGradleHome'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests me.rerere.rikkahub.ui.pages.codehut.CodeHutShellPolicyTest --tests me.rerere.rikkahub.data.codehut.HarnessInboxParserTest
.\gradlew.bat --no-daemon :app:compileCompanionDebugKotlin
```

预期：测试通过，Kotlin 编译成功。

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/me/rerere/rikkahub/ui/pages/codehut app/src/test/java/me/rerere/rikkahub/ui/pages/codehut/CodeHutShellPolicyTest.kt
git commit -m "feat: show active Harness inbox tasks in Code Hut"
```

### Task 5: 手机 Harness 附件入口探针（独立，不 fork 上游）

**Files:**
- Create: `docs/testing/2026-08-24-harness-mobile-attachment-probe.md`

**Interfaces:** 产出“图片正式附件 API”和“普通文件受控上传 API”各自是否可用的证据；本任务不修改 Daddy 页面或上游 Harness。

- [ ] **Step 1: 验证现有 Android WebView 文件选择**

在手机工作台打开一个已知带 `input[type=file]` 的最小本地测试页或 Harness 已有图片拖放入口，确认 `HarnessWebViewPage.onShowFileChooser` 调起系统选择器，并记录 JPEG/PNG/WebP 的成功/拒绝结果。

- [ ] **Step 2: 验证视觉模型真实接收**

选择一张无私人信息的测试图；发送后在 Harness 会话事件中确认存在附件引用，并让当前声明视觉能力的模型描述一个仅图中可见的测试标记。文本模型必须收到“不支持视觉输入”错误，不得声称看见。

- [ ] **Step 3: 审核普通文件链路**

检查已安装 Harness 的插件目录及 API，寻找已文档化的“上传至当前会话工作区”接口。对候选插件执行源码审阅：限制大小、相对路径、重名不覆盖、无自动执行、无外网上传。未同时满足即不安装。

- [ ] **Step 4: 记录结论并分支**

若图片接口和安全文件接口都实测可用，另起“Harness 手机附件插件”实现计划；若仅图片可用，先只交付图片按钮；若均不可用，保留现状并报告缺口。不得以 DOM 注入或把文件绝对路径塞进提示词作为替代。

- [ ] **Step 5: Commit**

```powershell
git add docs/testing/2026-08-24-harness-mobile-attachment-probe.md
git commit -m "test: document Harness mobile attachment probe"
```

## Plan Self-Review

- 任务 1 先把会话与确认事件变成可验证证据，避免 UI 猜测。
- 任务 2 保持 Daddy 派单工具兼容并隔离私人上下文。
- 任务 3 和 4 将真实事件落到原生看板，并删除旧手动表单与误导环境卡。
- 任务 5 将视觉图片和普通文件分开验证；附件插件在有正式、安全接口前不实施。
