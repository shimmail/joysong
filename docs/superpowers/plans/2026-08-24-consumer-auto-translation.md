# Consumer AI Auto-Translation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in English-mode AI translation setting that progressively replaces eligible Chinese database content across consumer Home, Discover, and detail experiences while leaving Chinese professional operations unchanged.

**Architecture:** Extract the existing endpoint client into `core/translation`, add one shared controller with eligibility checks, three-request concurrency, in-flight deduplication, bounded successful-result caching, and generation invalidation, then render fields through reusable translated-value builders. Flutter continues to use the authenticated single-text endpoint; backend contracts and database schemas do not change.

**Tech Stack:** Flutter/Dart, `ChangeNotifier`, `InheritedWidget`, `ApiClient`, `flutter_secure_storage`, Flutter widget/unit tests.

**Spec:** `docs/superpowers/specs/2026-08-24-consumer-auto-translation-design.md`

**Command working directories:** Run every `flutter` command from `joysong-flutter/`. Run `git` commands from the worktree root.

## Global Constraints

- Automatic translation is active only when App language is English, persisted `aiTranslationEnabled` is true, and `AuthController.status == AuthStatus.authenticated`.
- The preference defaults to `false`; legacy JSON without the field also resolves to `false`.
- Source text renders immediately; success replaces it asynchronously and every failure retains source text.
- Reuse authenticated `POST /api/translations`; do not change backend contracts or database schemas.
- Send only backend-supported content types: `diary`, `comment`, `article`, `article_html`, `project`, `project_html`, `institution`, `doctor`, `message`, or `general`. Use `general` for banners and `comment` for reviews.
- Run at most three requests concurrently and coalesce identical in-flight requests.
- Cache identity includes target language, content type, entity ID, field, and complete source text; failures are not cached.
- Do not translate person names, phone numbers, prices, dates, identifiers, or certificate numbers.
- Do not modify `features/identity/**`, `features/professional_management/**`, institution project management, doctor service management, consultant management, or `joysong-admin/**`.
- Consumer institution and doctor cards/details are in scope, including the Home doctor section.
- Existing manual diary, comment, reply, and direct-message translation remains usable.
- Add no Flutter dependency.
- Reuse `test/core/translation/translation_test_fixtures.dart` for recording, controlled-completion, concurrency, and failure fakes instead of duplicating fake repositories per page test.
- Run focused tests first, `flutter analyze` after focused tests, and at most one full Flutter test run capped at ten minutes.

---

### Task 1: Persisted AI Translation Preference and Settings Switch

**Files:**
- Modify: `joysong-flutter/lib/features/settings/domain/settings_preferences.dart`
- Modify: `joysong-flutter/lib/features/settings/presentation/settings_controller.dart`
- Modify: `joysong-flutter/lib/features/settings/presentation/settings_strings.dart`
- Modify: `joysong-flutter/lib/features/settings/presentation/settings_page.dart`
- Modify: `joysong-flutter/test/features/settings/settings_preferences_store_test.dart`
- Modify: `joysong-flutter/test/features/settings/settings_controller_test.dart`
- Modify: `joysong-flutter/test/features/settings/presentation/settings_page_test.dart`

**Interfaces:**
- Produces: `SettingsPreferences.aiTranslationEnabled: bool`
- Produces: `SettingsController.aiTranslationEnabled: bool`
- Produces: `Future<void> SettingsController.setAiTranslationEnabled(bool value)`
- Produces: widget key `ai-translation-switch`

- [ ] **Step 1: Add failing persistence and controller tests**

```dart
test('legacy settings keep AI translation disabled', () {
  final value = SettingsPreferences.fromJson({
    'version': 1,
    'appearanceMode': 'system',
    'notifications': <String, Object?>{},
  });
  expect(value.aiTranslationEnabled, isFalse);
});

test('AI translation preference survives a secure-store round trip', () async {
  final storage = _MemorySecureStore();
  final store = SecureSettingsPreferenceStore(storage: storage);
  await store.write(const SettingsPreferences(aiTranslationEnabled: true));
  expect((await store.read()).aiTranslationEnabled, isTrue);
  final json = jsonDecode(
    storage.values[SecureSettingsPreferenceStore.preferenceKey]!,
  ) as Map<String, dynamic>;
  expect(json['aiTranslationEnabled'], isTrue);
});

test('failed AI translation write keeps the prior value', () async {
  final store = _MemorySettingsStore()..failWrites = true;
  final controller = SettingsController(
    preferenceStore: store,
    cacheMaintenance: _FakeCacheMaintenance(),
  );
  await expectLater(
    controller.setAiTranslationEnabled(true),
    throwsStateError,
  );
  expect(controller.aiTranslationEnabled, isFalse);
  expect(controller.isSaving, isFalse);
});
```

- [ ] **Step 2: Add the failing English-only Settings widget test**

```dart
testWidgets('AI translation switch appears only in English and persists',
    (tester) async {
  final fixture = _Fixture();
  await _pumpPage(tester, fixture);
  expect(find.byKey(const Key('ai-translation-switch')), findsNothing);

  await _tapAfterScroll(tester, const Key('language-entry'));
  await tester.tap(find.byKey(const Key('language-english-option')));
  await tester.pumpAndSettle();
  await _scrollTo(tester, const Key('ai-translation-switch'));
  expect(find.text('AI automatic translation'), findsOneWidget);
  expect(find.textContaining('sent to an AI translation service'),
      findsOneWidget);

  await tester.tap(find.byKey(const Key('ai-translation-switch')));
  await tester.pumpAndSettle();
  expect(fixture.settingsStore.value.aiTranslationEnabled, isTrue);

  await _tapAfterScroll(tester, const Key('language-entry'));
  await tester.tap(find.byKey(const Key('language-chinese-option')));
  await tester.pumpAndSettle();
  expect(find.byKey(const Key('ai-translation-switch')), findsNothing);
  expect(fixture.settingsStore.value.aiTranslationEnabled, isTrue);
});
```

