# Doctor Project Client Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a working Flutter doctor project management flow grouped by institution, with profile editing, uploaded images, and institution-reviewed leave requests.

**Architecture:** Keep the existing server endpoints and models as the source of truth. Add one narrow repository method for `LEAVE`, split the current combined selector/form into an institution-grouped list plus a focused edit page, and reuse the existing image upload field. Pending requests remain loaded only to drive row state and duplicate-submission protection.

**Tech Stack:** Flutter, Dart, `flutter_test`, existing `IdentityRepository` and `ApiClient`.

**Spec:** `docs/superpowers/specs/2026-08-24-doctor-project-client-management-design.md`

## Global Constraints

- Modify only `joysong-flutter/` code and its closest tests; preserve all unrelated dirty-worktree changes.
- Keep USD pricing and the existing travel ground service fee preview.
- Do not invent client-only persistence for institution shared fields or doctor-level visibility.
- Do not show the bottom “我的项目申请” history section.
- Use the existing `/admin/institution-project-requests` endpoint for both profile updates and leave requests.
- Follow red-green-refactor and run the smallest related tests first.

---

### Task 1: Add the typed leave repository contract

**Files:**
- Modify: `joysong-flutter/lib/features/identity/domain/identity_repository.dart`
- Modify: `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`
- Test: `joysong-flutter/test/features/identity/doctor_project_profile_update_contract_test.dart`

**Interfaces:**
- Produces: `Future<DoctorProjectChangeRequest> submitDoctorProjectLeave({required String institutionProjectId})`.
- Sends: `POST /admin/institution-project-requests` with exactly `requestType` and `institutionProjectId`.

- [ ] **Step 1: Write the failing repository contract test**

Add a test that calls `submitDoctorProjectLeave(institutionProjectId: ' ip-1 ')` and expects:

```dart
const _Request(
  'POST',
  '/admin/institution-project-requests',
  {'requestType': 'LEAVE', 'institutionProjectId': 'ip-1'},
)
```

- [ ] **Step 2: Run the contract test and verify it fails because the method is missing**

Run: `flutter test test/features/identity/doctor_project_profile_update_contract_test.dart`

- [ ] **Step 3: Implement the minimal repository method**

Validate the trimmed ID is non-empty, post the exact two-field body, decode with `DoctorProjectChangeRequest.fromJson`, and throw `FormatException('医生项目变更申请响应为空')` for an empty response.

- [ ] **Step 4: Run the contract test and verify it passes**

Run the same contract test once.

### Task 2: Replace the flat selector with institution groups and leave actions

**Files:**
- Modify: `joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart`
- Test: `joysong-flutter/test/features/identity/doctor_project_profile_update_page_test.dart`

**Interfaces:**
- Consumes: `loadDoctorSelfProfile`, `listDoctorProjectProfileUpdateTargets`, `listDoctorProjectChangeRequests`, and `submitDoctorProjectLeave`.
- Produces keys: `doctor-project-institution-<institutionId>`, `doctor-project-menu-<institutionProjectId>`, `doctor-project-edit-<institutionProjectId>`, and `doctor-project-leave-<institutionProjectId>`.

- [ ] **Step 1: Write failing widget tests for grouping and history removal**

Use two institutions and multiple targets. Assert institutions render as expansion groups, collapsing hides their project rows, and “My project requests” is absent.

- [ ] **Step 2: Write failing widget tests for leave confirmation**

Assert cancel sends no request; confirm passes the exact institution project ID; success keeps the row and shows a pending status because institution approval is still required.

- [ ] **Step 3: Run only the page test and verify the new cases fail for missing UI/actions**

Run: `flutter test test/features/identity/doctor_project_profile_update_page_test.dart`

- [ ] **Step 4: Implement the grouped list and leave flow**

Load the doctor profile tolerantly, synthesize any missing institution groups from target data, group by institution ID, and mark a project pending when its latest pending request type is `PROFILE_UPDATE` or `LEAVE`. Show an empty group state for affiliated institutions without editable joined projects. Confirm leave before submitting and refresh after success.

- [ ] **Step 5: Run the page test and verify grouping and leave cases pass**

Run the same page test once.

### Task 3: Move editing to a focused form and reuse image upload UI

**Files:**
- Modify: `joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart`
- Test: `joysong-flutter/test/features/identity/doctor_project_profile_update_page_test.dart`

**Interfaces:**
- Consumes: a selected `DoctorProjectProfileUpdateTarget` and the existing `pickAndUploadImage` callback.
- Produces: the unchanged `DoctorProjectProfileUpdateDraft` wire contract.

- [ ] **Step 1: Write failing widget tests for the edit action and image controls**

Assert tapping the project edit action opens a prefilled form. Assert cover upload replaces the old cover, gallery upload appends a URL, deleting a gallery item removes it, and submission contains the resulting `coverImage` and `images`.

- [ ] **Step 2: Run only the page test and verify the cases fail for the old URL fields/flat form**

Run: `flutter test test/features/identity/doctor_project_profile_update_page_test.dart`

- [ ] **Step 3: Implement the focused edit form**

Reuse `_imageUploadField` for cover and gallery, keep existing price/description/tags/schedule/note validation and hidden legacy split values, and return to the grouped page after a successful submission so it can refresh pending state.

- [ ] **Step 4: Run the page test and verify all edit cases pass**

Run the same page test once.

### Task 4: Verify the client increment

**Files:**
- Verify only; no new production files.

**Interfaces:**
- Consumes the completed repository and page behavior.
- Produces test and static-analysis evidence.

- [ ] **Step 1: Run the two closest tests together once**

Run:

```text
flutter test test/features/identity/doctor_project_profile_update_contract_test.dart test/features/identity/doctor_project_profile_update_page_test.dart
```

- [ ] **Step 2: Run Flutter analysis once**

Run: `flutter analyze`

- [ ] **Step 3: If the shared Flutter SDK lock blocks execution, record the exact failure and run the bundled Dart analyzer against the touched files**

Analyze the repository interface, implementation, page, and the two test files without modifying SDK state.

- [ ] **Step 4: Review the final diff for scope and temporary artifacts**

Confirm no server/admin files changed for this task, no URL input remains in the doctor edit form, and no temporary test files remain.
