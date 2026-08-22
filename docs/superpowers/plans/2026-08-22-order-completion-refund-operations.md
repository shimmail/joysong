# Travel Ground Service Completion and Refund Operations Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task with a fresh implementer and reviewer for every task.

**Goal:** Let a paid travel-ground-service order complete without entering medical settlement, keep completed-order consultant history readable, allow full refund after completion, and give administrators actionable failed-refund details plus an idempotent manual retry.

**Architecture:** Reuse `COMPLETED` as the terminal fulfillment state for `TRAVEL_GROUND_SERVICE_ONLY`, distinguished by `paymentFlow`; travel completion writes only completion state/time. Persist and restore `refund.originalStatus` across cancellation/rejection. Extend the existing provider-neutral refund item state machine so only failed items are requeued and successful items are never replayed.

**Tech Stack:** Kotlin/Spring Boot/JPA/JUnit/MockK, Flutter/Dart, React/TypeScript/Ant Design/Vitest, PlantUML.

**Spec:** `docs/superpowers/specs/2026-08-21-travel-ground-service-payment-design.md`

## Global constraints

- Scope is only `TRAVEL_GROUND_SERVICE_ONLY`; legacy medical completion, settlement, and refund behavior must remain unchanged.
- Travel completion is `SERVICE_ACTIVE -> COMPLETED`, idempotent when already completed, and must not call medical verification, balance, settlement, split, or wallet code.
- Completed travel orders expose only already-authorized consultant public information and readable order-service history; sending remains disabled.
- Travel refund application is allowed only from `SERVICE_ACTIVE` or `COMPLETED` and is always full-value/manual-review.
- Cancellation or rejection restores the exact persisted `refund.originalStatus`.
- Provider failure remains visible as `REFUND_PROCESSING`; it must never be reported as success.
- Manual retry touches only failed refund items. Items with a provider refund ID resume/query the same provider operation; items without one reuse the existing idempotency key. Succeeded items are immutable.
- No database migration unless implementation evidence proves a schema change is necessary.
- No travel filing, passport, flight, hotel, quantity, medical tail payment, Stripe new-flow, or settlement feature.

---

### Task 1: Backend completion, post-completion refunds, and safe retry

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/entity/OrderStatusEnum.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/dto/OrderResponse.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/dm/service/OrderServiceConversationService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundWorkflowPersistenceService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundItemPersistenceService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminRefundController.kt`
- Test: closest existing order, conversation, refund, and admin-controller tests.

**Contract:**
- `POST /api/orders/{id}/confirm-completion` accepts an owned active travel order and returns `COMPLETED` without a settlement.
- `POST /api/orders/{id}/refund` accepts travel state `SERVICE_ACTIVE` or `COMPLETED` and records it as `originalStatus`.
- Cancel/reject restores `originalStatus` and matching messaging entitlement.
- `GET /api/admin/refunds` includes `reviewedBy`, `reviewedAt`, `rejectReason`, and refund item fields: `id`, `paymentId`, `provider`, `currency`, `amountMinor`, `providerRefundId`, `status`, `failureCode`, `failureMessage`, timestamps.
- `POST /api/admin/refunds/{id}/retry` is admin-only and only operates on a processing refund with failed items.

- [ ] Write focused failing tests for travel completion, idempotency, no settlement, completed entitlements/read-only conversation, refund from completed, cancel/reject exact restore, item details, and retry safety.
- [ ] Run only those tests and preserve the expected RED evidence.
- [ ] Implement the smallest state-machine and service changes. Lock the order in mutable completion/refund transitions. Keep the legacy branch intact.
- [ ] In retry preparation, move failed items with `providerRefundId` to `PROCESSING`, otherwise to `CREATED`; clear prior failure fields, never modify `SUCCEEDED`, then invoke the existing executor.
- [ ] Run the focused tests to GREEN and commit with a backend-scoped message.

### Task 2: Flutter completion and completed-order refund experience

**Files:**
- Modify: `joysong-flutter/lib/features/orders/domain/order_models.dart`
- Modify: `joysong-flutter/lib/features/orders/application/orders_controller.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/order_detail_page.dart`
- Modify only if needed: refund model/data-source files under `joysong-flutter/lib/features/orders/`.
- Test: closest order model/controller/detail-page tests.

**Contract:**
- Active travel orders expose a confirmation action with travel-specific copy.
- Confirmation refreshes the order as `COMPLETED`; it does not show settlement language or request settlement data.
- Completed travel orders keep consultant public information and a read-only conversation entry.
- Completed travel orders can request a full refund; rejected/cancelled refund display the restored state and rejection reason when present.
- Legacy medical order behavior remains unchanged.

- [ ] Write failing model/controller/widget tests for the contract, including the absence of settlement fetches and medical-settlement copy.
- [ ] Run only those tests and preserve RED evidence.
- [ ] Implement flow-aware eligibility, actions, copy, refund details, and settlement exclusion.
- [ ] Run focused tests plus `flutter analyze` to GREEN and commit with a Flutter-scoped message.

### Task 3: Admin failure diagnostics and manual retry

**Files:**
- Modify: `joysong-admin/src/pages/RefundsPage.tsx`
- Modify: `joysong-admin/src/pages/PaymentOperationsPages.test.tsx`
- Modify only if required: shared admin API/types used by the page.

**Contract:**
- Refund details show reviewer, review time, rejection reason, and each provider refund item/error.
- A retry action appears only for `REFUND_PROCESSING` records containing a failed item.
- Retry calls `POST /api/admin/refunds/{id}/retry`, reports success/failure honestly, and reloads the list/detail.
- Approval/rejection behavior is preserved.

- [ ] Add focused failing UI/API tests for details, retry visibility, retry request, refresh, and provider failure display.
- [ ] Run the focused test and preserve RED evidence.
- [ ] Implement the smallest page/type changes.
- [ ] Run the focused test, lint, and build to GREEN and commit with an admin-scoped message.

### Task 4: Synchronize UML and verify the cross-stack flow

**Files:**
- Modify: `design/TRAVEL_GROUND_SERVICE_PAYMENT_WORKFLOW.puml`
- Modify if contracts changed: `docs/FLUTTER_API_CONTRACT.md`
- Modify if operational guidance changed: `docs/支付开发与云服务器部署指南.md`

**Contract:** The activity diagram must show active completion, completed-order refund, exact original-state restoration, failed provider refund visibility/manual retry, and hospital-direct medical payment outside the platform.

- [ ] Update the activity diagram and concise API/operations text; do not add new speculative components.
- [ ] Run focused backend, Flutter, and admin suites once after integration. Run at most one relevant full suite per project and stop any full suite exceeding ten minutes.
- [ ] Perform source checks proving no travel completion enters medical settlement and no completed-order refund hardcodes restoration to `SERVICE_ACTIVE`.
- [ ] Request a final branch-wide review, resolve findings through the task implementer/reviewer loop, and run verification-before-completion.
- [ ] Commit documentation/verification corrections.

## External readiness boundary

Production Alipay+ payment/refund end-to-end verification remains blocked until the merchant registration, endpoint, credentials, certificates, notification URL, supported USD product, and sandbox/production parameters are supplied. Tests must use the existing test gateway and must never simulate production success to hide this dependency.
