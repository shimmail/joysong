# AI Agent Flutter Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Flutter Agent client use one account-scoped composition root, consume the additive Turn terminal contract, and present only bounded recent messages without automatic POST replay.

**Architecture:** `AppEnvironment` owns typed immutable Agent configuration and `AppShell` remains the only user-scoped composition root because it is recreated on account changes. REST and SSE map to the same typed terminal state. The controller keeps at most the configured recent messages and removes infinite history pagination; formal profiles, assessments, and plans remain unchanged.

**Tech Stack:** Flutter/Dart, existing `ApiClient`, `HttpClient` SSE transport, ChangeNotifier controllers, existing Agent widget/controller tests.

## Global Constraints

- Complete backend Tasks 1–5 in `2026-08-09-ai-agent-v2-backend-core.md` before implementing the terminal JSON mapping in Task 2 below.
- Work only in `D:\code\kotlin\joysong\.worktrees\ai-agent-architecture-refactor` on branch `codex/ai-agent-architecture-refactor`.
- `AppShell` is the single Agent composition root. Do not create controllers in `JoysongApp` or reconstruct networking in `AssistantPage`.
- Name the client build define `AGENT_SSE_ENABLED`; do not reuse the server's `OPENAI_STREAM_ENABLED`.
- `AGENT_SSE_ENABLED` defaults to `false`; recent messages default to 20.
- New REST/SSE fields are optional and must not break old responses or legacy SSE `{ "message": "..." }` errors.
- Never automatically replay a failed message POST. Retry remains an explicit user action using a new idempotency key unless the UI deliberately reuses the original key.
- Do not add a client database or persist full chat history locally.
- Keep the simplified test boundary: update the three existing Agent test files; do not add broad widget or integration suites.
- Baseline in the new worktree: the three focused Flutter files run 8 tests, all passing.

---

## File Structure

- `core/config/app_environment.dart` — owns typed `AgentConfig` and reads build defines once.
- `features/shell/presentation/app_shell.dart` — owns account-scoped Agent repository/controllers.
- `features/assistant/presentation/assistant_page.dart` — remove the duplicate wrapper and dependency graph.
- `features/agent/domain/agent_models.dart` — additive Turn/status/error models.
- `features/agent/data/chat_sse_transport.dart` — legacy and enriched SSE decoder.
- `features/agent/presentation/agent_chat_controller.dart` — unified terminal mapping and bounded in-memory messages.

---

### Task 1: Introduce Typed AgentConfig and One Account-Scoped Composition Root

**Files:**
- Modify: `joysong-flutter/lib/core/config/app_environment.dart`
- Modify: `joysong-flutter/lib/app/app.dart`
- Modify: `joysong-flutter/lib/features/auth/presentation/auth_gate.dart`
- Modify: `joysong-flutter/lib/features/shell/presentation/app_shell.dart`
- Delete: `joysong-flutter/lib/features/assistant/presentation/assistant_page.dart`
- Modify imports/usages found by analyzer after deletion.
- Test: `joysong-flutter/test/features/agent/agent_chat_controller_test.dart`

**Interfaces:**
- Produces: immutable `AgentConfig`, one `AgentRepository`, one chat controller, and one plan controller per authenticated AppShell.
- Consumes: existing environment, API root, token provider, and language provider.

- [ ] **Step 1: Add the typed configuration**

```dart
final class AgentConfig {
  const AgentConfig({
    this.sseEnabled = false,
    this.recentMessageLimit = 20,
  }) : assert(recentMessageLimit > 0 && recentMessageLimit <= 100);

  final bool sseEnabled;
  final int recentMessageLimit;
}
```

Add `required AgentConfig agentConfig` to `AppEnvironment`. `fromBuildDefines()` is the only place that reads:

```dart
const bool.fromEnvironment('AGENT_SSE_ENABLED', defaultValue: false)
```

- [ ] **Step 2: Thread AgentConfig without moving user state upward**

Pass `environment.agentConfig` through `JoysongApp -> AuthGate -> AppShell`. Keep the existing user-keyed AppShell behavior so switching accounts disposes the old Agent controllers.

- [ ] **Step 3: Make AppShell the only composition root**

Replace hard-coded values with:

```dart
AgentChatController(
  repository: agentRepository,
  streamingEnabled: widget.agentConfig.sseEnabled,
  recentMessageLimit: widget.agentConfig.recentMessageLimit,
)
```

Include `languageTagProvider` and `agentConfig` in `didUpdateWidget` dependency-change checks. Continue disposing both controllers in `_disposeControllers()`.

