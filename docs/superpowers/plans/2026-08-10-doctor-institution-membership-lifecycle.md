# Doctor Institution Membership Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an auditable doctor institution JOIN/LEAVE workflow with doctor submission and withdrawal, legal-representative approval or rejection, atomic leave cleanup, and a visible Flutter entry.

**Architecture:** Add `doctor_institution_change_requests` as the workflow ledger while retaining `doctor_institutions` as relationship state. A focused backend service owns validation, locking, approval side effects, and actor-scoped listing. Flutter exposes one institution-relationships destination that renders doctor and legal-representative views from the management context.

**Tech Stack:** Kotlin 1.9, Spring Boot, JdbcTemplate, Spring Data JPA, MySQL 8, JUnit 5, MockK, Flutter/Dart, existing API client and widget-test patterns.

## Global Constraints

- Reviews support only `APPROVED` and `REJECTED`; rejection requires a reason.
- Institution legal representatives cannot invite doctors or consultants.
- Approved LEAVE removes the institution's doctor project bindings and prevents new orders while preserving historical orders and request audit records.
- Joining an institution never automatically joins an institution project.
- Use `joysong-flutter`; do not implement this flow in legacy `joysong-app`.
- All migrations and database-backed tests use a worktree-isolated database whose name starts with `myapp_worktree_`.
- Run the smallest related tests first and no more than one backend full suite after related tests pass.

---

### Task 1: Request Ledger Migration

**Files:**
- Create: `joysong-server/src/main/resources/db/migration/V13__add_doctor_institution_change_requests.sql`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/DoctorInstitutionChangeMigrationTest.kt`

**Interfaces:**
- Produces table `doctor_institution_change_requests` with action `JOIN|LEAVE` and status `PENDING|APPROVED|REJECTED|WITHDRAWN`.
- Produces unique generated key `pending_key` for one pending request per doctor and institution.
- Normalizes historical membership `CHANGES_REQUESTED` to `REJECTED` while retaining notes. V13 keeps legacy table status constraints compatible until Task 4 removes the legacy doctor path and adds final V14 constraints.

- [ ] **Step 1: Write a failing migration contract test**

Add a test that reads `V13__add_doctor_institution_change_requests.sql` and asserts the migration defines the new table, action/status checks, pending unique key, historical `JOIN` copy, and `CHANGES_REQUESTED` normalization. Name the test `migration creates request ledger and preserves historical membership decisions`.

- [ ] **Step 2: Run the migration test and verify RED**

Run:

```powershell
$env:GRADLE_USER_HOME='C:\Users\shimeng\.gradle'; .\gradlew.bat test --tests com.joysong.server.identity.service.DoctorInstitutionChangeMigrationTest
```

Expected: FAIL because `V13__add_doctor_institution_change_requests.sql` does not exist.

- [ ] **Step 3: Implement the migration**

Create the table with foreign keys to doctors, institutions, submitter, and reviewer. Use generated column:

```sql
pending_key VARCHAR(150) GENERATED ALWAYS AS (
    CASE WHEN status = 'PENDING' THEN CONCAT(doctor_id, ':', institution_id) ELSE NULL END
) STORED
```

Copy each existing `doctor_institutions` row as a `JOIN` request, mapping `CHANGES_REQUESTED` to `REJECTED`. Supply a deterministic historical fallback reason when a rejected legacy row has an empty review note. Keep `APPROVED` and `REVOKED` relationship rows; delete non-active relationship rows after copying. Normalize existing consultant membership `CHANGES_REQUESTED` to `REJECTED`, but defer restrictive legacy-table status checks to Task 4 so rolling deployment cannot make old application code fail.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Task 1 command again. Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add joysong-server/src/main/resources/db/migration/V13__add_doctor_institution_change_requests.sql joysong-server/src/test/kotlin/com/joysong/server/identity/service/DoctorInstitutionChangeMigrationTest.kt
git commit -m "feat: add doctor institution change request ledger"
```

