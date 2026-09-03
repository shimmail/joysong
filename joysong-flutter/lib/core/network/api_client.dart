import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/core/network/api_envelope.dart';

typedef AccessTokenProvider = Future<String?> Function();
typedef UnauthorizedHandler = Future<String?> Function();
typedef LanguageTagProvider = String Function();
typedef RequestIdProvider = String Function();
typedef StreamHttpRequestOpener = Future<StreamHttpRequest> Function(Uri uri);

abstract interface class StreamHttpRequest {
  void setHeader(String name, String value);
  void add(List<int> bytes);
  Future<StreamHttpResponse> close();
  void abort();
}

abstract interface class StreamHttpResponse {
  int get statusCode;
  Stream<List<int>> get bytes;
  Future<void> cancel();
}

final class StreamHttpOperation {
  StreamHttpOperation._();

  final _response = Completer<StreamHttpResponse>();
  late final Future<void> Function() _cancel;

  Future<StreamHttpResponse> get response => _response.future;
  Future<void> cancel() => _cancel();
}

final class MultipartCancellationToken {
  final Completer<void> _cancelledSignal = Completer<void>();
  HttpClientRequest? _request;
  HttpClientResponse? _response;
  bool _cancelled = false;

  bool get isCancelled => _cancelled;
  Future<void> get whenCancelled => _cancelledSignal.future;

  Future<void> cancel() async {
    if (_cancelled) return;
    _cancelled = true;
    _cancelledSignal.complete();
    _request?.abort(const MultipartUploadCancelledException());
    final response = _response;
    if (response != null) await _cancelResponse(response);
  }

  void _attachRequest(HttpClientRequest request) {
    if (_cancelled) {
      request.abort(const MultipartUploadCancelledException());
      throw const MultipartUploadCancelledException();
    }
    _request = request;
    _response = null;
  }

  void _attachResponse(HttpClientResponse response) {
    if (_cancelled) {
      unawaited(_cancelResponse(response));
      throw const MultipartUploadCancelledException();
    }
    _request = null;
    _response = response;
  }

  void _detach() {
    _request = null;
    _response = null;
  }

  void _throwIfCancelled() {
    if (_cancelled) throw const MultipartUploadCancelledException();
  }
}

final class MultipartUploadCancelledException implements Exception {
  const MultipartUploadCancelledException();

  @override
  String toString() => 'Multipart upload cancelled';
}

class ApiClient {
  ApiClient({
    required Uri apiRoot,
    AccessTokenProvider? accessTokenProvider,
    LanguageTagProvider? languageTagProvider,
    RequestIdProvider? requestIdProvider,
    String clientName = 'joysong-flutter',
    HttpClient? httpClient,
    StreamHttpRequestOpener? streamRequestOpener,
    Duration requestTimeout = const Duration(seconds: 30),
    Duration multipartConnectTimeout = const Duration(seconds: 15),
    Duration multipartUploadTimeout = const Duration(seconds: 120),
    Duration multipartResponseTimeout = const Duration(seconds: 15),
  })  : _apiRoot = apiRoot,
        _accessTokenProvider = accessTokenProvider,
        _languageTagProvider = languageTagProvider,
        _requestIdProvider = requestIdProvider ?? generateApiRequestId,
        _clientName = clientName,
        _httpClient = httpClient ?? HttpClient(),
        _streamRequestOpener = streamRequestOpener,
        _requestTimeout = requestTimeout,
        _multipartConnectTimeout = multipartConnectTimeout,
        _multipartUploadTimeout = multipartUploadTimeout,
        _multipartResponseTimeout = multipartResponseTimeout;

  final Uri _apiRoot;
  final AccessTokenProvider? _accessTokenProvider;
  final LanguageTagProvider? _languageTagProvider;
  final RequestIdProvider _requestIdProvider;
  final String _clientName;
  final HttpClient _httpClient;
  final StreamHttpRequestOpener? _streamRequestOpener;
  final Duration _requestTimeout;
  final Duration _multipartConnectTimeout;
  final Duration _multipartUploadTimeout;
  final Duration _multipartResponseTimeout;
  UnauthorizedHandler? _unauthorizedHandler;
  Future<String?>? _refreshInFlight;

