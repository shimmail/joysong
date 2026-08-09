# Provider-Neutral Wallet and Payout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add auditable USD wallets for institutions, doctors, and consultants, then execute recipient-requested withdrawals through a provider-neutral payout layer whose first adapter is Stripe Connect Express.

**Architecture:** Settlement snapshots credit an immutable wallet ledger and a transactional balance projection. Withdrawals reserve internal funds and enqueue durable jobs; a provider-neutral orchestrator calls Stripe Transfer and connected-account Payout outside database transactions, then webhook/query reconciliation finalizes the withdrawal. Refunds reverse original allocations and create negative balances only after funds can no longer be recovered.

**Tech Stack:** Kotlin 1.9.22, Java 17, Spring Boot 3.2.2, Spring Data JPA, MySQL/Flyway, Spring `RestClient`, JUnit 5, MockK, Stripe Connect REST API.

## Global Constraints

- Launch country is `US`; launch currency is exactly `USD`.
- Store persisted money as signed minor-unit `Long`/`BIGINT`; never use floating point for financial effects.
- `customerPaidMinor = consultationFeeMinor + distributableMinor`.
- `distributableMinor = platformSplitMinor + institutionMinor + consultantMinor + doctorMinor`.
- Platform revenue is `consultationFeeMinor + platformSplitMinor`; platform revenue never enters a recipient wallet.
- Institution, doctor, and consultant each have one independent USD wallet and may bind independent payout accounts.
- Earnings remain pending for 30 days before release to available balance.
- Minimum gross wallet debit per withdrawal is `5_000` cents; standard payout only.
- Recipient bears payout fees: `transferMinor = requestedMinor - estimatedFeeMinor`.
- Ledger entries are immutable; corrections use linked reversal/adjustment entries.
- Wallet ledger and balance projection change in one database transaction with wallet locking.
- External provider calls happen only from durable `payout_jobs`, never inside the reservation transaction.
- Stripe types and IDs must not leak into wallet, settlement, or withdrawal business interfaces.
- Transfer success is not bank payout success; only final Payout success completes a withdrawal.
- All business and provider operations are idempotent.

---

### Task 1: Correct the settlement basis and persist an immutable allocation snapshot

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/settlement/entity/SettlementEntity.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/settlement/service/SettlementService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/settlement/repository/SettlementRepository.kt`
- Create: `joysong-server/src/main/resources/db/migration/V10__snapshot_settlement_allocations.sql`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/settlement/SettlementServiceTest.kt`

**Interfaces:**
- Produces: immutable settlement fields `consultationFeeMinor`, `distributableMinor`, `platformSplitMinor`, party IDs, `policyVersion`, and statuses `PENDING_HOLD`, `AVAILABLE`, `PARTIALLY_REVERSED`, `FULLY_REVERSED`.
- Consumes: `OrderEntity.consultationFeeMinor`, `remainingAmountMinor`, currency, recipient snapshots, and split policy.

- [ ] **Step 1: Write failing settlement-basis tests**

Add tests proving that a USD 100.00 order with USD 10.00 consultation fee uses USD 90.00 as distributable amount, preserves consultation fee as platform-only revenue, and makes all distributable allocations sum to 9,000 cents. Add a rounding test whose final cent is assigned to the doctor.

```kotlin
val settlement = service.saveSettlement(order.id)
assertEquals(1_000, settlement.consultationFeeMinor)
assertEquals(9_000, settlement.distributableAmountMinor)
assertEquals(
    settlement.distributableAmountMinor,
    settlement.platformAmountMinor!! + settlement.institutionAmountMinor!! +
        settlement.consultantAmountMinor!! + settlement.doctorAmountMinor!!
)
assertEquals("PENDING_HOLD", settlement.status)
```

- [ ] **Step 2: Run the targeted tests and verify RED**

Run: `./gradlew test --tests com.joysong.server.settlement.SettlementServiceTest`

Expected: FAIL because the snapshot fields/status do not exist and current code splits `order.price`.

- [ ] **Step 3: Add the V10 migration and entity snapshot fields**

Add non-provider-specific snapshot columns, recipient identity columns, policy version, and new status values. Preserve old columns during migration, backfill monetary values from orders only where the relationship is unambiguous, and do not turn historical `COMPLETED` rows into wallet credit.

- [ ] **Step 4: Implement minor-unit settlement calculation**

Calculate from `remainingAmountMinor`, not `order.price`. Calculate the first three allocations with deterministic integer rules and assign the remainder to doctor. Validate all equations before saving.

