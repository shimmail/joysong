import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/agent/data/agent_remote_data_source.dart';
import 'package:joysong_flutter/features/agent/data/agent_repository_impl.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';

void main() {
  test('decodes named SSE events across UTF-8 and record boundaries', () async {
    final fixture = await _StreamFixture.start((request) async {
      expect(request.method, 'POST');
      expect(request.uri.path, '/api/chat/sessions/session-1/messages/stream');
      expect(
          request.headers.value(HttpHeaders.acceptHeader), 'text/event-stream');
      expect(request.headers.value(HttpHeaders.authorizationHeader),
          'Bearer token-1');
      expect(request.headers.value('Idempotency-Key'), 'key-1');
      expect(
        jsonDecode(await utf8.decoder.bind(request).join()),
        {'content': '你好', 'idempotencyKey': 'key-1'},
      );
      request.response
        ..statusCode = HttpStatus.ok
        ..headers.contentType =
            ContentType('text', 'event-stream', charset: 'utf-8');
      await _writeSplit(
        request.response,
        'event: started\n'
        'data: {"traceId":"trace-1","turnId":"turn-1","userMessage":${jsonEncode(_messageJson('user-1', '你好'))}}\n\n'
        'event: delta\n'
        'data: {"content":\n'
        'data: "你好"}\n\n'
        'event: completed\n'
        'data: {"turn":${jsonEncode(_turnJson('完整答复'))}}\n\n',
        splitInside: utf8.encode('你').first,
      );
      await request.response.close();
    });
    addTearDown(fixture.close);

    final events = await fixture.repository
        .streamMessage(
          sessionId: 'session-1',
          content: '你好',
          idempotencyKey: 'key-1',
        )
        .toList();

    expect(events, hasLength(3));
    final started = events[0] as AgentStreamStarted;
    expect(started.traceId, 'trace-1');
    expect(started.turnId, 'turn-1');
    expect(started.userMessage.content, '你好');
    expect((events[1] as AgentStreamDelta).content, '你好');
    expect((events[2] as AgentStreamCompleted).turn.message.content, '完整答复');
  });

  test('completed SSE decodes message-bound comparison request and report',
      () async {
    final fixture = await _StreamFixture.start((request) async {
      await utf8.decoder.bind(request).join();
      request.response
        ..statusCode = HttpStatus.ok
        ..headers.contentType =
            ContentType('text', 'event-stream', charset: 'utf-8')
        ..write(
          'event: completed\n'
          'data: {"turn":${jsonEncode(_comparisonTurnJson('完整答复'))}}\n\n',
        );
      await request.response.close();
    });
    addTearDown(fixture.close);

    final event = await fixture.repository
        .streamMessage(
          sessionId: 'session-1',
          content: '比较项目',
          idempotencyKey: 'comparison-key',
        )
        .single;

    final completed = event as AgentStreamCompleted;
    expect(completed.turn.message.comparisonRequest?.operands.single.entityId,
        'project-1');
    expect(completed.turn.message.catalogReport?.title, 'Message report');
  });

  test('decodes a stable terminal failure without exposing raw SSE', () async {
    final fixture = await _StreamFixture.start((request) async {
      await utf8.decoder.bind(request).join();
      request.response
        ..statusCode = HttpStatus.ok
        ..headers.contentType =
            ContentType('text', 'event-stream', charset: 'utf-8')
        ..write(
          'event: error\n'
          'data: {"code":"AI_PROVIDER_TIMEOUT","traceId":"trace-2","retryable":true}\n\n',
        );
      await request.response.close();
    });
    addTearDown(fixture.close);

    final events = await fixture.repository
        .streamMessage(
          sessionId: 'session-1',
          content: 'hello',
          idempotencyKey: 'key-2',
        )
        .toList();

    final failed = events.single as AgentStreamFailed;
    expect(failed.code, 'AI_PROVIDER_TIMEOUT');
    expect(failed.traceId, 'trace-2');
    expect(failed.retryable, isTrue);
  });

  test('keeps parsing legacy failed terminal events', () async {
    final fixture = await _StreamFixture.start((request) async {
      await utf8.decoder.bind(request).join();
      request.response
        ..statusCode = HttpStatus.ok
        ..headers.contentType =
            ContentType('text', 'event-stream', charset: 'utf-8')
        ..write(
          'event: failed\n'
          'data: {"code":"AI_PROVIDER_UNAVAILABLE","traceId":"trace-legacy","retryable":true}\n\n',
        );
      await request.response.close();
    });
    addTearDown(fixture.close);

    final events = await fixture.repository
        .streamMessage(
          sessionId: 'session-1',
          content: 'hello',
          idempotencyKey: 'legacy-key',
        )
        .toList();

    expect(events.single, isA<AgentStreamFailed>());
  });

  test('fails when EOF arrives without a terminal event', () async {
    final fixture = await _StreamFixture.start((request) async {
      await utf8.decoder.bind(request).join();
      request.response
        ..statusCode = HttpStatus.ok
        ..headers.contentType =
            ContentType('text', 'event-stream', charset: 'utf-8')
        ..write('event: delta\ndata: {"content":"partial"}\n\n');
      await request.response.close();
    });
    addTearDown(fixture.close);

    await expectLater(
      fixture.repository.streamMessage(
        sessionId: 'session-1',
        content: 'hello',
        idempotencyKey: 'key-3',
      ),
      emitsInOrder([
        isA<AgentStreamDelta>(),
        emitsError(
          isA<StateError>().having(
            (error) => error.message,
            'message',
            'AGENT_STREAM_MISSING_TERMINAL',
          ),
        ),
      ]),
    );
  });

  test('closes the domain stream after an upstream decoder error', () async {
    final upstreamCancelled = Completer<void>();
    late final StreamController<List<int>> bytes;
    bytes = StreamController<List<int>>(
      onListen: () {
        bytes.add(const [0xff]);
        bytes.close();
      },
      onCancel: upstreamCancelled.complete,
    );
    final repository = _repository(
      (_) async => _FakeStreamRequest(_FakeStreamResponse(bytes: bytes.stream)),
    );

    await expectLater(
      repository.streamMessage(
        sessionId: 'session-1',
        content: 'hello',
        idempotencyKey: 'key-error',
      ),
      emitsInOrder([emitsError(isA<FormatException>()), emitsDone]),
    );
    await upstreamCancelled.future;
  });

  test('cancel while opening aborts before the body is sent', () async {
    final request = _FakeStreamRequest();
    final opened = Completer<StreamHttpRequest>();
    final repository = _repository((_) => opened.future);
    final subscription = repository
        .streamMessage(
          sessionId: 'session-1',
          content: 'hello',
          idempotencyKey: 'key-open',
        )
        .listen((_) {});

    await subscription.cancel();
    opened.complete(request);
    await request.aborted.future;

    expect(request.writeCalls, 0);
    expect(request.closeCalls, 0);
  });

  test('cancel while awaiting auth token aborts before body send', () async {
    final request = _FakeStreamRequest();
    final token = Completer<String?>();
    final repository = _repository(
      (_) async => request,
      accessTokenProvider: () => token.future,
    );
    final subscription = repository
        .streamMessage(
          sessionId: 'session-1',
          content: 'hello',
          idempotencyKey: 'key-token',
        )
        .listen((_) {});
    await request.headersSet.future;

    await subscription.cancel();
    token.complete('late-token');
    await request.aborted.future;

    expect(request.writeCalls, 0);
    expect(request.closeCalls, 0);
  });

  test('cancel while POST awaits response closes a late response', () async {
    final response = _FakeStreamResponse();
    final responsePending = Completer<StreamHttpResponse>();
    final request = _FakeStreamRequest(response, responsePending.future);
    final repository = _repository((_) async => request);
    final subscription = repository
        .streamMessage(
          sessionId: 'session-1',
          content: 'hello',
          idempotencyKey: 'key-post',
        )
        .listen((_) {});
    await request.closeStarted.future;

    await subscription.cancel();
    responsePending.complete(response);

    await request.aborted.future;
    await response.cancelled.future;
  });

  test('cancel after response arrival cancels its byte subscription', () async {
    final response = _FakeStreamResponse();
    final request = _FakeStreamRequest(response);
    final repository = _repository((_) async => request);
    final subscription = repository
        .streamMessage(
          sessionId: 'session-1',
          content: 'hello',
          idempotencyKey: 'key-response',
        )
        .listen((_) {});
    await response.listened.future;

    await subscription.cancel();

    await response.cancelled.future;
  });

  test('subscription cancel absorbs asynchronous response close races',
      () async {
    final response = _FakeStreamResponse(
      cancelError: const SocketException('already closed'),
    );
    final request = _FakeStreamRequest(response);
    final repository = _repository((_) async => request);
    final subscription = repository
        .streamMessage(
          sessionId: 'session-1',
          content: 'hello',
          idempotencyKey: 'key-close-race',
        )
        .listen((_) {});
    await response.listened.future;

    await subscription.cancel();
    await Future<void>.delayed(Duration.zero);
  });
}

