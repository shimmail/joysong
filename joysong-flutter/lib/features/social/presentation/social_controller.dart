import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';

final class SocialActionResult<T> {
  const SocialActionResult._(
      {required this.succeeded, this.value, this.message});

  const SocialActionResult.success([T? value])
      : this._(succeeded: true, value: value);

  const SocialActionResult.failure(String message)
      : this._(succeeded: false, message: message);

  final bool succeeded;
  final T? value;
  final String? message;
}

final class SocialController extends ChangeNotifier {
  SocialController(this._repository, {this.currentUserId = ''});

  final SocialRepository _repository;
  SocialRepository get repository => _repository;
  final String currentUserId;
  final Set<String> _busyActions = <String>{};
  final Map<String, List<Comment>> _comments = <String, List<Comment>>{};
  final Map<String, List<Comment>> _replies = <String, List<Comment>>{};
  final Map<String, EngagementStatus> _likes = <String, EngagementStatus>{};
  final Map<String, EngagementStatus> _favorites = <String, EngagementStatus>{};
  final Set<String> _reportedTargets = <String>{};
  final Map<String, ContentTranslation> _translations =
      <String, ContentTranslation>{};
  final Set<String> _showingTranslations = <String>{};
  final Map<String, Review?> _reviews = <String, Review?>{};

  List<Diary> _diaries = const [];
  bool _isLoadingDiaries = false;
  bool _hasMoreDiaries = true;
  String? _errorMessage;

  List<Diary> get diaries => List.unmodifiable(_diaries);
  bool get isLoadingDiaries => _isLoadingDiaries;
  bool get hasMoreDiaries => _hasMoreDiaries;
  String? get errorMessage => _errorMessage;

  List<Comment> commentsFor(String diaryId) =>
      List.unmodifiable(_comments[diaryId] ?? const []);

  List<Comment> repliesFor(String parentId) =>
      List.unmodifiable(_replies[parentId] ?? const []);

  EngagementStatus? likeStatus(LikeTargetType type, String targetId) =>
      _likes[_likeKey(type, targetId)];

  EngagementStatus? favoriteStatus(
    FavoriteTargetType type,
    String targetId,
  ) =>
      _favorites[_favoriteKey(type, targetId)];

  bool hasReported(ReportTargetType type, String targetId) =>
      _reportedTargets.contains(_reportKey(type, targetId));

  ContentTranslation? translationFor(String contentId) =>
      _translations[contentId];

  bool isShowingTranslation(String contentId) =>
      _showingTranslations.contains(contentId);

  void toggleTranslationVisibility(String contentId) {
    if (!_translations.containsKey(contentId)) return;
    if (!_showingTranslations.remove(contentId)) {
      _showingTranslations.add(contentId);
    }
    notifyListeners();
  }

  Future<SocialActionResult<ContentTranslation>> translateComment({
    required String commentId,
    required String text,
    required String targetLanguage,
  }) {
    return _guarded('translation:comment:$commentId', () async {
      final repository = _repository;
      if (repository is! SocialTranslationRepository) {
        return const SocialActionResult.failure('当前版本暂不支持翻译');
      }
      final translationRepository = repository as SocialTranslationRepository;
      final translation = await translationRepository.translateText(
        text: text,
        targetLanguage: targetLanguage,
        contentType: 'comment',
      );
      _translations[commentId] = translation;
      _showingTranslations.add(commentId);
      return SocialActionResult.success(translation);
    }, fallback: 'AI翻译暂时不可用，请稍后重试');
  }

  Future<SocialActionResult<bool>> loadReportedStatus(
    ReportTargetType type,
    String targetId,
  ) {
    final targetKey = _reportKey(type, targetId);
    return _guarded('report:check:$targetKey', () async {
      final reported = await _repository.hasReported(type, targetId);
      if (reported) {
        _reportedTargets.add(targetKey);
      } else {
        _reportedTargets.remove(targetKey);
      }
      return SocialActionResult.success(reported);
    }, fallback: '举报状态加载失败');
  }

  Review? reviewForOrder(String orderId) => _reviews[orderId];

  bool isBusy(String actionKey) => _busyActions.contains(actionKey);

