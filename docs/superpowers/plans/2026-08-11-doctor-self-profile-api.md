# Doctor Self Profile API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a documented `GET/PUT /api/management/doctor-profile` vertical slice that lets an ACTIVE DOCTOR read and replace the nine editable fields of their own public profile, then migrate Flutter VO, repository, and page behavior to that contract.

**Architecture:** Add a management-scoped controller and a focused doctor self-profile service that derive the doctor ID from `ManagementActor`, use a dedicated update DTO, preserve platform-managed fields, and reuse doctor/institution persistence. Flutter replaces the admin-oriented list/draft models with single-resource read/update VO and injects the existing public-media upload capability into the profile page.

**Tech Stack:** Kotlin, Spring Boot, Spring Security, JPA, MockK/JUnit 5; Dart, Flutter, existing `ApiClient`, `ApiPublicMediaUploader`, Flutter test.

## Global Constraints

- Routes are exactly `GET /api/management/doctor-profile` and `PUT /api/management/doctor-profile`.
- Only an authenticated user with real-time `ACTIVE DOCTOR` and a matching non-deleted doctor profile may access the resource.
- The client never sends a path doctor ID or `userId`.
- PUT is full replacement: all nine editable JSON properties are required; `name` must be non-blank; the other eight may be `""` to clear them; `null` is rejected.
- Editable fields are exactly `name`, `title`, `bio`, `avatar`, `contactPhone`, `specialties`, `credentials`, `credentialImages`, and `certificationTags`.
- Platform-managed identity, institution, rating, verification, and counter fields remain unchanged.
- `credentials` and `credentialImages` are doctor-authored public display material, never private identity evidence or proof of platform verification.
- No database migration is required.
- Preserve unrelated dirty-worktree changes and do not refactor outside this vertical slice.
- Run the smallest relevant test first; run each already-passing command only once; run at most one broader suite per stack after relevant tests pass.

---

## File Structure

- Create `joysong-server/src/main/kotlin/com/joysong/server/doctor/controller/DoctorProfileController.kt`: management HTTP contract and dedicated request DTO.
- Create `joysong-server/src/main/kotlin/com/joysong/server/doctor/service/DoctorProfileService.kt`: actor validation, normalization, persistence, response assembly, and shared profile view models.
- Create `joysong-server/src/test/kotlin/com/joysong/server/doctor/service/DoctorProfileServiceTest.kt`: service behavior and immutable-field coverage.
- Create `joysong-server/src/test/kotlin/com/joysong/server/doctor/controller/DoctorProfileControllerTest.kt`: controller contract, full PUT validation, and actor-derived identity.
- Modify `joysong-server/src/main/kotlin/com/joysong/server/identity/service/ManagementAccessService.kt`: preserve ACTIVE DOCTOR identity when its profile row is missing so the resource can return 404.
- Modify `joysong-server/src/test/kotlin/com/joysong/server/identity/service/ManagementAccessServiceTest.kt`: cover ACTIVE DOCTOR without a profile.
- Modify `joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt`: explicitly authenticate the new management route.
- Modify `doc/用户端API文档.md`: add the normative doctor self-profile contract and correct the role matrix.
- Modify `docs/FLUTTER_API_CONTRACT.md`: replace the doctor profile admin-route contract with the new single-resource contract.
- Modify `docs/CORE_ROLES_BUSINESS_SWIMLANE.puml`: show the new GET/PUT routes in the doctor lane.
- Modify `joysong-flutter/lib/features/identity/domain/identity_models.dart`: introduce `DoctorSelfProfile` and `DoctorSelfProfileUpdate`.
- Modify `joysong-flutter/lib/features/identity/domain/identity_repository.dart`: expose single-resource load/update methods.
- Modify `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`: call the new routes.
- Modify `joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart`: load one profile, edit all nine fields, and support avatar/material upload/remove.
- Modify the professional-center composition entry in `joysong-flutter/lib/features/profile/presentation/profile_page.dart`: inject public image selection/upload into the page.
- Modify `joysong-flutter/test/features/identity/identity_models_controller_test.dart`: model, repository fake, and page regression coverage.
- Create `joysong-flutter/test/features/identity/doctor_self_profile_page_test.dart`: focused widget tests for single-resource load, complete PUT, blank optional fields, upload, removal, and copy.

