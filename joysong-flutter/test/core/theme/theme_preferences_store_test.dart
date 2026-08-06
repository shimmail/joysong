import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/theme/theme_preferences_store.dart';
import 'package:joysong_flutter/features/auth/data/secure_token_store.dart';

void main() {
  late _MemorySecureStore secureStore;
  late SecureThemePreferenceStore preferenceStore;

  setUp(() {
    secureStore = _MemorySecureStore();
    preferenceStore = SecureThemePreferenceStore(storage: secureStore);
  });

  test('round-trips the complete ARGB color through secure storage', () async {
    const color = Color(0xFFBFD8C5);

    await preferenceStore.writeSeedColor(color);

    expect(
      secureStore.values[SecureThemePreferenceStore.preferenceKey],
      'ffbfd8c5',
    );
    expect((await preferenceStore.readSeedColor())?.toARGB32(), 0xFFBFD8C5);
  });

  test('removes a malformed stored color and uses no preference', () async {
    secureStore.values[SecureThemePreferenceStore.preferenceKey] = 'pink';

    expect(await preferenceStore.readSeedColor(), isNull);
    expect(
      secureStore.values.containsKey(SecureThemePreferenceStore.preferenceKey),
      isFalse,
    );
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
