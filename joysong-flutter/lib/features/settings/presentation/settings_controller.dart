import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/features/settings/domain/settings_preferences.dart';
import 'package:joysong_flutter/features/settings/domain/settings_services.dart';

final class SettingsController extends ChangeNotifier {
  SettingsController({
    required SettingsPreferenceStore preferenceStore,
    required CacheMaintenance cacheMaintenance,
    NotificationPreferenceSync? notificationSync,
  })  : _preferenceStore = preferenceStore,
        _cacheMaintenance = cacheMaintenance,
        _notificationSync = notificationSync;

  final SettingsPreferenceStore _preferenceStore;
  final CacheMaintenance _cacheMaintenance;
  final NotificationPreferenceSync? _notificationSync;

  SettingsPreferences _preferences = const SettingsPreferences();
  bool _isRestoring = false;
  bool _isSaving = false;
  bool _isCalculatingCache = false;
  bool _isClearingCache = false;
  bool _hasRestored = false;
  int? _cacheSizeBytes;

  SettingsPreferences get preferences => _preferences;
  AppAppearanceMode get appearanceMode => _preferences.appearanceMode;
  NotificationPreferences get notifications => _preferences.notifications;
  bool get aiTranslationEnabled => _preferences.aiTranslationEnabled;
  bool get isRestoring => _isRestoring;
  bool get isSaving => _isSaving;
  bool get isCalculatingCache => _isCalculatingCache;
  bool get isClearingCache => _isClearingCache;
  bool get hasRestored => _hasRestored;
  int? get cacheSizeBytes => _cacheSizeBytes;

  Future<void> restore() async {
    if (_isRestoring || _hasRestored) {
      return;
    }
    _isRestoring = true;
    notifyListeners();
    try {
      _preferences = await _preferenceStore.read();
      _hasRestored = true;
    } finally {
      _isRestoring = false;
      notifyListeners();
    }
  }

  Future<void> setAppearanceMode(AppAppearanceMode mode) async {
    if (_isSaving || mode == _preferences.appearanceMode) {
      return;
    }
    await _save(_preferences.copyWith(appearanceMode: mode));
  }

  Future<void> setAiTranslationEnabled(bool value) async {
    if (_isSaving || value == _preferences.aiTranslationEnabled) {
      return;
    }
    await _save(_preferences.copyWith(aiTranslationEnabled: value));
  }

  Future<void> setNotifications(NotificationPreferences value) async {
    if (_isSaving || value == _preferences.notifications) {
      return;
    }
    final next = _preferences.copyWith(notifications: value);
    _isSaving = true;
    notifyListeners();
    try {
      await _notificationSync?.sync(value);
      await _preferenceStore.write(next);
      _preferences = next;
    } finally {
      _isSaving = false;
      notifyListeners();
    }
  }

  Future<void> refreshCacheSize() async {
    if (_isCalculatingCache || _isClearingCache) {
      return;
    }
    _isCalculatingCache = true;
    notifyListeners();
    try {
      _cacheSizeBytes = await _cacheMaintenance.calculateSizeBytes();
    } finally {
      _isCalculatingCache = false;
      notifyListeners();
    }
  }

  Future<void> clearCache() async {
    if (_isClearingCache) {
      return;
    }
    _isClearingCache = true;
    notifyListeners();
    try {
      await _cacheMaintenance.clearTemporaryFiles();
      _cacheSizeBytes = await _cacheMaintenance.calculateSizeBytes();
    } finally {
      _isClearingCache = false;
      notifyListeners();
    }
  }

  Future<void> _save(SettingsPreferences next) async {
    _isSaving = true;
    notifyListeners();
    try {
      await _preferenceStore.write(next);
      _preferences = next;
    } finally {
      _isSaving = false;
      notifyListeners();
    }
  }
}
