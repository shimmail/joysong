import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/booking/domain/booking_models.dart';
import 'package:joysong_flutter/features/booking/presentation/booking_controller.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

import 'booking_test_fixtures.dart';
import '../orders/order_test_fixtures.dart';

void main() {
  test('builds an untagged Beijing wall clock from a controlled UTC instant',
      () {
    final wallClock = beijingWallClockNow(
      DateTime.utc(2026, 8, 22, 4, 5, 6, 7, 8),
    );

    expect(wallClock, DateTime(2026, 8, 22, 12, 5, 6, 7, 8));
    expect(wallClock.isUtc, isFalse);
  });

  test('validates appointments against the same controlled Beijing wall clock',
      () async {
    final repository = FakeBookingRepository();
    final controller = BookingController(
      repository: repository,
      institutionId: 'institution-1',
      projectId: 'project-1',
      beijingClock: () => DateTime(2026, 8, 22, 12),
    );
    await controller.load();
    await controller.selectConsultant(controller.consultants.single);
    await controller.selectDoctor(controller.doctors.single);
    controller.selectAppointmentTime(DateTime(2026, 8, 22, 12, 1));

    expect(controller.currentBeijingWallClock, DateTime(2026, 8, 22, 12));
    expect(await controller.submit(), isNotNull);
    expect(repository.lastCommand?.appointmentTime.isUtc, isFalse);
  });

  test('loads one server quote and creates consultant-bound order command',
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
    controller
        .selectAppointmentTime(DateTime.now().add(const Duration(days: 2)));

    final order = await controller.submit();

    expect(order, isA<Order>());
    expect(repository.lastCommand?.institutionProjectId, 'ip-1');
    expect(repository.lastCommand?.consultantId, 'consultant-1');
    expect(repository.lastCommand?.doctorId, 'doctor-1');
    expect(repository.quoteDoctorId, 'doctor-1');
    expect(repository.quoteInstitutionProjectId, 'ip-1');
    expect(controller.travelGroundServiceQuote?.travelGroundServiceFeeMinor,
        40000);
    expect(controller.travelGroundServiceQuote?.travelGroundServiceFeeFormatted,
        r'$400.00');
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
    controller
        .selectAppointmentTime(DateTime.now().add(const Duration(days: 2)));

    expect(await controller.submit(), isNull);
    expect(controller.errorMessage, '请选择医美顾问');
    expect(repository.createCalls, 0);
  });

  test('blocks submission until the service fee quote is loaded', () async {
    final repository = FakeBookingRepository()
      ..quoteCompleter = Completer<TravelGroundServiceQuote>();
    final controller = BookingController(
      repository: repository,
      institutionId: 'institution-1',
      projectId: 'project-1',
    );
    await controller.load();
    await controller.selectConsultant(controller.consultants.single);
    final quoteLoad = controller.selectDoctor(controller.doctors.single);
    controller.selectAppointmentTime(
      DateTime.now().add(const Duration(days: 2)),
    );

    expect(controller.isQuoteLoading, isTrue);
    expect(controller.canSubmit, isFalse);
    expect(await controller.submit(), isNull);
    expect(controller.errorMessage, '旅游地接服务费尚未加载');
    expect(repository.createCalls, 0);

    repository.quoteCompleter!.complete(sampleTravelGroundServiceQuote());
    await quoteLoad;
    expect(controller.canSubmit, isTrue);
  });

  test('blocks direct submission when the service fee quote failed', () async {
    final repository = FakeBookingRepository()
      ..quoteError = const FormatException('报价不可用');
    final controller = BookingController(
      repository: repository,
      institutionId: 'institution-1',
      projectId: 'project-1',
    );
    await controller.load();
    await controller.selectConsultant(controller.consultants.single);
    await controller.selectDoctor(controller.doctors.single);
    controller.selectAppointmentTime(
      DateTime.now().add(const Duration(days: 2)),
    );

    expect(controller.canSubmit, isFalse);
    expect(await controller.submit(), isNull);
    expect(controller.errorMessage, '旅游地接服务费尚未加载');
    expect(repository.createCalls, 0);
  });
}
