# Qwen Agent Streaming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Qwen → Kotlin SSE → Flutter end-to-end incremental Agent responses, persisting the assistant message only after a complete successful stream.

**Architecture:** Keep Spring MVC and the existing synchronous endpoint. Add a provider SSE parser and a narrowly scoped streaming orchestration component so the 1,243-line `ChatService` does not absorb transport lifecycle code. Flutter receives typed domain events from its data layer and maintains one replaceable local assistant placeholder.

**Tech Stack:** Kotlin, Spring Boot MVC, `SseEmitter`, `RestTemplate`, Jackson, JPA; Dart, Flutter, Dio byte streams; JUnit 5, MockMvc, Mockito, Flutter test.

## Global Constraints

- Preserve `POST /api/chat/sessions/{id}/messages` unchanged for old clients.
- Only Qwen main-chat streaming requests use `stream=true`; intent classification, translation, and the legacy chat endpoint remain non-streaming.
- Do not add environment variables or database migrations.
- Never persist a partial assistant message.
- A `completed` event is emitted only after successful persistence.
- Planning responses must be fully buffered and safety-processed before any user-visible content is emitted.
- Provider payloads, exception messages, user content, model names, and untrusted provider codes must not enter logs or public error events.
- Database tests use `myapp_worktree_qwen_agent_streaming`; print resolved host and database name before migrations/tests.

---

### Task 1: Provider streaming request and SSE parser

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/provider/AgentProviderRequestFactory.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/provider/QwenChatStreamParser.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/ChatCompletionRequestTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/agent/QwenChatStreamParserTest.kt`

**Interfaces:**
- Produces: `build(provider, purpose, model, messages, maxTokens, streaming: Boolean = false): Map<String, Any>`.
- Produces: `QwenChatStreamParser.parse(input: InputStream, onDelta: (String) -> Unit): String`; returns the accumulated text only after `[DONE]`, otherwise throws a controlled parser exception with no raw payload.

- [ ] **Step 1: Add failing request-factory tests** proving Qwen `CHAT + streaming=true` emits `stream=true`, while intent and default calls emit `false`; reject streaming for non-chat purposes.
- [ ] **Step 2: Run** `./gradlew.bat test --tests com.joysong.server.agent.ChatCompletionRequestTest --no-daemon`; expect the new streaming contract to fail.
- [ ] **Step 3: Implement the explicit `streaming` argument** without model-name inference and retain every existing request field.
- [ ] **Step 4: Add failing parser tests** using byte streams split inside UTF-8 characters and JSON/SSE boundaries; assert ordered deltas, empty-delta tolerance, heartbeat tolerance, `[DONE]`, malformed JSON, upstream error event, and EOF-before-DONE.
- [ ] **Step 5: Run** `./gradlew.bat test --tests com.joysong.server.agent.QwenChatStreamParserTest --no-daemon`; expect missing parser failures.
- [ ] **Step 6: Implement the parser** with `InputStreamReader(UTF_8)`/buffered line parsing, Jackson `JsonNode`, a completion flag, and stable internal exceptions that never include the input line.
- [ ] **Step 7: Run both Task 1 test classes once** and expect all tests to pass.
- [ ] **Step 8: Commit** `feat: add Qwen chat stream parser`.

### Task 2: Backend streaming event contract and orchestration

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/streaming/AgentStreamEvent.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/streaming/AgentStreamingService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/controller/ChatController.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentStreamingServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt`

**Interfaces:**
- Consumes: Task 1 parser and request factory.
- Produces: sealed events `Started(traceId, turnId, userMessage)`, `Delta(content)`, `Completed(turn: ChatTurnResponse)`, and `Failed(code, traceId, retryable)` with stable JSON field names.
- Produces: `AgentStreamingService.stream(sessionId: String, userId: String, request: SendMessageRequest, sink: AgentStreamSink)`; `AgentStreamSink` exposes `started`, `delta`, `completed`, `failed`, and `isOpen`.
- Produces: controller endpoint `POST /api/chat/sessions/{id}/messages/stream`, `produces = MediaType.TEXT_EVENT_STREAM_VALUE`, returning `SseEmitter`.

