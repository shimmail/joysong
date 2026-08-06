import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/auth/data/auth_remote_data_source.dart';
import 'package:joysong_flutter/features/auth/data/auth_repository_impl.dart';
import 'package:joysong_flutter/features/auth/data/auth_requests.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/auth/domain/token_store.dart';

void main() {
  late _FakeRemoteDataSource remote;
  late _MemoryTokenStore tokenStore;
  late AuthRepositoryImpl repository;

  setUp(() {
    remote = _FakeRemoteDataSource();
    tokenStore = _MemoryTokenStore();
    repository = AuthRepositoryImpl(
      remoteDataSource: remote,
      tokenStore: tokenStore,
    );
  });

  test('login saves the access and refresh token in one store call', () async {
    remote.session = _session('access-1', 'refresh-1');

    final session = await repository.loginWithPassword(
      phone: '+86 138-0000-0000',
      password: 'password8',
    );

    expect(remote.passwordLoginRequest?.phone, '+8613800000000');
    expect(tokenStore.saveCount, 1);
    expect(tokenStore.value, same(session.tokens));
    expect(tokenStore.value?.accessToken, 'access-1');
    expect(tokenStore.value?.refreshToken, 'refresh-1');
  });

  test('concurrent refresh callers share one rotated token request', () async {
    tokenStore.value = _tokens('access-old', 'refresh-old');
    final completer = Completer<AuthSession>();
    remote.refreshCompleter = completer;

    final first = repository.refreshSession();
    final second = repository.refreshSession();
    await Future<void>.delayed(Duration.zero);

    expect(identical(first, second), isTrue);
    expect(remote.refreshCount, 1);
    expect(remote.refreshRequest?.refreshToken, 'refresh-old');

    completer.complete(_session('access-new', 'refresh-new'));
    final sessions = await Future.wait([first, second]);

    expect(sessions[0].tokens.accessToken, 'access-new');
    expect(tokenStore.value?.refreshToken, 'refresh-new');
    expect(tokenStore.saveCount, 1);
  });

  test('unauthorized refresh clears the invalid local token pair', () async {
    tokenStore.value = _tokens('access-old', 'refresh-old');
    remote.refreshError = const ApiException(
      message: '刷新令牌无效',
      httpStatus: 401,
      businessCode: 401,
    );

    await expectLater(
        repository.refreshSession(), throwsA(isA<ApiException>()));

    expect(tokenStore.value, isNull);
    expect(tokenStore.clearCount, 1);
  });

  test('transient refresh failure preserves the existing token pair', () async {
    final existing = _tokens('access-old', 'refresh-old');
    tokenStore.value = existing;
    remote.refreshError = const ApiException(message: '网络连接失败');

    await expectLater(
        repository.refreshSession(), throwsA(isA<ApiException>()));

    expect(tokenStore.value, same(existing));
    expect(tokenStore.clearCount, 0);
  });

  test('logout clears tokens even when the remote request fails', () async {
    tokenStore.value = _tokens('access-old', 'refresh-old');
    remote.logoutError = const ApiException(message: '网络连接失败');

    await expectLater(repository.logout(), throwsA(isA<ApiException>()));

    expect(remote.logoutRequest?.refreshToken, 'refresh-old');
    expect(tokenStore.value, isNull);
    expect(tokenStore.clearCount, 1);
  });
}

final class _MemoryTokenStore implements TokenStore {
  AuthTokens? value;
  int saveCount = 0;
  int clearCount = 0;

  @override
  Future<void> clear() async {
    clearCount += 1;
    value = null;
  }

  @override
  Future<AuthTokens?> read() async => value;

  @override
  Future<void> save(AuthTokens tokens) async {
    saveCount += 1;
    value = tokens;
  }
}

final class _FakeRemoteDataSource implements AuthRemoteDataSource {
  AuthSession session = _session('access-default', 'refresh-default');
  PasswordLoginRequest? passwordLoginRequest;
  RefreshTokenRequest? refreshRequest;
  RefreshTokenRequest? logoutRequest;
  Completer<AuthSession>? refreshCompleter;
  Object? refreshError;
  Object? logoutError;
  int refreshCount = 0;

  @override
  Future<bool> checkPhoneRegistered(String phone) async => false;

  @override
  Future<AuthSession> loginWithCode(CodeLoginRequest request) async => session;

  @override
  Future<AuthSession> loginWithGoogle(GoogleLoginRequest request) async =>
      session;

  @override
  Future<AuthSession> loginWithPassword(PasswordLoginRequest request) async {
    passwordLoginRequest = request;
    return session;
  }

  @override
  Future<void> logout(RefreshTokenRequest request) async {
    logoutRequest = request;
    final error = logoutError;
    if (error != null) {
      throw error;
    }
  }

  @override
  Future<AuthSession> refresh(RefreshTokenRequest request) {
    refreshCount += 1;
    refreshRequest = request;
    final error = refreshError;
    if (error != null) {
      return Future<AuthSession>.error(error);
    }
    return refreshCompleter?.future ?? Future.value(session);
  }

  @override
  Future<AuthSession> register(RegisterRequest request) async => session;

  @override
  Future<void> resetPassword(ResetPasswordRequest request) async {}

  @override
  Future<void> sendCode(SendCodeRequest request) async {}
}

AuthSession _session(String accessToken, String refreshToken) => AuthSession(
      tokens: _tokens(accessToken, refreshToken),
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
