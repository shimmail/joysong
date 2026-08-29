# 服务费退款多凭证实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `TRAVEL_GROUND_SERVICE_ONLY` 服务费退款增加 0 到 5 个私有图片/PDF 凭证，并让管理员通过鉴权接口查看，同时保持历史医疗退款和 `evidenceUrl` 兼容。

**Architecture:** Flutter 在提交前持有结构化文件列表，服务费退款通过现有路径的 multipart 分支一次提交；后端在单一事务中创建退款、写入共享私有文件表并建立规范化关联。管理员页面只用带认证的 blob 请求读取新凭证，旧 `evidenceUrl` 继续作为明确标记的历史公开链接展示。

**Tech Stack:** Kotlin 2 / Spring Boot / Spring Security / JPA + `JdbcTemplate` / Flyway / MySQL 8 / JUnit 5 + MockK；Flutter/Dart / `HttpClient` multipart / widget tests；React 18 / TypeScript / Ant Design / Axios / Vitest + Testing Library。

**Spec:** `docs/plan/service-fee-refund-evidence-20260829.md`

## Global Constraints

- 只改变 `paymentFlow=TRAVEL_GROUND_SERVICE_ONLY` 的服务费退款凭证；历史医疗退款资格、JSON 请求、`evidenceUrl`、退款金额与状态机保持兼容。
- 新凭证可选，最多 5 个；每个最多 `10 * 1024 * 1024` 字节；仅接受 `image/jpeg`、`image/png`、`image/webp`、`application/pdf`。
- 服务端同时验证扩展名、声明 MIME 和文件签名；允许签名固定为 JPEG `FF D8 FF`、PNG 标准 8 字节、WebP `RIFF....WEBP`、PDF `%PDF-`。
- 新凭证只保存在私有目录；响应只能返回 `fileId`、`originalName`、`contentType`、`sizeBytes`、`position`，不得返回路径、`storageKey` 或匿名 URL。
- 退款记录、`private_files`、`refund_evidence_files` 与物理文件写入必须原子完成；回滚必须清理本次已经写入的每个物理文件。
- 提交成功后没有增、删、换附件接口；账户注销保留已绑定退款凭证，直到另行确定合规保留政策。
- 管理员内容接口固定为 `GET /api/admin/refunds/{refundId}/evidence/{fileId}/content`，并返回 `Cache-Control: no-store`、`X-Content-Type-Options: nosniff` 与安全 UTF-8 inline 文件名。
- 不新增原生多选、存储权限、Word/Excel/压缩包、病毒扫描、OCR、缩略图或对象存储签名 URL。
- 迁移固定为 `V37__add_refund_evidence_files.sql`；不编辑 `B33__current_schema.sql`，不修改 `refunds.evidence_url`。
- 当前 worktree 为 `worktree_service_fee_refund_evidence`；隔离数据库名固定为 `myapp_worktree_worktree_service_fee_refund_evidence`，若使用 Compose，项目名固定为 `myapp-worktree_service_fee_refund_evidence`。
- 迁移前必须打印数据库主机与数据库名；不得连接共享开发库；不得删除或重置不以 `myapp_worktree_` 开头的数据库。
- 每项功能按 RED → GREEN → REFACTOR；先跑最小相关测试，相关测试通过后每个技术栈最多运行一次全量检查，超过 10 分钟停止，不重复运行已通过的命令。
- 不做无关重构，不引入新依赖，不创建重复上传能力；复用现有 `ApiClient.postMultipart`、`AppFilePicker` 原生单选实现和管理端 `api` Axios 实例。
- 主工作区的 `docs/guide/支付开发与云服务器部署指南.md` 是用户未提交且与分支基线不同的内容；本计划不得复制或覆盖主工作区版本，只能在本 worktree 内迁移并编辑本分支跟踪的指南。
- 沙箱中的 Git 命令统一使用 `git -c safe.directory=D:/code/kotlin/joysong/.worktrees/worktree_service_fee_refund_evidence`，不得修改用户全局 Git 配置。

## Baseline Evidence

- 后端基线：`RefundServiceTest` 与 `AdminRefundControllerTest` 已使用离线 Gradle 缓存通过；不要重复运行同一基线命令。
- 管理端基线：`PaymentOperationsPages.test.tsx` 共 18 项，16 项通过，2 项在功能开发前已超时；新增测试先按测试名运行，整文件最多再运行一次并把相同超时记录为既有环境证据。
- Flutter 基线：`pub get` 曾因共享 Flutter/Dart 进程锁无输出而终止；后续只重试一次必要命令，若仍为锁冲突则记录环境阻塞，不循环重试。

## File and Interface Map

| 单元 | 职责 | 稳定接口 |
| --- | --- | --- |
| `common/privatefile` | 私有文件校验、写入、元数据、回滚清理和安全读取 | `PrivateFileStorageService.store`、`loadActive` |
| `refund/service/RefundEvidenceFileService` | 退款附件数量策略、关联顺序与管理员按退款读取 | `storeForRefund`、`listForRefund`、`loadContentForAdmin` |
| `RefundWorkflowPersistenceService` | 在既有退款事务内挂接附件保存 | `prepareTravelServiceApplicationWithEvidence` |
| Flutter refund models/data | 本地草稿、服务端元数据与 multipart 映射 | `RefundEvidenceDraft`、`RefundEvidenceFile`、`requestServiceFeeRefund` |
| `RefundApplyPage` | 仅服务费显示多凭证列表；失败保留表单 | `onSubmit(OrderRefundDraft)` |
| `RefundEvidenceSection` | 管理员鉴权预览/下载和 object URL 生命周期 | `evidenceContentPath`、`legacyEvidenceUrls` |

---

