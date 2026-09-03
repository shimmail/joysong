import 'dart:async';
import 'dart:io';
import 'dart:math';

import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/core/network/public_media_url.dart';
import 'package:joysong_flutter/features/social/data/public_media_upload.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';

final class ApiPublicMediaUploader implements PublicMediaUploader {
  const ApiPublicMediaUploader(this._apiClient);

  static const _statusRecoveryWindow = Duration(seconds: 15);
  static const _defaultPollDelay = Duration(milliseconds: 750);

  final ApiClient _apiClient;

  @override
  Stream<PublicUploadProgress> upload(PublicUploadRequest request) {
    final cancellation = MultipartCancellationToken();
    var subscriptionCancelled = false;
    late final StreamController<PublicUploadProgress> controller;

    void emit(PublicUploadProgress progress) {
      if (!subscriptionCancelled && !controller.isClosed) {
        controller.add(progress);
      }
    }

    Future<void> run() async {
      var mayRetryAfterKnownFailure = true;
      try {
        emit(
          PublicUploadProgress(
            stage: UploadStage.uploading,
            totalBytes: request.byteLength,
          ),
        );
        while (true) {
          _UploadReply reply;
          try {
            reply = await _send(request, cancellation, emit);
          } on Object catch (error) {
            if (!_isAmbiguousTransportFailure(error)) rethrow;
            emit(
              PublicUploadProgress(
                stage: UploadStage.processing,
                bytesSent: max(0, request.byteLength - 1),
                totalBytes: request.byteLength,
                message: '正在确认上传结果',
              ),
            );
            final recovery = await _pollStatus(request, cancellation);
            if (recovery.reply?.isComplete == true) {
              _emitComplete(emit, request, recovery.reply!);
              return;
            }
            if (recovery.canRetry && mayRetryAfterKnownFailure) {
              mayRetryAfterKnownFailure = false;
              continue;
            }
            if (recovery.reply?.isFailed == true) {
              throw _UploadFailure(recovery.reply!);
            }
            rethrow;
          }

          if (reply.isComplete) {
            _emitComplete(emit, request, reply);
            return;
          }
          emit(
            PublicUploadProgress(
              stage: UploadStage.processing,
              bytesSent: max(0, request.byteLength - 1),
              totalBytes: request.byteLength,
              message: '服务端处理中',
            ),
          );
          final recovery = await _pollStatus(
            request,
            cancellation,
            initial: reply,
          );
          if (recovery.reply?.isComplete == true) {
            _emitComplete(emit, request, recovery.reply!);
            return;
          }
          if (recovery.canRetry && mayRetryAfterKnownFailure) {
            mayRetryAfterKnownFailure = false;
            continue;
          }
          if (recovery.reply?.isFailed == true) {
            throw _UploadFailure(recovery.reply!);
          }
          throw ApiException(
            message: recovery.reply?.errorCode ?? '上传结果确认超时，请手动重试',
            errorCode: recovery.reply?.errorCode,
          );
        }
      } on MultipartUploadCancelledException {
        emit(
          PublicUploadProgress(
            stage: UploadStage.cancelled,
            totalBytes: request.byteLength,
            message: '上传已取消',
          ),
        );
      } on Object catch (error) {
        var retryable = true;
        var message = '上传失败，请手动重试';
        if (error is _UploadFailure) {
          retryable = error.reply.retryable;
          message = error.reply.errorCode ?? message;
        } else if (error is ApiException) {
          retryable = error.httpStatus != 409;
          if (error.message.trim().isNotEmpty) message = error.message;
        }
        emit(
          PublicUploadProgress(
            stage: UploadStage.failed,
            totalBytes: request.byteLength,
            message: message,
            retryable: retryable,
          ),
        );
      } finally {
        if (!controller.isClosed) await controller.close();
      }
    }

    controller = StreamController<PublicUploadProgress>(
      onListen: () => unawaited(run()),
      onCancel: () async {
        subscriptionCancelled = true;
        await cancellation.cancel();
      },
    );
    return controller.stream;
  }

  Future<_UploadReply> _send(
    PublicUploadRequest request,
    MultipartCancellationToken cancellation,
    void Function(PublicUploadProgress progress) emit,
  ) async {
    final response = await _apiClient.postFileMultipart<_UploadReply>(
      '/upload',
      idempotencyKey: request.uploadId,
      fields: {'folder': request.folder},
      files: [
        MultipartFilePart.fromPath(
          fieldName: 'file',
          fileName: request.fileName,
          contentType: request.mimeType,
          localPath: request.localPath,
          byteLength: request.byteLength,
        ),
      ],
      cancellationToken: cancellation,
      onProgress: (sent, total) {
        if (sent >= total) {
          emit(
            PublicUploadProgress(
              stage: UploadStage.processing,
              bytesSent: max(0, total - 1),
              totalBytes: total,
              message: '服务端处理中',
            ),
          );
          return;
        }
        emit(
          PublicUploadProgress(
            stage: UploadStage.uploading,
            bytesSent: sent,
            totalBytes: total,
          ),
        );
      },
      decodeData: (json) => _UploadReply.fromJson(
        json,
        apiRoot: _apiClient.apiRoot,
        fallbackUploadId: request.uploadId,
      ),
    );
    if (response == null) {
      throw const FormatException('公共图片上传响应为空');
    }
    if (response.uploadId != request.uploadId) {
      throw const FormatException('公共图片上传响应标识不匹配');
    }
    return response;
  }