  Future<SocialActionResult<List<Diary>>> loadMyDiaries({
    bool refresh = false,
    int limit = 50,
  }) async {
    if (_isLoadingDiaries) {
      return const SocialActionResult.failure('日记正在加载');
    }
    if (!refresh && !_hasMoreDiaries) {
      return SocialActionResult.success(diaries);
    }
    _isLoadingDiaries = true;
    _clearError();
    notifyListeners();
    try {
      final offset = refresh ? 0 : _diaries.length;
      final page = await _repository.getMyDiaries(offset: offset, limit: limit);
      _diaries = refresh
          ? List.of(page)
          : _mergeById(_diaries, page, (item) => item.id);
      _hasMoreDiaries = page.length >= limit;
      return SocialActionResult.success(diaries);
    } catch (error) {
      final message = _messageFor(error, '日记加载失败，请重试');
      _errorMessage = message;
      return SocialActionResult.failure(message);
    } finally {
      _isLoadingDiaries = false;
      notifyListeners();
    }
  }

  Future<SocialActionResult<Diary>> publishDiary(DiaryDraft draft) async {
    const key = 'diary:publish';
    return _guarded(key, () async {
      final diary = await _repository.publishDiary(draft);
      _diaries = [diary, ..._diaries.where((item) => item.id != diary.id)];
      return SocialActionResult.success(diary);
    }, fallback: '日记发布失败，请重试');
  }

  Future<SocialActionResult<Diary>> updateDiary(
    String diaryId,
    DiaryUpdate update,
  ) {
    final key = 'diary:update:$diaryId';
    return _guarded(key, () async {
      final diary = await _repository.updateDiary(diaryId, update);
      _diaries = _diaries
          .map((item) => item.id == diary.id ? diary : item)
          .toList(growable: false);
      return SocialActionResult.success(diary);
    }, fallback: '日记更新失败，请重试');
  }

  Future<SocialActionResult<void>> deleteDiary(String diaryId) async {
    final key = 'diary:delete:$diaryId';
    if (!_begin(key)) {
      return const SocialActionResult.failure('操作正在进行');
    }
    final original = _diaries;
    _diaries =
        _diaries.where((item) => item.id != diaryId).toList(growable: false);
    notifyListeners();
    try {
      await _repository.deleteDiary(diaryId);
      return const SocialActionResult.success();
    } catch (error) {
      _diaries = original;
      final message = _messageFor(error, '删除失败，已恢复日记');
      _errorMessage = message;
      return SocialActionResult.failure(message);
    } finally {
      _end(key);
    }
  }

  Future<SocialActionResult<List<Comment>>> loadComments(
    String diaryId, {
    bool refresh = false,
    int limit = 50,
  }) async {
    final current = _comments[diaryId] ?? const <Comment>[];
    final key = 'comments:load:$diaryId';
    return _guarded(key, () async {
      final page = await _repository.getComments(
        diaryId,
        offset: refresh ? 0 : current.length,
        limit: limit,
      );
      final next = refresh
          ? List.of(page)
          : _mergeById(current, page, (item) => item.id);
      _comments[diaryId] = next;
      return SocialActionResult.success(List.unmodifiable(next));
    }, fallback: '评论加载失败，请重试');
  }

  /// Loads the complete visible thread: top-level comments first, then every
  /// reply group. Replies remain nested under their parent in the UI.
  Future<SocialActionResult<List<Comment>>> loadCommentThread(
    String diaryId, {
    bool refresh = true,
    int limit = 50,
  }) async {
    final commentsResult = await loadComments(
      diaryId,
      refresh: refresh,
      limit: limit,
    );
    if (!commentsResult.succeeded) return commentsResult;
    final comments = commentsResult.value ?? const <Comment>[];
    await Future.wait(
      comments.map(
        (comment) => loadReplies(comment.id, refresh: refresh, limit: limit),
      ),
    );
    return SocialActionResult.success(comments);
  }

  Future<SocialActionResult<List<Comment>>> loadReplies(
    String parentId, {
    bool refresh = false,
    int limit = 50,
  }) async {
    final current = _replies[parentId] ?? const <Comment>[];
    final key = 'replies:load:$parentId';
    return _guarded(key, () async {
      final page = await _repository.getReplies(
        parentId,
        offset: refresh ? 0 : current.length,
        limit: limit,
      );
      final next = refresh
          ? List.of(page)
          : _mergeById(current, page, (item) => item.id);
      _replies[parentId] = next;
      return SocialActionResult.success(List.unmodifiable(next));
    }, fallback: '回复加载失败，请重试');
  }

