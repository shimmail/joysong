# Doctor Project Application Full VO Design

Date: 2026-08-15

## Goal

Make doctor-submitted platform-project and institution-project applications carry the same business content as the corresponding admin creation forms, while preserving approval boundaries and adding the applicant doctor's consultation-fee and split proposal to institution-project creation.

## Confirmed Decisions

- A doctor application to create a platform project contains the project content used by the admin project form.
- A doctor application to create an institution project contains the institution-project content used by the admin institution-project form, plus consultation fee and split rates.
- Neither application accepts rating or review count. Approved records initialize both values to `0`.
- The institution-project application does not accept multi-doctor selection. The authenticated applicant doctor is bound automatically after approval.
- Platform-project applications are reviewed by platform administrators only.
- Institution-project applications are reviewed by a legal representative of the target institution or by a platform administrator.
- Review decisions are limited to `APPROVED` and `REJECTED`. Rejection requires a non-blank review note.
- Reviewers cannot edit a submitted payload. A rejected application must be submitted again as a new application.
- The operator clears old institution-project application data manually before the DDL migration. The migration must not contain data-deletion statements.
- Old initializer-owned project-application examples are removed and replaced with a minimal set matching the new VOs.

## Scope

This design changes:

- doctor-facing Flutter platform-project and institution-project application forms;
- professional project request wire models and review views;
- `professional_project_requests` persistence;
- platform-admin and institution-scoped review behavior;
- approval-time project, doctor binding, and split-config writes;
- initializer-owned project-application sample data;
- related backend, Flutter, migration, contract, and persistence tests.

It does not change the direct admin project or institution-project forms, the existing join-project workflow, or the existing doctor project profile-update workflow.

## Roles And Review Authority

| Application | Submitter | Reviewer | Approval result |
|---|---|---|---|
| Platform project | Authenticated active doctor | Platform administrator only | Create one `projects` row |
| Institution project | Authenticated active doctor with an approved practice relationship to the target institution | Target institution legal representative or platform administrator | Atomically create one `institution_projects` row, one applicant `doctor_projects` binding, one applicant split config, and close the request |

An institution legal representative may review only applications whose `institution_id` belongs to the representative's current approved scope. A platform administrator may review all institution-project applications.

## Platform Project Application VO

The request body has exactly these fields:

```kotlin
data class DoctorPlatformProjectRequest(
    val name: String,
    val category: String,
    val description: String,
    val referencePrice: BigDecimal = BigDecimal.ZERO,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val slogan: String = "",
    val salesCount: Int = 0,
    val coverImage: String = "",
    val images: List<String> = emptyList(),
    val detailContent: String? = null,
    val tags: List<String> = emptyList(),
    val categoryTags: List<String> = emptyList(),
    val notes: String = ""
)
```

`name`, `category`, and `description` are required after trimming. `referencePrice` and `salesCount` are non-negative. `images`, `tags`, and `categoryTags` are JSON string arrays on the wire. The application never accepts `rating`, `reviewCount`, `doctorId`, `doctorIds`, or `doctorBindings`.

Approval creates the platform project with `rating = 0` and `reviewCount = 0`, regardless of entity defaults used by other creation paths.

## Institution Project Application VO

The Flutter draft contains the target institution so the form is self-contained:

```kotlin
data class DoctorInstitutionProjectRequestDraft(
    val institutionId: String,
    val projectId: String,
    val name: String? = null,
    val category: String? = null,
    val description: String? = null,
    val tags: List<String>? = null,
    val slogan: String? = null,
    val detailContent: String? = null,
    val price: BigDecimal,
    val originalPrice: BigDecimal? = null,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val coverImage: String? = null,
    val images: List<String>? = null,
    val salesCount: Int = 0,
    val isActive: Boolean = true,
    val consultationFee: BigDecimal,
    val commissionRate: BigDecimal,
    val institutionRate: BigDecimal,
    val notes: String = ""
)
```

The existing endpoint keeps `institutionId` in `/api/management/project-requests/institutions/{institutionId}`. The exact JSON request body therefore contains the other 18 fields and does not duplicate `institutionId`.

`name`, `category`, `description`, `tags`, `slogan`, `detailContent`, `coverImage`, and `images` are independent institution-project overrides. Null or empty values inherit the corresponding platform-project display value. `price`, `consultationFee`, `commissionRate`, and `institutionRate` are required.

The application must reject `rating`, `reviewCount`, `doctorId`, `doctorIds`, `doctorBindings`, `platformRate`, and `doctorRate`.

