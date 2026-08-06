import 'package:flutter/material.dart';
import 'package:joysong_flutter/features/auth/data/secure_token_store.dart';

abstract interface class ThemePreferenceStore {
  Future<Color?> readSeedColor();

  Future<void> writeSeedColor(Color color);

  Future<void> clearSeedColor();
}

final class SecureThemePreferenceStore implements ThemePreferenceStore {
  SecureThemePreferenceStore({SecureKeyValueStore? storage})
      : _storage = storage ?? FlutterSecureKeyValueStore();

  static const preferenceKey = 'joysong.theme.seed_color.v1';

  final SecureKeyValueStore _storage;

  @override
  Future<Color?> readSeedColor() async {
    final rawValue = await _storage.read(preferenceKey);
    if (rawValue == null || rawValue.isEmpty) {
      return null;
    }

    final value =
        rawValue.length == 8 ? int.tryParse(rawValue, radix: 16) : null;
    if (value == null) {
      await clearSeedColor();
      return null;
    }
    return Color(value);
  }

  @override
  Future<void> writeSeedColor(Color color) {
    final value = color.toARGB32().toRadixString(16).padLeft(8, '0');
    return _storage.write(preferenceKey, value);
  }

  @override
  Future<void> clearSeedColor() => _storage.delete(preferenceKey);
}
