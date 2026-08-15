# Doctor Project Application Full VO Implementation Plan

> **For Codex:** REQUIRED SUB-SKILLS: use superpowers:test-driven-development for every production change, superpowers:subagent-driven-development for independent backend/Flutter/admin tasks, and superpowers:verification-before-completion before claiming completion.

**Goal:** Let an authenticated doctor submit complete platform-project or institution-project creation applications; let only the correct reviewer approve or reject them; and make institution approval atomically create the institution project, applicant binding, and applicant split configuration.

**Authoritative design:** docs/superpowers/specs/2026-08-15-doctor-project-application-full-vo-design.md

**Worktree:** D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo

**Branch:** codex/doctor-project-application-full-vo

**Architecture:** Keep the existing professional-project request aggregate and endpoints, but replace its minimal payload with immutable full snapshots. Store wire arrays as JSON in the request ledger and convert them at the approval boundary to the legacy comma-separated target-table format. Keep direct admin creation VOs and the existing JOIN/PROFILE_UPDATE flows unchanged. Revalidate current authority, membership, project state, and split policy inside the approval transaction.

**Tech stack:** Kotlin/Spring Boot/JdbcTemplate/Flyway/MySQL, Flutter/Dart, React/TypeScript/Ant Design/Vitest.

## Fixed contracts

The platform request body has exactly these 13 keys:

~~~text
name, category, description, referencePrice, currency, slogan, salesCount,
coverImage, images, detailContent, tags, categoryTags, notes
~~~

The institution request path carries institutionId. Its body has exactly these 18 keys:

~~~text
projectId, name, category, description, tags, slogan, detailContent, price,
originalPrice, currency, coverImage, images, salesCount, isActive,
consultationFee, commissionRate, institutionRate, notes
~~~

Neither request accepts rating, reviewCount, doctorId, doctorIds, doctorBindings, platformRate, or doctorRate. The applicant doctor comes only from the authenticated actor. Creation-request review accepts only APPROVED and REJECTED. REJECTED requires a non-blank note.

## Local verification setup

Use the checked local Gradle 8.9 distribution directly and force offline mode. Do not invoke the Wrapper and do not download another distribution. Every command block below runs in a fresh PowerShell process, so it repeats all paths it needs:

~~~powershell
$worktree = 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo'
$gradle = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat'
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex'
& $gradle --offline -p "$worktree\joysong-server" --version
~~~

The baseline command is already green on the unmodified branch:

~~~powershell
$worktree = 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo'
$gradle = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat'
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex'
& $gradle --offline -p "$worktree\joysong-server" test --tests com.joysong.server.project.service.ProfessionalProjectRequestServiceTest
~~~

Expected baseline: BUILD SUCCESSFUL. The first run observed 5m 2s because it compiled the full backend test source set.

Derive the database-safe worktree identifier at runtime from the current worktree directory. When `user.dir` is the `joysong-server` module, read its parent directory name, sanitize non-alphanumeric runs to `_`, lowercase it, and prefix it with `worktree_`. In this checkout that resolves to `WORKTREE_ID=worktree_doctor_project_application_full_vo`. Every isolated Testcontainers instance must use the exact database name `myapp_<WORKTREE_ID>`; separate containers provide test isolation, so do not append scenario suffixes. Centralize that derivation in one test helper so the migration, baseline, and persistence tests cannot drift. Every resolved name must equal the expected derived name and start with:

~~~text
myapp_worktree_doctor_project_application_full_vo
~~~

Validate and print the resolved host and database name before the **first** Flyway migration, and print it again before a later migration phase when a test migrates in two steps. Never connect these tests to the shared development database and never drop/reset another database.

## Task 1: Lock the V28 request-ledger structure with migration tests

**Files:**

- Create joysong-server/src/main/resources/db/migration/V28__expand_professional_project_requests.sql
- Create joysong-server/src/test/kotlin/com/joysong/server/support/WorktreeTestDatabase.kt
- Create joysong-server/src/test/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestMigrationTest.kt
- Modify joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt

### Step 1: Write the failing migration tests

Add a MySQL integration test that:

1. derives and validates the exact name `myapp_<WORKTREE_ID>`, starts its isolated container, then prints the resolved host/database before any Flyway call;
2. migrates the isolated database through V27;
3. inserts one valid legacy PLATFORM request;
4. prints the validated host/database again immediately before migrating through V28;
5. migrates through V28;
6. proves the legacy row remains and its safe default JSON arrays, currency, reference price, slogan/cover, and sales count are readable through JDBC mapping;
7. proves service_content is gone and price exists;
8. proves every new snapshot/split column exists;
9. proves tags, images, and category_tags are JSON columns;
10. rejects negative money/counts, out-of-range rates, and invalid PLATFORM/INSTITUTION shapes.

Update the baseline history assertion to:

