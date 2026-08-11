import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/agent/data/agent_remote_data_source.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/domain/agent_repository.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_controller.dart';

void main() {
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

  test('non-streaming send trims the conversation to the latest 20 messages',
      () async {
    final messages = _messages(25);
    final controller = AgentChatController(
      repository: _FakeAgentRepository(messages: messages),
      recentMessageLimit: 20,
    );
    await controller.openSession(_session);

    await controller.send('想改善肤质');

    expect(controller.state.messages.length, 20);
    expect(controller.state.messages.first.id, messages[7].id);
    expect(controller.state.messages.last.content, '完整答复');
  });

  test('send uses REST exactly once and reaches completed', () async {
    final repository = _FakeAgentRepository();
    final controller = AgentChatController(
      repository: repository,
      recentMessageLimit: 20,
    );

    await controller.send('想改善肤质');

    expect(repository.createCalls, 1);
    expect(repository.sendCalls, 1);
    expect(controller.state.deliveryState, ChatDeliveryState.completed);
    expect(controller.state.messages.last.content, '完整答复');
  });

  test('failed REST send is attempted once and awaits manual retry', () async {
    final repository = _FakeAgentRepository(sendError: StateError('offline'));
    final controller = AgentChatController(repository: repository);

    await controller.send('想改善肤质');

    expect(repository.sendCalls, 1);
    expect(controller.state.deliveryState, ChatDeliveryState.failed);
  });

  test('delete during a REST send fails explicitly without deleting', () async {
    final pendingSend = Completer<ChatTurn>();
    final repository = _FakeAgentRepository(pendingSend: pendingSend);
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

    pendingSend.complete(_turn('完整答复'));
    await send;
  });

  test('REST send includes an idempotency key in exactly one POST', () async {
    var requests = 0;
    Map<String, Object?>? requestBody;
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));
    server.listen((request) async {
      requests += 1;
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

    await remote.sendMessage(_session.id, '想改善肤质');

    expect(requests, 1);
    expect(requestBody?['content'], '想改善肤质');
    expect(
      requestBody?['idempotencyKey'],
      isA<String>()
          .having((value) => value.isNotEmpty, 'is not empty', isTrue)
          .having((value) => value.length <= 100, 'length', isTrue),
    );
  });
}

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
    this.sendError,
    this.pendingSend,
  });

  final List<ChatMessage> messages;
  final Object? sendError;
  final Completer<ChatTurn>? pendingSend;
  int createCalls = 0;
  int sendCalls = 0;
  int deleteCalls = 0;

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

  @override
  Future<ChatTurn> sendMessage(String sessionId, String content) async {
    sendCalls++;
    if (sendError case final error?) throw error;
    if (pendingSend case final completer?) return completer.future;
    return _turn('完整答复');
  }
}