---

### Task 1: Backend domain service for doctor-owned profile

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/doctor/service/DoctorProfileService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/doctor/service/DoctorProfileServiceTest.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/ManagementAccessService.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/ManagementAccessServiceTest.kt`

**Interfaces:**
- Consumes: `ManagementActor`, `DoctorService.findById/save`, `DoctorInstitutionService.institutionsFor`.
- Produces: `DoctorProfileService.get(actor): DoctorProfileView`, `DoctorProfileService.update(actor, command): DoctorProfileView`, `DoctorProfileUpdateCommand`, `DoctorProfileView`, and `DoctorInstitutionView`.

- [ ] **Step 1: Read the test-quality rules before changing tests**

Read `C:/Users/shimeng/.codex/plugins/cache/openai-curated-remote/superpowers/6.2.0/skills/test-driven-development/writing-good-tests.md` completely and apply its naming, mutation, and behavior-assertion rules.

- [ ] **Step 2: Write failing service tests for access, normalization, and preservation**

Create tests whose production-code failure point is the missing `DoctorProfileService`:

```kotlin
@Test
fun `active doctor replaces editable profile fields and preserves platform fields`() {
    val existing = DoctorEntity(
        id = "doctor-1",
        name = "旧姓名",
        institutionId = "institution-1",
        institutionName = "机构一",
        rating = BigDecimal("4.9"),
        reviewCount = 8,
        isVerified = true,
        consultationCount = 12,
        caseCount = 7
    )
    every { doctorService.findById("doctor-1") } returns existing
    every { doctorService.save(any()) } answers { firstArg() }
    every { doctorInstitutionService.institutionsFor("doctor-1") } returns emptyList()

    val view = service.update(
        doctorActor("doctor-1"),
        DoctorProfileUpdateCommand(
            name = "  王医生  ", title = "", bio = " 新简介 ", avatar = "",
            contactPhone = "", specialties = "玻尿酸, , 肉毒素",
            credentials = "", credentialImages = "", certificationTags = ""
        )
    )

    verify { doctorService.save(match {
        it.name == "王医生" && it.bio == "新简介" &&
            it.specialties == "玻尿酸,肉毒素" &&
            it.rating == BigDecimal("4.9") && it.reviewCount == 8 &&
            it.isVerified && it.institutionId == "institution-1" &&
            it.consultationCount == 12 && it.caseCount == 7
    }) }
    assertEquals("doctor-1", view.id)
}
```

Also add one-behavior tests for `get` returning a single view, blank `name` rejection, non-doctor rejection, and missing profile producing the dedicated not-found exception. In `ManagementAccessServiceTest`, add an ACTIVE DOCTOR/no-row case proving `actor.activeRoles` contains `DOCTOR` while `actor.doctorId` is null instead of throwing 403.

- [ ] **Step 3: Run the service test and verify RED**

Run from `joysong-server`:

```powershell
$env:GRADLE_USER_HOME = (Join-Path $PWD '..\.tmp\gradle-user-home-codex')
.\gradlew.bat test --tests com.joysong.server.doctor.service.DoctorProfileServiceTest
```

Expected: compilation failure because the new service/types do not exist. Do not proceed if the failure is caused by an unrelated dirty-worktree compile error; report and isolate it first.

- [ ] **Step 4: Implement the minimal service and models**

Implement focused types in `DoctorProfileService.kt`:

```kotlin
data class DoctorProfileUpdateCommand(
    val name: String,
    val title: String,
    val bio: String,
    val avatar: String,
    val contactPhone: String,
    val specialties: String,
    val credentials: String,
    val credentialImages: String,
    val certificationTags: String
)

data class DoctorInstitutionView(val id: String, val name: String)

