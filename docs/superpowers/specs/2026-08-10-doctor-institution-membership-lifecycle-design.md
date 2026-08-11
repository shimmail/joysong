# Doctor Institution Membership Lifecycle Design

## Objective

Provide a complete, auditable doctor-to-institution relationship workflow:

- A certified doctor can request to join an institution.
- A doctor with an active relationship can request to leave that institution.
- A legal representative can approve or reject requests for institutions they manage.
- An approved leave immediately prevents new business under that relationship while preserving historical orders and audit records.
- The Flutter app exposes the workflow through an obvious entry instead of hiding it only inside the generic professional-management capability list.

## Confirmed Product Rules

- Institution legal representatives cannot invite doctors or consultants.
- Institution relationship reviews have only `APPROVED` and `REJECTED`; there is no `CHANGES_REQUESTED` action.
- A rejection requires a review reason. The doctor may correct the request and submit a new one.
- Approval of a leave request revokes the doctor-institution relationship and removes all doctor project bindings for that institution.
- Existing orders and review history remain available. Only new orders are prohibited.
- Doctor project requests retain their previously agreed three review outcomes; this design changes only institution relationship reviews.

## Current-State Diagnosis

The current implementation stores doctor join applications directly in `doctor_institutions`. The same row is both a pending request and the eventual active relationship. This supports joining but cannot represent an active relationship and a pending leave request at the same time.

The Flutter implementation is reachable through `My -> Professional management`, but the institution workflow is not a distinct first-level destination. This makes the merged feature easy to miss. The repository also contains a legacy Android application with the same package name; the supported implementation for this workflow is `joysong-flutter`.

## Considered Approaches

### 1. Dedicated change-request table

Create a request table for `JOIN` and `LEAVE`, and make `doctor_institutions` represent relationships only. This cleanly separates workflow history from current state and supports an active relationship plus a pending leave request.

This is the selected approach.

### 2. Add a pending action to `doctor_institutions`

This requires fewer schema changes but continues mixing current state and workflow state. It also overwrites review history and makes concurrency rules harder to express.

### 3. Add a leave-only request table

This is the smallest immediate change, but join and leave would use different storage and APIs. The resulting duplication would make future maintenance and audit reporting harder.

## Data Model

### `doctor_institution_change_requests`

Add a new table with these core fields:

| Field | Meaning |
| --- | --- |
| `id` | Request identifier |
| `doctor_id` | Applying doctor |
| `institution_id` | Target institution |
| `action` | `JOIN` or `LEAVE` |
| `status` | `PENDING`, `APPROVED`, `REJECTED`, or `WITHDRAWN` |
| `request_note` | Doctor-provided explanation |
| `review_note` | Required for rejection |
| `submitted_by` | Doctor user identifier |
| `reviewed_by` | Legal representative or platform administrator |
| `submitted_at`, `reviewed_at`, `updated_at` | Audit timestamps |

A generated nullable key and unique index enforce at most one pending request for a doctor and institution, regardless of action.

Database checks enforce valid action/status values and require a review note for `REJECTED` requests.

### `doctor_institutions`

After migration, this table represents relationship state only:

- `APPROVED` means active.
- `REVOKED` means historical and inactive.
- Future pending, rejected, and withdrawn workflow states live only in the request table.

Existing application reads must continue to treat only non-deleted, non-revoked `APPROVED` rows as active relationships.

## Migration Strategy

The migration must be safe for existing production data:

1. Create `doctor_institution_change_requests` and its constraints.
2. Copy every existing `doctor_institutions` row into an immutable historical `JOIN` request.
3. Normalize historical `CHANGES_REQUESTED` membership decisions to `REJECTED`, preserving the review note.
4. Keep `APPROVED` and `REVOKED` rows in `doctor_institutions` as relationship history.
5. Remove or soft-delete non-active relationship rows after their request history has been copied.
6. Normalize consultant institution membership `CHANGES_REQUESTED` states to `REJECTED`; consultant joining remains on its existing relationship flow in this scope.
7. Rebuild relevant status checks so institution membership reviews accept only `APPROVED` and `REJECTED` as reviewer decisions.

The migration will be verified from both a fresh empty database and a fixture database containing pending, approved, rejected, changes-requested, revoked, and soft-deleted relationships.

## Backend API

Introduce doctor-specific endpoints under:

`/api/management/doctor-institution-change-requests`

### List

`GET /api/management/doctor-institution-change-requests`

- Doctors see their own request history.
- Legal representatives see requests for institutions they manage.
- Platform administrators may see all requests.
- The response includes doctor and institution display names plus the current relationship state.

### Submit

`POST /api/management/doctor-institution-change-requests`

Body:

```json
{
  "institutionId": "institution-id",
  "action": "JOIN",
  "requestNote": "optional explanation"
}
```

Rules:

- Only a currently certified doctor may submit.
- `JOIN` requires that no active relationship exists.
- `LEAVE` requires an active relationship.
- Only one pending request is allowed per doctor and institution.
- Duplicate/concurrent submissions return a stable conflict response rather than a database exception.

### Withdraw

`POST /api/management/doctor-institution-change-requests/{id}/withdraw`

Only the submitting doctor may withdraw their own `PENDING` request.

### Review

`POST /api/management/doctor-institution-change-requests/{id}/review`

Body:

```json
{
  "decision": "APPROVED",
  "reviewNote": ""
}
```

Rules:

- Reviewers may use only `APPROVED` or `REJECTED`.
- Rejection requires a non-empty reason.
- A non-admin reviewer must manage the target institution.
- The service locks the request and relevant relationship row before revalidating current state.
- A request can be reviewed only once.

The existing membership endpoint remains temporarily available for consultant join requests. Doctor UI and new doctor requests use only the new API.

## Transactional Approval Behavior

### Approve JOIN

Within one transaction:

1. Revalidate doctor certification and institution existence.
2. Lock or create the doctor-institution relationship.
3. Create or restore it as `APPROVED`, clearing revocation fields.
4. Select it as primary only when the doctor has no other active primary institution.
5. Synchronize the doctor's denormalized primary institution fields when necessary.
6. Mark the request `APPROVED` with reviewer audit fields.

Approval does not create doctor project bindings. Joining projects remains a separate doctor request and institution review flow.

### Approve LEAVE

Within one transaction:

1. Revalidate and lock the active doctor-institution relationship.
2. Mark it `REVOKED` and set `revoked_at`.
3. Remove all `doctor_projects` bindings whose institution project belongs to the institution.
4. Soft-delete associated doctor-institution-project split configurations.
5. Withdraw pending doctor project change requests for that institution.
6. Close pending split proposals that can no longer be applied.
7. If this was the primary institution, select another active relationship deterministically; otherwise clear the doctor's primary institution fields.
8. Mark the leave request `APPROVED` with reviewer audit fields.
9. Invalidate or refresh authorization/session context so new requests immediately use the reduced institution scope.

Existing orders are not cancelled or rewritten. Their doctor, institution, project, price, and split snapshots remain authoritative for historical and in-progress processing. New order creation fails because both the active relationship and doctor project binding are absent.

### Reject

The relationship and all downstream bindings remain unchanged. The request becomes `REJECTED` with the required reason, and the doctor may create a new request later.

## Flutter Experience

The supported client is `joysong-flutter`.

### Discoverable entry

Add a visible `Institution relationships` item to the `My` page near professional identity and management. Opening it loads the management context and renders role-appropriate views:

- Doctor: `My institutions`
- Legal representative: `Member reviews`
- A user holding both roles: a segmented control switches between the two views.

The generic professional-management page retains project and profile capabilities, but institution joining and leaving no longer depend on users discovering a nested capability card.

### Doctor view

- Current institutions appear first with primary/active/pending-leave states.
- Each active institution has a `Request to leave` command with a confirmation dialog explaining that project bindings will be disabled while historical orders remain.
- Available institutions support `Request to join`.
- Pending requests can be withdrawn.
- Request history shows action, status, submitted note, reviewer reason, and timestamps.
- Joined institutions and institutions with pending requests cannot be submitted again.

### Legal representative view

- Pending requests appear first and are limited to managed institutions.
- Each row clearly labels `Join institution` or `Leave institution`.
- Review actions are `Approve` and `Reject` only.
- Reject requires a reason before submission.
- Processed requests remain visible as history.
- The reviewer cannot edit the doctor's submitted note.

## Error Handling and Concurrency

- Database unique constraints protect pending-request invariants.
- Services convert duplicate-key races to stable conflict responses.
- Review uses row locks and conditional status updates.
- Approval revalidates relationship state after acquiring locks.
- A stale `JOIN` approval fails if the relationship is already active.
- A stale `LEAVE` approval fails if the relationship is no longer active.
- Cleanup and request status changes occur in one transaction; partial revocation is not allowed.

## Tests

### Backend

- Doctor can submit valid JOIN and LEAVE requests.
- Invalid role, duplicate state, and duplicate pending requests are rejected.
- Doctor can withdraw only their own pending request.
- Legal representative can review only managed-institution requests.
- Rejection requires a reason and never changes the relationship.
- JOIN approval creates/restores exactly one active relationship.
- LEAVE approval revokes the relationship and removes all institution project bindings/configuration.
- LEAVE approval preserves orders and order snapshots.
- Primary institution selection is updated deterministically.
- Concurrent submit and review paths return stable domain errors.
- Historical membership migration preserves notes and normalizes `CHANGES_REQUESTED` to `REJECTED`.

### Flutter

- `My -> Institution relationships` is visible when the identity repository is configured.
- Doctor and legal representative contexts render the correct view.
- Doctor can submit JOIN/LEAVE and withdraw a pending request.
- Legal representative sees only approve/reject and cannot submit an empty rejection reason.
- Models parse migrated history and current API responses.

### Verification

- Run the smallest related backend test classes first, then one backend full suite.
- Run Flutter analysis and focused widget/model tests when the Flutter SDK is available.
- Verify migrations against a fresh worktree-isolated MySQL database and a seeded historical fixture database.
- Confirm the legacy `joysong-app` is not used to validate this Flutter workflow.

## Non-Goals

- Institution-initiated invitations.
- Institution-initiated removal of a doctor through this workflow.
- Automatic creation of doctor project bindings after joining an institution.
- Cancelling or rewriting historical/in-progress orders on leave.
- Redesigning platform administrator identity-review workflows beyond compatibility with the new relationship model.

## Acceptance Criteria

The feature is complete when a certified doctor can visibly access the institution relationship page, request either join or leave, withdraw a pending request, and see history; a legal representative can approve or reject requests only for managed institutions; and an approved leave atomically disables the relationship and all institution project bindings while preserving existing orders and audit records.