~~~kotlin
listOf(
    Triple("26", "SQL_BASELINE", "B26__current_schema.sql"),
    Triple("27", "SQL", "V27__add_consultant_institution_change_requests.sql"),
    Triple("28", "SQL", "V28__expand_professional_project_requests.sql"),
)
~~~

Also replace the baseline test's older fixed database name with the shared runtime derivation. Its exact resolved value in this checkout is `myapp_worktree_doctor_project_application_full_vo`.

Run:

~~~powershell
$worktree = 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo'
$gradle = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat'
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex'
& $gradle --offline -p "$worktree\joysong-server" mysqlIntegrationTest --tests com.joysong.server.project.service.ProfessionalProjectRequestMigrationTest --tests com.joysong.server.migration.BaselineMigrationIntegrationTest
~~~

Expected failure: V28 is missing and the Flyway history/column assertions fail.

### Step 2: Implement DDL-only V28

Do not edit B26 or earlier migrations. In V28:

1. drop chk_project_requests_shape, chk_project_requests_price, and chk_project_requests_review_note;
2. drop service_content;
3. rename price_suggestion to price;
4. add shared tags, slogan, detail_content, currency, cover_image, images, sales_count;
5. add platform-only reference_price and category_tags;
6. add institution-only original_price, is_active, consultation_fee, commission_rate, institution_rate;
7. rebuild amount/count/rate/review-note/request-shape checks;
8. retain CHANGES_REQUESTED in the database status check only for legacy readability.

Use safe defaults for existing PLATFORM rows: reference_price=0, currency=USD, slogan/cover_image empty, sales_count=0, and empty JSON arrays. Use JSON request-ledger columns so wire arrays remain arrays:

~~~sql
tags JSON DEFAULT (JSON_ARRAY()),
images JSON DEFAULT (JSON_ARRAY()),
category_tags JSON DEFAULT (JSON_ARRAY())
~~~

The new request-shape check must enforce:

- PLATFORM has no institution/project/price/split fields and has non-null name/category/description/reference_price.
- INSTITUTION has institution/project/price/is_active and all three submitted split values.
- Institution override content may be null or empty for inheritance.
- No request-ledger rating, review_count, doctor selection, platform_rate, or doctor_rate columns are added.

The migration must contain no DELETE, TRUNCATE, data cleanup, or request-row rewrite. The operator will clear old real institution-project requests manually. Enforce this with the behavioral legacy-row/default assertions plus task diff review; do not add a brittle test that merely searches SQL source text.

### Step 3: Run the focused migration tests

Run the command from Step 1 once.

Expected: both classes pass, and output includes the isolated host/database name before migration.

### Step 4: Commit

~~~powershell
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' add joysong-server/src/main/resources/db/migration/V28__expand_professional_project_requests.sql joysong-server/src/test/kotlin/com/joysong/server/support/WorktreeTestDatabase.kt joysong-server/src/test/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestMigrationTest.kt joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' commit -m "feat: expand professional project request schema"
~~~

## Task 2: Recompose initializer-owned application fixtures

**Files:**

- Modify joysong-server/src/main/kotlin/com/joysong/server/common/initializer/SeedIds.kt
- Modify joysong-server/src/main/kotlin/com/joysong/server/common/initializer/IdentityDataInitializer.kt
- Create joysong-server/src/test/kotlin/com/joysong/server/common/initializer/IdentityDataInitializerTest.kt

The clean branch has no old professional_project_requests fixture, so removal is intentionally a no-op. Do not compensate by deleting unrelated PROFILE_UPDATE or split-proposal fixtures.

### Step 1: Write a failing initializer test

Capture JdbcTemplate writes and assert observable fixture behavior:

- exactly one complete PENDING PLATFORM fixture is inserted;
- exactly one complete PENDING INSTITUTION fixture is inserted;
- neither SQL/payload carries rating/review count, doctor selection, platformRate, or doctorRate;
- the institution row stores applicant doctor_id directly and all three editable split values;
- the independent PROFILE_UPDATE row includes its complete proposal and current snapshot instead of being silently ignored by B26 checks.

Run:

~~~powershell
$worktree = 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo'
$gradle = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat'
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex'
& $gradle --offline -p "$worktree\joysong-server" test --tests com.joysong.server.common.initializer.IdentityDataInitializerTest
~~~

Expected failure: the two new request fixtures do not exist and the old PROFILE_UPDATE fixture is incomplete.

### Step 2: Add only the necessary fixed IDs

Keep PROJECT_CHANGE_REQUEST_ID, SPLIT_CONFIG_ID, and SPLIT_PROPOSAL_ID. Add:

~~~kotlin
const val PLATFORM_PROJECT_REQUEST_ID =
    "95000001-0000-4000-8000-000000000004"
const val INSTITUTION_PROJECT_REQUEST_ID =
    "95000001-0000-4000-8000-000000000005"
~~~

Use distinct fixture combinations:

