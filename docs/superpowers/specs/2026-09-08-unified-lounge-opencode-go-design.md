# Unified Visitor Lounge and OpenCode Go Integration

## Goal

Produce one arm64 companion APK that keeps the installed Daddy PC bridge
behavior, includes the completed Visitor Lounge client, and makes OpenCode Go
requests carry a stable, per-conversation session identifier automatically.

## Integration baseline

The isolated integration branch starts at `codex/visitor-lounge-client`. It
contains the completed Visitor Lounge implementation, including its Room
migration and encrypted visitor credentials. The current PC bridge worktree
has uncommitted, tested bridge changes; those will be copied as a reviewed
patch into this isolated branch. The original worktree will not be modified.

`ChatService` is changed by both feature sets. Its integration must preserve
both the Visitor Lounge entry points/proactive visit coordination and the PC
bridge task-board lifecycle. No wholesale replacement of either file is
allowed.

## OpenCode Go request policy

1. Recognize only the exact Go endpoint rooted at
   `https://opencode.ai/zen/go/v1`.
2. For normal chat generation, use the existing persistent `Conversation.id`
   as the `x-opencode-session` value. The same chat therefore retains one
   session value across sends, regenerations, and tool-continuation requests;
   separate chats have distinct values.
3. For internal calls with no conversation identity, create a fresh opaque
   request session value. Such calls must never reuse another chat's value.
4. Add the required header at provider request construction time rather than
   storing it in a model or assistant setting. The user cannot accidentally
   overwrite it with a static custom header, and it is not persisted or shown
   in configuration UI.
5. Keep all non-Go providers, user custom headers, and API paths unchanged.
   Go remains configured as the Responses API; its unsupported balance probe
   stays disabled in the user-facing configuration.

## Safety and privacy

- Never log the OpenCode Go API key, the visitor key, or raw visitor response
  content.
- The session identifier is an opaque UUID, not a prompt, account identifier,
  filesystem path, or credential.
- Existing Visitor Lounge rules remain intact: HTTPS-only endpoint validation,
  Android Keystore-backed visitor credentials, consent before proactive visits,
  bounded visit history, and sanitized summaries.
- Existing PC bridge card expiry and queued-progress draining behavior remain
  intact.

## Test and release plan

1. Add a failing unit test for the Go endpoint policy: matching URLs receive a
   supplied conversation UUID, non-Go URLs receive no injected header, and a
   static user-supplied Go session header cannot replace the generated value.
2. Add a failing generation-parameter test proving the same conversation is
   propagated to repeat requests and internal requests use a distinct fresh
   value.
3. Apply the current PC bridge patch to this isolated worktree, resolving
   overlapping `ChatService` code with both feature behaviors retained.
4. Run the focused Go policy and PC bridge/Visitor Lounge tests, then the full
   `:app:testDebugUnitTest` suite.
5. Build `:app:assembleCompanion`, inspect the arm64 APK's version and
   signature, and provide its exact output path for an in-place update.

## Non-goals

- No server-side proxy, account migration, API-key recovery, or static global
  `x-opencode-session` setting.
- No removal or clearing of the user's existing chats, providers, PC pairing,
  or Visitor Lounge data.
