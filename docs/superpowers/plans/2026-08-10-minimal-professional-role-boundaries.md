# Minimal Professional Role Boundaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce non-overlapping minimum capabilities for institution legal representatives, doctors, and consultants across backend authorization and the Flutter management experience.

**Architecture:** Keep platform identity approval in the existing admin identity service. Add user-initiated, institution-scoped request services under `/api/management`, with all review authorization resolved from `ManagementActor`; retain `/api/admin/**` member creation as platform-admin-only. Reuse existing doctor-institution and institution-membership records for join requests, and add dedicated request tables for missing platform projects and institution-project creation.

**Tech Stack:** Kotlin 1.9, Spring Boot 3.2, JdbcTemplate/JPA, Flyway/MySQL, Flutter/Dart, MockK/JUnit 5, flutter_test.

## Global Constraints

- Institution legal representatives may edit only their institution profile and review institution-scoped doctor, consultant, and institution-project requests.
- Institution legal representatives cannot view orders, invite members, edit professional profiles, create platform projects, edit doctor-submitted service content, or manage splits, customers, or finance.
- Doctors may edit only their own profile and submit institution membership, platform-project, and institution-project requests.
- Consultants may apply for platform identity, submit an institution membership request, and view approved affiliation only.
- Platform administrators review professional identity and platform-project requests; they do not perform a second approval for institution membership.
- Review decisions are `APPROVED`, `REJECTED`, or `CHANGES_REQUESTED`; rejection and change requests require a review note.
- All institution-scoped reads and decisions must be checked against `ManagementActor.managedInstitutionIds`.
- Legal representatives must never receive an invitation or generic member-creation capability.
- Tests and migrations must not connect to a shared development database.
- Existing institution-project join and new institution-project request flows must both remain available.
- `doctor_projects.price` is mandatory and is the only runtime order-price source after migration.
- Historical doctor-project prices are backfilled once from their institution project reference prices.

---

### Task 1: Enforce Role Capability Boundaries

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/ManagementAccessService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminProjectController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/InstitutionProjectController.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/ManagementAccessServiceTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt`

**Interfaces:**
- Produces: `ManagementActor` whose doctor resources contain only the actor's own doctor id.
- Produces: `ManagementContextView` flags where legal representatives have institution/profile and request-review capabilities but no doctor, order, article, or split capability.

- [ ] **Step 1: Write failing authorization tests**

Add tests proving a legal representative context has `canManageOrders == false`, `canManageDoctors == false`, `canManageSplitConfigs == false`, and that `OrderService.requireOrderForManagement` rejects an institution order for a non-admin legal representative.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew test --tests com.joysong.server.identity.service.ManagementAccessServiceTest --tests com.joysong.server.order.OrderServiceTest`

- [ ] **Step 3: Apply minimal authorization changes**

Build `manageableDoctorIds` from the actor's own doctor id only; set order and split flags from `isAdmin || doctorId != null`; remove legal-representative visibility from `OrderService.canManageOrder`; make platform-project creation admin-only and direct institution-project create/update/delete admin-only.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the same focused Gradle command and require zero failures.

### Task 2: Add User-Initiated Institution Membership Requests

**Files:**
- Create: `joysong-server/src/main/resources/db/migration/V11__add_professional_role_requests.sql`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/identity/controller/InstitutionMembershipRequestController.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestServiceTest.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt`

**Interfaces:**
- Produces: `submit(actor, institutionId, requestNote)` for active doctors and consultants only.
- Produces: `list(actor)` returning the actor's own requests plus requests for represented institutions.
- Produces: `review(actor, id, decision, reviewNote)` restricted to the target institution's legal representative or platform admin.

- [ ] **Step 1: Write failing service tests**

Cover doctor and consultant self-submission, rejection of legal-representative submission, institution-scoped listing, legal-representative approval, cross-institution denial, and mandatory review notes for `REJECTED`/`CHANGES_REQUESTED`.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew test --tests com.joysong.server.identity.service.InstitutionMembershipRequestServiceTest`

- [ ] **Step 3: Add schema and service**

Add `request_note` and `review_note` to `doctor_institutions` and `institution_memberships`. Insert or reactivate only the authenticated actor's own relation; never accept a target user id. On approval populate `confirmed_by`/`confirmed_at`; on request changes preserve applicant content and record reviewer feedback.

- [ ] **Step 4: Add `/api/management/institution-membership-requests` endpoints**

Expose `GET`, `POST`, and `POST /{id}/review`; all endpoints derive the actor from `Authentication` and never accept reviewer or applicant ids in request bodies.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run the focused service and controller tests.

### Task 3: Add Platform and Institution Project Request Workflows

**Files:**
- Modify: `joysong-server/src/main/resources/db/migration/V11__add_professional_role_requests.sql`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/project/controller/ProfessionalProjectRequestController.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestServiceTest.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt`

**Interfaces:**
- Produces: `submitPlatformProject(actor, name, category, description, notes)` for doctors.
- Produces: `submitInstitutionProject(actor, institutionId, projectId, serviceContent, priceSuggestion, notes)` for approved doctor memberships.
- Produces: institution-project reviews by target legal representative and platform-project reviews by admin only.

- [ ] **Step 1: Write failing workflow tests**

Cover doctor-only submission, admin-only platform-project approval, legal-representative institution scoping, immutable doctor content during review, all three decisions, and creation of the base/institution project only on approval.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew test --tests com.joysong.server.project.service.ProfessionalProjectRequestServiceTest`

