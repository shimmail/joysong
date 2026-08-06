import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/auth/data/secure_token_store.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';

void main() {
  group('SecureTokenStore', () {
    test('stores and restores the complete token pair as one value', () async {
      final storage = _MemorySecureStore();
      final store = SecureTokenStore(storage: storage);
      const tokens = AuthTokens(
        accessToken: 'access-token',
        refreshToken: 'refresh-token',
        tokenType: 'Bearer',
        expiresIn: 3600,
      );

      await store.save(tokens);

      expect(storage.values, hasLength(1));
      final persisted =
          jsonDecode(storage.values.values.single) as Map<String, dynamic>;
      expect(persisted['accessToken'], 'access-token');
      expect(persisted['refreshToken'], 'refresh-token');

      final restored = await store.read();
      expect(restored?.accessToken, tokens.accessToken);
      expect(restored?.refreshToken, tokens.refreshToken);
      expect(restored?.tokenType, tokens.tokenType);
      expect(restored?.expiresIn, tokens.expiresIn);
    });

    test('clears a malformed persisted session', () async {
      final storage = _MemorySecureStore();
      final store = SecureTokenStore(storage: storage);
      await store.save(
        const AuthTokens(
          accessToken: 'access-token',
          refreshToken: 'refresh-token',
          tokenType: 'Bearer',
          expiresIn: 3600,
        ),
      );
      storage.values[storage.values.keys.last] = '{not-json';

      expect(await store.read(), isNull);
      expect(storage.values, isEmpty);
    });

    test('clear removes the persisted session', () async {
      final storage = _MemorySecureStore();
      final store = SecureTokenStore(storage: storage);
      await store.save(
        const AuthTokens(
          accessToken: 'access-token',
          refreshToken: 'refresh-token',
          tokenType: 'Bearer',
          expiresIn: 3600,
        ),
      );

      await store.clear();

      expect(await store.read(), isNull);
      expect(storage.values, isEmpty);
    });
  });
}

class _MemorySecureStore implements SecureKeyValueStore {
  final values = <String, String>{};

  @override
  Future<void> delete(String key) async {
    values.remove(key);
  }

  @override
  Future<String?> read(String key) async => values[key];

  @override
  Future<void> write(String key, String value) async {
    values[key] = value;
  }
}
