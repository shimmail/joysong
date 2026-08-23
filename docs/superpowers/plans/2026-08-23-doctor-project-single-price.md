# Doctor Project Single Price Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `doctor_projects.price` the only effective per-doctor project price, calculate the USD travel ground service fee at 40%, repair the admin save flow, and replace legacy demo orders with valid travel-service data.

**Architecture:** Keep the existing database schema and composite doctor-project key. Runtime quote and order creation read `DoctorProjectEntity.price`; the old `medical_list_price` remains a transactionally synchronized compatibility mirror. The institution-project admin form owns doctor price editing and shows a derived fee, while new demo data contains only the travel-ground-service payment flow.

**Tech Stack:** Kotlin 2.x, Spring Boot, Spring Data JPA, Flyway, JUnit 5, MockK, Testcontainers MySQL 8; React 19, TypeScript 6, Ant Design 6, Vitest, Testing Library.

**Spec:** `docs/superpowers/specs/2026-08-23-doctor-project-single-price-design.md`

## Global Constraints

- `doctor_projects.price` is the only runtime price for a `(doctor_id, institution_project_id)` pair.
- The travel ground service fee is `doctor project price × OrderSplitProperties.platformRate`; the current platform rate is exactly `40.00%`.
- Calculate in USD minor units and basis points with HALF_UP rounding; never persist the derived fee in doctor configuration.
- Preserve existing doctor profile fields when changing a retained doctor's price.
- Keep `medical_list_price` only as a compatibility mirror; do not add or remove database columns in this change.
- Historical orders retain their existing snapshots and are never recalculated.
- Do not restore consultation fee, balance, settlement, or medical-payment behavior.
- Tests and initialization must use an isolated fresh database named by `WorktreeTestDatabase`; never connect to or reset a shared development database.
- Before any fresh-database verification, print and verify the resolved database host and database name.
- Follow RED → GREEN → REFACTOR for every production behavior change; run only the smallest related tests before the final verification pass.

---

### Task 1: Make institution-project doctor bindings save and update individual prices

**Files:**

- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/InstitutionProjectController.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/InstitutionProjectControllerTest.kt`

**Interfaces:**

- Consumes: existing `InstitutionProjectRequest.doctorBindings: List<DoctorProjectBinding>` and `DoctorProjectBinding.price: BigDecimal?`.
- Produces: atomic create/update behavior where each retained or new `DoctorProjectEntity` has its submitted price and its compatibility config has the same `medicalListPrice`.

- [ ] **Step 1: Add failing controller tests for distinct prices and retained profile fields**

Create a focused MockK fixture with an admin `ManagementActor`, mocked repositories, `TravelGroundServicePricing` configured with `platformRate = 40.00`, and these tests:

```kotlin
@Test
fun `create saves two doctors on one institution project with independent prices`() {
    val request = institutionProjectRequest(
        doctorBindings = listOf(
            DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00")),
            DoctorProjectBinding("doctor-b", price = BigDecimal("4299.00"))
        )
    )

    fixture.controller.create(fixture.authentication, request)

    verify {
        fixture.doctorProjects.saveAll(match { rows ->
            rows.associate { it.doctorId to it.price } == mapOf(
                "doctor-a" to BigDecimal("3999.00"),
                "doctor-b" to BigDecimal("4299.00")
            )
        })
    }
    verify(exactly = 2) {
        fixture.configs.save(match { it.medicalListPrice in setOf(BigDecimal("3999.00"), BigDecimal("4299.00")) })
    }
}

