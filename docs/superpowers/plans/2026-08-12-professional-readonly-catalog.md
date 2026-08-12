# Professional Read-only Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give active doctors and legal representatives a localized, object-safe, read-only professional catalog and let doctors withdraw pending JOIN or PROFILE_UPDATE requests without changing administrator or backend code.

**Architecture:** Reuse discover models, decoding, cards, and detail pages as the only catalog presentation layer. Identity remains responsible for context gating and professional request actions; remove professional project-edit operations from its production graph.

**Tech Stack:** Flutter/Dart, existing `ApiClient`, existing discover and identity features, Flutter widget/unit tests.

## Global Constraints

- Modify only `joysong-flutter/` and Flutter-facing documentation; never modify `joysong-admin/`, `joysong-server/`, migrations, or database data.
- Consume the existing `/api/admin/...` compatibility GET and withdraw paths without adding backend endpoints.
- Enforce workspace reachability from `ManagementContext` and preserve server object-level visibility; never probe arbitrary ids.
- Reuse existing discover VO/repository/card/detail code; do not create duplicate catalog VO classes.
- All new UI has Chinese and English copy plus search, empty, error, and retry states.
- Maintain at most three new/expanded core behavioral tests.

---

### Task 1: Pending doctor project-request withdrawal

**Files:**
- Modify: `joysong-flutter/lib/features/identity/domain/identity_repository.dart`
- Modify: `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart`
- Test: `joysong-flutter/test/features/identity/professional_request_pages_test.dart`

**Interfaces:**
- Produces: `Future<void> withdrawDoctorProjectChangeRequest(String id)`.
- Consumes: `DoctorProjectChangeRequest.status` and request type discriminator (`JOIN` or `PROFILE_UPDATE`).

- [ ] **Step 1: Add one failing widget/repository test**

Create two PENDING doctor requests, one `JOIN` and one `PROFILE_UPDATE`, and a non-pending request. Assert only the pending rows contain localized Withdraw actions; tapping confirms and records exactly `POST /admin/institution-project-requests/<id>/withdraw`, then refreshes the list. Assert a failed call preserves the row and exposes retryable localized feedback.

- [ ] **Step 2: Run the focused test and verify failure**

Run: `flutter test test/features/identity/professional_request_pages_test.dart --plain-name "doctor withdraws pending project requests"`

Expected: FAIL because the repository operation and eligible UI action do not exist.

- [ ] **Step 3: Add the minimal repository and UI implementation**

Add to `IdentityRepository`:

```dart
Future<void> withdrawDoctorProjectChangeRequest(String id);
```

Implement with:

```dart
await _apiClient.post<void>(
  '/admin/institution-project-requests/${id.trim()}/withdraw',
  decodeData: (_) {},
);
```

In the doctor-facing request list, normalize status/type to uppercase and render Withdraw only for `status == 'PENDING' && {'JOIN', 'PROFILE_UPDATE'}.contains(type)`. On success reload; on failure keep current state and show localized feedback.

- [ ] **Step 4: Run the focused test**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit the withdrawal slice**

```bash
git add joysong-flutter/lib/features/identity joysong-flutter/test/features/identity/professional_request_pages_test.dart
git commit -m "feat(flutter): withdraw pending doctor project requests"
```

### Task 2: Shared professional read-only catalog

**Files:**
- Modify: `joysong-flutter/lib/features/discover/domain/discover_repository.dart`
- Modify: `joysong-flutter/lib/features/discover/data/discover_repository_impl.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/catalog_widgets.dart`
- Create: `joysong-flutter/lib/features/discover/presentation/professional_catalog_page.dart`
- Test: `joysong-flutter/test/features/professional_management/professional_readonly_catalog_test.dart`

**Interfaces:**
- Consumes: existing discover institution, doctor, institution-project, and project models plus existing card/detail widgets.
- Produces: `ProfessionalCatalogPage({required DiscoverRepository repository, required ProfessionalCatalogScope scope})`, where scope is `doctor` or `legalRepresentative` and changes entry copy only, never visibility rules.

- [ ] **Step 1: Write the doctor catalog test**

Test an ACTIVE doctor journey with a recording fake: visible institution list, search match/no-results, institution detail, its doctors, one doctor’s projects, project detail, an injected GET failure, and retry. Assert every object id used came from the preceding response and the request log contains no POST/PUT/PATCH/DELETE.

- [ ] **Step 2: Run the doctor test and verify failure**

Run: `flutter test test/features/professional_management/professional_readonly_catalog_test.dart --plain-name "active doctor browses visible catalog"`

Expected: FAIL because the professional composition page is absent.

- [ ] **Step 3: Implement the shared page by composition**

Expose or reuse existing discover repository methods for the six compatibility GET resources. Keep the existing discover model decoders. Extract only reusable public card/detail widgets from `catalog_widgets.dart` when currently private; do not copy their build methods. Implement loading, localized search/no-results/empty/error/retry, and hierarchical navigation in `professional_catalog_page.dart`. Treat 403 and 404 identically in visible copy.

- [ ] **Step 4: Run the doctor test**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Add the legal representative case to the same test file**

Verify a legal representative sees institutions and institution projects returned by the recording fake, can open the reused details, sees all state copy in Chinese and English configurations, and has no create/edit/delete controls. Assert the full request log contains GET only.

