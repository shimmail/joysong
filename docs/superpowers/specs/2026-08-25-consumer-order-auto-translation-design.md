# Consumer Order AI Auto-Translation Design

## Summary

Extend the existing opt-in consumer AI auto-translation experience to the
booking and order journey. When the App language is English, the user is
authenticated, and **AI automatic translation** is enabled, eligible Chinese
database text is shown immediately and then replaced asynchronously with
English. A failed or stale translation leaves the Chinese source visible.

This phase is Flutter-only. It reuses the existing authenticated
`POST /api/translations` endpoint and the translation configuration already
separated from `ai-agent`. It does not change backend contracts, YAML files,
provider configuration, order data, or payment behavior.

The feature covers consumer booking, order list/detail, payment and order
summary surfaces, refund details, order progress, order notifications, and the
consumer diary order picker. Professional and operational order-management
surfaces remain unchanged.

This design extends
`2026-08-24-consumer-auto-translation-design.md`; its setting, activation,
concurrency, cache, source-first rendering, and stale-result rules continue to
apply.

## Decisions

- Use the existing Flutter translation core and backend content-type whitelist.
- Do not add `order`, `refund`, or `notification` backend content types in this
  phase.
- Translate persisted, read-only booking notes, refund text, rejection reasons,
  and status-log remarks after explicit user opt-in.
- Never translate or replace text while the user is editing or submitting it.
- Keep every reusable page and component default-off; only consumer entry
  points explicitly opt in.
- Keep payment, identity, authorization, and workflow decisions outside the
  translation layer.

## Goals

- Let English-speaking consumers understand Chinese database content throughout
  the booking and order lifecycle.
- Preserve immediate page rendering and source fallback.
- Reuse the existing API, switch, controller, concurrency limit, in-flight
  deduplication, and bounded cache.
- Prevent names, identifiers, money, dates, payment data, and workflow values
  from being submitted for translation.
- Keep translated values attached to the correct entity and field across list
  refreshes, pagination, reordering, timers, and detail reloads.
- Preserve all Chinese professional, institution, doctor, consultant, and admin
  workflows.

## Non-Goals

- Adding translated database columns, persistent translations, or a batch API.
- Changing translation or `ai-agent` YAML configuration.
- Adding new backend content types or changing `/api/translations`.
- Translating payment-provider responses, URLs, failure details, or security
  instructions.
- Translating person names or identifiers.
- Translating or rewriting booking, refund, or review input fields and drafts.
- Changing order notification navigation or making notifications deep-link to a
  specific order.
- Redesigning booking, order, refund, payment, review, or conversation UX.
- Reimplementing order-service conversation translation; received text messages
  already use the existing message translation path.

## Activation and Consent

The existing global conditions remain mandatory:

1. App language is English.
2. The user enabled **AI automatic translation** in Settings.
3. The user is authenticated.
4. The source is non-empty and contains Chinese text.
5. The page and field are explicitly allowlisted by this design.

The switch is the consent boundary for read-only order free text. Persisted
booking notes, refund text, rejection reasons, order-log remarks, and order
notification text may contain personal, medical, or operational information.
Only the individual visible text value is submitted to `/api/translations`;
order DTOs, operator metadata, payment records, IDs, and other fields are not
submitted.

Disabling the switch, changing to Chinese, or logging out immediately returns
every translated field to its source value and invalidates queued or stale
results under the existing generation rules.

## Consumer Page Coverage

### Booking confirmation

`BookingPage` translates:

- institution-project name;
- institution name;
- doctor professional title, independently from the doctor name.

Doctor and consultant names stay in the source language. The combined doctor
row is rendered from an untranslated name plus a separately translated title so
the name is never included in the translation request. Price, quote, date/time,
and the booking-note input remain outside translation.

The model's project description is not currently displayed on this page. This
feature does not add UI merely to expose or translate it.

### My orders

`OrdersPage` translates each eligible order card's:

- project name;
- visible institution name.

Doctor name, order status, refund status, price, appointment time, image URL,
and card actions remain deterministic source or localized values.

### Order detail

`OrderDetailPage` translates:

- project name;
- visible institution name;
- persisted booking note;
- refund reason;
- refund description;
- refund rejection reason;
- each visible order-status-log remark.

Dates and log remarks are rendered separately; only the remark is submitted.
Names, order number, all IDs, verification codes, settlement values, status
codes, amounts, currency, and permissions stay outside translation.

### Payment, refund, and review summaries

`PaymentPage`, the refund request flow, and `ReviewOrderPage` translate only the
shared order summary's project and institution names.

