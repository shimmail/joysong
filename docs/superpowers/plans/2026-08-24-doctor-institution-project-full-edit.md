# 机构项目与医生服务管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task with review checkpoints.

**Goal:** 让医生通过版本化申请完整编辑机构项目共享资料和本人的价格/上架状态，由机构法人原子审核生效，并让公开展示、候选医生、最低价与下单统一遵守医生级上架状态。

**Architecture:** 保留现有 `doctor_project_change_requests` 与 v1 路径，在同一申请账本上增加 `payloadVersion=2`、严格快照、canonical revision 和定价策略 revision。后端先冻结 v2 契约与原子审批，再让 Flutter 与 Web 后台统一切换到 v2；机构共享变更用 `institution_projects.version` 防止覆盖，医生私有变更只更新申请医生。审核预览由纯展示 view model/component 复用，创建申请通过独立 adapter 接入，不把创建申请伪装成 v2 编辑快照。

**Tech Stack:** Kotlin 1.9.22、Spring Boot 3.2.2、Spring Data JPA/JdbcTemplate、Flyway、MySQL、JUnit/MockMvc/Testcontainers；Flutter/Dart；React 19、TypeScript 6、Ant Design、Vitest；PlantUML。

**Spec:** `docs/superpowers/specs/2026-08-24-doctor-institution-project-full-edit-design.md`

## Global Constraints

- 只在 worktree `D:\code\kotlin\joysong\.worktrees\doctor-institution-project-full-edit`、分支 `codex/doctor-institution-project-full-edit` 中实施。
- 数据库隔离固定为 `WORKTREE_ID=worktree_doctor_institution_project_full_edit`、`DATABASE_NAME=myapp_worktree_doctor_institution_project_full_edit`、Docker Compose project `myapp-worktree-doctor-institution-project-full-edit`。
- 任何迁移前必须打印实际解析出的数据库 host 与 name；不得连接共享开发库，不得 drop/reset 不以 `myapp_worktree_` 开头的数据库。
- 后端错误响应统一返回机器可读字段 `errorCode`。该字段使用 NON_NULL 序列化：成功响应和既有 v1 响应继续省略它，避免改变旧 wire shape；带稳定码的错误响应精确包含 `code,message,errorCode,data`。
- 后台直接更新机构项目的 PUT 请求冻结使用 `baseVersion`；版本过期返回 HTTP 409 和 `errorCode=INSTITUTION_PROJECT_VERSION_STALE`。
- v2 `PROFILE_UPDATE` 请求只允许规格列出的 15 个键；v2 review 只允许 `decision,reviewNote,force,forceBaseRevision` 四个键。
- v2 不提交或写入排期、机构/平台/医生关联 ID、币种、原价、评分、评价数、机构级 `isActive`、分账或面诊字段。
- 医生币种固定 USD；旅游地接服务费由服务端价格策略计算，客户端只显示预览。
- `sharedChanged=false` 时不得写入或递增 `institution_projects.version`；`doctorActive` 只影响申请医生。
- 任何审核权限都必须在锁内实时校验，不能信任页面加载时的 `managedInstitutionIds`。
- 全局锁序冻结为：已有申请行（如有）→ 审核权限关系行（如有）→ 按医生 ID 排序的医生-机构执业关系行 → 机构项目 → 平台项目 → 按 ID 排序的医生项目行 → 按 ID 排序的兼容配置行；提交、审批和后台直接更新均不得反向加锁。
- 缓存只能在事务成功提交后失效。
- 失败后只重跑失败用例或相关测试；相关测试通过后每个子项目最多执行一次全量测试，超过 10 分钟立即停止并记录最慢项。
- 发布顺序固定为：后端 v2 → Flutter/Web 审核端 → Flutter/Web 医生端 → 关闭 v1 `PROFILE_UPDATE` 提交。
- 命令工作目录固定为：Tasks 1–7 使用 `joysong-server/`，Tasks 8–11 使用 `joysong-flutter/`，Tasks 12–14 使用 `joysong-admin/`，Task 15 与未显式 `cd` 的 Task 16 命令使用 worktree 根目录。

## File Structure Map

### Backend

Create:

- `joysong-server/src/main/resources/db/migration/V33__doctor_institution_project_full_edit.sql`
- `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/DoctorProjectChangeV2Controller.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/config/ProjectChangeCompatibilityProperties.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/institution/service/DoctorProjectChangeV2Models.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/institution/service/DoctorProjectSnapshotCodec.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/project/service/InstitutionProjectPayloadPolicy.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/DoctorProjectChangeV2HttpTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectSnapshotCodecTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectFullEditPersistenceTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorInstitutionProjectFullEditMigrationTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/institution/service/InstitutionProjectVersionPersistenceTest.kt`

Modify:

- `joysong-server/src/main/kotlin/com/joysong/server/common/BaseResponse.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/common/GlobalExceptionHandler.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt`
- `joysong-server/src/main/resources/application.yml`
- `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/DoctorProjectChangeController.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/InstitutionProjectController.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/institution/entity/InstitutionProjectEntity.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/discover/entity/DoctorProjectEntity.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/institution/repository/InstitutionProjectRepository.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/discover/repository/DoctorProjectRepository.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/institution/service/InstitutionProjectDetailResolver.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/institution/service/DoctorProjectChangeService.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestService.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/order/service/TravelGroundServicePricing.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/discover/dto/PublicContentDtos.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/discover/dto/DetailDtos.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/discover/controller/DiscoverController.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/discover/service/DiscoverDetailService.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/discover/service/DiscoverService.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/discover/service/DiscoverSearchService.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/home/service/HomeService.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentCatalogService.kt`
- `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentPlanService.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/DoctorProjectChangeControllerTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/InstitutionProjectControllerTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectChangeServiceTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectProfileUpdateMigrationTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectProfileUpdatePersistenceTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/institution/service/InstitutionProjectDetailResolverTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/order/service/TravelGroundServicePricingTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestServiceTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/discover/dto/PublicContentDtosTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/discover/controller/DiscoverControllerTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/discover/service/DiscoverDetailServiceTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/discover/service/DiscoverServiceTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/discover/service/DiscoverSearchServiceTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/home/service/HomeServiceTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentCatalogServiceTest.kt`
- `joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentPlanServiceTest.kt`

### Flutter

Create:

- `joysong-flutter/lib/features/discover/presentation/institution_project_preview_body.dart`
- `joysong-flutter/lib/features/identity/presentation/institution_project_review_widgets.dart`
- `joysong-flutter/test/features/discover/catalog_project_availability_test.dart`
- `joysong-flutter/test/features/discover/institution_project_preview_body_test.dart`

Modify:

- `joysong-flutter/lib/core/network/api_exception.dart`
- `joysong-flutter/lib/core/network/api_envelope.dart`
- `joysong-flutter/lib/core/network/api_client.dart`
- `joysong-flutter/lib/features/discover/domain/discover_models.dart`
- `joysong-flutter/lib/features/discover/presentation/catalog_project_detail_view.dart`
- `joysong-flutter/lib/features/identity/domain/identity_models.dart`
- `joysong-flutter/lib/features/identity/domain/identity_repository.dart`
- `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`
- `joysong-flutter/lib/features/identity/presentation/identity_error_messages.dart`
- `joysong-flutter/lib/features/identity/presentation/identity_pages.dart`
- `joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart`
- `joysong-flutter/test/core/network/api_client_test.dart`
- `joysong-flutter/test/core/network/api_envelope_test.dart`
- `joysong-flutter/test/features/identity/doctor_project_profile_update_contract_test.dart`
- `joysong-flutter/test/features/identity/doctor_project_profile_update_page_test.dart`
- `joysong-flutter/test/features/identity/identity_error_messages_test.dart`
- `joysong-flutter/test/features/identity/identity_models_controller_test.dart`
- `joysong-flutter/test/features/identity/professional_project_request_contract_test.dart`
- `joysong-flutter/test/features/identity/professional_project_request_page_test.dart`

### Web Admin

Create:

- `joysong-admin/src/types/projectRequests.ts`
- `joysong-admin/src/types/projectRequests.test.ts`
- `joysong-admin/src/components/InstitutionProjectPreview.tsx`
- `joysong-admin/src/components/InstitutionProjectPreview.test.tsx`

Modify:

- `joysong-admin/src/api.ts`
- `joysong-admin/src/api.test.ts`
- `joysong-admin/src/layouts/AdminLayout.tsx`
- `joysong-admin/src/pages/ProjectRequestsPage.tsx`
- `joysong-admin/src/pages/ProjectRequestsPage.test.tsx`
- `joysong-admin/src/pages/ProjectCollaborationPage.tsx`
- `joysong-admin/src/pages/ProjectCollaborationPage.test.tsx`
- `joysong-admin/src/pages/InstitutionProjectsPage.tsx`
- `joysong-admin/src/pages/InstitutionProjectsPage.test.tsx`
- `joysong-admin/src/pages/DirectProjectCreationFormsRegression.test.tsx`

### Docs and UML

Modify:

- `docs/FLUTTER_API_CONTRACT.md`
- `docs/superpowers/specs/2026-08-24-doctor-project-client-management-design.md`
- `docs/superpowers/specs/2026-08-12-doctor-profile-update-request-design.md`
- `design/DOCTOR_PROJECT_APPLICATION_WORKFLOW.puml`
- `design/DOCTOR_PROJECT_APPLICATION_WORKFLOW.svg`

### Cross-stack contract fixture

Create:

- `test-fixtures/doctor-project-change-v2.json`

---

## Task 1: Add versioned storage and entity fields

**Consumes:** Confirmed v2 snapshot schema and current V32 migration head.

**Produces:** V33 schema, historical v1 backfill, doctor-level active flag, institution shared version, nullable inherited media storage, and entity mappings used by all later tasks.

**Files:**

- Create: `joysong-server/src/main/resources/db/migration/V33__doctor_institution_project_full_edit.sql`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorInstitutionProjectFullEditMigrationTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/InstitutionProjectVersionPersistenceTest.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/institution/entity/InstitutionProjectEntity.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/discover/entity/DoctorProjectEntity.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/institution/service/InstitutionProjectDetailResolver.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/InstitutionProjectController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/discover/dto/PublicContentDtos.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectProfileUpdateMigrationTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/InstitutionProjectDetailResolverTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/discover/dto/PublicContentDtosTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt`

- [ ] Write the migration test first. It must assert a fresh V26 baseline migrates through V33, historical request rows become `payload_version=1`, historical schedule values remain unchanged, existing doctor rows become active, and the new JSON/version columns have the expected null/default behavior.
- [ ] Add a MySQL JPA RED test that saves/updates a versioned institution project, soft-deletes it through the repository, and proves stale update/delete cannot affect the newer row.
- [ ] Before the migration test opens MySQL, print the host and database name resolved by `WorktreeTestDatabase` and assert the name equals `myapp_worktree_doctor_institution_project_full_edit`.
- [ ] Mark every new/modified MySQL test with `@Tag("mysql-integration")`. Replace hard-coded database names in `DoctorProjectProfileUpdateMigrationTest` and `DoctorProjectProfileUpdatePersistenceTest` with `WorktreeTestDatabase.databaseName()`; after the container starts and before Flyway runs, call the helper's validation/print operation.
- [ ] Run the new migration test and capture the expected RED failure caused by missing V33:

  ```powershell
  $env:GRADLE_USER_HOME = Join-Path $PWD '.tmp\gradle-user-home-codex'
  .\gradlew.bat mysqlIntegrationTest --tests com.joysong.server.institution.service.DoctorInstitutionProjectFullEditMigrationTest
  ```

- [ ] Add V33 without editing frozen `B26__current_schema.sql`. The migration must add:

  ```sql
  ALTER TABLE doctor_projects
      ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

  ALTER TABLE institution_projects
      ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
      MODIFY cover_image VARCHAR(500) NULL,
      MODIFY images VARCHAR(2000) NULL;

  ALTER TABLE doctor_project_change_requests
      ADD COLUMN payload_version INT NOT NULL DEFAULT 1,
      ADD COLUMN base_institution_project_version BIGINT NULL,
      ADD COLUMN base_platform_inheritance_hash CHAR(64) NULL,
      ADD COLUMN pricing_policy_revision VARCHAR(100) NULL,
      ADD COLUMN proposed_travel_ground_service_fee DECIMAL(18,2) NULL,
      ADD COLUMN shared_changed BOOLEAN NULL,
      ADD COLUMN current_project_snapshot JSON NULL,
      ADD COLUMN proposed_project_snapshot JSON NULL,
      ADD COLUMN current_doctor_is_active BOOLEAN NULL,
      ADD COLUMN proposed_doctor_is_active BOOLEAN NULL,
      ADD COLUMN approval_audit_snapshot JSON NULL;
  ```

- [ ] Update v1/v2 check constraints so v1 retains its old required fields while v2 requires snapshots, revisions, current/proposed doctor price and active state; keep historical schedule columns and allow obsolete v1 list/media columns to be null for v2 rows.
- [ ] Freeze the ledger mapping in those constraints: v2 proposed doctor `price` is stored in existing `medical_list_price`, v2 current doctor price in `current_price`; `price_suggestion` remains reserved for legacy JOIN/v1 semantics.
- [ ] Add the public lookup index `(institution_project_id, is_active, price)` to `doctor_projects`.
- [ ] Map the new entity fields without changing the meaning of institution-level `isActive`:

  ```kotlin
  @Version
  @Column(name = "version", nullable = false)
  val version: Long = 0

  @Column(name = "is_active", nullable = false)
  val isActive: Boolean = true
  ```

- [ ] Update the version-aware soft delete annotation so Hibernate receives both bind values:

  ```kotlin
  @SQLDelete(
      sql = "UPDATE institution_projects " +
          "SET deleted_at = NOW(), version = version + 1 " +
          "WHERE id = ? AND version = ?"
  )
  ```

- [ ] In the same task, make every current non-null boundary compile: resolver compatibility reads use nullable-safe normalization; admin and public legacy DTOs use `.orEmpty()`; v2 snapshot construction retains real `null`. Add regressions for both inherited null media and unchanged legacy wire values.
- [ ] Re-run the migration test and the two existing migration regressions:

  ```powershell
  .\gradlew.bat test --tests com.joysong.server.institution.service.InstitutionProjectDetailResolverTest --tests com.joysong.server.discover.dto.PublicContentDtosTest --tests com.joysong.server.admin.controller.InstitutionProjectControllerTest
  .\gradlew.bat mysqlIntegrationTest --tests com.joysong.server.institution.service.DoctorInstitutionProjectFullEditMigrationTest --tests com.joysong.server.institution.service.InstitutionProjectVersionPersistenceTest --tests com.joysong.server.institution.service.DoctorProjectProfileUpdateMigrationTest --tests com.joysong.server.migration.BaselineMigrationIntegrationTest
  ```

- [ ] Commit: `git commit -m "feat(server): add versioned doctor project change storage"`

---

## Task 2: Freeze error, snapshot, inheritance, and pricing primitives

**Consumes:** Task 1 entity/schema fields.

**Produces:** One machine-readable error envelope, strict v2 DTO/codec, reusable shared-field validation, fixed canonical hashes, and one policy snapshot for rate/revision/fee.

**Files:**

- Create: `joysong-server/src/main/kotlin/com/joysong/server/institution/service/DoctorProjectChangeV2Models.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/institution/service/DoctorProjectSnapshotCodec.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/project/service/InstitutionProjectPayloadPolicy.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectSnapshotCodecTest.kt`
- Create: `test-fixtures/doctor-project-change-v2.json`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/common/BaseResponse.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/common/GlobalExceptionHandler.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/institution/service/InstitutionProjectDetailResolver.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/TravelGroundServicePricing.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/InstitutionProjectDetailResolverTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/order/service/TravelGroundServicePricingTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/project/service/ProfessionalProjectRequestServiceTest.kt`

- [ ] Add RED tests proving success/v1 responses omit `errorCode`, coded errors include it, and covering all stable error codes, the two documented SHA-256 vectors, NFC, nulls, array order/duplicates, legacy comma-or-JSON list decoding, `Instant` precision, strict extra/missing-key rejection, and pricing revision formatting.
- [ ] Define the response/error contract:

  ```kotlin
  data class BaseResponse<T>(
      val code: Int = 200,
      val message: String = "success",
      @get:JsonInclude(JsonInclude.Include.NON_NULL)
      val errorCode: String? = null,
      val data: T? = null
  ) {
      companion object {
          fun <T> success(data: T): BaseResponse<T> = BaseResponse(data = data)
          fun <T> error(
              message: String,
              code: Int = 400,
              errorCode: String? = null
          ): BaseResponse<T> = BaseResponse(code, message, errorCode, null)
      }
  }

  enum class ProjectChangeErrorCode {
      EDIT_BASE_STALE,
      APPROVAL_BASE_STALE,
      INHERITANCE_SOURCE_STALE,
      PRICING_POLICY_STALE,
      FORCE_BASE_STALE,
      REQUEST_ALREADY_PENDING,
      REQUEST_ALREADY_HANDLED,
      CLIENT_UPGRADE_REQUIRED,
      FORCE_NOT_APPLICABLE,
      PROJECT_PAYLOAD_INVALID,
      REQUEST_SNAPSHOT_INVALID,
      INSTITUTION_PROJECT_VERSION_STALE
  }
  ```

- [ ] Use one typed domain exception carrying `HttpStatus` and `ProjectChangeErrorCode`; map it in `GlobalExceptionHandler` without changing unrelated legacy exception behavior.
- [ ] Define strict snapshot types with no extension map:

  ```kotlin
  data class InstitutionProjectSnapshotV2(
      val schemaVersion: Int,
      val association: ProjectAssociationSnapshot,
      val rawOverrides: ProjectRawOverridesSnapshot,
      val effective: ProjectEffectiveSnapshot,
      val source: ProjectSnapshotSource
  )

  data class ProjectRawOverridesSnapshot(
      val name: String?,
      val category: String?,
      val description: String?,
      val tags: List<String>?,
      val slogan: String?,
      val detailContent: String?,
      val coverImage: String?,
      val images: List<String>?
  )
  ```