  Future<_RecoveryResult> _pollStatus(
    PublicUploadRequest request,
    MultipartCancellationToken cancellation, {
    _UploadReply? initial,
  }) async {
    final deadline = DateTime.now().add(_statusRecoveryWindow);
    var current = initial;
    while (DateTime.now().isBefore(deadline)) {
      if (current?.isComplete == true) {
        return _RecoveryResult(current, canRetry: false);
      }
      if (current?.isFailed == true) {
        return _RecoveryResult(current, canRetry: current!.retryable);
      }
      if (current != null) {
        final delay = _pollDelay(current.retryAfterMs);
        final remaining = deadline.difference(DateTime.now());
        if (remaining <= Duration.zero) break;
        await _untilCancelled<void>(
          Future<void>.delayed(delay < remaining ? delay : remaining),
          cancellation,
        );
      }
      final remaining = deadline.difference(DateTime.now());
      if (remaining <= Duration.zero) break;
      try {
        current = await _apiClient.getCancellable<_UploadReply>(
          '/upload/status/${Uri.encodeComponent(request.uploadId)}',
          decodeData: (json) => _UploadReply.fromJson(
            json,
            apiRoot: _apiClient.apiRoot,
            fallbackUploadId: request.uploadId,
          ),
          cancellationToken: cancellation,
          timeout: remaining,
        );
        if (current == null) {
          return const _RecoveryResult(null, canRetry: true);
        }
      } on ApiException catch (error) {
        if (error.httpStatus == HttpStatus.notFound) {
          return const _RecoveryResult(null, canRetry: true);
        }
        if (!_isAmbiguousTransportFailure(error)) rethrow;
      } on TimeoutException {
        break;
      }
    }
    return _RecoveryResult(current, canRetry: false);
  }

  static Duration _pollDelay(int? retryAfterMs) {
    if (retryAfterMs == null) return _defaultPollDelay;
    return Duration(milliseconds: retryAfterMs.clamp(200, 2000));
  }

  static void _emitComplete(
    void Function(PublicUploadProgress progress) emit,
    PublicUploadRequest request,
    _UploadReply reply,
  ) {
    final url = reply.url;
    if (url == null || url.isEmpty) {
      throw const FormatException('公共图片上传成功响应缺少 url');
    }
    emit(
      PublicUploadProgress(
        stage: UploadStage.complete,
        bytesSent: request.byteLength,
        totalBytes: request.byteLength,
        url: url,
      ),
    );
  }
}

final class _UploadReply {
  const _UploadReply({
    required this.uploadId,
    required this.status,
    this.url,
    this.retryAfterMs,
    this.errorCode,
    this.retryable = false,
  });

  factory _UploadReply.fromJson(
    Object? json, {
    required Uri apiRoot,
    required String fallbackUploadId,
  }) {
    if (json is! Map) {
      throw const FormatException('公共图片上传响应格式无效');
    }
    final rawUrl = json['url']?.toString().trim();
    final rawStatus = json['status']?.toString().trim().toUpperCase() ?? '';
    return _UploadReply(
      uploadId: json['uploadId']?.toString().trim().isNotEmpty == true
          ? json['uploadId'].toString().trim()
          : fallbackUploadId,
      status: rawStatus.isEmpty && rawUrl?.isNotEmpty == true
          ? 'COMPLETE'
          : rawStatus,
      url: rawUrl == null || rawUrl.isEmpty
          ? null
          : resolvePublicMediaUrl(rawUrl, apiRoot: apiRoot),
      retryAfterMs: _intOrNull(json['retryAfterMs']),
      errorCode: json['errorCode']?.toString(),
      retryable: json['retryable'] == true,
    );
  }

  final String uploadId;
  final String status;
  final String? url;
  final int? retryAfterMs;
  final String? errorCode;
  final bool retryable;

  bool get isComplete => status == 'COMPLETE';
  bool get isFailed => status == 'FAILED';
}

final class _UploadFailure implements Exception {
  const _UploadFailure(this.reply);

  final _UploadReply reply;
}

final class _RecoveryResult {
  const _RecoveryResult(this.reply, {required this.canRetry});

  final _UploadReply? reply;
  final bool canRetry;
}

int? _intOrNull(Object? value) {
  if (value is int) return value;
  return int.tryParse(value?.toString() ?? '');
}

bool _isAmbiguousTransportFailure(Object error) {
  if (error is! ApiException) return false;
  return error.cause is TimeoutException ||
      error.cause is SocketException ||
      error.cause is HttpException ||
      error.cause is FormatException;
}

Future<T> _untilCancelled<T>(
  Future<T> operation,
  MultipartCancellationToken cancellation,
) {
  if (cancellation.isCancelled) {
    throw const MultipartUploadCancelledException();
  }
  return Future.any<T>([
    operation,
    cancellation.whenCancelled.then<T>(
      (_) => throw const MultipartUploadCancelledException(),
    ),
  ]);
}
