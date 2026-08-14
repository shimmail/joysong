# Professional Institution Relationship Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver one complete, auditable workflow in which verified doctors and consultants can apply to join or leave an institution, withdraw their own pending application, and an authorized institution legal representative can approve or reject it with correct relationship effects.

**Architecture:** Keep request history separate from live relationships. Doctors continue to use `doctor_institution_change_requests` plus `doctor_institutions`; consultants gain `consultant_institution_change_requests` while `institution_memberships` remains the authoritative live consultant relationship projection. A single management controller exposes normalized owned, reviewable, candidate, submit, withdraw, and review contracts; the Flutter app consumes that contract through one explicitly scoped relationship page shared by Professional Management and Profile.

**Tech Stack:** Kotlin 2.x, Spring Boot, Spring MVC/Security/JDBC transactions, Flyway/MySQL 8, JUnit 5/MockK/Testcontainers; Flutter/Dart, Material 3, `flutter_test`.

## Global Constraints

- Work only in `D:\code\kotlin\joysong\.worktrees\institution-membership-application-review` on branch `codex/institution-membership-application-review`.
- Treat `docs/superpowers/specs/2026-08-14-professional-institution-relationship-workflow-design.md` as the accepted product contract. Do not broaden scope to institution invitations or unrelated identity refactors.
- Follow red-green-refactor for every behavior. Each test must fail for the intended missing behavior before production code changes.
- Preserve rolling compatibility for `/api/management/consultant-memberships` and the old root list endpoint, but route every new mutation through the new request ledger and the same validation/state machine.
- Never write `PENDING` or `REJECTED` into `doctor_institutions`; live doctor relationships are only `APPROVED` or `REVOKED`.
- Never create new pending/rejected consultant rows in `institution_memberships`; it is the relationship projection after V27.
- Use worktree id `worktree_institution_membership_application_review`. Any test database must be named `myapp_worktree_institution_membership_application_review` (or a suffix of it), and the test must print the resolved host and database before migration. Do not connect to a shared development database.
- If Docker Compose is introduced or invoked, use project name `myapp-institution-membership-application-review`. Prefer the existing Testcontainers dependency so no new Compose file is needed.
- Keep request note length at 1,000 characters or fewer for both professions. A rejection requires a nonblank review note.
- Rolling deployment decision: drain every old writer instance before applying V27 or starting new writers. This release does not implement delta/lazy import; document and verify the maintenance cutover sequence so no post-V27 legacy PENDING consultant membership can be created outside the ledger.
- Applicant authorization is identity-specific and self-only. Reviewer authorization is platform admin or active legal representative for the request's institution.
- Pending/rejected/withdrawn `LEAVE` requests do not change an active relationship. Only approving `LEAVE` revokes it.
- Commit after each task only when its focused tests pass. Do not stage unrelated files.

---

## Task 1: Add the consultant request ledger and prove upgrade behavior

**Files:**