- PLATFORM: DOC_ID_4 with a complete 13-field snapshot;
- INSTITUTION: DOC_ID_3 + INST_ID_2 + PROJ_ID_1, which has a valid doctor relationship, no existing institution project, and a legal representative for review;
- PROFILE_UPDATE: retain DOC_ID_2 + INST_ID_1 + IP_ID_4 but fill every required proposal/current-snapshot column;
- split proposal: retain DOC_ID_1 + IP_ID_1 as the independent split screen fixture.

### Step 3: Implement the replacement seed data

Keep the data in IdentityDataInitializer rather than adding a redundant initializer. Encode array snapshots with the same JSON convention used by ProfessionalProjectRequestService.

Do not add runtime cleanup. Use deterministic upsert/insert-ignore behavior only for the fixed demo rows.

### Step 4: Run the focused initializer test

Expected: PASS.

### Step 5: Commit

~~~powershell
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' add joysong-server/src/main/kotlin/com/joysong/server/common/initializer/SeedIds.kt joysong-server/src/main/kotlin/com/joysong/server/common/initializer/IdentityDataInitializer.kt joysong-server/src/test/kotlin/com/joysong/server/common/initializer/IdentityDataInitializerTest.kt
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' commit -m "testdata: recompose project application fixtures"
~~~

## Task 3: Define strict HTTP VOs, form configuration, and real error statuses

**Files:**

- Modify joysong-server/src/main/kotlin/com/joysong/server/project/controller/ProfessionalProjectRequestController.kt
- Modify joysong-server/src/main/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestService.kt
- Modify joysong-server/src/main/kotlin/com/joysong/server/common/GlobalExceptionHandler.kt
- Modify joysong-server/src/test/kotlin/com/joysong/server/project/controller/ProfessionalProjectRequestControllerTest.kt
- Create joysong-server/src/test/kotlin/com/joysong/server/project/controller/ProfessionalProjectRequestHttpTest.kt

### Step 1: Write failing exact-shape and route tests

Test the exact HTTP business shape and real consumer-visible behavior rather than reflecting private DTO structure:

- the valid 13-key platform JSON reaches submitPlatform;
- the valid 18-key institution JSON reaches submitInstitution, with institutionId only in the path;
- prohibited and arbitrary unknown keys return HTTP 400;
- institution form config returns exactly platformRate;
- review accepts APPROVED/REJECTED, rejects CHANGES_REQUESTED, and rejects blank REJECTED notes;
- the admin controller still delegates platform review, while the management controller delegates institution review;
- unauthenticated or wrong-object authority is exposed as HTTP 403 on these real-status paths;
- missing request/institution/project is exposed as HTTP 404;
- duplicate pending submission, already processed/concurrent review, invalidated relationship, duplicate target, and split-rate drift are exposed as HTTP 409 with the corresponding response code. Controller HTTP tests may stub the service exception to verify the transport mapping; Tasks 4 and 5 must separately exercise the real service conditions that raise each exception.

Use the repository's strict request pattern:

~~~kotlin
@JsonIgnoreProperties(ignoreUnknown = false)
data class DoctorPlatformProjectRequest(
    val name: String = "",
) {
    @JsonIgnore
    val unknownFields: MutableMap<String, Any?> = linkedMapOf()

    @JsonAnySetter
    fun unknown(name: String, value: Any?) {
        unknownFields[name] = value
    }
}
~~~

The controller must require unknownFields.isEmpty() before calling the service.

Run:

~~~powershell
$worktree = 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo'
$gradle = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat'
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex'
& $gradle --offline -p "$worktree\joysong-server" test --tests com.joysong.server.project.controller.ProfessionalProjectRequestControllerTest --tests com.joysong.server.project.controller.ProfessionalProjectRequestHttpTest
~~~

Expected failure: current DTOs are minimal, unknown fields are not rejected, form config is absent, and CHANGES_REQUESTED is accepted.

### Step 2: Replace the minimal input VOs

Keep the DTOs next to the existing service to avoid creating redundant model files.

~~~kotlin
data class DoctorPlatformProjectRequest(
    val name: String = "",
    val category: String = "",
    val description: String = "",
    val referencePrice: BigDecimal = BigDecimal.ZERO,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val slogan: String = "",
    val salesCount: Int = 0,
    val coverImage: String = "",
    val images: List<String> = emptyList(),
    val detailContent: String? = null,
    val tags: List<String> = emptyList(),
    val categoryTags: List<String> = emptyList(),
    val notes: String = "",
)
~~~

~~~kotlin
data class DoctorInstitutionProjectRequest(
    val projectId: String = "",
    val name: String? = null,
    val category: String? = null,
    val description: String? = null,
    val tags: List<String>? = null,
    val slogan: String? = null,
    val detailContent: String? = null,
    val price: BigDecimal = BigDecimal.ZERO,
    val originalPrice: BigDecimal? = null,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val coverImage: String? = null,
    val images: List<String>? = null,
    val salesCount: Int = 0,
    val isActive: Boolean = true,
    val consultationFee: BigDecimal = BigDecimal.ZERO,
    val commissionRate: BigDecimal = BigDecimal.ZERO,
    val institutionRate: BigDecimal = BigDecimal.ZERO,
    val notes: String = "",
)
~~~