### Task 1: 共享私有文件内核、V37 与退款凭证持久化

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/common/privatefile/PrivateFileModels.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/common/privatefile/PrivateFileStorageService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/PrivateIdentityFileService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/refund/dto/RefundEvidenceFileResponse.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundEvidenceFileService.kt`
- Create: `joysong-server/src/main/resources/db/migration/V37__add_refund_evidence_files.sql`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/common/privatefile/PrivateFileStorageServiceTransactionTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/refund/service/RefundEvidenceFileServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/PrivateIdentityFileServiceTransactionTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt`

**Interfaces:**
- Consumes: existing `private_files` and `user_media_assets` schema, `UserMediaAssetService.register`, `MultipartFile` and the storage properties currently used by `PrivateIdentityFileService`.
- Produces:

```kotlin
data class PrivateFilePolicy(
    val allowedContentTypes: Map<String, String>,
    val maxFileSizeBytes: Long = 10L * 1024 * 1024,
)

data class StoredPrivateFile(
    val fileId: String,
    val ownerUserId: String,
    val purpose: String,
    val storageKey: String,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long,
)

data class PrivateFileDownload(
    val path: Path,
    val originalName: String,
    val contentType: String,
)

class PrivateFileStorageService {
    fun store(
        ownerUserId: String,
        purpose: String,
        file: MultipartFile,
        policy: PrivateFilePolicy,
        assetType: String,
    ): StoredPrivateFile

    fun loadActive(fileId: String): PrivateFileDownload
}

data class RefundEvidenceFileResponse(
    val fileId: String,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long,
    val position: Int,
)

data class RefundEvidenceFileContent(
    val path: Path,
    val originalName: String,
    val contentType: String,
)

class RefundEvidenceFileService {
    fun storeForRefund(
        refundId: String,
        userId: String,
        files: List<MultipartFile>,
    ): List<RefundEvidenceFileResponse>

    fun listForRefund(refundId: String): List<RefundEvidenceFileResponse>

    fun loadContentForAdmin(
        refundId: String,
        fileId: String,
    ): RefundEvidenceFileContent
}
```

- [ ] **Step 1: Write RED tests for private storage and ordered refund associations**

Add tests whose literal behavior matrix is:

```text
PrivateFileStorageServiceTransactionTest
- store accepts valid JPEG, PNG, WebP and PDF signatures at exactly 10 MiB
- store rejects empty files, 10 MiB + 1 byte, unsupported MIME, mismatched extension, and mismatched signature
- a transaction rollback deletes every physical file written in that transaction and rolls back metadata
- stored originalName contains no CR, LF, slash, backslash, quote or control character and is at most 255 characters

RefundEvidenceFileServiceTest
- storeForRefund([]) returns [] and writes no rows
- storeForRefund(five valid files) returns positions [0,1,2,3,4] in submission order
- storeForRefund(six files) throws before PrivateFileStorageService.store is called
- listForRefund returns only ACTIVE, non-deleted, purpose REFUND_EVIDENCE files ordered by position
- loadContentForAdmin requires both refundId and fileId to match the same association

PrivateIdentityFileServiceTransactionTest
- existing identity upload response, rollback cleanup and submitted-admin-read cases still pass through the delegated storage service
```

Use hand-built byte arrays for each signature and assert persisted rows/files, not only calls on mocks. The production mutation each test catches is the corresponding missing validation, wrong order, missing association predicate or missing rollback cleanup.

- [ ] **Step 2: Run the new unit tests and capture the expected RED state**

Run from `joysong-server`:

```powershell
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'
.\gradlew.bat test --offline --tests com.joysong.server.common.privatefile.PrivateFileStorageServiceTransactionTest --tests com.joysong.server.refund.service.RefundEvidenceFileServiceTest
```

Expected RED: compilation or assertions fail because `PrivateFileStorageService` and `RefundEvidenceFileService` do not exist; after a test compiles, its first observable failure must name the missing validation/association/rollback behavior.

- [ ] **Step 3: Implement the shared private storage with no identity API change**

Move these responsibilities from `PrivateIdentityFileService` into `PrivateFileStorageService`: path containment, MIME/extension/signature verification, 10 MiB limit, SHA-256, `private_files` insert, `user_media_assets` registration and transaction `afterCompletion` cleanup. Keep identity purpose whitelists, draft deletion and submitted identity lookups in `PrivateIdentityFileService`, delegating only common storage/read behavior.

Use the exact refund policy:

```kotlin
private val refundEvidencePolicy = PrivateFilePolicy(
    allowedContentTypes = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "application/pdf" to "pdf",
    ),
)
```

Normalize `jpeg` as an accepted extension for `image/jpeg` without changing the stored canonical extension. Register refund files with purpose and asset type `REFUND_EVIDENCE` and storage class `LOCAL_PRIVATE`. Never expose `storageKey` from the refund service.

- [ ] **Step 4: Add V37 and the JDBC association service**

Create the migration exactly as follows:

```sql
CREATE TABLE refund_evidence_files (
    file_id VARCHAR(36) NOT NULL,
    refund_id VARCHAR(36) NOT NULL,
    position SMALLINT UNSIGNED NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (file_id),
    UNIQUE KEY uk_refund_evidence_position (refund_id, position),
    KEY idx_refund_evidence_refund (refund_id),
    CONSTRAINT fk_refund_evidence_file
        FOREIGN KEY (file_id) REFERENCES private_files(id),
    CONSTRAINT fk_refund_evidence_refund
        FOREIGN KEY (refund_id) REFERENCES refunds(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

Implement `RefundEvidenceFileService` with `JdbcTemplate`. Validate `files.size <= 5` before storing any file, insert one association immediately after each successful store, and query with:

```sql
WHERE ref.refund_id = ?
  AND pf.purpose = 'REFUND_EVIDENCE'
  AND pf.status = 'ACTIVE'
  AND pf.deleted_at IS NULL
