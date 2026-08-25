# User Agreement and Privacy Policy Design

## Summary

Joysong will manage two public legal documents: **User Agreement** and
**Privacy Policy**. Administrators edit bilingual rich-text content in the
admin application, preview it, and publish an immutable release. The backend
serves the current published release both as JSON for Flutter and as a public
HTML page for app-store metadata and browser access.

Flutter exposes the documents from the existing login and registration consent
rows and from the existing Settings buttons. Login and registration retain
their current checkbox gates. This phase does not send a document version with
authentication requests and does not create server-side user consent records.

## Goals

- Replace the placeholder agreement and privacy dialogs with complete,
  remotely managed documents.
- Give administrators a safe bilingual draft, preview, publish, and history
  workflow.
- Keep published legal text immutable and preserve prior releases.
- Present the same published content in Flutter and at a public HTTPS URL.
- Keep the User Agreement and Privacy Policy links on login and registration.
- Make both documents accessible from their existing Settings entries.
- Reuse existing project architecture and rich-content rendering where safe.

## Non-Goals

- Adding any legal document type other than User Agreement and Privacy Policy.
- Adding a separate sensitive-personal-information consent document.
- Sending legal document IDs or versions in login or registration requests.
- Recording, withdrawing, or auditing user consent on the server.
- Requiring existing users to re-consent after a publication.
- Uploading Word or PDF files as the primary document content.
- Scheduling future publications or implementing multi-step legal approval.
- Supporting locales other than `zh-CN` and `en-US` in this phase.
- Drafting or approving the final legal language. Production text must be
  supplied and reviewed by the product owner's qualified legal reviewer.
- Updating App Store Connect, Google Play Data Safety, Android permission
  disclosures, or Apple's privacy manifest as part of this code change.

## Product Decisions

### Content source

The database is the source of truth. Each locale stores sanitized HTML edited
through the admin rich-text editor. File upload is not part of the authoring
flow. A downloadable PDF snapshot can be added later without changing the
primary content model.

### Entry points

The only Flutter entry points are:

1. User Agreement and Privacy Policy links in the login consent row.
2. User Agreement and Privacy Policy links in the registration consent row.
3. The existing User Agreement and Privacy Policy buttons in Settings.

Login and registration keep their current combined consent checkbox and local
submission guard. The existing device-local `agreementsAccepted` preference
continues to behave as it does today, but it is not a legal audit record. No
authentication API contract changes are introduced.

### Publication semantics

Every publication is a release containing both `zh-CN` and `en-US` content.
Publishing fails unless both locales have a non-empty title and body. A
published release cannot be edited or deleted. Editing published content begins
a new draft copied from the current release. Publishing the draft immediately
supersedes the prior release for that document type.

The backend assigns a monotonically increasing integer version independently
for each document type. Versions support content history and display only;
they are never bound to a user in this phase.

## Data Model

### `legal_document_releases`

- `id`
- `document_type`: `USER_AGREEMENT` or `PRIVACY_POLICY`
- `version`: positive integer, unique with `document_type`
- `status`: `DRAFT`, `PUBLISHED`, or `SUPERSEDED`
- `change_summary`
- `published_at`, `published_by`
- `created_at`, `created_by`, `updated_at`, `updated_by`
- optimistic-lock field for concurrent draft editing

At most one draft and one published release may exist per document type.
Application-level locking and database uniqueness enforce publication order.

### `legal_document_contents`

- `id`
- `release_id`
- `locale`: `zh-CN` or `en-US`
- `title`
- `content_html`
- `content_sha256`
- `created_at`, `updated_at`

`(release_id, locale)` is unique. The hash is generated after server-side HTML
sanitization and supports integrity checks and future snapshot exports.

No user consent table is created.

## Backend Behavior

### Public JSON API

`GET /api/public/legal-documents/{type}?locale={locale}`

- `{type}` is `user-agreement` or `privacy-policy`.
- `{locale}` accepts only `zh-CN` or `en-US`.
- The endpoint returns the current published release without authentication.
- The response contains `type`, `locale`, `version`, `title`, `contentHtml`,
  `publishedAt`, and `contentSha256`.
- A missing publication returns the project's standard `404` envelope.
- Unsupported types or locales return the standard validation error.
- Response caching uses an ETag based on `contentSha256`; publishing invalidates
  any application cache for the affected type.

### Public HTML page

`GET /legal/{type}?locale={locale}`

The server wraps the same sanitized release in a minimal responsive HTML
document. It includes the title, version, publication date, body, and a language
switch. The page is anonymous and suitable for the privacy-policy URLs required
by app stores. It does not load analytics, third-party scripts, remote fonts, or
advertising resources.

### Admin API

- `GET /api/admin/legal-documents`: current release and draft summary for both
  document types.
- `GET /api/admin/legal-documents/{type}/history`: immutable release history.
- `POST /api/admin/legal-documents/{type}/draft`: create the next draft,
  copying the current publication when present.
- `GET /api/admin/legal-document-releases/{id}`: load both locale contents.
- `PUT /api/admin/legal-document-releases/{id}`: update a draft only.
- `POST /api/admin/legal-document-releases/{id}/publish`: atomically validate
  and publish the draft.

Existing `/api/admin/**` authorization protects admin operations. The public
JSON and HTML GET routes are explicitly added to the anonymous SecurityConfig
allowlist. No public write route is introduced.

