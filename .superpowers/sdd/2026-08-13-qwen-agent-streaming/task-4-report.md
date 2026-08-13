# Task 4 Report: Flutter typed SSE transport

## Result

- Added sealed domain events: `AgentStreamStarted`, `AgentStreamDelta`, `AgentStreamCompleted`, and `AgentStreamFailed`.
- Added `AgentRepository.streamMessage` and typed repository forwarding.
- Added authenticated streaming POST transport with `Accept: text/event-stream`, incremental UTF-8 decoding, SSE record assembly, multiline `data:` handling, typed JSON decoding, missing-terminal detection, and cancellation of the byte subscription and HTTP socket.
- Raw SSE records and HTTP response types remain inside the data layer.

## TDD evidence

- RED: the scoped test failed to compile because all stream event types and `streamMessage` were missing.
- GREEN: `flutter test --no-pub test/features/agent/agent_stream_repository_test.dart` passed 4/4 tests.
- Static analysis: scoped `flutter analyze --no-pub` over the five Task 4 Dart files reported `No issues found`.

## Coverage

- Named started/delta/completed events split across UTF-8 and SSE boundaries.
- Multiline `data:` JSON assembly.
- Stable terminal error decoding.
- EOF without completed/error terminal event.
- Subscription cancellation propagated to the fake byte stream; the real HTTP path also destroys the active response socket.

## Self-review

- Only Task 4 production/test files and this report are included in the commit.
- No Dio/HTTP response or raw SSE string is exposed through the repository contract.
- `ApiAgentRemoteDataSource` keeps its prior constructor compatible and delegates streaming authentication to the configured `ApiClient`.

## Fix round 1

- Moved stream POST creation into `ApiClient`, so production streaming reuses its existing access-token provider without a second nullable wiring path.
- Added `Authorization`, `Accept: text/event-stream`, JSON content type, and `Idempotency-Key`; the idempotency key remains in the JSON body too.
- Added a cancellable request/response handle with cancellation checks after opening the request, resolving authentication, and receiving the response. An opened request is aborted and an arrived response is cancelled.
- Decoder/upstream errors now emit the error and close the domain stream.
- RED evidence: production HTTP sent no Authorization header; decoder error did not close; cancellable HTTP lifecycle types were absent.
- GREEN evidence: the scoped Task 4 test passed 6/6 after the transport fix. Scoped analysis over six affected files subsequently reported no issues, including the added deterministic token-pending and response-pending cancellation cases.

## Fix round 2

- Changed `StreamHttpOperation.cancel()` to return `Future<void>` so response cancellation is owned instead of discarded.
- The data source awaits cancellation and absorbs the expected asynchronous already-closed response race, preventing an unhandled zone error.
- Added a regression test whose fake response throws asynchronously from `cancel()`: RED exposed an unhandled `SocketException`; GREEN passed 9/9 scoped tests.
- Added an assertion that a decoder failure cancels its underlying byte-stream subscription.
- Scoped analysis over the three affected files reported no issues.
