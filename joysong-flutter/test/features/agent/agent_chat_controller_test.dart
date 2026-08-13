import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/agent/data/agent_remote_data_source.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/domain/agent_repository.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_controller.dart';

void main() {
  test('ChatMessage decodes persisted catalog items with old-response fallback',
      () {
    final withCards = ChatMessage.fromJson({
      'id': 'assistant-1',
      'sessionId': 'session-1',
      'role': 'ASSISTANT',
      'content': 'reply',
      'createdAt': '2026-08-12T00:00:00',
      'catalogItems': [_catalogItemJson],
    });
    final legacy = ChatMessage.fromJson({
      'id': 'assistant-2',
      'sessionId': 'session-1',
      'role': 'ASSISTANT',
      'content': 'legacy',
      'createdAt': '2026-08-12T00:00:01',
    });

    expect(withCards.catalogItems.single.id, 'project-1');
    expect(legacy.catalogItems, isEmpty);
  });

  test('openSession keeps only the latest 20 messages', () async {
    final messages = _messages(25);
    final controller = AgentChatController(
      repository: _FakeAgentRepository(messages: messages),
      recentMessageLimit: 20,
    );

    await controller.openSession(_session);

    expect(controller.state.messages.length, 20);
    expect(controller.state.messages.first.id, messages[5].id);
    expect(controller.state.messages.last.id, messages[24].id);
  });

  test('streaming send trims the conversation to the latest 20 messages',
      () async {
    final messages = _messages(25);
    final stream = StreamController<AgentStreamEvent>();
    final controller = AgentChatController(
      repository: _FakeAgentRepository(messages: messages, streams: [stream]),
      recentMessageLimit: 20,
    );
    await controller.openSession(_session);

    final send = controller.send('想改善肤质');
    await Future<void>.delayed(Duration.zero);
    stream.add(const AgentStreamCompleted(turn: _completeTurn));
    await stream.close();
    await send;

    expect(controller.state.messages.length, 20);
    expect(controller.state.messages.first.id, messages[7].id);
    expect(controller.state.messages.last.content, '完整答复');
  });

  test('send immediately adds one user message and one assistant placeholder',
      () async {
    final stream = StreamController<AgentStreamEvent>();
    final repository = _FakeAgentRepository(streams: [stream]);
    final controller = AgentChatController(
      repository: repository,
      recentMessageLimit: 20,
    );

    final send = controller.send('想改善肤质');
    await Future<void>.delayed(Duration.zero);

    expect(repository.createCalls, 1);
    expect(repository.streamCalls, 1);
    expect(controller.state.deliveryState, ChatDeliveryState.sending);
    expect(controller.state.messages.map((message) => message.role),
        ['USER', 'ASSISTANT']);
    expect(controller.state.messages.last.content, isEmpty);
    expect(controller.state.messages.last.isTemporary, isTrue);
    expect(
        controller.state.streamingMessageId, controller.state.messages.last.id);

    stream.add(const AgentStreamCompleted(turn: _completeTurn));
    await stream.close();
    await send;
  });

  test('ordered deltas append only to the active assistant placeholder',
      () async {
    final stream = StreamController<AgentStreamEvent>();
    final repository = _FakeAgentRepository(streams: [stream]);
    final controller = AgentChatController(repository: repository);
    final send = controller.send('想改善肤质');
    await Future<void>.delayed(Duration.zero);

    stream
      ..add(const AgentStreamDelta(content: '第一段'))
      ..add(const AgentStreamDelta(content: '，第二段'));
    await Future<void>.delayed(Duration.zero);

    expect(controller.state.messages.last.content, '第一段，第二段');
    expect(controller.state.messages.where((message) => !message.isUser),
        hasLength(1));
    stream.add(const AgentStreamCompleted(turn: _completeTurn));
    await stream.close();
    await send;
  });

  test('completed event replaces placeholder with persisted assistant message',
      () async {
    final stream = StreamController<AgentStreamEvent>();
    final controller = AgentChatController(
      repository: _FakeAgentRepository(streams: [stream]),
    );
    final send = controller.send('想改善肤质');
    await Future<void>.delayed(Duration.zero);
    final placeholderId = controller.state.messages.last.id;

    stream.add(const AgentStreamCompleted(turn: _completeTurn));
    await stream.close();
    await send;

    expect(controller.state.messages.last.id, 'assistant-1');
    expect(controller.state.messages.last.id, isNot(placeholderId));
    expect(controller.state.messages.last.content, '完整答复');
    expect(controller.state.messages.last.isTemporary, isFalse);
    expect(controller.state.streamingMessageId, isNull);
    expect(controller.state.failedMessageId, isNull);
    expect(controller.state.latestTurn, _completeTurn);
    expect(controller.state.deliveryState, ChatDeliveryState.completed);
  });

  test('failed event retains partial text and marks local assistant retryable',
      () async {
    final stream = StreamController<AgentStreamEvent>();
    final repository = _FakeAgentRepository(streams: [stream]);
    final controller = AgentChatController(repository: repository);
    final send = controller.send('想改善肤质');
    await Future<void>.delayed(Duration.zero);
    stream
      ..add(const AgentStreamDelta(content: '已经生成的内容'))
      ..add(const AgentStreamFailed(
        code: 'UPSTREAM_TIMEOUT',
        traceId: 'trace-1',
        retryable: true,
      ));
    await stream.close();
    await send;

    expect(controller.state.deliveryState, ChatDeliveryState.failed);
    expect(controller.state.messages.last.content, '已经生成的内容');
    expect(controller.state.messages.last.isTemporary, isTrue);
    expect(controller.state.failedMessageId, controller.state.messages.last.id);
    expect(controller.state.streamingMessageId, isNull);
  });

  test('retry replaces failed placeholder without duplicating user message',
      () async {
    final first = StreamController<AgentStreamEvent>();
    final second = StreamController<AgentStreamEvent>();
    final repository = _FakeAgentRepository(streams: [first, second]);
    final controller = AgentChatController(
      repository: repository,
      recentMessageLimit: 20,
    );

    final initialSend = controller.send('在吗');
    await Future<void>.delayed(Duration.zero);
    final failedId = controller.state.messages.last.id;
    first
      ..add(const AgentStreamDelta(content: '旧内容'))
      ..add(const AgentStreamFailed(
        code: 'UPSTREAM_TIMEOUT',
        traceId: null,
        retryable: true,
      ));
    await first.close();
    await initialSend;

    final retry = controller.retry();
    await Future<void>.delayed(Duration.zero);
    expect(controller.state.messages.where((message) => message.isUser),
        hasLength(1));
    expect(controller.state.messages.where((message) => !message.isUser),
        hasLength(1));
    expect(controller.state.messages.last.id, isNot(failedId));
    expect(controller.state.messages.last.content, isEmpty);

    second.add(const AgentStreamCompleted(turn: _completeTurn));
    await second.close();
    await retry;

    expect(repository.streamCalls, 2);
    expect(repository.idempotencyKeys.length, 2);
    expect(repository.idempotencyKeys.first, repository.idempotencyKeys.last);
    expect(controller.state.deliveryState, ChatDeliveryState.completed);
  });

  test('duplicate send while streaming is ignored', () async {
    final stream = StreamController<AgentStreamEvent>();
    final repository = _FakeAgentRepository(streams: [stream]);
    final controller = AgentChatController(repository: repository);

    final first = controller.send('在吗');
    await Future<void>.delayed(Duration.zero);
    await controller.send('第二条');

    expect(repository.streamCalls, 1);
    expect(controller.state.messages.where((message) => message.isUser),
        hasLength(1));
    stream.add(const AgentStreamCompleted(turn: _completeTurn));
    await stream.close();
    await first;
  });

  test('opening another session cancels stream and ignores late events',
      () async {
    final stream = StreamController<AgentStreamEvent>();
    final repository = _FakeAgentRepository(streams: [stream]);
    final controller = AgentChatController(repository: repository);
    final send = controller.send('在吗');
    await Future<void>.delayed(Duration.zero);

    await controller.openSession(_otherSession);
    stream.add(const AgentStreamDelta(content: '迟到内容'));
    await Future<void>.delayed(Duration.zero);

    expect(repository.cancelCalls, 1);
    expect(controller.state.activeSession?.id, _otherSession.id);
    expect(controller.state.messages, isEmpty);
    await stream.close();
    await send;
  });

  test('dispose cancels the active stream subscription', () async {
    final stream = StreamController<AgentStreamEvent>();
    final repository = _FakeAgentRepository(streams: [stream]);
    final controller = AgentChatController(repository: repository);
    final send = controller.send('在吗');
    await Future<void>.delayed(Duration.zero);

    controller.dispose();
    await Future<void>.delayed(Duration.zero);

    expect(repository.cancelCalls, 1);
    await stream.close();
    await send;
  });

  test('delete during a streaming send fails explicitly without deleting',
      () async {
    final stream = StreamController<AgentStreamEvent>();
    final repository = _FakeAgentRepository(streams: [stream]);
    final controller = AgentChatController(repository: repository);
    final send = controller.send('想改善肤质');

    expect(controller.state.deliveryState, ChatDeliveryState.sending);
    await expectLater(
      controller.deleteSession(_session),
      throwsA(
        isA<StateError>().having(
          (error) => error.message,
          'message',
          'CHAT_SEND_IN_PROGRESS',
        ),
      ),
    );
    expect(repository.deleteCalls, 0);

    stream.add(const AgentStreamCompleted(turn: _completeTurn));
    await stream.close();
    await send;
  });

  test('REST send includes an idempotency key in exactly one POST', () async {
    var requests = 0;
    Map<String, Object?>? requestBody;
    String? idempotencyHeader;
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));
    server.listen((request) async {
      requests += 1;
      idempotencyHeader = request.headers.value('Idempotency-Key');
      requestBody = (jsonDecode(await utf8.decoder.bind(request).join()) as Map)
          .cast<String, Object?>();
      request.response
        ..statusCode = HttpStatus.ok
        ..headers.contentType = ContentType.json
        ..write(jsonEncode({
          'code': 200,
          'message': 'ok',
          'data': _turnJson('完整答复'),
        }));
      await request.response.close();
    });
    final client = ApiClient(
      apiRoot: Uri.parse(
        'http://${server.address.host}:${server.port}/api/',
      ),
    );
    addTearDown(client.close);
    final remote = ApiAgentRemoteDataSource(
      apiClient: client,
    );

    await remote.sendMessage(
      _session.id,
      '想改善肤质',
      idempotencyKey: 'fixed-idempotency-key',
    );

    expect(requests, 1);
    expect(requestBody?['content'], '想改善肤质');
    expect(
      requestBody?['idempotencyKey'],
      'fixed-idempotency-key',
    );
    expect(idempotencyHeader, 'fixed-idempotency-key');
  });
}

