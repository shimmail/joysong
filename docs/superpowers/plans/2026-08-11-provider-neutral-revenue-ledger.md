# Provider-Neutral Revenue Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build immutable four-party settlement snapshots, wallets, append-only ledger entries, idempotent refund reversals, and internal reconciliation without integrating Airwallex or any other money-movement provider.

**Architecture:** Keep `settlements` as the order-level aggregate and add normalized allocations, wallet projections, immutable ledger entries, and reconciliation issues. All money mutations run in one database transaction, use minor units, acquire wallet locks in a stable order, and rely on database uniqueness for idempotency. Provider integrations remain behind future adapters and do not participate in this plan.

**Tech Stack:** Kotlin, Spring Boot, Spring Data JPA, MySQL 8, Flyway, MockK, JUnit 5, Gradle.

## Global Constraints

- Work only in `D:\code\kotlin\joysong\.worktrees\revenue-sharing` on `codex/revenue-sharing`.
- Do not call Airwallex, Stripe, beneficiary, FX, withdrawal, or payout APIs.
- Use `Long` minor units for ledger arithmetic; never use `Double`.
- Ledger rows and settlement allocations are append-only; corrections use reversal entries.
- Lock wallets in `(currency, ownerType, ownerId)` order.
- Do not connect tests or migrations to a shared development database.
- Derive `WORKTREE_ID` from the worktree directory, use database `myapp_<WORKTREE_ID>`, and Compose project `myapp-<WORKTREE_ID>`.
- Before migrations, print the resolved database host and database name.
- Never drop or reset a database unless its name begins with `myapp_worktree_`.
- Validate migration changes against a newly created empty isolated database.
- Run the smallest related test first; after all related tests pass, run the full server suite at most once and stop it after ten minutes.
- `V16` is intentionally skipped because the main checkout already contains an uncommitted `V16__repair_doctor_project_price.sql`; use `V17` to avoid a future collision.

---

## File Structure

New production files are grouped by responsibility:

- `settlement/entity/SettlementAllocationEntity.kt`: immutable recipient and amount snapshot.
- `settlement/repository/SettlementAllocationRepository.kt`: allocation queries.
- `settlement/service/SettlementAmountAllocator.kt`: deterministic minor-unit allocation.
- `wallet/entity/WalletEntity.kt`: balance projection and optimistic version.
- `wallet/entity/WalletLedgerEntryEntity.kt`: append-only money fact.
- `wallet/repository/WalletRepository.kt`: stable pessimistic wallet locking.
- `wallet/repository/WalletLedgerEntryRepository.kt`: operation-key idempotency and ledger sums.
- `wallet/service/WalletLedgerService.kt`: atomic credit, release, and reversal operations.
- `settlement/service/SettlementReleaseService.kt`: due-settlement release orchestration.
- `settlement/service/SettlementReversalService.kt`: cumulative-target refund reversal.
- `reconciliation/entity/ReconciliationIssueEntity.kt`: durable mismatch record.
- `reconciliation/repository/ReconciliationIssueRepository.kt`: active-issue upsert lookup.
- `reconciliation/service/RevenueReconciliationService.kt`: internal consistency checks.

Existing `SettlementService` remains the settlement creation facade. `OrderScheduledTasks` remains the scheduler entry point. `RefundService` invokes reversal only after provider-confirmed refund success.

---

### Task 1: Add the revenue-ledger schema

