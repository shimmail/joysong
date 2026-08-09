import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/booking/presentation/booking_controller.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

import 'booking_test_fixtures.dart';
import '../orders/order_test_fixtures.dart';

void main() {
  test('loads booking context and creates server-priced order command',
      () async {
    final repository = FakeBookingRepository();
    final controller = BookingController(
      repository: repository,
      institutionId: 'institution-1',
      projectId: 'project-1',
    );
    await controller.load();
    await controller.selectConsultant(controller.consultants.single);
    await controller.selectDoctor(controller.doctors.single);
    await controller.selectCoupon(controller.coupons.single);
    controller
        .selectAppointmentTime(DateTime.now().add(const Duration(days: 2)));

    final order = await controller.submit();

    expect(order, isA<Order>());
    expect(repository.lastCommand?.institutionProjectId, 'ip-1');
    expect(repository.lastCommand?.consultantId, 'consultant-1');
    expect(repository.lastCommand?.doctorId, 'doctor-1');
    expect(repository.lastCommand?.userCouponId, 11);
    expect(controller.payablePreview.toDecimalString(), '1180.5');
  });

  test('prevents duplicate order submission', () async {
    final repository = FakeBookingRepository();
    final completer = Completer<Order>();
    repository.createCompleter = completer;
    final controller = BookingController(
      repository: repository,
      institutionId: 'institution-1',
      projectId: 'project-1',
    );
    await controller.load();
    await controller.selectConsultant(controller.consultants.single);
    await controller.selectDoctor(controller.doctors.single);
    controller
        .selectAppointmentTime(DateTime.now().add(const Duration(days: 2)));

    final first = controller.submit();
    final second = await controller.submit();
    expect(second, isNull);
    expect(repository.createCalls, 1);

    completer.complete(sampleOrder());
    expect(await first, isNotNull);
  });

  test('rejects a past appointment before network submission', () async {
    final repository = FakeBookingRepository();
    final controller = BookingController(
      repository: repository,
      institutionId: 'institution-1',
      projectId: 'project-1',
    );
    await controller.load();
    await controller.selectConsultant(controller.consultants.single);
    await controller.selectDoctor(controller.doctors.single);
    controller.selectAppointmentTime(
        DateTime.now().subtract(const Duration(hours: 1)));

    expect(await controller.submit(), isNull);
    expect(controller.errorMessage, '预约时间必须晚于当前北京时间');
    expect(repository.createCalls, 0);
  });

  test('requires an institution consultant before submission', () async {
    final repository = FakeBookingRepository();
    final controller = BookingController(
      repository: repository,
      institutionId: 'institution-1',
      projectId: 'project-1',
    );
    await controller.load();
    await controller.selectDoctor(controller.doctors.single);
    controller.selectAppointmentTime(DateTime.now().add(const Duration(days: 2)));

    expect(await controller.submit(), isNull);
    expect(controller.errorMessage, '请选择医美顾问');
    expect(repository.createCalls, 0);
  });
}
