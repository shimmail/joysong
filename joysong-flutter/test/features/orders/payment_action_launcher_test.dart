import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_action_launcher.dart';

void main() {
  const hosts = {'cashier.alipayplus.com'};

  test('accepts only the configured Alipay+ HTTPS host and subdomains', () {
    expect(
      isAllowedPaymentRedirect(
        Uri.parse('https://cashier.alipayplus.com/pay/session-1'),
        allowedRedirectHosts: hosts,
      ),
      isTrue,
    );
    expect(
      isAllowedPaymentRedirect(
        Uri.parse('https://sg.cashier.alipayplus.com/pay/session-1'),
        allowedRedirectHosts: hosts,
      ),
      isTrue,
    );
  });

  test('rejects unsafe schemes, credentials and lookalike hosts', () {
    for (final raw in [
      'http://cashier.alipayplus.com/pay',
      'alipay://cashier/pay',
      'https://user@cashier.alipayplus.com/pay',
      'https://cashier.alipayplus.com.evil.test/pay',
      'https:///pay',
    ]) {
      expect(
        isAllowedPaymentRedirect(
          Uri.parse(raw),
          allowedRedirectHosts: hosts,
        ),
        isFalse,
        reason: raw,
      );
    }
  });

  test('fails closed for empty allowlist and implicit IP hosts', () {
    expect(
      isAllowedPaymentRedirect(
        Uri.parse('https://cashier.alipayplus.com/pay'),
        allowedRedirectHosts: const {},
      ),
      isFalse,
    );
    expect(
      isAllowedPaymentRedirect(
        Uri.parse('https://203.0.113.10/pay'),
        allowedRedirectHosts: hosts,
      ),
      isFalse,
    );
    expect(
      isAllowedPaymentRedirect(
        Uri.parse('https://203.0.113.10/pay'),
        allowedRedirectHosts: const {'203.0.113.10'},
      ),
      isTrue,
    );
  });
}
