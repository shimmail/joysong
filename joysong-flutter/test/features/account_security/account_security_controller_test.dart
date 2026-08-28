import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_repository.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_controller.dart';

void main() {
  test('loads profile and exposes unsupported device management fail-closed',
      () async {
    final controller = AccountSecurityController(_FakeRepository());

    await controller.load();

    expect(controller.status, AccountSecurityLoadStatus.ready);
    expect(controller.profile!.maskedPhone, '+8613******00');
    expect(controller.supportsDeviceManagement, isFalse);
  });

  test('prevents duplicate sensitive writes and requests a fresh login',
      () async {
    final repository = _FakeRepository()..changeCompleter = Completer<void>();
    final controller = AccountSecurityController(repository);
    await controller.load();

    final first = controller.changePassword(
      oldPassword: 'old-secret',
      newPassword: 'new-secret',
    );
    final second = await controller.changePassword(
      oldPassword: 'old-secret',
      newPassword: 'new-secret',
    );

    expect(second, isFalse);
    expect(repository.changeCalls, 1);
    repository.changeCompleter!.complete();
    expect(await first, isTrue);
    expect(controller.sessionMustEnd, isTrue);
    expect(controller.successMessage, contains('重新登录'));
  });

  test('does not claim account deletion when preflight is unavailable',
      () async {
    final repository = _FakeRepository()..preflightError = Exception('offline');
    final controller = AccountSecurityController(repository);
    await controller.load();

    expect(await controller.beginAccountDeletion(), isFalse);
    expect(controller.status, AccountSecurityLoadStatus.ready);
    expect(
      controller.deletionErrorCode,
      AccountDeletionErrorCode.requestFailed,
    );
  });

  test('maps SMS delivery outages to a stable client error code', () {
    expect(
      accountDeletionErrorCodeFor(
        const ApiException(
          message: 'raw provider failure',
          httpStatus: 503,
          errorCode: 'SMS_DELIVERY_UNAVAILABLE',
        ),
      ),
      AccountDeletionErrorCode.smsDeliveryUnavailable,
    );
  });

  test('enforces the bound-phone verification sequence', () async {
    final repository = _FakeRepository();
    final controller = AccountSecurityController(repository);
    await controller.load();
    controller.beginPhoneChange();

    expect(await controller.sendNewPhoneChangeCode('+85251234567'), isFalse);
    expect(repository.sendNewPhoneCodeCalls, 0);

    expect(await controller.sendCurrentPhoneChangeCode(), isTrue);
    expect(await controller.verifyCurrentPhoneChangeCode('123456'), isTrue);
    expect(controller.currentPhoneVerified, isTrue);
    expect(await controller.sendNewPhoneChangeCode('+85251234567'), isTrue);
    expect(
      await controller.completePhoneChange(
        phone: '+85251234567',
        code: '654321',
      ),
      isTrue,
    );

    expect(repository.sendCurrentPhoneCodeCalls, 1);
    expect(repository.verifyCurrentPhoneCodeCalls, 1);
    expect(repository.sendNewPhoneCodeCalls, 1);
    expect(repository.changePhoneCalls, 1);
    expect(repository.lastPhone, '+85251234567');
    expect(controller.profile!.phone, '+85251234567');
    expect(controller.sessionMustEnd, isTrue);
  });

  test('unbound account uses generic code and bind-phone endpoints', () async {
    final repository = _FakeRepository()
      ..profile = const AccountSecurityProfile(
        id: 'user-1',
        phone: null,
        email: null,
        hasPassword: false,
      );
    final controller = AccountSecurityController(repository);
    await controller.load();
    controller.beginPhoneChange();

    expect(await controller.sendNewPhoneChangeCode('+8613900000000'), isTrue);
    expect(
      await controller.completePhoneChange(
        phone: '+8613900000000',
        code: '123456',
      ),
      isTrue,
    );

    expect(repository.sendBindingPhoneCodeCalls, 1);
    expect(repository.bindPhoneCalls, 1);
    expect(repository.sendCurrentPhoneCodeCalls, 0);
    expect(controller.profile!.phone, '+8613900000000');
    expect(controller.sessionMustEnd, isTrue);
  });
}

final class _FakeRepository implements AccountSecurityRepository {
  AccountSecurityProfile profile = const AccountSecurityProfile(
    id: 'user-1',
    phone: '+8613800000000',
    email: null,
    hasPassword: true,
  );
  Completer<void>? changeCompleter;
  Object? preflightError;
  int changeCalls = 0;
  int sendCurrentPhoneCodeCalls = 0;
  int verifyCurrentPhoneCodeCalls = 0;
  int sendNewPhoneCodeCalls = 0;
  int sendBindingPhoneCodeCalls = 0;
  int changePhoneCalls = 0;
  int bindPhoneCalls = 0;
  String? lastPhone;

  @override
  Future<AccountSecurityProfile> getProfile() async => profile;

  @override
  Future<void> changePassword({
    required String oldPassword,
    required String newPassword,
  }) async {
    changeCalls++;
    await changeCompleter?.future;
  }

  @override
  Future<AccountDeletionPreflight> preflightAccountDeletion() async {
    final error = preflightError;
    if (error != null) throw error;
    return const AccountDeletionPreflight(
      requestId: 'request-1',
      eligible: true,
      stepUpMethod: AccountDeletionStepUpMethod.sms,
      maskedCredential: '+8613******00',
      policyVersion: 'dev-v1',
      blockers: [],
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
