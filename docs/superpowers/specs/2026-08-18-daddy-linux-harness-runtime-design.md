# Daddy Linux 环境与 Harness 稳定运行时设计

日期：2026-08-18  
状态：设计已与用户确认，等待实施计划  
目标仓库：`Fanchuan999/orangechat`（Daddy / OrangeChat 衍生版）

## 1. 文档关系

本文是《Daddy × DeepSeek Harness 手机端集成设计》的运行时补充设计，替代其中“直接在 Android / Termux 原生环境运行 Node.js 与 Harness”的部分。原设计中已经确认的产品边界、Daddy 入口、独立模型配置、WebView 工作台、危险操作原则和分期方案继续有效。

手机实测已经证明，`@deepseek-ai/dsh@0.1.0-rc.7` 的 `node-pty`、`koffi`、`sharp` 等原生依赖不能可靠运行在 Node 的 `android-arm64` 平台。通过删除原生插件或跳过依赖只能得到残缺工作台，不能作为正式方案。因此，新架构把 Harness 放进 Debian/glibc 用户态环境运行，Termux 只负责安装、启动、监控和目录桥接。

## 2. 目标与非目标

### 2.1 本期目标

1. 在无 Root 的 iQOO Neo 10 上提供可重建、可修复的轻量 Debian 运行环境。
2. 在 Debian 中运行完整官方 Harness，而不是删减原生插件的兼容版。
3. 保留现有 Daddy Harness 页面、启动控制、WebView、自动复活和 Termux:Boot 体验。
4. 安装/修复重复执行时不删除 Harness 会话、设置、API Key、工作区或用户文件。
5. Harness 只监听 `127.0.0.1:3080`，不暴露给局域网。
6. 给未来其他 Linux 服务保留清晰的目录结构，但本期只安装 Harness。

### 2.2 本期非目标

- 不接入 Operit 插件市场或 ToolPkg。
- 不复制 Operit 的 Ubuntu Runtime、插件实现或界面代码。
- 不实现通用 Linux 插件商店、容器管理器或桌面环境。
- 不实现 Daddy → Harness 聊天委托桥。
- 不自动更新 Harness、Node 或 Debian 镜像。
- 不删除手机里现有约 267 MB 的 Android 原生 Harness 目录；Linux 版通过验收后再单独询问用户是否清理。

## 3. 总体架构

```text
Daddy Android App
  ├─ Harness 状态页 / WebView
  ├─ 安装、启动、停止、重启
  └─ TermuxConfigBridge / RUN_COMMAND
                    ↓
Termux（编排层）
  ├─ proot-distro
  ├─ 安装脚本、状态文件、脱敏日志
  ├─ watchdog 与 Termux:Boot
  ├─ Termux Home / Android 共享存储
  └─ daddy-linux 容器
         ↓
Debian Bookworm / glibc（执行层）
  ├─ Node.js v24.19.0 linux-arm64
  ├─ @deepseek-ai/dsh@0.1.0-rc.7
  ├─ Harness 原生依赖
  └─ Web UI：127.0.0.1:3080
```

Termux 中原有的 Ombre-Brain、Termux Bridge、高德 MCP 和其他脚本不迁入 Debian，也不改启动方式。Daddy Linux 环境是新增的独立服务，不得占用它们的端口或覆盖它们的目录。

## 4. 固定版本与兼容策略

首个稳定版本使用以下精确版本：

- 容器：Debian Bookworm，`linux/arm64`。
- Node.js：`v24.19.0` 官方 `linux-arm64` 包。
- Harness：`@deepseek-ai/dsh@0.1.0-rc.7`。

安装脚本禁止使用浮动的 `latest`。Node 下载后必须使用 Node 官方 `SHASUMS256.txt` 验证 SHA-256，再解压安装。实际安装版本和校验结果写入版本清单。

PRoot-Distro 安装命令需要兼容两个接口：

- 新接口：使用 `--name daddy-linux` 创建自定义容器名。
- 旧接口：不支持 `--name` 时，回退到已弃用但仍兼容的 `--override-alias daddy-linux`。

