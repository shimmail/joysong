import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/domain/payment_models.dart';

import 'payment_test_fixtures.dart';

void main() {
  test('maps payment attempt and Stripe next action', () {
    final payment = PaymentAttempt.fromJson(
      paymentAttemptJson(
        status: 'REQUIRES_ACTION',
        nextAction: {
          'type': 'STRIPE_CLIENT_SECRET',
          'clientSecret': 'pi_secret',
        },
      ),
    );

    expect(payment.paymentType, PaymentType.consultationFee);
    expect(payment.provider, PaymentProvider.stripe);
    expect(payment.status, PaymentStatus.requiresAction);
    expect(payment.requiresAction, isTrue);
    expect(
      payment.nextAction?.type,
      PaymentNextActionType.stripeClientSecret,
    );
    expect(payment.nextAction?.clientSecret, 'pi_secret');
    expect(payment.amountMinor, 10000);
  });

  test('maps every supported next action payload', () {
    final redirect = PaymentNextAction.fromJson({
      'type': 'REDIRECT',
      'url': 'https://pay.example/authorize',
    });
    final wechat = PaymentNextAction.fromJson({
      'type': 'WECHAT_SDK_PARAMS',
      'params': {'prepayId': 'prepay-1', 'nonceStr': 'nonce-1'},
    });
    final alipay = PaymentNextAction.fromJson({
      'type': 'ALIPAY_ORDER_STRING',
      'orderString': 'signed-order',
    });

    expect(redirect.url, 'https://pay.example/authorize');
    expect(wechat.params?['prepayId'], 'prepay-1');
    expect(alipay.orderString, 'signed-order');
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
