# User Agreement and Privacy Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let administrators publish complete bilingual User Agreement and Privacy Policy content and let users read the current published content from login, registration, and Settings.

**Architecture:** A new Kotlin legal-document module stores immutable bilingual releases and exposes admin, public JSON, and public HTML endpoints. The React admin reuses its rich-text editor for draft, preview, publish, and history workflows. Flutter loads the anonymous JSON endpoint into a native full-screen renderer shared by all existing legal entry points.

**Tech Stack:** Kotlin 1.9/Spring Boot 3.2/JPA/Flyway/MySQL/jsoup 1.23.1, React 19/TypeScript/Ant Design/WangEditor/Vitest, Flutter/Dart/ApiClient/url_launcher.

**Spec:** `docs/superpowers/specs/2026-08-25-user-agreement-privacy-policy-design.md`

## Global Constraints

- Only `USER_AGREEMENT` and `PRIVACY_POLICY` document types are in scope.
- Each release must contain both `zh-CN` and `en-US` before publication.
- Published releases are immutable; changes start a new draft.
- Login and registration retain their existing combined checkbox and both legal links.
- Settings retains its existing two legal buttons.
- Authentication payloads and the device-local `agreementsAccepted` preference do not change.
- No server-side user consent table or consent API is added.
- Legal content is sanitized HTML stored in the database; PDF/Word upload is not added.
- No WebView is added to Flutter.
- Do not modify `B26__current_schema.sql` or any existing migration.
- The provisional migration filename is `V33_20260825_1__add_legal_documents.sql`. A parallel worktree already contains `V33__doctor_institution_project_full_edit.sql`; before final integration, rescan every worktree and visible branch, integrate V33 first, and rename this migration if any equal Flyway version appears.
- Never apply `33.20260825.1` to a persistent environment that does not already contain V33. The isolated disposable migration test is the only permitted pre-integration application.
- The migration test must print its host and database and require the database name `myapp_worktree_legal_documents`; it must never connect to a shared development database.
- Final Chinese and English legal language is supplied and reviewed outside this code change; production receives no placeholder seed data.

---

### Task 1: Database schema and legal domain model