  Future<SocialActionResult<Comment>> publishComment(CommentDraft draft) {
    final key = 'comment:publish:${draft.diaryId}';
    return _guarded(key, () async {
      final comment = await _repository.publishComment(draft);
      if (comment.parentId == null) {
        final current = _comments[comment.diaryId] ?? const <Comment>[];
        _comments[comment.diaryId] = [
          comment,
          ...current.where((item) => item.id != comment.id),
        ];
      } else {
        final parentId = comment.parentId!;
        final current = _replies[parentId] ?? const <Comment>[];
        _replies[parentId] = _mergeById(current, [comment], (item) => item.id);
      }
      return SocialActionResult.success(comment);
    }, fallback: '评论发送失败，请重试');
  }

  Future<SocialActionResult<void>> deleteComment(Comment comment) async {
    final key = 'comment:delete:${comment.id}';
    if (!_begin(key)) {
      return const SocialActionResult.failure('操作正在进行');
    }
    final originalComments = Map<String, List<Comment>>.from(_comments);
    final originalReplies = Map<String, List<Comment>>.from(_replies);
    if (comment.parentId == null) {
      _comments[comment.diaryId] = (_comments[comment.diaryId] ?? const [])
          .where((item) => item.id != comment.id)
          .toList(growable: false);
      _replies.remove(comment.id);
    } else {
      _replies[comment.parentId!] = (_replies[comment.parentId!] ?? const [])
          .where((item) => item.id != comment.id)
          .toList(growable: false);
    }
    notifyListeners();
    try {
      await _repository.deleteComment(comment.id);
      return const SocialActionResult.success();
    } catch (error) {
      _comments
        ..clear()
        ..addAll(originalComments);
      _replies
        ..clear()
        ..addAll(originalReplies);
      final message = _messageFor(error, '删除失败，已恢复评论');
      _errorMessage = message;
      return SocialActionResult.failure(message);
    } finally {
      _end(key);
    }
  }

  void seedLikeStatus(
    LikeTargetType type,
    String targetId,
    EngagementStatus status,
  ) {
    _likes[_likeKey(type, targetId)] = status;
  }

  Future<SocialActionResult<EngagementStatus>> toggleLike(
    LikeTargetType type,
    String targetId, {
    EngagementStatus initial = const EngagementStatus(active: false, count: 0),
  }) =>
      _toggleEngagement(
        key: _likeKey(type, targetId),
        store: _likes,
        initial: initial,
        remoteWrite: (active) => _repository.setLiked(type, targetId, active),
        fallback: '点赞失败，已恢复原状态',
      );

  void seedFavoriteStatus(
    FavoriteTargetType type,
    String targetId,
    EngagementStatus status,
  ) {
    _favorites[_favoriteKey(type, targetId)] = status;
  }

  Future<SocialActionResult<EngagementStatus>> loadFavoriteStatus(
    FavoriteTargetType type,
    String targetId,
  ) {
    final targetKey = _favoriteKey(type, targetId);
    return _guarded('favorite:load:$targetKey', () async {
      final status = await _repository.getFavoriteStatus(type, targetId);
      _favorites[targetKey] = status;
      return SocialActionResult.success(status);
    }, fallback: '收藏状态加载失败');
  }

  Future<SocialActionResult<EngagementStatus>> toggleFavorite(
    FavoriteTargetType type,
    String targetId, {
    required String targetName,
    String targetImage = '',
    EngagementStatus initial = const EngagementStatus(active: false, count: 0),
  }) =>
      _toggleEngagement(
        key: _favoriteKey(type, targetId),
        store: _favorites,
        initial: initial,
        remoteWrite: (active) => _repository.setFavorited(
          type,
          targetId,
          active,
          targetName: targetName,
          targetImage: targetImage,
        ),
        fallback: '收藏失败，已恢复原状态',
      );

  Future<SocialActionResult<ReportReceipt>> report({
    required ReportTargetType type,
    required String targetId,
    required String reason,
    String? description,
  }) {
    final targetKey = _reportKey(type, targetId);
    return _guarded('report:$targetKey', () async {
      final receipt = await _repository.report(
        type: type,
        targetId: targetId,
        reason: reason,
        description: description,
      );
      _reportedTargets.add(targetKey);
      return SocialActionResult.success(receipt);
    }, fallback: '举报提交失败，请重试');
  }

  Future<SocialActionResult<Review?>> loadOrderReview(String orderId) {
    return _guarded('review:load:$orderId', () async {
      final review = await _repository.getOrderReview(orderId);
      _reviews[orderId] = review;
      return SocialActionResult.success(review);
    }, fallback: '评价加载失败，请重试');
  }

