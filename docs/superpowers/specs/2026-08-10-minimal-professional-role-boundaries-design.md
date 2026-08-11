# Minimal Professional Role Boundaries

Date: 2026-08-10

## Goal

Define the smallest useful capability boundary for institution legal representatives, doctors, and consultants. The design reduces overlapping authority and keeps each role responsible only for its own core object.

## Role Boundaries

### Institution Legal Representative

Core object: one approved institution membership.

Allowed:

- Edit public information for institutions where the user has an approved legal representative membership.
- Review doctor requests to join the represented institution.
- Review consultant requests to join the represented institution.
- Review doctor requests to create institution projects under the represented institution.
- Approve, reject, or request changes for institution-scoped requests.

Not allowed:

- View or manage orders.
- Invite doctors or consultants.
- Edit doctor profiles.
- Edit consultant profiles.
- Create platform base projects.
- Directly create or publish a doctor's service content.
- Manage another institution.
- Manage split configuration, customer lists, or finance workflows.

### Doctor

Core object: the doctor's own profile and service supply.

Allowed:

- Edit the doctor's own public profile after platform doctor identity approval.
- Apply to join an institution.
- Apply to platform admins for a new platform base project.
- View every institution project under institutions where the doctor has an approved practice relationship.
- Apply to join an existing institution project.
- Apply to an institution legal representative to create an institution project.
- Provide service content, price suggestion, and notes for the institution project request.
- Maintain the doctor's own service description for approved institution project bindings.

Not allowed:

- Edit institution information.
- Approve members.
- Approve consultants.
- Create platform base projects directly.
- Create institution projects without legal representative approval.
- Override legal representative review decisions.

### Consultant

Core object: the consultant's own identity and institution membership.

Allowed:

- Apply for platform consultant identity approval.
- Apply to join an institution after consultant identity approval.
- Show approved institution affiliation where needed.

Not allowed:

- View or manage orders.
- Edit institution information.
- Approve members.
- Create or manage projects.
- Access customer lists.
- Configure split rates or finance data.

## Approval Flows

### Legal Representative Identity

1. User submits legal representative identity materials.
2. Platform admin reviews the identity application.
3. On approval, the system creates or confirms the institution and legal representative membership.
4. The legal representative can manage only that institution's public profile and institution-scoped requests.

### Doctor Identity And Institution Join

1. User submits doctor identity materials.
2. Platform admin reviews doctor identity only.
3. Approved doctor may edit their doctor profile.
4. Doctor applies to join an institution.
5. The target institution's legal representative approves, rejects, or asks for changes.
6. No platform admin second review is required for joining the institution.

### Consultant Identity And Institution Join

1. User submits consultant identity materials.
2. Platform admin reviews consultant identity only.
3. Approved consultant applies to join an institution.
4. The target institution's legal representative approves, rejects, or asks for changes.
5. The approved consultant membership only represents affiliation. It does not grant orders, project, customer, split, or finance capabilities.

### Platform Base Project Request

1. Doctor submits a request for a missing platform base project.
2. Platform admin approves or rejects the request.
3. Approved base projects become available for later institution project requests.

### Institution Project Request

1. Doctor selects an existing platform base project and submits an institution project request to an institution.
2. Doctor provides service content, price suggestion, and notes.
3. Institution legal representative reviews the request.
4. Legal representative may approve, reject, or request changes.
5. Legal representative does not directly edit the doctor's submitted content.
6. Approved requests create institution-scoped project availability according to backend rules.

### Existing Institution Project Join Request

1. Doctor sees all institution projects under institutions where the doctor has an approved practice relationship.
2. Doctor selects an existing institution project and submits service content, a price suggestion, and notes.
3. The institution legal representative may approve, reject, or request changes, but cannot edit the submission.
4. Approval creates only the doctor-to-institution-project binding; it never creates a duplicate institution project.

## Doctor-Specific Institution Project Pricing

- `institution_projects.price` remains the institution project reference price.
- `doctor_projects.price` is the doctor's actual service price for that institution project and is mandatory.
- Existing doctor-project bindings are backfilled once from the institution project reference price during migration.
- Runtime order pricing never falls back to the institution project reference price.
- Join and new-institution-project requests both carry the doctor's proposed price.
- Approval copies the proposed price into `doctor_projects.price`; the legal representative cannot directly change it.
- Approval of a new institution project also uses the proposal as the first reference price and binds the submitting doctor at the same explicit doctor price.
- Institution project lists show the minimum available doctor price as a starting price, while doctor choices show each doctor's own price.
- Order creation requires a selected doctor with an explicit active doctor-project price and snapshots that price into the order.

## Current Implementation Gaps

- The Flutter legal representative order entry was removed, but backend/admin order permissions should still be reviewed so legal representatives cannot reach order data through admin routes or APIs.
- Current admin identity membership management is admin-only. Institution legal representatives need a scoped review surface for join requests, not a generic member creation tool.
- Consultant identity exists in admin role options, but Flutter currently lacks a complete consultant identity/join flow equivalent to this boundary.
- Institution legal representatives must not be able to invite doctors or consultants. Any existing generic create-membership UI should remain platform-admin only or be removed from legal representative surfaces.
- Institution project creation should be request-based for doctors and review-based for legal representatives. Existing direct project editing/publishing paths should be checked against this rule.
- Platform base project creation should be admin-owned. Doctor-facing UI should submit requests instead of creating base projects directly.

## Implementation Direction

- Keep role authorization centralized in the management context and backend object-level checks.
- Separate identity approval from institution membership approval.
- Model institution membership requests as user-initiated records, not legal representative invitations.
- Model institution project requests as doctor-initiated and institution-reviewed.
- Avoid granting order, customer, split, or finance capabilities to legal representatives and consultants in the minimal version.

## Test Strategy

- Backend service tests for each role boundary and forbidden action.
- Controller tests for institution-scoped legal representative approval.
- Flutter widget tests for visible capabilities per role.
- Admin frontend route tests to ensure legal representatives cannot access admin-only identity, customer service, orders, or finance surfaces.