Add:

~~~kotlin
data class InstitutionProjectApplicationFormConfig(
    val platformRate: BigDecimal,
)
~~~

Expose GET /api/management/project-requests/institution-form-config, require the current actor to be an authenticated active doctor, and return OrderSplitRatePolicy.currentPlatformRate().

### Step 3: Normalize 400/404/409

Add ProfessionalProjectRequestNotFoundException and ProfessionalProjectRequestConflictException. Map them in GlobalExceptionHandler and add /api/management/project-requests to usesRealHttpErrorStatus().

Use:

- IllegalArgumentException for malformed values and review decisions: 400;
- AccessDeniedException for role/object authority: 403;
- the not-found exception for request/institution/project absence: 404;
- the conflict exception for duplicate pending, already-created targets, stale relationship/rate, processed/concurrent review: 409.

### Step 4: Run the focused HTTP/controller tests

Expected: PASS.

### Step 5: Commit

~~~powershell
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' add joysong-server/src/main/kotlin/com/joysong/server/project/controller/ProfessionalProjectRequestController.kt joysong-server/src/main/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestService.kt joysong-server/src/main/kotlin/com/joysong/server/common/GlobalExceptionHandler.kt joysong-server/src/test/kotlin/com/joysong/server/project/controller/ProfessionalProjectRequestControllerTest.kt joysong-server/src/test/kotlin/com/joysong/server/project/controller/ProfessionalProjectRequestHttpTest.kt
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' commit -m "feat: define strict doctor project application VOs"
~~~

## Task 4: Persist and list complete immutable request snapshots

**Files:**

- Modify joysong-server/src/main/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestService.kt
- Modify joysong-server/src/test/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestServiceTest.kt

### Step 1: Write failing submission/list tests

Cover:

- complete platform snapshot trimming, normalization, and INSERT arguments;
- complete institution override snapshot and split INSERT arguments;
- automatic use of actor.doctorId and absence of any selectable doctor;
- non-negative, two-decimal money constraints;
- non-negative integer salesCount;
- bounded arrays/items and text;
- valid USD/CNY enum parsing;
- institution membership, project existence, target absence, and duplicate-pending behavior;
- ObjectMapper JSON array round-trip;
- list view reconstruction of every snapshot field;
- legacy PLATFORM rows reconstructed with readable defaults for arrays, currency, reference price, slogan/cover, and sales count;
- list visibility is object-scoped: the applicant sees their own requests, the target institution legal representative sees only that institution's requests, a non-target legal representative cannot see or act on them, and an admin sees both request types;
- PLATFORM split is null;
- INSTITUTION has a nested InstitutionProjectSplitView containing consultationFee, commissionRate, institutionRate, current platformRate, and derived doctorRate without persisting the last two values.

Run:

~~~powershell
$worktree = 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo'
$gradle = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat'
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex'
& $gradle --offline -p "$worktree\joysong-server" test --tests com.joysong.server.project.service.ProfessionalProjectRequestServiceTest
~~~

Expected failure: current insert/select logic knows only serviceContent and priceSuggestion.

### Step 2: Implement shared validation helpers

Inject ObjectMapper and OrderSplitRatePolicy. Reuse small private helpers rather than duplicating field checks:

~~~kotlin
private fun requireMoney(label: String, value: BigDecimal)
private fun optionalMoney(label: String, value: BigDecimal?)
private fun requireCount(label: String, value: Int)
private fun normalizeOptionalText(value: String?): String?
private fun normalizeList(label: String, values: List<String>?, maxItems: Int): List<String>
private fun encodeList(values: List<String>?): String?
private fun decodeList(value: String?): List<String>
~~~

Money range is 0..99999999.99 with at most two decimals. Rates are delegated to OrderSplitRatePolicy.resolve(institutionRate, commissionRate).

Do not hardcode a platform rate and do not store a doctor rate.

### Step 3: Replace INSERT and SELECT mappings

Insert all V28 columns. Expand ProfessionalProjectRequestView and the locked target with:

- shared fields: name/category/description/tags/slogan/detailContent/currency/coverImage/images/salesCount;
- platform fields: referencePrice/categoryTags;
- institution content fields: price/originalPrice/isActive;
- nested split view: consultationFee/commissionRate/institutionRate/current platformRate/derived doctorRate.

Keep identity/reviewer/result/timestamp fields already present. Do not add rating or reviewCount.

When a stored institution split no longer resolves because the configured platform rate changed, list the submitted rates and current platform rate without crashing the entire list; approval remains the strict enforcement point.

### Step 4: Run the focused service tests

Expected: PASS.

### Step 5: Commit

~~~powershell
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' add joysong-server/src/main/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestService.kt joysong-server/src/test/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestServiceTest.kt
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' commit -m "feat: persist full project application snapshots"
~~~

