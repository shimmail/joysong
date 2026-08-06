import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/auth/domain/auth_repository.dart';
import 'package:joysong_flutter/features/auth/presentation/auth_controller.dart';

void main() {
  test('registration stores the real session and authenticates the app',
      () async {
    final repository = _ActionAuthRepository();
    final controller = AuthController(repository);

    final succeeded = await controller.registerAccount(
      '+8613800000000',
      '123456',
      'password8',
    );

    expect(succeeded, isTrue);
    expect(controller.status, AuthStatus.authenticated);
    expect(controller.currentUser?.id, 'new-user');
    expect(repository.registerPhone, '+8613800000000');
    controller.dispose();
  });

  test('password reset stays signed out and forwards real request fields',
      () async {
    final repository = _ActionAuthRepository();
    final controller = AuthController(repository);
    await controller.restoreSession();

    final succeeded = await controller.resetPassword(
      '+8613800000000',
      '654321',
      'newPassword8',
    );

    expect(succeeded, isTrue);
    expect(controller.status, AuthStatus.unauthenticated);
    expect(repository.resetCode, '654321');
    expect(repository.resetPasswordValue, 'newPassword8');
    controller.dispose();
  });
}

final class _ActionAuthRepository implements AuthRepository {
  String? registerPhone;
  String? resetCode;
  String? resetPasswordValue;

  @override
  Future<AuthSession> register({
    required String phone,
    required String code,
    required String password,
  }) async {
    registerPhone = phone;
    return _session;
  }

  @override
  Future<void> resetPassword({
    required String phone,
    required String code,
    required String newPassword,
  }) async {
    resetCode = code;
    resetPasswordValue = newPassword;
  }

  @override
  Future<AuthTokens?> readTokens() async => null;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final _session = AuthSession(
  tokens: const AuthTokens(
    accessToken: 'access',
    refreshToken: 'refresh',
    tokenType: 'Bearer',
    expiresIn: 3600,
  ),
  user: const AuthUser(
    id: 'new-user',
    phone: '+8613800000000',
    email: null,
    nickname: 'New user',
    avatar: '',
    gender: '',
    city: '',
    bio: '',
    birthday: null,
    role: 'USER',
    hasPassword: true,
  ),
);