- [ ] Implement one codec API and make it the only place that creates hashes:

  ```kotlin
  class DoctorProjectSnapshotCodec(private val objectMapper: ObjectMapper) {
      fun platformInheritanceHash(source: PlatformInheritanceSource): String
      fun baseRevision(source: DoctorProjectRevisionSource): String
      fun encode(snapshot: InstitutionProjectSnapshotV2): String
      fun decode(raw: String): InstitutionProjectSnapshotV2
  }
  ```

- [ ] Configure the codec's snapshot reader to fail on unknown properties and explicitly verify exact keys at every nested level before mapping. Unknown schema, invalid JSON, absent/extra keys, empty raw override arrays, and incomplete required effective values must fail closed.
- [ ] Freeze one shared golden fixture with these exact variants:
  - `targetV2` keys: `payloadVersion,institutionProjectId,institutionId,institutionName,platformProjectId,platformProjectName,doctorId,doctorName,baseRevision,currentProject,currentDoctorPrice,currentDoctorActive,platformRate,pricingPolicyRevision,travelGroundServiceFee`;
  - `requestV1`: `payloadVersion=1` plus every existing `DoctorProjectChangeView` field with its existing nullability and v1 `status/scheduleNote` names;
  - valid `requestV2` keys: `payloadVersion,id,requestType,doctorId,doctorName,institutionId,institutionName,institutionProjectId,institutionProjectName,platformProjectId,platformProjectName,baseRevision,currentProject,proposedProject,latestProject,latestRevision,sharedChanged,currentDoctorPrice,proposedDoctorPrice,latestDoctorPrice,currentDoctorActive,proposedDoctorActive,latestDoctorActive,platformRate,pricingPolicyRevision,travelGroundServiceFee,requestStatus,notes,forceProcessed,submittedBy,submittedAt,reviewedBy,reviewerName,reviewNote,reviewedAt,updatedAt,snapshotState,snapshotError,reviewable`;
  - damaged `requestV2InvalidSnapshot` with the same outer keys and metadata, `snapshotState=INVALID`, `reviewable=false`, a stable nonblank `snapshotError`, and only the unparseable snapshot slots set to `null`.
- [ ] For valid v2 rows require `snapshotState=VALID`, `snapshotError=null`, non-null current/proposed/latest snapshots, and `reviewable` derived from request status and permissions. The fixture's nested project objects use the strict snapshot schema from the confirmed spec.
- [ ] Extract shared normalization/validation into `InstitutionProjectPayloadPolicy` and make both creation and v2 editing call it. Blank optional text, empty tags, blank cover, and empty gallery normalize to `null`; `salesCount` remains an explicit non-negative integer. At the legacy creation-table boundary, encode policy nulls back to the required empty-string columns such as `slogan/cover_image` so V28 constraints and existing creation responses remain compatible.
- [ ] Extend the resolver to return both raw override and effective media while preserving legacy read compatibility.
- [ ] Read the platform rate once per quote:

  ```kotlin
  data class TravelGroundServicePolicyQuote(
      val platformRate: BigDecimal,
      val pricingPolicyRevision: String,
      val serviceFee: BigDecimal
  )

  fun quoteWithPolicy(price: BigDecimal): TravelGroundServicePolicyQuote
  ```

  The revision must be `travel-ground-service-rate:0.400000` at the current 40% configuration, and `serviceFee` must use the existing USD scale and rounding.
- [ ] Run:

  ```powershell
  .\gradlew.bat test --tests com.joysong.server.institution.service.DoctorProjectSnapshotCodecTest --tests com.joysong.server.institution.service.InstitutionProjectDetailResolverTest --tests com.joysong.server.order.service.TravelGroundServicePricingTest --tests com.joysong.server.project.service.ProfessionalProjectRequestServiceTest
  ```

- [ ] Commit: `git commit -m "feat(server): define strict project change snapshots"`

---

## Task 3: Implement v2 targets, mixed-version list, and submit

**Consumes:** Task 1 storage and Task 2 codec/policy/pricing primitives.

**Produces:** Complete v2 targets, mixed v1/v2 list models, exact v2 submission, immutable current/proposed snapshots, and stable submit conflicts.

**Files:**

- Modify: `joysong-server/src/main/kotlin/com/joysong/server/institution/service/DoctorProjectChangeService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/institution/service/DoctorProjectChangeV2Models.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/institution/repository/InstitutionProjectRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/discover/repository/DoctorProjectRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/repository/DoctorInstitutionProjectConfigRepository.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectChangeServiceTest.kt`

- [ ] Add service tests first for target raw/effective values, inactive doctor targets, exact base revision, unchanged inheritance intent, clearing to inheritance, explicit override equal to platform value, pure-private/shared change classification, missing effective required values, relationship/binding denial, duplicate pending requests, and stale base sources.
- [ ] Add version-aware service entry points while retaining the existing v1 methods:

  ```kotlin
  fun listProfileUpdateTargetsV2(actor: ManagementActor): List<DoctorProjectProfileUpdateTargetV2>
  fun listV2(actor: ManagementActor): List<DoctorProjectChangeViewV2>
  fun submitV2(
      actor: ManagementActor,
      request: DoctorProjectChangeV2Request
  ): DoctorProjectChangeViewV2
  ```

- [ ] The target response must include `payloadVersion=2`, immutable IDs/display names, `baseRevision`, raw/effective project state, current doctor price/active, platform rate, `pricingPolicyRevision`, and service fee preview. Do not filter targets by `doctor_projects.is_active`.
- [ ] In `submitV2` derive the doctor from authentication, read target IDs without locks, then reacquire and validate the active doctor relationship → institution project → platform project → doctor binding → compatibility config using the frozen global order.
- [ ] Recompute `baseRevision` from the locked state. Return 409 `EDIT_BASE_STALE` if the client token differs.
- [ ] Normalize the 15-field request, apply inheritance, validate effective values, compute `sharedChanged` from raw overrides plus `salesCount`, and derive doctor price/active independently.
- [ ] Persist `payload_version=2`, source versions/hash/revision, platform rate, service fee, current/proposed snapshots, current/proposed doctor price and active state. Do not write v1 schedule, split, relationship, or copied shared-service fields.
- [ ] Map the v2 request `price` to request-ledger `medical_list_price` and the before value to `current_price`; never overload `price_suggestion`, which remains the legacy JOIN/v1 suggestion column. On approval write `doctor_projects.price` and derive compatibility `doctor_institution_project_configs.medical_list_price` from it.
- [ ] Let different doctors submit against the same shared version; retain the existing database guarantee that one doctor/project can have only one PENDING `PROFILE_UPDATE` or `LEAVE`.
- [ ] Parse v1 and v2 list rows only through their matching parser. A damaged v2 row uses the golden invalid variant, remains visible as a data-error view, and exposes `reviewable=false`.
- [ ] On every v2 list/detail read, resolve project/doctor/config values from one consistent read transaction and return `latestProject/latestDoctorPrice/latestDoctorActive/latestRevision`. This is the only force-confirmation source; clients must not use profile-update targets as an审核详情 substitute. The later force transaction still locks and recomputes the token.
- [ ] Add tests for submit → underlying shared/private drift → refreshed list returns a new latest snapshot/revision, plus a second drift after refresh that later yields `FORCE_BASE_STALE`.
- [ ] Re-run only `DoctorProjectChangeServiceTest` until green:

  ```powershell
  .\gradlew.bat test --tests com.joysong.server.institution.service.DoctorProjectChangeServiceTest
  ```

- [ ] Commit: `git commit -m "feat(server): submit versioned project edit requests"`

---

## Task 4: Expose the exact v1/v2 route compatibility matrix

**Consumes:** Task 3 service entry points and v2 models.

**Produces:** Five v2 endpoints, unchanged v1 wire contract, strict request bodies, compatibility switch, 422/426/409 HTTP behavior, and authenticated security rules.

**Files:**

- Create: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/DoctorProjectChangeV2Controller.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/config/ProjectChangeCompatibilityProperties.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/DoctorProjectChangeV2HttpTest.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/DoctorProjectChangeController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt`
- Modify: `joysong-server/src/main/resources/application.yml`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/common/GlobalExceptionHandler.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/DoctorProjectChangeControllerTest.kt`

