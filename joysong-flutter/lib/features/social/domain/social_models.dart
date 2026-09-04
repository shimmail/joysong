import 'package:joysong_flutter/core/translation/content_translation.dart'
    as core_translation;

enum DiaryStatus {
  draft('draft'),
  published('published');

  const DiaryStatus(this.wireValue);
  final String wireValue;
}

enum LikeTargetType {
  diary('diary'),
  comment('comment');

  const LikeTargetType(this.wireValue);
  final String wireValue;
}

enum FavoriteTargetType {
  diary('DIARY'),
  project('PROJECT'),
  institution('INSTITUTION'),
  doctor('DOCTOR'),
  article('ARTICLE'),
  unknown('UNKNOWN');

  const FavoriteTargetType(this.wireValue);
  final String wireValue;
}

enum ReportTargetType {
  diary('diary'),
  review('review'),
  comment('comment'),
  user('user'),
  unknown('unknown');

  const ReportTargetType(this.wireValue);
  final String wireValue;
}

enum PublicMediaPurpose {
  diary,
  review,
  avatar,
  directMessage,
  institutionProfile,
  doctorProfile,
}

enum MediaPrivacy { publicContent, privateIdentityMaterial }

enum UploadStage { queued, uploading, processing, complete, failed, cancelled }

final class PublicUserProfile {
  const PublicUserProfile({
    required this.id,
    required this.nickname,
    required this.diaryCount,
    required this.followingCount,
    required this.followerCount,
    this.avatar = '',
    this.gender = '',
    this.bio = '',
    this.city = '',
    this.birthday,
  });

  final String id;
  final String nickname;
  final String avatar;
  final String gender;
  final String bio;
  final String city;
  final DateTime? birthday;
  final int diaryCount;
  final int followingCount;
  final int followerCount;
}

final class Diary {
  const Diary({
    required this.id,
    required this.title,
    required this.userId,
    required this.authorName,
    required this.content,
    required this.images,
    required this.tags,
    required this.likeCount,
    required this.commentCount,
    required this.favoriteCount,
    required this.isLiked,
    required this.status,
    this.authorAvatar = '',
    this.coverImage = '',
    this.publishDate = '',
    this.createdAt,
    this.rating = 0,
    this.doctorId = '',
    this.projectId = '',
    this.institutionId = '',
    this.institutionProjectId = '',
    this.orderId = '',
    this.projectName = '',
    this.doctorName = '',
    this.institutionName = '',
    this.beforeImages = const [],
    this.afterImages = const [],
  });

  final String id;
  final String title;
  final String userId;
  final String authorName;
  final String authorAvatar;
  final String content;
  final String coverImage;
  final List<String> images;
  final List<String> tags;
  final int likeCount;
  final int commentCount;
  final int favoriteCount;
  final bool isLiked;
  final String publishDate;
  final DateTime? createdAt;
  final int rating;
  final String status;
  final String doctorId;
  final String projectId;
  final String institutionId;
  final String institutionProjectId;
  final String orderId;
  final String projectName;
  final String doctorName;
  final String institutionName;
  final List<String> beforeImages;
  final List<String> afterImages;
}

final class DiaryDraft {
  const DiaryDraft({
    required this.title,
    required this.content,
    this.images = const [],
    this.tags = const [],
    this.rating,
    this.doctorId,
    this.projectId,
    this.institutionId,
    this.institutionProjectId,
    this.orderId,
    this.beforeImages = const [],
    this.afterImages = const [],
    this.status = DiaryStatus.published,
  });

  final String title;
  final String content;
  final List<String> images;
  final List<String> tags;
  final int? rating;
  final String? doctorId;
  final String? projectId;
  final String? institutionId;
  final String? institutionProjectId;
  final String? orderId;
  final List<String> beforeImages;
  final List<String> afterImages;
  final DiaryStatus status;

