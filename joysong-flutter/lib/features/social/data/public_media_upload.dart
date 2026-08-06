import 'dart:typed_data';

import 'package:joysong_flutter/features/social/domain/social_models.dart';

final class PublicImageCompressionPolicy {
  const PublicImageCompressionPolicy({
    this.maxLongEdge = 2048,
    this.jpegQuality = 85,
    this.maxBytes = 10 * 1024 * 1024,
  });

  final int maxLongEdge;
  final int jpegQuality;
  final int maxBytes;
}

abstract interface class PublicImagePreprocessor {
  Future<PublicMediaDraft> prepare(
    PublicMediaDraft source,
    PublicImageCompressionPolicy policy,
  );
}

final class PublicUploadRequest {
  PublicUploadRequest({
    required Uint8List bytes,
    required this.fileName,
    required this.mimeType,
    required this.folder,
  }) : bytes = Uint8List.fromList(bytes);

  final Uint8List bytes;
  final String fileName;
  final String mimeType;
  final String folder;
}

abstract interface class PublicMediaUploader {
  Stream<PublicUploadProgress> upload(PublicUploadRequest request);
}
