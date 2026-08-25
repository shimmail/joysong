# Unified Flutter Transient Messages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route every Flutter bottom message through one component that immediately replaces the current message and disappears after exactly two seconds.

**Architecture:** Keep `showTransientMessage(BuildContext context, String message)` as the only presentation entry point. Feature code continues to select localized text and business outcomes, while the shared component exclusively owns `ScaffoldMessenger`, replacement, and duration behavior.

**Tech Stack:** Flutter, Dart, Material `ScaffoldMessenger`/`SnackBar`, `flutter_test`

**Spec:** `docs/superpowers/specs/2026-08-25-unified-flutter-transient-messages-design.md`

## Global Constraints

- Migrate all 58 feature-level `showSnackBar` call sites.
- Preserve every existing message string, localization choice, business condition, asynchronous `mounted` guard, and side effect.
- Every informational, success, failure, dynamic, and long-form error message uses exactly `Duration(seconds: 2)`.
- Every invocation immediately removes the current message with `removeCurrentSnackBar()` before showing the next message.
- Add no dependencies, global navigator keys, queues, categories, icons, colors, actions, or platform-specific branches.
- After migration, no direct `showSnackBar` call or `SnackBar` construction may remain under `joysong-flutter/lib` outside `core/transient_message.dart`.
- Run focused tests first, `flutter analyze` after they pass, and the full Flutter suite at most once with a ten-minute limit.

---

### Task 1: Lock the Shared Component Contract with Widget Tests

**Files:**
- Modify: `joysong-flutter/lib/core/transient_message.dart`
- Create: `joysong-flutter/test/core/transient_message_test.dart`

**Interfaces:**
- Consumes: Flutter `BuildContext`, `ScaffoldMessenger`, and `SnackBar`.
- Produces: `void showTransientMessage(BuildContext context, String message)` with immediate replacement and a fixed two-second duration.

- [ ] **Step 1: Add direct contract tests**

Create a Material test harness whose button calls the public helper. Cover fixed duration, latest-message replacement, no duplicates after rapid calls, timer restart, and final dismissal. Use precise pumps so the entrance animation, two-second display interval, and exit animation advance separately.

```dart
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/transient_message.dart';

void main() {
  Widget app(ValueNotifier<String> message) => MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () => showTransientMessage(context, message.value),
              child: const Text('show'),
            ),
          ),
        ),
      );

  testWidgets('uses a fixed two-second duration', (tester) async {
    final message = ValueNotifier('first');
    await tester.pumpWidget(app(message));
    await tester.tap(find.text('show'));
    await tester.pump();

    expect(tester.widget<SnackBar>(find.byType(SnackBar)).duration,
        const Duration(seconds: 2));
  });

  testWidgets('latest call replaces the current message and restarts expiry',
      (tester) async {
    final message = ValueNotifier('first');
    await tester.pumpWidget(app(message));
    await tester.tap(find.text('show'));
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 1500));

    message.value = 'latest';
    await tester.tap(find.text('show'));
    await tester.tap(find.text('show'));
    await tester.pump();

    expect(find.text('first'), findsNothing);
    expect(find.text('latest'), findsOneWidget);
    expect(find.byType(SnackBar), findsOneWidget);

    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 1700));
    expect(find.text('latest'), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('latest'), findsNothing);
  });
}
```

- [ ] **Step 2: Run the component tests before modifying the helper**

Run: `flutter test test/core/transient_message_test.dart`

Expected: the fixed-duration assertion passes against the existing helper; if the replacement/timer test exposes an animation timing difference, it fails only in that test and provides the exact behavior to correct.

- [ ] **Step 3: Keep the helper minimal and satisfy the contract**

The implementation must remain equivalent to:

```dart
const _transientMessageDuration = Duration(seconds: 2);

void showTransientMessage(BuildContext context, String message) {
  final messenger = ScaffoldMessenger.of(context);
  messenger
    ..removeCurrentSnackBar()
    ..showSnackBar(
      SnackBar(
        content: Text(message),
        duration: _transientMessageDuration,
      ),
    );
}
```

Do not add variants or optional parameters. Adjust only if the contract test demonstrates a concrete mismatch.

- [ ] **Step 4: Run the focused component tests**

Run: `flutter test test/core/transient_message_test.dart`

Expected: all tests pass.

- [ ] **Step 5: Commit the contract tests**

```bash
git add joysong-flutter/lib/core/transient_message.dart joysong-flutter/test/core/transient_message_test.dart
git commit -m "test: cover transient message replacement"
```

---

### Task 2: Migrate Agent, Authentication, Booking, Discover, and Identity Messages

