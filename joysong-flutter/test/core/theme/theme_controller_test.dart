import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/theme/app_theme.dart';
import 'package:joysong_flutter/core/theme/theme_controller.dart';
import 'package:joysong_flutter/core/theme/theme_preferences_store.dart';

void main() {
  test('restores a saved theme color', () async {
    final store = _MemoryThemePreferenceStore(ThemePreset.mistBlue.seedColor);
    final controller = ThemeController(preferenceStore: store);

    await controller.restore();

    expect(controller.selectedPreset, ThemePreset.mistBlue);
    expect(controller.isRestoring, isFalse);
  });

  test('persists presets and can restore the default', () async {
    final store = _MemoryThemePreferenceStore();
    final controller = ThemeController(preferenceStore: store);

    await controller.setPreset(ThemePreset.sage);
    expect(store.color?.toARGB32(), ThemePreset.sage.seedColor.toARGB32());
    expect(controller.selectedPreset, ThemePreset.sage);

    await controller.resetToDefault();
    expect(store.color, isNull);
    expect(controller.selectedPreset, ThemePreset.softRose);
  });

  test('supports a custom color outside the built-in presets', () async {
    final store = _MemoryThemePreferenceStore();
    final controller = ThemeController(preferenceStore: store);
    const customColor = Color(0xFFAADDEE);

    await controller.setSeedColor(customColor);

    expect(controller.seedColor, customColor);
    expect(controller.selectedPreset, isNull);
  });
}

final class _MemoryThemePreferenceStore implements ThemePreferenceStore {
  _MemoryThemePreferenceStore([this.color]);

  Color? color;

  @override
  Future<void> clearSeedColor() async {
    color = null;
  }

  @override
  Future<Color?> readSeedColor() async => color;

  @override
  Future<void> writeSeedColor(Color color) async {
    this.color = color;
  }
}
