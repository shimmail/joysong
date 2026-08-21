# Travel Ground Service Payment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace new-order consultation/balance charging with one USD travel ground service fee, activate consultant details and order-scoped messaging only after verified payment, and route every fee refund through manual review.

**Architecture:** Add a `TRAVEL_GROUND_SERVICE_ONLY` order flow beside dormant legacy values, snapshot a doctor-maintained medical list price and the platform rate into each new order, and reuse the provider-neutral payment attempt/event/refund core. A verified `TRAVEL_GROUND_SERVICE_FEE` success atomically activates service; an `ORDER_SERVICE` conversation is authorized from the order on every operation. Flutter and admin consume explicit server entitlements instead of inferring access from amounts.

**Tech Stack:** Kotlin 2.x, Spring Boot, Spring Data JPA, Flyway/MySQL, JUnit 5/MockK/Testcontainers, Flutter/Dart, React/TypeScript/Ant Design/Vitest.

**Spec:** `docs/superpowers/specs/2026-08-21-travel-ground-service-payment-design.md`

## Global Constraints

- User-facing fee name is exactly `旅游地接服务费`; do not use `面诊金`, `尾款`, or medical-payment wording for the new flow.
- Currency is exactly `USD`; money uses minor-unit `Long`/`int`, and percentages use basis points with `10000 = 100%`.
- `travelGroundServiceFeeMinor = roundHalfUp(medicalListPriceMinor * platformServiceRateBps / 10000)`.
- The doctor sets `medicalListPrice`; the platform owns `platformServiceRate`; quantity and coupons never affect the fee.
- The selected consultant is bound when the order is created, but user-facing order APIs redact the consultant ID/details until verified payment activates service.
- Only verified provider webhook/query truth can activate service; client redirect/resume is never payment truth.
- A payment attempt expires after 30 minutes; expiration never cancels the order and a new attempt may be created.
- New orders cannot create `CONSULTATION_FEE`, `BALANCE`, medical refunds, settlements, or wallet allocations.
- Travel ground service refunds are full-value and manual-review only.
- Hospital medical payment is outside the platform and has no platform payment/refund/settlement record.
- No travel filing, passport, flight, or hotel feature is created.
- Stripe is removed from every new-order/client path; its adapter remains default-off only behind a legacy-liability safety gate until old query/refund duties are cleared. An unconfigured Alipay+ provider returns `PAYMENT_PROVIDER_UNAVAILABLE` before attempt persistence and never simulates success.
- Do not modify historical Flyway files; add new migrations only.
- Do not delete old orders in a schema migration. Historical cleanup is a separately validated operation with backup and explicit targets.
- Tests and migrations must not connect to a shared development database.
- Run Gradle commands from `joysong-server`, Flutter commands from `joysong-flutter`, and npm commands from `joysong-admin`; git commands run from the worktree root.

## File and Interface Map

- `TravelGroundServicePricing.kt`: sole owner of USD minor-unit fee calculation and platform-rate conversion.
- `DoctorInstitutionProjectConfigEntity.kt`: doctor-maintained medical list price source.
- `OrderEntity.kt`: immutable pricing, flow, consultant, and activation snapshots.
- `OrderResponse.kt`: user/management projections and server-side consultant redaction.
- `PaymentPersistenceService.kt`: local attempt preparation and atomic activation on provider success.
- `PaymentAttemptExpiryService.kt`: expires eligible payment attempts without mutating orders.
- `OrderServiceConversationService.kt`: creates and authorizes `ORDER_SERVICE` conversations from `orderId`.
- Flutter booking: fetches the quote and creates an order without quantity/coupon fields.
- Flutter order/payment: renders server entitlements and launches only trusted HTTPS redirect actions.
- Admin configuration: edits medical list price and displays platform rate as platform-owned read-only data.

---

### Task 1: Add deterministic travel ground service pricing

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/order/service/TravelGroundServicePricing.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/entity/DoctorInstitutionProjectConfigEntity.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminOrderController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/DoctorProjectChangeController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/institution/service/DoctorProjectChangeService.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/order/service/TravelGroundServicePricingTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/AdminOrderControllerTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/DoctorProjectChangeControllerTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectChangeServiceTest.kt`

**Interfaces:**
- Consumes: `OrderSplitRatePolicy.currentPlatformRate(): BigDecimal`, `Money.toMinor(amount, "USD")`.
- Produces: `TravelGroundServicePricing.quote(medicalListPrice: BigDecimal): TravelGroundServiceQuote` and `DoctorInstitutionProjectConfigEntity.medicalListPrice: BigDecimal`.

- [ ] **Step 1: Write the pricing tests**

```kotlin
class TravelGroundServicePricingTest {
    private val properties = OrderSplitProperties().apply { platformRate = BigDecimal("40.00") }
    private val pricing = TravelGroundServicePricing(OrderSplitRatePolicy(properties))

    @Test
    fun `quotes USD fee from list price and platform basis points`() {
        val quote = pricing.quote(BigDecimal("1000.00"))

        assertEquals("USD", quote.currency)
        assertEquals(100_000L, quote.medicalListPriceMinor)
        assertEquals(4_000, quote.platformServiceRateBps)
        assertEquals(40_000L, quote.travelGroundServiceFeeMinor)
    }

    @Test
    fun `rounds fractional minor unit half up`() {
        properties.platformRate = BigDecimal("50.00")
        assertEquals(1L, pricing.quote(BigDecimal("0.01")).travelGroundServiceFeeMinor)
    }