## Split Presentation And Validation

Flutter retrieves the current platform rate from `GET /api/management/project-requests/institution-form-config` instead of hardcoding it:

```kotlin
data class InstitutionProjectApplicationFormConfig(
    val platformRate: BigDecimal
)
```

The institution-project application form displays:

- editable `consultationFee`;
- editable `commissionRate`, labeled as the medical-aesthetic consultant rate;
- editable `institutionRate`;
- read-only `platformRate` from the server;
- read-only derived `doctorRate`.

The calculation is:

```text
doctorRate = 100 - platformRate - institutionRate - commissionRate
```

The submission body includes only the three editable stored values. The review view adds current derived values:

```kotlin
data class InstitutionProjectSplitView(
    val consultationFee: BigDecimal,
    val commissionRate: BigDecimal,
    val institutionRate: BigDecimal,
    val platformRate: BigDecimal,
    val doctorRate: BigDecimal
)
```

Money values must be between `0` and `99999999.99`, inclusive, with at most two decimal places. Each editable rate must be between `0` and `100`, inclusive, with at most two decimal places. The platform, institution, and consultant rates must not total more than `100`.

Submission and approval both validate the rates through `OrderSplitRatePolicy`. Approval revalidates against the current platform rate. If the configured platform rate changed and the stored proposal is no longer valid, approval fails without changing the application or creating partial business records.

## Review VO And State

The existing professional-project request view remains the common review envelope and is expanded to expose the submitted project snapshot. It continues to include request identity, applicant doctor, target institution where applicable, status, reviewer, review note, result IDs, and timestamps.

New application transitions are limited to:

- `PENDING`
- `APPROVED`
- `REJECTED`

Review decisions are limited to `APPROVED` and `REJECTED`. `REJECTED` requires a non-blank `reviewNote`; `APPROVED` may include an optional note. This change applies only to platform-project and new institution-project applications in `professional_project_requests`. It does not remove `CHANGES_REQUESTED` from unrelated doctor join or profile-update workflows.

The database may continue to recognize legacy `CHANGES_REQUESTED` rows so an existing platform-project application does not require destructive migration. No new API request can create that state, and Flutter exposes no review action for it.

## Approval Transactions

### Platform Project

Platform approval:

1. locks and verifies the pending request;
2. verifies the reviewer is a platform administrator;
3. validates the complete submitted snapshot;
4. creates a `projects` row with `rating = 0` and `review_count = 0`;
5. marks the request `APPROVED` and stores the resulting project ID.

All writes occur in one transaction.

### Institution Project

Institution approval:

1. locks and verifies the pending request;
2. verifies target-institution legal-representative scope unless the reviewer is an administrator;
3. revalidates the applicant's active approved practice relationship;
4. verifies the platform project still exists and the institution does not already expose it;
5. revalidates money and split values against the current platform rate;
6. creates the `institution_projects` row with `rating = 0` and `review_count = 0`;
7. creates the applicant's `doctor_projects` binding at the submitted `price`;
8. initializes the doctor binding's display fields from the effective submitted/inherited institution-project content;
9. creates `doctor_institution_project_configs` for the applicant and new institution project;
10. marks the request `APPROVED` and stores the resulting institution-project ID.

The request never carries a selectable doctor collection. `doctor_id` comes from the authenticated submitter and is stored on the request. All approval writes occur in one transaction; any failure rolls back all writes and leaves the request pending.

## Database Structure

Migration V28 is DDL-only. It contains no explicit data-deletion or cleanup statement.

`professional_project_requests` keeps its common identity, authority, review, result, timestamp, generated-key, index, and foreign-key columns.

The migration:

- drops obsolete `service_content`;
- renames `price_suggestion` to `price`;
- adds shared snapshot columns `tags`, `slogan`, `detail_content`, `currency`, `cover_image`, `images`, and `sales_count`;
- adds platform-only `reference_price` and `category_tags`;
- adds institution-only `original_price`, `is_active`, `consultation_fee`, `commission_rate`, and `institution_rate`;
- replaces the review-note, price, and request-shape constraints;
- adds non-negative checks for monetary and count fields;
- adds rate range and institution-plus-consultant sum checks.

New platform snapshot columns use safe schema defaults such as zero money/count values, default currency, empty text, and empty arrays so existing platform-project request rows remain readable. The service accepts only the two new review decisions even if the database status constraint retains legacy `CHANGES_REQUESTED` for historical compatibility.

It does not add `rating`, `review_count`, doctor selection, platform-rate, or doctor-rate columns.

