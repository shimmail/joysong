# Consumer AI Auto-Translation Design

## Summary

Joysong serves foreign consumers while institution legal representatives, doctors, and consultants operate the service in Chinese. The consumer-facing Flutter experience therefore needs optional automatic Chinese-to-English translation for database-backed content, while Chinese professional and operational workflows must remain unchanged.

When the App language is English, Settings exposes an **AI automatic translation** switch. The switch is off by default. After the user enables it, eligible pages render the database source text immediately, request translations asynchronously, and replace each successful Chinese field with English. Failed translations continue to display the Chinese source text.

The existing manual translation actions for diaries, comments, replies, and direct messages remain available.

## Goals

- Give English-language consumers automatic English rendering of eligible Chinese database content after explicit opt-in.
- Preserve fast page loading by rendering source content before translations complete.
- Reuse the authenticated `POST /api/translations` endpoint and its existing translation provider configuration.
- Centralize eligibility, caching, concurrency, and stale-result handling in a reusable Flutter translation module.
- Preserve existing Chinese professional, identity, service-management, and administration workflows.
- Retain Chinese source content as the fallback for every field.

## Non-Goals

- Adding translated columns or translation tables to the database.
- Changing backend translation API contracts or adding a batch endpoint in this phase.
- Translating the admin application.
- Translating professional identity application, verification, review, or management workflows.
- Translating institution project management, doctor service management, or consultant management workflows.
- Translating person names, phone numbers, prices, dates, identifiers, or certificate numbers.
- Removing or redesigning existing manual translation controls.

## User Setting

`SettingsPreferences` gains a persisted `aiTranslationEnabled` boolean with a default of `false`. Existing stored settings that lack the field deserialize to `false`.

The Settings page shows the switch only when `AppLanguage.english` is active. Its subtitle explains that eligible page text is sent to an AI translation service. Changing to Chinese makes automatic translation inactive but retains the stored preference. Returning to English reactivates it without requiring the user to opt in again.

Saving follows the existing serialized settings-operation pattern. If persistence fails, the visible preference remains unchanged and Settings shows one localized save failure message.

## Architecture

### Core translation module

Create a reusable `core/translation` module with these boundaries:

- `TranslationRepository`: translates one text value to a target BCP 47 language and returns the existing translation response fields.
- `ApiTranslationRepository`: calls the existing authenticated `POST /api/translations` endpoint.
- `AutoTranslationController`: owns eligibility checks, request scheduling, in-flight deduplication, bounded caching, and stale-result protection.
- `AutoTranslationScope`: exposes the shared controller and current enablement to consumer pages.
- Reusable translated-value/text builders: render source text immediately, subscribe to the shared result, and replace only their own field when a valid translation arrives.

The social repository continues to expose manual translation behavior during this phase, but its HTTP implementation delegates to the shared translation repository instead of duplicating endpoint logic.

### Activation conditions

An automatic request is eligible only when all of these conditions hold:

1. App language is English.
2. `aiTranslationEnabled` is true.
3. The user has an authenticated API client capable of calling `/api/translations`.
4. The source value is non-empty and contains at least one CJK Unified Ideograph.
5. The field is included in the consumer-facing allowlist.

Empty values and values containing only Latin text, numbers, punctuation, URLs, dates, prices, or telephone numbers do not create requests.

### Request identity and cache

Each request identity contains:

- target language;
- content type;
- entity ID;
- field name;
- complete source text.

Including the source text makes edited content a new cache entry. Including the target language and field prevents results from leaking across language changes or distinct fields on one entity.

The controller keeps:

- an in-flight future map so identical concurrent requests share one HTTP call;
- a bounded in-memory least-recently-used successful-result cache;
- a generation counter incremented when language or enablement changes.

Failures are not cached. At most three provider-bound requests run concurrently. Queued work checks the current generation and activation conditions before starting. Completed work checks them again before publishing a result.

## Page Coverage

### Home

Automatically translate eligible dynamic fields in:

- banners: title and subtitle;
- popular projects: project name and category;
- recommended institution projects: institution name, project name, slogan, and category;
- expert articles: title, summary, and category;
- featured diaries: title, summary/content excerpt, and project name;
- institution cards: institution name, address, and description;
- doctor cards: professional title, specialties, and institution name.

