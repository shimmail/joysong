import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/auth/data/login_preferences_store.dart';
import 'package:joysong_flutter/features/auth/data/secure_token_store.dart';

void main() {
  group('SecureLoginPreferencesStore', () {
    test('stores all login preferences in one secure value', () async {
      final storage = _MemorySecureStore();
      final store = SecureLoginPreferencesStore(storage: storage);

      await store.save(
        const LoginPreferences(
          userId: 'user-1',
          phone: '+8613800000000',
          password: 'password8',
          rememberPassword: true,
          autoLogin: true,
          agreementsAccepted: true,
        ),
      );

      expect(storage.values, hasLength(1));
      final persisted =
          jsonDecode(storage.values.values.single) as Map<String, dynamic>;
      expect(persisted, {
        'userId': 'user-1',
        'phone': '+8613800000000',
        'password': 'password8',
        'rememberPassword': true,
        'autoLogin': true,
        'agreementsAccepted': true,
      });

      final restored = await store.read();
      expect(restored?.phone, '+8613800000000');
      expect(restored?.userId, 'user-1');
      expect(restored?.password, 'password8');
      expect(restored?.canAutoLogin, isTrue);
    });

    test('never retains a password when remember password is disabled',
        () async {
      final storage = _MemorySecureStore();
      final store = SecureLoginPreferencesStore(storage: storage);

      await store.save(
        const LoginPreferences(
          phone: '+8613800000000',
          password: 'must-not-survive',
          autoLogin: true,
          agreementsAccepted: true,
        ),
      );

      final restored = await store.read();
      expect(restored?.password, isEmpty);
      expect(restored?.rememberPassword, isFalse);
      expect(restored?.autoLogin, isFalse);
      expect(storage.values.values.single, isNot(contains('must-not-survive')));
    });

    test('clears malformed persisted preferences', () async {
      final storage = _MemorySecureStore();
      final store = SecureLoginPreferencesStore(storage: storage);
      storage.values['unknown-key'] = '{not-json';

      // First save discovers the private key without exposing it to callers.
      await store.save(const LoginPreferences(phone: '+8613800000000'));
      final key = storage.values.keys.last;
      storage.values
        ..clear()
        ..[key] = '{not-json';

      expect(await store.read(), isNull);
      expect(storage.values, isEmpty);
    });

    test('redacts the password from diagnostics', () {
      const preferences = LoginPreferences(
        phone: '+8613800000000',
        password: 'password8',
        rememberPassword: true,
      );

      expect(preferences.toString(), isNot(contains('password8')));
    });
  });
}

final class _MemorySecureStore implements SecureKeyValueStore {
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