ORDER BY ref.position ASC
```

The administrator content query must additionally constrain `ref.file_id = ?`; missing relation and missing physical file both become the same not-found domain error.

- [ ] **Step 5: Verify GREEN for storage, association and identity regressions**

Run once:

```powershell
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'
.\gradlew.bat test --offline --tests com.joysong.server.common.privatefile.PrivateFileStorageServiceTransactionTest --tests com.joysong.server.refund.service.RefundEvidenceFileServiceTest --tests com.joysong.server.identity.service.PrivateIdentityFileServiceTransactionTest
```

Expected: all selected tests pass; no test leaves files outside its temporary directory.

- [ ] **Step 6: Extend and run the fresh-database migration test**

Rename the existing test to `fresh database applies B33 baseline through V37 refund evidence`. Assert Flyway history contains B33 and V34–V37; assert `refund_evidence_files` has primary key `file_id`, unique key `uk_refund_evidence_position`, index `idx_refund_evidence_refund`, and both named foreign keys.

Before running, print the resolved isolated target and then run only the migration class:

```powershell
$worktreeId = Split-Path -Leaf (Resolve-Path '..')
$databaseName = 'myapp_worktree_' + ($worktreeId -replace '[^A-Za-z0-9_]', '_')
"database host: Testcontainers MySQL"
"database name: $databaseName"
if (-not $databaseName.StartsWith('myapp_worktree_')) { throw 'Unsafe database name' }
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'
.\gradlew.bat test --offline --tests com.joysong.server.migration.BaselineMigrationIntegrationTest
```

Expected printed name: `myapp_worktree_worktree_service_fee_refund_evidence`. Expected test result: PASS against a newly created empty MySQL container.

- [ ] **Step 7: Commit Task 1**

```powershell
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/worktree_service_fee_refund_evidence add joysong-server/src/main/kotlin/com/joysong/server/common/privatefile joysong-server/src/main/kotlin/com/joysong/server/identity/service/PrivateIdentityFileService.kt joysong-server/src/main/kotlin/com/joysong/server/refund/dto/RefundEvidenceFileResponse.kt joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundEvidenceFileService.kt joysong-server/src/main/resources/db/migration/V37__add_refund_evidence_files.sql joysong-server/src/test/kotlin/com/joysong/server/common/privatefile joysong-server/src/test/kotlin/com/joysong/server/refund/service/RefundEvidenceFileServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/identity/service/PrivateIdentityFileServiceTransactionTest.kt joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/worktree_service_fee_refund_evidence commit -m "feat(server): persist private refund evidence"
```

---

### Task 2: 原子 multipart 退款、元数据响应、管理员内容与注销保留

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/refund/dto/RefundDetailResponse.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundWorkflowPersistenceService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/controller/OrderController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminRefundController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/user/deletion/UserMediaAssetService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/user/deletion/AccountDeletionDataEraser.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/refund/service/RefundServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/order/controller/OrderControllerTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/AdminRefundControllerTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/AdminRefundEvidenceSecurityHttpTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/user/deletion/AccountDeletionDataEraserTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/user/deletion/AccountDeletionDataEraserMySqlIntegrationTest.kt`

**Interfaces:**
- Consumes: `RefundEvidenceFileService` and DTOs from Task 1.
- Produces:

```kotlin
data class RefundDetailResponse(
    @get:JsonUnwrapped val refund: RefundEntity,
    val evidenceFiles: List<RefundEvidenceFileResponse>,
)

fun RefundWorkflowPersistenceService.prepareTravelServiceApplicationWithEvidence(
    orderId: String,
    userId: String,
    reason: String,
    description: String,
    reasonCode: String?,
    evidenceFiles: List<MultipartFile>,
): RefundPreparation

fun RefundService.applyTravelServiceRefundWithEvidence(
    orderId: String,
    userId: String,
    reason: String,
    description: String,
    reasonCode: String?,
    evidenceFiles: List<MultipartFile>,
): RefundDetailResponse

fun RefundService.getRefundDetailByOrderId(
    orderId: String,
    userId: String,
): RefundDetailResponse?
```

- [ ] **Step 1: Write RED tests for scope, atomicity, API shape and authorization**

Add these exact behavior tests:

```text
RefundServiceTest
- service fee multipart refund accepts zero evidence files
- service fee multipart refund persists five ordered private evidence files
- service fee multipart refund rejects six files before refund persistence
- service fee multipart refund rejects legacy medical order without writing private files
- invalid refund evidence rolls back refund metadata order transition and every written file
- user refund detail adds safe evidence metadata and preserves evidenceUrl
- admin refund list adds safe evidence metadata without storage path

OrderControllerTest
- multipart refund forwards repeated evidence parts in submission order
- multipart refund omits evidenceUrl and returns flat evidenceFiles metadata
- JSON refund request preserves legacy evidence URL handling

AdminRefundControllerTest
- admin refund evidence content returns no-store nosniff and safe inline disposition
- admin refund evidence content rejects a file not linked to the requested refund

AdminRefundEvidenceSecurityHttpTest
- anonymous refund evidence content request returns 401
- authenticated non-admin refund evidence content request returns 403

AccountDeletionDataEraserTest
- account erasure retains private files bound to refund evidence

AccountDeletionDataEraserMySqlIntegrationTest
- account erasure retains refund evidence file and active media asset
```

Assertions must inspect the resulting refund/order rows, association rows, response JSON/headers and retained file rows. Mocking is allowed only for provider/filesystem boundaries; do not assert only that a mock was called.

- [ ] **Step 2: Run the smallest RED groups**

Run each group once until its failure is the missing production behavior:

```powershell
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'
.\gradlew.bat test --offline --tests com.joysong.server.refund.service.RefundServiceTest --tests com.joysong.server.order.controller.OrderControllerTest
.\gradlew.bat test --offline --tests com.joysong.server.admin.controller.AdminRefundControllerTest --tests com.joysong.server.admin.controller.AdminRefundEvidenceSecurityHttpTest
.\gradlew.bat test --offline --tests com.joysong.server.user.deletion.AccountDeletionDataEraserTest
```

