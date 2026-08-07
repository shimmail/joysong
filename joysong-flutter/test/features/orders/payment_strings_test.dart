import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_strings.dart';

void main() {
  group('PaymentStrings', () {
    test('provides Chinese and English payment copy', () {
      const chinese = PaymentStrings.chinese();
      const english = PaymentStrings.english();

      expect(chinese.paymentTitle, '确认支付');
      expect(english.paymentTitle, 'Confirm payment');
      expect(chinese.providerLabel('WECHAT_PAY'), '微信支付');
      expect(english.providerLabel('WECHAT_PAY'), 'WeChat Pay');
      expect(chinese.amount('¥100.00'), '支付金额：¥100.00');
      expect(english.amount(r'$100.00'), r'Amount: $100.00');
    });

    test('maps payment states without exposing identifiers', () {
      const strings = PaymentStrings.english();

      expect(strings.statusTitle('SUCCEEDED'), 'Payment successful');
      expect(strings.statusTitle('CANCELLED'), 'Payment cancelled');
      expect(strings.providerLabel('UNKNOWN_PROVIDER'), 'Other payment method');
      expect(strings.statusTitle('UNKNOWN_STATUS'), 'Payment result pending');
    });

    test('maps known and unknown error codes to safe localized messages', () {
      const chinese = PaymentStrings.chinese();
      const english = PaymentStrings.english();

      expect(
        chinese.errorMessage('PAYMENT_PROVIDER_UNAVAILABLE'),
        '该支付方式暂不可用，请更换支付方式。',
      );
      expect(
        english.errorMessage('PAYMENT_PROVIDER_UNAVAILABLE'),
        'This payment method is currently unavailable. Choose another method.',
      );
      expect(
        chinese.errorMessage('RAW_PROVIDER_DIAGNOSTIC'),
        '支付未完成，请稍后重试。',
      );
      expect(
        english.errorMessage('RAW_PROVIDER_DIAGNOSTIC'),
        'The payment could not be completed. Try again later.',
      );
    });
  });
}
