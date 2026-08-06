import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/domain/money.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

import 'order_test_fixtures.dart';

void main() {
  test('Money keeps decimal arithmetic exact and formats currency', () {
    final result = Money.parse('0.10').times(3).subtract(Money.parse('0.20'));

    expect(result.toDecimalString(), '0.1');
    expect(result.formatted, '¥0.10');
    expect(Money.parse('1.00'), Money.parse('1'));
  });

  test('Order maps current status and safely degrades unknown status', () {
    final order = Order.fromJson(sampleOrderJson(status: 'BALANCE_PAID'));
    final unknown = Order.fromJson(sampleOrderJson(status: 'FUTURE_STATUS'));

    expect(order.status, OrderStatus.balancePaid);
    expect(order.showsCompletionCode, isTrue);
    expect(order.amount.toDecimalString(), '1280.5');
    expect(unknown.status, OrderStatus.unknown);
  });

  test('Order requires protocol-critical id and status-safe fields', () {
    final json = sampleOrderJson()..remove('id');

    expect(() => Order.fromJson(json), throwsFormatException);
  });
}
