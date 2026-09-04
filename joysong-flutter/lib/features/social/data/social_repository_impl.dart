import 'dart:io';

import 'package:joysong_flutter/features/social/data/public_media_upload.dart';
import 'package:joysong_flutter/features/social/data/social_remote_data_source.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';

final class SocialRepositoryImpl
    implements SocialRepository, SocialTranslationRepository {
  SocialRepositoryImpl({
    required SocialRemoteDataSource remoteDataSource,
    required PublicMediaUploader mediaUploader,
  })  : _remoteDataSource = remoteDataSource,
        _mediaUploader = mediaUploader;

  final SocialRemoteDataSource _remoteDataSource;
  final PublicMediaUploader _mediaUploader;

  @override
  Future<PublicUserProfile> getPublicUserProfile(String userId) {
    _requireId(userId, 'userId');
    return _remoteDataSource.getPublicUserProfile(userId);
  }

  @override
  Future<List<Diary>> getUserDiaries(String userId) {
    _requireId(userId, 'userId');
    return _remoteDataSource.getUserDiaries(userId);
  }

  @override
  Future<List<Diary>> getMyDiaries({int offset = 0, int limit = 50}) {
    _validatePage(offset, limit);
    return _remoteDataSource.getMyDiaries(offset: offset, limit: limit);
  }

  @override
  Future<Diary> publishDiary(DiaryDraft draft) {
    draft.validate();
    return _remoteDataSource.publishDiary(draft);
  }

  @override
  Future<Diary> updateDiary(String id, DiaryUpdate update) {
    _requireId(id, 'id');
    update.validate();
    return _remoteDataSource.updateDiary(id, update);
  }

  @override
  Future<void> deleteDiary(String id) {
    _requireId(id, 'id');
    return _remoteDataSource.deleteDiary(id);
  }

  @override
  Future<String> createDiaryShareUrl(String id) {
    _requireId(id, 'id');
    return _remoteDataSource.createDiaryShareUrl(id);
  }

  @override
  Future<List<Comment>> getComments(
    String diaryId, {
    int offset = 0,
    int limit = 50,
  }) {
    _requireId(diaryId, 'diaryId');
    _validatePage(offset, limit);
    return _remoteDataSource.getComments(
      diaryId,
      offset: offset,
      limit: limit,
    );
  }

  @override
  Future<List<Comment>> getReplies(
    String parentId, {
    int offset = 0,
    int limit = 50,
  }) {
    _requireId(parentId, 'parentId');
    _validatePage(offset, limit);
    return _remoteDataSource.getReplies(
      parentId,
      offset: offset,
      limit: limit,
    );
  }

  @override
  Future<Comment> publishComment(CommentDraft draft) {
    draft.validate();
    return _remoteDataSource.publishComment(draft);
  }

  @override
  Future<void> deleteComment(String id) {
    _requireId(id, 'id');
    return _remoteDataSource.deleteComment(id);
  }

  @override
  Future<ContentTranslation> translateText({
    required String text,
    required String targetLanguage,
    required String contentType,
  }) {
    final trimmed = text.trim();
    if (trimmed.isEmpty) {
      throw ArgumentError.value(text, 'text', '待翻译内容不能为空');
    }
    if (trimmed.length > 12000) {
      throw ArgumentError.value(text, 'text', '待翻译内容不能超过 12000 字符');
    }
    final remote = _remoteDataSource;
    if (remote is! SocialTranslationRemoteDataSource) {
      throw UnsupportedError('当前数据源不支持内容翻译');
    }
    final translationRemote = remote as SocialTranslationRemoteDataSource;
    return translationRemote.translateText(
      text: trimmed,
      targetLanguage: targetLanguage,
      contentType: contentType,
    );
  }

  @override
  Future<EngagementStatus> getLikeStatus(
    LikeTargetType type,
    String targetId,
  ) {
    _requireId(targetId, 'targetId');
    return _remoteDataSource.getLikeStatus(type, targetId);
  }

  @override
  Future<void> setLiked(
    LikeTargetType type,
    String targetId,
    bool liked,
  ) {
    _requireId(targetId, 'targetId');
    return _remoteDataSource.setLiked(type, targetId, liked);
  }

  @override
  Future<List<FavoriteItem>> getFavorites({int offset = 0, int limit = 50}) {
    _validatePage(offset, limit);
    return _remoteDataSource.getFavorites(offset: offset, limit: limit);
  }

  @override
  Future<EngagementStatus> getFavoriteStatus(
    FavoriteTargetType type,
    String targetId,
  ) {
    _ensureFavoriteType(type);
    _requireId(targetId, 'targetId');
    return _remoteDataSource.getFavoriteStatus(type, targetId);
  }

  @override
  Future<void> setFavorited(
    FavoriteTargetType type,
    String targetId,
    bool favorited, {
    String targetName = '',
    String targetImage = '',
  }) {
    _ensureFavoriteType(type);
    _requireId(targetId, 'targetId');
    if (targetName.length > 100) {
      throw ArgumentError.value(targetName, 'targetName', '收藏名称不能超过 100 字符');
    }
    return _remoteDataSource.setFavorited(
      type,
      targetId,
      favorited,
      targetName: targetName,
      targetImage: targetImage,
    );
  }

  @override
  Future<bool> hasReported(ReportTargetType type, String targetId) {
    _ensureReportType(type);
    _requireId(targetId, 'targetId');
    return _remoteDataSource.hasReported(type, targetId);
  }

  @override
  Future<ReportReceipt> report({
    required ReportTargetType type,
    required String targetId,
    required String reason,
    String? description,
  }) {
    _ensureReportType(type);
    _requireId(targetId, 'targetId');
    if (reason.trim().isEmpty) {
      throw ArgumentError.value(reason, 'reason', '举报原因不能为空');
    }
    if (description != null && description.trim().length > 500) {
      throw ArgumentError.value(description, 'description', '举报描述不能超过 500 字');
    }
    return _remoteDataSource.report(
      type: type,
      targetId: targetId,
      reason: reason,
      description: description,
    );
  }

  @override
  Future<Review?> getOrderReview(String orderId) {
    _requireId(orderId, 'orderId');
    return _remoteDataSource.getOrderReview(orderId);
  }

  @override
  Future<Review> submitOrderReview(String orderId, ReviewDraft draft) {
    _requireId(orderId, 'orderId');
    draft.validate();
    return _remoteDataSource.submitOrderReview(orderId, draft);
  }

  @override
  Future<Review> updateReview(String reviewId, ReviewDraft draft) {
    _requireId(reviewId, 'reviewId');
    draft.validate();
    return _remoteDataSource.updateReview(reviewId, draft);
  }

  @override
  Future<void> deleteReview(String reviewId) {
    _requireId(reviewId, 'reviewId');
    return _remoteDataSource.deleteReview(reviewId);
  }

  @override
  Stream<PublicUploadProgress> uploadPublicMedia(
    PublicMediaDraft media,
  ) async* {
    if (media.privacy == MediaPrivacy.privateIdentityMaterial) {
      throw const PrivateMaterialUploadException();
    }
    await _validateSource(media);
    yield PublicUploadProgress(
      stage: UploadStage.queued,
      totalBytes: media.byteLength,
    );

    try {
      var terminalReceived = false;
      final request = PublicUploadRequest(
        uploadId: media.uploadId,
        localPath: media.localPath,
        byteLength: media.byteLength,
        fileName: media.fileName,
        mimeType: media.mimeType,
        folder: _folder(media.purpose),
      );
      await for (final progress in _mediaUploader.upload(request)) {
        if (progress.stage == UploadStage.complete &&
            (progress.url == null || progress.url!.trim().isEmpty)) {
          throw const FormatException('公共上传成功响应缺少 URL');
        }
        terminalReceived = terminalReceived || progress.isTerminal;
        yield progress;
      }
      if (!terminalReceived) {
        yield const PublicUploadProgress(
          stage: UploadStage.failed,
          message: '上传连接已结束，请手动重试',
          retryable: true,
        );
      }
    } on PrivateMaterialUploadException {
      rethrow;
    } catch (error) {
      yield PublicUploadProgress(
        stage: UploadStage.failed,
        message: _uploadMessage(error),
        retryable: true,
      );
    }
  }

  Future<void> _validateSource(PublicMediaDraft media) async {
    if (media.uploadId.trim().isEmpty ||
        media.uploadId.contains('\r') ||
        media.uploadId.contains('\n')) {
      throw ArgumentError.value(media.uploadId, 'uploadId', '上传标识格式不正确');
    }
    if (media.localPath.trim().isEmpty) {
      throw ArgumentError.value(media.localPath, 'localPath', '图片路径不能为空');
    }
    if (media.fileName.trim().isEmpty) {
      throw ArgumentError.value(media.fileName, 'fileName', '文件名不能为空');
    }
    final file = File(media.localPath);
    if (!await file.exists()) {
      throw ArgumentError.value(media.localPath, 'localPath', '图片文件不存在');
    }
    final actualLength = await file.length();
    if (actualLength <= 0 || actualLength != media.byteLength) {
      throw ArgumentError.value(media.byteLength, 'byteLength', '图片大小已发生变化');
    }
    if (actualLength > 10 * 1024 * 1024) {
      throw ArgumentError.value(actualLength, 'byteLength', '图片不能超过 10 MB');
    }
    final extension = media.fileName.split('.').last.toLowerCase();
    const allowedExtensions = {'jpg', 'jpeg', 'png'};
    if (!allowedExtensions.contains(extension)) {
      throw ArgumentError.value(media.fileName, 'fileName', '仅支持 JPG 或 PNG');
    }
    const allowedMimeTypes = {'image/jpeg', 'image/png'};
    if (!allowedMimeTypes.contains(media.mimeType.toLowerCase())) {
      throw ArgumentError.value(media.mimeType, 'mimeType', '图片 MIME 类型不受支持');
    }
    final handle = await file.open();
    late final List<int> signature;
    try {
      signature = await handle.read(12);
    } finally {
      await handle.close();
    }
    if (!_hasValidSignature(signature, extension)) {
      throw ArgumentError.value(media.fileName, 'fileName', '文件内容与图片格式不匹配');
    }
  }
}

