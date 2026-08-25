import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_content_card.dart';

void main() {
  for (final cardCase in _cardCases) {
    testWidgets(
      'Discover ${cardCase.type.name} card translates its baseline-visible fields',
      (tester) async {
        await _useTallSurface(tester);
        final repository = _LiteralTranslationRepository(
          translations: cardCase.translations,
          holdResponses: true,
        );
        final controller = _controller(repository, active: true);
        addTearDown(controller.dispose);

        await tester.pumpWidget(
          _host(
            controller: controller,
            child: DiscoverContentCard(
              item: DiscoverItem.fromJson(
                cardCase.json,
                type: cardCase.type,
              ),
              onTap: _noop,
            ),
          ),
        );
        await tester.pump();

        for (final source in cardCase.sourceVisible) {
          expect(find.text(source), findsOneWidget, reason: source);
        }
        for (final hidden in cardCase.hiddenTexts) {
          expect(find.text(hidden), findsNothing, reason: hidden);
        }
        final requests = tester
            .widgetList<AutoTranslationBuilder>(
              find.byType(AutoTranslationBuilder),
            )
            .map((builder) => builder.request)
            .toList(growable: false);
        expect(requests, hasLength(cardCase.expectedRequests.length));
        expect(
          requests.map(_requestRecord).toSet(),
          cardCase.expectedRequests,
        );

        await _completeAll(
          tester,
          repository,
          cardCase.expectedRequests.length,
        );

        for (final translated in cardCase.translatedVisible) {
          expect(find.text(translated), findsOneWidget, reason: translated);
        }
        for (final retained in cardCase.retainedVisible) {
          expect(find.text(retained), findsOneWidget, reason: retained);
        }
        expect(
          repository.sourceTexts.toSet(),
          cardCase.expectedRequests.map((request) => request.$4).toSet(),
        );
        for (final excluded in cardCase.excludedSources) {
          expect(
            repository.sourceTexts,
            isNot(contains(excluded)),
            reason: excluded,
          );
        }
        expect(tester.takeException(), isNull);
      },
    );
  }

  for (final state in const [
    (label: 'disabled scope', scopeEnabled: false, controllerActive: true),
    (label: 'inactive controller', scopeEnabled: true, controllerActive: false),
  ]) {
    testWidgets('Discover ${state.label} makes zero translation calls',
        (tester) async {
      final repository = _LiteralTranslationRepository(
        translations: _cardCases.first.translations,
      );
      final controller = _controller(
        repository,
        active: state.controllerActive,
      );
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _host(
          controller: controller,
          enabled: state.scopeEnabled,
          child: DiscoverContentCard(
            item: DiscoverItem.fromJson(
              _cardCases.first.json,
              type: _cardCases.first.type,
            ),
            onTap: _noop,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('水光焕肤'), findsOneWidget);
      expect(repository.calls, isEmpty);
    });
  }

  testWidgets(
    'joined Discover field does not retry a failed part when its sibling translates',
    (tester) async {
      final repository = _LiteralTranslationRepository(
        translations: const {
          '上海市': 'Shanghai',
          '静安区南京西路': 'Nanjing West Road, Jing an',
        },
        holdResponses: true,
      );
      final controller = _controller(repository, active: true);
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _host(
          controller: controller,
          child: DiscoverContentCard(
            item: DiscoverItem.fromJson(
              const {
                'id': 'joined-institution',
                'name': 'Yuemei Clinic',
                'city': '上海市',
                'address': '静安区南京西路',
              },
              type: DiscoverContentType.institution,
            ),
            onTap: _noop,
          ),
        ),
      );
      await tester.pump();

      expect(
        repository.callRecords,
        const [
          ('上海市', 'institution'),
          ('静安区南京西路', 'institution'),
        ],
      );
      expect(repository.pendingCount, 2);

      repository.failSource('静安区南京西路');
      await tester.pump();
      await tester.pump();
      expect(find.text('上海市 · 静安区南京西路'), findsOneWidget);

      repository.completeSource('上海市');
      await tester.pump();
      await tester.pump();

      expect(find.text('Shanghai · 静安区南京西路'), findsOneWidget);
      expect(find.byType(SnackBar), findsNothing);
      expect(
        repository.callRecords,
        const [
          ('上海市', 'institution'),
          ('静安区南京西路', 'institution'),
        ],
      );
    },
  );

  testWidgets(
    'one Discover translation failure keeps exact source while siblings continue',
    (tester) async {
      final repository = _LiteralTranslationRepository(
        translations: const {
          '安心焕肤': 'Trusted rejuvenation',
          '保留这段说明': 'Keep this description',
        },
        failedSources: const {'保留这段说明'},
      );
      final controller = _controller(repository, active: true);
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _host(
          controller: controller,
          child: DiscoverContentCard(
            item: DiscoverItem.fromJson(
              const {
                'id': 'failure-project',
                'name': '安心焕肤',
                'description': '保留这段说明',
              },
              type: DiscoverContentType.project,
            ),
            onTap: _noop,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Trusted rejuvenation'), findsOneWidget);
      expect(find.text('保留这段说明'), findsOneWidget);
      expect(find.byType(SnackBar), findsNothing);
    },
  );

  testWidgets(
    'DiaryPreviewCard factory rejects auto translation without a backend id',
    (tester) async {
      final repository = _LiteralTranslationRepository(
        translations: const {'空标识日记': 'Diary without an id'},
        failedSources: const {'空标识日记'},
      );
      final controller = _controller(repository, active: true);
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _host(
          controller: controller,
          child: DiaryPreviewCard.fromData(
            data: const {
              'id': '  ',
              'title': '空标识日记',
            },
            enableAutoTranslation: true,
            onTap: _noop,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('空标识日记'), findsOneWidget);
      expect(repository.calls, isEmpty);
      expect(
        tester
            .widget<DiaryPreviewCard>(find.byType(DiaryPreviewCard))
            .autoTranslationContentId,
        isEmpty,
      );
    },
  );

  testWidgets(
    'DiaryPreviewRail translates only a unique non-empty backend id',
    (tester) async {
      final repository = _LiteralTranslationRepository(
        translations: const {
          '唯一日记': 'Unique diary',
          '空标识日记': 'Blank-id diary',
          '重复日记甲': 'Duplicate diary one',
          '重复日记乙': 'Duplicate diary two',
        },
      );
      final controller = _controller(repository, active: true);
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _host(
          controller: controller,
          child: const DiaryPreviewRail(
            enableAutoTranslation: true,
            diaries: [
              {'id': 'unique-diary', 'title': '唯一日记'},
              {'id': ' ', 'title': '空标识日记'},
              {'id': 'duplicate-diary', 'title': '重复日记甲'},
              {'diaryId': 'duplicate-diary', 'title': '重复日记乙'},
            ],
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Unique diary'), findsOneWidget);
      for (final retained in const ['空标识日记', '重复日记甲', '重复日记乙']) {
        expect(find.text(retained), findsOneWidget, reason: retained);
      }
      expect(repository.sourceTexts, const ['唯一日记']);
    },
  );

  testWidgets(
    'DiaryPreviewRail reorder preserves a failed unique diary owner without retry',
    (tester) async {
      final repository = _LiteralTranslationRepository(
        translations: const {'重排失败日记': 'Failed diary after reorder'},
        holdResponses: true,
      );
      final controller = _controller(repository, active: true);
      addTearDown(controller.dispose);
      final failedDiary = <String, Object?>{
        'id': 'failed-diary',
        'title': '重排失败日记',
      };
      final englishDiary = <String, Object?>{
        'id': 'english-diary',
        'title': 'English diary',
      };
      var diaries = [failedDiary, englishDiary];
      late StateSetter updateRail;

      await tester.pumpWidget(
        _host(
          controller: controller,
          child: StatefulBuilder(
            builder: (context, setState) {
              updateRail = setState;
              return DiaryPreviewRail(
                diaries: diaries,
                enableAutoTranslation: true,
              );
            },
          ),
        ),
      );
      await tester.pump();
      expect(repository.sourceTexts, const ['重排失败日记']);

      repository.failSource('重排失败日记');
      await tester.pump();
      await tester.pump();
      expect(find.text('重排失败日记'), findsOneWidget);

      updateRail(() => diaries = [englishDiary, failedDiary]);
      await tester.pump();
      await tester.pump();

      expect(repository.sourceTexts, const ['重排失败日记']);
      expect(repository.pendingCount, 0);
      expect(find.text('重排失败日记'), findsOneWidget);
    },
  );
}

typedef _RequestRecord = (String, String, String, String);

_RequestRecord _requestRecord(AutoTranslationRequest request) => (
      request.contentType,
      request.contentId,
      request.field,
      request.sourceText,
    );

final class _CardCase {
  const _CardCase({
    required this.type,
    required this.json,
    required this.translations,
    required this.expectedRequests,
    required this.sourceVisible,
    required this.translatedVisible,
    this.retainedVisible = const [],
    this.hiddenTexts = const [],
    this.excludedSources = const [],
  });

  final DiscoverContentType type;
  final Map<String, Object?> json;
  final Map<String, String> translations;
  final Set<_RequestRecord> expectedRequests;
  final List<String> sourceVisible;
  final List<String> translatedVisible;
  final List<String> retainedVisible;
  final List<String> hiddenTexts;
  final List<String> excludedSources;
}

const _cardCases = [
  _CardCase(
    type: DiscoverContentType.project,
    json: {
      'id': 'project-1',
      'name': '水光焕肤',
      'category': '皮肤管理',
      'description': '深层补水改善肤质',
      'tags': '隐藏项目标签,Hydra,808',
      'coverImage': 'https://images.example/project.jpg',
      'referencePrice': 1280,
      'currency': 'CNY',
      'rating': 4.8,
      'reviewCount': 19,
      'projectCode': 'PROJECT-001',
    },
    translations: {
      '水光焕肤': 'Hydrating skin treatment',
      '皮肤管理': 'Skin care',
      '深层补水改善肤质': 'Deep hydration for improved skin texture',
    },
    expectedRequests: {
      ('project', 'project:project-1', 'name', '水光焕肤'),
      ('project', 'project:project-1', 'category', '皮肤管理'),
      ('project', 'project:project-1', 'description', '深层补水改善肤质'),
    },
    sourceVisible: ['水光焕肤', '皮肤管理', '深层补水改善肤质'],
    translatedVisible: [
      'Hydrating skin treatment',
      'Skin care',
      'Deep hydration for improved skin texture',
    ],
    hiddenTexts: ['隐藏项目标签', 'Hydra', '808', 'PROJECT-001'],
    excludedSources: [
      '隐藏项目标签',
      'Hydra',
      '808',
      'PROJECT-001',
      '1280',
      r'$1280',
      'CNY',
      '4.8',
      '19',
      'https://images.example/project.jpg',
      'project-1',
    ],
  ),
  _CardCase(
    type: DiscoverContentType.institution,
    json: {
      'id': 'institution-1',
      'name': '悦美医疗美容医院',
      'city': '上海市',
      'address': '静安区南京西路',
      'description': '正规资质与舒适环境',
      'specialties': '隐藏机构专长',
      'tags': '隐藏机构标签',
      'coverImage': 'https://images.example/institution.jpg',
      'contactPhone': '021-12345678',
      'rating': 4.9,
      'reviewCount': 27,
      'isVerified': true,
    },
    translations: {
      '悦美医疗美容医院': 'Yuemei Aesthetic Hospital',
      '上海市': 'Shanghai',
      '静安区南京西路': 'Nanjing West Road, Jing an',
      '正规资质与舒适环境': 'Licensed care in a comfortable setting',
    },
    expectedRequests: {
      ('institution', 'institution:institution-1', 'name', '悦美医疗美容医院'),
      ('institution', 'institution:institution-1', 'city', '上海市'),
      ('institution', 'institution:institution-1', 'address', '静安区南京西路'),
      (
        'institution',
        'institution:institution-1',
        'description',
        '正规资质与舒适环境',
      ),
    },
    sourceVisible: [
      '悦美医疗美容医院',
      '上海市 · 静安区南京西路',
      '正规资质与舒适环境',
    ],
    translatedVisible: [
      'Yuemei Aesthetic Hospital',
      'Shanghai · Nanjing West Road, Jing an',
      'Licensed care in a comfortable setting',
    ],
    hiddenTexts: ['隐藏机构专长', '隐藏机构标签', '021-12345678'],
    excludedSources: [
      '隐藏机构专长',
      '隐藏机构标签',
      '021-12345678',
      '4.9',
      '27',
      'https://images.example/institution.jpg',
      'institution-1',
    ],
  ),
  _CardCase(
    type: DiscoverContentType.doctor,
    json: {
      'id': 'doctor-1',
      'name': '李医生',
      'title': '主治医师',
      'institutionName': '悦美医院',
      'specialties': '皮肤管理,Laser,808',
      'certificationTags': '隐藏认证标签',
      'avatar': 'https://images.example/doctor.jpg',
      'contactPhone': '13800000000',
      'rating': 4.7,
      'reviewCount': 31,
      'consultationCount': 120,
      'isVerified': true,
    },
    translations: {
      '主治医师': 'Attending physician',
      '悦美医院': 'Yuemei Hospital',
      '皮肤管理': 'Skin care',
    },
    expectedRequests: {
      ('doctor', 'doctor:doctor-1', 'professionalTitle', '主治医师'),
      ('doctor', 'doctor:doctor-1', 'institutionName', '悦美医院'),
      ('doctor', 'doctor:doctor-1', 'specialties', '皮肤管理'),
    },
    sourceVisible: ['李医生', '主治医师 · 悦美医院', '皮肤管理', 'Laser', '808'],
    translatedVisible: [
      'Attending physician · Yuemei Hospital',
      'Skin care',
    ],
    retainedVisible: ['李医生', 'Laser', '808'],
    hiddenTexts: ['隐藏认证标签', '13800000000'],
    excludedSources: [
      '李医生',
      'Laser',
      '808',
      '隐藏认证标签',
      '13800000000',
      '4.7',
      '31',
      '120',
      'https://images.example/doctor.jpg',
      'doctor-1',
    ],
  ),
  _CardCase(
    type: DiscoverContentType.diary,
    json: {
      'id': 'diary-1',
      'title': '恢复日记',
      'content': '恢复过程很顺利',
      'projectName': '光电嫩肤',
      'authorName': '小美',
      'nickname': '日记昵称',
      'authorAvatar': '',
      'publishDate': '2026-08-20',
      'imageUrls': ['https://images.example/diary.jpg'],
      'tags': '隐藏日记标签',
      'doctorName': '王医生',
      'institutionName': '隐藏日记机构',
      'likeCount': 12,
      'favoriteCount': 5,
      'commentCount': 3,
      'rating': 5,
    },
    translations: {
      '恢复日记': 'Recovery diary',
      '恢复过程很顺利': 'Recovery went smoothly',
      '光电嫩肤': 'Laser rejuvenation',
    },
    expectedRequests: {
      ('diary', 'diary:diary-1', 'title', '恢复日记'),
      ('diary', 'diary:diary-1', 'content', '恢复过程很顺利'),
      ('diary', 'diary:diary-1', 'projectName', '光电嫩肤'),
    },
    sourceVisible: [
      '恢复日记',
      '恢复过程很顺利',
      '光电嫩肤',
      '小美',
      '2026-08-20',
    ],
    translatedVisible: [
      'Recovery diary',
      'Recovery went smoothly',
      'Laser rejuvenation',
    ],
    retainedVisible: ['小美', '2026-08-20'],
    hiddenTexts: ['日记昵称', '隐藏日记标签', '王医生', '隐藏日记机构'],
    excludedSources: [
      '小美',
      '日记昵称',
      '2026-08-20',
      '隐藏日记标签',
      '王医生',
      '隐藏日记机构',
      '12',
      '5',
      '3',
      'https://images.example/diary.jpg',
      'diary-1',
    ],
  ),
  _CardCase(
    type: DiscoverContentType.article,
    json: {
      'id': 'article-1',
      'title': '术后护理指南',
      'summary': '专家讲解恢复注意事项',
      'authorName': '赵教授',
      'publishDate': '2026-08-21',
      'content': '隐藏文章正文',
      'coverImage': 'https://images.example/article.jpg',
      'readCount': 37,
      'doctorId': 'doctor-99',
      'category': '隐藏文章分类',
    },
    translations: {
      '术后护理指南': 'Post-treatment care guide',
      '专家讲解恢复注意事项': 'Expert recovery guidance',
    },
    expectedRequests: {
      ('article', 'article:article-1', 'title', '术后护理指南'),
      ('article', 'article:article-1', 'summary', '专家讲解恢复注意事项'),
    },
    sourceVisible: ['术后护理指南', '专家讲解恢复注意事项', '赵教授'],
    translatedVisible: [
      'Post-treatment care guide',
      'Expert recovery guidance',
    ],
    retainedVisible: ['赵教授'],
    hiddenTexts: ['2026-08-21', '隐藏文章正文', '隐藏文章分类'],
    excludedSources: [
      '赵教授',
      '2026-08-21',
      '隐藏文章正文',
      '隐藏文章分类',
      '37',
      'doctor-99',
      'https://images.example/article.jpg',
      'article-1',
    ],
  ),
];

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
  List<(String, String)> get callRecords => calls
      .map((call) => (call.sourceText, call.contentType))
      .toList(growable: false);
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

  void completeSource(String sourceText) {
    final pending = _removePending(sourceText);
    pending.completer.complete(
      _response(translations[pending.sourceText]!, 'en-US'),
    );
  }

  void failSource(String sourceText) {
    final pending = _removePending(sourceText);
    pending.completer.completeError(
      StateError('configured controlled failure for $sourceText'),
    );
  }

  _PendingTranslation _removePending(String sourceText) {
    final index = _pending.indexWhere(
      (pending) => pending.sourceText == sourceText,
    );
    if (index < 0) {
      throw StateError('no pending translation for $sourceText');
    }
    return _pending.removeAt(index);
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

AutoTranslationController _controller(
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
}) {
  return MaterialApp(
    locale: const Locale('en'),
    home: AutoTranslationScope(
      controller: controller,
      enabled: enabled,
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
  tester.view.physicalSize = const Size(1200, 1800);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetPhysicalSize);
}

void _noop() {}
