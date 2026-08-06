import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/auth/data/auth_requests.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';

abstract interface class AuthRemoteDataSource {
  Future<AuthSession> loginWithPassword(PasswordLoginRequest request);

  Future<AuthSession> loginWithCode(CodeLoginRequest request);

  Future<AuthSession> loginWithGoogle(GoogleLoginRequest request);

  Future<AuthSession> register(RegisterRequest request);

  Future<void> sendCode(SendCodeRequest request);

  Future<bool> checkPhoneRegistered(String phone);

  Future<void> resetPassword(ResetPasswordRequest request);

  Future<AuthSession> refresh(RefreshTokenRequest request);

  Future<void> logout(RefreshTokenRequest request);
}

final class ApiAuthRemoteDataSource implements AuthRemoteDataSource {
  const ApiAuthRemoteDataSource(this._apiClient);

  final ApiClient _apiClient;

  @override
  Future<AuthSession> loginWithPassword(PasswordLoginRequest request) =>
      _sessionPost('auth/login', request.toJson());

  @override
  Future<AuthSession> loginWithCode(CodeLoginRequest request) =>
      _sessionPost('auth/login-with-code', request.toJson());

  @override
  Future<AuthSession> loginWithGoogle(GoogleLoginRequest request) =>
      _sessionPost('auth/login-with-google', request.toJson());

  @override
  Future<AuthSession> register(RegisterRequest request) =>
      _sessionPost('auth/register', request.toJson());

  @override
  Future<void> sendCode(SendCodeRequest request) async {
    await _apiClient.post<Object?>(
      'auth/send-code',
      body: request.toJson(),
      decodeData: (json) => json,
    );
  }

  @override
  Future<bool> checkPhoneRegistered(String phone) async {
    final registered = await _apiClient.get<bool>(
      'auth/check-phone-registered',
      query: {'phone': normalizeE164Phone(phone)},
      decodeData: (json) {
        final map = _map(json, '手机号检查响应');
        final value = map['registered'];
        if (value is! bool) {
          throw const FormatException('手机号检查响应缺少 registered');
        }
        return value;
      },
    );
    if (registered == null) {
      throw const FormatException('手机号检查响应 data 为空');
    }
    return registered;
  }

  @override
  Future<void> resetPassword(ResetPasswordRequest request) async {
    await _apiClient.post<Object?>(
      'auth/reset-password',
      body: request.toJson(),
      decodeData: (json) => json,
    );
  }

  @override
  Future<AuthSession> refresh(RefreshTokenRequest request) =>
      _sessionPost('auth/refresh', request.toJson());

  @override
  Future<void> logout(RefreshTokenRequest request) async {
    await _apiClient.post<Object?>(
      'auth/logout',
      body: request.toJson(),
      decodeData: (json) => json,
    );
  }

  Future<AuthSession> _sessionPost(
    String path,
    Map<String, Object> body,
  ) async {
    final session = await _apiClient.post<AuthSession>(
      path,
      body: body,
      decodeData: AuthSession.fromJson,
    );
    if (session == null) {
      throw const FormatException('登录响应 data 为空');
    }
    return session;
  }
}

Map<String, dynamic> _map(Object? value, String label) {
  if (value is! Map) {
    throw FormatException('$label不是 JSON 对象');
  }
  try {
    return value.cast<String, dynamic>();
  } on TypeError {
    throw FormatException('$label包含无效字段');
  }
}
