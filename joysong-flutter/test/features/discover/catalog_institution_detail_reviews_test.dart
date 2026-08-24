import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/optimized_network_image.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_institution_detail_view.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_project_detail_view.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_review_section.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_page.dart';

import '../../core/translation/translation_test_fixtures.dart';

void main() {
  testWidgets('shows complete institution review information in Chinese', (
    tester,
  ) async {
    await tester.pumpWidget(
      _testApp(
        locale: const Locale('zh'),
        reviews: const [
          {
            'id': 'review-1',
            'rating': 4,
            'content': '医生沟通耐心，服务流程也很清晰。',
            'tags': '耐心,专业',
            'images':
                'https://example.com/review-one.jpg,https://example.com/review-two.jpg',
            'userName': '王女士',
            'createdAt': '2026-08-06T12:34:00',
          },
        ],
      ),
    );

    await _scrollToReviews(tester);

    final card = find.byType(CatalogReviewCard);
    expect(card, findsOneWidget);
    expect(
      find.descendant(of: card, matching: find.text('★ 4.0')),
      findsOneWidget,
    );
    expect(find.text('医生沟通耐心，服务流程也很清晰。'), findsOneWidget);
    expect(find.text('耐心'), findsOneWidget);
    expect(find.text('专业'), findsOneWidget);
    expect(find.text('王女士'), findsOneWidget);
    expect(find.text('2026-08-06'), findsOneWidget);
    final imageList = find.descendant(
      of: card,
      matching: find.byWidgetPredicate(
        (widget) =>
            widget is ListView && widget.scrollDirection == Axis.horizontal,
      ),
    );
    expect(imageList, findsOneWidget);
    expect(
      tester.widget<ListView>(imageList).semanticChildCount,
      2,
    );
    expect(
      find.descendant(
        of: card,
        matching: find.byType(OptimizedNetworkImage),
      ),
      findsWidgets,
    );
  });

  testWidgets('shows the English empty state when there are no reviews', (
    tester,
  ) async {
    await tester.pumpWidget(
      _testApp(locale: const Locale('en'), reviews: const []),
    );

    await _scrollToReviews(tester);

    expect(find.text('Patient reviews'), findsOneWidget);
    expect(find.text('No reviews yet'), findsOneWidget);
  });

  testWidgets('shows English fallbacks for missing optional review text', (
    tester,
  ) async {
    await tester.pumpWidget(
      _testApp(
        locale: const Locale('en'),
        reviews: const [
          {
            'id': 'review-2',
            'rating': 5,
            'content': '',
            'tags': '',
            'images': '',
            'userName': '',
            'createdAt': '2026-08-07T09:00:00',
          },
        ],
      ),
    );

    await _scrollToReviews(tester);

    final card = find.byType(CatalogReviewCard);
    expect(find.text('No written review'), findsOneWidget);
    expect(find.text('Anonymous user'), findsOneWidget);
    expect(find.descendant(of: card, matching: find.text('★ 5.0')),
        findsOneWidget);
  });

  testWidgets(
      'consumer institution reviews translate stable fields and isolate one failure',
      (tester) async {
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      _testApp(
        locale: const Locale('en'),
        institutionName: 'Test institution',
        controller: controller,
        enableAutoTranslation: true,
        reviews: const [
          {
            'id': 'review-translate',
            'rating': 4,
            'content': '评价内容需要保留',
            'projectName': '水光项目',
            'tags': ['沟通耐心', '流程专业'],
            'userName': '王女士',
            'createdAt': '2026-08-06T12:34:00',
            'images': ['https://example.com/review.jpg'],
          },
        ],
      ),
    );
    await _scrollToReviews(tester);

    expect(find.text('评价内容需要保留'), findsOneWidget);
    expect(find.text('水光项目'), findsOneWidget);
    expect(find.text('沟通耐心'), findsOneWidget);
    expect(
      _requestRecords(tester),
      containsAll(const {
        (
          'comment',
          'institution:institution-1:review:review-translate',
          'content',
          '评价内容需要保留',
        ),
        (
          'comment',
          'institution:institution-1:review:review-translate',
          'projectName',
          '水光项目',
        ),
        (
          'comment',
          'institution:institution-1:review:review-translate',
          'tags:0',
          '沟通耐心',
        ),
        (
          'comment',
          'institution:institution-1:review:review-translate',
          'tags:1',
          '流程专业',
        ),
      }),
    );

    repository.failNext();
    await _pumpTranslation(tester);
    for (final translation in const [
      '水光项目|Hydration project',
      '沟通耐心|Patient communication',
      '流程专业|Professional process',
    ]) {
      final parts = translation.split('|');
      final call = repository.calls.firstWhere((call) => call.text == parts[0]);
      expect(call.contentType, 'comment');
      repository.completeNext(parts[1]);
      await _pumpTranslation(tester);
    }

    expect(find.text('评价内容需要保留'), findsOneWidget);
    expect(find.text('Hydration project'), findsOneWidget);
    expect(find.text('Patient communication'), findsOneWidget);
    expect(find.text('Professional process'), findsOneWidget);
    expect(find.text('王女士'), findsOneWidget);
    expect(find.text('2026-08-06'), findsOneWidget);
    _expectNeverRequested(repository, const [
      '王女士',
      '2026-08-06T12:34:00',
      '4',
      'https://example.com/review.jpg',
    ]);
  });

  testWidgets('reviews default off and missing stable IDs make zero requests',
      (tester) async {
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    const reviews = [
      {
        'rating': 5,
        'content': '无稳定标识的评价',
        'projectName': '无标识项目',
        'tags': ['无标识标签'],
      },
      {
        'id': 'service-name-review',
        'rating': 5,
        'content': 'English review',
        'serviceName': '王医生服务',
      },
    ];

    await tester.pumpWidget(
      _testApp(
        locale: const Locale('en'),
        institutionName: 'Test institution',
        controller: controller,
        reviews: reviews,
      ),
    );
    await _scrollToReviews(tester);
    expect(repository.calls, isEmpty);

    await tester.pumpWidget(
      _testApp(
        locale: const Locale('en'),
        institutionName: 'Test institution',
        controller: controller,
        enableAutoTranslation: true,
        reviews: reviews,
      ),
    );
    await _scrollToReviews(tester);
    expect(find.text('无稳定标识的评价'), findsOneWidget);
    expect(find.text('王医生服务'), findsOneWidget);
    expect(repository.calls, isEmpty);
  });

  testWidgets('consumer full review route keeps automatic translation enabled',
      (tester) async {
    await _useTallSurface(tester);
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    const item = DiscoverItem(
      id: 'institution-full-reviews',
      type: DiscoverContentType.institution,
      title: 'Institution',
      raw: {
        'institution': {
          'id': 'institution-full-reviews',
          'name': 'Institution',
        },
        'reviews': [
          {
            'id': 'review-full',
            'content': '全量评价内容',
            'rating': 5,
          },
        ],
      },
    );

    await tester.pumpWidget(
      AutoTranslationScope(
        controller: controller,
        enabled: true,
        targetLanguage: 'en-US',
        child: MaterialApp(
          locale: const Locale('en'),
          home: DiscoverDetailPage(
            repository: const _DetailRepository(item),
            type: item.type,
            id: item.id,
            initialItem: item,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('All (1)'));
    await tester.pumpAndSettle();

    expect(find.text('All reviews'), findsOneWidget);
    expect(find.text('en-US:全量评价内容'), findsOneWidget);
    expect(
      _requestRecords(tester),
      contains(const (
        'comment',
        'institution:institution-full-reviews:review:review-full',
        'content',
        '全量评价内容',
      )),
    );
  });

  testWidgets('consumer project preview opts reviews into translation',
      (tester) async {
    await _useTallSurface(tester);
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    const item = DiscoverItem(
      id: 'project-review-owner',
      type: DiscoverContentType.project,
      title: 'Project',
      raw: {
        'project': {'id': 'project-review-owner', 'name': 'Project'},
        'reviews': [
          {
            'id': 'project-review',
            'content': '项目评价内容',
            'rating': 5,
          },
        ],
      },
    );

    await tester.pumpWidget(
      AutoTranslationScope(
        controller: controller,
        enabled: true,
        targetLanguage: 'en-US',
        child: const MaterialApp(
          locale: Locale('en'),
          home: Scaffold(
            body: CatalogProjectDetailView(
              item: item,
              enableAutoTranslation: true,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('en-US:项目评价内容'), findsOneWidget);
    expect(
      _requestRecords(tester),
      contains(const (
        'comment',
        'project:project-review-owner:review:project-review',
        'content',
        '项目评价内容',
      )),
    );
  });

  testWidgets('duplicate review IDs keep independent preview state',
      (tester) async {
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      AutoTranslationScope(
        controller: controller,
        enabled: true,
        targetLanguage: 'en-US',
        child: const MaterialApp(
          locale: Locale('en'),
          home: Scaffold(
            body: CatalogReviewPreview(
              enableAutoTranslation: true,
              ownerType: 'doctor',
              ownerId: 'doctor-duplicate-preview',
              reviews: [
                {
                  'id': 'duplicate-review',
                  'content': '预览第一条评价',
                  'rating': 5,
                },
                {
                  'id': 'duplicate-review',
                  'content': '预览第二条评价',
                  'rating': 1,
                },
              ],
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.byType(CatalogReviewCard), findsNWidgets(2));
    expect(find.text('en-US:预览第一条评价'), findsOneWidget);
    expect(find.text('en-US:预览第二条评价'), findsOneWidget);
  });

  testWidgets('duplicate review IDs keep independent full-route state',
      (tester) async {
    await _useTallSurface(tester);
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    final reviews = <Map<String, Object?>>[
      {
        'id': 'duplicate-review',
        'content': '全量第一条评价',
        'rating': 5,
      },
      {
        'id': 'unique-before-navigation',
        'content': '全量第二条评价',
        'rating': 1,
      },
    ];
    final item = DiscoverItem(
      id: 'institution-duplicate-full',
      type: DiscoverContentType.institution,
      title: 'Institution',
      raw: {
        'institution': {
          'id': 'institution-duplicate-full',
          'name': 'Institution',
        },
        'reviews': reviews,
      },
    );

    await tester.pumpWidget(
      AutoTranslationScope(
        controller: controller,
        enabled: true,
        targetLanguage: 'en-US',
        child: MaterialApp(
          locale: const Locale('en'),
          home: DiscoverDetailPage(
            repository: _DetailRepository(item),
            type: item.type,
            id: item.id,
            initialItem: item,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    reviews[1]['id'] = 'duplicate-review';

    await tester.tap(find.text('All (2)'));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.text('All reviews'), findsOneWidget);
    expect(find.byType(CatalogReviewCard), findsNWidgets(2));
    expect(
      tester
          .widgetList<CatalogReviewCard>(find.byType(CatalogReviewCard))
          .map((card) => card.key)
          .toSet(),
      hasLength(2),
    );
    expect(find.text('en-US:全量第一条评价'), findsOneWidget);
    expect(find.text('en-US:全量第二条评价'), findsOneWidget);
  });

  testWidgets('blank review id falls back to reviewId in preview',
      (tester) async {
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      AutoTranslationScope(
        controller: controller,
        enabled: true,
        targetLanguage: 'en-US',
        child: const MaterialApp(
          locale: Locale('en'),
          home: Scaffold(
            body: CatalogReviewPreview(
              enableAutoTranslation: true,
              ownerType: 'institution',
              ownerId: 'institution-blank-preview',
              reviews: [
                {
                  'id': '',
                  'reviewId': 'review-1',
                  'content': '预览空标识评价',
                  'rating': 5,
                },
              ],
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('en-US:预览空标识评价'), findsOneWidget);
    expect(
      _requestRecords(tester),
      contains(const (
        'comment',
        'institution:institution-blank-preview:review:review-1',
        'content',
        '预览空标识评价',
      )),
    );
  });

  testWidgets('blank review id falls back to reviewId in direct card',
      (tester) async {
    final repository = RecordingTranslationRepository();
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      AutoTranslationScope(
        controller: controller,
        enabled: true,
        targetLanguage: 'en-US',
        child: const MaterialApp(
          locale: Locale('en'),
          home: Scaffold(
            body: CatalogReviewCard(
              enableAutoTranslation: true,
              ownerType: 'doctor',
              ownerId: 'doctor-blank-card',
              review: {
                'id': '',
                'reviewId': 'review-1',
                'content': '直接卡片空标识评价',
                'rating': 5,
              },
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('en-US:直接卡片空标识评价'), findsOneWidget);
    expect(
      _requestRecords(tester),
      contains(const (
        'comment',
        'doctor:doctor-blank-card:review:review-1',
        'content',
        '直接卡片空标识评价',
      )),
    );
  });

  testWidgets('full review filtering does not retry a failed moved review',
      (tester) async {
    await _useTallSurface(tester);
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);
    const item = DiscoverItem(
      id: 'institution-full-filter',
      type: DiscoverContentType.institution,
      title: 'Institution',
      raw: {
        'institution': {
          'id': 'institution-full-filter',
          'name': 'Institution',
        },
        'reviews': [
          {
            'id': 'positive-full-filter',
            'content': '全量好评内容',
            'rating': 5,
          },
          {
            'id': 'negative-full-filter',
            'content': '全量差评内容',
            'rating': 1,
          },
        ],
      },
    );

    await tester.pumpWidget(
      AutoTranslationScope(
        controller: controller,
        enabled: true,
        targetLanguage: 'en-US',
        child: MaterialApp(
          locale: const Locale('en'),
          home: DiscoverDetailPage(
            repository: const _DetailRepository(item),
            type: item.type,
            id: item.id,
            initialItem: item,
          ),
        ),
      ),
    );
    await tester.pump();
    expect(repository.calls.map((call) => call.text), [
      '全量好评内容',
      '全量差评内容',
    ]);
    repository.completeNext('Preview positive');
    await _pumpTranslation(tester);
    repository.completeNext('Preview negative');
    await _pumpTranslation(tester);
    controller.synchronize(
      enabled: false,
      authenticated: true,
      targetLanguage: 'en-US',
    );
    controller.synchronize(
      enabled: true,
      authenticated: true,
      targetLanguage: 'en-US',
    );

    await tester.tap(find.text('All (2)'));
    await tester.pumpAndSettle();
    expect(repository.calls, hasLength(4));
    expect(repository.calls[2].text, '全量好评内容');
    expect(repository.calls[3].text, '全量差评内容');
    repository.completeNext('Full positive');
    await _pumpTranslation(tester);
    repository.failNext();
    await _pumpTranslation(tester);
    final callsBeforeFilter = repository.calls.length;

    await tester.tap(find.text('Negative'));
    await _pumpTranslation(tester);

    expect(find.text('全量差评内容'), findsOneWidget);
    expect(repository.calls, hasLength(callsBeforeFilter));
  });

  testWidgets('review filtering preserves the matching review request state',
      (tester) async {
    final repository = RecordingTranslationRepository()..holdResponses = true;
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      AutoTranslationScope(
        controller: controller,
        enabled: true,
        targetLanguage: 'en-US',
        child: MaterialApp(
          locale: const Locale('en'),
          home: Scaffold(
            body: CatalogReviewPreview(
              enableAutoTranslation: true,
              ownerType: 'doctor',
              ownerId: 'doctor-filter',
              reviews: const [
                {
                  'id': 'positive-review',
                  'content': '好评内容',
                  'rating': 5,
                },
                {
                  'id': 'negative-review',
                  'content': '差评内容',
                  'rating': 1,
                },
              ],
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    expect(repository.calls.map((call) => call.text), [
      '好评内容',
      '差评内容',
    ]);
    repository.completeNext('Positive review');
    repository.failNext();
    await _pumpTranslation(tester);

    await tester.tap(find.text('Negative'));
    await _pumpTranslation(tester);

    expect(find.text('差评内容'), findsOneWidget);
    expect(find.text('Positive review'), findsNothing);
    expect(repository.calls, hasLength(2));
  });
}

Widget _testApp({
  required Locale locale,
  required List<Map<String, Object?>> reviews,
  String institutionName = '测试机构',
  AutoTranslationController? controller,
  bool enableAutoTranslation = false,
}) {
  final item = DiscoverItem(
    id: 'institution-1',
    type: DiscoverContentType.institution,
    title: institutionName,
    raw: {
      'institution': {
        'id': 'institution-1',
        'name': institutionName,
        'rating': 4.6,
        'reviewCount': 12,
      },
      'projects': const [],
      'diaries': const [],
      'reviews': reviews,
      'doctors': const [],
    },
  );
  final app = MaterialApp(
    locale: locale,
    supportedLocales: const [Locale('zh'), Locale('en')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    home: Scaffold(
      body: CatalogInstitutionDetailView(
        item: item,
        enableAutoTranslation: enableAutoTranslation,
      ),
    ),
  );
  if (controller == null) return app;
  return AutoTranslationScope(
    controller: controller,
    enabled: true,
    targetLanguage: 'en-US',
    child: app,
  );
}

Future<void> _scrollToReviews(WidgetTester tester) async {
  final scrollable = find.byType(CustomScrollView);
  for (var index = 0; index < 6; index++) {
    await tester.drag(scrollable, const Offset(0, -500));
    await tester.pump();
  }
  await tester.pump(const Duration(milliseconds: 400));
}

Future<void> _useTallSurface(WidgetTester tester) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1200, 4200);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetPhysicalSize);
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

AutoTranslationController _activeController(
  TranslationRepository repository,
) =>
    AutoTranslationController(repository: repository, maxConcurrent: 30)
      ..synchronize(
        enabled: true,
        authenticated: true,
        targetLanguage: 'en-US',
      );

Future<void> _pumpTranslation(WidgetTester tester) async {
  await tester.pump();
  await tester.pump();
}

void _expectNeverRequested(
  RecordingTranslationRepository repository,
  List<String> excluded,
) {
  final requested = repository.calls.map((call) => call.text).toList();
  for (final source in excluded) {
    expect(requested, isNot(contains(source)), reason: source);
  }
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
  Future<DiscoverFilterOptions> loadFilterOptions() =>
      throw UnimplementedError();

  @override
  Future<DiscoverPageResult> loadPage({
    required DiscoverContentType type,
    required int offset,
    required int limit,
    String query = '',
    List<String> categories = const [],
    List<String> cities = const [],
    List<String> tags = const [],
  }) =>
      throw UnimplementedError();
}
