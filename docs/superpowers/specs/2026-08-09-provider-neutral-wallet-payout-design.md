# Provider-Neutral Wallet and Payout Design

## 1. Purpose

Build a production-grade USD settlement and withdrawal system for the United States. Stripe Connect Express is the first payout provider, but the wallet, ledger, withdrawal, refund, and reconciliation domains must remain provider-neutral so another payout provider can be added without rewriting financial business rules.

This design replaces the current meaning of "settled." Today the application calculates a split, stores it in `settlements`, waits 30 days, and marks the record `COMPLETED`; it does not create a wallet balance or move money to a recipient. The new system turns an eligible settlement into an auditable payable balance and moves money only after the recipient requests a withdrawal.

## 2. Confirmed Product Decisions

- Launch country and currency: United States and USD only.
- Stripe account model: Stripe Connect Express.
- Recipients: institutions, doctors, and consultants each have an independent wallet, payout account, and withdrawal history.
- Platform share: retained as platform revenue and never credited to a recipient wallet.
- Funding model: the platform Stripe account collects the customer charge.
- Release policy: recipient earnings remain pending for 30 days, then become withdrawable if there is no refund, dispute, or hold.
- Withdrawal policy: recipient-initiated withdrawal with a minimum of USD 50.00.
- Payout speed: standard payout only for the first release; Instant Payout is out of scope.
- Fees: payout fees are borne by the recipient and recorded as separate ledger entries.
- Risk policy: normal requests may be approved automatically; first withdrawals, large withdrawals, recent payout-account changes, and other risk signals require manual review.
- Negative balance policy: post-payout refunds create a negative wallet balance, block withdrawals, and are offset by future earnings.
- Provider strategy: Stripe is the first adapter behind a provider-neutral `PayoutGateway`.

## 3. Scope

### 3.1 In scope

- Correct settlement basis and immutable split snapshots.
- Pending and withdrawable wallet balances for three recipient types.
- Immutable wallet ledger and transactional balance projections.
- Stripe Express onboarding and account-status synchronization.
- Withdrawal application, reservation, risk review, execution, failure recovery, and history.
- Stripe Transfer followed by a standard Payout to the connected account's US bank account.
- Refund and dispute holds, reversals, and negative balances.
- Provider-neutral payout interfaces and normalized statuses.
- Idempotent webhook processing, scheduled recovery, reconciliation, audit logs, and operational controls.

### 3.2 Out of scope for the first release

- Countries other than the United States.
- Currencies other than USD.
- Instant Payout.
- Customer wallet or stored-value spending.
- Recipient-to-recipient transfers.
- Multiple wallets for the same owner and currency.
- Automatic provider selection or provider failover after funds have moved externally.
- Reusing current split policy after an order has captured its split snapshot.

## 4. Financial Invariants

1. All persisted money is stored as USD minor units in signed `Long`/`BIGINT` values. Floating-point types are forbidden.
2. `customer_paid_minor = consultation_fee_minor + distributable_minor`, and `distributable_minor = platform_split_minor + institution_minor + consultant_minor + doctor_minor`. Platform total revenue is `consultation_fee_minor + platform_split_minor`.
3. Refunds use the original settlement snapshot, never the current split configuration.
4. Ledger entries are immutable. Corrections are new reversing or adjusting entries that reference the original entry.
5. The wallet balance projection and its ledger entries are updated in the same database transaction.
6. A withdrawal can reserve only currently withdrawable funds and cannot make the available partition negative.
7. The same business idempotency key cannot create two financial effects.
8. Transfer acceptance is not bank payout success. A withdrawal becomes `SUCCEEDED` only after the corresponding payout succeeds.
9. Manual operations never mutate a balance directly; they create reviewed ledger adjustments.
10. Provider webhook order and duplication cannot move an internal state backward or repeat a financial effect.

## 5. Settlement Basis and Split Snapshot