- [ ] **Step 3: Run focused Settings tests and verify RED**

```powershell
flutter test test/features/settings/settings_preferences_store_test.dart test/features/settings/settings_controller_test.dart test/features/settings/presentation/settings_page_test.dart
```

Expected: missing preference field, setter, and widget key.

- [ ] **Step 4: Implement the preference and controller**

```dart
const SettingsPreferences({
  this.appearanceMode = AppAppearanceMode.system,
  this.notifications = const NotificationPreferences(),
  this.aiTranslationEnabled = false,
});

final bool aiTranslationEnabled;

Future<void> setAiTranslationEnabled(bool value) async {
  if (_isSaving || value == _preferences.aiTranslationEnabled) return;
  await _save(_preferences.copyWith(aiTranslationEnabled: value));
}
```

Add `aiTranslationEnabled` to `copyWith`, `toJson`, `fromJson`, equality, and `hashCode`. Deserialize only a Boolean and otherwise use `false`.

- [ ] **Step 5: Implement the English-only switch and copy**

```dart
String get aiTranslation => pick('AI 自动翻译', 'AI automatic translation');
String get aiTranslationSubtitle => pick(
      '英文模式下，符合条件的页面文本将发送至 AI 翻译服务',
      'Eligible page text is sent to an AI translation service in English mode.',
    );
String get aiTranslationSaveFailed => pick(
      'AI 翻译设置保存失败，请稍后重试',
      'Could not save AI translation setting. Try again later.',
    );
```

Immediately after the language tile, conditionally render:

```dart
if (AppLocaleScope.maybeOf(context)?.language == AppLanguage.english)
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
```

- [ ] **Step 6: Run the Step 3 command and verify GREEN**

Expected: all Settings tests pass.

- [ ] **Step 7: Commit**

```powershell
git add -- joysong-flutter/lib/features/settings joysong-flutter/test/features/settings
git commit -m "feat: add AI translation preference"
```

---

### Task 2: Shared Translation API Boundary

**Files:**
- Create: `joysong-flutter/lib/core/translation/content_translation.dart`
- Create: `joysong-flutter/lib/core/translation/translation_repository.dart`
- Create: `joysong-flutter/lib/core/translation/api_translation_repository.dart`
- Create: `joysong-flutter/lib/core/translation/translation.dart`
- Create: `joysong-flutter/test/core/translation/api_translation_repository_test.dart`
- Modify: `joysong-flutter/lib/features/social/domain/social_models.dart`
- Modify: `joysong-flutter/lib/features/social/data/social_remote_data_source.dart`
- Modify: `joysong-flutter/test/features/social/social_remote_data_source_test.dart`
- Modify: `joysong-flutter/lib/features/shell/presentation/app_shell.dart`
- Modify: `joysong-flutter/test/features/shell/app_shell_navigation_test.dart`

**Interfaces:**
- Produces: immutable `ContentTranslation`
- Produces: `TranslationRepository.translateText({text, targetLanguage, contentType})`
- Produces: `ApiTranslationRepository(ApiClient apiClient)`
- Preserves: existing social manual translation types and behavior
- Fixes: direct-message manual translation uses backend-supported `contentType: 'message'`

- [ ] **Step 1: Write the failing core API test**

```dart
test('posts the translation contract and decodes the response', () async {
  final client = _RecordingApiClient({
    'translatedText': 'Recovery is progressing well',
    'detectedLanguage': 'zh',
    'targetLanguage': 'en-US',
    'provider': 'qwen',
    'cached': true,
  });
  final result = await ApiTranslationRepository(client).translateText(
    text: '恢复得很好',
    targetLanguage: 'en-US',
    contentType: 'diary',
  );
  expect(client.path, 'translations');
  expect(client.body, {
    'text': '恢复得很好',
    'targetLanguage': 'en-US',
    'contentType': 'diary',
  });
  expect(result.translatedText, 'Recovery is progressing well');
  expect(result.cached, isTrue);
});
```

Add cases for empty text, more than 12,000 characters, and a response without `translatedText`.

Add a regression to `app_shell_navigation_test.dart` through the real private callback path. Extend `_AgentHandoffApiClient.get` so `dm/conversations/dm-human/messages` returns one Chinese `TEXT` message, and make `post('translations')` record the body and return all five translation response fields. Follow the existing consultant handoff into `DmThreadPage`, long-press the message, tap `翻译`, then assert the recorded `translations` request sends `contentType: 'message'`, never the unsupported legacy value `direct_message`.

- [ ] **Step 2: Run the core API test and verify RED**

```powershell
flutter test test/core/translation/api_translation_repository_test.dart test/features/shell/app_shell_navigation_test.dart
```

Expected: missing core translation symbols, and the direct-message regression observes the legacy unsupported type until Step 4.

- [ ] **Step 3: Implement the model and repository**

```dart
final class ContentTranslation {
  const ContentTranslation({
    required this.translatedText,
    required this.detectedLanguage,
    required this.targetLanguage,
    required this.provider,
    required this.cached,
  });
  final String translatedText;
  final String detectedLanguage;
  final String targetLanguage;
  final String provider;
  final bool cached;
}

abstract interface class TranslationRepository {
  Future<ContentTranslation> translateText({
    required String text,
    required String targetLanguage,
    required String contentType,
  });
}
```

