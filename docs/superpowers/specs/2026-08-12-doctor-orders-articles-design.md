# Doctor Articles and Orders Management Contract Design

## Scope and decision

This slice normalizes every currently implemented doctor-owned article and order operation used by the professional management swimlane. It introduces a professional article resource and completes Flutter list/detail/action flows without changing the order lifecycle or granting institution legal representatives or consultants new order authority.

The canonical routes are:

- `GET/POST /api/management/doctor-articles`
- `PUT/DELETE /api/management/doctor-articles/{id}`
- `GET /api/management/orders`
- `GET /api/management/orders/{id}`
- `POST /api/management/orders/{id}/verify`
- `POST /api/management/orders/{id}/request-completion`

`/api/admin/articles` is a historical name. Keep its four mappings for one compatibility release for the administrator web app, backed by the same DTO/service rules and marked deprecated in documentation. Flutter professional management must call only `/api/management/doctor-articles`. Do not add a second order route: `/api/management/orders` is already the correct professional namespace.

No migration is required. `expert_articles`, `orders`, `order_status_logs`, `doctors`, and `user_roles` already contain the authoritative data.

## Database authority

`expert_articles` persists `id`, `title`, `author_name`, `summary`, `cover_image`, `publish_date`, `content`, `read_count`, `doctor_id`, `created_at`, `updated_at`, and soft-delete `deleted_at`. The doctor may write only title, summary, cover image, publish date, and content. Identity, author name, read count, timestamps, and deletion metadata are server-owned. `doctor_id` is always the authenticated doctor's id; `author_name` is resolved from `doctors.name`.

`orders` supplies the existing response data: identifiers and participant snapshots, project/institution/doctor names, currency and monetary fields, status, appointment/payment/completion timestamps, refund/review fields, and action state. Ownership is `orders.doctor_id = ManagementActor.doctorId`. The management response must continue to replace `verifyCode` with null; the client supplies the user-displayed code only in an action request.

The actual state machine remains authoritative:

- verification: `CONSULTATION_PAID -> VERIFIED`, setting `verified_at`, clearing `verify_code`, and recording an order status log;
- completion request: `BALANCE_PAID -> PENDING_COMPLETION`, setting `completion_requested_at`, clearing `verify_code`, and recording a status log.

All other states are read-only in this doctor slice. `PENDING_PAYMENT`, terminal states, disputes, refunds, settlement, payment, cancellation, and user confirmation remain handled by their existing actors and endpoints.

## Authorization and object ownership

Every doctor operation derives the actor from the bearer token and rechecks an `ACTIVE DOCTOR` row in `user_roles` plus a non-deleted `doctors` row. A stale context or locally cached role never authorizes a request. A platform administrator remains compatible with professional list/detail/mutations where explicitly supported: article admin operations may address all articles; order admin reads may address all non-deleted orders, while these two doctor workflow actions use the same state/code rules and must be audited as administrator actions if allowed. An administrator is never silently treated as a doctor.

A non-admin doctor can list, read, edit, or delete only `expert_articles.doctor_id == actor.doctorId`, and can list/read/act only on `orders.doctor_id == actor.doctorId`. Another doctor's id returns 403 after the resource has been found; list responses never contain it. Institution legal representatives and consultants receive 403 even if they belong to the same institution. This deliberately preserves the current `OrderService.canManageOrder` boundary.

Queries should enforce ownership and pagination in the repository/database rather than loading all rows and filtering in memory. Soft-deleted rows are excluded by the normal entity scope.

## Article HTTP contract

### List and detail model

`GET /api/management/doctor-articles?keyword=&offset=0&limit=20` returns an array ordered by `publishDate DESC, createdAt DESC, id DESC`. `offset` is at least 0, `limit` is 1..100, and `keyword` is trimmed and matched case-insensitively against id or title. For a doctor, the scope is always self; admin compatibility may search all articles.

Every `DoctorArticleView` contains exactly:

```json
{
  "id": "article-id",
  "title": "术后护理",
  "authorName": "张医生",
  "summary": "摘要",
  "coverImage": "https://...",
  "publishDate": "2026-08-12",
  "content": "<p>...</p>",
  "readCount": 0,
  "doctorId": "doctor-id",
  "createdAt": "2026-08-12T10:00:00",
  "updatedAt": "2026-08-12T10:00:00"
}
```

The same view is returned by create and update. The edit page may use the selected list item as its detail because no separate GET-by-id route exists in the agreed route inventory; after navigation or cache loss it reloads the scoped list and resolves by id. Do not invent an undocumented article-detail endpoint in this slice.

### Create and update

POST and PUT accept a dedicated body with exactly these keys:

```json
{
  "title": "术后护理",
  "summary": "摘要",
  "coverImage": "https://...",
  "publishDate": "2026-08-12",
  "content": "<p>...</p>"
}
```

All five keys are required and non-null. Trim title/summary/coverImage; title must be non-blank and at most 200 characters, summary at most 1000, coverImage at most 500, and content at most 100000 characters. `publishDate` is an ISO `yyyy-MM-dd` date. Content may be blank for a draft-like unpublished body, but this schema does not invent a publication-status column. Rich content must be sanitized or rendered through the existing restricted HTML policy.