Before enabling wallets, the business must correct the current split basis. `SettlementService` currently uses `order.price`, while the order separately stores consultation fee and remaining amount. The distributable amount must be explicit and persisted in minor units. Under the current product rule, the platform retains the consultation fee and the remaining amount is the amount split between configured parties. If the business later changes this rule, the change applies only to newly captured snapshots.

Each settlement stores:

- total customer-paid amount;
- platform-only consultation fee and platform split as distinct fields;
- distributable amount;
- platform, institution, consultant, and doctor allocations;
- each party's rate and recipient identity snapshot;
- currency and policy version;
- the source payment IDs included in the settlement.

Rounding is deterministic: calculate allocations in minor units and assign any final remainder to the doctor allocation, matching the existing last-party behavior. Institution, consultant, doctor, and platform-split allocations must sum exactly to the distributable amount. The platform-only consultation fee is outside that allocation equation.

Settlement status becomes:

- `PENDING_HOLD`: allocation captured but still within the 30-day hold.
- `AVAILABLE`: all unreversed recipient allocations have been credited to withdrawable balances.
- `PARTIALLY_REVERSED`: part of the original allocation has been reversed.
- `FULLY_REVERSED`: the entire recipient allocation has been reversed.

Settlement status describes eligibility and reversals, not external payout state.

## 6. Domain Architecture

### 6.1 Settlement domain

`SettlementService` captures the immutable allocation and schedules its release. It does not know about Stripe and does not execute withdrawals.

### 6.2 Wallet domain

`WalletLedgerService` records pending earnings, release, reservation, fees, payout completion, refund reversal, negative balance recovery, and manual adjustment. `WalletBalanceService` maintains query-efficient balance partitions while treating the ledger as the source of truth.

Each recipient has one USD wallet with these projections:

- `pending_minor`: earnings inside the hold period;
- `available_minor`: amount that can be reserved for withdrawal;
- `reserved_minor`: amount committed to an active withdrawal;
- `negative_minor`: post-payout loss that future earnings must offset;
- `paid_out_minor`: cumulative completed withdrawals for reporting.

New released earnings first reduce `negative_minor`; only the remainder increases `available_minor`.

### 6.3 Payout-account domain

`PayoutAccountService` owns recipient onboarding state and provider bindings. A recipient may have multiple provider accounts in the future, but only one default active payout account per wallet at a time.

The internal model stores provider-neutral capability and requirement state plus the provider's raw status snapshot. Stripe identifiers never appear on wallet or user entities.

### 6.4 Withdrawal domain

`WithdrawalService` validates a request, calculates a fee snapshot, reserves funds, evaluates risk, and owns the normalized withdrawal state machine. It delegates external execution to a payout orchestrator and never invokes a provider SDK directly.

### 6.5 Provider adapter domain

`PayoutGateway` exposes normalized recipient onboarding, transfer, payout, reversal, query, and webhook operations. `StripeConnectPayoutGateway` is the first implementation. Provider requests and responses are converted at the adapter boundary.

External calls are never executed as an untracked continuation of a database transaction. Reserving funds commits a persistent payout job in the same transaction. A worker claims that job, creates or updates a `payout_attempt`, invokes the provider with a stable idempotency key, and persists the result. A crashed worker can safely resume from the stored attempt and query an ambiguous provider result before retrying.

### 6.6 Reconciliation and operations

`PayoutReconciliationService` compares ledger projections, wallet liabilities, payout attempts, and provider objects. Differences create operational issues and never silently alter balances.

## 7. Provider-Neutral Interface

```kotlin
interface PayoutGateway {
    val provider: PayoutProvider

    fun createRecipient(request: CreateRecipientRequest): RecipientResult
    fun createOnboardingSession(request: CreateOnboardingSessionRequest): OnboardingSessionResult
    fun getRecipientStatus(externalRecipientId: String): RecipientStatusResult
    fun createTransfer(request: CreateTransferRequest): TransferResult
    fun queryTransfer(externalTransferId: String): TransferResult
    fun createPayout(request: CreatePayoutRequest): PayoutResult
    fun queryPayout(externalPayoutId: String): PayoutResult
    fun reverseTransfer(request: ReverseTransferRequest): TransferResult
    fun verifyWebhook(payload: String, headers: Map<String, String>): VerifiedPayoutEvent
}
```

