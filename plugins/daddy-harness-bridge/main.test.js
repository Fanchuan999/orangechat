const assert = require("node:assert/strict");
const bridge = require("./main.js");

const header = bridge.buildTaskEnvelope(
  "daddy-1700000000000-a",
  "修复 README",
  "2026-08-24T10:00:00.000Z",
  "只修改 README.md。"
);
assert.match(header, /task_id: daddy-1700000000000-a/);
assert.match(header, /title: 修复 README/);

const history = [
  { event: { type: "agent/inbox/spliced", data: {} } },
  { event: { type: "turn/start", data: {} } },
  { event: { type: "user/message", data: { content: [{ type: "text", text: header }] } } },
  { event: { type: "turn/end", data: {} } },
  { event: { type: "user/message", data: { content: [{ type: "text", text: bridge.buildTaskEnvelope("daddy-1700000000001-b", "第二件事", "2026-08-24T10:01:00.000Z", "只检查文件。") }] } } }
];
const tasks = bridge.parseTasks(history);
assert.equal(tasks.finished.length, 1);
assert.equal(tasks.active.length, 1);
assert.equal(tasks.active[0].status, "queued");
assert.throws(() => bridge.sanitizeRequirement("Authorization: secret"));
assert.throws(() => bridge.sanitizeRequirement("请带上 Ombre 记忆"));
assert.equal(
  bridge.inboxIdFromSessionList({
    items: [
      { sessionId: "other", projections: { values: { title: "别的会话" } } },
      { sessionId: "inbox", projections: { values: { title: "📥 Daddy收件箱" } } }
    ]
  }),
  "inbox"
);
assert.equal(bridge.inboxIdFromSessionList({ items: [] }), "");

console.log("Harness bridge tests passed");