`ApiTranslationRepository` trims and validates the source, posts to `translations`, maps all five fields, and throws `FormatException` for malformed responses.

- [ ] **Step 4: Delegate social translation HTTP to the shared repository**

Replace the social model with a compatibility alias:

```dart
import 'package:joysong_flutter/core/translation/content_translation.dart'
    as core_translation;
typedef ContentTranslation = core_translation.ContentTranslation;
```

Construct `ApiTranslationRepository` inside `ApiSocialRemoteDataSource`, delegate `translateText`, and remove its duplicate decoder. Retain `SocialTranslationRemoteDataSource` and `SocialTranslationRepository` for existing callers.

In `app_shell.dart`, change the existing direct-message manual translation request from `direct_message` to `message`. Keep its language-direction and failure behavior unchanged.

- [ ] **Step 5: Run core and social tests**

```powershell
flutter test test/core/translation/api_translation_repository_test.dart test/features/social/social_remote_data_source_test.dart test/features/social/social_repository_impl_test.dart test/features/social/social_controller_test.dart test/features/shell/app_shell_navigation_test.dart
```

Expected: all pass.

- [ ] **Step 6: Commit**

```powershell
git add -- joysong-flutter/lib/core/translation joysong-flutter/lib/features/social/domain/social_models.dart joysong-flutter/lib/features/social/data/social_remote_data_source.dart joysong-flutter/lib/features/shell/presentation/app_shell.dart joysong-flutter/test/core/translation joysong-flutter/test/features/social joysong-flutter/test/features/shell/app_shell_navigation_test.dart
git commit -m "refactor: share translation API client"
```

---

### Task 3: Automatic Translation Scheduler, Cache, and Eligibility

**Files:**
- Create: `joysong-flutter/lib/core/translation/auto_translation_request.dart`
- Create: `joysong-flutter/lib/core/translation/auto_translation_controller.dart`
- Create: `joysong-flutter/test/core/translation/auto_translation_controller_test.dart`
- Create: `joysong-flutter/test/core/translation/translation_test_fixtures.dart`
- Modify: `joysong-flutter/lib/core/translation/translation.dart`

**Interfaces:**
- Consumes: `TranslationRepository`
- Produces: `AutoTranslationRequest`
- Produces: `AutoTranslationController.synchronize({enabled, authenticated, targetLanguage})`
- Produces: `Future<String> AutoTranslationController.translateOrSource(request)`
- Produces: `AutoTranslationController.dispose()`
- Produces: `bool containsChineseText(String value)`

- [ ] **Step 1: Write failing eligibility, fallback, and retry tests**

```dart
test('translates Chinese only while English auto mode is active', () async {
  final repository = _FakeTranslationRepository();
  final controller = AutoTranslationController(repository: repository)
    ..synchronize(
      enabled: true,
      authenticated: true,
      targetLanguage: 'en-US',
    );
  final translated = await controller.translateOrSource(
    const AutoTranslationRequest(
      contentType: 'article',
      contentId: 'article-1',
      field: 'title',
      sourceText: '术后护理',
    ),
  );
  final untouched = await controller.translateOrSource(
    const AutoTranslationRequest(
      contentType: 'article',
      contentId: 'article-1',
      field: 'author',
      sourceText: 'Alice 2026',
    ),
  );
  expect(translated, 'EN:术后护理');
  expect(untouched, 'Alice 2026');
  expect(repository.calls, hasLength(1));
});

test('failure returns source and remains retryable', () async {
  final repository = _FakeTranslationRepository()..failuresRemaining = 1;
  final controller = AutoTranslationController(repository: repository)
    ..synchronize(
      enabled: true,
      authenticated: true,
      targetLanguage: 'en-US',
    );
  const request = AutoTranslationRequest(
    contentType: 'project',
    contentId: 'project-1',
    field: 'description',
    sourceText: '项目说明',
  );
  expect(await controller.translateOrSource(request), '项目说明');
  expect(await controller.translateOrSource(request), 'EN:项目说明');
  expect(repository.calls, hasLength(2));
});
```

- [ ] **Step 2: Write failing dedupe, cache-key, concurrency, and stale tests**

Create `translation_test_fixtures.dart` with a reusable recording repository, controlled `Completer<ContentTranslation>` responses, call/active-count tracking, and configurable failures. Assert identical concurrent requests call the repository once; each difference in target language/type/ID/field/source calls separately; `maximumActiveCalls` equals 3; an LRU configured with two entries re-requests the least-recently-used key. Queue a fourth request behind three active calls, then separately disable, change target language, and set `authenticated: false`; each transition must prevent the queued old-generation request from ever calling the repository. Assert a source-only controller makes zero calls, and disposing the controller invalidates pending/queued results without publishing them.

```dart
test('disablement discards an old result', () async {
  final repository = _CompletingTranslationRepository();
  final controller = AutoTranslationController(repository: repository)
    ..synchronize(
      enabled: true,
      authenticated: true,
      targetLanguage: 'en-US',
    );
  const request = AutoTranslationRequest(
    contentType: 'institution',
    contentId: 'institution-1',
    field: 'description',
    sourceText: '机构介绍',
  );
  final pending = controller.translateOrSource(request);
  controller.synchronize(
    enabled: false,
    authenticated: true,
    targetLanguage: 'en-US',
  );
  repository.completeNext('Institution introduction');
  expect(await pending, '机构介绍');
});
```

- [ ] **Step 3: Run the controller test and verify RED**

```powershell
flutter test test/core/translation/auto_translation_controller_test.dart
```

Expected: missing request/controller symbols.

- [ ] **Step 4: Implement request identity and controller**

