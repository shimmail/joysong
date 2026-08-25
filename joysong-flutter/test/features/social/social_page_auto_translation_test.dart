import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart'
    hide ContentTranslation;
import 'package:joysong_flutter/features/social/domain/social_repository.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';
import 'package:joysong_flutter/features/social/presentation/social_page.dart';

import '../../core/translation/translation_test_fixtures.dart';
import '../orders/order_test_fixtures.dart';

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

  testWidgets(
    'failed Social diary does not retry after a real prepend and reorder',
    (tester) async {
      final socialRepository = _SocialRepository([_diary]);
      final socialController = SocialController(socialRepository);
      final translations = _PendingTranslationRepository(
        const {
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

      translations.failSource('真实日记');
      translations.completeAll();
      await tester.pump();
      await tester.pump();
      expect(
        translations.sources.where((source) => source == '真实日记'),
        hasLength(1),
      );

      socialRepository.diaries = const [_latinDiary, _diary];
      await socialController.loadMyDiaries(refresh: true);
      await tester.pumpAndSettle();
      socialRepository.diaries = const [_diary, _latinDiary];
      await socialController.loadMyDiaries(refresh: true);
      await tester.pumpAndSettle();

      expect(find.text('真实日记'), findsOneWidget);
      expect(
        translations.sources.where((source) => source == '真实日记'),
        hasLength(1),
      );
    },
  );

  testWidgets('blank diary id stays source-only with zero automatic builders',
      (tester) async {
    final socialController = SocialController(_SocialRepository([_blankDiary]));
    final translations = _PendingTranslationRepository(const {});
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

    expect(find.text('空身份日记'), findsOneWidget);
    expect(find.text('空身份日记正文'), findsOneWidget);
    expect(find.text('空身份项目'), findsOneWidget);
    expect(find.byType(AutoTranslationBuilder), findsNothing);
    expect(translations.sources, isEmpty);
  });

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

  testWidgets(
    'diary order picker translates snapshots without doctor or order number',
    (tester) async {
      await _useTallSurface(tester);
      final socialController = SocialController(_SocialRepository());
      final ordersRepository = FakeOrdersRepository()..orders = [sampleOrder()];
      final translations = RecordingTranslationRepository()
        ..holdResponses = true;
      final autoController = _autoController(translations, active: true);
      addTearDown(socialController.dispose);
      addTearDown(autoController.dispose);

      await tester.pumpWidget(
        _editorHost(
          controller: autoController,
          child: DiaryEditorPage(
            controller: socialController,
            ordersRepository: ordersRepository,
            enableAutoTranslation: true,
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.enterText(
        find.widgetWithText(TextFormField, 'Title'),
        '中文日记标题',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Content'),
        '中文日记正文',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Tags (comma separated)'),
        '中文标签',
      );

      await _openOrderPicker(tester);

      expect(_mountedRequests(tester), const {
        ('project', 'order:order-1', 'projectName', '光子嫩肤'),
        ('institution', 'order:order-1', 'institutionName', '娇颜颂医疗美容'),
      });
      expect(find.text('张医生'), findsOneWidget);
      expect(find.text('JOY202608060001'), findsOneWidget);
      expect(
        translations.calls.map((call) => call.text),
        isNot(contains('张医生')),
      );
      expect(
        translations.calls.map((call) => call.text),
        isNot(contains('JOY202608060001')),
      );
      expect(
        translations.calls.any((call) => call.text.contains('order-1')),
        isFalse,
      );

      await tester.tap(find.text('光子嫩肤'));
      await tester.pumpAndSettle();

      expect(_mountedRequests(tester), const {
        ('project', 'order:order-1', 'projectName', '光子嫩肤'),
        ('institution', 'order:order-1', 'institutionName', '娇颜颂医疗美容'),
      });
      expect(
        tester
            .widget<TextFormField>(
              find.widgetWithText(TextFormField, 'Title'),
            )
            .controller!
            .text,
        '中文日记标题',
      );
      expect(
        tester
            .widget<TextFormField>(
              find.widgetWithText(TextFormField, 'Content'),
            )
            .controller!
            .text,
        '中文日记正文',
      );
      expect(
        tester
            .widget<TextFormField>(
              find.widgetWithText(TextFormField, 'Tags (comma separated)'),
            )
            .controller!
            .text,
        '中文标签',
      );
      for (final source in const ['中文日记标题', '中文日记正文', '中文标签']) {
        expect(
          translations.calls.map((call) => call.text),
          isNot(contains(source)),
          reason: source,
        );
      }
    },
  );

  testWidgets(
      'diary association summary translates project and institution only',
      (tester) async {
    final socialController = SocialController(_SocialRepository());
    final translations = RecordingTranslationRepository()..holdResponses = true;
    final autoController = _autoController(translations, active: true);
    addTearDown(socialController.dispose);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _editorHost(
        controller: autoController,
        child: DiaryEditorPage(
          controller: socialController,
          diary: _manualAssociationDiary,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(_mountedRequests(tester), const {
      ('project', 'project:project-manual', 'name', '手动项目'),
      (
        'institution',
        'institution:institution-manual',
        'name',
        '手动机构',
      ),
    });
    expect(find.text('手动医生'), findsOneWidget);
    for (final source in const [
      '编辑器标题',
      '编辑器正文',
      '编辑器标签',
      '手动医生',
    ]) {
      expect(
        translations.calls.map((call) => call.text),
        isNot(contains(source)),
        reason: source,
      );
    }

    await tester.pumpWidget(const SizedBox());
    final missingIdSocialController = SocialController(_SocialRepository());
    final missingIdTranslations = RecordingTranslationRepository();
    final missingIdAutoController =
        _autoController(missingIdTranslations, active: true);
    addTearDown(missingIdSocialController.dispose);
    addTearDown(missingIdAutoController.dispose);

    await tester.pumpWidget(
      _editorHost(
        controller: missingIdAutoController,
        child: DiaryEditorPage(
          controller: missingIdSocialController,
          diary: _missingAssociationIdsDiary,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('无项目身份名称'), findsOneWidget);
    expect(find.text('无机构身份名称'), findsOneWidget);
    expect(_mountedRequests(tester), isEmpty);
    expect(missingIdTranslations.calls, isEmpty);
  });

  testWidgets('diary order picker defaults off under an active scope',
      (tester) async {
    await _useTallSurface(tester);
    final socialController = SocialController(_SocialRepository());
    final ordersRepository = FakeOrdersRepository()
      ..orders = [
        sampleOrder(
          projectName: '默认关闭项目',
          institutionName: '默认关闭机构',
        ),
      ];
    final translations = RecordingTranslationRepository();
    final autoController = _autoController(translations, active: true);
    addTearDown(socialController.dispose);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _editorHost(
        controller: autoController,
        child: DiaryEditorPage(
          controller: socialController,
          ordersRepository: ordersRepository,
        ),
      ),
    );
    await tester.pumpAndSettle();
    await _openOrderPicker(tester);

    expect(find.text('默认关闭项目'), findsOneWidget);
    expect(find.text('默认关闭机构'), findsOneWidget);
    expect(translations.calls, isEmpty);
    expect(find.byType(AutoTranslationBuilder), findsNothing);
  });

  testWidgets(
      'diary order picker keeps blank and duplicate order IDs source-only',
      (tester) async {
    await _useTallSurface(tester);
    final socialController = SocialController(_SocialRepository());
    final ordersRepository = FakeOrdersRepository()
      ..orders = [
        sampleOrder(
          id: ' ',
          projectName: '空身份项目',
          institutionName: '空身份机构',
        ),
        sampleOrder(
          id: 'duplicate-order',
          projectName: '重复项目甲',
          institutionName: '重复机构甲',
        ),
        sampleOrder(
          id: ' duplicate-order ',
          projectName: '重复项目乙',
          institutionName: '重复机构乙',
        ),
        sampleOrder(
          id: 'unique-order',
          projectName: '唯一项目',
          institutionName: '唯一机构',
        ),
      ];
    final translations = RecordingTranslationRepository()..holdResponses = true;
    final autoController = _autoController(translations, active: true);
    addTearDown(socialController.dispose);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _editorHost(
        controller: autoController,
        child: DiaryEditorPage(
          controller: socialController,
          ordersRepository: ordersRepository,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pumpAndSettle();
    await _openOrderPicker(tester);

    for (final text in const [
      '空身份项目',
      '空身份机构',
      '重复项目甲',
      '重复机构甲',
      '重复项目乙',
      '重复机构乙',
    ]) {
      expect(find.text(text), findsOneWidget, reason: text);
    }
    expect(_mountedRequests(tester), const {
      ('project', 'order:unique-order', 'projectName', '唯一项目'),
      (
        'institution',
        'order:unique-order',
        'institutionName',
        '唯一机构',
      ),
    });
    expect(
      translations.calls.map((call) => call.text),
      const ['唯一项目', '唯一机构'],
    );
    expect(
      find.byKey(const ValueKey<String>('diary-order:unique-order')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('diary-order:duplicate-order')),
      findsNothing,
    );
  });
}

typedef _RequestRecord = (String, String, String, String);

_RequestRecord _requestRecord(AutoTranslationRequest request) => (
      request.contentType,
      request.contentId,
      request.field,
      request.sourceText,
    );

Set<_RequestRecord> _mountedRequests(WidgetTester tester) => tester
    .widgetList<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
    .map((widget) => _requestRecord(widget.request))
    .toSet();

Future<void> _openOrderPicker(WidgetTester tester) async {
  final relatedOrder = find.text('Related order');
  await tester.ensureVisible(relatedOrder);
  await tester.tap(relatedOrder);
  await tester.pumpAndSettle();
}

Future<void> _useTallSurface(WidgetTester tester) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1200, 1800);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetPhysicalSize);
}

final class _SocialRepository extends Fake implements SocialRepository {
  _SocialRepository([List<Diary>? diaries])
      : diaries = diaries ?? const [_diary];

  List<Diary> diaries;

  @override
  Future<List<Diary>> getMyDiaries({int offset = 0, int limit = 50}) async =>
      diaries.skip(offset).take(limit).toList();
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

  void failSource(String source) {
    final index = _pending.indexWhere((pending) => pending.$1 == source);
    if (index < 0) throw StateError('No pending translation for $source');
    final pending = _pending.removeAt(index);
    pending.$2.completeError(StateError('translation failed for $source'));
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

Widget _editorHost({
  required AutoTranslationController controller,
  required Widget child,
}) =>
    AutoTranslationScope(
      controller: controller,
      enabled: true,
      targetLanguage: 'en-US',
      child: MaterialApp(
        locale: const Locale('en'),
        home: child,
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

const _latinDiary = Diary(
  id: 'diary-2',
  title: 'Latin diary',
  userId: 'author-2',
  authorName: 'Latin author',
  content: 'Latin content',
  projectName: 'Latin project',
  publishDate: '2026-08-25',
  images: [],
  tags: [],
  likeCount: 0,
  commentCount: 0,
  favoriteCount: 0,
  isLiked: false,
  status: 'published',
);

const _blankDiary = Diary(
  id: '',
  title: '空身份日记',
  userId: 'author-blank',
  authorName: '空身份作者',
  content: '空身份日记正文',
  projectName: '空身份项目',
  publishDate: '2026-08-25',
  images: [],
  tags: [],
  likeCount: 0,
  commentCount: 0,
  favoriteCount: 0,
  isLiked: false,
  status: 'published',
);

const _manualAssociationDiary = Diary(
  id: 'manual-diary',
  title: '编辑器标题',
  userId: 'author-manual',
  authorName: '日记作者',
  content: '编辑器正文',
  images: [],
  tags: ['编辑器标签'],
  likeCount: 0,
  commentCount: 0,
  favoriteCount: 0,
  isLiked: false,
  status: 'published',
  projectId: 'project-manual',
  doctorId: 'doctor-manual',
  institutionId: 'institution-manual',
  projectName: '手动项目',
  doctorName: '手动医生',
  institutionName: '手动机构',
);

const _missingAssociationIdsDiary = Diary(
  id: 'missing-association-ids-diary',
  title: 'Missing association ids',
  userId: 'author-missing-association-ids',
  authorName: 'Author',
  content: 'Source content',
  images: [],
  tags: [],
  likeCount: 0,
  commentCount: 0,
  favoriteCount: 0,
  isLiked: false,
  status: 'published',
  projectName: '无项目身份名称',
  institutionName: '无机构身份名称',
);
