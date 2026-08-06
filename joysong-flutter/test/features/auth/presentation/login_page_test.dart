import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/auth/presentation/login_page.dart';

void main() {
  Widget buildSubject({
    PasswordLoginCallback? onPasswordLogin,
    VerificationCodeLoginCallback? onVerificationCodeLogin,
    SendVerificationCodeCallback? onSendVerificationCode,
    VoidCallback? onRegister,
    VoidCallback? onForgotPassword,
    bool isLoading = false,
    String? errorMessage,
    String initialPhone = '',
    String initialPassword = '',
    bool initialRememberPassword = false,
    bool initialAutoLogin = false,
    AppLocaleController? localeController,
  }) {
    final controller = localeController ??
        AppLocaleController(preferenceStore: _MemoryLocalePreferenceStore());
    return AppLocaleScope(
      controller: controller,
      child: MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: LoginPage(
          onPasswordLogin: onPasswordLogin ??
              (
                _,
                __, {
                required rememberPassword,
                required autoLogin,
                required agreementsAccepted,
              }) async {},
          onVerificationCodeLogin: onVerificationCodeLogin ?? (_, __) async {},
          onSendVerificationCode: onSendVerificationCode ?? (_) async {},
          onRegister: onRegister ?? () {},
          onForgotPassword: onForgotPassword ?? () {},
          isLoading: isLoading,
          errorMessage: errorMessage,
          initialPhone: initialPhone,
          initialPassword: initialPassword,
          initialRememberPassword: initialRememberPassword,
          initialAutoLogin: initialAutoLogin,
          initialMode: LoginMode.password,
        ),
      ),
    );
  }

  testWidgets('hides the brand subtitle and verification-code hint', (
    tester,
  ) async {
    await tester.pumpWidget(buildSubject());

    expect(find.text('JOYSONG'), findsNothing);

    await tester.tap(find.text('验证码登录'));
    await tester.pumpAndSettle();

    expect(find.text('请输入验证码'), findsNothing);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('switches all login copy between Chinese and English', (
    tester,
  ) async {
    final localeController = AppLocaleController(
      preferenceStore: _MemoryLocalePreferenceStore(),
    );
    await tester.pumpWidget(
      buildSubject(localeController: localeController),
    );

    expect(find.text('密码登录'), findsOneWidget);
    expect(find.text('请输入手机号'), findsOneWidget);
    expect(find.text('记住密码'), findsOneWidget);

    await tester.tap(find.byKey(const Key('login-language-toggle')));
    await tester.pumpAndSettle();

    expect(localeController.language, AppLanguage.english);
    expect(find.text('Password sign-in'), findsOneWidget);
    expect(find.text('Phone number'), findsOneWidget);
    expect(find.text('Remember'), findsOneWidget);
    expect(find.text('Auto sign-in'), findsOneWidget);
    expect(find.text('Forgot password?'), findsOneWidget);
    expect(find.text('Sign in'), findsOneWidget);
    expect(find.text('Register'), findsOneWidget);

    await tester.tap(find.byKey(const Key('login-language-toggle')));
    await tester.pumpAndSettle();

    expect(localeController.language, AppLanguage.chinese);
    expect(find.text('密码登录'), findsOneWidget);
    expect(find.text('记住密码'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('localizes country picker title, search and country names', (
    tester,
  ) async {
    final localeController = AppLocaleController(
      preferenceStore: _MemoryLocalePreferenceStore(),
      initialLanguage: AppLanguage.english,
    );
    await tester.pumpWidget(
      buildSubject(localeController: localeController),
    );

    await tester.tap(find.byKey(const Key('country-code-button')));
    await tester.pumpAndSettle();
    expect(find.text('Select country or region'), findsOneWidget);
    expect(find.text('Search country or calling code'), findsOneWidget);

    await tester.enterText(
      find.byKey(const Key('country-search-field')),
      'United States',
    );
    await tester.pump();
    expect(
      find.descendant(
        of: find.byKey(const Key('country-option-US')),
        matching: find.text('United States'),
      ),
      findsOneWidget,
    );
    expect(find.byKey(const Key('country-option-US')), findsOneWidget);
    expect(find.byKey(const Key('country-option-CN')), findsNothing);
    await tester.tap(find.byKey(const Key('country-option-US')));
    await tester.pumpAndSettle();
    expect(find.text('+1'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('shows English validation and agreement guidance', (
    tester,
  ) async {
    final localeController = AppLocaleController(
      preferenceStore: _MemoryLocalePreferenceStore(),
      initialLanguage: AppLanguage.english,
    );
    await tester.pumpWidget(
      buildSubject(localeController: localeController),
    );

    await tester.ensureVisible(find.byKey(const Key('login-submit-button')));
    await tester.tap(find.byKey(const Key('login-submit-button')));
    await tester.pump();
    expect(find.text('Enter your phone number'), findsOneWidget);
    expect(find.text('Enter your password'), findsOneWidget);

    await tester.enterText(
      find.byKey(const Key('login-phone-field')),
      '13800000000',
    );
    await tester.enterText(
      find.byKey(const Key('login-password-field')),
      'password123',
    );
    await tester.ensureVisible(
      find.byKey(const Key('login-submit-button')),
    );
    await tester.tap(find.byKey(const Key('login-submit-button')));
    await tester.pump();
    expect(
      find.text(
        'Please read and accept the User Agreement and Privacy Policy',
      ),
      findsOneWidget,
    );

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('submits verification-code login with normalized phone', (
    tester,
  ) async {
    String? submittedPhone;
    String? submittedCode;

    await tester.pumpWidget(
      buildSubject(
        onVerificationCodeLogin: (phone, code) async {
          submittedPhone = phone;
          submittedCode = code;
        },
      ),
    );

    await tester.enterText(
      find.byKey(const Key('login-phone-field')),
      '13800000000',
    );
    await tester.tap(find.text('验证码登录'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('login-code-field')),
      '123456',
    );
    await tester.ensureVisible(find.byKey(const Key('agreements-checkbox')));
    await tester.tap(find.byKey(const Key('agreements-checkbox')));
    await tester.ensureVisible(find.byKey(const Key('login-submit-button')));
    await tester.tap(find.byKey(const Key('login-submit-button')));
    await tester.pump();

    expect(submittedPhone, '+8613800000000');
    expect(submittedCode, '123456');
    expect(find.text('邮箱'), findsNothing);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('keeps the selected country code visible and aligned', (
    tester,
  ) async {
    await tester.pumpWidget(buildSubject());

    expect(find.byKey(const Key('selected-country-code')), findsOneWidget);
    expect(find.text('+86'), findsOneWidget);

    final fieldRect = tester.getRect(
      find.byKey(const Key('login-phone-field')),
    );
    final buttonRect = tester.getRect(
      find.byKey(const Key('country-code-button')),
    );
    expect((fieldRect.center.dy - buttonRect.center.dy).abs(), lessThan(1));
    expect(buttonRect.left, greaterThanOrEqualTo(fieldRect.left));
    expect(buttonRect.right, lessThan(fieldRect.right));

    final button = tester.widget<TextButton>(
      find.byKey(const Key('country-code-button')),
    );
    expect(
      button.style?.overlayColor?.resolve({WidgetState.pressed}),
      Colors.transparent,
    );

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('selects a country and submits the full international phone', (
    tester,
  ) async {
    String? submittedPhone;
    await tester.pumpWidget(
      buildSubject(
        onPasswordLogin: (
          phone,
          _, {
          required rememberPassword,
          required autoLogin,
          required agreementsAccepted,
        }) async {
          submittedPhone = phone;
        },
      ),
    );

    await tester.tap(find.byKey(const Key('country-code-button')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('country-option-US')));
    await tester.pumpAndSettle();

    expect(find.text('+1'), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('login-phone-field')),
      '2025550123',
    );
    await tester.enterText(
      find.byKey(const Key('login-password-field')),
      'password123',
    );
    await tester.tap(find.byKey(const Key('agreements-checkbox')));
    await tester.ensureVisible(find.byKey(const Key('login-submit-button')));
    await tester.tap(find.byKey(const Key('login-submit-button')));
    await tester.pump();

    expect(submittedPhone, '+12025550123');

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('splits a remembered international phone for editing', (
    tester,
  ) async {
    await tester.pumpWidget(
      buildSubject(initialPhone: '+852 9123 4567'),
    );

    expect(find.text('+852'), findsOneWidget);
    final phoneField = tester.widget<TextFormField>(
      find.byKey(const Key('login-phone-field')),
    );
    expect(phoneField.controller?.text, '91234567');

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('requires agreement before password login', (tester) async {
    var loginCalls = 0;
    await tester.pumpWidget(
      buildSubject(
        onPasswordLogin: (
          _,
          __, {
          required rememberPassword,
          required autoLogin,
          required agreementsAccepted,
        }) async {
          loginCalls += 1;
        },
      ),
    );

    await tester.enterText(
      find.byKey(const Key('login-phone-field')),
      '13800000000',
    );
    await tester.enterText(
      find.byKey(const Key('login-password-field')),
      'password123',
    );
    await tester.ensureVisible(find.byKey(const Key('login-submit-button')));
    await tester.tap(find.byKey(const Key('login-submit-button')));
    await tester.pump();

    expect(loginCalls, 0);
    expect(find.text('请先阅读并同意用户协议和隐私政策'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('sends verification code and starts countdown', (tester) async {
    String? codePhone;
    await tester.pumpWidget(
      buildSubject(
        onSendVerificationCode: (phone) async {
          codePhone = phone;
        },
      ),
    );

    await tester.enterText(
      find.byKey(const Key('login-phone-field')),
      '13800000000',
    );
    await tester.tap(find.text('验证码登录'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('send-code-button')));
    await tester.pump();

    expect(codePhone, '+8613800000000');
    expect(find.text('60 秒后重试'), findsOneWidget);

    await tester.pump(const Duration(seconds: 1));
    expect(find.text('59 秒后重试'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('shows external loading and error states', (tester) async {
    await tester.pumpWidget(
      buildSubject(
        isLoading: true,
        errorMessage: '手机号或密码错误',
      ),
    );

    expect(find.text('手机号或密码错误'), findsOneWidget);
    expect(find.text('登录中...'), findsOneWidget);
    final button = tester.widget<FilledButton>(
      find.byKey(const Key('login-submit-button')),
    );
    expect(button.onPressed, isNull);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('exposes register and forgot-password callbacks', (tester) async {
    var registerCalls = 0;
    var forgotPasswordCalls = 0;
    await tester.pumpWidget(
      buildSubject(
        onRegister: () => registerCalls += 1,
        onForgotPassword: () => forgotPasswordCalls += 1,
      ),
    );

    await tester.ensureVisible(find.byKey(const Key('forgot-password-button')));
    await tester.tap(find.byKey(const Key('forgot-password-button')));
    await tester.ensureVisible(find.byKey(const Key('register-button')));
    await tester.tap(find.byKey(const Key('register-button')));

    expect(forgotPasswordCalls, 1);
    expect(registerCalls, 1);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('submits remember-password and auto-login preferences', (
    tester,
  ) async {
    bool? remembered;
    bool? autoLoginSubmitted;
    bool? accepted;

    await tester.pumpWidget(
      buildSubject(
        onPasswordLogin: (
          _,
          __, {
          required rememberPassword,
          required autoLogin,
          required agreementsAccepted,
        }) async {
          remembered = rememberPassword;
          autoLoginSubmitted = autoLogin;
          accepted = agreementsAccepted;
        },
      ),
    );

    await tester.enterText(
      find.byKey(const Key('login-phone-field')),
      '13800000000',
    );
    await tester.enterText(
      find.byKey(const Key('login-password-field')),
      'password123',
    );
    await tester.tap(find.byKey(const Key('auto-login-checkbox')));
    await tester.tap(find.byKey(const Key('agreements-checkbox')));
    await tester.ensureVisible(find.byKey(const Key('login-submit-button')));
    await tester.tap(find.byKey(const Key('login-submit-button')));
    await tester.pump();

    expect(remembered, isTrue);
    expect(autoLoginSubmitted, isTrue);
    expect(accepted, isTrue);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('remember-password checkbox and label are vertically aligned', (
    tester,
  ) async {
    await tester.pumpWidget(buildSubject());

    final controlCenter = tester
        .getCenter(find.byKey(const Key('remember-password-checkbox')))
        .dy;
    final labelCenter = tester.getCenter(find.text('记住密码')).dy;

    expect((controlCenter - labelCenter).abs(), lessThan(1));

    await tester.pumpWidget(const SizedBox.shrink());
  });
}

final class _MemoryLocalePreferenceStore implements LocalePreferenceStore {
  String? value;

  @override
  Future<String?> readLanguageCode() async => value;

  @override
  Future<void> writeLanguageCode(String value) async {
    this.value = value;
  }
}
