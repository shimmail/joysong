import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

@immutable
final class RefundEvidenceDraft {
  RefundEvidenceDraft({
    required Uint8List bytes,
    required this.fileName,
    required this.contentType,
  }) : bytes = Uint8List.fromList(bytes);

  static const maxCount = 5;
  static const maxBytes = 10 * 1024 * 1024;

  final Uint8List bytes;
  final String fileName;
  final String contentType;

  void validate() {
    final extension = fileName.trim().split('.').last.toLowerCase();
    final normalizedContentType = contentType.trim().toLowerCase();
    final validPair = switch (extension) {
      'jpg' || 'jpeg' => normalizedContentType == 'image/jpeg',
      'png' => normalizedContentType == 'image/png',
      'webp' => normalizedContentType == 'image/webp',
      'pdf' => normalizedContentType == 'application/pdf',
      _ => false,
    };
    if (bytes.isEmpty) {
      throw ArgumentError.value(bytes, 'bytes', '退款凭证不能为空');
    }
    if (bytes.length > maxBytes) {
      throw ArgumentError.value(bytes.length, 'bytes', '退款凭证不能超过 10 MiB');
    }
    if (!validPair) {
      throw ArgumentError.value(fileName, 'fileName', '退款凭证类型不支持');
    }
  }
}

@immutable
final class RefundEvidenceFile {
  const RefundEvidenceFile({
    required this.fileId,
    required this.originalName,
    required this.contentType,
    required this.sizeBytes,
    required this.position,
  });

  final String fileId;
  final String originalName;
  final String contentType;
  final int sizeBytes;
  final int position;

  factory RefundEvidenceFile.fromJson(Object? json) {
    final map = jsonMap(json, '退款凭证');
    final sizeBytes = requiredInt(map, 'sizeBytes', '退款凭证');
    final position = requiredInt(map, 'position', '退款凭证');
    if (sizeBytes < 0) {
      throw const FormatException('退款凭证 sizeBytes 不能为负数');
    }
    if (position < 0 || position >= RefundEvidenceDraft.maxCount) {
      throw const FormatException('退款凭证 position 无效');
    }
    return RefundEvidenceFile(
      fileId: requiredString(map, 'fileId', '退款凭证'),
      originalName: requiredString(map, 'originalName', '退款凭证'),
      contentType: requiredString(map, 'contentType', '退款凭证'),
      sizeBytes: sizeBytes,
      position: position,
    );
  }
}
