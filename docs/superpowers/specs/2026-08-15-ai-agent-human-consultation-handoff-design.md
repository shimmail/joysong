# AI Agent Human Consultation Handoff Design

## Goal

Let the JoySong Agent recognize that a user wants a real-person consultation, guide the user to choose an eligible institution, show that institution's currently available consultants, and open the existing one-to-one direct-message conversation after the user selects a consultant.

The handoff must remain deterministic after intent recognition: it must not ask the answer model to invent institutions, consultants, user IDs, or navigation instructions.

## Approved Product Decisions

- Add a dedicated `HUMAN_CONSULTATION` intent and `SELECT_INSTITUTION` next action.
- Use both deterministic local routing and the existing intent model. Clear phrases are resolved locally; only ambiguous phrases need the intent model.
- After a consultant is selected, open the existing user-pair direct-message conversation.
- Candidate institutions must be verified and must currently have at least one eligible consultant.
- Candidate priority is: institution or city explicitly named by the user, then the user's saved profile city, then nationwide results ordered by rating.
- Return at most four institutions in a stable order.
- Do not call the answer model for the human-consultation handoff response.
- Do not add a database migration.

## Current Capabilities to Reuse

The current Agent pipeline already provides:

- local intent assessment and optional model parsing;
- structured `intent`, `queryTarget`, `nextAction`, `catalogItems`, and `catalogReport` values;
- one turn lifecycle shared by synchronous REST and SSE;
- metadata persistence, history reconstruction, and idempotent replay;
- institution catalog cards carrying `institutionId` and `canChatWithHuman`;
- a Flutter callback slot for human consultation that is not currently wired through;
- `GET /api/discover/institutions/{institutionId}/consultants`, which returns approved institution consultants as messaging user IDs and names;
- the existing consultant picker, DM creation call, and DM thread navigation.

The feature therefore extends the existing route and turn model. It does not introduce a parallel agent workflow.

## Scope

### In scope

- bilingual human-consultation intent recognition;
- deterministic selection of consultable institutions;
- a fixed localized handoff response;
- persistence and replay of the new intent, action, and institution cards;
- an actionable institution card in current and historical Agent messages;
- reuse of the current institution consultant picker and DM navigation;
- focused backend and Flutter tests;
- Agent development documentation and sequence-diagram updates.

### Out of scope

- changing the existing user-pair uniqueness of DM conversations;
- storing institution attribution on a DM conversation;
- a public consultant profile page, avatar, expertise, availability, workload, SLA, or automatic assignment;
- an atomic institution-scoped DM creation endpoint;
- LLM tools, function calling, or an agent tool loop;
- new database tables or migrations;
- admin application changes or external deep links;
- unrelated Agent, catalog, identity, or messaging refactors.

## Architecture

The existing request path remains authoritative:

1. `ChatService` receives a normal Agent message.
2. `AgentIntentRouter` assesses the current message using local bilingual signals, polarity, target evidence, and safety precedence.
3. When the local result is ambiguous, the existing intent model returns the validated route JSON.
4. If the final intent is `HUMAN_CONSULTATION`, `ChatService` runs the deterministic handoff branch instead of the answer-generation branch.
5. The handoff branch loads at most four eligible institutions, produces a fixed localized assistant message, and completes the turn through the existing lifecycle service.
6. The existing response and metadata projections deliver and persist the new intent, action, and institution cards.
7. Flutter renders the institution action. Selecting it opens the existing consultant picker, which loads current consultants from the public institution consultant endpoint.
8. Selecting a consultant passes the returned consultant user ID to the existing authenticated DM flow.

No new transport envelope is needed. Synchronous REST and SSE continue to expose the same response shape.

## Intent Contract

### New values

- `AgentIntent.HUMAN_CONSULTATION`
- `AgentNextAction.SELECT_INSTITUTION`

For an actionable result, the final turn contract is:

```json
{
  "intent": "HUMAN_CONSULTATION",
  "queryTarget": "INSTITUTION",
  "nextAction": "SELECT_INSTITUTION",
  "catalogItems": [
    {
      "type": "INSTITUTION",
      "id": "institution-id",
      "institutionId": "institution-id",
      "name": "Institution name",
      "canChatWithHuman": true
    }
  ]
}
```

If no eligible institution exists nationwide, the turn keeps `intent=HUMAN_CONSULTATION` and `queryTarget=INSTITUTION`, returns an empty catalog list and a localized empty-state explanation, and sets `nextAction=NONE` because there is nothing the client can select.

### Recognition rules