Expected RED: the new workflow/API/metadata/header/retention assertions fail; existing legacy JSON assertions remain green.

- [ ] **Step 3: Put multipart persistence inside the existing refund transaction**

Extract `prepareApplicationInternal` as a private method containing the current validation, locked order lookup, refund insert, order transition, log and after-commit notification. Keep public `prepareApplication` behavior unchanged and pass an empty post-create callback. Implement the new transactional method as:

```kotlin
@Transactional(rollbackFor = [Exception::class])
fun prepareTravelServiceApplicationWithEvidence(
    orderId: String,
    userId: String,
    reason: String,
    description: String,
    reasonCode: String?,
    evidenceFiles: List<MultipartFile>,
): RefundPreparation = prepareApplicationInternal(
    orderId = orderId,
    userId = userId,
    reason = reason,
    description = description,
    evidenceUrl = "",
    reasonCode = reasonCode,
    requireTravelGroundService = true,
    afterRefundCreated = { refund ->
        refundEvidenceFileService.storeForRefund(refund.id, userId, evidenceFiles)
    },
)
```

Validate the order is `TRAVEL_GROUND_SERVICE_ONLY` before the refund insert or any file write. Do not call `prepareApplication` and then upload in a second transaction.

- [ ] **Step 4: Add distinct JSON and multipart handlers on the same route**

Keep the current request class and add explicit consumes values:

```kotlin
@PostMapping("/{id}/refund", consumes = [MediaType.APPLICATION_JSON_VALUE])
fun refundOrderJson(
    @PathVariable id: String,
    @RequestBody request: RefundOrderRequest,
    authentication: Authentication,
): BaseResponse<*>

@PostMapping("/{id}/refund", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
fun refundOrderMultipart(
    @PathVariable id: String,
    @RequestParam reason: String,
    @RequestParam(required = false, defaultValue = "") description: String,
    @RequestParam(required = false) reasonCode: String?,
    @RequestPart(name = "evidenceFiles", required = false)
    evidenceFiles: List<MultipartFile>?,
    authentication: Authentication,
): BaseResponse<*>
```

Normalize a missing file part to `emptyList()`. JSON calls `applyRefund` with `evidenceUrl` exactly as before; multipart calls `applyTravelServiceRefundWithEvidence` and cannot accept `evidenceUrl`. Change `GET /{id}/refund` to return `getRefundDetailByOrderId`. Use `@JsonUnwrapped` so every existing refund field stays flat and only `evidenceFiles` is added.

- [ ] **Step 5: Add administrator-safe content response**

Add this controller method under the existing `/api/admin` mapping:

```kotlin
@GetMapping("/refunds/{refundId}/evidence/{fileId}/content")
fun evidenceContent(
    @PathVariable refundId: String,
    @PathVariable fileId: String,
): ResponseEntity<FileSystemResource> {
    val file = refundEvidenceFileService.loadContentForAdmin(refundId, fileId)
    val resource = FileSystemResource(file.path)
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.contentType))
        .contentLength(resource.contentLength())
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.X_CONTENT_TYPE_OPTIONS, "nosniff")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.inline()
                .filename(file.originalName, Charsets.UTF_8)
                .build()
                .toString(),
        )
        .body(resource)
}
```

The service must already have sanitized `originalName`. Do not add a public route or weaken the existing `/api/admin/**` role rule. Make an unlinked `fileId` indistinguishable from a missing file.

- [ ] **Step 6: Preserve refund evidence during account deletion**

Add this predicate beside every existing identity/platform-reference exclusion in `UserMediaAssetService.markPending` and both private-file queries in `AccountDeletionDataEraser.erasePrivateDrafts`:

```sql
AND NOT EXISTS (
    SELECT 1
    FROM refund_evidence_files ref
    WHERE ref.file_id = pf.id
)
```

For the media query whose private-file alias is reached through an asset row, correlate `ref.file_id` to that query's private-file ID expression. The bound `private_files` row and its `user_media_assets` row remain active; unbound drafts retain current deletion behavior.

- [ ] **Step 7: Verify GREEN with the focused backend tests**

Run only the changed classes:

```powershell
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'
.\gradlew.bat test --offline --tests com.joysong.server.refund.service.RefundServiceTest --tests com.joysong.server.order.controller.OrderControllerTest --tests com.joysong.server.admin.controller.AdminRefundControllerTest --tests com.joysong.server.admin.controller.AdminRefundEvidenceSecurityHttpTest --tests com.joysong.server.user.deletion.AccountDeletionDataEraserTest
```

Expected: all selected tests pass and legacy JSON/medical cases remain green.

Then print the isolated database target and run the MySQL retention integration test once:

```powershell
$worktreeId = Split-Path -Leaf (Resolve-Path '..')
$databaseName = 'myapp_worktree_' + ($worktreeId -replace '[^A-Za-z0-9_]', '_')
"database host: Testcontainers MySQL"
"database name: $databaseName"
if (-not $databaseName.StartsWith('myapp_worktree_')) { throw 'Unsafe database name' }
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'
.\gradlew.bat test --offline --tests com.joysong.server.user.deletion.AccountDeletionDataEraserMySqlIntegrationTest
```

Expected: PASS and the bound refund evidence remains readable after erasure.

- [ ] **Step 8: Commit Task 2**

```powershell
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/worktree_service_fee_refund_evidence add joysong-server/src/main/kotlin/com/joysong/server/refund joysong-server/src/main/kotlin/com/joysong/server/order/controller/OrderController.kt joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminRefundController.kt joysong-server/src/main/kotlin/com/joysong/server/user/deletion joysong-server/src/test/kotlin/com/joysong/server/refund joysong-server/src/test/kotlin/com/joysong/server/order/controller/OrderControllerTest.kt joysong-server/src/test/kotlin/com/joysong/server/admin/controller joysong-server/src/test/kotlin/com/joysong/server/user/deletion/AccountDeletionDataEraserTest.kt joysong-server/src/test/kotlin/com/joysong/server/user/deletion/AccountDeletionDataEraserMySqlIntegrationTest.kt
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/worktree_service_fee_refund_evidence commit -m "feat(server): accept service refund evidence"
```