data class DoctorProfileView(
    val id: String,
    val userId: String,
    val name: String,
    val title: String,
    val bio: String,
    val avatar: String,
    val contactPhone: String,
    val specialties: String,
    val credentials: String,
    val credentialImages: String,
    val certificationTags: String,
    val institutionId: String,
    val institutionName: String,
    val institutions: List<DoctorInstitutionView>,
    val primaryInstitution: DoctorInstitutionView?,
    val institutionCount: Int,
    val rating: BigDecimal,
    val reviewCount: Int,
    val isVerified: Boolean,
    val consultationCount: Int,
    val caseCount: Int
)
```

`requireDoctorId(actor)` must reject admins and actors without `DOCTOR` in `activeRoles` using `AccessDeniedException("当前账号没有有效医生身份")`; then use `actor.doctorId ?: actor.userId` so an ACTIVE DOCTOR whose row disappeared reaches the explicit 404 path. Define `DoctorProfileNotFoundException` for that path. In `ManagementAccessService.actorFor`, change the final incomplete-management-record rejection so an ACTIVE DOCTOR may return an actor with `doctorId = null`; retain the existing rejection for a legal representative with no managed institution and no other usable role. Normalize trim-able fields and comma-separated fields before copying only the nine editable properties onto the existing entity.

- [ ] **Step 5: Run the service test and verify GREEN**

Run the same focused Gradle command once. Expected: PASS.

- [ ] **Step 6: Commit the service slice**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/doctor/service/DoctorProfileService.kt joysong-server/src/main/kotlin/com/joysong/server/identity/service/ManagementAccessService.kt joysong-server/src/test/kotlin/com/joysong/server/doctor/service/DoctorProfileServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/identity/service/ManagementAccessServiceTest.kt
git commit -m "feat: add doctor self profile service"
```

---

### Task 2: Management HTTP contract, validation, and security

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/doctor/controller/DoctorProfileController.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/doctor/controller/DoctorProfileControllerTest.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/common/GlobalExceptionHandler.kt`

**Interfaces:**
- Consumes: `ManagementAccessService.actor(Authentication)`, Task 1 `DoctorProfileService`.
- Produces: `GET/PUT /api/management/doctor-profile`, `DoctorProfileUpdateRequest.toCommand()`.

- [ ] **Step 1: Write failing controller tests**

Use standalone MockMvc with `GlobalExceptionHandler` for controller validation and identity derivation:

```kotlin
@Test
fun `put derives doctor from authentication and accepts all nine fields`() {
    every { accessService.actor(authentication) } returns doctorActor("doctor-1")
    every { service.update(any(), any()) } returns profileView("doctor-1")

    mockMvc.perform(
        put("/api/management/doctor-profile")
            .principal(authentication)
            .contentType(MediaType.APPLICATION_JSON)
            .content(fullUpdateJson(name = "王医生", title = ""))
    )
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.data.id").value("doctor-1"))

    verify { service.update(match { it.userId == "doctor-1" }, match {
        it.name == "王医生" && it.title == ""
    }) }
}
```

Add focused tests for GET single-object shape, omitted property returning 400, `null` returning 400, blank name returning 400, `DoctorProfileNotFoundException` returning HTTP/body 404, and `AccessDeniedException` returning HTTP/body 403.

- [ ] **Step 2: Run controller tests and verify RED**

```powershell
.\gradlew.bat test --tests com.joysong.server.doctor.controller.DoctorProfileControllerTest
```

Expected: FAIL because controller/request mapping does not exist.

- [ ] **Step 3: Implement the controller and required request properties**

Use non-null constructor properties without defaults so omitted/null fields fail JSON binding:

```kotlin
data class DoctorProfileUpdateRequest(
    val name: String,
    val title: String,
    val bio: String,
    val avatar: String,
    val contactPhone: String,
    val specialties: String,
    val credentials: String,
    val credentialImages: String,
    val certificationTags: String
) {
    fun toCommand(): DoctorProfileUpdateCommand {
        require(name.isNotBlank()) { "name 不能为空" }
        return DoctorProfileUpdateCommand(
            name, title, bio, avatar, contactPhone,
            specialties, credentials, credentialImages, certificationTags
        )
    }
}

