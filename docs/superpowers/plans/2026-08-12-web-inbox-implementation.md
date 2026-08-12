# Daddy「网页来讯」实现计划

> **给实现者：** 这份计划按 TDD 执行；每一项先写能失败的 JVM 测试，再写最小生产代码，最后运行目标测试。

**目标：** 让手机浏览器的分享内容与电脑 Chrome 扩展选中的网页文字，安全地投递给 Daddy 当前设定的主聊天窗口，并在该窗口中以普通用户消息触发自然回复。

**架构：** 复用 Daddy 已有的主窗口标识 `ProactiveMessageSetting.primaryConversationId`。手机端走 Android 分享入口；电脑端走 Daddy 已有 Ktor Web Server 的一个仅写入、令牌保护的 LAN 路由。浏览器扩展只在用户点击时读取当前标签页的选择文字或可见正文，并通过二维码配对得到服务器地址与随机令牌。

**技术栈：** Kotlin、Compose、DataStore、Ktor CIO、JUnit、Chrome Manifest V3。

---

## 任务 1：定义网页来讯数据契约与纯逻辑校验

**文件：**

- 新建：`app/src/main/java/me/rerere/rikkahub/data/service/WebInboxContract.kt`
- 新建：`app/src/test/java/me/rerere/rikkahub/data/service/WebInboxContractTest.kt`

**步骤：**

1. 在测试中覆盖：正确令牌 + 选择文字可接受；令牌不匹配拒绝；空文本拒绝；选择文字超过 `2_500` 字符拒绝；全文超过 `8_000` 字符拒绝；标题、链接与文字生成稳定的普通聊天消息。
2. 运行失败测试：

   ```powershell
   .\gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.service.WebInboxContractTest"
   ```

3. 实现不可变的 `WebInboxPayload`、`WebInboxValidation`、限制常量与 `formatForChat()`。内容格式固定为：

   ```text
   【网页来讯】《{title}》
   {text}
   链接：{url}
   ```

   不写入隐藏提示词、不触发 Ombre 的自动写入。
4. 重跑同一测试确认通过。

## 任务 2：持久化 LAN 配对令牌与只写投递服务

**文件：**

- 修改：`app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`
- 新建：`app/src/main/java/me/rerere/rikkahub/data/service/WebInboxService.kt`
- 新建：`app/src/test/java/me/rerere/rikkahub/data/service/WebInboxServiceTest.kt`

**步骤：**

1. 写失败测试，覆盖：无主窗口时不投递；主窗口生成中返回 busy 而绝不取消原回答；对话不存在时返回可说明的错误；空闲主窗口只调用一次发送；重新配对后旧令牌无效。
2. 在 `Settings` 增加 `webInboxToken: String` 与 `webInboxEnabled: Boolean`，DataStore 使用独立 preferences key 保存；令牌为空时由服务生成 32 字节安全随机 Base64URL 字符串。
3. 实现 `WebInboxService`，注入 `SettingsStore`、`ConversationRepository`、`ChatService` 与 `AppScope`：

   ```kotlin
   suspend fun receive(payload: WebInboxPayload, token: String): WebInboxDeliveryResult
   ```

   先做契约校验，再解析 `primaryConversationId`，确认对话存在且 `getGenerationJobStateFlow(id).first() == null`，最后调用现有 `ChatService.sendMessage(...)`。任何 busy 状态均返回 `409` 语义，禁止打断正在生成的回复。
4. 实现 `rotateToken()`；只有二维码/配对信息使用新 token，旧 token 立刻失效。
5. 运行两个新增测试类。

## 任务 3：增加 Ktor 的最小化受保护写入接口

**文件：**

- 修改：`app/src/main/java/me/rerere/rikkahub/web/WebApiModule.kt`
- 新建：`app/src/main/java/me/rerere/rikkahub/web/routes/WebInboxRoutes.kt`
- 修改：`app/src/main/java/me/rerere/rikkahub/web/WebServerManager.kt`
- 新建：`app/src/test/java/me/rerere/rikkahub/web/routes/WebInboxRoutesTest.kt`

**步骤：**

1. 写 Ktor 路由测试：无 bearer token 是 401；错误令牌是 401；合法但未设主窗口是 409；正在生成是 409；正常请求为 202；`GET` 与任意非 `/daddy-web-inbox/v1/share` 路径不能暴露内容、设置或文件。
2. 将 `WebInboxService` 注入 `WebServerManager`，再传给 `configureWebApi`；通过 Koin 连接该服务。
3. 在现有 `routing` 中新增**不属于 `/api`**的唯一入口：

   ```http
   POST /daddy-web-inbox/v1/share
   Authorization: Bearer <pairing-token>
   Content-Type: application/json
   ```

   请求只接受 `title`、`url`、`text`、`kind`。返回只含状态码、简短 code 与 retry 指示；设置 `Cache-Control: no-store`，不记录正文、令牌或 URL 查询参数。
4. 路由使用 `secureEquals`（或移动为可复用 internal helper）作常数时间比较；不复用也不泄露 Web Server 的 JWT/管理密码。
5. 运行路由测试与已有 `:app:testDebugUnitTest` 中受影响测试。