AgentRepositoryImpl _repository(
  StreamHttpRequestOpener opener, {
  AccessTokenProvider? accessTokenProvider,
}) {
  final apiClient = ApiClient(
    apiRoot: Uri.parse('http://localhost/api/'),
    accessTokenProvider: accessTokenProvider,
    streamRequestOpener: opener,
  );
  return AgentRepositoryImpl(ApiAgentRemoteDataSource(apiClient: apiClient));
}

final class _FakeStreamRequest implements StreamHttpRequest {
  _FakeStreamRequest([
    StreamHttpResponse? response,
    Future<StreamHttpResponse>? responseFuture,
  ])  : _response = response ?? _FakeStreamResponse(),
        _responseFuture = responseFuture;

  final StreamHttpResponse _response;
  final Future<StreamHttpResponse>? _responseFuture;
  final aborted = Completer<void>();
  final headersSet = Completer<void>();
  final closeStarted = Completer<void>();
  int writeCalls = 0;
  int closeCalls = 0;

  final headers = <String, String>{};

  @override
  void setHeader(String name, String value) {
    headers[name] = value;
    if (!headersSet.isCompleted) headersSet.complete();
  }

  @override
  void abort() {
    if (!aborted.isCompleted) aborted.complete();
  }

  @override
  Future<StreamHttpResponse> close() async {
    closeCalls += 1;
    if (!closeStarted.isCompleted) closeStarted.complete();
    return _responseFuture == null ? _response : await _responseFuture;
  }