Provider-independent request objects contain internal IDs, minor-unit amounts, currency, idempotency keys, and external recipient IDs. They do not expose Stripe SDK types. Unsupported provider operations return a typed capability error rather than a fabricated success.

Normalized external execution statuses are:

- `PENDING`
- `PROCESSING`
- `SUCCEEDED`
- `FAILED_RETRYABLE`
- `FAILED_FINAL`
- `REVERSED`
- `CANCELLED`
- `OUTCOME_UNKNOWN`

The raw provider status is stored separately for support and reconciliation.

## 8. Data Model

### 8.1 `wallets`

- `id`
- `owner_type`: `INSTITUTION`, `DOCTOR`, or `CONSULTANT`
- `owner_id`
- `currency`: constrained to `USD` for the first release
- balance projections listed in section 6.2
- `status`: `ACTIVE`, `FROZEN`, or `CLOSED`
- optimistic `version`
- timestamps
- unique key on `(owner_type, owner_id, currency)`

### 8.2 `wallet_ledger_entries`

- `id`
- `wallet_id`
- `entry_type`
- `partition`: `PENDING`, `AVAILABLE`, `RESERVED`, `NEGATIVE`, or `PAID_OUT`
- signed `amount_minor`
- `currency`
- unique `idempotency_key`
- `source_type` and `source_id`
- optional `settlement_id`, `withdrawal_id`, and `reversal_of_entry_id`
- immutable description, actor, metadata snapshot, and timestamp

An index on `(wallet_id, created_at, id)` supports stable statement pagination.

### 8.3 `payout_accounts`

- wallet owner reference
- `provider`
- provider-neutral onboarding and account status
- encrypted or access-controlled `external_account_id`
- `transfers_enabled`, `payouts_enabled`, requirement summary, and raw provider-status snapshot
- default flag, account-change timestamp, restriction reason, and timestamps
- unique key on `(provider, external_account_id)`

### 8.4 `withdrawals`

- wallet and payout-account references
- requested amount, fee, transfer amount, and expected payout amount in minor units
- currency and fee-policy snapshot
- normalized status
- unique client idempotency key scoped to the wallet
- risk decision, reviewer, review reason, and timestamps
- failure summary and cancellation reason

Withdrawal states are:

- `PENDING_REVIEW`
- `FUNDS_RESERVED`
- `SUBMITTED`
- `PROCESSING`
- `SUCCEEDED`
- `FAILED_RETRYABLE`
- `FAILED_FINAL`
- `REVERSED`
- `CANCELLED`

### 8.5 `payout_attempts`

- withdrawal and provider
- attempt number and unique provider idempotency key
- external transfer ID and external payout ID
- normalized and raw provider statuses
- request/response summary, failure code, retry schedule, and timestamps

A withdrawal can have multiple attempts, but only one attempt may be externally active. A provider cannot be changed after a transfer succeeds.

### 8.6 `payout_events` and `reconciliation_issues`

`payout_events` stores unique provider event IDs, verified normalized payloads, processing status, and errors. `reconciliation_issues` stores expected and actual amounts/statuses, severity, affected objects, evidence, resolution action, reviewers, and timestamps.

### 8.7 `payout_jobs`

`payout_jobs` is the transactional outbox for onboarding synchronization, transfer creation/query, payout creation/query, reversal, and reconciliation work. It stores job type, aggregate ID, attempt count, next execution time, lease owner/time, last error, and terminal status. A unique business key prevents two active jobs for the same external action.

## 9. Wallet Lifecycle

1. Order completion captures a settlement and credits each recipient wallet's `pending_minor` with immutable `EARNING_PENDING` entries.
2. After 30 days, an eligible settlement debits pending and applies `EARNING_RELEASED`:
   - reduce negative balance first;
   - credit any remainder to available balance.