- Create: `joysong-server/src/main/resources/db/migration/V27__add_consultant_institution_change_requests.sql`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/ConsultantInstitutionChangeMigrationTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt`

- [ ] **Step 1: Write a real MySQL migration test for a fresh database**

  Add `ConsultantInstitutionChangeMigrationTest`, tagged `mysql-integration`, with a MySQL 8 Testcontainer configured with database name `myapp_worktree_institution_membership_application_review`. Before Flyway runs, print:

  ```kotlin
  println("Migration database host=${mysql.host}:${mysql.getMappedPort(3306)}, database=${mysql.databaseName}")
  require(mysql.databaseName.startsWith("myapp_worktree_"))
  ```

  Migrate an empty database to version 27 and assert the consultant ledger exists with `JOIN`/`LEAVE`, `PENDING`/`APPROVED`/`REJECTED`/`WITHDRAWN`, reviewer foreign keys, timestamps, and one generated `pending_key` unique constraint. Update `BaselineMigrationIntegrationTest` to expect B26 as `SQL_BASELINE` followed by V27, rather than the obsolete “B26 only” history. This catches a baseline that looks correct in SQL text but cannot actually migrate on MySQL.

- [ ] **Step 2: Write an upgrade/backfill behavior test**

  In the same container, migrate a second isolated schema named `myapp_worktree_institution_membership_application_review_history` to 26, seed consultant membership fixtures for `PENDING`, `REJECTED`, `APPROVED`, and `REVOKED`, then migrate to 27. Assert:

  - PENDING becomes `JOIN/PENDING` in the new ledger.
  - REJECTED becomes `JOIN/REJECTED`, retaining review note/reviewer/timestamps.
  - APPROVED becomes `JOIN/APPROVED`, retaining confirmation metadata.
  - Membership ids are reused for these backfilled request ids.
  - APPROVED membership remains APPROVED and active; no existing binding is changed.
  - REVOKED relationship history remains REVOKED and does not create a fictitious leave application.
  - Existing PENDING/REJECTED rows remain available for rolling readers, but no destructive rewrite occurs.
  - A historical REJECTED row with a blank note receives one stable migration note so the new table's rejected-note check is satisfied.

- [ ] **Step 3: Run the focused migration test and confirm RED**

  From `joysong-server`:

  ```powershell
  .\gradlew.bat mysqlIntegrationTest --tests com.joysong.server.identity.service.ConsultantInstitutionChangeMigrationTest --tests com.joysong.server.migration.BaselineMigrationIntegrationTest
  ```

  Expected: failure because V27/table/baseline declarations do not exist.

- [ ] **Step 4: Implement the V27 schema and deterministic backfill**

  Create `consultant_institution_change_requests` with:

  ```sql
  id, consultant_id, institution_id, action, status,
  request_note, review_note, submitted_by, reviewed_by,
  submitted_at, reviewed_at, created_at, updated_at,
  pending_key GENERATED ALWAYS AS (
    CASE WHEN status = 'PENDING'
      THEN CONCAT(consultant_id, ':', institution_id)
      ELSE NULL END
  ) STORED
  ```

  Add checks, indexes, foreign keys, and a unique key on `pending_key`. Use set-based deterministic backfill statements; do not delete or repurpose `institution_memberships` rows. Do not modify published B26/V13/V14 migrations. V27 must use ordinary `CREATE TABLE` so unexpected pre-existing schema drift fails loudly instead of being hidden by `IF NOT EXISTS`.

- [ ] **Step 5: Run the focused migration test and confirm GREEN**

  Run the exact Step 3 command once. Inspect both schema assertions and seeded data assertions.

- [ ] **Step 6: Commit the migration slice**

  ```powershell
  git add joysong-server/src/main/resources/db/migration/V27__add_consultant_institution_change_requests.sql joysong-server/src/test/kotlin/com/joysong/server/identity/service/ConsultantInstitutionChangeMigrationTest.kt joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt
  git commit -m "feat: add consultant institution request ledger"
  ```

## Task 2: Implement the consultant JOIN/LEAVE state machine and relationship projection

**Files:**

- Create: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/ConsultantInstitutionRelationshipService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/ConsultantInstitutionChangeRequestService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestModels.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/ConsultantInstitutionChangeRequestServiceTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/ConsultantInstitutionRelationshipServiceTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/ConsultantInstitutionChangeMySqlIntegrationTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/JdbcInstitutionMembershipRequestStoreTest.kt`

- [ ] **Step 1: Specify the consultant request domain and submit rules**

  Add failing service tests for:

  - only a user with active `CONSULTANT` role can submit for self;
  - JOIN requires a verified, nondeleted institution and no active consultant relationship;
  - LEAVE requires an active APPROVED, non-revoked consultant membership;
  - one pending request per consultant/institution blocks both JOIN and LEAVE, including duplicate-key races;
  - request note is trimmed and limited to 1,000 characters;
  - a rejected or withdrawn historical request is never overwritten; resubmission creates a new ledger row.

  Define strict enums `ConsultantInstitutionAction` and `ConsultantInstitutionRequestStatus` and a view with applicant/institution display names plus complete audit fields.

- [ ] **Step 2: Specify withdraw and review rules**

  Add failing tests proving:

  - only the request owner can withdraw their PENDING request;
  - legal representatives only review requests for `managedInstitutionIds`; platform admin may review globally;
  - only PENDING can be reviewed;
  - REJECTED requires a reason;
  - review locks and revalidates the institution is still verified/nondeleted and the current relationship still matches the action (JOIN still has no active relationship; LEAVE still has one);
  - concurrent conditional updates produce a stable conflict;
  - withdrawal and rejection never mutate the relationship projection.

- [ ] **Step 3: Specify relationship effects and transaction ordering**

  In `ConsultantInstitutionRelationshipServiceTest`, prove:

  - approving JOIN creates or restores exactly one `institution_memberships` row with `member_role='CONSULTANT'`, `status='APPROVED'`, `revoked_at=NULL`, reviewer metadata, and no duplicate binding;
  - approving LEAVE changes only the target membership to REVOKED and sets `revoked_at`;
  - approving LEAVE clears only sessions with `active_role='CONSULTANT'` and that target `active_institution_id`;
  - other institution memberships, the consultant identity/role, wallet, orders, and history remain untouched;
  - relationship projection is applied before the request is conditionally closed, so an exception rolls the transaction back and leaves the request PENDING.

- [ ] **Step 4: Run service tests and confirm RED**

  ```powershell
  .\gradlew.bat test --tests com.joysong.server.identity.service.ConsultantInstitutionChangeRequestServiceTest --tests com.joysong.server.identity.service.ConsultantInstitutionRelationshipServiceTest
  ```

