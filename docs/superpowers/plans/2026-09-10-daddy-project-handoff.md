# Daddy / OrangeChat project handoff

Updated: 2026-09-10 (Asia/Shanghai)

This is a practical handoff for the next Codex window. Read it before editing. It distinguishes the current, verified work from older parallel worktrees whose changes may not be merged.

## 1. Product direction currently agreed with the user

This is an Android Companion build of OrangeChat/RikkaHub, personalized as **Daddy**.

- Daddy can use normal model providers and normal MCP servers.
- The user wants ordinary chat to remain able to ask Daddy to use an MCP service (including a visitor-lounge MCP).
- When the user enables the idle "find something to do" feature, Daddy may choose either ordinary web reading or a user-selected, already-connected generic MCP tool. The MCP tool itself may provide posting, commenting, liking, login, or conversation; its server permissions and the existing login session remain authoritative.
- The old built-in **Visitor Lounge** feature was intentionally removed. It was a redundant, separate protocol/key/UI on top of generic MCP. Do not reintroduce it unless the user explicitly changes this product decision.
- The user wants automatic activity only through explicitly selected existing MCP tools; do not silently enable every MCP tool and do not auto-create fresh logins/authorizations.

## 2. Exact worktree to continue in

Use this worktree, not the repository root and not a similarly named older worktree:

```text
D:\small progect\ai_chat\orangechat-minimal\.worktrees\daddy-unified-lounge-go
```

Current branch: `codex/daddy-unified-lounge-go`
Base HEAD: `f4ccacd39bd7d6fd38133b78615a27e0d2ef1457` (`build: bump companion version to 2.5.52`)

Important: the current Visitor Lounge removal / generic MCP changes are **uncommitted**. Preserve them. Before any edit, run:

```powershell
git status --short
git diff -- app/build.gradle.kts app/src/main/java/me/rerere/rikkahub
```

There are several other worktrees (for example PC bridge, Code Hut, and a historical visitor-lounge branch). They are not proof that their code is merged here. Inspect and merge deliberately; never copy a whole worktree or reset this one to make the paths look simpler.

## 3. What has been implemented and verified in this worktree

### Unified generic MCP / removal of the dedicated lounge

Implemented locally, built, and JVM-tested:

- Removed dedicated Visitor Lounge pages, routes, special tool surface, visitor-key storage/access flow, protocol/client/repository/coordinator, and special local reports.
- Kept generic MCP as the sole integration route. A visitor lounge is now configured and authorized exactly like every other MCP server.
- Updated user-facing idle-activity language from a special "forum/lounge" model to generic **MCP tools**.
- Idle activity discovers only currently connected MCP tools and requires a separate per-tool user opt-in.
- Calls from autonomous activity use the generic MCP gateway with an already-existing connection. They do not start an OAuth/login flow and redact call arguments in logs.
- Regular chat MCP access remains intact; the removal affects only the dedicated duplicate lounge feature.
- Added Room migration `31 -> 32`. It drops the old `visitor_lounge_*` tables and removes historical autonomous activity records marked `VISITOR_LOUNGE`.
- Restored the generic autonomous-activity Room DAO/entity into standalone files after removing the old shared lounge files. This avoids breaking the database/KSP build.

### Latest build artifact

The built ARM64 Companion APK is:

```text
D:\Daddy-安装包\Daddy-v2.5.53-通用MCP-Companion-arm64.apk
```

Verified metadata:

```text
Package:     me.rerere.orangechat.companion
Version:     2.5.53-companion (versionCode 239)
SHA-256:     1A83632C0C308C7FD460007C882A5EE278C16A524AB59AFB3753383D07CA66B6
```

The user reported this APK installed successfully. The APK was also signature-checked (expected project debug certificate).

### Test/build evidence at the time of this handoff

All ran successfully against this source state:

```text
:app:testDebugUnitTest                         362 tests, 0 failures, 0 errors
AutonomousIdlePromptTest                        2 tests, 0 failures, 0 errors
AutonomousActivityToolSurfaceTest               5 tests, 0 failures, 0 errors
AutonomousActivityPolicyTest                    4 tests, 0 failures, 0 errors
:app:assembleCompanion                          succeeded
```

## 4. Device-level acceptance checks still worth doing

These have not been fully exercised end-to-end after the product simplification. Start with a harmless visitor/MCP greeting rather than a post or automated social action.

1. Confirm the old dedicated Visitor Lounge entry/page no longer appears in the Companion space or route targets.
2. Add the visitor lounge as a normal MCP server, complete the server's required OAuth/authorization, and confirm its tools are listed as connected.
3. In a normal chat, ask Daddy to use that connected MCP tool and confirm the remote side receives/claims the interaction as expected.
4. Enable idle exploration and autonomous activity, then explicitly opt in to only one safe MCP tool. Confirm it becomes selectable in settings and that no non-selected MCP tool is exposed to idle activity.
5. Confirm regular web-only idle exploration still works when no MCP tool is selected.
6. If the user wants a privacy purge beyond functional removal, decide whether to add a one-time cleanup for old encrypted DataStore visitor-secret bytes. Current migration makes the old secret unreachable; it does **not** claim to physically overwrite every historical byte.

