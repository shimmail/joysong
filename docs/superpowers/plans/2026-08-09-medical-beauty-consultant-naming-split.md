# 医美顾问全端命名与分账配置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将真人 `CONSULTANT` 的全端产品名称统一为“医美顾问”，移除管理端专用直绑入口，并让项目分账配置从服务端平台比例实时派生且校验医生剩余比例。

**Architecture:** 后端新增单一 `OrderSplitRatePolicy`，统一提供平台比例、四方比例计算和保存前校验，管理员直存、双方提案与最终结算全部复用。管理端通过只读策略接口获取平台比例，用共享表单组件和基点计算显示医美顾问与医生比例；Flutter、服务端提示和正式业务文档只改产品文案，内部 `CONSULTANT` 与 `consultant*` 契约保持不变。开发阶段只运行当前任务的聚焦 RED/GREEN 测试，全部功能完成后再通过单一验证门统一执行三端完整编译、静态检查、全量测试和 MySQL 集成测试。

**Tech Stack:** Kotlin 1.9、Spring Boot 3.2、JUnit 5、MockK、React 19、TypeScript 6、Ant Design 6、Vite 8、Vitest 4、Flutter/Dart、Gradle、npm

**Source Design:** `docs/superpowers/specs/2026-08-09-medical-beauty-consultant-naming-split-design.md`

## Global Constraints

- 真人职业身份的唯一产品名称是“医美顾问”；需要机构范围时使用“该机构的医美顾问”。
- 保留内部 `CONSULTANT`、`consultantId`、`consultantName`、`consultantAmount`、`consultantRate`、`commissionRate`、`CONSULTANT_PROOF` 和 `/consultants`。
- 保留 `user_roles` 与 `institution_memberships` 两层模型；同一医美顾问可以属于多家机构。
- 只删除管理端“添加咨询师”专用按钮、弹窗及其前端状态；保留通用“新增任职关系”和后端 `POST /api/admin/identity/consultants`。
- 平台比例必须来自 `OrderSplitProperties`，管理端不得硬编码 40%。
- 医生比例只读派生：`100 - 平台 - 合作医疗机构 - 医美顾问`，不得进入保存请求。
- 医美顾问比例继续通过兼容字段 `commissionRate` / `commission_rate` 保存。
- 比例位于 `0～100` 且最多两位小数；四方合计为 100%，医生比例为 0% 时允许保存。
- 分账提案仍只由医生与合作医疗机构确认，医美顾问不成为确认方。
- 不新增钱包、提现、支付渠道或实际打款流程。
- 不修改 AI 医美助手人格。
- 不修改 `V1__init_schema.sql`、`B1__init_schema.sql` 或任何已执行 Flyway migration；本功能不新增 migration。
- Task 1～7 只运行各自列出的聚焦 RED/GREEN 测试，不在中途重复执行完整 Gradle suite、`npm run build`、`npm run lint`、`flutter analyze` 或三端全量测试；这些命令统一由 Task 8 的最终验证门执行。

---

## File Structure

### New backend units

- `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderSplitRatePolicy.kt`：平台配置验证、四方比例解析和默认比例的唯一实现。
- `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/OrderSplitPolicyController.kt`：向已认证分账参与者返回当前平台比例。
- `joysong-server/src/test/kotlin/com/joysong/server/order/service/OrderSplitRatePolicyTest.kt`：纯比例规则测试。
- `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/OrderSplitPolicyControllerTest.kt`：策略接口 envelope、实际配置和访问上下文测试。
- `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/AdminOrderControllerTest.kt`：管理员直存保存前拒绝非法四方比例。
- `joysong-server/src/test/kotlin/com/joysong/server/order/service/SplitConfigProposalServiceTest.kt`：双方提案保存前拒绝非法四方比例。

### New admin units

- `joysong-admin/src/types/splitRates.ts`：`OrderSplitPolicy` 与兼容分账字段类型。
- `joysong-admin/src/utils/splitRates.ts`：以百分比基点计算医生比例并生成统一校验错误。
- `joysong-admin/src/hooks/useOrderSplitPolicy.ts`：只读加载 `/admin/order-split-policy`，不提供猜测默认值。
- `joysong-admin/src/hooks/useOrderSplitPolicy.test.ts`：策略加载成功、失败和卸载后不回写状态的 Hook 测试。
- `joysong-admin/src/components/SplitRateFields.tsx`：两个分账表单复用的平台、机构、医美顾问和医生比例字段。
- `joysong-admin/src/test/setup.ts`：Vitest DOM 断言初始化。
- `joysong-admin/src/utils/splitRates.test.ts`：基点计算和边界测试。
- `joysong-admin/src/components/SplitRateFields.test.tsx`：实时派生与超限校验测试。
- `joysong-admin/src/pages/DoctorProjectConfigsPage.test.tsx`：直接配置请求兼容性测试。
- `joysong-admin/src/pages/SplitConfigProposalsPage.test.tsx`：提案请求兼容性测试。
- `joysong-admin/src/pages/IdentityManagementPage.test.tsx`：专用直绑入口移除回归测试。

### Existing files modified in place

- Backend: `AdminOrderController.kt`, `SplitConfigProposalService.kt`, `SettlementService.kt`, `DoctorInstitutionProjectConfigEntity.kt`, `SettlementEntity.kt`, `InstitutionConsultantService.kt`, `OrderService.kt`, `SecurityConfig.kt` and focused tests.
- Admin: `DoctorProjectConfigsPage.tsx`, `SplitConfigProposalsPage.tsx`, `SettlementsPage.tsx`, `IdentityManagementPage.tsx`, `package.json`, `package-lock.json`, `vite.config.ts`.
- Flutter: identity model, profile, booking data/domain/controller/page, fixtures and focused tests.
- Docs: current API contract and active design/requirements documents; historical implementation plans and audit snapshots remain unchanged.

---

### Task 1: Create the shared backend split-rate policy and read-only endpoint

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderSplitRatePolicy.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/OrderSplitPolicyController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt:45-61`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/order/service/OrderSplitRatePolicyTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/OrderSplitPolicyControllerTest.kt`

