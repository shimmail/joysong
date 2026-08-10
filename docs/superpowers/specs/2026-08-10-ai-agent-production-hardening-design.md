# AI Agent Production Hardening Design

**Date:** 2026-08-10
**Status:** Approved for specification review
**Scope:** Backend forward migration and turn recovery, FastAIToken production configuration, Flutter information-assistant closure, isolated integration verification, and gradual-release controls.

## Goal

Make the existing synchronous REST AI assistant safe for a controlled production rollout without introducing autonomous tool execution, SSE, LangChain4j, a queue, or a recommendation engine. This release ships a stable information assistant, not personalized medical-aesthetic planning, diagnosis, or treatment decision support.

## Delivery Boundaries

- Keep synchronous REST as the only chat transport.
- Keep the existing eight `agent_*` tables and current service boundaries where practical.
- Do not modify the checksum of the already-applied `V10__rebuild_agent_v2.sql` migration.
- Do not recover historical Agent data removed by V10; the target database does not require recovery.
- Use FastAIToken as the production model relay, with the exact allowed host `www.fastaitoken.com` and HTTPS only.
- Do not automatically enable production traffic. Delivery includes feature flags, rollout guidance, and a rapid-disable path.
- Do not add suitability scores or claim that the system generated a personalized medical plan.

## 1. Migration Safety

V10 remains immutable because it has already been recorded in Flyway history. A small deployment preflight script inspects whether V10 is pending. If V10 is pending and any legacy Agent table contains rows, deployment stops before application startup and instructs the operator to back up and explicitly migrate the data. If the tables are absent or empty, migration may continue. This check is deployment tooling rather than a general application migration framework.

Forward schema changes use new numbered migrations only. The next migration adds the minimum fields and indexes required for turn leases and recovery. It must work both against a database that has already applied V10 and a newly created empty database. Migration verification covers both paths; no migration drops or resets an existing database.

## 2. Stale RUNNING Recovery

Each running turn receives a bounded lease represented by `lease_expires_at`. `beginTurn` locks the session and inspects its running turn. A non-expired turn returns `TURN_IN_PROGRESS`. An expired turn is atomically finalized as `FAILED` with the stable internal reason `STALE_RECOVERED`, its running guard is released, and the new turn may start.

The lease duration is configuration-backed and must exceed the configured model HTTP request budget. This synchronous release does not add heartbeats, scheduled scans, Redis locks, queues, or background recovery: the request either completes within its lease or a later request recovers it. Recovery is serialized under the existing session lock so two concurrent requests cannot both reclaim the same turn. Idempotent replay semantics remain unchanged for completed turns.

## 3. FastAIToken, Host Policy, and Proxy Isolation

Production uses `https://www.fastaitoken.com/v1`. The URL policy accepts HTTPS and the exact host `www.fastaitoken.com`; it rejects HTTP, user-info, unexpected ports unless explicitly allowed by policy, IP literals, sibling/subdomain tricks, and redirects to non-allowlisted hosts. The allowlist remains application-owned rather than request-controlled.

AI configuration is represented by one typed `AiAgentProperties` boundary containing enabled, base URL, API key, model, proxy URL, lease duration, and the application-owned exact allowed host. `ChatService` does not independently inject the same settings through scattered `@Value` parameters.

AI HTTP clients use `OPENAI_PROXY_URL`. Google clients continue to use `GOOGLE_PROXY_URL`. Neither property falls back to the other. Proxy URLs receive the same scheme and credential-safety validation appropriate to infrastructure configuration. The first release does not expose a configurable multi-host allowlist.

`AI_AGENT_ENABLED` is the module-level production switch. One availability guard at the chat-generation boundary performs the runtime check. When false, generation returns a stable `AGENT_DISABLED` response without creating a Turn, loading model context, or invoking the provider. Existing profiles and chat history remain readable. When true under the production profile, startup fails fast unless all of the following are valid:

- non-blank `OPENAI_API_KEY`;
- HTTPS `OPENAI_BASE_URL` passing the FastAIToken host policy;
- non-blank `AI_AGENT_MODEL`;
- valid optional `OPENAI_PROXY_URL`.

The model identifier remains explicit configuration because relay model availability can change independently of the application. Secrets and complete URLs are not logged.

## 4. Flutter REST Product Closure

The Flutter client removes the configurable SSE execution branch and always uses synchronous REST. The unused SSE transport is removed if it has no remaining consumers. The server's disabled streaming route remains a stable compatibility response, but no production client build can drift into it.

