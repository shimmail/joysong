import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/core/network/public_media_url.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';

abstract interface class SocialRemoteDataSource {
  Future<PublicUserProfile> getPublicUserProfile(String userId);
  Future<List<Diary>> getUserDiaries(String userId);
  Future<List<Diary>> getMyDiaries({required int offset, required int limit});
  Future<Diary> publishDiary(DiaryDraft draft);
  Future<Diary> updateDiary(String id, DiaryUpdate update);
  Future<void> deleteDiary(String id);
  Future<String> createDiaryShareUrl(String id);
  Future<List<Comment>> getComments(
    String diaryId, {
    required int offset,
    required int limit,
  });
  Future<List<Comment>> getReplies(
    String parentId, {
    required int offset,
    required int limit,
  });
  Future<Comment> publishComment(CommentDraft draft);
  Future<void> deleteComment(String id);
  Future<EngagementStatus> getLikeStatus(LikeTargetType type, String targetId);
  Future<void> setLiked(LikeTargetType type, String targetId, bool liked);
  Future<List<FavoriteItem>> getFavorites(
      {required int offset, required int limit});
  Future<EngagementStatus> getFavoriteStatus(
    FavoriteTargetType type,
    String targetId,
  );
  Future<void> setFavorited(
    FavoriteTargetType type,
    String targetId,
    bool favorited, {
    required String targetName,
    required String targetImage,
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
}

abstract interface class SocialTranslationRemoteDataSource {
  Future<ContentTranslation> translateText({
    required String text,
    required String targetLanguage,
    required String contentType,
  });
}

final class ApiSocialRemoteDataSource
    implements SocialRemoteDataSource, SocialTranslationRemoteDataSource {
  ApiSocialRemoteDataSource(this._apiClient)
      : _mediaResolver = ApiPublicMediaUrlResolver(_apiClient.apiRoot);

  final ApiClient _apiClient;
  final PublicMediaUrlResolver _mediaResolver;

  @override
  Future<PublicUserProfile> getPublicUserProfile(String userId) async {
    final value = await _apiClient.get<PublicUserProfile>(
      'users/${_segment(userId)}/profile',
      decodeData: (json) => _publicUser(
        resolvePublicMediaUrlsInJson(json, resolver: _mediaResolver),
      ),
    );
    return _requireData(value, '用户主页响应');
  }

  @override
  Future<List<Diary>> getUserDiaries(String userId) => _listGet(
        'users/${_segment(userId)}/diaries',
        query: const {},
        decodeItem: _resolvedDiary,
        label: '用户日记列表',
      );

  @override
  Future<List<Diary>> getMyDiaries({required int offset, required int limit}) =>
      _listGet(
        'diaries/my',
        query: {'offset': offset, 'limit': limit},
        decodeItem: _resolvedDiary,
        label: '日记列表',
      );

  @override
  Future<Diary> publishDiary(DiaryDraft draft) async {
    final value = await _apiClient.post<Diary>(
      'diaries',
      body: _diaryDraftJson(draft),
      decodeData: _resolvedDiary,
    );
    return _requireData(value, '发布日记响应');
  }

  @override
  Future<Diary> updateDiary(String id, DiaryUpdate update) async {
    final value = await _apiClient.put<Diary>(
      'diaries/${_segment(id)}',
      body: _diaryUpdateJson(update),
      decodeData: _resolvedDiary,
    );
    return _requireData(value, '更新日记响应');
  }

  @override
  Future<void> deleteDiary(String id) => _delete('diaries/${_segment(id)}');

  @override
  Future<String> createDiaryShareUrl(String id) async {
    final value = await _apiClient.post<String>(
      'diaries/${_segment(id)}/share',
      decodeData: (json) {
        final map = _map(json, '分享链接');
        return _requiredString(map['url'] ?? map['shareUrl'], 'url');
      },
    );
    return _requireData(value, '分享链接响应');
  }

  @override
  Future<List<Comment>> getComments(
    String diaryId, {
    required int offset,
    required int limit,
  }) =>
      _listGet(
        'comments',
        query: {'diaryId': diaryId, 'offset': offset, 'limit': limit},
        decodeItem: _comment,
        label: '评论列表',
      );

  @override
  Future<List<Comment>> getReplies(
    String parentId, {
    required int offset,
    required int limit,
  }) =>
      _listGet(
        'comments/replies',
        query: {'parentId': parentId, 'offset': offset, 'limit': limit},
        decodeItem: _comment,
        label: '回复列表',
      );

  @override
  Future<Comment> publishComment(CommentDraft draft) async {
    final value = await _apiClient.post<Comment>(
      'comments',
      body: {
        'diaryId': draft.diaryId,
        'content': draft.content.trim(),
        if (draft.parentId != null) 'parentId': draft.parentId,
        if (draft.replyToUserId != null) 'replyToUserId': draft.replyToUserId,
      },
      decodeData: _comment,
    );
    return _requireData(value, '发表评论响应');
  }

  @override
  Future<void> deleteComment(String id) => _delete('comments/${_segment(id)}');

  @override
  Future<ContentTranslation> translateText({
    required String text,
    required String targetLanguage,
    required String contentType,
  }) async {
    final value = await _apiClient.post<ContentTranslation>(
      'translations',
      body: {
        'text': text.trim(),
        'targetLanguage': targetLanguage.trim(),
        'contentType': contentType.trim(),
      },
      decodeData: _translation,
    );
    return _requireData(value, '翻译响应');
  }

  @override
  Future<EngagementStatus> getLikeStatus(
    LikeTargetType type,
    String targetId,
  ) async {
    final value = await _apiClient.get<EngagementStatus>(
      'likes/${type.wireValue}/${_segment(targetId)}',
      decodeData: (json) => _engagement(json, activeKey: 'liked'),
    );
    return _requireData(value, '点赞状态响应');
  }

  @override
  Future<void> setLiked(
    LikeTargetType type,
    String targetId,
    bool liked,
  ) async {
    if (liked) {
      await _apiClient.post<Object?>(
        'likes',
        body: {'targetType': type.wireValue, 'targetId': targetId},
        decodeData: (json) => json,
      );
    } else {
      await _delete('likes/${type.wireValue}/${_segment(targetId)}');
    }
  }

  @override
  Future<List<FavoriteItem>> getFavorites({
    required int offset,
    required int limit,
  }) =>
      _listGet(
        'favorites',
        query: {'offset': offset, 'limit': limit},
        decodeItem: _favorite,
        label: '收藏列表',
      );

  @override
  Future<EngagementStatus> getFavoriteStatus(
    FavoriteTargetType type,
    String targetId,
  ) async {
    _ensureKnownFavorite(type);
    final value = await _apiClient.get<EngagementStatus>(
      'favorites/${type.wireValue}/${_segment(targetId)}',
      decodeData: (json) => _engagement(json, activeKey: 'favorited'),
    );
    return _requireData(value, '收藏状态响应');
  }

  @override
  Future<void> setFavorited(
    FavoriteTargetType type,
    String targetId,
    bool favorited, {
    required String targetName,
    required String targetImage,
  }) async {
    _ensureKnownFavorite(type);
    if (favorited) {
      await _apiClient.post<Object?>(
        'favorites',
        body: {
          'targetType': type.wireValue,
          'targetId': targetId,
          'targetName': targetName,
          'targetImage': targetImage,
        },
        decodeData: (json) => json,
      );
    } else {
      await _delete('favorites/${type.wireValue}/${_segment(targetId)}');
    }
  }

  @override
  Future<bool> hasReported(ReportTargetType type, String targetId) async {
    _ensureKnownReport(type);
    final value = await _apiClient.get<bool>(
      'reports/check/${type.wireValue}/${_segment(targetId)}',
      decodeData: (json) {
        final map = _map(json, '举报状态响应');
        return _boolean(map['reported'], 'reported');
      },
    );
    return _requireData(value, '举报状态响应');
  }

  @override
  Future<ReportReceipt> report({
    required ReportTargetType type,
    required String targetId,
    required String reason,
    String? description,
  }) async {
    _ensureKnownReport(type);
    final value = await _apiClient.post<ReportReceipt>(
      'reports',
      body: {
        'targetType': type.wireValue,
        'targetId': targetId,
        'reason': reason.trim(),
        if (description != null) 'description': description.trim(),
      },
      decodeData: _report,
    );
    return _requireData(value, '举报响应');
  }

  @override
  Future<Review?> getOrderReview(String orderId) async {
    try {
      return await _apiClient.get<Review>(
        'reviews/order/${_segment(orderId)}',
        decodeData: _review,
      );
    } on ApiException catch (error) {
      if (error.httpStatus == 404 || error.businessCode == 404) {
        return null;
      }
      rethrow;
    }
  }

  @override
  Future<Review> submitOrderReview(String orderId, ReviewDraft draft) async {
    final value = await _apiClient.post<Review>(
      'orders/${_segment(orderId)}/review',
      body: _reviewDraftJson(draft),
      decodeData: _review,
    );
    return _requireData(value, '提交评价响应');
  }

  @override
  Future<Review> updateReview(String reviewId, ReviewDraft draft) async {
    final value = await _apiClient.put<Review>(
      'reviews/${_segment(reviewId)}',
      body: _reviewDraftJson(draft),
      decodeData: _review,
    );
    return _requireData(value, '更新评价响应');
  }

  @override
  Future<void> deleteReview(String reviewId) =>
      _delete('reviews/${_segment(reviewId)}');

  Future<void> _delete(String path) async {
    await _apiClient.delete<Object?>(path, decodeData: (json) => json);
  }

  Diary _resolvedDiary(Object? json) => _diary(
        resolvePublicMediaUrlsInJson(json, resolver: _mediaResolver),
      );

  Future<List<T>> _listGet<T>(
    String path, {
    required Map<String, Object?> query,
    required T Function(Object?) decodeItem,
    required String label,
  }) async {
    final value = await _apiClient.get<List<T>>(
      path,
      query: query,
      decodeData: (json) {
        if (json is! List) {
          throw FormatException('$label不是 JSON 数组');
        }
        return List.unmodifiable(json.map(decodeItem));
      },
    );
    return _requireData(value, label);
  }
}

Map<String, Object?> _diaryDraftJson(DiaryDraft draft) => {
      'title': draft.title.trim(),
      'content': draft.content.trim(),
      'images': draft.images.join(','),
      'tags': draft.tags.join(','),
      if (draft.rating != null) 'rating': draft.rating,
      if (draft.doctorId != null) 'doctorId': draft.doctorId,
      if (draft.projectId != null) 'projectId': draft.projectId,
      if (draft.institutionId != null) 'institutionId': draft.institutionId,
      if (draft.institutionProjectId != null)
        'institutionProjectId': draft.institutionProjectId,
      if (draft.orderId != null) 'orderId': draft.orderId,
      'beforeImages': draft.beforeImages.join(','),
      'afterImages': draft.afterImages.join(','),
      'status': draft.status.wireValue,
    };

Map<String, Object?> _diaryUpdateJson(DiaryUpdate update) => {
      if (update.title != null) 'title': update.title!.trim(),
      if (update.content != null) 'content': update.content!.trim(),
      if (update.images != null) 'images': update.images!.join(','),
      if (update.tags != null) 'tags': update.tags!.join(','),
      if (update.rating != null) 'rating': update.rating,
      if (update.doctorId != null) 'doctorId': update.doctorId,
      if (update.projectId != null) 'projectId': update.projectId,
      if (update.institutionId != null) 'institutionId': update.institutionId,
      if (update.institutionProjectId != null)
        'institutionProjectId': update.institutionProjectId,
      if (update.orderId != null) 'orderId': update.orderId,
      if (update.beforeImages != null)
        'beforeImages': update.beforeImages!.join(','),
      if (update.afterImages != null)
        'afterImages': update.afterImages!.join(','),
      if (update.status != null) 'status': update.status!.wireValue,
    };

Map<String, Object?> _reviewDraftJson(ReviewDraft draft) => {
      'rating': draft.rating,
      'content': draft.content.trim(),
      'tags': draft.tags.join(','),
      'images': draft.images.join(','),
    };

Diary _diary(Object? json) {
  final map = _map(json, '日记');
  return Diary(
    id: _requiredString(map['id'], 'id'),
    title: _requiredString(map['title'], 'title'),
    userId: _string(map['userId']),
    authorName: _string(map['authorName']),
    authorAvatar: _string(map['authorAvatar']),
    content: _string(map['content']),
    coverImage: _string(map['coverImage']),
    images: _stringList(map['imageUrls'], fallback: map['images']),
    tags: _csv(map['tags']),
    likeCount: _integer(map['likeCount']),
    commentCount: _integer(map['commentCount']),
    favoriteCount: _integer(map['favoriteCount']),
    isLiked: _optionalBoolean(map['isLiked']),
    publishDate: _string(map['publishDate']),
    createdAt: _date(map['createdAt']),
    rating: _integer(map['rating']),
    status: _string(map['status'], fallback: 'published'),
    doctorId: _string(map['doctorId']),
    projectId: _string(map['projectId']),
    institutionId: _string(map['institutionId']),
    institutionProjectId: _string(map['institutionProjectId']),
    orderId: _string(map['orderId']),
    projectName: _string(map['projectName']),
    doctorName: _string(map['doctorName']),
    institutionName: _string(map['institutionName']),
    beforeImages:
        _stringList(map['beforeImageUrls'], fallback: map['beforeImages']),
    afterImages:
        _stringList(map['afterImageUrls'], fallback: map['afterImages']),
  );
}

PublicUserProfile _publicUser(Object? json) {
  final map = _map(json, '用户主页');
  return PublicUserProfile(
    id: _requiredString(map['id'], 'id'),
    nickname: _string(map['nickname'], fallback: '用户'),
    avatar: _string(map['avatar']),
    gender: _string(map['gender']),
    bio: _string(map['bio']),
    city: _string(map['city']),
    birthday: _date(map['birthday']),
    diaryCount: _integer(map['diaryCount']),
    followingCount: _integer(map['followingCount']),
    followerCount: _integer(map['followerCount']),
  );
}

Comment _comment(Object? json) {
  final map = _map(json, '评论');
  return Comment(
    id: _requiredString(map['id'], 'id'),
    diaryId: _requiredString(map['diaryId'], 'diaryId'),
    userId: _requiredString(map['userId'], 'userId'),
    userName: _string(map['userName'], fallback: '已注销用户'),
    userAvatar: _string(map['userAvatar']),
    content: _string(map['content']),
    parentId: _nullableString(map['parentId']),
    replyToUserId: _nullableString(map['replyToUserId']),
    replyToUserName: _nullableString(map['replyToUserName']),
    createdAt: _date(map['createdAt']),
    likeCount: _integer(map['likeCount']),
    isLiked: _optionalBoolean(map['isLiked']),
  );
}

FavoriteItem _favorite(Object? json) {
  final map = _map(json, '收藏');
  final rawType = _string(map['targetType']).toUpperCase();
  return FavoriteItem(
    id: _requiredString(map['id'], 'id'),
    userId: _requiredString(map['userId'], 'userId'),
    targetType: FavoriteTargetType.values.firstWhere(
      (value) => value.wireValue == rawType,
      orElse: () => FavoriteTargetType.unknown,
    ),
    targetId: _requiredString(map['targetId'], 'targetId'),
    targetName: _string(map['targetName']),
    targetImage: _string(map['targetImage']),
    createdAt: _date(map['createdAt']),
  );
}

EngagementStatus _engagement(Object? json, {required String activeKey}) {
  final map = _map(json, '互动状态');
  return EngagementStatus(
    active: _boolean(map[activeKey], activeKey),
    count: _integer(map['count']),
  );
}

ContentTranslation _translation(Object? json) {
  final map = _map(json, '翻译');
  return ContentTranslation(
    translatedText: _requiredString(map['translatedText'], 'translatedText'),
    detectedLanguage: _string(map['detectedLanguage'], fallback: 'und'),
    targetLanguage: _requiredString(map['targetLanguage'], 'targetLanguage'),
    provider: _string(map['provider'], fallback: 'AI'),
    cached: _optionalBoolean(map['cached']),
  );
}

ReportReceipt _report(Object? json) {
  final map = _map(json, '举报');
  final rawType = _string(map['targetType']).toLowerCase();
  return ReportReceipt(
    id: _requiredString(map['id'], 'id'),
    targetType: ReportTargetType.values.firstWhere(
      (value) => value.wireValue == rawType,
      orElse: () => ReportTargetType.unknown,
    ),
    targetId: _requiredString(map['targetId'], 'targetId'),
    reason: _string(map['reason']),
    description: _nullableString(map['description']),
    status: _string(map['status'], fallback: 'pending'),
    createdAt: _date(map['createdAt']),
  );
}

Review _review(Object? json) {
  final map = _map(json, '评价');
  return Review(
    id: _requiredString(map['id'], 'id'),
    orderId: _requiredString(map['orderId'], 'orderId'),
    userId: _requiredString(map['userId'], 'userId'),
    userName: _string(map['userName']),
    doctorId: _string(map['doctorId']),
    rating: _integer(map['rating']),
    content: _string(map['content']),
    tags: _csv(map['tags']),
    images: _csv(map['images']),
    createdAt: _date(map['createdAt']),
  );
}

Map<String, dynamic> _map(Object? value, String label) {
  if (value is! Map) {
    throw FormatException('$label不是 JSON 对象');
  }
  try {
    return value.cast<String, dynamic>();
  } on TypeError {
    throw FormatException('$label包含无效字段');
  }
}

T _requireData<T>(T? value, String label) {
  if (value == null) {
    throw FormatException('$label data 为空');
  }
  return value;
}

String _requiredString(Object? value, String field) {
  final result = _string(value);
  if (result.isEmpty) {
    throw FormatException('响应缺少 $field');
  }
  return result;
}

String _string(Object? value, {String fallback = ''}) =>
    value?.toString() ?? fallback;

String? _nullableString(Object? value) => value?.toString();

int _integer(Object? value) {
  if (value is num) {
    return value.toInt();
  }
  return int.tryParse(value?.toString() ?? '') ?? 0;
}

bool _boolean(Object? value, String field) {
  if (value is! bool) {
    throw FormatException('响应缺少有效的 $field');
  }
  return value;
}

bool _optionalBoolean(Object? value) => value is bool && value;

DateTime? _date(Object? value) {
  final text = _string(value).trim();
  return text.isEmpty ? null : DateTime.tryParse(text);
}

List<String> _csv(Object? value) {
  final text = _string(value);
  if (text.trim().isEmpty) {
    return const [];
  }
  return List.unmodifiable(
    text.split(',').map((item) => item.trim()).where((item) => item.isNotEmpty),
  );
}

List<String> _stringList(Object? value, {Object? fallback}) {
  if (value is List) {
    return List.unmodifiable(
      value
          .map((item) => item?.toString().trim() ?? '')
          .where((item) => item.isNotEmpty),
    );
  }
  return _csv(fallback ?? value);
}

String _segment(String value) => Uri.encodeComponent(value);

void _ensureKnownFavorite(FavoriteTargetType type) {
  if (type == FavoriteTargetType.unknown) {
    throw ArgumentError.value(type, 'type', '未知收藏类型不能用于写操作');
  }
}

void _ensureKnownReport(ReportTargetType type) {
  if (type == ReportTargetType.unknown) {
    throw ArgumentError.value(type, 'type', '未知举报类型不能用于写操作');
  }
}