The following stay untouched:

- amount, currency, provider, payment method, payment status, expiry, countdown,
  next action, URL, failure code, and failure message;
- refund reason/description while the request form is being edited;
- review body, tags, rating, and images while a review is created or edited.

After a refund is submitted and its persisted read-only detail is returned, the
detail fields become eligible under the order-detail rules. An existing review
loaded into an editor is never automatically replaced because that would change
the value the user may submit.

### Order notifications

On the consumer notification page, translate `title` and `content` only for
order-related rows. The eligible target types are `order`, `order_refund`, and
`order_service_conversation`, as identified by the existing parsed target kinds
`orderDetail` and `orderServiceConversation`. Time, target ID, navigation data,
and all non-order notifications stay unchanged.

The existing notification navigation resolved by `NotificationTarget` is not
changed.

### Consumer diary order picker

The diary association summary and order picker translate project and
institution names. Doctor name, order number, and IDs remain source values.

This is included because it is a consumer surface that reads the same order
snapshots, even though it lives under the social feature.

### Order-service conversation

No new conversation implementation is added. The existing `DmThreadPage`
continues to translate only received `TEXT` messages with stable message IDs.
The user's own messages, images, peer names, timestamps, conversation
readability, and send permission are unchanged.

## Explicit Exclusions

Never submit these standalone fields to AI translation:

- doctor, consultant, ground-service worker, user, reviewer, or operator names;
- order number, order ID, project/institution/doctor/consultant IDs, payment ID,
  provider payment ID, target ID, or idempotency key;
- phone numbers, verification codes, QR codes, certificate numbers, or URLs;
- price, paid/refund/discount amounts, service fees, currency, rates, quantity,
  rating, counters, dates, times, and countdowns;
- order/refund/payment/settlement status values, payment flow, provider, payment
  method, failure code, next action, or permission flags;
- static UI labels, buttons, confirmations, and security instructions, which use
  existing deterministic localization;
- controller/API errors and upstream payment failure messages.

Free-text values approved above are submitted as complete visible values after
opt-in. Translation never changes the underlying DTO, controller state, request
payload, database record, or input controller.

## Architecture

### Default-off page boundary

The root `AutoTranslationScope` is globally available, so scope activation alone
must not make a page eligible. Consumer pages and reusable order widgets expose
an `enableAutoTranslation` value whose default is `false`.

The consumer `AppShell` entry points explicitly pass `true` for:

- `BookingPage`;
- `OrdersPage`;
- `OrderDetailPage`;
- consumer `ReviewOrderPage` entry points;
- consumer notification page;
- the consumer diary order picker.

`OrderDetailPage` propagates this flag to payment, refund, review, order summary,
refund detail, and status timeline children. Tests and future callers that omit
the flag remain source-only.

### Stable reusable translation builder

Add a small reusable stable builder in the existing core translation builder
module. It accepts:

- explicit `enabled`;
- content type;
- content ID;
- field;
- source text;
- optional validator;
- a rendering callback.

It retains an equivalent `AutoTranslationRequest` across rebuilds and replaces
the request only when one of its identity values changes. A text convenience
wrapper may be provided in the same file.

This avoids per-page request-state duplication and prevents high-frequency
rebuilds, especially the payment countdown, from repeatedly rescheduling the
same request. The existing `AutoTranslationController` remains responsible for
eligibility, concurrency, cache, in-flight sharing, failure fallback, and stale
generation handling.

### Content-type mapping

Use only the backend's current whitelist:

| Visible value | `contentType` | Stable content identity | `field` |
| --- | --- | --- | --- |
| Booking institution-project name | `project` | `institution-project:<id>` | `name` |
| Booking institution name | `institution` | `institution:<id>` | `name` |
| Booking doctor title | `doctor` | `doctor:<id>` | `title` |
| Order project snapshot | `project` | `order:<id>` | `projectName` |
| Order institution snapshot | `institution` | `order:<id>` | `institutionName` |
| Persisted booking note | `general` | `order:<id>` | `remark` |
| Refund reason | `general` | `refund:<id>` | `reason` |
| Refund description | `general` | `refund:<id>` | `description` |
| Refund rejection reason | `general` | `refund:<id>` | `rejectReason` |
| Status-log remark | `general` | `order-status-log:<id>` | `remark` |
| Order-related notification title | `general` | `notification:<id>` | `title` |
| Order-related notification content | `general` | `notification:<id>` | `content` |
| Manual diary project association | `project` | `project:<id>` | `name` |
| Manual diary institution association | `institution` | `institution:<id>` | `name` |

