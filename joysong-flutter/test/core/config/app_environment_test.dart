import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';

void main() {
  group('AppEnvironment', () {
    test('resolves UAT with production-safe capabilities', () {
      final environment = AppEnvironment.resolve(
        platform: AppPlatform.android,
        flavorName: 'uat',
        baseUrl: 'https://121.41.230.98',
      );

      expect(environment.flavor, AppFlavor.uat);
      expect(environment.apiRoot.toString(), 'https://121.41.230.98/api/');
      expect(environment.allowsPreviewData, isFalse);
      expect(environment.usesPasswordOnlyLogin, isTrue);
      expect(environment.deepLinkScheme, 'joysong-uat');
    });

    test('maps the staging compatibility names to UAT', () {
      for (final name in ['staging', 'stage']) {
        final environment = AppEnvironment.resolve(
          platform: AppPlatform.ios,
          flavorName: name,
          baseUrl: 'https://api-uat.example.com',
        );

        expect(environment.flavor, AppFlavor.uat);
      }
    });

    test('allows local HTTP and preview data only in development', () {
      final environment = AppEnvironment.resolve(
        platform: AppPlatform.android,
        flavorName: 'development',
      );

      expect(environment.apiBaseUri.toString(), 'http://10.0.2.2:8080');
      expect(environment.allowsPreviewData, isTrue);
      expect(environment.usesPasswordOnlyLogin, isFalse);
      expect(environment.deepLinkScheme, 'joysong');
    });

    test('requires HTTPS for UAT and production', () {
      for (final name in ['uat', 'production']) {
        expect(
          () => AppEnvironment.resolve(
            platform: AppPlatform.android,
            flavorName: name,
            baseUrl: 'http://api.example.com',
          ),
          throwsFormatException,
        );
      }
    });

    test('rejects unknown environment names', () {
      expect(
        () => AppEnvironment.resolve(
          platform: AppPlatform.android,
          flavorName: 'uat-typo',
          baseUrl: 'https://api.example.com',
        ),
        throwsFormatException,
      );
    });
  });
}
