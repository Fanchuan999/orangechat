# Daddy × DeepSeek Harness 手机端集成设计

日期：2026-08-17  
状态：已由用户确认，可进入实施规划  
目标仓库：`Fanchuan999/orangechat`（Daddy / OrangeChat 衍生版）

## 1. 目标

在 Daddy 中加入一个由 Termux 承载的完整 DeepSeek Harness 工作台，使用户可以在手机上运行 Agent 任务、配置独立模型、安装 Harness 插件、执行 Shell 与文件操作，同时保留危险操作前确认。

本功能不替换 Daddy 的日常聊天、人格、Ombre 记忆、主动消息或现有模型系统。Daddy 继续负责陪伴聊天；Harness 是独立的 Agent 工作台。

## 2. 已确认的产品决策

1. Harness 使用独立模型配置，不自动读取 Daddy 的模型或 API Key。
2. Harness 不人为限制在单个项目工作区；允许访问 Termux 和 Android 已授权的全部文件路径。
3. Android 系统沙盒仍然有效：无 Root 时不得假装能读取其他 App 的私有目录。
4. 普通 Shell 和文件操作可直接执行；危险操作必须由用户确认。
5. Daddy 提供两个入口：
   - 聊天侧边栏固定入口。
   - 聊天输入框右下角“＋”面板入口。
6. “＋”面板中的 Harness 入口点击后直接进入完整工作台，不额外增加“委托当前消息”二级菜单。
7. 智能工具节流和手动选择工具的功能保持独立，只合并 UI 入口。
8. 手机安装的是完整官方 Harness，因此后续可以安装兼容的 Harness 插件。

## 3. 总体架构

```text
Daddy Android App
  ├─ Harness 状态/管理页
  ├─ 内置 WebView 工作台
  ├─ 危险操作确认桥
  └─ TermuxConfigBridge / Termux 外部命令
                ↓
Termux
  ├─ Node.js / npm
  ├─ @deepseek-ai/dsh（锁定已验证版本）
  ├─ Web UI：127.0.0.1:3080
  ├─ Harness 独立配置与凭据
  └─ Harness 第三方插件
```

Harness 作为 Termux 内的独立服务运行，Daddy 只负责安装引导、启动管理、状态检测、WebView 展示、危险操作确认和备份索引。不要把 Harness 核心代码复制进 Android/Kotlin 工程。

这样 Harness 更新通常只更新 Termux 里的 npm 包，不需要每次重新构建 Daddy APK。

## 4. 运行与部署

### 4.1 服务地址

- 默认 Web UI：`http://127.0.0.1:3080`
- 只监听回环地址，不向局域网暴露。
- Daddy WebView 只允许加载本机 Harness 地址及 Harness 自身必要资源。

### 4.2 安装目录

建议统一放在：

```text
$HOME/daddy-harness/
  scripts/
  logs/
  backups/
  VERSION
```

Harness 自身的 `$DSH_HOME` 使用官方默认目录或显式指向 `$HOME/daddy-harness/dsh-home`。路径必须固定，便于联动备份和恢复。

### 4.3 启动管理

提供以下操作：

- 安装/修复运行环境
- 启动
- 停止
- 重启
- 状态检测
- 查看日志
- 更新
- 回滚到上一个已验证版本

后台使用 watchdog：Harness 进程异常退出时可以自动重启；用户主动停止时不得被 watchdog 拉起。

### 4.4 版本策略

DeepSeek Harness 当前处于 Developer Preview，可能发生破坏兼容的更新，因此：

- 首次安装必须锁定具体版本，禁止使用不受控的 `latest` 作为长期运行版本。
- 更新前备份 Harness 配置、凭据引用、会话、Profile、插件清单和旧版本号。
- 更新后执行健康检查与 Web UI 加载测试。
- 失败时自动回滚，保留失败日志。

## 5. 独立模型配置

Harness 模型配置完全独立于 Daddy。用户在 Harness 自己的“设置 → 模型”中配置提供商和密钥。

支持范围以 Harness 当前版本为准，包括：

- DeepSeek
- OpenAI
- Anthropic
- Azure OpenAI
- AWS Bedrock
- Google Vertex
- Codex OAuth（以官方当时支持状态为准）
- 自定义 OpenAI 兼容提供商，例如 MiMo、GLM、OpenRouter 等

自定义提供商需要填写 Provider ID、Base URL、API 协议、凭据和模型列表。多模态能力不能仅凭模型名称猜测，必须按 Harness 配置格式显式声明并实测。

