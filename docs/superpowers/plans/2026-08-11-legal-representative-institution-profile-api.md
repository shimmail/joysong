# Legal Representative Institution Profile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a three-endpoint institution-profile API for active legal representatives, document the exact database-backed contract, and migrate Flutter VO, repository, and pages away from the legacy admin institution entity contract.

**Architecture:** Add a dedicated `/api/management/institutions` controller and service that derive the legal representative's institution scope from `ManagementAccessService`. Use DTOs and a repository-level 13-field targeted update so professional users can never overwrite platform trust or statistics. Flutter consumes summary, detail, and full-update models with JSON arrays for multi-value fields and uses the existing public upload pipeline.

**Tech Stack:** Kotlin, Spring Boot, Spring Security, Spring Data JPA, Jackson, JUnit 5, MockK, Flutter/Dart, existing `ApiClient`, existing identity repository/controller patterns.

## Global Constraints

- Endpoints are exactly `GET /api/management/institutions`, `GET /api/management/institutions/{institutionId}`, and `PUT /api/management/institutions/{institutionId}`.
- Access requires an ACTIVE `INSTITUTION_LEGAL_REPRESENTATIVE` role plus an APPROVED legal-representative membership for the target institution.
- PUT is full replacement: all 13 editable keys must be present; `name` must be nonblank; other strings may be empty; arrays may be empty; `establishedYear` must be present and may be null.
- `images`, `credentialImages`, `specialties`, and `tags` are JSON arrays at the API boundary and comma-separated strings only inside persistence.
- `rating`, `reviewCount`, `isVerified`, `certificationTime`, all counters, IDs, timestamps, membership data, and deletion data are read-only and absent from the update DTO.
- Unknown JSON fields and attempted platform fields return HTTP/body 400.
- Updates use a targeted SQL/JPQL update and fresh read; never copy/save a full cached `InstitutionEntity`.
- No database migration is required. Never connect tests to a shared development database.
- Reuse existing upload and localization infrastructure. Do not create duplicate network clients or hard-code a second localization system.
- Use the smallest relevant tests; do not repeat a passed command and do not run unrelated broad suites.

---

### Task 1: Institution Profile Domain and Targeted Persistence

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/institution/service/ManagedInstitutionProfileService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/institution/repository/InstitutionRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/institution/service/InstitutionService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/ManagedInstitutionProfileServiceTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/ManagedInstitutionProfilePersistenceTest.kt`

**Interfaces:**
- Consumes: `ManagementActor`, `ManagementAccessService.requireInstitutionManaged(actor, institutionId)`, `InstitutionEntity`.
- Produces: `ManagedInstitutionSummary`, `ManagedInstitutionProfile`, `ManagedInstitutionProfileUpdateCommand`, `ManagedInstitutionProfileService.list(actor)`, `get(actor, institutionId)`, and `update(actor, institutionId, command)`.

- [ ] **Step 1: Write the service boundary tests**

Add focused tests proving that list filters to `actor.managedInstitutionIds`, get rejects an unmanaged ID, array-backed fields map to lists, `主任机构`-style ordinary text remains unchanged, and a legal representative update produces a full detail view without accepting any platform field in the command type.

Use this command shape in tests:

```kotlin
val command = ManagedInstitutionProfileUpdateCommand(
    name = "示例机构",
    address = "示例路 1 号",
    city = "上海",
    description = "公开介绍",
    coverImage = "cover.jpg",
    images = listOf("one.jpg", "two.jpg"),
    establishedYear = 2012,
    credentials = "公开资质说明",
    credentialImages = listOf("cert.jpg"),
    specialties = listOf("皮肤美容", "注射美容"),
    tags = listOf("夜间门诊"),
    contactPhone = "021-12345678",
    businessHours = "09:00-21:00"
)
```

- [ ] **Step 2: Run the service test to verify RED**

Run from `joysong-server`:

```powershell
.\gradlew.bat test --tests com.joysong.server.institution.service.ManagedInstitutionProfileServiceTest
```

Expected: compilation failure because the managed institution profile service and DTOs do not exist.

- [ ] **Step 3: Implement DTO mapping and service authorization**

Define immutable DTOs with these exact field categories:

```kotlin
data class ManagedInstitutionSummary(
    val id: String,
    val name: String,
    val city: String,
    val address: String,
    val coverImage: String,
    val isVerified: Boolean,
    val rating: BigDecimal,
    val reviewCount: Int,
    val projectCount: Int,
    val doctorCount: Int
)

