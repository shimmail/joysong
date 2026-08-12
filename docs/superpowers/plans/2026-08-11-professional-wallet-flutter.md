# Professional Wallet Flutter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Document and expose actor-scoped USD wallet value objects, then add a shared Flutter wallet page with identity selection, balances, ledger pagination, and a non-networked withdrawal placeholder.

**Architecture:** Keep money ownership separated by `(owner_type, owner_id, currency)`. A backend read service converts authenticated actor scopes into presentation-ready wallet overview and wallet-specific ledger VOs. Flutter adds a dedicated wallet feature using the existing repository/controller/manual dependency-injection patterns, while the existing consumer order settlement card is only brought into contract compatibility.

**Tech Stack:** Kotlin, Spring Boot, Spring Security, Spring Data JPA, JUnit 5/MockK; Flutter/Dart, ChangeNotifier, existing ApiClient and localization helpers, flutter_test.

## Global Constraints

- All exposed wallet amounts use `USD` integer minor units; no balance calculation uses floating point.
- Wallet authorization is derived only from the authenticated actor; a request wallet ID is a selector, never an ownership grant.
- A login may access independent DOCTOR, CONSULTANT, and one-or-more INSTITUTION wallets; balances are never aggregated across owners.
- Ordinary users receive an empty wallet overview and no synthetic CUSTOMER wallet.
- Withdrawal is a local “提现功能即将开放” interaction and sends no backend request.
- Do not add Airwallex, payout-provider, bank-account, beneficiary, KYC, FX, CNY, or withdrawal persistence/API fields.
- Follow `AGENTS.md`: smallest relevant tests first, at most one full suite after focused tests, and never use a shared database.

---

### Task 1: Backend wallet read VO and authorization contract

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/service/WalletReadService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/wallet/controller/WalletController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/wallet/dto/WalletDtos.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/wallet/repository/WalletLedgerEntryRepository.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/wallet/WalletControllerTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/wallet/WalletReadServiceTest.kt`

**Interfaces:**
- Consumes: `ManagementAccessService.actor(Authentication)` and `walletScopes(actor)`; `WalletRepository`; doctor, user, and institution repositories for owner display names.
- Produces: `WalletOverviewDto(currency: String, wallets: List<WalletViewDto>)`; `WalletViewDto(walletId, ownerType, ownerId, displayName, ownerName, pendingMinor, availableMinor, frozenMinor)` where `displayName` is the stable `ownerType` semantic code; `WalletLedgerPageDto(content, page, size, totalElements, totalPages, last)`; `WalletLedgerItemDto(id, walletId, entryType, title, description, sourceType, sourceId, amountMinor, pendingAfterMinor, availableAfterMinor, frozenAfterMinor, currency, createdAt)`, with Flutter-localized copy derived from semantic codes.

- [ ] **Step 1: Write failing service/controller tests**

Add tests proving an ordinary authenticated actor returns `WalletOverviewDto("USD", emptyList())`, a three-identity actor receives three independent wallet rows with resolved names, multiple institutions remain separate, and `walletId` outside the actor scope is rejected without ledger data. Assert every serialized amount is a minor-unit integer and DTO fields contain no provider, payout, bank, beneficiary, or FX names.

```kotlin
assertEquals("USD", overview.currency)
assertEquals(listOf("DOCTOR", "CONSULTANT", "INSTITUTION"), overview.wallets.map { it.ownerType })
assertThrows<AccessDeniedException> { service.ledger(actor, foreignWalletId, 0, 20) }
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests com.joysong.server.wallet.WalletReadServiceTest --tests com.joysong.server.wallet.WalletControllerTest
```

Expected: compilation/test failure because the new VO/service and required `walletId` contract do not exist.

- [ ] **Step 3: Implement minimal read service and VO mapping**

Implement actor-scope loading, USD filtering, owner-name resolution, deterministic owner ordering, and wallet-specific ledger authorization. Compute one signed `amountMinor` from the non-zero delta for income/reversal entries; return zero for the neutral `RELEASE` bucket transfer. Expose stable public semantic codes and source references without exposing internal operation keys. Bound page to `>= 0`, size to `1..100`, and order ledger rows by `createdAt DESC, id DESC`.

```kotlin
data class WalletOverviewDto(val currency: String = "USD", val wallets: List<WalletViewDto>)

