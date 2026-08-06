import 'package:joysong_flutter/features/auth/domain/auth_models.dart';

abstract interface class AuthRepository {
  Future<AuthSession> loginWithPassword({
    required String phone,
    required String password,
  });

  Future<AuthSession> loginWithCode({
    required String phone,
    required String code,
  });

  Future<AuthSession> loginWithGoogle({required String idToken});

  Future<AuthSession> register({
    required String phone,
    required String code,
    required String password,
  });

  Future<void> sendCode({required String phone});

  Future<bool> checkPhoneRegistered({required String phone});

  Future<void> resetPassword({
    required String phone,
    required String code,
    required String newPassword,
  });

  Future<AuthSession> refreshSession();

  Future<void> logout();

  Future<AuthTokens?> readTokens();

  Future<void> activateTokens(AuthTokens tokens);

  Future<void> clearLocalTokens();
}
