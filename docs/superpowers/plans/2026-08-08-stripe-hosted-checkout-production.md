# Stripe Hosted Checkout Production Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a production-ready, two-stage Stripe Hosted Checkout flow for overseas USD card payments with Chinese and English Flutter UI on Android and iOS.

**Architecture:** Preserve the existing provider abstraction and one-payment-attempt-per-stage model. Harden the server as the only monetary authority, constrain Stripe to live USD card Checkout Sessions, then make the shared Flutter controller restore and render server-confirmed state consistently on both mobile platforms.

**Tech Stack:** Kotlin 1.9.22, Spring Boot 3.2.2, Spring RestClient, Spring Data JPA, MySQL/Flyway, JUnit 5/MockK, Flutter/Dart, Material, `url_launcher`, `flutter_test`.

## Global Constraints

- Stripe Hosted Checkout only; do not introduce PaymentSheet.
- One-time payments only; do not add subscriptions.
- Persisted order currency and every Stripe payment must be `USD`.
- Existing catalog, consultation-fee, coupon, refund, and settlement monetary fields use global USD semantics; do not add runtime FX conversion.
- Historical orders retain their stored currency; only new USD orders may enter Stripe Checkout.
- Stripe Checkout must expose only `card`.
- Preserve `CONSULTATION_FEE` and `BALANCE` as independent payment stages.
- A stage may have multiple attempts but at most one successful attempt.
- Only verified webhook data or a server-side Stripe query may produce `SUCCEEDED`.
- Flutter UI must support Chinese and English on Android and iOS.
- Do not expose unfinished PayPal, WeChat Pay, or Alipay providers.
- Do not commit live or test Stripe credentials.

---

## File Structure

- `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt`: create authoritative USD order snapshots.
- `joysong-server/src/main/kotlin/com/joysong/server/payment/config/PaymentCurrencyPolicy.kt`: expose the single supported catalog/payment currency.
- `joysong-admin/src/pages/InstitutionProjectsPage.tsx`: label editable project prices as USD.
- `joysong-admin/src/pages/DoctorProjectConfigsPage.tsx`: label consultation fees as USD.
- `joysong-admin/src/pages/CouponsPage.tsx`: label fixed discounts and minimum spend as USD.
- `joysong-admin/src/pages/ProjectsPage.tsx`: label reference prices as USD.
- `joysong-admin/src/pages/OrdersPage.tsx`: render each order with its stored currency.
- `joysong-admin/src/pages/RefundsPage.tsx`: render refunds with their stored order currency.
- `joysong-admin/src/pages/SettlementsPage.tsx`: render settlement amounts as USD for new global-currency records.
- `joysong-flutter/lib/features/orders/domain/money.dart`: format global catalog values as USD and expose stored-currency formatting for historical orders.
- `joysong-flutter/lib/features/discover/presentation/`: remove hard-coded CNY symbols from active catalog views.
- `joysong-flutter/lib/features/home/domain/home_models.dart`: format home catalog prices as USD.
- `joysong-server/src/main/kotlin/com/joysong/server/payment/provider/StripePaymentGateway.kt`: create/query/refund Stripe objects and verify Stripe events.
- `joysong-server/src/main/kotlin/com/joysong/server/payment/provider/PaymentGateway.kt`: carry raw webhook data and stage metadata across the provider boundary.
- `joysong-server/src/main/kotlin/com/joysong/server/config/ConfigValidator.kt`: reject unsafe production Stripe configuration.
- `joysong-server/src/main/resources/application.yml`: declare explicit Stripe policy and timeout settings.
- `joysong-server/src/main/kotlin/com/joysong/server/payment/controller/PaymentWebhookController.kt`: receive exact webhook bytes on the explicit Stripe route.
- `joysong-server/src/main/kotlin/com/joysong/server/payment/service/PaymentWebhookService.kt`: verify before parsing/persisting and process events idempotently.
- `joysong-server/src/main/kotlin/com/joysong/server/payment/service/PaymentPersistenceService.kt`: enforce stage, amount, currency, and duplicate-success invariants.
- `joysong-server/src/main/kotlin/com/joysong/server/payment/service/PaymentService.kt`: throttle provider refresh and preserve unknown outcomes.
- `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundExecutionService.kt`: reconcile unknown Stripe refunds without unsafe indefinite POST retries.
- `joysong-flutter/lib/features/orders/presentation/payment_controller.dart`: own restorable external Checkout state.
- `joysong-flutter/lib/features/orders/presentation/payment_page.dart`: render provider, USD amount, status, and commands.
- `joysong-flutter/lib/features/orders/presentation/payment_strings.dart`: contain all Chinese and English payment copy.
- `joysong-flutter/lib/features/orders/presentation/order_detail_page.dart`: expose only the stage-valid payment entry point.
- `docs/PAYMENT_IMPLEMENTATION_GUIDE.md`: become the production Stripe runbook.
- `docs/PAYMENT_ORDER_FRONTEND_API.md`: document exact two-stage client/server contract.