  @override
  void add(List<int> body) {
    writeCalls += 1;
  }
}

final class _FakeStreamResponse implements StreamHttpResponse {
  _FakeStreamResponse({
    Stream<List<int>>? bytes,
    this.cancelError,
  }) : _bytes = bytes;

  final Stream<List<int>>? _bytes;
  final Object? cancelError;
  final listened = Completer<void>();
  final cancelled = Completer<void>();

  @override
  int get statusCode => HttpStatus.ok;

  @override
  late final Stream<List<int>> bytes = _bytes ??
      Stream<List<int>>.multi((events) {
        if (!listened.isCompleted) listened.complete();
        events.onCancel = () {
          if (!cancelled.isCompleted) cancelled.complete();
        };
      });

  @override
  Future<void> cancel() async {
    if (!cancelled.isCompleted) cancelled.complete();
    await Future<void>.delayed(Duration.zero);
    if (cancelError case final error?) throw error;
  }
}

final class _StreamFixture {
  _StreamFixture(this.server, this.apiClient, this.repository);

  final HttpServer server;
  final ApiClient apiClient;
  final AgentRepositoryImpl repository;

  static Future<_StreamFixture> start(
    Future<void> Function(HttpRequest request) handler,
  ) async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen(handler);
    final apiClient = ApiClient(
      apiRoot: Uri.parse('http://${server.address.host}:${server.port}/api/'),
      accessTokenProvider: () async => 'token-1',
    );
    final remote = ApiAgentRemoteDataSource(apiClient: apiClient);
    return _StreamFixture(server, apiClient, AgentRepositoryImpl(remote));
  }

  Future<void> close() async {
    apiClient.close();
    await server.close(force: true);
  }
}

Future<void> _writeSplit(
  HttpResponse response,
  String text, {
  required int splitInside,
}) async {
  final bytes = utf8.encode(text);
  final utf8Boundary = bytes.indexOf(splitInside);
  final boundaries = <int>{
    1,
    17,
    utf8Boundary + 1,
    utf8Boundary + 2,
    bytes.length
  }.where((value) => value > 0 && value <= bytes.length).toList()
    ..sort();
  var start = 0;
  for (final end in boundaries) {
    response.add(bytes.sublist(start, end));
    await response.flush();
    start = end;
  }
}

Map<String, Object?> _messageJson(String id, String content) => {
      'id': id,
      'sessionId': 'session-1',
      'role': 'USER',
      'content': content,
      'createdAt': '2026-08-13T10:00:00',
    };

Map<String, Object?> _turnJson(String content) => {
      'message': _messageJson('assistant-1', content)..['role'] = 'ASSISTANT',
      'catalogReport': null,
      'catalogItems': const <Object?>[],
      'intent': 'GENERAL_CHAT',
      'queryTarget': null,
      'nextAction': 'NONE',
    };

Map<String, Object?> _comparisonTurnJson(String content) => {
      ..._turnJson(content),
      'message': {
        ..._messageJson('assistant-1', content),
        'role': 'ASSISTANT',
        'comparisonRequest': {
          'operands': [
            {
              'entityType': 'PROJECT',
              'entityId': 'project-1',
              'displayName': 'Project',
            },
          ],
          'targetType': 'PROJECT',
          'dimensions': ['PRICE'],
          'constraints': {'city': 'Shanghai'},
          'missingFields': <String>[],
        },
        'catalogReport': {
          'mode': 'COMPARISON',
          'title': 'Message report',
          'summary': 'summary',
          'items': const <Object?>[],
          'comparisonDimensions': ['PRICE'],
          'warnings': const <String>[],
        },
      },
    };