Daddy 不读取、不显示、不同步 Harness API Key。Harness 凭据由 Harness 官方凭据文件管理；Daddy 联动备份时可以整体加密/原样备份其配置目录，但不得把明文密钥写入日志。

## 6. 文件系统与权限策略

### 6.1 可访问范围

不增加 Daddy 自定义的“只能访问某个工作区”限制。Harness 可以访问操作系统实际允许 Termux 访问的路径，例如：

- Termux Home
- `$HOME/daddy-harness`
- 用户授权后的共享存储
- `/sdcard` 或对应共享存储路径
- 用户显式提供的其他可访问目录

Android 原生沙盒限制仍然存在。无 Root、Shizuku 或其他系统授权时，不能访问其他 App 的私有数据目录。

### 6.2 危险操作确认

普通读取、搜索、创建文件、普通编辑和非破坏性 Shell 命令可直接执行。

下列操作必须确认：

- 删除文件或目录
- 覆盖现有重要文件
- 批量移动或批量改名
- 卸载软件或清除数据
- 修改启动脚本、系统配置、权限或网络暴露设置
- 递归命令和目标范围很大的命令
- 修改 Daddy、Ombre、Termux Bridge 或其他关键服务的数据
- 任何 Harness/插件自行标记为高风险的操作

确认弹窗至少显示：

- 操作类型
- 完整命令或等价说明
- 解析后的目标路径
- 预计影响范围
- “取消”与“仅本次允许”

不提供“永久允许所有危险操作”。用户拒绝后，操作必须停止并将拒绝结果返回 Harness。

## 7. Harness 插件支持

安装完整官方 Harness，保留其 Profile、Bundle 和第三方插件机制。Daddy 不把 Harness 插件转换成橘瓣插件或 MCP。

Harness 管理页面需要支持：

- 查看已安装插件
- 启用/停用
- 从 npm 安装
- 从 GitHub 地址安装
- 更新单个插件
- 卸载
- 查看版本、来源和兼容状态
- 更新失败回滚

安装前展示来源、版本、维护者信息（若能取得）和安装脚本/权限提示。第三方插件能在 Termux 中执行代码，首次安装与升级均需用户确认。

兼容性分级：

- 纯 JavaScript/TypeScript：优先支持。
- 普通 npm 依赖：安装后执行自检。
- 含原生二进制：必须验证 Android ARM64/Termux 兼容性。
- 依赖 Docker、x86 二进制、桌面 GUI 或 systemd：标记为不兼容或需要适配，不得假装安装成功。

## 8. Daddy 界面设计

### 8.1 侧边栏入口

在聊天侧边栏的固定功能区域加入：

```text
DeepSeek Harness    运行中 / 未启动 / 未安装 / 异常
```

点击行为：

- 已运行：进入完整 Harness 工作台。
- 未启动：显示启动中，成功后进入工作台。
- 未安装：进入安装引导页。
- 异常：进入状态页，展示修复、日志和回滚入口。

### 8.2 聊天“＋”面板改版

当前两个独立大块：

- 智能工具节流
- 手动选择工具

改成一个顶层标签：

```text
工具节流    已关闭 / 智能模式 / 手动模式
```

点击“工具节流”打开二级弹窗，弹窗内部保留：

- 智能工具节流开关及原说明
- 手动选择工具开关及原说明
- 手动 MCP/插件清单入口

两种模式继续保持原来的互斥逻辑：打开智能模式会关闭手动模式，打开手动模式会关闭智能模式。不得改变现有工具选择、上下文、记忆或世界书逻辑。

合并 UI 后，“＋”面板顶层顺序为：

```text
拍照    照片    上传文件
────────────────────
工具节流           手动模式
DeepSeek Harness   运行中
扩展管理           <启用数量>
压缩历史           <消息数量>
```

Harness 状态文案：

- `运行中`
- `未启动`
- `未安装`
- `异常`

点击 Harness 行的行为与侧边栏一致，直接进入完整工作台，不弹出“打开/委托”双选菜单。

### 8.3 完整工作台

工作台使用 Harness 官方 Web UI，不重新实现其 Agent 会话界面。Daddy 外层只增加必要的原生控制：

- 返回 Daddy
- 运行状态
- 启动/重启
- 日志
- 更新与回滚
- 全屏/刷新

工作台内部由 Harness 负责：

- Agent 会话
- 计划与工具执行过程
- 工作区/文件选择
- 模型配置
- Agent 预设
- 插件管理
- 终端与任务结果