**Files:**
- Create: `joysong-server/src/main/resources/db/migration/V21__add_revenue_ledger.sql`
- Modify: `joysong-server/src/main/resources/db/migration/B1__init_schema.sql`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/DoctorInstitutionChangeMigrationTest.kt`

**Interfaces:**
- Consumes: existing `orders`, `settlements`, `refunds`, and `users` tables.
- Produces: `settlement_allocations`, `wallets`, `wallet_ledger_entries`, `reconciliation_issues`, plus `uk_settlements_order_id`.

- [ ] **Step 1: Write the failing migration contract test**

Add a test that reads `V21__add_revenue_ledger.sql` and asserts these exact fragments exist:

```kotlin
@Test
fun `revenue ledger migration creates immutable idempotent money structures`() {
    val sql = migration("db/migration/V21__add_revenue_ledger.sql")
    assertContains(sql, "CREATE TABLE settlement_allocations")
    assertContains(sql, "CREATE TABLE wallets")
    assertContains(sql, "CREATE TABLE wallet_ledger_entries")
    assertContains(sql, "CREATE TABLE reconciliation_issues")
    assertContains(sql, "UNIQUE KEY uk_settlements_order_id (order_id)")
    assertContains(sql, "UNIQUE KEY uk_wallet_owner_currency (owner_type, owner_id, currency)")
    assertContains(sql, "UNIQUE KEY uk_wallet_ledger_operation (operation_key)")
    assertContains(sql, "CHECK (pending_minor >= 0 AND available_minor >= 0 AND frozen_minor >= 0)")
}
```

- [ ] **Step 2: Run the contract test and verify red**

Run:

```powershell
.\gradlew.bat test --tests com.joysong.server.identity.service.DoctorInstitutionChangeMigrationTest
```

Expected: FAIL because `V21__add_revenue_ledger.sql` does not exist.

- [ ] **Step 3: Create the migration and baseline equivalents**

Define:

```sql
ALTER TABLE settlements
    ADD UNIQUE KEY uk_settlements_order_id (order_id);

CREATE TABLE settlement_allocations (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    settlement_id BIGINT UNSIGNED NOT NULL,
    owner_type VARCHAR(20) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    owner_name VARCHAR(200) NOT NULL,
    rate DECIMAL(5,2) NOT NULL,
    amount_minor BIGINT NOT NULL,
    reversed_minor BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_allocation_settlement_owner (settlement_id, owner_type, owner_id),
    CONSTRAINT chk_allocation_amount CHECK (amount_minor >= 0 AND reversed_minor BETWEEN 0 AND amount_minor),
    CONSTRAINT fk_allocation_settlement FOREIGN KEY (settlement_id) REFERENCES settlements(id)
);
```

Create `wallets` with the owner/currency unique key, non-negative balance constraint, and `version BIGINT`; create `wallet_ledger_entries` with signed deltas, source references, immutable `operation_key`, and foreign keys to wallets and allocations; create `reconciliation_issues` with a generated nullable `active_key` unique for unresolved issues. Mirror the final schema in `B1__init_schema.sql`.

- [ ] **Step 4: Run the migration contract test and verify green**

Run the same focused Gradle test. Expected: PASS.

- [ ] **Step 5: Verify on a fresh isolated database**

Resolve `WORKTREE_ID=revenue_sharing`, print `DB_HOST` and `DB_NAME=myapp_worktree_revenue_sharing`, start Compose with project `myapp-revenue-sharing`, create only that fresh database, then run Flyway. Verify all four tables and all three uniqueness constraints through `information_schema`. Do not use or reset the shared development database.

- [ ] **Step 6: Commit the schema deliverable**

```powershell
git add joysong-server/src/main/resources/db/migration/V21__add_revenue_ledger.sql joysong-server/src/main/resources/db/migration/B1__init_schema.sql joysong-server/src/test/kotlin/com/joysong/server/identity/service/DoctorInstitutionChangeMigrationTest.kt
git commit -m "feat: add revenue ledger schema"
```

---

### Task 2: Implement deterministic minor-unit allocations

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/settlement/entity/SettlementAllocationEntity.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/settlement/repository/SettlementAllocationRepository.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/settlement/service/SettlementAmountAllocator.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/settlement/SettlementAmountAllocatorTest.kt`

**Interfaces:**
- Consumes: `OrderSplitRatePolicy.ResolvedRates` and a positive `Long totalMinor`.
- Produces: `SettlementAmountAllocator.allocate(totalMinor: Long, rates: ResolvedRates): AllocationAmounts`.

- [ ] **Step 1: Write failing allocation tests**

Cover exact conservation and tail assignment:

```kotlin
@Test
fun `allocations conserve every minor unit`() {
    val result = allocator.allocate(10_001, rates("40.00", "30.00", "10.00", "20.00"))
    assertEquals(10_001, result.platform + result.institution + result.consultant + result.doctor)
    assertEquals(2_001, result.doctor)
}

@Test
fun `invalid rates and non-positive totals are rejected`() {
    assertThrows<IllegalArgumentException> { allocator.allocate(0, validRates) }
    assertThrows<IllegalArgumentException> { allocator.allocate(100, rates("60", "30", "20", "-10")) }
}
```

- [ ] **Step 2: Run the allocator test and verify red**

```powershell
.\gradlew.bat test --tests com.joysong.server.settlement.SettlementAmountAllocatorTest
```

Expected: FAIL because the allocator does not exist.

- [ ] **Step 3: Implement allocation and persistence types**

Use `BigDecimal.valueOf(totalMinor).multiply(rate).divide(BigDecimal("100"), 0, HALF_UP).longValueExact()` for platform, institution, and consultant. Compute doctor as the exact remainder. Define owner types as `PLATFORM`, `INSTITUTION`, `DOCTOR`, `CONSULTANT` and allocation states as `PENDING`, `AVAILABLE`, `PARTIALLY_REVERSED`, `REVERSED`.

- [ ] **Step 4: Run allocator tests and existing settlement tests**

```powershell
.\gradlew.bat test --tests com.joysong.server.settlement.SettlementAmountAllocatorTest --tests com.joysong.server.settlement.SettlementServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/settlement joysong-server/src/test/kotlin/com/joysong/server/settlement/SettlementAmountAllocatorTest.kt
git commit -m "feat: add immutable settlement allocations"
```

---

### Task 3: Implement wallets and the append-only ledger

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/entity/WalletEntity.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/entity/WalletLedgerEntryEntity.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/repository/WalletRepository.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/repository/WalletLedgerEntryRepository.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/service/WalletLedgerService.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/wallet/WalletLedgerServiceTest.kt`

**Interfaces:**
- Consumes: sorted `WalletMutation(ownerType, ownerId, currency, allocationId, pendingDelta, availableDelta, frozenDelta, operationKey)` values.
- Produces: `WalletLedgerService.apply(mutations: List<WalletMutation>): List<WalletLedgerEntryEntity>`.

- [ ] **Step 1: Write failing ledger tests**

Test credit, release, idempotency, insufficient balance, and lock order:

```kotlin
@Test
fun `replaying an operation key does not change balances twice`() {
    service.apply(listOf(credit("doctor-1", 500, "settlement:create:s1:a1")))
    service.apply(listOf(credit("doctor-1", 500, "settlement:create:s1:a1")))
    assertEquals(500, wallet("doctor-1").pendingMinor)
    verify(exactly = 1) { ledgerRepository.save(any()) }
}

@Test
fun `wallets are locked in stable owner order`() {
    service.apply(listOf(credit("z", 1, "op-z"), credit("a", 1, "op-a")))
    verifyOrder { walletRepository.findForUpdate("DOCTOR", "a", "USD"); walletRepository.findForUpdate("DOCTOR", "z", "USD") }
}
```

- [ ] **Step 2: Run focused wallet tests and verify red**

```powershell
.\gradlew.bat test --tests com.joysong.server.wallet.WalletLedgerServiceTest
```

- [ ] **Step 3: Implement atomic ledger application**

`WalletRepository.findForUpdate` must use `@Lock(PESSIMISTIC_WRITE)`. `apply` first filters already-present operation keys, sorts remaining mutations by `(currency, ownerType, ownerId)`, locks or creates wallets, validates all projected balances remain non-negative, saves wallet projections, then appends ledger rows in the same transaction. Never expose repository `delete` through services.

- [ ] **Step 4: Run wallet tests and verify green**

Run the focused test class once. Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/wallet joysong-server/src/test/kotlin/com/joysong/server/wallet
git commit -m "feat: add wallet ledger core"
```

---