```kotlin
val doctorMinor = distributableMinor - platformMinor - institutionMinor - consultantMinor
require(doctorMinor >= 0) { "SETTLEMENT_SPLIT_INVALID" }
require(platformMinor + institutionMinor + consultantMinor + doctorMinor == distributableMinor)
```

- [ ] **Step 5: Run settlement and order tests and verify GREEN**

Run: `./gradlew test --tests com.joysong.server.settlement.* --tests com.joysong.server.order.*`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add joysong-server/src/main joysong-server/src/test/kotlin/com/joysong/server/settlement
git commit -m "fix: snapshot distributable settlement amounts"
```

---

### Task 2: Implement immutable wallet ledger and transactional projections

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/domain/WalletDomain.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/entity/WalletEntity.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/entity/WalletLedgerEntryEntity.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/repository/WalletRepository.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/repository/WalletLedgerRepository.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/service/WalletLedgerService.kt`
- Create: `joysong-server/src/main/resources/db/migration/V11__create_wallet_ledger.sql`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/wallet/WalletLedgerServiceTest.kt`

**Interfaces:**
- Produces: `creditPending`, `releasePending`, `reserveAvailable`, `restoreReservation`, `completePayout`, `applyNegative`, and immutable ledger lookup.
- Consumes: owner type/ID, source IDs, idempotency key, and positive USD minor-unit amounts.

- [ ] **Step 1: Write failing ledger tests**

Cover idempotent pending credit, release into available, release while negative, insufficient available reservation, duplicate reservation, restore, and payout completion.

```kotlin
service.creditPending(walletId, 10_000, "settlement:s1:doctor")
service.releasePending(walletId, 10_000, "settlement:s1:doctor:release")
val wallet = repository.findById(walletId).orElseThrow()
assertEquals(0, wallet.pendingMinor)
assertEquals(10_000, wallet.availableMinor)
```

- [ ] **Step 2: Run the targeted test and verify RED**

Run: `./gradlew test --tests com.joysong.server.wallet.WalletLedgerServiceTest`

Expected: FAIL because the wallet domain does not exist.

- [ ] **Step 3: Add wallet schema, entities, and locking repositories**

Create unique `(owner_type, owner_id, currency)` and unique ledger `idempotency_key`. Add `findByIdForUpdate` using `PESSIMISTIC_WRITE`. Prevent update/delete of ledger entries at the service and repository boundary.

- [ ] **Step 4: Implement ledger effects in one transaction**

Use a single internal primitive that inserts one immutable entry and changes exactly one projection partition. Multi-partition moves insert paired entries under derived idempotency keys in one transaction.

```kotlin
@Transactional
fun reserveAvailable(walletId: String, amountMinor: Long, key: String) {
    require(amountMinor > 0) { "WALLET_AMOUNT_NOT_POSITIVE" }
    if (ledger.existsByIdempotencyKey("$key:available")) return
    val wallet = wallets.findByIdForUpdate(walletId) ?: error("WALLET_NOT_FOUND")
    require(wallet.status == "ACTIVE" && wallet.negativeMinor == 0L) { "WALLET_NOT_WITHDRAWABLE" }
    require(wallet.availableMinor >= amountMinor) { "WALLET_INSUFFICIENT_AVAILABLE" }
    // persist paired AVAILABLE debit and RESERVED credit, then projection
}
```

- [ ] **Step 5: Verify concurrency and GREEN**

Add an integration test using two transactions that attempt to reserve the same available funds; exactly one succeeds. Run: `./gradlew test --tests com.joysong.server.wallet.*`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add joysong-server/src/main joysong-server/src/test/kotlin/com/joysong/server/wallet
git commit -m "feat: add immutable recipient wallet ledger"
```

---

### Task 3: Credit recipient wallets and release earnings after the hold

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/service/SettlementWalletService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/settlement/service/SettlementService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderScheduledTasks.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/wallet/SettlementWalletServiceTest.kt`
- Modify test: `joysong-server/src/test/kotlin/com/joysong/server/settlement/SettlementServiceTest.kt`

**Interfaces:**
- Produces: `creditSettlement(settlementId)` and `releaseDueSettlements(now)`.
- Consumes: Task 1 snapshot and Task 2 ledger methods.

- [ ] **Step 1: Write failing integration tests**

Assert that settlement creation credits three independent wallets in `pending`, excludes platform allocations, is idempotent, and releases after exactly the stored hold deadline. Assert that a recipient with negative balance receives only the remaining released amount as available.

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew test --tests com.joysong.server.wallet.SettlementWalletServiceTest`

Expected: FAIL because settlement-to-wallet orchestration is missing.

- [ ] **Step 3: Implement settlement wallet credit**