## 任务 4：手机网页分享直接投递到主聊天窗口

**文件：**

- 修改：`app/src/main/java/me/rerere/rikkahub/ui/pages/share/ShareHandlerVM.kt`
- 修改：`app/src/main/java/me/rerere/rikkahub/ui/pages/share/ShareHandlerPage.kt`
- 新建或修改：`app/src/test/java/me/rerere/rikkahub/ShareSheetTest.kt`

**步骤：**

1. 增加失败测试：有有效主窗口时，纯文字分享选择“发送给 Daddy”会使用该窗口 id；没有主窗口仍保留现有助手选择流程；图片分享不自动绕过原有文件处理。
2. 在 ViewModel 使用 `WebInboxService` 的同一投递路径（内部可信调用，不需要 token），并暴露 `ShareDeliveryState`。
3. 页面有主窗口时显示明确的“发送到主窗口：{title}”操作；点击后等待投递结果，成功关闭分享页，busy 显示“Daddy 正在回复，稍后重试”，从不创建伪造助手消息。
4. 无主窗口时保持当前选择助手、新建聊天并预填分享内容的行为。
5. 运行 `ShareSheetTest` 和新增相关单测。

## 任务 5：Daddy 内的网页来讯配对页

**文件：**

- 新建：`app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingWebInboxPage.kt`
- 修改：`app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt`
- 修改：导航的 `Screen` 定义与导航图（按实际位置）
- 复用：`app/src/main/java/me/rerere/rikkahub/ui/components/ui/QRCode.kt`

**步骤：**

1. 页面显示开关、当前主窗口名称、Web Server 的 LAN 地址与端口状态；未设主窗口时禁用配对，显示引导而非生成无效二维码。
2. 开启时启动/复用现有 `WebServerService` 的局域网监听（不能使用 `localhostOnly=true`）；若端口占用显示实际错误。
3. 二维码正文为版本化配对 JSON/URL，只含：`version`、`endpoint`、`token`。不含会话、Ombre、Supabase、Web Server 管理密码或 API Key。
4. “重新配对”调用 `rotateToken()`，使旧电脑立即失效；“关闭”停止新的来讯入口但不篡改用户原本独立设置的 Web Server 功能。
5. 手工验证：截图中的地址可复制、二维码可识别、无主窗口/无 LAN 地址/端口失败均有可理解提示。

## 任务 6：Chrome 扩展与端到端手工验证

**文件：**

- 新建目录：`tools/daddy-web-inbox-extension/`
- 新建：`tools/daddy-web-inbox-extension/manifest.json`
- 新建：`tools/daddy-web-inbox-extension/popup.html`
- 新建：`tools/daddy-web-inbox-extension/popup.js`
- 新建：`tools/daddy-web-inbox-extension/options.html`
- 新建：`tools/daddy-web-inbox-extension/options.js`
- 新建：`tools/daddy-web-inbox-extension/README.md`

**步骤：**

1. Manifest V3 仅申请 `activeTab`、`scripting`、`storage`；使用 `optional_host_permissions`，在用户配对后明确请求对应 LAN 地址的权限。不请求 cookies、history、tabs 全量、downloads 或后台网页访问权限。
2. 配对页提供：扫描二维码（支持 `BarcodeDetector` 的 Chrome）以及粘贴配对文本的后备方案；将端点与 token 存于 `chrome.storage.local`。
3. 弹窗默认读取用户当前选中文字；未选中时明确提示并让用户二次确认后才提取可见页面正文。发送前展示标题、URL、字符数和预览。
4. 用 `fetch` 仅向配对地址 POST。202 显示“已送到 Daddy 主窗口”；401 显示“请重新配对”；409 显示“Daddy 正在回复，稍后重试”。不在扩展日志、页面或错误消息输出 token 与正文。
5. README 给出加载未打包扩展、每个新 Wi‑Fi 的重配对、撤销配对与隐私边界。
6. 手工端到端验证：手机设置一个主窗口 → 启动网页来讯 → 扫描/粘贴配对 → 电脑选择一段公开网页文字 → Daddy 主窗口收到普通 `【网页来讯】` 用户消息并自然回复；没有选择文字时测试全文二次确认；正在生成时验证不会中断。

## 任务 7：全量回归、构建与交付

**文件：** 无生产新文件。

**步骤：**

1. 运行：

   ```powershell
   $env:GRADLE_USER_HOME='D:\gradle-ascii'
   .\gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:assembleDebug
   ```

2. 用 `git diff --check`、`git status --short` 检查；确认不暂存 `package-companion-v197.log` 或任何用户的无关变更。
3. 在真实手机上经无线调试安装前，先确认 build 的包名/签名与 `Daddy-v2.5.11-...-v197-arm64.apk` 兼容；按用户既有习惯把测试 APK 复制至 `D:\Daddy-安装包`。
4. 仅在用户明确确认后，创建有意图的提交并推送至 `Fanchuan999/orangechat`。
