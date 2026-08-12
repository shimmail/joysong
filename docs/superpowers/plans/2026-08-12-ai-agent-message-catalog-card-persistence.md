# AI Agent Message Catalog Card Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bind doctor, institution, project, and institution-project cards to their originating assistant message so they remain visible across subsequent turns and history reloads.

**Architecture:** Reuse the catalog metadata already persisted on assistant messages. The backend safely projects `metadata_json.catalogItems` into each history message response, while Flutter stores those references on `ChatMessage` and renders a compact clickable list immediately below that message.

**Tech Stack:** Kotlin, Spring Boot, Jackson, JUnit/MySQL integration tests, Dart, Flutter widget/unit tests.

## Global Constraints

- Supported types are exactly `DOCTOR`, `INSTITUTION`, `PROJECT`, and `INSTITUTION_PROJECT`; unknown types are omitted.
- Reuse `agent_messages.metadata_json`; do not add a table or migration.
- Cards show only type affordance, name, optional one-line subtitle, and navigation affordance.
- Do not show attributes, summary, report content, comparison tables, warnings, human consultation, price, rating, or fetched images.
- Cards follow the existing chat message retention, deletion, pagination, and cleanup behavior.
- Preserve unrelated dirty worktree changes.

---

### Task 1: Project persisted catalog references into history messages

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/dto/ChatDtos.kt:29-44`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/controller/ChatController.kt:55-89,143-151`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/TurnLifecycleService.kt:252-278`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt`

**Interfaces:**
- Produces: `ChatMessageResponse.catalogItems: List<AgentCatalogItemResponse>`.
- Produces: one safe metadata projection used by history mapping and turn reconstruction.
- Consumes: existing `ChatMessageEntity.metadataJson` and `AgentCatalogItemResponse`.

- [ ] **Step 1: Write failing history-contract tests**

Add an integration scenario that completes a first assistant turn containing all four supported card types plus one unknown type, completes a second plain turn, then calls `GET /api/chat/sessions/{id}/messages`. Assert the first assistant message contains the four supported items, the unknown item is absent, the second assistant and USER messages have empty `catalogItems`, and message order is unchanged. Add a stored legacy assistant message with `{}`, missing `catalogItems`, and malformed item entries; assert history still returns the message body and only valid references.

- [ ] **Step 2: Run the focused backend test and confirm RED**

Run from `joysong-server` with an isolated project-local Gradle home:

```powershell
$env:GRADLE_USER_HOME = (Join-Path $PWD '.gradle-codex-agent-card-persistence')
.\gradlew.bat --no-daemon mysqlIntegrationTest --tests com.joysong.server.agent.AgentChatFlowIntegrationTest --console=plain
```

Before the database-backed test starts, confirm its Testcontainers database name begins with `myapp_worktree_`. Expected failure: history response has no `catalogItems` field or returns an empty list for the first assistant message.

- [ ] **Step 3: Add the message response field**

Extend the DTO without breaking old callers:

```kotlin
data class ChatMessageResponse(
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val createdAt: String,
    val catalogItems: List<AgentCatalogItemResponse> = emptyList()
)
```

- [ ] **Step 4: Centralize safe catalog metadata projection**

Add a focused method on `TurnLifecycleService` (or a small internal projector beside it if visibility requires) with this contract:

```kotlin
fun catalogItemsForMessage(message: ChatMessageEntity): List<AgentCatalogItemResponse>
```

It returns empty for non-ASSISTANT messages; parses `metadataJson` defensively; maps array entries individually with `runCatching`; and filters by:

```kotlin
private val supportedCatalogTypes = setOf(
    "DOCTOR", "INSTITUTION", "PROJECT", "INSTITUTION_PROJECT"
)
```

Use the same projector inside `reconstruct` so history and idempotent replay cannot drift. A malformed item must be skipped without suppressing valid siblings or the message body.

- [ ] **Step 5: Map cards into send and history responses**

Change the controller message mapper to accept projected items:

```kotlin
private fun ChatMessageEntity.toResponse(
    catalogItems: List<AgentCatalogItemResponse> = emptyList()
): ChatMessageResponse
```

