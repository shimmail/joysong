# Consultant Management Contract Design

## Scope

This slice gives an authenticated, verified consultant an identity-owned institution-affiliation contract and gives every authenticated professional identity a read-only platform-project catalog. It removes consultant dependence on mixed reviewer DTOs and `/api/admin/projects`, while preserving the existing generic legal-representative/admin review route.

The routes are exactly:

- `GET /api/management/consultant-memberships`
- `POST /api/management/consultant-memberships`
- `GET /api/management/projects`

No database migration is required. `user_roles` and `institution_memberships` remain authoritative.

## Authorization and ownership

Both consultant-membership routes derive `userId` from the bearer token. They require `user_roles(user_id, role_code='CONSULTANT', status='ACTIVE')`; an administrator without that active professional identity receives 403. The client cannot submit `userId`, `memberRole`, `status`, confirmation metadata, or a request action.

`GET /api/management/projects` requires at least one current ACTIVE professional role (`DOCTOR`, `CONSULTANT`, or `INSTITUTION_LEGAL_REPRESENTATIVE`). It is read-only and does not confer project-management authority.

`visibleInstitutionIds` must include a consultant's APPROVED, non-revoked `institution_memberships` rows so management context reflects affiliations consistently with `canViewAffiliations`. However, visibility is not the ownership rule for the consultant endpoint: `GET consultant-memberships` always filters directly by token `userId` and `member_role='CONSULTANT'`. Managed institutions and another professional role must never leak another user's memberships into that list.

## Consultant membership contract

### GET `/api/management/consultant-memberships`

Returns all non-deleted consultant membership rows owned by the authenticated consultant, ordered by `createdAt DESC, id DESC`, including PENDING, APPROVED, REJECTED, and REVOKED history.

Each item has the exact normalized shape:

```json
{
  "id": "membership-id",
  "institutionId": "institution-id",
  "institutionName": "机构名称",
  "status": "APPROVED",
  "requestNote": "申请说明",
  "reviewNote": "审核意见",
  "createdAt": "2026-08-12T10:00:00",
  "updatedAt": "2026-08-12T10:05:00",
  "confirmedBy": "reviewer-user-id",
  "confirmedAt": "2026-08-12T10:05:00",
  "revokedAt": null
}
```

`institutionName` is joined from `institutions`; confirmation/revocation metadata is nullable. The response omits `userId`, `requestType`, `memberRole`, `deleted`, and doctor-only fields because identity and role are implied by the endpoint.

### POST `/api/management/consultant-memberships`

The exact request is:

```json
{
  "institutionId": "institution-id",
  "requestNote": "申请说明"
}
```

Both keys are required strings; `institutionId` must remain non-blank after trimming, exist, and identify a non-deleted institution. `requestNote` may be blank and is trimmed, with the existing database length limit enforced. The operation is always JOIN and always writes `member_role='CONSULTANT'`, `status='PENDING'`.

The unique key `(user_id, institution_id, member_role)` defines duplicate behavior. No row creates a PENDING request. REJECTED or REVOKED rows are resubmitted in place, clearing `review_note`, `confirmed_by`, `confirmed_at`, and `revoked_at`; PENDING or APPROVED rows return 409. The response is the created/resubmitted item in the GET schema.

The existing `POST /api/management/institution-membership-requests/{requestType}/{id}/review` stays authoritative for institution legal representatives and platform administrators. Approval/rejection semantics are unchanged.

## Professional project catalog

`GET /api/management/projects` returns active, non-deleted rows from `projects`, ordered deterministically by `name ASC, id ASC`. `ProjectSummary` contains `id`, `name`, `category`, `description`, `tags`, `categoryTags`, `coverImage`, `referencePrice`, and `currency`. JSON list fields use the project's existing normalization; money is a decimal value and currency is a three-letter code. There are no create/update/delete methods under this path.

This route replaces consultant reads from `/api/admin/projects`. Admin mutation APIs remain admin-only and are not reused by consultant Flutter flows.

## Backend structure and errors

Add focused management controllers/services rather than expanding the generic mixed request response. Reuse `ManagementAccessService.actor`, but extend actor/context construction with `consultantInstitutionIds` sourced only from APPROVED, non-revoked consultant memberships; `visibleInstitutionIds = managedInstitutionIds + doctorInstitutionIds + consultantInstitutionIds`.

Expected mapping is 401 for missing/invalid authentication, 403 for missing ACTIVE role, 400 for malformed fields, 404 for an unknown institution, and 409 for an existing PENDING/APPROVED consultant relation. Persistence races must map the unique-key conflict to the same 409 outcome rather than create duplicates.

## Flutter design

Create a consultant-only `ConsultantMembership` VO and `ConsultantMembershipDraft`; do not reuse `InstitutionMembershipRequest`, which mixes applicant and reviewer fields. Repository methods are `listConsultantMemberships()`, `submitConsultantMembership(draft)`, and `listManagementProjects()`, with the last decoding the new `ProjectSummary` route.

The consultant affiliation entry opens a dedicated `ConsultantMembershipPage`. It shows owned membership history/status/notes/confirmation metadata, and its JOIN action opens the already shared searchable institution picker with explicit `IdentityRoleType.consultant`. On selection it submits only `institutionId` and `requestNote`. Remove consultant branches and `requestType` plumbing from the mixed doctor/reviewer membership page; keep legal-representative review UI on the generic review contract.

The project catalog page is read-only, uses `ProjectSummary`, and has Chinese/English labels, loading, empty, retry, and error states. Consultant flows must contain no `/admin/projects` call.

## Documentation and verification

Update `doc/用户端API文档.md`, `docs/FLUTTER_API_CONTRACT.md`, and `docs/CORE_ROLES_BUSINESS_SWIMLANE.puml` with these boundaries and route shapes.

Minimal backend evidence covers authorization/ownership/schema, fixed CONSULTANT JOIN and duplicate/resubmission behavior, context visibility, and professional project catalog access. Flutter evidence is capped at three core tests: contract decoding/request body, consultant page picker-and-submit behavior with explicit role, and read-only project catalog rendering. Run focused tests first and at most one broader suite after they pass.

If persistence tests are needed, derive `WORKTREE_ID=consultant-management-contract`, print the resolved host and database, use `myapp_worktree_consultant_management_contract`, and never connect to or reset the shared development database.