- [ ] **Step 5: Implement the request store, service, and relationship service**

  Keep relationship SQL in `ConsultantInstitutionRelationshipService` and request ledger SQL/state transitions in `ConsultantInstitutionChangeRequestService`. Put only shared enums, projections, and typed not-found/conflict exceptions in `InstitutionMembershipRequestModels.kt`. The request store surface is:

  ```kotlin
  interface ConsultantInstitutionChangeRequestStore {
      fun create(...): ConsultantInstitutionChangeRequestView
      fun listOwned(consultantId: String): List<ConsultantInstitutionChangeRequestView>
      fun listReviewable(managedInstitutionIds: Set<String>, includeAll: Boolean): List<ConsultantInstitutionChangeRequestView>
      fun lock(id: String): ConsultantInstitutionChangeRequestView?
      fun changeStatus(...): Boolean
      fun hasPending(consultantId: String, institutionId: String): Boolean
      fun hasActiveRelationship(consultantId: String, institutionId: String): Boolean
  }
  ```

  Implement with `JdbcTemplate`, `SELECT ... FOR UPDATE`, generated UUID ids, and conditional status updates. Put review and relationship effects in one outer `@Transactional` request-service method. Translate only the named pending unique-key violation to the domain conflict; do not mask foreign-key or unrelated duplicate errors.

- [ ] **Step 6: Run focused consultant tests and confirm GREEN**

  Run the exact Step 4 command once. If one case fails, rerun only that class/method while fixing it.

- [ ] **Step 7: Prove transactional atomicity and concurrency on isolated MySQL**

  Add a `mysql-integration` Spring/JDBC test using database `myapp_worktree_institution_membership_application_review_service` and print/validate its host and name before migration. With real transaction management, prove:

  - approving LEAVE atomically changes membership, target consultant session, and request status;
  - an injected/constraint failure while conditionally closing the request rolls all three effects back and leaves the request PENDING;
  - two concurrent submits produce exactly one PENDING row and one typed conflict;
  - two concurrent reviews produce exactly one approved relationship effect and one typed conflict;
  - PENDING/REJECTED/WITHDRAWN LEAVE leaves the membership APPROVED, and leaving one institution preserves another membership, wallet, and historical order.

  Run:

  ```powershell
  .\gradlew.bat mysqlIntegrationTest --tests com.joysong.server.identity.service.ConsultantInstitutionChangeMySqlIntegrationTest
  ```

- [ ] **Step 8: Replace obsolete store tests with ledger behavior tests**

  Update `JdbcInstitutionMembershipRequestStoreTest` so it no longer expects consultant pending/resubmission writes in `institution_memberships`. Retain any doctor rolling-compatibility tests that still protect production behavior. Run:

  ```powershell
  .\gradlew.bat test --tests com.joysong.server.identity.service.JdbcInstitutionMembershipRequestStoreTest
  ```

- [ ] **Step 9: Commit the service slice**

  ```powershell
  git add joysong-server/src/main/kotlin/com/joysong/server/identity/service/ConsultantInstitutionRelationshipService.kt joysong-server/src/main/kotlin/com/joysong/server/identity/service/ConsultantInstitutionChangeRequestService.kt joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestModels.kt joysong-server/src/test/kotlin/com/joysong/server/identity/service/ConsultantInstitutionChangeRequestServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/identity/service/ConsultantInstitutionRelationshipServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/identity/service/ConsultantInstitutionChangeMySqlIntegrationTest.kt joysong-server/src/test/kotlin/com/joysong/server/identity/service/JdbcInstitutionMembershipRequestStoreTest.kt
  git commit -m "feat: implement consultant institution relationship workflow"
  ```

## Task 3: Expose explicit owned, reviewable, candidate, withdraw, and review APIs

**Files:**

- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/controller/InstitutionMembershipRequestController.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestQueryService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionMembershipCandidateService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/identity/controller/InstitutionMembershipCandidateController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/controller/ConsultantMembershipController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/common/GlobalExceptionHandler.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/identity/controller/InstitutionMembershipRequestControllerTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestQueryServiceTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/InstitutionMembershipCandidateServiceTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/identity/controller/InstitutionMembershipCandidateControllerTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/identity/controller/InstitutionMembershipRequestHttpTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/identity/controller/ConsultantMembershipHttpTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestServiceTest.kt`

- [ ] **Step 1: Write controller dispatch and scope tests**

  Replace mixed-list assumptions with failing tests for:

  - `GET /owned` returns only the authenticated doctor's or consultant's own full history;
  - `GET /reviewable` returns only requests for the legal representative's managed institutions, while admin sees all;
  - both lists normalize doctor and consultant rows to the same response fields;
  - POST requires explicit `requestType`, `action`, `institutionId`, and dispatches DOCTOR/CONSULTANT without inferring active role;
  - typed withdraw dispatches for both professions and rejects cross-owner requests;
  - typed review dispatches for both professions and cannot fall back to a weaker legacy path.

  Normalize responses to:

  ```text
  id, requestType, applicantId, applicantName,
  institutionId, institutionName, action, status,
  relationshipStatus, requestNote, reviewNote,
  submittedBy, reviewedBy, submittedAt, reviewedAt,
  createdAt, updatedAt
  ```

  The old root GET is a separate compatibility representation and must also include legacy `userId` as an alias of `applicantId`; add a decoding/JSON assertion using the existing legacy Flutter model. `/owned` and `/reviewable` use the normalized contract.

- [ ] **Step 2: Write candidate filtering and pagination tests**

  Add service/controller tests for:

  ```http
  GET /api/management/institution-membership-candidates
      ?requestType=DOCTOR|CONSULTANT
      &action=JOIN|LEAVE
      &query=<text>&offset=0&limit=20
  ```

  JOIN returns only verified, nondeleted institutions without an active relationship or pending request for that explicit identity. LEAVE returns only current active relationships without a pending request. Assert deterministic name/id ordering, query filtering, `offset`, bounded `limit`, and no raw UUID used as a display name.

- [ ] **Step 3: Write real HTTP status tests and confirm RED**

  In `InstitutionMembershipRequestHttpTest`, use `MockMvc`, `SecurityConfig`, and `GlobalExceptionHandler` to prove:

  - malformed/unknown request fields and invalid action are HTTP 400;
  - unauthenticated is HTTP 401;
  - inactive identity/cross-institution review is HTTP 403;
  - missing request/institution is HTTP 404;
  - duplicate pending or lost concurrent update is HTTP 409;
  - the body `code` matches the real HTTP status.

  Run:

  ```powershell
  .\gradlew.bat test --tests com.joysong.server.identity.controller.InstitutionMembershipRequestControllerTest --tests com.joysong.server.identity.controller.InstitutionMembershipRequestHttpTest
  ```

- [ ] **Step 4: Implement controller contracts and candidate service**

  Add `/owned` and `/reviewable` through a small query aggregator, and expose candidates from a dedicated controller. Parse enums strictly and cap `limit` (for example 1..100). Keep the old root GET as a compatibility union only; new Flutter code must not consume it. Compute `relationshipStatus` in each SQL projection to avoid N+1 queries. Use the same doctor/consultant services for every mutation.

- [ ] **Step 5: Turn the consultant endpoint into a compatibility adapter**

  Keep `/api/management/consultant-memberships` decodable for rolling clients, but map GET from consultant owned ledger/relationship projection and map POST to a consultant JOIN request in the new service. It must enforce the same verified-institution, note-length, duplicate-pending, and authorization rules. Remove old code paths that create PENDING/REJECTED membership projections.

- [ ] **Step 6: Enable real statuses for the generic workflow**

  Extend `GlobalExceptionHandler.usesRealHttpErrorStatus()` for both `/api/management/institution-membership-requests` and `/api/management/institution-membership-candidates`. Map domain not-found/conflict exceptions consistently rather than returning HTTP 200 with `code=500`.

- [ ] **Step 7: Remove weaker mixed-store mutation fallbacks**

  Delete or reduce the obsolete mixed `JdbcInstitutionMembershipRequestStore` paths that create doctor PENDING relationships or resubmit consultant membership rows in place. A missing doctor ledger request must return the typed 404; it must not fall back to reviewing `doctor_institutions`. Keep only the minimum read adapter required for a rolling root GET, covered by an explicit compatibility test.

- [ ] **Step 8: Run focused controller, HTTP, compatibility, candidate, and service tests**

  ```powershell
  .\gradlew.bat test --tests com.joysong.server.identity.controller.InstitutionMembershipRequestControllerTest --tests com.joysong.server.identity.controller.InstitutionMembershipRequestHttpTest --tests com.joysong.server.identity.controller.InstitutionMembershipCandidateControllerTest --tests com.joysong.server.identity.controller.ConsultantMembershipHttpTest --tests com.joysong.server.identity.service.InstitutionMembershipRequestQueryServiceTest --tests com.joysong.server.identity.service.InstitutionMembershipCandidateServiceTest --tests com.joysong.server.identity.service.InstitutionMembershipRequestServiceTest
  ```

- [ ] **Step 9: Commit the API slice**

  ```powershell
  git add joysong-server/src/main/kotlin/com/joysong/server/identity/controller/InstitutionMembershipRequestController.kt joysong-server/src/main/kotlin/com/joysong/server/identity/controller/InstitutionMembershipCandidateController.kt joysong-server/src/main/kotlin/com/joysong/server/identity/controller/ConsultantMembershipController.kt joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestQueryService.kt joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionMembershipCandidateService.kt joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestService.kt joysong-server/src/main/kotlin/com/joysong/server/common/GlobalExceptionHandler.kt joysong-server/src/test/kotlin/com/joysong/server/identity/controller/InstitutionMembershipRequestControllerTest.kt joysong-server/src/test/kotlin/com/joysong/server/identity/controller/InstitutionMembershipRequestHttpTest.kt joysong-server/src/test/kotlin/com/joysong/server/identity/controller/InstitutionMembershipCandidateControllerTest.kt joysong-server/src/test/kotlin/com/joysong/server/identity/controller/ConsultantMembershipHttpTest.kt joysong-server/src/test/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestQueryServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/identity/service/InstitutionMembershipCandidateServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestServiceTest.kt
  git commit -m "feat: expose institution relationship request APIs"
  ```

## Task 4: Harden doctor compatibility paths and remove illegal PENDING relationship writes

**Files:**

- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/DoctorInstitutionChangeRequestService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/doctor/entity/DoctorInstitutionEntity.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/doctor/repository/DoctorInstitutionRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/doctor/service/DoctorInstitutionService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminDoctorController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/common/initializer/DoctorDataInitializer.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/AdminIdentityService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminIdentityController.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/DoctorInstitutionChangeRequestServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/AdminDoctorControllerTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/AdminIdentityControllerTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/AdminIdentityServiceTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/doctor/service/DoctorInstitutionServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/DoctorInstitutionChangeMigrationTest.kt`