```dart
typedef TranslationValidator = bool Function(
  String source,
  String translated,
);

final class AutoTranslationRequest {
  const AutoTranslationRequest({
    required this.contentType,
    required this.contentId,
    required this.field,
    required this.sourceText,
    this.validator,
  });
  final String contentType;
  final String contentId;
  final String field;
  final String sourceText;
  final TranslationValidator? validator;
}
```

Use `RegExp(r'[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]')`. Implement a FIFO queue, active count capped by `maxConcurrent`, in-flight map, and `LinkedHashMap` LRU cache. Constructor defaults:

```dart
AutoTranslationController({
  TranslationRepository? repository,
  int maxConcurrent = 3,
  int maxCacheEntries = 500,
});
```

`synchronize` increments a generation when enablement, authentication, or target language changes. Every queued entry captures that generation and rechecks generation, enablement, authentication, repository availability, and Chinese eligibility immediately before invoking the repository. Ineligible stale queue entries resolve to source without an HTTP call. `dispose` marks the controller inactive, invalidates its generation, and drains queued work to source values; already-running calls may finish but cannot publish or populate cache. A stale or invalid completed result returns source and is not cached.

- [ ] **Step 5: Run the Step 3 command and verify GREEN**

Expected: eligibility, fallback, retry, dedupe, cache, concurrency, and stale tests all pass.

- [ ] **Step 6: Commit**

```powershell
git add -- joysong-flutter/lib/core/translation joysong-flutter/test/core/translation
git commit -m "feat: add automatic translation scheduler"
```

---

### Task 4: Translation Scope, Reusable Builders, and App Wiring

**Files:**
- Create: `joysong-flutter/lib/core/translation/auto_translation_scope.dart`
- Create: `joysong-flutter/lib/core/translation/auto_translation_builder.dart`
- Create: `joysong-flutter/test/core/translation/auto_translation_builder_test.dart`
- Modify: `joysong-flutter/lib/core/translation/translation.dart`
- Modify: `joysong-flutter/lib/app/app.dart`
- Modify: `joysong-flutter/test/widget_test.dart`

**Interfaces:**
- Consumes: Task 1 settings and Task 3 controller
- Produces: `AutoTranslationScope.maybeOf(BuildContext)`
- Produces: `AutoTranslationBuilder(request, builder)`
- Produces: `AutoTranslatedText(request, ...)`
- Produces: optional `JoysongApp.translationRepository` injection

- [ ] **Step 1: Write failing progressive-render and lifecycle tests**

Pump an active scope with a controlled fake repository:

```dart
expect(find.text('项目说明'), findsOneWidget);
repository.completeNext('Project description');
await tester.pump();
expect(find.text('Project description'), findsOneWidget);
expect(find.text('项目说明'), findsNothing);
```

Add cases proving disabled scope makes zero calls, source changes schedule a new key, disabling restores source immediately, and completion after disposal produces no Flutter error.

- [ ] **Step 2: Run the builder test and verify RED**

```powershell
flutter test test/core/translation/auto_translation_builder_test.dart
```

Expected: missing scope/builder symbols.

- [ ] **Step 3: Implement the scope and builder contracts**

```dart
final class AutoTranslationScope extends InheritedWidget {
  const AutoTranslationScope({
    required this.controller,
    required this.enabled,
    required this.targetLanguage,
    required super.child,
    super.key,
  });
  final AutoTranslationController controller;
  final bool enabled;
  final String targetLanguage;

  static AutoTranslationScope? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<AutoTranslationScope>();

  @override
  bool updateShouldNotify(AutoTranslationScope oldWidget) =>
      controller != oldWidget.controller ||
      enabled != oldWidget.enabled ||
      targetLanguage != oldWidget.targetLanguage;
}

final class AutoTranslationBuilder extends StatefulWidget {
  const AutoTranslationBuilder({
    required this.request,
    required this.builder,
    super.key,
  });
  final AutoTranslationRequest request;
  final Widget Function(BuildContext context, String visibleText) builder;
}
```

Initialize visible text to source. React in `didChangeDependencies` and `didUpdateWidget`, guard completion with a local monotonically increasing token, and check `mounted`. `AutoTranslatedText` supports `style`, `maxLines`, `overflow`, `textAlign`, and `semanticsLabel`.

- [ ] **Step 4: Wire one shared controller into `JoysongApp`**

Add:

```dart
final TranslationRepository? translationRepository;
```

After `_createAuthRepository` establishes `_apiClient`, build the controller from the injected repository or `ApiTranslationRepository(_apiClient!)`. A null client creates a source-only controller. Add a `widget_test.dart` case that injects a repository plus a restored authenticated session and English/enabled preferences and proves the root scope is active. Its unauthenticated, Chinese, and disabled variants must make zero calls, and logout must restore translated builders to source. Add `_authController` to the root `Listenable.merge`. In the root `AnimatedBuilder`:

```dart
final consentEnabled = _localeController.language == AppLanguage.english &&
    _settingsController.aiTranslationEnabled;
_autoTranslationController.synchronize(
  enabled: consentEnabled,
  authenticated: _authController.status == AuthStatus.authenticated,
  targetLanguage: 'en-US',
);
```

Wrap `MaterialApp` with `AutoTranslationScope`, setting `enabled` to `consentEnabled && _authController.status == AuthStatus.authenticated`, and dispose the controller with the app.

- [ ] **Step 5: Run builder and startup regression tests**

```powershell
flutter test test/core/translation/auto_translation_builder_test.dart test/core/localization/app_locale_controller_test.dart test/features/settings/presentation/settings_page_test.dart test/features/shell/app_shell_navigation_test.dart test/widget_test.dart
```

