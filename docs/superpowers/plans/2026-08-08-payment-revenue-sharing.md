# Payment Revenue Sharing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Stripe 订单支付基础上，实现医生与机构的可审计分账、冻结、退款反向冲正和可重试结算。

**Architecture:** 支付成功只记入平台清分账户，并按订单金额快照生成不可变的 `allocation`；订单完成/风控放行后，结算任务通过支付渠道的 Connect/Transfer 能力向已实名收款方转账。所有状态变化通过幂等事件和双重记账分录完成，配置提案必须由医生与机构双方确认后才可用于新订单。

**Tech Stack:** Spring Boot/Kotlin、现有订单与 Stripe Webhook、关系型数据库迁移、定时任务/Outbox、JUnit/集成测试。

## Global Constraints

- 金额使用最小货币单位 `Long`/`BigDecimal`，禁止 `Double` 累加；币种从订单快照读取，当前默认 USD。
- 客户端金额、回跳页和重复 Webhook 都不构成资金事实；只接受验签后的渠道事件或服务端查单。
- 已生效的分账配置不可修改；新配置只影响创建于生效时间之后的订单。
- 面诊金 `CONSULTATION_FEE` 全额计入平台收入，不生成医生或机构分账；只有尾款 `BALANCE` 按订单锁定的分账配置生成清分快照。
- 尾款金额由服务端订单快照计算，用户无需额外确认尾款金额；支付成功仅由 Stripe Webhook 或服务端查单确认。
- 用户确认完成使订单进入 `COMPLETED`；从该时点起仅冻结尾款分账 30 天，期满且无退款/争议才允许结算。
- 分账总额 + 平台手续费 + 渠道费调整项必须守恒，任何舍入余数明确归平台。
- 退款、拒付、撤销必须生成反向分录，不能直接改余额字段。

---