Local signals cover clear Chinese and English requests such as asking for a real person, human consultant, specialist, manual service, or transfer to a consultant. They use the router's existing polarity annotation so phrases such as "不需要真人咨询" or "do not connect me to a person" do not trigger the intent.

The model parser's allowed `intent` and `intents` values are extended with `HUMAN_CONSULTATION`. Parsed values remain strictly validated before they can influence routing.

`HUMAN_CONSULTATION` fixes the target to `INSTITUTION`; the model cannot supply a messaging user ID or select a consultant.

### Priority and context rules

- `SAFETY_SCREENING` remains the highest-priority intent. A turn containing safety risk and a request for a person follows the safety workflow and does not show commercial institution cards in that turn.
- Clear current-message intent and negation remain authoritative over history and model output.
- A prior human-consultation turn does not make unrelated follow-up text inherit the intent. Context can only complete an institution or city reference when the current message itself contains a consultation or positive unresolved-reference signal; a negated institution reference suppresses detail-context promotion.
- Detail context may supply the current institution only when the current request has no explicit institution or city and does not negate that reference. It cannot bypass verification or consultant-availability filtering.

## Consultable Institution Selection

### Eligibility

An institution is selectable only when:

- the institution is verified and not deleted; and
- at least one consultant satisfies the same current eligibility predicate used by `InstitutionConsultantService.listApprovedConsultants`: approved consultant membership, no revocation, non-deleted user and institution, and a non-blank nickname.

Only the consultant-eligibility institution IDs must be obtained by one set-based query. Institution, profile, and detail-context reads remain separate; the selector must not call the consultant-list query once per institution.

The candidate query and the consultant-list endpoint must share the same eligibility definition so an institution is not advertised using a weaker predicate than the picker uses.

### Ranking and fallback

Candidate tiers are accumulated without duplicates until four results are available:

1. an eligible institution explicitly named in the current request;
2. eligible institutions in a city explicitly named in the current request;
3. the eligible detail-context institution when the request has no explicit institution or city and does not negate that reference;
4. eligible institutions in the saved Agent profile city when the request has no explicit city;
5. remaining eligible institutions nationwide.

Within a tier, order by rating descending and institution ID ascending for deterministic ties. An explicit exact-name match is always first in its tier.

If a requested institution is unavailable, the response states that it cannot currently provide the handoff and presents eligible alternatives using the same city/profile/nationwide fallback. A profile lookup failure is treated as a missing profile city and falls back nationwide.

The raw request phrase such as "我想找真人咨询" must not be used as a catalog keyword. The human-consultation branch owns candidate selection explicitly; this prevents the existing discover search from returning an accidental empty result.

## Deterministic Turn Behavior

When eligible institutions exist, the backend completes the turn with a localized message equivalent to:

- Chinese: `我可以为你转接真人咨询。请选择希望咨询的机构，随后可查看该机构当前可联系的咨询师。`
- English: `I can help connect you with a real consultant. Choose an institution to see its currently available consultants.`

The answer model is not called. The intent model is still allowed only when local intent assessment is ambiguous.

For SSE, the deterministic branch uses the existing completed-turn path. The observable sequence is `started` followed by `completed`, with no model-generated `delta` events. A repeated idempotency key replays the same persisted turn and institution-card snapshot without querying the answer model.

## Persistence and Compatibility

The existing message metadata continues to persist the final intent, target, action, and catalog items. The Agent context allowlists are extended so `HUMAN_CONSULTATION` and `SELECT_INSTITUTION` survive summary validation and reconstruction.

No database column or migration is required because these values are stored in existing string and JSON fields. The REST and SSE DTO structure is unchanged; older clients ignore the new enum strings and continue to display the assistant text.

Historical institution cards remain snapshots. When the user taps one later, Flutter reloads the current consultant list, so revoked or deleted consultant relationships are not restored from Agent history.

## Flutter Interaction

The Agent page wires its existing human-consultation callback through both catalog presentation paths:

- ordinary structured catalog items;
- structured catalog report cards.

The navigation adapter resolves an institution ID only from a safe resource mapping:

- `INSTITUTION`: use its explicit `institutionId`, falling back to the institution item `id`;
- `INSTITUTION_PROJECT`: use its explicit `institutionId`;
- `DOCTOR`, `PROJECT`, unknown types, or blank IDs: no human-consultation action.

This preserves the current test and security invariant that a doctor catalog-record ID is not assumed to be a messaging user ID.

Both `AgentChatPage` construction sites in `AppShell` pass the callback. The callback delegates to `_openInstitutionConsultants`, which already prevents duplicate sheets, loads consultants through `BookingRepository.getConsultants`, and delegates the selected `BookingConsultant.id` to `_openDirectMessage`.

