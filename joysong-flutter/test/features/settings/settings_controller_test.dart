import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/settings/domain/settings_preferences.dart';
import 'package:joysong_flutter/features/settings/domain/settings_services.dart';
import 'package:joysong_flutter/features/settings/presentation/settings_controller.dart';

void main() {
  test('restores and persists appearance mode', () async {
    final store = _MemorySettingsStore(
      const SettingsPreferences(appearanceMode: AppAppearanceMode.dark),
    );
    final controller = SettingsController(
      preferenceStore: store,
      cacheMaintenance: _FakeCacheMaintenance(),
    );

    await controller.restore();
    await controller.setAppearanceMode(AppAppearanceMode.light);

    expect(controller.hasRestored, isTrue);
    expect(controller.appearanceMode, AppAppearanceMode.light);
    expect(store.value.appearanceMode, AppAppearanceMode.light);
  });

  test('failed preference write does not claim an appearance change', () async {
    final store = _MemorySettingsStore()..failWrites = true;
    final controller = SettingsController(
      preferenceStore: store,
      cacheMaintenance: _FakeCacheMaintenance(),
    );

    await expectLater(
      controller.setAppearanceMode(AppAppearanceMode.dark),
      throwsStateError,
    );

    expect(controller.appearanceMode, AppAppearanceMode.system);
    expect(controller.isSaving, isFalse);
  });

  test('notification sync failure keeps local preferences unchanged', () async {
    final store = _MemorySettingsStore();
    final controller = SettingsController(
      preferenceStore: store,
      cacheMaintenance: _FakeCacheMaintenance(),
      notificationSync: _FailingNotificationSync(),
    );
    const next = NotificationPreferences(productNews: true);

    await expectLater(controller.setNotifications(next), throwsStateError);

    expect(controller.notifications, const NotificationPreferences());
    expect(store.value.notifications, const NotificationPreferences());
  });

  test('cache result changes only after the maintenance operation completes',
      () async {
    final cache = _FakeCacheMaintenance(sizeBytes: 2 * 1024 * 1024);
    final controller = SettingsController(
      preferenceStore: _MemorySettingsStore(),
      cacheMaintenance: cache,
    );

    await controller.refreshCacheSize();
    expect(controller.cacheSizeBytes, 2 * 1024 * 1024);

    await controller.clearCache();
    expect(cache.clearCalls, 1);
    expect(controller.cacheSizeBytes, 0);
    expect(controller.isClearingCache, isFalse);
  });

  test('failed cache clear is surfaced and never reported as empty', () async {
    final cache = _FakeCacheMaintenance(sizeBytes: 4096, failClear: true);
    final controller = SettingsController(
      preferenceStore: _MemorySettingsStore(),
      cacheMaintenance: cache,
    );
    await controller.refreshCacheSize();

    await expectLater(controller.clearCache(), throwsStateError);

    expect(controller.cacheSizeBytes, 4096);
    expect(controller.isClearingCache, isFalse);
  });
}

final class _MemorySettingsStore implements SettingsPreferenceStore {
  _MemorySettingsStore([this.value = const SettingsPreferences()]);

  SettingsPreferences value;
  bool failWrites = false;

  @override
  Future<SettingsPreferences> read() async => value;

  @override
  Future<void> write(SettingsPreferences preferences) async {
    if (failWrites) {
      throw StateError('write failed');
    }
    value = preferences;
  }
}

final class _FailingNotificationSync implements NotificationPreferenceSync {
  @override
  Future<void> sync(NotificationPreferences preferences) {
    throw StateError('sync failed');
  }
}

final class _FakeCacheMaintenance implements CacheMaintenance {
  _FakeCacheMaintenance({this.sizeBytes = 0, this.failClear = false});

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