---

### Task 3: Flutter 凭证模型、multipart 数据层与控制器

**Files:**
- Create: `joysong-flutter/lib/features/orders/domain/refund_evidence_models.dart`
- Modify: `joysong-flutter/lib/features/orders/domain/order_models.dart`
- Modify: `joysong-flutter/lib/features/orders/domain/orders_repository.dart`
- Modify: `joysong-flutter/lib/features/orders/data/orders_remote_data_source.dart`
- Modify: `joysong-flutter/lib/features/orders/data/orders_repository_impl.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/orders_controller.dart`
- Modify: `joysong-flutter/lib/core/files/app_file_picker.dart`
- Create: `joysong-flutter/test/features/orders/domain/refund_evidence_models_test.dart`
- Create: `joysong-flutter/test/features/orders/data/orders_remote_data_source_test.dart`
- Create: `joysong-flutter/test/features/orders/presentation/orders_controller_refund_test.dart`

**Interfaces:**
- Consumes: backend multipart fields and metadata from Task 2; existing `ApiClient.postMultipart` supports repeated `MultipartFilePart.fieldName` values.
- Produces:

```dart
final class RefundEvidenceDraft {
  RefundEvidenceDraft({
    required Uint8List bytes,
    required this.fileName,
    required this.contentType,
  }) : bytes = Uint8List.fromList(bytes);

  static const maxCount = 5;
  static const maxBytes = 10 * 1024 * 1024;
  final Uint8List bytes;
  final String fileName;
  final String contentType;
  void validate();
}

final class RefundEvidenceFile {
  const RefundEvidenceFile({
    required this.fileId,
    required this.originalName,
    required this.contentType,
    required this.sizeBytes,
    required this.position,
  });
  factory RefundEvidenceFile.fromJson(Object? json);
}

Future<RefundDetail> OrdersRepository.requestServiceFeeRefund(
  String id, {
  required String reason,
  required String description,
  String? reasonCode,
  List<RefundEvidenceDraft> evidenceFiles = const [],
});
```

Keep the existing JSON `requestRefund(... evidenceUrl)` method for legacy medical flow.

- [ ] **Step 1: Write RED model and multipart mapping tests**

Use a `RecordingApiClient extends ApiClient` that overrides `post` and `postMultipart`, records the real path/fields/file parts, then invokes the supplied decoder on a literal refund JSON fixture. Assert:

```text
refund_evidence_models_test.dart
- validates jpg/jpeg/png/webp/pdf MIME-extension pairs at exactly 10 MiB
- rejects an empty file, 10 MiB + 1, unsupported MIME and mismatched extension
- parses evidenceFiles metadata in position order and defaults a missing array to []

orders_remote_data_source_test.dart
- service fee refund posts to orders/order-1/refund with reason, description and optional reasonCode fields
- every selected file becomes a MultipartFilePart with fieldName evidenceFiles in source order
- zero files still uses postMultipart with an empty files list
- legacy medical request still uses JSON and sends evidenceUrl

orders_controller_refund_test.dart
- travel service order selects requestServiceFeeRefund even when evidenceFiles is empty
- legacy medical order selects JSON requestRefund and preserves evidenceUrl
- failed service fee request returns false, exposes an error and does not discard the draft supplied by the caller
```

The recording client is only the network boundary; assertions must also validate the decoded real `RefundDetail` and controller state.

- [ ] **Step 2: Run focused Flutter tests to verify RED**

Run from `joysong-flutter` using the workspace Flutter SDK:

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' test test/features/orders/domain/refund_evidence_models_test.dart test/features/orders/data/orders_remote_data_source_test.dart test/features/orders/presentation/orders_controller_refund_test.dart
```

Expected RED: missing model/method symbols or assertions showing JSON used instead of multipart. If the shared SDK lock produces no output, terminate this single attempt, record the lock evidence and continue implementation without repeated dependency attempts.

- [ ] **Step 3: Implement strict local draft validation and response parsing**

`RefundEvidenceDraft.validate` must trim/lowercase the extension, normalize `jpeg` to the JPEG family, enforce non-empty bytes and `<= maxBytes`, and accept only the four MIME families. `RefundEvidenceFile.fromJson` must require non-empty IDs/names/types, non-negative `sizeBytes` and `position in 0..4`. Add `evidenceFiles` to `RefundDetail`:

```dart
final List<RefundEvidenceFile> evidenceFiles;

final parsedEvidenceFiles = (map['evidenceFiles'] as List<Object?>? ?? const [])
    .map(RefundEvidenceFile.fromJson)
    .toList(growable: false);
parsedEvidenceFiles.sort((a, b) => a.position.compareTo(b.position));
```

Assign `List.unmodifiable(parsedEvidenceFiles)` to the field; callers must not be able to mutate server order.

- [ ] **Step 4: Implement explicit service-fee multipart while preserving legacy JSON**

Add `requestServiceFeeRefund` to remote/repository interfaces and implementation:

```dart
final result = await _apiClient.postMultipart<RefundDetail>(
  'orders/$id/refund',
  fields: {
    'reason': reason,
    'description': description,
    if (reasonCode != null && reasonCode.trim().isNotEmpty)
      'reasonCode': reasonCode.trim(),
  },
  files: [
    for (final evidence in evidenceFiles)
      MultipartFilePart(
        fieldName: 'evidenceFiles',
        fileName: evidence.fileName,
        contentType: evidence.contentType,
        bytes: evidence.bytes,
      ),
  ],
  decodeData: RefundDetail.fromJson,
);
```

Call `validate` on every draft and reject more than five before opening the request. Leave the old `requestRefund` JSON body unchanged.

In `OrdersController.requestRefund` accept both `evidenceUrl` and `evidenceFiles`. Branch on the currently loaded order:

```dart
final refund = current.isTravelGroundServiceOnly
    ? await _repository.requestServiceFeeRefund(
        orderId,
        reason: normalizedReason,
        description: normalizedDescription,
        reasonCode: reasonCode,
        evidenceFiles: evidenceFiles,
      )
    : await _repository.requestRefund(
        orderId,
        reason: normalizedReason,
        description: normalizedDescription,
        evidenceUrl: evidenceUrl.trim(),
      );
