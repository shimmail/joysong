import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_content_card.dart';
import 'package:joysong_flutter/features/home/domain/home_models.dart';
import 'package:joysong_flutter/features/home/domain/home_repository.dart';
import 'package:joysong_flutter/features/home/presentation/home_page.dart';

void main() {
  testWidgets(
    'Home renders source first then translates every eligible visible field',
    (tester) async {
      await _useTallSurface(tester);
      final repository = _LiteralTranslationRepository(
        translations: _homeTranslations,
        holdResponses: true,
      );
      final controller = _activeController(repository);
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _homeHost(controller: controller, feed: _completeFeed),
      );
      await tester.pump();

      for (final source in _homeTranslations.keys) {
        expect(find.text(source), findsOneWidget, reason: source);
      }

      final requests = tester
          .widgetList<AutoTranslationBuilder>(
            find.byType(AutoTranslationBuilder),
          )
          .map((builder) => builder.request)
          .toList(growable: false);
      expect(requests, hasLength(_expectedHomeRequests.length));
      expect(
        requests
            .map(
              (request) => (
                request.contentType,
                request.contentId,
                request.field,
                request.sourceText,
              ),
            )
            .toSet(),
        _expectedHomeRequests,
      );

      await _completeAll(tester, repository, _expectedHomeRequests.length);

      for (final translation in _homeTranslations.values) {
        expect(find.text(translation), findsOneWidget, reason: translation);
      }
      expect(find.text('王医生'), findsOneWidget);
      expect(repository.sourceTexts, isNot(contains('王医生')));
      expect(repository.sourceTexts, isNot(contains('赵教授')));
      expect(repository.sourceTexts, isNot(contains('小美')));
      expect(repository.sourceTexts, isNot(contains('2026-08-20')));
      expect(repository.sourceTexts, isNot(contains(r'$1280')));
      expect(repository.sourceTexts, isNot(contains('4.9')));
      expect(repository.sourceTexts, isNot(contains('37')));
      expect(
        repository.sourceTexts,
        isNot(contains('https://images.example/diary.jpg')),
      );
    },
  );

  testWidgets('Home disabled scope registers no automatic requests',
      (tester) async {
    await _useTallSurface(tester);
    final repository = _LiteralTranslationRepository(
      translations: _homeTranslations,
    );
    final controller = _activeController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      _homeHost(
        controller: controller,
        feed: _completeFeed,
        enabled: false,
      ),
    );
    await tester.pump();

    expect(find.text('焕肤新选择'), findsOneWidget);
    expect(repository.calls, isEmpty);
  });

  testWidgets(
    'one Home provider failure preserves its source while siblings translate',
    (tester) async {
      final repository = _LiteralTranslationRepository(
        translations: const {
          '安心项目': 'Trusted project',
          '项目说明': 'Project description',
        },
        failedSources: const {'项目说明'},
      );
      final controller = _activeController(repository);
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _homeHost(
          controller: controller,
          feed: const HomeFeed(
            banners: [
              HomeContent(
                id: 'failure-banner',
                title: '安心项目',
                subtitle: '项目说明',
                kind: HomeSectionKind.banner,
              ),
            ],
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Trusted project'), findsOneWidget);
      expect(find.text('项目说明'), findsOneWidget);
      expect(find.byType(SnackBar), findsNothing);
    },
  );

  testWidgets(
    'DiaryPreviewCard defaults off even inside an active global scope',
    (tester) async {
      final repository = _LiteralTranslationRepository(
        translations: _diaryTranslations,
      );
      final controller = _activeController(repository);
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _sharedHost(
          controller,
          const DiaryPreviewCard(
            autoTranslationContentId: 'diary:card-default',
            title: '恢复日记',
            content: '恢复过程很顺利',
            authorName: '小美',
            authorAvatar: '',
            publishDate: '2026-08-20',
            projectName: '光电嫩肤',
            images: ['https://images.example/diary.jpg'],
            beforeImages: [],
            afterImages: [],
            likeCount: 12,
            favoriteCount: 5,
            commentCount: 3,
            onTap: _noop,
          ),
        ),
      );

      expect(find.text('恢复日记'), findsOneWidget);
      expect(find.text('小美'), findsOneWidget);
      expect(repository.calls, isEmpty);
      expect(find.byType(AutoTranslationBuilder), findsNothing);
    },
  );

  testWidgets(
    'DiaryPreviewCard opt-in translates only title content and project',
    (tester) async {
      final repository = _LiteralTranslationRepository(
        translations: _diaryTranslations,
        holdResponses: true,
      );
      final controller = _activeController(repository);
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _sharedHost(
          controller,
          const DiaryPreviewCard(
            enableAutoTranslation: true,
            autoTranslationContentId: 'diary:card-enabled',
            title: '恢复日记',
            content: '恢复过程很顺利',
            authorName: '小美',
            authorAvatar: '',
            publishDate: '2026-08-20',
            projectName: '光电嫩肤',
            images: ['https://images.example/diary.jpg'],
            beforeImages: [],
            afterImages: [],
            likeCount: 12,
            favoriteCount: 5,
            commentCount: 3,
            onTap: _noop,
          ),
        ),
      );

      expect(find.text('恢复日记'), findsOneWidget);
      final requests = tester
          .widgetList<AutoTranslationBuilder>(
            find.byType(AutoTranslationBuilder),
          )
          .map((builder) => builder.request)
          .toList(growable: false);
      expect(
        requests
            .map(
              (request) => (
                request.contentType,
                request.contentId,
                request.field,
                request.sourceText,
              ),
            )
            .toSet(),
        {
          ('diary', 'diary:card-enabled', 'title', '恢复日记'),
          ('diary', 'diary:card-enabled', 'content', '恢复过程很顺利'),
          ('diary', 'diary:card-enabled', 'projectName', '光电嫩肤'),
        },
      );

      await _completeAll(tester, repository, 3);

      expect(find.text('Recovery diary'), findsOneWidget);
      expect(find.text('Recovery went smoothly'), findsOneWidget);
      expect(find.text('Laser rejuvenation'), findsOneWidget);
      expect(find.text('小美'), findsOneWidget);
      expect(find.text('2026-08-20'), findsOneWidget);
      expect(repository.sourceTexts, isNot(contains('小美')));
      expect(repository.sourceTexts, isNot(contains('2026-08-20')));
      expect(
        repository.sourceTexts,
        isNot(contains('https://images.example/diary.jpg')),
      );
    },
  );

  testWidgets('DiaryPreviewRail forwards opt-in to every card', (tester) async {
    final repository = _LiteralTranslationRepository(
      translations: const {
        '第一篇日记': 'First diary',
        '第一篇内容': 'First content',
        '第一个项目': 'First project',
        '第二篇日记': 'Second diary',
        '第二篇内容': 'Second content',
        '第二个项目': 'Second project',
      },
    );
    final controller = _activeController(repository, maxConcurrent: 10);
    addTearDown(controller.dispose);
    const diaries = [
      {
        'id': 'rail-1',
        'title': '第一篇日记',
        'content': '第一篇内容',
        'projectName': '第一个项目',
        'authorName': '作者一',
        'publishDate': '2026-08-20',
      },
      {
        'diaryId': 'rail-2',
        'title': '第二篇日记',
        'content': '第二篇内容',
        'projectName': '第二个项目',
        'authorName': '作者二',
        'publishDate': '2026-08-21',
      },
    ];

    await tester.pumpWidget(
      _sharedHost(
        controller,
        const DiaryPreviewRail(diaries: diaries),
      ),
    );
    expect(repository.calls, isEmpty);
    expect(
      tester
          .widgetList<DiaryPreviewCard>(find.byType(DiaryPreviewCard))
          .every((card) => !card.enableAutoTranslation),
      isTrue,
    );

    await tester.pumpWidget(
      _sharedHost(
        controller,
        const DiaryPreviewRail(
          diaries: diaries,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    final cards = tester
        .widgetList<DiaryPreviewCard>(find.byType(DiaryPreviewCard))
        .toList(growable: false);
    expect(cards, hasLength(2));
    expect(cards.every((card) => card.enableAutoTranslation), isTrue);
    expect(
      cards.map((card) => card.autoTranslationContentId),
      ['diary:rail-1', 'diary:rail-2'],
    );
    expect(repository.calls, hasLength(6));
    expect(find.text('First diary'), findsOneWidget);
    expect(find.text('Second project'), findsOneWidget);
    expect(repository.sourceTexts, isNot(contains('作者一')));
    expect(repository.sourceTexts, isNot(contains('作者二')));
  });
}

const _completeFeed = HomeFeed(
  banners: [
    HomeContent(
      id: 'banner-1',
      title: '焕肤新选择',
      subtitle: '科学了解恢复过程',
      kind: HomeSectionKind.banner,
    ),
  ],
  recommendedInstitutionProjects: [
    HomeContent(
      id: 'recommended-1',
      title: '光电嫩肤',
      subtitle: '改善肤色与肤质',
      category: '皮肤管理',
      priceText: r'$1280',
      kind: HomeSectionKind.recommendedInstitutionProject,
      raw: {'institutionName': '安心医美中心'},
    ),
  ],
  hotProjects: [
    HomeContent(
      id: 'hot-1',
      title: '热玛吉',
      subtitle: '不应显示的简介',
      category: '紧致抗衰',
      priceText: r'$1280',
      kind: HomeSectionKind.hotProject,
    ),
  ],
  expertArticles: [
    HomeContent(
      id: 'article-1',
      title: '术后护理指南',
      subtitle: '专家讲解恢复注意事项',
      category: '护理科普',
      kind: HomeSectionKind.expertArticle,
      raw: {
        'authorName': '赵教授',
        'publishDate': '2026-08-20',
        'readCount': 37,
      },
    ),
  ],
  userDiaries: [
    HomeContent(
      id: 'diary-1',
      title: '我的恢复日记',
      subtitle: '恢复过程很顺利',
      kind: HomeSectionKind.userDiary,
      raw: {
        'projectName': '点阵激光',
        'authorName': '小美',
        'publishDate': '2026-08-20',
        'likeCount': 37,
        'favoriteCount': 5,
        'commentCount': 3,
        'images': ['https://images.example/diary.jpg'],
      },
    ),
  ],
  institutions: [
    HomeContent(
      id: 'institution-1',
      title: '华美医疗美容医院',
      subtitle: '上海市静安区',
      kind: HomeSectionKind.institution,
      raw: {
        'address': '上海市静安区',
        'description': '专注皮肤健康管理',
        'rating': 4.9,
        'reviewCount': 37,
      },
    ),
  ],
  doctors: [
    HomeContent(
      id: 'doctor-1',
      title: '王医生',
      subtitle: '主任医师',
      kind: HomeSectionKind.doctor,
      raw: {
        'professionalTitle': '主任医师',
        'specialties': ['激光治疗', '皮肤护理'],
        'institutionName': '仁爱医院',
        'rating': 4.9,
      },
    ),
  ],
);

const _homeTranslations = {
  '焕肤新选择': 'A new choice for rejuvenation',
  '科学了解恢复过程': 'Understand recovery scientifically',
  '安心医美中心': 'Trusted Aesthetic Center',
  '光电嫩肤': 'Laser rejuvenation',
  '改善肤色与肤质': 'Improve tone and texture',
  '皮肤管理': 'Skin care',
  '热玛吉': 'Thermage',
  '紧致抗衰': 'Firming and anti-aging',
  '术后护理指南': 'Post-treatment care guide',
  '专家讲解恢复注意事项': 'Expert recovery guidance',
  '护理科普': 'Care education',
  '我的恢复日记': 'My recovery diary',
  '恢复过程很顺利': 'Recovery went smoothly',
  '点阵激光': 'Fractional laser',
  '华美医疗美容医院': 'Huamei Aesthetic Hospital',
  '上海市静安区': 'Jing an District, Shanghai',
  '专注皮肤健康管理': 'Focused on skin health',
  '主任医师': 'Chief physician',
  '激光治疗 · 皮肤护理': 'Laser treatment · Skin care',
  '仁爱医院': 'Renai Hospital',
};

const _expectedHomeRequests = {
  ('general', 'banner:banner-1', 'title', '焕肤新选择'),
  ('general', 'banner:banner-1', 'subtitle', '科学了解恢复过程'),
  (
    'project',
    'recommendedInstitutionProject:recommended-1',
    'institutionName',
    '安心医美中心',
  ),
  (
    'project',
    'recommendedInstitutionProject:recommended-1',
    'name',
    '光电嫩肤',
  ),
  (
    'project',
    'recommendedInstitutionProject:recommended-1',
    'slogan',
    '改善肤色与肤质',
  ),
  (
    'project',
    'recommendedInstitutionProject:recommended-1',
    'category',
    '皮肤管理',
  ),
  ('project', 'hotProject:hot-1', 'name', '热玛吉'),
  ('project', 'hotProject:hot-1', 'category', '紧致抗衰'),
  ('article', 'expertArticle:article-1', 'title', '术后护理指南'),
  ('article', 'expertArticle:article-1', 'summary', '专家讲解恢复注意事项'),
  ('article', 'expertArticle:article-1', 'category', '护理科普'),
  ('diary', 'userDiary:diary-1', 'title', '我的恢复日记'),
  ('diary', 'userDiary:diary-1', 'content', '恢复过程很顺利'),
  ('diary', 'userDiary:diary-1', 'projectName', '点阵激光'),
  ('institution', 'institution:institution-1', 'name', '华美医疗美容医院'),
  ('institution', 'institution:institution-1', 'address', '上海市静安区'),
  (
    'institution',
    'institution:institution-1',
    'description',
    '专注皮肤健康管理',
  ),
  ('doctor', 'doctor:doctor-1', 'professionalTitle', '主任医师'),
  ('doctor', 'doctor:doctor-1', 'specialties', '激光治疗 · 皮肤护理'),
  ('doctor', 'doctor:doctor-1', 'institutionName', '仁爱医院'),
};

const _diaryTranslations = {
  '恢复日记': 'Recovery diary',
  '恢复过程很顺利': 'Recovery went smoothly',
  '光电嫩肤': 'Laser rejuvenation',
};

final class _HomeRepository implements HomeRepository {
  const _HomeRepository(this.feed);

  final HomeFeed feed;

  @override
  Future<HomeFeed> loadHome() async => feed;
}

final class _TranslationCall {
  const _TranslationCall(this.sourceText, this.contentType);

  final String sourceText;
  final String contentType;
}

final class _PendingTranslation {
  const _PendingTranslation(this.sourceText, this.completer);

  final String sourceText;
  final Completer<ContentTranslation> completer;
}

final class _LiteralTranslationRepository implements TranslationRepository {
  _LiteralTranslationRepository({
    required this.translations,
    this.failedSources = const {},
    this.holdResponses = false,
  });

  final Map<String, String> translations;
  final Set<String> failedSources;
  final bool holdResponses;
  final List<_TranslationCall> calls = [];
  final List<_PendingTranslation> _pending = [];

  List<String> get sourceTexts =>
      calls.map((call) => call.sourceText).toList(growable: false);
  int get pendingCount => _pending.length;

  @override
  Future<ContentTranslation> translateText({
    required String text,
    required String targetLanguage,
    required String contentType,
  }) async {
    calls.add(_TranslationCall(text, contentType));
    if (failedSources.contains(text)) {
      throw StateError('configured failure for $text');
    }
    if (holdResponses) {
      final completer = Completer<ContentTranslation>();
      _pending.add(_PendingTranslation(text, completer));
      return completer.future;
    }
    return _response(translations[text]!, targetLanguage);
  }

  void completeNext() {
    final pending = _pending.removeAt(0);
    pending.completer.complete(
      _response(translations[pending.sourceText]!, 'en-US'),
    );
  }

  ContentTranslation _response(String translatedText, String targetLanguage) {
    return ContentTranslation(
      translatedText: translatedText,
      detectedLanguage: 'zh',
      targetLanguage: targetLanguage,
      provider: 'test',
      cached: false,
    );
  }
}

AutoTranslationController _activeController(
  TranslationRepository repository, {
  int maxConcurrent = 3,
}) =>
    AutoTranslationController(
      repository: repository,
      maxConcurrent: maxConcurrent,
    )..synchronize(
        enabled: true,
        authenticated: true,
        targetLanguage: 'en-US',
      );

Widget _homeHost({
  required AutoTranslationController controller,
  required HomeFeed feed,
  bool enabled = true,
}) {
  return MaterialApp(
    locale: const Locale('en'),
    home: AutoTranslationScope(
      controller: controller,
      enabled: enabled,
      targetLanguage: 'en-US',
      child: HomePage(repository: _HomeRepository(feed)),
    ),
  );
}

Widget _sharedHost(AutoTranslationController controller, Widget child) {
  return MaterialApp(
    locale: const Locale('en'),
    home: AutoTranslationScope(
      controller: controller,
      enabled: true,
      targetLanguage: 'en-US',
      child: Scaffold(
        body: SingleChildScrollView(
          child: SizedBox(width: 700, child: child),
        ),
      ),
    ),
  );
}

Future<void> _completeAll(
  WidgetTester tester,
  _LiteralTranslationRepository repository,
  int expectedCalls,
) async {
  var safety = 0;
  while (
      repository.calls.length < expectedCalls || repository.pendingCount > 0) {
    if (repository.pendingCount > 0) {
      repository.completeNext();
    }
    await tester.pump();
    await tester.pump();
    safety += 1;
    if (safety > expectedCalls * 3) {
      fail('translations did not drain');
    }
  }
}

Future<void> _useTallSurface(WidgetTester tester) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1200, 5000);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetPhysicalSize);
}

void _noop() {}