data class ManagedInstitutionProfileUpdateCommand(
    val name: String,
    val address: String,
    val city: String,
    val description: String,
    val coverImage: String,
    val images: List<String>,
    val establishedYear: Int?,
    val credentials: String,
    val credentialImages: List<String>,
    val specialties: List<String>,
    val tags: List<String>,
    val contactPhone: String,
    val businessHours: String
)
```

`ManagedInstitutionProfile` contains the 13 editable values plus `id`, `rating`, `reviewCount`, `isVerified`, `certificationTime`, `projectCount`, `doctorCount`, `consultationCount`, `userCount`, `caseCount`, `createdAt`, and `updatedAt`.

Normalize strings with `trim()`. Normalize lists with trim, empty removal, and stable de-duplication. Reject names that become blank, list items containing commas, and years outside `1800..Year.now().value`.

- [ ] **Step 4: Write the isolated persistence regression test**

Use the project's existing Testcontainers pattern. Derive the database name from the worktree and print host/database before Spring/Flyway initialization. The test must:

1. Insert a user, institution, ACTIVE legal-representative role, and APPROVED membership.
2. Load the institution through the service.
3. Update platform fields through a separate repository/entity-manager operation.
4. Call the professional update.
5. Assert all 13 public fields changed and the newer platform fields remained unchanged.

- [ ] **Step 5: Run the persistence test to verify RED**

```powershell
.\gradlew.bat test --tests com.joysong.server.institution.service.ManagedInstitutionProfilePersistenceTest
```

Expected: FAIL because the targeted update method does not exist or a full entity save overwrites the newer platform values.

- [ ] **Step 6: Implement the targeted update**

Add a repository method with only the 13 editable columns:

```kotlin
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
    update InstitutionEntity i set
        i.name = :name,
        i.address = :address,
        i.city = :city,
        i.description = :description,
        i.coverImage = :coverImage,
        i.images = :images,
        i.establishedYear = :establishedYear,
        i.credentials = :credentials,
        i.credentialImages = :credentialImages,
        i.specialties = :specialties,
        i.tags = :tags,
        i.contactPhone = :contactPhone,
        i.businessHours = :businessHours
    where i.id = :id and i.deletedAt is null