Expected: all pass with no post-dispose errors.

- [ ] **Step 6: Commit**

```powershell
git add -- joysong-flutter/lib/core/translation joysong-flutter/lib/app/app.dart joysong-flutter/test/core/translation joysong-flutter/test/widget_test.dart
git commit -m "feat: wire shared automatic translation"
```

---

### Task 5: Home Consumer Content, Including Doctor Cards

**Files:**
- Modify: `joysong-flutter/lib/features/home/presentation/home_page.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/discover_content_card.dart`
- Create: `joysong-flutter/test/features/home/home_auto_translation_test.dart`

**Interfaces:**
- Consumes: `AutoTranslatedText` and `AutoTranslationRequest`
- Produces: stable Home keys from `HomeSectionKind.name`, content ID, and field
- Produces: `DiaryPreviewCard.enableAutoTranslation` and `DiaryPreviewRail.enableAutoTranslation`, both default `false`

- [ ] **Step 1: Write the failing active Home test**

Build a feed with one item for every section. Render under an active scope with literal fake translations. Assert source appears first, complete requests, then assert translated banner, project, article, diary, institution, and doctor-professional fields. The Home diary caller must explicitly pass `enableAutoTranslation: true` to its shared preview card.

```dart
expect(find.text('王医生'), findsOneWidget); // name remains source
expect(find.text('Chief physician'), findsOneWidget);
expect(find.text('Laser treatment'), findsOneWidget);
```

- [ ] **Step 2: Write failing disabled, failure, and exclusion tests**

Also pump a Chinese `DiaryPreviewCard` and `DiaryPreviewRail` inside an active global scope without their explicit flag and assert zero calls; repeat with `enableAutoTranslation: true` and assert only title/content/project fields are requested, never author/date fields.

```dart
expect(repository.calls, isEmpty); // English with switch disabled
expect(find.text('项目说明'), findsOneWidget); // provider failure fallback
expect(repository.sourceTexts, isNot(contains('王医生')));
expect(repository.sourceTexts, isNot(contains(r'$1280')));
```

- [ ] **Step 3: Run Home tests and verify RED**

```powershell
flutter test test/features/home/home_models_controller_test.dart test/features/home/home_auto_translation_test.dart
```

Expected: English replacements are absent.

- [ ] **Step 4: Add a focused request helper and integrate every Home section**

```dart
AutoTranslationRequest _homeRequest(
  HomeContent item,
  String field,
  String source,
) => AutoTranslationRequest(
      contentType: switch (item.kind) {
        HomeSectionKind.banner => 'general',
        HomeSectionKind.hotProject ||
        HomeSectionKind.recommendedInstitutionProject => 'project',
        HomeSectionKind.expertArticle => 'article',
        HomeSectionKind.userDiary => 'diary',
        HomeSectionKind.institution => 'institution',
        HomeSectionKind.doctor => 'doctor',
      },
      contentId: '${item.kind.name}:${item.id}',
      field: field,
      sourceText: source,
    );
```

Translate eligible visible text only. Keep doctor name, authors, nicknames, prices, counters, dates, and ratings unchanged. Translate doctor professional title, specialties, and institution name.

Add `enableAutoTranslation = false` to `DiaryPreviewCard` and `DiaryPreviewRail`; the rail passes its value to every card. When false, shared diary previews render source and register no requests even inside an active global scope. Home explicitly passes `true`; Discover, Social, and consumer detail callers are enabled in their later tasks, while professional shared callers retain the default.

- [ ] **Step 5: Run the Step 3 command and verify GREEN**

Expected: all pass and excluded values produce zero calls.

- [ ] **Step 6: Commit**

```powershell
git add -- joysong-flutter/lib/features/home/presentation/home_page.dart joysong-flutter/lib/features/discover/presentation/discover_content_card.dart joysong-flutter/test/features/home
git commit -m "feat: auto translate Home consumer content"
```

---

### Task 6: Discover Consumer Cards

**Files:**
- Modify: `joysong-flutter/lib/features/discover/presentation/discover_content_card.dart`
- Create: `joysong-flutter/test/features/discover/discover_content_card_auto_translation_test.dart`

**Interfaces:**
- Consumes: shared automatic translation builders
- Produces: Discover keys from type, item ID, and visible field

- [ ] **Step 1: Write failing table-driven card tests**

Pump representative project, institution, doctor, diary, and article cards under an active scope. Verify eligible title, description, category, address, specialty, and tag values are replaced. For doctor:

```dart
expect(find.text('李医生'), findsOneWidget);
expect(find.text('Attending physician'), findsOneWidget);
expect(repository.sourceTexts, isNot(contains('李医生')));
```

Assert article/diary author names and publish dates are not submitted.

- [ ] **Step 2: Run the card test and verify RED**

```powershell
flutter test test/features/discover/discover_content_card_auto_translation_test.dart
```

Expected: cards remain source-only.

- [ ] **Step 3: Refactor private card helpers to accept request metadata**

```dart
AutoTranslationRequest _discoverRequest(
  DiscoverItem item,
  String field,
  String source,
) => AutoTranslationRequest(
      contentType: item.type == DiscoverContentType.all
          ? 'general'
          : item.type.name,
      contentId: '${item.type.name}:${item.id}',
      field: field,
      sourceText: source,
    );
```

Replace `_Title` and `_Muted` call sites only when fields are eligible. Keep doctor names, person names, `_Rating`, `_Counter`, money, dates, and numeric metadata unchanged. Translate tags only when Chinese detection succeeds. The consumer-only `DiscoverContentCard` path explicitly passes `enableAutoTranslation: true` to any shared `DiaryPreviewCard`; never infer the shared flag from the global scope alone.

