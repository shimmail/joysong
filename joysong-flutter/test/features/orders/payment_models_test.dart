import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/domain/payment_models.dart';

import 'payment_test_fixtures.dart';

void main() {
  test('maps USD Alipay+ service-fee attempt and redirect action', () {
    final payment = PaymentAttempt.fromJson(
      paymentAttemptJson(
        status: 'REQUIRES_ACTION',
        nextAction: {
          'type': 'REDIRECT',
          'url': 'https://cashier.alipayplus.com/pay/session-1',
        },
      ),
    );

    expect(payment.paymentType, PaymentType.travelGroundServiceFee);
    expect(payment.provider, PaymentProvider.alipayPlus);
    expect(payment.status, PaymentStatus.requiresAction);
    expect(payment.requiresAction, isTrue);
    expect(payment.nextAction?.type, PaymentNextActionType.redirect);
    expect(
      payment.nextAction?.url,
      'https://cashier.alipayplus.com/pay/session-1',
    );
    expect(payment.currency, 'USD');
    expect(payment.amountMinor, 40000);
    expect(payment.refundedAmountMinor, 0);
    expect(payment.expiresAt, DateTime(2026, 8, 7, 12, 30));
  });

  test('fails closed for non-redirect next actions', () {
    final unsupported = PaymentNextAction.fromJson({
      'type': 'WECHAT_SDK_PARAMS',
      'params': {'prepayId': 'prepay-1', 'nonceStr': 'nonce-1'},
    });

    expect(unsupported.type, PaymentNextActionType.unknown);
    expect(unsupported.url, isNull);
  });

  test('rejects fractional minor-unit amounts', () {
    expect(
      () => PaymentAttempt.fromJson(paymentAttemptJson(amountMinor: 40000.5)),
      throwsFormatException,
    );
    expect(
      () => PaymentAttempt.fromJson(
        paymentAttemptJson(refundedAmountMinor: 1.5),
      ),
      throwsFormatException,
    );
  });

  test('unknown server values degrade safely for forward compatibility', () {
    final payment = PaymentAttempt.fromJson(
      paymentAttemptJson(
        paymentType: 'INSTALLMENT',
        provider: 'FUTURE_PROVIDER',
        status: 'AWAITING_REVIEW',
      ),
    );

    expect(payment.paymentType, PaymentType.unknown);
    expect(payment.provider, PaymentProvider.unknown);
    expect(payment.status, PaymentStatus.unknown);
  });

  test('requires protocol-critical payment fields', () {
    final json = paymentAttemptJson()..remove('id');

    expect(() => PaymentAttempt.fromJson(json), throwsFormatException);
  });
}
