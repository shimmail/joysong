import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
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
    expect(find.text('赞 3'), findsOneWidget);

    await tester.tap(find.byKey(const Key('comment-replies-comment-1')));
    await tester.pumpAndSettle();
    expect(find.text('来自数据库的回复'), findsOneWidget);
    await tester.tap(find.byKey(const Key('reply-like-reply-remote')));
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
    expect(find.text('这是一条真实回复'), findsOneWidget);

    await tester.tap(find.byKey(const Key('diary-report')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('report-submit')));
    await tester.pumpAndSettle();

    expect(repository.reportedType, ReportTargetType.diary);
    expect(repository.reportedTargetId, 'diary-1');
    expect(repository.reportedReason, 'spam');
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
    expect(find.text('Reply'), findsOneWidget);
    expect(find.text('View replies'), findsOneWidget);
    expect(find.text('Report'), findsOneWidget);
    expect(find.byTooltip('Send'), findsOneWidget);
  });
}

final class _DetailRepository implements SocialRepository {
  final diaries = [_diary];
  final commentDiaryIds = <String>[];
  final likeWrites = <String>[];
  CommentDraft? publishedDraft;
  ReportTargetType? reportedType;
  String? reportedTargetId;
  String? reportedReason;

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
