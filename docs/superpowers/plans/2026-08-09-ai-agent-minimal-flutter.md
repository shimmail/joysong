# AI Agent Minimal Flutter Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Use one account-scoped Agent dependency composition root, force synchronous REST, and display only the latest 20 messages without older-history pagination.

**Architecture:** `AppEnvironment` owns an immutable `AgentConfig`, `AuthGate` passes it into the user-keyed `AppShell`, and `AppShell` is the only place that constructs/disposes Agent repositories and controllers. `AgentChatController` performs synchronous REST sends and keeps a bounded in-memory recent-message list.

**Tech Stack:** Flutter, Dart, existing `ApiClient`, `SecureTokenStore`, Agent repository/controller/page, Flutter test.

## Global Constraints

- Execute only after the backend minimal synchronous REST plan stabilizes the additive contract.
- Work only in `D:\code\kotlin\joysong\.worktrees\ai-agent-architecture-refactor`.
- Do not add a Flutter package or state-management framework.
- Do not implement SSE terminal DTOs, stream retries, automatic POST replay, full-history pagination, LangChain memory, or a diagnostics screen.
- SSE is fixed false in this release; do not read `AI_STREAM_ENABLED` or create a second client entry behavior.
- The recent-message limit is exactly 20.
- Keep `AppShell` user-keyed so account changes cannot reuse another user's controllers.
- Update only the three existing focused Agent test files; do not add a broad plumbing test directory.

---

## File Structure

- `core/config/app_environment.dart` — immutable `AgentConfig` and environment ownership.
- `app/app.dart` / `auth_gate.dart` — pass configuration without owning controllers.
- `shell/presentation/app_shell.dart` — sole account-scoped Agent composition root.
- `agent_chat_controller.dart` — synchronous REST and bounded 20-message state.
- `agent_chat_page.dart` — no older-history control.
- `assistant_page.dart` — removed after all references use the AppShell-owned `AgentChatPage`.

---

### Task 1: Make AppShell the Only Agent Composition Root

**Files:**
- Modify: `joysong-flutter/lib/core/config/app_environment.dart`
- Modify: `joysong-flutter/lib/app/app.dart`
- Modify: `joysong-flutter/lib/features/auth/presentation/auth_gate.dart`
- Modify: `joysong-flutter/lib/features/shell/presentation/app_shell.dart`
- Delete: `joysong-flutter/lib/features/assistant/presentation/assistant_page.dart`
- Test: `joysong-flutter/test/features/agent/agent_chat_controller_test.dart`

**Interfaces:**
- Produces:

```dart
@immutable
class AgentConfig {
  const AgentConfig({
    this.sseEnabled = false,
    this.recentMessageLimit = 20,
  });

  final bool sseEnabled;
  final int recentMessageLimit;
}
```

- Consumes: current `AppEnvironment`, `AuthGate`, `AppShell`, `AgentRepositoryImpl`, and controllers.

- [ ] **Step 1: Add a failing controller/config expectation**

In `agent_chat_controller_test.dart`, construct the controller with the new explicit name:

```dart
final controller = AgentChatController(
  repository: repository,
  streamingEnabled: false,
  recentMessageLimit: 20,
);
```

