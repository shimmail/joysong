import 'package:joysong_flutter/features/social/domain/social_models.dart';

abstract interface class SocialRepository {
  Future<PublicUserProfile> getPublicUserProfile(String userId);

  Future<List<Diary>> getUserDiaries(String userId);

  Future<List<Diary>> getMyDiaries({int offset = 0, int limit = 50});

  Future<Diary> publishDiary(DiaryDraft draft);

  Future<Diary> updateDiary(String id, DiaryUpdate update);

  Future<void> deleteDiary(String id);

  Future<String> createDiaryShareUrl(String id);

  Future<List<Comment>> getComments(
    String diaryId, {
    int offset = 0,
    int limit = 50,
  });

  Future<List<Comment>> getReplies(
    String parentId, {
    int offset = 0,
    int limit = 50,
  });

  Future<Comment> publishComment(CommentDraft draft);

  Future<void> deleteComment(String id);

  Future<EngagementStatus> getLikeStatus(LikeTargetType type, String targetId);

  Future<void> setLiked(LikeTargetType type, String targetId, bool liked);

  Future<List<FavoriteItem>> getFavorites({int offset = 0, int limit = 50});

  Future<EngagementStatus> getFavoriteStatus(
    FavoriteTargetType type,
    String targetId,
  );

  Future<void> setFavorited(
    FavoriteTargetType type,
    String targetId,
    bool favorited, {
    String targetName = '',
    String targetImage = '',
  });

  Future<bool> hasReported(ReportTargetType type, String targetId);

  Future<ReportReceipt> report({
    required ReportTargetType type,
    required String targetId,
    required String reason,
    String? description,
  });

  Future<Review?> getOrderReview(String orderId);

  Future<Review> submitOrderReview(String orderId, ReviewDraft draft);

  Future<Review> updateReview(String reviewId, ReviewDraft draft);

  Future<void> deleteReview(String reviewId);

  Stream<PublicUploadProgress> uploadPublicMedia(PublicMediaDraft media);
}

abstract interface class SocialTranslationRepository {
  Future<ContentTranslation> translateText({
    required String text,
    required String targetLanguage,
    required String contentType,
  });
}