- [ ] **Step 4: Remove AssistantPage's duplicate networking**

Delete `assistant_page.dart`. Route/build `AgentChatPage` directly from AppShell-owned controllers. If Agent dependencies are unavailable, show the existing stable unavailable state; do not construct a private `ApiClient`, `SecureTokenStore`, or transport.

- [ ] **Step 5: Format and analyze the composition changes**

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\dart.bat' format lib/core/config/app_environment.dart lib/app/app.dart lib/features/auth/presentation/auth_gate.dart lib/features/shell/presentation/app_shell.dart
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' analyze
```

Expected: no unresolved `AssistantPage` imports and no scattered `AI_STREAM_ENABLED` reads.

- [ ] **Step 6: Commit the composition root**

```powershell
git add joysong-flutter/lib
git commit -m "refactor: unify Flutter agent composition"
```

---

### Task 2: Parse the Additive REST/SSE Terminal Contract

**Files:**
- Modify: `joysong-flutter/lib/features/agent/domain/agent_models.dart`
- Modify: `joysong-flutter/lib/features/agent/domain/agent_repository.dart`
- Modify: `joysong-flutter/lib/core/network/api_exception.dart`
- Modify: `joysong-flutter/lib/core/network/api_client.dart`
- Modify: `joysong-flutter/lib/features/agent/data/agent_remote_data_source.dart`
- Modify: `joysong-flutter/lib/features/agent/data/chat_sse_transport.dart`
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_controller.dart`
- Test: `joysong-flutter/test/features/agent/agent_chat_controller_test.dart`
- Test: `joysong-flutter/test/features/agent/chat_sse_transport_test.dart`

**Interfaces:**
- Produces: typed `AgentTurnStatus`, `AgentTurnRef`, `AgentTurnError`, and identical REST/SSE terminal state.
- Consumes: backend additive `traceId`, `turn`, and `error` fields; all are nullable for compatibility.

- [ ] **Step 1: Add failing legacy and enriched decoder tests**

Keep existing fixtures and add:

```json
{
  "message": {"id":"m1","sessionId":"s1","role":"ASSISTANT","content":"ok","createdAt":"now"},
  "traceId":"trace-1",
  "turn":{"id":"turn-1","status":"SUCCEEDED"},
  "error":null
}
```

and:

```json
{
  "message":"模型暂时不可用",
  "traceId":"trace-2",
  "turn":{"id":"turn-2","status":"FAILED"},
  "error":{"code":"MODEL_TIMEOUT","message":"模型暂时不可用","retryable":true}
}
```

Assert legacy done/error still decode, enriched fields survive, EOF flush works, and unknown events remain ignored.

- [ ] **Step 2: Add typed domain models**

```dart
enum AgentTurnStatus { pending, running, succeeded, failed, cancelled, unknown }

final class AgentTurnRef {
  const AgentTurnRef({required this.id, required this.status});
  final String id;
  final AgentTurnStatus status;
}

final class AgentTurnError {
  const AgentTurnError({required this.code, required this.message, required this.retryable});
  final String code;
  final String message;
  final bool retryable;
}
```

Extend `ChatTurn` with nullable `traceId`, `turn`, and `error`. Unknown/missing status maps to `unknown`; do not throw for new server enum values.

- [ ] **Step 3: Preserve structured REST errors in ApiException**

Add an optional `Object? data`/decoded-envelope field to `ApiException`. When `ApiClient` receives non-2xx JSON, preserve its structured data while retaining existing message and status behavior for every other feature.

- [ ] **Step 4: Map REST and SSE to one terminal error type**

Change `ChatStreamEvent.error` to carry `AgentTurnError? error`, `String? traceId`, and `AgentTurnRef? turn`, while keeping the legacy message fallback. `ApiAgentRemoteDataSource.sendMessage` maps structured non-2xx Agent errors to the same typed representation used by SSE.

- [ ] **Step 5: Update controller state without automatic replay**

Add nullable `traceId`, `serverTurn`, and `turnError` to `AgentChatState`. REST and SSE success both set completed plus the server terminal state. Typed error sets failed/disconnected based on transport, but never invokes `sendMessage` a second time.

