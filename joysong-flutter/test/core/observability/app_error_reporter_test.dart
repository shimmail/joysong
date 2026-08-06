import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/observability/app_error_reporter.dart';

void main() {
  test('redacts tokens, passwords, codes and identity numbers', () {
    final result = redactSensitiveText(
      'Bearer abc.def accessToken=secret refreshToken:refresh '
      'password="hello" verificationCode=123456 idNumber=110101199001011234',
    );

    expect(result, isNot(contains('abc.def')));
    expect(result, isNot(contains('secret')));
    expect(result, isNot(contains('refreshToken:refresh')));
    expect(result, isNot(contains('hello')));
    expect(result, isNot(contains('123456')));
    expect(result, isNot(contains('110101199001011234')));
  });
}
