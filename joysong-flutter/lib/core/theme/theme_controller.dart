import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/theme/app_theme.dart';
import 'package:joysong_flutter/core/theme/theme_preferences_store.dart';

final class ThemeController extends ChangeNotifier {
  ThemeController({
    required ThemePreferenceStore preferenceStore,
    Color defaultSeedColor = AppColors.primary,
  })  : _preferenceStore = preferenceStore,
        _defaultSeedColor = defaultSeedColor,
        _seedColor = defaultSeedColor;

  final ThemePreferenceStore _preferenceStore;
  final Color _defaultSeedColor;

  Color _seedColor;
  bool _isRestoring = false;

  Color get seedColor => _seedColor;

  ThemePreset? get selectedPreset => ThemePreset.fromColor(_seedColor);

  bool get isRestoring => _isRestoring;

  ThemeData get lightTheme => AppTheme.lightFor(_seedColor);

  ThemeData get darkTheme => AppTheme.darkFor(_seedColor);

  Future<void> restore() async {
    _setRestoring(true);
    try {
      final storedColor = await _preferenceStore.readSeedColor();
      _setSeedColor(storedColor ?? _defaultSeedColor);
    } finally {
      _setRestoring(false);
    }
  }

  Future<void> setPreset(ThemePreset preset) => setSeedColor(preset.seedColor);

  /// Persists any caller-provided ARGB color, not only the built-in presets.
  Future<void> setSeedColor(Color color) async {
    await _preferenceStore.writeSeedColor(color);
    _setSeedColor(color);
  }

  Future<void> resetToDefault() async {
    await _preferenceStore.clearSeedColor();
    _setSeedColor(_defaultSeedColor);
  }

  void _setRestoring(bool value) {
    if (_isRestoring == value) {
      return;
    }
    _isRestoring = value;
    notifyListeners();
  }

  void _setSeedColor(Color color) {
    if (_seedColor.toARGB32() == color.toARGB32()) {
      return;
    }
    _seedColor = color;
    notifyListeners();
  }
}
