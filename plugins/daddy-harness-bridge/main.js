/* Daddy → Harness 工作台桥接插件 v1.1.0
 * 任务通过本机 Harness 的固定「📥 Daddy收件箱」串行会话执行。
 * 本插件不转发 Daddy 人设、世界书、Ombre、密钥或完整聊天记录。 */
var BASE = "http://127.0.0.1:3080";
var SESSION_KEY = "harness_inbox_session_id";
var WORKSPACE_CWD = "/data/daddy-harness/home";
var INBOX_TITLE = "📥 Daddy收件箱";

function clean(value) {
  if (value === null || value === undefined) return "";
  return String(value).replace(/^\s+|\s+$/g, "");
}

function taskId(now, random) {
  return "daddy-" + String(now) + "-" + String(random);
}

function taskTitle(brief) {
  var first = clean(brief).split(/\r?\n/)[0] || "Daddy 转达的任务";
  return first.slice(0, 80);
}

function buildTaskEnvelope(id, title, createdAt, brief) {
  return "【Daddy任务】\n" +
    "task_id: " + id + "\n" +
    "created_at: " + createdAt + "\n" +
    "title: " + clean(title).slice(0, 80) + "\n" +
    "【任务简报】\n" + clean(brief);
}

function sanitizeRequirement(value) {
  var brief = clean(value);
  if (!brief) throw new Error("需要提供任务简报");
  if (brief.length > 20000) throw new Error("任务简报请精简到 20000 字以内");
  var forbidden = [
    /api[_ -]?key/i,
    /authorization\s*:/i,
    /bearer\s+/i,
    /\bombre\b/i,
    /世界书/,
    /完整聊天记录/,
    /系统提示词/,
    /人设/,
    /密码[：:]/,
    /密钥[：:]/
  ];
  for (var i = 0; i < forbidden.length; i++) {
    if (forbidden[i].test(brief)) {
      throw new Error("任务简报不能包含人设、记忆、密钥或完整聊天内容，请只保留完成任务必需的信息");
    }
  }
  return brief;
}

function rpc(method, payload) {
  var rpcId = "bridge-" + String(new Date().getTime()) + "-" + String(Math.floor(Math.random() * 1000000));
  try {
    var response = fetch(BASE + "/api/" + method, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        type: "client-request",
        rpcId: rpcId,
        method: method,
        payload: payload
      })
    });
    if (!response.ok) return { ok: false, error: "HTTP " + response.status };
    var parsed = JSON.parse(response.body || "");
    if (!parsed || parsed.type !== "server-response") return { ok: false, error: "工作台返回了意外响应" };
    return parsed.result || { ok: false, error: "工作台没有返回结果" };
  } catch (error) {
    return { ok: false, error: "无法连接本机 Harness 工作台" };
  }
}

function inboxIdFromSessionList(value) {
  var items = (value && value.items) || [];
  for (var i = 0; i < items.length; i++) {
    var item = items[i] || {};
    var values = item.projections && item.projections.values;
    var title = clean((values && values.title) || item.title);
    var sessionId = clean(item.sessionId);
    if (title === INBOX_TITLE && sessionId) return sessionId;
  }
  return "";
}

function ensureInbox() {
  var sid = clean(dataStore.get(SESSION_KEY));
  if (sid) return { ok: true, sessionId: sid };
  var listed = rpc("session.list", {});
  if (listed.ok) {
    var existing = inboxIdFromSessionList(listed.value);
    if (existing) {
      dataStore.set(SESSION_KEY, existing);
      return { ok: true, sessionId: existing };
    }
  }
  var createdResult = rpc("session.create", { cwd: WORKSPACE_CWD });
  if (!createdResult.ok) return createdResult;
  var created = (createdResult.value && createdResult.value.sessionId) || "";
  if (!created) return { ok: false, error: "工作台没有返回会话 ID" };
  dataStore.set(SESSION_KEY, created);
  rpc("session.rename", { sessionId: created, title: INBOX_TITLE });
  return { ok: true, sessionId: created };
}

function eventOf(item) { return (item && item.event) || item || {}; }