- [ ] **Step 4: Run Discover model and card tests**

```powershell
flutter test test/features/discover/discover_models_controller_test.dart test/features/discover/discover_content_card_auto_translation_test.dart
```

Expected: all pass.

- [ ] **Step 5: Commit**

```powershell
git add -- joysong-flutter/lib/features/discover/presentation/discover_content_card.dart joysong-flutter/test/features/discover/discover_content_card_auto_translation_test.dart
git commit -m "feat: auto translate Discover cards"
```

---

### Task 7: Article and Project Details with Rich-Text Protection

**Files:**
- Create: `joysong-flutter/lib/core/translation/rich_translation_validator.dart`
- Create: `joysong-flutter/test/core/translation/rich_translation_validator_test.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/article_detail_view.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/catalog_project_detail_view.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/discover_page.dart`
- Create: `joysong-flutter/test/features/discover/article_project_auto_translation_test.dart`
- Modify: `joysong-flutter/test/features/professional_management/professional_readonly_catalog_test.dart`

**Interfaces:**
- Produces: `bool preservesRichContentStructure(String source, String translated)`
- Produces: `CatalogProjectDetailView.enableAutoTranslation`, default `false`
- Consumes: `AutoTranslationRequest.validator`
- Preserves: current `RichContentView` rendering and navigation

- [ ] **Step 1: Write failing structural validation tests**

```dart
test('accepts translated text with the same markup and image URLs', () {
  const source = '<p>术后护理</p><img src="https://cdn.test/a.jpg">';
  const translated =
      '<p>Post-treatment care</p><img src="https://cdn.test/a.jpg">';
  expect(preservesRichContentStructure(source, translated), isTrue);
});

test('rejects changed markup or image URLs', () {
  const source = '<p>术后护理</p><img src="https://cdn.test/a.jpg">';
  expect(
    preservesRichContentStructure(source, '<p>Care<img src="x">'),
    isFalse,
  );
});
```

Validate paragraph, list, link, emphasis, and image structures. Require the normalized opening/closing/self-closing tag sequence and existing image/link URL identities to remain stable. Reject scripts, event-handler attributes, unsafe URL schemes, missing tags, and changed structure. Do not add HTML execution or rendering capabilities.

- [ ] **Step 2: Write failing article/project widget tests**

For article detail, assert title, category, summary, and body become English while the author name is never requested. For project detail, exercise either `DiscoverDetailPage` or `CatalogProjectDetailView(enableAutoTranslation: true)` and assert name, slogan, description, detail content, category/tags, institution name, address, and eligible diary-preview text become English while price, diary author, and doctor names remain unchanged. Return malformed rich markup once and assert source content remains visible and the same field remains retryable.

Extend `professional_readonly_catalog_test.dart` with an active global translation scope and assert opening the professional project catalog makes zero translation requests. Replace the selected fixture's project title/description and nested diary/review fields with Chinese first; the existing mostly-English fixture would otherwise make the zero-call assertion a false positive.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
flutter test test/core/translation/rich_translation_validator_test.dart test/features/discover/article_project_auto_translation_test.dart
```

Expected: missing validator and untranslated details.

- [ ] **Step 4: Implement validator and article integration**

Use ID `article:<item.id>` and fields `title`, `category`, `summary`, and `content`. Select `article_html` when the existing HTML detection matches, otherwise `article`. Attach `preservesRichContentStructure` to rich content and pass the accepted text to `RichContentView` through `AutoTranslationBuilder`. The validator must reject executable markup, event attributes, unsafe URLs, and any mutation of the source tag/URL structure before publishing translated HTML.

- [ ] **Step 5: Integrate the currently routed catalog project view**

Add `enableAutoTranslation = false` to `CatalogProjectDetailView`. Only the consumer `DiscoverDetailPage` passes `true`; leave professional catalog callers unchanged. Propagate the flag into `DiaryPreviewRail` (review propagation follows in Task 8). When enabled, use ID `project:<item.id>` and literal field names. Do not duplicate the integration in legacy `project_detail_view.dart`. Preserve doctor names and prices. Use `project_html` plus the validator for rich details. Apply builders to the consumer generic fallback in `discover_page.dart` for the same fields.

- [ ] **Step 6: Run focused tests and verify GREEN**

```powershell
flutter test test/core/translation/rich_translation_validator_test.dart test/features/discover/article_project_auto_translation_test.dart test/features/discover/discover_models_controller_test.dart test/features/professional_management/professional_readonly_catalog_test.dart
```

Expected: all pass.

- [ ] **Step 7: Commit**

```powershell
git add -- joysong-flutter/lib/core/translation/rich_translation_validator.dart joysong-flutter/lib/features/discover/presentation/article_detail_view.dart joysong-flutter/lib/features/discover/presentation/catalog_project_detail_view.dart joysong-flutter/lib/features/discover/presentation/discover_page.dart joysong-flutter/test/core/translation/rich_translation_validator_test.dart joysong-flutter/test/features/discover/article_project_auto_translation_test.dart joysong-flutter/test/features/professional_management/professional_readonly_catalog_test.dart
git commit -m "feat: auto translate article and project details"
```

---

### Task 8: Institution, Doctor, and Review Consumer Details

**Files:**
- Modify: `joysong-flutter/lib/features/discover/presentation/catalog_institution_detail_view.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/catalog_project_detail_view.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/doctor_detail_view.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/catalog_review_section.dart`
- Modify: `joysong-flutter/lib/features/discover/presentation/discover_page.dart`
- Modify: `joysong-flutter/test/features/discover/catalog_institution_detail_reviews_test.dart`
- Create: `joysong-flutter/test/features/discover/institution_doctor_auto_translation_test.dart`
- Modify: `joysong-flutter/test/features/professional_management/professional_readonly_catalog_test.dart`

**Interfaces:**
- Consumes: core automatic builders
- Produces: `CatalogInstitutionDetailView.enableAutoTranslation`, default `false`
- Produces: `CatalogReviewPreview.enableAutoTranslation` and `CatalogReviewCard.enableAutoTranslation`, both default `false`
- Preserves: consumer navigation and all Chinese professional management screens

- [ ] **Step 1: Write failing institution and review tests**

Extend the review fixture to accept an active scope and pass `CatalogInstitutionDetailView(enableAutoTranslation: true)` only for consumer translation cases; existing/default cases retain `false`. Assert institution name, address, description/public qualification text, consumer project names/descriptions, diary preview text, review body, project name, and tags become English. Force one field to fail and prove its Chinese source remains while unrelated fields still translate. Assert diary/review author names, date, rating, and image URLs remain unchanged.

Extend `professional_readonly_catalog_test.dart` with an active global translation scope and assert opening the professional institution catalog makes zero translation requests. Replace the selected institution and nested project, diary, and review fixture fields with Chinese before asserting, so Chinese eligibility is truly exercised. Cover both shared institution and project catalogs.

- [ ] **Step 2: Write failing doctor detail tests**

Use a Chinese institution/affiliation name, professional title, specialties, biography, credential description, related project text, diary text, and review text. Assert those translate while doctor name and certificate numbers do not:

```dart
expect(find.text('张医生'), findsOneWidget);
expect(find.text('Chief physician'), findsOneWidget);
expect(repository.sourceTexts, isNot(contains('张医生')));
expect(repository.sourceTexts, isNot(contains('CERT-2026-001')));
```

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
flutter test test/features/discover/catalog_institution_detail_reviews_test.dart test/features/discover/institution_doctor_auto_translation_test.dart test/features/professional_management/professional_readonly_catalog_test.dart
```

