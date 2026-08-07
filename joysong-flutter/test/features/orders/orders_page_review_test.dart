import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_controller.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_page.dart';

import 'order_test_fixtures.dart';

void main() {
  testWidgets('reviewed order card opens edit without opening details', (
    tester,
  ) async {
    final repository = FakeOrdersRepository()
      ..orders = [
        sampleOrder(
          status: OrderStatus.pendingSettlement,
          hasReview: true,
        ),
      ];
    final controller = OrdersController(repository);
    addTearDown(controller.dispose);
    var detailCalls = 0;
    var editCalls = 0;

    await tester.pumpWidget(
      MaterialApp(
        home: OrdersPage(
          controller: controller,
          onOrderSelected: (_) => detailCalls += 1,
          onEditReview: (_) async => editCalls += 1,
        ),
      ),
    );
    await tester.pumpAndSettle();

    final editButton = find.byKey(const Key('edit-review-card-order-1'));
    expect(editButton, findsOneWidget);
    await tester.tap(editButton);
    await tester.pump();

    expect(editCalls, 1);
    expect(detailCalls, 0);
  });

  testWidgets('unreviewed order card does not expose edit review', (
    tester,
  ) async {
    final repository = FakeOrdersRepository()
      ..orders = [sampleOrder(status: OrderStatus.completed)];
    final controller = OrdersController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        home: OrdersPage(
          controller: controller,
          onOrderSelected: (_) {},
          onEditReview: (_) async {},
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('edit-review-card-order-1')), findsNothing);
  });
}