## Task 5: Make approvals authoritative and atomic

**Files:**

- Modify joysong-server/src/main/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestService.kt
- Modify joysong-server/src/test/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestServiceTest.kt
- Create joysong-server/src/test/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestPersistenceTest.kt

### Step 1: Write failing unit tests for review rules

Cover:

- only an admin can review PLATFORM;
- the target institution's current legal representative or an admin can review INSTITUTION;
- InstitutionRelationshipReviewAuthorityOperations is called inside the transaction for non-admin review;
- CHANGES_REQUESTED is rejected before DB access;
- blank REJECTED note is rejected;
- APPROVED note is optional;
- processed/concurrent request produces 409;
- an institution request whose institution or platform project disappeared produces 404 before target writes;
- an institution request whose applicant relationship became inactive after submission produces 409 before target writes;
- an institution project created by another transaction after submission produces 409 before target writes;
- a platform-rate change that makes the submitted split invalid produces 409 before target writes;
- rating and review_count are explicit zero insert arguments.

### Step 2: Write failing approval-output tests

For PLATFORM approval, verify the projects INSERT carries the complete snapshot and explicit:

~~~text
rating = 0
review_count = 0
~~~

For INSTITUTION approval, verify exactly:

1. one complete institution_projects row with rating/review_count = 0;
2. one doctor_projects row for target.doctorId only;
3. one doctor_institution_project_configs row for the applicant;
4. one request status update.

Resolve effective inherited fields from the current platform project at approval. Initialize the doctor binding from the effective description/tags/cover/images, use the submitted price, and keep schedule_note empty.

### Step 3: Implement lock/revalidation order

Inject InstitutionRelationshipReviewAuthorityOperations and use this order:

1. lock request;
2. verify expected type and PENDING status;
3. verify platform admin or current target-institution authority;
4. lock institution;
5. revalidate the applicant's active doctor_institutions relationship;
6. lock/read the current platform project;
7. verify no institution project exists;
8. revalidate money and OrderSplitRatePolicy with the current platform rate;
9. create target rows;
10. compare-and-set the request to APPROVED.

Convert request JSON arrays to the target tables' existing comma-separated representation in one helper at this approval boundary.

If the platform rate drift makes the split invalid, raise 409 before target writes. Any write failure must leave the request PENDING.

Keep JOIN and PROFILE_UPDATE review behavior untouched.

Use the existing discover/home cache names to evict stale project catalog results only after a successful approval transaction.

### Step 4: Add a real MySQL transaction test

Use the shared runtime worktree derivation with no suffix:

~~~text
myapp_worktree_doctor_project_application_full_vo
~~~

Validate the exact derived name and its destructive-safe prefix, then print host/name before the first migration. Test:

- successful approval creates the institution project, applicant binding, split config, and APPROVED request;
- a forced failure during the final config insert rolls back all three target writes and leaves the request PENDING;
- two independent threads/transactions review the same PENDING request concurrently: exactly one returns success, exactly one raises the 409 conflict, the request is APPROVED once, and no target table contains duplicate rows.

Run unit tests first:

~~~powershell
$worktree = 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo'
$gradle = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat'
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex'
& $gradle --offline -p "$worktree\joysong-server" test --tests com.joysong.server.project.service.ProfessionalProjectRequestServiceTest
~~~

Then run the focused integration class once:

~~~powershell
$worktree = 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo'
$gradle = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat'
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex'
& $gradle --offline -p "$worktree\joysong-server" mysqlIntegrationTest --tests com.joysong.server.project.service.ProfessionalProjectRequestPersistenceTest
~~~

Expected: PASS, with isolated database evidence.

### Step 5: Commit

~~~powershell
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' add joysong-server/src/main/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestService.kt joysong-server/src/test/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestPersistenceTest.kt
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' commit -m "feat: approve project applications atomically"
~~~

## Task 6: Expand the professional project catalog needed for inheritance

**Files:**

- Modify joysong-server/src/main/kotlin/com/joysong/server/project/controller/ManagementProjectController.kt
- Modify joysong-server/src/test/kotlin/com/joysong/server/project/controller/ManagementProjectControllerTest.kt

### Step 1: Write a failing summary-shape test

Assert ManagementProjectSummary contains the current inherited display values needed by the institution form:

~~~text
id, name, category, description, tags, categoryTags, coverImage,
referencePrice, currency, slogan, detailContent, images, salesCount
~~~

It must not expose rating or reviewCount for this workflow.

Run:

~~~powershell
$worktree = 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo'
$gradle = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat'
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex'
& $gradle --offline -p "$worktree\joysong-server" test --tests com.joysong.server.project.controller.ManagementProjectControllerTest
~~~

Expected failure: slogan/detailContent/images/salesCount are missing.

### Step 2: Extend the read-only projection

Map the four missing fields from ProjectEntity. Preserve existing authorization and deterministic ordering; do not add write access.

### Step 3: Run and commit