---

### Task 1: Establish Global USD Catalog and Order Snapshots

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payment/config/PaymentCurrencyPolicy.kt`
- Modify: `joysong-admin/src/pages/InstitutionProjectsPage.tsx`
- Modify: `joysong-admin/src/pages/DoctorProjectConfigsPage.tsx`
- Modify: `joysong-admin/src/pages/SplitConfigProposalsPage.tsx`
- Modify: `joysong-admin/src/pages/CouponsPage.tsx`
- Modify: `joysong-admin/src/pages/ProjectsPage.tsx`
- Modify: `joysong-admin/src/pages/ProjectCollaborationPage.tsx`
- Modify: `joysong-admin/src/pages/OrdersPage.tsx`
- Modify: `joysong-admin/src/pages/RefundsPage.tsx`
- Modify: `joysong-admin/src/pages/SettlementsPage.tsx`
- Modify: `joysong-flutter/lib/features/orders/domain/money.dart`
- Modify: `joysong-flutter/lib/features/home/domain/home_models.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/catalog_detail_shared.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/catalog_institution_detail_view.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/discover_content_card.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/doctor_detail_view.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/institution_detail_view.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/project_detail_view.dart`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt`
- Test: `joysong-flutter/test/features/orders/order_detail_page_test.dart`
- Test: `joysong-flutter/test/features/orders/payment_page_test.dart`
- Test: `joysong-flutter/test/features/discover/catalog_detail_price_test.dart`

**Interfaces:**
- Consumes: existing order creation request and project pricing fields.
- Produces: globally consistent USD catalog displays and new orders whose `currency`, `totalAmountMinor`, `consultationFeeMinor`, and `remainingAmountMinor` are internally consistent USD snapshots.

- [ ] **Step 1: Inventory every active monetary input and display**

Search server, admin, and Flutter sources for currency symbols, `CNY`, price formatters, consultation fees, fixed coupon amounts, refunds, and settlements. Record the exact file list in the task notes before editing so no active screen retains CNY semantics.

- [ ] **Step 2: Add failing global USD policy and snapshot tests**

Add assertions to the order creation tests that a newly created overseas order has:

```kotlin
assertEquals("USD", saved.currency)
assertEquals(Money.toMinor(saved.price, "USD"), saved.totalAmountMinor)
assertEquals(Money.toMinor(saved.consultationFee, "USD"), saved.consultationFeeMinor)
assertEquals(Money.toMinor(saved.remainingAmount, "USD"), saved.remainingAmountMinor)
assertEquals(saved.totalAmountMinor, saved.consultationFeeMinor!! + saved.remainingAmountMinor!!)
```

- [ ] **Step 3: Run the focused test and confirm the current CNY behavior fails**

Run:

```powershell
cd joysong-server
$env:GRADLE_USER_HOME="$PWD\.tmp\gradle-user-home-stripe"
.\gradlew.bat test --tests com.joysong.server.order.OrderServiceTest
```

Expected: FAIL because `OrderService` currently persists `CNY`.

- [ ] **Step 4: Introduce one server-side currency policy**

Create:

```kotlin
object PaymentCurrencyPolicy {
    const val CATALOG_CURRENCY = "USD"
    const val PRICING_COUNTRY = "US"
}
```

Use this policy for new order snapshots and Stripe eligibility. Do not mutate historical orders.

- [ ] **Step 5: Replace the hard-coded CNY snapshot with one USD pricing snapshot**

Normalize currency once and derive every minor-unit field from it:

```kotlin
val currency = "USD"
val totalAmountMinor = Money.toMinor(price, currency)
val consultationFeeMinor = Money.toMinor(consultationFee, currency)
val remainingAmountMinor = totalAmountMinor - consultationFeeMinor
```

Reject negative remaining amounts and keep the decimal compatibility fields equal to their minor-unit values.

- [ ] **Step 6: Update admin monetary inputs and mobile catalog displays**

Keep the existing numeric fields, but label project price, original price, doctor consultation fee, and fixed coupon amount as USD in admin forms. Replace active `¥`/`CNY` formatters for these globally redefined fields with `$`/`USD` formatters in Flutter. Preserve historical order rendering by using each order's stored currency rather than the new global default.

- [ ] **Step 7: Add a live-enablement repricing gate**

Do not guess exchange rates or rewrite existing numeric values. Add a production configuration acknowledgement such as:

```text
USD_CATALOG_REPRICED=true
```

`ConfigValidator` must reject `PAYMENT_MODE=live` unless this acknowledgement is true. The runbook must require an operator query and signed review of all active project prices, consultation fees, and fixed coupon values before setting it.

- [ ] **Step 8: Run focused server, admin, and Flutter currency tests**

Run the server command from Step 3, the admin build, and these Flutter tests:

```powershell
cd joysong-admin
npm run build

cd ..\joysong-flutter
flutter test test/features/orders/order_detail_page_test.dart test/features/orders/payment_page_test.dart test/features/discover/catalog_detail_price_test.dart
```

Expected: PASS with no CNY display on active catalog/payment screens and correct stored-currency display for historical orders.

- [ ] **Step 9: Commit the global USD change**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt joysong-server/src/main/kotlin/com/joysong/server/payment/config/PaymentCurrencyPolicy.kt joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt joysong-admin/src/pages joysong-flutter/lib/features joysong-flutter/test/features
git commit -m "feat: establish global USD catalog pricing"
```

---

### Task 2: Enforce Stripe USD Card Checkout and Safe Live Configuration

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/provider/PaymentGateway.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/provider/StripePaymentGateway.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/ConfigValidator.kt`
- Modify: `joysong-server/src/main/resources/application.yml`
- Modify: `joysong-server/.env.example`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/payment/provider/StripePaymentGatewayTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/config/ConfigValidatorTest.kt`

**Interfaces:**
- Consumes: `ProviderCreatePaymentRequest` including `paymentType` and `paymentMethod`.
- Produces: a Stripe Session request constrained to USD/card with stage metadata and bounded HTTP timeouts.

- [ ] **Step 1: Add failing gateway payload tests**

Assert the recorded form contains:

```kotlin
assertEquals("card", form.getFirst("payment_method_types[0]"))
assertEquals("usd", form.getFirst("line_items[0][price_data][currency]"))
assertEquals("CONSULTATION_FEE", form.getFirst("metadata[payment_type]"))
assertEquals("CONSULTATION_FEE", form.getFirst("payment_intent_data[metadata][payment_type]"))
```

Add negative tests proving `CNY` and a non-`CARD` method fail before an HTTP request is sent.

- [ ] **Step 2: Run the gateway test and verify it fails**

```powershell
cd joysong-server
$env:GRADLE_USER_HOME="$PWD\.tmp\gradle-user-home-stripe"
.\gradlew.bat test --tests com.joysong.server.payment.provider.StripePaymentGatewayTest
```

Expected: FAIL because payment method type and stage metadata are absent.

- [ ] **Step 3: Extend the provider create request with the payment stage**

Use the existing domain enum rather than a free-form string:

```kotlin
data class ProviderCreatePaymentRequest(
    val paymentId: String,
    val orderId: String,
    val paymentType: PaymentType,
    val amountMinor: Long,
    val currency: String,
    val paymentMethod: String,
    val idempotencyKey: String
)
```

Update the single construction point in `PaymentService`.

- [ ] **Step 4: Implement the strict Checkout request**

Before creating the form:

```kotlin
require(request.currency == "USD") { "STRIPE_USD_REQUIRED" }
require(request.paymentMethod == "CARD") { "STRIPE_CARD_REQUIRED" }
```

Add `payment_method_types[0]=card`, stage metadata, and stage-specific product names.

- [ ] **Step 5: Add failing production configuration tests**

Cover test keys, blank API version, non-official API base, HTTP callback URLs, and invalid timeout ranges. Each test must expect startup validation failure with a stable configuration code.

- [ ] **Step 6: Implement production configuration validation**

Add explicit properties for API host, API version, connect timeout, response timeout, and expected live mode. In `prod`, require:

```kotlin
require(secretKey.startsWith("sk_live_") || secretKey.startsWith("rk_live_"))
require(webhookSecret.startsWith("whsec_"))
require(apiBase == "https://api.stripe.com")
require(apiVersion.isNotBlank())
```

Do not print property values in validation errors.

- [ ] **Step 7: Run gateway and configuration tests**

Expected: PASS.

- [ ] **Step 8: Commit Stripe policy hardening**

```powershell
git add joysong-server/src/main joysong-server/src/test joysong-server/.env.example
git commit -m "fix: enforce live USD card Stripe checkout"
```

---

### Task 3: Harden Webhook Verification and Payment State Convergence

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/provider/PaymentGateway.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/provider/StripePaymentGateway.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/controller/PaymentWebhookController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/service/PaymentWebhookService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/service/PaymentPersistenceService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/service/PaymentService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/payment/provider/StripePaymentGatewayTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/payment/service/PaymentOrchestrationTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/payment/service/PaymentServiceSafetyTest.kt`

