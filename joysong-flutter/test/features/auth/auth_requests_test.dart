import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/auth/data/auth_requests.dart';

void main() {
  test('normalizes harmless E.164 formatting', () {
    expect(normalizeE164Phone('+86 138-0000-0000'), '+8613800000000');
    expect(normalizeE164Phone('008613800000000'), '+8613800000000');
  });

  test('rejects a local phone number without country code', () {
    expect(
      () => normalizeE164Phone('13800000000'),
      throwsArgumentError,
    );
  });

  test('maps registration fields to the server contract', () {
    final request = RegisterRequest(
      phone: '+8613800000000',
      code: '123456',
      password: 'password8',
    );

    expect(request.toJson(), {
      'phone': '+8613800000000',
      'code': '123456',
      'password': 'password8',
    });
  });

  test('validates verification code and new password locally', () {
    expect(
      () => CodeLoginRequest(phone: '+8613800000000', code: '12345'),
      throwsArgumentError,
    );
    expect(
      () => ResetPasswordRequest(
        phone: '+8613800000000',
        code: '123456',
        newPassword: 'short',
      ),
      throwsArgumentError,
    );
  });
}
