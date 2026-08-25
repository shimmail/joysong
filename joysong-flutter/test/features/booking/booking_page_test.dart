import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/booking/domain/booking_models.dart';
import 'package:joysong_flutter/features/booking/presentation/booking_controller.dart';
import 'package:joysong_flutter/features/booking/presentation/booking_page.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

import 'booking_test_fixtures.dart';

void main() {
  testWidgets('shows one travel service fee and submits selected consultant', (
    tester,
  ) async {
    final repository = FakeBookingRepository();
    final controller = BookingController(
      repository: repository,
      institutionId: 'institution-1',
      projectId: 'project-1',
    );
    Order? created;
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('en'), Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: BookingPage(
          controller: controller,
          onOrderCreated: (order) => created = order,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('光子嫩肤'), findsOneWidget);
    expect(find.text('选择医美顾问'), findsOneWidget);
    expect(find.byKey(const Key('booking-consultant-select')), findsOneWidget);
    await controller.selectConsultant(controller.consultants.single);
    await controller.selectDoctor(controller.doctors.single);
    controller.selectAppointmentTime(
      DateTime.now().add(const Duration(days: 2)),
    );
    await tester.pump();
    await tester.scrollUntilVisible(
      find.byKey(const Key('booking-price-disclaimer')),
      260,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.byKey(const Key('booking-price-disclaimer')), findsOneWidget);
    expect(find.text('旅游地接服务费'), findsOneWidget);
    expect(find.text(r'$400.00'), findsOneWidget);
    expect(find.text('医疗费到院后直接向医院支付'), findsOneWidget);
    expect(find.text('面诊费'), findsNothing);
    expect(find.text('面诊金'), findsNothing);
    expect(find.text('尾款'), findsNothing);
    expect(find.text('数量'), findsNothing);
    expect(find.text('优惠券'), findsNothing);
    await tester.ensureVisible(find.byKey(const Key('booking-submit-button')));
    await tester.tap(find.byKey(const Key('booking-submit-button')));
    await tester.pumpAndSettle();

    expect(repository.createCalls, 1);
    expect(created?.id, 'order-1');
  });

  testWidgets('shows the medical aesthetics consultant empty state', (
    tester,
  ) async {
    final repository = FakeBookingRepository()..consultantResults = [];
    final controller = BookingController(
      repository: repository,
      institutionId: 'institution-1',
      projectId: 'project-1',
    );

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('en'), Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: BookingPage(controller: controller, onOrderCreated: (_) {}),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('该机构暂时没有可预约的医美顾问'), findsOneWidget);
  });

  testWidgets('retries a failed quote without changing the selected doctor', (
    tester,
  ) async {
    final repository = FakeBookingRepository()
      ..quoteError = const FormatException('报价不可用');
    final controller = BookingController(
      repository: repository,
      institutionId: 'institution-1',
      projectId: 'project-1',
    );

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('en'), Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: BookingPage(controller: controller, onOrderCreated: (_) {}),
      ),
    );
    await tester.pumpAndSettle();
    await controller.selectDoctor(controller.doctors.single);
    await tester.pump();

    expect(controller.selectedDoctor?.id, 'doctor-1');
    expect(find.text('报价不可用'), findsOneWidget);
    final retryButton = find.byKey(const Key('booking-quote-retry'));
    expect(retryButton, findsOneWidget);

    repository.quoteError = null;
    await tester.tap(retryButton);
    await tester.pumpAndSettle();

    expect(controller.selectedDoctor?.id, 'doctor-1');
    expect(controller.travelGroundServiceQuote, isNotNull);
    await tester.scrollUntilVisible(
      find.byKey(const Key('booking-price-disclaimer')),
      260,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text(r'$400.00'), findsOneWidget);
    expect(find.byKey(const Key('booking-quote-retry')), findsNothing);
  });

  testWidgets('switching doctors shows that doctor project quote', (
    tester,
  ) async {
    final repository = _DoctorPricedBookingRepository()
      ..doctorResults = [
        sampleDoctor(),
        const BookingDoctor(
          id: 'doctor-2',
          name: '王医生',
          title: '副主任医师',
          avatar: '',
          specialties: '皮肤美容',
          isVerified: true,
        ),
      ];
    final controller = BookingController(
      repository: repository,
      institutionId: 'institution-1',
      projectId: 'project-1',
    );

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('en'), Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: BookingPage(controller: controller, onOrderCreated: (_) {}),
      ),
    );
    await tester.pumpAndSettle();
    await controller.selectDoctor(controller.doctors.first);
    await tester.pump();
    await tester.scrollUntilVisible(
      find.byKey(const Key('booking-price-disclaimer')),
      260,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text(r'$400.00'), findsOneWidget);

    await controller.selectDoctor(controller.doctors.last);
    await tester.pump();

    expect(find.text(r'$525.00'), findsOneWidget);
    expect(repository.quoteDoctorId, 'doctor-2');
    expect(repository.quoteInstitutionProjectId, 'ip-1');
  });
}

final class _DoctorPricedBookingRepository extends FakeBookingRepository {
  @override
  Future<TravelGroundServiceQuote> getTravelGroundServiceQuote({
    required String doctorId,
    required String institutionProjectId,
  }) {
    quoteDoctorId = doctorId;
    quoteInstitutionProjectId = institutionProjectId;
    return Future.value(
      TravelGroundServiceQuote(
        currency: 'USD',
        medicalListPriceMinor: doctorId == 'doctor-1' ? 100000 : 150000,
        platformServiceRateBps: doctorId == 'doctor-1' ? 4000 : 3500,
        travelGroundServiceFeeMinor: doctorId == 'doctor-1' ? 40000 : 52500,
      ),
    );
  }
}
