# Daddy / OrangeChat：给 Claude Code 的上手交接

更新：2026-09-11（Asia/Shanghai）

这是一份给后续开发者的实用交接，不是产品需求本身。先读完它、看一遍 `git status`，再决定是否编辑。

## 0. 先说结论

这是一个 Android Compose 项目，基于 OrangeChat / RikkaHub，用户把 Companion 版本当作手机上的
“Daddy”。当前大方向已经定了：

- MCP 只走**通用 MCP**；旧的内置“会客室”是重复系统，已经被移除，**不要重新加回来**。
- 用户想要的是有陪伴感、但能自己决定边界的手机 AI。自动活动只能用用户已经连好、并主动允许的 MCP。
- “Daddy 的小屋”、主动消息、生活线、Todoist MCP 都是现有产品的一部分，不是临时 demo。
- 当前工作树有一大批**未提交**但已构建过的改动。先保护它们，不要为了让状态变干净而 `reset`、`clean`、
  整体 checkout 或覆盖其他工作树。

## 1. 唯一允许继续的工作树

```text
D:\small progect\ai_chat\orangechat-minimal\.worktrees\daddy-unified-lounge-go
```

当前分支：`codex/daddy-unified-lounge-go`
当前 HEAD：`f4ccacd build: bump companion version to 2.5.52`

仓库根目录和其他 worktree 都不是这次工作的落点。开始前执行：

```powershell
Set-Location 'D:\small progect\ai_chat\orangechat-minimal\.worktrees\daddy-unified-lounge-go'
git status --short
git diff --stat
```

目前的未提交改动跨越约 47 个已跟踪文件，还有新 Room migration、测试、计划文档等未跟踪文件。
它们是正在使用的统一 MCP / 陪伴功能改动，不能当垃圾清掉。除非用户明确说要提交，不要替用户提交。

旧交接仍有背景价值，但其中的 APK 名称和“最近完成状态”已经过时：

```text
docs\superpowers\plans\2026-09-10-daddy-project-handoff.md
```

本文件以 2026-09-11 的实际状态为准。

## 2. 用户已经确认的产品决定

### 通用 MCP 和旧会客室

- 以前有一套专属“会客室”页面、Visitor Key、数据库表、工具和访问流程；它和普通 MCP 重复。
- 这些专属实现正在从本工作树删除；远程“去别人家聊天”应作为**普通 MCP 服务**来配置、授权和调用。
- 普通聊天仍可使用已经连接的 MCP。删除的是专属会客室，不是 MCP 能力本身。
- “空闲时找点事做 / 主动消息与情绪”里按 **MCP 服务** 显示，一个服务的工具作为一组授权；
  新加的 MCP 也应该自然出现在列表里。用户已同意较宽松的隐私与“允许自由活动”设置，
  但仍不能暗中创建新登录或越过服务器本身的权限。

### Daddy 的小屋

入口在：`设置 → Daddy 的小屋`。

小屋承载本地优先的照片墙、纪念日、信箱、共同清单、桌面小组件、日记候选和 Ombre 确认写入。
日记候选必须由用户确认后才写入 Ombre；不要把它改成自动写长期记忆。

用户刚刚确认最新包里的小屋折叠交互已经正常。最新实现故意让每个折叠区保持为**一张稳定的卡**：

- 标题整行可以点；右侧箭头是独立、可点的 `IconButton`。
- 内容用 `AnimatedVisibility` 在同一张 `Surface` 中展开/收起。
- 不要再把展开内容临时插入 `CardGroup` 的另一条 item；那会改变圆角和列表布局，造成点击迟缓、
  箭头像失灵。

相关文件：

```text
app/src/main/java/me/rerere/rikkahub/ui/pages/companion/CompanionCollapsibleSection.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/companion/CompanionSpacePage.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/companion/PhotoWallSection.kt
app/src/androidTest/java/me/rerere/rikkahub/ui/pages/companion/CompanionCollapsibleSectionInstrumentedTest.kt
app/src/androidTest/java/me/rerere/rikkahub/ui/pages/companion/PhotoWallSectionInstrumentedTest.kt
```

设置页图标也已补齐：

```text
app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt
```

- “生活线与状态卡”使用 `HugeIcons.Timeline`。
- “Daddy 的小屋”使用 `HugeIcons.Home01`。

### 明确不要顺手做的事

用户一度提出“让 AI 按任意日期设置闹钟”，后来明确撤回：系统闹钟不适合无约束地新增、
修改、删除，风险不可控。**不要修改闹钟能力**，除非用户重新明确提出并同意范围。

## 3. Todoist MCP：现状、修复和真实验证点

用户已经完成 Todoist 官方 OAuth，能看到工具列表，也安装了 Todoist 手机 App。曾出现的现象是：
一开始能用，过一段时间后 MCP 提示 token 过期、无法同步。

### 这次做了什么

在 `McpManager.kt` 中，工具调用现在会：