  void validate() {
    final normalizedTitle = title.trim();
    final normalizedContent = content.trim();
    if (normalizedTitle.isEmpty) {
      throw ArgumentError.value(title, 'title', '标题不能为空');
    }
    if (normalizedTitle.length > 200) {
      throw ArgumentError.value(title, 'title', '标题不能超过 200 字');
    }
    if (normalizedContent.isEmpty) {
      throw ArgumentError.value(content, 'content', '内容不能为空');
    }
    if (normalizedContent.length > 20000) {
      throw ArgumentError.value(content, 'content', '内容不能超过 20000 字');
    }
    if (rating != null && (rating! < 0 || rating! > 5)) {
      throw ArgumentError.value(rating, 'rating', '评分必须在 0-5 之间');
    }
    validateCsvValues([...images, ...tags, ...beforeImages, ...afterImages]);
  }
}

final class DiaryUpdate {
  const DiaryUpdate({
    this.title,
    this.content,
    this.images,
    this.tags,
    this.rating,
    this.doctorId,
    this.projectId,
    this.institutionId,
    this.institutionProjectId,
    this.orderId,
    this.beforeImages,
    this.afterImages,
    this.status,
  });

  final String? title;
  final String? content;
  final List<String>? images;
  final List<String>? tags;
  final int? rating;
  final String? doctorId;
  final String? projectId;
  final String? institutionId;
  final String? institutionProjectId;
  final String? orderId;
  final List<String>? beforeImages;
  final List<String>? afterImages;
  final DiaryStatus? status;

  void validate() {
    if (title != null && title!.trim().isEmpty) {
      throw ArgumentError.value(title, 'title', '标题不能为空');
    }
    if (title != null && title!.trim().length > 200) {
      throw ArgumentError.value(title, 'title', '标题不能超过 200 字');
    }
    if (content != null && content!.trim().isEmpty) {
      throw ArgumentError.value(content, 'content', '内容不能为空');
    }
    if (content != null && content!.trim().length > 20000) {
      throw ArgumentError.value(content, 'content', '内容不能超过 20000 字');
    }
    if (rating != null && (rating! < 0 || rating! > 5)) {
      throw ArgumentError.value(rating, 'rating', '评分必须在 0-5 之间');
    }
    validateCsvValues([
      ...?images,
      ...?tags,
      ...?beforeImages,
      ...?afterImages,
    ]);
  }
}

final class Comment {
  const Comment({
    required this.id,
    required this.diaryId,
    required this.userId,
    required this.userName,
    required this.content,
    required this.likeCount,
    required this.isLiked,
    this.userAvatar = '',
    this.parentId,
    this.replyToUserId,
    this.replyToUserName,
    this.createdAt,
  });

  final String id;
  final String diaryId;
  final String userId;
  final String userName;
  final String userAvatar;
  final String content;
  final String? parentId;
  final String? replyToUserId;
  final String? replyToUserName;
  final DateTime? createdAt;
  final int likeCount;
  final bool isLiked;
}

typedef ContentTranslation = core_translation.ContentTranslation;

final class CommentDraft {
  const CommentDraft({
    required this.diaryId,
    required this.content,
    this.parentId,
    this.replyToUserId,
  });

  final String diaryId;
  final String content;
  final String? parentId;
  final String? replyToUserId;

  void validate() {
    if (diaryId.trim().isEmpty) {
      throw ArgumentError.value(diaryId, 'diaryId', '日记 ID 不能为空');
    }
    if (content.trim().isEmpty) {
      throw ArgumentError.value(content, 'content', '评论内容不能为空');
    }
    if (content.trim().length > 1000) {
      throw ArgumentError.value(content, 'content', '评论内容不能超过 1000 字');
    }
    if (parentId == null && replyToUserId != null) {
      throw ArgumentError.value(
        replyToUserId,
        'replyToUserId',
        '顶级评论不能指定被回复用户',
      );
    }
  }
}

final class EngagementStatus {
  const EngagementStatus({required this.active, required this.count});

