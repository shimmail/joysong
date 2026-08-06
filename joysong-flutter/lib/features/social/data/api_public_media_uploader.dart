import 'dart:async';

import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/public_media_url.dart';
import 'package:joysong_flutter/features/social/data/public_media_upload.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';

final class PassthroughPublicImagePreprocessor
    implements PublicImagePreprocessor {
  const PassthroughPublicImagePreprocessor();

  @override
  Future<PublicMediaDraft> prepare(
    PublicMediaDraft source,
    PublicImageCompressionPolicy policy,
  ) async {
    if (source.bytes.length > policy.maxBytes) {
      throw ArgumentError('图片超过上传大小限制，请压缩后重试');
    }
    return source;
  }
}

final class ApiPublicMediaUploader implements PublicMediaUploader {
  const ApiPublicMediaUploader(this._apiClient);

  final ApiClient _apiClient;

  @override
  Stream<PublicUploadProgress> upload(PublicUploadRequest request) {
    late final StreamController<PublicUploadProgress> controller;
    controller = StreamController<PublicUploadProgress>(
      onListen: () async {
        controller.add(
          PublicUploadProgress(
            stage: UploadStage.uploading,
            totalBytes: request.bytes.length,
          ),
        );
        try {
          final url = await _apiClient.postMultipart<String>(
            '/upload',
            fields: {'folder': request.folder},
            files: [
              MultipartFilePart(
                fieldName: 'file',
                fileName: request.fileName,
                contentType: request.mimeType,
                bytes: request.bytes,
              ),
            ],
            onProgress: (sent, total) => controller.add(
              PublicUploadProgress(
                stage: UploadStage.uploading,
                bytesSent: sent,
                totalBytes: total,
              ),
            ),
            decodeData: (json) {
              if (json is! Map || json['url'] == null) {
                throw const FormatException('公共图片上传响应缺少 url');
              }
              return resolvePublicMediaUrl(
                json['url'].toString(),
                apiRoot: _apiClient.apiRoot,
              );
            },
          );
          if (url == null || url.isEmpty) {
            throw const FormatException('公共图片上传响应为空');
          }
          controller.add(
            PublicUploadProgress(
              stage: UploadStage.complete,
              bytesSent: request.bytes.length,
              totalBytes: request.bytes.length,
              url: url,
            ),
          );
        } catch (_) {
          controller.add(
            PublicUploadProgress(
              stage: UploadStage.failed,
              totalBytes: request.bytes.length,
              message: '上传失败，请手动重试',
              retryable: true,
            ),
          );
        } finally {
          await controller.close();
        }
      },
    );
    return controller.stream;
  }
}
