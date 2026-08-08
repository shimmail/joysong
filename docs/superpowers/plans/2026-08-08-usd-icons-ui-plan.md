# USD Currency and Frontend Icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace business-facing frontend icons with semantic library icons and make USD the explicit default currency for persisted and displayed prices.

**Architecture:** Add a server-side USD currency default at the domain/API boundary without changing existing numeric precision. Add small currency formatters in Flutter and admin so every price display uses the API currency (defaulting to USD). Consolidate only the icon imports/usages that are business UI controls; leave brand imagery untouched.

**Tech Stack:** Kotlin/Spring, SQL migrations, Flutter/Dart Material icons, React/TypeScript Ant Design.

## Global Constraints

- Existing numeric price values remain backward-compatible; new records default to `USD`.
- User-visible prices must render with `$` and an explicit USD fallback when currency is absent.
- Do not alter logos, photos, illustrations, or generated distribution files.

### Task 1: Server Currency Contract

**Files:**
- Modify the existing price/order/project entities, request/response DTOs, and database migration files under `joysong-server/src/main/kotlin` and `joysong-server/src/main/resources`.
- Test: existing server service/controller tests plus focused currency tests.

- [ ] Add a `Currency` enum/value with `USD` as the default and expose it in price-bearing responses.
- [ ] Ensure persistence defaults and creation paths set USD when the field is omitted; preserve existing rows.
- [ ] Add tests covering omitted currency, explicit USD, and response serialization.
- [ ] Run the focused Gradle tests and compile check.

### Task 2: Flutter Currency and Icons

**Files:**
- Create or modify shared formatting/theme helpers under `joysong-flutter/lib/core`.
- Modify business presentation files under `joysong-flutter/lib/features/**/presentation` that display prices or use placeholder/non-semantic icons.
- Test: Dart analyze and widget/unit tests for formatting.

- [ ] Add a USD-aware formatter that accepts nullable currency and falls back to USD.
- [ ] Replace business UI icon literals with semantic Material icons while preserving layout and actions.
- [ ] Route all price labels through the formatter and remove RMB/yen text.
- [ ] Run `flutter analyze` and targeted tests.

### Task 3: Admin Currency and Icons

**Files:**
- Create or modify shared helpers under `joysong-admin/src`.
- Modify source pages/components under `joysong-admin/src` that display prices or use business action icons.
- Test: TypeScript build/lint.

- [ ] Add a USD formatter using API currency with USD fallback.
- [ ] Replace business action icon imports/usages with matching Ant Design icons; retain existing icon library.
- [ ] Route all monetary columns/forms through the formatter and remove RMB/yen text.
- [ ] Run `npm run build` and available lint/tests.

### Task 4: Integration Verification

- [ ] Search source trees for RMB/yen symbols and hard-coded currency labels.
- [ ] Run server tests, Flutter analyze/tests, and admin build.
- [ ] Review the diff for generated-file or asset changes and document any migration assumptions.
