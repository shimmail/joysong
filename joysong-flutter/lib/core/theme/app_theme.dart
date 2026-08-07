import 'package:flutter/material.dart';

abstract final class AppColors {
  /// Neutral gray is the default, matching the monochrome product UI.
  static const primary = Color(0xFF8A8A8A);
  static const primaryDark = Color(0xFFD99AB3);
  static const primaryLight = Color(0xFFF9E4EC);
  static const onPrimary = Color(0xFF442D36);
  static const secondary = Color(0xFFD7C092);
  static const secondaryLight = Color(0xFFF1E4C9);
  static const background = Color(0xFFFFF9FB);
  static const surface = Color(0xFFFFFCFD);
  static const surfaceVariant = Color(0xFFF6F0F2);
  static const textPrimary = Color(0xFF241D20);
  static const textSecondary = Color(0xFF6E6367);
}

enum ThemePreset {
  neutralGray('中性灰', AppColors.primary),
  softRose('柔雾粉', Color(0xFFF2C6D6)),
  peach('暖杏', Color(0xFFF2C7AE)),
  sage('薄荷绿', Color(0xFFBFD8C5)),
  mistBlue('雾霾蓝', Color(0xFFBDD2E5)),
  lavender('浅薰衣草', Color(0xFFD2C3E2));

  const ThemePreset(this.label, this.seedColor);

  final String label;
  final Color seedColor;

  static ThemePreset? fromColor(Color color) {
    for (final preset in values) {
      if (preset.seedColor.toARGB32() == color.toARGB32()) {
        return preset;
      }
    }
    return null;
  }
}

abstract final class AppTheme {
  static ThemeData get light => lightFor(AppColors.primary);

  static ThemeData get dark => darkFor(AppColors.primary);

  static ThemeData lightFor(Color seedColor) {
    final isNeutral =
        seedColor.toARGB32() == AppColors.primary.toARGB32();
    final pastelPrimary = _pastel(seedColor);
    final primary = Color.lerp(pastelPrimary, Colors.black, 0.44)!;
    final generated = ColorScheme.fromSeed(
      seedColor: pastelPrimary,
      brightness: Brightness.light,
    );
    final scheme = generated.copyWith(
      primary: primary,
      onPrimary: Colors.white,
      primaryContainer: Color.lerp(pastelPrimary, Colors.white, 0.42),
      onPrimaryContainer: AppColors.onPrimary,
      secondary: isNeutral ? const Color(0xFF666666) : AppColors.secondary,
      onSecondary: isNeutral ? Colors.white : const Color(0xFF3D3320),
      secondaryContainer:
          isNeutral ? const Color(0xFFECECEC) : AppColors.secondaryLight,
      onSecondaryContainer:
          isNeutral ? const Color(0xFF292929) : const Color(0xFF342B1C),
      surface: isNeutral ? Colors.white : AppColors.surface,
      onSurface: AppColors.textPrimary,
      onSurfaceVariant:
          isNeutral ? const Color(0xFF505050) : const Color(0xFF51484C),
      surfaceContainerLowest: Colors.white,
      surfaceContainerLow:
          isNeutral ? const Color(0xFFF5F5F5) : AppColors.surfaceVariant,
      surfaceContainer:
          isNeutral ? const Color(0xFFEEEEEE) : const Color(0xFFF2EAED),
      outline:
          isNeutral ? const Color(0xFF737373) : const Color(0xFF756A6E),
      outlineVariant:
          isNeutral ? const Color(0xFFD6D6D6) : const Color(0xFFD8CFD2),
    );
    return _base(scheme).copyWith(
      scaffoldBackgroundColor:
          isNeutral ? Colors.white : AppColors.background,
    );
  }

  static ThemeData darkFor(Color seedColor) {
    final isNeutral =
        seedColor.toARGB32() == AppColors.primary.toARGB32();
    final primary = _pastel(seedColor);
    final generated = ColorScheme.fromSeed(
      seedColor: primary,
      brightness: Brightness.dark,
    );
    final scheme = generated.copyWith(
      primary: primary,
      onPrimary: isNeutral ? Colors.black : AppColors.onPrimary,
      primaryContainer: Color.lerp(primary, Colors.black, 0.62),
      surface:
          isNeutral ? const Color(0xFF1C1C1C) : const Color(0xFF1D191B),
      onSurface:
          isNeutral ? const Color(0xFFF2F2F2) : const Color(0xFFF1E9EC),
      surfaceContainerLow:
          isNeutral ? const Color(0xFF242424) : const Color(0xFF252023),
      surfaceContainer:
          isNeutral ? const Color(0xFF2B2B2B) : const Color(0xFF2C2629),
      outline:
          isNeutral ? const Color(0xFF858585) : const Color(0xFF82767A),
      outlineVariant:
          isNeutral ? const Color(0xFF484848) : const Color(0xFF4A4144),
    );
    return _base(scheme).copyWith(
      scaffoldBackgroundColor:
          isNeutral ? const Color(0xFF181818) : const Color(0xFF181416),
    );
  }

  static Color _pastel(Color seedColor) {
    return Color.lerp(seedColor, Colors.white, 0.08)!;
  }

