import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/auth/presentation/auth_action_page.dart';

void main() {
  testWidgets('registers with normalized country code after phone check',
      (tester) async {
    final localeController = AppLocaleController(
      preferenceStore: _MemoryLocaleStore(),
    );
    String? checkedPhone;
    String? codePhone;
    String? registeredPhone;

    await tester.pumpWidget(
      _testApp(
        localeController,
        AuthActionPage(
          mode: AuthActionMode.register,
          onCheckPhoneRegistered: (phone) async {
            checkedPhone = phone;
            return false;
          },
          onSendCode: (phone) async => codePhone = phone,
          onRegister: (phone, code, password) async {
            registeredPhone = phone;
            expect(code, '123456');
            expect(password, 'password8');
            return true;
          },
        ),
      ),
    );

    await tester.enterText(
      find.byKey(const Key('auth-action-phone')),
      '13800000000',
    );
    await tester.tap(find.byKey(const Key('auth-action-send-code')));
    await tester.pump();
    expect(checkedPhone, '+8613800000000');
    expect(codePhone, '+8613800000000');

    await tester.enterText(
      find.byKey(const Key('auth-action-code')),
      '123456',
    );
    await tester.enterText(
      find.byKey(const Key('auth-action-password')),
      'password8',
    );
    await tester.enterText(
      find.byKey(const Key('auth-action-confirm-password')),
      'password8',
    );
    await tester.tap(find.byKey(const Key('auth-action-agreement')));
    await tester.tap(find.byKey(const Key('auth-action-submit')));
    await tester.pumpAndSettle();

    expect(registeredPhone, '+8613800000000');
    expect(find.byKey(const Key('test-home')), findsOneWidget);
    localeController.dispose();
  });

  testWidgets('reset password page renders English and rejects unknown phone',
      (tester) async {
    final localeController = AppLocaleController(
      preferenceStore: _MemoryLocaleStore(),
      initialLanguage: AppLanguage.english,
    );
    var codeSent = false;
    await tester.pumpWidget(
      _testApp(
        localeController,
        AuthActionPage(
          mode: AuthActionMode.resetPassword,
          onCheckPhoneRegistered: (_) async => false,
          onSendCode: (_) async => codeSent = true,
          onResetPassword: (_, __, ___) async => true,
        ),
      ),
    );

    expect(find.text('Reset password'), findsWidgets);
    await tester.enterText(
      find.byKey(const Key('auth-action-phone')),
      '13800000000',
    );
    await tester.tap(find.byKey(const Key('auth-action-send-code')));
    await tester.pump();

    expect(codeSent, isFalse);
    expect(
      find.text('No account was found for this phone number.'),
      findsOneWidget,
    );
    localeController.dispose();
  });

  testWidgets('registration remains blocked until both agreements are accepted',
      (tester) async {
    final localeController = AppLocaleController(
      preferenceStore: _MemoryLocaleStore(),
    );
    var registerCalls = 0;
    await tester.pumpWidget(
      _testApp(
        localeController,
        AuthActionPage(
          mode: AuthActionMode.register,
          onCheckPhoneRegistered: (_) async => false,
          onSendCode: (_) async {},
          onRegister: (_, __, ___) async {
            registerCalls += 1;
            return true;
          },
        ),
      ),
    );

    await tester.enterText(
      find.byKey(const Key('auth-action-phone')),
      '13800000000',
    );
    await tester.enterText(
      find.byKey(const Key('auth-action-code')),
      '123456',
    );
    await tester.enterText(
      find.byKey(const Key('auth-action-password')),
      'password8',
    );
    await tester.enterText(
      find.byKey(const Key('auth-action-confirm-password')),
      'password8',
    );
    await tester.tap(find.byKey(const Key('auth-action-submit')));
    await tester.pump();

    expect(registerCalls, 0);
    expect(find.text('请先同意用户协议和隐私政策'), findsOneWidget);
    expect(find.byKey(const Key('auth-action-agreement')), findsOneWidget);
    localeController.dispose();
  });
}

Widget _testApp(
  AppLocaleController localeController,
  Widget page,
) {
  return AppLocaleScope(
    controller: localeController,
    child: MaterialApp(
      locale: localeController.language.locale,
      initialRoute: '/action',
      routes: {
        '/': (_) => const Scaffold(
              body: SizedBox(key: Key('test-home')),
            ),
        '/action': (_) => page,
      },
    ),
  );
}

final class _MemoryLocaleStore implements LocalePreferenceStore {
  @override
  Future<String?> readLanguageCode() async => null;

  @override
  Future<void> writeLanguageCode(String value) async {}
}