No global route or new state-management layer is introduced.

## Error Handling

- Explicit local consultation phrases continue to work if the intent model is unavailable.
- Ambiguous phrases that cannot be classified do not fabricate a handoff; they follow the existing safe fallback behavior.
- A nationwide empty candidate result returns a successful localized empty state with no action.
- Candidate database/query failure follows the existing stable HTTP or SSE error contract and does not persist a false successful turn.
- Consultant endpoint loading failure remains in the picker with the existing retry action.
- A consultant list that becomes empty after the Agent turn displays the existing localized empty state.
- Dismissing the picker performs no navigation.
- DM creation failure and self-message prevention continue to use the existing messaging error handling.
- Duplicate taps remain guarded by `_institutionConsultantPickerOpen`.

## Security and Privacy

- The Agent response contains institution resource IDs only. Consultant messaging user IDs are loaded from the established institution consultant endpoint after an institution is selected.
- Institution, institution-project, project, and doctor resource IDs are never guessed or reused as DM targets.
- The feature does not expose model credentials, prompts, raw health information, or new trace data.
- Existing authentication remains unchanged: Agent and DM operations require authentication, while the current discover consultant-list contract remains as configured.
- The accepted first release reuses generic user-pair DM creation. It does not atomically revalidate institution membership during DM creation and does not preserve institution attribution on the conversation.

## Testing Strategy

Implementation follows test-driven development and the repository's narrow-test-first rules.

### Backend focused tests

- `AgentIntentRouterTest`
  - direct Chinese and English consultation phrases;
  - negative phrases;
  - safety precedence;
  - current-message precedence over history;
  - fixed institution target and action.
- intent parser/workflow tests
  - valid model parsing of the new intent;
  - invalid or conflicting parsed values rejected;
  - ambiguous consultation recognized through the intent model;
  - explicit local consultation works without the intent model.
- consultable institution selection tests
  - verified and non-deleted filtering;
  - approved/non-revoked consultant eligibility;
  - explicit institution and city ranking;
  - profile-city fallback, nationwide fill, stable top-four ordering;
  - no N+1 consultant lookup behavior.
- `AgentWorkflowCoreTest`
  - fixed localized content and no answer-model call;
  - non-empty and nationwide-empty next-action behavior;
  - metadata, summary, reconstruction, and idempotent replay.
- HTTP/SSE integration coverage
  - synchronous response contract;
  - `started` then `completed` with no generated delta;
  - historical message restoration.

No migration test is required because the schema is unchanged. Any MySQL integration test must still use the worktree-isolated database required by the repository rules and print the resolved host and database name before use.

### Flutter focused tests

- institution items display the human-consultation action only when the callback and safe institution ID are present;
- institution-project items map through `institutionId`;
- doctor, project, unknown, and blank-ID items never expose a DM target;
- ordinary item lists and report cards both invoke the correct callback;
- current and restored message cards remain actionable;
- both Agent entry points in `AppShell` wire the handoff;
- the existing picker tests continue to cover loading, long lists, empty results, retry, dismissal, duplicate protection, selection, and DM arguments.

After focused tests pass, run at most one full backend test suite and one `flutter analyze`. Stop a full suite that exceeds ten minutes and report its progress and slowest observed tests. Do not repeat a command that already passed.

## Documentation and Diagram Updates

- Update `docs/AI_AGENT_DEVELOPMENT.md` to replace the old blanket prohibition on Agent card human consultation with the safe two-step institution-to-consultant contract.
- Update `design/AI_AGENT_SEQUENCE.puml` with the new intent, deterministic handoff, institution choice, consultant lookup, and DM navigation.
- Regenerate `design/AI_AGENT_SEQUENCE.png` from the PlantUML source and visually verify it.
- Update testing documentation only where the new focused commands or contract need to be recorded.

The institution relationship diagram changes only if implementation changes membership semantics, which this design does not require.

## Success Criteria

The feature is complete when:

- clear Chinese and English real-person consultation requests produce `HUMAN_CONSULTATION` without calling the answer model;
- ambiguous requests can be classified by the intent model;
- safety and negation rules remain correct;
- the response contains at most four verified institutions with currently eligible consultants, using the approved fallback order;
- users can select an institution, see its current consultants, select one, and enter the existing DM thread;
- replayed and historical Agent messages preserve the institution cards without persisting consultant user IDs;
- focused backend and Flutter tests pass, and required documentation and the Agent sequence diagram are current.
