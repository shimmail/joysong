import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/auth/domain/auth_repository.dart';
import 'package:joysong_flutter/features/auth/presentation/auth_controller.dart';
import 'package:joysong_flutter/features/auth/presentation/login_page.dart';

void main() {
  test('registration and code sending errors follow the selected language', () async {
    var english = true;
    final controller = AuthController(
      _RejectedLoginRepository('手机号已注册'),
      messageResolver: (chinese, translation) => english ? translation : chinese,
    );
    addTearDown(controller.dispose);
    for (final language in [true, false]) {
      english = language;
      expect(await controller.registerAccount('+8613900000000', '123456', 'password123'), isFalse);
      expect(controller.errorMessage, english
          ? 'This phone number is already registered.' : '手机号已注册');
      await expectLater(controller.sendCode('+8613900000000'), throwsUnsupportedError);
      expect(controller.errorMessage, english
          ? 'Failed to send the verification code.' : '验证码发送失败');
    }
  });

  for (final error in {
    '密码错误，请重试': 'Incorrect password. Please try again.',
    '该手机号未注册，请先注册':
        'This phone number is not registered. Please sign up first.',
  }.entries) {
    testWidgets('login error follows selected language: ${error.key}',
        (tester) async {
      final locale = AppLocaleController(
        preferenceStore: _MemoryLocaleStore(),
      );
      final controller = AuthController(
        _RejectedLoginRepository(error.key),
        messageResolver: (chinese, english) =>
            locale.language == AppLanguage.english ? english : chinese,
      );
      addTearDown(controller.dispose);
      addTearDown(locale.dispose);

      await tester.pumpWidget(
        AppLocaleScope(
          controller: locale,
          child: MaterialApp(
            home: ListenableBuilder(
              listenable: controller,
              builder: (context, _) => LoginPage(
                onPasswordLogin: controller.loginWithPassword,
                onVerificationCodeLogin: _verificationCodeLogin,
                onSendVerificationCode: _sendVerificationCode,
                onRegister: _noop,
                onForgotPassword: _noop,
                initialPhone: '+8613900000000',
                initialPassword: 'wrong-password',
                initialAgreementsAccepted: true,
                initialMode: LoginMode.password,
                isLoading: controller.isBusy,
                errorMessage: controller.errorMessage,
              ),
            ),
          ),
        ),
      );

      for (final language in AppLanguage.values) {
        await locale.setLanguage(language);
        await tester.pumpAndSettle();
        final submit = find.byKey(const Key('login-submit-button'));
        await tester.ensureVisible(submit);
        await tester.tap(submit);
        await tester.pumpAndSettle();

        final english = language == AppLanguage.english;
        expect(find.text(english ? error.value : error.key), findsOneWidget);
        expect(find.text(english ? error.key : error.value), findsNothing);
        expect(controller.isBusy, isFalse);
        expect(controller.currentUser, isNull);
      }
    });
  }

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

final class _RejectedLoginRepository implements AuthRepository {
  _RejectedLoginRepository(this.message);

  final String message;

  @override
  Future<AuthSession> register({
    required String phone,
    required String code,
    required String password,
  }) async => throw ApiException(message: message, httpStatus: 400);

  @override
  Future<AuthSession> loginWithPassword({
    required String phone,
    required String password,
  }) async =>
      throw ApiException(message: message, httpStatus: 400);

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnsupportedError(invocation.memberName.toString());
}

final class _MemoryLocaleStore implements LocalePreferenceStore {
  @override
  Future<String?> readLanguageCode() async => null;

  @override
  Future<void> writeLanguageCode(String value) async {}
}
