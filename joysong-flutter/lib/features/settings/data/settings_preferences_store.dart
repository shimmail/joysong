import 'dart:convert';

import 'package:joysong_flutter/features/auth/data/secure_token_store.dart';
import 'package:joysong_flutter/features/settings/domain/settings_preferences.dart';
import 'package:joysong_flutter/features/settings/domain/settings_services.dart';

final class SecureSettingsPreferenceStore implements SettingsPreferenceStore {
  SecureSettingsPreferenceStore({SecureKeyValueStore? storage})
      : _storage = storage ?? FlutterSecureKeyValueStore();

  static const preferenceKey = 'joysong.settings.preferences.v1';

  final SecureKeyValueStore _storage;

  @override
  Future<SettingsPreferences> read() async {
    final raw = await _storage.read(preferenceKey);
    if (raw == null || raw.isEmpty) {
      return const SettingsPreferences();
    }
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map) {
        throw const FormatException('Settings value must be an object.');
      }
      return SettingsPreferences.fromJson(decoded.cast<String, Object?>());
    } on FormatException {
      await _storage.delete(preferenceKey);
      return const SettingsPreferences();
    } on TypeError {
      await _storage.delete(preferenceKey);
      return const SettingsPreferences();
    }
  }

  @override
  Future<void> write(SettingsPreferences preferences) {
    return _storage.write(preferenceKey, jsonEncode(preferences.toJson()));
  }
}
