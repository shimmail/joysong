import 'package:joysong_flutter/features/social/domain/social_models.dart';

final class PublicUploadRequest {
  const PublicUploadRequest({
    required this.uploadId,
    required this.localPath,
    required this.byteLength,
    required this.fileName,
    required this.mimeType,
    required this.folder,
  });

  final String uploadId;
  final String localPath;
  final int byteLength;
  final String fileName;
  final String mimeType;
  final String folder;
}

abstract interface class PublicMediaUploader {
  Stream<PublicUploadProgress> upload(PublicUploadRequest request);
}
