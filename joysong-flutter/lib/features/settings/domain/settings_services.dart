import 'package:flutter/painting.dart';
import 'package:joysong_flutter/features/settings/domain/settings_preferences.dart';

abstract interface class SettingsPreferenceStore {
  Future<SettingsPreferences> read();

  Future<void> write(SettingsPreferences preferences);
}

abstract interface class NotificationPreferenceSync {
  /// Persists notification preferences outside this device when a backend
  /// capability is available. Throwing keeps the previous local state.
  Future<void> sync(NotificationPreferences preferences);
}

abstract interface class CacheMaintenance {
  Future<int> calculateSizeBytes();

  /// Clears only recreatable application data. Authentication, drafts and
  /// user preferences must never be removed by this operation.
  Future<void> clearTemporaryFiles();
}

final class FlutterImageCacheMaintenance implements CacheMaintenance {
  const FlutterImageCacheMaintenance();

  @override
  Future<int> calculateSizeBytes() async {
    return PaintingBinding.instance.imageCache.currentSizeBytes;
  }

  @override
  Future<void> clearTemporaryFiles() async {
    final cache = PaintingBinding.instance.imageCache;
    cache.clear();
    cache.clearLiveImages();
  }
}
