import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';

void main() {
  test('adds request, client, platform, language, and auth headers', () async {
    late final HttpHeaders receivedHeaders;
    final server = await _serve((request) async {
      receivedHeaders = request.headers;
      await _respond(request, data: {'value': 'ok'});
    });
    addTearDown(() => server.close(force: true));
    final client = ApiClient(
      apiRoot: _apiRoot(server),
      accessTokenProvider: () async => 'access-token',
      languageTagProvider: () => 'en-US',
      requestIdProvider: () => 'request-1',
    );
    addTearDown(client.close);

    final result = await client.get<String>(
      'headers',
      decodeData: (json) => (json as Map)['value'].toString(),
    );

    expect(result, 'ok');
    expect(receivedHeaders.value('x-request-id'), 'request-1');
    expect(receivedHeaders.value('x-client'), 'joysong-flutter');
    expect(receivedHeaders.value('x-client-platform'), isNotEmpty);
    expect(receivedHeaders.value(HttpHeaders.acceptLanguageHeader), 'en-US');
    expect(
      receivedHeaders.value(HttpHeaders.authorizationHeader),
      'Bearer access-token',
    );
  });

  test('concurrent unauthorized GET requests share one refresh and replay once',
      () async {
    var accessToken = 'old-token';
    var refreshCount = 0;
    var oldRequests = 0;
    var newRequests = 0;
    final requestIds = <String, int>{};
    final server = await _serve((request) async {
      final requestId = request.headers.value('x-request-id')!;
      requestIds.update(requestId, (count) => count + 1, ifAbsent: () => 1);
      if (request.headers.value(HttpHeaders.authorizationHeader) ==
          'Bearer old-token') {
        oldRequests += 1;
        await _respond(request, status: 401, code: 401, message: 'expired');
        return;
      }
      newRequests += 1;
      await _respond(request, data: {'value': request.uri.path});
    });
    addTearDown(() => server.close(force: true));
    var requestSequence = 0;
    final client = ApiClient(
      apiRoot: _apiRoot(server),
      accessTokenProvider: () async => accessToken,
      requestIdProvider: () => 'request-${++requestSequence}',
    );
    addTearDown(client.close);
    client.configureUnauthorizedHandler(() async {
      refreshCount += 1;
      await Future<void>.delayed(const Duration(milliseconds: 30));
      accessToken = 'new-token';
      return accessToken;
    });

    final results = await Future.wait([
      client.get<String>('first', decodeData: (json) => 'first'),
      client.get<String>('second', decodeData: (json) => 'second'),
    ]);

    expect(results, ['first', 'second']);
    expect(refreshCount, 1);
    expect(oldRequests, 2);
    expect(newRequests, 2);
    expect(requestIds.length, 2);
    expect(requestIds.values, everyElement(2));
  });

  test('ordinary write is not replayed after unauthorized response', () async {
    var requests = 0;
    var refreshCount = 0;
    final server = await _serve((request) async {
      requests += 1;
      await _respond(request, status: 401, code: 401, message: 'expired');
    });
    addTearDown(() => server.close(force: true));
    final client = ApiClient(apiRoot: _apiRoot(server));
    addTearDown(client.close);
    client.configureUnauthorizedHandler(() async {
      refreshCount += 1;
      return 'new-token';
    });

    await expectLater(
      client.post<Object?>('orders', decodeData: (json) => json),
      throwsA(isA<ApiException>()),
    );

    expect(requests, 1);
    expect(refreshCount, 0);
  });

  test('maps provider machine codes to the active application language',
      () async {
    var languageTag = 'zh-CN';
    final server = await _serve((request) async {
      final machineCode = request.uri.path.endsWith('/ai')
          ? 'AI_PROVIDER_UNAVAILABLE'
          : 'PAYMENT_PROVIDER_UNAVAILABLE';
      await _respond(request, code: 503, message: machineCode);
    });
    addTearDown(() => server.close(force: true));
    final client = ApiClient(
      apiRoot: _apiRoot(server),
      languageTagProvider: () => languageTag,
    );
    addTearDown(client.close);

    await expectLater(
      client.post<Object?>('ai', decodeData: (json) => json),
      throwsA(
        isA<ApiException>().having(
          (error) => error.message,
          'message',
          'AI 服务暂不可用，请稍后重试',
        ),
      ),
    );
    languageTag = 'en-US';
    await expectLater(
      client.post<Object?>('payment', decodeData: (json) => json),
      throwsA(
        isA<ApiException>().having(
          (error) => error.message,
          'message',
          'The payment service is temporarily unavailable. Try again later.',
        ),
      ),
    );
  });

  test('exposes only string server error codes on HTTP and business failures',
      () async {
    var request = 0;
    final server = await _serve((incoming) async {
      request += 1;
      await _respond(
        incoming,
        status: request == 1 ? 409 : 200,
        code: request == 1 ? 409 : 422,
        message: '冲突',
        data: null,
        errorCode: request == 3 ? 'BASE_REVISION_CONFLICT' : null,
        includeErrorCode: request >= 2,
      );
    });
    addTearDown(() => server.close(force: true));
    final client = ApiClient(apiRoot: _apiRoot(server));
    addTearDown(client.close);

    await expectLater(
      client.get<Object?>('conflict', decodeData: (json) => json),
      throwsA(isA<ApiException>().having(
        (error) => error.errorCode,
        'errorCode',
        isNull,
      )),
    );
    await expectLater(
      client.get<Object?>('business', decodeData: (json) => json),
      throwsA(isA<ApiException>().having(
        (error) => error.errorCode,
        'errorCode',
        isNull,
      )),
    );
    await expectLater(
      client.get<Object?>('machine', decodeData: (json) => json),
      throwsA(isA<ApiException>().having(
        (error) => error.errorCode,
        'errorCode',
        'BASE_REVISION_CONFLICT',
      )),
    );
  });

  test('idempotent write preserves its key and replays after refresh',
      () async {
    var accessToken = 'old-token';
    var refreshCount = 0;
    final keys = <String?>[];
    final server = await _serve((request) async {
      keys.add(request.headers.value('idempotency-key'));
      if (request.headers.value(HttpHeaders.authorizationHeader) ==
          'Bearer old-token') {
        await _respond(request, status: 401, code: 401, message: 'expired');
        return;
      }
      await _respond(request, data: {'id': 'order-1'});
    });
    addTearDown(() => server.close(force: true));
    final client = ApiClient(
      apiRoot: _apiRoot(server),
      accessTokenProvider: () async => accessToken,
    );
    addTearDown(client.close);
    client.configureUnauthorizedHandler(() async {
      refreshCount += 1;
      accessToken = 'new-token';
      return accessToken;
    });

    final result = await client.postIdempotent<String>(
      'orders',
      idempotencyKey: 'checkout-1',
      body: {'projectId': 'project-1'},
      decodeData: (json) => (json as Map)['id'].toString(),
    );

    expect(result, 'order-1');
    expect(refreshCount, 1);
    expect(keys, ['checkout-1', 'checkout-1']);
  });
}

Future<HttpServer> _serve(
  Future<void> Function(HttpRequest request) handler,
) async {
  final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  server.listen((request) async {
    try {
      await handler(request);
    } on Object catch (error, stackTrace) {
      request.response.statusCode = 500;
      request.response.write('$error\n$stackTrace');
      await request.response.close();
    }
  });
  return server;
}

Uri _apiRoot(HttpServer server) =>
    Uri.parse('http://${server.address.host}:${server.port}/api/');

Future<void> _respond(
  HttpRequest request, {
  int status = 200,
  int code = 200,
  String message = 'ok',
  Object? data,
  String? errorCode,
  bool includeErrorCode = false,
}) async {
  await request.drain<void>();
  request.response.statusCode = status;
  request.response.headers.contentType = ContentType.json;
  request.response.write(jsonEncode({
    'code': code,
    'message': message,
    'data': data,
    if (includeErrorCode) 'errorCode': errorCode,
  }));
  await request.response.close();
}