运行前通过能力探测选择命令，不根据版本号猜测。容器固定名称为 `daddy-linux`，不得占用或重置用户已有的普通 `debian`、`ubuntu` 容器。

## 5. 目录与数据边界

### 5.1 Termux 编排目录

```text
$HOME/daddy-linux/
  manifest.json
  install-state.json
  scripts/
    install.sh
    start-harness.sh
    stop-harness.sh
    watchdog-harness.sh
    status-harness.sh
  services/
    harness/
      runtime/
      cache/
      logs/
      run/
  data/
    harness/
      dsh-home/
      workspaces/
```

职责分离如下：

- `services/harness/runtime`：可重新下载安装的 Node 与 Harness 程序。
- `services/harness/cache`：可删除并重新获取的下载缓存。
- `services/harness/logs`：脱敏安装和运行日志。
- `services/harness/run`：PID、停止标记、退避状态等临时运行信息。
- `data/harness/dsh-home`：Harness 会话、设置、Profile、凭据引用和插件数据。
- `data/harness/workspaces`：默认工作区；用户仍可选择已绑定的其他路径。

“安装/修复”只能重建容器和 `services`，不得删除 `data`。未来联动备份以 `data/harness`、版本清单和启动设置为核心；Debian rootfs、Node 和 npm 缓存属于可重建运行时，不要求塞进备份包。

### 5.2 Debian 内目录映射

每次启动使用显式绑定：

```text
/opt/daddy-harness   ← $HOME/daddy-linux/services/harness
/data/daddy-harness  ← $HOME/daddy-linux/data/harness
/host/termux         ← Termux $HOME
/host/storage        ← Android 共享存储
```

Harness 的 `DSH_HOME` 显式指向 `/data/daddy-harness/dsh-home`。默认工作区为 `/data/daddy-harness/workspaces`。这样 Debian rootfs 即使损坏并重装，用户数据仍保留在 Termux Home。

不绑定其他 App 的私有目录，不声称绕过 Android 沙盒。共享存储访问范围仍受 Termux 的 Android 存储权限约束。

## 6. 幂等安装与修复流程

安装由阶段化状态机驱动，每个阶段成功后原子写入 `install-state.json`。重新进入页面或命令超时后，从未完成阶段继续，不从头重放全部安装。

```text
PRECHECK
  → INSTALL_PROOT
  → INSTALL_DEBIAN
  → INSTALL_NODE
  → INSTALL_HARNESS
  → WRITE_SCRIPTS
  → START_AND_HEALTHCHECK
  → READY
```

### 6.1 PRECHECK

- 验证 Termux 外部命令权限和桥服务。
- 检查可用空间，预计完整新增占用约 0.8–1.2 GB。
- 检查 3080 端口是否被非 Harness 进程占用。
- 识别旧 Android 原生 Harness，只标记为“旧运行时”，不得误报 Linux 版已安装。
- 检查 `data/harness`，存在时原样保留。

### 6.2 INSTALL_PROOT

- 通过 Termux 官方包管理器安装 `proot-distro` 及其依赖。
- 已存在且能执行时直接跳过。
- 不升级 Termux 中与本功能无关的全部包。

### 6.3 INSTALL_DEBIAN

- 创建名为 `daddy-linux` 的 Debian Bookworm ARM64 容器。
- 容器已存在且基础命令自检通过时跳过。
- 容器损坏时只重建 rootfs，并再次挂载原有 `data/harness`。
- 不对其他用户容器执行 `reset` 或 `remove`。

### 6.4 INSTALL_NODE

- 下载 Node `v24.19.0-linux-arm64` 与官方校验文件。
- 校验成功后安装到 Harness 独立 runtime，不覆盖 Termux 的 Android Node。
- Debian 内执行 `node --version` 和 `npm --version` 通过才完成阶段。

### 6.5 INSTALL_HARNESS

- 使用 Debian 内的 Node/npm 安装精确版本 `@deepseek-ai/dsh@0.1.0-rc.7`。
- 安装失败保留完整阶段日志，但展示给用户前脱敏。
- 不通过删除 `node-pty`、`koffi`、`sharp` 或禁用官方插件来伪造成功。
- 执行 `dsh --version` 或等价官方自检后才完成阶段。

