import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/social/data/api_public_media_uploader.dart';
import 'package:joysong_flutter/features/social/data/public_media_upload.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';

void main() {
  const request = PublicUploadRequest(
    uploadId: 'upload-1',
    localPath: 'photo.jpg',
    byteLength: 12,
    fileName: 'upload-1.jpg',
    mimeType: 'image/jpeg',
    folder: 'diaries',
  );

  test('first successful POST completes without a status lookup', () async {
    final client = _UploadApiClient(
      postResults: [
        {
          'uploadId': 'upload-1',
          'status': 'COMPLETE',
          'url': '/media/photo.jpg',
          'replayed': false,
        },
      ],
    );

    final progress =
        await ApiPublicMediaUploader(client).upload(request).toList();

    expect(client.postCalls, 1);
    expect(client.statusCalls, 0);
    expect(client.idempotencyKeys, ['upload-1']);
    expect(progress.last.stage, UploadStage.complete);
    expect(progress.last.url, 'https://example.test/media/photo.jpg');
    expect(progress.last.fraction, 1);
    expect(
      progress.take(progress.length - 1).map((item) => item.fraction),
      everyElement(lessThan(1)),
    );
  });

  test('ambiguous POST failure checks status and reuses completed result',
      () async {
    final client = _UploadApiClient(
      postResults: [
        ApiException(
          message: 'timeout',
          cause: TimeoutException('response lost'),
        ),
      ],
      statusResults: [
        {
          'uploadId': 'upload-1',
          'status': 'COMPLETE',
          'url': '/media/recovered.jpg',
        },
      ],
    );

    final progress =
        await ApiPublicMediaUploader(client).upload(request).toList();

    expect(client.postCalls, 1);
    expect(client.statusCalls, 1);
    expect(progress.last.stage, UploadStage.complete);
    expect(progress.last.url, 'https://example.test/media/recovered.jpg');
  });

  test('truncated successful response checks status before retrying', () async {
    final client = _UploadApiClient(
      postResults: [
        ApiException(
          message: 'invalid response',
          cause: const FormatException('truncated JSON'),
        ),
      ],
      statusResults: [
        {
          'uploadId': 'upload-1',
          'status': 'COMPLETE',
          'url': '/media/recovered-after-truncation.jpg',
        },
      ],
    );

    final progress =
        await ApiPublicMediaUploader(client).upload(request).toList();

    expect(client.postCalls, 1);
    expect(client.statusCalls, 1);
    expect(progress.last.stage, UploadStage.complete);
    expect(
      progress.last.url,
      'https://example.test/media/recovered-after-truncation.jpg',
    );
  });

  test('non-retryable FAILED status does not send the file again', () async {
    final client = _UploadApiClient(
      postResults: [
        {
          'uploadId': 'upload-1',
          'status': 'PENDING',
          'retryAfterMs': 1,
        },
      ],
      statusResults: [
        {
          'uploadId': 'upload-1',
          'status': 'FAILED',
          'errorCode': 'IMAGE_REJECTED',
          'retryable': false,
        },
      ],
    );

    final progress =
        await ApiPublicMediaUploader(client).upload(request).toList();

    expect(client.postCalls, 1);
    expect(client.statusCalls, 1);
    expect(progress.last.stage, UploadStage.failed);
    expect(progress.last.retryable, isFalse);
    expect(progress.last.message, 'IMAGE_REJECTED');
  });

  test('404 status allows one replay with the same idempotency key', () async {
    final client = _UploadApiClient(
      postResults: [
        ApiException(
          message: 'timeout',
          cause: TimeoutException('response lost'),
        ),
        {
          'uploadId': 'upload-1',
          'status': 'COMPLETE',
          'url': '/media/retried.jpg',
        },
      ],
      statusResults: [
        const ApiException(message: 'missing', httpStatus: 404),
      ],
    );

    final progress =
        await ApiPublicMediaUploader(client).upload(request).toList();

    expect(client.postCalls, 2);
    expect(client.statusCalls, 1);
    expect(client.idempotencyKeys, ['upload-1', 'upload-1']);
    expect(progress.last.stage, UploadStage.complete);
  });
}

final class _UploadApiClient extends ApiClient {
  _UploadApiClient({
    required List<Object> postResults,
    List<Object> statusResults = const [],
  })  : _postResults = List<Object>.of(postResults),
        _statusResults = List<Object>.of(statusResults),
        super(apiRoot: Uri.parse('https://example.test/api/'));

  final List<Object> _postResults;
  final List<Object> _statusResults;
  final List<String> idempotencyKeys = [];
  int postCalls = 0;
  int statusCalls = 0;

  @override
  Future<T?> postFileMultipart<T>(
    String path, {
    Map<String, String> fields = const {},
    required List<MultipartFilePart> files,
    required String idempotencyKey,
    required T Function(Object? json) decodeData,
    void Function(int bytesSent, int totalBytes)? onProgress,
    MultipartCancellationToken? cancellationToken,
  }) async {
    expect(path, '/upload');
    expect(fields, const {'folder': 'diaries'});
    expect(files.single.localPath, 'photo.jpg');
    postCalls += 1;
    idempotencyKeys.add(idempotencyKey);
    onProgress?.call(files.single.byteLength, files.single.byteLength);
    final result = _postResults.removeAt(0);
    if (result is ApiException) throw result;
    return decodeData(result);
  }

  @override
  Future<T?> getCancellable<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
    required MultipartCancellationToken cancellationToken,
    required Duration timeout,
  }) async {
    expect(path, '/upload/status/upload-1');
    expect(cancellationToken.isCancelled, isFalse);
    statusCalls += 1;
    final result = _statusResults.removeAt(0);
    if (result is ApiException) throw result;
    return decodeData(result);
  }
}
