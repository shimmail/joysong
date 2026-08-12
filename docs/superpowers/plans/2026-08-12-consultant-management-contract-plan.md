# Consultant Management Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver consultant-owned institution membership APIs, an authenticated professional project catalog, normalized documentation/Flutter VO contracts, and dedicated bilingual consultant pages.

**Architecture:** Focused management controllers derive ownership and role from `ManagementActor`; consultant membership persistence continues to use `institution_memberships`, while the project catalog projects read-only summaries from `projects`. Flutter introduces consultant-specific models and pages, reuses the searchable institution picker with an explicit consultant role, and retains the generic route only for legal-representative/admin review.

**Tech Stack:** Kotlin, Spring Boot, Spring Security, JDBC/JPA, JUnit 5/MockK; Dart, Flutter, existing `ApiClient`, Riverpod-free injected repositories, Flutter test.

## Global Constraints

- Routes are exactly `GET/POST /api/management/consultant-memberships` and `GET /api/management/projects`.
- Consultant identity is token-derived and requires real-time `ACTIVE CONSULTANT`; the client cannot choose user, role, action, or status.
- Consultant membership reads are owner-only and include all statuses; `visibleInstitutionIds` includes only APPROVED non-revoked consultant affiliations in addition to existing sources.
- POST has exactly required `institutionId` and `requestNote`; it is always CONSULTANT JOIN.
- PENDING/APPROVED duplicate submission returns 409; REJECTED/REVOKED resubmits the unique row in place.
- The generic legal-representative/admin review endpoint remains supported.
- The project catalog is read-only for ACTIVE professional identities and replaces consultant `/admin/projects` reads.
- Reuse the existing searchable institution picker with explicit consultant role; do not add another picker or dropdown.
- UI copy and states are Chinese/English.
- No migration is required; preserve unrelated worktree changes and avoid unrelated refactors.
- Run smallest relevant tests first, never repeat a passing command, and run at most one broader suite per stack.
- If database verification becomes necessary, print host/database first and use only `myapp_worktree_consultant_management_contract`; never touch a shared database.

---

### Task 1: Consultant-owned membership service and context visibility

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/ManagementAccessService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/InstitutionMembershipRequestServiceTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/ManagementAccessServiceTest.kt`

**Interfaces:**
- Produces: `listOwnedConsultant(actor): List<ConsultantMembershipView>`, `submitConsultant(actor, institutionId, requestNote): ConsultantMembershipView`, and `ManagementActor.consultantInstitutionIds`.
- Preserves: generic `list/review` behavior used by legal representatives and admins.

- [ ] Add failing focused tests proving ACTIVE CONSULTANT ownership, joined institution name/confirmation metadata, inactive-role rejection, fixed CONSULTANT writes, PENDING/APPROVED 409, REJECTED/REVOKED in-place resubmission, and approved consultant IDs in `visibleInstitutionIds`.
- [ ] Run `./gradlew test --tests com.joysong.server.identity.service.InstitutionMembershipRequestServiceTest --tests com.joysong.server.identity.service.ManagementAccessServiceTest`; expect the new assertions to fail before implementation.
- [ ] Add the focused store projection/join and service methods; normalize notes, validate institution existence, preserve the unique row, clear review metadata on resubmit, and map duplicate state/races to the existing conflict exception convention.
- [ ] Extend actor lookup with `institution_memberships.member_role='CONSULTANT' AND status='APPROVED' AND revoked_at IS NULL`; keep owner filtering independent from visibility.
- [ ] Rerun the same focused command once; expect PASS.
- [ ] Commit exact Task 1 files with `feat: add consultant membership domain contract`.

### Task 2: Management HTTP routes and read-only project catalog

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/identity/controller/ConsultantMembershipController.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/project/controller/ManagementProjectController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/identity/controller/ConsultantMembershipControllerTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/project/controller/ManagementProjectControllerTest.kt`

**Interfaces:**
- `SubmitConsultantMembershipRequest(institutionId: String, requestNote: String)` has exactly two required JSON properties.
- `ConsultantMembershipResponse` exposes id, institutionId/name, status, request/review notes, created/updated timestamps, and nullable confirmedBy/confirmedAt/revokedAt.
- `ProjectSummary` exposes id, name, category, description, tags, categoryTags, coverImage, referencePrice, and currency.

- [ ] Write controller tests for exact response schema and actor-derived ownership; reject extra/omitted identity fields and non-consultants; prove POST always calls the consultant JOIN service.
- [ ] Write project controller tests proving ACTIVE doctor/consultant/legal representative access, ordinary/inactive role 403, deterministic non-deleted summaries, and absence of mutation mappings.
- [ ] Run `./gradlew test --tests com.joysong.server.identity.controller.ConsultantMembershipControllerTest --tests com.joysong.server.project.controller.ManagementProjectControllerTest`; expect RED for missing routes.
- [ ] Implement the two controllers/DTOs and explicit authenticated security matchers; reuse `ProjectRepository` through a read-only service/query without exposing admin DTOs.
- [ ] Rerun the focused command once; expect PASS.
- [ ] Commit exact Task 2 files with `feat: expose consultant management contracts`.

