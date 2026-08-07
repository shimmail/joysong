import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_action_launcher.dart';

void main() {
  test('accepts the real Stripe Checkout HTTPS host', () {
    expect(
      isAllowedPaymentRedirect(Uri.parse('https://checkout.stripe.com/c/pay/test')),
      isTrue,
    );
  });

  test('rejects unsafe redirect schemes and missing hosts', () {
    expect(
      isAllowedPaymentRedirect(Uri.parse('javascript:alert(1)')),
      isFalse,
    );
    expect(isAllowedPaymentRedirect(Uri.parse('https:///pay')), isFalse);
    expect(
      isAllowedPaymentRedirect(Uri.parse('https://malicious.example/pay')),
      isFalse,
    );
  });
}