- [ ] Write MockMvc RED cases for every route/type cell: v2 JOIN creates payload v1, v2 LEAVE creates payload v1, v2 PROFILE_UPDATE creates payload v2, v2 list returns both, v1 list hides v2, v1 review/withdraw reject v2 with 426, and the v1 PROFILE_UPDATE switch works both ways.
- [ ] Make MockMvc load `test-fixtures/doctor-project-change-v2.json` and assert the exact target, v1-adapter, valid-v2, and damaged-v2 response shapes also consumed by Dart and TypeScript.
- [ ] Cover missing/extra submit/review keys and invalid effective payload as HTTP 422 `PROJECT_PAYLOAD_INVALID`; corrupted/unknown snapshot review as HTTP 422 `REQUEST_SNAPSHOT_INVALID`; revoked permission as 403; missing request as 404; and old-client access as 426 `CLIENT_UPGRADE_REQUIRED`. Assert both HTTP status and the optional `errorCode` envelope field.
- [ ] Freeze the v2 request key sets:

  ```kotlin
  private val joinFields = setOf(
      "requestType", "institutionProjectId", "serviceDescription",
      "priceSuggestion", "notes"
  )
  private val leaveFields = setOf("requestType", "institutionProjectId")
  private val profileFields = setOf(
      "requestType", "institutionProjectId", "baseRevision",
      "name", "category", "description", "tags", "slogan",
      "detailContent", "price", "salesCount", "doctorActive",
      "coverImage", "images", "notes"
  )
  private val reviewFields = setOf(
      "decision", "reviewNote", "force", "forceBaseRevision"
  )
  ```

- [ ] Reject any absent/extra key before Jackson conversion. For ordinary review, require `force=false` and `forceBaseRevision=null`; when `force=true` require a nonblank note and nonblank `forceBaseRevision`.
- [ ] Reject `force=true` for non-APPROVED decisions as HTTP 422 `FORCE_NOT_APPLICABLE` before calling the service.
- [ ] Keep the v1 controller's original three-key review DTO and flat response. Filter all v1 list operations by `payload_version=1`.
- [ ] Add:

  ```kotlin
  @ConfigurationProperties("app.project-change")
  data class ProjectChangeCompatibilityProperties(
      var v1ProfileUpdateEnabled: Boolean = true
  )

  @Configuration
  @EnableConfigurationProperties(ProjectChangeCompatibilityProperties::class)
  class ProjectChangeCompatibilityConfiguration
  ```

- [ ] Add `/api/v2/admin/institution-project-requests/**` to explicit authenticated security rules and real HTTP error-status handling.
- [ ] Run:

  ```powershell
  .\gradlew.bat test --tests com.joysong.server.admin.controller.DoctorProjectChangeControllerTest --tests com.joysong.server.admin.controller.DoctorProjectChangeV2HttpTest
  ```

- [ ] Commit: `git commit -m "feat(server): expose v2 doctor project change API"`

---

## Task 5: Make v2 review, withdraw, force, and cache invalidation atomic

**Consumes:** Task 3 immutable request rows and Task 4 route contract.

**Produces:** Transactional legal-representative/admin review, scoped force behavior, audit snapshot, permission race protection, v2 withdraw, and after-commit cache invalidation.

**Files:**

- Modify: `joysong-server/src/main/kotlin/com/joysong/server/institution/service/DoctorProjectChangeService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/institution/service/DoctorProjectChangeV2Models.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectChangeServiceTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectFullEditPersistenceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectProfileUpdatePersistenceTest.kt`

- [ ] Add RED tests for ordinary approve/reject/request-changes, repeated review, withdrawn/handled rows, authority revoked after page load, lost doctor relationship/binding, two shared approvals, two different doctors' private approvals, inheritance drift, pricing drift, force authorization/revision matrix, rollback at every write stage, audit content, and after-commit-only cache invalidation.
- [ ] Use one review command:

  ```kotlin
  data class DoctorProjectReviewV2Command(
      val decision: ProjectChangeDecision,
      val reviewNote: String,
      val force: Boolean,
      val forceBaseRevision: String?
  )

  @Transactional
  fun reviewV2(
      actor: ManagementActor,
      id: String,
      command: DoctorProjectReviewV2Command
  ): DoctorProjectChangeViewV2
  ```

- [ ] Freeze one lock order for submit, approve, and direct admin paths: existing request row when applicable → real-time reviewer authority row when applicable → active doctor-institution relationship rows sorted by doctor ID → institution project → platform project → doctor project rows sorted by ID → compatibility config rows sorted by ID. Read IDs without locking first, then reacquire and validate every association under this order. Add a real concurrent persistence test, not only mock ordering assertions.
- [ ] Review must lock the platform project with `FOR UPDATE` before recomputing inheritance hash; this prevents a validate-then-change window.
- [ ] Recompute immutable association, doctor/private baseline, shared version, inheritance hash, pricing revision, fee, and `latestRevision` inside the transaction.
- [ ] Branch immediately after locking request, checking PENDING, and revalidating reviewer authority:
  - `REJECTED` and `CHANGES_REQUESTED` require a nonblank note, write only terminal review metadata, and never check doctor/shared/inheritance/pricing baselines or write business tables;
  - only `APPROVED` performs relationship, association, snapshot, amount, and business-write validation.
- [ ] For APPROVED, `sharedChanged=false` skips institution version and platform inheritance hash comparisons entirely. `sharedChanged=true` checks the institution version; it checks the platform hash only when proposed raw overrides still contain at least one inheritance `null`.
- [ ] Normal approval returns `APPROVAL_BASE_STALE` for applicable shared/private baseline drift, `INHERITANCE_SOURCE_STALE` for an applicable inherited source drift, `PRICING_POLICY_STALE` for policy drift, and `REQUEST_ALREADY_HANDLED` when PENDING was lost.
- [ ] Add explicit tests that a pure-private request still approves after unrelated institution version and platform content changes, while reject/request-changes still succeed after every kind of business drift.
- [ ] Only platform admins may force. Force may bypass shared version/private value drift only. It may not bypass status, permission, relationship, association, inheritance source, pricing, field validation, or fee recomputation.
- [ ] Allow `force=true` only with `decision=APPROVED`. Return HTTP 422 `FORCE_NOT_APPLICABLE` for any other decision or when no allowed shared/private baseline drift exists; force is a recovery path after a reviewed conflict, not an alternate normal approval button.
- [ ] Recompute and compare `forceBaseRevision` under lock; return `FORCE_BASE_STALE` if the confirmation view is stale.
- [ ] When `sharedChanged=true`, apply the proposed raw overrides and sales count and increment the locked institution version once. When false, issue no institution-project update.
- [ ] Update only the applicant's `doctor_projects.price/is_active` and sync only the compatibility medical price; preserve all existing split values and other doctors.
- [ ] Write `approval_audit_snapshot` with `beforeVersion,afterVersion,latestBefore,actualApplied,force` and drifted fields for force, set the existing `force_processed` and reviewer metadata consistently, then conditionally move the request from PENDING to its terminal state last.
- [ ] Treat `CHANGES_REQUESTED` as terminal; a new request can be submitted from a fresh target. Preserve current v1 LEAVE apply behavior and pending mutual exclusion.
- [ ] Register discover/home/project cache eviction through the existing transaction-synchronization pattern only after commit.
- [ ] Implement `withdrawV2` for the applicant's own PENDING v1/v2 row, while v1 withdraw remains payload-v1-only.
- [ ] Run unit tests first:

  ```powershell
  .\gradlew.bat test --tests com.joysong.server.institution.service.DoctorProjectChangeServiceTest
  ```

- [ ] Then run the isolated persistence tests after printing host/name:

  ```powershell
  .\gradlew.bat mysqlIntegrationTest --tests com.joysong.server.institution.service.DoctorProjectFullEditPersistenceTest --tests com.joysong.server.institution.service.DoctorProjectProfileUpdatePersistenceTest
  ```

- [ ] Commit: `git commit -m "feat(server): apply project edits atomically"`

---

## Task 6: Protect direct admin institution-project edits with CAS

**Consumes:** Task 1 `institution_projects.version` and Task 2 error envelope.

**Produces:** Version in admin responses, `baseVersion` in PUT, stale-edit rejection, and preserved doctor-level active flags.

**Files:**

- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/InstitutionProjectController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/institution/repository/InstitutionProjectRepository.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/InstitutionProjectControllerTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectFullEditPersistenceTest.kt`

- [ ] Add controller RED tests for response version, exact PUT keys, missing/extra `baseVersion`, and a successful update with a matching version.
- [ ] Add MySQL persistence RED tests for two-admin stale updates, direct-admin update racing legal-representative approval, exactly one version increment/flush, and zero shared/doctorBindings/config writes after a stale check.
- [ ] Split create/update DTOs so only PUT requires:

  ```kotlin
  data class InstitutionProjectUpdateRequest(
      val baseVersion: Long,
      val name: String?,
      val category: String?,
      val description: String?,
      val rating: BigDecimal?,
      val reviewCount: Int?,
      val tags: String?,
      val slogan: String?,
      val detailContent: String?,
      val price: BigDecimal,
      val originalPrice: BigDecimal?,
      val currency: CurrencyCode,
      val coverImage: String?,
      val images: String?,
      val salesCount: Int?,
      val isActive: Boolean?,
      val doctorBindings: List<DoctorProjectBinding>
  )
  ```

- [ ] Lock the row, compare `entity.version == request.baseVersion`, then save the shared update and flush so JPA increments once. Return 409 `INSTITUTION_PROJECT_VERSION_STALE` before any doctor binding/config write on mismatch.
- [ ] For direct updates with doctor bindings, read target IDs first and lock relevant active doctor-institution relationships in doctor-ID order before the institution/platform/doctor/config rows, matching the global order and the approval path.
- [ ] Include `version` in `InstitutionProjectDto`.
- [ ] Keep POST on the existing create DTO and wire shape. PUT accepts exactly the enumerated update fields plus `baseVersion`; absent/extra version fields return HTTP 422 `PROJECT_PAYLOAD_INVALID`.
- [ ] When updating doctor binding prices, copy the current doctor `isActive` unchanged; direct project editing must never reactivate a doctor.
- [ ] Register cache invalidation after commit and include project caches.
- [ ] Run:

  ```powershell
  .\gradlew.bat test --tests com.joysong.server.admin.controller.InstitutionProjectControllerTest
  .\gradlew.bat mysqlIntegrationTest --tests com.joysong.server.institution.service.DoctorProjectFullEditPersistenceTest
  ```

- [ ] Commit: `git commit -m "feat(server): guard institution project admin updates"`

---

## Task 7: Enforce doctor-level availability across public discovery and ordering

**Consumes:** Task 1 `doctor_projects.is_active`.

**Produces:** One public-bookability rule used by detail, discovery, home, search, quotes, AI recommendations, minimum price, and order creation.

**Files:**

- Modify: `joysong-server/src/main/kotlin/com/joysong/server/discover/repository/DoctorProjectRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/discover/dto/DetailDtos.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/discover/controller/DiscoverController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/discover/service/DiscoverDetailService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/discover/service/DiscoverService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/discover/service/DiscoverSearchService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/home/service/HomeService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentCatalogService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentPlanService.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/discover/controller/DiscoverControllerTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/discover/service/DiscoverDetailServiceTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/discover/service/DiscoverServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/discover/service/DiscoverSearchServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/home/service/HomeServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentCatalogServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentPlanServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/payment/TravelGroundServiceOrderFlowIntegrationTest.kt`

- [ ] Add RED matrix tests for institution active × doctor active × relationship active, no-doctor discovery exclusion, direct detail availability, minimum price, quote, order rejection, inactive→active restoration, and AI catalog/plan exclusion.
- [ ] Make repository public queries require all three conditions: institution project active, doctor project active, and active doctor-institution relationship. Keep management/target queries unfiltered by doctor active.
- [ ] Add `hasAvailableDoctors` to direct institution-project detail. Direct detail may return shared content with an empty doctor list; discovery/home/search/AI must omit the offering.
- [ ] Compute public prices only from eligible doctor bindings. Filter before applying homepage limits so inactive items do not reduce the result count.
- [ ] In travel-ground quote and order creation, read IDs first, then lock/revalidate the active relationship → institution project → doctor project in the global order before pricing/order writes. Approval that disables a doctor must contend on the same rows, eliminating the check/write TOCTOU.
- [ ] Add a MySQL concurrent test where doctor deactivation approval and order creation race: either the locked order completes against the still-active binding before deactivation, or deactivation wins and order creation is rejected; no order may commit after observing an inactive binding.
- [ ] Leave existing orders unchanged when a doctor is later disabled.
- [ ] Run:

  ```powershell
  .\gradlew.bat test --tests com.joysong.server.discover.service.DiscoverDetailServiceTest --tests com.joysong.server.discover.service.DiscoverServiceTest --tests com.joysong.server.discover.service.DiscoverSearchServiceTest --tests com.joysong.server.discover.controller.DiscoverControllerTest --tests com.joysong.server.home.service.HomeServiceTest --tests com.joysong.server.order.OrderServiceTest --tests com.joysong.server.agent.service.AgentCatalogServiceTest --tests com.joysong.server.agent.service.AgentPlanServiceTest
  .\gradlew.bat mysqlIntegrationTest --tests com.joysong.server.payment.TravelGroundServiceOrderFlowIntegrationTest
  ```

- [ ] Commit: `git commit -m "feat(server): enforce doctor project availability"`

---

## Task 8: Upgrade Flutter networking, models, and repository to v2

**Consumes:** Frozen Task 4 HTTP JSON and Task 5 review semantics.

**Produces:** Stable error codes in Flutter, strict mixed-version parsing, exact 15-key submit, exact four-key review, and v2 routes for JOIN/LEAVE/PROFILE_UPDATE.

**Files:**

- Modify: `joysong-flutter/lib/core/network/api_exception.dart`
- Modify: `joysong-flutter/lib/core/network/api_envelope.dart`
- Modify: `joysong-flutter/lib/core/network/api_client.dart`
- Modify: `joysong-flutter/lib/features/identity/domain/identity_models.dart`
- Modify: `joysong-flutter/lib/features/identity/domain/identity_repository.dart`
- Modify: `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`
- Modify: `joysong-flutter/test/core/network/api_client_test.dart`
- Modify: `joysong-flutter/test/core/network/api_envelope_test.dart`
- Modify: `joysong-flutter/test/features/identity/doctor_project_profile_update_contract_test.dart`
- Modify: `joysong-flutter/test/features/identity/identity_models_controller_test.dart`

- [ ] Add RED tests for missing/null/string `errorCode` on HTTP and business-envelope failures, exact v2 paths/bodies, mixed payload versions, v2 snapshot damage, inheritance normalization, JOIN/LEAVE v1 payloads over v2 routes, and JOIN review's exact four-key body.
- [ ] Load `../test-fixtures/doctor-project-change-v2.json` in Dart contract tests and assert every golden variant; do not hand-maintain a divergent Dart-only response fixture.
- [ ] Extend the core contract:

  ```dart
  class ApiException implements Exception {
    const ApiException({
      required this.message,
      this.httpStatus,
      this.businessCode,
      this.errorCode,
      this.cause,
    });
    final String? errorCode;
  }
  ```

- [ ] Make `ApiEnvelope<Object?>` the sole envelope parser used by `ApiClient`, preserve an explicit `hasData` flag, and remove the private duplicate `_RawEnvelope`. Parse `errorCode` in normal envelopes, non-2xx envelopes, refresh paths, and uploads; never infer it from Chinese messages.
- [ ] Define strict Dart v2 target/list models and verify exact top-level and nested key sets. Contract fixtures must include:
  - target `payloadVersion/baseRevision`, immutable association/display names, raw/effective/source, current doctor price/active, platform rate, `pricingPolicyRevision`, and fee preview;
  - request `currentProject/proposedProject/latestProject/latestRevision/sharedChanged`, current/proposed doctor price and active, pricing/fee values, `requestStatus`, notes, and review metadata.
- [ ] A malformed target must block opening/submitting the editor. A mixed-list row with valid outer metadata but a damaged nested snapshot remains visible with `hasCompleteSnapshot=false` and `snapshotError`, and cannot be reviewed.
- [ ] Keep application state and doctor availability unambiguous: parse the request lifecycle only from `requestStatus` and doctor visibility only from `currentDoctorActive/proposedDoctorActive`; never alias either value to a generic `status` or `isActive`.
- [ ] Keep a dedicated v1 adapter for flat JOIN/LEAVE/PROFILE_UPDATE data and its historical schedule. Do not default a malformed v2 row into v1.
- [ ] Make `DoctorProjectProfileUpdateDraft.toJson()` emit exactly:

  ```dart
  {
    'requestType': 'PROFILE_UPDATE',
    'institutionProjectId': institutionProjectId,
    'baseRevision': baseRevision,
    'name': name,
    'category': category,
    'description': description,
    'tags': tags,
    'slogan': slogan,
    'detailContent': detailContent,
    'price': price,
    'salesCount': salesCount,
    'doctorActive': doctorActive,
    'coverImage': coverImage,
    'images': images,
    'notes': notes,
  }
  ```

- [ ] Change all institution-project request methods to `/v2/admin/institution-project-requests`. Parse the mixed list once, then filter for JOIN or profile/review consumers.
- [ ] Make `reviewInstitutionProjectJoinRequest` delegate to the same four-key v2 review implementation (or change its signature equivalently) so JOIN approval/rejection/request-changes always sends `force=false, forceBaseRevision=null`.
- [ ] Extend the repository review signature with required nullable `forceBaseRevision` and always send:

  ```dart
  {
    'decision': decision,
    'reviewNote': reviewNote.trim(),
    'force': force,
    'forceBaseRevision': forceBaseRevision,
  }
  ```

- [ ] Run:

  ```powershell
  flutter test test/core/network/api_envelope_test.dart
  flutter test test/core/network/api_client_test.dart
  flutter test test/features/identity/doctor_project_profile_update_contract_test.dart
  flutter test test/features/identity/identity_models_controller_test.dart
  ```

- [ ] Commit: `git commit -m "feat(flutter): adopt v2 doctor project contract"`

---

## Task 9: Extract a pure institution-project preview for Flutter

**Consumes:** Task 8 strict request models and existing discover detail widgets.

**Produces:** One consumer-free detail body with real images/failure placeholders plus explicit adapters for v2, v1, and creation requests.

**Files:**

- Modify: `joysong-flutter/lib/features/discover/domain/discover_models.dart`
- Create: `joysong-flutter/lib/features/discover/presentation/institution_project_preview_body.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/catalog_project_detail_view.dart`
- Create: `joysong-flutter/lib/features/identity/presentation/institution_project_review_widgets.dart`
- Create: `joysong-flutter/test/features/discover/catalog_project_availability_test.dart`
- Create: `joysong-flutter/test/features/discover/institution_project_preview_body_test.dart`
- Modify: `joysong-flutter/test/features/identity/professional_project_request_page_test.dart`

- [ ] Add preview-widget RED tests for image carousel, image failure placeholder, USD and CNY formatting, sales/tags/slogan/description/detail, and absence of booking/favorite/doctor/diary/review/AI controls and raw URL text.
- [ ] Add a public-detail RED test that parses `hasAvailableDoctors=false`, displays “当前暂无可预约医生”, and disables/hides doctor selection and booking; `true` preserves existing consumer controls.
- [ ] Add the display-only model:

  ```dart
  class InstitutionProjectPreviewModel {
    const InstitutionProjectPreviewModel({
      required this.name,
      required this.institutionName,
      required this.price,
      required this.currency,
      required this.salesCount,
      required this.tags,
      required this.slogan,
      required this.description,
      required this.detailContent,
      required this.coverImage,
      required this.images,
    });

    final String name;
    final String institutionName;
    final double price;
    final String currency;
    final int salesCount;
    final List<String> tags;
    final String? slogan;
    final String? description;
    final String? detailContent;
    final String? coverImage;
    final List<String> images;
  }
  ```

- [ ] Build `InstitutionProjectPreviewBody` by composing existing `CatalogHero`, `DynamicImagePager`, and safe rich-content presentation. Keep every consumer action outside this widget.
- [ ] Refactor `catalog_project_detail_view.dart` to use the pure body for common content, leaving its current consumer controls in the outer detail page and gating them on `hasAvailableDoctors`.
- [ ] In `institution_project_review_widgets.dart` define a USD v2 adapter using `proposedProject.effective + proposedDoctorPrice`, a currency-preserving creation adapter that never calls the v2 snapshot parser, and a v1 legacy adapter.
- [ ] Keep v1 `scheduleNote` in a legacy-only review supplementary section outside `InstitutionProjectPreviewModel`; the common preview model never owns scheduling.
- [ ] Give all three adapters direct contract tests. Fix the media merge rule as: nonblank cover first, then gallery in source order, remove blank entries and duplicate URLs while preserving the first occurrence.
- [ ] Run:

  ```powershell
  flutter test test/features/discover/institution_project_preview_body_test.dart
  flutter test test/features/discover/catalog_project_availability_test.dart
  flutter test test/features/identity/professional_project_request_page_test.dart
  ```

- [ ] Commit: `git commit -m "refactor(flutter): share institution project preview"`

---

## Task 10: Build grouped Flutter legal review and force confirmation

**Consumes:** Task 8 mixed requests/error codes and Task 9 review adapters/preview.

**Produces:** Institution-grouped review cards, full image detail, current/proposed/latest comparison, scoped actions, 409 refresh, admin force confirmation, and creation-preview reuse.

**Files:**

- Modify: `joysong-flutter/lib/features/identity/presentation/institution_project_review_widgets.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/identity_error_messages.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/identity_pages.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart`
- Modify: `joysong-flutter/test/features/identity/doctor_project_profile_update_page_test.dart`
- Modify: `joysong-flutter/test/features/identity/professional_project_request_page_test.dart`
- Modify: `joysong-flutter/test/features/identity/identity_error_messages_test.dart`

- [ ] Add RED tests for institution grouping, institution/platform project/doctor/price summary, separate request and proposed-doctor-active tags, damaged-snapshot disablement, real images, current/proposed comparison, shared/private impact text, and submit de-duplication.
- [ ] Open a dedicated internal detail route using `InstitutionProjectPreviewBody`; do not add a new global route.
- [ ] Put approve/reject/request-changes at the bottom of the detail. Require nonblank notes for reject/request-changes and for force.
- [ ] Handle stable 409 codes by refreshing the mixed v2 list and invalidating the stale detail. Never leave old snapshot buttons enabled.
- [ ] Map all frozen codes in `identity_error_messages.dart` from `ApiException.errorCode`. Do not match server Chinese text; include edit/approval/inheritance/pricing/force stale, pending/handled, client upgrade, and direct-version stale where the shared mapper is reused.
- [ ] On 403, disable review immediately, call the management-center refresh callback through `identity_pages.dart`, and leave the page if the refreshed context no longer grants review capability.
- [ ] Keep request-queue loading independent from platform-project/detail auxiliary loading. If auxiliary data fails, preserve the request cards and degrade only optional labels/content.
- [ ] Offer force only to a platform admin after `APPROVAL_BASE_STALE`. Refresh that request's审核详情, show latest shared/private values vs proposed, then submit `latestRevision` as `forceBaseRevision`. Never offer force for inheritance/pricing/force stale, handled/pending, invalid snapshot/payload, permission, or upgrade errors; hide it from institution legal representatives.
- [ ] Add the full stable-error matrix to widget tests so only `APPROVAL_BASE_STALE` reaches force confirmation and every other code routes to refresh, resubmit, upgrade, or permission-exit behavior.
- [ ] Send normal decisions with `force=false, forceBaseRevision=null`.
- [ ] Route the existing JOIN review through the v2 repository while preserving its v1 adapter and three decisions.
- [ ] Keep pending v1 LEAVE rows in the legal review list and allow approve/reject/request-changes without requiring a v2 proposed snapshot; preserve its existing apply semantics.
- [ ] Route institution creation through `ProfessionalProjectRequestPreviewAdapter → the same institution-grouped card → the same internal detail page`. It has no before diff. Keep platform-only creation requests in their existing non-institution section.
- [ ] Run:

  ```powershell
  flutter test test/features/identity/doctor_project_profile_update_page_test.dart
  flutter test test/features/identity/professional_project_request_page_test.dart
  flutter test test/features/identity/identity_error_messages_test.dart
  ```

- [ ] Commit: `git commit -m "feat(flutter): add grouped institution project review"`

---

## Task 11: Complete the Flutter doctor editor

**Consumes:** Task 8 v2 target/draft and Task 9 preview primitives.

**Produces:** Grouped institutions/projects, inactive-project management, complete editable form, upload-based media, inheritance intent, pending mutual exclusion, and no schedule/history panel.

**Files:**

- Modify: `joysong-flutter/lib/features/identity/presentation/professional_request_pages.dart`
- Modify: `joysong-flutter/test/features/identity/doctor_project_profile_update_page_test.dart`
- Modify: `joysong-flutter/test/features/identity/professional_project_request_contract_test.dart`

- [ ] Add widget RED tests for institution ExpansionTiles, all joined projects including inactive ones, project name/price/doctor-active/request-state summaries, edit/leave controls, PENDING PROFILE_UPDATE/LEAVE mutual exclusion, and no bottom history section.
- [ ] In the same widget tests assert institution/platform association is non-editable; every editable field is prefilled; `baseRevision` is returned; unchanged inherited values and cleared text/tags/cover/gallery submit `null`; cover/gallery upload and delete update the draft; URL/schedule controls are absent; fee and doctor-active previews are correct; and the fake repository captures all 15 draft keys.
- [ ] Keep institution and platform project association read-only at the top. Render editable name, category, description, tags, slogan, detail, price, sales count, cover, gallery, doctor active, and notes.
- [ ] Remove schedule controls and all manual URL inputs. Reuse the existing `doctorImagePicker` upload callback for cover/gallery.
- [ ] Initialize inherited fields from `effective`. On submit compare against initial effective/raw state:
  - original raw `null` + unchanged effective input → `null`;
  - cleared/blank text/list/media → `null`;
  - changed nonblank input → explicit override, even when equal to the current platform value.
- [ ] Validate price/sales/shared fields through the same UI helpers used by institution project creation, but construct only the v2 draft so originalPrice/currency/institution `isActive`/split fields never leak.
- [ ] Show service fee as a read-only preview from the returned rate; label currency USD.
- [ ] On `EDIT_BASE_STALE` or `REQUEST_ALREADY_PENDING` reload the target/list and preserve an explicit conflict message rather than submitting the stale form again.
- [ ] On success return to the grouped list and show PENDING status.
- [ ] Run:

  ```powershell
  flutter test test/features/identity/doctor_project_profile_update_page_test.dart
  flutter test test/features/identity/professional_project_request_contract_test.dart
  ```

- [ ] Commit: `git commit -m "feat(flutter): complete doctor institution project editor"`

---

## Task 12: Add strict project-request types and preview primitives to Web Admin

**Consumes:** Task 4 response envelope/routes and Task 5 list/review models.

**Produces:** Shared TypeScript v1/v2 parser/adapters, stable error helper, safe project preview component, and real image rendering.

**Files:**

- Create: `joysong-admin/src/types/projectRequests.ts`
- Create: `joysong-admin/src/types/projectRequests.test.ts`
- Create: `joysong-admin/src/components/InstitutionProjectPreview.tsx`
- Create: `joysong-admin/src/components/InstitutionProjectPreview.test.tsx`
- Modify: `joysong-admin/src/api.test.ts`
- Modify: `joysong-admin/src/api.ts`

- [ ] Add pure RED tests for exact v2 nested keys/types, payload-version dispatch, damaged-snapshot fail closed, legacy schedule adapter, creation adapter, `errorCode` extraction, real images/fallback, and absence of URL text/consumer actions.
- [ ] Load `../test-fixtures/doctor-project-change-v2.json` in Vitest and assert every golden variant; do not duplicate the wire fixture inside the page test.
- [ ] Extend the envelope and export a stable helper:

  ```typescript
  type ApiEnvelope<T = unknown> = {
    code: number;
    message: string;
    errorCode?: string | null;
    data: T | null;
  };

  export function getApiErrorCode(error: unknown): string | null
  ```

- [ ] Move current page-local request types/validators into `types/projectRequests.ts`. Parse `payloadVersion` first; do not treat malformed v2 as v1. Expose `reviewable=false` and a parse issue for visible-but-disabled corrupt rows.
- [ ] Define one `InstitutionProjectPreviewModel` and explicit adapters for v2 proposed state, v1 legacy, and creation requests.
- [ ] Build `InstitutionProjectPreview` with AntD `Image.PreviewGroup` and `getPreviewImageUrl`. Loading failure must show a neutral placeholder, never the raw URL.
- [ ] Render detail content without `dangerouslySetInnerHTML`: parse with `DOMParser` and map only `p,br,strong,em,ul,ol,li,h1,h2,h3,blockquote` to React nodes while dropping attributes and script/style nodes.
- [ ] Run:

  ```powershell
  npm test -- src/api.test.ts src/types/projectRequests.test.ts src/components/InstitutionProjectPreview.test.tsx
  ```

- [ ] Commit: `git commit -m "feat(admin): add strict project request preview models"`

---

## Task 13: Replace the Web audit table with grouped review cards

**Consumes:** Task 12 parsers/preview and backend v2 review.

**Produces:** v2 review loading, institution groups, real detail preview, separate statuses, exact four-key actions, stale refresh, and admin-only force.

**Files:**

- Modify: `joysong-admin/src/pages/ProjectRequestsPage.tsx`
- Modify: `joysong-admin/src/pages/ProjectRequestsPage.test.tsx`

- [ ] Add RED tests/fixtures for mixed payload v1/v2, institution grouping, institution project name/platform project name/doctor name/USD price summaries, request-status and proposed-doctor-active tags, creation adapter, v1 schedule, damaged snapshot, current/proposed/latest comparison, shared/private warning, permission scope, and in-flight action blocking.
- [ ] Fetch doctor project changes only from `/v2/admin/institution-project-requests`. Keep the professional creation list independent so its failure does not hide the change queue.
- [ ] Keep platform project creation requests in their own section; group institution creation and doctor change requests by institution using Collapse/Card.
- [ ] Open an AntD Drawer/Modal with `InstitutionProjectPreview` and explicit before/after fields. Do not render `¥` or URL strings.
- [ ] Post normal review to `/v2/admin/institution-project-requests/{id}/review` with:

  ```typescript
  {
    decision,
    reviewNote,
    force: false,
    forceBaseRevision: null,
  }
  ```

- [ ] Branch on `getApiErrorCode`. For all stale/handled codes, disable the old detail, refresh, and retain a clear conflict alert.
- [ ] Admin force is available only after `APPROVAL_BASE_STALE`. It must refresh first, require a nonblank reason, display latest differences, and post refreshed `latestRevision`. Legal representatives and all other conflict codes must never see the force action.
- [ ] Add a TypeScript error-code matrix proving inheritance/pricing/force stale, handled/pending, invalid snapshot/payload, permission, and upgrade errors cannot enter force confirmation.
- [ ] Keep malformed snapshots in the list with an error badge and no review buttons.
- [ ] Run:

  ```powershell
  npm test -- src/pages/ProjectRequestsPage.test.tsx
  ```

- [ ] Commit: `git commit -m "feat(admin): group institution project reviews"`

---

## Task 14: Upgrade Web doctor collaboration and direct-admin CAS

**Consumes:** Backend Tasks 4–6 exact v2/CAS contracts and Web Task 12 shared parsers/components.

**Produces:** Web doctor full-edit v2 submission, v2 JOIN/LEAVE/withdraw, removal of duplicate review UI, and stale-safe direct project editing.

**Files:**

- Modify: `joysong-admin/src/pages/ProjectCollaborationPage.tsx`
- Modify: `joysong-admin/src/pages/ProjectCollaborationPage.test.tsx`
- Modify: `joysong-admin/src/pages/InstitutionProjectsPage.tsx`
- Modify: `joysong-admin/src/pages/InstitutionProjectsPage.test.tsx`
- Modify: `joysong-admin/src/pages/DirectProjectCreationFormsRegression.test.tsx`
- Modify: `joysong-admin/src/layouts/AdminLayout.tsx`
- Modify: `joysong-admin/src/api.test.ts`

- [ ] Add RED contract/UI tests for complete v2 target/form/15-key submit, inheritance intent, doctorActive, no schedule/split/relationship fields, v2 JOIN/LEAVE/withdraw, and no duplicated reviewer actions in the collaboration page.
- [ ] Change all collaboration request paths to v2. JOIN remains the exact five-key v1 payload; LEAVE remains the exact two-key v1 payload.
- [ ] Make the collaboration profile editor use the same complete field set and upload components as direct institution-project creation, but keep association read-only and emit only the v2 profile draft.
- [ ] Remove/route away the collaboration page's duplicate review implementation; all legal/admin review remains in `ProjectRequestsPage`.
- [ ] Rename the navigation entry from “项目协作与审核” to “项目协作” and update the existing layout assertion in `api.test.ts`.
- [ ] Add `version` to `InstitutionProjectRecord`. On edit retain it as `baseVersion` and include it in PUT.
- [ ] On `INSTITUTION_PROJECT_VERSION_STALE` keep the stale draft for side-by-side reference, fetch/display latest values and differences, disable Save, and do not change `baseVersion`. Only an explicit “基于最新版本重新编辑” action may reset the form to latest values and bind the new version; the user must then reapply intended edits. Never attach a fresh token to the untouched stale payload.
- [ ] Add tests proving refresh alone neither replaces `baseVersion` nor reposts, and that the explicit rebase action starts from latest values before a new save can occur.
- [ ] Ensure POST creation remains unchanged and update `DirectProjectCreationFormsRegression.test.tsx` to guard that boundary.
- [ ] Run:

  ```powershell
  npm test -- src/pages/ProjectCollaborationPage.test.tsx
  npm test -- src/pages/InstitutionProjectsPage.test.tsx src/pages/DirectProjectCreationFormsRegression.test.tsx
  ```

- [ ] Commit: `git commit -m "feat(admin): upgrade project collaboration editing"`

---

## Task 15: Synchronize API documentation and UML

**Consumes:** Final compiled wire types and implemented state transitions from Tasks 1–14.

**Produces:** Current v1/v2 contract docs and updated developer UML source/image.

**Files:**

- Modify: `docs/FLUTTER_API_CONTRACT.md`
- Modify: `docs/superpowers/specs/2026-08-24-doctor-project-client-management-design.md`
- Modify: `docs/superpowers/specs/2026-08-12-doctor-profile-update-request-design.md`
- Modify: `design/DOCTOR_PROJECT_APPLICATION_WORKFLOW.puml`
- Modify: `design/DOCTOR_PROJECT_APPLICATION_WORKFLOW.svg`

- [ ] Update `FLUTTER_API_CONTRACT.md` with the endpoint matrix, exact 15-key profile body, four-key review, target/list snapshots, `doctorActive`, `baseRevision/latestRevision`, inheritance null semantics, error envelope, 409/422/426 codes, and direct-admin `baseVersion`.
- [ ] Mark both old design documents as payload-v1 historical context and link to the confirmed full-edit spec. Do not rewrite historical implementation plans.
- [ ] Extend the existing PlantUML rather than adding another overlapping diagram. Include target/baseRevision, immutable snapshots, real-time legal permission and lock order, `sharedChanged` and version CAS, applicant-only price/is_active, after-commit caches, stale codes, admin force limits, public bookability, and the direct-detail exception.
- [ ] Validate and regenerate the SVG with pinned PlantUML:

  ```powershell
  $plantUmlTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
  $plantUmlTempDir = Join-Path $plantUmlTempRoot ('joysong-plantuml-' + [guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $plantUmlTempDir -ErrorAction Stop | Out-Null
  $plantUmlJar = Join-Path $plantUmlTempDir 'plantuml-1.2026.6.jar'
  try {
      Invoke-WebRequest -Uri 'https://github.com/plantuml/plantuml/releases/download/v1.2026.6/plantuml-1.2026.6.jar' -OutFile $plantUmlJar
      $expectedPlantUmlSha256 = '89948f14c93756c7a3fb7b69078ff37e8489fd79dd430c582b931e2f65358690'
      $actualPlantUmlSha256 = (Get-FileHash -LiteralPath $plantUmlJar -Algorithm SHA256).Hash.ToLowerInvariant()
      if ($actualPlantUmlSha256 -ne $expectedPlantUmlSha256) { throw 'PlantUML SHA-256 校验失败' }
      java -jar $plantUmlJar -charset UTF-8 -checkonly design\DOCTOR_PROJECT_APPLICATION_WORKFLOW.puml
      if ($LASTEXITCODE -ne 0) { throw 'PlantUML 校验失败' }
      java -jar $plantUmlJar -charset UTF-8 -tsvg design\DOCTOR_PROJECT_APPLICATION_WORKFLOW.puml
      if ($LASTEXITCODE -ne 0) { throw 'PlantUML SVG 生成失败' }
  } finally {
      $resolvedPlantUmlDir = (Resolve-Path -LiteralPath $plantUmlTempDir).Path
      if ([IO.Path]::GetDirectoryName($resolvedPlantUmlDir) -ne $plantUmlTempRoot) { throw 'PlantUML 临时目录路径异常' }
      if (Test-Path -LiteralPath $plantUmlJar) {
          $resolvedPlantUmlJar = (Resolve-Path -LiteralPath $plantUmlJar).Path
          if ([IO.Path]::GetDirectoryName($resolvedPlantUmlJar) -ne $resolvedPlantUmlDir) { throw 'PlantUML 临时文件路径异常' }
          Remove-Item -LiteralPath $resolvedPlantUmlJar
      }
      if ((Get-ChildItem -LiteralPath $resolvedPlantUmlDir -Force).Count -eq 0) {
          Remove-Item -LiteralPath $resolvedPlantUmlDir
      }
  }
  ```

- [ ] Visually inspect the generated SVG for clipped labels, unreadable branches, and mismatches with the implemented error/force rules.
- [ ] Run `git diff --check` and confirm only the intended docs/design artifacts changed in this task.
- [ ] Commit: `git commit -m "docs: update doctor project workflow contract"`

---

## Task 16: Cross-stack verification and release-readiness review

**Consumes:** All implementation tasks.

**Produces:** Focused passing evidence, at most one full run per stack, clean diff, no temporary artifacts, and an explicit rollout checklist.

**Files:** No planned production changes; fix only defects demonstrated by these checks in their owning task files.

- [ ] Backend core:

  ```powershell
  cd joysong-server
  $env:GRADLE_USER_HOME = Join-Path $PWD '.tmp\gradle-user-home-codex'
  .\gradlew.bat test --tests com.joysong.server.admin.controller.DoctorProjectChangeControllerTest --tests com.joysong.server.admin.controller.DoctorProjectChangeV2HttpTest --tests com.joysong.server.institution.service.DoctorProjectChangeServiceTest --tests com.joysong.server.institution.service.DoctorProjectSnapshotCodecTest --tests com.joysong.server.institution.service.InstitutionProjectDetailResolverTest --tests com.joysong.server.discover.dto.PublicContentDtosTest --tests com.joysong.server.project.service.ProfessionalProjectRequestServiceTest --tests com.joysong.server.admin.controller.InstitutionProjectControllerTest --tests com.joysong.server.order.service.TravelGroundServicePricingTest
  ```

- [ ] Backend public/order:

  ```powershell
  .\gradlew.bat test --tests com.joysong.server.discover.service.DiscoverDetailServiceTest --tests com.joysong.server.discover.service.DiscoverServiceTest --tests com.joysong.server.discover.service.DiscoverSearchServiceTest --tests com.joysong.server.discover.controller.DiscoverControllerTest --tests com.joysong.server.home.service.HomeServiceTest --tests com.joysong.server.order.OrderServiceTest --tests com.joysong.server.agent.service.AgentCatalogServiceTest --tests com.joysong.server.agent.service.AgentPlanServiceTest
  ```

- [ ] Print the isolated DB target, assert its name starts with `myapp_worktree_`, then run the related MySQL suite once:

  ```powershell
  .\gradlew.bat mysqlIntegrationTest --tests com.joysong.server.institution.service.DoctorInstitutionProjectFullEditMigrationTest --tests com.joysong.server.institution.service.InstitutionProjectVersionPersistenceTest --tests com.joysong.server.institution.service.DoctorProjectFullEditPersistenceTest --tests com.joysong.server.institution.service.DoctorProjectProfileUpdatePersistenceTest --tests com.joysong.server.payment.TravelGroundServiceOrderFlowIntegrationTest --tests com.joysong.server.migration.BaselineMigrationIntegrationTest
  ```

- [ ] Flutter focused suite and analysis:

  ```powershell
  cd ..\joysong-flutter
  flutter test test/core/network/api_envelope_test.dart test/core/network/api_client_test.dart test/features/discover/institution_project_preview_body_test.dart test/features/discover/catalog_project_availability_test.dart test/features/identity/doctor_project_profile_update_contract_test.dart test/features/identity/doctor_project_profile_update_page_test.dart test/features/identity/professional_project_request_contract_test.dart test/features/identity/professional_project_request_page_test.dart test/features/identity/identity_models_controller_test.dart test/features/identity/identity_error_messages_test.dart
  flutter analyze
  ```

- [ ] Web Admin focused suite, lint, and build:

  ```powershell
  cd ..\joysong-admin
  npm test -- src/api.test.ts src/types/projectRequests.test.ts src/components/InstitutionProjectPreview.test.tsx src/pages/ProjectRequestsPage.test.tsx src/pages/ProjectCollaborationPage.test.tsx src/pages/InstitutionProjectsPage.test.tsx src/pages/DirectProjectCreationFormsRegression.test.tsx
  npm run lint
  npm run build
  ```

- [ ] If the focused checks pass and elapsed time permits, run each stack's full test command at most once. Stop any full run after 10 minutes and report completed/slow tests; do not retry environment or flaky failures without new evidence.
- [ ] Manually exercise one exact cross-stack fixture flow:
  1. doctor submits v2 shared + private update;
  2. legal representative approves;
  3. all doctors see shared changes;
  4. only applicant price/active changes;
  5. disabled doctor disappears from discovery/order but remains manageable;
  6. stale approval and stale direct-admin PUT each return their frozen error codes;
  7. platform admin receives a normal-approval conflict, refreshes, confirms the latest diff, and force-approves with `latestRevision`;
  8. a second change after force confirmation returns `FORCE_BASE_STALE`;
  9. the old v1 endpoints stay flat and isolated from v2 rows;
  10. new Flutter/Web mixed-list clients display and review v1 history, including v1 schedule and LEAVE.
- [ ] Verify the release sequence flags: keep `v1-profile-update-enabled=true` for backend/reviewer/doctor client rollout; turn it off only after both current clients are verified against v2.
- [ ] From the worktree root run:

  ```powershell
  git diff --check
  git status --short
  Get-ChildItem -LiteralPath . -Recurse -File -Include '*.tmp','*.bak','plantuml-1.2026.6.jar' | Select-Object -ExpandProperty FullName
  ```

  Remove only task-created temporary files after verifying their exact paths; preserve all unrelated user changes. For the task-created Gradle home, resolve `joysong-server/.tmp/gradle-user-home-codex`, assert it is a real directory below the current worktree's `joysong-server/.tmp`, enumerate it, reject every reparse point, and only then remove that exact directory recursively. If any validation or deletion fails, stop rather than widening or retrying the target.
- [ ] Use `superpowers:requesting-code-review` for final review, then `superpowers:verification-before-completion` before claiming completion.
- [ ] Commit any verification-driven corrections in their owning scope; do not create a content-free “verification” commit.

## Execution Order and Parallelization

1. Tasks 1–5 are sequential because they freeze the database, canonical contract, routes, and approval semantics.
2. Task 6 and Task 7 may run in parallel after Task 5; their production files do not overlap except shared entity/repository contracts already frozen.
3. Flutter executes Task 8 networking → Task 9 preview → Task 10 reviewer UI → Task 11 doctor editor, so a reviewer-capable app exists before v2 doctor submission is enabled.
4. Web Task 12 may start after Tasks 4–5 and run in parallel with Flutter. Task 13 reviewer UI follows Task 12; Task 14 doctor collaboration follows Task 13 and must also wait for Task 6 CAS.
5. Treat the end of Flutter Task 10 and Web Task 13 as explicit reviewer release checkpoints. Build and deploy those reviewer artifacts before building/releasing the Task 11 or Task 14 doctor-submission artifacts.
6. Task 15 starts only after backend/Flutter/Web wire types match.
7. Task 16 runs after every implementation commit is present.

## Definition of Done

- v2 exact-body, compatibility, inheritance, snapshot, concurrency, force, permission, pricing, atomicity, availability, and migration tests pass.
- Flutter and Web use v2 for new doctor edits and review; Web `ProjectCollaborationPage` no longer creates v1 PROFILE_UPDATE rows.
- Inactive doctor services are invisible and unorderable publicly but remain manageable.
- Direct backend editing cannot overwrite a newer shared version.
- Images render as images in both review clients; raw URLs are not presented as content.
- v1 history/JOIN/LEAVE remain compatible, with schedule confined to v1.
- `design/DOCTOR_PROJECT_APPLICATION_WORKFLOW.puml` and its SVG match the implemented flow.
- Worktree is clean except intended committed changes, with no temporary jars or scratch code; isolated database lifecycle is owned and reported by `WorktreeTestDatabase`.
