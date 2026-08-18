# Daddy Harness Termux 烟雾测试

## 测试目标

确认 Daddy v2.5.29（v215）可在 iQOO Neo 10 上覆盖更新，并通过现有 Termux 环境安装、运行和自动恢复官方 DeepSeek Harness `0.1.0-rc.7`。

## 环境

| 项目 | 实际值 |
|---|---|
| 手机 | iQOO Neo 10 |
| Android / OriginOS | 待手机实测 |
| Daddy 包名 | `me.rerere.orangechat.companion` |
| Daddy 版本 | `2.5.29-companion`（215） |
| Termux 版本 | 待手机实测 |
| Node.js | 待手机实测 |
| npm | 待手机实测 |
| Harness | `@deepseek-ai/dsh@0.1.0-rc.7` |

## 桌面构建验证

| 检查项 | 结果 | 说明 |
|---|---|---|
| 全仓 JVM 单元测试 | 通过 | 与 Companion 打包合并执行，302 个 Gradle 任务完成，无测试失败 |
| Companion APK 构建 | 通过 | `:app:packageCompanion` 成功 |
| 包名 | 通过 | `me.rerere.orangechat.companion` |
| 版本 | 待重新构建验证 | `2.5.29-companion`（215） |
| CPU 架构 | 通过 | 仅 `arm64-v8a` |
| 签名 | 通过 | SHA-256 `ea958214d61fac06047eb4a5316bebf621d7da5a6d21af74768d144cc4217b68` |
| Android Lint | 旧债阻塞 | 全仓仍有 177 个既有错误；本次新增 Harness 代码未出现错误级问题 |

## 安装通道

| 检查项 | 结果 | 说明 |
|---|---|---|
| Daddy 一键安装 | 待复测 | v211 修 `$HOME`；v213 切到 rc.7；v214 禁止超时重放并延长等待；v215 通过 Termux `node` 启动官方入口，绕过包内 `/usr/bin/env` shebang |
| Termux RunCommand 回退 | 已打通，待复测安装 | 真机已授予 `com.termux.permission.RUN_COMMAND`，仅在本地桥失败时使用 |
| 手动复制备用命令 | 待测 | 不含 API Key 或 Harness 凭据 |
| 重复安装保留 `dsh-home` | 待测 | 不应覆盖登录与工作区数据 |

## 功能验收

| 检查项 | 结果 | 说明 |
|---|---|---|
| `GET http://127.0.0.1:3080/` 成功 | 待测 | 仅本机回环地址 |
| Daddy 内工作台打开 | 待测 | 外链交给浏览器 |
| 独立 provider / model 可保存 | 待测 | 不改变 Daddy 当前聊天模型 |
| 读取 `$HOME/daddy-harness/test/read.txt` | 待测 | 仅测试目录 |
| 创建 `$HOME/daddy-harness/test/write.txt` | 待测 | 仅测试目录 |
| `printf daddy-harness-alive` 返回标记 | 待测 | Shell 基础能力 |

## 生命周期验收

| 检查项 | 结果 | 说明 |
|---|---|---|
| 杀掉 Harness 子进程后约 3 秒恢复 | 待测 | watchdog 快速恢复 |
| 点 Daddy“停止”后 30 秒内不恢复 | 待测 | `.manual-stop` 必须生效 |
| 点“启动”后恢复服务 | 待测 | 清除手动停止标记 |
| 手机重启后恢复 | 待测 | 依赖 Termux:Boot 与系统自启动权限 |
| 15 分钟后台兜底 | 待测 | WorkManager，不使用前台服务 |

## 安全检查

- Harness 仅监听 `127.0.0.1:3080`。
- 状态页日志最多 200 行 / 24 KiB，并隐藏 Authorization、Cookie、API Key 与 token。
- Android 不读取或输出 Harness 凭据文件。
- 手动停止优先于脚本守护、开机恢复和 WorkManager 恢复。
