# Autonomous Social Activity for Daddy

## Goal

Extend Daddy's existing main-window idle-exploration loop so that, during a
small number of user-authorized idle opportunities, Daddy may choose exactly
one meaningful activity:

1. read public webpages using the existing read-only exploration tools;
2. use explicitly authorized tools from an already-configured forum MCP
   service, including publishing, commenting, reacting, or a service-provided
   login action; or
3. proactively visit a Visitor Lounge friend who has granted proactive-visit
   consent.

The user must also be able to tell Daddy in the normal chat to visit a named
friend. That request must invoke a real native tool rather than relying on an
LLM-produced hidden marker or the model's unsupported claim that it can see a
Visitor Lounge tool.

## Existing behavior and gap

`IdleExploreWorker` already schedules one to three low-frequency exploration
opportunities per day and routes them to `ProactiveMessageTriggerService` in
the user's selected primary conversation. Today that service intentionally
exposes only public search and page-reading tools. It excludes every MCP tool
and cannot see the Visitor Lounge coordinator.

Visitor Lounge visits are currently launched only from UI or from a hidden
`[[VISIT_LOUNGE:...]]` marker parsed after a normal chat reply. The marker is
not a tool presented to the model, so the model can correctly see no Visitor
Lounge capability in its tool list even when the app's manual visit screen can
complete a visit.

## Chosen design

### Permission model

Add an `AutonomousActivitySetting` under the existing proactive-message
settings. It is disabled by default, including for users upgrading from older
versions.

The settings page exposes:

- a master switch: `允许 Daddy 空闲时自动对外活动`;
- the existing idle-exploration schedule and daily opportunity limit (1--3);
- a per-MCP-server list of discovered tools, with a separate opt-in for each
  tool that may run during idle activity;
- a concise warning for tools selected for automatic publication, comments,
  reactions, or login; and
- recent activity records with target, action, time, result, and a redacted
  summary.

The automatic-activity allowlist is independent of normal MCP enablement and
the normal chat tool-approval settings. Selecting a tool for autonomous use
does not relax its permission anywhere else. A selected tool is only callable
from the idle worker while the new master switch remains enabled.

Visitor Lounge keeps its existing per-friend consent model. The idle worker
may only choose friends with `ALLOW_PROACTIVE`; a manual chat request may use
an existing saved friend normally. Existing per-friend cooldown and global
Visitor Lounge visit limits continue to apply.

### Authentication and secrets

The app never asks the model for forum passwords, Visitor Keys, cookies, OAuth
tokens, or one-time codes. It reuses the selected MCP service's existing
configured connection and authentication state.

An explicitly selected service-provided login tool may run automatically only
when it can complete from credentials or a session already stored by that
service. If it needs an interactive browser, a QR scan, CAPTCHA, password, or
one-time code, the action fails safely, is recorded, and is not retried in a
tight loop. Neither the activity log nor chat transcript exposes credentials.

### Direct chat visits

Introduce a native `visit_visitor_lounge` tool in Daddy's regular local tool
set. Its input is a saved friend identifier and a short topic; neither an MCP
endpoint nor a Visitor Key is exposed to the model.

The tool starts a manual visit through `VisitorLoungeVisitCoordinator`, returns
a truthful `started` or rejection status to the current tool turn, and later
adds the existing redacted report card once the visit completes. The tool
description requires an explicit user request to visit a saved friend. This
makes a request such as "去拜访兜兜，问候一下" actionable in the chat, while
the result remains observable instead of fabricated by the model.

### Idle decision and execution

Reuse `IdleExploreWorker` and `ProactiveMessageTriggerService`; no second
background scheduling loop is introduced.

At each existing idle opportunity, the model receives only:

- the normal read-only web exploration tools;
- the native proactive Visitor Lounge tool, only when there is at least one
  consented, policy-eligible friend; and
- the user-selected MCP forum tools from the autonomous allowlist.

The system prompt identifies these as three activity families: web, forum,
and Visitor Lounge. It may choose no activity. A runtime activity guard locks
the first tool call to one family for the whole opportunity: a forum activity
may use up to the existing three tool steps, but it cannot mix with a separate
web exploration or Visitor Lounge visit in that same opportunity. A lounge
visit occupies the opportunity when launched.

The existing one-to-three daily idle opportunities remain the upper bound for
all three families combined. Direct chat visits are not charged to that idle
budget. Visitor Lounge's independent friend cooldown and visit cap remain an
additional bound.

### Activity records and user-facing results

Persist a bounded autonomous-activity record for every attempted idle action,
including skipped/failed actions where a safe summary is available. Records
include the selected family, optional MCP server and tool display names,
timestamp, terminal status, and redacted summary. No raw request arguments,
credentials, cookies, or OAuth tokens are stored.

Successful activities may still create a brief normal proactive chat update
when there is something worth sharing. Otherwise Daddy remains quiet. The
settings page records the action regardless, so an automatic forum post or
Visitor Lounge visit is auditable even when no chat message is sent.

### Failure handling

- An unavailable or unauthenticated selected MCP service is skipped and logged
  as a single failed activity; no immediate retry loop is created.
- A selected tool that disappears after an MCP reconnect is not exposed and
  appears as unavailable in settings until rediscovered.
- Visitor Lounge errors retain redaction and do not expose the endpoint's
  credential.
- If the normal chat model does not call `visit_visitor_lounge`, the app does
  not claim a visit happened. If it calls the tool, its returned status and
  later report card are the source of truth.

## Components

1. **Settings and policy** -- serializable autonomous-activity configuration,
   validation helpers, and a bounded database record/migration.
2. **Permission UI** -- a new section beside "空闲时自己找点事" listing eligible
   MCP services and tools, an explicit warning before enabling automatic
   external actions, and recent activity history.
3. **Visitor Lounge native tool** -- a narrow local tool adapter around
   `VisitorLoungeVisitCoordinator`, shared by normal chat and idle activity.
4. **Idle tool broker** -- builds the restricted, user-authorized idle tool
   set and enforces the single-family activity guard plus activity recording.
5. **Proactive integration** -- expands the existing idle prompt while
   preserving normal proactive messages, Night Watch, user-selected primary
   conversation routing, and current web exploration behavior.

## Test and release plan

1. Add unit tests for independent MCP tool allowlisting, default-off settings,
   disappearing tools, and the one-family-per-opportunity guard.
2. Add unit tests for the native Visitor Lounge tool: no raw credential in its
   schema/output, truthful start states, and no proactive access without
   friend consent.
3. Add policy tests covering combined daily idle limits, existing Visitor
   Lounge cooldown, a no-action choice, and authentication-required failures.
4. Run focused unit tests and the available app test suite; document any
   pre-existing Gradle test-executor limitation separately from test results.
5. Build the arm64 companion APK, verify its incremented version and signing,
   then copy the verified artifact to `D:\Daddy-安装包` with a clear filename.

## Non-goals

- No automatic use of unselected MCP servers or tools.
- No collection, display, or model access to login passwords, QR codes,
  Visitor Keys, OAuth tokens, or cookies.
- No uncontrolled retries, background browser launches, CAPTCHA solving, or
  bypassing forum/platform rules.
- No deletion or replacement of existing chats, MCP configuration, Visitor
  Lounge records, or manual visit controls.
