import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Persistence boundary for the application language preference.
///
/// Keeping this interface free of Flutter plugin types makes locale behavior
/// deterministic in tests and lets the host application inject another secure
/// implementation if its storage policy changes.
abstract interface class LocalePreferenceStore {
  Future<String?> readLanguageCode();

  Future<void> writeLanguageCode(String value);
}

/// Stores the selected language in the platform keychain/keystore boundary.
final class SecureLocalePreferenceStore implements LocalePreferenceStore {
  const SecureLocalePreferenceStore({
    FlutterSecureStorage storage = const FlutterSecureStorage(),
    String storageKey = defaultStorageKey,
  })  : _storage = storage,
        _storageKey = storageKey;

  static const String defaultStorageKey = 'app_language';

  final FlutterSecureStorage _storage;
  final String _storageKey;

  @override
  Future<String?> readLanguageCode() => _storage.read(key: _storageKey);

  @override
  Future<void> writeLanguageCode(String value) =>
      _storage.write(key: _storageKey, value: value);
}
