# Daddy 代码小屋：单 Harness 多供应商设计

## 目标

把 Daddy 的代码小屋做成一个独立的工作空间：日常聊天仍按现有提供商、助手、模型切换和 API 配置运行；所有代码任务都由现有的 DeepSeek Harness 执行。OpenCode Go 不是第二个 Agent 或第二个小屋，而是 Harness 内的一个自定义模型供应商，主力工作模型为 MiniMax M3。

## 不在本次范围内

- 不修改 Daddy 日常聊天的提供商、模型选择、助手绑定或 API Key 行为。
- 不增加 OpenCode Agent、第二个执行后端或第二个可见的代码小屋。
- 不让工作 Agent 加载 Daddy 人设、世界书、Ombre、九维欲望、主动消息状态或完整日常聊天记录。
- 不通过 WebView DOM 抓取来驱动 Harness；只使用 Harness 已公开、可验证的任务/会话接口。

## 架构

```
Daddy 日常聊天 ── 精简任务单 ──┐
                              ▼
                       代码小屋任务记录
                              ▼
                  Harness 任务网关（唯一执行器）
                              ▼
                    DeepSeek Harness / Debian
                 ┌────────────┼────────────┐
                 ▼            ▼            ▼
             DeepSeek       OpenCode Go   OpenAI
             Chat API       Anthropic API Responses API
```

### 1. 代码小屋任务边界

`CodeHutTask` 是独立于 `Conversation` 的工作任务，包含任务标题、精简任务单、所选工作模型、Harness 会话引用、状态和面向 Daddy 的短结果摘要。

从 Daddy 聊天派单时，只把用户确认的任务描述、明确选择的文件/工作目录和必要约束压缩成任务单。不会自动携带日常聊天历史、模式注入、世界书、Ombre、九维欲望或任何主动消息状态。

直接进入代码小屋时，用户和工作 Agent 对话也只使用该任务自身的工作上下文。完整终端日志、逐步工具调用和原始工作对话由 Harness 保留并只在代码小屋显示。返回 Daddy 的内容限制为状态、改动摘要、验证结果和需要用户决定的一项问题。

### 2. 单执行器与安全边界

所有任务一律由现有 Debian 中的 DeepSeek Harness 执行。现有 Harness watchdog、3080 服务、风险门和“停止后不自动复活”语义必须保持不变。

Harness 的风险门仍是强制边界：删除、覆盖既有文件、批量移动/重命名和高风险 Shell 必须先得到 Harness 的确认；模型提示词不能替代此确认。普通只读、低风险查询和创建一个不存在的新文件可以直接执行。

若 Harness 没有稳定、公开的会话/任务接口可供原生页面调用，本阶段停止在“已验证的 Harness 工作台入口”，报告缺口；不采用 DOM 自动化作为替代方案。

### 3. 供应商和协议

代码小屋读取 Daddy 现有 `Settings.providers` 与其中的 `Model` 配置，不复制也不改变日常聊天的选择。只有被标记且实际探测通过的模型才会进入工作模型列表。

| 模型来源 | Harness 自定义供应商协议 | 典型模型 |
|---|---|---|
| DeepSeek | OpenAI Chat Completions | V4 Flash、V4 Pro |
| Xiaomi | OpenAI Chat Completions | MiMo |
| OpenCode Go | Anthropic Messages | MiniMax M3、M2.7 |
| OpenAI | OpenAI Responses | GPT-5.6 Luna |

每个 `WorkModelBinding` 仅引用既有的 `providerId` 与 `modelId`，并保存协议、Harness provider ID、可用性和最后一次探测结果。它不保存 API Key。模型必须通过普通对话、Shell、文件读取/修改、多轮工具调用和 reasoning 连续性测试，才标记为“可执行”。只适合咨询的模型显示“仅支持咨询”或不进入工作模型选择器。

DeepSeek V4 的自定义兼容配置以实际请求结果为准：必要时设置 `supportsDeveloperRole`、`maxTokensField` 和 thinking/reasoning-content 重放格式。不得因为首次 400 就切换执行器。

### 4. OpenCode Go 密钥：单一存储来源