- [ ] **Step 1: Add failing validation tests for doctor JOIN**

  Extend `DoctorInstitutionChangeRequestServiceTest` so JOIN rejects an unverified institution, not only missing/deleted ones, and rejects notes over 1,000 characters before insert. Preserve self-only verified-doctor authorization and stable pending-conflict behavior.

- [ ] **Step 2: Add failing tests for all legacy doctor mutation paths**

  Prove:

  - entity defaults never construct a PENDING live relationship;
  - repository reactivation writes APPROVED, never PENDING;
  - `DoctorInstitutionService.sync` cannot directly create, restore, or soft-delete an institution relationship as an application substitute;
  - legacy admin approve/reject routes dispatch into the doctor request ledger/relationship service or are rejected explicitly; they cannot expect PENDING rows in `doctor_institutions`;
  - legacy revoke uses the same leave cleanup/session rules as the main relationship service;
  - admin direct consultant binding remains an explicit APPROVED platform override and never fabricates applicant history;
  - force-revoking a consultant relationship returns 409 while a matching PENDING request exists and instructs the administrator to review that request; no administrator path fabricates applicant-owned WITHDRAWN status.
  - the existing V13 test asserts the migration's real immutable `information_schema` guard instead of the stale opposite assertion; fix the test only, never published V13.

- [ ] **Step 3: Run the focused doctor tests and confirm RED**

  ```powershell
  .\gradlew.bat test --tests com.joysong.server.identity.service.DoctorInstitutionChangeRequestServiceTest --tests com.joysong.server.doctor.service.DoctorInstitutionServiceTest --tests com.joysong.server.identity.service.AdminIdentityServiceTest --tests com.joysong.server.admin.controller.AdminDoctorControllerTest --tests com.joysong.server.admin.controller.AdminIdentityControllerTest --tests com.joysong.server.identity.service.DoctorInstitutionChangeMigrationTest
  ```

- [ ] **Step 4: Implement the smallest compatibility repair**

  Require `institutions.is_verified = 1` for doctor candidates/submission. Enforce the shared note limit. Change or remove PENDING defaults/reactivation SQL. Delegate legacy relationship effects to `DoctorInstitutionRelationshipService`; do not duplicate its cleanup SQL. If a legacy endpoint cannot preserve request audit semantics, fail it explicitly and keep the new management endpoint as the only application path. Keep seed relationships explicit and APPROVED; account for `DoctorDataInitializer` running before active professional-role initialization rather than calling a service whose authorization preconditions are not yet present.

- [ ] **Step 5: Run focused doctor tests and confirm GREEN**

  Run the exact Step 3 command once, narrowing to a failed class if necessary.

- [ ] **Step 6: Commit the doctor hardening slice**

  Stage only the files actually required and commit:

  ```powershell
  git commit -m "fix: enforce doctor institution request ledger"
  ```

## Task 5: Replace the Flutter split models/repository calls with the normalized contract

**Files:**

- Modify: `joysong-flutter/lib/features/identity/domain/identity_models.dart`
- Modify: `joysong-flutter/lib/features/identity/domain/identity_repository.dart`
- Modify: `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`
- Modify: `joysong-flutter/test/features/identity/identity_models_controller_test.dart`
- Create: `joysong-flutter/test/features/identity/institution_relationship_repository_contract_test.dart`
- Modify fake repositories only where Dart interface compilation requires it.

