# Doctor Articles and Orders Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver normalized doctor-owned article CRUD and existing order workflow APIs, precise Flutter VO/repositories, and bilingual professional article/order pages.

**Architecture:** Professional controllers derive an active doctor from `ManagementActor`, use dedicated request/view DTOs, and enforce ownership in database queries. Existing order transitions remain in `OrderService`, but row locking, replay idempotency, and management-only action flags make them safe for Flutter; the historical admin article controller delegates to the same service during one compatibility release.

**Tech Stack:** Kotlin, Spring Boot, Spring Security, Spring Data JPA, JUnit 5/MockK; Dart, Flutter, existing API client and fixed-point money types, Flutter test.

## Global Constraints

- Canonical article routes are exactly `GET/POST /api/management/doctor-articles` and `PUT/DELETE /api/management/doctor-articles/{id}`; do not invent a detail GET.
- Order routes remain exactly the four existing `/api/management/orders` mappings.
- Non-admin access requires current `ACTIVE DOCTOR`, a non-deleted doctor row, and exact `doctor_id=self` ownership.
- Legal representatives and consultants gain no article or order access through institution membership.
- Article clients can write only title, summary, coverImage, publishDate, and content; all identity/counter/timestamp fields are server-owned.
- Management order responses never expose a usable verification code.
- Verification is only `CONSULTATION_PAID -> VERIFIED`; completion request is only `BALANCE_PAID -> PENDING_COMPLETION`.
- Actions lock the order row, log exactly once, and replay only the immediately reached target state idempotently.
- No migration is required; never connect tests to a shared database.
- Flutter professional flows call only `/management/...`, use bilingual copy, and prevent duplicate writes.
- Run smallest relevant tests first, never repeat a passing command, and run at most one broader suite per stack.

---

### Task 1: Doctor article DTO, query, and service boundary

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/article/dto/DoctorArticleDtos.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/article/repository/ArticleRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/article/service/ArticleService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/article/service/ArticleServiceTest.kt`

**Interfaces:** Produces `DoctorArticleUpsertRequest(title, summary, coverImage, publishDate, content)`, `DoctorArticleView`, and scoped `listForManagement(actor, keyword, offset, limit)`, `createForManagement`, `updateForManagement`, `deleteForManagement` methods.

- [ ] Write failing service tests for exact editable-field mapping, server-derived doctor/name/id/count/timestamps, active doctor self scope, admin scope, keyword/order/page behavior, cross-doctor 403, missing 404, and soft deletion.
- [ ] Run `./gradlew test --tests com.joysong.server.article.service.ArticleServiceTest`; expect RED because the management methods/DTOs do not exist.
- [ ] Add repository queries that apply doctor id, optional keyword, deterministic ordering, and pageable window in SQL/JPA; do not call `findAll()` then filter.
- [ ] Implement validation and DTO mapping; preserve immutable/server fields on PUT and resolve `authorName` from the non-deleted doctor row.
- [ ] Rerun the focused test once; expect PASS.
- [ ] Commit Task 1 files with `feat: add doctor article domain contract`.

### Task 2: Professional article routes and admin compatibility

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/article/controller/DoctorArticleManagementController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminArticleController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/article/controller/DoctorArticleManagementControllerTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/AdminArticleControllerTest.kt`

**Interfaces:** The professional controller exposes the four canonical mappings and accepts only `DoctorArticleUpsertRequest`; the legacy controller delegates to the same DTO/service contract for administrators.

- [ ] Add failing MockMvc tests for method/path, exact JSON schema, pagination validation, 201 create, 200 update/delete, 400 validation, 401, inactive/non-doctor 403, self ownership, and 404.
- [ ] Add a compatibility test proving `/api/admin/articles` remains admin-accessible but no longer binds `ArticleEntity`; professional Flutter behavior is not tested against that path.
- [ ] Run the two controller test classes; expect RED for the missing professional controller.
- [ ] Implement controller mappings and explicit authenticated security matchers; rely on the service for active-role/object checks and use the project exception mapping for 400/403/404/409.
- [ ] Rerun the focused controller command once; expect PASS.
- [ ] Commit with `feat: expose doctor article management API`.

