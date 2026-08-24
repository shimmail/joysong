import 'package:flutter/material.dart';

enum AppAppearanceMode {
  system,
  light,
  dark;

  String get label => switch (this) {
        AppAppearanceMode.system => '跟随系统',
        AppAppearanceMode.light => '浅色',
        AppAppearanceMode.dark => '深色',
      };

  ThemeMode get themeMode => switch (this) {
        AppAppearanceMode.system => ThemeMode.system,
        AppAppearanceMode.light => ThemeMode.light,
        AppAppearanceMode.dark => ThemeMode.dark,
      };

  static AppAppearanceMode fromStorage(String? value) {
    return values.where((item) => item.name == value).firstOrNull ?? system;
  }
}

final class NotificationPreferences {
  const NotificationPreferences({
    this.enabled = true,
    this.orderUpdates = true,
    this.socialActivity = true,
    this.serviceMessages = true,
    this.productNews = false,
  });

  final bool enabled;
  final bool orderUpdates;
  final bool socialActivity;
  final bool serviceMessages;
  final bool productNews;

  NotificationPreferences copyWith({
    bool? enabled,
    bool? orderUpdates,
    bool? socialActivity,
    bool? serviceMessages,
    bool? productNews,
  }) {
    return NotificationPreferences(
      enabled: enabled ?? this.enabled,
      orderUpdates: orderUpdates ?? this.orderUpdates,
      socialActivity: socialActivity ?? this.socialActivity,
      serviceMessages: serviceMessages ?? this.serviceMessages,
      productNews: productNews ?? this.productNews,
    );
  }

  Map<String, Object> toJson() => {
        'enabled': enabled,
        'orderUpdates': orderUpdates,
        'socialActivity': socialActivity,
        'serviceMessages': serviceMessages,
        'productNews': productNews,
      };

  factory NotificationPreferences.fromJson(Map<String, Object?> json) {
    bool readBool(String key, bool fallback) {
      final value = json[key];
      return value is bool ? value : fallback;
    }

    return NotificationPreferences(
      enabled: readBool('enabled', true),
      orderUpdates: readBool('orderUpdates', true),
      socialActivity: readBool('socialActivity', true),
      serviceMessages: readBool('serviceMessages', true),
      productNews: readBool('productNews', false),
    );
  }

  @override
  bool operator ==(Object other) {
    return other is NotificationPreferences &&
        enabled == other.enabled &&
        orderUpdates == other.orderUpdates &&
        socialActivity == other.socialActivity &&
        serviceMessages == other.serviceMessages &&
        productNews == other.productNews;
  }

  @override
  int get hashCode => Object.hash(
        enabled,
        orderUpdates,
        socialActivity,
        serviceMessages,
        productNews,
      );
}

final class SettingsPreferences {
  const SettingsPreferences({
    this.appearanceMode = AppAppearanceMode.system,
    this.notifications = const NotificationPreferences(),
    this.aiTranslationEnabled = false,
  });

  final AppAppearanceMode appearanceMode;
  final NotificationPreferences notifications;
  final bool aiTranslationEnabled;

  SettingsPreferences copyWith({
    AppAppearanceMode? appearanceMode,
    NotificationPreferences? notifications,
    bool? aiTranslationEnabled,
  }) {
    return SettingsPreferences(
      appearanceMode: appearanceMode ?? this.appearanceMode,
      notifications: notifications ?? this.notifications,
      aiTranslationEnabled: aiTranslationEnabled ?? this.aiTranslationEnabled,
    );
  }

  Map<String, Object> toJson() => {
        'version': 1,
        'appearanceMode': appearanceMode.name,
        'notifications': notifications.toJson(),
        'aiTranslationEnabled': aiTranslationEnabled,
      };

  factory SettingsPreferences.fromJson(Map<String, Object?> json) {
    final rawNotifications = json['notifications'];
    final rawAiTranslationEnabled = json['aiTranslationEnabled'];
    return SettingsPreferences(
      appearanceMode: AppAppearanceMode.fromStorage(
        json['appearanceMode'] as String?,
      ),
      notifications: rawNotifications is Map
          ? NotificationPreferences.fromJson(
              rawNotifications.cast<String, Object?>(),
            )
          : const NotificationPreferences(),
      aiTranslationEnabled: rawAiTranslationEnabled is bool
          ? rawAiTranslationEnabled
          : false,
    );
  }

  @override
  bool operator ==(Object other) {
    return other is SettingsPreferences &&
        appearanceMode == other.appearanceMode &&
        notifications == other.notifications &&
        aiTranslationEnabled == other.aiTranslationEnabled;
  }

  @override
  int get hashCode =>
      Object.hash(appearanceMode, notifications, aiTranslationEnabled);
}