**Interfaces:**
- Consumes: `OrderSplitProperties.platformRate` and `OrderSplitProperties.institutionRate`.
- Produces: `OrderSplitRatePolicy.currentPlatformRate(): BigDecimal`, `defaultInstitutionRate(): BigDecimal`, `resolve(BigDecimal, BigDecimal): OrderSplitRates`, and `defaults(): OrderSplitRates`.
- Produces: authenticated `GET /api/admin/order-split-policy` returning `BaseResponse<OrderSplitPolicyView>` with `platformRate` only.

- [ ] **Step 1: Write failing pure policy tests**

Create tests with exact boundary assertions:

```kotlin
class OrderSplitRatePolicyTest {
    private fun policy(platform: String = "40.00", institution: String = "40.00") =
        OrderSplitRatePolicy(OrderSplitProperties().apply {
            platformRate = BigDecimal(platform)
            institutionRate = BigDecimal(institution)
        })

    @Test
    fun `platform 40 institution 35 consultant 10 leaves doctor 15`() {
        val rates = policy().resolve(BigDecimal("35.00"), BigDecimal("10.00"))
        assertEquals(BigDecimal("40.00"), rates.platformRate)
        assertEquals(BigDecimal("15.00"), rates.doctorRate)
    }

    @Test
    fun `total exactly 100 allows zero doctor rate`() {
        assertEquals(
            BigDecimal("0.00"),
            policy().resolve(BigDecimal("40.00"), BigDecimal("20.00")).doctorRate
        )
    }

    @Test
    fun `total over 100 is rejected`() {
        val error = assertThrows<IllegalArgumentException> {
            policy().resolve(BigDecimal("40.00"), BigDecimal("20.01"))
        }
        assertEquals("平台、合作医疗机构和医美顾问分账比例合计不能超过 100%", error.message)
    }

    @Test
    fun `platform override is the source of truth`() {
        assertEquals(
            BigDecimal("12.50"),
            policy(platform = "37.50").resolve(BigDecimal("40.00"), BigDecimal("10.00")).doctorRate
        )
    }

    @Test
    fun `input with more than two decimals is rejected`() {
        assertThrows<IllegalArgumentException> {
            policy().resolve(BigDecimal("39.999"), BigDecimal("10.00"))
        }
    }
}
```

- [ ] **Step 2: Run the policy test to verify RED**

Run from `joysong-server`:

```powershell
.\gradlew.bat test --tests com.joysong.server.order.service.OrderSplitRatePolicyTest --no-daemon --console=plain
```

Expected: compilation fails because `OrderSplitRatePolicy` and `OrderSplitRates` do not exist.

- [ ] **Step 3: Implement the shared policy**

Create the focused component:

```kotlin
@Component
class OrderSplitRatePolicy(
    private val properties: OrderSplitProperties
) {
    fun currentPlatformRate(): BigDecimal = properties.platformRate.also {
        checkConfiguredRate("平台分账比例", it)
    }

    fun defaultInstitutionRate(): BigDecimal = properties.institutionRate.also {
        checkConfiguredRate("合作医疗机构分账比例", it)
    }

    fun defaults(): OrderSplitRates = resolve(defaultInstitutionRate(), BigDecimal.ZERO)

    fun resolve(institutionRate: BigDecimal, consultantRate: BigDecimal): OrderSplitRates {
        val platformRate = currentPlatformRate()
        requireInputRate("合作医疗机构分账比例", institutionRate)
        requireInputRate("医美顾问分账比例", consultantRate)
        val doctorRate = HUNDRED - platformRate - institutionRate - consultantRate
        require(doctorRate >= BigDecimal.ZERO) {
            "平台、合作医疗机构和医美顾问分账比例合计不能超过 100%"
        }
        return OrderSplitRates(platformRate, institutionRate, consultantRate, doctorRate)
    }

    private fun checkConfiguredRate(label: String, rate: BigDecimal) {
        check(rate >= BigDecimal.ZERO && rate <= HUNDRED) { "$label 须在 0～100 之间" }
        check(rate.stripTrailingZeros().scale() <= 2) { "$label 最多保留两位小数" }
    }

    private fun requireInputRate(label: String, rate: BigDecimal) {
        require(rate >= BigDecimal.ZERO && rate <= HUNDRED) { "$label 须在 0～100 之间" }
        require(rate.stripTrailingZeros().scale() <= 2) { "$label 最多保留两位小数" }
    }

    private companion object {
        val HUNDRED = BigDecimal("100.00")
    }
}

data class OrderSplitRates(
    val platformRate: BigDecimal,
    val institutionRate: BigDecimal,
    val consultantRate: BigDecimal,
    val doctorRate: BigDecimal
)
```

- [ ] **Step 4: Run the policy test to verify GREEN**

Run the command from Step 2. Expected: all `OrderSplitRatePolicyTest` tests pass.

- [ ] **Step 5: Write the failing policy controller test**

Create a direct controller test that proves the response reads the configured rate and requires a valid management actor:

```kotlin
@Test
fun `policy endpoint returns configured platform rate in BaseResponse`() {
    val authentication = mockk<Authentication>()
    val access = mockk<ManagementAccessService>()
    val actor = ManagementActor("admin-1", true, setOf("ADMIN"), null, emptySet(), emptySet(), emptySet())
    every { access.actor(authentication) } returns actor
    val properties = OrderSplitProperties().apply { platformRate = BigDecimal("37.50") }

    val response = OrderSplitPolicyController(OrderSplitRatePolicy(properties), access)
        .get(authentication)

    assertEquals(200, response.code)
    assertEquals(BigDecimal("37.50"), response.data?.platformRate)
    verify(exactly = 1) { access.actor(authentication) }
}
```

Add a second test where `access.actor(authentication)` throws `AccessDeniedException`; assert the exception propagates and the response is never created. This preserves the existing doctor/institution representative/admin management boundary instead of widening the endpoint to every authenticated account.

- [ ] **Step 6: Run the controller test to verify RED**

```powershell
.\gradlew.bat test --tests com.joysong.server.admin.controller.OrderSplitPolicyControllerTest --no-daemon --console=plain
```

Expected: compilation fails because the controller and response type do not exist.

- [ ] **Step 7: Implement the authenticated policy endpoint and security matcher**

Create:

```kotlin
@RestController
@RequestMapping("/api/admin/order-split-policy")
class OrderSplitPolicyController(
    private val splitRatePolicy: OrderSplitRatePolicy,
    private val managementAccessService: ManagementAccessService
) {
    @GetMapping
    fun get(authentication: Authentication): BaseResponse<OrderSplitPolicyView> {
        managementAccessService.actor(authentication)
        return BaseResponse.success(OrderSplitPolicyView(splitRatePolicy.currentPlatformRate()))
    }
}

data class OrderSplitPolicyView(val platformRate: BigDecimal)
```

Add `"/api/admin/order-split-policy"` to the authenticated `HttpMethod.GET` matcher before the final `/api/admin/**` admin-only matcher. Do not make it public; `managementAccessService.actor(authentication)` rejects authenticated accounts without doctor, institution representative, or admin management context.

- [ ] **Step 8: Run focused backend tests**

```powershell
.\gradlew.bat test `
  --tests com.joysong.server.order.service.OrderSplitRatePolicyTest `
  --tests com.joysong.server.admin.controller.OrderSplitPolicyControllerTest `
  --no-daemon --console=plain
```

Expected: all focused tests pass.

- [ ] **Step 9: Commit Task 1**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderSplitRatePolicy.kt joysong-server/src/main/kotlin/com/joysong/server/admin/controller/OrderSplitPolicyController.kt joysong-server/src/main/kotlin/com/joysong/server/config/SecurityConfig.kt joysong-server/src/test/kotlin/com/joysong/server/order/service/OrderSplitRatePolicyTest.kt joysong-server/src/test/kotlin/com/joysong/server/admin/controller/OrderSplitPolicyControllerTest.kt
git commit -m "feat: centralize order split rate policy"
```

---

### Task 2: Enforce the shared split policy in direct config, proposals, and settlement

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminOrderController.kt:28-196`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/SplitConfigProposalService.kt:16-216`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/settlement/service/SettlementService.kt:25-119`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/entity/DoctorInstitutionProjectConfigEntity.kt:9-45`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/settlement/entity/SettlementEntity.kt:35-72`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/AdminOrderControllerTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/order/service/SplitConfigProposalServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/settlement/SettlementServiceTest.kt`

**Interfaces:**
- Consumes: `OrderSplitRatePolicy` from Task 1.
- Produces: both save entry points reject invalid totals before repository/JDBC writes.
- Produces: settlement snapshots use `OrderSplitRates` and keep doctor amount as the monetary remainder.

- [ ] **Step 1: Write failing integration-at-boundary tests for both save entry points**

In `AdminOrderControllerTest`, directly instantiate the controller with an admin actor, real `OrderSplitRatePolicy` configured to 40%, and strict repository mocks. Call:

```kotlin
val request = UpsertConfigRequest(
    doctorId = "doctor-1",
    institutionProjectId = "project-1",
    consultationFee = BigDecimal.ZERO,
    commissionRate = BigDecimal("20.01"),
    institutionRate = BigDecimal("40.00")
)
val error = assertThrows<IllegalArgumentException> {
    controller.upsertConfig(authentication, request)
}
assertEquals("平台、合作医疗机构和医美顾问分账比例合计不能超过 100%", error.message)
verify(exactly = 0) { configRepository.save(any()) }
```

In `SplitConfigProposalServiceTest`, use strict `JdbcTemplate` and `ManagementAccessService` mocks so any database access fails the test, then assert the same error for:

```kotlin
service.submit(
    actor,
    SplitConfigProposalRequest(
        doctorId = "doctor-1",
        institutionProjectId = "project-1",
        commissionRate = BigDecimal("20.01"),
        institutionRate = BigDecimal("40.00")
    )
)
```

- [ ] **Step 2: Run both boundary tests to verify RED**

```powershell
.\gradlew.bat test `
  --tests com.joysong.server.admin.controller.AdminOrderControllerTest `
  --tests com.joysong.server.order.service.SplitConfigProposalServiceTest `
  --no-daemon --console=plain
```

Expected: tests fail because the two production constructors do not consume `OrderSplitRatePolicy` and old validation allows the inputs.

- [ ] **Step 3: Replace duplicated validation with the shared policy**

Add `OrderSplitRatePolicy` to both constructors. In `AdminOrderController.upsertConfig`, retain ID and nonnegative fee validation, then call:

```kotlin
splitRatePolicy.resolve(
    institutionRate = request.institutionRate,
    consultantRate = request.commissionRate
)
```

In `SplitConfigProposalService.validateRates`, use:

```kotlin
private fun validateRates(request: SplitConfigProposalRequest) {
    require(request.consultationFee >= BigDecimal.ZERO) { "面诊金不能为负数" }
    splitRatePolicy.resolve(
        institutionRate = request.institutionRate,
        consultantRate = request.commissionRate
    )
}
```

Keep persisted and API field names unchanged. Update comments and logs so `commissionRate` is described as “医美顾问分账比例”, not doctor commission.

- [ ] **Step 4: Run both boundary tests to verify GREEN**

Run the command from Step 2. Expected: both tests pass and invalid inputs reach no write operation.

- [ ] **Step 5: Extend settlement tests before changing settlement production code**

Update the existing snapshot error expectation to:

```kotlin
assertEquals("订单分账信息不完整，缺少机构、机构项目、医美顾问或医生快照", error.message)
```

Add tests that capture `SettlementEntity` passed to `settlementRepository.save`:

```kotlin
@Test
fun `settlement stores medical beauty consultant share and derived doctor remainder`() {
    val order = completeOrder().copy(price = BigDecimal("100.01"))
    val config = DoctorInstitutionProjectConfigEntity(
        doctorId = order.doctorId,
        institutionProjectId = order.institutionProjectId,
        institutionRate = BigDecimal("35.00"),
        commissionRate = BigDecimal("10.00")
    )
    every { orderRepository.findById(order.id) } returns Optional.of(order)
    every { settlementRepository.findByOrderId(order.id) } returns null
    every { configRepository.findByDoctorIdAndInstitutionProjectId(order.doctorId, order.institutionProjectId) } returns config
    every { settlementRepository.save(any()) } answers { firstArg() }

    val result = service.saveSettlement(order.id)

    assertEquals(BigDecimal("40.00"), result.platformRate)
    assertEquals(BigDecimal("35.00"), result.institutionRate)
    assertEquals(BigDecimal("10.00"), result.consultantRate)
    assertEquals(BigDecimal("15.00"), result.doctorRate)
    assertEquals(result.totalAmount, result.platformAmount + result.institutionAmount + result.consultantAmount + result.doctorAmount)
}
```

Add a no-config test asserting platform 40%, institution 40%, medical beauty consultant 0%, and doctor 20%.

- [ ] **Step 6: Run settlement tests to verify RED**

```powershell
.\gradlew.bat test --tests com.joysong.server.settlement.SettlementServiceTest --no-daemon --console=plain
```

Expected: compilation or assertions fail until `SettlementService` consumes the shared policy and user-facing text is updated.

- [ ] **Step 7: Route settlement through `OrderSplitRatePolicy`**

Replace the constructor dependency on `OrderSplitProperties` with `OrderSplitRatePolicy`. Resolve the four rates exactly once:

```kotlin
val config = doctorInstitutionProjectConfigRepository
    .findByDoctorIdAndInstitutionProjectId(order.doctorId, order.institutionProjectId)