@RestController
@RequestMapping("/api/management/doctor-profile")
class DoctorProfileController(
    private val managementAccessService: ManagementAccessService,
    private val doctorProfileService: DoctorProfileService
) {
    @GetMapping
    fun get(authentication: Authentication) = BaseResponse.success(
        doctorProfileService.get(managementAccessService.actor(authentication))
    )

    @PutMapping
    fun update(authentication: Authentication, @RequestBody request: DoctorProfileUpdateRequest) =
        BaseResponse.success(doctorProfileService.update(
            managementAccessService.actor(authentication), request.toCommand()
        ))
}
```

Add a specific `DoctorProfileNotFoundException` handler returning `ResponseEntity.status(404).body(BaseResponse.error(..., 404))`. Ensure existing access-denied and unreadable-body handlers continue to return real HTTP 403/400.

- [ ] **Step 4: Add explicit security matchers**

In `SecurityConfig.kt`, add authenticated GET/PUT matchers for `/api/management/doctor-profile` before broader rules. Do not authorize by token role alone; keep real-time ACTIVE DOCTOR enforcement in the service.

- [ ] **Step 5: Run controller and service tests and verify GREEN**

```powershell
.\gradlew.bat test --tests com.joysong.server.doctor.controller.DoctorProfileControllerTest --tests com.joysong.server.doctor.service.DoctorProfileServiceTest
```

Expected: PASS with HTTP status and body-code assertions green.

- [ ] **Step 6: Commit the HTTP slice**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/doctor/controller/DoctorProfileController.kt joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt joysong-server/src/main/kotlin/com/joysong/server/common/GlobalExceptionHandler.kt joysong-server/src/test/kotlin/com/joysong/server/doctor/controller/DoctorProfileControllerTest.kt
git commit -m "feat: expose doctor self profile API"
```

---

### Task 3: Normative API documentation and business map

**Files:**
- Modify: `doc/用户端API文档.md`
- Modify: `docs/FLUTTER_API_CONTRACT.md`
- Modify: `docs/CORE_ROLES_BUSINESS_SWIMLANE.puml`

**Interfaces:**
- Consumes: Task 2 exact HTTP contract.
- Produces: human-readable source of truth for backend and Flutter.

- [ ] **Step 1: Update the detailed API document**

Add a dedicated “已认证医生本人档案” section containing:

- authentication and ACTIVE DOCTOR preconditions;
- complete request/response JSON copied from the approved design;
- the nine editable fields and exact blank/omitted/null rules;
- read-only field table;
- HTTP/body 400, 401, 403, 404 behavior;
- warning that `credentials` and `credentialImages` are public doctor-authored display content.

Correct the role matrix so the new GET/PUT entries are `ACTIVE DOCTOR（本人）`, while legacy `/api/admin/doctors` rows retain their actual admin compatibility semantics.

- [ ] **Step 2: Update Flutter contract and swimlane**

Replace the professional-center doctor profile rows with:

```markdown
| 医生本人档案 | `GET /management/doctor-profile`、`PUT /management/doctor-profile` |
```

In the PlantUML doctor lane replace `GET /api/admin/doctors` and `PUT /api/admin/doctors/{id}` with the new routes; keep endpoint totals accurate after counting unique routes.

- [ ] **Step 3: Check documentation consistency**

```powershell
$paths = @('doc/用户端API文档.md','docs/FLUTTER_API_CONTRACT.md','docs/CORE_ROLES_BUSINESS_SWIMLANE.puml')
Select-String -Path $paths -Pattern '/api/management/doctor-profile|/management/doctor-profile'
Select-String -Path $paths -Pattern '资质保险箱|查资质|平台已核验'
git diff --check -- $paths
```

Expected: new routes appear in all three sources; prohibited phrases appear only in explicit “must not display” guidance; no whitespace errors.

- [ ] **Step 4: Commit documentation**

```powershell
git add -- doc/用户端API文档.md docs/FLUTTER_API_CONTRACT.md docs/CORE_ROLES_BUSINESS_SWIMLANE.puml
git commit -m "docs: document doctor self profile API"
```

