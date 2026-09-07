import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_repository.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_controller.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_page.dart';

void main() {
  for (final english in [true, false]) {
    testWidgets('password and verification validation localized: $english',
        (tester) async {
      final controller = AccountSecurityController(_SecurityRepository());
      addTearDown(controller.dispose);
      await tester.pumpWidget(_app(controller, english));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('password-action')));
      await tester.pumpAndSettle();
      await tester.enterText(
          find.byKey(const Key('new-password-field')), 'short');
      await tester.tap(find.byKey(const Key('change-password-submit')));
      await tester.pumpAndSettle();
      expect(find.text(english ? 'Enter your current password' : '请输入原密码'),
          findsOneWidget);
      expect(
          find.text(
              english ? 'Password must be 8–128 characters' : '密码长度应为 8-128 位'),
          findsOneWidget);
      expect(find.text(english ? 'Passwords do not match' : '两次输入的密码不一致'),
          findsOneWidget);

      Navigator.of(tester.element(find.byType(AlertDialog))).pop();
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('reset-password-action')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('sms-password-submit')));
      await tester.pumpAndSettle();
      expect(
          find.text(
              english ? 'Enter a 6-digit verification code' : '请输入 6 位验证码'),
          findsOneWidget);
      Navigator.of(tester.element(find.byType(AlertDialog))).pop();
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('phone-change-action')));
      await tester.pumpAndSettle();
      await tester.enterText(
          find.byKey(const Key('current-phone-code-field')), '12');
      await tester.pump();
      expect(
          find.text(
              english ? 'Enter a 6-digit verification code' : '请输入 6 位验证码'),
          findsOneWidget);
    });
  }

  testWidgets(
      'existing API errors change language and unknown errors use fallback',
      (tester) async {
    final repository = _SecurityRepository();
    final controller = AccountSecurityController(repository);
    addTearDown(controller.dispose);
    await tester.pumpWidget(_app(controller, false));
    await tester.pumpAndSettle();
    await controller.resetPassword(code: '123456', newPassword: 'password123');
    await tester.pumpAndSettle();
    expect(find.text('验证码无效或已过期'), findsOneWidget);
    await tester.pumpWidget(_app(controller, true));
    await tester.pumpAndSettle();
    expect(find.text('The verification code is invalid or has expired.'),
        findsOneWidget);
    expect(find.text('验证码无效或已过期'), findsNothing);

    repository.error = '服务端新增中文错误';
    await controller.resetPassword(code: '123456', newPassword: 'password123');
    await tester.pumpAndSettle();
    expect(find.text('Unable to complete this action. Please try again.'),
        findsOneWidget);
    expect(find.text(repository.error), findsNothing);
  });
}

Widget _app(AccountSecurityController controller, bool english) => MaterialApp(
      locale: Locale(english ? 'en' : 'zh'),
      supportedLocales: const [Locale('en'), Locale('zh')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: AccountSecurityPage(controller: controller),
    );

final class _SecurityRepository implements AccountSecurityRepository {
  String error = '验证码无效或已过期';

  @override
  Future<AccountSecurityProfile> getProfile() async =>
      const AccountSecurityProfile(
        id: 'test-user',
        phone: '+8613900000000',
        email: null,
        hasPassword: true,
      );

  @override
  Future<void> resetPassword(
      {required String phone,
      required String code,
      required String newPassword}) async {
    throw ApiException(message: error, httpStatus: 400);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnsupportedError(invocation.memberName.toString());
}