@Test
fun `update changes a retained doctor price without clearing profile fields`() {
    val existing = DoctorProjectEntity(
        doctorId = "doctor-a",
        projectId = "project-1",
        institutionProjectId = "ip-1",
        price = BigDecimal("3000.00"),
        serviceDescription = "keep-description",
        serviceTags = "keep-tags",
        scheduleNote = "keep-schedule",
        coverImage = "keep-cover",
        images = "keep-images"
    )
    every { fixture.doctorProjects.findByInstitutionProjectId("ip-1") } returns listOf(existing)

    fixture.controller.update(
        fixture.authentication,
        "ip-1",
        institutionProjectRequest(
            doctorBindings = listOf(DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00")))
        )
    )

    verify {
        fixture.doctorProjects.saveAll(match { rows ->
            rows.single().price == BigDecimal("3999.00") &&
                rows.single().serviceDescription == "keep-description" &&
                rows.single().serviceTags == "keep-tags" &&
                rows.single().scheduleNote == "keep-schedule" &&
                rows.single().coverImage == "keep-cover" &&
                rows.single().images == "keep-images"
        })
    }
}
```

Add separate assertions that missing, zero, fractional-cent, duplicate-doctor, and unrelated-institution prices return an error and perform no `saveAll`.

- [ ] **Step 2: Run the new test and verify RED**

Run from `joysong-server`:

```powershell
.\gradlew.bat test --tests "com.joysong.server.admin.controller.InstitutionProjectControllerTest"
```

Expected: FAIL because the current UI-compatible request is validated but retained bindings are not repriced and compatibility configs are not synchronized.

- [ ] **Step 3: Implement price validation and atomic binding synchronization**

Inject `TravelGroundServicePricing`. Normalize each binding once and validate it through the production quote calculator:

```kotlin
private fun validatedPrice(binding: DoctorProjectBinding): BigDecimal {
    val price = requireNotNull(binding.price) { "医生项目价格不能为空" }
    require(price > BigDecimal.ZERO) { "医生项目价格必须大于 0" }
    travelGroundServicePricing.quote(price)
    return price
}
```

Replace the update-only-new-doctors block with a single mapping that copies retained entities and constructs only new entities:

```kotlin
val existingByDoctor = existing.associateBy { it.doctorId }
val savedBindings = normalizedBindings.map { binding ->
    val price = validatedPrice(binding)
    existingByDoctor[binding.doctorId]?.copy(price = price, updatedAt = LocalDateTime.now())
        ?: DoctorProjectEntity(
            doctorId = binding.doctorId,
            projectId = projectId,
            institutionProjectId = institutionProjectId,
            price = price
        )
}
doctorProjectRepository.saveAll(savedBindings)
savedBindings.forEach(::syncCompatibilityPrice)
```

`syncCompatibilityPrice` must load active or soft-deleted config, preserve its legacy fields, set `deletedAt = null`, set `medicalListPrice = doctorProject.price`, update `updatedAt`, and save it. Keep the existing removal and pending-request withdrawal logic unchanged.

- [ ] **Step 4: Run the controller test and verify GREEN**

```powershell
.\gradlew.bat test --tests "com.joysong.server.admin.controller.InstitutionProjectControllerTest"
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/admin/controller/InstitutionProjectController.kt joysong-server/src/test/kotlin/com/joysong/server/admin/controller/InstitutionProjectControllerTest.kt
git commit -m "fix: save doctor prices with institution projects"
```

---

### Task 2: Use the selected doctor's project price for quote and order creation

**Files:**

- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/discover/controller/DiscoverController.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/discover/controller/DiscoverControllerTest.kt`

**Interfaces:**

- Consumes: `DoctorProjectRepository.findByDoctorIdAndInstitutionProjectId(doctorId, institutionProjectId)`.
- Produces: quote and order snapshots based on `DoctorProjectEntity.price`, without requiring a config row.

- [ ] **Step 1: Change the order test to prove the config value is ignored**

Replace the old test that expects `medicalListPrice` to win with:

```kotlin
@Test
fun `new order uses the selected doctor project price as the only fee basis`() {
    every { doctorProjectRepository.findByDoctorIdAndInstitutionProjectId("doctor-1", "ip-1") } returns
        doctorProject(price = BigDecimal("3999.00"))
    every { configRepository.findByDoctorIdAndInstitutionProjectId(any(), any()) } returns
        DoctorInstitutionProjectConfigEntity(medicalListPrice = BigDecimal("9999.00"))

    val created = orderService.createOrder("user-1", request(doctorId = "doctor-1"))

    assertEquals(399_900L, created.medicalListPriceMinor)
    assertEquals(4_000, created.platformServiceRateBps)
    assertEquals(159_960L, created.travelGroundServiceFeeMinor)
}
```

Also add a case where no config exists and order creation still succeeds.

- [ ] **Step 2: Change the discover quote test to prove doctor-specific results**

```kotlin
@Test
fun `quote uses each selected doctor price without a medical config`() {
    every { doctorProjectRepository.findByDoctorIdAndInstitutionProjectId("doctor-a", "ip-1") } returns
        DoctorProjectEntity("doctor-a", "project-1", "ip-1", BigDecimal("3999.00"))
    every { doctorProjectRepository.findByDoctorIdAndInstitutionProjectId("doctor-b", "ip-1") } returns
        DoctorProjectEntity("doctor-b", "project-1", "ip-1", BigDecimal("4299.00"))

    assertEquals(159_960L, controller.getTravelGroundServiceQuote("doctor-a", "ip-1").data!!.travelGroundServiceFeeMinor)
    assertEquals(171_960L, controller.getTravelGroundServiceQuote("doctor-b", "ip-1").data!!.travelGroundServiceFeeMinor)
}
```

- [ ] **Step 3: Run both tests and verify RED**

```powershell
.\gradlew.bat test --tests "com.joysong.server.order.OrderServiceTest" --tests "com.joysong.server.discover.controller.DiscoverControllerTest"
```

Expected: FAIL because both production paths currently read `config.medicalListPrice`.

- [ ] **Step 4: Switch both production paths to `DoctorProjectEntity.price`**

In `OrderService.createOrder`, retain the repository result and quote it:

```kotlin
val doctorProject = doctorProjectRepository.findByDoctorIdAndInstitutionProjectId(
    request.doctorId,
    institutionProject.id
) ?: throw IllegalArgumentException("所选医生未加入该机构项目")

val quote = travelGroundServicePricing.quote(doctorProject.price)
```

Remove the now-unused config repository constructor dependency from `OrderService` and update its test fixture.

In `DiscoverController.getTravelGroundServiceQuote`:

```kotlin
val doctorProject = doctorProjectRepository
    .findByDoctorIdAndInstitutionProjectId(doctorId, institutionProjectId)
    ?: throw IllegalArgumentException("DOCTOR_PROJECT_NOT_CONFIGURED")
return BaseResponse.success(travelGroundServicePricing.quote(doctorProject.price))
```

Remove the now-unused config repository constructor dependency from `DiscoverController` and update its test constructor.

- [ ] **Step 5: Run both tests and verify GREEN**

```powershell
.\gradlew.bat test --tests "com.joysong.server.order.OrderServiceTest" --tests "com.joysong.server.discover.controller.DiscoverControllerTest"
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt joysong-server/src/main/kotlin/com/joysong/server/discover/controller/DiscoverController.kt joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/discover/controller/DiscoverControllerTest.kt
git commit -m "feat: price travel service by selected doctor"
```

---

### Task 3: Keep legacy writers synchronized with the single price source

**Files:**

- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminOrderController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/institution/service/DoctorProjectChangeService.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/admin/controller/AdminOrderControllerTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectChangeServiceTest.kt`

**Interfaces:**

- Consumes: legacy `UpsertConfigRequest.medicalListPrice` and profile requests containing both `priceSuggestion` and `medicalListPrice`.
- Produces: transactionally equal `doctor_projects.price` and compatibility `medical_list_price`; mismatched profile prices are rejected.

- [ ] **Step 1: Add failing tests for the compatibility endpoint**

Add `DoctorProjectRepository` to the `AdminOrderControllerTest` fixture and assert:

```kotlin
@Test
fun `legacy config price update also updates the doctor project source`() {
    every { doctorProjects.findByDoctorIdAndInstitutionProjectId("doctor-1", "ip-1") } returns
        DoctorProjectEntity("doctor-1", "project-1", "ip-1", BigDecimal("1000.00"))

    controller.upsertConfig(authentication, UpsertConfigRequest(
        doctorId = "doctor-1",
        institutionProjectId = "ip-1",
        medicalListPrice = BigDecimal("1200.00")
    ))

    verify { doctorProjects.save(match { it.price == BigDecimal("1200.00") }) }
    verify { configs.save(match { it.medicalListPrice == BigDecimal("1200.00") }) }
}
```

Add rejection coverage for an unbound `(doctorId, institutionProjectId)`.

- [ ] **Step 2: Add failing profile-request tests for one effective price**

```kotlin
@Test
fun `profile update rejects different project and compatibility prices`() {
    val error = assertThrows<IllegalArgumentException> {
        service.submit(doctorActor(), profileRequest(
            priceSuggestion = BigDecimal("3999.00"),
            medicalListPrice = BigDecimal("4299.00")
        ))
    }
    assertEquals("医生项目价格与兼容价格必须一致", error.message)
}

@Test
fun `profile approval writes one price to source and compatibility mirror`() {
    service.review(legalActor(), "request-1", approval())
    verify { doctorProjectRepository.save(match { it.price == BigDecimal("4299.00") }) }
    verify { configRepository.save(match { it.medicalListPrice == BigDecimal("4299.00") }) }
}
```

- [ ] **Step 3: Run both tests and verify RED**

```powershell
.\gradlew.bat test --tests "com.joysong.server.admin.controller.AdminOrderControllerTest" --tests "com.joysong.server.institution.service.DoctorProjectChangeServiceTest"
```

Expected: FAIL because the old config endpoint only writes the config table and profile updates allow two different amounts.

- [ ] **Step 4: Synchronize both server write paths**

Inject `DoctorProjectRepository` and `TravelGroundServicePricing` into `AdminOrderController`. Add `@Transactional` to `upsertConfig`, resolve the existing doctor binding, validate the requested compatibility amount through `quote`, then save a copied doctor project and the compatibility config in the same transaction.

For `PROFILE_UPDATE`, require equality after USD normalization:

```kotlin
val price = requireNotNull(request.priceSuggestion) { "医生项目价格不能为空" }
val compatibilityPrice = requireNotNull(request.medicalListPrice) { "医生项目价格不能为空" }
require(price.compareTo(compatibilityPrice) == 0) { "医生项目价格与兼容价格必须一致" }
travelGroundServicePricing.quote(price)
```

On approval, assign `effective.medicalListPrice = requireNotNull(target.priceSuggestion)` so the mirror is derived from the unique source proposal.

- [ ] **Step 5: Run both tests and verify GREEN**

```powershell
.\gradlew.bat test --tests "com.joysong.server.admin.controller.AdminOrderControllerTest" --tests "com.joysong.server.institution.service.DoctorProjectChangeServiceTest"
```

Expected: PASS.

- [ ] **Step 6: Commit Task 3**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminOrderController.kt joysong-server/src/main/kotlin/com/joysong/server/institution/service/DoctorProjectChangeService.kt joysong-server/src/test/kotlin/com/joysong/server/admin/controller/AdminOrderControllerTest.kt joysong-server/src/test/kotlin/com/joysong/server/institution/service/DoctorProjectChangeServiceTest.kt
git commit -m "fix: synchronize legacy doctor price writers"
```

---

### Task 4: Put per-doctor price and the 40% fee preview in the institution-project form

**Files:**

- Modify: `joysong-admin/src/pages/InstitutionProjectsPage.tsx`
- Create: `joysong-admin/src/pages/InstitutionProjectsPage.test.tsx`
- Modify: `joysong-admin/src/pages/ProjectCollaborationPage.tsx`
- Modify: `joysong-admin/src/pages/ProjectCollaborationPage.test.tsx`
- Modify: `joysong-admin/src/utils/money.ts`
- Modify: `joysong-admin/src/App.tsx`
- Modify: `joysong-admin/src/layouts/AdminLayout.tsx`
- Delete: `joysong-admin/src/pages/DoctorProjectConfigsPage.tsx`
- Delete: `joysong-admin/src/pages/DoctorProjectConfigsPage.test.tsx`

**Interfaces:**

- Consumes: institution-project DTO `doctors[].price`, `/admin/order-split-policy`, and `InstitutionProjectRequest.doctorBindings`.
- Produces: editable `doctorBindings: Array<{doctorId: string; price: number}>`, a read-only USD fee preview, and a compatibility redirect from `/doctor-project-configs`.

- [ ] **Step 1: Add a tested minor-unit fee helper**

Add to `utils/money.ts`:

```typescript
export function calculatePercentageFeeMinor(price: unknown, ratePercent: unknown): number | null {
  if (typeof price !== 'number' || !Number.isFinite(price) || price <= 0) return null;
  if (typeof ratePercent !== 'number' || !Number.isFinite(ratePercent) || ratePercent <= 0) return null;
  const priceMinor = Math.round((price + Number.EPSILON) * 100);
  const rateBps = Math.round((ratePercent + Number.EPSILON) * 100);
  const feeMinor = Math.round((priceMinor * rateBps) / 10_000);
  return Number.isSafeInteger(feeMinor) && feeMinor > 0 ? feeMinor : null;
}
```

The page test must assert `3999 / 40 -> 159960` and `4299 / 40 -> 171960` through rendered `USD 1599.60` and `USD 1719.60` values.

- [ ] **Step 2: Add failing institution-project page tests**

Mock one institution project containing two doctors with prices and the split-policy hook returning 40. Assert:

```typescript
expect(await screen.findByDisplayValue('3999.00')).toBeInTheDocument();
expect(screen.getByDisplayValue('4299.00')).toBeInTheDocument();
expect(screen.getByText('USD 1599.60')).toBeInTheDocument();
expect(screen.getByText('USD 1719.60')).toBeInTheDocument();
```

After editing doctor A to `4999`, save and assert the exact payload:

```typescript
expect(api.put).toHaveBeenCalledWith('/admin/institution-projects/ip-1', expect.objectContaining({
  doctorBindings: [
    { doctorId: 'doctor-a', price: 4999 },
    { doctorId: 'doctor-b', price: 4299 },
  ],
}));
expect(vi.mocked(api.put).mock.calls[0][1]).not.toHaveProperty('doctorIds');
```

Add validation cases for missing price, zero, `0.01` (which rounds to a zero-cent fee), duplicate doctor, and unavailable policy preview.

- [ ] **Step 3: Change the collaboration test to one project price field**

Expect the `PROFILE_UPDATE` dialog to show `医生项目价格（USD）`, not `医疗套餐优惠前金额（USD）`, and assert the compatibility payload uses one input value twice:

```typescript
expect(api.post).toHaveBeenCalledWith('/admin/institution-project-requests', expect.objectContaining({
  requestType: 'PROFILE_UPDATE',
  priceSuggestion: 4299,
  medicalListPrice: 4299,
}));
```

- [ ] **Step 4: Run the focused frontend tests and verify RED**

Run from `joysong-admin`:

```powershell
npm test -- InstitutionProjectsPage.test.tsx ProjectCollaborationPage.test.tsx
```

Expected: FAIL because `InstitutionProjectsPage` currently sends doctor IDs without prices and the collaboration page exposes a separate medical list price.

- [ ] **Step 5: Implement the dynamic doctor binding rows and remove the duplicate screen**

Use `Form.List name="doctorBindings"`. Each row renders a filtered doctor `Select`, required two-decimal `InputNumber`, and:

```tsx
const feeMinor = calculatePercentageFeeMinor(bindingPrice, policyState.policy?.platformRate);
<Typography.Text>{feeMinor == null ? '-' : formatMoney(feeMinor, 'USD')}</Typography.Text>
```

On edit, set:

```typescript
doctorBindings: record.doctors.map(doctor => ({ doctorId: doctor.id, price: doctor.price }))
```

Submit `doctorBindings` directly and remove `doctorIds` from form state and payload. Change the table's doctor tags to include each price and derived fee.

In `ProjectCollaborationPage`, initialize the one field from `target.currentPrice`, then submit the same value as `priceSuggestion` and `medicalListPrice`.

Remove the duplicate menu item and lazy import. Keep the old path as:

```tsx
<Route path="doctor-project-configs" element={<Navigate to="/institution-projects" replace />} />
```

Delete the obsolete page and its obsolete test.

- [ ] **Step 6: Run the focused frontend tests and verify GREEN**

```powershell
npm test -- InstitutionProjectsPage.test.tsx ProjectCollaborationPage.test.tsx
```

Expected: PASS.

- [ ] **Step 7: Commit Task 4**

```powershell
git add -A joysong-admin/src
git commit -m "feat: edit doctor prices in institution projects"
```

---

### Task 5: Replace legacy demo pricing and orders with valid travel-service data

**Files:**

- Modify: `joysong-server/src/main/kotlin/com/joysong/server/common/initializer/SeedIds.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/common/initializer/DoctorDataInitializer.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/common/initializer/IdentityDataInitializer.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/common/initializer/OrderDataInitializer.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/common/initializer/DiaryDataInitializer.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/common/initializer/DoctorDataInitializerTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/common/initializer/IdentityDataInitializerTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/common/initializer/IdentityDataInitializerPersistenceTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/common/initializer/OrderDataInitializerTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/common/initializer/DemoDataInitializationIntegrationTest.kt`

**Interfaces:**

- Consumes: stable `SeedIds`, current Flyway schema, `seed.demo.enabled=true` initializer ordering.
- Produces: two doctors with different prices on one institution project and only valid `TRAVEL_GROUND_SERVICE_ONLY` demo orders/payments.

- [ ] **Step 1: Add failing initializer unit tests**

Extend `DoctorDataInitializerTest` to capture `doctorProjectRepository.saveAll` and assert:

```kotlin
val sameProject = savedDoctorProjects.captured.filter { it.institutionProjectId == SeedIds.IP_ID_1 }
assertEquals(
    mapOf(
        SeedIds.DOC_ID_1 to BigDecimal("3999.00"),
        SeedIds.DOC_ID_2 to BigDecimal("4299.00")
    ),
    sameProject.associate { it.doctorId to it.price }
)
```

Extend `IdentityDataInitializerTest` to require two `doctor_institution_project_configs` writes with explicit positive `medical_list_price` equal to those prices, and require `USD` in professional request fixtures.

Create `OrderDataInitializerTest` that captures saved orders and payment SQL, then asserts:

```kotlin
assertEquals(
    setOf("PENDING_SERVICE_FEE", "SERVICE_ACTIVE", "COMPLETED"),
    savedOrders.map { it.status }.toSet()
)
assertTrue(savedOrders.all { it.paymentFlow == "TRAVEL_GROUND_SERVICE_ONLY" })
assertTrue(savedOrders.all { it.platformServiceRateBps == 4000 })
assertFalse(allSql.contains("CONSULTATION_FEE"))
assertFalse(allSql.contains("BALANCE"))
assertTrue(allSql.contains("TRAVEL_GROUND_SERVICE_FEE"))
```

- [ ] **Step 2: Run initializer unit tests and verify RED**

```powershell
.\gradlew.bat test --tests "com.joysong.server.common.initializer.DoctorDataInitializerTest" --tests "com.joysong.server.common.initializer.IdentityDataInitializerTest" --tests "com.joysong.server.common.initializer.OrderDataInitializerTest"
```

Expected: FAIL because current data has only one doctor per institution project, zero compatibility price, legacy orders, and consultation/balance payments.

- [ ] **Step 3: Rewrite the minimal seed graph**

Use stable IDs and these doctor bindings:

```kotlin
DoctorProjectEntity(SeedIds.DOC_ID_1, SeedIds.PROJ_ID_1, SeedIds.IP_ID_1, BigDecimal("3999.00"))
DoctorProjectEntity(SeedIds.DOC_ID_2, SeedIds.PROJ_ID_1, SeedIds.IP_ID_1, BigDecimal("4299.00"))
```

Add a second stable config ID. Insert both configs with `medical_list_price` equal to the corresponding doctor price; keep the existing split proposal attached only to its original config. Change project request currencies from `CNY` to `USD`, and make profile-update `price_suggestion` and `medical_list_price` equal.

Replace the six legacy orders with exactly three:

```kotlin
// snapshots: 399900 * 4000 / 10000 = 159960
OrderEntity(
    id = SeedIds.ORDER_ID_1,
    userId = SeedIds.USER_ID_1,
    projectName = "玻尿酸填充",
    institutionName = "上海娇颜颂医美中心",
    currency = "USD",
    price = BigDecimal("1599.60"),
    totalAmountMinor = 159_960,
    paidAmount = BigDecimal.ZERO,
    paidAmountMinor = 0,
    status = "PENDING_SERVICE_FEE",
    paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY",
    medicalListPriceMinor = 399_900,
    platformServiceRateBps = 4_000,
    travelGroundServiceFeeMinor = 159_960,
    doctorId = SeedIds.DOC_ID_1,
    institutionProjectId = SeedIds.IP_ID_1
)
```

Create equivalent `SERVICE_ACTIVE` and `COMPLETED` rows with successful `TRAVEL_GROUND_SERVICE_FEE` payments. Set their `paidAmountMinor`, `paymentTime`, `serviceActivatedAt`, and `completedAt` consistently. Keep favorites; retain only reviews tied to the completed new-flow order. Change diary `orderId` values so only a matching completed new-flow diary references an order; unrelated diaries use an empty order ID.

- [ ] **Step 4: Run initializer unit tests and verify GREEN**

```powershell
.\gradlew.bat test --tests "com.joysong.server.common.initializer.DoctorDataInitializerTest" --tests "com.joysong.server.common.initializer.IdentityDataInitializerTest" --tests "com.joysong.server.common.initializer.OrderDataInitializerTest"
```

Expected: PASS.

- [ ] **Step 5: Add a fresh isolated MySQL integration test**

Create `DemoDataInitializationIntegrationTest` using `@SpringBootTest`, `@Testcontainers`, and a MySQL 8.0.39 `@ServiceConnection`. Use these properties so the application migrates a new empty database and runs all demo initializers without external calls:

```kotlin
@SpringBootTest(properties = [
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.task.scheduling.enabled=false",
    "payment.reconciliation.enabled=false",
    "payment.stripe.legacy-enabled=false",
    "seed.demo.enabled=true",
    "seed.demo.password=demo-test-password",
    "admin.bootstrap.phone=13800138000",
    "admin.bootstrap.password=demo-admin-password",
    "jwt.secret=0123456789abcdef0123456789abcdef",
    "google.client-id=demo-data-test-google-client",
    "ai-agent.provider=QWEN",
    "ai-agent.base-url=https://invalid.example/v1",
    "ai-agent.api-key=demo-data-test-key",
    "ai-agent.model=demo-data-test-model",
    "ai-agent.intent-model=demo-data-test-intent-model",
    "order.split.platform-rate=40.00"
])
```

The container must use `.withDatabaseName(WorktreeTestDatabase.databaseName())`; override `start()` to call `WorktreeTestDatabase.validateAndPrint(this)`. Query the fresh database and assert:

```kotlin
assertEquals(listOf(BigDecimal("3999.00"), BigDecimal("4299.00")), doctorPrices)
assertEquals(0, legacyOrderCount)
assertEquals(0, legacyPaymentCount)
assertEquals(3, travelOrderCount)
assertEquals(2, successfulTravelPaymentCount)
```

- [ ] **Step 6: Run only the fresh-database integration test**

```powershell
.\gradlew.bat test --tests "com.joysong.server.common.initializer.DemoDataInitializationIntegrationTest"
```

Expected: output first prints an isolated host and a database beginning with `myapp_worktree_`; test PASS. If Docker is unavailable, stop after recording the environmental failure—do not substitute a shared database.

- [ ] **Step 7: Commit Task 5**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/common/initializer joysong-server/src/test/kotlin/com/joysong/server/common/initializer
git commit -m "testdata: seed doctor prices and travel orders"
```

---

### Task 6: Align UML, API documentation, and perform the final focused verification

**Files:**

- Modify: `design/TRAVEL_GROUND_SERVICE_PAYMENT_WORKFLOW.puml`
- Modify: `docs/FLUTTER_API_CONTRACT.md`

**Interfaces:**

- Consumes: final production behavior and the approved design specification.
- Produces: documentation that names `doctor_projects.price` as the sole fee basis and labels `medicalListPrice` fields as compatibility snapshots.

- [ ] **Step 1: Update the activity diagram and API contract**

Change the pricing node to:

```plantuml
:读取所选医生的机构项目价格\ndoctor_projects.price（USD）;
:计算旅游地接服务费\nfeeMinor = roundHalfUp(\ndoctorPriceMinor × 4000 / 10000);
```

In `FLUTTER_API_CONTRACT.md`, state that `medicalListPrice`/`medicalListPriceMinor` remain compatibility names only; new quotes and orders use the selected `DoctorProjectEntity.price`. Remove text that tells clients or admins to maintain a separate medical list price.

- [ ] **Step 2: Run documentation and repository checks**

```powershell
git diff --check
git grep -n -I -E "面诊金|尾款|CONSULTATION_FEE|BALANCE" -- joysong-server/src/main/kotlin/com/joysong/server/common/initializer design/TRAVEL_GROUND_SERVICE_PAYMENT_WORKFLOW.puml
```

Expected: `git diff --check` exits 0; the second command returns no legacy seed/workflow matches.

- [ ] **Step 3: Run frontend static verification once**

```powershell
npm run lint
npm run build
```

Expected: both commands exit 0 with no lint or TypeScript errors. Do not repeat the already-passing focused Vitest or Gradle commands, and do not run a full suite without evidence that it is needed.

- [ ] **Step 4: Review the final diff and verify scope**

```powershell
git status --short
git diff --stat 048ec4a..HEAD
git diff --check
```

Confirm there is no Flyway migration, no unrelated refactor, no `medical_list_price` runtime read in order/quote code, no generated build output, and no temporary test database file under the worktree.

- [ ] **Step 5: Commit documentation**

```powershell
git add design/TRAVEL_GROUND_SERVICE_PAYMENT_WORKFLOW.puml docs/FLUTTER_API_CONTRACT.md
git commit -m "docs: align travel fee with doctor pricing"
```