Expected: consumer detail strings remain Chinese.

- [ ] **Step 4: Integrate institution consumer fields**

Add `enableAutoTranslation = false` to `CatalogInstitutionDetailView` and propagate it to shared review, diary, and project preview children. Only the consumer `DiscoverDetailPage` passes `true`; professional catalog callers keep the default. When enabled, use ID `institution:<item.id>` and translate name, address, description, public qualification description, and consumer project-card text. Do not edit relationship, legal-representative, profile-editing, application, or project-management pages.

- [ ] **Step 5: Integrate doctor consumer fields**

Use ID `doctor:<item.id>`. Keep `name` as ordinary `Text`; translate the consumer-visible institution/affiliation name, professional title, specialties, biography, credential description, related project descriptions, diary excerpts, and reviews. Pass `true` from the consumer `DiscoverDetailPage` into the doctor's shared diary and review children. Preserve certificate IDs, counters, person names, and images.

- [ ] **Step 6: Integrate reusable review fields**

Give `CatalogReviewPreview` and `CatalogReviewCard` default-false flags plus stable owner type/ID and review ID metadata. Use backend-supported `contentType: 'comment'` and an identity such as `<owner-type>:<owner-id>:review:<review-id>`; translate `content`, `projectName`, and tag tokens with literal field names. Propagate the project/institution/doctor parent flag into previews, and pass `true` through the consumer-only full-reviews route in `discover_page.dart`. Keep reviewer name, timestamp, rating, and media unchanged.

- [ ] **Step 7: Run the Step 3 command and verify GREEN**

Expected: all pass.

- [ ] **Step 8: Commit**

```powershell
git add -- joysong-flutter/lib/features/discover/presentation/catalog_institution_detail_view.dart joysong-flutter/lib/features/discover/presentation/catalog_project_detail_view.dart joysong-flutter/lib/features/discover/presentation/doctor_detail_view.dart joysong-flutter/lib/features/discover/presentation/catalog_review_section.dart joysong-flutter/lib/features/discover/presentation/discover_page.dart joysong-flutter/test/features/discover/catalog_institution_detail_reviews_test.dart joysong-flutter/test/features/discover/institution_doctor_auto_translation_test.dart joysong-flutter/test/features/professional_management/professional_readonly_catalog_test.dart
git commit -m "feat: auto translate institution and doctor details"
```

---

### Task 9: Social and Direct-Message Automatic Mode with Manual Fallback

**Files:**
- Modify: `joysong-flutter/lib/features/social/presentation/social_page.dart`
- Modify: `joysong-flutter/lib/features/social/presentation/diary_detail_page.dart`
- Modify: `joysong-flutter/lib/features/messaging/presentation/messaging_pages.dart`
- Create: `joysong-flutter/test/features/social/social_page_auto_translation_test.dart`
- Modify: `joysong-flutter/test/features/social/diary_detail_page_test.dart`
- Modify: `joysong-flutter/test/features/messaging/dm_thread_page_test.dart`

**Interfaces:**
- Consumes: shared automatic controller/builders
- Preserves: manual translation callbacks and explicit failure feedback in both inactive and active automatic modes
- Produces: per-content source override while automatic mode is active

- [ ] **Step 1: Write failing diary/comment/reply automatic tests**

Under English plus enabled, assert the Social feed passes `enableAutoTranslation: true` to its shared diary preview and that diary title/body, a loaded comment, and a loaded reply progressively become English without tapping translation. Assert requests use `diary` or `comment` and exclude user names. Tap the existing action to show source locally, then tap again and assert cached automatic English returns without another HTTP call.

