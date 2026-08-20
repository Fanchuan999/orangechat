# Daddy Linux Harness 真机烟雾测试

## 测试目标

确认 Daddy v2.5.36（v222）可在 iQOO Neo 10 上覆盖更新，在 Termux 中幂等安装独立 Debian
Bookworm ARM64 运行时，并稳定运行官方 DeepSeek Harness `0.1.0-rc.7`。官方 Docker Hub 在 Termux 中
出现已识别的网络超时时，才改用明确标识的备用镜像源；桌面无法验证的项目保持“待实测”。

## 固定环境契约

| 项目 | 预期值 | 实测 |
|---|---|---|
| 手机 | iQOO Neo 10 | 已实测 |
| Android / OriginOS | 以手机当前系统为准 | 以手机当前系统为准 |
| Daddy 包名 | `me.rerere.orangechat.companion` | 桌面验证通过 |
| Daddy 版本 | `2.5.36-companion`（222） | 桌面构建通过 |
| Termux | 保留用户现有安装 | 已实测 |
| PRoot 容器 | `daddy-linux` | 已实测 |
| Linux 发行版 | Debian Bookworm ARM64 | 已实测 |
| 容器架构 | `aarch64` | 已实测 |
| Node.js | `v24.19.0` Linux ARM64 | 已实测 |
| pnpm | `10.17.1` | 已实测 |
| Harness | `@deepseek-ai/dsh@0.1.0-rc.7` | 已实测 |
| Web UI | `http://127.0.0.1:3080` | 已实测，HTTP 200 |

## 目录与绑定

| Termux / Android 路径 | Debian 内路径 | 用途 |
|---|---|---|
| `$HOME/daddy-linux/services/harness` | `/opt/daddy-harness` | 固定运行时、脚本、日志和安全门 |
| `$HOME/daddy-linux/data/harness` | `/data/daddy-harness` | Harness HOME、DSH_HOME、会话与用户数据 |
| `$HOME` | `/host/termux` | 用户明确授权后的 Termux 文件访问 |
| `/sdcard` | `/host/storage` | Android 已授予范围内的共享存储 |
| `$HOME/daddy-harness` | 不覆盖 | 旧运行时，仅用于迁移识别和回退 |

## 桌面验证

| 检查项 | 结果 | 说明 |
|---|---|---|
| Harness 聚焦单元测试 | 通过 | `HarnessScriptsTest` 22 项通过；使用纯英文 Gradle 缓存入口避开 Windows 用户目录编码问题 |
| 全仓 JVM 单元测试 | 未运行 | 不以局部测试代替全仓验证 |
| Companion APK 构建 | 通过 | `:app:assembleCompanion` 成功，生成 v222 ARM64 产物 |
| 包名 / 版本 | 通过 | `me.rerere.orangechat.companion` / `2.5.36-companion`（222） |
| CPU 架构 | 通过 | `output-metadata.json` 中 ARM64 元素为 `arm64-v8a` |
| 覆盖更新签名 | 通过 | 与 v221 的 V2 证书 SHA-256 同为 `ea958214d61fac06047eb4a5316bebf621d7da5a6d21af74768d144cc4217b68` |
| APK SHA-256 | 通过 | `3c5fc0f27dd9c62d0f8e8e786b5fc6e8d71ebc75ae4bb0e35027c32f88d0e141` |

## 安装与迁移

| 检查项 | 结果 | 说明 |
|---|---|---|
| Daddy 一键安装 / 修复 | 部分实测 | 现有 v221 环境已完成 profile 修复；v222 一键幂等流程待 APK 覆盖验证 |
| Docker Hub 网络超时 | 待实测 | 仅命中网络失败标识时改用 `docker.m.daocloud.io/library/debian:bookworm`；普通安装错误不切换 |
| Termux RunCommand 回退 | 待实测 | 只执行 Daddy 内置命令，不开放任意 UI Shell |
| 手动复制备用命令 | 待实测 | 与一键安装使用同一套幂等脚本，不含凭据 |
| 首次安装耗时 | 待实测 | 允许 10–20 分钟，30 分钟为有界等待上限 |
| 中断后续装 | 待实测 | 从已验证产物继续，不清空容器、配置、会话或凭据 |
| 旧 `$HOME/daddy-harness` | 待实测 | UI 提示迁移；新运行时成功前保留旧目录 |
| 重复安装 | 待实测 | 不丢失 `/data/daddy-harness/dsh-home` 与工作区 |

## 功能与安全

| 检查项 | 结果 | 说明 |
|---|---|---|
| `GET http://127.0.0.1:3080/` | 通过 | 实测返回 `HTTP 200` 与 Harness Web 首页；只监听本机回环地址 |
| Daddy 内工作台 | 待实测 | 官方 UI 在 WebView 打开，外链交给系统浏览器 |
| `/host/termux` 与 `/host/storage` | 待实测 | 仅在 Android / Termux 权限允许范围内可见 |
| 普通只读操作 | 待实测 | 读取专用烟雾测试目录时不弹危险确认 |
| 新建普通测试文件 | 待实测 | 仅在专用测试目录内创建 |
| 删除 / 覆盖 / 批量移动 | 部分实测 | 安全门在 Daddy 实际 profile 中成功加载且自检通过；弹窗交互待工作台内真机确认 |
| 高风险 Shell | 部分实测 | 安全门在 Daddy 实际 profile 中成功加载且自检通过；弹窗交互待工作台内真机确认 |
| 无确认界面时 | 待实测 | 危险操作应默认拒绝，不得仅依赖提示词 |
| 日志脱敏 | 待实测 | 最多 200 行 / 24 KiB，隐藏 Authorization、Cookie、API Key、token |
| 凭据边界 | 待实测 | Android 不读取或输出 Harness 凭据文件 |

## 生命周期与恢复

| 检查项 | 结果 | 说明 |
|---|---|---|
| 点击停止后 20 分钟不复活 | 待实测 | `.manual-stop` 优先于脚本、Boot 与 WorkManager |
| 点击启动后恢复 | 待实测 | 清除手动停止标记并通过 3080 健康检查 |
| 第一次意外退出 | 待实测 | watchdog 尽快拉起 |
| 连续失败 | 待实测 | 使用渐进退避，避免耗电和重启风暴 |
| Android 后台兜底 | 待实测 | WorkManager 约 15 分钟检查，不使用前台常驻服务 |
| 手机重启 | 待实测 | Termux:Boot 在权限允许时恢复服务 |

## 非回归

- Daddy 普通聊天、主动消息、Ombre-Brain、高德 MCP 和 Termux Bridge 均应正常。
- Harness 安装、停止或异常不得清理或占用上述服务的数据目录和端口。
- 测试只可在专用 Harness 烟雾测试目录中创建文件；不得修改用户其他文件。
