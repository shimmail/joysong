import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';

void main() {
  group('AppEnvironment', () {
    test('uses emulator-specific development URLs', () {
      final android = AppEnvironment.resolve(platform: AppPlatform.android);
      final ios = AppEnvironment.resolve(platform: AppPlatform.ios);

      expect(android.apiBaseUri.toString(), 'http://10.0.2.2:8080');
      expect(ios.apiBaseUri.toString(), 'http://127.0.0.1:8080');
      expect(android.apiRoot.toString(), 'http://10.0.2.2:8080/api/');
    });

    test('requires HTTPS in production', () {
      expect(
        () => AppEnvironment.resolve(
          platform: AppPlatform.android,
          flavorName: 'production',
          baseUrl: 'http://example.com',
        ),
        throwsFormatException,
      );
    });
  });
}