### 6.6 启动与健康检查

- 启动后同时验证进程、容器会话和 `127.0.0.1:3080`。
- 页面可连接且进程稳定一段时间后才写入 `READY`。
- 首次安装整体允许 30 分钟，Daddy UI 持续显示当前阶段；单次桥请求超时不得自动重放安装命令。

## 7. 运行、停止与自动复活

### 7.1 启动

启动脚本清除“用户主动停止”标记，然后由 Termux watchdog 拉起 Debian 中的 Harness。Harness 只监听 `127.0.0.1:3080`，工作目录使用默认工作区，日志重定向到固定文件。

### 7.2 停止

用户点击“停止”时：

1. 先原子写入主动停止标记。
2. 停止 watchdog。
3. 停止 `daddy-linux` 中的 Harness 会话及子进程。
4. 确认 3080 不再监听。

主动停止标记存在时，Termux:Boot、Android 后台检查和页面刷新都不得复活 Harness。只有用户点击“启动”或重新开启自动保持运行才清除标记。

### 7.3 watchdog 与退避

异常退出后的第一次恢复约 3 秒执行。连续失败时采用渐进退避，避免每 3 秒无限重启造成耗电和日志刷屏：

```text
第 1 次：3 秒
第 2 次：10 秒
第 3 次：30 秒
第 4 次及以后：5 分钟
```

Harness 连续稳定运行 15 分钟后，失败计数清零。Android 后台健康检查默认每 15 分钟兜底一次，但必须遵守主动停止标记和退避时间。Termux:Boot 只负责启动 watchdog，不直接并发启动第二个 Harness。

## 8. Daddy 界面行为

现有 Harness 页面沿用以下操作：

- 安装 / 修复
- 启动
- 停止
- 重启
- 刷新
- 打开 Harness 工作台
- 自动复活开关
- 脱敏日志与备用 Termux 命令

安装卡片展示明确阶段，而不是一个长期不变的“安装中”：

```text
检查环境 → 安装 Debian → 安装 Node → 安装 Harness → 启动并检查
```

已经完成的阶段显示完成标记。失败时显示失败阶段、可读原因和“从此阶段修复”。只有 3080 健康检查通过时，“打开 Harness 工作台”按钮可用。

状态模型扩展为：

```text
未安装 / 安装中 / 未启动 / 启动中 / 运行中 / 主动停止 / 异常退避 / 修复中
```

旧 Android 原生运行时存在但 Linux 版未完成时，页面显示“发现旧运行时，需要迁移”，不得显示“运行中”。

## 9. 凭据、安全与日志

- Harness API Key 仍在 Harness 官方页面配置；Daddy 不读取、不展示、不注入。
- 日志展示前屏蔽 `Authorization`、API Key、Cookie、token、密码和凭据文件内容。
- Harness 仅监听回环地址，不提供 `0.0.0.0` 开关。
- WebView 仅信任本机 Harness 同源页面；外部链接交给系统浏览器。
- 安装脚本不使用 `curl | sh`，下载文件先落盘、校验，再执行或解压。
- Debian 环境不获得 Root Android 权限；容器内的 `root` 只是 PRoot 用户态映射。

## 10. 更新、迁移与备份

### 10.1 从旧原生运行时迁移

首个 Linux 版本不自动删除或覆盖 `$HOME/daddy-harness`。若其中包含已成功生成的 DSH 用户数据，迁移前先备份，再复制到新的 `data/harness/dsh-home`；程序文件和 Android 原生 `node_modules` 不迁移。

Linux 版通过以下验收后，Daddy 才提示是否清理旧运行时：

- 能连续运行并打开 Web UI。
- 能配置模型并新建 Harness 会话。
- 能访问默认工作区和共享存储测试目录。
- 手动停止不会复活，异常退出可以复活。

删除旧运行时属于独立的破坏性操作，必须再次获得用户确认。

### 10.2 后续更新