### Task 4: Create settlement snapshots and pending credits atomically

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/settlement/entity/SettlementEntity.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/settlement/repository/SettlementRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/settlement/service/SettlementService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/repository/PaymentRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/repository/RefundItemRepository.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/settlement/SettlementServiceTest.kt`

**Interfaces:**
- Consumes: successful payment minor totals, completed refund item minor totals, order participant snapshot, and resolved split rates.
- Produces: `SettlementService.saveSettlement(orderId: String): SettlementEntity` with four allocations and four pending credits.

- [ ] **Step 1: Extend failing settlement tests**

Assert that `totalAmountMinor` equals successful payments minus completed refunds, exactly four allocations are stored, four wallet credits use stable operation keys, and a duplicate call returns the existing settlement without new ledger entries. Add a case that rejects incomplete recipient snapshots and creates no money facts.

- [ ] **Step 2: Run `SettlementServiceTest` and verify red**

```powershell
.\gradlew.bat test --tests com.joysong.server.settlement.SettlementServiceTest
```

- [ ] **Step 3: Implement net-paid snapshot creation**

Add repository sum queries returning `Long` minor totals. Replace `order.price` as the settlement source with:

```kotlin
val netPaidMinor = paymentRepository.sumSucceededAmountMinor(orderId) -
    refundItemRepository.sumCompletedAmountMinor(orderId)
require(netPaidMinor > 0) { "ORDER_NET_PAID_NOT_POSITIVE" }
```

Create the settlement, flush to obtain its ID, create four immutable allocations, then call `WalletLedgerService.apply` with `pendingDelta=allocation.amountMinor`. Catch only the database duplicate-key race, reload by `orderId`, and return it; do not swallow other persistence failures.

- [ ] **Step 4: Run settlement and order tests**

```powershell
.\gradlew.bat test --tests com.joysong.server.settlement.SettlementServiceTest --tests com.joysong.server.order.OrderServiceTest --tests com.joysong.server.review.ReviewServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/settlement joysong-server/src/main/kotlin/com/joysong/server/payment/repository/PaymentRepository.kt joysong-server/src/main/kotlin/com/joysong/server/refund/repository/RefundItemRepository.kt joysong-server/src/test/kotlin/com/joysong/server/settlement/SettlementServiceTest.kt
git commit -m "feat: credit settlement snapshots to pending wallets"
```

---

### Task 5: Release due settlements idempotently

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/settlement/service/SettlementReleaseService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/settlement/repository/SettlementRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderScheduledTasks.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/settlement/SettlementReleaseServiceTest.kt`

**Interfaces:**
- Consumes: `SettlementReleaseService.release(settlementId: Long, now: LocalDateTime)`.
- Produces: pending-to-available ledger entries and final `AVAILABLE`/`SETTLED` transitions.

- [ ] **Step 1: Write failing release tests**

Cover one atomic release, repeated release, wrong order state, and rollback when any allocation mutation fails. Verify keys follow `settlement:release:<settlementId>:<allocationId>`.

- [ ] **Step 2: Run the release test and verify red**

```powershell
.\gradlew.bat test --tests com.joysong.server.settlement.SettlementReleaseServiceTest
```

- [ ] **Step 3: Implement per-settlement release transactions**

The scheduler retrieves small pages of due settlement IDs; a separate proxied service method handles each ID in its own `@Transactional` boundary. Lock the settlement, skip `AVAILABLE` or reversed records, apply equal negative pending and positive available deltas for every unreversed amount, update allocation states, then update settlement and order states and append the existing order status log.

- [ ] **Step 4: Run release, settlement, and order scheduler tests**

Run the release class plus the closest existing scheduler/order tests once. Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/settlement joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderScheduledTasks.kt joysong-server/src/test/kotlin/com/joysong/server/settlement/SettlementReleaseServiceTest.kt
git commit -m "feat: release due settlement balances"
```

---

### Task 6: Reverse wallet earnings after confirmed refunds

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/settlement/service/SettlementReversalService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundService.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/settlement/SettlementReversalServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/refund/service/RefundServiceTest.kt`

