# AI Agent Flutter REST Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Flutter Agent client REST-only, preserve server profile data, and expose the smallest useful structured catalog navigation.

**Architecture:** Remove transport selection from `AgentChatController`, load profile state before opening the editor, and render the existing catalog cards with navigation callbacks supplied by the AppShell composition root.

**Tech Stack:** Dart, Flutter, ChangeNotifier controllers, widget tests.

## Global Constraints

- Work only on `codex/ai-agent-production-hardening` in the isolated worktree.
- Do not add SSE fallback, automatic POST retries, new detail pages, or automatic plan creation.
- Reuse existing project, doctor, institution, and human-consultation navigation.
- Preserve fields that the profile page does not edit.
- Follow test-first development and run the closest Flutter test before broader analysis.

---

### Task 1: Remove the Flutter SSE execution path

**Files:**
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_controller.dart`
- Modify: `joysong-flutter/lib/features/agent/data/agent_repository_impl.dart`
- Modify: `joysong-flutter/lib/features/agent/domain/agent_repository.dart`
- Modify: `joysong-flutter/lib/features/shell/presentation/app_shell.dart`
- Modify: `joysong-flutter/lib/core/config/app_environment.dart`
- Delete if unreferenced: `joysong-flutter/lib/features/agent/data/chat_sse_transport.dart`
- Delete if transport is deleted: `joysong-flutter/test/features/agent/chat_sse_transport_test.dart`
- Modify: `joysong-flutter/test/features/agent/agent_chat_controller_test.dart`

**Interfaces:**
- Produces: `AgentChatController({required AgentRepository repository, int recentMessageLimit = 20})` with `send` always calling `repository.sendMessage` once.

- [ ] **Step 1: Write a failing REST-only controller test**

Assert one send produces exactly one repository call and no stream subscription:

```dart
await controller.send('hello');
expect(repository.sendCalls, 1);
expect(repository.streamCalls, 0);
expect(controller.state.deliveryState, ChatDeliveryState.completed);
```

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
flutter test test/features/agent/agent_chat_controller_test.dart
```

Expected: FAIL while the controller constructor and branch still expose streaming.

- [ ] **Step 3: Remove transport selection**

Delete `streamingEnabled`, `_sendStreaming`, stream cancellation state, and AppShell wiring. Keep the existing idempotency key on REST sends. Delete SSE transport files only after confirming `git grep ChatSseTransport` has no production consumer. Remove the AppShell `apiRoot/accessTokenProvider` gate that existed only to construct the SSE transport; construct the Agent repository from the existing `ApiClient` boundary.

- [ ] **Step 4: Run focused tests and analysis**

```powershell
flutter test test/features/agent/agent_chat_controller_test.dart
flutter analyze lib/features/agent lib/features/shell/presentation/app_shell.dart
```

Expected: PASS with no unresolved SSE references.

- [ ] **Step 5: Commit**

```powershell
git add -A joysong-flutter/lib/features/agent joysong-flutter/lib/features/shell/presentation/app_shell.dart joysong-flutter/lib/core/config/app_environment.dart joysong-flutter/test/features/agent
git commit -m "refactor: make agent chat REST only"
```

### Task 2: Load and preserve the Agent profile before editing

**Files:**
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_plan_controller.dart`
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_profile_safety_page.dart`
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_page.dart`
- Create: `joysong-flutter/test/features/agent/agent_profile_safety_page_test.dart`

**Interfaces:**
- Produces: `Future<void> loadProfile()` and a page loading/error state before form construction.
- Consumes: existing `AgentProfileDraft` and repository `getProfile`/`saveProfile` calls.

- [ ] **Step 1: Write failing widget tests**

Cover loading before form display and field preservation:

```dart
expect(fakeRepository.getProfileCalls, 1);
await tester.enterText(find.byKey(const Key('agent-budget-field')), '12000');
await tester.tap(find.text('保存并评估'));
expect(fakeRepository.lastSavedProfile!.excludedProjects, ['project-1']);
```

Also assert the repository error text is visible with a retry control.

- [ ] **Step 2: Run the widget test and verify RED**

```powershell
flutter test test/features/agent/agent_profile_safety_page_test.dart
```

Expected: FAIL because the page initializes from nullable in-memory state and replaces excluded projects.

- [ ] **Step 3: Add explicit load and merge behavior**

Add `loadProfile()` to the controller, await it before pushing the page or from the page's initial loader, and build the draft from the loaded profile. When saving, use the loaded draft's unedited values:

```dart
final next = current.copyWith(
  budgetMin: parsedMin,
  budgetMax: parsedMax,
  acceptableDowntimeDays: parsedDowntime,
  painTolerance: selectedPain,
  excludedProjects: current.excludedProjects,
);
```

Show concrete load/save/assessment errors and a manual retry button.

- [ ] **Step 4: Run profile and Agent widget tests**

```powershell
flutter test test/features/agent/agent_profile_safety_page_test.dart test/features/agent/agent_plan_view_test.dart
flutter analyze lib/features/agent
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add joysong-flutter/lib/features/agent/presentation joysong-flutter/test/features/agent
git commit -m "fix: preserve agent profile fields"
```

### Task 3: Render minimal catalog cards and delegate navigation

**Files:**
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_page.dart`
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_catalog_cards.dart`
- Modify: `joysong-flutter/lib/features/shell/presentation/app_shell.dart`
- Create: `joysong-flutter/test/features/agent/agent_chat_page_test.dart`

**Interfaces:**
- Produces: optional `onOpenCatalogItem` and `onHumanConsult` callbacks on `AgentChatPage`.
- Consumes: `AgentChatState.latestTurn.catalogItems/catalogReport` and existing AppShell navigation methods.

- [ ] **Step 1: Write failing rendering/navigation tests**

```dart
expect(find.byType(AgentCatalogDetailCard), findsOneWidget);
await tester.tap(find.text('查看详情'));
expect(openedItem.id, 'project-1');
```

Add a second test where an unknown type has no navigation callback and displays `信息暂不完整`.

- [ ] **Step 2: Run the page test and verify RED**

```powershell
flutter test test/features/agent/agent_chat_page_test.dart
```

Expected: FAIL because `latestTurn` is not rendered.

- [ ] **Step 3: Render only supported actions**

Below the assistant message list, render `AgentCatalogReportCard` when present, otherwise `AgentCatalogReferenceList`; never render both copies of the same items. Resolve only `PROJECT`, `INSTITUTION_PROJECT`, `DOCTOR`, and `INSTITUTION`. Delegate navigation to AppShell; do not construct new detail repositories inside the card widget. Enable human consultation only for a `DOCTOR` item whose identifier is already proven to be the target user ID. Project and institution IDs must never be passed to direct messaging; hide the action until the server contract exposes an explicit `humanUserId`.

- [ ] **Step 4: Run Agent tests and analysis**

```powershell
flutter test test/features/agent
flutter analyze lib/features/agent lib/features/shell/presentation/app_shell.dart
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add joysong-flutter/lib/features/agent/presentation joysong-flutter/lib/features/shell/presentation/app_shell.dart joysong-flutter/test/features/agent
git commit -m "feat: show agent catalog references"
```
