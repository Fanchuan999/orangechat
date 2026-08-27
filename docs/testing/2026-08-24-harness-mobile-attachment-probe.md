# Harness 手机附件探测（已核实）

日期：2026-08-24。

## 结论

- Harness Web 工作台当前没有 `input[type=file]`、拖放区或粘贴图片入口，因此 WebView 无法自行调起系统文件选择器。
- `session.prompt` 已正式支持图片内容块：`image/png`、`image/jpeg`、`image/webp`、`image/gif`。单张最大 5 MB，最多 20 张，服务端会校验图片完整性。
- 当前附件后端只有图片持久化。普通文件没有通用上传 API、multipart 或 blob 通道；目录选择器也只是工作目录选择。

## Daddy 的安全处理

代码小屋提供原生“发送图片到 Harness 收件箱”入口。它只读取用户主动选取的一张支持格式图片，并通过本机 `session.prompt` 的正式 `image` 内容块进入固定收件箱。界面明确提示：视觉理解取决于当前 Harness 工作模型是否声明支持视觉输入。

不做 WebView DOM 注入，不把普通文件伪装成附件，也不把绝对路径塞进提示词。普通文件继续在完整工作台通过受确认保护的工具处理。