val rates = splitRatePolicy.resolve(
    institutionRate = config?.institutionRate ?: splitRatePolicy.defaultInstitutionRate(),
    consultantRate = config?.commissionRate ?: BigDecimal.ZERO
)
```

Use `rates.platformRate`, `rates.institutionRate`, `rates.consultantRate`, and `rates.doctorRate` in the settlement snapshot. Keep amount computation unchanged:

```kotlin
val platformAmount = totalAmount.multiply(rates.platformRate).divide(HUNDRED, SCALE, RoundingMode.HALF_UP)
val institutionAmount = totalAmount.multiply(rates.institutionRate).divide(HUNDRED, SCALE, RoundingMode.HALF_UP)
val consultantAmount = totalAmount.multiply(rates.consultantRate).divide(HUNDRED, SCALE, RoundingMode.HALF_UP)
val doctorAmount = totalAmount - platformAmount - institutionAmount - consultantAmount
```

Change settlement comments, logs and exception messages from “咨询师/佣金” to “医美顾问/医美顾问分账”. Do not edit migration comments.

- [ ] **Step 8: Run the complete Task 2 focused suite**

```powershell
.\gradlew.bat test `
  --tests com.joysong.server.order.service.OrderSplitRatePolicyTest `
  --tests com.joysong.server.admin.controller.AdminOrderControllerTest `
  --tests com.joysong.server.order.service.SplitConfigProposalServiceTest `
  --tests com.joysong.server.settlement.SettlementServiceTest `
  --no-daemon --console=plain
```

Expected: all focused tests pass.

- [ ] **Step 9: Commit Task 2**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminOrderController.kt joysong-server/src/main/kotlin/com/joysong/server/order/service/SplitConfigProposalService.kt joysong-server/src/main/kotlin/com/joysong/server/settlement/service/SettlementService.kt joysong-server/src/main/kotlin/com/joysong/server/order/entity/DoctorInstitutionProjectConfigEntity.kt joysong-server/src/main/kotlin/com/joysong/server/settlement/entity/SettlementEntity.kt joysong-server/src/test/kotlin/com/joysong/server/admin/controller/AdminOrderControllerTest.kt joysong-server/src/test/kotlin/com/joysong/server/order/service/SplitConfigProposalServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/settlement/SettlementServiceTest.kt
git commit -m "fix: enforce medical beauty consultant split rates"
```

---

### Task 3: Add the admin test foundation and precise split-rate utilities

**Files:**
- Modify: `joysong-admin/package.json`
- Modify: `joysong-admin/package-lock.json`
- Modify: `joysong-admin/vite.config.ts`
- Create: `joysong-admin/src/test/setup.ts`
- Create: `joysong-admin/src/types/splitRates.ts`
- Create: `joysong-admin/src/utils/splitRates.ts`
- Create: `joysong-admin/src/utils/splitRates.test.ts`

**Interfaces:**
- Consumes: Node 22 and Vite 8 already used by the workspace.
- Produces: `OrderSplitPolicy`, `calculateDoctorRate`, and `validateSplitRates` for Task 4.
- Produces: `npm test` / `npm run test:watch` and jsdom-based component test setup.

- [ ] **Step 1: Install the test dependencies and add scripts**

From `joysong-admin` run:

```powershell
npm install --save-dev vitest@4.1.10 jsdom @testing-library/react @testing-library/jest-dom @testing-library/user-event
```

Add scripts:

```json
"test": "vitest run",
"test:watch": "vitest"
```

Change `vite.config.ts` to import `defineConfig` from `vitest/config`, retain the existing React and proxy configuration, and add:

```ts
test: {
  environment: 'jsdom',
  setupFiles: './src/test/setup.ts',
  clearMocks: true,
},
```

Create setup:

```ts
import '@testing-library/jest-dom/vitest';
```

- [ ] **Step 2: Write the failing split-rate utility tests**

Create exact assertions:

```ts
import { describe, expect, it } from 'vitest';
import { calculateDoctorRate, validateSplitRates } from './splitRates';

describe('split rate calculations', () => {
  it('derives the doctor remainder in basis points', () => {
    expect(calculateDoctorRate(40, 35, 10)).toBe(15);
    expect(calculateDoctorRate(40, 39.99, 10.01)).toBe(10);
  });

  it('allows a zero doctor share', () => {
    expect(calculateDoctorRate(40, 40, 20)).toBe(0);
    expect(validateSplitRates(40, 40, 20)).toBeUndefined();
  });

  it('rejects a negative doctor share', () => {
    expect(validateSplitRates(40, 40, 20.01)).toBe(
      '平台、合作医疗机构和医美顾问分账比例合计不能超过 100%',
    );
  });

  it('fails closed when the platform policy is absent', () => {
    expect(calculateDoctorRate(undefined, 40, 10)).toBeNull();
    expect(validateSplitRates(undefined, 40, 10)).toBe('分账策略尚未加载');
  });
});
```

- [ ] **Step 3: Run the utility test to verify RED**

```powershell
npm test -- src/utils/splitRates.test.ts
```

Expected: test fails because the module and functions do not exist.

- [ ] **Step 4: Implement types and basis-point helpers**

Create types:

```ts
export interface OrderSplitPolicy {
  platformRate: number;
}