---

### Task 4: Flutter single-resource VO and repository migration

**Files:**
- Modify: `joysong-flutter/lib/features/identity/domain/identity_models.dart`
- Modify: `joysong-flutter/lib/features/identity/domain/identity_repository.dart`
- Modify: `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`
- Modify: `joysong-flutter/test/features/identity/identity_models_controller_test.dart`

**Interfaces:**
- Consumes: Task 2 response/request JSON.
- Produces: `Future<DoctorSelfProfile> loadDoctorSelfProfile()`, `Future<DoctorSelfProfile> updateDoctorSelfProfile(DoctorSelfProfileUpdate update)`.

- [ ] **Step 1: Write failing model and repository-contract tests**

Add tests proving a full response parses platform fields and the update JSON contains exactly nine keys:

```dart
test('DoctorSelfProfileUpdate serializes only nine editable fields', () {
  const update = DoctorSelfProfileUpdate(
    name: '王医生', title: '', bio: '', avatar: '', contactPhone: '',
    specialties: '', credentials: '', credentialImages: '',
    certificationTags: '',
  );

  expect(update.toJson().keys.toSet(), {
    'name', 'title', 'bio', 'avatar', 'contactPhone', 'specialties',
    'credentials', 'credentialImages', 'certificationTags',
  });
  expect(update.toJson(), isNot(contains('id')));
  expect(update.toJson(), isNot(contains('userId')));
});
```

Update the test fake to expose single-resource methods rather than list-based methods.

- [ ] **Step 2: Run the focused Dart test and verify RED**

From `joysong-flutter`:

```powershell
flutter test test/features/identity/identity_models_controller_test.dart
```

Expected: compilation failure because `DoctorSelfProfile` and methods do not exist.

- [ ] **Step 3: Implement VO and repository migration**

Create immutable models with required nine update fields and read-only response properties. `DoctorSelfProfile.toUpdate()` must copy all editable fields, including empty strings. Replace:

```dart
Future<List<ManagedDoctorProfile>> listManagedDoctorProfiles();
Future<ManagedDoctorProfile> updateManagedDoctorProfile(...);
```

with:

```dart
Future<DoctorSelfProfile> loadDoctorSelfProfile();
Future<DoctorSelfProfile> updateDoctorSelfProfile(
  DoctorSelfProfileUpdate update,
);
```

Repository implementation must call `/management/doctor-profile` for both GET and PUT and throw `FormatException('医生档案响应为空')` for null data.

- [ ] **Step 4: Run the focused Dart test and verify GREEN**

Run the same Flutter test once. Expected: PASS.

- [ ] **Step 5: Commit the VO/repository slice**

```powershell
git add -- joysong-flutter/lib/features/identity/domain/identity_models.dart joysong-flutter/lib/features/identity/domain/identity_repository.dart joysong-flutter/lib/features/identity/data/identity_repository_impl.dart joysong-flutter/test/features/identity/identity_models_controller_test.dart
git commit -m "refactor: use doctor self profile contract"
```

---

### Task 5: Flutter profile page, upload injection, and copy correction

**Files:**
- Modify: `joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart`
- Modify: `joysong-flutter/lib/features/profile/presentation/profile_page.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/doctor_detail_view.dart`
- Create: `joysong-flutter/test/features/identity/doctor_self_profile_page_test.dart`

**Interfaces:**
- Consumes: Task 4 repository methods; existing `AppFilePicker`, `PublicMediaUploader`, and `/upload` behavior.
- Produces: a page that edits all nine fields and submits a complete `DoctorSelfProfileUpdate`.

- [ ] **Step 1: Write failing widget tests**

Create a recording fake repository and injected image uploader. Cover one behavior per test:

```dart
testWidgets('saving submits all nine editable fields including blanks', (tester) async {
  final repository = RecordingIdentityRepository(profile: profileFixture());
  await tester.pumpWidget(testApp(ManagedDoctorProfilePage(
    repository: repository,
    pickAndUploadImage: (_) async => null,
  )));
  await tester.pumpAndSettle();

  await tester.enterText(find.byKey(const Key('doctor-name')), '王医生');
  await tester.enterText(find.byKey(const Key('doctor-title')), '');
  await tester.tap(find.text('保存'));
  await tester.pumpAndSettle();

  expect(repository.lastUpdate?.name, '王医生');
  expect(repository.lastUpdate?.title, '');
  expect(repository.lastUpdate?.credentialImages, profileFixture().credentialImages);
});
```

Add tests for single-resource loading, avatar upload replacement, credential-image add/remove, upload failure state, and absence of `资质保险箱`, `查资质`, and `平台已核验`.

- [ ] **Step 2: Run the page test and verify RED**

```powershell
flutter test test/features/identity/doctor_self_profile_page_test.dart
```

Expected: compilation/test failure because the page still loads a list and has no upload injection or stable field keys.

- [ ] **Step 3: Implement the page migration**

Change load to `repository.loadDoctorSelfProfile()`. Maintain avatar URL and credential-image URL list in state. Add stable keys for testability. Define a narrow callback:

```dart
typedef DoctorProfileImageUploader = Future<String?> Function(
  DoctorProfileImageKind kind,
);

enum DoctorProfileImageKind { avatar, credential }
```

Inject the callback from the professional-center composition layer using the existing picker/public uploader. Disable save while upload/save is in progress. On save construct all nine fields; join URL/tag/specialty lists with commas only at the VO boundary.

- [ ] **Step 4: Correct public doctor-detail wording**

Replace “资质保险箱/查资质” with “医生上传的证书图片/展示材料” and remove platform-verification implications. Do not alter the actual `isVerified` platform badge semantics.

- [ ] **Step 5: Run focused widget tests and verify GREEN**

```powershell
flutter test test/features/identity/doctor_self_profile_page_test.dart test/features/identity/identity_models_controller_test.dart
```

Expected: PASS.

- [ ] **Step 6: Commit the page slice**

```powershell
git add -- joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart joysong-flutter/lib/features/profile/presentation/profile_page.dart joysong-flutter/lib/features/discover/presentation/doctor_detail_view.dart joysong-flutter/test/features/identity/doctor_self_profile_page_test.dart
git commit -m "feat: adapt doctor self profile page"
```

---

### Task 6: Verification and completion evidence for the doctor slice

**Files:**
- Verify only; no planned production changes.

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: test and static-analysis evidence against the approved design.

- [ ] **Step 1: Invoke verification-before-completion skill**

Read and follow `superpowers:verification-before-completion` before making any success claim.

- [ ] **Step 2: Run backend focused tests once**

```powershell
$env:GRADLE_USER_HOME = (Join-Path $PWD '.tmp\gradle-user-home-codex')
.\gradlew.bat test --tests com.joysong.server.doctor.service.DoctorProfileServiceTest --tests com.joysong.server.doctor.controller.DoctorProfileControllerTest
```

Run from `joysong-server`. Expected: PASS. If a case fails, rerun only that test method/class after fixing it.

- [ ] **Step 3: Run Flutter focused tests and analysis once**

```powershell
flutter test test/features/identity/doctor_self_profile_page_test.dart test/features/identity/identity_models_controller_test.dart
flutter analyze
```

Expected: PASS and no analyzer issues introduced by the slice.

- [ ] **Step 4: Run at most one broader suite per stack if time permits**

Backend: `./gradlew.bat test`. Flutter: `flutter test`. Stop a suite that exceeds ten minutes and report progress/slowest visible test rather than rerunning.

- [ ] **Step 5: Audit the approved design requirement by requirement**

Inspect current files and outputs for routes, ACTIVE DOCTOR enforcement, nine-field complete PUT, blank optional fields, immutable platform fields, single-object Flutter VO, upload/removal UI, corrected copy, three documentation sources, and focused test coverage. Treat missing evidence as incomplete work.

- [ ] **Step 6: Prepare next-slice handoff**

Keep the overall professional-center goal active. Record that the next design slice must cover the remaining doctor operations, then institution legal-representative operations, then consultant operations, each using the same documentation → VO → page workflow.