**Files:**
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_page.dart`
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_profile_safety_page.dart`
- Modify: `joysong-flutter/lib/features/auth/presentation/auth_action_page.dart`
- Modify: `joysong-flutter/lib/features/booking/presentation/booking_page.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/catalog_institution_detail_view.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/catalog_project_detail_view.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/discover_page.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/identity_pages.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/institution_relationships_page.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart`
- Test: `joysong-flutter/test/features/agent/agent_chat_page_test.dart`
- Test: `joysong-flutter/test/features/booking/booking_page_test.dart`
- Test: `joysong-flutter/test/features/discover/discover_content_card_auto_translation_test.dart`
- Test: `joysong-flutter/test/features/discover/institution_picker_page_test.dart`
- Test: `joysong-flutter/test/features/identity/identity_error_messages_test.dart`
- Test: `joysong-flutter/test/features/identity/managed_institution_profile_page_test.dart`
- Test: `joysong-flutter/test/features/identity/professional_project_request_page_test.dart`

**Interfaces:**
- Consumes: `showTransientMessage(BuildContext context, String message)` from Task 1.
- Produces: migrated feature handlers with unchanged text and business behavior and no local `SnackBar` presentation.

- [ ] **Step 1: Record the failing migration guard for this file group**

Run:

```powershell
git grep -n "showSnackBar\|SnackBar(" -- joysong-flutter/lib/features/agent joysong-flutter/lib/features/auth joysong-flutter/lib/features/booking joysong-flutter/lib/features/discover joysong-flutter/lib/features/identity
```

Expected: matches list every remaining direct presentation in this group, including the agent copy message's local `hideCurrentSnackBar()` implementation.

- [ ] **Step 2: Replace each direct presentation with the shared helper**

Add this import to every file that presents a message:

```dart
import 'package:joysong_flutter/core/transient_message.dart';
```

Convert simple calls:

```dart
ScaffoldMessenger.of(context).showSnackBar(
  SnackBar(content: Text(message)),
);
```

to:

```dart
showTransientMessage(context, message);
```

Convert conditional content without moving its logic:

```dart
showTransientMessage(
  context,
  result.succeeded
      ? context.localized('操作成功', 'Operation completed')
      : (result.message ?? context.localized('操作失败', 'Operation failed')),
);
```

For agent copy feedback, delete the local `hideCurrentSnackBar()`/`SnackBar` chain and call the helper with the existing localized copied text. Preserve all `mounted` checks.

- [ ] **Step 3: Prove the group has no direct presentation left**

Run the Step 1 grep command again.

Expected: no output and exit code 1 because there are no matches.

- [ ] **Step 4: Run the closest feature tests**

Run:

```powershell
flutter test test/features/agent/agent_chat_page_test.dart test/features/booking/booking_page_test.dart
```

Run the discover and identity tests that exercise migrated presentation paths:

```powershell
flutter test test/features/discover/discover_content_card_auto_translation_test.dart test/features/discover/institution_picker_page_test.dart test/features/identity/identity_error_messages_test.dart test/features/identity/managed_institution_profile_page_test.dart test/features/identity/professional_project_request_page_test.dart
```

Expected: all selected tests pass with unchanged text assertions.

- [ ] **Step 5: Commit the first migration group**

```bash
git add joysong-flutter/lib/features/agent joysong-flutter/lib/features/auth joysong-flutter/lib/features/booking joysong-flutter/lib/features/discover joysong-flutter/lib/features/identity
git commit -m "refactor: unify core feature messages"
```

---

### Task 3: Migrate Messaging, Orders, Profile, Settings, Shell, and Social Messages

**Files:**
- Modify: `joysong-flutter/lib/features/messaging/presentation/messaging_pages.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/order_detail_page.dart`
- Modify: `joysong-flutter/lib/features/profile/presentation/edit_profile_page.dart`
- Modify: `joysong-flutter/lib/features/profile/presentation/profile_page.dart`
- Modify: `joysong-flutter/lib/features/profile/presentation/profile_support_pages.dart`
- Modify: `joysong-flutter/lib/features/settings/presentation/settings_page.dart`
- Modify: `joysong-flutter/lib/features/shell/presentation/app_shell.dart`
- Modify: `joysong-flutter/lib/features/social/presentation/diary_detail_page.dart`
- Modify: `joysong-flutter/lib/features/social/presentation/diary_share.dart`
- Modify: `joysong-flutter/lib/features/social/presentation/favorite_action_button.dart`
- Modify: `joysong-flutter/lib/features/social/presentation/report_action_button.dart`
- Modify: `joysong-flutter/lib/features/social/presentation/social_page.dart`
- Test: `joysong-flutter/test/features/settings/settings_page_test.dart`
- Test: `joysong-flutter/test/features/shell/app_shell_navigation_test.dart`
- Test: `joysong-flutter/test/features/messaging/dm_thread_page_test.dart`
- Test: `joysong-flutter/test/features/orders/order_detail_auto_translation_test.dart`
- Test: `joysong-flutter/test/features/orders/order_detail_page_test.dart`
- Test: `joysong-flutter/test/features/settings/settings_page_test.dart`
- Test: `joysong-flutter/test/features/shell/app_shell_navigation_test.dart`

