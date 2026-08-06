import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/auth/data/secure_token_store.dart';
import 'package:joysong_flutter/features/settings/data/settings_preferences_store.dart';
import 'package:joysong_flutter/features/settings/domain/settings_preferences.dart';

void main() {
  test('settings preferences survive a secure-store round trip', () async {
    final storage = _MemorySecureStore();
    final store = SecureSettingsPreferenceStore(storage: storage);
    const expected = SettingsPreferences(
      appearanceMode: AppAppearanceMode.dark,
      notifications: NotificationPreferences(
        enabled: true,
        orderUpdates: false,
        socialActivity: true,
        serviceMessages: false,
        productNews: true,
      ),
    );

    await store.write(expected);

    expect(await store.read(), expected);
    final encoded = jsonDecode(
      storage.values[SecureSettingsPreferenceStore.preferenceKey]!,
    ) as Map<String, dynamic>;
    expect(encoded['version'], 1);
    expect(encoded['appearanceMode'], 'dark');
  });

  test('corrupt preferences are removed and safe defaults are returned',
      () async {
    final storage = _MemorySecureStore()
      ..values[SecureSettingsPreferenceStore.preferenceKey] =
          '{"appearanceMode": 17}';
    final store = SecureSettingsPreferenceStore(storage: storage);

    final restored = await store.read();

    expect(restored, const SettingsPreferences());
    expect(
      storage.values.containsKey(SecureSettingsPreferenceStore.preferenceKey),
      isFalse,
    );
  });

  test('missing notification fields use privacy-conscious defaults', () {
    final preferences = SettingsPreferences.fromJson({
      'appearanceMode': 'system',
      'notifications': <String, Object?>{'enabled': false},
    });

    expect(preferences.notifications.enabled, isFalse);
    expect(preferences.notifications.orderUpdates, isTrue);
    expect(preferences.notifications.productNews, isFalse);
  });
}

final class _MemorySecureStore implements SecureKeyValueStore {
  final Map<String, String> values = {};

  @override
  Future<void> delete(String key) async => values.remove(key);

  @override
  Future<String?> read(String key) async => values[key];

  @override
  Future<void> write(String key, String value) async => values[key] = value;
}
