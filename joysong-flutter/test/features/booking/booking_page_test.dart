import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/booking/presentation/booking_controller.dart';
import 'package:joysong_flutter/features/booking/presentation/booking_page.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

import 'booking_test_fixtures.dart';

void main() {
  testWidgets('shows booking inputs and submits selected doctor and time', (
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
        home: BookingPage(
          controller: controller,
          onOrderCreated: (order) => created = order,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('光子嫩肤'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.byKey(const Key('booking-price-disclaimer')),
      260,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.textContaining('最终价格和优惠由服务端'), findsOneWidget);
    await controller.selectDoctor(controller.doctors.single);
    controller.selectAppointmentTime(
      DateTime.now().add(const Duration(days: 2)),
    );
    await tester.pump();
    await tester.ensureVisible(find.byKey(const Key('booking-submit-button')));
    await tester.tap(find.byKey(const Key('booking-submit-button')));
    await tester.pumpAndSettle();

    expect(repository.createCalls, 1);
    expect(created?.id, 'order-1');
  });
}