Resolve/create wallets by snapshot owner, emit keys `settlement:{id}:{party}:pending`, and never create a platform wallet entry.

- [ ] **Step 4: Replace status-only due processing with ledger release**

For each due settlement, lock it, release each unreversed recipient allocation through deterministic keys, then set status `AVAILABLE`. An exception leaves both settlement and all wallet effects rolled back.

- [ ] **Step 5: Run wallet, settlement, order, and review tests**

Run: `./gradlew test --tests com.joysong.server.wallet.* --tests com.joysong.server.settlement.* --tests com.joysong.server.order.* --tests com.joysong.server.review.*`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add joysong-server/src/main joysong-server/src/test
git commit -m "feat: release settlements into recipient wallets"
```

---

### Task 4: Add provider-neutral payout accounts and Stripe Express onboarding

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/domain/PayoutDomain.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/provider/PayoutGateway.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/provider/StripeConnectPayoutGateway.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/entity/PayoutAccountEntity.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/repository/PayoutAccountRepository.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/service/PayoutAccountService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/controller/PayoutAccountController.kt`
- Create: `joysong-server/src/main/resources/db/migration/V12__create_payout_accounts.sql`
- Modify: `joysong-server/src/main/resources/application.yml`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/payout/provider/StripeConnectPayoutGatewayTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/payout/service/PayoutAccountServiceTest.kt`

**Interfaces:**
- Produces: the `PayoutGateway` contract from the design and `PayoutGatewayRegistry`; payout-account create/onboarding/status APIs.
- Consumes: authenticated owner context and wallet ownership.

- [ ] **Step 1: Write failing provider contract and account-service tests**

Assert US/USD Express account parameters, safe onboarding URL validation, normalization of requirements and payout capability, one default account, owner authorization, and registry failure for unavailable providers.

- [ ] **Step 2: Run payout account tests and verify RED**

Run: `./gradlew test --tests com.joysong.server.payout.*`

Expected: FAIL because the payout domain does not exist.

- [ ] **Step 3: Implement provider-neutral contracts and persistence**

Define provider-neutral request/result classes for recipient, onboarding, transfer, payout, reversal, and webhook. Persist `provider`, `externalAccountId`, normalized capabilities, sanitized raw status, default flag, and change timestamp outside wallet/user entities.

- [ ] **Step 4: Implement Stripe Express account and onboarding adapter**

Use `RestClient`, platform idempotency keys, `country=US`, Express controller/account configuration, backend-owned refresh/return URLs, and strict HTTPS validation. Do not accept provider account IDs from the client.

- [ ] **Step 5: Implement authenticated owner endpoints and status sync**

Expose create/list/onboarding/status operations. An account is eligible only when normalized requirements are clear, transfers are enabled, payouts are enabled, and a bank destination exists.

- [ ] **Step 6: Run payout and security tests and verify GREEN**

Run: `./gradlew test --tests com.joysong.server.payout.* --tests com.joysong.server.config.*`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add joysong-server/src/main joysong-server/src/test/kotlin/com/joysong/server/payout
git commit -m "feat: add provider-neutral Express payout accounts"
```

---

### Task 5: Reserve withdrawals and enqueue durable payout work

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/entity/WithdrawalEntity.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/entity/PayoutAttemptEntity.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/entity/PayoutJobEntity.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/repository/WithdrawalRepository.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/repository/PayoutAttemptRepository.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/repository/PayoutJobRepository.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/service/WithdrawalService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/service/WithdrawalRiskService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/controller/WithdrawalController.kt`
- Create: `joysong-server/src/main/resources/db/migration/V13__create_withdrawals_and_jobs.sql`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/payout/service/WithdrawalServiceTest.kt`

**Interfaces:**
- Produces: `requestWithdrawal`, `approve`, `reject`, `cancel`, list/detail APIs, and durable `TRANSFER_CREATE` jobs.
- Consumes: wallet reservation, payout-account eligibility, versioned fee/limit policy, and risk decision.

- [ ] **Step 1: Write failing withdrawal tests**

Cover the 5,000-cent minimum, gross/fee/net formula, insufficient funds, frozen/negative wallet, ineligible account, duplicate client idempotency key, automatic approval, first-withdrawal review, rejection restore, pre-submission cancellation, and concurrent requests.

```kotlin
val result = service.requestWithdrawal(owner, wallet.id, 10_000, account.id, "client-key")
assertEquals(10_000, result.requestedAmountMinor)
assertEquals(estimatedFee, result.feeMinor)
assertEquals(10_000 - estimatedFee, result.transferAmountMinor)
```

- [ ] **Step 2: Run withdrawal tests and verify RED**

