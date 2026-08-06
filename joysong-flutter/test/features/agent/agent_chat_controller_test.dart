import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/domain/agent_repository.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_controller.dart';

void main() {
  test('non-streaming sends one POST and reaches completed', () async {
    final repository = _FakeAgentRepository();
    final controller = AgentChatController(
      repository: repository,
      streamingEnabled: false,
    );

    await controller.send('想改善肤质');

    expect(repository.createCalls, 1);
    expect(repository.nonStreamCalls, 1);
    expect(repository.streamCalls, 0);
    expect(controller.state.deliveryState, ChatDeliveryState.completed);
    expect(controller.state.messages.last.content, '完整答复');
  });

  test('stream done replaces temporary text and completes once', () async {
    final connection = _FakeConnection();
    final repository = _FakeAgentRepository(connection: connection);
    final controller = AgentChatController(repository: repository);

    final sending = controller.send('请给建议');
    await _waitFor(() => repository.streamCalls == 1);
    connection.eventsController
      ..add(const ChatStreamEvent.delta('部分'))
      ..add(ChatStreamEvent.done(_turn('最终答复')))
      ..close();
    await sending;

    expect(repository.streamCalls, 1);
    expect(repository.nonStreamCalls, 0);
    expect(controller.state.deliveryState, ChatDeliveryState.completed);
    expect(controller.state.messages.last.content, '最终答复');
    expect(controller.state.messages.last.isTemporary, isFalse);
  });

  test('server error preserves delta and never falls back to another POST',
      () async {
    final connection = _FakeConnection();
    final repository = _FakeAgentRepository(connection: connection);
    final controller = AgentChatController(repository: repository);

    final sending = controller.send('问题');
    await _waitFor(() => repository.streamCalls == 1);
    connection.eventsController
      ..add(const ChatStreamEvent.delta('已收到'))
      ..add(const ChatStreamEvent.error('模型繁忙'))
      ..close();
    await sending;

    expect(controller.state.deliveryState, ChatDeliveryState.failed);
    expect(controller.state.messages.last.content, '已收到');
    expect(controller.state.errorMessage, '模型繁忙');
    expect(repository.streamCalls, 1);
    expect(repository.nonStreamCalls, 0);
  });

  test('EOF without terminal event is disconnected and preserves delta',
      () async {
    final connection = _FakeConnection();
    final repository = _FakeAgentRepository(connection: connection);
    final controller = AgentChatController(repository: repository);

    final sending = controller.send('问题');
    await _waitFor(() => repository.streamCalls == 1);
    connection.eventsController
      ..add(const ChatStreamEvent.delta('半段内容'))
      ..close();
    await sending;

    expect(controller.state.deliveryState, ChatDeliveryState.disconnected);
    expect(controller.state.messages.last.content, '半段内容');
    expect(repository.streamCalls, 1);
    expect(repository.nonStreamCalls, 0);
  });

  test('active cancellation is terminal and closes only current stream',
      () async {
    final connection = _FakeConnection();
    final repository = _FakeAgentRepository(connection: connection);
    final controller = AgentChatController(repository: repository);

    final sending = controller.send('问题');
    await _waitFor(() => repository.streamCalls == 1);
    connection.eventsController.add(const ChatStreamEvent.delta('保留'));
    await _waitFor(() => controller.state.messages.last.content == '保留');
    await controller.cancelSend();
    await sending;

    expect(connection.cancelCalls, 1);
    expect(controller.state.deliveryState, ChatDeliveryState.cancelled);
    expect(controller.state.messages.last.content, '保留');
    expect(repository.streamCalls, 1);
  });
}

Future<void> _waitFor(bool Function() condition) async {
  for (var index = 0; index < 20 && !condition(); index++) {
    await Future<void>.delayed(Duration.zero);
  }
  expect(condition(), isTrue);
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

class _FakeConnection implements ChatStreamConnection {
  final eventsController = StreamController<ChatStreamEvent>();
  int cancelCalls = 0;

  @override
  Stream<ChatStreamEvent> get events => eventsController.stream;

  @override
  Future<void> cancel() async {
    cancelCalls++;
    if (!eventsController.isClosed) await eventsController.close();
  }
}

class _FakeAgentRepository extends Fake implements AgentRepository {
  _FakeAgentRepository({this.connection});

  final _FakeConnection? connection;
  int createCalls = 0;
  int nonStreamCalls = 0;
  int streamCalls = 0;

  @override
  Future<ChatSession> createSession({
    required ChatPersona persona,
    required ChatContextType contextType,
    String contextId = '',
    String title = '',
  }) async {
    createCalls++;
    return const ChatSession(
      id: 'session-1',
      persona: 'CONSULTANT',
      contextType: 'GENERAL',
      contextId: '',
      title: '会话',
      lastMessage: '',
      createdAt: '2026-08-06T10:00:00',
      updatedAt: '2026-08-06T10:00:00',
    );
  }

  @override
  Future<ChatTurn> sendMessage(String sessionId, String content) async {
    nonStreamCalls++;
    return _turn('完整答复');
  }

  @override
  Future<ChatStreamConnection> streamMessage(
    String sessionId,
    String content,
  ) async {
    streamCalls++;
    return connection!;
  }
}
