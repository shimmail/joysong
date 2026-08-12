# Professional Read-only Catalog Design

**Date:** 2026-08-12

## Purpose and boundary

The professional Flutter app shall let an active doctor or legal representative browse the catalog that the current server already exposes, while keeping every administrator implementation unchanged. This slice changes only `joysong-flutter/` plus Flutter-facing documentation. It does not change `joysong-admin/`, server controllers, services, security, VO classes, migrations, or database data.

The app consumes these existing compatibility endpoints exactly as deployed:

- `GET /api/admin/institutions`
- `GET /api/admin/institutions/{id}`
- `GET /api/admin/institutions/{id}/doctors`
- `GET /api/admin/institutions/{id}/doctors/{doctorId}/projects`
- `GET /api/admin/institution-projects`
- `GET /api/admin/projects`
- `POST /api/admin/institution-project-requests/{id}/withdraw`

The `/admin` path segment is an existing compatibility contract, not permission to expose administrator functions. The server remains the authority for authentication and object visibility. Flutter never broadens results, synthesizes foreign ids, or falls back to a write endpoint when a read fails.

## Chosen approach

Use the existing discover domain models, repository decoding, catalog cards, and detail views as the single read-only presentation system. Add a professional catalog composition page that selects only capabilities and entry points; do not create a second set of catalog VO classes.

Alternatives rejected:

1. Copy the administrator pages into Flutter. This would duplicate behavior and violate the explicit boundary.
2. Build a parallel professional VO/repository hierarchy. This would drift from the already compatible discover contract and add redundant mapping.
3. Keep the current identity management editor and hide buttons. Hidden write code remains callable and test doubles can accidentally exercise it, so the professional write surface must be deleted or isolated from production registration.

## Architecture and data flow

`ManagementContext` remains the gate for professional workspace entries. An ACTIVE doctor and a legal representative with the corresponding context capability see a localized “Catalog / 目录” entry. Opening it constructs the shared read-only catalog with the existing `DiscoverRepository`; the professional shell owns search, loading, empty, error, and retry states but delegates cards and details to existing discover widgets.

The navigation flow is:

1. Context-gated workspace entry loads visible institutions and catalog projects through existing GET methods.
2. Search filters the already authorized returned set by localized institution/project/doctor display fields. It does not issue arbitrary object probes.
3. Institution selection loads only `/institutions/{id}` and `/institutions/{id}/doctors` for an id returned by the visible list.
4. Doctor selection loads `/institutions/{id}/doctors/{doctorId}/projects` only when both ids came from the current visible hierarchy.
5. Project selection reuses the existing discover project detail.

An HTTP 403 or 404 is presented as an unavailable/error state with retry; the client does not distinguish “not owned” from “not found,” preserving object-level privacy. Empty collections have role-neutral localized copy. A retry repeats only the failed GET.

## Pending request withdrawal

`IdentityRepository` gains one explicit operation for project change requests:

```dart
Future<void> withdrawDoctorProjectChangeRequest(String id);
```

`ApiIdentityRepository` sends `POST /admin/institution-project-requests/${id.trim()}/withdraw` with no mutation body. Doctor request UI shows the localized Withdraw action only when the request belongs to the doctor-facing list, has normalized status `PENDING`, and type is `JOIN` or `PROFILE_UPDATE`. Success refreshes the list; failure keeps the item and reports a localized error. Legal representatives and reviewers never see this action.

## Removal of professional write residue

Remove from the professional Flutter production graph:

- `createManagementProject` and its `POST /admin/projects` implementation;
- create, update, and delete institution-project repository operations using `/admin/institution-projects`;
- editor/controller actions and unreachable page branches whose only purpose is those operations.

Read operations required by the catalog and legitimate request submission/review flows remain. Existing administrator system code and endpoints are unchanged. A core test records all requests during doctor and legal catalog journeys and asserts that no project or institution-project write method is sent.

## Localization and states

All new visible strings use the project’s existing Chinese/English localization helper. The shared page includes localized title, search hint, no-results state, empty-catalog state, load error, retry, and withdrawal confirmation/success/failure. Existing discover card and detail translations are reused.

## Verification

Keep the behavioral core at no more than three focused Flutter tests:

1. Pending doctor `JOIN` and `PROFILE_UPDATE` requests expose Withdraw and call the exact withdraw endpoint; non-pending requests do not.
2. ACTIVE doctor can traverse visible institution → doctor → projects, search and retry, with no write request.
3. Legal representative can traverse the visible institution/project catalog and cannot reach any write action; request log contains only GET calls.

Then run `flutter analyze`. No backend, database, administrator app, production environment, or migration test belongs to this slice.

## Documentation

Update `docs/FLUTTER_API_CONTRACT.md` and the existing professional user guide/swimlane only to describe Flutter reachability, roles, states, and the exact compatibility endpoints. State explicitly that administrator behavior is unchanged.