Doctor names and article/diary author names remain unchanged.

### Discover and consumer lists

Automatically translate eligible titles, summaries, descriptions, categories, addresses, specialties, and tags for article, project, institution, doctor, diary, and review cards. Person names and numeric metadata remain unchanged.

### Consumer detail pages

- Articles: title, category, summary, plain-text body, and supported rich-text body.
- Projects: name, slogan, description, detailed content, category, tags, institution name, and address.
- Institutions: name, address, description, public qualification description, and consumer-facing project cards.
- Doctors: professional title, specialties, biography, public credential description, related project text, diary text, and review text. Doctor names and certificate identifiers remain unchanged.
- Diaries, comments, replies, and direct messages: automatically invoke the existing translation capability when enabled while retaining all manual translation controls.
- Reviews: review body, related project name, and tags. Reviewer names remain unchanged.

### Explicit exclusions

No automatic translation integration is added to:

- `features/identity/**` professional identity, relationship, application, verification, or management pages;
- `features/professional_management/**` institution project and doctor service management;
- institution consultant selection or consultant-management flows;
- admin pages;
- forms used by Chinese institutions, doctors, or consultants to create or maintain operational content.

Consumer-facing institution and doctor discovery/detail pages are included; only their Chinese operator workflows are excluded.

## Rendering and Data Flow

1. A consumer page loads its normal database-backed DTOs.
2. The page renders the source strings without waiting for translation.
3. Each allowed field registers an automatic translation request with the shared controller.
4. The controller skips ineligible text, returns cached translations immediately, or queues a deduplicated request.
5. Successful results notify only consumers of that request key and replace the source field with English.
6. Failures leave the source field unchanged.
7. Disabling the switch or switching to Chinese invalidates the active generation and makes all builders render source values again.

Page controllers must not wait for translation before entering their ready state. Refreshing content may retry prior failures, while unchanged successful content reuses the cache.

## Rich Text

Article and project HTML use the existing `article_html` and `project_html` content types. A translated HTML result is accepted only if it passes structural validation sufficient for the current renderer. If required tags or parse structure are invalid, the result is discarded and the source HTML remains visible.

Rich-text translation tests cover preserved paragraph, list, link, and emphasis structures. Automatic translation must not inject executable markup or broaden the renderer's existing HTML capabilities.

## Error and Lifecycle Behavior

- Automatic failures do not show per-field snackbars or block other translations.
- Unauthenticated or unavailable translation services silently fall back to source text.
- A failed item does not cancel unrelated queued items.
- Disposed widgets do not receive UI updates.
- Results from an old language, disabled generation, or changed source value are ignored.
- Existing manual translation errors retain their current explicit feedback.

## Testing Strategy

Follow test-driven development and run the smallest relevant tests first.

### Settings tests

- Default and legacy-deserialized settings keep automatic translation off.
- The switch is visible in English and hidden in Chinese.
- Enabling and disabling persists correctly.
- A save failure keeps the prior value and displays localized feedback.

### Core translation tests

- Chinese eligibility and non-Chinese skipping.
- Identical in-flight requests produce one repository call.
- Cache isolation by language, type, entity, field, and source text.
- At most three requests execute concurrently.
- Failures return source text and remain retryable.
- Language/enablement generation changes discard stale results.
- Bounded cache eviction preserves correct behavior.

### Page tests

- English plus enabled progressively replaces eligible fields on Home, including doctor cards.
- English plus disabled and all Chinese-language cases make zero automatic calls.
- Representative article, project, institution, doctor, diary, and review detail fields translate.
- One failed field does not prevent other fields or page rendering.
- Person names and excluded data types do not translate.
- Identity, professional-management, institution-project-management, doctor-service-management, and consultant-management screens make zero automatic calls.
- Rich-text structural failure preserves source content.

After focused tests pass, run `flutter analyze`, then at most one full Flutter test run if it remains within the repository's ten-minute limit.

## Rollout and Follow-Up

This phase keeps translation transient and device-local. The switch defaults off, so rollout requires explicit consumer consent and can be disabled without changing source data.

A later phase may add a backend batch endpoint, shared Redis cache, provider single-flight protection, terminology controls, and reviewed human translations for medical risk or compliance content. Those changes are intentionally outside this implementation.