  static ThemeData _base(ColorScheme scheme) {
    final typography = _textTheme.apply(
      bodyColor: scheme.onSurface,
      displayColor: scheme.onSurface,
    );
    final fieldBorder = OutlineInputBorder(
      borderRadius: BorderRadius.circular(14),
      borderSide: BorderSide(color: scheme.outlineVariant, width: 1),
    );
    final focusedFieldBorder = OutlineInputBorder(
      borderRadius: BorderRadius.circular(14),
      borderSide: BorderSide(color: scheme.primary, width: 1.4),
    );
    final errorFieldBorder = OutlineInputBorder(
      borderRadius: BorderRadius.circular(14),
      borderSide: BorderSide(color: scheme.error, width: 1),
    );
    final buttonShape = RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(14),
    );

    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      textTheme: typography,
      primaryTextTheme: typography,
      splashFactory: InkSparkle.splashFactory,
      appBarTheme: AppBarTheme(
        centerTitle: false,
        elevation: 0,
        scrolledUnderElevation: 0,
        backgroundColor: Colors.transparent,
        foregroundColor: scheme.onSurface,
        surfaceTintColor: Colors.transparent,
        titleTextStyle: typography.titleLarge,
      ),
      cardTheme: CardThemeData(
        color: scheme.surface,
        surfaceTintColor: Colors.transparent,
        shadowColor: Colors.transparent,
        elevation: 0,
        margin: EdgeInsets.zero,
        clipBehavior: Clip.antiAlias,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(18),
          side: BorderSide.none,
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: false,
        floatingLabelBehavior: FloatingLabelBehavior.auto,
        floatingLabelAlignment: FloatingLabelAlignment.start,
        labelStyle: typography.bodyMedium?.copyWith(
          color: scheme.outline,
        ),
        hintStyle: typography.bodyMedium?.copyWith(
          color: scheme.onSurfaceVariant,
        ),
        floatingLabelStyle: WidgetStateTextStyle.resolveWith(
          (states) => TextStyle(
            color: states.contains(WidgetState.focused)
                ? scheme.primary
                : scheme.outline,
          ),
        ),
        contentPadding: const EdgeInsets.fromLTRB(16, 19, 16, 13),
        border: fieldBorder,
        enabledBorder: fieldBorder,
        focusedBorder: focusedFieldBorder,
        disabledBorder: fieldBorder.copyWith(
          borderSide: BorderSide(
            color: scheme.outlineVariant.withValues(alpha: 0.55),
          ),
        ),
        errorBorder: errorFieldBorder,
        focusedErrorBorder: errorFieldBorder.copyWith(
          borderSide: BorderSide(color: scheme.error, width: 1.4),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: scheme.primary,
          foregroundColor: scheme.onPrimary,
          disabledBackgroundColor: scheme.surfaceContainer,
          disabledForegroundColor: scheme.onSurface.withValues(alpha: 0.56),
          elevation: 0,
          minimumSize: const Size(64, 48),
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 13),
          shape: buttonShape,
          textStyle: typography.labelLarge,
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: scheme.onSurface,
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          shape: buttonShape,
          textStyle: typography.labelLarge,
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        height: 66,
        elevation: 0,
        backgroundColor: scheme.surface,
        surfaceTintColor: Colors.transparent,
        indicatorColor: scheme.primaryContainer.withValues(alpha: 0.82),
        indicatorShape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
        ),
        labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
        labelTextStyle: WidgetStatePropertyAll(typography.labelMedium),
        iconTheme: WidgetStateProperty.resolveWith((states) {
          return IconThemeData(
            size: 23,
            color: states.contains(WidgetState.selected)
                ? scheme.onPrimaryContainer
                : scheme.onSurfaceVariant,
          );
        }),
      ),
      dividerTheme: DividerThemeData(
        color: scheme.outlineVariant.withValues(alpha: 0.72),
        thickness: 0.6,
        space: 1,
      ),
    );
  }

  static const _textTheme = TextTheme(
    displayLarge: TextStyle(
      fontSize: 32,
      height: 1.25,
      fontWeight: FontWeight.w700,
      letterSpacing: -0.3,
    ),
    headlineLarge: TextStyle(
      fontSize: 24,
      height: 1.34,
      fontWeight: FontWeight.w700,
    ),
    headlineMedium: TextStyle(
      fontSize: 20,
      height: 1.4,
      fontWeight: FontWeight.w600,
    ),
    titleLarge: TextStyle(
      fontSize: 18,
      height: 1.45,
      fontWeight: FontWeight.w600,
    ),
    titleMedium: TextStyle(
      fontSize: 16,
      height: 1.5,
      fontWeight: FontWeight.w500,
    ),
    titleSmall: TextStyle(
      fontSize: 14,
      height: 1.45,
      fontWeight: FontWeight.w500,
    ),
    bodyLarge: TextStyle(
      fontSize: 16,
      height: 1.5,
      fontWeight: FontWeight.w400,
    ),
    bodyMedium: TextStyle(
      fontSize: 14,
      height: 1.45,
      fontWeight: FontWeight.w400,
    ),
    bodySmall: TextStyle(
      fontSize: 12,
      height: 1.4,
      fontWeight: FontWeight.w400,
    ),
    labelLarge: TextStyle(
      fontSize: 14,
      height: 1.4,
      fontWeight: FontWeight.w500,
    ),
    labelMedium: TextStyle(
      fontSize: 12,
      height: 1.35,
      fontWeight: FontWeight.w500,
    ),
  );
}