  final bool active;
  final int count;

  EngagementStatus toggled() => EngagementStatus(
        active: !active,
        count: active ? (count > 0 ? count - 1 : 0) : count + 1,
      );
}

final class FavoriteItem {
  const FavoriteItem({
    required this.id,
    required this.userId,
    required this.targetType,
    required this.targetId,
    required this.targetName,
    required this.targetImage,
    this.createdAt,
  });

  final String id;
  final String userId;
  final FavoriteTargetType targetType;
  final String targetId;
  final String targetName;
  final String targetImage;
  final DateTime? createdAt;
}

final class ReportReceipt {
  const ReportReceipt({
    required this.id,
    required this.targetType,
    required this.targetId,
    required this.reason,
    required this.status,
    this.description,
    this.createdAt,
  });

  final String id;
  final ReportTargetType targetType;
  final String targetId;
  final String reason;
  final String? description;
  final String status;
  final DateTime? createdAt;
}

final class Review {
  const Review({
    required this.id,
    required this.orderId,
    required this.userId,
    required this.rating,
    required this.content,
    required this.tags,
    required this.images,
    this.userName = '',
    this.doctorId = '',
    this.createdAt,
  });

  final String id;
  final String orderId;
  final String userId;
  final String userName;
  final String doctorId;
  final int rating;
  final String content;
  final List<String> tags;
  final List<String> images;
  final DateTime? createdAt;
}

final class ReviewDraft {
  static const maxImageCount = 6;
  static const maxImagesEncodedLength = 2000;

  const ReviewDraft({
    required this.rating,
    required this.content,
    this.tags = const [],
    this.images = const [],
  });

  final int rating;
  final String content;
  final List<String> tags;
  final List<String> images;

  void validate() {
    if (rating < 1 || rating > 5) {
      throw ArgumentError.value(rating, 'rating', '评分必须在 1-5 之间');
    }
    if (content.trim().isEmpty) {
      throw ArgumentError.value(content, 'content', '评价内容不能为空');
    }
    if (images.length > maxImageCount) {
      throw ArgumentError.value(
        images.length,
        'images',
        '评价图片不能超过 $maxImageCount 张',
      );
    }
    if (images.join(',').length > maxImagesEncodedLength) {
      throw ArgumentError.value(
        images,
        'images',
        '评价图片地址总长度不能超过 $maxImagesEncodedLength 个字符',
      );
    }
    validateCsvValues([...tags, ...images]);
  }
}

final class PublicMediaDraft {
  const PublicMediaDraft({
    required this.uploadId,
    required this.localPath,
    required this.byteLength,
    required this.fileName,
    required this.mimeType,
    required this.purpose,
    this.privacy = MediaPrivacy.publicContent,
  });

  final String uploadId;
  final String localPath;
  final int byteLength;
  final String fileName;
  final String mimeType;
  final PublicMediaPurpose purpose;
  final MediaPrivacy privacy;
}

final class PublicUploadProgress {
  const PublicUploadProgress({
    required this.stage,
    this.bytesSent = 0,
    this.totalBytes = 0,
    this.url,
    this.message,
    this.retryable = false,
  });

  final UploadStage stage;
  final int bytesSent;
  final int totalBytes;
  final String? url;
  final String? message;
  final bool retryable;

  double get fraction =>
      totalBytes <= 0 ? 0 : (bytesSent / totalBytes).clamp(0, 1).toDouble();

  bool get isTerminal =>
      stage == UploadStage.complete ||
      stage == UploadStage.failed ||
      stage == UploadStage.cancelled;
}

final class PrivateMaterialUploadException implements Exception {
  const PrivateMaterialUploadException();

  @override
  String toString() => '私有身份材料只能通过 /identity/files 上传';
}

void validateCsvValues(Iterable<String> values) {
  for (final value in values) {
    if (value.contains(',')) {
      throw ArgumentError.value(value, 'value', '列表项不能包含英文逗号');
    }
  }
}
