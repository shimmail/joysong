# Unified Flutter Transient Messages Design

## Goal

Replace every direct Flutter `SnackBar` presentation in the application with one reusable transient-message component. Every message must immediately replace the currently visible message and disappear automatically after two seconds on both Android and iOS.

## Scope

- Migrate all 58 feature-level `showSnackBar` call sites to the shared component.
- Keep `lib/core/transient_message.dart` as the single presentation entry point.
- Preserve every existing message string, localization choice, and business condition.
- Apply the same two-second duration to informational, success, failure, dynamic, and long-form error messages.
- Migrate the agent-chat copy notification from its local implementation to the shared component.
- Do not add new dependencies, global navigator keys, queues, message categories, icons, colors, or actions.

## Component Contract

The public interface remains:

```dart
void showTransientMessage(BuildContext context, String message)
```

Each invocation must:

1. Resolve the nearest `ScaffoldMessenger` from `context`.
2. Immediately remove the currently visible `SnackBar` with `removeCurrentSnackBar()`.
3. Show exactly one new `SnackBar` containing the supplied message.
4. Set its duration to exactly `Duration(seconds: 2)`.

The latest call wins. Repeated calls must never create a visible or pending queue, and the two-second timer restarts from the most recent invocation.

## Migration Strategy

Every feature page imports `package:joysong_flutter/core/transient_message.dart` and replaces direct `ScaffoldMessenger.of(context).showSnackBar(...)` expressions with `showTransientMessage(context, message)`.

Nested expressions and conditional message selection remain at the call site so the shared component owns only presentation behavior. Existing asynchronous `mounted` checks remain unchanged. The migration must not alter repository calls, navigation, error handling, copy, or localization.

After migration, `lib/` must contain no direct `showSnackBar` calls or `SnackBar` construction outside `core/transient_message.dart`.

## Accessibility and Platform Behavior

The component continues to use Flutter's Material `SnackBar` and `Text`, preserving standard semantics and screen-reader exposure. Because Flutter code is shared, Android and iOS receive identical replacement and duration behavior without platform-specific branches.

The approved two-second duration also applies to long messages. Message wording will not be shortened as part of this task.

## Testing

Add `test/core/transient_message_test.dart` to verify the shared contract directly:

- a message is presented as one `SnackBar` with a two-second duration;
- a later message immediately replaces the earlier message;
- rapid repeated calls never leave duplicate or queued messages;
- the expiration timer restarts for the latest invocation;
- the latest message disappears after its two-second display interval and dismissal animation.

Retain existing feature tests as regression coverage for business behavior and localized text. Update only tests whose setup or timing is coupled to a local `SnackBar` implementation.

Verification order follows the repository rules:

1. Run the new component test first.
2. Run the closest affected feature tests while fixing failures.
3. Run `flutter analyze` after relevant tests pass.
4. Run the full Flutter test suite at most once; stop and report if it exceeds ten minutes.

## Acceptance Criteria

- All application bottom messages use the shared component.
- A newly triggered message replaces the current message immediately.
- Every message has an exact two-second duration.
- Repeated taps do not extend display time through queued messages.
- Existing text, localization, and business behavior remain unchanged.
- Component tests, affected feature tests, and static analysis pass.
- The worktree contains no temporary files or unrelated changes.