### Task 2: Doctor Request API and State Machine

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/DoctorInstitutionChangeRequestService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/controller/InstitutionMembershipRequestController.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/DoctorInstitutionChangeRequestServiceTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/identity/controller/InstitutionMembershipRequestControllerTest.kt`

**Interfaces:**
- Produces enums `DoctorInstitutionAction { JOIN, LEAVE }` and `DoctorInstitutionRequestStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }`.
- Produces `submit(actor, institutionId, action, requestNote)`, `withdraw(actor, id)`, `list(actor)`, and `review(actor, id, decision, reviewNote)`.
- Reuses `GET/POST /api/management/institution-membership-requests` and the existing
  `POST /{requestType}/{id}/review` route. The submit payload gains optional
  `action`, with DOCTOR defaulting to JOIN for old clients. Only the missing
  `POST /{requestType}/{id}/withdraw` route is added.

- [ ] **Step 1: Write failing service tests**

Cover certified-doctor JOIN, active-relationship LEAVE, invalid state, duplicate pending request, actor-scoped list, own-request withdrawal, forbidden withdrawal, managed-institution review, forbidden cross-institution review, and required rejection reason. Assert only `APPROVED` and `REJECTED` parse as review decisions.

- [ ] **Step 2: Run service tests and verify RED**

```powershell
$env:GRADLE_USER_HOME='C:\Users\shimeng\.gradle'; .\gradlew.bat test --tests com.joysong.server.identity.service.DoctorInstitutionChangeRequestServiceTest
```

Expected: compilation failure because the service does not exist.

- [ ] **Step 3: Implement the state machine**

Use JdbcTemplate row mapping and transactional methods. Convert duplicate-key insertion races into `IllegalStateException("该机构已有待处理的关系申请")`. Lock request rows with `FOR UPDATE`, then use conditional updates with `WHERE status = 'PENDING'`.

- [ ] **Step 4: Run service tests and verify GREEN**

Run the Task 2 service command. Expected: PASS.

- [ ] **Step 5: Write failing controller dispatch tests**

Assert the existing controller dispatches doctor requests to the new state
machine and consultant requests to the legacy service. Cover legacy doctor JOIN
compatibility, unified listing, withdrawal, and legal review. Authentication is
already covered by the management API catch-all; service authorization remains
authoritative.

- [ ] **Step 6: Extend the existing controller and payload**

Request payloads:

```kotlin
data class SubmitInstitutionMembershipRequest(
    val requestType: String,
    val institutionId: String,
    val action: String? = null,
    val requestNote: String = ""
)
```

Reuse `ReviewInstitutionMembershipRequest` and `MembershipRequestDecision`.

- [ ] **Step 7: Run controller tests and verify GREEN**

Run both Task 2 test classes. Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/identity joysong-server/src/test/kotlin/com/joysong/server/identity
git commit -m "feat: add doctor institution relationship requests"
```

### Task 3: Transactional JOIN and LEAVE Effects

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/DoctorInstitutionChangeRequestService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/service/AdminIdentityService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/DoctorInstitutionChangeRequestServiceTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/admin/service/AdminIdentityServiceTest.kt`

**Interfaces:**
- JOIN approval creates or restores one `APPROVED` `doctor_institutions` row without creating doctor-project bindings.
- LEAVE approval revokes the relationship, removes institution doctor-project bindings, disables split configuration, withdraws pending project changes, closes pending split proposals, and synchronizes the primary institution.
- Platform-admin direct revoke calls the same cleanup component so both revocation paths have identical downstream behavior.

- [ ] **Step 1: Write failing JOIN approval tests**

Assert approval revalidates certification and institution state, restores a revoked relationship, selects primary only when none exists, and does not insert `doctor_projects`.

- [ ] **Step 2: Run JOIN tests and verify RED**

Run the service test methods containing `join approval`. Expected: FAIL because approval side effects are absent.

- [ ] **Step 3: Implement JOIN approval minimally**

Lock the institution and relationship rows, upsert relationship state, synchronize denormalized doctor institution fields, and update the request in one transaction.

- [ ] **Step 4: Run JOIN tests and verify GREEN**

Run the same methods. Expected: PASS.

- [ ] **Step 5: Write failing LEAVE cleanup tests**

Seed mocks or database rows for two institution relationships, doctor-project bindings in both institutions, split configs, pending project requests, pending split proposals, and historical orders. Assert only the leaving institution's live bindings/configuration are removed, orders are unchanged, and primary institution switches deterministically.

- [ ] **Step 6: Run LEAVE tests and verify RED**

Run service methods containing `leave approval`. Expected: FAIL because cleanup is absent.

- [ ] **Step 7: Implement LEAVE cleanup and shared admin revoke path**

Execute all relationship and downstream updates transactionally. Do not delete or update order rows. Ensure repository reads used for new order creation no longer find the removed doctor-project binding.

- [ ] **Step 8: Run Task 3 tests and verify GREEN**

Run the two Task 3 test classes. Expected: PASS.

- [ ] **Step 9: Commit**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/identity/service/DoctorInstitutionChangeRequestService.kt joysong-server/src/main/kotlin/com/joysong/server/admin/service/AdminIdentityService.kt joysong-server/src/test/kotlin/com/joysong/server/identity/service/DoctorInstitutionChangeRequestServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/admin/service/AdminIdentityServiceTest.kt
git commit -m "feat: apply doctor institution join and leave decisions"
```

