# Professional Institution Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace doctor and consultant institution-binding dropdowns with a searchable, paginated all-institutions picker while preserving doctor LEAVE as a local bound-institution selection and making request type explicit at each role entry.

**Architecture:** Build one selection-mode page in the discover presentation layer around the existing `DiscoverController`, `DiscoverRepository`, and `DiscoverContentCard`. Inject the existing discover repository through the shell/profile/management-center route chain. Return a small id/name selection value to identity pages; do not add a backend endpoint or duplicate discover networking.

**Tech Stack:** Flutter, Dart, existing repository/controller presentation pattern, `flutter_test`.

## Global Constraints

- Do not change backend code or API contracts.
- Add exactly three focused core tests; do not duplicate pagination coverage.
- Doctor `JOIN` uses the all-institutions picker; doctor `LEAVE` uses only locally bound institutions.
- Doctor and consultant entry points must pass explicit `DOCTOR` and `CONSULTANT` request types.
- Reuse the shell-owned `DiscoverRepository`; pages must not construct another API client or repository.

---

## File Structure

- Reference unchanged: `joysong-flutter/lib/features/discover/presentation/discover_page.dart` for search debounce, loading/error/empty states, and scroll behavior.
- Reuse unchanged unless a small reusable extraction is unavoidable: `discover_controller.dart`, `discover_repository.dart`, and `discover_content_card.dart`.
- Create: `joysong-flutter/lib/features/discover/presentation/institution_picker_page.dart` for selection-only UI and id/name result.
- Modify: `professional_request_pages.dart` and `institution_relationships_page.dart` for picker consumers.
- Modify: `identity_pages.dart`, `profile_page.dart`, and `app_shell.dart` for explicit role routing and repository injection.

---

### Task 1: Searchable Institution Picker

**Files:**
- Create: `joysong-flutter/lib/features/discover/presentation/institution_picker_page.dart`
- Test: `joysong-flutter/test/features/discover/institution_picker_page_test.dart`
- Reuse unchanged: `joysong-flutter/lib/features/discover/domain/discover_models.dart`
- Reuse unchanged: `joysong-flutter/lib/features/discover/domain/discover_repository.dart`
- Reuse unchanged: `joysong-flutter/lib/features/discover/presentation/discover_controller.dart`
- Reuse unchanged: `joysong-flutter/lib/features/discover/presentation/discover_content_card.dart`

**Interfaces:**
- Consumes: `DiscoverController(DiscoverRepository, type: DiscoverContentType.institution)`, `load(query:)`, and `loadMore()`.
- Produces: `InstitutionPickerSelection({required String id, required String name})` returned by `Navigator.pop`.

- [ ] Write test 1: enter a padded search term, settle the 350ms debounce, and assert the fake repository receives the trimmed query with `DiscoverContentType.institution`.
- [ ] Write test 2: tap an institution card and assert the pushed route returns `InstitutionPickerSelection(id, name)` while fake `loadDetail` remains uncalled.
- [ ] Run only `flutter test test/features/discover/institution_picker_page_test.dart` and confirm RED because the picker does not exist.
- [ ] Implement `InstitutionPickerSelection` and `InstitutionPickerPage`; use `DiscoverController(type: institution)`, existing institution cards, search debounce, retry/empty/loading states, and scroll-triggered `loadMore()`.
- [ ] Dispose the controller, text/scroll controllers and timer. Keep card tap selection-only; do not navigate to detail.
- [ ] Format touched Dart files and rerun only the new picker test until its two tests pass.
- [ ] Commit: `feat: add searchable institution picker`

### Task 2: Explicit Doctor and Consultant Request Types

**Files:**
- Modify: `joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/identity_pages.dart`
- Test: `joysong-flutter/test/features/identity/identity_models_controller_test.dart` (or the closest existing professional-request page test if one is introduced by concurrent work)

**Interfaces:**
- Consumes: `InstitutionPickerSelection` and injected `DiscoverRepository`.
- Produces: `InstitutionMembershipRequestsPage(..., required String requestType, required DiscoverRepository discoverRepository)` with `requestType` restricted to `DOCTOR` or `CONSULTANT`.

