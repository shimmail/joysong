# 预约订单服务费与开发环境自动支付实施计划

> **实施方式：** 在 `codex/booking-order-dev-autopay` 分支、`D:\code\kotlin\joysong\.worktrees\booking-order-dev-autopay` worktree 中按 TDD 分批实施；每批先验证失败测试，再做最小实现并提交。生产支付行为保持不变。

**目标：** 保留旅游地接服务费及订单不可变金额快照；把服务费定价从结算分账策略中拆开；开发环境创建订单后通过现有模拟 Alipay+ 网关和真实支付状态机自动完成支付，并让 Flutter 直接展示支付后的订单详情。

**边界：** `OrderService.createOrder` 只创建预约订单，不调用支付服务。开发自动支付位于 Web/应用编排层，在订单事务提交后执行。支付、退款、结算、钱包、对账及其表和迁移均保留；本次不拆 Gradle 模块、不改 Admin 业务、不实现生产 `PAY_LATER`。

**关键约束：** 默认及生产配置均关闭自动支付；仅 `dev & !prod` 且模拟网关、创建后自动支付两个开关同时开启时生效。自动支付沿用 `PaymentService` 的金额/币种校验、支付记录、幂等、锁、状态转换、激活、补偿及恢复路径。失败时订单仍为可重试的 `PENDING_SERVICE_FEE`，接口返回最新订单而不是伪造成功。

---

## Task 1：公共 Money 与独立服务费定价策略

**Files:**

- Create: `joysong-server/src/main/kotlin/com/joysong/server/common/money/Money.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/config/TravelGroundServicePricingProperties.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/order/service/TravelGroundServiceFeeRatePolicy.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/domain/PaymentDomain.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/TravelGroundServicePricing.kt`
- Modify: all production imports of `com.joysong.server.payment.domain.Money`
- Move/modify: `joysong-server/src/test/kotlin/com/joysong/server/payment/domain/MoneyTest.kt` to `joysong-server/src/test/kotlin/com/joysong/server/common/money/MoneyTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/order/service/TravelGroundServicePricingTest.kt`
- Modify: tests that directly construct `TravelGroundServicePricing`
- Modify: `joysong-server/src/main/resources/application.yml`

- [ ] Add RED tests proving the service-fee rate can differ from settlement `order.split.platform-rate`, while USD minor-unit conversion and HALF_UP calculation remain exact.
- [ ] Add a RED configuration-validation test for out-of-range or over-precise service-fee rates.
- [ ] Move `Money` into `common.money` without a payment-domain compatibility facade; update imports mechanically.
- [ ] Add `order.pricing.travel-ground-service.service-fee-rate`, defaulting to `40.00`, and make `TravelGroundServicePricing` depend only on the new rate policy.
- [ ] Keep the public compatibility name `platformServiceRateBps`, exact revision format `travel-ground-service-rate:0.400000`, USD, and `roundHalfUp(listMinor * rateBps / 10000)` unchanged.
- [ ] Run only `MoneyTest`, `TravelGroundServicePricingTest`, `OrderSplitRatePolicyTest`, then the nearest directly affected service tests.
- [ ] Commit: `refactor(order): isolate travel service fee pricing`

## Task 2：持久化完整订单定价快照

**Files:**

