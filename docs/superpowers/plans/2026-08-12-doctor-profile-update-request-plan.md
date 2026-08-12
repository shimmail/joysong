# Doctor PROFILE_UPDATE Implementation Plan

**Goal:** Extend the existing institution-project request workflow so a doctor can atomically request and activate their own doctor-project price, display profile, consultation fee, and split rates after institution/legal-representative approval, with audited admin force handling and Flutter adaptation.

**Authoritative design:** `docs/superpowers/specs/2026-08-12-doctor-profile-update-request-design.md`

## Task 1: Persist a complete immutable PROFILE_UPDATE snapshot

**Files:**

- Create `joysong-server/src/main/resources/db/migration/V16__extend_doctor_project_profile_update_requests.sql` (or the next free Flyway version at implementation time)
- Modify `joysong-server/src/main/resources/db/migration/V1__init_schema.sql`
- Modify `joysong-server/src/main/resources/db/migration/B1__init_schema.sql`
- Modify `joysong-server/src/main/kotlin/com/joysong/server/institution/service/DoctorProjectChangeService.kt`
- Test `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectChangeServiceTest.kt`
- Add focused migration integration test only if the existing migration verifier cannot assert constraints

Steps:

1. Add a failing test for PROFILE_UPDATE exact full input, snapshot fields, own-doctor target resolution, and duplicate PENDING conflict.
2. Add migration columns/checks for the three config values, base versions, and `force_processed`; retain historic rows safely.
3. Replace the flat permissive DTO validation with requestType-discriminated exact validation. Keep JOIN/LEAVE compatible.
4. On PROFILE_UPDATE submit, derive doctor/institution from actor and target, capture current doctor-project/config versions, and persist all proposed values.
5. Map duplicate-key and stale-domain cases to the normalized 409 response.
6. Run only `DoctorProjectChangeServiceTest`.
7. Print isolated host/database, then verify all migrations from empty `myapp_worktree_doctor_profile_update_request`; do not reset any other database.

## Task 2: Apply approval atomically and implement audited force semantics

**Files:**

- Modify `joysong-server/src/main/kotlin/com/joysong/server/institution/service/DoctorProjectChangeService.kt`
- Modify `joysong-server/src/main/kotlin/com/joysong/server/discover/repository/DoctorProjectRepository.kt`
- Modify `joysong-server/src/main/kotlin/com/joysong/server/order/repository/DoctorInstitutionProjectConfigRepository.kt`
- Modify `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/DoctorProjectChangeController.kt`
- Modify the project exception mapping only where required for 404/409
- Test `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectChangeServiceTest.kt`
- Test the closest controller/security test class
- Add one MySQL transactional integration test class if no existing class covers real row locks/rollback

Steps:

1. Add failing tests proving: legal representative approval changes both effective rows; injected failure rolls both back; doctor B under the same institution project is unchanged.
2. Add row-lock repository operations using the exact `(doctor_id, institution_project_id)` key and a fixed lock order.
3. Compare submission baselines for normal approval, re-run `OrderSplitRatePolicy`, update doctor project and config, then CAS the request inside one transaction.
4. Extend review input with mandatory `force`; reject force for non-admin. Require a note for force and record `force_processed=true`.
5. Allow admin force to bypass only relationship/baseline drift, never target existence, key ownership, range, request status, or atomicity.
6. Add the smallest controller/security cases for wrong legal representative, admin force, non-admin force, and repeat/concurrent review.
7. Run the service class, then the related controller/security class. Run the isolated MySQL transactional class once.

## Task 3: Publish the normalized API documentation

**Files:**

- Modify `docs/CORE_ROLES_BUSINESS_SWIMLANE.puml`
- Modify the repository's professional-management API document/OpenAPI source discovered during implementation
- Modify `joysong-server/openapi.json` only through the project's established generation/update process

Steps:

1. Document the exact 12-key PROFILE_UPDATE request, response fields, ranges, inferred doctor rate, null/empty rules, and 400/401/403/404/409 errors.
2. Document normal legal-representative review and admin `force=true`, including audit and stale-baseline behavior.
3. Update the swimlane to show one request and one atomic approval; explicitly state other doctors are untouched.
4. Run only documentation/schema validation if present; do not run backend tests for documentation-only changes.

## Task 4: Reconfigure Flutter VO and repository contracts

**Files:**

- Modify `joysong-flutter/lib/features/identity/domain/identity_models.dart`
- Modify `joysong-flutter/lib/features/identity/domain/identity_repository.dart`
- Modify `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`
- Modify `joysong-flutter/lib/features/identity/presentation/identity_controller.dart`
- Test `joysong-flutter/test/features/identity/identity_models_controller_test.dart` or a narrower new contract test

Steps:

1. Add failing contract tests for `DoctorProjectProfileUpdateDraft.toJson`, array decoding, new view fields, and explicit review `force`.
2. Introduce the dedicated PROFILE_UPDATE draft/view fields; keep JOIN VO separate and compatible.
3. Wire submit to the existing POST path and review to the existing review path. Do not call the split-proposal endpoint for PROFILE_UPDATE.
4. Add controller state for initial load, submitting, PENDING success, 409 conflict, and retry.
5. Run only the named Flutter contract/controller tests.

## Task 5: Adapt doctor, legal-representative, and admin Flutter pages

**Files:**

- Modify `joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart`
- Modify the closest existing professional-management navigation/page files only as needed
- Add/modify one focused widget test file

Steps:

1. Add failing widget cases for doctor full-form submission/PENDING, legal representative comparison/review, and admin-only force action.
2. Build the doctor editor with current values, exact full submission, list-based tags/images, field validation, and duplicate-submit prevention.
3. Build review comparison UI with derived platform/doctor rates; hide force for non-admin and require confirmation/note for admin force.
4. Add every new user-visible string in both Chinese and English using the existing localization convention.
5. Run the focused widget tests and `flutter analyze` scoped to changed files where supported; avoid rerunning already-green commands.

## Task 6: Cross-layer verification and cleanup

1. Search production code to confirm PROFILE_UPDATE uses only the specified submit path and never performs a project-wide doctor update.
2. Run each minimal related backend and Flutter test once; after all targeted tests pass, optionally run at most one broader suite and stop it at ten minutes.
3. Re-run the empty isolated migration verification if migration changed after Task 1.
4. Inspect the final diff for unrelated refactors, generated plugin registrants, test databases, `.runtime`, Gradle/Flutter caches, and temporary files; remove only task-created artifacts.
5. Verify requirement-by-requirement evidence: own doctor, full snapshot, legal approval, admin force audit, two-table atomicity, stale/CAS behavior, other doctor unchanged, docs, VO, pages, and bilingual UI.
