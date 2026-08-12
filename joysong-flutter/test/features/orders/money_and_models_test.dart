import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/domain/money.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

import 'order_test_fixtures.dart';

void main() {
  test('Money keeps decimal arithmetic exact and formats currency', () {
    final result = Money.parse('0.10').times(3).subtract(Money.parse('0.20'));

    expect(result.toDecimalString(), '0.1');
    expect(result.formatted, r'$0.10');
    expect(Money.parse('1.00'), Money.parse('1'));
  });

  test('Order maps current status and safely degrades unknown status', () {
    final order = Order.fromJson(sampleOrderJson(status: 'BALANCE_PAID'));
    final unknown = Order.fromJson(sampleOrderJson(status: 'FUTURE_STATUS'));

    expect(order.status, OrderStatus.balancePaid);
    expect(order.showsCompletionCode, isTrue);
    expect(order.amount.toDecimalString(), '1280.5');
    expect(order.consultantId, 'consultant-1');
    expect(order.consultantName, '李咨询师');
    expect(unknown.status, OrderStatus.unknown);
  });

  test('Order requires protocol-critical id and status-safe fields', () {
    final json = sampleOrderJson()..remove('id');

    expect(() => Order.fromJson(json), throwsFormatException);
  });

  test('Settlement maps the consumer-safe minor-unit contract', () {
    final settlement = Settlement.fromJson({
      'settlementId': 42,
      'orderId': 'order-1',
      'currency': 'USD',
      'grossTotalPaid': {'currency': 'USD', 'minor': 128050},
      'netSettled': {'currency': 'USD', 'minor': 120000},
      'state': 'RELEASED',
      'settlementDueAt': '2026-08-12T10:00:00',
      'settlementCreatedAt': '2026-08-11T10:00:00',
      'releasedAt': '2026-08-13T10:00:00',
    });

    expect(settlement.settlementId, 42);
    expect(settlement.grossTotalPaidMinor, 128050);
    expect(settlement.netSettledMinor, 120000);
    expect(settlement.grossTotalPaidMinor, isNot(settlement.netSettledMinor));
    expect(settlement.currency, 'USD');
    expect(settlement.releasedAt, DateTime(2026, 8, 13, 10));
  });
}
