import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/localization/app_language.dart';
import 'package:joysong_flutter/core/localization/locale_preference_store.dart';

/// Owns the application locale and persists explicit user selections.
final class AppLocaleController extends ChangeNotifier {
  AppLocaleController({
    required LocalePreferenceStore preferenceStore,
    AppLanguage initialLanguage = AppLanguage.chinese,
  })  : _preferenceStore = preferenceStore,
        _language = initialLanguage;

  final LocalePreferenceStore _preferenceStore;

  AppLanguage _language;
  bool _isRestoring = false;
  Future<void> _operationTail = Future<void>.value();

  AppLanguage get language => _language;

  bool get isRestoring => _isRestoring;

  /// Restores the preference. Missing or damaged values resolve to Chinese.
  ///
  /// A storage I/O failure is still reported to the caller while the in-memory
  /// language remains usable.
  Future<void> restore() {
    return _enqueue(() async {
      _setRestoring(true);
      try {
        final storedCode = await _preferenceStore.readLanguageCode();
        _setLanguageInMemory(AppLanguage.fromStorageCode(storedCode));
      } finally {
        _setRestoring(false);
      }
    });
  }

  /// Persists [language] before exposing it to the widget tree.
  ///
  /// If secure storage rejects the write, this future completes with that
  /// error and the visible language is left unchanged.
  Future<void> setLanguage(AppLanguage language) {
    return _enqueue(() async {
      if (_language == language) {
        return;
      }
      await _preferenceStore.writeLanguageCode(language.storageCode);
      _setLanguageInMemory(language);
    });
  }

  Future<void> _enqueue(Future<void> Function() operation) {
    final scheduled = _operationTail.then((_) => operation());
    _operationTail = scheduled.then<void>(
      (_) {},
      onError: (Object _, StackTrace __) {},
    );
    return scheduled;
  }

  void _setRestoring(bool value) {
    if (_isRestoring == value) {
      return;
    }
    _isRestoring = value;
    notifyListeners();
  }

  void _setLanguageInMemory(AppLanguage value) {
    if (_language == value) {
      return;
    }
    _language = value;
    notifyListeners();
  }
}