### Task 3: Safe management order reads and transitions

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/repository/OrderRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/dto/OrderResponse.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt`

**Interfaces:** Produces database-scoped management paging, `canVerify`/`canRequestCompletion` in the management view, and locked idempotent `confirmVerification`/`requestCompletion` transitions.

- [ ] Add failing tests for active doctor self/admin visibility, another doctor's exclusion/403, all valid status filters, invalid status/page inputs, hidden verifyCode, and server action flags.
- [ ] Add failing transition tests for exact source/target states, six-digit code comparison, cleared code/timestamps, status log, replay returning without a second log, later-state 409, and two serialized attempts producing one mutation.
- [ ] Run `./gradlew test --tests com.joysong.server.order.OrderServiceTest`; expect new assertions to fail.
- [ ] Replace `findAll` plus in-memory filtering with scoped repository query/page methods and keep response array compatibility.
- [ ] Load actions through `findByIdForUpdate`, check actor ownership inside the transaction, handle immediate target replay before code comparison, and map invalid state to conflict.
- [ ] Rerun the focused service class once; expect PASS.
- [ ] Commit with `fix: harden doctor order management transitions`.

### Task 4: Order HTTP contract tests

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/controller/ManagementOrderController.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/order/controller/ManagementOrderControllerTest.kt`

**Interfaces:** Preserves the four paths; validates status/offset/limit and exact `{verificationCode}` bodies; returns management views for both actions.

- [ ] Write MockMvc tests for owner list/detail, admin compatibility, inactive role and cross-owner 403, missing 404, invalid page/status/code 400, state conflict 409, and absence of non-null verifyCode.
- [ ] Run the new controller test class; expect RED where validation/error mapping/action DTOs differ.
- [ ] Add bounded parameter validation and call the actor-aware locked service methods without duplicating authorization in an unlocked pre-read.
- [ ] Rerun the class once; expect PASS.
- [ ] Commit with `test: specify doctor order management API`.

### Task 5: Normative documentation and swimlane count

**Files:**
- Modify: `doc/用户端API文档.md`
- Modify: `docs/FLUTTER_API_CONTRACT.md`
- Modify: `docs/CORE_ROLES_BUSINESS_SWIMLANE.puml`

**Interfaces:** Documents carry the exact DTO fields, status/error/idempotency rules, compatibility boundary, and unchanged doctor endpoint count of 27.

- [ ] Replace doctor `/api/admin/articles` routes with the four `/api/management/doctor-articles` routes in all normative capability tables and examples.
- [ ] Document legacy admin compatibility separately, the five writable article fields, server-owned fields, self/active-role checks, paging, and errors.
- [ ] Correct order responsibility text so only self doctor/admin may operate; document status preconditions, hidden code, replay behavior, and action flags.
- [ ] Update the doctor swimlane without changing its 27-route count: four article route replacements and the existing four order routes.
- [ ] Search the three files for normative doctor use of `/admin/articles`, claims that legal representatives can verify orders, and contradictory route counts; fix every hit.
- [ ] Commit with `docs: normalize doctor article and order contracts`.

### Task 6: Flutter professional article/order VO and repository

**Files:**
- Create: `joysong-flutter/lib/features/identity/domain/doctor_management_models.dart`
- Modify: `joysong-flutter/lib/features/identity/domain/identity_repository.dart`
- Modify: `joysong-flutter/lib/features/identity/data/identity_repository_impl.dart`
- Test: `joysong-flutter/test/features/identity/doctor_articles_orders_contract_test.dart`

**Interfaces:** Produces `DoctorArticle`, `DoctorArticleDraft`, `DoctorOrder`, and the eight repository methods named in the design; all paths are `/management/...`.