fun ledger(actor: ManagementActor, walletId: Long, page: Int, size: Int): WalletLedgerPageDto {
    val wallet = requireVisibleWallet(actor, walletId)
    require(wallet.currency == "USD")
    return mapPage(wallet, page.coerceAtLeast(0), size.coerceIn(1, 100))
}
```

- [ ] **Step 4: Update controller endpoints**

Keep `GET /api/wallets/me`. Change `GET /api/wallets/me/ledger` to require `walletId`, and delegate both endpoints to `WalletReadService` after deriving the authenticated actor.

- [ ] **Step 5: Run focused backend tests and commit**

Run the two test classes from Step 2. Expected: PASS. Commit only Task 1 files with message `feat: expose professional wallet views`.

---

### Task 2: Backend API documentation and contract examples

**Files:**
- Modify: `doc/用户端API文档.md`
- Modify: `docs/FLUTTER_API_CONTRACT.md`
- Modify: `joysong-server/openapi.yaml`
- Modify: `joysong-server/openapi.json`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/wallet/WalletControllerTest.kt`

**Interfaces:**
- Consumes: exact Task 1 paths and VO field names.
- Produces: authoritative request/response/error documentation for Flutter implementers.

- [ ] **Step 1: Verify the implemented response contract**

Inspect the Task 1 controller tests and add any missing JSON assertions for `data.currency`, `data.wallets[*].displayName`, `ownerName`, flat minor fields, and wallet-specific ledger pagination fields including `last`. This documentation task does not manufacture a RED cycle when Task 1 already proves the production contract.

- [ ] **Step 2: Run the focused contract test**

Run `WalletControllerTest`. If a newly added assertion exposes a real mismatch, fix the production mapper through RED/GREEN before documenting it. Otherwise record the existing green contract as the documentation source of truth.

- [ ] **Step 3: Write exact documentation**

Document both endpoints, authentication, ordinary-user empty result, wallet ownership rules, required `walletId`, 403/404 behavior, pagination bounds, stable order, signed `amountMinor`, USD-only semantics, and complete JSON examples. Correct the existing consumer settlement section to its current VO and 409-not-generated behavior. Mark Withdrawal as client-only and unavailable.

- [ ] **Step 4: Synchronize OpenAPI artifacts**

Update YAML and JSON with identical schemas and examples. Do not introduce a withdrawal operation. Validate that both documents contain `/api/wallets/me` and `/api/wallets/me/ledger` and no payout/bank schema.

- [ ] **Step 5: Verify and commit**

Run `WalletControllerTest`, `git diff --check`, and a textual comparison of YAML/JSON field names. Commit Task 2 files with message `docs: define professional wallet API`.

---

### Task 3: Flutter wallet domain, repository, and controller

**Files:**
- Create: `joysong-flutter/lib/features/wallet/domain/wallet_models.dart`
- Create: `joysong-flutter/lib/features/wallet/domain/wallet_repository.dart`
- Create: `joysong-flutter/lib/features/wallet/data/wallet_remote_data_source.dart`
- Create: `joysong-flutter/lib/features/wallet/data/wallet_repository_impl.dart`
- Create: `joysong-flutter/lib/features/wallet/presentation/wallet_controller.dart`
- Create: `joysong-flutter/test/features/wallet/wallet_models_test.dart`
- Create: `joysong-flutter/test/features/wallet/wallet_remote_data_source_test.dart`
- Create: `joysong-flutter/test/features/wallet/wallet_controller_test.dart`

**Interfaces:**
- Consumes: Task 1 overview and wallet-ledger JSON contracts; existing `ApiClient` and repository patterns.
- Produces: integer-minor `WalletOverview`, `WalletAccount`, `WalletLedgerEntry`, `WalletLedgerPage`, `UsdMoneyFormatter`, `WalletRepository`, and `WalletController` states used by Task 4.

- [ ] **Step 1: Write failing model/formatter tests**

Assert exact JSON parsing, preservation of 64-bit minor values, `$1,234.56`, `-$12.00`, and no use of `double` in the public money API.

```dart
expect(const UsdMoneyFormatter().format(123456), r'$1,234.56');
expect(const UsdMoneyFormatter().format(-1200), r'-$12.00');
```

- [ ] **Step 2: Verify RED and implement immutable models**

Run `flutter test test/features/wallet/wallet_models_test.dart`, verify missing-type failure, then implement minimal `fromJson` factories and integer formatting until green.

- [ ] **Step 3: Write failing remote-data-source tests**

Assert `GET /api/wallets/me` and `GET /api/wallets/me/ledger?walletId=101&page=0&size=20`, response-envelope parsing, and propagation of malformed/network errors.

- [ ] **Step 4: Verify RED and implement data/repository layer**

Follow the existing API client and repository conventions. Do not create any withdrawal method.

- [ ] **Step 5: Write failing controller tests**

Cover ordinary-user empty overview, automatic selection for one wallet, selector state for several wallets, switching resets entries and requests page zero, refresh, next-page append, stale response protection, ledger retry, and pagination failure without discarding existing entries.

- [ ] **Step 6: Verify RED and implement controller**

Use `ChangeNotifier`; maintain explicit overview and ledger loading/error flags, selected wallet ID, immutable displayed entries, and `hasNextPage`. Ignore results whose wallet ID no longer matches the active selection.

