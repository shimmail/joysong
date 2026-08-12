# AI Agent Provider Decoupling Design

## Goal

Decouple the AI Agent provider protocol from model selection so the chat service can switch between an OpenAI-compatible gateway and Alibaba Cloud Qwen without inferring provider capabilities from model names. Keep Agent credentials independent from translation credentials, and keep chat, intent-classification, and translation model names distinct.

## Configuration Contract

The Agent uses only these environment variables:

```ini
AI_AGENT_PROVIDER=qwen
AI_AGENT_API_KEY=...
AI_AGENT_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_AGENT_MODEL=qwen-plus
AI_AGENT_INTENT_MODEL=qwen-turbo
```

- `AI_AGENT_PROVIDER` selects request construction and provider validation.
- `AI_AGENT_API_KEY` and `AI_AGENT_BASE_URL` are mandatory Agent credentials. The Agent never falls back to `OPENAI_*`, `QWEN_*`, or translation credentials.
- `AI_AGENT_MODEL` selects the conversational generation model.
- `AI_AGENT_INTENT_MODEL` selects the intent-classification model. When blank, it falls back only to `AI_AGENT_MODEL`.

Translation remains an independent subsystem:

```ini
TRANSLATION_PROVIDER=qwen
TRANSLATION_API_KEY=...
TRANSLATION_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
TRANSLATION_MODEL=qwen3.7-flash
```

Translation never reads `AI_AGENT_*`. Agent and translation credentials may have the same configured values operationally, but reuse is an explicit deployment choice rather than an application fallback.

Legacy `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_INTENT_MODEL`, `QWEN_API_KEY`, `QWEN_BASE_URL`, and `QWEN_MODEL` are not fallback sources for the new Agent or translation configuration. This avoids ambiguous configuration provenance.

## Architecture

### Provider identity and capabilities

Introduce an `AiAgentProvider` enum with the initially supported values:

- `QWEN`
- `OPENAI_COMPATIBLE`

Provider parsing is case-insensitive and fails at startup for unknown values. The provider determines endpoint validation and request shape. Model strings remain opaque identifiers and never determine provider behavior.

### Agent properties

`AiAgentProperties` owns:

- enabled state
- provider
- API key
- base URL
- chat model
- intent model
- proxy and timeout-related Agent settings

`resolvedIntentModel()` continues to fall back to the chat model when the intent model is blank. It does not change provider or credentials.

### Request factory

Move Chat Completions body construction from `ChatService` into a focused `AgentProviderRequestFactory`.

Its input contains provider, model, messages, maximum output tokens, and request purpose (`CHAT` or `INTENT`). Its output is the JSON-compatible request map.

Initial request profiles:

- `QWEN`: `model`, `messages`, `stream=false`, `max_tokens`; use `temperature=0` for intent classification and omit optional reasoning fields.
- `OPENAI_COMPATIBLE`: conservative compatible shape using `model`, `messages`, `stream=false`, and `max_tokens`. Do not infer official OpenAI parameters from a `gpt-*` model name.

Provider-specific features such as Qwen thinking mode or official OpenAI reasoning parameters are out of scope until explicitly required and covered by provider-specific tests.

### Chat service

`ChatService` remains the turn orchestrator. It passes the provider, appropriate model, messages, token budget, and purpose to the request factory. It no longer:

- calls `startsWith("gpt-5")`
- selects token parameter names
- owns reasoning-effort configuration
- treats a model name as a provider capability signal

Both chat generation and intent classification share the Agent provider, API key, and base URL, while using their distinct configured model names.

### Translation service

Translation continues to be separately routed and cached. Its provider, API key, base URL, and model use only `TRANSLATION_*` configuration. Existing OpenAI fallback-provider complexity should be removed unless a separately configured translation fallback is still required by a documented product requirement.

## Validation and Security

Production startup validation must report the new variable names:

- `AI_AGENT_PROVIDER`
- `AI_AGENT_API_KEY`
- `AI_AGENT_BASE_URL`
- `AI_AGENT_MODEL`

`AI_AGENT_INTENT_MODEL` is optional because it may use the chat model.

The existing base-URL policy must be replaced with provider-aware validation:

- `QWEN` accepts only HTTPS DashScope compatible-mode endpoints under `dashscope.aliyuncs.com`.
- `OPENAI_COMPATIBLE` initially accepts only the currently approved HTTPS gateway host `www.fastaitoken.com`.
- URLs containing user info, query strings, fragments, or non-HTTPS schemes are rejected.

Logs include the provider category and actual provider host but never emit API keys or unredacted model names. Provider response diagnostics remain bounded and sanitized.

## Error Handling

Unknown providers and invalid provider/base-URL combinations fail during startup, not on the first request. Runtime provider errors retain the existing safe public error surface while internal diagnostics distinguish invalid request, authentication, rate limit, model-not-found, upstream 5xx, timeout, and network failures.

## Migration

Update:

- `application.yml`
- production and development example profiles
- `.env.example`
- configuration validation messages and tests
- Agent request-construction tests
- translation configuration and tests

Deployment must set the new Agent variables before starting the updated service. No automatic fallback to legacy credential variables is provided.

Remove obsolete reasoning-effort configuration and stale `openai.model` Agent settings after all references are verified absent. Do not modify unrelated translation behavior beyond configuration naming unless its existing tests require the change.

## Testing

Use the smallest relevant tests first:

1. `AiAgentPropertiesTest`: provider parsing, intent-model fallback, and independent credential binding.
2. Provider URL policy tests: Qwen and approved compatible gateway acceptance plus unsafe URL rejection.
3. `ChatCompletionRequestTest`: provider-specific request bodies and proof that model names do not change the request profile.
4. `TranslationServiceTest`: translation sends `TRANSLATION_MODEL` and never uses an Agent model.
5. `AiAgentProfileStartupTest` and `ProductionProfileTest`: new environment-variable contract and startup validation.
6. The closest Agent integration test after focused unit tests pass.

No database migrations are involved. Run at most one full backend test suite after all focused tests pass.

## Out of Scope

- Different providers or credentials for chat and intent classification.
- Automatic credential sharing between Agent and translation.
- Runtime provider failover.
- Dynamic provider registration from a database.
- Provider-specific streaming or reasoning APIs.
