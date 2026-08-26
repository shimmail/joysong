import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/theme/app_theme.dart';
import 'package:joysong_flutter/core/theme/theme_controller.dart';
import 'package:joysong_flutter/core/transient_message.dart';
import 'package:joysong_flutter/features/settings/data/settings_preferences_store.dart';
import 'package:joysong_flutter/features/settings/domain/settings_preferences.dart';
import 'package:joysong_flutter/features/settings/domain/settings_services.dart';
import 'package:joysong_flutter/features/settings/presentation/settings_controller.dart';
import 'package:joysong_flutter/features/settings/presentation/settings_strings.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({
    required this.themeController,
    this.settingsController,
    this.onAppearanceModeChanged,
    this.onAccountSecurity,
    this.onPrivacySettings,
    this.onPrivacyPolicy,
    this.onTermsOfService,
    this.onAbout,
    this.appVersion = '0.1.0',
    super.key,
  });

  final ThemeController themeController;
  final SettingsController? settingsController;

  /// The application root should use this callback to update MaterialApp's
  /// ThemeMode immediately. The selected value is persisted by this page.
  final ValueChanged<AppAppearanceMode>? onAppearanceModeChanged;
  final VoidCallback? onAccountSecurity;
  final VoidCallback? onPrivacySettings;
  final VoidCallback? onPrivacyPolicy;
  final VoidCallback? onTermsOfService;
  final VoidCallback? onAbout;
  final String appVersion;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  late final SettingsController _settingsController;
  late final bool _ownsSettingsController;

  SettingsStrings get _strings {
    final language = AppLocaleScope.maybeOf(context)?.language;
    return language == AppLanguage.english
        ? const SettingsStrings.english()
        : const SettingsStrings.chinese();
  }

  @override
  void initState() {
    super.initState();
    _ownsSettingsController = widget.settingsController == null;
    _settingsController = widget.settingsController ??
        SettingsController(
          preferenceStore: SecureSettingsPreferenceStore(),
          cacheMaintenance: const FlutterImageCacheMaintenance(),
        );
    unawaited(_restoreSettings());
    unawaited(_refreshCacheSize());
  }

  @override
  void dispose() {
    if (_ownsSettingsController) {
      _settingsController.dispose();
    }
    super.dispose();
  }

  Future<void> _restoreSettings() async {
    try {
      await _settingsController.restore();
    } on Object {
      _showMessage(_strings.restoreFailed);
    }
  }

  Future<void> _refreshCacheSize() async {
    try {
      await _settingsController.refreshCacheSize();
    } on Object {
      // Cache size is supplementary information. Clearing remains available
      // and will report its own result.
    }
  }

  void _showMessage(String message) {
    if (!mounted) {
      return;
    }
    showTransientMessage(context, message);
  }

  void _open(VoidCallback? callback, String unavailableMessage) {
    if (callback == null) {
      _showMessage(unavailableMessage);
      return;
    }
    callback();
  }

  Future<void> _confirmClearCache() async {
    final strings = _strings;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(strings.cacheDialogTitle),
        content: Text(strings.cacheDialogBody),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(strings.cancel),
          ),
          FilledButton(
            key: const Key('confirm-clear-cache'),
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(strings.confirmClear),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }
    try {
      await _settingsController.clearCache();
      _showMessage(_strings.cacheCleared);
    } on Object {
      _showMessage(_strings.cacheClearFailed);
    }
  }

  Future<void> _showLanguageDialog() async {
    final localeController = AppLocaleScope.maybeOf(context);
    if (localeController == null) {
      _showMessage(_strings.languageUnavailable);
      return;
    }
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => AnimatedBuilder(
        animation: localeController,
        builder: (context, _) {
          final strings = localeController.language == AppLanguage.english
              ? const SettingsStrings.english()
              : const SettingsStrings.chinese();
          return AlertDialog(
            title: Text(strings.languageDialogTitle),
            contentPadding: const EdgeInsets.fromLTRB(8, 12, 8, 8),
            content: RadioGroup<AppLanguage>(
              groupValue: localeController.language,
              onChanged: (language) => _selectLanguage(
                dialogContext,
                localeController,
                language,
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  RadioListTile<AppLanguage>(
                    key: const Key('language-chinese-option'),
                    title: Text(strings.chinese),
                    value: AppLanguage.chinese,
                  ),
                  RadioListTile<AppLanguage>(
                    key: const Key('language-english-option'),
                    title: Text(strings.english),
                    value: AppLanguage.english,
                  ),
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(),
                child: Text(strings.cancel),
              ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _openAppPreferences() => Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => _AppPreferencesPage(
            themeController: widget.themeController,
            settingsController: _settingsController,
            onAppearanceModeChanged: widget.onAppearanceModeChanged,
          ),
        ),
      );

  Future<void> _selectLanguage(
    BuildContext dialogContext,
    AppLocaleController controller,
    AppLanguage? language,
  ) async {
    if (language == null) {
      return;
    }
    try {
      await controller.setLanguage(language);
      if (dialogContext.mounted) {
        Navigator.of(dialogContext).pop();
      }
    } on Object {
      _showMessage(_strings.languageSaveFailed);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: Listenable.merge([
        widget.themeController,
        _settingsController,
      ]),
      builder: (context, _) {
        final settings = _settingsController;
        final strings = _strings;
        return Scaffold(
          appBar: AppBar(title: Text(strings.settings)),
          body: ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
            children: [
              if (settings.isRestoring) const LinearProgressIndicator(),
              _SectionTitle(strings.generalSection),
              _SettingsCard(
                children: [
                  _SettingsTile(
                    key: const Key('language-entry'),
                    icon: Icons.language_rounded,
                    title: strings.language,
                    trailing: AppLocaleScope.maybeOf(context)?.language ==
                            AppLanguage.english
                        ? strings.english
                        : strings.chinese,
                    onTap: _showLanguageDialog,
                  ),
                  if (AppLocaleScope.maybeOf(context)?.language ==
                      AppLanguage.english)
                    SwitchListTile(
                      key: const Key('ai-translation-switch'),
                      secondary: const Icon(Icons.translate_rounded),
                      title: Text(strings.aiTranslation),
                      subtitle: Text(strings.aiTranslationSubtitle),
                      value: settings.aiTranslationEnabled,
                      onChanged: settings.isSaving
                          ? null
                          : (value) async {
                              try {
                                await settings.setAiTranslationEnabled(value);
                              } on Object {
                                _showMessage(_strings.aiTranslationSaveFailed);
                              }
                            },
                    ),
                  _SettingsTile(
                    key: const Key('app-preferences-entry'),
                    icon: Icons.tune_rounded,
                    title: strings.appPreferences,
                    subtitle: strings.appPreferencesSubtitle,
                    onTap: _openAppPreferences,
                  ),
                ],
              ),
              const SizedBox(height: 18),
              _SectionTitle(strings.accountSection),
              _SettingsCard(
                children: [
                  _SettingsTile(
                    key: const Key('account-security-entry'),
                    icon: Icons.shield_outlined,
                    title: strings.accountSecurity,
                    subtitle: strings.accountSecuritySubtitle,
                    onTap: () => _open(
                      widget.onAccountSecurity,
                      strings.accountSecurityUnavailable,
                    ),
                  ),
                  _SettingsTile(
                    key: const Key('privacy-settings-entry'),
                    icon: Icons.privacy_tip_outlined,
                    title: strings.privacySettings,
                    subtitle: strings.privacySettingsSubtitle,
                    onTap: () => _open(
                      widget.onPrivacySettings,
                      strings.privacySettingsUnavailable,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 18),
              _SectionTitle(strings.appSection),
              _SettingsCard(
                children: [
                  _SettingsTile(
                    key: const Key('clear-cache-entry'),
                    icon: Icons.cleaning_services_outlined,
                    title: strings.clearCache,
                    subtitle: settings.isCalculatingCache
                        ? strings.calculating
                        : _formatBytes(settings.cacheSizeBytes, strings),
                    loading: settings.isClearingCache,
                    onTap: settings.isClearingCache ? null : _confirmClearCache,
                  ),
                  _SettingsTile(
                    key: const Key('privacy-policy-entry'),
                    icon: Icons.policy_outlined,
                    title: strings.privacyPolicy,
                    onTap: () => _open(
                      widget.onPrivacyPolicy,
                      strings.privacyPolicyUnavailable,
                    ),
                  ),
                  _SettingsTile(
                    key: const Key('terms-entry'),
                    icon: Icons.description_outlined,
                    title: strings.terms,
                    onTap: () => _open(
                      widget.onTermsOfService,
                      strings.termsUnavailable,
                    ),
                  ),
                  _SettingsTile(
                    key: const Key('about-entry'),
                    icon: Icons.info_outline_rounded,
                    title: strings.about,
                    trailing: 'v${widget.appVersion}',
                    onTap: () => _open(
                      widget.onAbout,
                      strings.aboutUnavailable,
                    ),
                  ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }

  static String _formatBytes(int? bytes, SettingsStrings strings) {
    if (bytes == null) {
      return strings.sizeUnavailable;
    }
    if (bytes < 1024) {
      return '$bytes B';
    }
    final kilobytes = bytes / 1024;
    if (kilobytes < 1024) {
      return '${kilobytes.toStringAsFixed(kilobytes >= 10 ? 0 : 1)} KB';
    }
    final megabytes = kilobytes / 1024;
    return '${megabytes.toStringAsFixed(megabytes >= 10 ? 0 : 1)} MB';
  }
}

class _AppPreferencesPage extends StatefulWidget {
  const _AppPreferencesPage({
    required this.themeController,
    required this.settingsController,
    this.onAppearanceModeChanged,
  });

  final ThemeController themeController;
  final SettingsController settingsController;
  final ValueChanged<AppAppearanceMode>? onAppearanceModeChanged;

  @override
  State<_AppPreferencesPage> createState() => _AppPreferencesPageState();
}

class _AppPreferencesPageState extends State<_AppPreferencesPage> {
  ThemePreset? _pendingPreset;
  bool _isResettingTheme = false;

  SettingsStrings get _strings =>
      AppLocaleScope.maybeOf(context)?.language == AppLanguage.english
          ? const SettingsStrings.english()
          : const SettingsStrings.chinese();

  void _showMessage(String message) {
    if (!mounted) return;
    showTransientMessage(context, message);
  }

  Future<void> _selectAppearanceMode(AppAppearanceMode mode) async {
    try {
      await widget.settingsController.setAppearanceMode(mode);
      widget.onAppearanceModeChanged?.call(mode);
    } on Object {
      _showMessage(_strings.appearanceSaveFailed);
    }
  }

  Future<void> _updateNotifications(NotificationPreferences value) async {
    try {
      await widget.settingsController.setNotifications(value);
    } on Object {
      _showMessage(_strings.notificationSaveFailed);
    }
  }

  Future<void> _selectPreset(ThemePreset preset) async {
    if (_pendingPreset != null || _isResettingTheme) return;
    setState(() => _pendingPreset = preset);
    try {
      await widget.themeController.setPreset(preset);
    } on Object {
      _showMessage(_strings.themeSaveFailed);
    } finally {
      if (mounted) setState(() => _pendingPreset = null);
    }
  }

  Future<void> _resetTheme() async {
    if (_pendingPreset != null || _isResettingTheme) return;
    setState(() => _isResettingTheme = true);
    try {
      await widget.themeController.resetToDefault();
    } on Object {
      _showMessage(_strings.themeResetFailed);
    } finally {
      if (mounted) setState(() => _isResettingTheme = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: Listenable.merge([
        widget.themeController,
        widget.settingsController,
      ]),
      builder: (context, _) {
        final strings = _strings;
        final settings = widget.settingsController;
        final theme = Theme.of(context);
        return Scaffold(
          appBar: AppBar(title: Text(strings.appPreferences)),
          body: ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
            children: [
              _SectionTitle(strings.notificationsSection),
              _NotificationCard(
                preferences: settings.notifications,
                enabled: !settings.isSaving,
                onChanged: _updateNotifications,
                strings: strings,
              ),
              const SizedBox(height: 6),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: Text(
                  strings.notificationFootnote,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ),
              const SizedBox(height: 18),
              _SectionTitle(strings.appearanceSection),
              Card(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 16, 16, 12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(strings.displayMode,
                          style: theme.textTheme.titleSmall),
                      const SizedBox(height: 4),
                      Text(strings.displayModeSubtitle,
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          )),
                      const SizedBox(height: 12),
                      SizedBox(
                        width: double.infinity,
                        child: SegmentedButton<AppAppearanceMode>(
                          key: const Key('appearance-mode-selector'),
                          segments: [
                            for (final mode in AppAppearanceMode.values)
                              ButtonSegment(
                                value: mode,
                                label: Text(strings.appearanceModeLabel(mode)),
                              ),
                          ],
                          selected: {settings.appearanceMode},
                          onSelectionChanged: settings.isSaving
                              ? null
                              : (value) => _selectAppearanceMode(value.single),
                          showSelectedIcon: false,
                        ),
                      ),
                      const SizedBox(height: 20),
                      Text(strings.themeColor,
                          style: theme.textTheme.titleSmall),
                      const SizedBox(height: 4),
                      Text(strings.themeColorSubtitle,
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          )),
                      const SizedBox(height: 14),
                      Wrap(
                        spacing: 10,
                        runSpacing: 10,
                        children: [
                          for (final preset in ThemePreset.values)
                            _ThemeChoice(
                              preset: preset,
                              selected: widget.themeController.selectedPreset ==
                                  preset,
                              waiting: _pendingPreset == preset,
                              strings: strings,
                              onSelected: () => _selectPreset(preset),
                            ),
                        ],
                      ),
                      const SizedBox(height: 18),
                      _ThemePreview(
                        seedColor: widget.themeController.seedColor,
                        appearanceMode: settings.appearanceMode,
                        strings: strings,
                      ),
                      Align(
                        alignment: Alignment.centerLeft,
                        child: TextButton.icon(
                          onPressed: _isResettingTheme ? null : _resetTheme,
                          icon: _isResettingTheme
                              ? const SizedBox.square(
                                  dimension: 18,
                                  child:
                                      CircularProgressIndicator(strokeWidth: 2),
                                )
                              : const Icon(Icons.refresh_rounded),
                          label: Text(strings.restoreDefaultTheme),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.title);

  final String title;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 4, 4, 8),
      child: Text(
        title,
        style: Theme.of(context).textTheme.labelLarge?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
      ),
    );
  }
}

class _SettingsCard extends StatelessWidget {
  const _SettingsCard({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final dividerColor = Theme.of(context).colorScheme.surfaceContainerHighest;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          for (var index = 0; index < children.length; index++) ...[
            children[index],
            if (index < children.length - 1)
              Divider(height: 1, indent: 56, color: dividerColor),
          ],
        ],
      ),
    );
  }
}

class _SettingsTile extends StatelessWidget {
  const _SettingsTile({
    required this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
    this.loading = false,
    this.onTap,
    super.key,
  });

  final IconData icon;
  final String title;
  final String? subtitle;
  final String? trailing;
  final bool loading;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
        child: Row(
          children: [
            Icon(icon, size: 22, color: colors.primary),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: Theme.of(context).textTheme.titleSmall),
                  if (subtitle != null) ...[
                    const SizedBox(height: 2),
                    Text(
                      subtitle!,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: colors.onSurfaceVariant,
                          ),
                    ),
                  ],
                ],
              ),
            ),
            if (loading)
              const SizedBox.square(
                dimension: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            else ...[
              if (trailing != null)
                Padding(
                  padding: const EdgeInsets.only(right: 6),
                  child: Text(
                    trailing!,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: colors.onSurfaceVariant,
                        ),
                  ),
                ),
              Icon(
                Icons.chevron_right_rounded,
                size: 20,
                color: colors.onSurfaceVariant,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _NotificationCard extends StatelessWidget {
  const _NotificationCard({
    required this.preferences,
    required this.enabled,
    required this.onChanged,
    required this.strings,
  });

  final NotificationPreferences preferences;
  final bool enabled;
  final ValueChanged<NotificationPreferences> onChanged;
  final SettingsStrings strings;

  @override
  Widget build(BuildContext context) {
    final childEnabled = enabled && preferences.enabled;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          SwitchListTile.adaptive(
            key: const Key('notifications-master-switch'),
            title: Text(strings.receiveNotifications),
            subtitle: Text(strings.receiveNotificationsSubtitle),
            value: preferences.enabled,
            onChanged: enabled
                ? (value) => onChanged(preferences.copyWith(enabled: value))
                : null,
          ),
          const Divider(height: 1, indent: 16),
          SwitchListTile.adaptive(
            key: const Key('notifications-order-switch'),
            title: Text(strings.orderNotifications),
            subtitle: Text(strings.orderNotificationsSubtitle),
            value: preferences.orderUpdates,
            onChanged: childEnabled
                ? (value) =>
                    onChanged(preferences.copyWith(orderUpdates: value))
                : null,
          ),
          SwitchListTile.adaptive(
            key: const Key('notifications-social-switch'),
            title: Text(strings.socialNotifications),
            subtitle: Text(strings.socialNotificationsSubtitle),
            value: preferences.socialActivity,
            onChanged: childEnabled
                ? (value) =>
                    onChanged(preferences.copyWith(socialActivity: value))
                : null,
          ),
          SwitchListTile.adaptive(
            key: const Key('notifications-service-switch'),
            title: Text(strings.serviceNotifications),
            subtitle: Text(strings.serviceNotificationsSubtitle),
            value: preferences.serviceMessages,
            onChanged: childEnabled
                ? (value) =>
                    onChanged(preferences.copyWith(serviceMessages: value))
                : null,
          ),
          SwitchListTile.adaptive(
            key: const Key('notifications-product-switch'),
            title: Text(strings.productNotifications),
            subtitle: Text(strings.productNotificationsSubtitle),
            value: preferences.productNews,
            onChanged: childEnabled
                ? (value) => onChanged(preferences.copyWith(productNews: value))
                : null,
          ),
        ],
      ),
    );
  }
}

class _ThemeChoice extends StatelessWidget {
  const _ThemeChoice({
    required this.preset,
    required this.selected,
    required this.waiting,
    required this.onSelected,
    required this.strings,
  });

  final ThemePreset preset;
  final bool selected;
  final bool waiting;
  final VoidCallback onSelected;
  final SettingsStrings strings;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      selected: selected,
      button: true,
      label: strings.themeColorSemantics(preset),
      child: ChoiceChip(
        selected: selected,
        onSelected: waiting ? null : (_) => onSelected(),
        showCheckmark: false,
        side: BorderSide.none,
        avatar: waiting
            ? const SizedBox.square(
                dimension: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : DecoratedBox(
                decoration: BoxDecoration(
                  color: preset.seedColor,
                  shape: BoxShape.circle,
                ),
                child: const SizedBox.square(dimension: 18),
              ),
        label: Text(strings.themePresetLabel(preset)),
      ),
    );
  }
}

class _ThemePreview extends StatelessWidget {
  const _ThemePreview({
    required this.seedColor,
    required this.appearanceMode,
    required this.strings,
  });

  final Color seedColor;
  final AppAppearanceMode appearanceMode;
  final SettingsStrings strings;

  @override
  Widget build(BuildContext context) {
    final platformBrightness = MediaQuery.platformBrightnessOf(context);
    final usesDarkTheme = appearanceMode == AppAppearanceMode.dark ||
        (appearanceMode == AppAppearanceMode.system &&
            platformBrightness == Brightness.dark);
    final previewTheme = usesDarkTheme
        ? AppTheme.darkFor(seedColor)
        : AppTheme.lightFor(seedColor);
    return Theme(
      data: previewTheme,
      child: Builder(
        builder: (context) => Card(
          color: Theme.of(context).colorScheme.surfaceContainerLow,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        strings.themePreview,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      const SizedBox(height: 3),
                      Text(
                        usesDarkTheme
                            ? strings.darkPreview
                            : strings.lightPreview,
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
                FilledButton(
                  onPressed: () {},
                  child: Text(strings.previewButton),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
