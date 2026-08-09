import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/auth/data/login_preferences_store.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/auth/domain/auth_repository.dart';
import 'package:joysong_flutter/features/auth/presentation/auth_controller.dart';

void main() {
  late _FakeAuthRepository repository;
  late _MemoryLoginPreferencesStore preferencesStore;
  late AuthController controller;

  setUp(() {
    repository = _FakeAuthRepository();
    preferencesStore = _MemoryLoginPreferencesStore();
    controller = AuthController(
      repository,
      loginPreferencesStore: preferencesStore,
    );
  });

  test('keeps the original constructor compatible without a preference store',
      () async {
    repository.tokens = _tokens('access-existing', 'refresh-existing');
    final compatibleController = AuthController(repository);

    await compatibleController.restoreSession();

    expect(compatibleController.status, AuthStatus.authenticated);
    expect(compatibleController.currentUser?.id, 'user-1');
    expect(repository.refreshCount, 1);
    expect(compatibleController.loginPreferences.phone, isEmpty);
    compatibleController.dispose();
  });

  test('restores preferences and automatically logs in when no token exists',
      () async {
    preferencesStore.value = const LoginPreferences(
      phone: '+8613800000000',
      password: 'password8',
      rememberPassword: true,
      autoLogin: true,
      agreementsAccepted: true,
    );

    await controller.restoreSession();

    expect(repository.passwordLoginCount, 1);
    expect(repository.lastPhone, '+8613800000000');
    expect(repository.lastPassword, 'password8');
    expect(controller.status, AuthStatus.authenticated);
    expect(controller.currentUser?.id, 'user-1');
    expect(controller.loginPreferences.canAutoLogin, isTrue);
  });

  test('existing tokens are refreshed to restore the current user safely',
      () async {
    repository.tokens = _tokens('access-existing', 'refresh-existing');

    await controller.restoreSession();

    expect(repository.refreshCount, 1);
    expect(controller.status, AuthStatus.authenticated);
    expect(controller.currentUser?.id, 'user-1');
  });

  test('failed token refresh does not expose a user-less authenticated shell',
      () async {
    repository.tokens = _tokens('access-existing', 'refresh-existing');
    repository.refreshError = Exception('offline');
    final englishController = AuthController(
      repository,
      messageResolver: (_, english) => english,
    );

    await englishController.restoreSession();

    expect(englishController.status, AuthStatus.unauthenticated);
    expect(englishController.currentUser, isNull);
    expect(
      englishController.errorMessage,
      'Could not restore your session. Check your connection and try again.',
    );
    englishController.dispose();
  });

  test('does not auto login without accepted agreements', () async {
    preferencesStore.value = const LoginPreferences(
      phone: '+8613800000000',
      password: 'password8',
      rememberPassword: true,
      autoLogin: true,
    );

    await controller.restoreSession();

    expect(repository.passwordLoginCount, 0);
    expect(controller.status, AuthStatus.unauthenticated);
    expect(controller.loginPreferences.autoLogin, isFalse);
  });

  test('password login persists the selected preference flags', () async {
    await controller.restoreSession();

    await controller.loginWithPassword(
      '+8613800000000',
      'password8',
      rememberPassword: true,
      autoLogin: true,
      agreementsAccepted: true,
    );

    expect(controller.status, AuthStatus.authenticated);
    expect(preferencesStore.value?.phone, '+8613800000000');
    expect(preferencesStore.value?.password, 'password8');
    expect(preferencesStore.value?.rememberPassword, isTrue);
    expect(preferencesStore.value?.autoLogin, isTrue);
    expect(preferencesStore.value?.agreementsAccepted, isTrue);
  });

  test('remember password off forces password and auto login off', () async {
    await controller.restoreSession();

    await controller.loginWithPassword(
      '+8613800000000',
      'password8',
      autoLogin: true,
      agreementsAccepted: true,
    );

    expect(preferencesStore.value?.password, isEmpty);
    expect(preferencesStore.value?.rememberPassword, isFalse);
    expect(preferencesStore.value?.autoLogin, isFalse);
  });

  test('verification-code login disables auto login for the same account',
      () async {
    preferencesStore.value = const LoginPreferences(
      phone: '+8613800000000',
      password: 'password8',
      rememberPassword: true,
      autoLogin: true,
      agreementsAccepted: true,
    );
    await controller.restoreSession();
    repository.tokens = null;

    await controller.loginWithCode(
      '+8613800000000',
      '123456',
      agreementsAccepted: true,
    );

    expect(preferencesStore.value?.password, 'password8');
    expect(preferencesStore.value?.rememberPassword, isTrue);
    expect(preferencesStore.value?.autoLogin, isFalse);
  });

  test('verification-code login for another account clears old password',
      () async {
    preferencesStore.value = const LoginPreferences(
      phone: '+8613800000000',
      password: 'password8',
      rememberPassword: true,
      autoLogin: true,
      agreementsAccepted: true,
    );
    await controller.restoreSession();

    await controller.loginWithCode(
      '+8613900000000',
      '123456',
      agreementsAccepted: true,
    );

    expect(preferencesStore.value?.phone, '+8613900000000');
    expect(preferencesStore.value?.password, isEmpty);
    expect(preferencesStore.value?.rememberPassword, isFalse);
    expect(preferencesStore.value?.autoLogin, isFalse);
  });

  test('failed automatic login disables future auto login but keeps password',
      () async {
    preferencesStore.value = const LoginPreferences(
      phone: '+8613800000000',
      password: 'password8',
      rememberPassword: true,
      autoLogin: true,
      agreementsAccepted: true,
    );
    repository.passwordLoginError = const ApiException(
      message: '密码错误',
      businessCode: 400,
    );

    await controller.restoreSession();

    expect(controller.status, AuthStatus.unauthenticated);
    expect(controller.errorMessage, '密码错误');
    expect(preferencesStore.value?.password, 'password8');
    expect(preferencesStore.value?.rememberPassword, isTrue);
    expect(preferencesStore.value?.autoLogin, isFalse);
  });

  test('explicit logout disables auto login and prevents immediate re-login',
      () async {
    preferencesStore.value = const LoginPreferences(
      phone: '+8613800000000',
      password: 'password8',
      rememberPassword: true,
      autoLogin: true,
      agreementsAccepted: true,
    );
    repository.tokens = _tokens('access-existing', 'refresh-existing');
    await controller.restoreSession();

    await controller.logout();
    await controller.restoreSession();

    expect(repository.logoutCount, 1);
    expect(repository.passwordLoginCount, 0);
    expect(controller.status, AuthStatus.unauthenticated);
    expect(preferencesStore.value?.phone, '+8613800000000');
    expect(preferencesStore.value?.password, 'password8');
    expect(preferencesStore.value?.rememberPassword, isTrue);
    expect(preferencesStore.value?.autoLogin, isFalse);
  });

  test('remote logout failure still preserves credentials with auto login off',
      () async {
    preferencesStore.value = const LoginPreferences(
      phone: '+8613800000000',
      password: 'password8',
      rememberPassword: true,
      autoLogin: true,
      agreementsAccepted: true,
    );
    repository.tokens = _tokens('access-existing', 'refresh-existing');
    repository.logoutError = const ApiException(message: '网络连接失败');
    await controller.restoreSession();

    await controller.logout();

    expect(controller.status, AuthStatus.unauthenticated);
    expect(controller.errorMessage, '网络连接失败');
    expect(preferencesStore.value?.phone, '+8613800000000');
    expect(preferencesStore.value?.password, 'password8');
    expect(preferencesStore.value?.rememberPassword, isTrue);
    expect(preferencesStore.value?.autoLogin, isFalse);
  });
}