3. A withdrawal atomically debits available and credits reserved.
4. A final provider failure or review rejection debits reserved and restores available.
5. A successful bank payout debits reserved and credits the paid-out reporting partition.
6. Refunds and disputes create linked reversal entries; original entries remain unchanged.

Release processing is idempotent per settlement allocation and locks the affected wallet during projection updates.

## 10. Stripe Express Onboarding

1. Create one US Stripe Express connected account for each recipient payout account.
2. Generate a short-lived Stripe-hosted onboarding link from the backend.
3. Receive and verify account events through the provider webhook endpoint.
4. Normalize current requirements, overdue requirements, restrictions, transfer eligibility, payout eligibility, and external bank-account presence.
5. Permit withdrawal only when the internal account policy evaluates the account as eligible.
6. Immediately freeze new withdrawals when Stripe restricts an account or required information becomes overdue.

An onboarding submission flag alone is insufficient. The eligibility decision uses the complete normalized account state.

## 11. Withdrawal and Stripe Money Movement

### 11.1 Request and review

1. Require an authenticated recipient and step-up authentication.
2. Validate wallet ownership, USD currency, minimum USD 50.00, configured maximums, daily limits, payout-account eligibility, holds, negative balance, and available funds.
3. Treat the entered withdrawal amount as the gross wallet debit and require it to be at least USD 50.00. Snapshot the estimated fee and calculate `transfer_minor = requested_minor - estimated_fee_minor`; reject a request whose transfer amount is not positive.
4. Atomically move exactly `requested_minor` from available to reserved and create the withdrawal.
5. In the same transaction, enqueue the next durable payout job when the request is automatically approved.
6. Send first withdrawals, large requests, recent payout-account changes, and anomalous activity to manual review; otherwise approve automatically.

### 11.2 External execution

1. Create a Stripe Transfer from the platform account to the Express connected account using a stable attempt idempotency key.
2. Attach internal order/settlement correlation metadata and Stripe transfer grouping information so the charge, transfer, refund, and reversal can be reconciled without relying on descriptions.
3. Persist the transfer ID before proceeding.
4. Create a standard Payout from the connected-account balance to its bank account when the transferred funds are available.
5. Persist the payout ID and follow it through verified webhook events and scheduled queries.
6. Mark the withdrawal `SUCCEEDED` only when the bank payout succeeds.

Stripe account creation must use a controller and payout configuration that allows the platform to implement the confirmed manual withdrawal experience. This capability must be proven in Stripe test mode before production implementation is accepted. If the configured Express model cannot support one withdrawal-to-one manual payout, the fallback is a controlled standard payout schedule with explicit transfer-to-payout reconciliation; internal success still waits for the relevant payout result.

### 11.3 Failure behavior

- Provider timeout or ambiguous response: mark `OUTCOME_UNKNOWN`, query by idempotency key or external object, and never blind-retry.
- Explicit pre-transfer failure: retry according to policy or release the reservation on final failure.
- Transfer succeeds but payout fails: retain the reservation and retry the payout after the recipient fixes the bank account; do not restore internal available balance because funds already left the platform account.
- Repeated failure: freeze the attempt and open a reconciliation issue for an operator.
- Duplicate or out-of-order webhook: deduplicate by event ID and enforce legal monotonic state transitions.

### 11.4 Transfer reversal accounting

- A reversal caused by an order refund consumes the recovered portion from `reserved` and applies it to the linked refund reversal. It does not restore that amount to `available` because the recipient no longer owns the refunded earning.
- A reversal caused only by an operational payout failure moves the recovered gross amount from `reserved` back to `available`, less any final non-refundable fee recorded through a separate fee entry.
- A full successful reversal makes the withdrawal `REVERSED`. A partial reversal records the exact recovered amount, keeps the unrecovered amount reserved while recovery continues, and opens a reconciliation issue.
- A final unrecoverable amount attributable to a refund is removed from reserved and becomes `negative_minor`; future earnings offset it. An unrecoverable operational failure remains frozen for finance review and is never silently converted into recipient debt.
- Provider reversal events are idempotent and reference both the payout attempt and the refund or operational recovery reason.