Harness 更新和 Daddy APK 更新解耦。未来更新流程必须：备份 `data/harness` → 安装到新 runtime 目录 → 自检 → 原子切换版本 → 失败回滚。本文首期只安装固定 rc.7，不提供自动更新。

### 10.3 联动备份

后续联动备份至少包含：

- `data/harness/dsh-home`
- `data/harness/workspaces` 中用户选择纳入的内容
- `manifest.json`
- 自动复活与用户停止设置

Debian rootfs、Node 包、npm 缓存和运行日志默认不备份，以控制包体；恢复时按版本清单重新构建运行时。

## 11. 错误处理

- `proot-distro` 下载失败：保留下载缓存和阶段，网络恢复后继续。
- Debian 创建失败：不碰 `data/harness` 和其他容器。
- Node 校验失败：删除错误下载文件，报告校验失败，禁止继续解压。
- npm 包不存在或安装失败：显示精确包版本与 npm 原始错误摘要，不改装未验证版本。
- 3080 被占用：显示占用进程信息，不强杀未知进程。
- Harness 反复崩溃：进入“异常退避”，提供最近脱敏日志和手动修复。
- Termux 外部命令被拒绝：展示权限检查与同一套备用 Termux 命令。
- Daddy 与桥连接超时：查询阶段状态，不重放可能仍在执行的安装命令。
- 手机切换应用或 Daddy 进程重建：重新读取状态文件并恢复进度 UI。

## 12. 测试与验收

### 12.1 JVM 单元测试

- 安装脚本含固定 Debian、Node、Harness 版本且不含 `latest`。
- 新旧 PRoot-Distro 命令能力探测与回退正确。
- 每个阶段可独立重试，已完成阶段不会重复破坏性执行。
- 修复脚本不包含删除 `data/harness`、其他容器或用户工作区的命令。
- 主动停止标记阻止 watchdog、Boot 和后台检查复活。
- 首次崩溃 3 秒恢复，连续失败按 10 秒、30 秒、5 分钟退避。
- 安装请求提交成功但桥等待超时时不会重放命令。
- 日志脱敏覆盖凭据、请求头、Cookie 和 token。
- 旧 Android 原生 runtime 不会被误识别为 Linux READY。

### 12.2 Android / 手机烟测

1. 在没有 PRoot-Distro 的手机上完成一次全新安装。
2. 重复点击“安装 / 修复”，会话、设置和工作区不丢失。
3. `127.0.0.1:3080` 可从 Daddy WebView 打开。
4. Harness 能选择 `/data/daddy-harness/workspaces`、`/host/termux` 和 `/host/storage` 中的授权目录。
5. 能创建测试文件、读取测试文件并执行普通 Shell 命令。
6. 用户点击停止后等待至少 20 分钟，Harness 不复活。
7. 再次点击启动后能正常恢复。
8. 模拟 Harness 子进程异常退出，约 3 秒后首次复活。
9. 模拟连续崩溃，确认进入渐进退避而非高频循环。
10. 重启手机后，自动复活开启时由 Termux:Boot 恢复；关闭或主动停止时不恢复。
11. Harness 安装、运行和异常不影响 Ombre-Brain、Termux Bridge、高德 MCP 和 Daddy 日常聊天。
12. 新版验证完成前，旧约 267 MB 原生 runtime 保持不动。

### 12.3 发布门槛

只有以下条件全部满足，才生成可交付 APK：

- 新增单元测试通过。
- 相关 Harness 既有测试通过。
- Debug APK 构建成功。
- 手机完成安装、3080、工作区、停止和异常复活烟测。
- APK 包名与签名保持当前 Daddy 覆盖更新链一致。
- 本次实现不包含用户 API Key、手机日志或本地临时诊断文件。

## 13. 预期影响

相较直接在 Termux/Android Node 中运行，Debian/PRoot 会增加约 0.8–1.2 GB 存储占用，启动速度和运行性能也会有少量损耗。但它提供 Harness 原生依赖需要的标准 Linux/glibc 环境，能避免目前已经复现的 Android 原生模块安装失败。Daddy、Ombre 和其他 Termux 服务仍按原方式运行，因此风险被限制在新增的 `daddy-linux` 容器与 Harness 目录内。