For history, call `turnLifecycleService.catalogItemsForMessage(message)`. For `sendMessage`, populate the nested `message.catalogItems` from the same filtered list used by the top-level `ChatTurnResponse.catalogItems`.

- [ ] **Step 6: Run the focused backend test and confirm GREEN**

Run the command from Step 2 once. Expected: `AgentChatFlowIntegrationTest` passes and no shared development database is contacted.

- [ ] **Step 7: Commit the backend contract slice**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/chat/dto/ChatDtos.kt joysong-server/src/main/kotlin/com/joysong/server/chat/controller/ChatController.kt joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/TurnLifecycleService.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt
git commit -m "feat: restore agent cards with chat history"
```

### Task 2: Bind catalog references to Flutter chat messages

**Files:**
- Modify: `joysong-flutter/lib/features/agent/domain/agent_models.dart:61-99,175-205`
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_controller.dart:18-58,101-124,275-363`
- Test: `joysong-flutter/test/features/agent/agent_chat_controller_test.dart`

**Interfaces:**
- Consumes: `ChatMessageResponse.catalogItems` from Task 1.
- Produces: `ChatMessage.catalogItems: List<AgentCatalogItem>` and `copyWith(catalogItems: ...)`.
- Produces: assistant messages that retain cards independently of `AgentChatState.latestTurn`.

- [ ] **Step 1: Write failing model and controller tests**

Add assertions that a historical assistant message decodes `catalogItems`, an old message without the field decodes an empty list, and two sequential sends leave the first assistant message's cards intact. Assert `openSession` restores cards from repository history and that starting the second send does not remove the first message's references.

- [ ] **Step 2: Run the focused controller test and confirm RED**

```powershell
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat test test/features/agent/agent_chat_controller_test.dart
```

Expected failure: `ChatMessage` has no `catalogItems` or the first turn's cards are unavailable after the next send.

- [ ] **Step 3: Extend the Flutter message model**

Add the immutable field and compatibility decoding:

```dart
final List<AgentCatalogItem> catalogItems;

catalogItems: jsonList(map['catalogItems'])
    .map(AgentCatalogItem.fromJson)
    .toList(),
```

Default constructor usage to `const []`, and add a `copyWith` that can replace `catalogItems` without changing message identity or content.

- [ ] **Step 4: Merge the turn references into its assistant message**

When `sendMessage` succeeds, normalize the returned message before appending:

```dart
final assistantMessage = turn.message.copyWith(
  catalogItems: turn.message.catalogItems.isNotEmpty
      ? turn.message.catalogItems
      : turn.catalogItems,
);
```

Keep `latestTurn` only if another non-card consumer still requires it; card persistence and rendering must use `state.messages`. Do not clear catalog references from existing messages when a new delivery begins.

- [ ] **Step 5: Run the focused controller test and confirm GREEN**

Run the command from Step 2 once. Expected: all controller tests pass.

- [ ] **Step 6: Commit the Flutter message-state slice**

```powershell
git add -- joysong-flutter/lib/features/agent/domain/agent_models.dart joysong-flutter/lib/features/agent/presentation/agent_chat_controller.dart joysong-flutter/test/features/agent/agent_chat_controller_test.dart
git commit -m "feat: bind agent cards to assistant messages"
```

### Task 3: Replace report cards with compact discover-style links

**Files:**
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_catalog_cards.dart`
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_page.dart:180-210,227-235`
- Test: `joysong-flutter/test/features/agent/agent_chat_page_test.dart`

**Interfaces:**
- Consumes: `ChatMessage.catalogItems` from Task 2.
- Produces: `AgentCatalogLinkList` and `AgentCatalogLinkCard` with whole-card tap behavior.
- Reuses: existing `AgentCatalogItemAction onOpen` and catalog detail navigation in `AppShell`.

- [ ] **Step 1: Replace widget expectations with failing compact-card tests**

