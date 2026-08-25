import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/article_detail_view.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_project_detail_view.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_page.dart';
import 'package:joysong_flutter/features/discover/presentation/rich_content_view.dart';

import '../../core/translation/translation_test_fixtures.dart';

void main() {
  testWidgets(
    'article with a body translates only its baseline-visible rich fields',
    (tester) async {
      await _useTallSurface(tester);
      final repository = RecordingTranslationRepository()..holdResponses = true;
      final controller = _activeController(repository);
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _host(
          controller: controller,
          child: const ArticleDetailView(item: _richArticle),
        ),
      );
      await tester.pump();

      for (final source in const [
        '术后护理指南',
        '恢复护理',
        '赵教授',
        '2026-08-21',
      ]) {
        expect(find.text(source), findsWidgets, reason: source);
      }
      expect(
        tester.widget<RichContentView>(find.byType(RichContentView)).content,
        '<p>术后护理<strong>要点</strong></p>',
      );
      expect(find.text('未展示的文章摘要'), findsNothing);
      expect(
        _requestRecords(tester),
        const {
          ('article', 'article:article-1', 'title', '术后护理指南'),
          ('article', 'article:article-1', 'category', '恢复护理'),
          (
            'article_html',
            'article:article-1',
            'content',
            '<p>术后护理<strong>要点</strong></p>',
          ),
        },
      );

      await _completeAll(
        tester,
        repository,
        const {
          '术后护理指南': 'Post-treatment care guide',
          '恢复护理': 'Recovery care',
          '<p>术后护理<strong>要点</strong></p>':
              '<p>Post-treatment care <strong>essentials</strong></p>',
        },
      );

      expect(find.text('Post-treatment care guide'), findsOneWidget);
      expect(find.text('Recovery care'), findsOneWidget);
      expect(
        tester.widget<RichContentView>(find.byType(RichContentView)).content,
        '<p>Post-treatment care <strong>essentials</strong></p>',
      );
      expect(find.text('赵教授'), findsOneWidget);
      expect(find.text('2026-08-21'), findsOneWidget);
      _expectNeverRequested(repository, const [
        '未展示的文章摘要',
        '赵教授',
        '2026-08-21',
        'https://images.test/article.jpg',
        'article-1',
      ]);
    },
  );

  testWidgets('article without a body translates its visible summary fallback',
      (tester) async {
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    const article = DiscoverItem(
      id: 'article-2',
      type: DiscoverContentType.article,
      title: 'Care article',
      subtitle: '可见文章摘要',
      raw: {'summary': '可见文章摘要', 'authorName': '李医生'},
    );

    await tester.pumpWidget(
      _host(
        controller: controller,
        child: const ArticleDetailView(item: article),
      ),
    );
    await tester.pump();

    expect(
      tester.widget<RichContentView>(find.byType(RichContentView)).content,
      '可见文章摘要',
    );
    expect(
      _requestRecords(tester),
      const {
        ('article', 'article:article-2', 'title', 'Care article'),
        ('article', 'article:article-2', 'summary', '可见文章摘要'),
      },
    );
    repository.completeNext('Visible article summary');
    await _pumpTranslation(tester);

    expect(
      tester.widget<RichContentView>(find.byType(RichContentView)).content,
      'Visible article summary',
    );
    expect(find.text('李医生'), findsOneWidget);
    _expectNeverRequested(repository, const ['李医生']);
  });

  testWidgets(
      'article plain summary falls back when translation adds Markdown',
      (tester) async {
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    const article = DiscoverItem(
      id: 'article-plain-summary',
      type: DiscoverContentType.article,
      title: 'Care article',
      subtitle: '可见文章摘要',
      raw: {'summary': '可见文章摘要'},
    );

    await tester.pumpWidget(
      _host(
        controller: controller,
        child: const ArticleDetailView(item: article),
      ),
    );
    await tester.pump();
    repository.completeText(
      '可见文章摘要',
      '**Visible article summary**',
    );
    await _pumpTranslation(tester);

    expect(
      tester.widget<RichContentView>(find.byType(RichContentView)).content,
      '可见文章摘要',
    );
  });

  testWidgets(
      'deserialized author-only article renders fallback without translating it',
      (tester) async {
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    final article = DiscoverItem.fromJson(
      const {
        'id': 'article-author-only',
        'title': 'Care article',
        'authorName': '赵教授',
      },
      type: DiscoverContentType.article,
    );

    await tester.pumpWidget(
      _host(
        controller: controller,
        child: ArticleDetailView(item: article),
      ),
    );
    await tester.pump();

    expect(
      tester.widget<RichContentView>(find.byType(RichContentView)).content,
      '赵教授',
    );
    expect(
      _requestRecords(tester),
      const {
        ('article', 'article:article-author-only', 'title', 'Care article')
      },
    );
    expect(repository.calls, isEmpty);
  });

  testWidgets(
      'deserialized author-only project renders fallback without translating it',
      (tester) async {
    await _useTallSurface(tester);
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    final project = DiscoverItem.fromJson(
      const {
        'id': 'project-author-only',
        'name': 'Care project',
        'authorName': '项目作者',
      },
      type: DiscoverContentType.project,
    );

    await tester.pumpWidget(
      _host(
        controller: controller,
        child: CatalogProjectDetailView(
          item: project,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pump();

    expect(find.text('项目作者'), findsOneWidget);
    expect(
      _requestRecords(tester),
      const {
        ('project', 'project:project-author-only', 'name', 'Care project'),
      },
    );
    expect(repository.calls, isEmpty);
  });

  testWidgets('standalone project category stays hidden and unrequested',
      (tester) async {
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    final project = DiscoverItem.fromJson(
      const {
        'id': 'project-hidden-category',
        'name': 'Care project',
        'description': 'Care description',
        'category': '隐藏项目分类',
      },
      type: DiscoverContentType.project,
    );

    await tester.pumpWidget(
      _host(
        controller: controller,
        child: CatalogProjectDetailView(
          item: project,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pump();

    expect(find.text('隐藏项目分类'), findsNothing);
    expect(
      _requestRecords(tester).where((record) => record.$4 == '隐藏项目分类'),
      isEmpty,
    );
    _expectNeverRequested(repository, const ['隐藏项目分类']);
  });

  testWidgets(
      'project Markdown detail falls back when translation changes a URL',
      (tester) async {
    await _useTallSurface(tester);
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    const source = '[护理指南](https://care.test/guide)\n'
        '![护理图](https://cdn.test/care.jpg)';
    const project = DiscoverItem(
      id: 'project-markdown',
      type: DiscoverContentType.project,
      title: 'Care project',
      subtitle: 'Care description',
      raw: {
        'project': {
          'id': 'project-markdown',
          'name': 'Care project',
          'description': 'Care description',
          'detailContent': source,
        },
      },
    );

    await tester.pumpWidget(
      _host(
        controller: controller,
        child: const CatalogProjectDetailView(
          item: project,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.text('View more information'));
    await tester.pump();
    repository.completeText(
      source,
      '[Care guide](https://evil.test/guide)\n'
      '![Care image](https://cdn.test/care.jpg)',
    );
    await _pumpTranslation(tester);

    expect(
      tester.widget<RichContentView>(find.byType(RichContentView)).content,
      source,
    );
  });

  testWidgets(
    'consumer project route translates exact visible fields and retries rejected rich content',
    (tester) async {
      await _useTallSurface(tester);
      final repository = RecordingTranslationRepository()..holdResponses = true;
      final controller = _activeController(repository);
      addTearDown(controller.dispose);
      final discoverRepository = _DetailRepository(_consumerProject);

      await tester.pumpWidget(
        _host(
          controller: controller,
          child: DiscoverDetailPage(
            repository: discoverRepository,
            type: DiscoverContentType.project,
            id: _consumerProject.id,
            initialItem: _consumerProject,
          ),
        ),
      );
      await tester.pump();

      for (final source in const [
        '水光焕肤',
        '深层补水',
        '改善干燥肤质',
        '皮肤管理',
        '补水护理',
        '悦美医疗美容医院',
        '上海市 · 静安路',
        '恢复日记',
        '恢复过程顺利',
        '小美',
        '2026-08-20',
      ]) {
        expect(find.text(source), findsWidgets, reason: source);
      }
      expect(find.text(r'$1280'), findsWidgets);

      await tester.tap(find.text('View more information'));
      await tester.pump();
      expect(
        tester.widget<RichContentView>(find.byType(RichContentView)).content,
        '<p>项目详情<strong>说明</strong></p>',
      );

      expect(
        _requestRecords(tester),
        const {
          ('project', 'project:project-1', 'name', '水光焕肤'),
          ('project', 'project:project-1', 'slogan', '深层补水'),
          ('project', 'project:project-1', 'description', '改善干燥肤质'),
          ('project', 'project:project-1', 'category', '皮肤管理'),
          ('project', 'project:project-1', 'tags', '补水护理'),
          (
            'project',
            'project:project-1',
            'institutionName',
            '悦美医疗美容医院',
          ),
          ('project', 'project:project-1', 'address', '上海市 · 静安路'),
          (
            'project_html',
            'project:project-1',
            'content',
            '<p>项目详情<strong>说明</strong></p>',
          ),
          ('diary', 'diary:diary-1', 'title', '恢复日记'),
          ('diary', 'diary:diary-1', 'content', '恢复过程顺利'),
          ('diary', 'diary:diary-1', 'projectName', '水光焕肤'),
        },
      );

      const translations = {
        '水光焕肤': 'Hydra Care',
        '深层补水': 'Deep hydration',
        '改善干燥肤质': 'Improves dry skin texture',
        '皮肤管理': 'Skin care',
        '补水护理': 'Hydration care',
        '悦美医疗美容医院': 'Yuemei Hospital',
        '上海市 · 静安路': 'Shanghai · Jing an Road',
        '静安路': 'Jing an Road',
        '恢复日记': 'Recovery diary',
        '恢复过程顺利': 'Recovery went smoothly',
        '<p>项目详情<strong>说明</strong></p>':
            '<p>Project detail<strong>description</p>',
      };
      await _completeAll(tester, repository, translations);

      expect(
        tester.widget<RichContentView>(find.byType(RichContentView)).content,
        '<p>项目详情<strong>说明</strong></p>',
      );
      expect(find.text('Hydra Care'), findsWidgets);
      expect(find.text('Deep hydration'), findsOneWidget);
      expect(find.text('Improves dry skin texture'), findsOneWidget);
      expect(find.text('Skin care'), findsOneWidget);
      expect(find.text('Hydration care'), findsOneWidget);
      expect(find.text('Yuemei Hospital'), findsWidgets);
      expect(find.text('Shanghai · Jing an Road'), findsOneWidget);
      expect(find.text('Recovery diary'), findsOneWidget);
      expect(find.text('Recovery went smoothly'), findsOneWidget);
      for (final retained in const [
        '小美',
        '2026-08-20',
      ]) {
        expect(find.text(retained), findsWidgets, reason: retained);
      }
      expect(find.text(r'$1280'), findsWidgets);
      _expectNeverRequested(repository, const [
        '小美',
        '2026-08-20',
        '王医生',
        '1280',
        '4.8',
        '19',
        '12',
        '5',
        '3',
        '021-12345678',
        'https://images.test/project.jpg',
        'https://images.test/diary.jpg',
        'project-1',
        'diary-1',
      ]);

      Navigator.of(tester.element(find.text('View more information'))).pop();
      await tester.pumpAndSettle();
      await tester.tap(find.text('View more information'));
      await tester.pump();
      expect(
        repository.calls
            .where((call) => call.text == '<p>项目详情<strong>说明</strong></p>')
            .length,
        2,
      );
      repository.completeNext(
        '<p>Project detail <strong>description</strong></p>',
      );
      await _pumpTranslation(tester);

      expect(
        tester.widget<RichContentView>(find.byType(RichContentView)).content,
        '<p>Project detail <strong>description</strong></p>',
      );
    },
  );

  testWidgets(
    'consumer project all-diaries route shows source then translates unique diary fields',
    (tester) async {
      await _useTallSurface(tester);
      final repository = RecordingTranslationRepository()..holdResponses = true;
      final controller = _activeController(repository);
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _host(
          controller: controller,
          child: DiscoverDetailPage(
            repository: const _DetailRepository(_projectWithSixDiaries),
            type: DiscoverContentType.project,
            id: _projectWithSixDiaries.id,
            initialItem: _projectWithSixDiaries,
          ),
        ),
      );
      await tester.pump();

      expect(repository.calls, isEmpty);
      expect(find.text('全部日记标题'), findsNothing);

      await tester.tap(find.text('All (6)'));
      await tester.pumpAndSettle();

      for (final source in const ['全部日记标题', '全部日记正文', '全部日记项目']) {
        expect(find.text(source), findsOneWidget, reason: source);
      }
      expect(
        _requestRecords(tester).where(
          (request) => request.$2 == 'diary:all-diary-6',
        ),
        const {
          ('diary', 'diary:all-diary-6', 'title', '全部日记标题'),
          ('diary', 'diary:all-diary-6', 'content', '全部日记正文'),
          ('diary', 'diary:all-diary-6', 'projectName', '全部日记项目'),
        },
      );
      expect(
        repository.calls.map((call) => call.text).toSet(),
        const {'全部日记标题', '全部日记正文', '全部日记项目'},
      );

      await _completeAll(tester, repository, const {
        '全部日记标题': 'All diary title',
        '全部日记正文': 'All diary content',
        '全部日记项目': 'All diary project',
      });

      expect(find.text('All diary title'), findsOneWidget);
      expect(find.text('All diary content'), findsOneWidget);
      expect(find.text('All diary project'), findsOneWidget);
    },
  );

  testWidgets(
    'all-diaries list filters unsafe ids and preserves failed owner across reorder',
    (tester) async {
      await _useTallSurface(tester);
      final repository = RecordingTranslationRepository()..holdResponses = true;
      final controller = AutoTranslationController(
        repository: repository,
        maxConcurrent: 30,
      );
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _host(
          controller: controller,
          child: DiscoverDetailPage(
            repository: const _DetailRepository(_projectWithUnsafeDiaryIds),
            type: DiscoverContentType.project,
            id: _projectWithUnsafeDiaryIds.id,
            initialItem: _projectWithUnsafeDiaryIds,
          ),
        ),
      );
      await tester.pump();
      expect(repository.calls, isEmpty);

      controller.synchronize(
        enabled: true,
        authenticated: true,
        targetLanguage: 'en-US',
      );
      await tester.tap(find.text('All (4)'));
      await tester.pumpAndSettle();

      for (final source in const ['唯一全部日记', '空ID全部日记', '重复全部日记甲', '重复全部日记乙']) {
        expect(find.text(source), findsOneWidget, reason: source);
      }
      expect(
        repository.calls.map((call) => call.text).toList(growable: false),
        const ['唯一全部日记'],
      );

      repository.failNext();
      await _pumpTranslation(tester);
      final beforeReorder = tester.getTopLeft(find.text('唯一全部日记')).dy;
      final allPageFinder = find.byWidgetPredicate(
        (widget) => widget.runtimeType.toString() == '_AllRelatedContentPage',
      );
      final dynamic allPage = tester.widget(allPageFinder);
      final List<dynamic> diaries = allPage.groups['diaries'] as List<dynamic>;
      final moved = diaries.removeAt(0);
      diaries.insert(1, moved);
      tester.element(allPageFinder).markNeedsBuild();
      await tester.pump();
      await tester.pump();

      expect(
        tester.getTopLeft(find.text('唯一全部日记')).dy,
        greaterThan(beforeReorder),
      );
      expect(
        repository.calls.map((call) => call.text).toList(growable: false),
        const ['唯一全部日记'],
      );
      expect(repository.pendingCount, 0);
    },
  );

  testWidgets('consumer generic fallback translates only matched visible text',
      (tester) async {
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    const item = DiscoverItem(
      id: 'fallback-1',
      type: DiscoverContentType.all,
      title: '综合内容标题',
      subtitle: '综合内容说明',
      meta: '2026-08-24',
      raw: {
        'title': '综合内容标题',
        'description': '综合内容说明',
        'publishDate': '2026-08-24',
      },
    );

    await tester.pumpWidget(
      _host(
        controller: controller,
        child: DiscoverDetailPage(
          repository: _DetailRepository(item),
          type: item.type,
          id: item.id,
          initialItem: item,
        ),
      ),
    );
    await tester.pump();

    expect(find.text('综合内容标题'), findsOneWidget);
    expect(find.text('综合内容说明'), findsOneWidget);
    expect(find.text('2026-08-24'), findsOneWidget);
    expect(
      _requestRecords(tester),
      const {
        ('general', 'all:fallback-1', 'title', '综合内容标题'),
        ('general', 'all:fallback-1', 'description', '综合内容说明'),
      },
    );

    await _completeAll(tester, repository, const {
      '综合内容标题': 'General content title',
      '综合内容说明': 'General content description',
    });
    expect(find.text('General content title'), findsOneWidget);
    expect(find.text('General content description'), findsOneWidget);
    expect(find.text('2026-08-24'), findsOneWidget);
    _expectNeverRequested(repository, const ['2026-08-24']);
  });
}

typedef _RequestRecord = (String, String, String, String);

Set<_RequestRecord> _requestRecords(WidgetTester tester) => tester
    .widgetList<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
    .map(
      (builder) => (
        builder.request.contentType,
        builder.request.contentId,
        builder.request.field,
        builder.request.sourceText,
      ),
    )
    .toSet();

void _expectNeverRequested(
  RecordingTranslationRepository repository,
  List<String> excluded,
) {
  final requested = repository.calls.map((call) => call.text).toList();
  for (final source in excluded) {
    expect(requested, isNot(contains(source)), reason: source);
  }
}

Future<void> _completeAll(
  WidgetTester tester,
  RecordingTranslationRepository repository,
  Map<String, String> translations,
) async {
  var completed = 0;
  var safety = 0;
  while (completed < repository.calls.length || repository.activeCalls > 0) {
    while (completed < repository.calls.length) {
      final call = repository.calls[completed];
      repository.completeNext(translations[call.text]!);
      completed += 1;
      await _pumpTranslation(tester);
    }
    safety += 1;
    if (safety > translations.length * 3 + 3) {
      fail('translations did not drain');
    }
    await tester.pump();
  }
}

Future<void> _pumpTranslation(WidgetTester tester) async {
  await tester.pump();
  await tester.pump();
}

AutoTranslationController _activeController(
  TranslationRepository repository,
) =>
    AutoTranslationController(repository: repository, maxConcurrent: 30)
      ..synchronize(
        enabled: true,
        authenticated: true,
        targetLanguage: 'en-US',
      );

Widget _host({
  required AutoTranslationController controller,
  required Widget child,
}) {
  return AutoTranslationScope(
    controller: controller,
    enabled: true,
    targetLanguage: 'en-US',
    child: MaterialApp(
      locale: const Locale('en'),
      home: Scaffold(body: child),
    ),
  );
}

Future<void> _useTallSurface(WidgetTester tester) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1200, 2200);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetPhysicalSize);
}

final class _DetailRepository implements DiscoverRepository {
  const _DetailRepository(this.item);

  final DiscoverItem item;

  @override
  Future<DiscoverItem> loadDetail({
    required DiscoverContentType type,
    required String id,
  }) async =>
      item;

  @override
  Future<DiscoverFilterOptions> loadFilterOptions() {
    throw UnimplementedError();
  }

  @override
  Future<DiscoverPageResult> loadPage({
    required DiscoverContentType type,
    required int offset,
    required int limit,
    String query = '',
    List<String> categories = const [],
    List<String> cities = const [],
    List<String> tags = const [],
  }) {
    throw UnimplementedError();
  }
}

const _richArticle = DiscoverItem(
  id: 'article-1',
  type: DiscoverContentType.article,
  title: '术后护理指南',
  subtitle: '未展示的文章摘要',
  raw: {
    'summary': '未展示的文章摘要',
    'category': '恢复护理',
    'content': '<p>术后护理<strong>要点</strong></p>',
    'authorName': '赵教授',
    'publishDate': '2026-08-21',
    'coverImage': 'https://images.test/article.jpg',
  },
);

const _consumerProject = DiscoverItem(
  id: 'project-1',
  type: DiscoverContentType.project,
  title: '水光焕肤',
  subtitle: '改善干燥肤质',
  imageUrl: 'https://images.test/project.jpg',
  raw: {
    'project': {
      'id': 'project-1',
      'name': '水光焕肤',
      'slogan': '深层补水',
      'description': '改善干燥肤质',
      'detailContent': '<p>项目详情<strong>说明</strong></p>',
      'categoryTags': ['皮肤管理'],
      'tags': ['补水护理'],
      'coverImage': 'https://images.test/project.jpg',
    },
    'institutionProject': {
      'id': 'institution-project-1',
      'institutionId': 'institution-1',
      'projectId': 'project-1',
      'price': 1280,
      'rating': 4.8,
      'reviewCount': 19,
      'caseCount': 12,
    },
    'institution': {
      'id': 'institution-1',
      'name': '悦美医疗美容医院',
      'city': '上海市',
      'address': '静安路',
      'contactPhone': '021-12345678',
    },
    'diaries': [
      {
        'id': 'diary-1',
        'title': '恢复日记',
        'content': '恢复过程顺利',
        'projectName': '水光焕肤',
        'authorName': '小美',
        'publishDate': '2026-08-20',
        'imageUrls': ['https://images.test/diary.jpg'],
        'likeCount': 12,
        'favoriteCount': 5,
        'commentCount': 3,
      },
    ],
    'doctorName': '王医生',
  },
);

const _projectWithSixDiaries = DiscoverItem(
  id: 'project-all-diaries',
  type: DiscoverContentType.project,
  title: 'All diaries project',
  subtitle: 'English-only project description',
  raw: {
    'project': {
      'id': 'project-all-diaries',
      'name': 'All diaries project',
      'description': 'English-only project description',
    },
    'diaries': [
      {
        'id': 'all-diary-1',
        'title': 'Diary one',
        'content': 'English content one',
        'projectName': 'English project',
      },
      {
        'id': 'all-diary-2',
        'title': 'Diary two',
        'content': 'English content two',
        'projectName': 'English project',
      },
      {
        'id': 'all-diary-3',
        'title': 'Diary three',
        'content': 'English content three',
        'projectName': 'English project',
      },
      {
        'id': 'all-diary-4',
        'title': 'Diary four',
        'content': 'English content four',
        'projectName': 'English project',
      },
      {
        'id': 'all-diary-5',
        'title': 'Diary five',
        'content': 'English content five',
        'projectName': 'English project',
      },
      {
        'id': 'all-diary-6',
        'title': '全部日记标题',
        'content': '全部日记正文',
        'projectName': '全部日记项目',
      },
    ],
  },
);

const _projectWithUnsafeDiaryIds = DiscoverItem(
  id: 'project-unsafe-diary-ids',
  type: DiscoverContentType.project,
  title: 'Diary identity project',
  subtitle: 'English-only project description',
  raw: {
    'project': {
      'id': 'project-unsafe-diary-ids',
      'name': 'Diary identity project',
      'description': 'English-only project description',
    },
    'diaries': [
      {'id': 'unique-all-diary', 'title': '唯一全部日记'},
      {'id': ' ', 'title': '空ID全部日记'},
      {'id': 'duplicate-all-diary', 'title': '重复全部日记甲'},
      {'diaryId': 'duplicate-all-diary', 'title': '重复全部日记乙'},
    ],
  },
);