Run: `./gradlew test --tests com.joysong.server.payout.service.WithdrawalServiceTest`

Expected: FAIL because withdrawals/outbox are missing.

- [ ] **Step 3: Add withdrawal, attempt, and job schema**

Add unique wallet-scoped client key, attempt numbers, provider idempotency keys, and one active job business key. Persist policy/risk snapshots and normalized states.

- [ ] **Step 4: Implement transactional reservation and outbox creation**

Lock wallet, validate policy, reserve gross amount, create withdrawal, and enqueue job in one transaction. Review-required requests reserve funds but enqueue only after approval. Rejection/cancellation restores with an idempotent ledger move.

- [ ] **Step 5: Add recipient and reviewer authorization**

Recipients see only their wallet. Finance/risk review endpoints use dedicated authorities and always persist reviewer/reason audit data.

- [ ] **Step 6: Run wallet/payout tests and verify GREEN**

Run: `./gradlew test --tests com.joysong.server.wallet.* --tests com.joysong.server.payout.*`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add joysong-server/src/main joysong-server/src/test/kotlin/com/joysong/server/payout
git commit -m "feat: reserve wallet withdrawals with durable jobs"
```

---

### Task 6: Execute Stripe Transfer and Payout with webhook/query recovery

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/service/PayoutJobWorker.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/service/PayoutPersistenceService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/entity/PayoutEventEntity.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/repository/PayoutEventRepository.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/controller/PayoutWebhookController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payout/provider/StripeConnectPayoutGateway.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt`
- Create: `joysong-server/src/main/resources/db/migration/V14__create_payout_events.sql`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/payout/service/PayoutOrchestrationTest.kt`
- Modify test: `joysong-server/src/test/kotlin/com/joysong/server/payout/provider/StripeConnectPayoutGatewayTest.kt`

**Interfaces:**
- Produces: leased job execution, `create/queryTransfer`, `create/queryPayout`, verified payout events, and legal persisted state transitions.
- Consumes: approved withdrawal jobs and Task 4 gateway.

- [ ] **Step 1: Write failing orchestration tests**

Cover transfer success followed by payout success, transfer timeout/query recovery, explicit retryable failure, final failure restore, transfer success/payout failure retaining reservation, duplicate/late/out-of-order webhook, job lease recovery, and stable provider idempotency keys.

- [ ] **Step 2: Run orchestration tests and verify RED**

Run: `./gradlew test --tests com.joysong.server.payout.service.PayoutOrchestrationTest`

Expected: FAIL because execution is missing.

- [ ] **Step 3: Implement Stripe transfer/payout/reversal endpoints**

Use connected-account headers where required, internal correlation metadata, transfer grouping, safe Stripe ID validation, and separate external transfer/payout IDs. Normalize raw provider states at the adapter boundary.

- [ ] **Step 4: Implement leased durable worker and short persistence transactions**

Claim jobs with a lease, commit, call provider, then persist through `PayoutPersistenceService`. `OUTCOME_UNKNOWN` schedules query jobs; it never creates a new external object blindly.

- [ ] **Step 5: Implement signed idempotent payout webhooks**

Permit only the payout webhook route in `SecurityConfig`, verify Stripe signature before saving, unique provider event IDs, and reject state regression.

- [ ] **Step 6: Finalize wallet accounting only on payout success**

Consume gross reserved amount, add actual bank payout to `paidOutMinor`, record final fee separately, and credit/debit fee differences according to the design.

- [ ] **Step 7: Run all payout/payment tests and verify GREEN**

Run: `./gradlew test --tests com.joysong.server.payout.* --tests com.joysong.server.payment.*`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add joysong-server/src/main joysong-server/src/test/kotlin/com/joysong/server/payout
git commit -m "feat: execute Stripe transfers and bank payouts"
```

---

