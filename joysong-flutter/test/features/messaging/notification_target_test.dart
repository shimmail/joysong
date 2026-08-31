import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/messaging/domain/notification_target.dart';

void main() {
  test('institution project notification targets preserve review intent', () {
    expect(
      NotificationTarget.parse('institution_project_review', 'request-1'),
      isA<NotificationTarget>()
          .having((target) => target.kind, 'kind',
              NotificationTargetKind.institutionProjectReview)
          .having((target) => target.id, 'id', 'request-1'),
    );
    expect(
      NotificationTarget.parse('institution_project_application', 'request-2'),
      isA<NotificationTarget>()
          .having((target) => target.kind, 'kind',
              NotificationTargetKind.institutionProjectApplication)
          .having((target) => target.id, 'id', 'request-2'),
    );
  });

  test('doctor booking target preserves professional order intent', () {
    final target = NotificationTarget.parse(
      'professional_doctor_orders',
      'order-1',
    );

    expect(target.kind.name, 'professionalDoctorOrders');
    expect(target.id, 'order-1');
  });

  test('consultant completion target preserves order history intent', () {
    final target = NotificationTarget.parse(
      'professional_consultant_orders_history',
      'order-2',
    );

    expect(target.kind.name, 'professionalConsultantOrdersHistory');
    expect(target.id, 'order-2');
  });

  test('professional order targets remain order-related for translation', () {
    for (final targetType in const [
      'order',
      'order_refund',
      'order_service_conversation',
      'professional_doctor_orders',
      'professional_consultant_orders_history',
    ]) {
      expect(
        isOrderRelatedNotificationTarget(targetType),
        isTrue,
        reason: targetType,
      );
    }
    expect(isOrderRelatedNotificationTarget('identity_management'), isFalse);
  });
}
