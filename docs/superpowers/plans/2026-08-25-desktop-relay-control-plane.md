# Desktop Relay Control Plane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Windows PC Relay after the feasibility prototype passes, with immutable task/attempt identities, local workspace confinement, same-process remote approval, and no automatic duplicate execution.

**Architecture:** The Node Relay owns Windows path resolution, process lifetime, attempt leases, and Claude Code’s permission MCP tool. It talks to a dedicated encrypted mailbox; it never accepts raw absolute paths or writes project data to the mailbox. Supabase stores opaque envelopes and guarded metadata only.

**Tech Stack:** Node.js 20+, TypeScript, SQLite local state, Supabase PostgREST/RPC, Web Crypto AES-GCM/HKDF, Claude Code CLI/MCP, Windows Task Scheduler.

**Spec:** `docs/superpowers/specs/2026-08-25-harness-mobile-desktop-relay-design.md`

## Global Constraints

- Start only after `2026-08-25-pc-relay-feasibility-prototype.md` records a passing same-process permission contract.
- Never auto-run a lease-expired task or reuse an `attemptId`.
- Workspace roots exist only locally; requests use `workspaceId` plus relative scope.
- Do not auto-stash, reset, checkout, or overwrite dirty user work.
- Desktop Relay runs as the signed-in Windows user and invokes `claude.cmd` only.

---

### Task 1: Define durable task, attempt, event, and lease contracts

**Files:**
- Create: `tools/daddy-pc-relay/src/domain/Task.ts`
- Create: `tools/daddy-pc-relay/src/domain/TaskStateMachine.ts`
- Create: `tools/daddy-pc-relay/src/domain/Lease.ts`
- Test: `tools/daddy-pc-relay/test/TaskStateMachine.test.ts`

- [ ] **Step 1: Write failing transition tests for queued, phone approval, running, risk approval, completed, failed, cancelled, interrupted, and orphaned**
- [ ] **Step 2: Implement immutable `taskId`, per-run `attemptId`, monotonic `sequence`, idempotency keys, and `fencingToken` validation**
- [ ] **Step 3: Add tests proving stale-token events and a used approval nonce are rejected**
- [ ] **Step 4: Run `npm test -- --test-name-pattern=TaskStateMachine`**
- [ ] **Step 5: Commit the domain state machine**

### Task 2: Implement local workspace registry and path/dirty-worktree guard

**Files:**
- Create: `tools/daddy-pc-relay/src/workspace/WorkspaceRegistry.ts`
- Create: `tools/daddy-pc-relay/src/workspace/PathBoundary.ts`
- Create: `tools/daddy-pc-relay/src/workspace/GitBaseline.ts`
- Test: `tools/daddy-pc-relay/test/PathBoundary.test.ts`
- Test: `tools/daddy-pc-relay/test/GitBaseline.test.ts`

- [ ] **Step 1: Write failing path tests for `..`, absolute drive paths, UNC paths, symlinks, Junction/Reparse Point escape, and path-prefix collision (`C:\\work` versus `C:\\work-old`)**
- [ ] **Step 2: Implement real-path canonicalization and root containment using resolved paths, not text prefix matching**
- [ ] **Step 3: Implement Git baseline capture: branch, HEAD, porcelain changed paths, and scope-overlap detection; non-Git roots receive a directory fingerprint**
- [ ] **Step 4: Test that dirty overlapping paths require an approval flag while unrelated dirty paths allow read/test tasks**
- [ ] **Step 5: Commit workspace confinement and dirty-change guard**

### Task 3: Create encrypted mailbox schema and transport

**Files:**
- Create: `docs/supabase/daddy_pc_relay.sql`
- Create: `tools/daddy-pc-relay/src/transport/EnvelopeCipher.ts`
- Create: `tools/daddy-pc-relay/src/transport/DevicePairing.ts`
- Create: `tools/daddy-pc-relay/src/transport/MailboxClient.ts`
- Test: `tools/daddy-pc-relay/test/EnvelopeCipher.test.ts`
- Test: `tools/daddy-pc-relay/test/DevicePairing.test.ts`
- Test: `tools/daddy-pc-relay/test/MailboxClient.test.ts`

- [ ] **Step 1: Write SQL for paired devices, opaque task envelopes, attempt events, approvals, expiry cleanup, RLS and atomic claim RPC with lease/fencing token**
- [ ] **Step 2: Write failing AES-GCM/HKDF tests proving ciphertext changes per nonce, wrong device key cannot decrypt, and action digest is authenticated**
- [ ] **Step 3: Write pairing tests for a QR-carried one-time offer: PC X25519 public key + 192-bit nonce, phone response, shared-key challenge, replay rejection, and device revocation**
- [ ] **Step 4: Implement ciphertext-only payload transport, pairing handshake, and idempotent RPC requests**
- [ ] **Step 5: Add mocked transport tests proving task files, raw prompts, environment variables, and full terminal output are not serialized**
- [ ] **Step 6: Commit schema, cryptography, and transport tests; apply SQL only after user explicitly authorizes the database change**

### Task 4: Build Relay supervisor, permission wait, and safe recovery

**Files:**
- Create: `tools/daddy-pc-relay/src/runner/AttemptSupervisor.ts`
- Create: `tools/daddy-pc-relay/src/runner/RemoteApprovalBridge.ts`
- Create: `tools/daddy-pc-relay/src/runner/DirectoryLock.ts`
- Create: `tools/daddy-pc-relay/src/runner/ModelProfileRegistry.ts`
- Test: `tools/daddy-pc-relay/test/AttemptSupervisor.test.ts`
- Test: `tools/daddy-pc-relay/test/DirectoryLock.test.ts`

- [ ] **Step 1: Write tests for one directory lock, late old-worker event rejection, approval timeout deny, and lease expiry becoming orphaned rather than requeued**
- [ ] **Step 2: Implement `ModelProfileRegistry`: it reports only named, locally verified `claude.cmd` profiles; “computer default” is always available and phone-provided arbitrary model names are rejected**
- [ ] **Step 3: Implement supervisor launch from the verified Task 0 command contract and retain PID/start time/session ID locally**
- [ ] **Step 4: Connect high-risk tool calls to the blocking permission MCP bridge; approved action returns allow in the same process, denied/expired action returns deny**
- [ ] **Step 5: Implement crash/sleep reconciliation: verify prior PID/session first, then require a new phone action before new attempt creation**
- [ ] **Step 6: Run all Relay tests and commit the supervisor**

### Task 5: Package Windows installation and prove offline behavior

**Files:**
- Create: `tools/daddy-pc-relay/scripts/install-relay.ps1`
- Create: `tools/daddy-pc-relay/scripts/uninstall-relay.ps1`
- Create: `tools/daddy-pc-relay/scripts/start-relay.ps1`
- Create: `tools/daddy-pc-relay/README.zh-CN.md`

- [ ] **Step 1: Write installer dry-run tests that reject administrator/system context, unknown install root, and absent `claude.cmd`**
- [ ] **Step 2: Implement per-user `%LOCALAPPDATA%\\DaddyPCRelay` install, configuration permissions, Windows logon scheduled task, and rolling redacted logs**
- [ ] **Step 3: Implement uninstall that stops the task and Relay process but preserves a user-confirmed backup of local configuration**
- [ ] **Step 4: On a test workspace, verify offline queue, phone approval, normal execution, risk deny, sleep interruption, and no double execution**
- [ ] **Step 5: Commit installer, documentation, and evidence**
