import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/theme/app_theme.dart';

void main() {
  test('default palette is softer and keeps primary content readable', () {
    final theme = AppTheme.light;
    final scheme = theme.colorScheme;

    expect(AppColors.primary.toARGB32(), isNot(0xFFE8A0BF));
    expect(scheme.onPrimary, AppColors.onPrimary);
    expect(_contrastRatio(scheme.primary, scheme.onPrimary), greaterThan(4.5));
  });

  test('component defaults are light and borderless', () {
    final theme = AppTheme.light;
    final cardShape = theme.cardTheme.shape! as RoundedRectangleBorder;
    final inputBorder = theme.inputDecorationTheme.enabledBorder!;

    expect(theme.cardTheme.elevation, lessThanOrEqualTo(1));
    expect(cardShape.side.style, BorderStyle.none);
    expect(inputBorder, isA<OutlineInputBorder>());
    expect(inputBorder.borderSide.style, BorderStyle.none);
    expect(theme.inputDecorationTheme.filled, isTrue);
    expect(theme.navigationBarTheme.elevation, 0);
  });

  test('typography follows the Compose size and weight baseline', () {
    final textTheme = AppTheme.light.textTheme;

    expect(textTheme.displayLarge?.fontSize, 32);
    expect(textTheme.displayLarge?.fontWeight, FontWeight.w700);
    expect(textTheme.titleLarge?.fontSize, 18);
    expect(textTheme.titleLarge?.fontWeight, FontWeight.w600);
    expect(textTheme.bodyMedium?.fontSize, 14);
    expect(textTheme.bodyMedium?.fontWeight, FontWeight.w400);
  });
}

double _contrastRatio(Color first, Color second) {
  final firstLuminance = first.computeLuminance();
  final secondLuminance = second.computeLuminance();
  final lighter =
      firstLuminance > secondLuminance ? firstLuminance : secondLuminance;
  final darker =
      firstLuminance > secondLuminance ? secondLuminance : firstLuminance;
  return (lighter + 0.05) / (darker + 0.05);
}
