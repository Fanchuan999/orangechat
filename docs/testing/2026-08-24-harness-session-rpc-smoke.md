# Harness 收件箱 RPC 冒烟记录（已脱敏）

日期：2026-08-24。测试仅在手机本机 `127.0.0.1:3080` 上进行。

## 已证实接口

- `session.list`：请求载荷为 `{}`，返回 `items[]`；会话标题位于 `projections.values.title`。
- `session.history`：请求载荷含 `sessionId` 与 `maxMessages`；返回 `events[]`。
- 事件外层字段为 `event`，内层为 `{ type, seq, time, data }`。

## 收件箱最小队列任务的实测事件顺序

```text
agent/inbox/spliced
turn/start
step/start
user/message
request/header
request/context
assistant/chunk
assistant/message
step/end
turn/end
```

`agent/inbox/spliced` 表示任务已被接入队列；`turn/start` 表示执行开始；`turn/end` 表示这一回合闭合。实测样本中未发现可可靠识别“等待用户确认”的独立事件字段。因此原生看板不得把未知事件猜成等待确认。

## 脱敏约束

本记录和 fixture 不保存真实会话 ID、绝对路径、认证头、cookie、API Key、令牌或用户对话正文。
