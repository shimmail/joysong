# Order Consultant Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Require every new order to snapshot its institution, consultant, and doctor, then block settlement for incomplete historical orders.

**Architecture:** Extend the order request/entity/response with a separate consultant snapshot. Use a focused JDBC-backed lookup for approved institution consultants because the identity module currently owns `institution_memberships` without a JPA entity. Validate required associations in `OrderService`; guard `SettlementService` before calculating payouts.

**Tech Stack:** Kotlin, Spring Boot, Spring Data JPA, JdbcTemplate, MockK, Flyway, Flutter/Dart.

## Global Constraints

New orders must have an institution project, institution consultant, and doctor. Historical rows remain readable. Settlement uses only order snapshots for recipient identity and rejects incomplete snapshots.

---

### Task 1: Persist and expose the consultant snapshot

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/entity/OrderEntity.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/dto/CreateOrderRequest.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/dto/OrderResponse.kt`
- Create: `joysong-server/src/main/resources/db/migration/V9__add_order_consultant_snapshot.sql`

- [ ] **Step 1: Write failing contract tests**

Add assertions that an order response serializes `consultantId` and `consultantName` when its entity has both values.

- [ ] **Step 2: Run the contract test to verify it fails**

Run: `./gradlew.bat test --tests com.joysong.server.order.OrderServiceTest`

- [ ] **Step 3: Add the fields and migration**

Use nullable database columns for existing records and empty-string Kotlin defaults for deserialization compatibility.

- [ ] **Step 4: Run the contract test to verify it passes**

Run: `./gradlew.bat test --tests com.joysong.server.order.OrderServiceTest`

### Task 2: Require a verified institution consultant at order creation

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionConsultantService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt`

- [ ] **Step 1: Write failing service tests**

Test that missing institution project, doctor, or consultant is rejected and that an approved consultant is stored in the order snapshot.

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew.bat test --tests com.joysong.server.order.OrderServiceTest`

- [ ] **Step 3: Implement minimal validation**

Add `InstitutionConsultantService.requireApprovedConsultant(institutionId, consultantId)` backed by `institution_memberships` and `users`, then use it during order creation.

- [ ] **Step 4: Run the targeted test to verify it passes**

Run: `./gradlew.bat test --tests com.joysong.server.order.OrderServiceTest`

### Task 3: Prevent settlement without complete order recipients

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/settlement/service/SettlementService.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/settlement/SettlementServiceTest.kt`

- [ ] **Step 1: Write the failing settlement test**

Construct a historical order with an empty consultant snapshot and assert `saveSettlement` throws `IllegalStateException` before any settlement is saved.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat test --tests com.joysong.server.settlement.SettlementServiceTest`

- [ ] **Step 3: Add the snapshot completeness guard**

Require nonblank institution ID/name, institution project ID, consultant ID/name, doctor ID/name before rate lookup.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew.bat test --tests com.joysong.server.settlement.SettlementServiceTest`

### Task 4: Complete the booking API contract

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/discover/controller/DiscoverController.kt`
- Modify: `joysong-flutter/lib/features/booking/data/booking_remote_data_source.dart`
- Modify: `joysong-flutter/lib/features/booking/domain/booking_models.dart`
- Modify: `joysong-flutter/lib/features/booking/presentation/booking_controller.dart`

- [ ] **Step 1: Write failing client/controller tests**

Assert the booking command serializes `consultantId` and cannot submit without a selected consultant.

- [ ] **Step 2: Run focused tests to verify failure**

Run: `flutter test test/features/booking`

- [ ] **Step 3: Implement consultant list and selection**

Expose confirmed institution consultants and load/select one in the booking client before creating the order.

- [ ] **Step 4: Run focused tests to verify pass**

Run: `flutter test test/features/booking`

### Task 5: Verify the integrated behavior

**Files:**
- Test: `joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/settlement/SettlementServiceTest.kt`

- [ ] **Step 1: Run server tests**

Run: `./gradlew.bat test --tests com.joysong.server.order.OrderServiceTest --tests com.joysong.server.settlement.SettlementServiceTest`

- [ ] **Step 2: Run client booking tests**

Run: `flutter test test/features/booking`

- [ ] **Step 3: Inspect working tree**

Run: `git status --short`
