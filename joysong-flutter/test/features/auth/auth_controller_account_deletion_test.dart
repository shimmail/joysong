import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/account_security/data/account_deletion_pending_store.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_repository.dart';
import 'package:joysong_flutter/features/auth/data/login_preferences_store.dart';
import 'package:joysong_flutter/features/auth/data/saved_account_store.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/auth/domain/auth_repository.dart';
import 'package:joysong_flutter/features/auth/presentation/auth_controller.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_preferences.dart';

void main() {
  test('deletion cleanup removes only the erased account local data', () async {
    final authRepository = _AuthRepository();
    final savedStore = _SavedStore([
      _saved('user-1', 'access-1'),
      _saved('user-2', 'access-2'),
    ]);
    final loginStore = _LoginStore(
      const LoginPreferences(
        userId: 'user-1',
        phone: '+8613800000000',
        password: 'secret-password',
        rememberPassword: true,
        autoLogin: true,
        agreementsAccepted: true,
      ),
    );
    final messagingStore = _MessagingStore();
    final clearedCaches = <String>[];
    var googleClearCount = 0;
    final controller = AuthController(
      authRepository,
      savedAccountStore: savedStore,
      loginPreferencesStore: loginStore,
      messagingPreferencesStore: messagingStore,
      googleSessionClearer: () async => googleClearCount += 1,
      accountCacheClearer: (userId) async => clearedCaches.add(userId),
    );
    await controller.restoreSession();

    await controller.completeAccountDeletion('user-1');

    expect(controller.status, AuthStatus.unauthenticated);
    expect(authRepository.tokens, isNull);
    expect(savedStore.value.map((item) => item.userId), ['user-2']);
    expect(loginStore.value, isNull);
    expect(messagingStore.clearedUsers, ['user-1']);
    expect(clearedCaches, ['user-1']);
    expect(googleClearCount, 1);
  });

  test('deletion preserves login preferences owned by another saved account',
      () async {
    final authRepository = _AuthRepository();
    final loginStore = _LoginStore(
      const LoginPreferences(
        userId: 'user-2',
        phone: '+8613900000000',
        password: 'other-password',
        rememberPassword: true,
        agreementsAccepted: true,
      ),
    );
    final controller = AuthController(
      authRepository,
      loginPreferencesStore: loginStore,
    );

    await controller.completeAccountDeletion('user-1');

    expect(loginStore.value?.userId, 'user-2');
    expect(loginStore.value?.password, 'other-password');
  });

  test('startup resumes the same pending terminal request before login',
      () async {
    final authRepository = _AuthRepository();
    final pendingStore = _PendingStore(
      const PendingAccountDeletion(
        requestId: 'request-1',
        idempotencyKey: 'delete-key-1',
        deletionAuthorization: 'delete-auth-1',
        policyVersion: 'dev-v1',
        userId: 'user-1',
      ),
    );
    final deletionRepository = _DeletionRepository();
    final controller = AuthController(
      authRepository,
      accountDeletionRepository: deletionRepository,
      pendingAccountDeletionStore: pendingStore,
    );

    await controller.restoreSession();

    expect(deletionRepository.confirmedKeys, ['delete-key-1']);
    expect(authRepository.refreshCalls, 0);
    expect(pendingStore.value, isNull);
    expect(controller.status, AuthStatus.unauthenticated);
  });

  test('expired pending authorization clears pending and restores the account',
      () async {
    final authRepository = _AuthRepository();
    final pendingStore = _PendingStore(_pending);
    final deletionRepository = _DeletionRepository()
      ..error = const ApiException(
        message: 'raw server message',
        httpStatus: 410,
        errorCode: 'ACCOUNT_DELETION_AUTHORIZATION_EXPIRED',
      );
    final controller = AuthController(
      authRepository,
      accountDeletionRepository: deletionRepository,
      pendingAccountDeletionStore: pendingStore,
    );

    await controller.restoreSession();

    expect(pendingStore.value, isNull);
    expect(authRepository.refreshCalls, 1);
    expect(controller.status, AuthStatus.authenticated);
  });

  test('unknown pending result remains signed out with the same request',
      () async {
    final authRepository = _AuthRepository();
    final pendingStore = _PendingStore(_pending);
    final deletionRepository = _DeletionRepository()
      ..error = const ApiException(message: 'private socket details');
    final controller = AuthController(
      authRepository,
      accountDeletionRepository: deletionRepository,
      pendingAccountDeletionStore: pendingStore,
    );

    await controller.restoreSession();

    expect(pendingStore.value, _pending);
    expect(authRepository.tokens, isNotNull);
    expect(authRepository.refreshCalls, 0);
    expect(controller.status, AuthStatus.accountDeletionPending);
    expect(controller.errorMessage, isNot(contains('socket')));
  });

  test('token refresh during terminal lookup never exposes the account shell',
      () async {
    final authRepository = _AuthRepository();
    final controller = AuthController(authRepository);
    controller.holdPendingAccountDeletion();

    expect(await controller.refreshAccessToken(), 'access-1');
    expect(controller.status, AuthStatus.accountDeletionPending);
    expect(controller.currentUser, isNull);
  });

  test('corrupted pending marker keeps startup fail closed', () async {
    final authRepository = _AuthRepository();
    final pendingStore = _PendingStore(_pending)
      ..readError = const AccountDeletionPendingCorruptedException();
    final controller = AuthController(
      authRepository,
      accountDeletionRepository: _DeletionRepository(),
      pendingAccountDeletionStore: pendingStore,
    );

    await controller.restoreSession();

    expect(controller.status, AuthStatus.accountDeletionPending);
    expect(controller.currentUser, isNull);
    expect(authRepository.refreshCalls, 0);
    expect(pendingStore.clearCalls, 0);
  });

  test('exact blocked terminal clears pending and restores the account',
      () async {
    final authRepository = _AuthRepository();
    final pendingStore = _PendingStore(_pending);
    final deletionRepository = _DeletionRepository()
      ..error = const ApiException(
        message: 'blocked',
        httpStatus: 409,
        errorCode: 'ACCOUNT_DELETION_BLOCKED',
      );
    final controller = AuthController(
      authRepository,
      accountDeletionRepository: deletionRepository,
      pendingAccountDeletionStore: pendingStore,
    );

    await controller.restoreSession();

    expect(pendingStore.value, isNull);
    expect(authRepository.refreshCalls, 1);
    expect(controller.status, AuthStatus.authenticated);
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
    test('${scenario.key} keeps startup signed out with pending intact',
        () async {
      final authRepository = _AuthRepository();
      final pendingStore = _PendingStore(_pending);
      final deletionRepository = _DeletionRepository()..error = scenario.value;
      final controller = AuthController(
        authRepository,
        accountDeletionRepository: deletionRepository,
        pendingAccountDeletionStore: pendingStore,
      );

      await controller.restoreSession();

      expect(pendingStore.value, _pending);
      expect(authRepository.refreshCalls, 0);
      expect(controller.status, AuthStatus.accountDeletionPending);
      expect(controller.currentUser, isNull);
    });
  }

  test('failed pending clear after expiry remains signed out', () async {
    final authRepository = _AuthRepository();
    final pendingStore = _PendingStore(_pending)
      ..clearError = Exception('secure storage unavailable');
    final deletionRepository = _DeletionRepository()
      ..error = const ApiException(
        message: 'expired',
        httpStatus: 410,
        errorCode: 'ACCOUNT_DELETION_AUTHORIZATION_EXPIRED',
      );
    final controller = AuthController(
      authRepository,
      accountDeletionRepository: deletionRepository,
      pendingAccountDeletionStore: pendingStore,
    );

    await controller.restoreSession();

    expect(pendingStore.value, _pending);
    expect(authRepository.refreshCalls, 0);
    expect(controller.status, AuthStatus.accountDeletionPending);
  });

  for (final step in _CleanupFailure.values) {
    test('${step.name} cleanup failure retains pending and stays signed out',
        () async {
      final authRepository = _AuthRepository();
      final pendingStore = _PendingStore(_pending);
      final savedStore = _SavedStore([_saved('user-1', 'access-1')]);
      final loginStore = _LoginStore(
        const LoginPreferences(
          userId: 'user-1',
          phone: '+8613800000000',
          password: 'secret-password',
          rememberPassword: true,
          agreementsAccepted: true,
        ),
      );
      final messagingStore = _MessagingStore();
      if (step == _CleanupFailure.tokens) {
        authRepository.clearError = Exception('token clear failed');
      }
      if (step == _CleanupFailure.loginPreferences) {
        loginStore.clearError = Exception('preference clear failed');
      }
      if (step == _CleanupFailure.savedAccounts) {
        savedStore.saveError = Exception('saved account write failed');
      }
      if (step == _CleanupFailure.messagingPreferences) {
        messagingStore.error = Exception('messaging clear failed');
      }
      final controller = AuthController(
        authRepository,
        accountDeletionRepository: _DeletionRepository(),
        pendingAccountDeletionStore: pendingStore,
        savedAccountStore: savedStore,
        loginPreferencesStore: loginStore,
        messagingPreferencesStore: messagingStore,
        googleSessionClearer: () async {
          if (step == _CleanupFailure.googleSession) {
            throw Exception('google clear failed');
          }
        },
        accountCacheClearer: (_) async {
          if (step == _CleanupFailure.accountCache) {
            throw Exception('cache clear failed');
          }
        },
      );

      await controller.restoreSession();

      expect(pendingStore.value, _pending);
      expect(controller.status, AuthStatus.accountDeletionPending);
      expect(controller.currentUser, isNull);
    });
  }

  test('legacy preferences remain attributable across a failed cleanup retry',
      () async {
    final authRepository = _AuthRepository();
    final pendingStore = _PendingStore(_pending);
    final savedStore = _SavedStore([
      _saved(
        'user-1',
        'access-1',
        identifier: '+8613800000000',
      ),
      _saved('user-2', 'access-2'),
    ]);
    final loginStore = _LoginStore(
      const LoginPreferences(
        phone: '+8613800000000',
        password: 'legacy-password',
        rememberPassword: true,
        agreementsAccepted: true,
      ),
    )..clearError = Exception('first clear failed');
    final controller = AuthController(
      authRepository,
      accountDeletionRepository: _DeletionRepository(),
      pendingAccountDeletionStore: pendingStore,
      savedAccountStore: savedStore,
      loginPreferencesStore: loginStore,
    );

    await controller.restoreSession();

    expect(pendingStore.value, _pending);
    expect(savedStore.value.map((item) => item.userId), ['user-1', 'user-2']);
    expect(loginStore.value?.password, 'legacy-password');
    expect(controller.status, AuthStatus.accountDeletionPending);

    loginStore.clearError = null;
    await controller.retryPendingAccountDeletion();

    expect(pendingStore.value, isNull);
    expect(savedStore.value.map((item) => item.userId), ['user-2']);
    expect(loginStore.value, isNull);
    expect(controller.status, AuthStatus.unauthenticated);
  });

  test('pre-cleanup preference read failure preserves data and pending',
      () async {
    final authRepository = _AuthRepository();
    final pendingStore = _PendingStore(_pending);
    final savedStore = _SavedStore([
      _saved(
        'user-1',
        'access-1',
        identifier: '+8613800000000',
      ),
    ]);
    final loginStore = _LoginStore(
      const LoginPreferences(
        phone: '+8613800000000',
        password: 'legacy-password',
        rememberPassword: true,
        agreementsAccepted: true,
      ),
    )..readError = Exception('keystore read failed');
    final controller = AuthController(
      authRepository,
      accountDeletionRepository: _DeletionRepository(),
      pendingAccountDeletionStore: pendingStore,
      savedAccountStore: savedStore,
      loginPreferencesStore: loginStore,
    );

    await controller.restoreSession();

    expect(pendingStore.value, _pending);
    expect(loginStore.value?.password, 'legacy-password');
    expect(savedStore.value.single.userId, 'user-1');
    expect(controller.status, AuthStatus.accountDeletionPending);
  });
}

enum _CleanupFailure {
  tokens,
  loginPreferences,
  savedAccounts,
  messagingPreferences,
  googleSession,
  accountCache,
}

final class _AuthRepository implements AuthRepository {
  AuthTokens? tokens = const AuthTokens(
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    tokenType: 'Bearer',
    expiresIn: 3600,
  );
  int refreshCalls = 0;
  Object? clearError;

  @override
  Future<void> clearLocalTokens() async {
    final error = clearError;
    if (error != null) throw error;
    tokens = null;
  }

  @override
  Future<AuthTokens?> readTokens() async => tokens;

  @override
  Future<AuthSession> refreshSession() async {
    refreshCalls += 1;
    return AuthSession(tokens: tokens!, user: _user);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _DeletionRepository implements AccountSecurityRepository {
  final confirmedKeys = <String>[];
  Object? error;

  @override
  Future<AccountDeletionConfirmation> confirmAccountDeletion(
    PendingAccountDeletion pending,
  ) async {
    confirmedKeys.add(pending.idempotencyKey);
    final failure = error;
    if (failure != null) throw failure;
    return AccountDeletionConfirmation(
      requestId: pending.requestId,
      outcome: AccountDeletionOutcome.erased,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _PendingStore implements AccountDeletionPendingStore {
  _PendingStore(this.value);
  PendingAccountDeletion? value;
  Object? readError;
  Object? clearError;
  int clearCalls = 0;

  @override
  Future<void> clear() async {
    clearCalls += 1;
    final error = clearError;
    if (error != null) throw error;
    value = null;
  }

  @override
  Future<PendingAccountDeletion?> read() async {
    final error = readError;
    if (error != null) throw error;
    return value;
  }

  @override
  Future<void> save(PendingAccountDeletion pending) async => value = pending;
}

final class _SavedStore implements SavedAccountStore {
  _SavedStore(this.value);
  List<SavedAccount> value;
  Object? readError;
  Object? saveError;

  @override
  Future<List<SavedAccount>> read() async {
    final error = readError;
    if (error != null) throw error;
    return List.of(value);
  }

  @override
  Future<void> save(List<SavedAccount> accounts) async {
    final error = saveError;
    if (error != null) throw error;
    value = List.of(accounts);
  }
}

final class _LoginStore implements LoginPreferencesStore {
  _LoginStore(this.value);
  LoginPreferences? value;
  Object? readError;
  Object? clearError;

  @override
  Future<void> clear() async {
    final error = clearError;
    if (error != null) throw error;
    value = null;
  }

  @override
  Future<LoginPreferences?> read() async {
    final error = readError;
    if (error != null) throw error;
    return value;
  }

  @override
  Future<void> save(LoginPreferences preferences) async => value = preferences;
}

final class _MessagingStore implements MessagingPreferencesStore {
  final clearedUsers = <String>[];
  Object? error;

  @override
  Future<void> clear(String currentUserId) async {
    final failure = error;
    if (failure != null) throw failure;
    clearedUsers.add(currentUserId);
  }

  @override
  Future<MessagingPreferences> read(String currentUserId) async =>
      MessagingPreferences();

  @override
  Future<void> write(
    String currentUserId,
    MessagingPreferences preferences,
  ) async {}
}

SavedAccount _saved(
  String userId,
  String accessToken, {
  String? identifier,
}) =>
    SavedAccount(
      userId: userId,
      nickname: userId,
      avatar: '',
      identifier: identifier ?? '$userId@example.com',
      tokens: AuthTokens(
        accessToken: accessToken,
        refreshToken: 'refresh-$userId',
        tokenType: 'Bearer',
        expiresIn: 3600,
      ),
    );

const _user = AuthUser(
  id: 'user-1',
  phone: '+8613800000000',
  email: null,
  nickname: 'User 1',
  avatar: '',
  gender: '',
  city: '',
  bio: '',
  birthday: null,
  role: 'USER',
  hasPassword: true,
);

const _pending = PendingAccountDeletion(
  requestId: 'request-1',
  idempotencyKey: 'delete-key-1',
  deletionAuthorization: 'delete-auth-1',
  policyVersion: 'dev-v1',
  userId: 'user-1',
);