- [ ] Write test 3 as one multi-role widget scenario: use a context containing both `DOCTOR` and `CONSULTANT`, enter through each role-specific capability, choose an institution, submit, and assert calls carry `DOCTOR` and `CONSULTANT` respectively.
- [ ] Run only that named test and confirm RED because the page still infers type from `activeRoles`.
- [ ] Add required `requestType` and `DiscoverRepository` inputs to `InstitutionMembershipRequestsPage`; validate the type at the constructor/boundary and remove the `_requestType` role inference.
- [ ] In `_ManagementCapabilities`, model capability actions with an explicit role discriminator (do not route by the identical translated label alone). Pass `DOCTOR` from the doctor entry and `CONSULTANT` from the consultant entry.
- [ ] Replace the membership dropdown with a button/list tile that opens `InstitutionPickerPage`, stores returned id/name, and submits the selected id with the explicit request type.
- [ ] Run only the named multi-role test; do not rerun the already-passed picker tests.
- [ ] Commit: `fix: preserve role in institution applications`

### Task 3: Doctor JOIN Picker and LEAVE Boundary

**Files:**
- Modify: `joysong-flutter/lib/features/identity/presentation/institution_relationships_page.dart`
- Modify: `joysong-flutter/lib/features/profile/presentation/profile_page.dart`
- Modify: `joysong-flutter/lib/features/shell/presentation/app_shell.dart`
- Compile-migrate as needed: `joysong-flutter/lib/features/identity/presentation/identity_pages.dart`
- Existing test: `joysong-flutter/test/features/identity/institution_relationships_page_test.dart`

**Interfaces:**
- Consumes: the shell-owned `DiscoverRepository` threaded through `ProfilePage` and `ManagementCenterPage`.
- Produces: doctor relationship selection where `JOIN` stores picker id/name and `LEAVE` resolves only `ManagementContext.doctorInstitutionIds`.

- [ ] Thread the existing `DiscoverRepository` from `AppShell` into `ProfilePage`, then into both `ManagementCenterPage` and `InstitutionRelationshipsPage`. Do not instantiate `DiscoverRepositoryImpl` inside a page.
- [ ] For doctor `JOIN`, replace the institution dropdown with picker navigation and retain the selected id/name for display and submission.
- [ ] For doctor `LEAVE`, retain the local dropdown filtered by `doctorInstitutionIds`; do not query all institutions for a leave action.
- [ ] Clear selection when switching actions. Disable selection during submit and preserve the prior value if picker navigation is cancelled.
- [ ] Compile-migrate constructors and test fakes without adding duplicate behavioral tests.
- [ ] Run the existing named test `doctor submits a leave relationship request` once to confirm the LEAVE boundary remains intact.
- [ ] Commit: `feat: use institution picker for doctor joins`

### Task 4: Focused Verification and Cleanup

**Files:** all files touched above.

- [ ] Run `dart format --output=none --set-exit-if-changed` on the exact touched Dart files.
- [ ] Run `flutter analyze` once for the Flutter package. If unrelated baseline diagnostics exist, record them and run analysis scoped to touched files only for fix cycles.
- [ ] Run the three new core tests together once. Do not add or rerun a new pagination test; rely on the existing `controller paginates and removes duplicate ids` evidence.
- [ ] Run the existing doctor LEAVE named test once if Task 3 changed its widget wiring.
- [ ] Search touched identity pages to confirm no institution-binding `DropdownButtonFormField` remains for JOIN and no `activeRoles.contains('DOCTOR') ? 'DOCTOR' : 'CONSULTANT'` inference remains. The LEAVE dropdown is expected.
- [ ] Remove temporary files and generated plugin registrant changes, inspect `git diff --check`, and commit any final scoped fix.

## Completion Evidence

- Doctor and consultant binding entries open the same searchable, paginated institution picker.
- Search reaches `/discover/institutions` through the existing discover repository; pagination remains covered by the existing controller test.
- Card tap returns id/name without opening or loading institution detail.
- Multi-role users submit the role selected at the entry, not a role inferred from their account.
- Doctor JOIN uses the picker; doctor LEAVE only offers already-bound institutions.
- No backend file or API contract is changed.
