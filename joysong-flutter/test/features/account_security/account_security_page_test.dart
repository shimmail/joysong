import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/account_security/data/account_deletion_pending_store.dart';
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
    final controller = AccountSecurityController(
      repository,
      pendingDeletionStore: _MemoryPendingStore(),
      idempotencyKeyFactory: () => 'delete-key-1',
    );
    await tester.pumpWidget(
      MaterialApp(home: AccountSecurityPage(controller: controller)),
    );
    await tester.pump();

    await tester.tap(find.byKey(const Key('delete-account-action')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('delete-account-send-sms')));
    await tester.pump();
    await tester.enterText(
      find.byKey(const Key('delete-account-sms-code')),
      '123456',
    );
    await tester.tap(find.byKey(const Key('delete-account-verify-sms')));
    await tester.pumpAndSettle();
    var button = tester.widget<FilledButton>(
      find.byKey(const Key('delete-account-submit')),
    );
    expect(button.onPressed, isNull);

    await tester.enterText(
      find.byKey(const Key('delete-account-confirmation')),
      'DELETE',
    );
    await tester.pump();
    button = tester.widget<FilledButton>(
      find.byKey(const Key('delete-account-submit')),
    );
    expect(button.onPressed, isNotNull);
    await tester.tap(find.byKey(const Key('delete-account-submit')));
    await tester.pumpAndSettle();

    expect(repository.deleteCalls, 1);
    expect(find.text('账号已注销'), findsWidgets);
  });

  testWidgets('shows the Google deletion step in English', (tester) async {
    final repository = _FakeRepository()
      ..deletionMethod = AccountDeletionStepUpMethod.google;
    final controller = AccountSecurityController(
      repository,
      pendingDeletionStore: _MemoryPendingStore(),
      googleIdTokenProvider: () async => 'google-id-token',
    );
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        home: AccountSecurityPage(controller: controller),
      ),
    );
    await tester.pump();

    await tester.tap(find.byKey(const Key('delete-account-action')));
    await tester.pumpAndSettle();

    expect(find.text('Verify again with Google'), findsOneWidget);
    expect(find.text('Delete account'), findsWidgets);
    await tester.tap(find.byKey(const Key('delete-account-google-verify')));
    await tester.pumpAndSettle();
    expect(
      find.text('Final confirmation: enter DELETE. This cannot be undone.'),
      findsOneWidget,
    );
    expect(repository.googleTokens, ['google-id-token']);
  });

  testWidgets('shows only explicitly supported identity blocker actions',
      (tester) async {
    final repository = _FakeRepository()
      ..deletionBlockers = const [
        AccountDeletionBlocker(
          type: 'IDENTITY_APPLICATION',
          count: 1,
          action: 'VIEW_IDENTITY_APPLICATION',
        ),
        AccountDeletionBlocker(
          type: 'ADMIN_ACCOUNT',
          count: 1,
          action: 'CONTACT_SUPPORT',
        ),
        AccountDeletionBlocker(
          type: 'INSTITUTION_MEMBERSHIP',
          count: 1,
          action: 'MANAGE_INSTITUTION_RELATIONSHIP',
        ),
        AccountDeletionBlocker(
          type: 'PENDING_RELATIONSHIP_CHANGE',
          count: 1,
          action: 'RESOLVE_PENDING_RELATIONSHIP',
        ),
      ];
    final controller = AccountSecurityController(repository);
    final actions = <String>[];
    await tester.pumpWidget(
      MaterialApp(
        home: AccountSecurityPage(
          controller: controller,
          onDeletionBlockerAction: actions.add,
        ),
      ),
    );
    await tester.pump();

    await tester.tap(find.byKey(const Key('delete-account-action')));
    await tester.pumpAndSettle();

    expect(
      find.byKey(
        const Key('delete-account-blocker-action-VIEW_IDENTITY_APPLICATION'),
      ),
      findsOneWidget,
    );
    expect(
      find.byKey(
        const Key('delete-account-blocker-action-CONTACT_SUPPORT'),
      ),
      findsNothing,
    );
    expect(
      find.byKey(
        const Key(
          'delete-account-blocker-action-MANAGE_INSTITUTION_RELATIONSHIP',
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.byKey(
        const Key(
          'delete-account-blocker-action-RESOLVE_PENDING_RELATIONSHIP',
        ),
      ),
      findsOneWidget,
    );
    await tester.tap(
      find.byKey(
        const Key('delete-account-blocker-action-VIEW_IDENTITY_APPLICATION'),
      ),
    );
    expect(actions, ['VIEW_IDENTITY_APPLICATION']);
  });

  testWidgets('hides blocker actions when the route has no handler',
      (tester) async {
    final repository = _FakeRepository()
      ..deletionBlockers = const [
        AccountDeletionBlocker(
          type: 'IDENTITY_APPLICATION',
          count: 1,
          action: 'VIEW_IDENTITY_APPLICATION',
        ),
      ];
    final controller = AccountSecurityController(repository);
    await tester.pumpWidget(
      MaterialApp(home: AccountSecurityPage(controller: controller)),
    );
    await tester.pump();

    await tester.tap(find.byKey(const Key('delete-account-action')));
    await tester.pumpAndSettle();

    expect(
      find.byKey(
        const Key('delete-account-blocker-action-VIEW_IDENTITY_APPLICATION'),
      ),
      findsNothing,
    );
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
  AccountDeletionStepUpMethod deletionMethod =
      AccountDeletionStepUpMethod.sms;
  List<AccountDeletionBlocker> deletionBlockers = const [];
  final googleTokens = <String>[];

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
  Future<AccountDeletionPreflight> preflightAccountDeletion() async =>
      AccountDeletionPreflight(
        requestId: 'request-1',
        eligible: deletionBlockers.isEmpty,
        stepUpMethod: deletionMethod,
        maskedCredential: '+8613******00',
        policyVersion: 'dev-v1',
        blockers: deletionBlockers,
      );

  @override
  Future<void> sendAccountDeletionSmsCode(String requestId) async {}

  @override
  Future<AccountDeletionAuthorization> stepUpAccountDeletionWithSms({
    required String requestId,
    required String code,
  }) async =>
      const AccountDeletionAuthorization(token: 'delete-auth-1');

  @override
  Future<AccountDeletionAuthorization> stepUpAccountDeletionWithGoogle({
    required String requestId,
    required String idToken,
  }) async {
    googleTokens.add(idToken);
    return const AccountDeletionAuthorization(token: 'delete-auth-1');
  }

  @override
  Future<AccountDeletionConfirmation> confirmAccountDeletion(
    PendingAccountDeletion pending,
  ) async {
    deleteCalls++;
    return AccountDeletionConfirmation(
      requestId: pending.requestId,
      outcome: AccountDeletionOutcome.erased,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);

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

final class _MemoryPendingStore implements AccountDeletionPendingStore {
  PendingAccountDeletion? value;

  @override
  Future<void> clear() async => value = null;

  @override
  Future<PendingAccountDeletion?> read() async => value;

  @override
  Future<void> save(PendingAccountDeletion pending) async => value = pending;
}