  Uri get apiRoot => _apiRoot;

  void configureUnauthorizedHandler(UnauthorizedHandler handler) {
    _unauthorizedHandler = handler;
  }

  StreamHttpOperation openStreamPost(
    String path, {
    required String idempotencyKey,
    required Object body,
  }) {
    _validateHeaderToken(idempotencyKey, 'Idempotency-Key');
    final operation = StreamHttpOperation._();
    StreamHttpRequest? request;
    StreamHttpResponse? response;
    var cancelled = false;
    operation._cancel = () async {
      cancelled = true;
      request?.abort();
      await response?.cancel();
    };

    Future<void>(() async {
      try {
        final uri = _resolve(path, const {});
        final opener = _streamRequestOpener;
        request = opener == null
            ? _IoStreamHttpRequest(await _httpClient.postUrl(uri))
            : await opener(uri);
        if (cancelled) {
          request!.abort();
          return;
        }
        request!
          ..setHeader(HttpHeaders.acceptHeader, 'text/event-stream')
          ..setHeader(HttpHeaders.contentTypeHeader, 'application/json')
          ..setHeader('Idempotency-Key', idempotencyKey);
        final token = await _accessTokenProvider?.call();
        if (cancelled) {
          request!.abort();
          return;
        }
        if (token != null && token.isNotEmpty) {
          request!.setHeader(
            HttpHeaders.authorizationHeader,
            'Bearer $token',
          );
        }
        request!.add(utf8.encode(jsonEncode(body)));
        response = await request!.close();
        if (cancelled) {
          await response!.cancel();
          return;
        }
        operation._response.complete(response);
      } on Object catch (error, stackTrace) {
        if (!cancelled && !operation._response.isCompleted) {
          operation._response.completeError(error, stackTrace);
        }
      }
    });
    return operation;
  }

  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) {
    return _send<T>(
      method: 'GET',
      path: path,
      query: query,
      decodeData: decodeData,
      replayAfterRefresh: true,
    );
  }

  Future<T?> getCancellable<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
    required MultipartCancellationToken cancellationToken,
    required Duration timeout,
  }) {
    return _send<T>(
      method: 'GET',
      path: path,
      query: query,
      decodeData: decodeData,
      replayAfterRefresh: true,
      cancellationToken: cancellationToken,
      requestTimeoutOverride: timeout,
    );
  }

  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) {
    return _send<T>(
      method: 'POST',
      path: path,
      body: body,
      decodeData: decodeData,
    );
  }

  /// Sends a POST that may be replayed once after refreshing authentication.
  ///
  /// Callers must use a stable [idempotencyKey] and the server must enforce it.
  /// Ordinary writes deliberately do not retry after a 401 because repeating a
  /// payment, order, upload, or message can create duplicate side effects.
  Future<T?> postIdempotent<T>(
    String path, {
    required String idempotencyKey,
    Object? body,
    required T Function(Object? json) decodeData,
  }) {
    _validateHeaderToken(idempotencyKey, 'Idempotency-Key');
    return _send<T>(
      method: 'POST',
      path: path,
      body: body,
      decodeData: decodeData,
      replayAfterRefresh: true,
      idempotencyKey: idempotencyKey,
    );
  }

  /// Sends an idempotent POST with narrowly scoped, caller-provided headers.
  ///
  /// This is used by protocols whose one-time authorization is distinct from
  /// the user's Bearer token. Header names and values are validated before any
  /// network request is opened.
  Future<T?> postIdempotentWithHeaders<T>(
    String path, {
    required String idempotencyKey,
    required Map<String, String> headers,
    Object? body,
    required T Function(Object? json) decodeData,
    bool includeAccessToken = true,
  }) {
    _validateHeaderToken(idempotencyKey, 'Idempotency-Key');
    for (final entry in headers.entries) {
      _validateHeaderName(entry.key);
      _validateHeaderToken(entry.value, entry.key);
    }
    return _send<T>(
      method: 'POST',
      path: path,
      body: body,
      decodeData: decodeData,
      replayAfterRefresh: includeAccessToken,
      idempotencyKey: idempotencyKey,
      extraHeaders: headers,
      includeAccessToken: includeAccessToken,
    );
  }

  Future<T?> put<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) {
    return _send<T>(
      method: 'PUT',
      path: path,
      body: body,
      decodeData: decodeData,
    );
  }

  Future<T?> delete<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) {
    return _send<T>(
      method: 'DELETE',
      path: path,
      body: body,
      decodeData: decodeData,
    );
  }

  Future<T?> postMultipart<T>(
    String path, {
    Map<String, String> fields = const {},
    required List<MultipartFilePart> files,
    required T Function(Object? json) decodeData,
    void Function(int bytesSent, int totalBytes)? onProgress,
  }) {
    return _postMultipart<T>(
      path,
      fields: fields,
      files: files,
      decodeData: decodeData,
      onProgress: onProgress,
    );
  }

  Future<T?> postFileMultipart<T>(
    String path, {
    Map<String, String> fields = const {},
    required List<MultipartFilePart> files,
    required String idempotencyKey,
    required T Function(Object? json) decodeData,
    void Function(int bytesSent, int totalBytes)? onProgress,
    MultipartCancellationToken? cancellationToken,
  }) {
    _validateHeaderToken(idempotencyKey, 'Idempotency-Key');
    return _postMultipart<T>(
      path,
      fields: fields,
      files: files,
      idempotencyKey: idempotencyKey,
      replayAfterRefresh: true,
      acceptedBusinessCodes: const {200, 202},
      decodeData: decodeData,
      onProgress: onProgress,
      cancellationToken: cancellationToken,
    );
  }

  Future<T?> _postMultipart<T>(
    String path, {
    required Map<String, String> fields,
    required List<MultipartFilePart> files,
    required T Function(Object? json) decodeData,
    void Function(int bytesSent, int totalBytes)? onProgress,
    String? idempotencyKey,
    bool replayAfterRefresh = false,
    bool hasRetriedAuthentication = false,
    String? requestId,
    Set<int> acceptedBusinessCodes = const {200},
    MultipartCancellationToken? cancellationToken,
  }) async {
    final uri = _resolve(path, const {});
    final logicalRequestId = requestId ?? _requestIdProvider();
    final boundary = '----joysong-${DateTime.now().microsecondsSinceEpoch}-'
        '${Random.secure().nextInt(1 << 32)}';
    HttpClientRequest? request;
    HttpClientResponse? response;
    try {
      cancellationToken?._throwIfCancelled();
      for (final entry in fields.entries) {
        _validateMultipartToken(entry.key, '字段名');
      }
      for (final file in files) {
        _validateMultipartToken(file.fieldName, '文件字段名');
        _validateMultipartToken(file.fileName, '文件名');
        _validateMultipartToken(file.contentType, '文件类型');
        await file.validateSource();
      }

      final fieldChunks = <List<int>>[
        for (final entry in fields.entries)
          utf8.encode(
            '--$boundary\r\n'
            'Content-Disposition: form-data; name="${entry.key}"\r\n\r\n'
            '${entry.value}\r\n',
          ),
      ];
      final fileHeaderChunks = <List<int>>[
        for (final file in files)
          utf8.encode(
            '--$boundary\r\n'
            'Content-Disposition: form-data; name="${file.fieldName}"; '
            'filename="${file.fileName}"\r\n'
            'Content-Type: ${file.contentType}\r\n\r\n',
          ),
      ];
      final trailer = utf8.encode('--$boundary--\r\n');
      final contentLength = fieldChunks.fold<int>(
            0,
            (total, bytes) => total + bytes.length,
          ) +
          List<int>.generate(files.length, (index) => index).fold<int>(
            0,
            (total, index) =>
                total +
                fileHeaderChunks[index].length +
                files[index].byteLength +
                2,
          ) +
          trailer.length;
      final totalFileBytes = files.fold<int>(
        0,
        (total, file) => total + file.byteLength,
      );

      _httpClient.connectionTimeout ??= _multipartConnectTimeout;
      request =
          await _httpClient.postUrl(uri).timeout(_multipartConnectTimeout);
      cancellationToken?._attachRequest(request);
      request.bufferOutput = false;
      _applyStandardHeaders(request.headers, requestId: logicalRequestId);
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      request.headers.set(
        HttpHeaders.contentTypeHeader,
        'multipart/form-data; boundary=$boundary',
      );
      request.contentLength = contentLength;
      if (idempotencyKey != null) {
        request.headers.set('Idempotency-Key', idempotencyKey);
      }
      final token = await _accessTokenProvider?.call();
      if (token != null && token.isNotEmpty) {
        request.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
      }
      final activeRequest = request;

      Future<HttpClientResponse> sendBody() async {
        for (final bytes in fieldChunks) {
          activeRequest.add(bytes);
        }
        var sentFileBytes = 0;
        for (var index = 0; index < files.length; index += 1) {
          cancellationToken?._throwIfCancelled();
          activeRequest.add(fileHeaderChunks[index]);
          await activeRequest.addStream(
            files[index].openRead().map((chunk) {
              sentFileBytes += chunk.length;
              onProgress?.call(sentFileBytes, totalFileBytes);
              return chunk;
            }),
          );
          activeRequest.add(const [13, 10]);
        }
        activeRequest.add(trailer);
        return activeRequest.close();
      }

      response = await sendBody().timeout(
        _multipartUploadTimeout,
        onTimeout: () {
          request!.abort(TimeoutException('Multipart upload timed out'));
          throw TimeoutException('Multipart upload timed out');
        },
      );
      cancellationToken?._attachResponse(response);
      final text = await utf8.decoder.bind(response).join().timeout(
        _multipartResponseTimeout,
        onTimeout: () {
          unawaited(_cancelResponse(response!));
          throw TimeoutException('Multipart response timed out');
        },
      );
      final successfulHttp = response.statusCode >= 200 &&
          response.statusCode < HttpStatus.multipleChoices;
      ApiEnvelope<Object?>? envelope;
      try {
        envelope = _decodeEnvelope(text);
      } on FormatException {
        if (successfulHttp) {
          rethrow;
        }
      }
      final isUnauthorized = response.statusCode == HttpStatus.unauthorized ||
          envelope?.code == HttpStatus.unauthorized;
      if (isUnauthorized && replayAfterRefresh && !hasRetriedAuthentication) {
        final refreshedToken = await _refreshAuthentication();
        if (refreshedToken != null && refreshedToken.isNotEmpty) {
          cancellationToken?._detach();
          return await _postMultipart<T>(
            path,
            fields: fields,
            files: files,
            decodeData: decodeData,
            onProgress: onProgress,
            idempotencyKey: idempotencyKey,
            replayAfterRefresh: replayAfterRefresh,
            hasRetriedAuthentication: true,
            requestId: logicalRequestId,
            acceptedBusinessCodes: acceptedBusinessCodes,
            cancellationToken: cancellationToken,
          );
        }
      }
      if (!successfulHttp ||
          envelope == null ||
          !acceptedBusinessCodes.contains(envelope.code)) {
        throw ApiException(
          message: envelope == null || envelope.message.isEmpty
              ? _localized('上传失败，请稍后重试', 'Upload failed. Try again later.')
              : _localizedServerMessage(envelope.message),
          httpStatus: response.statusCode,
          businessCode: envelope?.code,
          errorCode: envelope?.errorCode,
          data: envelope?.data,
        );
      }
      return envelope.hasData ? decodeData(envelope.data) : null;
    } on MultipartUploadCancelledException {
      rethrow;
    } on ApiException {
      rethrow;
    } on TimeoutException catch (error) {
      request?.abort(error);
      if (response != null) await _cancelResponse(response);
      throw ApiException(
        message: _localized('上传超时，请稍后重试', 'Upload timed out. Try again.'),
        cause: error,
      );
    } on SocketException catch (error) {
      if (cancellationToken?.isCancelled == true) {
        throw const MultipartUploadCancelledException();
      }
      throw ApiException(
        message: _localized(
          '网络连接失败，请检查网络',
          'Network connection failed. Check your connection.',
        ),
        cause: error,
      );
    } on HttpException catch (error) {
      if (cancellationToken?.isCancelled == true) {
        throw const MultipartUploadCancelledException();
      }
      throw ApiException(
        message: _localized(
          '上传请求失败，请稍后重试',
          'Upload request failed. Try again later.',
        ),
        cause: error,
      );
    } on FormatException catch (error) {
      throw ApiException(
        message: _localized(
          '服务响应格式异常',
          'The server returned an invalid response.',
        ),
        cause: error,
      );
    } on FileSystemException catch (error) {
      throw ApiException(
        message: _localized(
          '无法读取待上传文件',
          'Unable to read the file to upload.',
        ),
        cause: error,
      );
    } finally {
      cancellationToken?._detach();
    }
  }

  Future<T?> _send<T>({
    required String method,
    required String path,
    Map<String, Object?> query = const {},
    Object? body,
    required T Function(Object? json) decodeData,
    bool replayAfterRefresh = false,
    bool hasRetried = false,
    String? idempotencyKey,
    String? requestId,
    Map<String, String> extraHeaders = const {},
    bool includeAccessToken = true,
    MultipartCancellationToken? cancellationToken,
    Duration? requestTimeoutOverride,
  }) async {
    final uri = _resolve(path, query);
    final logicalRequestId = requestId ?? _requestIdProvider();
    final timeout = requestTimeoutOverride ?? _requestTimeout;
    HttpClientRequest? request;
    HttpClientResponse? response;
    try {
      cancellationToken?._throwIfCancelled();
      request = await _httpClient.openUrl(method, uri).timeout(timeout);
      cancellationToken?._attachRequest(request);
      _applyStandardHeaders(request.headers, requestId: logicalRequestId);
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      if (idempotencyKey != null) {
        request.headers.set('Idempotency-Key', idempotencyKey);
      }
      for (final entry in extraHeaders.entries) {
        request.headers.set(entry.key, entry.value);
      }
      if (includeAccessToken) {
        final token = await _accessTokenProvider?.call();
        if (token != null && token.isNotEmpty) {
          request.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
        }
      }
      if (body != null) {
        request.headers.contentType = ContentType.json;
        request.write(jsonEncode(body));
      }

      response = await request.close().timeout(
        timeout,
        onTimeout: () {
          request!.abort(TimeoutException('Request timed out'));
          throw TimeoutException('Request timed out');
        },
      );
      cancellationToken?._attachResponse(response);
      final text = await utf8.decoder.bind(response).join().timeout(
        timeout,
        onTimeout: () {
          unawaited(_cancelResponse(response!));
          throw TimeoutException('Response timed out');
        },
      );
      final successfulHttp = response.statusCode >= 200 &&
          response.statusCode < HttpStatus.multipleChoices;
      ApiEnvelope<Object?>? envelope;
      try {
        envelope = _decodeEnvelope(text);
      } on FormatException {
        if (successfulHttp) {
          rethrow;
        }
      }

      final isUnauthorized = response.statusCode == HttpStatus.unauthorized ||
          envelope?.code == HttpStatus.unauthorized;
      if (isUnauthorized && replayAfterRefresh && !hasRetried) {
        final refreshedToken = await _refreshAuthentication();
        if (refreshedToken != null && refreshedToken.isNotEmpty) {
          return await _send<T>(
            method: method,
            path: path,
            query: query,
            body: body,
            decodeData: decodeData,
            replayAfterRefresh: replayAfterRefresh,
            hasRetried: true,
            idempotencyKey: idempotencyKey,
            requestId: logicalRequestId,
            extraHeaders: extraHeaders,
            includeAccessToken: includeAccessToken,
            cancellationToken: cancellationToken,
            requestTimeoutOverride: requestTimeoutOverride,
          );
        }
      }

      if (!successfulHttp || envelope == null || envelope.code != 200) {
        throw ApiException(
          message: envelope == null || envelope.message.isEmpty
              ? _localized('请求失败，请稍后重试', 'Request failed. Try again later.')
              : _localizedServerMessage(envelope.message),
          httpStatus: response.statusCode,
          businessCode: envelope?.code,
          errorCode: envelope?.errorCode,
          data: envelope?.data,
        );
      }
      return envelope.hasData ? decodeData(envelope.data) : null;
    } on MultipartUploadCancelledException {
      rethrow;
    } on ApiException {
      rethrow;
    } on TimeoutException catch (error) {
      request?.abort(error);
      if (response != null) await _cancelResponse(response);
      throw ApiException(
        message: _localized('请求超时，请稍后重试', 'Request timed out. Try again.'),
        cause: error,
      );
    } on SocketException catch (error) {
      if (cancellationToken?.isCancelled == true) {
        throw const MultipartUploadCancelledException();
      }
      throw ApiException(
        message: _localized(
          '网络连接失败，请检查网络',
          'Network connection failed. Check your connection.',
        ),
        cause: error,
      );
    } on HttpException catch (error) {
      if (cancellationToken?.isCancelled == true) {
        throw const MultipartUploadCancelledException();
      }
      throw ApiException(
        message: _localized(
          '网络请求失败，请稍后重试',
          'Network request failed. Try again later.',
        ),
        cause: error,
      );
    } on FormatException catch (error) {
      throw ApiException(
        message: _localized(
          '服务响应格式异常',
          'The server returned an invalid response.',
        ),
        cause: error,
      );
    } finally {
      cancellationToken?._detach();
    }
  }

  Future<String?> _refreshAuthentication() {
    final running = _refreshInFlight;
    if (running != null) {
      return running;
    }
    final handler = _unauthorizedHandler;
    if (handler == null) {
      return Future<String?>.value();
    }
    late final Future<String?> operation;
    operation = Future<String?>.sync(handler).whenComplete(() {
      if (identical(_refreshInFlight, operation)) {
        _refreshInFlight = null;
      }
    });
    _refreshInFlight = operation;
    return operation;
  }

  void _applyStandardHeaders(
    HttpHeaders headers, {
    required String requestId,
  }) {
    _validateHeaderToken(requestId, 'X-Request-ID');
    headers.set('X-Request-ID', requestId);
    headers.set('X-Client', _clientName);
    headers.set('X-Client-Platform', Platform.operatingSystem);
    headers.set(HttpHeaders.acceptLanguageHeader, _languageTag);
  }

  String get _languageTag {
    try {
      final value = _languageTagProvider?.call().trim();
      return value == null || value.isEmpty ? 'zh-CN' : value;
    } on Object {
      return 'zh-CN';
    }
  }

  bool get _usesEnglish => _languageTag.toLowerCase().startsWith('en');

  String _localized(String chinese, String english) =>
      _usesEnglish ? english : chinese;

  String _localizedServerMessage(String message) => switch (message) {
        'AI_PROVIDER_UNAVAILABLE' => _localized(
            'AI 服务暂不可用，请稍后重试',
            'The AI service is temporarily unavailable. Try again later.',
          ),
        'PAYMENT_PROVIDER_UNAVAILABLE' => _localized(
            '支付服务暂不可用，请稍后重试',
            'The payment service is temporarily unavailable. Try again later.',
          ),
        _ => message,
      };

  Uri _resolve(String path, Map<String, Object?> query) {
    final normalizedPath = path.startsWith('/') ? path.substring(1) : path;
    final uri = _apiRoot.resolve(normalizedPath);
    final parameters = <String, String>{
      for (final entry in query.entries)
        if (entry.value != null) entry.key: entry.value.toString(),
    };
    return parameters.isEmpty ? uri : uri.replace(queryParameters: parameters);
  }

  ApiEnvelope<Object?> _decodeEnvelope(String text) {
    if (text.trim().isEmpty) {
      throw const FormatException('响应体为空');
    }
    final json = jsonDecode(text);
    if (json is! Map<String, dynamic>) {
      throw const FormatException('响应不是 JSON 对象');
    }
    return ApiEnvelope<Object?>.fromJson(json, (data) => data);
  }

  void _validateMultipartToken(String value, String label) {
    if (value.isEmpty || value.contains('\r') || value.contains('\n')) {
      throw ArgumentError.value(value, label, '$label 格式不正确');
    }
  }

  void _validateHeaderToken(String value, String label) {
    if (value.trim().isEmpty || value.contains('\r') || value.contains('\n')) {
      throw ArgumentError.value(value, label, '$label 格式不正确');
    }
  }

  void _validateHeaderName(String value) {
    if (!RegExp(r'^[A-Za-z0-9-]+$').hasMatch(value)) {
      throw ArgumentError.value(value, 'headerName', '请求头名称格式不正确');
    }
  }

  void close() => _httpClient.close(force: false);
}