The `contentId` and `field` remain local Flutter cache/deduplication identity;
the current API sends only text, target language, and content type.

### Invalid and duplicate identity handling

An empty entity ID makes that entity source-only. For a rendered collection,
duplicate IDs in the same owner set make every duplicated entity source-only so
two records cannot share widget or translation state ambiguously.

Lists use stable entity ownership rather than list index. Prepending, pagination,
refresh, filtering, and reordering must keep completed or in-flight translations
attached to the same entity. A changed source value creates a new request under
the existing cache key rules.

## Rendering and Data Flow

1. Existing repositories load booking/order DTOs normally.
2. Controllers enter their current ready states without waiting for translation.
3. Eligible widgets render Chinese source values immediately.
4. Stable builders register allowlisted field requests with the shared
   controller.
5. The controller skips ineligible values, serves a cached result, shares an
   in-flight request, or queues a provider call under the existing limit of
   three concurrent requests.
6. A valid current result replaces only that visible field.
7. Failure, invalid identity, disposal, content refresh, language change, switch
   disablement, or logout leaves or restores source text.

Translation completion never triggers order actions, changes an action's
enabled state, creates a conversation, updates a form controller, or writes to a
repository.

## Error and Lifecycle Behavior

- Per-field automatic failures are silent and preserve source text.
- One failed field does not cancel other fields.
- Failed values remain retryable after a genuine page/data refresh.
- A rebuild with the same identity and source does not repeatedly retry a failed
  request.
- Old results cannot overwrite refreshed order, refund, log, notification, or
  payment-summary values.
- Payment polling, lifecycle resume, expiry timers, idempotency, and navigation
  operate independently of translation.
- Manual translation controls and caches retain their current behavior.

## Professional and Operational Boundary

Automatic order translation must make zero requests from:

- `DoctorOrdersPage` and `DoctorOrderDetailPage`;
- institution legal-representative order and project management;
- doctor service management;
- consultant membership, project, and management flows;
- professional catalog, identity, relationship, application, review, and edit
  pages;
- admin order, payment, refund, and settlement pages;
- institution consultant pickers that display person names.

These surfaces either use separate widgets or omit the default-off opt-in flag.
An active global translation scope must not change that behavior.

## Testing Strategy

Use test-driven development and run the smallest related test file while making
each change.

### Core stable-builder tests

- Default-off makes zero calls under an active global scope.
- Equivalent rebuilds reuse the request and do not reschedule.
- Changed entity, field, or source creates the correct new request.
- Disablement or language-generation change restores source and rejects stale
  results.

### Booking tests

- Project and institution names render source first and then English.
- Doctor title translates without submitting the doctor name.
- Doctor/consultant names, price, dates, and note input make zero calls.
- Failure preserves source.

### Order list tests

- Project and visible institution names translate; names, status, money, and
  time do not.
- Multiple cards keep translation ownership through prepend/reorder/refresh.
- Empty and duplicate IDs remain source-only.
- Default-off callers make zero calls.

### Order detail tests

- Project, institution, persisted note, the three refund fields, and individual
  log remarks translate independently.
- Date/log and name/title combinations submit only the eligible substring.
- Failure keeps source and stale results cannot replace refreshed data.
- IDs, codes, money, dates, status, names, payment values, and actions make zero
  calls.

### Payment, refund, and review tests

- Order summaries translate only project and institution names.
- Payment refreshes and the one-second expiry rebuild do not duplicate calls.
- Payment provider, state, amount, URL, and errors make zero calls.
- Refund and review input controllers retain source values and submission
  payloads exactly.

### Notification and social adjacency tests

- Notification title/content translate for `order`, `order_refund`, and
  `order_service_conversation` target types.
- Other notification types make zero calls.
- Diary order picker translates project/institution names while keeping doctor
  name and order number unchanged.

### Professional exclusion regression

Render doctor order list/detail and representative professional catalog or
management surfaces inside an active `AutoTranslationScope`. Chinese fixtures
must remain source-only and the recording repository must receive zero calls.

After focused tests pass, run `flutter analyze`, then at most one full Flutter
test run if it remains within the repository's ten-minute limit.

## Baseline and Delivery

The isolated worktree baseline ran the existing booking, order list/detail,
payment, and review page test files: 35 tests passed before implementation.

Implementation and commits remain on `codex/order-auto-translation` in the
dedicated worktree. The local `master` checkout is not modified. Integration is
performed only after focused verification and explicit user direction.