### Task 7: Integrate refund/dispute reversal and negative balances

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/service/WalletRefundService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundWorkflowPersistenceService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payout/service/PayoutPersistenceService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payout/provider/StripeConnectPayoutGateway.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/wallet/WalletRefundServiceTest.kt`
- Modify test: `joysong-server/src/test/kotlin/com/joysong/server/refund/RefundExecutionServiceTest.kt`

**Interfaces:**
- Produces: idempotent full/partial wallet reversal and provider-recovery jobs.
- Consumes: successful business refund, original settlement snapshot, wallet ledger, newest-first unsubmitted withdrawal cancellation, and gateway reversal capability.

- [ ] **Step 1: Write failing reversal tests**

Cover pending reversal, available reversal, cancellation of whole newest unsubmitted withdrawals, partial refund rounding, transfer reversal success, partial reversal, post-payout negative balance, retry idempotency, and future earnings offset.

- [ ] **Step 2: Run refund/wallet tests and verify RED**

Run: `./gradlew test --tests com.joysong.server.wallet.WalletRefundServiceTest --tests com.joysong.server.refund.*`

Expected: FAIL because refund completion does not affect wallets.

- [ ] **Step 3: Implement deterministic refund allocation**

Use the original settlement snapshot and cumulative refunded amount. Assign the final rounding remainder so party reversals sum exactly to the business refund amount.

- [ ] **Step 4: Implement balance-pool recovery order**

Lock wallet; consume pending then available; cancel whole newest eligible withdrawals if needed; enqueue provider reversal after external transfer; create negative balance only for final unrecovered recipient funds. Link every effect to the refund idempotency key.

- [ ] **Step 5: Integrate after confirmed external customer refund**

Invoke wallet reversal from the successful refund persistence boundary. A retry resumes incomplete provider recovery and cannot double-debit wallets.

- [ ] **Step 6: Run settlement/wallet/payout/refund suites and verify GREEN**

Run: `./gradlew test --tests com.joysong.server.settlement.* --tests com.joysong.server.wallet.* --tests com.joysong.server.payout.* --tests com.joysong.server.refund.*`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add joysong-server/src/main joysong-server/src/test
git commit -m "feat: reverse wallet earnings on refunds"
```

---

### Task 8: Add wallet statements, operations controls, and reconciliation

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/controller/WalletController.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/wallet/dto/WalletDtos.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/entity/ReconciliationIssueEntity.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/repository/ReconciliationIssueRepository.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/service/PayoutReconciliationService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminPayoutController.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payout/service/PayoutScheduledTasks.kt`
- Create: `joysong-server/src/main/resources/db/migration/V15__create_reconciliation_issues.sql`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/wallet/WalletControllerTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/payout/PayoutReconciliationServiceTest.kt`

**Interfaces:**
- Produces: wallet summary/ledger pagination, withdrawal history, frozen-wallet controls, reconciliation issues, retry/resolve actions, and scheduled daily reconciliation.
- Consumes: all previous task projections, events, attempts, and provider queries.

- [ ] **Step 1: Write failing API authorization and reconciliation tests**

Assert owner isolation, stable `(createdAt,id)` ledger pagination, no external account IDs in responses, freeze/unfreeze audit requirement, projection mismatch detection, one-cent provider mismatch detection, and no automatic balance mutation.

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew test --tests com.joysong.server.wallet.WalletControllerTest --tests com.joysong.server.payout.PayoutReconciliationServiceTest`

Expected: FAIL because APIs and reconciliation are missing.

- [ ] **Step 3: Implement recipient wallet and statement APIs**

Return pending, available, reserved, negative, paid-out, and sanitized immutable statements. Resolve the active professional owner from server authentication rather than client-supplied owner IDs.

- [ ] **Step 4: Implement reconciliation without auto-correction**

Compare projection to ledger sums, wallet liability totals, and payout attempts to provider objects. Persist issues with expected/actual values and freeze affected execution at high severity.

- [ ] **Step 5: Implement audited operations endpoints and an independent payout scheduled job**

Require finance/risk authorities, reason text, and audit actor for freeze/unfreeze, retry, and issue resolution. Schedule reconciliation independently from settlement release.

- [ ] **Step 6: Run the complete server test suite**

Run: `./gradlew test`

Expected: PASS with zero failures.

- [ ] **Step 7: Run migration/build verification**

Run: `./gradlew clean build`

Expected: BUILD SUCCESSFUL. Start the application against a disposable MySQL schema and confirm Flyway applies from an empty database and upgrades a representative pre-wallet schema.

- [ ] **Step 8: Commit**

```bash
git add joysong-server/src/main joysong-server/src/test
git commit -m "feat: add wallet operations and payout reconciliation"
```

---

## Final Verification

- [ ] Run `./gradlew clean test` from `joysong-server` and record the test count and result.
- [ ] Verify every new financial mutation has a test that was observed failing before implementation.
- [ ] Verify duplicate API requests and webhooks cannot duplicate ledger or provider effects.
- [ ] Verify no Stripe identifier exists in wallet, settlement, or withdrawal domain interfaces.
- [ ] Verify no historical `COMPLETED` settlement was automatically credited during migration.
- [ ] Exercise Stripe test-mode Express onboarding, Transfer, Payout, payout failure, and reversal.
- [ ] Run a one-cent reconciliation mismatch drill and confirm it creates an issue without changing a balance.
- [ ] Run a final whole-branch code review before integration.