const _catalogItemJson = <String, Object?>{
  'type': 'PROJECT',
  'id': 'project-1',
  'name': 'Project',
  'subtitle': '',
  'summary': '',
  'attributes': <String, String>{},
};

ChatTurn _turn(String content) => ChatTurn(
      message: ChatMessage(
        id: 'assistant-1',
        sessionId: 'session-1',
        role: 'ASSISTANT',
        content: content,
        createdAt: '2026-08-06T10:01:00',
      ),
      catalogReport: null,
      catalogItems: const [],
      intent: 'GENERAL_CHAT',
      queryTarget: null,
      nextAction: 'NONE',
    );

const _completeTurn = ChatTurn(
  message: ChatMessage(
    id: 'assistant-1',
    sessionId: 'session-1',
    role: 'ASSISTANT',
    content: '完整答复',
    createdAt: '2026-08-06T10:01:00',
  ),
  catalogReport: null,
  catalogItems: [],
  intent: 'GENERAL_CHAT',
  queryTarget: null,
  nextAction: 'NONE',
);

Map<String, Object?> _turnJson(String content) => {
      'message': {
        'id': 'assistant-1',
        'sessionId': _session.id,
        'role': 'ASSISTANT',
        'content': content,
        'createdAt': '2026-08-06T10:01:00',
      },
      'catalogReport': null,
      'catalogItems': const <Object?>[],
      'intent': 'GENERAL_CHAT',
      'queryTarget': null,
      'nextAction': 'NONE',
    };

