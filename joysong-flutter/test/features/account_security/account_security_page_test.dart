import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_repository.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_controller.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_page.dart';

void main() {
  testWidgets('shows masked phone and keeps unsupported devices disabled',
      (tester) async {
    final controller = AccountSecurityController(_FakeRepository());
    await tester.pumpWidget(
      MaterialApp(home: AccountSecurityPage(controller: controller)),
    );
    await tester.pump();

    expect(find.text('+8613******00'), findsOneWidget);
    expect(find.text('服务端尚未开放设备管理接口'), findsOneWidget);
    final devices = tester.widget<ListTile>(
      find.byKey(const Key('device-management-disabled')),
    );
    expect(devices.enabled, isFalse);
  });

  testWidgets('submits password change once and reports session invalidation',
      (tester) async {
    final repository = _FakeRepository();
    final controller = AccountSecurityController(repository);
    var invalidated = false;
    await tester.pumpWidget(
      MaterialApp(
        home: AccountSecurityPage(
          controller: controller,
          onSessionInvalidated: () => invalidated = true,
        ),
      ),
    );
    await tester.pump();

    await tester.tap(find.byKey(const Key('password-action')));
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byKey(const Key('old-password-field')), 'old-secret');
    await tester.enterText(
        find.byKey(const Key('new-password-field')), 'new-secret');
    await tester.enterText(find.byType(TextFormField).at(2), 'new-secret');
    await tester.tap(find.byKey(const Key('change-password-submit')));
    await tester.pumpAndSettle();

    expect(repository.changeCalls, 1);
    expect(invalidated, isTrue);
    expect(find.text('密码已修改，请重新登录'), findsOneWidget);
  });

  testWidgets('requires an exact destructive confirmation phrase',
      (tester) async {
    final repository = _FakeRepository();
    final controller = AccountSecurityController(repository);
    await tester.pumpWidget(
      MaterialApp(home: AccountSecurityPage(controller: controller)),
    );
    await tester.pump();

    await tester.tap(find.byKey(const Key('delete-account-action')));
    await tester.pumpAndSettle();
    var button = tester.widget<FilledButton>(
      find.byKey(const Key('delete-account-submit')),
    );
    expect(button.onPressed, isNull);

    await tester.enterText(
      find.byKey(const Key('delete-account-confirmation')),
      '注销账号',
    );
    await tester.pump();
    button = tester.widget<FilledButton>(
      find.byKey(const Key('delete-account-submit')),
    );
    expect(button.onPressed, isNotNull);
    await tester.tap(find.byKey(const Key('delete-account-submit')));
    await tester.pumpAndSettle();

    expect(repository.deleteCalls, 1);
    expect(find.text('账号已注销，请返回登录页'), findsOneWidget);
  });

  testWidgets('changes a bound phone through both verification stages',
      (tester) async {
    final repository = _FakeRepository();
    final controller = AccountSecurityController(repository);
    var invalidated = false;
    await tester.pumpWidget(
      MaterialApp(
        home: AccountSecurityPage(
          controller: controller,
          onSessionInvalidated: () => invalidated = true,
        ),
      ),
    );
    await tester.pump();

    await tester.tap(find.byKey(const Key('phone-change-action')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('send-current-phone-code')));
    await tester.pump();
    expect(find.byKey(const Key('current-phone-code-sent')), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('current-phone-code-field')),
      '123456',
    );
    await tester.tap(find.byKey(const Key('verify-current-phone-submit')));
    await tester.pump();

    expect(find.text('🇨🇳 +86 ▾'), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('new-phone-national-field')),
      '13900000000',
    );
    await tester.tap(find.byKey(const Key('send-new-phone-code')));
    await tester.pump();
    expect(find.byKey(const Key('new-phone-code-sent')), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('new-phone-code-field')),
      '654321',
    );
    await tester.ensureVisible(
      find.byKey(const Key('complete-phone-change-submit')),
    );
    await tester.tap(find.byKey(const Key('complete-phone-change-submit')));
    await tester.pumpAndSettle();

    expect(repository.sendCurrentPhoneCodeCalls, 1);
    expect(repository.verifyCurrentPhoneCodeCalls, 1);
    expect(repository.sendNewPhoneCodeCalls, 1);
    expect(repository.changePhoneCalls, 1);
    expect(repository.lastPhone, '+8613900000000');
    expect(invalidated, isTrue);
  });
}

final class _FakeRepository implements AccountSecurityRepository {
  int changeCalls = 0;
  int deleteCalls = 0;
  int sendCurrentPhoneCodeCalls = 0;
  int verifyCurrentPhoneCodeCalls = 0;
  int sendNewPhoneCodeCalls = 0;
  int sendBindingPhoneCodeCalls = 0;
  int changePhoneCalls = 0;
  int bindPhoneCalls = 0;
  String? lastPhone;
  bool hasBoundPhone = true;

  @override
  Future<AccountSecurityProfile> getProfile() async {
    return AccountSecurityProfile(
      id: 'user-1',
      phone: hasBoundPhone ? '+8613800000000' : null,
      email: null,
      hasPassword: hasBoundPhone,
    );
  }

  @override
  Future<void> changePassword({
    required String oldPassword,
    required String newPassword,
  }) async {
    changeCalls++;
  }

  @override
  Future<void> deleteAccount() async {
    deleteCalls++;
  }

  @override
  Future<void> sendCurrentPhoneChangeCode() async {
    sendCurrentPhoneCodeCalls++;
  }

  @override
  Future<void> verifyCurrentPhoneChangeCode(String code) async {
    verifyCurrentPhoneCodeCalls++;
  }

  @override
  Future<void> sendNewPhoneChangeCode(String phone) async {
    sendNewPhoneCodeCalls++;
    lastPhone = phone;
  }

  @override
  Future<void> changePhone(
      {required String phone, required String code}) async {
    changePhoneCalls++;
    lastPhone = phone;
  }

  @override
  Future<void> sendBindingPhoneCode(String phone) async {
    sendBindingPhoneCodeCalls++;
    lastPhone = phone;
  }

  @override
  Future<void> bindPhone({required String phone, required String code}) async {
    bindPhoneCalls++;
    lastPhone = phone;
  }

  @override
  Future<void> resetPassword({
    required String phone,
    required String code,
    required String newPassword,
  }) async {}

  @override
  Future<void> sendPasswordCode(String phone) async {}

  @override
  Future<void> setPassword({
    required String phone,
    required String code,
    required String newPassword,
  }) async {}
}
