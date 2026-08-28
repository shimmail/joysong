import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/account_security/data/account_deletion_pending_store.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_repository.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_controller.dart';

void main() {
  test('SMS deletion persists one request before one idempotent confirmation',
      () async {
    final repository = _DeletionRepository();
    final pendingStore = _MemoryPendingStore();
    final completedUsers = <String>[];
    final controller = AccountSecurityController(
      repository,
      pendingDeletionStore: pendingStore,
      idempotencyKeyFactory: () => 'delete-key-1',
      onDeletionConfirmed: (userId) async => completedUsers.add(userId),
    );

    await controller.beginAccountDeletion();
    expect(controller.deletionStage, AccountDeletionStage.awaitingStepUp);
    expect(await controller.sendAccountDeletionSmsCode(), isTrue);
    expect(await controller.verifyAccountDeletionSmsCode('123456'), isTrue);
    expect(controller.deletionStage, AccountDeletionStage.awaitingConfirmation);

    final first = controller.confirmAccountDeletion(
      confirmation: 'DELETE',
      userId: 'user-1',
    );
    final second = await controller.confirmAccountDeletion(
      confirmation: 'DELETE',
      userId: 'user-1',
    );

    expect(second, isFalse);
    expect(repository.confirmCalls, 1);
    expect(pendingStore.value?.idempotencyKey, 'delete-key-1');
    expect(pendingStore.value?.deletionAuthorization, 'delete-auth-1');
    repository.confirmCompleter.complete(
      const AccountDeletionConfirmation(
        requestId: 'request-1',
        outcome: AccountDeletionOutcome.erased,
      ),
    );
    expect(await first, isTrue);
    expect(completedUsers, ['user-1']);
    expect(pendingStore.value, isNull);
    expect(controller.deletionStage, AccountDeletionStage.completed);
  });

  test('Google cancellation stays in step-up without exposing an exception',
      () async {
    final repository = _DeletionRepository(
      method: AccountDeletionStepUpMethod.google,
    );
    final controller = AccountSecurityController(
      repository,
      googleIdTokenProvider: () async => null,
    );

    await controller.beginAccountDeletion();
    expect(await controller.verifyAccountDeletionWithGoogle(), isFalse);

    expect(controller.deletionStage, AccountDeletionStage.awaitingStepUp);
    expect(
      controller.deletionErrorCode,
      AccountDeletionErrorCode.googleReauthenticationCancelled,
    );
    expect(repository.googleTokens, isEmpty);
  });

  test('confirmation requires the exact ASCII DELETE literal', () async {
    final repository = _DeletionRepository();
    final pendingStore = _MemoryPendingStore();
    final controller = AccountSecurityController(
      repository,
      pendingDeletionStore: pendingStore,
    );
    await controller.beginAccountDeletion();
    await controller.verifyAccountDeletionSmsCode('123456');

    expect(
      await controller.confirmAccountDeletion(
        confirmation: ' DELETE ',
        userId: 'user-1',
      ),
      isFalse,
    );
    expect(repository.confirmCalls, 0);
    expect(pendingStore.value, isNull);
    expect(
      controller.deletionErrorCode,
      AccountDeletionErrorCode.invalidConfirmation,
    );
  });

  test('blockers are exposed without sensitive identifiers', () async {
    final repository = _DeletionRepository(
      blockers: const [
        AccountDeletionBlocker(
          type: 'IDENTITY_APPLICATION',
          count: 1,
          action: '/identity',
        ),
      ],
    );
    final controller = AccountSecurityController(repository);

    await controller.beginAccountDeletion();

    expect(controller.deletionStage, AccountDeletionStage.blocked);
    expect(controller.deletionPreflight?.blockers.single.count, 1);
    expect(controller.deletionPreflight?.blockers.single.action, '/identity');
  });

  test('SMS verification failure stays in step-up with a stable error code',
      () async {
    final repository = _DeletionRepository()
      ..smsStepUpError = const ApiException(
        message: 'raw verification failure',
        httpStatus: 400,
        errorCode: 'ACCOUNT_DELETION_VERIFICATION_FAILED',
      );
    final controller = AccountSecurityController(repository);
    await controller.beginAccountDeletion();

    expect(await controller.verifyAccountDeletionSmsCode('123456'), isFalse);
    expect(controller.deletionStage, AccountDeletionStage.awaitingStepUp);
    expect(
      controller.deletionErrorCode,
      AccountDeletionErrorCode.verificationFailed,
    );
  });

  test('unknown confirmation failure keeps pending and signs the UI out',
      () async {
    final repository = _DeletionRepository();
    final pendingStore = _MemoryPendingStore();
    var heldPending = false;
    final controller = AccountSecurityController(
      repository,
      pendingDeletionStore: pendingStore,
      idempotencyKeyFactory: () => 'delete-key-1',
      onDeletionUncertain: () => heldPending = true,
    );
    await controller.beginAccountDeletion();
    await controller.verifyAccountDeletionSmsCode('123456');
    repository.confirmError =
        const ApiException(message: 'socket details must not be shown');

    expect(
      await controller.confirmAccountDeletion(
        confirmation: 'DELETE',
        userId: 'user-1',
      ),
      isFalse,
    );

    expect(pendingStore.value, isNotNull);
    expect(heldPending, isTrue);
    expect(
      controller.deletionErrorCode,
      AccountDeletionErrorCode.retryRequired,
    );
  });

  test('final blocked response keeps blocker details for the UI', () async {
    final repository = _DeletionRepository()
      ..confirmError = const ApiException(
        message: 'raw blocked message',
        httpStatus: 409,
        errorCode: 'ACCOUNT_DELETION_BLOCKED',
        data: {
          'blockers': [
            {
              'type': 'IDENTITY_APPLICATION',
              'count': 2,
              'action': 'VIEW_IDENTITY_APPLICATION',
            },
          ],
        },
      );
    final pendingStore = _MemoryPendingStore();
    var heldPending = false;
    final controller = AccountSecurityController(
      repository,
      pendingDeletionStore: pendingStore,
      idempotencyKeyFactory: () => 'delete-key-1',
      onDeletionUncertain: () => heldPending = true,
    );
    await controller.beginAccountDeletion();
    await controller.verifyAccountDeletionSmsCode('123456');

    expect(
      await controller.confirmAccountDeletion(
        confirmation: 'DELETE',
        userId: 'user-1',
      ),
      isFalse,
    );

    expect(pendingStore.value, isNull);
    expect(heldPending, isFalse);
    expect(controller.deletionStage, AccountDeletionStage.blocked);
    expect(controller.deletionPreflight?.blockers.single.count, 2);
    expect(
      controller.deletionPreflight?.blockers.single.action,
      'VIEW_IDENTITY_APPLICATION',
    );
  });

  test('expired authorization clears the old request and returns to idle',
      () async {
    final repository = _DeletionRepository()
      ..confirmError = const ApiException(
        message: 'raw expired message',
        httpStatus: 410,
        errorCode: 'ACCOUNT_DELETION_AUTHORIZATION_EXPIRED',
      );
    final pendingStore = _MemoryPendingStore();
    final controller = AccountSecurityController(
      repository,
      pendingDeletionStore: pendingStore,
      idempotencyKeyFactory: () => 'delete-key-1',
    );
    await controller.beginAccountDeletion();
    await controller.verifyAccountDeletionSmsCode('123456');

    await controller.confirmAccountDeletion(
      confirmation: 'DELETE',
      userId: 'user-1',
    );

    expect(pendingStore.value, isNull);
    expect(controller.deletionStage, AccountDeletionStage.idle);
    expect(controller.deletionPreflight, isNull);
  });

  for (final scenario in <String, ApiException>{
    'idempotency conflict': const ApiException(
      message: 'conflict',
      httpStatus: 409,
      errorCode: 'ACCOUNT_DELETION_IDEMPOTENCY_CONFLICT',
    ),
    'generic HTTP 409': const ApiException(message: 'conflict', httpStatus: 409),
    'generic HTTP 410': const ApiException(message: 'gone', httpStatus: 410),
  }.entries) {
    test('${scenario.key} retains pending and signs the UI out', () async {
      final repository = _DeletionRepository()..confirmError = scenario.value;
      final pendingStore = _MemoryPendingStore();
      var heldPending = false;
      final controller = AccountSecurityController(
        repository,
        pendingDeletionStore: pendingStore,
        idempotencyKeyFactory: () => 'delete-key-1',
        onDeletionUncertain: () => heldPending = true,
      );
      await controller.beginAccountDeletion();
      await controller.verifyAccountDeletionSmsCode('123456');

      await controller.confirmAccountDeletion(
        confirmation: 'DELETE',
        userId: 'user-1',
      );

      expect(pendingStore.value, isNotNull);
      expect(heldPending, isTrue);
      expect(controller.deletionStage, AccountDeletionStage.awaitingConfirmation);
    });
  }

  test('failed pending clear keeps an expired request fail closed', () async {
    final repository = _DeletionRepository()
      ..confirmError = const ApiException(
        message: 'expired',
        httpStatus: 410,
        errorCode: 'ACCOUNT_DELETION_AUTHORIZATION_EXPIRED',
      );
    final pendingStore = _MemoryPendingStore()..clearError = Exception('disk');
    var heldPending = false;
    final controller = AccountSecurityController(
      repository,
      pendingDeletionStore: pendingStore,
      idempotencyKeyFactory: () => 'delete-key-1',
      onDeletionUncertain: () => heldPending = true,
    );
    await controller.beginAccountDeletion();
    await controller.verifyAccountDeletionSmsCode('123456');

    await controller.confirmAccountDeletion(
      confirmation: 'DELETE',
      userId: 'user-1',
    );

    expect(pendingStore.value, isNotNull);
    expect(heldPending, isTrue);
    expect(controller.deletionStage, AccountDeletionStage.awaitingConfirmation);
  });
}

