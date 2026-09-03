import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';

void main() {
  test('file multipart has exact length and repeats the same file after 401',
      () async {
    final temporaryDirectory =
        await Directory.systemTemp.createTemp('joysong-multipart-test-');
    final file = File('${temporaryDirectory.path}/payload.jpg');
    const payload = <int>[0xff, 0xd8, 0xff, 1, 2, 3, 4, 5, 6, 7, 8, 9];
    await file.writeAsBytes(payload);
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final requests = <_CapturedRequest>[];
    var token = 'expired-token';
    final client = ApiClient(
      apiRoot: Uri(
        scheme: 'http',
        host: InternetAddress.loopbackIPv4.address,
        port: server.port,
        path: '/api/',
      ),
      accessTokenProvider: () async => token,
      requestIdProvider: () => 'request-1',
    );
    client.configureUnauthorizedHandler(() async {
      token = 'fresh-token';
      return token;
    });
    server.listen((request) async {
      final body = BytesBuilder(copy: false);
      await for (final chunk in request) {
        body.add(chunk);
      }
      requests.add(
        _CapturedRequest(
          authorization: request.headers.value(HttpHeaders.authorizationHeader),
          idempotencyKey: request.headers.value('Idempotency-Key'),
          requestId: request.headers.value('X-Request-ID'),
          declaredLength: request.contentLength,
          body: body.takeBytes(),
        ),
      );
      request.response.headers.contentType = ContentType.json;
      if (requests.length == 1) {
        request.response.statusCode = HttpStatus.unauthorized;
        request.response.write(jsonEncode({'code': 401, 'message': 'expired'}));
      } else {
        request.response.write(
          jsonEncode({
            'code': 200,
            'data': {
              'uploadId': 'upload-1',
              'status': 'COMPLETE',
              'url': '/media/photo.jpg',
            },
          }),
        );
      }
      await request.response.close();
    });

    try {
      final progress = <int>[];
      final response = await client.postFileMultipart<Map<Object?, Object?>>(
        '/upload',
        idempotencyKey: 'upload-1',
        fields: const {'folder': 'diaries'},
        files: [
          MultipartFilePart.fromPath(
            fieldName: 'file',
            fileName: 'upload-1.jpg',
            contentType: 'image/jpeg',
            localPath: file.path,
            byteLength: payload.length,
          ),
        ],
        onProgress: (sent, total) {
          expect(total, payload.length);
          progress.add(sent);
        },
        decodeData: (json) => Map<Object?, Object?>.from(json! as Map),
      );

      expect(response?['status'], 'COMPLETE');
      expect(requests, hasLength(2));
      expect(requests.map((item) => item.authorization), [
        'Bearer expired-token',
        'Bearer fresh-token',
      ]);
      expect(
        requests.map((item) => item.idempotencyKey),
        everyElement('upload-1'),
      );
      expect(requests.map((item) => item.requestId), everyElement('request-1'));
      for (final request in requests) {
        expect(request.declaredLength, request.body.length);
        expect(_containsSequence(request.body, payload), isTrue);
      }
      expect(progress, isNotEmpty);
      expect(progress.last, payload.length);
    } finally {
      client.close();
      await server.close(force: true);
      await temporaryDirectory.delete(recursive: true);
    }
  });

  test('cancellable GET aborts the active status request', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final requestArrived = Completer<void>();
    final releaseServer = Completer<void>();
    server.listen((request) async {
      await request.drain<void>();
      if (!requestArrived.isCompleted) requestArrived.complete();
      await releaseServer.future;
      try {
        request.response.write(
          jsonEncode({
            'code': 200,
            'data': {'status': 'PENDING'}
          }),
        );
        await request.response.close();
      } on Object {
        // The client is expected to close this socket first.
      }
    });
    final client = ApiClient(
      apiRoot: Uri(
        scheme: 'http',
        host: InternetAddress.loopbackIPv4.address,
        port: server.port,
        path: '/api/',
      ),
    );
    final cancellation = MultipartCancellationToken();

    try {
      final pending = client.getCancellable<Object?>(
        '/upload/status/upload-1',
        cancellationToken: cancellation,
        timeout: const Duration(seconds: 30),
        decodeData: (json) => json,
      );
      final expectation = expectLater(
        pending.timeout(const Duration(seconds: 3)),
        throwsA(isA<MultipartUploadCancelledException>()),
      );
      await requestArrived.future.timeout(const Duration(seconds: 3));
      await cancellation.cancel();
      await expectation;
    } finally {
      if (!releaseServer.isCompleted) releaseServer.complete();
      client.close();
      await server.close(force: true);
    }
  });

  test('cancellable multipart aborts while waiting for the response', () async {
    final temporaryDirectory =
        await Directory.systemTemp.createTemp('joysong-multipart-cancel-test-');
    final file = File('${temporaryDirectory.path}/payload.jpg');
    const payload = <int>[0xff, 0xd8, 0xff, 1, 2, 3, 4, 5];
    await file.writeAsBytes(payload);
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final requestArrived = Completer<void>();
    final releaseServer = Completer<void>();
    server.listen((request) async {
      await request.drain<void>();
      if (!requestArrived.isCompleted) requestArrived.complete();
      await releaseServer.future;
      try {
        request.response.write(jsonEncode({'code': 200, 'data': {}}));
        await request.response.close();
      } on Object {
        // The cancellation path deliberately closes this socket first.
      }
    });
    final client = ApiClient(
      apiRoot: Uri(
        scheme: 'http',
        host: InternetAddress.loopbackIPv4.address,
        port: server.port,
        path: '/api/',
      ),
    );
    final cancellation = MultipartCancellationToken();

    try {
      final pending = client.postFileMultipart<Object?>(
        '/upload',
        idempotencyKey: 'upload-1',
        files: [
          MultipartFilePart.fromPath(
            fieldName: 'file',
            fileName: 'upload-1.jpg',
            contentType: 'image/jpeg',
            localPath: file.path,
            byteLength: payload.length,
          ),
        ],
        cancellationToken: cancellation,
        decodeData: (json) => json,
      );
      final expectation = expectLater(
        pending.timeout(const Duration(seconds: 3)),
        throwsA(isA<MultipartUploadCancelledException>()),
      );
      await requestArrived.future.timeout(const Duration(seconds: 3));
      await cancellation.cancel();
      await expectation;
    } finally {
      if (!releaseServer.isCompleted) releaseServer.complete();
      client.close();
      await server.close(force: true);
      await temporaryDirectory.delete(recursive: true);
    }
  });
}

final class _CapturedRequest {
  const _CapturedRequest({
    required this.authorization,
    required this.idempotencyKey,
    required this.requestId,
    required this.declaredLength,
    required this.body,
  });

  final String? authorization;
  final String? idempotencyKey;
  final String? requestId;
  final int declaredLength;
  final List<int> body;
}

bool _containsSequence(List<int> value, List<int> sequence) {
  for (var start = 0; start <= value.length - sequence.length; start += 1) {
    var matches = true;
    for (var offset = 0; offset < sequence.length; offset += 1) {
      if (value[start + offset] != sequence[offset]) {
        matches = false;
        break;
      }
    }
    if (matches) return true;
  }
  return false;
}