**Interfaces:**
- Consumes: exact webhook body bytes and Stripe headers.
- Produces: an idempotent verified event and a monotonic local payment/order state.

- [ ] **Step 1: Add failing raw-body and live-mode webhook tests**

Test a UTF-8 payload using its exact bytes, valid and invalid signatures, expired timestamps, multiple `v1` signatures, and `livemode=false` under the production policy.

- [ ] **Step 2: Change webhook verification to accept raw bytes**

Use:

```kotlin
fun verifyWebhook(payload: ByteArray, headers: Map<String, String>): VerifiedProviderEvent
```

Compute HMAC over `timestamp + "." + payload` without parsing or re-serializing first. Decode as UTF-8 only after signature verification.

- [ ] **Step 3: Narrow the anonymous route**

Receive `@RequestBody payload: ByteArray` at the Stripe endpoint and permit only:

```kotlin
.requestMatchers(HttpMethod.POST, "/api/payment-webhooks/STRIPE").permitAll()
```

Keep every other payment route authenticated.

- [ ] **Step 4: Add failing monetary invariant tests**

Prove that a successful result with missing amount, missing currency, wrong amount, wrong currency, or wrong stage cannot advance an order. Prove duplicate and out-of-order events cannot increment `paidAmountMinor` twice.

- [ ] **Step 5: Require complete successful provider facts**

For `SUCCEEDED`, require non-null matching `amountMinor` and `currency`, matching provider ID, and a stage-valid order transition inside the locked transaction.

- [ ] **Step 6: Add provider refresh throttling tests**

Call `getPayment(refresh=true)` repeatedly and assert only one provider query occurs inside the configured minimum refresh interval.

- [ ] **Step 7: Implement refresh throttling**

Use persisted payment timestamps and a configured minimum interval. Return current local state inside the interval; do not sleep request threads.

- [ ] **Step 8: Run all payment server tests**

```powershell
.\gradlew.bat test --tests "com.joysong.server.payment.*"
```

Expected: PASS.

- [ ] **Step 9: Commit webhook and state hardening**

```powershell
git add joysong-server/src/main joysong-server/src/test
git commit -m "fix: harden Stripe webhook state convergence"
```

---

### Task 4: Make Two-Stage Refund Outcomes Recoverable

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundExecutionService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/provider/StripePaymentGateway.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/service/PaymentReconciliationService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundItemPersistenceService.kt`
- Create: `joysong-server/src/main/resources/db/migration/V8__harden_refund_item_uniqueness.sql`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/refund/RefundExecutionServiceTest.kt`

**Interfaces:**
- Consumes: refund items tied to original successful consultation-fee or balance payments.
- Produces: deterministic `SUCCEEDED`, `FAILED`, or `NEEDS_REVIEW` outcomes without duplicate refund items.

- [ ] **Step 1: Add failing split-refund and unknown-outcome tests**

Cover a two-payment full refund, concurrent refund item creation, a lost Stripe response, a retry older than the safe idempotency window, and a definitive failed refund.

