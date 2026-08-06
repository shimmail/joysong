import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/theme/app_theme.dart';
import 'package:joysong_flutter/core/theme/theme_controller.dart';
import 'package:joysong_flutter/core/theme/theme_preferences_store.dart';
import 'package:joysong_flutter/features/settings/domain/settings_preferences.dart';
import 'package:joysong_flutter/features/settings/domain/settings_services.dart';
import 'package:joysong_flutter/features/settings/presentation/settings_controller.dart';
import 'package:joysong_flutter/features/settings/presentation/settings_page.dart';

void main() {
  testWidgets('shows native-parity setting groups and opens callback entries',
      (tester) async {
    var accountSecurityCalls = 0;
    var privacyPolicyCalls = 0;
    var termsCalls = 0;
    var aboutCalls = 0;
    final fixture = _Fixture();
    await _pumpPage(
      tester,
      fixture,
      onAccountSecurity: () => accountSecurityCalls += 1,
      onPrivacyPolicy: () => privacyPolicyCalls += 1,
      onTermsOfService: () => termsCalls += 1,
      onAbout: () => aboutCalls += 1,
    );

    expect(find.text('设置'), findsOneWidget);
    expect(find.text('账号与安全'), findsOneWidget);
    expect(find.text('消息通知'), findsOneWidget);
    expect(find.text('清理缓存'), findsOneWidget);

    await tester.tap(find.byKey(const Key('account-security-entry')));
    await _tapAfterScroll(tester, const Key('privacy-policy-entry'));
    await _tapAfterScroll(tester, const Key('terms-entry'));
    await _tapAfterScroll(tester, const Key('about-entry'));

    expect(accountSecurityCalls, 1);
    expect(privacyPolicyCalls, 1);
    expect(termsCalls, 1);
    expect(aboutCalls, 1);
  });

  testWidgets('persists appearance and theme color selections', (tester) async {
    final fixture = _Fixture();
    AppAppearanceMode? selectedMode;
    await _pumpPage(
      tester,
      fixture,
      onAppearanceModeChanged: (mode) => selectedMode = mode,
    );

    await tester.tap(find.text('深色'));
    await tester.pumpAndSettle();

    expect(fixture.settingsStore.value.appearanceMode, AppAppearanceMode.dark);
    expect(selectedMode, AppAppearanceMode.dark);
    expect(find.text('深色界面预览'), findsOneWidget);

    await tester.tap(find.text('薄荷绿'));
    await tester.pumpAndSettle();

    expect(fixture.themeController.selectedPreset, ThemePreset.sage);
    expect(
      fixture.themeStore.color?.toARGB32(),
      ThemePreset.sage.seedColor.toARGB32(),
    );

    await tester.tap(find.text('恢复默认主题色'));
    await tester.pumpAndSettle();

    expect(fixture.themeController.selectedPreset, ThemePreset.softRose);
    expect(fixture.themeStore.color, isNull);
  });

  testWidgets('notification switches persist individual preferences',
      (tester) async {
    final fixture = _Fixture();
    await _pumpPage(tester, fixture);

    await _tapAfterScroll(tester, const Key('notifications-product-switch'));
    expect(fixture.settingsStore.value.notifications.productNews, isTrue);

    await _tapAfterScroll(tester, const Key('notifications-master-switch'));
    expect(fixture.settingsStore.value.notifications.enabled, isFalse);

    final orderSwitch = tester.widget<SwitchListTile>(
      find.byKey(const Key('notifications-order-switch')),
    );
    expect(orderSwitch.onChanged, isNull);
  });

  testWidgets('language selection immediately switches all settings copy',
      (tester) async {
    final fixture = _Fixture();
    await _pumpPage(tester, fixture);

    await _tapAfterScroll(tester, const Key('language-entry'));
    expect(find.text('选择语言'), findsOneWidget);
    await tester.tap(find.byKey(const Key('language-english-option')));
    await tester.pumpAndSettle();

    expect(fixture.localeController.language, AppLanguage.english);
    expect(fixture.localeStore.code, 'en');
    expect(find.text('Settings'), findsOneWidget);
    expect(find.text('Account & security'), findsOneWidget);
    expect(find.text('Appearance'), findsOneWidget);
    expect(find.text('Theme color'), findsOneWidget);
    expect(find.text('Notifications'), findsOneWidget);
    expect(find.text('Clear cache'), findsOneWidget);
    expect(find.text('Privacy Policy'), findsOneWidget);
    expect(find.text('Terms of Service'), findsOneWidget);
    expect(find.text('About Joysong'), findsOneWidget);
    expect(find.text('设置'), findsNothing);

    await _tapAfterScroll(tester, const Key('language-entry'));
    expect(find.text('Choose language'), findsOneWidget);
    await tester.tap(find.byKey(const Key('language-chinese-option')));
    await tester.pumpAndSettle();

    expect(fixture.localeController.language, AppLanguage.chinese);
    expect(fixture.localeStore.code, 'zh');
    expect(find.text('设置'), findsOneWidget);
    expect(find.text('账号与安全'), findsOneWidget);
    expect(find.text('Settings'), findsNothing);
  });

  testWidgets('cache clear requires confirmation and reports actual success',
      (tester) async {
    final fixture = _Fixture(cacheSizeBytes: 1536);
    await _pumpPage(tester, fixture);
    await _scrollTo(tester, const Key('clear-cache-entry'));

    expect(find.text('1.5 KB'), findsOneWidget);
    await tester.tap(find.byKey(const Key('clear-cache-entry')));
    await tester.pumpAndSettle();
    expect(find.textContaining('不会删除账号'), findsOneWidget);
    expect(fixture.cache.clearCalls, 0);

    await tester.tap(find.byKey(const Key('confirm-clear-cache')));
    await tester.pumpAndSettle();

    expect(fixture.cache.clearCalls, 1);
    expect(find.text('临时缓存已清理'), findsOneWidget);
    expect(find.text('0 B'), findsOneWidget);
  });

  testWidgets('cache failure is not presented as success', (tester) async {
    final fixture = _Fixture(cacheSizeBytes: 4096, failCacheClear: true);
    await _pumpPage(tester, fixture);

    await _tapAfterScroll(tester, const Key('clear-cache-entry'));
    await tester.tap(find.byKey(const Key('confirm-clear-cache')));
    await tester.pumpAndSettle();

    expect(find.text('缓存清理失败，请稍后重试'), findsOneWidget);
    expect(find.text('临时缓存已清理'), findsNothing);
    expect(find.text('4.0 KB'), findsOneWidget);
  });
}

