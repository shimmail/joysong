# AI Agent ComparisonRequest Workflow Design

**Date:** 2026-08-14
**Scope:** Kotlin backend and Flutter agent chat
**Branch:** `codex/comparison-request-workflow`

## 1. Goal

Turn a recognized comparison request into durable workflow state instead of treating comparison as a catalog search followed by an unconstrained model answer.

The implementation must:

- represent confirmed comparison operands, target type, dimensions, constraints, and completeness explicitly;
- persist that state in the assistant message `metadataJson`;
- restore the same state during idempotent replay and chat-history loading;
- reuse the latest comparison state only in a later turn whose final primary intent is still `COMPARISON`;
- bind `ComparisonRequest` and `catalogReport` to the assistant message that produced them;
- render incomplete-state guidance or the existing comparison report under that message in Flutter;
- add no model call, tool loop, database column, or migration.

## 2. Non-goals

This phase does not implement:

- an editable comparison builder;
- add/remove/reorder object controls;
- object-level raw text, resolution state, or confidence;
- comparison scoring, winners, rankings, or medical suitability conclusions;
- cross-intent mutation of comparison state;
- a new workflow table or migration;
- model-generated completeness;
- inferred health, contraindication, medication, pregnancy, allergy, pain, or recovery data;
- comparison across mixed entity types.

## 3. Chosen approach

Use a deterministic `ComparisonRequestBuilder` and the existing assistant-message JSON metadata.

Alternative approaches were rejected:

1. Extending the intent model to return operands, dimensions, and constraints would add latency, strict-JSON compatibility work, and hallucination risk.
2. A dedicated workflow table and state machine would provide stronger editing semantics but is disproportionate to this phase.
3. Defining a DTO without consuming it would create dead metadata and would not prevent comparison-object drift.

The builder must perform only bounded in-memory normalization over the route, the already-loaded history, the current detail context, and one existing catalog-search result. It must not call an LLM or initiate a second catalog search.

## 4. Domain model

Create the comparison model in a focused backend file rather than growing `AgentIntentRouter.kt`.

```kotlin
data class ComparisonOperand(
    val entityType: AgentQueryTarget,
    val entityId: String,
    val displayName: String
)

enum class ComparisonMissingField {
    OPERANDS,
    TARGET_TYPE
}

data class ComparisonRequest(
    val operands: List<ComparisonOperand> = emptyList(),
    val targetType: AgentQueryTarget? = null,
    val dimensions: List<String> = emptyList(),
    val constraints: Map<String, String> = emptyMap(),
    val missingFields: Set<ComparisonMissingField> = emptySet()
) {
    val isComplete: Boolean
        get() = missingFields.isEmpty()
}
```

Rules:

- Every stored operand is a confirmed platform entity. `entityType`, `entityId`, and `displayName` are non-null and non-blank.
- At least two unique operands of one target type are required.
- A request is complete when it has a target type and at least two valid operands.
- Dimensions are never a completeness blocker. When the user does not specify dimensions, target-specific defaults are applied.
- Constraints are optional.
- `isComplete` is derived and is not serialized as a second source of truth.
- `missingFields` is recalculated by trusted backend code after every merge and after metadata recovery.

Capacity limits:

- at most 4 operands;
- at most 5 dimensions after normalization;
- at most 8 constraint entries;
- IDs at most 100 characters;
- display names at most 120 characters;
- dimension keys and constraint keys at most 40 characters;
- constraint values at most 120 characters.

Normalization trims whitespace, removes blank values, preserves first-seen order, deduplicates stable identifiers, and rejects operands whose type differs from `targetType`.

## 5. Dimensions and table content

Internal dimension keys are stable, locale-independent identifiers. Report labels remain localized presentation values.

`ComparisonRequest.dimensions` stores high-level comparison groups rather than one entry per rendered row. The default groups are `PRICE`, `CREDENTIALS`, and `RATING`, restricted by target type. A projection expands each group into the target-specific structured rows below. Identity and context rows such as city, category, specialties, tags, and practice institutions may accompany those groups without consuming additional request-dimension slots. Consequently, the five-dimension request limit does not limit the number of rows rendered in a table.

Default groups by target:

- institution: `CREDENTIALS`, `RATING`;
- doctor: `CREDENTIALS`, `RATING`;
- project: `PRICE`, `RATING`;
- institution project: `PRICE`, `CREDENTIALS`, `RATING`.

### 5.1 Institution

No price dimension is shown because an institution has different prices for different projects.

Default rows:

- city;
- platform verification;
- rating;
- review count;
- doctor count;
- structured specialties.

### 5.2 Doctor

