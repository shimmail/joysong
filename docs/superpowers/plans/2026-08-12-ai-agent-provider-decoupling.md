# AI Agent Provider Decoupling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Qwen a first-class Agent provider with independent Agent credentials and distinct chat, intent, and translation models.

**Architecture:** `AiAgentProperties` carries an explicit provider and Agent-only connection settings. A provider-aware URL policy and request factory isolate protocol decisions from `ChatService`; translation reads a separate flat `translation.*` contract.

**Tech Stack:** Kotlin, Spring Boot configuration properties, RestTemplate, JUnit 5, MockRestServiceServer.

## Global Constraints

- Agent never falls back to translation, `OPENAI_*`, or `QWEN_*` credentials.
- Chat and intent share provider credentials but retain separate model names.
- Translation reads only `TRANSLATION_*` variables.
- Provider capabilities are never inferred from model-name prefixes.
- Do not modify unrelated dirty-worktree files.

---

### Task 1: Agent provider configuration and URL validation

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/AiAgentProperties.kt`
- Replace: `joysong-server/src/main/kotlin/com/joysong/server/config/OpenAiBaseUrlPolicy.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/ConfigValidator.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/config/AiAgentPropertiesTest.kt`

**Interfaces:**
- Produces: `enum class AiAgentProvider { QWEN, OPENAI_COMPATIBLE }`
- Produces: `AiAgentProviderUrlPolicy.normalizeAllowed(provider, value): String?`

- [ ] Add failing tests for case-insensitive provider binding, unknown provider rejection, Qwen DashScope URL acceptance, FastAIToken acceptance, mismatched hosts, and new validation variable names.
- [ ] Run `./gradlew test --tests com.joysong.server.config.AiAgentPropertiesTest` and verify the new tests fail for missing provider behavior.
- [ ] Implement provider parsing, provider-aware URL normalization, and `AI_AGENT_*` validation messages.
- [ ] Run the same test class and verify it passes.

### Task 2: Provider request factory and ChatService integration

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/provider/AgentProviderRequestFactory.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Replace test: `joysong-server/src/test/kotlin/com/joysong/server/chat/service/ChatCompletionRequestTest.kt`

**Interfaces:**
- Consumes: `AiAgentProvider`
- Produces: `AgentRequestPurpose { CHAT, INTENT }`
- Produces: `AgentProviderRequestFactory.build(provider, model, messages, maxOutputTokens, purpose): Map<String, Any>`

- [ ] Add failing tests proving Qwen chat and intent shapes, conservative compatible shape, and identical behavior for differently named models under one provider.
- [ ] Run `ChatCompletionRequestTest` and verify failure because the factory is absent.
- [ ] Implement the request factory; Qwen intent uses `temperature=0`, all profiles use `max_tokens`, and no profile emits reasoning fields.
- [ ] Inject/use the factory in both `callLLM` and `parseAmbiguousRoute`; remove obsolete GPT/reasoning logic.
- [ ] Run `ChatCompletionRequestTest` and the closest Agent flow integration tests.

### Task 3: Environment contract and translation isolation

**Files:**
- Modify: `joysong-server/src/main/resources/application.yml`
- Modify: `joysong-server/src/main/resources/application-prod.yml`
- Modify: `joysong-server/src/main/resources/application-dev.example.yml`
- Modify: `joysong-server/.env.example`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/translation/service/TranslationService.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/translation/service/TranslationServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/config/ProductionProfileTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/config/AiAgentProfileStartupTest.kt`

**Interfaces:**
- Agent binding: `AI_AGENT_PROVIDER`, `AI_AGENT_API_KEY`, `AI_AGENT_BASE_URL`, `AI_AGENT_MODEL`, `AI_AGENT_INTENT_MODEL`.
- Translation binding: `TRANSLATION_PROVIDER`, `TRANSLATION_API_KEY`, `TRANSLATION_BASE_URL`, `TRANSLATION_MODEL`.

- [ ] Add failing configuration/profile and translation request tests proving the new variables and model isolation.
- [ ] Run the three focused test classes and verify expected failures against the old configuration contract.
- [ ] Update YAML/example files and flatten TranslationService configuration to `translation.api-key/base-url/model`; remove implicit OpenAI fallback and Qwen-specific credential fields.
- [ ] Run the focused tests and resolve only failures caused by this migration.

### Task 4: Verification and commits

**Files:** all files changed by Tasks 1-3 only.

- [ ] Run `git diff --check` and inspect the complete scoped diff.
- [ ] Run focused configuration, request factory, translation, and Agent integration tests once.
- [ ] If focused tests pass, run the backend test suite at most once and stop it if it exceeds ten minutes.
- [ ] Commit implementation separately from existing unrelated workspace changes.