Expected: PASS.

~~~powershell
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' add joysong-server/src/main/kotlin/com/joysong/server/project/controller/ManagementProjectController.kt joysong-server/src/test/kotlin/com/joysong/server/project/controller/ManagementProjectControllerTest.kt
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' commit -m "feat: expose project inheritance details"
~~~

## Task 7: Implement Flutter contracts and repository calls

**Files:**

- Modify joysong-flutter/lib/features/identity/domain/identity_models.dart
- Modify joysong-flutter/lib/features/identity/domain/identity_repository.dart
- Modify joysong-flutter/lib/features/identity/data/identity_repository_impl.dart
- Create joysong-flutter/test/features/identity/professional_project_request_contract_test.dart
- Modify only fake IdentityRepository implementations that fail to compile after the interface addition

### Step 1: Prepare the local Flutter workspace

Use the local SDK, not a network install:

~~~powershell
$flutter = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat'
Push-Location 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo\joysong-flutter'
try { & $flutter pub get --offline } finally { Pop-Location }
~~~

### Step 2: Write failing exact-JSON tests

Assert PlatformProjectRequestDraft.toJson() emits exactly 13 keys and InstitutionProjectRequestDraft.toJson() exactly 18 body keys. Assert institutionId is not in the body and every prohibited field is absent.

Add parsing tests for:

- InstitutionProjectApplicationFormConfig(platformRate);
- complete ProfessionalProjectRequest snapshot with nested InstitutionProjectSplit and derived split fields;
- expanded ManagementProjectOption inheritance values.

Add a recording ApiClient contract test for:

~~~text
GET  /management/project-requests/institution-form-config
POST /management/project-requests/platform
POST /management/project-requests/institutions/{institutionId}
POST /management/project-requests/{id}/review
POST /admin/project-requests/{id}/review
~~~

Run:

~~~powershell
$flutter = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat'
Push-Location 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo\joysong-flutter'
try { & $flutter test test/features/identity/professional_project_request_contract_test.dart } finally { Pop-Location }
~~~

Expected failure: the models/config method/full JSON shapes are absent.

### Step 3: Implement models and repository

Use the exact fixed contracts above. Add draft validate methods for:

- required platform name/category/description;
- amount/count/rate precision and ranges;
- USD/CNY;
- platform + institution + consultant total not above 100;
- bounded non-empty array items.

The platform rate is loaded only from institution-form-config. Doctor rate is calculated for display and never serialized. Keep `reviewInstitutionProjectRequest(...)` on the management path and add a distinct `reviewPlatformProjectRequest(...)` repository method on the admin path; the two authority scopes must not share an endpoint accidentally.

Expand ProfessionalProjectRequest without rating or reviewCount. Represent the review split as a nullable nested InstitutionProjectSplit object, matching the backend InstitutionProjectSplitView rather than duplicating five flat top-level fields. Preserve legacy CHANGES_REQUESTED parsing as an unknown/read-only status, but expose no creation-review action for it.

### Step 4: Run and commit

Expected: PASS.

Before committing, inspect `git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' status --short`, append every fake `IdentityRepository` implementation that the interface addition actually required, and inspect the staged diff. Do not leave compiling fake changes unstaged, and do not edit unrelated fakes.

~~~powershell
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' add joysong-flutter/lib/features/identity/domain/identity_models.dart joysong-flutter/lib/features/identity/domain/identity_repository.dart joysong-flutter/lib/features/identity/data/identity_repository_impl.dart joysong-flutter/test/features/identity/professional_project_request_contract_test.dart
# Add each actually modified fake repository path reported by status here.
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' diff --cached --check
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' commit -m "feat(flutter): define full project application contracts"
~~~

## Task 8: Build the two complete Flutter forms and creation review UI

**Files:**

- Modify joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart
- Modify joysong-flutter/lib/features/identity/presentation/identity_pages.dart
- Create joysong-flutter/test/features/identity/professional_project_request_page_test.dart
- Modify joysong-flutter/test/features/identity/identity_models_controller_test.dart

### Step 1: Write failing widget tests

Cover the platform form:

- content order matches the admin form minus rating/review count;
- cover and gallery use the existing upload callback;
- detail content is multiline plain text;
- no doctor control exists;
- invalid money/count blocks submission;
- upload/submission failure retains every field;
- fields clear only after submit and refresh both succeed.

Cover the institution form:

- target institution and platform project selectors;
- current applicant message and no multi-doctor selector;
- all optional inherited content fields;
- price/original price/currency/images/sales/isActive;
- consultation fee, consultant rate, and institution rate;
- read-only server platform rate and live derived doctor rate;
- invalid precision/range/sum blocks submission;
- full immutable application snapshot in the list/review surface.

Cover review decisions:

- institution creation review offers APPROVED and REJECTED only;
- REJECTED requires a note;
- existing JOIN review still offers CHANGES_REQUESTED;
- an applicant doctor sees their own immutable platform/institution rows but no review action;
- a target legal representative sees and can review only that institution's creation rows;
- a non-target legal representative sees neither the row nor a review action;
- an administrator sees platform and institution creation rows, uses the admin review endpoint for PLATFORM and the management review endpoint for INSTITUTION, and no non-admin can review a PLATFORM row.

Run:

~~~powershell
$flutter = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat'
Push-Location 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo\joysong-flutter'
try {
    & $flutter test test/features/identity/professional_project_request_page_test.dart
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & $flutter test test/features/identity/identity_models_controller_test.dart
} finally { Pop-Location }
~~~

Expected failure: current pages contain only the minimal fields and share a three-decision dialog.

### Step 2: Implement the platform form

Field order:

~~~text
name -> reference price/currency -> slogan -> sales count -> cover/gallery
-> category -> description -> multiline detail -> tags/category tags -> notes
~~~

Accept pickAndUploadImage in the page constructor. Use one cover URL and a list for gallery images. Keep values on any failure.

### Step 3: Implement the institution form

Field order:

~~~text
institution -> platform project -> current applicant notice
-> optional inherited name/category/description/tags/slogan/detail
-> price/original price/currency/cover/gallery/sales/isActive
-> consultation fee/consultant rate/institution rate
-> read-only platform rate/derived doctor rate -> notes
~~~

When the project changes, show inheritance hints from ManagementProjectOption and initialize currency from the public project. Empty overrides remain empty and serialize as nullable/empty inheritance values.

Pass the existing doctorImagePicker from identity_pages.dart to both submission pages.

### Step 4: Add role-scoped review entry points and split creation/legacy dialogs

Add platform-project and institution-project review actions to the Flutter platform-administration group for administrators. Reuse the existing scoped list endpoint, but dispatch PLATFORM decisions through `reviewPlatformProjectRequest(...)` and INSTITUTION decisions through `reviewInstitutionProjectRequest(...)`. Legal representatives retain only the target-institution review entry; doctors retain submission/history surfaces without review buttons.

Add a creation-specific helper with only APPROVED/REJECTED. Use it for both ProfessionalProjectRequest creation review types. Keep the existing three-decision helper for the unrelated JOIN flow.

### Step 5: Run, analyze, and commit

Run the two focused widget test commands once. Then:

~~~powershell
$flutter = 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat'
Push-Location 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo\joysong-flutter'
try { & $flutter analyze } finally { Pop-Location }
~~~

Expected: tests and analysis pass.

~~~powershell
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' add joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart joysong-flutter/lib/features/identity/presentation/identity_pages.dart joysong-flutter/test/features/identity/professional_project_request_page_test.dart joysong-flutter/test/features/identity/identity_models_controller_test.dart
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' commit -m "feat(flutter): build full doctor project application forms"
~~~

## Task 9: Show immutable full snapshots in the web admin review page

**Files:**

- Modify joysong-admin/src/pages/ProjectRequestsPage.tsx
- Modify joysong-admin/src/pages/ProjectRequestsPage.test.tsx
- Create joysong-admin/src/pages/DirectProjectCreationFormsRegression.test.tsx

### Step 1: Write failing Vitest cases

Test:

- a complete platform snapshot is visible;
- a complete institution snapshot includes price/original price/status, images, and all five values from the nested split object;
- professional creation requests offer Through/Reject but no Request changes;
- reject sends exactly decision REJECTED plus reviewNote;
- platform review uses /admin/project-requests/{id}/review;
- institution review uses /management/project-requests/{id}/review for legal representative and admin;
- applicant/target legal representative/non-target legal representative/admin fixtures expose only the rows and actions allowed to that actor;
- legacy JOIN rows continue to render and keep their existing review behavior;
- direct admin project and institution-project creation pages still expose their pre-existing rating/review-count and multi-doctor controls and retain their existing create routes. This is a behavior/render regression test; do not inspect source text.

Use currency from the response instead of hardcoded ¥.

Run with the already-installed local dependencies:

~~~powershell
Push-Location 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo\joysong-admin'
try {
    & 'D:\code\kotlin\joysong\joysong-admin\node_modules\.bin\vitest.cmd' run src/pages/ProjectRequestsPage.test.tsx src/pages/DirectProjectCreationFormsRegression.test.tsx
} finally { Pop-Location }
~~~

Expected failure: current view is minimal and shows Request changes for professional creation requests.

### Step 2: Separate professional and JOIN view shapes

Use a discriminated union on requestSource/requestType. The professional shape has an optional nested split object; the JOIN shape keeps its existing flat proposal fields. Render the immutable snapshot in the expandable row. Keep ProjectRequestStatus capable of displaying historical CHANGES_REQUESTED, but do not produce it for professional creation review.

Remove the professional Request changes action and fixed CHANGES_REQUESTED modal path. Do not remove the global status label or unrelated JOIN/PROFILE_UPDATE behavior.

### Step 3: Run test and build

