import 'package:flutter/widgets.dart';

/// Languages that can be selected inside the application.
enum AppLanguage {
  chinese('zh'),
  english('en');

  const AppLanguage(this.storageCode);

  /// Stable value persisted across application versions.
  final String storageCode;

  Locale get locale => Locale(storageCode);

  /// Reads both the current storage format and a few common legacy variants.
  /// Unknown or damaged values deliberately fall back to Chinese.
  static AppLanguage fromStorageCode(String? value) {
    final normalized = value?.trim().toLowerCase().replaceAll('_', '-') ?? '';
    return switch (normalized) {
      'en' || 'en-us' || 'en-gb' => AppLanguage.english,
      'zh' || 'zh-cn' || 'zh-hans' => AppLanguage.chinese,
      _ => AppLanguage.chinese,
    };
  }
}