- [ ] **Step 3: Add request tables and transactional service**

Create `platform_project_requests` and `institution_project_requests` with submitter, target, content snapshot, status, reviewer, review note, and timestamps. Approval creates the corresponding `projects` or `institution_projects` record once; review methods accept only decision and note, never replacement content.

- [ ] **Step 4: Add management endpoints**

Expose doctor submit/list endpoints and institution legal-representative review endpoints under `/api/management/project-requests`; keep platform-project review under `/api/admin/project-requests` with `ROLE_ADMIN` security.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run the focused service/controller tests and require zero failures.

### Task 4: Align Flutter With the Minimum Boundaries

**Files:**
- Modify: `joysong-flutter/lib/features/identity/domain/identity_models.dart`
- Modify: `joysong-flutter/lib/features/identity/domain/identity_repository.dart`
- Modify: `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/identity_controller.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/identity_pages.dart`
- Modify: `joysong-flutter/test/features/identity/identity_models_controller_test.dart`

**Interfaces:**
- Consumes: management membership and project-request APIs from Tasks 2 and 3.
- Produces: legal-representative request review views, doctor join/project request forms, consultant join/affiliation view, and no legal-representative or consultant order surface.

- [ ] **Step 1: Write failing widget/controller tests**

Assert legal representatives see institution profile and request review only; doctors see doctor profile, join institution, platform-project request, institution-project request, articles, and their own orders; consultants see join institution and affiliation only. Assert no role receives an invitation action and no doctor form calls direct project-creation or split APIs.

- [ ] **Step 2: Run Flutter tests and verify RED**

Run: `flutter test test/features/identity/identity_models_controller_test.dart`

- [ ] **Step 3: Replace direct publishing UI with request UI**

Remove base-project creation, direct institution-project editing, and split fields from doctor-facing pages. Route legal representatives to scoped review lists; route doctors and consultants to self-service institution request forms; render consultant approved affiliations read-only.

- [ ] **Step 4: Run Flutter tests and analyze**

Run the focused widget test, then `flutter analyze`. If the SDK is unavailable, record the exact environment failure and perform static cross-reference checks without claiming the suite passed.

### Task 5: Migration and Cross-Stack Verification

**Files:**
- Modify: `docs/superpowers/specs/2026-08-10-minimal-professional-role-boundaries-design.md` only if endpoint names need documenting.

**Interfaces:**
- Consumes all previous tasks.
- Produces verified role boundaries and request state transitions.

- [ ] **Step 1: Verify migration on a fresh isolated database**

Derive `WORKTREE_ID` from the current directory, print host and `myapp_<WORKTREE_ID>`, and run Flyway against a newly created empty database using Docker Compose project `myapp-<WORKTREE_ID>`. Never use or reset a shared database.

- [ ] **Step 2: Run one broader backend test pass**

After focused tests pass, run `./gradlew test` once; stop and report if it exceeds 10 minutes.

- [ ] **Step 3: Run admin verification**

Because admin remains platform-only, run `npm test -- --run src/pages/IdentityManagementPage.test.tsx` and `npm run build` to ensure the existing admin identity surface still compiles.

- [ ] **Step 4: Review capability matrix against the design**

Confirm each allowed action has one owner, each forbidden action is denied server-side, review endpoints are institution-scoped, and no legal-representative invitation API or UI exists.

### Task 6: Add Doctor-Specific Institution Project Prices

**Files:**
- Modify: `joysong-server/src/main/resources/db/migration/V12__add_professional_project_requests.sql`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/discover/entity/DoctorProjectEntity.kt`
- Modify: order creation and institution-project DTO services that currently read `institution_projects.price`
- Test: related order and institution-project service tests

**Interfaces:**
- Produces: non-null `doctor_projects.price` backfilled from the current institution project price.
- Produces: order creation that requires and snapshots the selected doctor's project price without fallback.

- [ ] **Step 1: Add failing tests for distinct doctor prices and missing-price rejection**

- [ ] **Step 2: Add the migration and entity/DTO fields**

- [ ] **Step 3: Change order pricing to use only `doctor_projects.price`**

- [ ] **Step 4: Run focused order and project tests**

### Task 7: Preserve Both Institution Project Request Flows

**Files:**
- Modify: `DoctorProjectChangeService.kt` and its controller/tests for joining an existing institution project.
- Modify: `ProfessionalProjectRequestService.kt` and its controller/tests for creating a missing institution project.
- Modify: Flutter repository/models/pages and admin request review page.

**Interfaces:**
- Produces: existing-project `JOIN` requests with service content, price suggestion, and notes.
- Produces: new institution-project requests only when the institution does not already have the selected base project.
- Produces: `APPROVED`, `REJECTED`, and `CHANGES_REQUESTED` for both flows without reviewer content edits.

- [ ] **Step 1: Add failing tests for both request types and duplicate prevention**

- [ ] **Step 2: Reuse `doctor_project_change_requests` for existing-project joins**

- [ ] **Step 3: Keep `professional_project_requests` for missing institution projects**

- [ ] **Step 4: Update Flutter and admin UI labels, lists, forms, and review actions**

- [ ] **Step 5: Run focused tests, builds, migration verification, then one backend full test**