```

Do not infer the flow from whether the file list is empty because zero attachments is a valid service-fee multipart submission.

- [ ] **Step 5: Add the picker wrapper without native changes**

Add exactly:

```dart
Future<AppPickedFile?> pickRefundEvidence() => _pick(
  allowedExtensions: const ['jpg', 'jpeg', 'png', 'webp', 'pdf'],
);
```

Do not edit Android or iOS picker implementations; they already support one-file `pickFile` with the required extensions and memory limit.

- [ ] **Step 6: Verify GREEN and static analysis**

Run the focused tests once:

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' test test/features/orders/domain/refund_evidence_models_test.dart test/features/orders/data/orders_remote_data_source_test.dart test/features/orders/presentation/orders_controller_refund_test.dart
```

Then run once:

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' analyze lib/core/files/app_file_picker.dart lib/features/orders
```

Expected: tests pass and analysis reports no new issue. Do not repeat if the same shared SDK lock recurs.

- [ ] **Step 7: Commit Task 3**

```powershell
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/worktree_service_fee_refund_evidence add joysong-flutter/lib/core/files/app_file_picker.dart joysong-flutter/lib/features/orders joysong-flutter/test/features/orders
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/worktree_service_fee_refund_evidence commit -m "feat(flutter): submit private refund evidence"
```

---

### Task 4: Flutter 服务费多凭证交互与失败保留

**Files:**
- Modify: `joysong-flutter/lib/features/orders/presentation/order_detail_page.dart`
- Create: `joysong-flutter/test/features/orders/presentation/refund_apply_page_test.dart`

**Interfaces:**
- Consumes: `RefundEvidenceDraft`, `RefundDetail.evidenceFiles` and flow-aware `OrdersController.requestRefund` from Task 3.
- Produces:

```dart
final class OrderRefundDraft {
  const OrderRefundDraft({
    required this.reason,
    required this.description,
    this.reasonCode,
    this.evidenceUrl = '',
    this.evidenceFiles = const [],
  });
  final String reason;
  final String description;
  final String? reasonCode;
  final String evidenceUrl;
  final List<RefundEvidenceDraft> evidenceFiles;
}

class RefundApplyPage extends StatefulWidget {
  const RefundApplyPage({
    required this.order,
    required this.onSubmit,
    this.canUploadLegacyEvidence = false,
    this.onPickLegacyEvidence,
    this.onPickRefundEvidence,
    this.enableAutoTranslation = false,
    super.key,
  });
  final Order order;
  final Future<bool> Function(OrderRefundDraft draft) onSubmit;
  final bool canUploadLegacyEvidence;
  final Future<String?> Function()? onPickLegacyEvidence;
  final Future<RefundEvidenceDraft?> Function()? onPickRefundEvidence;
}
```

- [ ] **Step 1: Write RED widget tests for the complete user flow**

Build literal service-fee and legacy-medical `Order` fixtures and pump the real page. Add:

```text
- service fee page shows 退款凭证（选填）, 0/5 and accepts jpg/pdf drafts in order
- service fee page removes one selected item and disables add at five
- service fee page rejects an oversize or unsupported draft without changing the list
- legacy medical page does not show the private multi-file list and still invokes the legacy public evidence picker
- submit disables add/remove/submit until completion and sends the exact ordered draft
- failed submit keeps reason description and every selected file on screen
- successful submit pops once
- read-only refund card shows evidence count and ordered originalName values
```

Use keys `refund-evidence-add`, `refund-evidence-count`, `refund-evidence-remove-{position}` and `refund-submit`. Assert visible state and navigation, not mocked widget existence.

- [ ] **Step 2: Run the widget file and verify RED**

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' test test/features/orders/presentation/refund_apply_page_test.dart
```

Expected RED: new labels/keys/list behavior and submit retention are absent.

- [ ] **Step 3: Keep submission inside the page**

Change `OrderDetailPage._requestRefund` so it pushes a page with an `onSubmit` callback. The callback calls the controller and returns its boolean; the page pops only on `true`. On false, leave controllers and file list untouched and render the controller error.

For service-fee orders, `onPickRefundEvidence` calls `widget.filePicker.pickRefundEvidence()` and converts the returned `AppPickedFile`:

```dart
return RefundEvidenceDraft(
  bytes: picked.bytes,
  fileName: picked.fileName,
  contentType: picked.mimeType,
);
```

For legacy medical orders, keep `_pickAndUpload(PublicMediaPurpose.review)` and `evidenceUrl` behavior. Do not upload a service-fee refund file through the public media endpoint.

- [ ] **Step 4: Render and manage the private evidence list**

Only when `order.isTravelGroundServiceOnly`:

- render “退款凭证（选填）” / “Refund evidence (optional)” and `count/5`;
- add one picked item per picker invocation;
- show image thumbnail from bytes for image MIME, a PDF/file icon otherwise, file name, MIME and formatted size;
- allow removal by current index before submission;
- disable add at five and all edits while picking/submitting;
- validate client-side and show a localized error while preserving current items.

The submit callback receives `List.unmodifiable(_evidenceFiles)`. Keep `description` maximum compatible with the backend 1000-character limit; do not silently truncate.

