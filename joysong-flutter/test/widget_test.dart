import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/app/app.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/auth/domain/auth_repository.dart';
import 'package:joysong_flutter/features/profile/presentation/profile_page.dart';
import 'package:joysong_flutter/features/settings/domain/settings_preferences.dart';
import 'package:joysong_flutter/features/settings/domain/settings_services.dart';

import 'core/translation/translation_test_fixtures.dart';

void main() {
  testWidgets('app shell switches between primary destinations',
      (tester) async {
    await tester.pumpWidget(
      JoysongApp(
        environment: AppEnvironment.resolve(platform: AppPlatform.android),
        authRepository: _AuthenticatedRepository(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('安心变美，从了解开始'), findsOneWidget);

    await tester.tap(
      find.descendant(
        of: find.byType(NavigationBar),
        matching: find.text('发现'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('项目'), findsOneWidget);

    await tester.tap(
      find.descendant(
        of: find.byType(NavigationBar),
        matching: find.text('我的'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('娇颜颂用户'), findsOneWidget);
    await tester.drag(
      find.descendant(
        of: find.byType(ProfilePage),
        matching: find.byType(ListView),
      ),
      const Offset(0, -420),
    );
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.settings_outlined), findsOneWidget);
  });

  testWidgets('restores persisted appearance mode at the application root',
      (tester) async {
    await tester.pumpWidget(
      JoysongApp(
        environment: AppEnvironment.resolve(platform: AppPlatform.android),
        authRepository: _AuthenticatedRepository(),
        settingsPreferenceStore: _SettingsStore(
          const SettingsPreferences(
            appearanceMode: AppAppearanceMode.dark,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(tester.widget<MaterialApp>(find.byType(MaterialApp)).themeMode,
        ThemeMode.dark);
  });

  testWidgets('restores persisted language at the application root',
      (tester) async {
    await tester.pumpWidget(
      JoysongApp(
        environment: AppEnvironment.resolve(platform: AppPlatform.android),
        authRepository: _AuthenticatedRepository(),
        localePreferenceStore: _LocaleStore('en'),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      tester.widget<MaterialApp>(find.byType(MaterialApp)).locale,
      const Locale('en'),
    );
    expect(find.text('Home'), findsOneWidget);
    expect(find.text('Discover'), findsOneWidget);
    expect(find.text('Profile'), findsOneWidget);
  });

  testWidgets(
      'restored authenticated English consent activates injected translation',
      (tester) async {
    final translationRepository = RecordingTranslationRepository()
      ..holdResponses = true;
    await tester.pumpWidget(
      JoysongApp(
        environment: AppEnvironment.resolve(platform: AppPlatform.android),
        authRepository: _AuthenticatedRepository(),
        localePreferenceStore: _LocaleStore('en'),
        settingsPreferenceStore: _SettingsStore(
          const SettingsPreferences(aiTranslationEnabled: true),
        ),
        translationRepository: translationRepository,
      ),
    );
    await tester.pumpAndSettle();
    _insertTranslationProbe(tester);
    await tester.pump();

    expect(find.text(_rootTranslationRequest.sourceText), findsOneWidget);
    expect(translationRepository.calls, hasLength(1));
    expect(translationRepository.calls.single.targetLanguage, 'en-US');

    translationRepository.completeNext('Root translation');
    await _pumpTranslation(tester);
    expect(find.text('Root translation'), findsOneWidget);
  });

  testWidgets('unauthenticated restoration keeps root translation inactive',
      (tester) async {
    final translationRepository = RecordingTranslationRepository();
    await tester.pumpWidget(
      JoysongApp(
        environment: AppEnvironment.resolve(platform: AppPlatform.android),
        authRepository: _UnauthenticatedRepository(),
        localePreferenceStore: _LocaleStore('en'),
        settingsPreferenceStore: _SettingsStore(
          const SettingsPreferences(aiTranslationEnabled: true),
        ),
        translationRepository: translationRepository,
      ),
    );
    await tester.pumpAndSettle();
    _insertTranslationProbe(tester);
    await tester.pump();

    expect(find.text(_rootTranslationRequest.sourceText), findsOneWidget);
    expect(translationRepository.calls, isEmpty);
  });

  testWidgets('Chinese restoration keeps root translation inactive',
      (tester) async {
    final translationRepository = RecordingTranslationRepository();
    await tester.pumpWidget(
      JoysongApp(
        environment: AppEnvironment.resolve(platform: AppPlatform.android),
        authRepository: _AuthenticatedRepository(),
        localePreferenceStore: _LocaleStore('zh'),
        settingsPreferenceStore: _SettingsStore(
          const SettingsPreferences(aiTranslationEnabled: true),
        ),
        translationRepository: translationRepository,
      ),
    );
    await tester.pumpAndSettle();
    _insertTranslationProbe(tester);
    await tester.pump();

    expect(find.text(_rootTranslationRequest.sourceText), findsOneWidget);
    expect(translationRepository.calls, isEmpty);
  });

  testWidgets('disabled persisted consent keeps root translation inactive',
      (tester) async {
    final translationRepository = RecordingTranslationRepository();
    await tester.pumpWidget(
      JoysongApp(
        environment: AppEnvironment.resolve(platform: AppPlatform.android),
        authRepository: _AuthenticatedRepository(),
        localePreferenceStore: _LocaleStore('en'),
        settingsPreferenceStore: _SettingsStore(
          const SettingsPreferences(aiTranslationEnabled: false),
        ),
        translationRepository: translationRepository,
      ),
    );
    await tester.pumpAndSettle();
    _insertTranslationProbe(tester);
    await tester.pump();

    expect(find.text(_rootTranslationRequest.sourceText), findsOneWidget);
    expect(translationRepository.calls, isEmpty);
  });

  testWidgets('logout restores source and invalidates a late translation',
      (tester) async {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final logoutCompleter = Completer<void>();
    addTearDown(() {
      if (!logoutCompleter.isCompleted) logoutCompleter.complete();
    });
    final authRepository = _AuthenticatedRepository()
      ..logoutCompleter = logoutCompleter;
    final translationRepository = RecordingTranslationRepository()
      ..holdResponses = true;
    await tester.pumpWidget(
      JoysongApp(
        environment: AppEnvironment.resolve(platform: AppPlatform.android),
        authRepository: authRepository,
        localePreferenceStore: _LocaleStore('en'),
        settingsPreferenceStore: _SettingsStore(
          const SettingsPreferences(aiTranslationEnabled: true),
        ),
        translationRepository: translationRepository,
      ),
    );
    await tester.pumpAndSettle();
    _insertTranslationProbe(tester);
    await tester.pump();
    expect(translationRepository.calls, hasLength(1));
    translationRepository.completeNext('Visible root translation');
    await _pumpTranslation(tester);
    expect(find.text('Visible root translation'), findsOneWidget);

    _insertTranslationProbe(tester, request: _pendingRootTranslationRequest);
    await tester.pump();
    expect(translationRepository.calls, hasLength(2));

    await tester.tap(
      find.descendant(
        of: find.byType(NavigationBar),
        matching: find.text('Profile'),
      ),
    );
    await tester.pumpAndSettle();
    await tester.drag(
      find.descendant(
        of: find.byType(ProfilePage),
        matching: find.byType(ListView),
      ),
      const Offset(0, -1000),
    );
    await tester.pumpAndSettle();
    final logoutButton = find.byKey(const Key('logout-button'));
    await tester.tap(logoutButton);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Confirm'));
    await tester.pump();
    await tester.pump();

    expect(authRepository.logoutCalls, 1);
    expect(logoutCompleter.isCompleted, isFalse);
    expect(
      tester
          .widget<AutoTranslationScope>(find.byType(AutoTranslationScope))
          .enabled,
      isFalse,
    );
    expect(find.text(_rootTranslationRequest.sourceText), findsOneWidget);
    expect(find.text('Visible root translation'), findsNothing);

    _insertTranslationProbe(tester, request: _inactiveRootTranslationRequest);
    await tester.pump();
    expect(translationRepository.calls, hasLength(2));

    translationRepository.completeNext('Translation after logout started');
    await _pumpTranslation(tester);
    expect(
        find.text(_pendingRootTranslationRequest.sourceText), findsOneWidget);
    expect(find.text('Translation after logout started'), findsNothing);
    expect(tester.takeException(), isNull);

    logoutCompleter.complete();
    await tester.pumpAndSettle();
    expect(authRepository.logoutCalls, 1);
  });
}

class _AuthenticatedRepository extends Fake implements AuthRepository {
  int logoutCalls = 0;
  Completer<void>? logoutCompleter;

  @override
  Future<AuthTokens?> readTokens() async => const AuthTokens(
        accessToken: 'access-token',
        refreshToken: 'refresh-token',
        tokenType: 'Bearer',
        expiresIn: 3600,
      );

  @override
  Future<AuthSession> refreshSession() async => AuthSession(
        tokens: (await readTokens())!,
        user: const AuthUser(
          id: 'user-1',
          phone: '+8613800000000',
          email: null,
          nickname: '娇颜颂用户',
          avatar: '',
          gender: '',
          city: '',
          bio: '',
          birthday: null,
          role: 'USER',
          hasPassword: true,
        ),
      );

  @override
  Future<void> logout() {
    logoutCalls += 1;
    return logoutCompleter?.future ?? Future<void>.value();
  }
}

class _UnauthenticatedRepository extends Fake implements AuthRepository {
  @override
  Future<AuthTokens?> readTokens() async => null;
}

final class _SettingsStore implements SettingsPreferenceStore {
  _SettingsStore(this.value);

  SettingsPreferences value;

  @override
  Future<SettingsPreferences> read() async => value;

  @override
  Future<void> write(SettingsPreferences preferences) async {
    value = preferences;
  }
}

final class _LocaleStore implements LocalePreferenceStore {
  _LocaleStore(this.value);

  String? value;

  @override
  Future<String?> readLanguageCode() async => value;

  @override
  Future<void> writeLanguageCode(String value) async {
    this.value = value;
  }
}

const _rootTranslationRequest = AutoTranslationRequest(
  contentType: 'project',
  contentId: 'root-probe',
  field: 'description',
  sourceText: '自动翻译根测试',
);

const _pendingRootTranslationRequest = AutoTranslationRequest(
  contentType: 'project',
  contentId: 'root-pending-probe',
  field: 'description',
  sourceText: '等待中的根翻译',
);

const _inactiveRootTranslationRequest = AutoTranslationRequest(
  contentType: 'project',
  contentId: 'root-inactive-probe',
  field: 'description',
  sourceText: '退出后的根翻译',
);

void _insertTranslationProbe(
  WidgetTester tester, {
  AutoTranslationRequest request = _rootTranslationRequest,
}) {
  final overlay = tester.state<OverlayState>(find.byType(Overlay).first);
  overlay.insert(
    OverlayEntry(
      builder: (context) => Positioned(
        left: 0,
        top: 0,
        child: IgnorePointer(
          child: Material(
            child: AutoTranslatedText(request: request),
          ),
        ),
      ),
    ),
  );
}

Future<void> _pumpTranslation(WidgetTester tester) async {
  await tester.pump();
  await tester.pump();
}