- [ ] **Step 1: Write model parsing/serialization tests**

  Add failing tests for one `InstitutionMembershipRequest` model that parses both DOCTOR and CONSULTANT normalized payloads, all four statuses, both actions, relationship status, display names, notes, and audit timestamps. Prefer typed Dart enums for request type/action/status/decision. Add a request draft whose JSON always contains explicit `requestType`, `action`, `institutionId`, and trimmed `requestNote`.

  Add `InstitutionMembershipCandidatePage`/candidate models with `items`, `offset`, `limit`, and `hasMore` (or the exact server page envelope selected in Task 3).

  Extend `ManagementContext` to parse `consultantInstitutionIds`, which the backend already supplies. This is the live consultant affiliation source; do not infer an active relationship from an approved historical request.

- [ ] **Step 2: Write repository endpoint contract tests**

  Prove the data repository calls exactly:

  - `GET /management/institution-membership-requests/owned`
  - `GET /management/institution-membership-requests/reviewable`
  - `GET /management/institution-membership-candidates` with explicit type/action/query/offset/limit
  - POST root with explicit type/action
  - POST `/{type}/{id}/withdraw`
  - POST `/{type}/{id}/review`

  Assert a consultant LEAVE and consultant withdraw serialize identically to doctor except for `requestType`.

- [ ] **Step 3: Run focused Flutter contract tests and confirm RED**

  From `joysong-flutter`:

  ```powershell
  flutter test test/features/identity/identity_models_controller_test.dart test/features/identity/institution_relationship_repository_contract_test.dart
  ```

- [ ] **Step 4: Implement the normalized Flutter domain/data surface**

  Replace page-facing use of `DoctorInstitutionChangeRequest` plus the old reduced membership model with the unified `InstitutionMembershipRequest` and these repository methods:

  ```dart
  Future<List<InstitutionMembershipRequest>> listOwnedInstitutionMembershipRequests();
  Future<List<InstitutionMembershipRequest>> listReviewableInstitutionMembershipRequests();
  Future<InstitutionMembershipCandidatePage> listInstitutionMembershipCandidates({...});
  Future<InstitutionMembershipRequest> submitInstitutionMembershipRequest(InstitutionMembershipRequestDraft draft);
  Future<InstitutionMembershipRequest> withdrawInstitutionMembershipRequest({required InstitutionMembershipRequestType requestType, required String id});
  Future<InstitutionMembershipRequest> reviewInstitutionMembershipRequest({...});
  ```

  Keep old public methods only where another screen still needs rolling compatibility; make them thin delegates, not a second protocol implementation.

- [ ] **Step 5: Run focused contract tests and confirm GREEN**

  Run the exact Step 3 command once.

- [ ] **Step 6: Commit the Flutter contract slice**

  ```powershell
  git add joysong-flutter/lib/features/identity/domain/identity_models.dart joysong-flutter/lib/features/identity/domain/identity_repository.dart joysong-flutter/lib/features/identity/data/identity_repository_impl.dart joysong-flutter/test/features/identity/identity_models_controller_test.dart joysong-flutter/test/features/identity/institution_relationship_repository_contract_test.dart
  git commit -m "feat: add unified institution relationship client contract"
  ```

## Task 6: Build one explicitly scoped Flutter relationship page for doctor, consultant, and legal review

**Files:**

- Modify: `joysong-flutter/lib/features/identity/presentation/institution_relationships_page.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/institution_picker_page.dart`
- Modify: `joysong-flutter/test/features/identity/institution_relationships_page_test.dart`
- Modify: `joysong-flutter/test/features/discover/institution_picker_page_test.dart`
- Delete or reduce to a redirect: the institution-membership section in `joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart`
- Modify: `joysong-flutter/test/features/identity/professional_institution_membership_test.dart`

- [ ] **Step 1: Write applicant workflow widget tests**

  Parameterize the page with an explicit scope enum (`doctor`, `consultant`, `legalRepresentative`) and add failing tests proving:

  - one `/owned` fixture contains both DOCTOR and CONSULTANT rows for the same user; `scope: doctor` renders only DOCTOR and `scope: consultant` renders only CONSULTANT;
  - doctors use `doctorInstitutionIds` and consultants use `consultantInstitutionIds` for current relationships, with names merged from candidates/request projections so UUIDs are never shown;
  - JOIN candidate search excludes current/pending institutions and paginates;
  - LEAVE selection contains only current active institutions;
  - both submit explicit role/action and can withdraw only their own PENDING request;
  - approved JOIN and approved LEAVE use human-readable status/action labels; no UUID is presented as the institution or applicant name;
  - rejected/withdrawn history remains visible;
  - when `canApplyToInstitutions` is false, history remains visible but picker/form/actions are absent.
  - after submit or withdraw, management context, owned history, and relevant candidates are loaded again; sequential fake responses prove the visible row/actions change rather than merely asserting the mutation call.

- [ ] **Step 2: Write legal representative review widget tests**

  Add failing tests proving:

  - only `/reviewable` data is displayed, and a mixed managed/unmanaged fixture is defensively filtered to `managedInstitutionIds`;
  - pending rows are separated from review history;
  - DOCTOR/CONSULTANT and JOIN/LEAVE badges are visible;
  - approve sends the request's type/id;
  - reject cannot submit without a reason and then sends the entered reason;
  - after review, sequential responses move the request immediately from pending to history and reload management context;
  - an empty queue and API error have localized states.

