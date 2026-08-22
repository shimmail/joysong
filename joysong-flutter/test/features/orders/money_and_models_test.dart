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

  test('travel-service entitlements come only from explicit server flags', () {
    final order = Order.fromJson(
      sampleOrderJson(
        status: 'PENDING_SERVICE_FEE',
        paymentFlow: 'TRAVEL_GROUND_SERVICE_ONLY',
        paidAmount: '1.00',
        consultantId: 'consultant-must-not-imply-access',
        consultantName: '不能据此激活',
        serviceActivated: false,
        consultantDetailsVisible: false,
        serviceMessagingEnabled: false,
      ),
    );

    expect(order.status, OrderStatus.pendingServiceFee);
    expect(order.paymentFlow, OrderPaymentFlow.travelGroundServiceOnly);
    expect(order.travelGroundServiceFeeMinor, 40000);
    expect(order.serviceActivated, isFalse);
    expect(order.consultantDetailsVisible, isFalse);
    expect(order.canOpenServiceConversation, isFalse);
  });

  test('maps active and refund travel-service states and visible fields', () {
    final active = Order.fromJson(
      sampleOrderJson(
        status: 'SERVICE_ACTIVE',
        paymentFlow: 'TRAVEL_GROUND_SERVICE_ONLY',
        consultantAvatar: 'https://cdn.example/consultant.jpg',
        consultantBound: true,
        serviceActivated: true,
        consultantDetailsVisible: true,
        serviceConversationReadable: true,
        serviceMessagingEnabled: true,
      ),
    );

    expect(active.status, OrderStatus.serviceActive);
    expect(active.consultantAvatar, 'https://cdn.example/consultant.jpg');
    expect(active.canOpenServiceConversation, isTrue);
    expect(OrderStatus.fromWire('REFUND_REVIEW'), OrderStatus.refundReview);
    expect(
      OrderStatus.fromWire('REFUND_PROCESSING'),
      OrderStatus.refundProcessing,
    );
    expect(
      RefundStatus.fromWire('REFUND_PROCESSING'),
      RefundStatus.processing,
    );
  });

  test('completion and refund eligibility remain payment-flow aware', () {
    final activeTravel = sampleOrder(
      status: OrderStatus.serviceActive,
      paymentFlow: OrderPaymentFlow.travelGroundServiceOnly,
    );
    final completedTravel = sampleOrder(
      status: OrderStatus.completed,
      paymentFlow: OrderPaymentFlow.travelGroundServiceOnly,
    );
    final refundingTravel = sampleOrder(
      status: OrderStatus.completed,
      paymentFlow: OrderPaymentFlow.travelGroundServiceOnly,
      refundStatus: RefundStatus.pending,
    );
    final processingRefundTravel = sampleOrder(
      status: OrderStatus.completed,
      paymentFlow: OrderPaymentFlow.travelGroundServiceOnly,
      refundStatus: RefundStatus.processing,
    );
    final pendingLegacy = sampleOrder(status: OrderStatus.pendingCompletion);
    final activeLegacy = sampleOrder(status: OrderStatus.serviceActive);

    expect(activeTravel.canConfirmCompletion, isTrue);
    expect(completedTravel.canRequestRefund, isTrue);
    expect(refundingTravel.canRequestRefund, isFalse);
    expect(processingRefundTravel.canRequestRefund, isFalse);
    expect(pendingLegacy.canConfirmCompletion, isTrue);
    expect(activeLegacy.canConfirmCompletion, isFalse);
    expect(activeLegacy.canRequestRefund, isFalse);
  });

  test('RefundDetail exposes the server rejection reason', () {
    final detail = RefundDetail.fromJson({
      'id': 'refund-1',
      'orderId': 'order-1',
      'amount': '400.00',
      'reason': '行程变更',
      'description': '',
      'status': 'REJECTED',
      'rejectReason': '服务已经完成，需补充材料',
      'createdAt': '2026-08-22T10:00:00',
    });

    expect(detail.rejectReason, '服务已经完成，需补充材料');
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