""")
fun updateManagedProfile(
    @Param("id") id: String,
    @Param("name") name: String,
    @Param("address") address: String,
    @Param("city") city: String,
    @Param("description") description: String,
    @Param("coverImage") coverImage: String,
    @Param("images") images: String,
    @Param("establishedYear") establishedYear: Int?,
    @Param("credentials") credentials: String,
    @Param("credentialImages") credentialImages: String,
    @Param("specialties") specialties: String,
    @Param("tags") tags: String,
    @Param("contactPhone") contactPhone: String,
    @Param("businessHours") businessHours: String
): Int
```

Run authorization before the update, require an affected-row count of one, evict institution/discover caches after success, and re-read from persistence for the response.

- [ ] **Step 7: Run both focused tests to verify GREEN**

Run each previously failing class once. Expected: all tests in both classes pass.

- [ ] **Step 8: Commit Task 1**

Stage only the service, repository, institution service cache hook, and two tests. Commit:

```text
feat: add managed institution profile service
```

---

### Task 2: Management HTTP Contract, Validation, and Security

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/institution/controller/ManagedInstitutionProfileController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/common/GlobalExceptionHandler.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminInstitutionController.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/institution/controller/ManagedInstitutionProfileControllerTest.kt`

**Interfaces:**
- Consumes: Task 1 service and DTOs.
- Produces: the three exact management routes and `ManagedInstitutionProfileUpdateRequest.toCommand()`.

- [ ] **Step 1: Write controller/security contract tests**

Cover only distinct boundaries:

- unauthenticated GET/PUT returns HTTP/body 401;
- ACTIVE legal representative lists managed summaries, gets own detail, and updates own institution;
- legal representative receives 403 for an unmanaged institution;
- doctor, consultant, ordinary user, pending/revoked legal representative, and admin receive 403;
- PUT rejects missing keys, unknown keys, platform fields, null string/list values, blank name, comma-bearing list items, and invalid year;
- `establishedYear: null` succeeds;
- unexpected runtime exceptions return HTTP/body 500 with a generic message.

Use raw JSON for presence/unknown-field tests so the test proves the wire contract rather than Kotlin construction.

- [ ] **Step 2: Run the controller test to verify RED**

```powershell
.\gradlew.bat test --tests com.joysong.server.institution.controller.ManagedInstitutionProfileControllerTest
```

Expected: 404/no handler or DTO validation failures because the management controller does not exist.

- [ ] **Step 3: Implement strict request decoding and routes**

Create:

```kotlin
@RestController
@RequestMapping("/api/management/institutions")
class ManagedInstitutionProfileController(
    private val managedInstitutionProfileService: ManagedInstitutionProfileService,
    private val managementAccessService: ManagementAccessService
) {
    @GetMapping fun list(authentication: Authentication): BaseResponse<List<ManagedInstitutionSummary>>
    @GetMapping("/{institutionId}") fun get(authentication: Authentication, @PathVariable institutionId: String): BaseResponse<ManagedInstitutionProfile>
    @PutMapping("/{institutionId}") fun update(authentication: Authentication, @PathVariable institutionId: String, @RequestBody body: JsonNode): BaseResponse<ManagedInstitutionProfile>
}
```

At the controller boundary, require the exact 13-key set. Parse strings, arrays, and the required nullable `establishedYear` explicitly from `JsonNode`; reject extra or missing keys before creating the command. This preserves the difference between a missing year and an explicit null without introducing a wrapper abstraction.

- [ ] **Step 4: Configure route and error semantics**

Require authentication in `SecurityConfig`; keep role/object authorization in the service. Extend the management-route exception handling to return real HTTP 400/401/403/404/500 values and never expose internal runtime messages.

Remove professional-write support from the old `PUT /api/admin/institutions/{id}` path: it must require ADMIN after Flutter migration. Do not remove platform administrator CRUD.

- [ ] **Step 5: Run the controller test to verify GREEN**

Run the same controller class once. Expected: all focused cases pass.

- [ ] **Step 6: Commit Task 2**

Commit only HTTP/security files and the focused controller test:

```text
feat: expose legal representative institution profiles
```

---

### Task 3: Normative API and Swimlane Documentation

**Files:**
- Modify: `doc/用户端API文档.md`
- Modify: `docs/FLUTTER_API_CONTRACT.md`
- Modify: `docs/CORE_ROLES_BUSINESS_SWIMLANE.puml`

**Interfaces:**
- Consumes: Tasks 1–2 exact paths, DTO fields, and errors.
- Produces: the authoritative backend/Flutter contract for Task 4.

- [ ] **Step 1: Replace the legal representative institution-profile contract**

Document all three routes, Bearer authentication, object authorization, 13 editable fields, nullable-but-required year, four JSON arrays, full request/response examples, platform read-only fields, and HTTP/body errors.

- [ ] **Step 2: Correct legacy and role-boundary wording**

State that old `/api/admin/institutions/**` professional profile usage is deprecated/removed while administrator CRUD remains. Clarify that legal representatives review doctor/consultant relationships and project requests; they do not directly edit doctor profiles or bypass project workflows.

- [ ] **Step 3: Update the swimlane and recount routes**

Replace the old legal-representative profile list/detail/PUT paths with the three management paths. Recount explicit route patterns from the diagram and replace the unsupported “15 endpoints” note with the proven count and counting convention.

- [ ] **Step 4: Verify documentation mechanically**

Run `git diff --check` for the three files and text searches proving:

- all three new routes occur;
- the legal representative lane no longer uses old admin institution profile routes;
- `certificationTime`, `userCount`, and `caseCount` are documented as read-only;
- request arrays and `establishedYear` semantics are present.

- [ ] **Step 5: Commit Task 3**

```text
docs: specify legal representative institution profiles
```

---

### Task 4: Flutter VO and Repository Migration

**Files:**
- Modify: `joysong-flutter/lib/features/identity/domain/identity_models.dart`
- Modify: `joysong-flutter/lib/features/identity/domain/identity_repository.dart`
- Modify: `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/identity_controller.dart`
- Test: `joysong-flutter/test/features/identity/managed_institution_profile_contract_test.dart`
- Test: `joysong-flutter/test/features/identity/identity_models_controller_test.dart`

**Interfaces:**
- Consumes: Task 3 JSON contract.
- Produces: `ManagedInstitutionSummary`, `ManagedInstitutionProfile`, `ManagedInstitutionProfileUpdate`, and repository methods `listManagedInstitutions()`, `loadManagedInstitution(String id)`, `updateManagedInstitution(String id, ManagedInstitutionProfileUpdate update)`.

- [ ] **Step 1: Write the contract test**

Assert that summary/detail JSON parses all fields, arrays remain `List<String>`, nullable year is preserved, and update JSON has exactly these 13 keys:

```dart
{
  'name', 'address', 'city', 'description', 'coverImage', 'images',
  'establishedYear', 'credentials', 'credentialImages', 'specialties',
  'tags', 'contactPhone', 'businessHours'
}
```

Assert no platform field, `id`, or user/membership field appears in the update JSON. Assert repository paths are the three `/management/institutions` routes.

- [ ] **Step 2: Run the contract test to verify RED**

```powershell
flutter test test/features/identity/managed_institution_profile_contract_test.dart
```

Expected: compilation or assertion failures because the new models/methods do not exist.

- [ ] **Step 3: Implement models and repository methods**

Replace the old institution profile/draft contract. Do not leave compatibility aliases after all call sites migrate. Keep `ManagedInstitutionProject` types unchanged.

Use exact signatures:

```dart
Future<List<ManagedInstitutionSummary>> listManagedInstitutions();
Future<ManagedInstitutionProfile> loadManagedInstitution(String id);
Future<ManagedInstitutionProfile> updateManagedInstitution(
  String id,
  ManagedInstitutionProfileUpdate update,
);
```

- [ ] **Step 4: Update the controller state model**

The controller first loads summaries, then loads the selected full profile. It exposes distinct loading/empty/failure/ready/saving states, prevents concurrent saves, and replaces the current profile with the server response after save.

- [ ] **Step 5: Run focused Flutter tests to verify GREEN**

Run the new contract test and only the existing controller test file affected by the signature change. Expected: both pass.

- [ ] **Step 6: Commit Task 4**

```text
refactor: use managed institution profile contract
```

---

### Task 5: Flutter Institution Profile Pages and Uploads

**Files:**
- Modify: `joysong-flutter/lib/features/identity/presentation/identity_pages.dart`
- Modify: `joysong-flutter/lib/features/profile/presentation/profile_page.dart`
- Modify: `joysong-flutter/lib/features/social/domain/social_models.dart`
- Modify: `joysong-flutter/lib/features/social/data/social_repository_impl.dart`
- Test: `joysong-flutter/test/features/identity/managed_institution_profile_page_test.dart`

**Interfaces:**
- Consumes: Task 4 models/repository/controller and existing public media uploader.
- Produces: list/selection/detail/edit UI with `PublicMediaPurpose.institutionProfile` mapped to the existing institution public upload directory.

- [ ] **Step 1: Write three page behavior tests**

Keep the new widget test file to exactly three tests:

1. one managed institution loads its detail directly and platform fields are visible but not editable;
2. multiple summaries require selection and save submits all 13 editable fields, including arrays and explicit null year;
3. image add/remove, saving-disabled state, failure message, and retry work without post-dispose callbacks.

- [ ] **Step 2: Run the page test to verify RED**

```powershell
flutter test test/features/identity/managed_institution_profile_page_test.dart
```

Expected: failures because the page still uses the legacy list-of-full-entities contract and platform edit fields.

- [ ] **Step 3: Implement list/detail/edit flow**

Update the existing `ManagedInstitutionProfilesPage` rather than creating a parallel route. Remove editable controls for `certificationTime`, `userCount`, `caseCount`, and every other platform field. Display trust/statistics in read-only cards. Preserve mounted checks after every awaited load, upload, and save.

- [ ] **Step 4: Wire public uploads**

Use the existing uploader injected from `ProfilePage`. Add only the purpose mapping needed for institution cover/environment/credential images; do not add a new client. Maintain separate lists for `images` and `credentialImages`, and a single `coverImage` URL.

- [ ] **Step 5: Remove the legacy Flutter bridge**

Search the Flutter tree and remove old institution profile calls to `/admin/institutions`, `ManagedInstitutionProfileDraft`, and obsolete profile methods. Do not remove admin project APIs or unrelated administrator paths.

- [ ] **Step 6: Run the page test and targeted analyze**

Run the three-test file once after implementation. Then run `flutter analyze` only on the touched Dart files and their two focused test files. Expected: tests pass and analyzer reports zero issues.

- [ ] **Step 7: Commit Task 5**

```text
feat: adapt legal representative institution profile page
```

---

### Task 6: Scoped Verification and Review

**Files:**
- Create only as ignored scratch: `.superpowers/sdd/2026-08-11-legal-representative-institution-profile/task-6-report.md`
- Do not commit scratch reports.

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: evidence that the slice is ready for final branch integration.

- [ ] **Step 1: Run one focused backend command**

Run the three new backend test classes in one Gradle invocation. If the persistence test uses MySQL, print the isolated worktree host and `myapp_<WORKTREE_ID>` database name before Flyway starts. Record exact test counts and failures.

- [ ] **Step 2: Run one focused Flutter command**

Run only the contract/controller/page files touched by Tasks 4–5 in one `flutter test` invocation. Record exact counts.

- [ ] **Step 3: Run targeted analyze once**

Analyze only touched Dart production and test files. Record the issue count.

- [ ] **Step 4: Audit requirements**

Prove with file/line and command evidence:

1. three exact management routes;
2. ACTIVE legal representative plus APPROVED membership authorization;
3. unmanaged institution denial;
4. exact 13-key full PUT and nullable-but-required year;
5. four JSON arrays at the boundary;
6. targeted update preserves every platform field;
7. real HTTP/body error status and safe 500;
8. Flutter uses no legacy institution profile admin routes;
9. platform fields are read-only in UI and absent from update JSON;
10. upload/load/save/error/dispose behavior;
11. three normative documents agree;
12. no migration, generated plugin, scratch report, or unrelated file in the branch diff.

- [ ] **Step 5: Request whole-slice code review**

Review the branch against the design and this plan. Fix Critical/Important findings with the smallest TDD wave; do not add speculative tests.

- [ ] **Step 6: Finish the branch**

After clean review and fresh evidence, use `superpowers:finishing-a-development-branch` and present the integration options. Preserve the worktree until the user chooses.
