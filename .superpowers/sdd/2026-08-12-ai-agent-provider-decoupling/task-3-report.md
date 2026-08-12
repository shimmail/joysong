# Task 3 Report: Five-variable Agent contract and Qwen translation isolation

## Result

- Agent deployment configuration now exposes exactly five environment variables:
  `AI_AGENT_PROVIDER`, `AI_AGENT_API_KEY`, `AI_AGENT_BASE_URL`, `AI_AGENT_MODEL`, and
  `AI_AGENT_INTENT_MODEL`.
- Agent enablement, direct-connection proxy policy, 90-second turn lease, enabled intent
  parser, disabled demo fallback, HTTP timeouts, stream behavior, and reasoning behavior
  remain fixed code policy rather than environment bindings.
- Translation reuses `AiAgentProperties.apiKey` and `AiAgentProperties.baseUrl`, always
  sends the Qwen request shape, and always selects the isolated model constant
  `qwen3.7-flash`. The old provider fallback and OpenAI/Qwen-specific constructor fields
  were removed.
- Translation input validation and content-type-normalized cache keys remain unchanged.

## TDD evidence

### RED

Command (project-local `GRADLE_USER_HOME=.tmp/gradle-user-home-codex`):

```text
.\gradlew.bat test \
  --tests com.joysong.server.translation.service.TranslationServiceTest \
  --tests com.joysong.server.config.ProductionProfileTest \
  --tests com.joysong.server.config.AiAgentPropertiesTest
```

Observed result: 53 tests completed, 3 failed, exactly in the three newly added contract
tests:

- translation still selected the independent legacy Qwen URL/credential/model;
- YAML and `.env.example` still exposed obsolete AI/translation variables;
- obsolete Agent settings still overrode runtime policy.

An earlier attempt with a fresh project-local cache did not reach tests because Gradle 8.9
download timed out after 10 seconds. The existing project-local cache was reused; it is not
counted as RED evidence.

### GREEN

Focused five-class command:

```text
.\gradlew.bat test \
  --tests com.joysong.server.translation.service.TranslationServiceTest \
  --tests com.joysong.server.config.ProductionProfileTest \
  --tests com.joysong.server.config.AiAgentProfileStartupTest \
  --tests com.joysong.server.config.AiAgentPropertiesTest \
  --tests com.joysong.server.config.RestTemplateConfigTest
```

Observed result: 45 tests completed, 1 failed. The four other classes passed. The single
failure showed Spring constructor binding rejects removed settings rather than silently
ignoring them, which is the stronger fixed-policy behavior. After changing only that test
expectation, the failed class was rerun:

```text
.\gradlew.bat test --tests com.joysong.server.config.AiAgentPropertiesTest
```

Observed result: BUILD SUCCESSFUL in 11 seconds.

Closest non-database Agent tests:

```text
.\gradlew.bat test --tests com.joysong.server.agent.AgentWorkflowCoreTest
```

Observed result: 45 tests, 0 failures, BUILD SUCCESSFUL in 7 seconds.

Closest MySQL Agent integration class, using the test's isolated database
`myapp_worktree_ai_provider_decoupling` and printing its host/name before container start:

```text
.\gradlew.bat mysqlIntegrationTest \
  --tests com.joysong.server.agent.AgentChatFlowIntegrationTest
```

Observed result: 33 tests completed, 6 failed in 44 seconds. Fixed intent-parser policy now
causes one intent call plus one answer call for ambiguous requests; old fixture assertions
assumed the removed mutable parser override. Per the test rules, only the six failed cases
were rerun (four parameterized statuses plus two ordinary cases):

```text
.\gradlew.bat mysqlIntegrationTest \
  --tests 'com.joysong.server.agent.AgentChatFlowIntegrationTest.successful synchronous turn is persisted once and replayed without a transaction around the model call' \
  --tests 'com.joysong.server.agent.AgentChatFlowIntegrationTest.idempotency conflict is exposed as a stable domain error without another model call' \
  --tests 'com.joysong.server.agent.AgentChatFlowIntegrationTest.HTTP provider status failures share one stable redacted contract'
```

Observed result: 6 tests, 0 failures, BUILD SUCCESSFUL in 32 seconds.

No full test suite was run because the focused configuration/translation tests and closest
Agent tests exercised the changed signatures and policies, and project rules limit redundant
test runs.

## Self-review

- Static scan of all three YAML files plus `.env.example` found exactly the five allowed
  environment names and no `OPENAI_*`, `QWEN_*`, `TRANSLATION_*`, proxy, lease, parser,
  fallback, stream, or reasoning substitutions.
- Production source scan found no obsolete environment-variable guidance; two stale
  `ChatService` comments/messages were updated to describe fixed policy generically.
- `git diff --check` passed after removing an extra EOF blank line.
- The diff is limited to brief-owned production/config/test files and direct Agent fixtures
  affected by the removed mutable settings.
- No migrations changed and no shared development database was accessed.
- Gradle's existing deprecation notice and HotSpot class-sharing warning remain unrelated
  build-tool/runtime warnings.

## Commit

Branch: `codex/ai-provider-decoupling`

Commit subject: `feat: enforce five-variable AI agent contract`

## Review fix round 1

Three Important review findings were addressed in one follow-up:

- Current deployment/configuration documentation now exposes only the five-variable Agent
  contract. The regression scan covers seven top-level operational guides and excludes only
  Markdown filenames such as `AI_AGENT_TESTING.md`, not document prose or code blocks.
- The Qwen `.env.example` is internally consistent: chat uses `qwen-plus`, intent parsing uses
  `qwen-turbo`, and translation remains the isolated code constant `qwen3.7-flash`.
- Translation now injects a dedicated direct `translationRestTemplate`; setting
  `GOOGLE_PROXY_URL` affects only the Google identity client and cannot route translation
  traffic through that proxy.

### Round 1 RED

```text
.\gradlew.bat test \
  --tests com.joysong.server.config.ProductionProfileTest \
  --tests com.joysong.server.config.RestTemplateConfigTest
```

Observed result: 12 tests completed, 3 failed. The three new tests independently exposed old
variables in current guides, incompatible Qwen example models, and translation inheriting the
Google proxy.

### Round 1 GREEN

The same two-class command completed 12 tests with 0 failures in 10 seconds. Direct qualifier
regressions were then checked without repeating the passing command:

```text
.\gradlew.bat test \
  --tests com.joysong.server.translation.service.TranslationServiceTest \
  --tests com.joysong.server.config.AiAgentProfileStartupTest
```

Observed result: BUILD SUCCESSFUL in 5 seconds. Static review found exactly the five allowed
environment names across the maintained operational guides, and `git diff --check` passed.
