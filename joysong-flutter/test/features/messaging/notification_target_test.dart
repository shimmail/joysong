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
}