**Files:**
- Create: `joysong-server/src/main/resources/db/migration/V33_20260825_1__add_legal_documents.sql`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/legal/entity/LegalDocumentEntities.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/legal/repository/LegalDocumentRepositories.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/legal/dto/LegalDocumentDtos.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/legal/LegalDocumentMigrationTest.kt`

**Interfaces:**
- Produces: `LegalDocumentType`, `LegalDocumentLocale`, `LegalDocumentStatus`, release/content entities and repositories used by Tasks 2 and 3.
- Produces: public/admin DTOs consumed by backend controllers and mirrored by admin/Flutter clients.

- [ ] **Step 1: Write the failing isolated migration test**

Create a MySQL Testcontainers test that derives `legal_documents` from the worktree directory after removing one optional leading `worktree_`, constructs `myapp_worktree_legal_documents`, prints host/database before migration, and requires the `myapp_worktree_` safety prefix.

```kotlin
@Tag("mysql-integration")
@Testcontainers
class LegalDocumentMigrationTest {
    @Test
    fun `fresh database contains legal release constraints`() {
        printAndValidateDatabase()
        Flyway.configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val jdbc = JdbcTemplate(DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'legal_document_releases'", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'legal_document_contents'", Int::class.java))
    }

    private fun printAndValidateDatabase() {
        require(mysql.databaseName == "myapp_worktree_legal_documents")
        require(mysql.databaseName.startsWith("myapp_worktree_"))
        println("Migration database host=${mysql.host}:${mysql.getMappedPort(3306)}, database=${mysql.databaseName}")
    }

    companion object {
        @Container
        @JvmField
        val mysql = MySqlLegalContainer("mysql:8.0.39")
            .withDatabaseName("myapp_worktree_legal_documents")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class MySqlLegalContainer(image: String) : MySQLContainer<MySqlLegalContainer>(image)
```

- [ ] **Step 2: Run the migration test and verify it fails**

Run from `joysong-server`:

```powershell
$legalGradleHome = Join-Path $PWD '.tmp\gradle-user-home-codex-legal-documents'
New-Item -ItemType Directory -Force -Path $legalGradleHome | Out-Null
$env:GRADLE_USER_HOME = $legalGradleHome
.\gradlew.bat mysqlIntegrationTest --tests com.joysong.server.legal.LegalDocumentMigrationTest
```

Expected: FAIL because the two legal tables do not exist.

- [ ] **Step 3: Add the migration**

Use these core constraints:

```sql
CREATE TABLE legal_document_releases (
    id VARCHAR(36) PRIMARY KEY,
    document_type VARCHAR(32) NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    change_summary VARCHAR(1000) NOT NULL DEFAULT '',
    published_at DATETIME(6) NULL,
    published_by VARCHAR(36) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(36) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(36) NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    active_draft_key VARCHAR(32) GENERATED ALWAYS AS (
        CASE WHEN status = 'DRAFT' THEN document_type ELSE NULL END
    ) STORED,
    active_published_key VARCHAR(32) GENERATED ALWAYS AS (
        CASE WHEN status = 'PUBLISHED' THEN document_type ELSE NULL END
    ) STORED,
    CONSTRAINT uq_legal_release_version UNIQUE (document_type, version),
    CONSTRAINT uq_legal_active_draft UNIQUE (active_draft_key),
    CONSTRAINT uq_legal_active_published UNIQUE (active_published_key),
    CONSTRAINT ck_legal_release_type CHECK (document_type IN ('USER_AGREEMENT', 'PRIVACY_POLICY')),
    CONSTRAINT ck_legal_release_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED'))
);

CREATE TABLE legal_document_contents (
    id VARCHAR(36) PRIMARY KEY,
    release_id VARCHAR(36) NOT NULL,
    locale VARCHAR(8) NOT NULL,
    title VARCHAR(200) NOT NULL DEFAULT '',
    content_html MEDIUMTEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_legal_release_locale UNIQUE (release_id, locale),
    CONSTRAINT fk_legal_content_release FOREIGN KEY (release_id) REFERENCES legal_document_releases(id),
    CONSTRAINT ck_legal_content_locale CHECK (locale IN ('zh-CN', 'en-US'))
);
```

- [ ] **Step 4: Add exact Kotlin domain types**

```kotlin
enum class LegalDocumentType(val slug: String) {
    USER_AGREEMENT("user-agreement"),
    PRIVACY_POLICY("privacy-policy");

    companion object {
        fun fromSlug(value: String) = entries.firstOrNull { it.slug == value }
            ?: throw IllegalArgumentException("不支持的协议类型")
    }
}

enum class LegalDocumentLocale(val tag: String) {
    ZH_CN("zh-CN"), EN_US("en-US");

    companion object {
        fun fromTag(value: String) = entries.firstOrNull { it.tag == value }
            ?: throw IllegalArgumentException("不支持的协议语言")
    }
}

enum class LegalDocumentStatus { DRAFT, PUBLISHED, SUPERSEDED }
```

Create `LegalDocumentReleaseEntity` with `@Version var lockVersion: Long`, and `LegalDocumentContentEntity` with the schema columns above. Repositories expose:

```kotlin
fun findAllByDocumentTypeOrderByVersionDesc(type: LegalDocumentType): List<LegalDocumentReleaseEntity>
fun findFirstByDocumentTypeAndStatus(type: LegalDocumentType, status: LegalDocumentStatus): LegalDocumentReleaseEntity?
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT r FROM LegalDocumentReleaseEntity r WHERE r.documentType = :type ORDER BY r.version DESC")
fun findAllByDocumentTypeForUpdate(type: LegalDocumentType): List<LegalDocumentReleaseEntity>
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT r FROM LegalDocumentReleaseEntity r WHERE r.id = :id")
fun findByIdForUpdate(id: String): LegalDocumentReleaseEntity?
fun findAllByReleaseIdOrderByLocaleAsc(releaseId: String): List<LegalDocumentContentEntity>
fun findAllByReleaseIdIn(releaseIds: Collection<String>): List<LegalDocumentContentEntity>
```

- [ ] **Step 5: Add stable DTO contracts**

Define `LegalDocumentLocaleInput`, `UpdateLegalDocumentDraftRequest`, `PublishLegalDocumentRequest`, `LegalDocumentReleaseSummaryView`, `AdminLegalDocumentSummaryView`, `LegalDocumentContentView`, `LegalDocumentReleaseView`, and `PublicLegalDocumentView`. The update request is exact:

```kotlin
data class UpdateLegalDocumentDraftRequest(
    @field:PositiveOrZero val lockVersion: Long,
    @field:Size(max = 1000) val changeSummary: String,
    @field:Size(min = 2, max = 2) val contents: Map<String, @Valid LegalDocumentLocaleInput>
)

data class LegalDocumentLocaleInput(
    @field:Size(max = 200) val title: String,
    @field:Size(max = 200_000) val contentHtml: String
)

data class PublishLegalDocumentRequest(@field:PositiveOrZero val lockVersion: Long)
```

- [ ] **Step 6: Run the isolated migration test**

Run the Step 2 command once. Expected: PASS and output explicitly includes `database=myapp_worktree_legal_documents`.

- [ ] **Step 7: Commit Task 1**

```powershell
git add -- joysong-server/src/main/resources/db/migration/V33_20260825_1__add_legal_documents.sql joysong-server/src/main/kotlin/com/joysong/server/legal joysong-server/src/test/kotlin/com/joysong/server/legal/LegalDocumentMigrationTest.kt
git commit -m "feat: add legal document schema"
```

---

### Task 2: Sanitized draft and publication service

**Files:**
- Modify: `joysong-server/build.gradle.kts`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/legal/service/LegalDocumentHtmlSanitizer.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/legal/service/LegalDocumentService.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/legal/service/LegalDocumentHtmlSanitizerTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/legal/service/LegalDocumentServiceTest.kt`

**Interfaces:**
- Consumes: Task 1 entities, repositories, and DTOs.
- Produces: draft/history/publish/current methods consumed by Task 3 controllers.

- [ ] **Step 1: Write sanitizer and service failing tests**

Cover scripts/event attributes/unsafe schemes being removed, title-only hash changes, one draft per type, copying published bilingual contents, incomplete bilingual publication rejection, immutable published releases, optimistic-lock conflicts, and atomic superseding.

```kotlin
@Test
fun `publish rejects an empty English document`() {
    val draft = draftWith(
        "zh-CN" to LegalDocumentLocaleInput("用户协议", "<p>正文</p>"),
        "en-US" to LegalDocumentLocaleInput("", "")
    )
    every { releaseRepository.findByIdForUpdate(draft.id) } returns draft
    every { contentRepository.findAllByReleaseIdOrderByLocaleAsc(draft.id) } returns draftContents(draft.id)

    assertThrows<IllegalArgumentException> {
        service.publish(draft.id, "admin-1", PublishLegalDocumentRequest(draft.lockVersion))
    }
    verify(exactly = 0) { releaseRepository.save(any()) }
}
```

- [ ] **Step 2: Run focused tests and verify failure**

```powershell
.\gradlew.bat test --tests com.joysong.server.legal.service.LegalDocumentHtmlSanitizerTest --tests com.joysong.server.legal.service.LegalDocumentServiceTest
```

Expected: FAIL because the services do not exist.

- [ ] **Step 3: Add jsoup and the restricted sanitizer**

Add:

```kotlin
implementation("org.jsoup:jsoup:1.23.1")
```

Implement an explicit safelist:

```kotlin
private val safelist = Safelist.none()
    .addTags("p", "h1", "h2", "h3", "h4", "ul", "ol", "li", "strong", "em", "u", "blockquote", "br", "a")
    .addAttributes("a", "href")
    .addProtocols("a", "href", "https", "mailto", "tel")

fun sanitize(rawHtml: String): SanitizedLegalHtml {
    val cleaned = Jsoup.clean(rawHtml, "", safelist, Document.OutputSettings().prettyPrint(false))
    val visibleText = Jsoup.parseBodyFragment(cleaned).text().trim()
    return SanitizedLegalHtml(cleaned, visibleText)
}

fun sha256(title: String, sanitizedHtml: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest("${title.trim()}\u0000$sanitizedHtml".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
```

- [ ] **Step 4: Implement the service transaction boundaries**

Use these exact public methods:

```kotlin
fun listAdmin(): List<AdminLegalDocumentSummaryView>
fun history(type: LegalDocumentType): List<LegalDocumentReleaseSummaryView>
@Transactional fun createDraft(type: LegalDocumentType, actorId: String): LegalDocumentReleaseView
fun getRelease(id: String): LegalDocumentReleaseView
@Transactional fun updateDraft(id: String, actorId: String, request: UpdateLegalDocumentDraftRequest): LegalDocumentReleaseView
@Transactional fun publish(id: String, actorId: String, request: PublishLegalDocumentRequest): LegalDocumentReleaseView
@Cacheable(cacheNames = ["legalDocuments"], key = "#type.name() + ':' + #locale.tag")
fun findPublished(type: LegalDocumentType, locale: LegalDocumentLocale): PublicLegalDocumentView?
```

`createDraft` locks all releases for the type, rejects an existing draft, sets version to `max(version)+1`, and creates exactly two content rows. `updateDraft` verifies DRAFT plus matching `lockVersion`, sanitizes both inputs, and saves hashes. `publish` locks, verifies DRAFT plus lock version, requires non-empty visible title/body for both locales, supersedes the previous PUBLISHED release, publishes the draft, and evicts `legalDocuments` cache entries.

- [ ] **Step 5: Run focused service tests**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```powershell
git add -- joysong-server/build.gradle.kts joysong-server/src/main/kotlin/com/joysong/server/legal/service joysong-server/src/test/kotlin/com/joysong/server/legal/service
git commit -m "feat: publish sanitized legal documents"
```

---

### Task 3: Admin API, anonymous JSON, and public HTML

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/legal/controller/PublicLegalDocumentController.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminLegalDocumentController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/common/GlobalExceptionHandler.kt`
- Modify: `docs/FLUTTER_API_CONTRACT.md`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/legal/controller/LegalDocumentHttpTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/config/SecurityConfigLegalDocumentsTest.kt`

**Interfaces:**
- Consumes: Task 2 service.
- Produces: `/api/admin/legal-documents/**`, `/api/public/legal-documents/{type}`, and `/legal/{type}` used by Tasks 4-7.

- [ ] **Step 1: Write failing controller and security tests**

Verify anonymous JSON/HTML GET succeeds, absent publication returns HTTP 404, ETag/If-None-Match returns 304, anonymous admin calls return 401, non-admin admin calls return 403, and publication HTML contains neither scripts nor third-party resources.

```kotlin
mockMvc.perform(get("/api/public/legal-documents/privacy-policy").param("locale", "en-US"))
    .andExpect(status().isOk)
    .andExpect(header().string(HttpHeaders.ETAG, "\"hash-en\""))
    .andExpect(jsonPath("$.data.type").value("privacy-policy"))
    .andExpect(jsonPath("$.data.locale").value("en-US"))
```

- [ ] **Step 2: Run focused HTTP tests and verify failure**

```powershell
.\gradlew.bat test --tests com.joysong.server.legal.controller.LegalDocumentHttpTest --tests com.joysong.server.config.SecurityConfigLegalDocumentsTest
```

Expected: FAIL because routes are absent.

- [ ] **Step 3: Implement admin controller methods**

Under `@RequestMapping("/api/admin/legal-documents")`, implement:

```kotlin
@GetMapping fun list(): BaseResponse<List<AdminLegalDocumentSummaryView>>
@GetMapping("/{type}/history") fun history(@PathVariable type: String): BaseResponse<List<LegalDocumentReleaseSummaryView>>
@PostMapping("/{type}/draft") fun createDraft(authentication: Authentication, @PathVariable type: String): BaseResponse<LegalDocumentReleaseView>
@GetMapping("/releases/{id}") fun getRelease(@PathVariable id: String): BaseResponse<LegalDocumentReleaseView>
@PutMapping("/releases/{id}") fun updateDraft(authentication: Authentication, @PathVariable id: String, @Valid @RequestBody request: UpdateLegalDocumentDraftRequest): BaseResponse<LegalDocumentReleaseView>
@PostMapping("/releases/{id}/publish") fun publish(authentication: Authentication, @PathVariable id: String, @Valid @RequestBody request: PublishLegalDocumentRequest): BaseResponse<LegalDocumentReleaseView>
```

Use `authentication.name` for actor audit fields.

- [ ] **Step 4: Implement public JSON and HTML responses**

Under JSON path `/api/public/legal-documents/{type}` and HTML path `/legal/{type}`, use the same `PublicLegalDocumentView`. JSON returns `BaseResponse.success(view)` with `ETag: "<hash>"`; a matching `If-None-Match` returns 304. The responsive HTML escapes title/metadata, inserts only already sanitized `contentHtml`, declares UTF-8 and viewport, and adds a restrictive CSP header.

- [ ] **Step 5: Add exact security and error routing**

Before authenticated matchers add:

```kotlin
.requestMatchers(HttpMethod.GET, "/api/public/legal-documents/**", "/legal/**").permitAll()
```

Keep the existing `/api/admin/**` ADMIN rule. Map legal not-found and conflict exceptions to real 404/409 responses and include `/api/public/legal-documents/` in `usesRealHttpErrorStatus()`.

- [ ] **Step 6: Document the public response contract**

Add the exact path, locale enum, response fields, 404 behavior, ETag behavior, and the fact that auth request payloads remain unchanged to `docs/FLUTTER_API_CONTRACT.md`.

- [ ] **Step 7: Run focused HTTP tests**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 8: Commit Task 3**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/legal/controller joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminLegalDocumentController.kt joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt joysong-server/src/main/kotlin/com/joysong/server/common/GlobalExceptionHandler.kt joysong-server/src/test/kotlin/com/joysong/server/legal/controller joysong-server/src/test/kotlin/com/joysong/server/config/SecurityConfigLegalDocumentsTest.kt docs/FLUTTER_API_CONTRACT.md
git commit -m "feat: expose legal document APIs"
```

---

### Task 4: Admin legal API and restricted rich-text editor

**Files:**
- Create: `joysong-admin/src/legalDocuments.ts`
- Modify: `joysong-admin/src/components/RichTextEditor.tsx`
- Create: `joysong-admin/src/components/RichTextEditor.test.tsx`
- Create: `joysong-admin/src/legalDocuments.test.ts`

**Interfaces:**
- Consumes: Task 3 admin endpoints.
- Produces: typed API functions and a configurable editor used by Task 5.

- [ ] **Step 1: Write failing API and editor tests**

Assert path/body mapping for list, create draft, get, update, publish, and history. Assert restricted `toolbarKeys`, placeholder, and height are passed to WangEditor.

```ts
expect(mockedApi.put).toHaveBeenCalledWith('/admin/legal-documents/releases/draft-1', {
  lockVersion: 2,
  changeSummary: '更新联系方式',
  contents: {
    'zh-CN': { title: '隐私政策', contentHtml: '<p>中文</p>' },
    'en-US': { title: 'Privacy Policy', contentHtml: '<p>English</p>' },
  },
});
```

- [ ] **Step 2: Run focused admin tests and verify failure**

```powershell
npm test -- src/legalDocuments.test.ts src/components/RichTextEditor.test.tsx
```

Expected: FAIL because the API module and configurable editor props are absent.

- [ ] **Step 3: Add exact TypeScript contracts and API functions**

```ts
export type LegalDocumentType = 'user-agreement' | 'privacy-policy';
export type LegalDocumentLocale = 'zh-CN' | 'en-US';
export type LegalDocumentStatus = 'DRAFT' | 'PUBLISHED' | 'SUPERSEDED';

export interface LegalDocumentContent {
  locale: LegalDocumentLocale;
  title: string;
  contentHtml: string;
  contentSha256: string;
}

export interface UpdateLegalDocumentDraftInput {
  lockVersion: number;
  changeSummary: string;
  contents: Record<LegalDocumentLocale, Pick<LegalDocumentContent, 'title' | 'contentHtml'>>;
}
```

Export `listLegalDocuments`, `getLegalDocumentHistory`, `createLegalDocumentDraft`, `getLegalDocumentRelease`, `updateLegalDocumentDraft`, and `publishLegalDocumentRelease`, all decoding via existing `getData`.

- [ ] **Step 4: Make RichTextEditor configurable without changing article defaults**

Add optional `toolbarKeys`, `placeholder`, and `height` props. When absent, preserve the exact existing article toolbar and 400-pixel height. The legal page will pass:

```ts
const legalToolbarKeys = [
  'headerSelect', 'bold', 'italic', 'underline', 'blockquote',
  'bulletedList', 'numberedList', 'insertLink', 'undo', 'redo',
];
```

- [ ] **Step 5: Run focused tests**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 6: Commit Task 4**

```powershell
git add -- joysong-admin/src/legalDocuments.ts joysong-admin/src/legalDocuments.test.ts joysong-admin/src/components/RichTextEditor.tsx joysong-admin/src/components/RichTextEditor.test.tsx
git commit -m "feat: add legal document admin client"
```

---

### Task 5: Admin draft, preview, publish, and history page

**Files:**
- Create: `joysong-admin/src/pages/LegalDocumentsPage.tsx`
- Create: `joysong-admin/src/pages/LegalDocumentsPage.test.tsx`
- Modify: `joysong-admin/src/App.tsx`
- Modify: `joysong-admin/src/layouts/AdminLayout.tsx`
- Create or Modify: `joysong-admin/src/layouts/AdminLayout.test.tsx`

**Interfaces:**
- Consumes: Task 4 typed API/editor.
- Produces: the administrator workflow required by the goal.

- [ ] **Step 1: Write failing page/navigation tests**

Cover both document cards, creation from no draft, bilingual tab editing, required publication validation, sanitized preview, save, publish confirmation, conflict reload, history read-only display, admin menu visibility, and `AdminOnlyRoute` protection.

```tsx
render(<LegalDocumentsPage />);
await user.click(await screen.findByRole('button', { name: '创建隐私政策草稿' }));
expect(createLegalDocumentDraft).toHaveBeenCalledWith('privacy-policy');
expect(await screen.findByRole('tab', { name: '中文' })).toBeVisible();
expect(screen.getByRole('tab', { name: 'English' })).toBeVisible();
```

- [ ] **Step 2: Run the focused page tests and verify failure**

```powershell
npm test -- src/pages/LegalDocumentsPage.test.tsx src/layouts/AdminLayout.test.tsx
```

Expected: FAIL because page, route, and menu entry are absent.

- [ ] **Step 3: Implement the two-card admin page**

Use Ant Design `Card`, `Tag`, `Tabs`, `Form`, `Modal`, `Drawer`, `Table`, and `message`. Maintain a single loaded draft model, preserve server `lockVersion`, and send both locale contents on save. Publish is disabled until the latest saved server response has non-empty sanitized visible content in both locales.

Preview uses a sandboxed iframe with `srcDoc` and no `allow-scripts` token:

```tsx
<iframe
  title="协议预览"
  sandbox=""
  srcDoc={`<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"></head><body>${previewHtml}</body></html>`}
/>
```

- [ ] **Step 4: Add route and admin-only menu entry**

Lazy-load `LegalDocumentsPage`; add:

```tsx
<Route path="legal-documents" element={<AdminOnlyRoute><LegalDocumentsPage /></AdminOnlyRoute>} />
```

Under 内容运营 add `/legal-documents` with label `协议与隐私`. Existing `isAdmin` menu filtering and `AdminOnlyRoute` remain the authorization boundary.

- [ ] **Step 5: Run focused admin tests, lint, and build once**

```powershell
npm test -- src/pages/LegalDocumentsPage.test.tsx src/layouts/AdminLayout.test.tsx src/legalDocuments.test.ts src/components/RichTextEditor.test.tsx
npm run lint
npm run build
```

Expected: all focused tests pass, lint exits 0, build exits 0.

- [ ] **Step 6: Commit Task 5**

```powershell
git add -- joysong-admin/src/pages/LegalDocumentsPage.tsx joysong-admin/src/pages/LegalDocumentsPage.test.tsx joysong-admin/src/App.tsx joysong-admin/src/layouts/AdminLayout.tsx joysong-admin/src/layouts/AdminLayout.test.tsx
git commit -m "feat: manage legal documents in admin"
```

---

### Task 6: Flutter legal document data and full-screen page

**Files:**
- Create: `joysong-flutter/lib/features/legal_documents/domain/legal_document_models.dart`
- Create: `joysong-flutter/lib/features/legal_documents/domain/legal_document_repository.dart`
- Create: `joysong-flutter/lib/features/legal_documents/data/legal_document_repository_impl.dart`
- Create: `joysong-flutter/lib/features/legal_documents/presentation/legal_document_controller.dart`
- Create: `joysong-flutter/lib/features/legal_documents/presentation/legal_document_page.dart`
- Create: `joysong-flutter/test/features/legal_documents/legal_document_repository_impl_test.dart`
- Create: `joysong-flutter/test/features/legal_documents/legal_document_controller_test.dart`
- Create: `joysong-flutter/test/features/legal_documents/legal_document_page_test.dart`

**Interfaces:**
- Consumes: Task 3 public JSON contract and existing `ApiClient`.
- Produces: `LegalDocumentRepository` and `LegalDocumentPage` used by Task 7 routing.

- [ ] **Step 1: Write failing repository/controller/page tests**

Cover exact API path/query, strict DTO parsing, session cache, force refresh, loading/ready/not-found/failure states, locale selection, metadata, retry, and no placeholder body on errors.

```dart
expect(
  requestedUri.toString(),
  'http://localhost:8080/api/public/legal-documents/privacy-policy?locale=en-US',
);
expect(document.type, LegalDocumentType.privacyPolicy);
expect(document.contentHtml, '<h1>Privacy</h1><p>Body</p>');
```

- [ ] **Step 2: Run focused Flutter tests and verify failure**

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' test test/features/legal_documents/legal_document_repository_impl_test.dart test/features/legal_documents/legal_document_controller_test.dart test/features/legal_documents/legal_document_page_test.dart
```

Expected: FAIL because the feature files do not exist.

- [ ] **Step 3: Implement strict models and repository**

```dart
enum LegalDocumentType {
  userAgreement('user-agreement'),
  privacyPolicy('privacy-policy');

  const LegalDocumentType(this.pathSegment);
  final String pathSegment;
}

abstract interface class LegalDocumentRepository {
  Future<LegalDocument> load({
    required LegalDocumentType type,
    required String locale,
    bool forceRefresh = false,
  });
}
```

`ApiLegalDocumentRepository` calls `public/legal-documents/${type.pathSegment}` because `apiRoot` already ends in `/api/`. Cache successful results by `(type, locale)` for the process lifetime. Translate `ApiException(httpStatus: 404)` into `LegalDocumentNotFoundException`; preserve other failures.

- [ ] **Step 4: Implement controller and page states**

The controller exposes immutable state with `initial`, `loading`, `ready`, `notFound`, and `failure` status plus `load()`/`retry()`. The page owns and disposes its controller, maps current app language to `zh-CN`/`en-US`, and shows title, `V<version> · <published date>`, selectable rich content, and localized retry UI.

- [ ] **Step 5: Run focused feature tests**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 6: Commit Task 6**

```powershell
git add -- joysong-flutter/lib/features/legal_documents joysong-flutter/test/features/legal_documents
git commit -m "feat: display published legal documents"
```

---

### Task 7: Flutter links, routes, and all required entry points

**Files:**
- Modify: `joysong-flutter/lib/app/app.dart`
- Modify: `joysong-flutter/lib/core/routing/app_router.dart`
- Modify: `joysong-flutter/lib/features/auth/presentation/auth_gate.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/rich_content_view.dart`
- Create: `joysong-flutter/test/features/auth/presentation/auth_gate_legal_navigation_test.dart`
- Create: `joysong-flutter/test/core/routing/app_router_legal_navigation_test.dart`
- Create or Modify: `joysong-flutter/test/features/discover/presentation/rich_content_view_test.dart`
- Regression: `joysong-flutter/test/features/auth/presentation/login_page_test.dart`
- Regression: `joysong-flutter/test/features/auth/presentation/auth_action_page_test.dart`
- Regression: `joysong-flutter/test/features/settings/presentation/settings_page_test.dart`

**Interfaces:**
- Consumes: Task 6 repository/page.
- Produces: working login, registration, and Settings navigation.

- [ ] **Step 1: Write failing navigation and rich-content tests**

Assert the four auth links map to the correct document type, the two Settings buttons map correctly, login/registration checkbox guards remain present, safe links call the injected launcher, unsafe schemes do not, `<ol>` renders numbered items, and `<ul>` renders bullets.

```dart
await tester.tap(find.text('隐私政策').first);
await tester.pumpAndSettle();
expect(find.byKey(const ValueKey('legal-document-privacy-policy')), findsOneWidget);
```

- [ ] **Step 2: Run focused navigation tests and verify failure**

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' test test/features/auth/presentation/auth_gate_legal_navigation_test.dart test/core/routing/app_router_legal_navigation_test.dart test/features/discover/presentation/rich_content_view_test.dart
```

Expected: FAIL because callbacks still show placeholders and links are not actionable.

- [ ] **Step 3: Add shared named legal routes**

Add `/legal/user-agreement` and `/legal/privacy-policy` constants. Extend `AppRouter.onGenerateRoute` with a required `LegalDocumentRepository` and construct the same `LegalDocumentPage` with different types. Inject Settings callbacks to push these routes.

At app root, create one anonymous `ApiClient(apiRoot: environment.apiRoot, languageTagProvider: currentLanguageTag)` and one `ApiLegalDocumentRepository`; close the client when the app root disposes. Do not attach token refresh behavior to this client.

- [ ] **Step 4: Replace AuthGate placeholder callbacks**

Delete `_showLegalText`. Login and registration callbacks push the two named routes. Do not modify `LoginPage`, `AuthActionPage`, auth request DTOs, auth repositories, or agreement preference serialization.

- [ ] **Step 5: Make safe rich-content links actionable and preserve numbering**

Extend `RichContentView` with `ValueChanged<Uri>? onLinkTap`. Parse `href` from sanitized `<a>` tags, allow only `https`, `mailto`, and `tel`, and manage gesture recognizer disposal in a StatefulWidget. Render ordered-list items as `1.`, `2.`, and unordered items as `•`.

- [ ] **Step 6: Run all focused and existing auth/settings regressions**

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' test test/features/legal_documents test/features/auth/presentation/auth_gate_legal_navigation_test.dart test/core/routing/app_router_legal_navigation_test.dart test/features/discover/presentation/rich_content_view_test.dart test/features/auth/presentation/login_page_test.dart test/features/auth/presentation/auth_action_page_test.dart test/features/settings/presentation/settings_page_test.dart
```

Expected: all tests pass; registration and login still require their checkbox.

- [ ] **Step 7: Run one targeted analyze**

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' analyze lib/features/legal_documents lib/app/app.dart lib/core/routing/app_router.dart lib/features/auth/presentation/auth_gate.dart lib/features/discover/presentation/rich_content_view.dart test/features/legal_documents test/features/auth/presentation/auth_gate_legal_navigation_test.dart test/core/routing/app_router_legal_navigation_test.dart test/features/discover/presentation/rich_content_view_test.dart
```

Expected: no issues.

- [ ] **Step 8: Commit Task 7**

```powershell
git add -- joysong-flutter/lib/app/app.dart joysong-flutter/lib/core/routing/app_router.dart joysong-flutter/lib/features/auth/presentation/auth_gate.dart joysong-flutter/lib/features/discover/presentation/rich_content_view.dart joysong-flutter/test/features/auth/presentation/auth_gate_legal_navigation_test.dart joysong-flutter/test/core/routing/app_router_legal_navigation_test.dart joysong-flutter/test/features/discover/presentation/rich_content_view_test.dart
git commit -m "feat: connect legal document entry points"
```

---

### Task 8: Integration verification and migration collision audit

**Files:**
- Modify only if needed: `docs/superpowers/plans/2026-08-25-user-agreement-privacy-policy.md` checkbox states
- Inspect: every changed file and every migration in every worktree

**Interfaces:**
- Consumes: Tasks 1-7.
- Produces: evidence that the original admin-update/user-view objective is fully met.

- [ ] **Step 1: Rescan migration versions before integration**

List migrations from the main checkout, every worktree, and visible branch. Confirm no other file resolves to Flyway version `33.20260825.1`. Confirm `V33__doctor_institution_project_full_edit.sql` is integrated before this migration in any persistent deployment sequence. If a same-version migration exists, rename this file to the next unused `V33_20260825_N` and update the migration test assertion before running it again.

- [ ] **Step 2: Run backend focused tests once**

```powershell
.\gradlew.bat test --tests com.joysong.server.legal.service.LegalDocumentHtmlSanitizerTest --tests com.joysong.server.legal.service.LegalDocumentServiceTest --tests com.joysong.server.legal.controller.LegalDocumentHttpTest --tests com.joysong.server.config.SecurityConfigLegalDocumentsTest
```

Expected: all selected classes pass.

- [ ] **Step 3: Run the isolated migration test once**

Run Task 1 Step 2 and capture the printed host/database. Expected: fresh migration passes and database is exactly `myapp_worktree_legal_documents`.

- [ ] **Step 4: Run admin verification once**

```powershell
npm test -- src/pages/LegalDocumentsPage.test.tsx src/layouts/AdminLayout.test.tsx src/legalDocuments.test.ts src/components/RichTextEditor.test.tsx
npm run lint
npm run build
```

Expected: tests, lint, and build pass.

- [ ] **Step 5: Run Flutter verification once**

Run Task 7 Steps 6 and 7. Expected: focused widget tests and analyze pass.

- [ ] **Step 6: Audit the original requirements against authoritative evidence**

Verify each item directly:

1. Admin can create/edit bilingual drafts and publish both document types.
2. Published content survives reload and appears in history as immutable.
3. Public JSON and HTML expose only current published content anonymously.
4. Login and registration retain checkbox plus both links.
5. Settings exposes both working buttons.
6. Each user entry displays complete current content in the selected language.
7. No auth request carries versions and no consent table exists.
8. No PDF/Word upload or WebView was introduced.
9. Master remains untouched; all implementation commits exist only on `codex/legal-documents`.

- [ ] **Step 7: Commit any final test-only corrections**

```powershell
git status --short
git add -- joysong-server joysong-admin joysong-flutter docs
git commit -m "test: verify legal document workflow"
```

Skip this commit when the worktree is already clean.