- [ ] **Step 5: Add read-only evidence metadata to the refund card**

When `RefundDetail.evidenceFiles` is non-empty, display the count and each ordered `originalName`. Do not create a user content URL or add post-submit edit actions.

- [ ] **Step 6: Verify GREEN**

Run the widget test once:

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' test test/features/orders/presentation/refund_apply_page_test.dart
```

Then run analysis once if Task 3 analysis did not already cover the final UI diff:

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' analyze lib/features/orders/presentation/order_detail_page.dart
```

Expected: all widget tests pass and no new analysis issue.

- [ ] **Step 7: Commit Task 4**

```powershell
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/worktree_service_fee_refund_evidence add joysong-flutter/lib/features/orders/presentation/order_detail_page.dart joysong-flutter/test/features/orders/presentation/refund_apply_page_test.dart
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/worktree_service_fee_refund_evidence commit -m "feat(flutter): manage multiple refund evidence files"
```

---

### Task 5: 管理员鉴权 blob 预览、下载与旧链接隔离

**Files:**
- Modify: `joysong-admin/src/pages/RefundsPage.tsx`
- Modify: `joysong-admin/src/pages/PaymentOperationsPages.test.tsx`

**Interfaces:**
- Consumes: admin refund `evidenceFiles` metadata and content endpoint from Task 2.
- Produces:

```typescript
type RefundEvidenceFile = {
  fileId: string;
  originalName: string;
  contentType: string;
  sizeBytes: number;
  position: number;
};

const evidenceContentPath = (refundId: string, fileId: string) =>
  `/admin/refunds/${refundId}/evidence/${fileId}/content`;

declare const legacyEvidenceUrls: (value: unknown) => string[];
declare const formatFileSize: (bytes: number) => string;
```

- [ ] **Step 1: Add RED tests to the existing payment operations file**

Use the existing mocked Axios instance and browser URL spies. Add these exact tests:

```text
- refunds table and detail show new evidence count and ordered metadata
- authenticated image preview requests a blob and revokes its object URL when detail closes
- PDF preview requests a blob and opens only the generated blob URL with noopener
- evidence download failure shows an error without locking approve reject or retry actions
- legacy evidence accepts at most five absolute http or https URLs and rejects data blob file javascript relative and protocol-relative values
```

Assert `api.get(contentPath, { responseType: 'blob' })`, the rendered filename/type/size, `URL.createObjectURL`, `URL.revokeObjectURL` and that review buttons remain enabled after preview failure. Do not render a direct authenticated `<img src="/api/...">`.

- [ ] **Step 2: Run only the five new test names and verify RED**

From `joysong-admin`:

```powershell
npm test -- src/pages/PaymentOperationsPages.test.tsx --testNamePattern "evidence|legacy evidence|PDF preview"
```

Expected RED: evidence count/controls are absent and no authenticated blob request occurs.

- [ ] **Step 3: Implement typed parsing and the evidence section in the same page file**

Keep the change local to `RefundsPage.tsx`; do not create another component file. Implement:

```typescript
const legacyEvidenceUrls = (value: unknown): string[] => {
  if (typeof value !== 'string') return [];
  return value
    .split(',')
    .map(item => item.trim())
    .filter(Boolean)
    .flatMap(item => {
      try {
        const url = new URL(item);
        return url.protocol === 'http:' || url.protocol === 'https:' ? [url.href] : [];
      } catch {
        return [];
      }
    })
    .slice(0, 5);
};

const formatFileSize = (bytes: number): string => {
  if (!Number.isFinite(bytes) || bytes < 0) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};
```

Because `new URL('/relative')` throws without a base and `new URL('//host')` also throws, relative/protocol-relative values remain rejected. Label these links “旧版凭证（公开链接）”.

- [ ] **Step 4: Fetch every new preview/download through authenticated Axios**

For an image, request a blob, create one object URL and render the preview. For PDF preview, request a blob, create a URL, call:

```typescript
window.open(objectUrl, '_blank', 'noopener,noreferrer');
```

For download, request the same endpoint as a blob, create a temporary anchor with `download=originalName`, click it, remove it and revoke the URL. Maintain a ref-backed set/map of every live object URL; revoke all when replacing a preview, closing the detail modal and unmounting.

Preview state and errors must be independent of `reviewingRefundId`, `blockedReviewIds` and `retryingRefundId`. A failed blob request shows `message.error` for that file only.

- [ ] **Step 5: Render count in the table and details only in the modal**

Add a compact “凭证” column whose value is `evidenceFiles.length + legacyEvidenceUrls(evidenceUrl).length`. In the detail modal render ordered new metadata and old links separately. Do not auto-fetch any blob for the table.

- [ ] **Step 6: Verify GREEN, lint and build**

Run the five new tests once:

```powershell
npm test -- src/pages/PaymentOperationsPages.test.tsx --testNamePattern "evidence|legacy evidence|PDF preview"
```

Run the whole modified test file once:

```powershell
npm test -- src/pages/PaymentOperationsPages.test.tsx
```

If only the two baseline tests named in `Baseline Evidence` time out again, record them as pre-existing and do not retry. Then run:

```powershell
npm run lint
npm run build
```

Expected: every new evidence test passes; lint/build report no new error.

- [ ] **Step 7: Commit Task 5**

```powershell
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/worktree_service_fee_refund_evidence add joysong-admin/src/pages/RefundsPage.tsx joysong-admin/src/pages/PaymentOperationsPages.test.tsx
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/worktree_service_fee_refund_evidence commit -m "feat(admin): view private refund evidence"
```

---

### Task 6: API、运维指南、UML 与一次性跨栈验证

**Files:**
- Modify: `docs/FLUTTER_API_CONTRACT.md`
- Move: `docs/支付开发与云服务器部署指南.md` → `docs/guide/支付开发与云服务器部署指南.md`
- Modify: `docs/guide/支付开发与云服务器部署指南.md`
- Modify: `design/TRAVEL_GROUND_SERVICE_PAYMENT_WORKFLOW.puml`