PUT is a full replacement of the five editable properties and preserves doctor, author, read count, creation time, and soft-delete fields. The request cannot reassign an article. DELETE performs the existing soft delete and returns success with null data. POST returns 201; successful GET/PUT/DELETE use 200.

Malformed fields are 400, inactive/non-doctor or another doctor's resource is 403, missing article is 404, and concurrent update conflict is 409. Add an `updatedAt` precondition through `If-Unmodified-Since` or an explicit request token only if the existing API-wide convention supports it; otherwise use a pessimistic row lock for mutation and document last-write-wins. Do not add a database version column in this migration-free slice.

POST is not automatically retried. PUT and DELETE are naturally idempotent for an unchanged target; a repeated DELETE should return 404 rather than expose deleted data.

## Order HTTP contract

`GET /api/management/orders?status=&offset=0&limit=20` retains the existing `OrderResponse` field names and returns `DoctorOrderView` values ordered by creation descending. Validate status against all values in `OrderStatusEnum`, `offset >= 0`, and `limit` 1..100. Move the doctor/admin predicate, optional status predicate, ordering, and page window into repository queries. The response remains a bare array for compatibility.

`GET /api/management/orders/{id}` returns the same view. It never returns a usable `verifyCode`; `canVerify` and `canRequestCompletion` are derived server-side booleans added to the management view so Flutter does not infer permissions solely from a cached status. They are true only when the actor owns/can administer the order and status is respectively `CONSULTATION_PAID` or `BALANCE_PAID`.

Both actions accept exactly `{ "verificationCode": "123456" }`; the value must match `^\\d{6}$`. The service must acquire `findByIdForUpdate`, then recheck ownership, status, and code inside one transaction before changing the order and writing its status log. Code mismatch is 400 without revealing whether any digit matched. Missing is 404, cross-doctor/inactive role is 403, and a valid but disallowed current state is 409.

Action replay policy is narrowly idempotent: if verification is retried and the row is already `VERIFIED` with a non-null `verifiedAt`, return the current management view without another log; if completion request is retried and the row is already `PENDING_COMPLETION` with a non-null `completionRequestedAt`, do likewise. This covers a lost response after a committed transition. Any later/different state remains 409. Because the code has been cleared, replay detection must occur before comparing the code. Parallel first attempts serialize on the row lock and produce one transition/log.

## Flutter VO, repository, and pages

Add a dedicated professional-management feature boundary; do not reuse customer `Order` actions or admin entities.

- `DoctorArticle` mirrors `DoctorArticleView`; `DoctorArticleDraft` serializes only the five editable fields.
- `DoctorOrder` mirrors the management order view, uses the existing fixed-point `Money` parser, preserves unknown statuses as an unknown display value, and exposes server `canVerify`/`canRequestCompletion`.
- Repository methods are `listDoctorArticles`, `createDoctorArticle`, `updateDoctorArticle`, `deleteDoctorArticle`, `listDoctorOrders`, `getDoctorOrder`, `verifyDoctorOrder`, and `requestDoctorOrderCompletion`.
- Every professional call uses `/management/...`; no Flutter doctor code calls `/admin/articles` or customer `/orders/{id}/verify`.

The bilingual pages are:

1. Article list: keyword search, loading/empty/error/retry, refresh, create, edit, delete confirmation, and offset loading. Cards show cover, title, summary, publish date, read count, and author. Delete/update controls only appear for owned rows.
2. Article editor: create/edit mode, five contract fields, cover upload URL integration, date picker, validation, unsaved-change confirmation, submit lock, and Chinese/English labels. It never exposes doctorId, authorName, readCount, or timestamps as editable controls.
3. Doctor order list: status filter, paging/refresh, loading/empty/error, project/patient-safe summary, amount/currency, appointment time, and status localization.
4. Doctor order detail: all safe management fields, no verification code display, server action flags, 6-digit input dialogs, one-submit lock, success reload, and localized 400/403/404/409 messages. It does not expose payment, refund, cancellation, user confirmation, settlement, or arbitrary status-update controls.

Navigation is gated by current `/api/management/context`: `canManageArticles` and `canManageOrders`. The server remains authoritative if capability changes while a page is open.

## Documentation, counts, and migration

Update all three normative artifacts: `doc/用户端API文档.md`, `docs/FLUTTER_API_CONTRACT.md`, and `docs/CORE_ROLES_BUSINESS_SWIMLANE.puml`. Replace the doctor's four `/api/admin/articles` capabilities with the four `/api/management/doctor-articles` capabilities. The doctor route count remains 27 because paths are replaced, not added; the four order routes remain unchanged. Document the old article routes only in a compatibility/deprecation note, not as doctor capabilities.

No schema migration is justified. If optimistic versioning is later required, it must be a separately reviewed migration verified against a fresh isolated database; this slice instead uses existing timestamps and row locking.

## Minimal verification plan

Backend focused tests must prove exact article request/response fields, active-role and self ownership, admin compatibility, database-scoped filtering, validation and soft delete; and order self/admin visibility, status filtering/pagination, hidden code, state/code preconditions, row-lock serialization, replay idempotency, one status log, and all 400/403/404/409 mappings.

Flutter tests are capped to the smallest useful set: one article repository/VO contract test, one article list/editor widget test, one order repository/VO contract test, and one order list/detail/action widget test. After focused tests pass, run `flutter analyze` once and at most one broader suite per project under the repository test rules.