- Create: `joysong-server/src/main/resources/db/migration/V35__snapshot_order_pricing_policy_revision.sql`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/entity/OrderEntity.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/dto/OrderResponse.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/migration/BaselineMigrationIntegrationTest.kt`

- [ ] Add RED assertions that a created order stores and returns currency, medical list price, service-fee rate, service fee, and pricing policy revision from the same quote.
- [ ] Add a nullable revision column for legacy medical orders; backfill existing travel-service orders deterministically from their saved basis points and require new travel-service snapshots to have a revision.
- [ ] Update the frozen B33 baseline only through V35 (do not edit B33); update the baseline migration expectation from V34 through V35.
- [ ] Print and verify the Testcontainers host/database before migration. Expected database: `myapp_worktree_booking_order_dev_autopay`.
- [ ] Run `OrderServiceTest`, then `mysqlIntegrationTest --tests com.joysong.server.migration.BaselineMigrationIntegrationTest` against a fresh isolated MySQL database.
- [ ] Commit: `feat(order): persist pricing policy snapshot`

## Task 3：开发环境创建后自动支付编排

**Files:**

- Create: `joysong-server/src/main/kotlin/com/joysong/server/order/application/DevelopmentOrderAutoPaymentService.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/order/application/DevelopmentOrderAutoPaymentServiceTest.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/controller/OrderController.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/order/controller/OrderControllerTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/config/ProductionProfileTest.kt`
- Modify: `joysong-server/src/main/resources/application.yml`
- Modify: `joysong-server/src/main/resources/application-dev.example.yml`
- Modify: `joysong-server/src/main/resources/application-prod.yml`
- Modify: `joysong-server/.env.example`

- [ ] Add RED tests for the fixed payment contract: `TRAVEL_GROUND_SERVICE_FEE`, `ALIPAY_PLUS`, `ALIPAY_PLUS_CASHIER`, and stable key `dev-order-autopay-{orderId}`.
- [ ] Add RED tests proving provider failure/unknown outcome is logged and returned as a non-success result without throwing through order creation; repeated invocation uses the same key and therefore cannot create a second logical attempt.
- [ ] Add RED context/config tests proving the bean is absent unless both dev-only flags are enabled and remains absent under `prod` even if environment variables try to enable it.
- [ ] Implement the dev-only service as a thin adapter over `PaymentService.createPaymentSession`; do not duplicate payment persistence or state-transition logic.
- [ ] Inject it into `OrderController` through an optional provider. Call only after `OrderService.createOrder` returns (and its transaction has committed), then always re-read the canonical order when dev auto-pay is enabled.
- [ ] Keep HTTP order creation successful on auto-pay failure and return the latest pending order, so clients can safely retry payment without creating another order.
- [ ] Run `DevelopmentOrderAutoPaymentServiceTest`, `OrderControllerTest`, `SimulatedAlipayPlusPaymentGatewayTest`, `PaymentOrchestrationTest`, and `ProductionProfileTest`.
- [ ] Commit: `feat(payment): auto-pay dev booking orders`

## Task 4：Flutter 直接展示服务端返回的最终订单

**Files:**

- Modify: `joysong-flutter/lib/features/orders/domain/order_models.dart`
- Modify: `joysong-flutter/lib/features/orders/data/orders_remote_data_source.dart`
- Modify: `joysong-flutter/lib/features/orders/presentation/orders_controller.dart`
- Modify: `joysong-flutter/lib/features/shell/presentation/app_shell.dart`
- Modify: `joysong-flutter/test/features/orders/money_and_models_test.dart`
- Modify: `joysong-flutter/test/features/orders/orders_controller_test.dart`
- Modify: `joysong-flutter/test/features/shell/app_shell_navigation_test.dart`
- Modify: relevant order/booking fixtures

- [ ] Add RED model coverage for `pricingPolicyRevision`.
- [ ] Add RED controller coverage proving a matching `initialOrder` is immediately available and a mismatched ID is rejected.
- [ ] Add RED shell navigation coverage where the detail refresh is paused: after booking, the backend-returned `SERVICE_ACTIVE` order detail is already visible, the pay button is absent, and no `PaymentPage` route opens.
- [ ] Pass the created order into `OrderDetailController(initialOrder: ...)`; retain the background detail/status-log refresh for canonical updates.
- [ ] Keep Flutter independent of environment/profile flags: it renders only the server-returned order status. Production pending orders retain the existing manual payment entry.
- [ ] Run formatter, the three closest Flutter test files, then `flutter analyze` once.
- [ ] Commit: `feat(flutter): open post-payment order detail`

## Task 5：契约、UML、验证与独立审查

**Files:**

- Modify: `design/TRAVEL_GROUND_SERVICE_PAYMENT_WORKFLOW.puml`
- Modify: `docs/支付开发与云服务器部署指南.md`
- Modify: `docs/FLUTTER_API_CONTRACT.md`

- [ ] Update UML to show the separate service-fee policy, immutable revision snapshot, post-commit dev auto-pay branch, canonical re-read, and failure fallback to `PENDING_SERVICE_FEE`.
- [ ] Document the two independent dev flags and state clearly that production still requires a real provider and never simulates or auto-succeeds.
- [ ] Run the smallest affected backend and Flutter suites after final edits; do not rerun a previously passing command unless covered code changed.
- [ ] Run at most one backend full `test`; stop and report if it exceeds 10 minutes. Run one final Flutter analyze/test verification within the same rule.
- [ ] Inspect `git diff --check`, worktree status, migration history, and all commits.
- [ ] Request independent task reviews during implementation and a final branch review; fix findings with focused tests.
- [ ] Commit: `docs(payment): document dev booking auto-pay`

## Risks and rollback

- **Production safety:** three gates apply—`dev & !prod`, simulated gateway enabled, auto-pay-on-create enabled. Base and production values are false. Removing the new auto-pay flag or service cleanly restores the old flow.
- **Unknown provider result:** the existing payment state machine retains `PROCESSING` and recovery metadata. The order remains recoverable; no second key is generated.
- **Transaction boundary:** controller/application orchestration starts only after the proxied order transaction returns. Payment provider calls remain outside order creation and outside long database transactions.
- **Migration:** V35 is additive. Rollback means reverting application code and leaving the nullable column harmless; no destructive down migration is planned.
- **Scope:** full order-leaf cleanup, finance deletion, Gradle module split, Admin redesign, and production pay-later modes are explicit follow-up work.
