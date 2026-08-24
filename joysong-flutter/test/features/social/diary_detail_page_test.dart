import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart'
    hide ContentTranslation;
import 'package:joysong_flutter/features/social/domain/social_repository.dart';
import 'package:joysong_flutter/features/social/presentation/diary_detail_page.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';
import 'package:joysong_flutter/features/social/presentation/social_page.dart';

void main() {
  testWidgets('opens a reachable diary detail backed by repository comments',
      (tester) async {
    final repository = _DetailRepository();
    final controller = SocialController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh', 'CN'),
        supportedLocales: const [Locale('zh', 'CN'), Locale('en', 'US')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: SocialPage(controller: controller),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('open-diary-diary-1')));
    await tester.pumpAndSettle();

    expect(find.text('日记详情'), findsOneWidget);
    expect(find.text('来自数据库的评论'), findsOneWidget);
    expect(repository.commentDiaryIds, ['diary-1']);

    await tester.tap(find.byKey(const Key('comment-like-comment-1')));
    await tester.pumpAndSettle();

    expect(repository.likeWrites, ['comment:comment-1:true']);
    expect(
      find.descendant(
        of: find.byKey(const Key('comment-like-comment-1')),
        matching: find.text('3'),
      ),
      findsOneWidget,
    );

    expect(
      find.textContaining('来自数据库的回复', findRichText: true),
      findsOneWidget,
    );
    final replyLike = find.byKey(const Key('comment-like-reply-remote'));
    await tester.ensureVisible(replyLike);
    await tester.drag(find.byType(ListView), const Offset(0, -160));
    await tester.pumpAndSettle();
    await tester.tap(replyLike);
    await tester.pumpAndSettle();
    expect(repository.likeWrites.last, 'comment:reply-remote:true');
  });

  testWidgets('publishes a reply and reports through the real repository API',
      (tester) async {
    final repository = _DetailRepository();
    final controller = SocialController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh', 'CN'),
        supportedLocales: const [Locale('zh', 'CN'), Locale('en', 'US')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: DiaryDetailPage(
          controller: controller,
          diary: repository.diaries.single,
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('comment-reply-comment-1')));
    await tester.pump();
    await tester.enterText(find.byKey(const Key('comment-input')), '这是一条真实回复');
    await tester.tap(find.byKey(const Key('comment-send')));
    await tester.pumpAndSettle();

    expect(repository.publishedDraft?.diaryId, 'diary-1');
    expect(repository.publishedDraft?.parentId, 'comment-1');
    expect(repository.publishedDraft?.replyToUserId, 'user-2');
    expect(
      find.textContaining('这是一条真实回复', findRichText: true),
      findsOneWidget,
    );

    await tester.tap(find.byKey(const Key('diary-report')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('report-reason-spam')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('report-submit')));
    await tester.pumpAndSettle();

    expect(repository.reportedType, ReportTargetType.diary);
    expect(repository.reportedTargetId, 'diary-1');
    expect(repository.reportedReason, '垃圾广告');
    expect(
      controller.hasReported(ReportTargetType.diary, 'diary-1'),
      isTrue,
    );
  });

  testWidgets('new diary detail surfaces are available in English',
      (tester) async {
    final repository = _DetailRepository();
    final controller = SocialController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en', 'US'),
        supportedLocales: const [Locale('zh', 'CN'), Locale('en', 'US')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: DiaryDetailPage(
          controller: controller,
          diary: repository.diaries.single,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Diary details'), findsOneWidget);
    expect(find.text('Comments'), findsOneWidget);
    expect(find.text('Reply'), findsWidgets);
    expect(find.text('Hide replies'), findsOneWidget);
    expect(find.byKey(const Key('diary-report')), findsOneWidget);
    expect(find.byTooltip('Send'), findsOneWidget);
  });

  testWidgets(
    'diary comment and reply translate automatically with exact identities and source round trips',
    (tester) async {
      final repository = _DetailRepository();
      final controller = SocialController(repository);
      final translations = _AutoRepository(
        translations: const {
          '真实日记': 'Real diary',
          '日记正文': 'Diary body',
          '来自数据库的评论': 'Database comment',
          '来自数据库的回复': 'Database reply',
        },
        holdResponses: true,
      );
      final autoController = _activeAutoController(translations);
      addTearDown(controller.dispose);
      addTearDown(autoController.dispose);

      await tester.pumpWidget(
        _autoHost(
          controller: autoController,
          child: DiaryDetailPage(
            controller: controller,
            diary: repository.diaries.single,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('真实日记'), findsOneWidget);
      expect(find.text('日记正文'), findsOneWidget);
      expect(find.text('来自数据库的评论'), findsOneWidget);
      expect(
        _requests(tester),
        containsAll(const {
          ('diary', 'diary:diary-1', 'title', '真实日记'),
          ('diary', 'diary:diary-1', 'content', '日记正文'),
          ('comment', 'comment:comment-1', 'content', '来自数据库的评论'),
        }),
      );

      translations.completeSource('真实日记');
      translations.completeSource('日记正文');
      translations.completeSource('来自数据库的评论');
      await tester.pump();
      await tester.pump();

      expect(find.text('Real diary'), findsOneWidget);
      expect(find.text('Diary body'), findsOneWidget);
      expect(find.text('Database comment'), findsOneWidget);
      expect(find.text('评论用户'), findsOneWidget);

      expect(
        find.textContaining('来自数据库的回复', findRichText: true),
        findsOneWidget,
      );
      expect(
        _requests(tester),
        contains(
          const (
            'comment',
            'comment:reply-remote',
            'content',
            '来自数据库的回复',
          ),
        ),
      );
      translations.completeSource('来自数据库的回复');
      await tester.pump();
      await tester.pump();
      expect(
        find.textContaining('Database reply', findRichText: true),
        findsOneWidget,
      );
      expect(find.text('回复用户'), findsOneWidget);
      expect(find.textContaining('评论用户'), findsWidgets);
      expect(translations.sources, isNot(contains('评论用户')));
      expect(translations.sources, isNot(contains('回复用户')));

      final callCount = translations.sources.length;
      await tester.tap(
        find.byKey(const Key('translation:diary-title:diary-1-button')),
      );
      await tester.pump();
      expect(find.text('真实日记'), findsOneWidget);
      expect(find.text('日记正文'), findsOneWidget);
      await tester.tap(
        find.byKey(const Key('translation:diary-title:diary-1-button')),
      );
      await tester.pump();
      expect(find.text('Real diary'), findsOneWidget);
      expect(find.text('Diary body'), findsOneWidget);

      await tester.tap(find.byKey(const Key('comment-translate-comment-1')));
      await tester.pump();
      expect(find.text('来自数据库的评论'), findsOneWidget);
      await tester.tap(find.byKey(const Key('comment-translate-comment-1')));
      await tester.pump();
      expect(find.text('Database comment'), findsOneWidget);
      expect(translations.sources, hasLength(callCount));
      expect(repository.manualCalls, isEmpty);
    },
  );

  testWidgets(
    'automatic comment failure leaves manual success and failure feedback unchanged',
    (tester) async {
      final repository = _DetailRepository(
        manualTranslations: const {
          '来自数据库的评论': 'Manual comment translation',
        },
        manualFailures: const {'来自数据库的回复'},
      );
      final controller = SocialController(repository);
      final translations = _AutoRepository(
        translations: const {
          '真实日记': 'Real diary',
          '日记正文': 'Diary body',
        },
        failedSources: const {
          '来自数据库的评论',
          '来自数据库的回复',
        },
      );
      final autoController = _activeAutoController(translations);
      addTearDown(controller.dispose);
      addTearDown(autoController.dispose);

      await tester.pumpWidget(
        _autoHost(
          controller: autoController,
          child: DiaryDetailPage(
            controller: controller,
            diary: repository.diaries.single,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('来自数据库的评论'), findsOneWidget);
      final autoCallsBeforeManual = translations.sources.length;
      await tester.tap(find.byKey(const Key('comment-translate-comment-1')));
      await tester.pumpAndSettle();
      expect(find.text('Manual comment translation'), findsOneWidget);
      expect(
        repository.manualCalls,
        const [('来自数据库的评论', 'comment')],
      );
      expect(translations.sources, hasLength(autoCallsBeforeManual));

      expect(
        find.textContaining('来自数据库的回复', findRichText: true),
        findsOneWidget,
      );
      final autoCallsBeforeFailure = translations.sources.length;
      final replyTranslate =
          find.byKey(const Key('comment-translate-reply-remote'));
      await tester.ensureVisible(replyTranslate);
      await tester.pump();
      await tester.tap(replyTranslate);
      await tester.pumpAndSettle();

      expect(
        find.textContaining('来自数据库的回复', findRichText: true),
        findsOneWidget,
      );
      expect(
        repository.manualCalls.last,
        const ('来自数据库的回复', 'comment'),
      );
      expect(
        find.text(
          'AI translation is temporarily unavailable. Please try again.',
        ),
        findsOneWidget,
      );
      expect(translations.sources, hasLength(autoCallsBeforeFailure));
    },
  );

  testWidgets(
    'partial automatic diary failure manually fills only the missing field',
    (tester) async {
      final repository = _DetailRepository(
        manualTranslations: const {'日记正文': 'Manual diary body'},
      );
      final controller = SocialController(repository);
      final translations = _AutoRepository(
        translations: const {
          '真实日记': 'Automatic diary title',
          '来自数据库的评论': 'Database comment',
        },
        failedSources: const {'日记正文'},
      );
      final autoController = _activeAutoController(translations);
      addTearDown(controller.dispose);
      addTearDown(autoController.dispose);

      await tester.pumpWidget(
        _autoHost(
          controller: autoController,
          child: DiaryDetailPage(
            controller: controller,
            diary: repository.diaries.single,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Automatic diary title'), findsOneWidget);
      expect(find.text('日记正文'), findsOneWidget);
      final callCount = translations.sources.length;

      await tester.tap(
        find.byKey(const Key('translation:diary-title:diary-1-button')),
      );
      await tester.pump();
      expect(find.text('真实日记'), findsOneWidget);
      expect(find.text('日记正文'), findsOneWidget);

      await tester.tap(
        find.byKey(const Key('translation:diary-title:diary-1-button')),
      );
      await tester.pumpAndSettle();

      expect(find.text('Automatic diary title'), findsOneWidget);
      expect(find.text('Manual diary body'), findsOneWidget);
      expect(repository.manualCalls, const [('日记正文', 'diary')]);
      expect(translations.sources, hasLength(callCount));
    },
  );
}

typedef _RequestRecord = (String, String, String, String);

Set<_RequestRecord> _requests(WidgetTester tester) => tester
    .widgetList<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
    .map((widget) => widget.request)
    .map(
      (request) => (
        request.contentType,
        request.contentId,
        request.field,
        request.sourceText,
      ),
    )
    .toSet();

final class _DetailRepository
    implements SocialRepository, SocialTranslationRepository {
  _DetailRepository({
    this.manualTranslations = const {},
    this.manualFailures = const {},
  });

  final Map<String, String> manualTranslations;
  final Set<String> manualFailures;
  final manualCalls = <(String, String)>[];
  final diaries = [_diary];
  final commentDiaryIds = <String>[];
  final likeWrites = <String>[];
  CommentDraft? publishedDraft;
  ReportTargetType? reportedType;
  String? reportedTargetId;
  String? reportedReason;

  @override
  Future<ContentTranslation> translateText({
    required String text,
    required String targetLanguage,
    required String contentType,
  }) async {
    manualCalls.add((text, contentType));
    if (manualFailures.contains(text)) {
      throw StateError('manual translation failed for $text');
    }
    return ContentTranslation(
      translatedText: manualTranslations[text] ?? 'Manual: $text',
      detectedLanguage: 'zh',
      targetLanguage: targetLanguage,
      provider: 'manual-test',
      cached: false,
    );
  }

  @override
  Future<List<Diary>> getMyDiaries({int offset = 0, int limit = 50}) async =>
      diaries.skip(offset).take(limit).toList();

  @override
  Future<List<Comment>> getComments(
    String diaryId, {
    int offset = 0,
    int limit = 50,
  }) async {
    commentDiaryIds.add(diaryId);
    return const [
      Comment(
        id: 'comment-1',
        diaryId: 'diary-1',
        userId: 'user-2',
        userName: '评论用户',
        content: '来自数据库的评论',
        likeCount: 2,
        isLiked: false,
      ),
    ];
  }

  @override
  Future<List<Comment>> getReplies(
    String parentId, {
    int offset = 0,
    int limit = 50,
  }) async =>
      const [
        Comment(
          id: 'reply-remote',
          diaryId: 'diary-1',
          userId: 'user-3',
          userName: '回复用户',
          content: '来自数据库的回复',
          parentId: 'comment-1',
          replyToUserName: '评论用户',
          likeCount: 1,
          isLiked: false,
        ),
      ];

  @override
  Future<Comment> publishComment(CommentDraft draft) async {
    publishedDraft = draft;
    return Comment(
      id: 'reply-1',
      diaryId: draft.diaryId,
      userId: 'current-user',
      userName: '当前用户',
      content: draft.content,
      parentId: draft.parentId,
      replyToUserId: draft.replyToUserId,
      replyToUserName: '评论用户',
      likeCount: 0,
      isLiked: false,
    );
  }

  @override
  Future<void> setLiked(
    LikeTargetType type,
    String targetId,
    bool liked,
  ) async {
    likeWrites.add('${type.wireValue}:$targetId:$liked');
  }

  @override
  Future<bool> hasReported(ReportTargetType type, String targetId) async =>
      false;

  @override
  Future<ReportReceipt> report({
    required ReportTargetType type,
    required String targetId,
    required String reason,
    String? description,
  }) async {
    reportedType = type;
    reportedTargetId = targetId;
    reportedReason = reason;
    return ReportReceipt(
      id: 'report-1',
      targetType: type,
      targetId: targetId,
      reason: reason,
      status: 'pending',
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _PendingAutoTranslation {
  const _PendingAutoTranslation(this.source, this.completer);

  final String source;
  final Completer<ContentTranslation> completer;
}

final class _AutoRepository implements TranslationRepository {
  _AutoRepository({
    required this.translations,
    this.failedSources = const {},
    this.holdResponses = false,
  });

  final Map<String, String> translations;
  final Set<String> failedSources;
  final bool holdResponses;
  final sources = <String>[];
  final _pending = <_PendingAutoTranslation>[];

  @override
  Future<ContentTranslation> translateText({
    required String text,
    required String targetLanguage,
    required String contentType,
  }) async {
    sources.add(text);
    if (failedSources.contains(text)) {
      throw StateError('automatic translation failed for $text');
    }
    if (holdResponses) {
      final completer = Completer<ContentTranslation>();
      _pending.add(_PendingAutoTranslation(text, completer));
      return completer.future;
    }
    return _response(text, targetLanguage);
  }

  void completeSource(String source) {
    final index = _pending.indexWhere((pending) => pending.source == source);
    if (index < 0) throw StateError('No pending translation for $source');
    final pending = _pending.removeAt(index);
    pending.completer.complete(_response(source, 'en-US'));
  }

  ContentTranslation _response(String source, String targetLanguage) =>
      ContentTranslation(
        translatedText: translations[source]!,
        detectedLanguage: 'zh',
        targetLanguage: targetLanguage,
        provider: 'auto-test',
        cached: false,
      );
}

AutoTranslationController _activeAutoController(
  TranslationRepository repository,
) {
  final controller = AutoTranslationController(
    repository: repository,
    maxConcurrent: 10,
  );
  controller.synchronize(
    enabled: true,
    authenticated: true,
    targetLanguage: 'en-US',
  );
  return controller;
}

Widget _autoHost({
  required AutoTranslationController controller,
  required Widget child,
}) =>
    MaterialApp(
      locale: const Locale('en', 'US'),
      supportedLocales: const [Locale('zh', 'CN'), Locale('en', 'US')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: AutoTranslationScope(
        controller: controller,
        enabled: true,
        targetLanguage: 'en-US',
        child: child,
      ),
    );

const _diary = Diary(
  id: 'diary-1',
  title: '真实日记',
  userId: 'user-1',
  authorName: '作者',
  content: '日记正文',
  images: [],
  tags: [],
  likeCount: 4,
  commentCount: 1,
  favoriteCount: 0,
  isLiked: false,
  status: 'published',
);
