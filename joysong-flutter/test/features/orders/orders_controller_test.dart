import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_controller.dart';

import 'order_test_fixtures.dart';

void main() {
  test('orders controller forwards status and offset pagination', () async {
    final repository = FakeOrdersRepository()
      ..orders = [
        sampleOrder(id: '1'),
        sampleOrder(id: '2'),
        sampleOrder(id: '3'),
      ];
    final controller = OrdersController(repository, pageSize: 2);

    await controller.load(filter: OrderStatus.pendingPayment);
    await controller.loadMore();

    expect(controller.orders.map((order) => order.id), ['1', '2', '3']);
    expect(repository.lastStatus, OrderStatus.pendingPayment);
    expect(repository.lastOffset, 2);
    expect(controller.hasMore, isFalse);
  });

  test('detail controller prevents duplicate write action', () async {
    final repository = FakeOrdersRepository();
    final completer = Completer<Order>();
    repository.actionFuture = completer.future;
    final controller = OrderDetailController(repository, orderId: 'order-1');
    await controller.load();

    final first = controller.payConsultation();
    final second = await controller.payConsultation();
    expect(second, isFalse);
    expect(repository.actionCalls, 1);

    completer.complete(sampleOrder(status: OrderStatus.consultationPaid));
    expect(await first, isTrue);
    expect(controller.order?.status, OrderStatus.consultationPaid);
  });

  test('refund rejects an empty reason without calling repository', () async {
    final repository = FakeOrdersRepository();
    final controller = OrderDetailController(repository, orderId: 'order-1');

    final result = await controller.requestRefund(reason: '   ');

    expect(result, isFalse);
    expect(controller.errorMessage, '请选择或填写退款原因');
    expect(repository.actionCalls, 0);
  });
}