- [ ] Write one failing contract test covering strict article decoding, exact five-key create/update bodies, delete, order money/status/null parsing, server action flags, filters, detail, and exact six-digit action bodies.
- [ ] Run the new Flutter contract test; expect RED for missing models/methods.
- [ ] Implement focused models using existing date, envelope, API-error, and fixed-point money helpers; unknown order status must not throw.
- [ ] Implement repository calls and remove any doctor article `/admin/articles` or customer order-action reuse.
- [ ] Format changed Dart files and rerun the focused test once; expect PASS.
- [ ] Commit with `feat: add doctor article and order Flutter contracts`.

### Task 7: Bilingual doctor article pages

**Files:**
- Create: `joysong-flutter/lib/features/identity/presentation/doctor_articles_page.dart`
- Create: `joysong-flutter/lib/features/identity/presentation/doctor_article_editor_page.dart`
- Modify: `joysong-flutter/lib/features/profile/presentation/profile_page.dart`
- Test: `joysong-flutter/test/features/identity/doctor_articles_page_test.dart`

**Interfaces:** Uses Task 6 repository; list passes keyword/offset/limit, editor submits `DoctorArticleDraft`, and entry visibility uses `canManageArticles`.

- [ ] Write widget tests for loading/empty/error/retry/paging, create/edit/delete, exact owned controls, bilingual labels, validation, submit lock, and unsaved-change confirmation.
- [ ] Run the page test; expect RED before pages exist.
- [ ] Implement the two focused pages, reusing existing image upload/date/input components and resolving edit state from the scoped list rather than inventing a detail endpoint.
- [ ] Wire the professional-center entry through current management context and remove any doctor UI link to admin article pages.
- [ ] Format files and rerun the focused widget test once; expect PASS.
- [ ] Commit with `feat: add doctor article management pages`.

### Task 8: Bilingual doctor order pages

**Files:**
- Create: `joysong-flutter/lib/features/identity/presentation/doctor_orders_page.dart`
- Create: `joysong-flutter/lib/features/identity/presentation/doctor_order_detail_page.dart`
- Modify: `joysong-flutter/lib/features/profile/presentation/profile_page.dart`
- Test: `joysong-flutter/test/features/identity/doctor_orders_page_test.dart`

**Interfaces:** Uses Task 6 management repository, action flags from the server, and one six-digit dialog for each named transition; entry visibility uses `canManageOrders`.

- [ ] Write widget tests for filters/paging/states, safe detail rendering, no displayed verifyCode, action visibility from flags, invalid code, submit lock, success reload, and localized conflict/permission/not-found errors.
- [ ] Run the new widget test; expect RED.
- [ ] Implement list/detail pages without payment/refund/cancel/confirm/settlement/arbitrary-status controls and without reusing customer action routes.
- [ ] Wire the professional entry and refresh detail/list after successful action or replay.
- [ ] Format files and rerun the focused test once; expect PASS.
- [ ] Commit with `feat: adapt doctor order management pages`.

### Task 9: Completion audit and bounded verification

**Files:** Inspect all Task 1-8 files; modify only proven contract mismatches.

- [ ] Map every design requirement to current controller/service/query/VO/page/document evidence; any missing item remains unfinished.
- [ ] Search production Flutter for doctor `/admin/articles`, customer `/orders/{id}/verify`, client-written article doctor/counter fields, and locally inferred order actions; expect no violations.
- [ ] Confirm the three documents agree on four article and four order routes and doctor total 27.
- [ ] Run only focused backend tests not already evidenced as passing; then at most one full backend suite if focused changes indicate cross-module risk.
- [ ] Run only focused Flutter tests not already evidenced as passing, then `flutter analyze` once; at most one broader Flutter suite.
- [ ] If persistence verification becomes necessary, derive the worktree id, print resolved host/database first, and use only a fresh database beginning `myapp_worktree_`; do not reset shared state.
- [ ] Review diff for unrelated edits and delete only temporary artifacts created by this slice.
- [ ] Commit audit corrections as `fix: align doctor articles and orders contracts`; otherwise leave verified commits unchanged.