void _validatePage(int offset, int limit) {
  if (offset < 0) {
    throw RangeError.range(offset, 0, null, 'offset');
  }
  if (limit < 1 || limit > 100) {
    throw RangeError.range(limit, 1, 100, 'limit');
  }
}

void _requireId(String value, String name) {
  if (value.trim().isEmpty) {
    throw ArgumentError.value(value, name, '$name 不能为空');
  }
}

void _ensureFavoriteType(FavoriteTargetType type) {
  if (type == FavoriteTargetType.unknown) {
    throw ArgumentError.value(type, 'type', '未知收藏类型不能用于请求');
  }
}

void _ensureReportType(ReportTargetType type) {
  if (type == ReportTargetType.unknown) {
    throw ArgumentError.value(type, 'type', '未知举报类型不能用于请求');
  }
}

String _folder(PublicMediaPurpose purpose) => switch (purpose) {
      PublicMediaPurpose.diary => 'diaries',
      PublicMediaPurpose.review => 'reviews',
      PublicMediaPurpose.avatar => 'avatars',
      PublicMediaPurpose.directMessage => 'dm',
      PublicMediaPurpose.institutionProfile => 'institutions',
      PublicMediaPurpose.doctorProfile => 'doctors',
    };

String _uploadMessage(Object error) {
  if (error is ArgumentError && error.message != null) {
    return error.message.toString();
  }
  if (error is FormatException) {
    return error.message;
  }
  return '图片上传失败，请检查网络后手动重试';
}

bool _hasValidSignature(List<int> bytes, String extension) {
  if (bytes.length < 12) {
    return false;
  }
  return switch (extension) {
    'jpg' || 'jpeg' => bytes[0] == 0xff && bytes[1] == 0xd8 && bytes[2] == 0xff,
    'png' => const [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
        .asMap()
        .entries
        .every((entry) => bytes[entry.key] == entry.value),
    _ => false,
  };
}