Add an assertion that `sendMessage` invokes the repository's non-streaming `sendMessage` exactly once and never invokes `sendMessageStream`.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
& ..\.flutter-cache\sdk\flutter\bin\flutter.bat test test/features/agent/agent_chat_controller_test.dart
```

Expected: FAIL because the controller still exposes `historyPageSize` and the app has inconsistent composition roots.

- [ ] **Step 3: Add immutable AgentConfig**

Add `AgentConfig` to `app_environment.dart` and an `agentConfig` field to `AppEnvironment`. `fromBuildDefines()` must use:

```dart
agentConfig: const AgentConfig(
  sseEnabled: false,
  recentMessageLimit: 20,
),
```

Do not read `AI_STREAM_ENABLED`, `AGENT_SSE_ENABLED`, or a message-limit build define in this release.

- [ ] **Step 4: Pass config through app and auth boundaries**

Add required `AgentConfig agentConfig` constructor parameters to `AuthGate` and `AppShell`. Pass `environment.agentConfig` from the authenticated application path. Do not construct repositories/controllers in `JoysongApp` or `AuthGate`.

- [ ] **Step 5: Make AppShell the sole constructor/disposer**

In `AppShell._createDependencies()`:

```dart
_agentChatController = AgentChatController(
  repository: repository,
  streamingEnabled: widget.agentConfig.sseEnabled,
  recentMessageLimit: widget.agentConfig.recentMessageLimit,
);
```

Keep all Agent plan/profile/safety controllers in the same current-user composition. Preserve disposal in `AppShell.dispose`. In `didUpdateWidget`, rebuild dependencies when the account-scoped API/token/language inputs or `AgentConfig` values change.

- [ ] **Step 6: Remove the duplicate AssistantPage composition**

Search first:

```powershell
Get-ChildItem -Recurse -File lib | Select-String -Pattern 'AssistantPage'
```

Replace every entry with the AppShell-owned `AgentChatPage` and existing controller. Delete `assistant_page.dart` only after the search returns no reference. Do not create fallback network dependencies inside a page.

- [ ] **Step 7: Run format, analyze, and focused test**

```powershell
& ..\.flutter-cache\sdk\flutter\bin\dart.bat format lib/core/config/app_environment.dart lib/app/app.dart lib/features/auth/presentation/auth_gate.dart lib/features/shell/presentation/app_shell.dart lib/features/agent/presentation/agent_chat_controller.dart test/features/agent/agent_chat_controller_test.dart
& ..\.flutter-cache\sdk\flutter\bin\flutter.bat analyze
& ..\.flutter-cache\sdk\flutter\bin\flutter.bat test test/features/agent/agent_chat_controller_test.dart
```

Expected: PASS; there is one Agent composition root and no environment-driven SSE divergence.

- [ ] **Step 8: Commit Task 1**

```powershell
git add -- joysong-flutter/lib/core/config/app_environment.dart joysong-flutter/lib/app/app.dart joysong-flutter/lib/features/auth/presentation/auth_gate.dart joysong-flutter/lib/features/shell/presentation/app_shell.dart joysong-flutter/lib/features/assistant/presentation/assistant_page.dart joysong-flutter/lib/features/agent/presentation/agent_chat_controller.dart joysong-flutter/test/features/agent/agent_chat_controller_test.dart
git commit -m "refactor: unify Flutter agent composition"
```

---

### Task 2: Keep Only the Latest 20 Messages and Remove Older-History UI

**Files:**
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_controller.dart`
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_page.dart`
- Modify: `joysong-flutter/test/features/agent/agent_chat_controller_test.dart`
- Modify: `joysong-flutter/test/features/agent/chat_sse_transport_test.dart`
- Verify unchanged: `joysong-flutter/test/features/agent/agent_plan_view_test.dart`
- Modify: `joysong-flutter/README.md`
- Modify: `docs/FLUTTER_API_CONTRACT.md`

**Interfaces:**
- Consumes: backend synchronous `POST /api/chat/sessions/{id}/messages` and recent-history GET.
- Produces: controller state with at most 20 messages and no load-older command.

- [ ] **Step 1: Write failing recent-window tests**

In `agent_chat_controller_test.dart`, make the fake repository return 25 ordered messages. Assert after `openSession`:

```dart
expect(controller.state.messages.length, 20);
expect(controller.state.messages.first.id, messages[5].id);
expect(controller.state.messages.last.id, messages[24].id);
```

After a synchronous send appends USER and ASSISTANT, assert the state is trimmed back to the latest 20. Remove tests for `loadOlderMessages` and `hasOlderMessages`.

- [ ] **Step 2: Run the controller test and verify RED**

```powershell
& ..\.flutter-cache\sdk\flutter\bin\flutter.bat test test/features/agent/agent_chat_controller_test.dart
```

Expected: FAIL because existing pagination retains older-message state or exposes the old load method.

- [ ] **Step 3: Implement a single bounded recent window**

Rename constructor/state usage from `historyPageSize` to `recentMessageLimit`. Validate the constructor value is positive.

`openSession()` performs one request with `limit: recentMessageLimit` and keeps:

```dart
List<ChatMessage> latest(Iterable<ChatMessage> messages) {
  final ordered = messages.toList(growable: false);
  if (ordered.length <= recentMessageLimit) return ordered;
  return ordered.sublist(ordered.length - recentMessageLimit);
}
```

Apply the same trim after optimistic USER insertion and final ASSISTANT replacement. Remove `loadOlderMessages`, `hasOlderMessages`, offsets/cursors, and any loading-older state. Never automatically resend a failed POST.

- [ ] **Step 4: Remove older-history UI**

Delete the “加载更早消息” button and its callbacks from `AgentChatPage`. Keep session list, new session, clear, delete, profile/safety and plan navigation.

Show existing synchronous error text; do not add SSE terminal or retry UI.

- [ ] **Step 5: Keep the dormant SSE parser test minimal**

`chat_sse_transport_test.dart` may remain as a decoder unit test for future compatibility, but remove any assertion that SSE is enabled by application configuration. Do not add new stream cases.

- [ ] **Step 6: Update client documentation**

Document in `joysong-flutter/README.md` and `docs/FLUTTER_API_CONTRACT.md`:

- Agent uses synchronous REST only.
- SSE is fixed disabled.
- `idempotencyKey` is optional and old clients remain valid.
- UI loads/displays only the latest 20 messages and has no permanent-history pagination.
- `AppShell` is the sole account-scoped composition root.

- [ ] **Step 7: Run the reduced Flutter verification**

```powershell
& ..\.flutter-cache\sdk\flutter\bin\dart.bat format lib test
& ..\.flutter-cache\sdk\flutter\bin\flutter.bat analyze
& ..\.flutter-cache\sdk\flutter\bin\flutter.bat test test/features/agent/agent_chat_controller_test.dart test/features/agent/chat_sse_transport_test.dart test/features/agent/agent_plan_view_test.dart
```

Expected: analyze PASS and all three focused test files PASS. If generated plugin registrants receive line-ending-only changes, restore only those generated files after confirming no semantic difference.

- [ ] **Step 8: Commit Task 2**

```powershell
git add -- joysong-flutter/lib/features/agent/presentation/agent_chat_controller.dart joysong-flutter/lib/features/agent/presentation/agent_chat_page.dart joysong-flutter/test/features/agent/agent_chat_controller_test.dart joysong-flutter/test/features/agent/chat_sse_transport_test.dart joysong-flutter/README.md docs/FLUTTER_API_CONTRACT.md
git commit -m "feat: bound Flutter agent chat history"
```
