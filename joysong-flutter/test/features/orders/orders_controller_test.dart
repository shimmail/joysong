import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
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

    final first = controller.requestVerificationCode();
    final second = await controller.requestVerificationCode();
    expect(second, isFalse);
    expect(repository.actionCalls, 1);

    completer.complete(
      sampleOrder(status: OrderStatus.consultationPaid, verifyCode: '123456'),
    );
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

  test('detail treats settlement-not-generated as pending, not an error',
      () async {
    final repository = FakeOrdersRepository()
      ..orders = [sampleOrder(status: OrderStatus.completed)]
      ..settlementError = const ApiException(
        message: 'SETTLEMENT_NOT_GENERATED',
        httpStatus: 409,
        businessCode: 409,
      );
    final controller = OrderDetailController(repository, orderId: 'order-1');

    await controller.load();

    expect(controller.isSettlementGenerationPending, isTrue);
    expect(controller.settlementErrorMessage, isNull);
  });

  test('detail keeps settlement support errors visible and retryable',
      () async {
    final repository = FakeOrdersRepository()
      ..orders = [sampleOrder(status: OrderStatus.completed)]
      ..settlementError = const FormatException('结算详情不是 JSON 对象');
    final controller = OrderDetailController(repository, orderId: 'order-1');

    await controller.load();

    expect(controller.settlementErrorMessage, '结算详情不是 JSON 对象');
    repository.settlementError = null;
    await controller.retrySettlement();
    expect(controller.settlementErrorMessage, isNull);
    expect(controller.settlement, isNotNull);
  });
}