~~~powershell
Push-Location 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo\joysong-admin'
try {
    & 'D:\code\kotlin\joysong\joysong-admin\node_modules\.bin\vitest.cmd' run src/pages/ProjectRequestsPage.test.tsx src/pages/DirectProjectCreationFormsRegression.test.tsx
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & 'D:\code\kotlin\joysong\joysong-admin\node_modules\.bin\tsc.cmd' -b
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & 'D:\code\kotlin\joysong\joysong-admin\node_modules\.bin\vite.cmd' build
} finally { Pop-Location }
~~~

Expected: focused tests and build pass.

### Step 4: Commit

~~~powershell
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' add joysong-admin/src/pages/ProjectRequestsPage.tsx joysong-admin/src/pages/ProjectRequestsPage.test.tsx joysong-admin/src/pages/DirectProjectCreationFormsRegression.test.tsx
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' commit -m "feat(admin): review full project application snapshots"
~~~

## Task 10: Publish the API contract and developer workflow

**Files:**

- Modify docs/FLUTTER_API_CONTRACT.md
- Create design/DOCTOR_PROJECT_APPLICATION_WORKFLOW.puml
- Create design/DOCTOR_PROJECT_APPLICATION_WORKFLOW.svg

### Step 1: Update the contract

Document:

- exact 13-key and 18-key bodies;
- institution-form-config;
- immutable review response fields;
- inheritance and JSON-array rules;
- split formula and current-rate approval revalidation;
- applicant-only binding;
- APPROVED/REJECTED only for these two creation flows;
- admin vs target legal-representative authority;
- explicit rating/reviewCount = 0 on created records;
- 400/403/404/409 behavior;
- deployment note that the operator clears old real institution creation requests and V28 is DDL-only.

Update the /management/projects response list with the new inheritance fields.

### Step 2: Add the developer flow

The PUML and SVG must show:

- doctor -> platform request -> admin approve/reject -> projects;
- doctor -> institution request -> target legal representative/admin approve/reject;
- one transaction creating institution_projects, applicant doctor_projects, applicant split config, then closing the request;
- immutable snapshot, no doctor selector, zero rating/review count, and rollback on any failure.

Because no PlantUML renderer is installed, keep the PUML as source and create a matching lightweight SVG in design/. Visually inspect the SVG before committing. Do not invent a PNG.

### Step 3: Documentation checks and commit

Run:

~~~powershell
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' diff --check
~~~

Then inspect the SVG locally.

~~~powershell
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' add docs/FLUTTER_API_CONTRACT.md design/DOCTOR_PROJECT_APPLICATION_WORKFLOW.puml design/DOCTOR_PROJECT_APPLICATION_WORKFLOW.svg
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' commit -m "docs: document doctor project application workflow"
~~~

## Task 11: Cross-layer verification and cleanup

### Step 1: Audit task test evidence instead of repeating green commands

For every focused backend, Flutter, admin, and MySQL command, compare its last green report with the last commit that changed the covered production files. If the command passed after that final production edit, record and reuse the evidence; do **not** run it again. If covered production changed later, run only the now-stale focused group using the same absolute/offline command pattern from its owning task.

For a stale backend group, use this fresh-shell template and include only the affected `--tests` selectors:

~~~powershell
$worktree = 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo'
$gradle = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat'
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex'
& $gradle --offline -p "$worktree\joysong-server" test --tests <affected.test.Class>
~~~

For a stale MySQL group, use the same local Gradle command with `mysqlIntegrationTest`, and first confirm its runtime-derived database name is isolated and printed before migration. For stale Flutter/admin groups, rerun only the owning task's absolute-path command block; redefine all variables and working directories in that block.

### Step 2: Run at most one justified broader verification per component

Only after all required focused evidence is current, decide whether the combined cross-stack risk justifies one broader backend, Flutter, or admin command. Run each chosen broader command at most once. Stop it at ten minutes and report the completed/slowest tests rather than retrying. A component whose focused evidence is sufficient needs no repeated suite.

### Step 3: Inspect contract and forbidden fields

Search production submission DTOs/Flutter drafts/admin creation-review types and confirm:

- no rating/reviewCount in either doctor creation request;
- no doctorId/doctorIds/doctorBindings in request bodies;
- no platformRate/doctorRate in the institution body or ledger;
- current applicant doctor is the only created binding;
- direct admin create forms remain unchanged;
- JOIN and PROFILE_UPDATE retain their existing decision options.

### Step 4: Clean generated artifacts

Remove only task-created caches, build output, Testcontainers artifacts, and temporary files. Do not touch the main worktree's unrelated changes. Keep source/test/docs/design files.

Run:

~~~powershell
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' status --short
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' diff --check
git -C 'D:\code\kotlin\joysong\.worktrees\doctor-project-application-full-vo' log --oneline --decorate -12
~~~

### Step 5: Request review

Use superpowers:requesting-code-review against the completed branch. Address findings with focused tests, then use superpowers:verification-before-completion before reporting the result.