- [ ] **Step 6: Run focused tests**

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' test test/features/agent/agent_chat_controller_test.dart test/features/agent/chat_sse_transport_test.dart
```

Expected: PASS for legacy/enriched REST and SSE, cancellation, EOF, and exactly one POST.

- [ ] **Step 7: Commit terminal state compatibility**

```powershell
git add joysong-flutter/lib/core/network joysong-flutter/lib/features/agent joysong-flutter/test/features/agent/agent_chat_controller_test.dart joysong-flutter/test/features/agent/chat_sse_transport_test.dart
git commit -m "feat: consume agent turn terminal states"
```

---

### Task 3: Present Only Bounded Recent Messages

**Files:**
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_controller.dart`
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_page.dart`
- Test: `joysong-flutter/test/features/agent/agent_chat_controller_test.dart`

**Interfaces:**
- Produces: a controller/page that never requests infinite history and keeps at most `recentMessageLimit` messages.
- Consumes: Task 1 `AgentConfig.recentMessageLimit` and existing repository `getMessages(limit:, before:)` compatibility method.

- [ ] **Step 1: Write failing bounded-window tests**

Construct the controller with `recentMessageLimit: 3`. Open a session returning five messages and assert only the latest three remain. Send a user/assistant pair and assert the list is trimmed after temporary insertion and final replacement. Assert repository history is requested once with limit 3 and never with `before`.

- [ ] **Step 2: Rename and simplify controller state**

Rename `historyPageSize` to `recentMessageLimit`. Remove `hasOlderMessages` and `loadOlderMessages()`. `openSession()` performs exactly one latest-message fetch.

Centralize trimming:

```dart
List<ChatMessage> _bounded(Iterable<ChatMessage> messages) {
  final values = _deduplicate(messages);
  return values.length <= recentMessageLimit
      ? values
      : values.sublist(values.length - recentMessageLimit);
}
```

Use `_bounded` after history load, temporary user/assistant append, stream delta replacement, and final response replacement.

- [ ] **Step 3: Remove older-history UI affordances**

Delete the “load older messages” button/callback and related loading state from `agent_chat_page.dart`. Keep session selection, new session, delete, and clear behavior.

- [ ] **Step 4: Run controller and page-contract tests**

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' test test/features/agent/agent_chat_controller_test.dart test/features/agent/agent_plan_view_test.dart
```

Expected: PASS; plan detail remains the durable user-facing artifact.

- [ ] **Step 5: Commit bounded recent messages**

```powershell
git add joysong-flutter/lib/features/agent/presentation joysong-flutter/test/features/agent
git commit -m "feat: bound Flutter agent message history"
```

---

### Task 4: Update the Minimal Client Contract and Verify

**Files:**
- Modify: `docs/FLUTTER_API_CONTRACT.md`
- Modify: `joysong-flutter/README.md`
- Modify: `joysong-flutter/test/features/agent/agent_chat_controller_test.dart`
- Modify: `joysong-flutter/test/features/agent/chat_sse_transport_test.dart`
- Modify: `joysong-flutter/test/features/agent/agent_plan_view_test.dart`

**Interfaces:**
- Produces: three focused tests and accurate build/API documentation.
- Consumes: completed Tasks 1–3.

- [ ] **Step 1: Keep only the agreed test matrix**

Ensure the three files cover:

```text
agent_chat_controller_test.dart:
  REST/SSE same terminal mapping
  typed trace/code/retryable/status
  legacy error compatibility
  EOF/cancel does not replay POST
  recent N trimming

chat_sse_transport_test.dart:
  legacy delta/done/error
  enriched done/error
  final event without blank line

agent_plan_view_test.dart:
  limitations, risks, alternatives, confirmations, non-diagnosis copy
```

Do not add low-value tests for constructor plumbing or repository passthrough.

- [ ] **Step 2: Update API and build documentation**

Document `AGENT_SSE_ENABLED=false`, recent-message default 20, optional `traceId/turn/error`, legacy compatibility, explicit retry, and that full chat history is not a durable product artifact.

- [ ] **Step 3: Run all focused Agent tests**

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' test test/features/agent/agent_chat_controller_test.dart test/features/agent/chat_sse_transport_test.dart test/features/agent/agent_plan_view_test.dart
```

Expected: all pass.

- [ ] **Step 4: Run formatter and analyzer**

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\dart.bat' format --output=none --set-exit-if-changed lib test
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' analyze
```

Expected: no formatting or analyzer errors. Existing unrelated warnings, if any, must be listed rather than silently changed.

- [ ] **Step 5: Commit client docs and verification**

```powershell
git add docs/FLUTTER_API_CONTRACT.md joysong-flutter/README.md joysong-flutter/test/features/agent
git commit -m "docs: document bounded agent client history"
```