function textFromEvent(event) {
  var content = event && event.data && event.data.content;
  if (!Array.isArray(content)) return "";
  var text = "";
  for (var i = 0; i < content.length; i++) {
    if (content[i] && content[i].type === "text" && content[i].text) text += String(content[i].text);
  }
  return text;
}

function headerFromText(text) {
  if (text.indexOf("【Daddy任务】") < 0) return null;
  var id = /(?:^|\n)task_id:\s*([^\r\n]+)/.exec(text);
  var createdAt = /(?:^|\n)created_at:\s*([^\r\n]+)/.exec(text);
  var title = /(?:^|\n)title:\s*([^\r\n]+)/.exec(text);
  if (!id) return null;
  return {
    taskId: clean(id[1]),
    createdAt: createdAt ? clean(createdAt[1]) : "",
    title: title ? clean(title[1]).slice(0, 80) : "Daddy 转达的任务"
  };
}

function parseTasks(events) {
  var tasks = [];
  var current = null;
  var turnOpen = false;
  for (var i = 0; i < (events || []).length; i++) {
    var event = eventOf(events[i]);
    if (event.type === "turn/start") {
      turnOpen = true;
      continue;
    }
    if (event.type === "user/message") {
      var header = headerFromText(textFromEvent(event));
      if (header) {
        current = {
          taskId: header.taskId,
          title: header.title,
          createdAt: header.createdAt,
          status: turnOpen ? "running" : "queued",
          result: ""
        };
        tasks.push(current);
      }
      continue;
    }
    if (event.type === "assistant/message" && current) {
      current.result = textFromEvent(event).slice(0, 160);
      continue;
    }
    if (event.type === "turn/end") {
      if (current && current.status === "running") current.status = "finished";
      turnOpen = false;
    }
  }
  var active = [];
  var finished = [];
  for (var j = 0; j < tasks.length; j++) {
    if (tasks[j].status === "finished") finished.push(tasks[j]); else active.push(tasks[j]);
  }
  return { active: active, finished: finished };
}

function relay_to_harness(params) {
  params = params || {};
  var brief;
  try { brief = sanitizeRequirement(params.requirement); } catch (error) {
    return { success: false, error: error.message || "任务简报无效" };
  }
  var inbox = ensureInbox();
  if (!inbox.ok) return { success: false, error: "无法连接工作台：" + inbox.error };
  var now = new Date();
  var id = taskId(now.getTime(), Math.floor(Math.random() * 1000000));
  var envelope = buildTaskEnvelope(id, taskTitle(brief), now.toISOString(), brief);
  var result = rpc("session.prompt", {
    sessionId: inbox.sessionId,
    mode: "queue",
    content: [{ type: "text", text: envelope }]
  });
  if (!result.ok) return { success: false, error: "转达失败：" + result.error };
  return {
    success: true,
    taskId: id,
    sessionId: inbox.sessionId,
    status: "queued",
    message: "任务已进入 Harness 收件箱队列，尚未宣告完成。"
  };
}

function check_harness_status() {
  var inbox = ensureInbox();
  if (!inbox.ok) return { success: false, error: "无法连接工作台：" + inbox.error };
  var result = rpc("session.history", { sessionId: inbox.sessionId, maxMessages: 200 });
  if (!result.ok) return { success: false, error: "查询失败：" + result.error };
  var tasks = parseTasks(((result.value || {}).events) || []);
  return {
    success: true,
    sessionId: inbox.sessionId,
    tasks: tasks.active,
    status: tasks.active.length ? tasks.active[0].status : "idle",
    message: tasks.active.length ? "收件箱有 " + tasks.active.length + " 个进行中的任务。" : "目前没有进行中的任务。"
  };
}

exports.relay_to_harness = relay_to_harness;
exports.check_harness_status = check_harness_status;
exports.taskId = taskId;
exports.buildTaskEnvelope = buildTaskEnvelope;
exports.sanitizeRequirement = sanitizeRequirement;
exports.parseTasks = parseTasks;
exports.inboxIdFromSessionList = inboxIdFromSessionList;