**Interfaces:**
- Consumes: final endpoint, request fields, response fields, storage behavior and UI behavior from Tasks 1–5.
- Produces: developer API contract, deployment/private-storage instructions and renderable PlantUML source.

- [ ] **Step 1: Update the API contract with exact request and response shapes**

Under the refund section document both content types:

```http
POST /api/orders/{orderId}/refund
Content-Type: multipart/form-data

reason=行程取消
description=无法按期出行
reasonCode=CUSTOMER_REQUEST
evidenceFiles=@receipt.pdf;type=application/pdf
evidenceFiles=@photo.jpg;type=image/jpeg
```

Document allowed MIME types, 10 MiB per file, service-fee-only scope, atomic failure behavior and the flat response field:

```json
{
  "evidenceUrl": "",
  "evidenceFiles": [
    {
      "fileId": "uuid",
      "originalName": "receipt.pdf",
      "contentType": "application/pdf",
      "sizeBytes": 245760,
      "position": 0
    }
  ]
}
```

Also document the admin content route and `no-store`/`nosniff` headers. Keep the existing public-media section and explicitly state it is not used for new service-fee refund evidence.

- [ ] **Step 2: Move and update only the worktree guide**

Create `docs/guide` if absent, move the tracked worktree file, and add a “服务费退款私有凭证” section that states:

```text
- private upload directory must not be mounted under /images or any static web root
- the application process needs read/write permission; the reverse proxy must have no direct route to it
- accepted types are JPG/JPEG, PNG, WebP and PDF; per-file limit is 10 MiB; count limit is 5
- administrators read through the authenticated /api/admin/refunds/{refundId}/evidence/{fileId}/content endpoint
- responses are no-store and nosniff; logs must not print storage keys or local paths
- account deletion retains linked refund evidence; no manual purge is authorized until a retention policy is approved
- migration verification uses myapp_worktree_worktree_service_fee_refund_evidence and never a shared development database
```

Do not read or copy `D:\code\kotlin\joysong\docs\guide\支付开发与云服务器部署指南.md` from the main checkout.

- [ ] **Step 3: Extend the PlantUML workflow**

Add these participants and messages while preserving the existing payment/refund states:

```plantuml
participant "Flutter 退款页" as RefundPage
participant "私有文件服务" as PrivateFiles
participant "管理员退款页" as AdminRefunds

RefundPage -> API: multipart 退款申请\nreason + description + 0..5 evidenceFiles
API -> PrivateFiles: 同一事务校验并保存私有凭证
PrivateFiles --> API: 安全元数据（无 URL/路径）
API --> RefundPage: 退款详情 + evidenceFiles
note right of RefundPage
  提交前可增删
  提交成功后冻结
end note
AdminRefunds -> API: 鉴权读取 /admin/refunds/{refundId}/evidence/{fileId}/content
API -> PrivateFiles: 校验 refundId 与 fileId 关联
PrivateFiles --> AdminRefunds: image/PDF blob\nno-store + nosniff
```

Keep the diagram syntactically valid; the maintainer will regenerate images manually.

- [ ] **Step 4: Run documentation consistency checks**

```powershell
rg -n "evidenceFiles|REFUND_EVIDENCE|no-store|nosniff|myapp_worktree_worktree_service_fee_refund_evidence" docs design
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/worktree_service_fee_refund_evidence diff --check
```

Expected: the API contract, guide and UML each contain the relevant terms; `git diff --check` has no whitespace error. Documentation-only edits require no additional code test.

- [ ] **Step 5: Run at most one final check per technical stack**

Run a backend full suite once with a 10-minute controller timeout:

```powershell
$env:GRADLE_USER_HOME = 'D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'
.\gradlew.bat test --offline
```

Run the Flutter test suite once only if the shared SDK is responsive:

```powershell
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' test
```

Do not rerun the admin test file already exercised in Task 5; `npm run lint` and `npm run build` from Task 5 are its final checks. Stop any full command after 10 minutes and report elapsed progress and the slowest visible test rather than retrying.

- [ ] **Step 6: Commit Task 6**

```powershell
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/worktree_service_fee_refund_evidence add docs/FLUTTER_API_CONTRACT.md docs/guide/支付开发与云服务器部署指南.md design/TRAVEL_GROUND_SERVICE_PAYMENT_WORKFLOW.puml
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/worktree_service_fee_refund_evidence add -u docs/支付开发与云服务器部署指南.md
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/worktree_service_fee_refund_evidence commit -m "docs: describe private refund evidence"
```

---

## Final Acceptance Checklist

- [ ] A service-fee refund accepts 0, 1 and 5 valid files and rejects 6 or any invalid file before creating a durable refund.
- [ ] The same transaction owns refund creation, private metadata and associations; rollback leaves no database row or physical file.
- [ ] A zero-file service-fee submission still uses multipart; a legacy medical submission still uses the original JSON `evidenceUrl` contract.
- [ ] User/admin JSON exposes ordered safe metadata only.
- [ ] Only an administrator can read content, and `refundId` must match `fileId`; headers are `no-store` and `nosniff`.
- [ ] Flutter allows repeated one-file selection, removal before submit, no edits during submit, failure retention and read-only names afterward.
- [ ] Admin image/PDF access uses authenticated blobs, revokes every object URL, and old links accept only absolute HTTP(S).
- [ ] Account deletion retains bound refund evidence and its active media asset.
- [ ] V37 passes against a fresh isolated database named `myapp_worktree_worktree_service_fee_refund_evidence`.
- [ ] Focused tests, static checks and at most one bounded full check per stack have recorded outcomes.
- [ ] API contract, guide and PlantUML match the code; final handoff reminds the maintainer to regenerate UML images manually.