**Interfaces:**
- Consumes: `reverseCompletedRefund(refundId: String)` after provider-confirmed refund completion.
- Produces: cumulative-target reversal ledger entries and updated allocation/settlement reversal states.

- [ ] **Step 1: Write failing reversal tests**

Cover pending reversal, available reversal, two partial refunds followed by full refund, duplicate refund-item delivery, available insufficiency, and frozen funds. The cumulative target per allocation is:

```kotlin
target = allocation.amountMinor * cumulativeRefundedMinor / originalNetPaidMinor
delta = target - allocation.reversedMinor
```

Assign the final rounding remainder to the doctor allocation so total reversals equal the business refund total.

- [ ] **Step 2: Run reversal and refund tests and verify red**

```powershell
.\gradlew.bat test --tests com.joysong.server.settlement.SettlementReversalServiceTest --tests com.joysong.server.refund.service.RefundServiceTest
```

- [ ] **Step 3: Implement reversal orchestration**

Lock the settlement and allocations, calculate cumulative targets, and apply `refund:reverse:<refundItemId>:<allocationId>` mutations. For pending allocations reduce pending; for released allocations reduce available. If available is insufficient or frozen is non-zero, do not create a negative balance: reverse the coverable amount and ask the reconciliation service to record `RECOVERY_REQUIRED`. Invoke this service only from `RefundService.afterApproved`, after provider completion is durably finalized.

- [ ] **Step 4: Run reversal, refund, and settlement tests**

Expected: PASS with no duplicate entries on retries.

- [ ] **Step 5: Commit**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/settlement/service/SettlementReversalService.kt joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundService.kt joysong-server/src/test/kotlin/com/joysong/server/settlement/SettlementReversalServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/refund/service/RefundServiceTest.kt
git commit -m "feat: reverse settled earnings after refunds"
```

---

### Task 7: Add internal reconciliation and durable issues

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/reconciliation/entity/ReconciliationIssueEntity.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/reconciliation/repository/ReconciliationIssueRepository.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/reconciliation/service/RevenueReconciliationService.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/reconciliation/RevenueReconciliationServiceTest.kt`

**Interfaces:**
- Consumes: `reconcileSettlement(settlementId: Long): ReconciliationResult` and `reconcileWallet(walletId: Long): ReconciliationResult`.
- Produces: active issues for `ORDER_NET_VS_SETTLEMENT`, `SETTLEMENT_VS_ALLOCATIONS`, `ALLOCATION_VS_LEDGER`, `WALLET_VS_LEDGER`, and `RECOVERY_REQUIRED`.

- [ ] **Step 1: Write failing reconciliation tests**

For each check, test matching data, mismatch creation, repeated mismatch upsert, and automatic resolution when a later run matches. Assert the task never modifies settlement, allocation, ledger, or wallet balances.

- [ ] **Step 2: Run reconciliation tests and verify red**

```powershell
.\gradlew.bat test --tests com.joysong.server.reconciliation.RevenueReconciliationServiceTest
```

- [ ] **Step 3: Implement read-only checks and issue upsert**

Use repository aggregate queries. `recordIssue` updates `actual_minor`, `last_detected_at`, and occurrence count for an existing active key; `resolveIssue` sets status `RESOLVED` and `resolved_at`. Never call wallet or ledger mutation services from reconciliation.

- [ ] **Step 4: Run reconciliation and wallet tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/reconciliation joysong-server/src/test/kotlin/com/joysong/server/reconciliation
git commit -m "feat: add revenue ledger reconciliation"
```

---

### Task 8: Add scoped read APIs and forbid destructive money operations

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/controller/WalletController.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/dto/WalletDtos.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/controller/OrderController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminOrderController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/ManagementAccessService.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/wallet/WalletControllerTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/order/OrderControllerTest.kt`

**Interfaces:**
- Produces: `GET /api/orders/{id}/settlement`, `GET /api/wallets/me`, `GET /api/wallets/me/ledger`, and admin reconciliation reads.
- Consumes: authenticated actor context and server-side institution visibility.

- [ ] **Step 1: Write failing authorization and contract tests**