- [ ] **Step 1: Add failing service tests** for normal delta order, persistence-before-completed, planning full-buffer behavior, provider failure, malformed/unfinished stream, emitter cancellation, and exactly one terminal event.
- [ ] **Step 2: Run** `./gradlew.bat test --tests com.joysong.server.agent.AgentStreamingServiceTest --no-daemon`; expect missing types/service failures.
- [ ] **Step 3: Extract only the reusable turn preparation and completion seams from `ChatService`** so streaming reuses current authorization, intent, context, post-processing, catalog, lifecycle, and persistence behavior; do not duplicate business rules.
- [ ] **Step 4: Implement `AgentStreamingService`** on the configured Agent executor, call `RestTemplate.execute` to consume the response stream, buffer the complete answer, suppress raw planning deltas, invoke existing finalization once, and emit `Completed` only afterward.
- [ ] **Step 5: Implement idempotent cleanup** with an atomic terminal guard. Timeout, disconnect, send failure, provider failure, or parser failure closes/cancels the upstream request, fails the turn, and never stores an assistant message.
- [ ] **Step 6: Add failing controller tests** for content type, named event serialization, authenticated user capture before async execution, pre-stream HTTP errors, and post-start SSE error events.
- [ ] **Step 7: Replace the disabled controller placeholder** with an emitter adapter that registers completion/error/timeout callbacks before starting work and never routes asynchronous failures through JSON `ControllerAdvice`.
- [ ] **Step 8: Run** `./gradlew.bat test --tests com.joysong.server.agent.AgentStreamingServiceTest --tests com.joysong.server.agent.AgentWorkflowCoreTest --no-daemon`; expect all to pass.
- [ ] **Step 9: Commit** `feat: stream Qwen agent responses over SSE`.

### Task 3: Backend persistence, idempotency, and security integration

