import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/auth/presentation/login_page.dart';

void main() {
  testWidgets('password-only login hides unavailable UAT actions',
      (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: LoginPage(
          onPasswordLogin: _passwordLogin,
          onVerificationCodeLogin: _verificationCodeLogin,
          onSendVerificationCode: _sendVerificationCode,
          onRegister: _noop,
          onForgotPassword: _noop,
          onGoogleLogin: () async => true,
          initialMode: LoginMode.verificationCode,
          passwordOnly: true,
        ),
      ),
    );

    expect(find.byKey(const Key('login-password-field')), findsOneWidget);
    expect(find.byKey(const Key('login-mode-selector')), findsNothing);
    expect(find.byKey(const Key('login-code-field')), findsNothing);
    expect(find.byKey(const Key('send-code-button')), findsNothing);
    expect(find.byKey(const Key('forgot-password-button')), findsNothing);
    expect(find.byKey(const Key('google-login-button')), findsNothing);
    expect(find.byKey(const Key('register-button')), findsNothing);
  });

  testWidgets('default login keeps the full password sign-in choices', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: LoginPage(
          onPasswordLogin: _passwordLogin,
          onVerificationCodeLogin: _verificationCodeLogin,
          onSendVerificationCode: _sendVerificationCode,
          onRegister: _noop,
          onForgotPassword: _noop,
          onGoogleLogin: () async => true,
          initialMode: LoginMode.password,
        ),
      ),
    );

    expect(find.byKey(const Key('login-mode-selector')), findsOneWidget);
    expect(find.byKey(const Key('forgot-password-button')), findsOneWidget);
    expect(find.byKey(const Key('google-login-button')), findsOneWidget);
    expect(find.byKey(const Key('register-button')), findsOneWidget);
  });
}

Future<void> _passwordLogin(
  String phone,
  String password, {
  required bool rememberPassword,
  required bool autoLogin,
  required bool agreementsAccepted,
}) async {}

Future<void> _verificationCodeLogin(String phone, String code) async {}

Future<void> _sendVerificationCode(String phone) async {}

void _noop() {}