- [ ] **Step 6: Run the legal test**

Run: `flutter test test/features/professional_management/professional_readonly_catalog_test.dart --plain-name "legal representative browses read-only catalog"`

Expected: PASS.

- [ ] **Step 7: Commit the catalog slice**

```bash
git add joysong-flutter/lib/features/discover joysong-flutter/test/features/professional_management/professional_readonly_catalog_test.dart
git commit -m "feat(flutter): add professional read-only catalog"
```

### Task 3: Context-gated workspace entry and write-residue removal

**Files:**
- Modify: `joysong-flutter/lib/features/identity/domain/identity_models.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/identity_pages.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/identity_controller.dart`
- Modify: `joysong-flutter/lib/features/identity/domain/identity_repository.dart`
- Modify: `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`
- Modify/delete unreachable editor portions: `joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart`
- Expand: `joysong-flutter/test/features/professional_management/professional_readonly_catalog_test.dart`

**Interfaces:**
- Consumes: server-derived `ManagementContext` roles/capabilities and `ProfessionalCatalogPage` from Task 2.
- Produces: catalog workspace action reachable only for ACTIVE doctor or capable legal representative.

- [ ] **Step 1: Extend the two role tests with entry gating and write-call assertions**

For doctor, assert inactive context hides the catalog and ACTIVE context exposes it. For legal representative, assert the relevant context capability exposes it. Traverse every visible action and assert the recording client never receives `POST /admin/projects` or POST/PUT/PATCH/DELETE to `/admin/institution-projects`.

- [ ] **Step 2: Run both catalog cases and verify failure**

Run: `flutter test test/features/professional_management/professional_readonly_catalog_test.dart`

Expected: FAIL while the old editor/create action remains reachable or the catalog entry is absent.

- [ ] **Step 3: Wire the context-gated catalog entry**

Add one localized workspace action in `identity_pages.dart`, derived strictly from `ManagementContext`, and navigate to `ProfessionalCatalogPage` with the appropriate scope. Do not hard-code a role claim from local preferences.

- [ ] **Step 4: Delete production write residue**

Remove `createManagementProject`, create/update/delete institution-project contracts and implementations, their controller methods, editor callbacks, and page branches used only by them. Preserve compatible GET operations and legitimate request submission/review/withdrawal. Search the Flutter production tree to ensure the prohibited write paths have no call sites.

- [ ] **Step 5: Run the combined catalog test**

Run the Step 2 command. Expected: PASS with doctor and legal cases and zero write calls.

- [ ] **Step 6: Commit the entry and cleanup**

```bash
git add joysong-flutter/lib/features/identity joysong-flutter/test/features/professional_management/professional_readonly_catalog_test.dart
git commit -m "refactor(flutter): isolate professional catalog from admin writes"
```

### Task 4: Flutter contract and user-flow documentation

**Files:**
- Modify: `docs/FLUTTER_API_CONTRACT.md`
- Modify: `docs/CORE_ROLES_BUSINESS_SWIMLANE.puml`
- Modify the existing professional user guide identified under `docs/` or `doc/`; if none exists, create: `docs/PROFESSIONAL_CATALOG_USER_GUIDE.md`

**Interfaces:**
- Documents: the seven compatibility endpoints, context gates, object-safe hierarchy, localized states, and administrator-system invariance.

- [ ] **Step 1: Update the Flutter contract**

List the exact GET and withdraw routes from the design. Mark catalog resources read-only in professional Flutter and state that 403/404 use the same unavailable state.

- [ ] **Step 2: Update user guidance and swimlane**

Describe doctor and legal representative entry conditions, search/empty/error/retry, details navigation, and pending JOIN/PROFILE_UPDATE withdrawal. Show Flutter → existing API only; add the explicit note “管理员系统、管理员接口实现与页面不变 / Admin system, admin API implementation, and pages are unchanged.”

- [ ] **Step 3: Commit documentation**

```bash
git add docs/FLUTTER_API_CONTRACT.md docs/CORE_ROLES_BUSINESS_SWIMLANE.puml docs/PROFESSIONAL_CATALOG_USER_GUIDE.md
git commit -m "docs: describe professional read-only catalog"
```

### Task 5: Final focused verification

**Files:**
- Verify only; do not add temporary files.

- [ ] **Step 1: Run the three core behavioral cases once**

```bash
flutter test test/features/identity/professional_request_pages_test.dart --plain-name "doctor withdraws pending project requests"
flutter test test/features/professional_management/professional_readonly_catalog_test.dart --plain-name "active doctor browses visible catalog"
flutter test test/features/professional_management/professional_readonly_catalog_test.dart --plain-name "legal representative browses read-only catalog"
```

Expected: all PASS.

- [ ] **Step 2: Run static analysis once**

Run: `flutter analyze`

Expected: exit 0 with no analyzer errors.

- [ ] **Step 3: Audit the boundary**

Run searches scoped to `joysong-flutter/lib` for `createManagementProject`, mutation methods targeting `/admin/institution-projects`, and `POST /admin/projects`; expect no production call sites. Run `git diff --name-only <base>...HEAD`; expect no `joysong-admin/`, `joysong-server/`, migration, or database files.

- [ ] **Step 4: Commit any verification-only documentation correction**

If no correction is needed, do not create an empty commit. Remove any temporary test artifact before handoff.