### Publish transaction

Publishing performs one transaction:

1. Lock the document type's releases.
2. Verify the target is the only draft.
3. Sanitize both locale bodies and require non-empty visible text.
4. Recompute each content hash.
5. Mark the prior `PUBLISHED` release `SUPERSEDED`.
6. Mark the draft `PUBLISHED` and set publisher and timestamp.
7. Invalidate the public document cache.

Any failure rolls back all steps so Chinese and English cannot diverge.

## HTML Safety

WangEditor remains the admin authoring component, but the legal editor uses a
restricted toolbar. The initial allowlist includes paragraphs, headings,
ordered and unordered lists, list items, bold, emphasis, underline, blockquote,
line breaks, and links.

The backend sanitizes every draft save and again at publication. Scripts,
styles, iframes, forms, images, media, event-handler attributes, and unsafe URL
schemes are removed. Links permit only `https`, `mailto`, and `tel`. Client-side
rendering never treats admin HTML as executable code.

## Admin Application

Add **协议与隐私** under content operations. The page contains two document
cards, one for each type, showing current version, publication date, draft
state, and actions.

Editing uses Chinese and English tabs within one draft form. Administrators can:

- create a draft from the current publication;
- edit title, body, and change summary;
- preview the sanitized content in desktop and narrow mobile widths;
- publish only after both locales pass validation;
- inspect prior immutable releases.

The existing `RichTextEditor` is reused with a configurable restricted toolbar.
The legal feature receives its own API types and page rather than using generic
CRUD or the article endpoint. Initial access uses the existing admin-only route;
a dedicated capability is deferred.

## Flutter Application

### Data boundary

Add a small legal-document feature with:

- a document type and document DTO;
- a repository interface;
- an API implementation using the anonymous API client;
- a controller with loading, ready, not-found, and failure states;
- a reusable full-screen `LegalDocumentPage`.

The page selects `zh-CN` or `en-US` from the current application language. It
shows a loading state, then the title, version/publication metadata, and
selectable rich content. Fetch failures show localized retry UI. A missing
publication never displays placeholder legal language.

This phase keeps only an in-memory cache for the running app session. Persistent
offline legal-document storage is deferred; login and registration already
require a working backend connection.

### Navigation integration

- Replace the placeholder `_showLegalText` path in AuthGate with navigation to
  `LegalDocumentPage`.
- Keep both login callbacks wired to the two document types.
- Keep both registration callbacks wired to the same pages.
- Inject Settings callbacks from AppRouter so both existing Settings entries
  open the same pages.
- Do not change checkbox behavior, login requests, registration requests, or
  local agreement preference serialization.

The existing native `RichContentView` is extended only as needed for the safe
legal HTML allowlist. Legal links become actionable through `url_launcher`.
No WebView dependency is added.

## Error Handling

- Admin draft validation errors remain inline and never alter the current
  publication.
- Concurrent draft updates return conflict rather than silently overwriting.
- Publish failures preserve the draft and current publication.
- Public APIs never expose draft or superseded content through the current
  endpoint.
- Flutter network and parse failures show retry UI while leaving the consent
  checkbox state untouched.
- External links with unsupported schemes remain visible as text but cannot be
  opened.

## Migration and Rollout

Add a new Flyway migration without modifying existing migration history. Per
the repository database rules, implementation must print the isolated database
host and database name and verify the migration against a newly created empty
worktree database before broader backend tests.

No production legal document is silently seeded. Test fixtures may seed sample
content, but deployment requires a legally reviewed Chinese and English release
for both document types before store submission. The admin publishes those
initial releases after deployment.

The public HTTPS URLs must be configured in App Store Connect and Google Play
as a release-operation follow-up. Store privacy declarations and the Apple
privacy manifest must be reviewed against actual application data practices;
they are not generated from the document text automatically.

## Testing Strategy

Follow the repository's smallest-relevant-test rule.

### Backend

- Draft creation starts at the correct per-type version and copies current
  bilingual content.
- Draft edits reject published releases and optimistic-lock conflicts.
- Sanitization removes executable markup and unsafe links.
- Publishing requires complete `zh-CN` and `en-US` content.
- Publishing atomically supersedes the prior release.
- Public endpoints return only the current publication and correct locale.
- Anonymous public reads succeed; anonymous admin writes remain forbidden.
- HTML and JSON responses represent the same release and content hash.
- The new migration validates against a fresh isolated empty database.

### Admin

- Both document types and publication metadata render.
- Draft creation, bilingual validation, preview, save, and publish call the
  correct APIs.
- Published history is read-only.
- Unsafe server-normalized content is not reintroduced by the editor.

### Flutter

- Login and registration retain their agreement checkbox and both links.
- Each login and registration link opens the correct document type.
- Both Settings buttons open the same legal document pages.
- Chinese and English select the correct locale.
- Loading, ready, not-found, failure, and retry states render correctly.
- Safe headings, lists, emphasis, and links render; supported links launch.
- Unsupported URL schemes cannot launch.
- Authentication request payloads and local agreement persistence remain
  unchanged.

Run the focused backend classes, admin tests/typecheck, and Flutter widget tests
first. After they pass, run each affected package's broader check at most once,
stopping any full suite that exceeds ten minutes and reporting the slowest
progress observed.