const _session = ChatSession(
  id: 'session-1',
  persona: 'CONSULTANT',
  contextType: 'GENERAL',
  contextId: '',
  title: '会话',
  lastMessage: '',
  createdAt: '2026-08-06T10:00:00',
  updatedAt: '2026-08-06T10:00:00',
);

const _otherSession = ChatSession(
  id: 'session-2',
  persona: 'CONSULTANT',
  contextType: 'GENERAL',
  contextId: '',
  title: '另一个会话',
  lastMessage: '',
  createdAt: '2026-08-06T11:00:00',
  updatedAt: '2026-08-06T11:00:00',
);

List<ChatMessage> _messages(int count) => List.generate(
      count,
      (index) => ChatMessage(
        id: 'message-$index',
        sessionId: _session.id,
        role: index.isEven ? 'USER' : 'ASSISTANT',
        content: '消息 $index',
        createdAt: '2026-08-06T10:${index.toString().padLeft(2, '0')}:00',
      ),
      growable: false,
    );

class _FakeAgentRepository extends Fake implements AgentRepository {
  _FakeAgentRepository({
    this.messages = const [],
    this.streams = const [],
  });

  final List<ChatMessage> messages;
  final List<StreamController<AgentStreamEvent>> streams;
  int createCalls = 0;
  int deleteCalls = 0;
  int streamCalls = 0;
  int cancelCalls = 0;
  final idempotencyKeys = <String>[];

  @override
  Stream<AgentStreamEvent> streamMessage({
    required String sessionId,
    required String content,
    required String idempotencyKey,
  }) {
    final source = streams[streamCalls++];
    idempotencyKeys.add(idempotencyKey);
    return source.stream.asBroadcastStream(onCancel: (_) => cancelCalls++);
  }

  @override
  Future<ChatSession> createSession({
    required ChatPersona persona,
    required ChatContextType contextType,
    String contextId = '',
    String title = '',
  }) async {
    createCalls++;
    return _session;
  }

  @override
  Future<List<ChatMessage>> getMessages(
    String sessionId, {
    int limit = 30,
    String? before,
  }) async {
    return messages;
  }

  @override
  Future<List<ChatSession>> getSessions({ChatPersona? persona}) async =>
      const [];

  @override
  Future<void> deleteSession(String sessionId) async {
    deleteCalls++;
  }
}