## 5. Current uncommitted change shape

Expected changes include:

- `app/build.gradle.kts` — Companion version is now 2.5.53 / code 239.
- Visitor Lounge source/UI/tests are deleted.
- Generic activity files changed: `AutonomousActivityPolicy`, `AutonomousActivityRepository`, `AutonomousActivityToolSurface`, `ProactiveMessageService`, and `SettingProactiveMessagePage`.
- App routes, DI, database, chat/tool surfaces, and Companion page changed to remove special lounge references.
- New database files:
  - `data/db/dao/AutonomousActivityDao.kt`
  - `data/db/entity/AutonomousActivityEntity.kt`
  - `data/db/migrations/Migration_31_32.kt`
  - schema `app/schemas/.../32.json`

There is also an untracked, user-owned planning file:

```text
docs/superpowers/plans/2026-09-09-autonomous-social-activity.md
```

Do not delete, overwrite, or accidentally commit it as part of an unrelated cleanup. This handoff document is also untracked until deliberately added.

## 6. Known separate areas: do not overstate their status

### PC Bridge / Code Hut

The app has a PC-task bridge / Code Hut/Harness area. It has been developed in separate worktrees and had earlier diagnostics such as `model_failed`, `model_terminated`, and `model_launch_failed` while running the Node worker on Windows. This is **not verified solved** by the current generic MCP work.

The desktop worker command previously used was:

```powershell
node --experimental-strip-types src\cli\main.ts worker --once --diagnose
```

Run it only from the actual bridge tool directory, then diagnose the precise output. Windows `.cmd` spawning and paths with spaces/non-ASCII characters were implicated before. Do not claim the bridge is fixed merely because the Android app builds.

### Model providers

- Generic OpenAI-compatible providers work through Base URL + path concatenation. For a standard OpenAI-compatible endpoint, Daddy normally needs Base URL including `/v1` plus path `/chat/completions`.
- "OpenCode Go" has a provider-side session-routing requirement (`x-opencode-session`) and should not be treated as a normal generic API-key provider without verifying its official integration requirements.
- A cheap third-party marketplace provider recently returned `Concurrency limit exceeded for account`. That is a provider/account capacity issue, not an Android application fix. The user decided not to use it. Do not add it as an automatic/idle default.
- Never paste API keys, Visitor Keys, OAuth tokens, or full authorization URLs into logs, commits, issues, screenshots, or handoff notes. Any secret previously shown in a screenshot should be rotated by its owner.

## 7. Build and test instructions

Run commands from the unified worktree. On this Windows setup, Gradle sometimes needs a project-local temporary directory and conservative workers:

```powershell
$taskTemp = Join-Path (Get-Location) '.cache\java-temp'
New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null
$env:TEMP = $taskTemp
$env:TMP = $taskTemp
.\gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 --console=plain :app:testDebugUnitTest
.\gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 --console=plain :app:assembleCompanion
```

Expected output APK before copying to the friendly release folder:

```text
app\build\outputs\apk\companion\app-arm64-v8a-companion.apk
```

The Gradle wrapper occasionally returns partial terminal output while a descendant JVM is finishing. Verify the generated APK and test XML/report instead of treating a truncated terminal response as a failure. Avoid parallel builds against the same worktree.

## 8. Recommended next sequence

1. Read `git status` and this handoff. Keep the current worktree untouched until its diff is understood.
2. Do the six device acceptance checks in section 4 with the user; record any concrete unexpected behavior and diagnose it before editing.
3. If acceptance is good, run the full unit test command again and make a focused commit containing only the unified MCP/lounge-removal work. Keep the user-owned plan separate unless the user asks otherwise.
4. Only after that, pick one independent next project area with the user (PC bridge reliability, Code Hut, model-provider UX, or idle-activity UX). Do not combine them in the same commit.
5. Before claiming an APK or behavior is fixed, build/install-test it and report exact evidence, version, and artifact path.

## 9. Suggested first message for the next Codex window

```text
Please continue the Daddy / OrangeChat project. First read:
D:\small progect\ai_chat\orangechat-minimal\.worktrees\daddy-unified-lounge-go\docs\superpowers\plans\2026-09-10-daddy-project-handoff.md

Work only in the `daddy-unified-lounge-go` worktree for the current task. It contains important uncommitted changes removing the dedicated Visitor Lounge in favor of generic MCP. Preserve them; start by inspecting `git status` and the diff. Help me perform the remaining device acceptance checks before making new product changes.
```