1. 正常调用已有 MCP 连接。
2. 如果服务把 OAuth 未授权包装成“工具调用的错误结果”（例如文本里含 `UNAUTHORIZED`、`401`、
   `invalid_token`），或者调用直接抛出未授权异常，就强制刷新 OAuth token。
3. 刷新后的 token 写回持久化 OAuth 配置；若服务轮换 refresh token，也保存新的 refresh token。
4. 因为 MCP transport 会捕获请求 Header，刷新后必须重新连接 transport。
5. 对原工具调用**只重试一次**。仍未授权时才进入“需要重新授权”状态，避免无限重试。

关键位置：

```text
app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt
  isMcpToolAuthorizationFailure()       # 约第 89 行
  callToolDetailed()                    # 约第 217 行
  ensureFreshToken(forceRefresh = ...)  # 约第 806 行

app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthClient.kt
  refreshToken()                        # 约第 231 行

app/src/test/java/me/rerere/rikkahub/data/ai/mcp/
  McpOAuthTransportRefreshPolicyTest.kt
```

已跑过的针对性 JVM 测试：

```text
:app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.ai.mcp.McpOAuthTransportRefreshPolicyTest'
```

结果：通过。它覆盖“把未授权包装成 MCP `CallToolResult`”的识别，避免只依赖异常路径。

### 还没有夸大的部分

这次修复后还需要一次**真实的长时间验证**：等 Todoist access token 自然过期后，在手机上调用一个
Todoist 工具，确认能自动恢复而不是再次让用户手动授权。没有做过这一步之前，不要写“已经彻底解决”。

排查时不要记录、截图或提交 access token、refresh token、OAuth 回调 URL 的完整参数。

## 4. 模型供应商：已经踩过的坑

这些多为配置/上游限制，不要误判成 Android 代码坏了。

### OpenCode Go

- 它不是“填一个 OpenAI API Key 就完全等同普通 OpenAI 接口”的服务。
- 每段对话需要稳定的 `x-opencode-session` Header；还应有明确的 `User-Agent`。
- 保存或切换供应商时，要特别警惕把这个自定义 Header 清空。
- GLM 等 OpenAI-compatible 模型使用 `/chat/completions`。
- OpenCode Go 内某些 MiniMax、Qwen 模型走 Anthropic `/messages` 协议；不能仅改模型名就继续用 OpenAI 格式。
- “获取账户余额”曾 404，因为 OpenCode Go 没有 Daddy 预期的余额接口；关掉该选项即可，
  它不影响聊天。

用户最近在手机上实际反馈：DeepSeek V4 Flash、工具调用、GLM 已经能用。曾有的
`reasoning_text ... must be passed back` 是带思考文本的连续调用兼容问题；修复后已能使用。

### 其他上游错误的含义

- Qwen 显示“OpenAI 格式不支持模型”通常是协议/模型配置不匹配。
- Kimi、MiniMax 的 `Internal server error` 需要先确认协议与服务端状态，不能凭这个笼统错误改一大片代码。
- `unsupported_country_region_territory` 是上游账号/地区限制，不是手机客户端缺 Header 的证据。

## 5. 代码地图：从哪里开始看

| 目标 | 先看这些文件 | 说明 |
| --- | --- | --- |
| MCP 连接、OAuth、工具调用 | `data/ai/mcp/McpManager.kt`、`McpOAuthClient.kt`、`transport/` | MCP 的连接、重连、OAuth 和通用工具网关。 |
| MCP 设置 UI | `ui/pages/setting/SettingMcpPage.kt`、`SettingVM.kt` | 添加/授权/查看服务器。 |
| 空闲活动与 MCP 服务授权 | `data/service/AutonomousActivityPolicy.kt`、`AutonomousActivityToolSurface.kt`、`ProactiveMessageService.kt`、`ui/pages/setting/SettingProactiveMessagePage.kt` | 只能用已连接、用户授权的 MCP。 |
| Daddy 的小屋 | `ui/pages/companion/`、`data/service/CompanionSpaceService.kt`、`CompanionDiaryService.kt`、`data/datastore/CompanionSpaceSetting.kt` | 生活内容与日记候选。 |
| 生活线与情绪 | `data/datastore/CompanionMoodSetting.kt`、`data/service/CompanionMoodEngine.kt` | 纯本地的延续感状态。 |
| 路由与设置入口 | `RouteActivity.kt`、`Screen`、`ui/pages/setting/SettingPage.kt` | 专属会客室路由已移除。 |
| Room 迁移 | `data/db/AppDatabase.kt`、`data/db/migrations/Migration_31_32.kt`、`app/schemas/.../32.json` | 32 号迁移会清理旧会客室表与相应历史记录。 |
| 手机给电脑/Claude Code 下任务 | `data/pcbridge/`、`ui/pages/codehut/`、`ui/pages/harness/`、`data/sync/companion/Harness*.kt` | 这是下一块想继续的方向，但不能假设它已经端到端验证。 |

## 6. 构建、测试与 APK 交付

本机 Gradle 有一些 Windows 约束。命令必须在本 worktree 运行，并尽量不并行跑两个 Gradle：

