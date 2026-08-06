import 'package:joysong_flutter/features/auth/domain/auth_models.dart';

abstract interface class TokenStore {
  Future<AuthTokens?> read();

  /// Implementations must replace the access and refresh token as one value.
  /// A failed write must leave the previous token pair intact.
  Future<void> save(AuthTokens tokens);

  Future<void> clear();
}