export interface SplitConfigRates {
  institutionRate: number;
  /** Compatibility field whose product meaning is medical beauty consultant share. */
  commissionRate: number;
}
```

Create the pure utility:

```ts
type OptionalRate = number | null | undefined;

const toBasisPoints = (rate: number) => Math.round((rate + Number.EPSILON) * 100);
const fromBasisPoints = (basisPoints: number) => basisPoints / 100;
const present = (rate: OptionalRate): rate is number => rate != null && Number.isFinite(rate);

export function calculateDoctorRate(
  platformRate: OptionalRate,
  institutionRate: OptionalRate,
  consultantRate: OptionalRate,
): number | null {
  if (!present(platformRate) || !present(institutionRate) || !present(consultantRate)) return null;
  return fromBasisPoints(
    10_000 - toBasisPoints(platformRate) - toBasisPoints(institutionRate) - toBasisPoints(consultantRate),
  );
}

export function validateSplitRates(
  platformRate: OptionalRate,
  institutionRate: OptionalRate,
  consultantRate: OptionalRate,
): string | undefined {
  if (!present(platformRate)) return '分账策略尚未加载';
  if (!present(institutionRate)) return '请输入合作医疗机构分成比例';
  if (!present(consultantRate)) return '请输入医美顾问分账比例';
  const entries: Array<[string, number]> = [
    ['平台分账比例', platformRate],
    ['合作医疗机构分成比例', institutionRate],
    ['医美顾问分账比例', consultantRate],
  ];
  for (const [label, rate] of entries) {
    if (rate < 0 || rate > 100) return `${label}须在 0～100 之间`;
    if (Math.abs(rate - fromBasisPoints(toBasisPoints(rate))) > 1e-9) return `${label}最多保留两位小数`;
  }
  return calculateDoctorRate(platformRate, institutionRate, consultantRate)! < 0
    ? '平台、合作医疗机构和医美顾问分账比例合计不能超过 100%'
    : undefined;
}
```

- [ ] **Step 5: Run the focused utility test**

```powershell
npm test -- src/utils/splitRates.test.ts
```

Expected: the focused utility test passes. Defer full admin tests, TypeScript/Vite build and lint to Task 8.

- [ ] **Step 6: Commit Task 3**

```powershell
git add joysong-admin/package.json joysong-admin/package-lock.json joysong-admin/vite.config.ts joysong-admin/src/test/setup.ts joysong-admin/src/types/splitRates.ts joysong-admin/src/utils/splitRates.ts joysong-admin/src/utils/splitRates.test.ts
git commit -m "test: add admin split rate test foundation"
```

---

### Task 4: Show server policy, medical beauty consultant share, and derived doctor share in admin

**Files:**
- Create: `joysong-admin/src/hooks/useOrderSplitPolicy.ts`
- Create: `joysong-admin/src/hooks/useOrderSplitPolicy.test.ts`
- Create: `joysong-admin/src/components/SplitRateFields.tsx`
- Create: `joysong-admin/src/components/SplitRateFields.test.tsx`
- Modify: `joysong-admin/src/pages/DoctorProjectConfigsPage.tsx:1-321`
- Create: `joysong-admin/src/pages/DoctorProjectConfigsPage.test.tsx`
- Modify: `joysong-admin/src/pages/SplitConfigProposalsPage.tsx:1-246`
- Create: `joysong-admin/src/pages/SplitConfigProposalsPage.test.tsx`
- Modify: `joysong-admin/src/pages/SettlementsPage.tsx:60-69`

**Interfaces:**
- Consumes: `GET /admin/order-split-policy` and helpers from Task 3.
- Produces: shared form fields that only register `institutionRate` and `commissionRate`; platform and doctor values are never submitted.

- [ ] **Step 1: Write failing hook and shared-field behavior tests**

In `useOrderSplitPolicy.test.ts`, use `renderHook`, mock `api.get('/admin/order-split-policy')`, and assert successful loading returns `platformRate: 40`. Add a rejected-request case that returns no policy plus the API error, and a deferred-request case that unmounts before resolution and produces no post-unmount state update.

In the component test use an Ant Design `Form` harness and assert:

```ts
expect(screen.getByText('平台分账比例')).toBeInTheDocument();
expect(screen.getByText('医美顾问分账比例')).toBeInTheDocument();
expect(screen.getByText('医生分账比例')).toBeInTheDocument();
expect(screen.getByRole('spinbutton', { name: '医生分账比例' })).toHaveValue('15');
```

Set institution 40 and medical beauty consultant 20.01, then assert the exact total error. Call `form.validateFields()` for a valid form and assert:

```ts
expect(await form.validateFields()).toEqual({
  institutionRate: 35,
  commissionRate: 10,
});
```

This assertion proves the read-only platform and doctor values have no `name` and cannot leak into request values.

- [ ] **Step 2: Run focused tests to verify RED**

```powershell
npm test -- src/hooks/useOrderSplitPolicy.test.ts src/components/SplitRateFields.test.tsx
```

Expected: tests fail because the hook and shared component do not exist.

- [ ] **Step 3: Implement the fail-closed policy hook**

Use this state contract:

```ts
export function useOrderSplitPolicy() {
  const [policy, setPolicy] = useState<OrderSplitPolicy>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  useEffect(() => {
    let active = true;
    api.get('/admin/order-split-policy')
      .then((response) => {
        if (!active) return;
        setPolicy(getData<OrderSplitPolicy>(response));
        setError(undefined);
      })
      .catch((cause) => {
        if (!active) return;
        setPolicy(undefined);
        setError(getApiErrorMessage(cause, '分账策略加载失败'));
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  return { policy, loading, error };
}
```

Do not set a 40% fallback.

- [ ] **Step 4: Implement reusable split fields**

`SplitRateFields` accepts the Ant Design form instance plus policy state. It uses `Form.useWatch('institutionRate', form)` and `Form.useWatch('commissionRate', form)`, calls `calculateDoctorRate`, and renders:

```tsx
<Form.Item label="平台分账比例">
  <InputNumber aria-label="平台分账比例" value={platformRate} disabled addonAfter="%" style={{ width: '100%' }} />
</Form.Item>
<Form.Item
  name="institutionRate"
  label="合作医疗机构分成比例"
  dependencies={['commissionRate']}
  rules={[{ validator: validateAllRates }]}
>
  <InputNumber aria-label="合作医疗机构分成比例" min={0} max={100} precision={2} addonAfter="%" style={{ width: '100%' }} />
</Form.Item>
<Form.Item
  name="commissionRate"
  label="医美顾问分账比例"
  dependencies={['institutionRate']}
  rules={[{ validator: validateAllRates }]}
>
  <InputNumber aria-label="医美顾问分账比例" min={0} max={100} precision={2} addonAfter="%" style={{ width: '100%' }} />
</Form.Item>
<Form.Item label="医生分账比例" help="100% - 平台 - 合作医疗机构 - 医美顾问">
  <InputNumber aria-label="医生分账比例" value={doctorRate ?? undefined} disabled addonAfter="%" style={{ width: '100%' }} />
</Form.Item>
```

When policy loading fails, render an error `Alert` and make `validateAllRates` reject with `分账策略尚未加载`.

- [ ] **Step 5: Run the shared-field test to verify GREEN**

Run the command from Step 2. Expected: hook/component tests pass.

- [ ] **Step 6: Write failing page integration tests**

For both pages, mock the policy hook to return `{ policy: { platformRate: 40 }, loading: false, error: undefined }` and mock API list/option calls with explicit fixtures containing one doctor, institution and institution project. Open the create modal and assert the old labels are absent and the new labels are present.

For direct config, submit a fixture with institution 35 and medical beauty consultant 10 and assert:

```ts
expect(mockPost).toHaveBeenCalledWith(
  '/admin/doctor-institution-project-configs',
  expect.objectContaining({ institutionRate: 35, commissionRate: 10 }),
);
const payload = mockPost.mock.calls[0][1] as Record<string, unknown>;
expect(payload).not.toHaveProperty('doctorRate');
expect(payload).not.toHaveProperty('platformRate');
```

For proposal submission, assert the same compatible rate fields are posted to `/admin/doctor-institution-project-config-proposals`. Add an invalid-total case for each page and assert `mockPost` is not called. For each page, also mock `{ policy: undefined, loading: false, error: '分账策略加载失败' }`, open the modal, assert the confirmation button is disabled, and verify no POST occurs.

- [ ] **Step 7: Run page tests to verify RED**

```powershell
npm test -- src/pages/DoctorProjectConfigsPage.test.tsx src/pages/SplitConfigProposalsPage.test.tsx
```

Expected: assertions fail because current fields still describe `commissionRate` as doctor/publisher commission and no platform/derived doctor fields exist.

- [ ] **Step 8: Integrate the shared policy and fields into both pages**

In both pages:

- call `useOrderSplitPolicy()` once;
- replace the two hand-written rate `Form.Item`s with `SplitRateFields`;
- disable modal confirmation while policy is loading or failed;
- keep `commissionRate` and `institutionRate` in edit backfill and POST requests;
- never add `doctorRate` or `platformRate` to `Form.Item name` or request bodies.

In both current-config and proposal tables, rename the `commissionRate` column to “医美顾问分账比例” and add a “医生分账比例” column computed with the current platform policy. If `calculateDoctorRate` returns a negative value, render a red `配置无效` tag; if the policy is unavailable, render `-`.

In `SettlementsPage.tsx`, change “咨询师佣金” to “医美顾问分账金额” and the doctor amount label to “医生分账金额”.

- [ ] **Step 9: Run only the Task 4 focused admin tests**

```powershell
npm test -- src/hooks/useOrderSplitPolicy.test.ts src/components/SplitRateFields.test.tsx src/pages/DoctorProjectConfigsPage.test.tsx src/pages/SplitConfigProposalsPage.test.tsx
```

Expected: the hook, shared fields and both page tests pass. Defer the complete Vitest suite, TypeScript/Vite build and lint to Task 8.

- [ ] **Step 10: Commit Task 4**

```powershell
git add joysong-admin/src/hooks/useOrderSplitPolicy.ts joysong-admin/src/hooks/useOrderSplitPolicy.test.ts joysong-admin/src/components/SplitRateFields.tsx joysong-admin/src/components/SplitRateFields.test.tsx joysong-admin/src/pages/DoctorProjectConfigsPage.tsx joysong-admin/src/pages/DoctorProjectConfigsPage.test.tsx joysong-admin/src/pages/SplitConfigProposalsPage.tsx joysong-admin/src/pages/SplitConfigProposalsPage.test.tsx joysong-admin/src/pages/SettlementsPage.tsx
git commit -m "feat: show derived doctor split rates"
```

---

### Task 5: Remove only the dedicated admin consultant-binding UI

**Files:**
- Modify: `joysong-admin/src/pages/IdentityManagementPage.tsx:1-728,840`
- Create: `joysong-admin/src/pages/IdentityManagementPage.test.tsx`

**Interfaces:**
- Preserves: generic `/admin/identity/memberships` flow and `institutionMemberRoleOptions` including `CONSULTANT`.
- Removes: all frontend calls reachable only through `/admin/identity/consultants`.
- Preserves: backend direct-binding endpoint and tests.

- [ ] **Step 1: Write the failing UI regression test**

Render `IdentityManagementPage`, mock `/admin/institutions` and identity list endpoints with empty arrays, click the `机构成员` tab, and assert:

```ts
expect(screen.getByRole('button', { name: '新增任职关系' })).toBeInTheDocument();
expect(screen.queryByRole('button', { name: /添加咨询师/ })).not.toBeInTheDocument();
expect(screen.queryByRole('button', { name: /添加医美顾问/ })).not.toBeInTheDocument();
expect(mockGet).not.toHaveBeenCalledWith('/admin/users');
expect(mockPost).not.toHaveBeenCalledWith('/admin/identity/consultants', expect.anything());
```

- [ ] **Step 2: Run the test to verify RED**

```powershell
npm test -- src/pages/IdentityManagementPage.test.tsx
```

Expected: test fails because “添加咨询师” is still rendered.

- [ ] **Step 3: Delete the dedicated frontend flow**

Remove all of the following from `IdentityManagementPage.tsx`:

- `ConsultantCandidate` type;
- `consultantCandidates`, dialog/loading/submitting state, dedicated form, and both request-generation refs;
- candidate loading, close/cancel, option mapping, validation and direct POST functions;
- the “添加咨询师” button;
- the dedicated binding modal.

Remove `useRef` from the React import after confirming no remaining use. Keep `PlusOutlined` because “新增任职关系” still uses it. Keep `getApiErrorMessage` because other page sections use it.

The final extra action area must contain only:

```tsx
<Button icon={<PlusOutlined />} onClick={() => void openCreate()}>
  新增任职关系
</Button>
```

Change the page description to “医生、医美顾问、机构法人和机构客服均需审核后才能切换。”

- [ ] **Step 4: Run only the focused identity-page verification**

```powershell
npm test -- src/pages/IdentityManagementPage.test.tsx
```

Expected: the focused test passes and generic membership behavior remains rendered. Defer the complete admin test/build/lint verification to Task 8.

- [ ] **Step 5: Commit Task 5**

```powershell
git add joysong-admin/src/pages/IdentityManagementPage.tsx joysong-admin/src/pages/IdentityManagementPage.test.tsx
git commit -m "refactor: remove direct consultant binding entry"
```

---

### Task 6: Align backend user-facing consultant terminology

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/AdminIdentityService.kt:226`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionConsultantService.kt:15-35`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt:101-144`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/AdminIdentityServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt:159-228`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/InstitutionConsultantServiceTest.kt`

**Interfaces:**
- Preserves: `InstitutionConsultant`, `listApprovedConsultants`, `consultantId`, and `/discover/institutions/{id}/consultants`.
- Produces: user-facing validation consistently names the role “医美顾问”.

- [ ] **Step 1: Update tests first to establish RED**

Change the existing missing-consultant expectation to:

```kotlin
assertEquals("订单必须关联医美顾问", error.message)
```

Rename the test to `创建订单 - 缺少医美顾问时拒绝` and the successful snapshot test to `创建订单 - 固化医美顾问和医生快照`.

Create a strict `JdbcTemplate` test for an empty approved list and assert:

```kotlin
val error = assertThrows<IllegalArgumentException> {
    service.requireApprovedConsultant("institution-1", "consultant-1")
}
assertEquals("所选医美顾问未加入该机构或尚未确认", error.message)
```

Extend the existing `AdminIdentityServiceTest` fixture so its final membership query can return no row. Assert `bindConsultant` then throws `IllegalStateException("医美顾问绑定关系写入失败")`; keep the method name, endpoint contract, atomic upserts and transaction tests unchanged.

- [ ] **Step 2: Run focused tests to verify RED**

```powershell
.\gradlew.bat test `
  --tests com.joysong.server.identity.service.AdminIdentityServiceTest `
  --tests com.joysong.server.order.OrderServiceTest `
  --tests com.joysong.server.identity.service.InstitutionConsultantServiceTest `
  --no-daemon --console=plain
```

Expected: message assertions fail against current “机构咨询师/咨询师” text.

- [ ] **Step 3: Update production messages without renaming contracts**

Apply exact user-facing text:

```kotlin
require(request.consultantId.isNotBlank()) { "订单必须关联医美顾问" }
require(consultant.name.isNotBlank()) { "医美顾问名称不能为空" }
```

and:

```kotlin
?: throw IllegalArgumentException("所选医美顾问未加入该机构或尚未确认")
```

Change only the exposed fallback error in `AdminIdentityService` to:

```kotlin
).firstOrNull() ?: throw IllegalStateException("医美顾问绑定关系写入失败")
```

Do not rename classes, methods, paths, SQL role values, request fields, or snapshots.

- [ ] **Step 4: Run focused backend tests to verify GREEN**

Run the command from Step 2. Expected: all focused tests pass.

- [ ] **Step 5: Commit Task 6**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/identity/service/AdminIdentityService.kt joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionConsultantService.kt joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt joysong-server/src/test/kotlin/com/joysong/server/identity/service/AdminIdentityServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/identity/service/InstitutionConsultantServiceTest.kt
git commit -m "refactor: align backend medical beauty consultant terms"
```

---

### Task 7: Unify Flutter medical beauty consultant labels

**Files:**
- Modify: `joysong-flutter/lib/features/identity/domain/identity_models.dart:3-55`
- Modify: `joysong-flutter/lib/features/profile/presentation/profile_page.dart:128-135`
- Modify: `joysong-flutter/lib/features/booking/data/booking_remote_data_source.dart:74-82`
- Modify: `joysong-flutter/lib/features/booking/domain/booking_models.dart:98-108`
- Modify: `joysong-flutter/lib/features/booking/presentation/booking_controller.dart:193-202`
- Modify: `joysong-flutter/lib/features/booking/presentation/booking_page.dart:140-168`
- Modify: `joysong-flutter/test/features/identity/identity_models_controller_test.dart`
- Modify: `joysong-flutter/test/features/booking/booking_controller_test.dart:78-91`
- Modify: `joysong-flutter/test/features/booking/booking_page_test.dart`
- Modify: `joysong-flutter/test/features/booking/booking_remote_data_source_test.dart:8-22`
- Modify: `joysong-flutter/test/features/booking/booking_test_fixtures.dart`

**Interfaces:**
- Preserves: enum codes, Dart enum member names, `BookingConsultant`, `getConsultants`, `consultantId`, and `/consultants`.
- Produces: Chinese product labels “医美顾问” and aligned English localization.

- [ ] **Step 1: Change focused test expectations before production copy**

Add identity assertions:

```dart
test('uses medical aesthetics consultant product labels', () {
  expect(IdentityRoleType.fromCode('CONSULTANT').label, '医美顾问');
  expect(IdentityDocumentType.consultantProof.label, '医美顾问证明');
});
```

Change the booking controller expectation to `请选择医美顾问`. Add the page assertion:

```dart
expect(find.text('选择医美顾问'), findsOneWidget);
```

Make `FakeBookingRepository.consultantResults` configurable with the existing sample as its default, set it to an empty list in a new widget test, and assert:

```dart
expect(find.text('该机构暂时没有可预约的医美顾问'), findsOneWidget);
```

Keep the remote data source assertion exactly:

```dart
expect(client.lastPath, 'discover/institutions/institution-1/consultants');
```

- [ ] **Step 2: Run focused Flutter tests to verify RED**

```powershell
flutter test test/features/identity/identity_models_controller_test.dart test/features/booking/booking_controller_test.dart test/features/booking/booking_page_test.dart test/features/booking/booking_remote_data_source_test.dart
```

Expected: identity, controller and page label assertions fail while the API path assertion remains green.

- [ ] **Step 3: Update Flutter product labels**

Use these exact mappings:

```dart
consultant('CONSULTANT', '医美顾问')
consultantProof('CONSULTANT_PROOF', '医美顾问证明')
```

Update booking decode contexts and fallback to “医美顾问”. Use:

- `选择医美顾问` / `Select a medical aesthetics consultant`;
- `该机构暂时没有可预约的医美顾问` / `No medical aesthetics consultants are currently available for this institution`;
- `请选择医美顾问`.

Update the profile subtitle to `医生、医美顾问与机构法人认证` and aligned English copy. Do not modify sample user nicknames such as “李咨询师”.

- [ ] **Step 4: Format and run focused Flutter tests to verify GREEN**

```powershell
dart format lib/features/identity/domain/identity_models.dart lib/features/profile/presentation/profile_page.dart lib/features/booking test/features/identity/identity_models_controller_test.dart test/features/booking
flutter test test/features/identity/identity_models_controller_test.dart test/features/booking/booking_controller_test.dart test/features/booking/booking_page_test.dart test/features/booking/booking_remote_data_source_test.dart
```

Expected: formatting succeeds and all focused tests pass.

- [ ] **Step 5: Commit Task 7**

```powershell
git add joysong-flutter/lib/features/identity/domain/identity_models.dart joysong-flutter/lib/features/profile/presentation/profile_page.dart joysong-flutter/lib/features/booking joysong-flutter/test/features/identity/identity_models_controller_test.dart joysong-flutter/test/features/booking
git commit -m "feat: unify medical beauty consultant labels"
```

---

### Task 8: Align active documentation and run the unified compile/regression gate

**Files:**
- Modify: `docs/FLUTTER_API_CONTRACT.md`
- Modify: `docs/superpowers/specs/2026-08-08-order-consultant-split-design.md`
- Modify: `docs/superpowers/specs/2026-08-09-admin-consultant-binding-design.md`
- Modify: `docs/superpowers/specs/2026-08-09-professional-workspace-identity-design.md`
- Modify: `docs/订单支付与身份切换需求.md`

**Interfaces:**
- Preserves: all code identifiers shown in documentation.
- Produces: current business contracts that consistently describe the human role as “医美顾问”.

- [ ] **Step 1: Update active contracts with bounded replacements**

Apply these rules manually, never as a blind full-repository replacement:

- `FLUTTER_API_CONTRACT.md`: human role and proof names become “医美顾问” and “医美顾问证明”; retain `CONSULTANT`.
- order split design: use “该机构已批准的医美顾问” for membership scope; retain `consultant_id`, `consultant_name`, and `consultantId`.
- admin binding design: state that the backend direct-binding API remains compatible while the management UI exposes only the generic membership flow for already certified medical beauty consultants.
- professional workspace design: use “医美顾问” for the human role and retain the explicit non-physician, no-diagnosis, no-prescription, no-medical-practice boundary.
- `订单支付与身份切换需求.md`: update human-facing terminology only; retain formulas and internal consultant identifiers.

Do not edit historical `docs/superpowers/plans/**`, `docs/API_AUDIT_REPORT.md`, migration comments, or AI persona descriptions.

- [ ] **Step 2: Run terminology and diff safety checks**

```powershell
git grep -n -I -E "添加咨询师|医生/项目发布者佣金比例|医生/发布者比例|请选择机构咨询师|订单必须关联机构咨询师|咨询师佣金" -- joysong-admin/src joysong-flutter/lib joysong-server/src/main/kotlin
git grep -n -I "咨询师" -- joysong-admin/src joysong-flutter/lib joysong-server/src/main/kotlin
git diff --check
git status --short
```

Expected: the bounded deprecated user-facing phrases return no matches. The broad `咨询师` scan may match only explicitly reviewed AI persona text and seed/sample user nicknames or comments; every other match must be changed to “医美顾问” or documented as an intentional non-product identifier before proceeding. Matches inside excluded migrations and historical plans are not edited.

- [ ] **Step 3: Run the unified backend clean compile, full suite, and MySQL verification**

From `joysong-server`:

```powershell
.\gradlew.bat clean test mysqlIntegrationTest --no-daemon --console=plain
```

Expected: Kotlin production/test sources compile from a clean state, unit/integration tests and MySQL Testcontainers tests all pass, and Docker test containers stop afterward. Do not proceed to another platform if this command fails; fix the failure, rerun this exact gate, then continue.

- [ ] **Step 4: Run the unified admin test, compile, and lint verification**

From `joysong-admin`:

```powershell
npm test
npm run build
npm run lint
```

Expected: the complete Vitest suite, TypeScript/Vite production build and lint pass without new warnings or errors. If any command fails, fix it and rerun all three commands before continuing.

- [ ] **Step 5: Run the unified Flutter format, compile-analysis, and full test verification**

From `joysong-flutter`:

```powershell
dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test
```

Expected: format check, static analysis/compilation and all Flutter tests pass. If any command fails, fix it and rerun all three commands before continuing.

- [ ] **Step 6: Perform final code review**

Review `git diff HEAD~7..HEAD` and the remaining uncommitted documentation diff. Confirm:

- no internal/API/database identifier was renamed;
- no Flyway migration was modified;
- no frontend request contains `doctorRate` or `platformRate`;
- policy load failure disables rate submission;
- both backend save paths reject a negative doctor rate;
- medical beauty consultant remains a beneficiary, not a proposal confirmer;
- the admin direct-binding backend endpoint still exists while its dedicated UI is absent.

- [ ] **Step 7: Commit documentation and final verified state**

```powershell
git add docs/FLUTTER_API_CONTRACT.md docs/superpowers/specs/2026-08-08-order-consultant-split-design.md docs/superpowers/specs/2026-08-09-admin-consultant-binding-design.md docs/superpowers/specs/2026-08-09-professional-workspace-identity-design.md "docs/订单支付与身份切换需求.md"
git commit -m "docs: align medical beauty consultant terminology"
```

- [ ] **Step 8: Record final evidence**

```powershell
git status --short --branch
git log --oneline -8
git diff --check HEAD~8..HEAD
```

Expected: worktree is clean, the eight task commits are visible, and the committed range has no whitespace errors.