- [ ] **Step 3: Write explicit-scope rendering protection**

  Add a dual-identity page test that mounts each explicit scope against the same context/owned response and proves submit/candidates use the supplied type. The page must never infer request identity from current session role. Route construction is tested and implemented in Task 7.

- [ ] **Step 4: Run the focused widget tests and confirm RED**

  ```powershell
  flutter test test/features/identity/institution_relationships_page_test.dart test/features/identity/professional_institution_membership_test.dart test/features/discover/institution_picker_page_test.dart
  ```

- [ ] **Step 5: Implement the unified page**

  Rework `InstitutionRelationshipsPage` around the explicit scope and normalized repository. Use the management candidate endpoint instead of `/discover/institutions` and remove the now-unneeded `DiscoverRepository` dependency. Refactor `InstitutionPickerPage` to accept a candidate-page loader callback; retain its existing search debounce, pagination, retry, and `InstitutionPickerSelection`, but remove the unused `role` argument. The caller closure binds the explicit request type and action.

  Keep request history collapsed by default with an accessible expand/collapse control. Preserve expansion state through refresh with a single state source. A PENDING consultant or doctor LEAVE remains listed as a current relationship and is labeled as awaiting exit review.

  Use bilingual strings through `context.localized`. Keep action buttons disabled during mutations; on success refresh candidates/history/context, and on failure show the backend-safe localized error without losing form input.

- [ ] **Step 6: Remove the duplicate simplified implementation**

  Delete only the obsolete `InstitutionMembershipRequestsPage` implementation from `professional_request_pages.dart`, or retain a deprecated wrapper that immediately constructs the unified page if a temporary source-compatible symbol is required. Do not leave two independently mutable UI/state implementations.

- [ ] **Step 7: Run focused widget tests and confirm GREEN**

  Run the exact Step 4 command once.

- [ ] **Step 8: Commit the unified page slice**

  ```powershell
  git add joysong-flutter/lib/features/identity/presentation/institution_relationships_page.dart joysong-flutter/lib/features/discover/presentation/institution_picker_page.dart joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart joysong-flutter/test/features/identity/institution_relationships_page_test.dart joysong-flutter/test/features/identity/professional_institution_membership_test.dart joysong-flutter/test/features/discover/institution_picker_page_test.dart
  git commit -m "feat: unify professional institution relationship screens"
  ```

## Task 7: Route Professional Management and Profile to the same scoped page

**Files:**

- Modify: `joysong-flutter/lib/features/identity/presentation/identity_pages.dart`
- Modify: `joysong-flutter/lib/features/profile/presentation/profile_page.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/consultant_management_pages.dart`
- Modify: `joysong-flutter/test/features/identity/identity_models_controller_test.dart`
- Modify: `joysong-flutter/test/features/identity/institution_relationships_page_test.dart`
- Modify: `joysong-flutter/test/features/identity/professional_institution_membership_test.dart`
- Modify: `joysong-flutter/test/features/identity/consultant_management_contract_page_test.dart`

- [ ] **Step 1: Write route-level tests before changing routes**

  Add failing tests for a dual DOCTOR+CONSULTANT user and a LEGAL_REP user:

  - doctor group “机构关系” opens `InstitutionRelationshipsPage(scope: doctor)`;
  - consultant group has one institution relationship entry, not duplicate “申请加入机构” and “机构归属” entries, and opens `scope: consultant`;
  - legal representative group opens `scope: legalRepresentative`;
  - Profile shortcut resolves to the same page; one eligible identity opens directly, while multiple eligible identities show a role choice and then pass that explicit scope;
  - Profile institution relationships remain visible and open correctly when `discoverRepository == null`; this shortcut depends only on `IdentityRepository`;
  - “专业目录” remains a read-only professional catalog and does not mix current institution relationship actions/data into catalog contents.

- [ ] **Step 2: Run route tests and confirm RED**

  ```powershell
  flutter test test/features/identity/identity_models_controller_test.dart test/features/identity/institution_relationships_page_test.dart test/features/identity/professional_institution_membership_test.dart test/features/identity/consultant_management_contract_page_test.dart
  ```

- [ ] **Step 3: Implement explicit routing and remove duplicate entries**

  Update the stable management group actions/keys, retain only enabled actions, and pass the role scope at the route construction site. Point Profile at the same implementation, remove its unrelated Discover-repository gate, and show a role chooser for multi-identity users. Keep Professional Catalog routes explicitly doctor/legal-representative and read-only.

  Delete the obsolete `ConsultantMembershipPage` business implementation or reduce it to a source-compatible wrapper over the unified scoped page. Preserve `ConsultantProjectCatalogPage` and unrelated consultant project features in the same file.

