import 'dart:convert';

import 'package:joysong_flutter/features/auth/data/secure_token_store.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_preferences.dart';

/// Persists message-list preferences separately for every signed-in account.
final class SecureMessagingPreferencesStore
    implements MessagingPreferencesStore {
  SecureMessagingPreferencesStore({SecureKeyValueStore? storage})
      : _storage = storage ?? FlutterSecureKeyValueStore();

  static const _keyPrefix = 'joysong.messaging.preferences.v1';

  final SecureKeyValueStore _storage;

  @override
  Future<MessagingPreferences> read(String currentUserId) async {
    final key = _keyFor(currentUserId);
    final raw = await _storage.read(key);
    if (raw == null || raw.trim().isEmpty) return MessagingPreferences();
    try {
      return MessagingPreferences.fromJson(jsonDecode(raw));
    } on FormatException {
      await _storage.delete(key);
      return MessagingPreferences();
    } on TypeError {
      await _storage.delete(key);
      return MessagingPreferences();
    }
  }

  @override
  Future<void> write(
    String currentUserId,
    MessagingPreferences preferences,
  ) {
    return _storage.write(
      _keyFor(currentUserId),
      jsonEncode(preferences.toJson()),
    );
  }

  @override
  Future<void> clear(String currentUserId) =>
      _storage.delete(_keyFor(currentUserId));

  String _keyFor(String currentUserId) {
    final normalized =
        currentUserId.trim().isEmpty ? 'anonymous' : currentUserId.trim();
    final accountKey = base64Url.encode(utf8.encode(normalized));
    return '$_keyPrefix.$accountKey';
  }
}