### Task 1: 固化分账配置与订单清分快照

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payment/revenue/entity/RevenueShareConfig.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payment/revenue/entity/RevenueShareAllocation.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/payment/revenue/repository/*Repository.kt`
- Modify: 现有机构项目分账配置/提案 service 与订单创建 service
- Test: `joysong-server/src/test/kotlin/com/joysong/server/payment/revenue/RevenueShareSnapshotTest.kt`

**Interfaces:**
- `RevenueShareConfigService.getEffective(projectId, at): EffectiveShareConfig`
- `RevenueShareAllocationService.snapshot(orderId, paymentStage, grossMinor, configVersion): List<AllocationLine>`

- [ ] **Step 1: Write the failing test**：覆盖双方确认前不可生效、按版本读取、医生/机构/平台比例合计 10000bp、余数归平台；面诊金只生成平台收入分录且不生成 allocation，尾款生成医生、机构和平台 allocation。
- [ ] **Step 2: Run test to verify it fails**：`./gradlew test --tests '*RevenueShareSnapshotTest'`，预期实体/服务不存在。
- [ ] **Step 3: Write minimal implementation**：增加配置版本、状态、双方确认时间和订单清分快照表；订单创建时锁定生效配置版本，只有尾款支付成功时写 allocation，唯一键为 `order_id + payment_stage + beneficiary_type`；面诊金成功时只写平台收入台账。
- [ ] **Step 4: Run test to verify it passes**：再次运行上述测试并验证数据库唯一约束。
- [ ] **Step 5: Commit**：`git commit -m "feat: snapshot revenue share allocations"`

### Task 2: 建立双重记账台账与余额冻结

**Files:**
- Create: `.../ledger/entity/LedgerAccount.kt`, `LedgerEntry.kt`, `LedgerBatch.kt`
- Create: `.../ledger/service/LedgerService.kt`
- Create: `.../payment/revenue/service/SettlementHoldService.kt`
- Test: `.../ledger/LedgerServiceTest.kt`

- [ ] **Step 1: Write the failing test**：面诊金支付成功只生成平台收入分录；尾款支付成功生成平台、医生和机构分录且借贷平衡；重复事件不重复入账；尾款冻结期间可查询但不可结算。
- [ ] **Step 2: Run test to verify it fails**：`./gradlew test --tests '*LedgerServiceTest'`，预期失败。
- [ ] **Step 3: Implement**：所有金额以不可变 entry/batch 保存，业务幂等键为 `provider_event_id` 或 `payment_id + stage`；余额由分录聚合或物化表加版本号维护。
- [ ] **Step 4: Run test to verify it passes**。
- [ ] **Step 5: Commit**：`git commit -m "feat: add double entry ledger for settlements"`

### Task 3: Stripe Connect 收款方与结算执行器

**Files:**
- Create: `.../payment/provider/StripeConnectGateway.kt`
- Create: `.../payment/revenue/service/SettlementService.kt`
- Create: `.../payment/revenue/job/SettlementRetryJob.kt`
- Modify: Stripe 配置、Webhook handler、管理员订单查询 DTO
- Test: `.../payment/revenue/SettlementServiceTest.kt`, provider contract tests

- [ ] **Step 1: Write failing tests**：未完成 KYC 不可结算；同一 allocation 只创建一次 transfer；渠道超时进入 `RETRYABLE`；永久失败进入人工复核。
- [ ] **Step 2: Run tests and verify failure**。
- [ ] **Step 3: Implement**：保存 beneficiary 的 Stripe Connected Account ID、KYC 状态和冻结原因；用户确认完成后记录 `settlement_eligible_at = completed_at + 30 days`，到期且无退款/争议后创建 transfer；使用 Idempotency-Key=`allocationId`。
- [ ] **Step 4: Run tests and verify pass**。
- [ ] **Step 5: Commit**：`git commit -m "feat: settle revenue shares through stripe connect"`

### Task 4: 退款、拒付与对账

**Files:**
- Modify: 现有退款 service 与 Stripe webhook controller
- Create: `.../payment/revenue/service/RevenueShareReversalService.kt`
- Create: `.../payment/reconciliation/*`
- Test: `.../payment/revenue/RevenueShareReversalTest.kt`, reconciliation tests

- [ ] **Step 1: Write failing tests**：部分退款按原 allocation 比例反向冲正；已结算金额形成应收负债而不是透支；重复 webhook、乱序 webhook 最终一致。
- [ ] **Step 2: Run tests and verify failure**。
- [ ] **Step 3: Implement**：退款先冲未结算冻结余额；已转账部分调用 Stripe Transfer Reversal，失败则挂起人工处理；每日导入 Stripe balance/transfer/payout 报表，按 payment/transfer/event ID 对账。
- [ ] **Step 4: Run tests and verify pass**。
- [ ] **Step 5: Commit**：`git commit -m "feat: reverse and reconcile revenue shares"`

### Task 5: 管理端、审计与上线开关

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/*`
- Create: 分账明细、结算批次、失败原因和审计 DTO/VO
- Modify: `docs/支付开发与云服务器部署指南.md`, `.env.example`
- Test: admin authorization and end-to-end webhook tests

- [ ] **Step 1: Write failing tests**：只有平台管理员可强制重试/解冻；医生和机构只能查看自己的 allocation 与结算状态；审计记录包含操作者、前后状态和原因。
- [ ] **Step 2: Run tests and verify failure**。
- [ ] **Step 3: Implement**：增加只读查询、人工复核/重试接口、Outbox 监控指标；增加 `REVENUE_SHARE_MODE=shadow|hold|live`，默认 `shadow`。
- [ ] **Step 4: Run tests and verify pass**。
- [ ] **Step 5: Commit**：`git commit -m "feat: expose settlement operations and rollout controls"`

## Rollout Order

先上线 `shadow`（只生成快照和台账，不转账），再启用 `hold`（真实冻结、人工结算），最后按单一机构灰度 `live`。上线前必须完成 Stripe Connect 账户/KYC、30 天退款观察期演练、财务科目映射和日终对账演练。

## Self-Review

- 覆盖了配置生效、支付入账、冻结、转账、退款/拒付、对账、权限和灰度开关。
- 所有跨服务副作用均有幂等键和可重试状态；未把渠道回调与本地订单状态简单绑定。
- 实际落地时需先确认业务法务主体、收款方类型、税务与结算周期；这些决定 Stripe Connect 账户类型和资金流合规方案。
