# 高德入口、聊天图片清理与情绪天气实施计划

> 设计依据：`docs/superpowers/specs/2026-08-12-amap-media-cleanup-affect-design.md`。
> 用户已确认直接实施；所有改动留在 `codex/unified-wake-desire` 工作树。

## 1. 先建立可测试的本地规则

- 为聊天图片写一个纯 Kotlin 的候选筛选策略：仅将 `upload/` 中的图片分成“聊天仍引用”“聊天未引用且不受头像/背景/小屋保护”，并按创建时间段过滤。
- 为情绪天气写纯 Kotlin 检测器：只识别明确词表；否定、提问、解释词义三类上下文不触发；相同事件只刷新，不累计成无限条。
- 先为这两套规则补 JVM 测试，先看到失败，再实现到通过。

## 2. 迁移高德路线入口

- 在 `SettingSystemToolsPage` 的“位置服务”卡片中注入既有 `AmapMcpService`，保留同一个 `CompanionSpaceSetting.amapRouteSetting` 和现有 Termux/MCP 安装逻辑。
- 将原小屋中的配置卡和对话框移除；设置页使用相同的 Key 校验、保存、重新安装和失败提示。
- 不迁移数据，不新建 Key 字段；更新后已有 Key 和本机 `127.0.0.1:8001` 服务继续可用。

## 3. 完成聊天图片清理闭环

- 在 `ConversationRepository` 提供一次性读取完整消息节点的只读方法；用它建立聊天文件引用集合。
- 新增 `ChatMediaStorageService`：安全模式仅删不被聊天、头像、显示素材、小屋照片引用的上传图片；永久模式先把所有引用这些图片的消息替换为 `【图片已清理】`，再删磁盘文件及 managed-files 条目。
- 永久替换递归处理工具输出里的图片，保证没有残留的坏链接；不处理非图片附件。
- 将 `SettingFilesPage` 改为“安全清理 / 彻底删除聊天图片”两模式：时间筛选、全选/取消、单选、空间统计与永久删除二次确认。默认安全模式且不自动选中任何文件。

## 4. 接入共享情绪天气

- 为 `CompanionMoodSetting` 增加可序列化的短期事件（种类、强度、记录/到期时间、规则来源），最多三条；旧设置自动使用空列表，无迁移风险。
- `CompanionMoodEngine.recordUserMessage(text)` 在既有用户消息成功写入后本地更新情绪与九维；不增加网络、模型、Ombre 或 Supabase 调用。
- `promptContext` 仅用自然语言输出当前天气和必要的“少讲道理、先陪着”语气提示，继续处于稳定人设/工具提示之后、聊天上下文之前。
- 扩展九维面板显示当前天气、到期时间、注入预览及清除按钮；使用相同的全局 `CompanionMoodSetting`，不绑定窗口。

## 5. 验证与交付

- 运行新增及相关 JVM 测试，再运行 `:app:assembleCompanion`。
- 用 aapt 检查 APK 包名、版本和标签；只在构建成功后复制一份到 `D:\Daddy-安装包`（不覆盖已有安装包）。
- 不提交或推送，除非用户随后明确要求。
