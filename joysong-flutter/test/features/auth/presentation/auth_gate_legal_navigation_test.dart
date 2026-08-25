import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/core/routing/app_router.dart';
import 'package:joysong_flutter/core/theme/theme_controller.dart';
import 'package:joysong_flutter/core/theme/theme_preferences_store.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/auth/domain/auth_repository.dart';
import 'package:joysong_flutter/features/auth/presentation/auth_controller.dart';
import 'package:joysong_flutter/features/auth/presentation/auth_gate.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_models.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_repository.dart';
import 'package:joysong_flutter/features/settings/domain/settings_preferences.dart';
import 'package:joysong_flutter/features/settings/domain/settings_services.dart';
import 'package:joysong_flutter/features/settings/presentation/settings_controller.dart';

void main() {
  testWidgets('login legal links open their shared document routes', (
    tester,
  ) async {
    final fixture = await _pumpAuthGate(tester);

    expect(find.byKey(const Key('agreements-checkbox')), findsOneWidget);

    await _tapLoginLink(tester, const Key('user-agreement-button'));
    _expectLegalPage(LegalDocumentType.userAgreement);
    await tester.pageBack();
    await tester.pumpAndSettle();

    await _tapLoginLink(tester, const Key('privacy-policy-button'));
    _expectLegalPage(LegalDocumentType.privacyPolicy);

    fixture.dispose();
  });

  testWidgets('registration legal links open their shared document routes', (
    tester,
  ) async {
    final fixture = await _pumpAuthGate(tester);

    await tester.ensureVisible(find.byKey(const Key('register-button')));
    await tester.tap(find.byKey(const Key('register-button')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('auth-action-agreement')), findsOneWidget);

    await tester.tap(find.text('《用户协议》'));
    await tester.pumpAndSettle();
    _expectLegalPage(LegalDocumentType.userAgreement);
    await tester.pageBack();
    await tester.pumpAndSettle();

    await tester.tap(find.text('《隐私政策》'));
    await tester.pumpAndSettle();
    _expectLegalPage(LegalDocumentType.privacyPolicy);

    fixture.dispose();
  });
}

Future<_Fixture> _pumpAuthGate(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  final fixture = _Fixture();
  await fixture.authController.restoreSession();
  await tester.pumpWidget(
    MaterialApp(
      home: AuthGate(
        controller: fixture.authController,
        agentConfig: const AgentConfig(),
      ),
      onGenerateRoute: (settings) => AppRouter.onGenerateRoute(
        settings,
        agentConfig: const AgentConfig(),
        themeController: fixture.themeController,
        settingsController: fixture.settingsController,
        legalDocumentRepository: fixture.legalRepository,
      ),
    ),
  );
  await tester.pumpAndSettle();
  return fixture;
}

Future<void> _tapLoginLink(WidgetTester tester, Key key) async {
  await tester.ensureVisible(find.byKey(key));
  await tester.tap(find.byKey(key));
  await tester.pumpAndSettle();
}

void _expectLegalPage(LegalDocumentType type) {
  expect(
    find.byKey(ValueKey('legal-document-${type.pathSegment}')),
    findsOneWidget,
  );
}

final class _Fixture {
  _Fixture()
      : authController = AuthController(_FakeAuthRepository()),
        themeController = ThemeController(preferenceStore: _ThemeStore()),
        settingsController = SettingsController(
          preferenceStore: _SettingsStore(),
          cacheMaintenance: _CacheMaintenance(),
        );

  final AuthController authController;
  final ThemeController themeController;
  final SettingsController settingsController;
  final LegalDocumentRepository legalRepository = _LegalRepository();

  void dispose() {
    authController.dispose();
    themeController.dispose();
    settingsController.dispose();
  }
}

final class _FakeAuthRepository implements AuthRepository {
  @override
  Future<AuthTokens?> readTokens() async => null;

  @override
  Future<bool> checkPhoneRegistered({required String phone}) async => false;

  @override
  Future<void> sendCode({required String phone}) async {}

  @override
  Future<void> clearLocalTokens() async {}

  @override
  Future<void> logout() async {}

  @override
  Future<void> activateTokens(AuthTokens tokens) async {}

  @override
  Future<AuthSession> loginWithCode({
    required String phone,
    required String code,
  }) =>
      throw UnimplementedError();

  @override
  Future<AuthSession> loginWithGoogle({required String idToken}) =>
      throw UnimplementedError();

  @override
  Future<AuthSession> loginWithPassword({
    required String phone,
    required String password,
  }) =>
      throw UnimplementedError();

  @override
  Future<AuthSession> refreshSession() => throw UnimplementedError();

  @override
  Future<AuthSession> register({
    required String phone,
    required String code,
    required String password,
  }) =>
      throw UnimplementedError();

  @override
  Future<void> resetPassword({
    required String phone,
    required String code,
    required String newPassword,
  }) =>
      throw UnimplementedError();
}

final class _LegalRepository implements LegalDocumentRepository {
  @override
  Future<LegalDocument> load({
    required LegalDocumentType type,
    required String locale,
    bool forceRefresh = false,
  }) async =>
      LegalDocument(
        type: type,
        locale: locale,
        version: 1,
        title: type == LegalDocumentType.userAgreement ? '用户协议' : '隐私政策',
        contentHtml: '<p>正文</p>',
        publishedAt: DateTime(2026, 8, 25),
        contentSha256: 'hash',
      );
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