Future<void> _pumpPage(
  WidgetTester tester,
  _Fixture fixture, {
  ValueChanged<AppAppearanceMode>? onAppearanceModeChanged,
  VoidCallback? onAccountSecurity,
  VoidCallback? onPrivacyPolicy,
  VoidCallback? onTermsOfService,
  VoidCallback? onAbout,
}) async {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    AppLocaleScope(
      controller: fixture.localeController,
      child: MaterialApp(
        locale: fixture.localeController.language.locale,
        theme: fixture.themeController.lightTheme,
        home: SettingsPage(
          themeController: fixture.themeController,
          settingsController: fixture.settingsController,
          onAppearanceModeChanged: onAppearanceModeChanged,
          onAccountSecurity: onAccountSecurity,
          onPrivacyPolicy: onPrivacyPolicy,
          onTermsOfService: onTermsOfService,
          onAbout: onAbout,
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> _scrollTo(WidgetTester tester, Key key) async {
  await tester.scrollUntilVisible(
    find.byKey(key),
    300,
    scrollable: find.byType(Scrollable).first,
  );
  await tester.pumpAndSettle();
}

Future<void> _tapAfterScroll(WidgetTester tester, Key key) async {
  await _scrollTo(tester, key);
  await tester.tap(find.byKey(key));
  await tester.pumpAndSettle();
}

final class _Fixture {
  _Fixture({
    int cacheSizeBytes = 0,
    bool failCacheClear = false,
  }) {
    cache = _FakeCacheMaintenance(
      sizeBytes: cacheSizeBytes,
      failClear: failCacheClear,
    );
    themeStore = _MemoryThemePreferenceStore();
    themeController = ThemeController(preferenceStore: themeStore);
    localeController = AppLocaleController(preferenceStore: localeStore);
    settingsController = SettingsController(
      preferenceStore: settingsStore,
      cacheMaintenance: cache,
    );
  }

  final settingsStore = _MemorySettingsStore();
  late final _FakeCacheMaintenance cache;
  late final ThemeController themeController;
  late final _MemoryThemePreferenceStore themeStore;
  final localeStore = _MemoryLocalePreferenceStore();
  late final AppLocaleController localeController;
  late final SettingsController settingsController;
}

final class _MemoryLocalePreferenceStore implements LocalePreferenceStore {
  String? code;

  @override
  Future<String?> readLanguageCode() async => code;

  @override
  Future<void> writeLanguageCode(String value) async => code = value;
}

final class _MemoryThemePreferenceStore implements ThemePreferenceStore {
  Color? color;

  @override
  Future<void> clearSeedColor() async => color = null;

  @override
  Future<Color?> readSeedColor() async => color;

  @override
  Future<void> writeSeedColor(Color color) async => this.color = color;
}

final class _MemorySettingsStore implements SettingsPreferenceStore {
  SettingsPreferences value = const SettingsPreferences();

  @override
  Future<SettingsPreferences> read() async => value;

  @override
  Future<void> write(SettingsPreferences preferences) async {
    value = preferences;
  }
}

final class _FakeCacheMaintenance implements CacheMaintenance {
  _FakeCacheMaintenance({required this.sizeBytes, required this.failClear});

  int sizeBytes;
  final bool failClear;
  int clearCalls = 0;

  @override
  Future<int> calculateSizeBytes() async => sizeBytes;

  @override
  Future<void> clearTemporaryFiles() async {
    clearCalls += 1;
    if (failClear) {
      throw StateError('clear failed');
    }
    sizeBytes = 0;
  }
}