String generateApiRequestId() {
  final random = Random.secure();
  final entropy = List<int>.generate(12, (_) => random.nextInt(256));
  final suffix =
      entropy.map((value) => value.toRadixString(16).padLeft(2, '0'));
  return '${DateTime.now().microsecondsSinceEpoch}-${suffix.join()}';
}

Future<void> _cancelResponse(HttpClientResponse response) async {
  try {
    final socket = await response.detachSocket();
    socket.destroy();
  } on Object {
    // The peer may already have closed the response while cancellation raced.
  }
}

final class MultipartFilePart {
  const MultipartFilePart({
    required this.fieldName,
    required this.fileName,
    required this.contentType,
    required List<int> bytes,
  })  : _bytes = bytes,
        localPath = null,
        _declaredByteLength = null;

  const MultipartFilePart.fromPath({
    required this.fieldName,
    required this.fileName,
    required this.contentType,
    required this.localPath,
    required int byteLength,
  })  : _bytes = null,
        assert(localPath != null && localPath != ''),
        _declaredByteLength = byteLength;

  final String fieldName;
  final String fileName;
  final String contentType;
  final List<int>? _bytes;
  final String? localPath;
  final int? _declaredByteLength;

  List<int> get bytes => _bytes ?? const [];

  int get byteLength => _bytes?.length ?? _declaredByteLength!;