### Task 4: Simplify Existing Institution Membership Reviews

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestService.kt`
- Create: `joysong-server/src/main/resources/db/migration/V14__finalize_institution_membership_statuses.sql`
- Modify: `joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestServiceTest.kt`
- Test: `joysong-flutter/test/features/identity/identity_models_controller_test.dart`

**Interfaces:**
- Existing consultant join reviews accept only `APPROVED` and `REJECTED`.
- Doctor submissions are rejected by the legacy membership service and use the Task 2 API exclusively.
- V14 restricts `doctor_institutions` to relationship states and removes `CHANGES_REQUESTED` from consultant membership storage only after code compatibility exists.
- Doctor project review dialogs remain unchanged and continue to support `CHANGES_REQUESTED`.

- [ ] **Step 1: Write failing backend and widget assertions**

Assert membership decision parsing rejects `CHANGES_REQUESTED`, doctor submission through the legacy service is rejected, rejection requires a note, and the membership review dialog has exactly approve/reject actions while project review dialogs still contain request-changes. Add migration assertions for final doctor and consultant status checks.

- [ ] **Step 2: Run focused tests and verify RED**

Run the membership service test. If Flutter is available, run the focused widget test. Expected: failure because membership review still exposes request-changes.

- [ ] **Step 3: Split membership and project review dialogs**

Create a membership-specific dialog returning only `APPROVED` or `REJECTED`; retain the existing three-way project dialog. Remove `CHANGES_REQUESTED` from `MembershipRequestDecision.parse`, reject `DOCTOR` submissions through the old service, and add V14 constraints after normalizing any rows created during rolling deployment.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the same tests. Expected: PASS, or record Flutter SDK unavailability with the backend test passing.

- [ ] **Step 5: Commit**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestService.kt joysong-server/src/main/resources/db/migration/V14__finalize_institution_membership_statuses.sql joysong-server/src/test/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestServiceTest.kt joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart joysong-flutter/test/features/identity/identity_models_controller_test.dart
git commit -m "refactor: simplify institution membership reviews"
```

### Task 5: Flutter Institution Relationships Destination

**Files:**
- Modify: `joysong-flutter/lib/features/profile/presentation/profile_page.dart`
- Modify: `joysong-flutter/lib/features/identity/domain/identity_models.dart`
- Modify: `joysong-flutter/lib/features/identity/domain/identity_repository.dart`
- Modify: `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`
- Create: `joysong-flutter/lib/features/identity/presentation/institution_relationships_page.dart`
- Modify: `joysong-flutter/test/features/identity/identity_models_controller_test.dart`
- Create: `joysong-flutter/test/features/identity/institution_relationships_page_test.dart`