**Files:**
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt`
- Modify only if a verified defect requires it: `joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/TurnLifecycleService.kt`
- Modify only if a verified defect requires it: `joysong-server/src/main/kotlin/com/joysong/server/agent/diagnostics/AgentOperationLogger.kt`

**Interfaces:**
- Consumes: Task 2 endpoint and existing turn lifecycle.
- Verifies: successful replay emits the stored result without another provider call; running duplicate does not start another stream; failed execution may retry; assistant persistence is all-or-nothing.

- [ ] **Step 1: Replace the old `AGENT_STREAMING_DISABLED` integration assertion** with fake-provider SSE cases for success, planning safety, replay, running conflict, upstream 4xx/5xx, timeout, malformed event, EOF-before-DONE, and disconnect.
- [ ] **Step 2: Before database execution print** `AGENT_CHAT_TEST_DB_HOST` and `AGENT_CHAT_TEST_DB_NAME`, assert the latter equals `myapp_worktree_qwen_agent_streaming`, then run only the newly added methods via `mysqlIntegrationTest --tests`.
- [ ] **Step 3: For each failure, rerun only its method** and make the smallest production correction. Do not weaken message-count, lifecycle-state, provider-call-count, event-order, or log-redaction assertions.
- [ ] **Step 4: Run the affected integration class at most once** after all individual failures pass; stop and report if it approaches ten minutes.
- [ ] **Step 5: Commit** `test: verify streamed agent turn lifecycle`.

### Task 4: Flutter typed SSE transport

**Files:**
- Modify: `joysong-flutter/lib/features/agent/domain/agent_models.dart`
- Modify: `joysong-flutter/lib/features/agent/domain/agent_repository.dart`
- Modify: `joysong-flutter/lib/features/agent/data/agent_remote_data_source.dart`
- Modify: `joysong-flutter/lib/features/agent/data/agent_repository_impl.dart`
- Create: `joysong-flutter/test/features/agent/agent_stream_repository_test.dart`

**Interfaces:**
- Produces: sealed/domain event family `AgentStreamStarted`, `AgentStreamDelta`, `AgentStreamCompleted`, `AgentStreamFailed`.
- Produces: `Stream<AgentStreamEvent> streamMessage({required String sessionId, required String content, required String idempotencyKey})`.
- The data source owns authenticated POST, `Accept: text/event-stream`, incremental UTF-8 decoding, SSE record assembly, JSON decoding, cancellation, and missing-terminal detection.

- [ ] **Step 1: Add failing repository/data-source tests** using a fake byte-stream response split across UTF-8 and SSE boundaries; cover named events, multiline data, stable error decoding, EOF without terminal event, and subscription cancellation.
- [ ] **Step 2: Run** `flutter test test/features/agent/agent_stream_repository_test.dart`; expect missing streaming API failures.
- [ ] **Step 3: Implement typed domain events and repository contract** without exposing Dio response objects or raw SSE strings outside the data layer.
- [ ] **Step 4: Implement incremental transport parsing** with `utf8.decoder`, line buffering, blank-line event dispatch, terminal-event tracking, and cancellation of the HTTP subscription when the Dart stream is cancelled.
- [ ] **Step 5: Run the Task 4 test file** and expect all tests to pass.
- [ ] **Step 6: Commit** `feat: add Flutter agent SSE transport`.

### Task 5: Flutter incremental chat state and retry UI

**Files:**
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_controller.dart`
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_page.dart`
- Modify: `joysong-flutter/test/features/agent/agent_chat_controller_test.dart`
- Modify: `joysong-flutter/test/features/agent/agent_chat_page_test.dart`
- Modify if required by existing busy-state assertions: `joysong-flutter/test/features/agent/agent_chat_busy_actions_test.dart`

**Interfaces:**
- Consumes: Task 4 typed events.
- Produces: one local assistant placeholder per send with explicit streaming/failed-local status; a retry command reuses the pending user input and does not duplicate the visible user message.

- [ ] **Step 1: Add failing controller tests** for immediate placeholders, ordered delta append, completed replacement with persisted message, failure retaining partial text, retry replacement, duplicate-send prevention, session switch cancellation, and dispose cancellation.
- [ ] **Step 2: Run** `flutter test test/features/agent/agent_chat_controller_test.dart`; expect failures against the current `Future<ChatTurn>` flow.
- [ ] **Step 3: Change the controller to subscribe to `streamMessage`**, guard callbacks with the operation/session identity, update only the active placeholder, and cancel the subscription on switch/dispose.
- [ ] **Step 4: Add failing widget tests** for live text updates and the exact interruption copy `生成中断，可重试`, including a retry action that does not duplicate the user bubble.
- [ ] **Step 5: Implement the minimal page rendering and retry control** using the existing message bubble and busy-action patterns.
- [ ] **Step 6: Run the three affected Flutter test files once**, then run `flutter analyze` once; fix only new issues attributable to this change.
- [ ] **Step 7: Commit** `feat: render streamed agent responses in Flutter`.

### Task 6: Focused verification and contract documentation

**Files:**
- Modify: `docs/AI_AGENT_DEVELOPMENT.md`
- Modify: `docs/CONFIGURATION_GUIDE.md`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/config/ProductionProfileTest.kt` only if the current documentation guard needs a streaming-contract assertion.

**Interfaces:**
- Documents: new endpoint/event schema, old endpoint compatibility, Qwen-only streaming behavior, planning buffering, failure persistence semantics, and unchanged five-variable deployment contract.

- [ ] **Step 1: Update current documentation** with exact endpoint and event examples. State that no new environment variable enables streaming and that partial assistant output is local-only after failure.
- [ ] **Step 2: Run backend focused tests once:** request factory, parser, streaming service/controller, workflow core, logger, RestTemplate configuration, Agent configuration, and documentation guard.
- [ ] **Step 3: Run Flutter focused tests once:** repository stream, controller, page, and busy actions; do not repeat already-passing commands.
- [ ] **Step 4: Inspect `git diff --check`, tracked temporary files, raw response logging, and environment-variable additions**; remove test fixtures or generated files that are no longer needed.
- [ ] **Step 5: Request a final code review** against the approved design, fix only Critical/Important findings with focused RED/GREEN tests, and repeat the affected test only.
- [ ] **Step 6: Commit** `docs: document Qwen agent streaming`.