Before rendering the profile/safety editor, the client loads the current server profile. Updates preserve fields the page does not edit, including excluded projects, instead of replacing them with empty defaults. Loading, save, assessment, and plan errors are visible and manually retryable.

The chat page renders a deliberately small subset of the server's structured result through the existing Agent catalog-card components. Supported actions are project detail, doctor detail, institution detail, and the existing human-consultation entry. Unsupported or missing targets remain non-clickable and display an explanatory label. Complex comparison editing, automatic plan creation, and cross-page workflow state are deferred. No automatic retry repeats a chat POST.

## 5. Medical-Aesthetic Information-Only Scope

User-facing terminology is downgraded from personalized treatment planning to information reference. `AgentPlanService` does not present suitability scores or personalized treatment plans in this release. Every generated reference includes a concise statement that it is not diagnosis or treatment advice and that eligibility, recovery, contraindications, and risks require confirmation by a qualified institution or clinician.

Only trustworthy structured inputs provide conservative controls:

- excluded projects are hard exclusions;
- acceptable downtime and pain tolerance are displayed as user preferences but do not filter or score candidates until the catalog contains governed structured values;
- missing downtime, pain, contraindication, or risk data is shown as `需向机构确认` and is never invented by the model;
- explanations name the actual matched fields and never claim to have used data that the algorithm ignored.

Because the current catalog lacks governed recovery, pain, contraindication, and risk fields, the implementation is limited to exclusions, transparent unknowns, information comparison, risk copy, and professional-consultation navigation. It does not fabricate a scoring model from free text. Structured catalog data governance and personalized deterministic matching are a separate later phase.

## 6. Error Handling and Observability

New stable errors include `AGENT_DISABLED` and stale-turn recovery diagnostics. Provider errors remain sanitized. Operational events record the trace ID, turn ID, terminal status, recovery reason, duration, and model identifier without message content, health data, API keys, authorization headers, proxy credentials, or raw provider bodies.

Configuration exposes an immediate disable switch suitable for rollback. Rollout documentation defines readiness checks, a small initial traffic cohort, monitored error/latency indicators, and rollback conditions. Actual traffic changes remain an operator action outside this implementation.

## 7. Test Strategy

All behavior changes follow test-first development. The smallest relevant test runs first; after related tests pass, the full suite runs at most once.

The first release gate uses a compact backend unit and integration matrix:

- V10 preflight allows empty/new databases and refuses populated legacy tables;
- forward migration works from an already-V10 schema and from a fresh empty database;
- non-expired running turns conflict, expired turns recover, and concurrent recovery creates only one successor;
- completed idempotent requests replay without another provider call;
- same key with different content conflicts;
- disabled Agent creates no Turn and makes no provider call;
- production configuration rejects missing key/base URL/model, unexpected hosts, and proxy cross-use;
- FastAIToken exact HTTPS host is accepted;
- a parameterized provider-error test covers timeout, malformed response, 401, 429, and 5xx as stable sanitized errors;
- profile updates retain unedited fields;
- information-reference copy does not overstate unavailable recovery, pain, or contraindication data.

Flutter coverage includes profile load-before-edit, field preservation, REST-only sending, structured card rendering/navigation, non-clickable unknown targets, visible failures, and manual retry without duplicate automatic POSTs.

Fresh MySQL tests derive `WORKTREE_ID` from `ai-agent-production-hardening`, use database `myapp_worktree_ai_agent_production_hardening`, use Docker Compose project `myapp-worktree-ai-agent-production-hardening`, and print the resolved database host and name before migration. Tests never connect to or reset a shared development database. SQLite artifacts, if any, stay under `.runtime/`.

## 8. Rollout Acceptance

The information assistant is ready for operator-controlled gray release only when:

1. targeted backend and Flutter tests pass;
2. fresh and already-V10 isolated MySQL migration paths pass;
3. concurrency and idempotency integration cases pass;
4. production-profile configuration tests have zero accepted failures;
5. FastAIToken connectivity is verified through an explicit, secret-safe operator canary;
6. `AI_AGENT_ENABLED=false` is proven to stop new provider calls and Turn creation;
7. the rollout and rollback runbook is reviewed.

The implementation does not claim personalized planning, medical decision support, autonomous Agent execution, streaming, or production traffic activation. Governed recovery, pain, contraindication, and risk data plus deterministic matching belong to a separate post-release phase.
