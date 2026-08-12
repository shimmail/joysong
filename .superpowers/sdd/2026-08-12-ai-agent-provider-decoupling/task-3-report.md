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

## Review fix round 2

The documentation guard now recursively enumerates every Markdown file under `doc/` and
`docs/`. Its only path exclusion is `docs/superpowers/`, whose specs and plans are historical
requirements records. Markdown filenames are stripped before matching so links such as
`AI_AGENT_TESTING.md` are not confused with environment variables; prose and code blocks are
still scanned. At verification time the guard covered 27 current documents.

The remaining current-document inconsistencies were corrected:

- `doc/项目技术文档.md` now shows the five Qwen Agent variables and states that the Google
  proxy is identity-only and cannot affect Agent or translation traffic.
- `AI_TRANSLATION_SOLUTION.md` no longer describes an OpenAI fallback or selectable provider.
- `AI_AGENT_ROLLOUT.md` consistently describes a Qwen canary instead of FastAIToken.

### Round 2 RED/GREEN

```text
.\gradlew.bat test --tests com.joysong.server.config.ProductionProfileTest
```

RED: 8 tests completed, 2 failed. The recursive contract test found the old variables in
`doc/项目技术文档.md`; the semantic guard found obsolete OpenAI and FastAIToken guidance.

GREEN: the same class completed 8 tests with 0 failures in 4 seconds. A fresh recursive scan
found exactly `AI_AGENT_PROVIDER`, `AI_AGENT_API_KEY`, `AI_AGENT_BASE_URL`,
`AI_AGENT_MODEL`, and `AI_AGENT_INTENT_MODEL`; `git diff --check` passed.

## Review fix round 3

The recursive current-document guard now also rejects obsolete Spring property keys,
Agent injection through `@Qualifier("llmRestTemplate")`, blank-key demo fallback examples,
and statements that make `google.proxy-url` drive Agent or translation traffic. The proxy
patterns target positive coupling language so the required statement that Google identity is
isolated from Agent and translation clients remains valid.

The two remaining documents now use `AiAgentProperties`, `agentLlmRestTemplate`,
`agentIntentParserRestTemplate`, and `translationRestTemplate`. They describe the five-variable
Qwen contract, stable provider errors rather than demo fallback, and direct Agent/translation
network policy independent of the Google identity proxy.

### Round 3 RED/GREEN

```text
.\gradlew.bat test --tests com.joysong.server.config.ProductionProfileTest
```

RED: 9 tests completed, 1 failed. The new semantic guard detected the obsolete property and
network examples in the current documentation.

GREEN: the same class completed 9 tests with 0 failures in 6 seconds. A focused static scan of
the two corrected documents found no obsolete `openai.api-key`, `openai.base-url`,
`openai.model`, Agent `@Qualifier("llmRestTemplate")`, or blank-key demo fallback pattern.

### Round 3 self-review

- The documentation enumeration still covers all Markdown under `doc/` and `docs/`, excluding
  only historical `docs/superpowers/` requirements records.
- Generic discussion of third-party protocols is not banned; the guard targets obsolete
  configuration and unsafe fallback/network examples.
- No production code, migrations, database, or environment-variable surface changed.
- Only the focused `ProductionProfileTest` was rerun, following the project test rules.

## Review fix round 4

The environment-variable scan is now case-insensitive while still requiring the five allowed
names to use their exact uppercase spelling. A focused synthetic regression covers lowercase
and mixed-case legacy names, so variants such as `openai_api_key` and `OpenAI_BASE_URL` no
longer evade the guard.

The current-document guard now rejects stable obsolete configuration tokens rather than
trying to infer proxy semantics from Chinese or English word order. It rejects legacy
provider properties, removed environment-facing Agent policy properties, and the obsolete
`llmRestTemplate` bean name anywhere in current documentation.

The unused `llmRestTemplate` bean was removed. Google identity verification already creates
its own `NetHttpTransport` inside `AuthenticationService`, while Agent, intent parsing, and
translation retain their dedicated direct `RestTemplate` beans. The bean's proxy factory
branch and now-unreferenced `AiAgentProxyUrlPolicy` were removed with it.

The two inaccurate guides were aligned with production code:

- `doc/项目技术文档.md` now explains that adding a provider requires code changes to
  `AiAgentProvider`, `AiAgentProviderUrlPolicy`, and `AgentProviderRequestFactory`, plus tests;
  environment variables can only select an already-supported enum value and allowlisted URL.
- `doc/系统安全加固开发文档.md` now states that `AuthenticationService` owns the Google
  transport and documents only the dedicated direct Agent and translation clients.

### Round 4 RED

```text
.\gradlew.bat test \
  --tests com.joysong.server.config.ProductionProfileTest \
  --tests com.joysong.server.config.RestTemplateConfigTest
```

Observed result: 15 tests completed, 3 failed, exactly in the new regressions for mixed-case
legacy environment names, stable obsolete documentation tokens, and the still-published
unused legacy bean.

### Round 4 GREEN

The same two-class command completed successfully in 10 seconds. After tightening the allowed
name assertion to preserve exact uppercase spelling, only the changed class was rerun:

```text
.\gradlew.bat test --tests com.joysong.server.config.ProductionProfileTest
```

Observed result: BUILD SUCCESSFUL in 6 seconds.

Direct bean-consumer regressions were checked without repeating the passing command:

```text
.\gradlew.bat test \
  --tests com.joysong.server.config.AiAgentProfileStartupTest \
  --tests com.joysong.server.translation.service.TranslationServiceTest
```

Observed result: 7 tests, 0 failures, BUILD SUCCESSFUL in 4 seconds.

After removing the proxy-policy helper left unreachable by the legacy bean deletion:

```text
.\gradlew.bat test --tests com.joysong.server.config.AiAgentPropertiesTest
```

Observed result: BUILD SUCCESSFUL in 16 seconds, including fresh production and test Kotlin
compilation.