Test a message containing doctor, institution, project, institution-project, and unknown items. Assert four `AgentCatalogLinkCard` widgets appear immediately after that assistant bubble; tapping each valid card calls `onOpenCatalogItem` with the matching item; unknown is absent. Assert item attributes, summary, report title, warnings, consultation copy, and a separate “查看详情/View details” button are absent. Add an item missing required navigation IDs and assert tapping it does not invoke the callback.

- [ ] **Step 2: Run the focused page test and confirm RED**

```powershell
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat test test/features/agent/agent_chat_page_test.dart
```

Expected failure: page still renders a single `AgentCatalogReportCard` after the full message list and exposes report detail UI.

- [ ] **Step 3: Implement the compact card components**

Replace the report/comparison presentation used by chat with focused widgets:

```dart
class AgentCatalogLinkList extends StatelessWidget {
  const AgentCatalogLinkList({
    required this.items,
    required this.onOpen,
    required this.canOpen,
    super.key,
  });
}

class AgentCatalogLinkCard extends StatelessWidget {
  const AgentCatalogLinkCard({
    required this.item,
    required this.onOpen,
    required this.canOpen,
    super.key,
  });
}
```

Use `Card` + `InkWell` with the existing type icon, item name, optional subtitle limited to one line with ellipsis, and `Icons.chevron_right`. Do not render any other `AgentCatalogItem` or report fields. Retain older report widgets only if another screen imports them; otherwise remove dead code from this file.

- [ ] **Step 4: Render cards under each assistant message**

Inside the message loop, emit the bubble and then the filtered link list for that same message. Supported type check:

```dart
const supportedTypes = {
  'DOCTOR',
  'INSTITUTION',
  'PROJECT',
  'INSTITUTION_PROJECT',
};
```

Remove the list-bottom `latestTurn.catalogReport/catalogItems` block. Reuse `_canOpenCatalogItem` for navigation ID validation and keep the current `onOpenCatalogItem` callback.

- [ ] **Step 5: Run the focused page test and confirm GREEN**

Run the command from Step 2 once. Expected: all page tests pass.

- [ ] **Step 6: Run the related Flutter test group once**

```powershell
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat test test/features/agent/agent_chat_controller_test.dart test/features/agent/agent_chat_busy_actions_test.dart test/features/agent/agent_chat_page_test.dart
```

Expected: all related Agent chat tests pass. Do not repeat already-passing individual commands.

- [ ] **Step 7: Commit the compact-card UI slice**

```powershell
git add -- joysong-flutter/lib/features/agent/presentation/agent_catalog_cards.dart joysong-flutter/lib/features/agent/presentation/agent_chat_page.dart joysong-flutter/test/features/agent/agent_chat_page_test.dart
git commit -m "feat: show compact persistent agent cards"
```

### Task 4: Verify the integrated delivery and clean temporary artifacts

**Files:**
- Verify only: all files changed in Tasks 1-3
- Remove: `joysong-server/.gradle-codex-agent-card-persistence/` only if it was created by this task and is not needed by the project

**Interfaces:**
- Consumes: complete backend and Flutter implementation.
- Produces: test evidence and a clean, reviewable diff.

- [ ] **Step 1: Inspect the scoped diff and working tree**

```powershell
git status --short
git diff --check
git diff --stat HEAD~3..HEAD
```

Confirm unrelated dirty files remain unstaged and unchanged.

- [ ] **Step 2: Run Flutter static analysis once**

```powershell
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat analyze
```

If it reports pre-existing unrelated diagnostics, record file/line evidence and do not expand scope. Do not rerun after unchanged unrelated failures.

- [ ] **Step 3: Clean task-owned temporary cache safely**

Resolve the exact absolute cache path, verify it is `D:\code\kotlin\joysong\joysong-server\.gradle-codex-agent-card-persistence`, verify it is a directory and not a reparse point, enumerate it, then remove only that directory. Stop if validation differs or deletion fails.

- [ ] **Step 4: Final review**

Confirm the final behavior from tests: cards are tied to their assistant message, survive a second turn, restore from history, support all four types, remain compact, and navigate only with valid IDs. Report backend integration, Flutter related tests, analysis result, and any unrelated dirty files separately.