- [ ] **Step 2: Write failing direct-message automatic tests**

Extend `dm_thread_page_test.dart`: an incoming Chinese text message becomes English with `contentType: 'message'`; media-only messages create no calls; the existing context-menu translation action still works while automatic mode is disabled. Also force the automatic request to fail, then tap the manual action and assert the original callback still runs and retains its existing explicit success/failure feedback.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
flutter test test/features/social/social_page_auto_translation_test.dart test/features/social/diary_detail_page_test.dart test/features/messaging/dm_thread_page_test.dart
```

Expected: no automatic calls and source content remains.

- [ ] **Step 4: Integrate auto mode without replacing manual behavior**

Keep automatic rendering separate from the existing manual caches and callbacks. Use this visible-text precedence: explicit source override, currently displayed manual translation, valid automatic translation, then source. Maintain a local `Set<String>` of source overrides.

For each existing action:

- when automatic or manual English is visible, keep the current `显示原文 / Show original` behavior and add the key to the source override as needed;
- when source is visible and a valid automatic/manual translation is already cached, remove the override and show it without another request;
- when source is visible and no valid translation exists, including after an automatic failure, invoke the current `SocialController` method or messaging callback unchanged;
- preserve the current explicit manual success/failure state and snackbar behavior regardless of whether the shared automatic scope is active.

Automatic requests use `AutoTranslationBuilder`, stable IDs, and `diary`, `comment`, or `message`. They never emit failure snackbars. The Social feed explicitly enables its shared `DiaryPreviewCard`; shared operational callers remain default false.

- [ ] **Step 5: Run focused and manual regression tests**

```powershell
flutter test test/features/social/social_page_auto_translation_test.dart test/features/social/diary_detail_page_test.dart test/features/messaging/dm_thread_page_test.dart test/features/social/social_controller_test.dart test/features/messaging/messaging_controllers_test.dart
```

Expected: all pass.

- [ ] **Step 6: Commit**

```powershell
git add -- joysong-flutter/lib/features/social/presentation/social_page.dart joysong-flutter/lib/features/social/presentation/diary_detail_page.dart joysong-flutter/lib/features/messaging/presentation/messaging_pages.dart joysong-flutter/test/features/social/social_page_auto_translation_test.dart joysong-flutter/test/features/social/diary_detail_page_test.dart joysong-flutter/test/features/messaging/dm_thread_page_test.dart
git commit -m "feat: auto translate social consumer content"
```

---

### Task 10: Scope Audit and Final Verification

**Files:**
- Modify only when a focused verification exposes a consumer translation defect: files listed in Tasks 1–9
- Do not modify: `joysong-flutter/lib/features/identity/**`
- Do not modify: `joysong-flutter/lib/features/professional_management/**`
- Do not modify: `joysong-admin/**`

**Interfaces:**
- Consumes: Tasks 1–9
- Produces: verified consumer auto translation with operational modules unchanged

- [ ] **Step 1: Run the complete focused suite once**

```powershell
flutter test test/core/translation test/core/localization/app_locale_controller_test.dart test/features/settings/settings_preferences_store_test.dart test/features/settings/settings_controller_test.dart test/features/settings/presentation/settings_page_test.dart test/features/home/home_models_controller_test.dart test/features/home/home_auto_translation_test.dart test/features/discover/discover_models_controller_test.dart test/features/discover/discover_content_card_auto_translation_test.dart test/features/discover/article_project_auto_translation_test.dart test/features/discover/catalog_institution_detail_reviews_test.dart test/features/discover/institution_doctor_auto_translation_test.dart test/features/professional_management/professional_readonly_catalog_test.dart test/features/social/social_remote_data_source_test.dart test/features/social/social_repository_impl_test.dart test/features/social/social_controller_test.dart test/features/social/social_page_auto_translation_test.dart test/features/social/diary_detail_page_test.dart test/features/messaging/messaging_controllers_test.dart test/features/messaging/dm_thread_page_test.dart test/features/shell/app_shell_navigation_test.dart test/widget_test.dart
```

Expected: zero failures. The professional catalog test uses Chinese nested content under an active global scope and proves every modified shared component reachable from the operator catalog makes zero translation calls. After a failure, rerun only that case or its containing file.

- [ ] **Step 2: Run Flutter static analysis once**

```powershell
flutter analyze
```

Expected: zero analyzer errors. Record unrelated warnings instead of refactoring them.

- [ ] **Step 3: Verify excluded operational modules are untouched**

```powershell
git diff --name-only master...HEAD -- joysong-flutter/lib/features/identity joysong-flutter/lib/features/professional_management joysong-admin
```

Expected: no output. Runtime zero-call coverage applies to every excluded operator entry that renders a modified shared component (currently the professional project/institution catalog); the unchanged-file audit covers identity, institution project management, doctor service management, consultant management, and admin modules that do not consume the new shared builders. Inspect the full changed-file list and confirm none of those excluded files appears.

- [ ] **Step 4: Run at most one full Flutter test pass**

```powershell
flutter test
```

Stop after ten minutes if it is still running. Report completed and slow tests rather than retrying the full command.

- [ ] **Step 5: Inspect final diff quality**

```powershell
git diff --check master...HEAD
git status --short
git diff --stat master...HEAD
```

Expected: no whitespace errors, no uncommitted implementation files, and changes limited to design/plan docs, shared Flutter translation infrastructure, consumer pages, and tests.

- [ ] **Step 6: Commit a verification correction only when needed**

When Step 5 is already clean, do not create an empty commit. If a focused defect correction was required, stage only its production and test files and commit:

```powershell
git commit -m "fix: finalize consumer auto translation"
```
