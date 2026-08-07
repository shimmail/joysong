import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_action_launcher.dart';

void main() {
  test('accepts HTTPS payment redirects in debug builds', () {
    expect(
      isAllowedPaymentRedirect(Uri.parse('https://sandbox.paypal.com/pay')),
      isTrue,
    );
  });

  test('rejects unsafe redirect schemes and missing hosts', () {
    expect(
      isAllowedPaymentRedirect(Uri.parse('javascript:alert(1)')),
      isFalse,
    );
    expect(isAllowedPaymentRedirect(Uri.parse('https:///pay')), isFalse);
  });
}
