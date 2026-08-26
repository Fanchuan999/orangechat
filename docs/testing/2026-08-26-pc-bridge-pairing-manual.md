# Daddy 电脑协作（PC Bridge）手工配对与验收

> 这是一份**用户手动执行**的上线与验收清单。它不会自动部署 Supabase、不会安装 APK，也不会修改 Daddy 的聊天、Ombre-Brain、世界书、管理记忆或现有聊天 Supabase 表。

## 使用前确认

- 仅使用已审阅的 PC Bridge 配套源码/发行包，以及其公开的 Supabase Edge Function 地址。
- 电脑端 Bridge 的工作根目录应位于 **D 盘**。手机不会复制电脑工作区文件。
- PC 只需要在配对期间、或以后实际处理电脑任务时保持唤醒；不需要为了保留配对而持续运行。
- 高风险文件操作、批量覆盖、删除、外网访问、推送等，仍应在执行器的确认界面中由你手动确认。

## 第一次配对

1. 在开始前，从 Daddy 的“备份与恢复”导出当前备份包。这个配对功能不应触碰记忆系统，但先备份是上线前的保护措施。
2. 打开 Supabase 的 **SQL Editor**，只运行已审阅的 `docs/supabase/daddy_pc_bridge_v2.sql`。
   - 预期结果是：`Success. No rows returned`。
   - 该迁移只使用 `daddy_pc_bridge_*` 表和相关 RPC；不要把任何聊天、记忆或 Ombre 表放入迁移。
3. 在 Supabase 中，将 `daddy-pc-bridge` Edge Function 替换为已审阅的 `dashboard-index.ts` 部署版本。
   - 将该函数的 **legacy JWT verification** 设为关闭。
   - 这里关闭的是 Supabase 的旧 JWT 门槛；真正的安全门仍是 Bridge 的自定义 relay proof（时间戳、一次性 nonce、请求体摘要与 HMAC），不要把它改成匿名开放接口。
4. 在 Windows 电脑上配置该公开 Edge Function 端点，然后运行：

   ```text
   daddy-pc-bridge pair-pc
   ```

   终端会显示一条临时的 `DADDY-PC2:` 邀请码。只复制这一行到手机；它仅五分钟有效、只能使用一次。
5. 在手机打开 Daddy → **代码小屋** → **电脑协作** → **连接电脑**，粘贴邀请码，再点“确认连接”。
   - Daddy 只会展示经过校验的临时预览、电脑标签和连接状态；不会显示 relay token、AES 信封密钥或电脑路径。
6. 看到“已配对”后，先点“刷新状态”确认服务端状态正常；再作为验收的一部分选择“解除配对”，并在确认弹窗中确认。
   - PC 下一次发送桥接请求时应报告已撤销、需要重新配对。
   - 若要继续使用，重新从第 4 步生成一条新的邀请码；旧码不能复用。

## 严禁粘贴到手机的内容

以下内容不得进入 Daddy 聊天、代码小屋输入框、插件、WebView、截图或错误反馈：

- Supabase `service_role` key；
- 任何模型/提供商 API key；
- PC 或手机的 `relayToken`；
- AES 信封密钥、配对私钥或完整信封正文；
- 电脑工作目录的隐私文件清单。

手机端只应接收短时 `DADDY-PC2:` 邀请码。邀请码过期、被确认或被拒绝后都应丢弃，不要保存到笔记、任务历史或聊天记录。

## 状态与故障排查

### 手机上显示“电脑不可用”或刷新失败

先确认 PC Bridge 是否正在运行且电脑没有休眠；再确认 PC 配置的是同一个公开 `https://*.supabase.co/functions/v1/daddy-pc-bridge` 端点。不要把本机 `127.0.0.1`、HTTP 地址、带查询参数的地址或其他 Edge Function 地址填入配置。

### 粘贴后提示邀请码无效、已过期或已使用

不要反复粘贴旧码。回到 PC 重新运行 `daddy-pc-bridge pair-pc`，只使用新产生的一条 `DADDY-PC2:`。请在五分钟内完成手机确认。

### PC 已运行，但手机始终无法配对

按顺序检查：

1. SQL Editor 的迁移是否确实显示 `Success. No rows returned`；
2. `daddy-pc-bridge` 是否是已审阅的 Edge Function 版本，且 legacy JWT verification 已关闭；
3. PC 与手机是否使用同一个公开 Supabase 项目端点；
4. 是否误把密钥、旧 v1 邀请或非 `DADDY-PC2:` 文本当成邀请码。

以上检查都不需要读取、更改或清理 `chat_history`、Ombre-Brain 或其他 Daddy 记忆数据。

## 第一次上线的回退方式

若你不想继续测试：

1. 在手机的“电脑协作”卡片选择“解除配对”；
2. 确认 PC 已要求重新配对后，关闭 PC Bridge；
3. 如需恢复应用设置，使用第 1 步创建的 Daddy 备份。

不要通过删除聊天表、清空 Supabase 项目或手工删除 Android 应用数据来回退配对。解除配对是预期且可重复的回退路径。

## 验收记录

记录时只保留“成功/失败”和时间，不记录邀请码、端点中的私密参数、令牌、密钥、聊天正文或电脑路径。建议至少确认：

- SQL 迁移成功；
- 手机配对成功并可刷新；
- 解除配对后 PC 要求重新配对；
- Daddy 聊天、Ombre 和记忆系统仍保持原状。
