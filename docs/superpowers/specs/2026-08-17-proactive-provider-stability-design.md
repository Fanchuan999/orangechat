# Daddy 主动消息与模型兼容稳定性修复设计

日期：2026-08-17

## 目标

修复以下互有关联的问题，同时保持现有人格、聊天历史、主动模式设置和模型配置不变：

1. 主动消息偶发生成两个正文相同的独立气泡或通知。
2. 主动消息会把模型思考链当作正文发送，真正回复出现在思考内容下方。
3. Coil 图片加载器重复初始化导致应用崩溃。
4. MiMo v2.5 因请求参数不兼容返回 HTTP 400，错误又被 SSE 包装解析掩盖。
5. 思考区在“用户 / user”“应帆”和用户设置的小名之间来回切换。

## 根因

### 主动消息与思考泄漏

正常聊天在流式生成完成后调用输出转换器的 `onGenerationFinish`，主动消息却调用了普通的
`transform`。`ThinkTagTransformer` 的普通 `transform` 不处理 `<think>`，导致主动消息保留原始
“思考 + 正文”。通知、保存和去重因此都使用了未清理内容。两次生成即使正文完全相同，只要思考
不同，现有指纹也会把它们识别为不同消息。

### MiniMax 思考格式

MiniMax OpenAI 兼容接口支持 `reasoning_split=true`，启用后思考通过
`reasoning_content` / `reasoning_details` 返回，正文留在 `content`。当前实现没有请求该格式，也没有
解析 `reasoning_details`，只能依赖 `<think>` 标签兜底。

### MiMo 请求与错误解析

当前通用 OpenAI 分支会向 MiMo 发送 `reasoning_effort`，其中“超高”会变成 MiMo 不支持的
`xhigh`。MiMo Chat Completions 实际使用 `thinking.type=enabled|disabled`。HTTP 400 响应还可能以
`data:{...}` 的 SSE 形式返回；当前失败处理直接把整个响应当 JSON，因此再次抛出 JSON 解析异常，
遮住真实服务端错误。

### Coil 初始化时序

主 Activity 在 Compose 内容中设置 Coil 单例工厂，但悬浮球服务可能更早调用
`SingletonImageLoader.get()`。先 get、后 set 会触发 Coil 的单例重复创建异常。

### 思考昵称

当前显示替换只处理泛称“用户 / user”等，不知道系统提示词里的真实姓名。仅靠提示模型使用小名并不
可靠，因此需要一个用户可编辑的旧称列表作为显示层兜底。

## 设计

### 1. 统一主动消息完成流程

- 主动消息生成完成后调用与正常聊天一致的 `onGenerationFinish`。
- 保存、通知和去重只使用完成转换后的 `UIMessagePart.Text`。
- `Reasoning` 仍保存在对应思考区，但不得进入通知正文或正文指纹。
- 保留现有会话级生成抢占和 15 分钟重复窗口；修复后相同正文会在窗口内被稳定拦截，即使两次思考
  不同。
- `[PASS]`、`[JUMP]` 和工具调用行为保持不变。

### 2. MiniMax OpenAI 兼容适配

- 对 `api.minimaxi.com` 请求自动加入 `reasoning_split=true`。
- 同时解析 `reasoning_content` 与 `reasoning_details[].text`，统一生成 `Reasoning` part。
- 正文只读取 `content`，不把 reasoning 复制进正文。
- 保留 `<think>` 转换器作为其他网关和旧接口的兼容兜底。
- 为流式累计字段增加去重/增量处理，避免重复拼接已返回的前缀。

### 3. MiMo v2.5 参数与错误适配

- 对 `api.xiaomimimo.com` 使用 `thinking.type=enabled|disabled`。
- 不向 MiMo Chat Completions 发送 `reasoning_effort`；思考深度的低/中/高/超高在 MiMo 侧统一映射为
  “开启”，关闭则映射为 `disabled`。
- 思考开启时不发送自定义 `temperature` / `top_p`，由 MiMo 使用官方推荐值。
- 生成长度优先使用 `max_completion_tokens`。
- 失败响应若为 SSE，先提取 `data:` 事件，再解析其中的 `error`，向界面展示真实 HTTP 状态和错误信息。
- 多轮工具调用继续完整回传 `reasoning_content`。

### 4. Coil 全进程单例

- 把 ImageLoader 工厂移动到 `RikkaHubApp` 的应用启动阶段。
- Activity 不再设置单例工厂，只负责 Compose 页面。
- 悬浮球、聊天图片、小屋和小组件继续共用同一 ImageLoader 配置。

### 5. 思考昵称统一

- 在“显示设置 → 消息显示 → 思考沉浸化”增加“需要统一替换的旧称”输入框。
- 支持用逗号、中文逗号或换行分隔多个旧称，例如 `应帆, 帆帆`。
- 显示层将“用户 / user”、全局用户昵称和旧称列表统一显示为“思考里怎样称呼你”的值。
- 替换只作用于 `Reasoning` 显示，不修改模型原始返回、长期记忆、提示词或最终回复。

## 测试与验收

采用测试优先实现，至少覆盖：

- 两次思考不同但最终正文相同时，第二次主动消息被拦截。
- 主动消息的通知正文和去重指纹不包含思考内容。
- MiniMax `reasoning_details` 被解析为 Reasoning，`content` 只保留最终回复。
- MiniMax 累计式流字段不会重复拼接。
- MiMo 开启与关闭思考时的请求体字段正确，不出现 `reasoning_effort=xhigh`。
- `data:{"error":...}` 能还原为真实服务端错误，而不是 JSON token 异常。
- “用户 / user / 应帆”显示为用户设置的小名。
- ImageLoader 工厂仅在 Application 级初始化一次。

构建完成后执行相关 JVM 单元测试、主模块编译和 arm64 Debug APK 构建；APK 必须保持
`me.rerere.orangechat.companion` 包名与现有签名，以便覆盖更新。

## 不在本次范围

- 不重写整个 Provider 抽象层。
- 不更改主动消息触发频率、情绪引擎或晚安守夜规则。
- 不修改助手人格、系统提示词正文、聊天历史和 Ombre 数据。
- 不将思考翻译成中文；只继续使用现有中文思考提示与显示昵称替换。