## 12. Refunds, Disputes, and Reversals

Refund allocation is calculated from the original settlement snapshot. Partial-refund allocation uses deterministic minor-unit rounding and assigns the final remainder so the reversal total equals the refund exactly.

Wallet funds use a balance-pool model rather than tracing each withdrawn cent back to an earning batch. Refund processing locks the wallet and follows this deterministic priority order:

1. Reverse pending earnings.
2. Debit available earnings.
3. If available funds are insufficient, cancel whole withdrawals that have not transferred externally in newest-created-first order. Each cancellation restores its gross reserved amount to available; the refund then debits the required amount from available. Cancelling a larger withdrawal can leave the non-refunded remainder available. Partially cancelling a withdrawal is not supported in the first release.
4. After a successful transfer but before payout, attempt a supported transfer reversal and reconcile the external result.
5. After bank payout, create recipient negative balance and block further withdrawals.

Disputes place an immediate hold on all still-controlled funds associated with the order. Reversal capability varies by provider, so `PayoutGateway` reports whether an operation is supported; unsupported recovery enters the negative-balance/manual-recovery flow.

The refund operation stores every cancelled withdrawal and reversal entry under one refund idempotency key. Retrying the same refund resumes incomplete external recovery and cannot debit the wallet twice.

## 13. Fees

- The recipient bears payout fees. `requested_minor` is the gross wallet debit, `estimated_fee_minor` is the fee snapshot, and `transfer_minor = requested_minor - estimated_fee_minor` is the amount sent toward the connected account. For the first release, expected bank receipt equals `transfer_minor` unless Stripe reports a separately itemized downstream fee.
- The UI shows gross wallet debit, estimated fee, transfer amount, and expected bank amount before confirmation.
- On success, debit `requested_minor` from reserved, add the actual bank payout amount to the `paid_out_minor` reporting projection, and record the final fee as a separate `FEE` ledger entry. The payout amount plus final fee must equal the gross amount consumed, except where a separate provider fee-adjustment entry explains the difference.
- If the final fee is lower than estimated, credit the difference to available. If it is higher, debit available; if available is insufficient, record the remainder in `negative_minor`. Fee adjustments are idempotent and reference the payout attempt.
- Fees are separate ledger entries and never overwrite an earning entry.
- Fee policy is versioned and copied onto each withdrawal so later configuration changes do not alter history.

## 14. Reconciliation

A daily job performs:

1. Wallet reconciliation: projection values equal ledger sums for every wallet partition.
2. Liability reconciliation: total wallet liabilities are compared with platform funds reserved for recipients.
3. Provider reconciliation: payout attempts match provider Transfer, balance transaction, and Payout objects by amount, currency, connected account, and status.

Differences create `reconciliation_issues`, freeze affected execution when necessary, and alert operators. Reconciliation never auto-edits a balance. Resolution occurs through retried provider operations, linked reversals, or dual-approved adjustment entries.

## 15. Security, Authorization, and Audit

- Stripe secrets and webhook secrets remain in server-side secret storage.
- Verify webhook signatures before persisting normalized effects.
- Do not expose external account IDs or raw sensitive provider payloads to clients.
- Institution actors can access only their institution wallet; doctors and consultants can access only their own wallet.
- Separate finance-review, risk-review, and system-administration permissions.
- Every onboarding action, withdrawal, review, retry, reversal, freeze, unfreeze, configuration change, and adjustment is audited with actor and reason.
- Administrators cannot edit balances directly.
- Sensitive payout-account changes trigger a configurable cooling period.
- Store only the provider data required for operations and support; Stripe remains responsible for hosted KYC collection.

## 16. API Surface

Recipient APIs:

- `GET /api/wallet`
- `GET /api/wallet/ledger`
- `GET /api/payout-accounts`
- `POST /api/payout-accounts/{provider}`
- `POST /api/payout-accounts/{id}/onboarding-sessions`
- `GET /api/payout-accounts/{id}/status`
- `POST /api/withdrawals`
- `GET /api/withdrawals`
- `GET /api/withdrawals/{id}`
- `POST /api/withdrawals/{id}/cancel` only before external submission

Operations APIs:

- review or reject a pending withdrawal;
- freeze or unfreeze a wallet with a reason;
- view payout attempts and sanitized provider evidence;
- retry an eligible failed attempt;
- view and resolve reconciliation issues;
- submit and dual-approve a ledger adjustment;
- manage versioned withdrawal limits, fee policy, and risk thresholds.

Provider webhooks use a dedicated endpoint and are not authenticated as recipient APIs; they require provider signature verification and event idempotency.

## 17. Observability

Metrics and alerts include:

- pending and available wallet liabilities;
- reserved and negative balances;
- withdrawal counts and amounts by state;
- onboarding restrictions;
- provider error rate and outcome-unknown age;
- time from request to transfer and payout;
- webhook processing latency and failures;
- reconciliation issue count and unmatched amount;
- wallets whose projection does not match their ledger.

Logs use internal IDs and correlation IDs, not bank details or full provider payloads.

## 18. Migration and Rollout

1. Correct and test the settlement basis before crediting any wallet.
2. Add new tables and provider-neutral services behind feature flags.
3. Backfill existing settlements only after defining an explicit migration cutoff and validating whether each historical payment is still held by the platform. No historical wallet balance is created merely because a settlement currently says `COMPLETED`.
4. Enable ledger creation in shadow mode and reconcile it against current settlement calculations.
5. Enable Stripe Express onboarding for internal test recipients.
6. Prove Transfer, manual/controlled Payout, failure, reversal, and webhook flows in Stripe test mode.
7. Enable wallet display without withdrawal.
8. Enable withdrawals for a small allowlist with low limits.
9. Reconcile daily and gradually expand access.
10. Retire the old `COMPLETED` settlement meaning after all consumers use the new statuses.

## 19. Verification Strategy

### Unit tests

- Exact minor-unit split and remainder allocation.
- Pending release and negative-balance offset.
- Reservation under concurrent withdrawal requests.
- Fee snapshots and adjustments.
- Legal state transitions and prevention of state regression.
- Full and partial refund reversal from original snapshots.
- Idempotent ledger and provider-event handling.

### Integration tests

- Database transaction rollback leaves ledger and projections consistent.
- Two concurrent withdrawals cannot reserve the same funds.
- Duplicate, delayed, and out-of-order webhooks have one effect.
- Transfer timeout recovers by query without duplicate transfer.
- Transfer success plus payout failure does not restore available balance.
- Post-payout refund creates negative balance and blocks withdrawal.
- New earnings offset negative balance before becoming available.
- Account restriction immediately blocks new withdrawals.
- One-cent synthetic mismatch produces a reconciliation issue.

### Stripe test-mode acceptance

- Express onboarding and bank-account setup.
- Account restriction and requirement updates.
- Platform-to-connected-account Transfer.
- Standard Payout success and failure handling.
- Transfer reversal where supported.
- Signed webhook verification and replay.
- Confirmation that the chosen Express controller model supports the required payout control; otherwise validation of the documented scheduled-payout fallback.

## 20. Delivery Stages

1. Settlement correctness and immutable snapshot.
2. Wallet schema, ledger, projections, and 30-day release.
3. Provider-neutral payout contracts and Stripe Express onboarding.
4. Withdrawal reservation, risk review, Stripe Transfer, and Payout tracking.
5. Refund/dispute reversals and negative balances.
6. Reconciliation, operations console, alerts, and controlled production rollout.

Each stage must be independently testable. Real withdrawals remain disabled until all preceding financial invariants, webhook idempotency, and reconciliation checks pass.