  Stream<List<int>> openRead() {
    final inMemory = _bytes;
    if (inMemory != null) return Stream<List<int>>.value(inMemory);
    return File(localPath!).openRead();
  }

  Future<void> validateSource() async {
    final path = localPath;
    if (path == null) return;
    final declaredByteLength = _declaredByteLength;
    if (declaredByteLength == null || declaredByteLength < 0) {
      throw ArgumentError.value(
        _declaredByteLength,
        'byteLength',
        '文件大小格式不正确',
      );
    }
    final file = File(path);
    final actualLength = await file.length();
    if (actualLength != declaredByteLength) {
      throw StateError('待上传文件大小已发生变化');
    }
  }
}

final class _IoStreamHttpRequest implements StreamHttpRequest {
  _IoStreamHttpRequest(this._request);

  final HttpClientRequest _request;

  @override
  void setHeader(String name, String value) =>
      _request.headers.set(name, value);

  @override
  void add(List<int> bytes) => _request.add(bytes);

  @override
  Future<StreamHttpResponse> close() async =>
      _IoStreamHttpResponse(await _request.close());

  @override
  void abort() => _request.abort();
}

final class _IoStreamHttpResponse implements StreamHttpResponse {
  _IoStreamHttpResponse(this._response);

  final HttpClientResponse _response;

  @override
  int get statusCode => _response.statusCode;

  @override
  Stream<List<int>> get bytes => _response;

  @override
  Future<void> cancel() async {
    final socket = await _response.detachSocket();
    socket.destroy();
  }
}
