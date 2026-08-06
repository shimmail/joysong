import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';

void main() {
  test('maps account profile and masks bound phone', () {
    final profile = AccountSecurityProfile.fromJson({
      'id': 'user-1',
      'phone': '+8613800000000',
      'email': null,
      'hasPassword': true,
    });

    expect(profile.id, 'user-1');
    expect(profile.hasBoundPhone, isTrue);
    expect(profile.maskedPhone, '+8613******00');
    expect(profile.maskedPhone, isNot(contains('13800000000')));
  });

  test('requires protocol-critical hasPassword capability', () {
    expect(
      () => AccountSecurityProfile.fromJson({
        'id': 'user-1',
        'phone': '+8613800000000',
      }),
      throwsFormatException,
    );
  });

  test('validates server password, code, and E.164 constraints', () {
    expect(() => validateNewPassword('short'), throwsArgumentError);
    expect(() => validateVerificationCode('12345'), throwsArgumentError);
    expect(() => validateE164Phone('13800000000'), throwsArgumentError);
    expect(validateE164Phone('+86 138-0000-0000'), '+8613800000000');
  });
}