- [ ] **Step 4: Run route tests and confirm GREEN**

  Run the exact Step 2 command once.

- [ ] **Step 5: Commit the route slice**

  ```powershell
  git add joysong-flutter/lib/features/identity/presentation/identity_pages.dart joysong-flutter/lib/features/profile/presentation/profile_page.dart joysong-flutter/lib/features/identity/presentation/consultant_management_pages.dart joysong-flutter/test/features/identity/identity_models_controller_test.dart joysong-flutter/test/features/identity/institution_relationships_page_test.dart joysong-flutter/test/features/identity/professional_institution_membership_test.dart joysong-flutter/test/features/identity/consultant_management_contract_page_test.dart
  git commit -m "fix: route professional roles to scoped institution relationships"
  ```

## Task 8: Document the contract, verify end to end, and clean temporary artifacts

**Files:**

- Create or modify: `docs/` API documentation nearest the existing management identity API docs
- Create or modify: `design/` PlantUML/Mermaid source and rendered image for the institution relationship state/sequence diagram
- Modify: accepted design/plan docs only if implementation deliberately differs, recording the reason.

- [ ] **Step 1: Update API and developer documentation**

  Document endpoint payloads, normalized response, candidate semantics, authorization, status codes, rolling compatibility, and the fact that `institution_memberships` is now relationship-only for new consultant writes.

- [ ] **Step 2: Update the UML source and rendered artifact**

  Show applicant → request ledger → legal review → relationship projection for both professions, including withdraw/reject no-op effects and approved LEAVE session cleanup. Keep developer-facing UML in `design/` per repository convention.

- [ ] **Step 3: Run smallest backend regression set once**

  ```powershell
  .\gradlew.bat test --tests com.joysong.server.identity.service.ConsultantInstitutionChangeRequestServiceTest --tests com.joysong.server.identity.service.ConsultantInstitutionRelationshipServiceTest --tests com.joysong.server.identity.service.DoctorInstitutionChangeRequestServiceTest --tests com.joysong.server.identity.service.InstitutionMembershipRequestQueryServiceTest --tests com.joysong.server.identity.service.InstitutionMembershipCandidateServiceTest --tests com.joysong.server.identity.controller.InstitutionMembershipRequestControllerTest --tests com.joysong.server.identity.controller.InstitutionMembershipCandidateControllerTest --tests com.joysong.server.identity.controller.InstitutionMembershipRequestHttpTest --tests com.joysong.server.identity.controller.ConsultantMembershipHttpTest
  .\gradlew.bat mysqlIntegrationTest --tests com.joysong.server.identity.service.ConsultantInstitutionChangeMigrationTest --tests com.joysong.server.identity.service.ConsultantInstitutionChangeMySqlIntegrationTest --tests com.joysong.server.migration.BaselineMigrationIntegrationTest
  ```

  Do not rerun already-passing commands. Narrow only if this aggregate exposes a new failure.

- [ ] **Step 4: Run smallest Flutter regression set once**

  ```powershell
  flutter test test/features/identity/institution_relationship_repository_contract_test.dart test/features/identity/institution_relationships_page_test.dart test/features/identity/professional_institution_membership_test.dart test/features/identity/identity_models_controller_test.dart test/features/discover/institution_picker_page_test.dart
  flutter analyze
  ```

- [ ] **Step 5: Run at most one broader suite per stack**

  If focused checks pass and the toolchains are available:

  ```powershell
  .\gradlew.bat test
  flutter test
  ```

  Stop a broad suite after 10 minutes and report progress/slowest tests. Do not retry environment-only failures repeatedly.

- [ ] **Step 6: Inspect diff and clean test-only runtime artifacts**

  Verify:

  ```powershell
  git diff --check
  git status --short
  ```

  Remove only temporary files created by this task under the worktree (for example its ignored `.runtime` Gradle directory) after resolving and validating the exact path. Do not touch caches or user files outside this worktree.

- [ ] **Step 7: Request code review and address only verified findings**

  Review security/authorization, transactional atomicity, data migration, rolling compatibility, bilingual UX, explicit role scope, and tests. Re-run only the tests affected by accepted fixes.

- [ ] **Step 8: Commit documentation/final fixes**

  ```powershell
  git add docs design
  git commit -m "docs: document institution relationship workflow"
  ```

## Completion Evidence

Before declaring completion, capture:

- branch/worktree status and commit list;
- the fresh isolated MySQL host/database line and successful V27 migration/backfill assertions;
- focused backend test summary;
- focused Flutter test and analyze summary, or exact evidence that Flutter is unavailable in the environment;
- one confirmation each for doctor JOIN/LEAVE/withdraw, consultant JOIN/LEAVE/withdraw, legal approve/reject, relationship projection, and session cleanup;
- any intentionally retained compatibility endpoint and its test.
