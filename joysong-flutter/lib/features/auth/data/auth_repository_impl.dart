import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/auth/data/auth_remote_data_source.dart';
import 'package:joysong_flutter/features/auth/data/auth_requests.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/auth/domain/auth_repository.dart';
import 'package:joysong_flutter/features/auth/domain/token_store.dart';

final class AuthRepositoryImpl implements AuthRepository {
  AuthRepositoryImpl({
    required AuthRemoteDataSource remoteDataSource,
    required TokenStore tokenStore,
  })  : _remoteDataSource = remoteDataSource,
        _tokenStore = tokenStore;

  final AuthRemoteDataSource _remoteDataSource;
  final TokenStore _tokenStore;
  Future<AuthSession>? _refreshInFlight;

  @override
  Future<AuthSession> loginWithPassword({
    required String phone,
    required String password,
  }) async {
    final session = await _remoteDataSource.loginWithPassword(
      PasswordLoginRequest(phone: phone, password: password),
    );
    return _saveSession(session);
  }

  @override
  Future<AuthSession> loginWithCode({
    required String phone,
    required String code,
  }) async {
    final session = await _remoteDataSource.loginWithCode(
      CodeLoginRequest(phone: phone, code: code),
    );
    return _saveSession(session);
  }

  @override
  Future<AuthSession> loginWithGoogle({required String idToken}) async {
    final session = await _remoteDataSource.loginWithGoogle(
      GoogleLoginRequest(idToken: idToken),
    );
    return _saveSession(session);
  }

  @override
  Future<AuthSession> register({
    required String phone,
    required String code,
    required String password,
  }) async {
    final session = await _remoteDataSource.register(
      RegisterRequest(phone: phone, code: code, password: password),
    );
    return _saveSession(session);
  }

  @override
  Future<void> sendCode({required String phone}) =>
      _remoteDataSource.sendCode(SendCodeRequest(phone: phone));

  @override
  Future<bool> checkPhoneRegistered({required String phone}) =>
      _remoteDataSource.checkPhoneRegistered(phone);

  @override
  Future<void> resetPassword({
    required String phone,
    required String code,
    required String newPassword,
  }) =>
      _remoteDataSource.resetPassword(
        ResetPasswordRequest(
          phone: phone,
          code: code,
          newPassword: newPassword,
        ),
      );

  @override
  Future<AuthSession> refreshSession() {
    final running = _refreshInFlight;
    if (running != null) {
      return running;
    }

    late final Future<AuthSession> operation;
    operation = _performRefresh().whenComplete(() {
      if (identical(_refreshInFlight, operation)) {
        _refreshInFlight = null;
      }
    });
    _refreshInFlight = operation;
    return operation;
  }

  Future<AuthSession> _performRefresh() async {
    final current = await _tokenStore.read();
    if (current == null) {
      throw const AuthSessionMissingException();
    }
    try {
      final session = await _remoteDataSource.refresh(
        RefreshTokenRequest(refreshToken: current.refreshToken),
      );
      return _saveSession(session);
    } on ApiException catch (error) {
      if (error.isUnauthorized) {
        await _tokenStore.clear();
      }
      rethrow;
    }
  }

  @override
  Future<void> logout() async {
    try {
      final current = await _tokenStore.read();
      if (current != null) {
        await _remoteDataSource.logout(
          RefreshTokenRequest(refreshToken: current.refreshToken),
        );
      }
    } finally {
      await _tokenStore.clear();
    }
  }

  @override
  Future<AuthTokens?> readTokens() => _tokenStore.read();

  Future<AuthSession> _saveSession(AuthSession session) async {
    await _tokenStore.save(session.tokens);
    return session;
  }
}

final class AuthSessionMissingException implements Exception {
  const AuthSessionMissingException();

  @override
  String toString() => 'AuthSessionMissingException(本地没有可刷新的登录会话)';
}