No price dimension is shown because doctor pricing depends on the institution and the concrete project.

Default rows:

- title;
- doctor platform verification;
- structured credentials;
- structured specialties;
- rating;
- review count;
- practice institutions;
- practice-institution verification summary.

A doctor remains one operand and one table column even when the doctor practices at multiple institutions. All active practice-institution relationships are considered. Institutions explicitly relevant to the current query are ordered first. The cell shows at most three institutions directly and then `另有 N 家 / N more`; the doctor detail page remains the source for the complete list. Institution verification is displayed as `verified count / related institution count` and must not be presented as the doctor's personal credential.

If the user wants to compare one doctor's concrete offerings or prices at different institutions, the target must be `INSTITUTION_PROJECT`, not `DOCTOR`.

### 5.3 Project

Default rows:

- category;
- platform reference price;
- rating;
- structured tags.

Project credentials are not shown because credentials belong to a doctor or institution.

### 5.4 Institution project

Default rows:

- institution city;
- project category;
- institution price;
- platform project reference price;
- rating;
- review count;
- sales count;
- institution verification;
- structured tags.

Marketing slogans and free-text detail summaries are excluded from comparison dimensions.

### 5.5 Missing data

Missing structured values do not make a request incomplete. Flutter renders `暂无平台数据 / Not available`. The backend and LLM must not infer a value from descriptions, slogans, biographies, or other free text.

## 6. Constraint policy

The first-phase constraint allowlist is:

- `city`;
- `budgetMin`;
- `budgetMax`;
- `downtimeDays`.

Current-turn values override the same keys inherited from the previous comparison request. Unknown keys are discarded.

Health and safety content is never copied into comparison constraints. In particular, pregnancy, nursing, allergy, medication, disease, active skin conditions, recent procedures, and psychological-distress content remain in the safety workflow and must not be persisted in `comparisonRequest`.

## 7. Backend data flow

For a turn whose final primary intent is `COMPARISON`:

1. Route the current request using the existing local/context/model pipeline.
2. Read the most recent valid `ComparisonRequest` from already-loaded assistant history.
3. Execute the existing catalog search exactly once.
4. Resolve explicit current operands from:
   - exact entity IDs or names present in the current request;
   - the current detail-page entity;
   - confirmed operands inherited from the previous incomplete request.
5. Do not turn ranked or popular search results into operands unless they were explicitly identified by the user or detail context.
6. Merge dimensions and allowlisted constraints.
7. Normalize the request and recalculate `missingFields`.
8. When complete, filter generation evidence and the visible report to the operand IDs.
9. When incomplete, do not expose unrelated catalog candidates as a comparison. Return a deterministic bilingual clarification and skip the answer-model call.
10. Persist the assistant message, report, and comparison request in the existing completion transaction.

Multi-turn merge rules:

- two or more explicit current operands replace inherited operands;
- no explicit current operands inherit the previous operands;
- one current operand may complete a previous request only when the previous request was incomplete and both have the same target type;
- one current operand never implicitly adds a third operand to a previously complete request;
- current dimensions are unioned with inherited dimensions in stable order;
- current constraints override inherited constraints;
- non-comparison turns neither read nor mutate comparison state.

For idempotent replay, the saved request is restored as-is and then defensively normalized. Replay must never re-run operand resolution or rebuild the request from current catalog data.

## 8. Latency and timeout budget

The feature must not increase remote call count.

| Scenario | Intent model | Catalog search | Answer model |
|---|---:|---:|---:|
| Explicit complete comparison | 0 | 1 | 1 |
| Ambiguous complete comparison | 1 | 1 | 1 |
| Incomplete comparison | 0 or 1 | 1 | 0 |

The builder performs bounded in-memory operations only. It consumes loaded history and the existing catalog result, performs no per-operand repository loop, and adds no transaction. Metadata is written with the assistant message in the existing transaction.

## 9. Metadata and API contract

`agent_messages.metadata_json` remains the durable source. No migration is required.

Assistant metadata adds:

```json
{
  "comparisonRequest": {
    "operands": [],
    "targetType": null,
    "dimensions": ["CREDENTIALS", "RATING"],
    "constraints": {},
    "missingFields": ["OPERANDS", "TARGET_TYPE"]
  },
  "catalogReport": null
}
```

Compatibility rules:

- both fields are nullable and optional;
- old metadata without either field remains valid;
- malformed `comparisonRequest` and malformed `catalogReport` are decoded independently and each falls back to `null`;
- failure to decode one optional field must not break message history or idempotent replay;
- no comparison state or constraints are written to logs.

