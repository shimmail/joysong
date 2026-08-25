import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/core/routing/app_router.dart';
import 'package:joysong_flutter/core/theme/theme_controller.dart';
import 'package:joysong_flutter/core/theme/theme_preferences_store.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_models.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_repository.dart';
import 'package:joysong_flutter/features/legal_documents/presentation/legal_document_page.dart';
import 'package:joysong_flutter/features/settings/domain/settings_preferences.dart';
import 'package:joysong_flutter/features/settings/domain/settings_services.dart';
import 'package:joysong_flutter/features/settings/presentation/settings_controller.dart';

void main() {
  testWidgets('shared named routes build the correct legal document type', (
    tester,
  ) async {
    final fixture = _Fixture();
    await _pumpRouterHost(tester, fixture);

    for (final entry in const [
      (AppRoutes.userAgreement, LegalDocumentType.userAgreement),
      (AppRoutes.privacyPolicy, LegalDocumentType.privacyPolicy),
    ]) {
      await tester.tap(find.byKey(const Key('open-route')));
      await tester.pumpAndSettle();

      final page = tester.widget<LegalDocumentPage>(
        find.byKey(ValueKey('legal-document-${entry.$2.pathSegment}')),
      );
      expect(page.type, entry.$2);
      expect(fixture.repository.types.last, entry.$2);

      await tester.pageBack();
      await tester.pumpAndSettle();
      fixture.nextRoute = entry.$1 == AppRoutes.userAgreement
          ? AppRoutes.privacyPolicy
          : AppRoutes.userAgreement;
    }

    fixture.dispose();
  });

  testWidgets('Settings keeps two legal entries wired to the shared pages', (
    tester,
  ) async {
    final fixture = _Fixture()..nextRoute = AppRoutes.settings;
    await _pumpRouterHost(tester, fixture);

    await tester.tap(find.byKey(const Key('open-route')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('privacy-policy-entry')), findsOneWidget);
    expect(find.byKey(const Key('terms-entry')), findsOneWidget);

    await _tapAfterScroll(tester, const Key('privacy-policy-entry'));
    expect(
      tester.widget<LegalDocumentPage>(find.byType(LegalDocumentPage)).type,
      LegalDocumentType.privacyPolicy,
    );
    await tester.pageBack();
    await tester.pumpAndSettle();

    await _tapAfterScroll(tester, const Key('terms-entry'));
    expect(
      tester.widget<LegalDocumentPage>(find.byType(LegalDocumentPage)).type,
      LegalDocumentType.userAgreement,
    );

    fixture.dispose();
  });
}

Future<void> _pumpRouterHost(WidgetTester tester, _Fixture fixture) async {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    MaterialApp(
      home: Builder(
        builder: (context) => Scaffold(
          body: FilledButton(
            key: const Key('open-route'),
            onPressed: () => Navigator.of(context).pushNamed(fixture.nextRoute),
            child: const Text('Open'),
          ),
        ),
      ),
      onGenerateRoute: (settings) => AppRouter.onGenerateRoute(
        settings,
        agentConfig: const AgentConfig(),
        themeController: fixture.themeController,
        settingsController: fixture.settingsController,
        legalDocumentRepository: fixture.repository,
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> _tapAfterScroll(WidgetTester tester, Key key) async {
  await tester.scrollUntilVisible(
    find.byKey(key),
    300,
    scrollable: find.byType(Scrollable).first,
  );
  await tester.tap(find.byKey(key));
  await tester.pumpAndSettle();
}

final class _Fixture {
  _Fixture()
      : themeController = ThemeController(preferenceStore: _ThemeStore()),
        settingsController = SettingsController(
          preferenceStore: _SettingsStore(),
          cacheMaintenance: _CacheMaintenance(),
        );

  String nextRoute = AppRoutes.userAgreement;
  final ThemeController themeController;
  final SettingsController settingsController;
  final _LegalRepository repository = _LegalRepository();

  void dispose() {
    themeController.dispose();
    settingsController.dispose();
  }
}

final class _LegalRepository implements LegalDocumentRepository {
  final types = <LegalDocumentType>[];

  @override
  Future<LegalDocument> load({
    required LegalDocumentType type,
    required String locale,
    bool forceRefresh = false,
  }) async {
    types.add(type);
    return LegalDocument(
      type: type,
      locale: locale,
      version: 1,
      title: type.pathSegment,
      contentHtml: '<p>Body</p>',
      publishedAt: DateTime(2026, 8, 25),
      contentSha256: 'hash',
    );
  }
}

final class _ThemeStore implements ThemePreferenceStore {
  @override
  Future<void> clearSeedColor() async {}

  @override
  Future<Color?> readSeedColor() async => null;

  @override
  Future<void> writeSeedColor(Color color) async {}
}

final class _SettingsStore implements SettingsPreferenceStore {
  @override
  Future<SettingsPreferences> read() async => const SettingsPreferences();

  @override
  Future<void> write(SettingsPreferences preferences) async {}
}

final class _CacheMaintenance implements CacheMaintenance {
  @override
  Future<int> calculateSizeBytes() async => 0;

  @override
  Future<void> clearTemporaryFiles() async {}
}