  Future<SocialActionResult<Review>> submitOrderReview(
    String orderId,
    ReviewDraft draft,
  ) {
    return _guarded('review:submit:$orderId', () async {
      final review = await _repository.submitOrderReview(orderId, draft);
      _reviews[orderId] = review;
      return SocialActionResult.success(review);
    }, fallback: '评价提交失败，请重试');
  }

  Future<SocialActionResult<Review>> updateReview(
    String reviewId,
    ReviewDraft draft,
  ) {
    return _guarded('review:update:$reviewId', () async {
      final review = await _repository.updateReview(reviewId, draft);
      _reviews[review.orderId] = review;
      return SocialActionResult.success(review);
    }, fallback: '评价更新失败，请重试');
  }

  Future<SocialActionResult<void>> deleteReview(Review review) async {
    final key = 'review:delete:${review.id}';
    if (!_begin(key)) {
      return const SocialActionResult.failure('操作正在进行');
    }
    final original = _reviews[review.orderId];
    _reviews[review.orderId] = null;
    notifyListeners();
    try {
      await _repository.deleteReview(review.id);
      return const SocialActionResult.success();
    } catch (error) {
      _reviews[review.orderId] = original;
      final message = _messageFor(error, '删除失败，已恢复评价');
      _errorMessage = message;
      return SocialActionResult.failure(message);
    } finally {
      _end(key);
    }
  }

  Future<SocialActionResult<String>> uploadPublicMedia(
    PublicMediaDraft media,
  ) {
    final key = 'media:upload:${identityHashCode(media)}';
    return _guarded(key, () async {
      await for (final progress in _repository.uploadPublicMedia(media)) {
        if (progress.stage == UploadStage.failed) {
          return SocialActionResult.failure(
            progress.message ?? '图片上传失败，请重试',
          );
        }
        final url = progress.url?.trim();
        if (progress.stage == UploadStage.complete &&
            url != null &&
            url.isNotEmpty) {
          return SocialActionResult.success(url);
        }
      }
      return const SocialActionResult.failure('图片上传未返回可用地址');
    }, fallback: '图片上传失败，请重试');
  }

  Future<SocialActionResult<EngagementStatus>> _toggleEngagement({
    required String key,
    required Map<String, EngagementStatus> store,
    required EngagementStatus initial,
    required Future<void> Function(bool active) remoteWrite,
    required String fallback,
  }) async {
    final actionKey = 'engagement:$key';
    if (!_begin(actionKey)) {
      return const SocialActionResult.failure('操作正在进行');
    }
    final original = store[key] ?? initial;
    final optimistic = original.toggled();
    store[key] = optimistic;
    notifyListeners();
    try {
      await remoteWrite(optimistic.active);
      return SocialActionResult.success(optimistic);
    } catch (error) {
      store[key] = original;
      final message = _messageFor(error, fallback);
      _errorMessage = message;
      return SocialActionResult.failure(message);
    } finally {
      _end(actionKey);
    }
  }

  Future<SocialActionResult<T>> _guarded<T>(
    String key,
    Future<SocialActionResult<T>> Function() action, {
    required String fallback,
  }) async {
    if (!_begin(key)) {
      return const SocialActionResult.failure('操作正在进行');
    }
    _clearError();
    notifyListeners();
    try {
      final result = await action();
      return result;
    } catch (error) {
      final message = _messageFor(error, fallback);
      _errorMessage = message;
      return SocialActionResult.failure(message);
    } finally {
      _end(key);
    }
  }

  bool _begin(String key) {
    if (_busyActions.contains(key)) {
      return false;
    }
    _busyActions.add(key);
    return true;
  }

  void _end(String key) {
    _busyActions.remove(key);
    notifyListeners();
  }

  void _clearError() {
    _errorMessage = null;
  }
}

List<T> _mergeById<T>(
  List<T> current,
  List<T> incoming,
  String Function(T item) idOf,
) {
  final result = <T>[];
  final seen = <String>{};
  for (final item in [...current, ...incoming]) {
    if (seen.add(idOf(item))) {
      result.add(item);
    }
  }
  return result;
}

String _likeKey(LikeTargetType type, String targetId) =>
    '${type.wireValue}:$targetId';

String _favoriteKey(FavoriteTargetType type, String targetId) =>
    '${type.wireValue}:$targetId';

String _reportKey(ReportTargetType type, String targetId) =>
    '${type.wireValue}:$targetId';

String _messageFor(Object error, String fallback) {
  if (error is ApiException && error.message.isNotEmpty) {
    return error.message;
  }
  if (error is ArgumentError && error.message != null) {
    return error.message.toString();
  }
  return fallback;
}