OpenCode Go 的 API Key 只保留在 Daddy 既有提供商配置中。Daddy 在本机回环地址提供一个仅限 Harness 的协议转发桥：

1. Harness 的自定义 Provider 指向 Daddy 本机桥而不是直接读取 Go 密钥。
2. Harness 仅得到短时会话令牌；令牌不等于 API Key，停止任务或服务重启后立即失效。
3. 回环桥根据 `WorkModelBinding` 找到 Daddy 已保存的提供商配置，按对应协议转发请求并在内存中附加认证。
4. DSH/Termux 配置、日志和 Debian 文件系统绝不落地保存 OpenCode Go API Key。

回环桥只监听 `127.0.0.1`，拒绝缺失或错误令牌的请求，日志脱敏请求头和授权信息。这样满足“Daddy 配一次、Harness 复用一次”，又不需要在 Termux 再保存第二份密钥。

### 5. 默认与临时模型选择

新增独立且可备份的 `CodeHutSetting`：

- 默认执行器固定为 `Harness`。
- 默认工作模型是一个通过探测的 `WorkModelBinding`；初始建议为 OpenCode Go / MiniMax M3，但未配置 Go 时回退为已验证的 DeepSeek 模型。
- 新任务默认采用该模型。
- 每个任务可选择“使用默认配置”或另一个已验证工作模型；该临时选择只写任务，不回写全局默认。
- 高级信息显示“Harness / 协议 / 供应商”，普通界面只显示易懂的模型名称与“可执行”状态。

### 6. 用户界面分期

第一期提供一个可直接使用的原生“代码小屋”入口：任务列表、从聊天生成的待确认任务单、默认工作模型、单次模型切换、状态、短摘要和进入 Harness 详细工作台的入口。

视觉方向采用 Daddy 的黑、酒红、粉色卡片风格；手机布局为单列卡片/底部弹窗，不把桌面三栏强行缩进手机。现有 Harness WebView 保留为完整日志和高级调试入口，不承担原生页面的状态管理。

### 7. 验收矩阵

每个启用的工作模型都必须记录以下探测结果：

1. 普通对话：流式响应与最终消息正确。
2. Shell：执行一个只读、安全的 `pwd` / `ls` 类命令。
3. 文件：读取测试文件、创建新测试文件，并验证修改既有文件会触发风险确认。
4. 多轮工具：至少两次连续工具调用，能正确衔接工具结果。
5. 推理：启用思考时验证流式思考显示，并验证后续工具调用仍保留该协议要求的 reasoning 内容。

任一必需项失败，模型显示失败原因，不得被设为默认工作模型；其他模型和既有 Harness 功能继续可用。

## 数据与迁移

- 增加独立 `CodeHutSetting`、`WorkModelBinding`、`CodeHutTask` 存储；不修改现有 `Assistant`、`Conversation` 或日常模型 ID 的语义。
- 旧安装没有代码小屋配置时，默认关闭，不创建供应商或写入密钥。
- 导出/恢复包含代码小屋设置和任务摘要；不导出短时会话令牌；API Key 延续现有 Daddy 提供商备份策略。

## 失败处理

- Go 未配置、密钥无效、协议不匹配或模型探测失败：保留原任务，显示可操作错误，不伪造“已执行”。
- Daddy 回环桥不可用：Harness 不会取得真实密钥，任务安全失败，可重试。
- Harness 不在线：提示启动/修复，不影响 Daddy 聊天。
- 任务或桥接失败不改变用户的日常聊天默认模型和当前助手。

## 外部依据

- DeepSeek Harness 自定义供应商以 Provider ID、协议、Base URL、环境变量凭据和模型清单描述；本设计把真实密钥留在 Daddy 侧，仅给 Harness 短时令牌。[配置说明](https://dshkit.dev/plugins/custom-model-provider)
- OpenCode Go 当前将 MiniMax M3 作为可选模型，并使用 Anthropic Messages 协议；这正是其作为 Harness 自定义供应商而非第二 Agent 的理由。[OpenCode Go 文档](https://opencode.ai/docs/go/)