- [ ] **Step 2: Add database uniqueness for refund items**

Create a migration with a unique key on `(refund_id, payment_id)` after a query that detects existing duplicates. The migration must fail visibly rather than silently discard financial records.

- [ ] **Step 3: Reconcile unknown refunds by stable metadata**

Before repeating an uncertain refund POST, list or retrieve refunds for the original PaymentIntent and match `metadata.refund_item_id`. If the outcome cannot be proven after the configured window, mark `NEEDS_REVIEW` and alert instead of issuing another refund.

- [ ] **Step 4: Aggregate terminal item failures into the parent refund**

Stop rescanning unrecoverable `FAILED` items forever. Preserve partial success totals and expose a manual-review state.

- [ ] **Step 5: Run refund and payment reconciliation tests**

Expected: PASS with no duplicate refund calls.

- [ ] **Step 6: Commit refund safety changes**

```powershell
git add joysong-server/src/main joysong-server/src/test
git commit -m "fix: make staged Stripe refunds recoverable"
```

---

### Task 5: Synchronize the Flutter Two-Stage Payment Controller

**Files:**
- Modify: `joysong-flutter/lib/features/orders/presentation/payment_controller.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/payment_action_launcher.dart`
- Modify: `joysong-flutter/lib/features/orders/domain/payment_models.dart`
- Test: `joysong-flutter/test/features/orders/payment_controller_test.dart`
- Test: `joysong-flutter/test/features/orders/payment_action_launcher_test.dart`

**Interfaces:**
- Consumes: server `PaymentAttempt` responses and app lifecycle resume events.
- Produces: monotonic `PaymentFlowStage` state and a boolean completion signal to refresh the order.

- [ ] **Step 1: Add failing controller tests**

Test consultation-fee submit, balance submit, double tap, restored `REQUIRES_ACTION`, browser launch, app resume, processing timeout, cancelled/expired retry, and server-confirmed success. Assert Stripe never calls the PayPal confirm endpoint.

- [ ] **Step 2: Add strict response validation**

Reject a payment response when its order ID, payment type, provider, currency, or amount conflicts with the page context. A malformed response becomes a localized-safe failure and never success.

- [ ] **Step 3: Separate browser launch from payment completion**

After `PaymentActionOutcome.launched`, retain the current attempt and enter `processing`. On lifecycle resume, query the same payment ID with controlled polling. Only `PaymentStatus.succeeded` returns `true`.

- [ ] **Step 4: Preserve safe retry semantics**

Continue the same Checkout URL for `REQUIRES_ACTION`. Generate a new create idempotency key only after a terminal failed, cancelled, or expired attempt is confirmed by the server.

- [ ] **Step 5: Keep the redirect allowlist fail-closed**

Test exact `https://checkout.stripe.com` and subdomains, and reject HTTP, user-info tricks, suffix lookalikes, missing hosts, and non-Stripe configured values in release defaults.

- [ ] **Step 6: Run focused controller and launcher tests**

```powershell
cd joysong-flutter
flutter test test/features/orders/payment_controller_test.dart test/features/orders/payment_action_launcher_test.dart
```

Expected: PASS.

- [ ] **Step 7: Commit shared mobile controller changes**

```powershell
git add joysong-flutter/lib/features/orders joysong-flutter/test/features/orders
git commit -m "fix: synchronize Stripe checkout mobile state"
```

---

### Task 6: Complete Chinese/English Android/iOS Payment UI

**Files:**
- Modify: `joysong-flutter/lib/features/orders/presentation/payment_page.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/payment_strings.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/order_detail_page.dart`
- Test: `joysong-flutter/test/features/orders/payment_page_test.dart`
- Test: `joysong-flutter/test/features/orders/order_detail_page_test.dart`

**Interfaces:**
- Consumes: `PaymentController`, `PaymentType`, order stage, and locale.
- Produces: identical functional payment behavior with localized copy on Android and iOS.

- [ ] **Step 1: Add failing bilingual widget tests**

For both `Locale('zh')` and `Locale('en')`, assert stage title, `$19.00 USD` style amount, `Card (Stripe)` provider selector, primary command, processing copy, retry copy, and success return action.

- [ ] **Step 2: Add stage-valid order entry tests**

Assert `PENDING_PAYMENT` exposes consultation-fee payment, `VERIFIED` exposes balance payment, and every other state hides payment commands.