final class _DeletionRepository implements AccountSecurityRepository {
  _DeletionRepository({
    this.method = AccountDeletionStepUpMethod.sms,
    this.blockers = const [],
  });

  final AccountDeletionStepUpMethod method;
  final List<AccountDeletionBlocker> blockers;
  final confirmCompleter = Completer<AccountDeletionConfirmation>();
  final googleTokens = <String>[];
  int confirmCalls = 0;
  Object? smsStepUpError;
  Object? confirmError;

  @override
  Future<AccountDeletionPreflight> preflightAccountDeletion() async =>
      AccountDeletionPreflight(
        requestId: 'request-1',
        eligible: blockers.isEmpty,
        stepUpMethod: method,
        maskedCredential: method == AccountDeletionStepUpMethod.sms
            ? '+8613******00'
            : 'u***@example.com',
        policyVersion: 'dev-v1',
        blockers: blockers,
      );

  @override
  Future<void> sendAccountDeletionSmsCode(String requestId) async {}

  @override
  Future<AccountDeletionAuthorization> stepUpAccountDeletionWithSms({
    required String requestId,
    required String code,
  }) async =>
      _smsAuthorization();

  AccountDeletionAuthorization _smsAuthorization() {
    final error = smsStepUpError;
    if (error != null) throw error;
    return const AccountDeletionAuthorization(token: 'delete-auth-1');
  }

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
  ) {
    confirmCalls += 1;
    final error = confirmError;
    if (error != null) return Future.error(error);
    return confirmCompleter.future;
  }

  @override
  Future<AccountSecurityProfile> getProfile() async =>
      const AccountSecurityProfile(
        id: 'user-1',
        phone: '+8613800000000',
        email: null,
        hasPassword: true,
      );

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _MemoryPendingStore implements AccountDeletionPendingStore {
  PendingAccountDeletion? value;
  Object? clearError;

  @override
  Future<void> clear() async {
    final error = clearError;
    if (error != null) throw error;
    value = null;
  }

  @override
  Future<PendingAccountDeletion?> read() async => value;

  @override
  Future<void> save(PendingAccountDeletion pending) async => value = pending;
}