`ChatMessageResponse` gains nullable `comparisonRequest` and `catalogReport` fields. Existing top-level `ChatTurnResponse.catalogReport` is retained for compatibility. Synchronous completion, SSE completion, idempotent replay, and history retrieval all project the message-level fields from the same metadata.

## 10. Flutter behavior

`ChatMessage` gains nullable `comparisonRequest` and `catalogReport` fields. `ChatTurn` remains compatible with the existing top-level report. During the transition, stream completion binds message-level values first and falls back to top-level turn values when the message does not contain them.

The chat page renders, under each assistant message:

1. `AgentComparisonStatusCard` when `comparisonRequest` exists and is incomplete;
2. the existing `AgentCatalogReportCard` when the request is complete and a report exists;
3. the existing lightweight catalog link list when no comparison request exists.

One message must never display both a report card and a duplicate catalog link list.

The incomplete status card is read-only and bilingual:

- `OPERANDS`: `请选择至少两个对比对象 / Select at least two items to compare`;
- `TARGET_TYPE`: `请明确要比较机构、医生、项目还是机构项目 / Specify whether to compare clinics, doctors, treatments, or clinic treatments`.

It may list already confirmed operand display names. It has no add, remove, reorder, dimension picker, constraint editor, or quick-action button. The user continues through ordinary chat.

The comparison table keeps objects as columns and dimensions as rows, supports horizontal scrolling, shows at most four operands, and renders missing cells as `暂无平台数据 / Not available`.

## 11. Error handling

- An incomplete request produces a successful assistant clarification, not an HTTP error.
- Zero or one resolved operand sets `OPERANDS`.
- A missing target sets `TARGET_TYPE`.
- Mixed target types are discarded from the request and produce the relevant missing state.
- Missing catalog data produces an incomplete state or missing cells; it never causes fabricated values.
- Legacy or damaged metadata falls back to no comparison state without breaking the message.
- Provider timeouts retain the existing error contract. This feature introduces no additional provider timeout.

## 12. Testing strategy

Development follows test-driven development.

### Backend unit tests

- complete request construction;
- missing operands and missing target;
- target-specific default dimensions;
- explicit operand matching without ranked-candidate promotion;
- dimension inheritance and stable deduplication;
- constraint allowlist and current-turn override;
- previous incomplete request completion;
- no implicit third operand on a complete request;
- normalization and capacity limits;
- multi-institution doctor projection and verification summary;
- missing structured values remain missing.

### Backend workflow and persistence tests

- complete comparison filters evidence to exact operands;
- incomplete comparison skips the answer model and returns deterministic clarification;
- non-comparison turns do not create comparison state;
- later comparison turns restore the latest request;
- the feature adds no second catalog search or additional provider call;
- completion writes all comparison fields into assistant metadata;
- idempotent replay restores the same request and report;
- old and malformed metadata degrade independently;
- synchronous and SSE responses bind request/report to the assistant message;
- history retrieval restores message-level request/report.

No database-migration test is required because the schema does not change. The existing MySQL metadata round-trip test may be extended, but tests must not connect to a shared development database.

### Flutter tests

- message and turn JSON parsing;
- SSE top-level fallback into the assistant message;
- history restoration;
- incomplete comparison status card;
- complete horizontal comparison report;
- target-specific rows;
- multiple doctor institutions and truncation summary;
- missing-data copy;
- report card and link-card mutual exclusion;
- bilingual labels;
- unknown or absent optional fields remain backward compatible.

After targeted tests pass, run `flutter analyze` and at most one broader relevant backend/Flutter suite in accordance with project test rules.

## 13. Documentation

Update `design/AI_AGENT_SEQUENCE.puml` with:

- request construction;
- completeness branch;
- deterministic clarification path;
- exact-operand evidence filtering;
- message metadata persistence and replay;
- message-bound Flutter state/report rendering.

## 14. Acceptance criteria

The feature is accepted when:

1. A comparison turn persists a normalized request in its assistant message metadata.
2. A complete request contains at least two confirmed same-type platform operands and a target type.
3. An incomplete request does not call the answer model or show unrelated candidate cards.
4. A complete request constrains evidence and report items to its operands.
5. Replay and history loading recover the same message-bound request and report.
6. Flutter renders an incomplete status card or a complete report under the correct assistant message.
7. Institution and doctor comparison tables contain no price dimension.
8. A doctor with multiple practice institutions appears once and shows the bounded institution list plus verification summary.
9. Missing structured data is displayed explicitly and is never inferred.
10. The remote-call count does not exceed the pre-feature workflow.
11. Existing clients remain compatible with optional response fields.
12. No database migration, new model call, or workflow loop is introduced.