**Interfaces:**
- Consumes: `showTransientMessage(BuildContext context, String message)` from Task 1.
- Produces: the remaining migrated feature handlers and a single application-wide message presentation path.

- [ ] **Step 1: Record the failing migration guard for this file group**

Run:

```powershell
git grep -n "showSnackBar\|SnackBar(" -- joysong-flutter/lib/features/messaging joysong-flutter/lib/features/orders joysong-flutter/lib/features/profile joysong-flutter/lib/features/settings joysong-flutter/lib/features/shell joysong-flutter/lib/features/social
```

Expected: matches list every remaining direct presentation in this group.

- [ ] **Step 2: Replace every direct presentation with the shared helper**

Import `package:joysong_flutter/core/transient_message.dart` in each listed file. Apply the same transformations from Task 2, keeping dynamic success/error expressions at their original call sites. Do not change message strings, localization, `mounted` guards, callbacks, navigation, repository calls, or controller state.

For existing local wrappers such as `_showMessage(String message)`, keep the wrapper if it expresses page intent, but change its body to:

```dart
void _showMessage(String message) {
  if (!mounted) return;
  showTransientMessage(context, message);
}
```

- [ ] **Step 3: Prove the group has no direct presentation left**

Run the Step 1 grep command again.

Expected: no output and exit code 1.

- [ ] **Step 4: Run focused regression tests**

Run:

```powershell
flutter test test/features/settings/settings_page_test.dart test/features/shell/app_shell_navigation_test.dart test/features/wallet/wallet_page_test.dart
```

Run the messaging and order tests that exercise migrated presentation paths:

```powershell
flutter test test/features/messaging/dm_thread_page_test.dart test/features/orders/order_detail_auto_translation_test.dart test/features/orders/order_detail_page_test.dart
```

Expected: all selected tests pass. The wallet test continues to prove rapid taps leave one message and the message expires.

- [ ] **Step 5: Commit the second migration group**

```bash
git add joysong-flutter/lib/features/messaging joysong-flutter/lib/features/orders joysong-flutter/lib/features/profile joysong-flutter/lib/features/settings joysong-flutter/lib/features/shell joysong-flutter/lib/features/social
git commit -m "refactor: unify remaining Flutter messages"
```

---

### Task 4: Enforce Exhaustive Migration and Verify the Flutter App

**Files:**
- Modify only files from Tasks 1-3 if formatting or verification exposes a concrete issue.
- Test: all tests selected in Tasks 1-3, followed by the Flutter suite once.

**Interfaces:**
- Consumes: all migrated feature call sites and the shared helper.
- Produces: static proof that the shared helper is the sole message presenter and a verified clean branch.

- [ ] **Step 1: Run the application-wide presentation guard**

Run:

```powershell
git grep -n "showSnackBar\|SnackBar(" -- joysong-flutter/lib ":(exclude)joysong-flutter/lib/core/transient_message.dart"
```

Expected: no output and exit code 1. Any match is a missed migration and must be converted before continuing.

- [ ] **Step 2: Format only touched Dart files**

Run `dart format` with the exact paths reported by:

```powershell
git diff --name-only --diff-filter=ACM | Select-String '\.dart$'
```

Expected: formatting completes without creating unrelated generated-file changes.

- [ ] **Step 3: Run all focused tests once**

Run the component test and every feature test selected in Tasks 2-3 in one `flutter test` invocation.

Expected: all focused tests pass.

- [ ] **Step 4: Run static analysis**

Run: `flutter analyze`

Expected: `No issues found!`

- [ ] **Step 5: Run the full Flutter test suite at most once**

Run: `flutter test`

Expected: all tests pass. If it exceeds ten minutes, stop it once, report progress and the slowest visible test, and do not restart the full suite.

- [ ] **Step 6: Inspect final scope and cleanliness**

Run:

```powershell
git diff --check
git status --short
git diff --stat master...HEAD
```

Expected: no whitespace errors, only planned source/test/doc changes, and no temporary or generated files.

- [ ] **Step 7: Commit verification-only corrections if any**

If Step 2-6 required source or test corrections:

```bash
git add joysong-flutter/lib joysong-flutter/test
git commit -m "test: verify unified Flutter messages"
```

If there are no corrections, do not create an empty commit.
