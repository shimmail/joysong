import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/localization/localization.dart';

void main() {
  group('AppLanguage', () {
    test('exposes stable locales', () {
      expect(AppLanguage.chinese.locale, const Locale('zh'));
      expect(AppLanguage.english.locale, const Locale('en'));
    });

    test('falls back to Chinese for missing or damaged storage values', () {
      expect(AppLanguage.fromStorageCode(null), AppLanguage.chinese);
      expect(AppLanguage.fromStorageCode(''), AppLanguage.chinese);
      expect(AppLanguage.fromStorageCode('damaged'), AppLanguage.chinese);
    });

    test('accepts current and common legacy codes', () {
      expect(AppLanguage.fromStorageCode('en'), AppLanguage.english);
      expect(AppLanguage.fromStorageCode('en_US'), AppLanguage.english);
      expect(AppLanguage.fromStorageCode('zh-CN'), AppLanguage.chinese);
    });
  });

  group('AppLocaleController', () {
    test('defaults to Chinese', () {
      final controller = AppLocaleController(
        preferenceStore: _FakeLocalePreferenceStore(),
      );

      expect(controller.language, AppLanguage.chinese);
    });

    test('restores a persisted language', () async {
      final store = _FakeLocalePreferenceStore(storedCode: 'en');
      final controller = AppLocaleController(preferenceStore: store);

      await controller.restore();

      expect(controller.language, AppLanguage.english);
      expect(controller.isRestoring, isFalse);
    });

    test('damaged persisted value restores Chinese', () async {
      final store = _FakeLocalePreferenceStore(storedCode: 'not-a-locale');
      final controller = AppLocaleController(
        preferenceStore: store,
        initialLanguage: AppLanguage.english,
      );

      await controller.restore();

      expect(controller.language, AppLanguage.chinese);
    });

    test('persists before publishing a language change', () async {
      final store = _FakeLocalePreferenceStore();
      final controller = AppLocaleController(preferenceStore: store);
      var notificationCount = 0;
      controller.addListener(() => notificationCount++);

      await controller.setLanguage(AppLanguage.english);

      expect(store.writtenCodes, ['en']);
      expect(controller.language, AppLanguage.english);
      expect(notificationCount, 1);
    });

    test('write failure does not publish a language change', () async {
      final store = _FakeLocalePreferenceStore(writeError: StateError('full'));
      final controller = AppLocaleController(preferenceStore: store);
      var notificationCount = 0;
      controller.addListener(() => notificationCount++);

      await expectLater(
        controller.setLanguage(AppLanguage.english),
        throwsStateError,
      );

      expect(controller.language, AppLanguage.chinese);
      expect(notificationCount, 0);
    });

    test('a failed write does not block a later successful write', () async {
      final store = _FakeLocalePreferenceStore(
        writeError: StateError('temporary'),
      );
      final controller = AppLocaleController(preferenceStore: store);

      await expectLater(
        controller.setLanguage(AppLanguage.english),
        throwsStateError,
      );
      store.writeError = null;
      await controller.setLanguage(AppLanguage.english);

      expect(controller.language, AppLanguage.english);
      expect(store.writtenCodes, ['en']);
    });
  });

  group('AppLocaleScope', () {
    testWidgets('maybeOf returns null outside the scope', (tester) async {
      AppLocaleController? found;
      await tester.pumpWidget(
        Builder(
          builder: (context) {
            found = AppLocaleScope.maybeOf(context);
            return const SizedBox.shrink();
          },
        ),
      );

      expect(found, isNull);
    });

    testWidgets('exposes the controller and rebuilds dependents', (
      tester,
    ) async {
      final controller = AppLocaleController(
        preferenceStore: _FakeLocalePreferenceStore(),
      );
      var builds = 0;

      await tester.pumpWidget(
        AppLocaleScope(
          controller: controller,
          child: Builder(
            builder: (context) {
              builds++;
              final language = AppLocaleScope.maybeOf(context)!.language;
              return Text(language.storageCode,
                  textDirection: TextDirection.ltr);
            },
          ),
        ),
      );
      expect(find.text('zh'), findsOneWidget);

      await controller.setLanguage(AppLanguage.english);
      await tester.pump();

      expect(find.text('en'), findsOneWidget);
      expect(builds, 2);
    });
  });
}

final class _FakeLocalePreferenceStore implements LocalePreferenceStore {
  _FakeLocalePreferenceStore({this.storedCode, this.writeError});

  String? storedCode;
  Object? writeError;
  final List<String> writtenCodes = [];

  @override
  Future<String?> readLanguageCode() async => storedCode;

  @override
  Future<void> writeLanguageCode(String value) async {
    final error = writeError;
    if (error != null) {
      throw error;
    }
    writtenCodes.add(value);
    storedCode = value;
  }
}