```powershell
$taskTemp = Join-Path (Get-Location) '.cache\java-temp'
New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null
$env:TEMP = $taskTemp
$env:TMP = $taskTemp
$env:GRADLE_USER_HOME = 'D:\Daddy-Gradle'
$env:JAVA_HOME = 'D:\ebbingflow\jdk-17.0.19+10'

.\gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 --console=plain :app:testDebugUnitTest
.\gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 --console=plain :app:compileDebugAndroidTestKotlin
.\gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 --console=plain :app:assembleCompanion
```

已知现象：Codex/终端有时会在 Gradle 子 JVM 真正结束前截断输出。不要只看截断输出判断失败，
要看最终的 `BUILD SUCCESSFUL` 和 APK 是否存在。日志通常在：

```text
D:\Daddy-Gradle\daemon\9.4.1\daemon-*.out.log
```

常见但非本次失败的警告：SDK XML version、`ExperimentalNavigation3Api`、少数既有 opt-in warning。
它们不等于构建失败；出现具体 Kotlin 编译错误才按错误文件修。

Companion 产物路径：

```text
app\build\outputs\apk\companion\app-arm64-v8a-companion.apk
```

当前 flavor 的元数据：

```text
applicationId: me.rerere.orangechat.companion
versionName:   2.5.53-companion
versionCode:   239
```

最新已验证并交付的 ARM64 包：

```text
D:\Daddy-安装包\Daddy-v2.5.53-Todoist续期与小屋交互修复-Companion-arm64.apk
SHA-256: 4500E20336ED0070C097B7BCDE5A4446E350A95EC5AD7D9AFB5118A10E4C927D
```

用户刚确认此包安装成功。因为 versionCode 相同，安装器若说“相同版本”，选择“重新安装”即可；
不要要求用户删数据或卸载旧 Companion。构建完成后可以复制新包到 `D:\Daddy-安装包`，但不要覆盖旧包，
用清楚的新文件名并核对 SHA-256。

仪器测试代码能编译；本轮没有在真实手机上跑 Android 测试 runner。无线 ADB 地址会变化，且 `adb` 未必在
当前 PATH；没有用户给出的最新地址和明确授权时，不要猜地址、不要擅自安装 APK。

## 7. 下一步建议：先做什么比较稳

按风险从低到高：

1. **Todoist 真实过期回归**：等 token 老化后调用一个只读工具，确认自动刷新、重连、重试是否真的走通。
   若失败，保存脱敏后的错误类别、HTTP 状态和 MCP status，不保存 token。
2. **手机→Claude Code/PC Bridge 的诊断**：先只梳理手机端入口、桌面 worker、配对与任务状态链路；
   复现后再改。过去在 Windows 上见过 `model_failed`、`model_terminated`、`model_launch_failed`，
   以及 `.cmd` 启动、空格/中文路径相关问题。不要因为 APK 能构建就说这块已经修好。
3. **陪伴体验的小优化**：优先局部、可撤回的 UI 或文案优化；保持小屋、本地情绪、用户确认写日记这些边界。

每次只挑一件独立事情。先给用户解释“要改什么、不改什么”，再做；不能把 PC bridge、MCP、模型设置和
小屋 UI 混在同一个无边界提交里。

## 8. 必须守住的安全与协作边界

- 不在代码、日志、交接、截图、提交消息中放 API Key、OAuth token、refresh token、MCP 私有 URL 参数、
  用户聊天内容或设备配对码。
- 不用 `git reset --hard`、`git clean`、批量 checkout 来“清理”当前脏工作树。
- 不删除 `docs/superpowers/plans/2026-09-09-autonomous-social-activity.md`，它是用户保留的计划文件。
- 不擅自提交当前大改动；若用户要求提交，先按功能边界复查 diff 和测试，再拆成能理解、能回滚的提交。
- 新的 MCP 默认只在设置页可见；聊天/空闲活动的可用范围遵守现有的连接状态和用户授权，不做静默登录。
- 要声称“修好了”之前，至少给出构建/测试证据；涉及手机/OAuth/远程服务的结论还要标明是否实机验证。

## 9. 给 Claude Code 的第一条任务提示词

把下面整段作为新会话的第一条消息即可：

```text
请继续 Daddy / OrangeChat Android 项目。先切换到：
D:\small progect\ai_chat\orangechat-minimal\.worktrees\daddy-unified-lounge-go

先完整阅读：
docs\superpowers\plans\2026-09-11-claude-code-handoff.md

然后运行 git status --short，只报告你看到的状态，不要 reset、clean、checkout、提交、删除未提交文件。
当前目标暂定为继续“手机给 Claude Code 下任务 / PC Bridge”的诊断与规划：先定位手机端入口、
桌面 worker、配对和任务状态链路，列出可验证的最小下一步；在我确认前不要改代码或碰真实设备。
不要重新引入内置会客室，不要修改闹钟能力，也不要暴露任何 token、API Key、配对码或隐私数据。
```
