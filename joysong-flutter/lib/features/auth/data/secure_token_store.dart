import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/auth/domain/token_store.dart';

abstract interface class SecureKeyValueStore {
  Future<String?> read(String key);

  Future<void> write(String key, String value);

  Future<void> delete(String key);
}

final class FlutterSecureKeyValueStore implements SecureKeyValueStore {
  FlutterSecureKeyValueStore({FlutterSecureStorage? storage})
      : _storage = storage ??
            const FlutterSecureStorage(
              aOptions: AndroidOptions(migrateWithBackup: true),
              iOptions: IOSOptions(
                accessibility: KeychainAccessibility.first_unlock_this_device,
              ),
            );

  final FlutterSecureStorage _storage;

  @override
  Future<String?> read(String key) => _storage.read(key: key);

  @override
  Future<void> write(String key, String value) =>
      _storage.write(key: key, value: value);

  @override
  Future<void> delete(String key) => _storage.delete(key: key);
}

final class SecureTokenStore implements TokenStore {
  SecureTokenStore({SecureKeyValueStore? storage})
      : _storage = storage ?? FlutterSecureKeyValueStore();

  static const _sessionKey = 'joysong.auth.session.v1';

  final SecureKeyValueStore _storage;

  @override
  Future<AuthTokens?> read() async {
    final rawValue = await _storage.read(_sessionKey);
    if (rawValue == null || rawValue.isEmpty) {
      return null;
    }

    try {
      final decoded = jsonDecode(rawValue);
      if (decoded is! Map) {
        throw const FormatException('安全凭证格式不正确');
      }
      return AuthTokens.fromJson(decoded.cast<String, dynamic>());
    } on FormatException {
      await clear();
      return null;
    } on TypeError {
      await clear();
      return null;
    }
  }

  @override
  Future<void> save(AuthTokens tokens) {
    final value = jsonEncode({
      'accessToken': tokens.accessToken,
      'refreshToken': tokens.refreshToken,
      'tokenType': tokens.tokenType,
      'expiresIn': tokens.expiresIn,
    });
    // The complete token pair is stored in one encrypted value so a rotation
    // can never expose a new access token with an old refresh token.
    return _storage.write(_sessionKey, value);
  }

  @override
  Future<void> clear() => _storage.delete(_sessionKey);
}