### Task 3: Normative API documentation

**Files:**
- Modify: `doc/用户端API文档.md`
- Modify: `docs/FLUTTER_API_CONTRACT.md`
- Modify: `docs/CORE_ROLES_BUSINESS_SWIMLANE.puml`

**Interfaces:** Documents must exactly match the design response/request keys, role matrix, duplicate semantics, and retained generic review route.

- [ ] Add all three endpoints, examples, status/error tables, database authority, and explicit owner/token rules to the user API document.
- [ ] Replace consultant mixed/admin contracts in the Flutter contract and add `ConsultantMembership`, draft, and `ProjectSummary` mappings.
- [ ] Update swimlanes to show consultant list/JOIN, legal representative review, and professional read-only projects without admin-project access.
- [ ] Search the three documents for consultant `/admin/projects`, client-supplied consultant `requestType`, and contradictory membership ownership; expect no obsolete normative statement.
- [ ] Commit with `docs: specify consultant management contracts`.

### Task 4: Flutter consultant VO and repository migration

**Files:**
- Modify: `joysong-flutter/lib/features/identity/domain/identity_models.dart`
- Modify: `joysong-flutter/lib/features/identity/domain/identity_repository.dart`
- Modify: `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`
- Test: `joysong-flutter/test/features/identity/consultant_management_contract_test.dart`

**Interfaces:**
- Produces `ConsultantMembership`, `ConsultantMembershipDraft`, and `ProjectSummary` with strict required-key decoding and nullable confirmation fields.
- Produces repository methods `listConsultantMemberships()`, `submitConsultantMembership(ConsultantMembershipDraft)`, and `listManagementProjects()` calling only the new routes.

- [ ] Write one contract test covering GET decoding, exact POST body `{institutionId, requestNote}`, management project decoding, and route paths; expect RED for missing models/methods.
- [ ] Run `D:/code/kotlin/joysong/.flutter-cache/sdk/flutter/bin/flutter.bat test test/features/identity/consultant_management_contract_test.dart`; expect RED.
- [ ] Implement the dedicated models/repository methods, removing consultant consumption of mixed request DTOs and `/admin/projects` while preserving admin mutation methods for admin flows.
- [ ] Format changed Dart files with the cached Dart SDK.
- [ ] Rerun the focused test once; expect PASS.
- [ ] Commit with `refactor: use consultant management VO contract`.

### Task 5: Dedicated consultant membership and project pages

**Files:**
- Create: `joysong-flutter/lib/features/identity/presentation/consultant_membership_page.dart`
- Create: `joysong-flutter/lib/features/identity/presentation/management_project_catalog_page.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/identity_pages.dart`
- Modify: `joysong-flutter/lib/features/profile/presentation/profile_page.dart`
- Test: `joysong-flutter/test/features/identity/consultant_management_page_test.dart`

**Interfaces:** Uses the Task 4 repository and the existing searchable institution picker; picker invocation must pass `IdentityRoleType.consultant` and return selected institution id/name.

- [ ] Write at most two widget tests: consultant history plus picker/submit/reload with explicit consultant role; bilingual read-only project catalog loading/empty/error/retry with no edit action. Expect RED before pages exist.
- [ ] Run the new page test file; expect RED.
- [ ] Implement focused pages and route the consultant center entries to them; retain legal representative generic review page and doctor-specific relationship page.
- [ ] Delete consultant `requestType`/`affiliationOnly` branches and duplicate consultant UI from the mixed page, without changing reviewer semantics.
- [ ] Format changed Dart files and rerun the focused page test once; expect PASS.
- [ ] Run `flutter analyze` once after focused tests pass; fix only diagnostics caused by this slice.
- [ ] Commit with `feat: adapt consultant management pages`.

### Task 6: Contract audit and bounded verification

**Files:** Inspect all Task 1-5 files; modify only to fix discovered contract mismatches.

- [ ] Verify every explicit design requirement against current controllers, services, VO, pages, and all three documents; record missing evidence as unfinished rather than assuming completion.
- [ ] Search production Flutter code to prove consultant flows do not call `/admin/projects` or submit `requestType`, `userId`, `memberRole`, `status`, or `action` to the dedicated endpoint.
- [ ] Run the focused backend controller/service classes once if their earlier results are not available; do not run a full backend suite unless a reviewer identifies cross-module risk.
- [ ] Run the two focused Flutter test files once if their earlier results are not available; do not repeat already-passing commands.
- [ ] If database verification is required, print `DB_HOST` and `DB_NAME=myapp_worktree_consultant_management_contract` before connecting and refuse any shared/reset target.
- [ ] Review the branch diff for unrelated files and temporary artifacts; clean only artifacts created by this work.
- [ ] Commit any audit correction as `fix: align consultant management contracts`; otherwise leave the verified commits unchanged.