- [ ] **Step 7: Run focused Flutter tests and commit**

Run all three Task 3 test files and `flutter analyze` limited by the package's supported command. Commit with message `feat: add wallet client state`.

---

### Task 4: Flutter wallet page, Profile entry, and dependency wiring

**Files:**
- Create: `joysong-flutter/lib/features/wallet/presentation/wallet_page.dart`
- Modify: `joysong-flutter/lib/features/profile/presentation/profile_page.dart`
- Modify: `joysong-flutter/lib/features/shell/presentation/app_shell.dart`
- Modify: `joysong-flutter/lib/core/routing/app_router.dart`
- Create: `joysong-flutter/test/features/wallet/wallet_page_test.dart`
- Modify: `joysong-flutter/test/features/profile/profile_page_test.dart`

**Interfaces:**
- Consumes: Task 3 `WalletController`, wallet models, repository implementation, and USD formatter.
- Produces: Wallet navigation available to every signed-in Profile; shared role-aware wallet UI.

- [ ] **Step 1: Write failing Profile and wallet widget tests**

Cover visible Wallet menu entry for ordinary and professional users; empty identity state; single-wallet page without selector; multi-wallet selector labels; institution entries; available main balance plus pending/frozen secondary balances; ledger empty/loading/error/retry/pagination states; and Withdrawal button displaying a local coming-soon message.

- [ ] **Step 2: Verify RED**

Run the two widget test files. Expected failure: missing Wallet page, navigation callback, selector, and balance widgets.

- [ ] **Step 3: Implement the shared wallet page**

Use existing theme/localization helpers. Render a selector only when `wallets.length > 1`. Use stable keys for tests (`wallet-owner-selector`, `wallet-withdraw-button`, `wallet-retry-button`). Never aggregate balances. A withdrawal tap calls only `ScaffoldMessenger.showSnackBar`.

- [ ] **Step 4: Wire Profile and AppShell**

Add `onWallet` to `ProfilePage`, display Wallet for every signed-in user, construct the wallet repository/controller from `ApiClient`, and navigate through the existing content navigator. Dispose the controller with other shell dependencies.

- [ ] **Step 5: Run focused widget tests and commit**

Run the wallet page and profile page tests, then the closest AppShell/routing test if present. Commit with message `feat: add professional wallet page`.

---

### Task 5: Consumer settlement compatibility and final verification

**Files:**
- Modify: `joysong-flutter/lib/features/orders/domain/order_models.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/orders_controller.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/order_detail_page.dart`
- Modify: `joysong-flutter/test/features/orders/money_and_models_test.dart`
- Modify: `joysong-flutter/test/features/orders/orders_controller_test.dart`
- Modify: `joysong-flutter/test/features/orders/order_detail_page_test.dart`
- Modify: `joysong-flutter/test/features/orders/orders_remote_data_source_test.dart`

**Interfaces:**
- Consumes: existing `GET /api/orders/{id}/settlement` `ConsumerSettlementDto`.
- Produces: a compatible existing order settlement card with visible pending-generation and error states.

- [ ] **Step 1: Write failing compatibility tests**

Parse `settlementId`, `orderId`, `currency`, nested `grossTotalPaid` and `netSettled`, `state`, `settlementDueAt`, `settlementCreatedAt`, and `releasedAt`. Assert gross and net differ after refund. Assert 409 `SETTLEMENT_NOT_GENERATED` is a nonfatal generation-pending state, while malformed/network errors expose retry UI.

- [ ] **Step 2: Verify RED**

Run the four closest order tests. Expected failure: old `id/totalAmount/status/createdAt/settledAt` model cannot parse the current response and controller has no distinct support-data error state.

- [ ] **Step 3: Implement minimal compatibility changes**

Replace only the stale settlement model/card fields, use integer minor-unit formatting, and stop silently converting parsing/network errors to `null`. Do not redesign navigation or expose allocation recipients.

- [ ] **Step 4: Run focused order and wallet tests**

Run Task 5 order tests followed by all new wallet tests once. Fix only failures related to this plan.

- [ ] **Step 5: Run package verification once**

Run `flutter analyze` and, if focused tests are green, one Flutter full test suite. Run the focused backend wallet tests once; do not repeat the previously completed backend full suite unless backend production code changed after its last complete run and the repository's one-run rule permits it.

- [ ] **Step 6: Final contract and scope check**

Run `git diff --check`; verify no withdrawal endpoint/call, provider field, bank field, FX field, or CUSTOMER wallet was introduced. Confirm all four user states: ordinary, doctor, consultant, institution legal representative.

- [ ] **Step 7: Commit**

Commit Task 5 files with message `fix: align wallet and settlement client contracts`.