Test consumer own-order settlement summary, cross-user denial, doctor/consultant own wallet, institution-scoped wallet access, admin global read, pagination, and the absence of delete/update money endpoints. Responses expose amounts, currency, statuses, and masked owner identity only; they never expose bank account data.

- [ ] **Step 2: Run controller tests and verify red**

```powershell
.\gradlew.bat test --tests com.joysong.server.wallet.WalletControllerTest --tests com.joysong.server.order.OrderControllerTest
```

- [ ] **Step 3: Implement read-only DTOs and actor scoping**

Delegate object visibility to `ManagementAccessService`; do not trust requested owner IDs. Remove or reject hard deletion of any order that has payments, refunds, settlement, allocation, or ledger references. Keep repository deletion methods inaccessible from controllers.

- [ ] **Step 4: Run wallet, order, security, and management access tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/wallet joysong-server/src/main/kotlin/com/joysong/server/order/controller/OrderController.kt joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminOrderController.kt joysong-server/src/main/kotlin/com/joysong/server/identity/service/ManagementAccessService.kt joysong-server/src/test/kotlin/com/joysong/server/wallet joysong-server/src/test/kotlin/com/joysong/server/order/OrderControllerTest.kt
git commit -m "feat: expose scoped revenue ledger reads"
```

---

### Task 9: Integrated verification and documentation

**Files:**
- Modify: `docs/FLUTTER_API_CONTRACT.md`
- Modify: `doc/数据库文档.md`
- Test: all focused classes created or modified above.

**Interfaces:**
- Consumes: completed Tasks 1-8.
- Produces: verified backend behavior and current API/schema documentation.

- [ ] **Step 1: Run all focused revenue-ledger tests once**

```powershell
.\gradlew.bat test --tests com.joysong.server.settlement.* --tests com.joysong.server.wallet.* --tests com.joysong.server.reconciliation.* --tests com.joysong.server.refund.service.RefundServiceTest --tests com.joysong.server.order.OrderServiceTest --tests com.joysong.server.review.ReviewServiceTest
```

Expected: PASS.

- [ ] **Step 2: Re-verify migrations on a newly created empty isolated database**

Print the resolved isolated host and `myapp_worktree_revenue_sharing` name before running Flyway. Verify schema constraints and run a smoke transaction that inserts one settlement, four allocations, four wallets, and balanced ledger entries. Remove only temporary fixtures inside that isolated database; do not drop any shared database.

- [ ] **Step 3: Update docs with implemented contracts**

Document the four new tables, state meanings, minor-unit fields, read APIs, idempotency keys, and the explicit exclusion of Airwallex/withdrawal behavior. Remove any statement that `COMPLETED` alone means money was paid externally.

- [ ] **Step 4: Run the full server test suite at most once**

```powershell
.\gradlew.bat test
```

Stop after ten minutes if unfinished and report progress and the slowest observed tests. Do not rerun an already passing full suite.

- [ ] **Step 5: Inspect final diff and money invariants**

```powershell
git diff --check
git status --short
```

Confirm no Airwallex/Stripe adapter, secret, beneficiary, FX, withdrawal, payout, temporary file, or unrelated refactor entered the change set.

- [ ] **Step 6: Commit documentation and verification updates**

```powershell
git add docs/FLUTTER_API_CONTRACT.md doc/数据库文档.md
git commit -m "docs: document revenue ledger contracts"
```

## Completion Criteria

- Four new tables exist in both V17 migration and the consolidated baseline.
- Settlement creation is unique under concurrency and uses successful payment minus completed refund minor amounts.
- Exactly four immutable allocations and pending credits are created per eligible order.
- Release and reversal retries never duplicate ledger entries.
- Wallet balances never become negative and always match ledger aggregates.
- Internal reconciliation creates durable, deduplicated issues without mutating money facts.
- Actor-scoped reads prevent cross-user, cross-professional, and cross-institution access.
- No provider-specific collection, FX, beneficiary, withdrawal, or payout behavior is implemented.
- Focused tests pass; the full server suite is run no more than once after focused tests pass.