- [ ] **Step 3: Implement localized USD formatting**

Render the server minor-unit amount with two USD fraction digits:

```dart
String usdAmount(int amountMinor) =>
    '\$${(amountMinor / 100).toStringAsFixed(2)} USD';
```

Use the order snapshot only as a pre-create display fallback; replace it with the server payment amount as soon as an attempt loads.

- [ ] **Step 4: Preserve the extensible provider selector**

Keep the selector visible, render only enabled providers, and label Stripe as localized card payment. Do not render disabled future providers.

- [ ] **Step 5: Implement complete localized stage UI**

Provide Chinese and English strings for ready, opening browser, awaiting payment, processing, continuation, cancellation, expiry, failure, success, refresh, retry, and return-to-order states. Map stable server codes to these strings and hide raw provider messages.

- [ ] **Step 6: Verify Android/iOS lifecycle integration**

Use the existing `WidgetsBindingObserver` for both platforms. Add widget coverage for `AppLifecycleState.resumed` and manually run one Android emulator and one iOS simulator/device flow where available.

- [ ] **Step 7: Test compact layouts**

Run widget tests at a narrow phone size and assert no overflow exceptions in Chinese or English.

- [ ] **Step 8: Run all order feature tests**

```powershell
flutter test test/features/orders
```

Expected: PASS.

- [ ] **Step 9: Commit bilingual cross-platform UI**

```powershell
git add joysong-flutter/lib/features/orders joysong-flutter/test/features/orders
git commit -m "feat: complete bilingual Stripe payment UI"
```

---

### Task 7: Rewrite the Stripe Runbook and Verify the Release

**Files:**
- Modify: `docs/PAYMENT_IMPLEMENTATION_GUIDE.md`
- Modify: `docs/PAYMENT_ORDER_FRONTEND_API.md`
- Modify: `joysong-server/deploy/README.md`
- Reference: `doc/order_dispute_flow.puml`
- Reference: `doc/stripe_consultation_fee_payment_flow.puml`

**Interfaces:**
- Consumes: final configuration keys, endpoints, states, tests, and operational controls.
- Produces: an executable sandbox/live deployment and incident runbook.

- [ ] **Step 1: Rewrite current-state and scope sections**

State plainly that Stripe USD card Hosted Checkout is the supported production path and other providers remain unimplemented. Document the consultation-fee and balance Session split.

- [ ] **Step 2: Add exact sandbox and live configuration tables**

Document every Stripe environment variable, safe example value shape, ownership, rotation procedure, and startup failure condition without including credentials.

- [ ] **Step 3: Add Dashboard and CLI setup**

Document card-only settings, exact webhook event subscriptions, local `stripe listen` forwarding, webhook secret differences, and why a generic CLI trigger cannot validate Joysong metadata-driven order transitions.

- [ ] **Step 4: Add the acceptance matrix**

Include normal success, 3DS success, 3DS decline, cancellation, expiry, delayed/replayed webhook, two-stage payment, split refund, and controlled live small-value validation with expected database states.

- [ ] **Step 5: Add operations and rollback procedures**

Document logging correlation IDs, alert conditions, reconciliation, manual review, key rotation, stopping new Checkout creation while preserving callbacks, webhook replay, refund recovery, and rollback.

- [ ] **Step 6: Run server verification**

```powershell
cd joysong-server
$env:GRADLE_USER_HOME="$PWD\.tmp\gradle-user-home-stripe"
.\gradlew.bat test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Run Flutter verification**

```powershell
cd joysong-flutter
flutter analyze
flutter test
```

Expected: no analyzer errors and all tests pass.

- [ ] **Step 8: Perform a secret and configuration scan**

Search tracked changes for `sk_test_`, `sk_live_`, `rk_live_`, and concrete `whsec_` values. Expected: only documented redacted prefixes or test fixtures with obviously synthetic values.

- [ ] **Step 9: Review the final diff against the design**

Confirm every completion criterion in `docs/superpowers/specs/2026-08-08-stripe-hosted-checkout-production-design.md` has code, test, or documented external acceptance evidence.

- [ ] **Step 10: Commit documentation and release verification updates**

```powershell
git add docs joysong-server/deploy/README.md
git commit -m "docs: add Stripe production operations runbook"
```
