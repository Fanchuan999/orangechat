# PC Relay Feasibility Prototype Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the actual Windows `claude.cmd` + cc switch environment can stream task events and hold a single permission request open for a remote decision, without touching a real project.

**Architecture:** A standalone Node/TypeScript prototype starts `claude.cmd -p` in a user-selected empty test directory, parses JSON Lines from `--output-format stream-json`, and exposes one local MCP permission tool. It launches Claude with the temporary `--mcp-config` and exact `--permission-prompt-tool <generated MCP tool name>` flag. The tool blocks on a local decision file/HTTP callback; it must return `allow` or `deny` to the same live process. No Supabase, Daddy API, Android code, or real project path is used here.

**Tech Stack:** Node.js 20+, TypeScript, Node built-in test runner, `child_process`, `@modelcontextprotocol/sdk`, Claude Code CLI.

**Spec:** `docs/superpowers/specs/2026-08-25-harness-mobile-desktop-relay-design.md`

## Global Constraints

- Run only from a new empty directory selected by the user; never default to the Daddy repository.
- Invoke `claude.cmd`, never `claude.ps1`.
- Use `claude.cmd -p --output-format stream-json --verbose --mcp-config <temp-config> --permission-prompt-tool <generated-tool-name>`; do not use `--dangerously-skip-permissions`.
- No secret, full prompt, environment variable, or raw Claude output may be committed to the repository.
- A failure to keep the permission tool alive is a failed prototype, not permission to simulate recovery with a new worker.

---

### Task 1: Create an isolated, testable probe package

**Files:**
- Create: `tools/daddy-pc-relay/package.json`
- Create: `tools/daddy-pc-relay/tsconfig.json`
- Create: `tools/daddy-pc-relay/src/probe/contracts.ts`
- Create: `tools/daddy-pc-relay/src/probe/ProbeStateStore.ts`
- Test: `tools/daddy-pc-relay/test/ProbeStateStore.test.ts`

**Interfaces:**
- Produces `ProbeRun`, `ProbeEvent`, `PermissionDecision`, and `ProbeStateStore` for later tasks.
- `ProbeStateStore.create(run): void`, `append(event): void`, `decide(requestId, decision): boolean`, and `read(runId): ProbeRun` are synchronous local-only APIs.

- [ ] **Step 1: Write failing state tests**

```ts
const store = new ProbeStateStore(tempDir)
const run = store.create({ cwd: tempDir, startedAt: 1 })
expect(store.decide('unknown', 'allow')).toBe(false)
store.append({ runId: run.id, sequence: 1, kind: 'permission-request', requestId: 'p1' })
expect(store.decide('p1', 'allow')).toBe(true)
expect(store.read(run.id).events.at(-1)?.kind).toBe('permission-allow')
```

- [ ] **Step 2: Run the test and confirm it fails because the store is absent**

Run: `npm test -- --test-name-pattern=ProbeStateStore`

- [ ] **Step 3: Implement the JSON-file state store**

Use `randomUUID()` for `run.id`, store data below a caller-provided temporary directory, and atomically write via `*.tmp` then rename. The persisted model must contain only protocol fields, timestamps, short action digests, and process exit state.

- [ ] **Step 4: Re-run the test and inspect the generated fixture**

Run: `npm test -- --test-name-pattern=ProbeStateStore`

- [ ] **Step 5: Commit the package skeleton and passing state-store tests**

```bash
git add tools/daddy-pc-relay/package.json tools/daddy-pc-relay/tsconfig.json tools/daddy-pc-relay/src/probe/contracts.ts tools/daddy-pc-relay/src/probe/ProbeStateStore.ts tools/daddy-pc-relay/test/ProbeStateStore.test.ts
git commit -m "feat: scaffold PC relay feasibility probe"
```

### Task 2: Parse Claude stream-json without assuming undocumented fields

**Files:**
- Create: `tools/daddy-pc-relay/src/probe/ClaudeStreamDecoder.ts`
- Create: `tools/daddy-pc-relay/test/fixtures/claude-stream.jsonl`
- Test: `tools/daddy-pc-relay/test/ClaudeStreamDecoder.test.ts`

**Interfaces:**
- Consumes `ProbeEvent` from Task 1.
- Produces `decodeClaudeLine(line, sequence): ProbeEvent | null`; unknown JSON must become `kind: 'unknown'` with no copied sensitive payload.

- [ ] **Step 1: Add failing fixture tests for malformed JSON, text progress, tool events, final result, session ID, and unknown events**

```ts
expect(decodeClaudeLine('{not-json}', 1)?.kind).toBe('decoder-error')
expect(decodeClaudeLine(fixtureToolUse, 2)).toMatchObject({ kind: 'tool-call', sequence: 2 })
expect(decodeClaudeLine(fixtureResult, 3)?.sessionId).toBe('session-safe-fixture')
```

- [ ] **Step 2: Run only the decoder tests and confirm failure**

Run: `npm test -- --test-name-pattern=ClaudeStreamDecoder`

- [ ] **Step 3: Implement a line-buffered JSON decoder**

Parse one newline-delimited object at a time. Map only documented/observed safe fields to `kind`, `sessionId`, `toolName`, `actionDigest`, `costUsd`, and a maximum 1,000-character message. Preserve raw JSON only in the local untracked probe directory when the user explicitly enables diagnostic mode.

