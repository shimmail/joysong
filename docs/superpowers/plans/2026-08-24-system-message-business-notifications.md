# System Message Business Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver selected order, professional-management, and identity-review events to the existing Flutter system-message page with accurate recipients and business navigation.

**Architecture:** Add focused backend notification coordinators that centralize event text, recipient resolution, and target contracts, then invoke them only after real business state transitions. Extend Flutter's existing notification target dispatcher and existing identity/relationship pages rather than creating another message center.

**Tech Stack:** Kotlin, Spring Boot, Spring Data/JDBC, MockK/JUnit 5, Flutter/Dart, Riverpod, flutter_test.

**Spec:** `docs/superpowers/specs/2026-08-24-system-message-business-notifications-design.md`

## Global Constraints

- Persist all covered events in the existing `notifications` table and show them in the system-message category.
- Do not add a database migration, realtime push channel, administrator inbox, or duplicate chat messages.
- Resolve recipients on the server, de-duplicate per event, and dynamically resolve current active legal representatives.
- Emit notifications only after real state transitions; replayed payment/refund/review requests must not duplicate them.
- Notification failures must not cause payment-provider or refund-completion side effects to repeat.
- Run focused tests first and at most one broader suite per project.

---

### Task 1: Shared business-notification contract and legal-representative resolver

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/notification/service/BusinessNotificationService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/notification/service/BusinessNotificationServiceTest.kt`

**Interfaces:**
- Produces semantic identity, professional, and order notification methods used by later tasks.
- Produces legal-representative resolution by institution ID with active-role, approved-membership, non-revoked, non-deleted constraints.

- [ ] Write failing tests for recipient de-duplication, legal-representative filtering, type/title/content/target contracts, and empty recipients.
- [ ] Run `./gradlew test --tests com.joysong.server.notification.service.BusinessNotificationServiceTest` and confirm feature failures.
- [ ] Implement the minimal coordinator and resolver using the existing `NotificationService`.
- [ ] Re-run the focused test and confirm it passes.

### Task 2: Platform identity and professional-management notifications

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/AdminIdentityService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/DoctorInstitutionChangeRequestService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/ConsultantInstitutionChangeRequestService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/AdminIdentityServiceTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/DoctorInstitutionChangeRequestServiceTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/ConsultantInstitutionChangeRequestServiceTest.kt`

**Interfaces:**
- Consumes Task 1 semantic notification methods.
- Produces submit/withdraw notifications to legal representatives and approve/reject notifications to applicants.

- [ ] Add failing tests for identity approval/rejection recipients, rejection reason and targets.
- [ ] Add failing tests for doctor and consultant submission, withdrawal, approval, rejection, and no notification on rejected transitions.
- [ ] Run the three focused test classes and confirm expected failures.
- [ ] Inject and call the notification coordinator only after successful writes.
- [ ] Re-run the three focused test classes and confirm they pass.

### Task 3: Order creation, activation, completion, and cancellation notifications

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/payment/service/PaymentPersistenceService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/order/OrderServiceTest.kt`
- Create or modify: closest `PaymentPersistenceService` focused test.

**Interfaces:**
- Consumes Task 1 order notification methods.
- Produces transition-only notifications for create, service activation, completion, and every cancellation path.

- [ ] Add failing focused tests for exact recipients/targets, recipient de-duplication, all cancellation paths, and replay safety.
- [ ] Run only the focused order/payment tests and confirm expected failures.
- [ ] Add minimal notification calls after successful transitions, preserving payment callback safety.
- [ ] Re-run the focused tests and confirm they pass.

### Task 4: Refund lifecycle notifications

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundWorkflowPersistenceService.kt`
- Modify only if required: `joysong-server/src/main/kotlin/com/joysong/server/refund/service/RefundService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/refund/service/RefundServiceTest.kt`
- Create or modify: closest refund-workflow focused test.

**Interfaces:**
- Consumes Task 1 refund notification methods.
- Produces requested, approved, rejected, and completed notifications exactly once per real transition.

- [ ] Add failing tests for submission, approval, rejection with reason, completion, automatic completion semantics, and replay/retry suppression.
- [ ] Run only refund-focused tests and confirm expected failures.
- [ ] Implement notifications at the authoritative transition points, not generic post-processing callbacks.
- [ ] Re-run refund-focused tests and confirm they pass.

### Task 5: Flutter system-message routing and focused business pages

**Files:**
- Modify: `joysong-flutter/lib/features/shell/presentation/app_shell.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/identity_pages.dart`
- Modify: `joysong-flutter/lib/features/identity/presentation/institution_relationships_page.dart`
- Test: `joysong-flutter/test/features/shell/app_shell_navigation_test.dart`
- Test: `joysong-flutter/test/features/identity/identity_center_page_test.dart`
- Test: `joysong-flutter/test/features/identity/institution_relationships_page_test.dart`
- Test: `joysong-flutter/test/features/messaging/messaging_center_page_test.dart`

**Interfaces:**
- Consumes the target types defined in the spec.
- Produces direct order/refund/service-conversation navigation and focused identity/professional application histories.

- [ ] Add failing widget tests for system categorization, direct order/refund navigation, identity targets, role-specific professional targets, rejection text, stale-target fallback, and single-item read behavior.
- [ ] Run the four focused Flutter test files and confirm expected failures.
- [ ] Implement target dispatch, optional initial IDs/focus behavior, and remove messages-tab-wide automatic read-all.
- [ ] Re-run the focused Flutter tests and confirm they pass.
- [ ] Run `flutter analyze` once.

### Task 6: Integrated verification and documentation follow-up

**Files:**
- Modify if maintained: `docs/CORE_ROLES_BUSINESS_SWIMLANE.puml`
- Modify if maintained: relevant notification/API documentation under `docs/` or `doc/`.

**Interfaces:**
- Consumes all prior tasks.
- Produces reviewable verification evidence and updated developer diagrams where the flow is documented.

- [ ] Run the consolidated focused backend tests once.
- [ ] Run the consolidated focused Flutter tests once.
- [ ] Run at most one broader backend suite and one broader Flutter suite if focused verification is green and time remains.
- [ ] Inspect the branch diff for scope, generated files, temporary files, and notification-contract consistency.
- [ ] Update maintained diagrams/docs only where they describe the changed flows.