## 9. 数据隔离、备份与恢复

Daddy 与 Harness 数据逻辑隔离：

- Daddy 聊天历史不会自动复制到 Harness。
- Harness 会话不会自动写入 Daddy 聊天历史或 Ombre。
- Daddy 人格、世界书和九维状态不会自动注入 Harness。
- 后续若增加“委托当前任务”，必须另立设计，不在本期暗中实现。

联动备份需要新增 Harness 项目：

- 固定版本号
- `$DSH_HOME` 设置
- 凭据文件（按用户现有“完整一键恢复”偏好纳入备份）
- Profile 与 patch 配置
- 插件清单及锁定版本
- 会话与工作台数据
- Daddy 侧 Harness 设置
- 启动脚本和 watchdog

恢复顺序：运行环境检查 → 安装锁定 Harness 版本 → 恢复配置与插件 → 恢复会话 → 启动 → 健康检查。

## 10. 错误处理

- Termux 拒绝外部命令：展示可操作的权限检查，不笼统宣称用户未授权。
- Node/npm 缺失：进入安装/修复流程。
- 3080 未监听：显示最近日志并提供重启。
- WebView 连接失败：区分“服务未启动”“端口异常”“页面加载异常”。
- 插件安装失败：保留旧版，展示原始错误和兼容性判断。
- Harness 更新失败：自动回滚，不破坏当前可用版本。
- API Key/模型错误：由 Harness 独立显示，不影响 Daddy 日常聊天。
- 用户拒绝危险操作：将明确的拒绝结果返回 Agent，不重复弹窗死循环。

## 11. 实施分期

### 第一阶段：能稳定使用

- Termux 安装/启动/停止/状态检测
- 锁定版本和 watchdog
- Daddy WebView 完整工作台
- 侧边栏入口
- “＋”面板 UI 合并与 Harness 入口
- 独立模型配置沿用 Harness 官方 UI

### 第二阶段：长期维护

- 插件管理入口和兼容性自检
- 更新、备份、回滚
- 联动备份纳入 Harness
- 危险操作确认桥和确认记录

### 第三阶段：可选扩展（不属于本期）

- Daddy 将输入框任务与附件委托给 Harness
- Harness 结果回传指定 Daddy 聊天窗口
- 用户确认后把重要任务结果写入 Ombre

## 12. 测试要求

至少覆盖：

- 工具节流 UI 合并后，两种模式的原状态和互斥行为不变。
- 侧边栏与“＋”面板入口状态一致。
- 未安装、未启动、运行中、异常四种状态路由正确。
- Harness 启动后 `127.0.0.1:3080` 可访问。
- WebView 能登录/配置模型、创建会话并恢复页面状态。
- Daddy 切换模型不会改变 Harness 模型。
- Harness 切换模型不会改变 Daddy 模型。
- 普通文件/Shell 操作可执行。
- 删除、覆盖和高风险命令会触发确认；拒绝后不执行。
- 插件安装、启停、升级失败回滚符合预期。
- 手机重启后服务按设置恢复，不产生重复进程。
- Harness 服务崩溃不会导致 Daddy 主聊天崩溃。
- Daddy 备份恢复后能按锁定版本恢复 Harness、配置、插件和会话。

## 13. 验收标准

1. 用户能从侧边栏和聊天“＋”面板进入同一个完整 Harness 工作台。
2. “＋”面板只显示一个“工具节流”顶层入口，原两个功能仍完整可用。
3. Harness 至少能配置 DeepSeek 和一个非 DeepSeek 提供商。
4. 用户可以安装一个兼容的第三方 Harness 插件并在重启后保留。
5. Harness 可以访问 Termux 和已授权共享存储，不受 Daddy 单工作区限制。
6. 危险操作未经确认绝不执行，普通操作不会被过度打断。
7. Harness 更新失败不会损坏当前可用版本、Daddy 聊天或 Ombre。
8. Harness 停止或崩溃时，Daddy 的日常陪伴聊天仍可正常使用。

## 14. 非目标

- 不把 Operit ToolPkg 直接安装到 Daddy。
- 不移植 Operit 的整套 Ubuntu Runtime。
- 不把 Harness 作为 Daddy 的日常聊天模型。
- 不自动共享 Daddy 聊天历史、人格、世界书或 Ombre。
- 不承诺所有 GitHub Harness 插件均兼容 Android ARM64。
- 不在本期实现 Daddy 与 Harness 的双向任务/记忆同步。

