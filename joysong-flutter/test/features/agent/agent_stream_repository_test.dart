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

  test('cancelling the domain stream closes the HTTP response subscription',
      () async {
    final cancelled = Completer<void>();
    late final StreamController<List<int>> bytes;
    bytes = StreamController<List<int>>(
      onListen: () => bytes.add(
        utf8.encode('event: delta\ndata: {"content":"first"}\n\n'),
      ),
      onCancel: cancelled.complete,
    );
    final apiClient = ApiClient(apiRoot: Uri.parse('http://localhost/api/'));
    addTearDown(apiClient.close);
    final repository = AgentRepositoryImpl(
      ApiAgentRemoteDataSource(
        apiClient: apiClient,
        streamByteSource: () => bytes.stream,
      ),
    );

    late final StreamSubscription<AgentStreamEvent> subscription;
    final first = Completer<void>();
    subscription = repository
        .streamMessage(
      sessionId: 'session-1',
      content: 'hello',
      idempotencyKey: 'key-4',
    )
        .listen((_) {
      if (!first.isCompleted) first.complete();
    });
    await first.future;
    await subscription.cancel();

    await cancelled.future;
  });
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
    final remote = ApiAgentRemoteDataSource(
      apiClient: apiClient,
      accessTokenProvider: () async => 'token-1',
    );
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
