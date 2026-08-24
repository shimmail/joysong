import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart'
    hide ContentTranslation;
import 'package:joysong_flutter/features/social/domain/social_repository.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';
import 'package:joysong_flutter/features/social/presentation/social_page.dart';

void main() {
  testWidgets(
    'Social diary opts in with exact fields and keeps stable requests on refresh',
    (tester) async {
      final socialRepository = _SocialRepository();
      final socialController = SocialController(socialRepository);
      final translations = _PendingTranslationRepository(
        const {
          '真实日记': 'A real diary',
          '来自数据库的日记正文': 'Diary content from the database',
          '水光焕肤': 'Hydrating skin treatment',
        },
      );
      final autoController = _autoController(translations, active: true);
      addTearDown(socialController.dispose);
      addTearDown(autoController.dispose);

      await tester.pumpWidget(
        _host(
          controller: autoController,
          child: SocialPage(controller: socialController),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('真实日记'), findsOneWidget);
      expect(find.text('来自数据库的日记正文'), findsOneWidget);
      expect(find.text('水光焕肤'), findsOneWidget);
      expect(find.text('日记作者'), findsOneWidget);
      expect(find.text('2026-08-24'), findsOneWidget);

      final requests = tester
          .widgetList<AutoTranslationBuilder>(
            find.byType(AutoTranslationBuilder),
          )
          .map((widget) => widget.request)
          .map(_requestRecord)
          .toSet();
      expect(
        requests,
        const {
          ('diary', 'diary:diary-1', 'title', '真实日记'),
          ('diary', 'diary:diary-1', 'content', '来自数据库的日记正文'),
          ('diary', 'diary:diary-1', 'projectName', '水光焕肤'),
        },
      );
      expect(translations.sources, hasLength(3));
      expect(translations.sources, isNot(contains('日记作者')));
      expect(translations.sources, isNot(contains('2026-08-24')));
      expect(translations.sources, isNot(contains('12')));
      expect(translations.sources, isNot(contains('7')));
      expect(translations.sources, isNot(contains('3')));

      translations.completeAll();
      await tester.pump();
      await tester.pump();

      expect(find.text('A real diary'), findsOneWidget);
      expect(find.text('Diary content from the database'), findsOneWidget);
      expect(find.text('Hydrating skin treatment'), findsOneWidget);
      expect(find.text('日记作者'), findsOneWidget);
      expect(find.text('2026-08-24'), findsOneWidget);

      await socialController.loadMyDiaries(refresh: true);
      await tester.pumpAndSettle();
      expect(translations.sources, hasLength(3));
    },
  );

  for (final state in const [
    (label: 'disabled scope', scopeEnabled: false, controllerActive: true),
    (label: 'inactive controller', scopeEnabled: true, controllerActive: false),
  ]) {
    testWidgets('Social ${state.label} keeps source and makes zero calls',
        (tester) async {
      final socialController = SocialController(_SocialRepository());
      final translations = _PendingTranslationRepository(const {});
      final autoController = _autoController(
        translations,
        active: state.controllerActive,
      );
      addTearDown(socialController.dispose);
      addTearDown(autoController.dispose);

      await tester.pumpWidget(
        _host(
          controller: autoController,
          enabled: state.scopeEnabled,
          child: SocialPage(controller: socialController),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('真实日记'), findsOneWidget);
      expect(find.text('来自数据库的日记正文'), findsOneWidget);
      expect(translations.sources, isEmpty);
    });
  }
}

typedef _RequestRecord = (String, String, String, String);

_RequestRecord _requestRecord(AutoTranslationRequest request) => (
      request.contentType,
      request.contentId,
      request.field,
      request.sourceText,
    );

final class _SocialRepository extends Fake implements SocialRepository {
  @override
  Future<List<Diary>> getMyDiaries({int offset = 0, int limit = 50}) async =>
      const [_diary];
}

final class _PendingTranslationRepository implements TranslationRepository {
  _PendingTranslationRepository(this.translations);

  final Map<String, String> translations;
  final List<String> sources = [];
  final List<(String, Completer<ContentTranslation>)> _pending = [];

  @override
  Future<ContentTranslation> translateText({
    required String text,
    required String targetLanguage,
    required String contentType,
  }) {
    sources.add(text);
    final completer = Completer<ContentTranslation>();
    _pending.add((text, completer));
    return completer.future;
  }

  void completeAll() {
    for (final pending in _pending.toList(growable: false)) {
      pending.$2.complete(
        ContentTranslation(
          translatedText: translations[pending.$1]!,
          detectedLanguage: 'zh',
          targetLanguage: 'en-US',
          provider: 'test',
          cached: false,
        ),
      );
    }
    _pending.clear();
  }
}

AutoTranslationController _autoController(
  TranslationRepository repository, {
  required bool active,
}) {
  final controller = AutoTranslationController(
    repository: repository,
    maxConcurrent: 10,
  );
  if (active) {
    controller.synchronize(
      enabled: true,
      authenticated: true,
      targetLanguage: 'en-US',
    );
  }
  return controller;
}

Widget _host({
  required AutoTranslationController controller,
  required Widget child,
  bool enabled = true,
}) =>
    MaterialApp(
      locale: const Locale('en'),
      home: AutoTranslationScope(
        controller: controller,
        enabled: enabled,
        targetLanguage: 'en-US',
        child: child,
      ),
    );

const _diary = Diary(
  id: 'diary-1',
  title: '真实日记',
  userId: 'author-1',
  authorName: '日记作者',
  content: '来自数据库的日记正文',
  projectName: '水光焕肤',
  publishDate: '2026-08-24',
  images: [],
  tags: [],
  likeCount: 12,
  commentCount: 3,
  favoriteCount: 7,
  isLiked: false,
  status: 'published',
);
