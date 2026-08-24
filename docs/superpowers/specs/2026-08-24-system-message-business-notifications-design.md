# System Message Business Notifications Design

## Goal

Route selected order, professional-management, and identity-review business events into the existing system-notification inbox, with accurate recipients, readable result text, and direct navigation to the relevant Flutter business screen.

## Scope

All notifications persist through the existing `notifications` table and appear under the Flutter system-message category. Chat messages remain in DM/customer-service/order-service conversations and are not copied into system notifications.

Covered order events are order creation, travel-service activation, user-confirmed completion, refund submission, refund approval, refund rejection, refund completion, and cancellation. Covered professional-management events are doctor or consultant relationship submission, withdrawal, approval, and rejection. Covered platform-identity events are approval and rejection only.

No WebSocket, FCM/APNs, email, SMS, marketing notifications, administrator inbox, notification-preference system, or database migration is included.

## Notification Contract

Stable notification types:

- `ORDER_CREATED`
- `ORDER_SERVICE_ACTIVATED`
- `ORDER_COMPLETED`
- `ORDER_REFUND_REQUESTED`
- `ORDER_REFUND_APPROVED`
- `ORDER_REFUND_REJECTED`
- `ORDER_REFUNDED`
- `ORDER_CANCELLED`
- `PROFESSIONAL_APPLICATION_SUBMITTED`
- `PROFESSIONAL_APPLICATION_WITHDRAWN`
- `PROFESSIONAL_APPLICATION_APPROVED`
- `PROFESSIONAL_APPLICATION_REJECTED`
- `IDENTITY_APPLICATION_APPROVED`
- `IDENTITY_APPLICATION_REJECTED`

Because the current notification schema has no metadata JSON, navigation scope is encoded by stable target types:

- `order`: `targetId` is the order ID and opens order details.
- `order_service_conversation`: `targetId` is the order ID and opens the order service conversation; only consultant notifications use it.
- `order_refund`: `targetId` is the order ID and opens order details with refund information.
- `professional_doctor_review`: request ID, opens the legal representative's doctor review view.
- `professional_consultant_review`: request ID, opens the legal representative's consultant review view.
- `professional_doctor_application`: request ID, opens the doctor's own relationship history.
- `professional_consultant_application`: request ID, opens the consultant's own relationship history.
- `professional_doctor_relationships`: request ID, opens the doctor's relationships.
- `professional_consultant_relationships`: request ID, opens the consultant's relationships.
- `identity_management`: application ID, opens identity/professional management.
- `identity_application`: application ID, opens identity history focused on the rejected application.

## Recipients

Recipients are resolved by the server and de-duplicated per event. Order users come from `userId`, consultants from `consultantId`, and doctors from `doctorId`. Institution legal representatives are dynamically resolved from the institution ID and must have an active legal-representative role plus an approved, non-revoked legal-representative membership; deleted users are excluded.

Order creation notifies user and consultant. Service activation notifies user, consultant, and doctor. The consultant target is the service conversation; other recipients open order details. User-confirmed completion notifies consultant, doctor, and current institution legal representatives. Refund events notify user, consultant, and doctor. Cancellation notifies user and consultant.

Professional submissions and withdrawals notify current legal representatives. Approval and rejection notify the doctor or consultant applicant. Identity approval and rejection notify only the identity applicant.

## Consistency and Idempotency

Notifications are emitted only after the corresponding conditional state transition succeeds. Professional and identity review transitions already enforce `PENDING` compare-and-set semantics. Payment and refund replay paths must not emit a second notification when no transition occurred.

Business state remains authoritative. Payment-provider success and refund completion must not be repeated because notification persistence fails. The implementation should centralize notification creation and use a deterministic event-recipient key where replay-prone flows require it; schema changes are out of scope, so idempotency may use transition results and existing business rows rather than a new notification column.

## Flutter Experience

Every new type falls into the existing system-message filter. Clicking a notification marks that item read, then navigates to its target. Opening the overall messages tab must not mark every system message read automatically.

Order and refund targets open order details directly. Identity rejection and professional application targets focus or expand the matching history item and show the server review note. Missing or stale targets fall back to the relevant list/management screen without crashing.

## Testing

Backend focused tests cover exact recipients, de-duplication, review-note text, transition-only emission, replay safety, and target contracts. Flutter focused tests cover system-category filtering, single-item read behavior, direct order navigation, identity focus, professional scope navigation, review-note rendering, and stale-target fallback. Run focused tests first, then at most one broader suite per project.