No schema change is needed for `projects`, `institution_projects`, `doctor_projects`, or `doctor_institution_project_configs`. Those tables already contain the required target fields. The approval services explicitly persist zero rating and review count for records created through these application flows.

Before V28 runs, the operator manually clears all old institution-project creation requests. V28 must contain no `DELETE` statement. The implementation and verification workflow must print the isolated target database host and database name before applying migrations.

## Initializer Data Recomposition

The first implementation task removes the old initializer-owned project-application examples and their obsolete fixed IDs. It does not connect to or clear a real development database.

The initializer then provides the smallest useful independent examples:

- one complete pending platform-project application matching `DoctorPlatformProjectRequest`, for administrator review;
- one complete pending institution-project application matching `DoctorInstitutionProjectRequestDraft`, for target legal-representative review;
- one valid current doctor-project profile-update example matching its existing exact VO;
- one valid split-proposal example only if still needed to exercise the independent split-proposal screen.

Each example uses a distinct doctor/project/institution combination so pending uniqueness constraints do not interfere. Automated unit and integration test fixtures remain isolated and are updated to the new VOs rather than deleted.

## Flutter UX

The platform-project application form follows the admin project form's content order and reuses the existing Flutter image-upload callback for cover and gallery uploads.

The institution-project application form follows the admin institution-project form's content order, preserves optional inheritance semantics, adds the split section, and omits the multi-doctor selector. It shows the authenticated applicant as the doctor that will be associated after approval.

Flutter uses multiline text input for detail content; creating a new rich-text editor is outside this slice. Upload failures preserve existing form values. Submission failures preserve the complete draft and expose a retryable error. Fields clear only after a successful submission and refreshed request list.

Review surfaces show an immutable submitted snapshot. Platform applications are visible to administrators. Institution applications are visible to the applicant, the target institution's legal representatives, and administrators according to existing scoped list behavior.

## Error Handling

- `400`: malformed exact-shape body, invalid money/rate/count, missing required content, invalid review decision, or blank rejection note.
- `403`: unauthenticated doctor submission, out-of-scope institution submission, unauthorized platform review, or unauthorized institution review.
- `404`: target institution, platform project, or request does not exist.
- `409`: duplicate pending request, institution already exposes the platform project, concurrent review, applicant relationship invalidated before approval, or platform-rate drift makes the split invalid.

Clients retain the draft on all submission failures. Review failures never create partial project, binding, config, or status changes.

## Test Strategy

Backend tests cover:

- exact request-body keys and prohibited keys for both application types;
- validation boundaries for money, arrays, text, counts, currencies, and rates;
- platform administrator-only platform review;
- target legal representative and administrator institution review;
- rejection-note requirements and rejection of `CHANGES_REQUESTED` as a new review decision;
- duplicate-pending and concurrent-review behavior;
- zero rating and review-count initialization;
- applicant-only doctor binding;
- approval-time split revalidation;
- atomic creation and rollback of institution project, doctor binding, config, and request status;
- V28 against a newly created isolated empty database after printing host and database name.

Flutter tests cover:

- exact JSON serialization and response parsing;
- all approved form fields;
- absence of rating, review count, and multi-doctor controls;
- cover/gallery upload behavior;
- inheritance values;
- read-only platform rate and live doctor-rate calculation;
- invalid amount/rate/count blocking;
- draft retention on failure and clearing on success;
- role-based review page visibility.

Admin regression tests confirm direct admin forms and routes remain unchanged.

## Documentation

Update `docs/FLUTTER_API_CONTRACT.md` with exact request and review payloads, field validation, derived split values, authorization, statuses, and error codes.

Update the developer-facing flow under `design/` to show:

- doctor platform-project request to administrator review;
- doctor institution-project request to institution-legal-representative or administrator review;
- atomic institution-project, applicant binding, and split-config creation.

## Superseded Prior Decisions

For these two creation-request flows, this design supersedes the older minimal-role-boundary wording that allowed reviewers to request changes. Review is now approve or reject only. A target institution legal representative approving the split embedded in an institution-project application does not grant access to generic split-config management; it authorizes only the exact applicant/project proposal in the same approval transaction.

## Out Of Scope

- Editing a submitted request during review.
- Selecting multiple doctors during doctor-submitted institution-project creation.
- Changing the existing join-project or doctor profile-update request decisions.
- Freezing a platform rate per request or per doctor config; the platform rate remains centrally configured.
- Replacing the Flutter multiline detail editor with a new rich-text editor.
