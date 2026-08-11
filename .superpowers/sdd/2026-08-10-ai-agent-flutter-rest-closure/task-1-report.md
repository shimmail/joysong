# Task 1 Report: Remove the Flutter SSE execution path

## Status

Complete. The Flutter Agent chat path is REST-only and the SSE transport,
interfaces, configuration, tests, and stop UI have been removed.

## TDD evidence

- Flutter SDK: `D:\code\kotlin\joysong\.flutter-cache\sdk\flutter`
  (Flutter 3.44.8, Dart 3.12.2), discovered from the existing main-worktree
  package configuration. No SDK was installed; worktree dependencies were
  prepared from the local cache with `flutter pub get --offline`.
- RED command:
  `flutter test test/features/agent/agent_chat_controller_test.dart`
  exited 1 with the three intended failures:
  - default send expected one REST repository call but observed zero because it
    still selected SSE;
  - failed send expected one REST repository call but observed zero for the
    same reason;
  - the real HTTP request body expected a non-empty `idempotencyKey` but
    observed `null`.
- GREEN command:
  `flutter test --no-pub test/features/agent/agent_chat_controller_test.dart test/features/agent/agent_plan_view_test.dart`
  passed 6/6 tests.

## Implementation

- `AgentChatController` now has only the REST execution path. Each accepted
  send invokes `repository.sendMessage` once, surfaces failure for manual retry,
  and never auto-retries.
- The REST data source includes a fresh, bounded `idempotencyKey` in the JSON
  body while continuing to use ordinary `ApiClient.post`, whose write path is
  not replayed after authentication failure.
- Removed `ChatStreamConnection`, stream events, `streamMessage`, transport
  selection, `ChatSseTransport`, `sseEnabled`, and the obsolete SSE tests.
- `AppShell` constructs the Agent repository directly from its existing
  `ApiClient`; the SSE-only `apiRoot`/token/language gate was removed through
  `AuthGate` and the app composition root without changing `ApiClient`
  creation.
- Removed the REST-busy fake stop behavior. The composer now disables its send
  action while busy and no longer advertises cancellation that cannot stop the
  underlying POST. Session mutation actions are ignored while the send is in
  flight.

## Verification

- Focused Agent tests: pass, 6/6.
- Required production grep: zero references for `ChatSseTransport`,
  `streamMessage`, and `sseEnabled`.
- Extended production grep: zero references for `streamingEnabled`,
  `ChatStreamConnection`, `ChatStreamEvent`, `cancelSend`, the stop-generation
  label, streaming error text, and `streamTransport`.
- `flutter analyze` found no errors or warnings. The repository treats info
  diagnostics as fatal, so the exact command exits 1 for three pre-existing
  deprecations also present in `HEAD`: `cacheExtent`, `WillPopScope`, and
  `onPopPage`. The same scoped analysis with `--no-fatal-infos` exits 0 and
  reports only those three infos.
- `git diff --check`: pass.

## Review notes

- No full Flutter test suite was run; validation followed the project rule to
  run the smallest relevant Agent tests and one scoped analysis.
- The pre-existing navigation and scroll deprecations were left unchanged to
  avoid unrelated refactoring.