**Interfaces:**
- Produces `DoctorInstitutionChangeRequest`, `DoctorInstitutionChangeRequestDraft`, and repository list/submit/withdraw/review methods matching Task 2 routes.
- Produces `InstitutionRelationshipsPage(repository: IdentityRepository)` that loads management context and renders doctor, legal, or dual-role views.
- Adds visible `机构关系` / `Institution relationships` navigation on the My page.

- [ ] **Step 1: Write failing model and repository tests**

Assert JSON parsing, `JOIN/LEAVE` serialization, API paths, review body, and withdrawal path.

- [ ] **Step 2: Run focused Dart tests and verify RED**

```powershell
flutter test test/features/identity/identity_models_controller_test.dart
```

Expected: FAIL because models and repository methods do not exist. If Flutter is unavailable, record that limitation and continue with static signature checks.

- [ ] **Step 3: Implement models and repository API**

Use existing `_objectList`, required-string, nullable-number, and ApiClient patterns. Do not add a second HTTP abstraction.

- [ ] **Step 4: Write failing page tests**

Cover visible My-page navigation, doctor current/available/history sections, JOIN submit, LEAVE confirmation text, withdraw, legal pending/history views, dual-role segmented control, approve/reject-only actions, and required reject reason.

- [ ] **Step 5: Run page tests and verify RED**

Run `flutter test test/features/identity/institution_relationships_page_test.dart`. Expected: FAIL because the page is absent.

- [ ] **Step 6: Implement the page and navigation**

Use tabs or a segmented control only for dual-role users. Use icon buttons with tooltips for withdrawal and review commands. Keep request forms unframed and avoid nested cards.

- [ ] **Step 7: Run Flutter verification and verify GREEN**

Run focused tests and `flutter analyze`. If the SDK is unavailable, run `git diff --check`, inspect every interface against Task 2, and report the unexecuted commands.

- [ ] **Step 8: Commit**

```powershell
git add joysong-flutter/lib/features/profile/presentation/profile_page.dart joysong-flutter/lib/features/identity joysong-flutter/test/features/identity
git commit -m "feat: add institution relationship workspace"
```

### Task 6: Isolated Migration and End-to-End Verification

**Files:**
- Modify only tests or implementation files required by failures from this task.

**Interfaces:**
- Verifies all previous tasks together without introducing new product behavior.

- [ ] **Step 1: Create the isolated database runtime**

Use worktree id `worktree_institution_membership_lifecycle`, Compose project `myapp-worktree-institution-membership-lifecycle`, and database `myapp_worktree_institution_membership_lifecycle`. Print host and database name before migrations.

- [ ] **Step 2: Verify fresh migration**

Apply `B1`, then `V2` through `V14` to a newly created empty isolated database. Assert the request table, checks, indexes, and foreign keys exist.

- [ ] **Step 3: Verify historical migration fixture**

Create a second database named `myapp_worktree_institution_membership_lifecycle_history`, migrate only through `V12`, seed pending/approved/rejected/changes-requested/revoked/soft-deleted doctor relationships and consultant memberships including rejected rows with empty notes, apply `V13` and `V14`, and assert request history, fallback reasons, and active relationships match the design.

- [ ] **Step 4: Run related backend tests**

Run the Task 1-4 backend classes plus order creation tests. Fix only failures caused by this feature and rerun only failed classes.

- [ ] **Step 5: Run one backend full suite**

```powershell
$env:GRADLE_USER_HOME='C:\Users\shimeng\.gradle'; .\gradlew.bat test
```

Stop after ten minutes and report progress if it exceeds the project limit.

- [ ] **Step 6: Run Flutter verification**

Run focused Flutter tests and `flutter analyze` when the SDK is available. Otherwise record `flutter` and `dart` command lookup results and retain static contract evidence.

- [ ] **Step 7: Run final cleanliness checks**

Run `git diff --check`, inspect `git status --short`, and confirm `.runtime/` remains ignored.

- [ ] **Step 8: Commit verification fixes if any**

If verification required code changes, inspect `git status --short`, stage each reported implementation or test path explicitly, and commit with message `test: verify doctor institution membership lifecycle`. If verification produced no tracked changes, do not create an empty commit.