- [ ] **Step 4: Run the decoder tests and TypeScript typecheck**

Run: `npm test -- --test-name-pattern=ClaudeStreamDecoder`

Run: `npm run typecheck`

- [ ] **Step 5: Commit decoder and fixtures**

```bash
git add tools/daddy-pc-relay/src/probe/ClaudeStreamDecoder.ts tools/daddy-pc-relay/test/fixtures/claude-stream.jsonl tools/daddy-pc-relay/test/ClaudeStreamDecoder.test.ts
git commit -m "feat: decode Claude stream events for relay probe"
```

### Task 3: Implement a blocking local permission MCP tool

**Files:**
- Create: `tools/daddy-pc-relay/src/probe/PermissionMcpServer.ts`
- Create: `tools/daddy-pc-relay/src/probe/DecisionWaiter.ts`
- Test: `tools/daddy-pc-relay/test/DecisionWaiter.test.ts`
- Test: `tools/daddy-pc-relay/test/PermissionMcpServer.test.ts`

**Interfaces:**
- `DecisionWaiter.wait(requestId, expiresAt): Promise<'allow' | 'deny' | 'expired'>` remains unresolved until a matching decision or expiry.
- MCP tool name is `daddy_probe_permission`; it returns the JSON-stringified Claude permission payload `{ behavior: 'allow', updatedInput }` or `{ behavior: 'deny', message }`.

- [ ] **Step 1: Write failing tests proving an unrelated approval cannot resolve a waiter**

```ts
const wait = waiter.wait('request-a', Date.now() + 5_000)
waiter.resolve('request-b', 'allow')
await expect(withTimeout(wait, 20)).rejects.toThrow('timed out')
waiter.resolve('request-a', 'deny')
await expect(wait).resolves.toBe('deny')
```

- [ ] **Step 2: Implement `DecisionWaiter` with one-time consumption and expiry**

After `resolve`, delete the waiter; duplicate `allow` calls must return `false`. Expiry returns `deny` with a clear reason rather than throwing an unhandled error.

- [ ] **Step 3: Write the MCP server test**

The test must invoke the tool, verify it remains pending, resolve the exact request ID, then assert a single `allow` response containing unchanged `updatedInput`.

- [ ] **Step 4: Implement the MCP server and run all probe unit tests**

Run: `npm test`

- [ ] **Step 5: Commit the permission-waiting primitive**

```bash
git add tools/daddy-pc-relay/src/probe/PermissionMcpServer.ts tools/daddy-pc-relay/src/probe/DecisionWaiter.ts tools/daddy-pc-relay/test/DecisionWaiter.test.ts tools/daddy-pc-relay/test/PermissionMcpServer.test.ts
git commit -m "feat: add blocking permission tool to PC relay probe"
```

### Task 4: Run and record a user-approved real CLI probe

**Files:**
- Create: `tools/daddy-pc-relay/src/probe/runProbe.ts`
- Create: `tools/daddy-pc-relay/scripts/run-safe-probe.ps1`
- Create: `docs/testing/2026-08-25-pc-relay-probe.md`
- Test: `tools/daddy-pc-relay/test/runProbe.test.ts`

**Interfaces:**
- `runSafeProbe({ cwd, prompt, timeoutMs }): Promise<ProbeRun>` spawns `claude.cmd` with `-p`, `--output-format stream-json`, `--verbose`, a temporary `--mcp-config`, its exact generated `--permission-prompt-tool` name, and a maximum-turns limit of `2`.

- [ ] **Step 1: Write a spawn-argument test**

```ts
expect(buildClaudeArgs('describe only', probeMcpConfig)).toEqual(expect.arrayContaining([
  '-p', 'describe only', '--output-format', 'stream-json', '--verbose',
  '--mcp-config', probeMcpConfig, '--permission-prompt-tool', probePermissionToolName,
  '--max-turns', '2',
]))
expect(buildClaudeCommand()).toBe('claude.cmd')
```

- [ ] **Step 2: Implement the safe PowerShell launcher**

The launcher must require an existing empty directory, reject the Daddy repository path, print the exact command, and require `-Confirm` before invoking Claude. Its default prompt is read-only: “State the current directory and request no tools.”

- [ ] **Step 3: Run the no-model dry-run and unit suite**

Run: `npm test`

Run: `powershell -ExecutionPolicy Bypass -File scripts/run-safe-probe.ps1 -Cwd <empty-test-directory>`

Expected: prints the command and exits before model invocation because `-Confirm` is absent.

- [ ] **Step 4: With user approval, run the real read-only probe and a controlled permission probe**

Use a freshly created empty directory. Record only feature outcomes: CLI authentication works/does not work, observed event keys, session ID availability, whether same-process allow/deny works, timeout behavior, and process behavior after interruption.

- [ ] **Step 5: Decide the next gate from evidence and commit the report**

If same-process remote permission does not work, mark PC Relay high-risk approval as blocked and do not begin the production relay. If it works, record the exact observed interface contract for the next plan.

```bash
git add tools/daddy-pc-relay/src/probe/runProbe.ts tools/daddy-pc-relay/scripts/run-safe-probe.ps1 tools/daddy-pc-relay/test/runProbe.test.ts docs/testing/2026-08-25-pc-relay-probe.md
git commit -m "test: document PC relay CLI feasibility"
```