    @Test
    fun `rejects zero list price`() {
        assertEquals(
            "MEDICAL_LIST_PRICE_NOT_POSITIVE",
            assertThrows<IllegalArgumentException> { pricing.quote(BigDecimal.ZERO) }.message
        )
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
.\gradlew.bat test --tests com.joysong.server.order.service.TravelGroundServicePricingTest
```

Expected: compilation fails because `TravelGroundServicePricing` does not exist.

- [ ] **Step 3: Implement the pricing policy**

```kotlin
data class TravelGroundServiceQuote(
    val currency: String,
    val medicalListPriceMinor: Long,
    val platformServiceRateBps: Int,
    val travelGroundServiceFeeMinor: Long
)

@Component
class TravelGroundServicePricing(
    private val splitRatePolicy: OrderSplitRatePolicy
) {
    fun quote(medicalListPrice: BigDecimal): TravelGroundServiceQuote {
        require(medicalListPrice > BigDecimal.ZERO) { "MEDICAL_LIST_PRICE_NOT_POSITIVE" }
        val currency = "USD"
        val listMinor = Money.toMinor(medicalListPrice, currency)
        val rateBps = splitRatePolicy.currentPlatformRate()
            .movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact()
        val feeMinor = BigDecimal.valueOf(listMinor)
            .multiply(BigDecimal.valueOf(rateBps.toLong()))
            .divide(BigDecimal("10000"), 0, RoundingMode.HALF_UP)
            .longValueExact()
        require(feeMinor > 0) { "TRAVEL_GROUND_SERVICE_FEE_NOT_POSITIVE" }
        return TravelGroundServiceQuote(currency, listMinor, rateBps, feeMinor)
    }
}
```

Add `medicalListPrice` to `DoctorInstitutionProjectConfigEntity`, `UpsertConfigRequest`, and the admin upsert mapping. Validate it is positive; keep legacy fields only as dormant compatibility fields. Extend the existing doctor `PROFILE_UPDATE` request, immutable before-snapshot, approval mapping, and strict controller field list with `medicalListPrice`; the platform rate remains read-only and is never accepted from the doctor as the service-fee rate. Preserve `medicalListPrice` in every legacy config upsert path so an old split update cannot reset it to zero. Task 2 supplies the Flyway columns used by these mappings.

- [ ] **Step 4: Add the controller behavior test and make it pass**

Add an admin request fixture and a doctor `PROFILE_UPDATE` fixture containing `medicalListPrice = BigDecimal("1000.00")`; assert the saved/approved config contains that exact value, the before-snapshot is retained, a zero value is rejected with `医疗套餐优惠前金额必须大于 0`, and a doctor request cannot submit a platform-rate override.

Run:

```powershell
.\gradlew.bat test --tests com.joysong.server.order.service.TravelGroundServicePricingTest --tests com.joysong.server.admin.controller.AdminOrderControllerTest --tests com.joysong.server.admin.controller.DoctorProjectChangeControllerTest --tests com.joysong.server.institution.service.DoctorProjectChangeServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/order/service/TravelGroundServicePricing.kt joysong-server/src/main/kotlin/com/joysong/server/order/entity/DoctorInstitutionProjectConfigEntity.kt joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminOrderController.kt joysong-server/src/main/kotlin/com/joysong/server/admin/controller/DoctorProjectChangeController.kt joysong-server/src/main/kotlin/com/joysong/server/institution/service/DoctorProjectChangeService.kt joysong-server/src/test/kotlin/com/joysong/server/order/service/TravelGroundServicePricingTest.kt joysong-server/src/test/kotlin/com/joysong/server/admin/controller/AdminOrderControllerTest.kt joysong-server/src/test/kotlin/com/joysong/server/admin/controller/DoctorProjectChangeControllerTest.kt joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectChangeServiceTest.kt
git commit -m "feat: add travel ground service pricing"
```

### Task 2: Create the new order flow and redact consultant details

**Files:**
- Create: `joysong-server/src/main/resources/db/migration/V29__travel_ground_service_order_flow.sql`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/dto/OrderStatusEnum.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/entity/OrderEntity.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/dto/CreateOrderRequest.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/dto/OrderResponse.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/discover/controller/DiscoverController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionConsultantService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/order/OrderStatusEnumTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/order/controller/OrderControllerTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/discover/controller/DiscoverControllerTest.kt`

**Interfaces:**
- Consumes: `TravelGroundServicePricing.quote`, doctor/institution project config, selected `consultantId`.
- Produces: new-order status `PENDING_SERVICE_FEE`, flow `TRAVEL_GROUND_SERVICE_ONLY`, immutable money/rate snapshots, and explicit response entitlements.

- [ ] **Step 1: Write failing order creation and projection tests**

```kotlin
@Test
fun `new order snapshots one USD travel ground service fee without quantity or coupon`() {
    config.medicalListPrice = BigDecimal("1000.00")
    val response = orderService.createOrder(
        "user-1",
        CreateOrderRequest("project-1", "institution-project-1", "doctor-1", "consultant-1")
    )

    val saved = slot<OrderEntity>().captured
    assertEquals("TRAVEL_GROUND_SERVICE_ONLY", saved.paymentFlow)
    assertEquals(OrderStatusEnum.PENDING_SERVICE_FEE.value, saved.status)
    assertEquals(100_000L, saved.medicalListPriceMinor)
    assertEquals(4_000, saved.platformServiceRateBps)
    assertEquals(40_000L, saved.travelGroundServiceFeeMinor)
    assertEquals("USD", saved.currency)
    assertTrue(response.consultantBound)
    assertNull(response.consultantId)
    assertNull(response.consultantName)
    assertFalse(response.serviceMessagingEnabled)
}

@Test
fun `active service exposes the bound consultant`() {
    val response = OrderResponse.from(order(status = "SERVICE_ACTIVE", serviceActivatedAt = now))
    assertEquals("consultant-1", response.consultantId)
    assertEquals("测试咨询师", response.consultantName)
    assertTrue(response.serviceActivated)
    assertTrue(response.serviceMessagingEnabled)
}
```

Update request-construction tests so JSON/request DTO has no `quantity` or `userCouponId` fields.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests com.joysong.server.order.OrderServiceTest --tests com.joysong.server.order.OrderStatusEnumTest --tests com.joysong.server.order.controller.OrderControllerTest
```

Expected: compilation/assertion failures for the missing flow fields and states.

- [ ] **Step 3: Add the order schema and state model**

`V29__travel_ground_service_order_flow.sql` must add, without deleting data:

```sql
ALTER TABLE doctor_institution_project_configs
    ADD COLUMN medical_list_price DECIMAL(19,4) NOT NULL DEFAULT 0 AFTER institution_project_id;

ALTER TABLE doctor_project_change_requests
    ADD COLUMN medical_list_price DECIMAL(19,4) NULL AFTER price_suggestion,
    ADD COLUMN current_medical_list_price DECIMAL(19,4) NULL AFTER current_price;

ALTER TABLE orders
    ADD COLUMN payment_flow VARCHAR(40) NOT NULL DEFAULT 'LEGACY_MEDICAL' AFTER status,
    ADD COLUMN medical_list_price_minor BIGINT NULL AFTER total_amount_minor,
    ADD COLUMN platform_service_rate_bps INT NULL AFTER medical_list_price_minor,
    ADD COLUMN travel_ground_service_fee_minor BIGINT NULL AFTER platform_service_rate_bps,
    ADD COLUMN consultant_avatar VARCHAR(500) NULL AFTER consultant_name,
    ADD COLUMN service_activated_at DATETIME NULL AFTER payment_time,
    ADD CONSTRAINT chk_orders_platform_service_rate_bps
        CHECK (platform_service_rate_bps IS NULL OR platform_service_rate_bps BETWEEN 0 AND 10000),
    ADD CONSTRAINT chk_orders_travel_ground_service_fee
        CHECK (travel_ground_service_fee_minor IS NULL OR travel_ground_service_fee_minor > 0);
```

Add `PENDING_SERVICE_FEE`, `SERVICE_ACTIVE`, `REFUND_REVIEW`, and `REFUND_PROCESSING` to `OrderStatusEnum` with only the transitions defined by the spec. Do not delete legacy values in this migration.

- [ ] **Step 4: Implement new-order creation and server-side redaction**

Replace quantity/coupon/consultation/balance calculations in `createOrder` with the pricing quote. Persist `price` and `totalAmountMinor` as the platform fee for compatibility, and also persist the dedicated snapshots. Keep the internal consultant snapshot but make user response fields nullable. `consultantDetailsVisible` is true after activation for `SERVICE_ACTIVE`, `REFUND_REVIEW`, and `REFUND_PROCESSING`, then false after `REFUNDED`. Never expose private phone/email/standalone contact details; fulfillment communication stays inside the order conversation.

Add these response fields:

```kotlin
val paymentFlow: String,
val medicalListPriceMinor: Long?,
val platformServiceRateBps: Int?,
val travelGroundServiceFeeMinor: Long?,
val consultantBound: Boolean,
val serviceActivated: Boolean,
val consultantDetailsVisible: Boolean,
val serviceConversationReadable: Boolean,
val serviceMessagingEnabled: Boolean
```

When `consultantDetailsVisible` is true, expose the snapshotted `consultantId`, display name, avatar, and the order's institution ID/name. Before activation, test that all of these are absent. `serviceConversationReadable` remains true for `SERVICE_ACTIVE`, `REFUND_REVIEW`, `REFUND_PROCESSING`, and `REFUNDED`; `serviceMessagingEnabled` is true only for `SERVICE_ACTIVE`. Management projection continues to receive the internal consultant snapshot.

- [ ] **Step 5: Replace the quote endpoint**

Expose a server-calculated quote using the existing discover composition point:

```text
GET /api/discover/travel-ground-service-quote?doctorId={doctorId}&institutionProjectId={institutionProjectId}
```

Return exactly:

```json
{
  "currency": "USD",
  "medicalListPriceMinor": 100000,
  "platformServiceRateBps": 4000,
  "travelGroundServiceFeeMinor": 40000
}
```

The endpoint must reject a missing/zero doctor configuration instead of falling back to the old consultation fee.

- [ ] **Step 6: Run focused tests and commit**

Run the command from Step 2 plus `DiscoverControllerTest`. Expected: PASS.

```powershell
git add joysong-server/src/main/resources/db/migration/V29__travel_ground_service_order_flow.sql joysong-server/src/main/kotlin/com/joysong/server/order joysong-server/src/main/kotlin/com/joysong/server/discover joysong-server/src/test/kotlin/com/joysong/server/order
git commit -m "feat: create travel ground service orders"
```

### Task 3: Add the fixed service-fee payment contract and 30-minute attempt expiry

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/domain/PaymentDomain.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/entity/PaymentEntity.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/repository/PaymentRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/service/PaymentPersistenceService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/service/PaymentService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payment/service/PaymentAttemptExpiryService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payment/service/PaymentAttemptExpiryScheduler.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/controller/PaymentController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/controller/OrderController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderScheduledTasks.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/payment/service/PaymentAttemptExpiryServiceTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/payment/service/PaymentOrchestrationTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/payment/controller/PaymentControllerTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/order/controller/OrderControllerTest.kt`

**Interfaces:**
- Consumes: an order in `PENDING_SERVICE_FEE` with `travelGroundServiceFeeMinor`.
- Produces: `POST /api/orders/{id}/service-fee-payment-attempts`, `PaymentType.TRAVEL_GROUND_SERVICE_FEE`, `PaymentProvider.ALIPAY_PLUS`, and attempt-only expiry.

- [ ] **Step 1: Write failing payment-contract tests**

```kotlin
@Test
fun `service fee endpoint fixes type provider method and amount on the server`() {
    controller.createServiceFeePaymentAttempt("order-1", "idem-key-123", authentication)

    verify {
        paymentService.createPaymentSession(
            "order-1", "user-1",
            PaymentType.TRAVEL_GROUND_SERVICE_FEE,
            PaymentProvider.ALIPAY_PLUS,
            "ALIPAY_PLUS_CASHIER",
            "idem-key-123"
        )
    }
}

@Test
fun `prepared attempt expires in thirty minutes and uses service fee snapshot`() {
    val before = LocalDateTime.now()
    val payment = persistence.prepareAttempt(
        "order-1", "user-1", PaymentType.TRAVEL_GROUND_SERVICE_FEE,
        PaymentProvider.ALIPAY_PLUS, "ALIPAY_PLUS_CASHIER", "idem-key-123"
    )
    assertEquals(40_000L, payment.amountMinor)
    assertFalse(payment.expiresAt!!.isBefore(before.plusMinutes(30)))
    assertFalse(payment.expiresAt!!.isAfter(before.plusMinutes(30).plusSeconds(2)))
}
```

Also test that an existing `CREATED`, `REQUIRES_ACTION`, `PROCESSING`, or successful service-fee payment prevents a second attempt, while `FAILED`/`EXPIRED` permits a retry. When the provider returns an earlier expiry it wins; a later provider expiry must be clamped to the local 30-minute deadline. Provider unavailability is checked before persistence and produces zero local saves.

- [ ] **Step 2: Write the failing expiry test**

```kotlin
@Test
fun `expires eligible attempt without cancelling order`() {
    every { paymentRepository.findExpirableAttempts(any(), any()) } returns listOf(createdAttempt)

    expiryService.expireDueAttempts(now)

    verify { paymentRepository.save(match { it.id == createdAttempt.id && it.status == "EXPIRED" }) }
    verify(exactly = 0) { orderRepository.save(any()) }
}
```

- [ ] **Step 3: Run tests and verify RED**

```powershell
.\gradlew.bat test --tests com.joysong.server.payment.service.PaymentAttemptExpiryServiceTest --tests com.joysong.server.payment.service.PaymentOrchestrationTest --tests com.joysong.server.payment.controller.PaymentControllerTest --tests com.joysong.server.order.controller.OrderControllerTest
```

Expected: missing enum values, endpoint, and expiry service failures.

- [ ] **Step 4: Implement the fixed payment entry point**

Add `TRAVEL_GROUND_SERVICE_FEE` and `ALIPAY_PLUS`. In `prepareAttempt`, reject every other payment type for `TRAVEL_GROUND_SERVICE_ONLY`, reject an overlapping in-progress or successful service-fee payment, read `travelGroundServiceFeeMinor`, and set `expiresAt = createdAt.plusMinutes(30)` before calling the provider. When applying a provider result, retain `min(localExpiresAt, providerExpiresAt)`. Keep provider calls outside database transactions.

The endpoint has no request body and therefore cannot accept a client amount/provider/type. The generic `/{id}/payment-attempts` route must reject a `TRAVEL_GROUND_SERVICE_ONLY` order. Remove the public client-confirm route from `PaymentController`; redirect/resume can only trigger a server-side canonical query through the refresh path and can never submit success state.

- [ ] **Step 5: Replace order timeout with attempt timeout**

`PaymentAttemptExpiryService.expireDueAttempts(now)` updates only `CREATED` and `REQUIRES_ACTION` attempts. Leave `PROCESSING` for reconciliation/query. Preserve the existing legacy `PENDING_PAYMENT` order scheduler, but ensure it never selects `PENDING_SERVICE_FEE`; the new flow expires attempts only and never calls `OrderService.cancelExpiredPendingOrder`.

- [ ] **Step 6: Run focused tests and commit**

Run the command from Step 3. Expected: PASS.

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/payment joysong-server/src/main/kotlin/com/joysong/server/order/controller/OrderController.kt joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderScheduledTasks.kt joysong-server/src/test/kotlin/com/joysong/server/payment joysong-server/src/test/kotlin/com/joysong/server/order/controller/OrderControllerTest.kt
git commit -m "feat: add expiring service fee payment attempts"
```

### Task 4: Activate service exactly once and block medical payment paths

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/service/PaymentPersistenceService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/service/PaymentService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/controller/OrderController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/payment/service/PaymentOrchestrationTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/payment/service/PaymentServiceSafetyTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt`

**Interfaces:**
- Consumes: verified `ProviderPaymentResult(status = SUCCEEDED)` for a service-fee attempt.
- Produces: atomic `SERVICE_ACTIVE + serviceActivatedAt`, duplicate-success anomaly evidence, and no balance/settlement path for new orders.

- [ ] **Step 1: Write failing activation tests**

```kotlin
@Test
fun `verified service fee success atomically activates bound service`() {
    val result = persistence.applyProviderResult(
        "payment-1",
        ProviderPaymentResult(
            status = PaymentStatus.SUCCEEDED,
            providerPaymentId = "provider-1",
            amountMinor = 40_000,
            currency = "USD"
        )
    )

    assertEquals(PaymentStatus.SUCCEEDED.name, result.status)
    verify {
        orderRepository.save(match {
            it.status == OrderStatusEnum.SERVICE_ACTIVE.value && it.serviceActivatedAt != null
        })
    }
}

@Test
fun `new flow rejects balance payment`() {
    val error = assertThrows<IllegalArgumentException> {
        persistence.prepareAttempt(
            "order-1", "user-1", PaymentType.BALANCE,
            PaymentProvider.ALIPAY_PLUS, "ALIPAY_PLUS_CASHIER", "idem-key-123"
        )
    }
    assertEquals("MEDICAL_PAYMENT_NOT_SUPPORTED", error.message)
}
```

- [ ] **Step 2: Add the late duplicate-success test**

Create two provider attempts for one order. Apply success to the first, then apply a different provider success to the second. Assert the order activation timestamp is unchanged, the second payment remains recorded as real provider success with `failureCode = DUPLICATE_PAYMENT_SUCCEEDED`, and no second order transition is written.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
.\gradlew.bat test --tests com.joysong.server.payment.service.PaymentOrchestrationTest --tests com.joysong.server.payment.service.PaymentServiceSafetyTest --tests com.joysong.server.order.OrderServiceTest
```

- [ ] **Step 4: Implement atomic activation and safety guards**

Handle `TRAVEL_GROUND_SERVICE_FEE` in `completeSuccessfulPayment` by locking the order and validating `PENDING_SERVICE_FEE -> SERVICE_ACTIVE`. Never generate a verify code, balance timestamp, settlement, wallet entry, or coupon mutation. Reject verification, completion, settlement, and generic payment-type selection endpoints for `TRAVEL_GROUND_SERVICE_ONLY`.

For a second real success, persist the provider truth and anomaly code, enqueue it for manual refund through existing reconciliation/admin visibility, and skip order activation/logging.

- [ ] **Step 5: Run focused tests and commit**

Run the command from Step 3. Expected: PASS.

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/payment/service joysong-server/src/main/kotlin/com/joysong/server/order joysong-server/src/test/kotlin/com/joysong/server/payment/service joysong-server/src/test/kotlin/com/joysong/server/order
git commit -m "feat: activate travel ground service after payment"
```

### Task 5: Make service-fee refunds full-value and manual-review only

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundWorkflowPersistenceService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundItemPersistenceService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundExecutionService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/domain/RefundReasonCode.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/refund/service/RefundServiceTest.kt`

**Interfaces:**
- Consumes: `SERVICE_ACTIVE` order and its successful `TRAVEL_GROUND_SERVICE_FEE` payment.
- Produces: `REFUND_REVIEW -> REFUND_PROCESSING -> REFUNDED`, or `REFUND_REVIEW -> SERVICE_ACTIVE` on rejection/cancellation.

- [ ] **Step 1: Write failing manual-review tests**

```kotlin
@Test
fun `service fee refund request is pending manual review and does not call provider`() {
    val refund = refundService.applyRefund("order-1", "user-1", "行程取消", "不再来华")

    assertEquals("PENDING", refund.status)
    assertEquals(40_000L, refund.requestedAmountMinor)
    verify(exactly = 0) { refundExecutionService.execute(any()) }
    verify { orderRepository.save(match { it.status == "REFUND_REVIEW" }) }
}

@Test
fun `rejected review restores active service`() {
    refundService.adminUpdateStatus("refund-1", "REJECTED", "admin-1", "服务已安排")
    verify { orderRepository.save(match { it.status == "SERVICE_ACTIVE" }) }
}
```

Add tests asserting approval submits exactly the original successful service-fee amount, provider-processing remains `REFUND_PROCESSING`, and provider success changes the order to `REFUNDED`. When the order also has legacy `CONSULTATION_FEE` or `BALANCE` successes, refund-item preparation must select only the `TRAVEL_GROUND_SERVICE_FEE` payment and must create exactly one full-value item.

- [ ] **Step 2: Run the test and verify RED**

```powershell
.\gradlew.bat test --tests com.joysong.server.refund.service.RefundServiceTest
```

- [ ] **Step 3: Implement manual-review refund transitions**

Allow only `SERVICE_ACTIVE` to apply. Set `automatic = false`, derive amount from the successful service-fee payment/snapshot, set order status `REFUND_REVIEW`, and remove the automatic consultation-fee branch for the new flow. Approval changes order to `REFUND_PROCESSING` before provider execution; rejection/cancellation restores `SERVICE_ACTIVE`; provider success sets `REFUNDED`. Filter refund items by the explicit payment type before execution; never infer eligibility from every successful payment on the order.

Skip settlement reversal and coupon return for `TRAVEL_GROUND_SERVICE_ONLY`.

- [ ] **Step 4: Run the test and commit**

Run the command from Step 2. Expected: PASS.

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/refund joysong-server/src/test/kotlin/com/joysong/server/refund/service/RefundServiceTest.kt
git commit -m "feat: require manual review for service fee refunds"
```

### Task 6: Add order-scoped service conversations

**Files:**
- Create: `joysong-server/src/main/resources/db/migration/V30__order_service_conversations.sql`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/dm/entity/DmConversationEntity.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/dm/repository/DmConversationRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/dm/dto/DmDtos.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/dm/service/DmService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/dm/service/OrderServiceConversationService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/dm/controller/OrderServiceConversationController.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/dm/service/OrderServiceConversationServiceTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/dm/service/DmServiceTest.kt`

**Interfaces:**
- Consumes: order user, bound consultant, activation snapshot, and the separate conversation-read/send entitlements.
- Produces: `POST /api/orders/{orderId}/service-conversation` and per-operation order authorization.

- [ ] **Step 1: Write failing conversation creation tests**

```kotlin
@Test
fun `unpaid order cannot create service conversation`() {
    every { orderRepository.findById("order-1") } returns Optional.of(order(status = "PENDING_SERVICE_FEE"))
    assertEquals(
        "ORDER_SERVICE_NOT_ACTIVE",
        assertThrows<IllegalArgumentException> {
            service.getOrCreate("order-1", "user-1")
        }.message
    )
}

@Test
fun `active order creates one conversation from order participants`() {
    every { orderRepository.findById("order-1") } returns Optional.of(order(status = "SERVICE_ACTIVE"))
    every { conversationRepository.findByConversationTypeAndOrderId("ORDER_SERVICE", "order-1") } returns null

    val result = service.getOrCreate("order-1", "user-1")

    assertEquals("ORDER_SERVICE", result.conversationType)
    assertEquals("order-1", result.orderId)
    verify { conversationRepository.save(match {
        it.userAId == "user-1" && it.userBId == "consultant-1" && it.orderId == "order-1"
    }) }
}
```

- [ ] **Step 2: Write failing per-operation authorization tests**

Test list/send/read/delete with `ORDER_SERVICE`: unpaid rejects every operation; `REFUND_REVIEW`, `REFUND_PROCESSING`, and `REFUNDED` allow participants to list/read/mark-read existing history but reject sends; `SERVICE_ACTIVE` participants can create/list/send/read; unrelated users reject; order-service messages cannot be deleted. Existing `DIRECT` behavior remains unchanged. Add a uniqueness test proving the same user/consultant pair can have one `DIRECT` conversation and separate `ORDER_SERVICE` conversations for two different orders.

- [ ] **Step 3: Run tests and verify RED**

```powershell
.\gradlew.bat test --tests com.joysong.server.dm.service.OrderServiceConversationServiceTest --tests com.joysong.server.dm.service.DmServiceTest
```

- [ ] **Step 4: Add the conversation schema**

```sql
ALTER TABLE dm_conversations
    ADD COLUMN conversation_type VARCHAR(30) NOT NULL DEFAULT 'DIRECT' AFTER id,
    ADD COLUMN order_id VARCHAR(36) NULL AFTER conversation_type,
    DROP INDEX uk_dm_users,
    ADD COLUMN direct_pair_key VARCHAR(73)
        GENERATED ALWAYS AS (
            CASE WHEN conversation_type = 'DIRECT'
                 THEN CONCAT(LEAST(user_a_id, user_b_id), ':', GREATEST(user_a_id, user_b_id))
                 ELSE NULL END
        ) STORED,
    ADD UNIQUE KEY uk_dm_direct_pair (direct_pair_key),
    ADD UNIQUE KEY uk_dm_order_service_conversation (order_id),
    ADD KEY idx_dm_conversation_order (order_id),
    ADD CONSTRAINT fk_dm_conversation_order FOREIGN KEY (order_id) REFERENCES orders(id),
    ADD CONSTRAINT chk_dm_conversation_scope CHECK (
        (conversation_type = 'DIRECT' AND order_id IS NULL)
        OR (conversation_type = 'ORDER_SERVICE' AND order_id IS NOT NULL)
    );
```

- [ ] **Step 5: Implement order-derived creation and authorization**

The new controller passes only `orderId` and authenticated user ID. `OrderServiceConversationService` derives both participants from the order. `DmService` calls the access service before every order-scoped list/send/read/delete operation and applies read and send policies separately. Every existing pair lookup/creation for `DIRECT` must explicitly filter `conversationType = DIRECT`, so it cannot accidentally return an order-scoped conversation. `DmConversationResponse` adds nullable `orderId` and `conversationType`.

Do not expose this conversation through a target-ID creation path, and do not allow message deletion because the order conversation is a platform supervision record.

- [ ] **Step 6: Run tests and commit**

Run the command from Step 3. Expected: PASS.

```powershell
git add joysong-server/src/main/resources/db/migration/V30__order_service_conversations.sql joysong-server/src/main/kotlin/com/joysong/server/dm joysong-server/src/test/kotlin/com/joysong/server/dm
git commit -m "feat: gate order service conversations by payment"
```

### Task 7: Retire Stripe from the new flow and expose an unavailable-safe Alipay+ slot

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/provider/StripePaymentGateway.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/payment/provider/StripePaymentGatewayTest.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/provider/PaymentGateway.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/service/PaymentWebhookService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payment/service/StripeLegacyPaymentGuard.kt`
- Modify: `joysong-server/src/main/resources/application.yml`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/payment/service/PaymentServiceSafetyTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/payment/service/PaymentWebhookServiceTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/payment/service/StripeLegacyPaymentGuardTest.kt`

**Interfaces:**
- Consumes: `PaymentProvider.ALIPAY_PLUS` selected only by the fixed order endpoint.
- Produces: generic HTTPS redirect next action and explicit `PAYMENT_PROVIDER_UNAVAILABLE` when no live gateway bean is configured.

- [ ] **Step 1: Write failing provider-unavailable and raw-webhook tests**

```kotlin
@Test
fun `unconfigured alipay plus rejects before local attempt creation`() {
    val service = paymentServiceWith(PaymentGatewayRegistry(emptyList()))
    assertEquals(
        "PAYMENT_PROVIDER_UNAVAILABLE",
        assertThrows<PaymentProviderException> {
            service.createPaymentSession(
                "order-1", "user-1", PaymentType.TRAVEL_GROUND_SERVICE_FEE,
                PaymentProvider.ALIPAY_PLUS, "ALIPAY_PLUS_CASHIER", "idem-key-123"
            )
        }.errorCode
    )
    verify(exactly = 0) { paymentRepository.save(any()) }
}
```

Add a webhook test with form-encoded/raw text asserting the gateway receives the unchanged payload; `PaymentWebhookService` must not parse JSON before provider verification. Add legacy-guard tests proving application startup is blocked when Stripe compatibility is disabled while a Stripe payment still needs query/refund handling, and is allowed when no actionable Stripe liability exists.

- [ ] **Step 2: Run tests and verify RED**

```powershell
.\gradlew.bat test --tests com.joysong.server.payment.service.PaymentServiceSafetyTest --tests com.joysong.server.payment.service.PaymentWebhookServiceTest --tests com.joysong.server.payment.service.StripeLegacyPaymentGuardTest
```

- [ ] **Step 3: Disable Stripe for new runtime paths and preserve guarded legacy handling**

Remove `StripeClientSecret` from `PaymentNextAction`; retain `Redirect(url)` as the only first-release client action. Move all webhook-body interpretation behind `PaymentGateway.verifyWebhook`. Make `StripePaymentGateway` conditional on `payment.stripe.legacy-enabled=true`, default the flag to false, and label all remaining Stripe settings as legacy-only rather than required/default production configuration.

`StripeLegacyPaymentGuard` runs when legacy mode is disabled and performs a read-only repository check for Stripe payments/refund items that remain nonterminal, successful-but-refundable, processing, or otherwise need provider query. If any exist, fail startup with `STRIPE_LEGACY_PAYMENTS_REQUIRE_ADAPTER`; operators must explicitly enable the legacy adapter with credentials until liabilities are resolved. Do not delete or mutate historical rows. This task does not physically delete the adapter; a later removal is allowed only after the guard reports zero actionable records.

Do not create a fake Alipay+ bean. The registry absence is the correct state until signed Cashier Payment endpoints/certificates are configured.

- [ ] **Step 4: Run tests and commit**

Run the command from Step 2. Expected: PASS.

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/payment joysong-server/src/test/kotlin/com/joysong/server/payment joysong-server/src/main/resources/application.yml
git commit -m "refactor: retire stripe from new payment flow"
```

### Task 8: Simplify Flutter booking to one server quote

**Files:**
- Modify: `joysong-flutter/lib/features/booking/domain/booking_models.dart`
- Modify: `joysong-flutter/lib/features/booking/domain/booking_repository.dart`
- Modify: `joysong-flutter/lib/features/booking/data/booking_remote_data_source.dart`
- Modify: `joysong-flutter/lib/features/booking/data/booking_repository_impl.dart`
- Modify: `joysong-flutter/lib/features/booking/presentation/booking_controller.dart`
- Modify: `joysong-flutter/lib/features/booking/presentation/booking_page.dart`
- Test: `joysong-flutter/test/features/booking/booking_controller_test.dart`
- Test: `joysong-flutter/test/features/booking/booking_page_test.dart`
- Test: `joysong-flutter/test/features/booking/booking_remote_data_source_test.dart`

**Interfaces:**
- Consumes: quote endpoint and order-create contract from Tasks 1–2.
- Produces: consultant-bound order creation with no quantity/coupon fields and an explicit USD service-fee preview.

- [ ] **Step 1: Write failing model/data tests**

```dart
test('create order omits quantity and coupon fields', () {
  final json = CreateOrderCommand(
    projectId: 'project-1',
    institutionProjectId: 'ip-1',
    consultantId: 'consultant-1',
    doctorId: 'doctor-1',
    appointmentTime: DateTime(2026, 8, 22, 10),
  ).toJson();

  expect(json['consultantId'], 'consultant-1');
  expect(json.containsKey('quantity'), isFalse);
  expect(json.containsKey('userCouponId'), isFalse);
});

test('parses USD travel ground service quote', () async {
  final quote = TravelGroundServiceQuote.fromJson({
    'currency': 'USD',
    'medicalListPriceMinor': 100000,
    'platformServiceRateBps': 4000,
    'travelGroundServiceFeeMinor': 40000,
  });
  expect(quote.travelGroundServiceFeeMinor, 40000);
});
```

Keep quote money as integer minor units; do not parse `40000` through the existing decimal `Money` constructor. The data-source test must assert `discover/travel-ground-service-quote` receives the doctor and institution-project query parameters, and the formatter renders `USD 40000` minor units as `$400.00`.

- [ ] **Step 2: Write failing controller/widget tests**

Assert consultant remains required, selection submits the consultant ID, quote load uses doctor + institution project, and the page contains `旅游地接服务费`, `$400.00`, and `医疗费到院后直接向医院支付`. Assert no `面诊金`, `尾款`, quantity, or coupon control appears.

- [ ] **Step 3: Run Flutter tests and verify RED**

```powershell
flutter test test/features/booking/booking_controller_test.dart test/features/booking/booking_page_test.dart test/features/booking/booking_remote_data_source_test.dart
```

- [ ] **Step 4: Implement the simplified booking contract**

Replace `getConsultationFee`, coupon load, and discount preview with:

```dart
Future<TravelGroundServiceQuote> getTravelGroundServiceQuote({
  required String doctorId,
  required String institutionProjectId,
});
```

Keep public consultant selection. Remove quantity and coupon fields from `CreateOrderCommand`. The page may show the medical list price as reference only; the prominent payable amount is the server quote.

- [ ] **Step 5: Run tests and commit**

Run the command from Step 3. Expected: PASS.

```powershell
git add joysong-flutter/lib/features/booking joysong-flutter/test/features/booking
git commit -m "feat: simplify booking to ground service fee"
```

### Task 9: Update Flutter order and payment models, page, and trusted redirect

**Files:**
- Modify: `joysong-flutter/lib/features/orders/domain/order_models.dart`
- Modify: `joysong-flutter/lib/features/orders/domain/payment_models.dart`
- Modify: `joysong-flutter/lib/features/orders/domain/orders_repository.dart`
- Modify: `joysong-flutter/lib/features/orders/data/orders_remote_data_source.dart`
- Modify: `joysong-flutter/lib/features/orders/data/orders_repository_impl.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/order_detail_page.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/payment_controller.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/payment_page.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/payment_action_launcher.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/payment_strings.dart`
- Test: `joysong-flutter/test/features/orders/money_and_models_test.dart`
- Test: `joysong-flutter/test/features/orders/payment_models_test.dart`
- Test: `joysong-flutter/test/features/orders/orders_remote_data_source_test.dart`
- Test: `joysong-flutter/test/features/orders/order_detail_page_test.dart`
- Test: `joysong-flutter/test/features/orders/payment_controller_test.dart`
- Test: `joysong-flutter/test/features/orders/payment_page_test.dart`
- Test: `joysong-flutter/test/features/orders/payment_action_launcher_test.dart`

**Interfaces:**
- Consumes: explicit order entitlements and fixed payment endpoint.
- Produces: unpaid placeholder, active consultant card, USD Alipay+ redirect payment, and attempt-expiry retry.

- [ ] **Step 1: Write failing parsing and entitlement tests**

```dart
test('unpaid order remains redacted even when paidAmount is nonzero', () {
  final order = Order.fromJson(unpaidJson(
    paidAmount: '1.00',
    consultantId: null,
    consultantName: null,
    serviceActivated: false,
    consultantDetailsVisible: false,
    serviceMessagingEnabled: false,
  ));
  expect(order.serviceActivated, isFalse);
  expect(order.consultantDetailsVisible, isFalse);
  expect(order.canOpenServiceConversation, isFalse);
});

test('parses travel fee and alipay plus', () {
  expect(PaymentType.fromWire('TRAVEL_GROUND_SERVICE_FEE'), PaymentType.travelGroundServiceFee);
  expect(PaymentProvider.fromWire('ALIPAY_PLUS'), PaymentProvider.alipayPlus);
});
```

- [ ] **Step 2: Write failing page and redirect-policy tests**

Assert unpaid detail shows `支付旅游地接服务费后可查看地接资料并沟通`, with no consultant name/chat button. Active detail shows consultant name and chat action. Assert no consultation/balance/quantity UI.

For redirect policy, make `MobilePaymentActionLauncher` accept a testable `allowedRedirectHosts` constructor value, delete the hard-coded Stripe host, inject `{'cashier.alipayplus.com'}`, and assert HTTPS exact/subdomain policy accepts the intended host while rejecting HTTP, `alipay://`, `cashier.alipayplus.com.evil.test`, and an empty production allowlist. Add explicit assertions that reachable new-flow strings contain neither `Stripe` nor `checkout.stripe.com`.

- [ ] **Step 3: Run Flutter tests and verify RED**

```powershell
flutter test test/features/orders/money_and_models_test.dart test/features/orders/payment_models_test.dart test/features/orders/orders_remote_data_source_test.dart test/features/orders/order_detail_page_test.dart test/features/orders/payment_controller_test.dart test/features/orders/payment_page_test.dart test/features/orders/payment_action_launcher_test.dart
```

- [ ] **Step 4: Implement fixed payment and entitlement rendering**

Replace the repository call with:

```dart
Future<PaymentAttempt> createTravelGroundServicePaymentAttempt(
  String orderId, {
  required String idempotencyKey,
});
```

The data source uses the existing idempotent POST facility for `orders/$orderId/service-fee-payment-attempts` without a body. Remove client payment-type/provider/method selection and the PayPal confirm/resume branch. Use only `REDIRECT` next action, format amount solely from server attempt `amountMinor/currency`, and show an explicit loading/unavailable state when no attempt exists instead of falling back to consultation/balance fields. Refresh on resume and let server `EXPIRED` determine terminal expiry. A retry may request a new attempt only after the server reports the old attempt `FAILED`/`EXPIRED`; the server remains authoritative for overlap prevention.

- [ ] **Step 5: Run tests and commit**

Run the command from Step 3. Expected: PASS.

```powershell
git add joysong-flutter/lib/features/orders joysong-flutter/test/features/orders
git commit -m "feat: add alipay plus service fee checkout UI"
```

### Task 10: Open the order-scoped conversation from Flutter

**Files:**
- Modify: `joysong-flutter/lib/features/messaging/domain/messaging_models.dart`
- Modify: `joysong-flutter/lib/features/messaging/domain/messaging_repository.dart`
- Modify: `joysong-flutter/lib/features/messaging/data/messaging_remote_data_source.dart`
- Modify: `joysong-flutter/lib/features/messaging/data/messaging_repository_impl.dart`
- Modify: `joysong-flutter/lib/features/shell/presentation/app_shell.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/order_detail_page.dart`
- Create: `joysong-flutter/test/features/messaging/messaging_remote_data_source_test.dart`
- Test: `joysong-flutter/test/features/shell/app_shell_navigation_test.dart`
- Test: `joysong-flutter/test/features/orders/order_detail_page_test.dart`

**Interfaces:**
- Consumes: `POST /api/orders/{orderId}/service-conversation` and `serviceMessagingEnabled`.
- Produces: payment-gated navigation to the existing direct-message page using an order-scoped conversation ID.

- [ ] **Step 1: Write failing repository and navigation tests**

```dart
test('creates service conversation by order id without target user id', () async {
  await dataSource.createOrderServiceConversation('order-1');
  expect(client.lastPath, 'orders/order-1/service-conversation');
  expect(client.lastBody, isNull);
});
```

In the order detail widget test, tap the chat action on an active order and assert the callback receives `order-1`. On an unpaid order the action is absent. In refund review/processing/refunded states, an existing-history action remains visible as read-only while the compose/send control is absent.

In `app_shell_navigation_test.dart`, assert the active-order action calls only `orders/{orderId}/service-conversation`, never generic `dm/conversations` with a consultant target ID, and then opens the existing thread page.

- [ ] **Step 2: Run tests and verify RED**

```powershell
flutter test test/features/messaging/messaging_remote_data_source_test.dart test/features/shell/app_shell_navigation_test.dart test/features/orders/order_detail_page_test.dart
```

- [ ] **Step 3: Implement order-scoped conversation navigation**

Add:

```dart
Future<DmConversation> createOrderServiceConversation(String orderId);
```

Parse `conversationType` and nullable `orderId`. The shell creates/gets the conversation only after the server-authorized order action, then opens the existing thread page. Pass read-only/send-enabled entitlement into the thread so refund-state history cannot compose a message. Do not call `createDmConversation(consultantId)` for fulfillment.

- [ ] **Step 4: Run tests and commit**

Run the command from Step 2. Expected: PASS.

```powershell
git add joysong-flutter/lib/features/messaging joysong-flutter/lib/features/shell/presentation/app_shell.dart joysong-flutter/lib/features/orders/presentation/order_detail_page.dart joysong-flutter/test/features/messaging joysong-flutter/test/features/shell/app_shell_navigation_test.dart joysong-flutter/test/features/orders/order_detail_page_test.dart
git commit -m "feat: open payment gated service conversations"
```

### Task 11: Update admin configuration and product documentation

**Files:**
- Modify: `joysong-admin/src/pages/DoctorProjectConfigsPage.tsx`
- Modify: `joysong-admin/src/pages/DoctorProjectConfigsPage.test.tsx`
- Modify: `joysong-admin/src/pages/ProjectCollaborationPage.tsx`
- Create: `joysong-admin/src/pages/ProjectCollaborationPage.test.tsx`
- Modify: `joysong-admin/src/pages/ProjectRequestsPage.tsx`
- Modify: `joysong-admin/src/pages/ProjectRequestsPage.test.tsx`
- Modify: `joysong-admin/src/pages/OrdersPage.tsx`
- Modify: `joysong-admin/src/pages/PaymentsPage.tsx`
- Modify: `joysong-admin/src/pages/RefundsPage.tsx`
- Create: `joysong-admin/src/pages/PaymentOperationsPages.test.tsx`
- Modify: `docs/订单支付与身份切换需求.md`
- Modify: `docs/支付开发与云服务器部署指南.md`
- Modify: `docs/FLUTTER_API_CONTRACT.md`
- Modify: `design/TRAVEL_GROUND_SERVICE_PAYMENT_WORKFLOW.puml`

**Interfaces:**
- Consumes: backend config/order/payment/refund contracts from Tasks 1–7.
- Produces: doctor/admin configuration UI and documentation with only the new user-facing payment semantics.

- [ ] **Step 1: Write failing admin UI tests**

```tsx
it('edits medical list price while platform rate is read only', async () => {
  render(<DoctorProjectConfigsPage />);
  await user.click(await screen.findByRole('button', { name: /编辑/ }));
  expect(screen.getByRole('spinbutton', { name: '医疗套餐优惠前金额（USD）' })).toHaveValue('1000');
  expect(screen.getByRole('spinbutton', { name: '平台服务比例' })).toBeDisabled();
  expect(screen.queryByText('面诊金')).not.toBeInTheDocument();
  expect(screen.queryByText('尾款')).not.toBeInTheDocument();
});
```

Add doctor-flow tests proving `ProjectCollaborationPage` submits `medicalListPrice` in `PROFILE_UPDATE`, presents the platform rate as read-only, and never sends a platform-rate override; add reviewer-page coverage for the proposed/current list-price snapshot. Add table/page assertions for `旅游地接服务费`, `USD`, `REFUND_REVIEW`, and `REFUND_PROCESSING`, with no new-flow split/wallet, artificial verification, balance, or settlement action.

- [ ] **Step 2: Run admin tests and verify RED**

```powershell
npm test -- src/pages/DoctorProjectConfigsPage.test.tsx src/pages/ProjectCollaborationPage.test.tsx src/pages/ProjectRequestsPage.test.tsx src/pages/PaymentOperationsPages.test.tsx
```

- [ ] **Step 3: Implement the admin fields and labels**

The editable request sends `doctorId`, `institutionProjectId`, and `medicalListPrice`; it must not send `consultationFee`, `commissionRate`, or `institutionRate`. Platform rate is loaded from the existing policy endpoint and rendered read-only; do not call `calculateDoctorRate` or render editable `SplitRateFields` for the new flow. The doctor `PROFILE_UPDATE` UI and institution/admin review UI carry the proposed/current medical list-price snapshots from Task 1. Preserve legacy rows only as read-only compatibility data.

`OrdersPage` adds the new statuses and prevents new-flow rows from entering legacy manual verification/completion/settlement actions. `PaymentsPage` and `RefundsPage` format integer `amountMinor` using the explicit currency and visibly label USD rather than treating minor units as decimal dollars.

- [ ] **Step 4: Rewrite current product/API/deployment docs**

Document the fixed endpoint, USD fee formula, attempt expiry, verified activation, order-scoped messaging, manual refund, and hospital-direct medical payment. Mark old Stripe/consultation/balance flows as superseded; do not leave operational instructions that suggest enabling Stripe or charging medical balance.

Keep `design/TRAVEL_GROUND_SERVICE_PAYMENT_WORKFLOW.puml` synchronized if implementation naming differs from the approved design.

- [ ] **Step 5: Run admin tests and commit**

```powershell
npm test -- src/pages/DoctorProjectConfigsPage.test.tsx src/pages/ProjectCollaborationPage.test.tsx src/pages/ProjectRequestsPage.test.tsx src/pages/PaymentOperationsPages.test.tsx
npm run lint
npm run build
git add joysong-admin docs design
git commit -m "docs: align admin and payment guidance with service fee flow"
```

### Task 12: Verify migrations in an isolated MySQL database and run final focused suites

**Files:**
- Create: `joysong-server/src/test/kotlin/com/joysong/server/migration/TravelGroundServicePaymentMigrationTest.kt`
- Modify only if failures prove a defect: files changed in Tasks 1–11.

**Interfaces:**
- Consumes: V29/V30 and all implementation contracts.
- Produces: evidence that a fresh empty database migrates and the cross-stack focused suites pass.

- [ ] **Step 1: Write the migration integration test**

Use Testcontainers with an explicit isolated database name derived from this worktree and tag the test `mysql-integration` so it runs through `mysqlIntegrationTest`:

```kotlin
@Testcontainers
class TravelGroundServicePaymentMigrationTest {
    companion object {
        private const val DATABASE = "myapp_worktree_travel_ground_service_payment"
        @Container
        val mysql = MySQLContainer("mysql:8.0.36").withDatabaseName(DATABASE)
    }

    @Test
    fun `fresh database has travel service order and conversation schema`() {
        println("MYSQL_HOST=${mysql.host}:${mysql.getMappedPort(3306)}")
        println("MYSQL_DATABASE=$DATABASE")
        Flyway.configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .load().migrate()

        assertColumn("orders", "travel_ground_service_fee_minor")
        assertColumn("orders", "service_activated_at")
        assertColumn("dm_conversations", "conversation_type")
        assertUniqueIndex("dm_conversations", "uk_dm_order_service_conversation")
    }
}
```

The helpers query `information_schema.columns/statistics` against `DATABASE`; they do not inspect SQL source text.

In the same isolated container, add an upgrade scenario: migrate to V28, insert one representative legacy order and one direct-message conversation, migrate to latest, then assert the order row still exists with `payment_flow = 'LEGACY_MEDICAL'`, its legacy status/amounts are unchanged, the direct conversation still exists, and no delete/update cleanup was applied to business rows.

- [ ] **Step 2: Run the migration test**

Before Flyway runs, confirm the test output resolves to:

```text
MYSQL_DATABASE=myapp_worktree_travel_ground_service_payment
```

Run:

```powershell
.\gradlew.bat mysqlIntegrationTest --tests com.joysong.server.migration.TravelGroundServicePaymentMigrationTest
```

Expected: PASS against a newly created empty Testcontainers database. Do not invoke Flyway clean/drop against any other name.

- [ ] **Step 3: Run the focused backend suite once**

```powershell
.\gradlew.bat test --tests com.joysong.server.order.OrderServiceTest --tests com.joysong.server.order.OrderStatusEnumTest --tests com.joysong.server.order.controller.OrderControllerTest --tests com.joysong.server.payment.service.PaymentOrchestrationTest --tests com.joysong.server.payment.service.PaymentServiceSafetyTest --tests com.joysong.server.payment.service.PaymentAttemptExpiryServiceTest --tests com.joysong.server.payment.service.PaymentWebhookServiceTest --tests com.joysong.server.refund.service.RefundServiceTest --tests com.joysong.server.dm.service.OrderServiceConversationServiceTest --tests com.joysong.server.dm.service.DmServiceTest
```

- [ ] **Step 4: Run Flutter verification once**

```powershell
flutter test test/features/booking test/features/orders test/features/messaging test/features/shell/app_shell_navigation_test.dart
flutter analyze
```

- [ ] **Step 5: Run admin verification once**

```powershell
npm test -- src/pages/DoctorProjectConfigsPage.test.tsx src/pages/ProjectCollaborationPage.test.tsx src/pages/ProjectRequestsPage.test.tsx src/pages/PaymentOperationsPages.test.tsx
npm run lint
npm run build
```

- [ ] **Step 6: Perform semantic source checks**

Search user-facing new-flow files for `面诊金`, `尾款`, `STRIPE`, `quantity`, passport/travel-filing fields, and generic `createDmConversation(consultantId)` fulfillment calls. Inspect every match; legacy-only backend compatibility code may remain, but new controllers, DTOs, Flutter pages, and admin pages must contain none.

- [ ] **Step 7: Commit migration test/final corrections**

```powershell
git add joysong-server/src/test/kotlin/com/joysong/server/migration/TravelGroundServicePaymentMigrationTest.kt
git commit -m "test: verify travel service payment migrations"
```

## Self-Review Record

- Spec coverage: doctor-owned pricing, consultant binding/redaction, attempt overlap/expiry, verified activation, medical-payment exclusion, manual full refund, refund-state read-only conversations, Stripe new-flow retirement with a legacy safety gate, unavailable-safe Alipay+, Flutter, admin, documentation, fresh-database migration, and legacy-row upgrade are each assigned to a task.
- Scope exclusion: no task creates passport, flight, hotel, or travel-filing data/UI.
- Type consistency: `TRAVEL_GROUND_SERVICE_ONLY`, `TRAVEL_GROUND_SERVICE_FEE`, `ALIPAY_PLUS`, `PENDING_SERVICE_FEE`, `SERVICE_ACTIVE`, `REFUND_REVIEW`, `REFUND_PROCESSING`, `ORDER_SERVICE`, and `serviceActivatedAt` are used consistently across tasks.
- Test order: every production behavior is preceded by a focused failing test and a verified RED step.
- Path audit: every `Modify`/existing `Test` path was checked against the worktree; new tests and migrations are explicitly marked `Create`, and component commands name their required working directory in Global Constraints.
