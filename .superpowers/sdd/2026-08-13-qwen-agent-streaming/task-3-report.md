# Task 3 Report: Backend persistence, idempotency, and security integration

## Scope

- Replaced the obsolete `AGENT_STREAMING_DISABLED` integration assertion with fake-provider SSE coverage.
- Added persistence and event-contract coverage for success, planning safety, replay, running conflict, provider HTTP errors, timeout, malformed SSE, EOF-before-DONE, disconnect, and failed-turn retry.
- Tightened the integration container guard to require exactly `myapp_worktree_qwen_agent_streaming`.
- Corrected one verified lifecycle defect: a non-stale failed turn can retry the same idempotency key by reusing its existing turn and user message.
- `AgentOperationLogger` required no production change.

## Database isolation

Every database test command printed and asserted:

```text
AGENT_CHAT_TEST_DB_HOST=testcontainers-docker
AGENT_CHAT_TEST_DB_NAME=myapp_worktree_qwen_agent_streaming
```

The Testcontainers wrapper also prints its resolved host and database name and now refuses to start unless the database name equals `myapp_worktree_qwen_agent_streaming`. No database was dropped or reset.

## TDD and verification evidence

1. Success method initially failed during context startup because its existing fixture used an obsolete provider value/base URL. After switching the fixture to `QWEN`, the exact approved URL, and the fake-provider path, the method passed:
   - `HTTP streaming success emits ordered events and persists one complete turn`
   - `BUILD SUCCESSFUL in 1m 11s`
2. Remaining new-method baseline ran 11 invocations in `1m 07s`: 9 passed and 2 failed.
   - Passing without rerun: planning safety, replay, provider 400/429/500, timeout, malformed SSE, EOF-before-DONE, disconnect.
   - Running conflict failed only because the expected stable code was misspelled in the test; the actual HTTP 409 and no-provider-call behavior were correct. After correcting the fixture to `TURN_IN_PROGRESS`, its single-method run passed in `1m 23s`.
   - Failed retry exposed the production defect: `TurnLifecycleService` synchronously rejected every non-stale `FAILED` turn, so the second request never started async processing.
3. Minimal lifecycle correction resets the same failed turn to `RUNNING`, refreshes its lease and timestamps, clears the prior error, and returns `Started` with the original turn id, trace id, and sequence number. It does not create another user message or turn.
   - `failed streaming execution retries the same idempotency key without duplicating user message`
   - `BUILD SUCCESSFUL in 1m 53s`
   - Assertions prove two provider calls, one turn, exactly one user and one assistant message, and final `SUCCEEDED` state.

Per coordination and the test rule against repeating passing commands, the full integration class was not rerun: the remaining-method baseline plus isolated reruns already supplied current evidence for every new invocation. No full-suite run was performed.

## Review fix round 1

Closed the four Important test gaps; the two Minor review notes were intentionally left unchanged.

- Captured real `AgentOperationLogger` Logback events for an SSE upstream 500 and asserted the provider body marker, API key, email, and phone number were absent.
- Replaced the artificial closed sink test with a real MockMvc async SSE request and servlet completion callback. This RED proved the turn was persisted as `CANCELLED`; the minimal production correction now records a disconnected streamed turn as `FAILED` with `CLIENT_DISCONNECTED`, while still persisting no assistant message.
- Strengthened failed retry coverage to prove the original turn id, trace id, sequence number, and user message id survive; error/completion are cleared while RUNNING; start and lease timestamps refresh.
- Added a provider latch concurrency case proving a second same-key HTTP request receives `TURN_IN_PROGRESS` while exactly one turn, one user message, and one provider stream exist.

Verification evidence:

- First four-method run: expected test compile RED due to a nullable servlet async context; test fixture corrected without production changes.
- Second four-method run: 4 tests completed, 3 passed, disconnect failed with `expected FAILED but was CANCELLED` in `1m 27s`.
- Disconnect-only run after the minimal production correction: `BUILD SUCCESSFUL in 1m 17s`.
- The other three already-passing methods were not rerun.

## Files changed

- `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/TurnLifecycleService.kt`