final class _MemoryLoginPreferencesStore implements LoginPreferencesStore {
  LoginPreferences? value;
  int clearCount = 0;

  @override
  Future<void> clear() async {
    clearCount += 1;
    value = null;
  }

  @override
  Future<LoginPreferences?> read() async => value;

  @override
  Future<void> save(LoginPreferences preferences) async {
    value = preferences.normalized();
  }
}

final class _FakeAuthRepository implements AuthRepository {
  AuthTokens? tokens;
  Object? passwordLoginError;
  Object? logoutError;
  Object? refreshError;
  String? lastPhone;
  String? lastPassword;
  int passwordLoginCount = 0;
  int logoutCount = 0;
  int refreshCount = 0;

  @override
  Future<bool> checkPhoneRegistered({required String phone}) async => false;

  @override
  Future<AuthSession> loginWithCode({
    required String phone,
    required String code,
  }) async =>
      _session();

  @override
  Future<AuthSession> loginWithGoogle({required String idToken}) async =>
      _session();

  @override
  Future<AuthSession> loginWithPassword({
    required String phone,
    required String password,
  }) async {
    passwordLoginCount += 1;
    lastPhone = phone;
    lastPassword = password;
    final error = passwordLoginError;
    if (error != null) {
      throw error;
    }
    tokens = _tokens('access-new', 'refresh-new');
    return _session();
  }

  @override
  Future<void> logout() async {
    logoutCount += 1;
    tokens = null;
    final error = logoutError;
    if (error != null) {
      throw error;
    }
  }

  @override
  Future<AuthSession> refreshSession() async {
    refreshCount += 1;
    final error = refreshError;
    if (error != null) {
      throw error;
    }
    return _session();
  }

  @override
  Future<AuthSession> register({
    required String phone,
    required String code,
    required String password,
  }) async =>
      _session();

  @override
  Future<AuthTokens?> readTokens() async => tokens;

  @override
  Future<void> activateTokens(AuthTokens value) async {
    tokens = value;
  }

  @override
  Future<void> clearLocalTokens() async {
    tokens = null;
  }

  @override
  Future<void> resetPassword({
    required String phone,
    required String code,
    required String newPassword,
  }) async {}

  @override
  Future<void> sendCode({required String phone}) async {}
}

AuthSession _session() => AuthSession(
      tokens: _tokens('access-new', 'refresh-new'),
      user: const AuthUser(
        id: 'user-1',
        phone: '+8613800000000',
        email: null,
        nickname: '用户',
        avatar: '',
        gender: '',
        city: '',
        bio: '',
        birthday: null,
        role: 'USER',
        hasPassword: true,
      ),
    );

AuthTokens _tokens(String accessToken, String refreshToken) => AuthTokens(
      accessToken: accessToken,
      refreshToken: refreshToken,
      tokenType: 'Bearer',
      expiresIn: 86400,
    );
