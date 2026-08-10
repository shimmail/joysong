import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/domain/agent_repository.dart';

enum ChatDeliveryState {
  idle,
  loadingHistory,
  sending,
  streaming,
  completed,
  failed,
  cancelled,
  disconnected,
}

extension ChatDeliveryStateX on ChatDeliveryState {
  bool get isBusy =>
      this == ChatDeliveryState.sending || this == ChatDeliveryState.streaming;

  bool get isTerminal => switch (this) {
        ChatDeliveryState.completed ||
        ChatDeliveryState.failed ||
        ChatDeliveryState.cancelled ||
        ChatDeliveryState.disconnected =>
          true,
        _ => false,
      };
}

class AgentChatState {
  const AgentChatState({
    this.sessions = const [],
    this.activeSession,
    this.messages = const [],
    this.deliveryState = ChatDeliveryState.idle,
    this.errorMessage,
    this.isLoadingSessions = false,
    this.latestTurn,
  });

  final List<ChatSession> sessions;
  final ChatSession? activeSession;
  final List<ChatMessage> messages;
  final ChatDeliveryState deliveryState;
  final String? errorMessage;
  final bool isLoadingSessions;
  final ChatTurn? latestTurn;

  AgentChatState copyWith({
    List<ChatSession>? sessions,
    ChatSession? activeSession,
    bool clearActiveSession = false,
    List<ChatMessage>? messages,
    ChatDeliveryState? deliveryState,
    String? errorMessage,
    bool clearError = false,
    bool? isLoadingSessions,
    ChatTurn? latestTurn,
    bool clearLatestTurn = false,
  }) =>
      AgentChatState(
        sessions: sessions ?? this.sessions,
        activeSession:
            clearActiveSession ? null : activeSession ?? this.activeSession,
        messages: messages ?? this.messages,
        deliveryState: deliveryState ?? this.deliveryState,
        errorMessage: clearError ? null : errorMessage ?? this.errorMessage,
        isLoadingSessions: isLoadingSessions ?? this.isLoadingSessions,
        latestTurn: clearLatestTurn ? null : latestTurn ?? this.latestTurn,
      );
}

class AgentChatController extends ChangeNotifier {
  AgentChatController({
    required AgentRepository repository,
    this.streamingEnabled = true,
    this.recentMessageLimit = 20,
    this.persona = ChatPersona.consultant,
  })  : assert(recentMessageLimit > 0),
        _repository = repository;

  final AgentRepository _repository;
  final bool streamingEnabled;
  final int recentMessageLimit;
  final ChatPersona persona;

  AgentChatState _state = const AgentChatState();
  AgentChatState get state => _state;

  ChatStreamConnection? _activeConnection;
  int _operation = 0;
  int _localId = 0;
  bool _disposed = false;

  Future<void> loadSessions() async {
    _emit(_state.copyWith(isLoadingSessions: true, clearError: true));
    try {
      final sessions = await _repository.getSessions(persona: persona);
      _emit(_state.copyWith(sessions: sessions, isLoadingSessions: false));
      // Restore the most recently updated conversation when the page is
      // recreated, so persisted history is immediately visible.
      if (sessions.isNotEmpty && _state.activeSession == null) {
        await openSession(sessions.first);
      }
    } on Object catch (error) {
      _emit(
        _state.copyWith(
          isLoadingSessions: false,
          errorMessage: _messageFor(error),
        ),
      );
    }
  }

  Future<void> openSession(ChatSession session) async {
    await cancelSend();
    final operation = ++_operation;
    _emit(
      _state.copyWith(
        activeSession: session,
        messages: const [],
        deliveryState: ChatDeliveryState.loadingHistory,
        clearError: true,
        clearLatestTurn: true,
      ),
    );
    try {
      final messages = await _repository.getMessages(
        session.id,
        limit: recentMessageLimit,
      );
      if (!_isCurrent(operation)) return;
      _emit(
        _state.copyWith(
          messages: _latest(_deduplicate(messages)),
          deliveryState: ChatDeliveryState.idle,
        ),
      );
    } on Object catch (error) {
      if (!_isCurrent(operation)) return;
      _emit(
        _state.copyWith(
          deliveryState: ChatDeliveryState.failed,
          errorMessage: _messageFor(error),
        ),
      );
    }
  }

  void startNewSession() {
    unawaited(cancelSend());
    ++_operation;
    _emit(
      _state.copyWith(
        clearActiveSession: true,
        messages: const [],
        deliveryState: ChatDeliveryState.idle,
        clearError: true,
        clearLatestTurn: true,
      ),
    );
  }

  Future<void> startContextSummary({
    required ChatContextType contextType,
    required String contextId,
    required String contextName,
  }) async {
    final normalizedId = contextId.trim();
    final normalizedName = contextName.trim();
    if (contextType == ChatContextType.general ||
        normalizedId.isEmpty ||
        normalizedName.isEmpty) {
      return;
    }
    await cancelSend();
    final operation = ++_operation;
    _emit(
      _state.copyWith(
        clearActiveSession: true,
        messages: const [],
        deliveryState: ChatDeliveryState.sending,
        clearError: true,
        clearLatestTurn: true,
      ),
    );
    try {
      final session = await _repository.createSession(
        persona: persona,
        contextType: contextType,
        contextId: normalizedId,
        title: normalizedName,
      );
      if (!_isCurrent(operation)) return;
      _emit(
        _state.copyWith(
          activeSession: session,
          sessions: [
            session,
            ..._state.sessions.where((item) => item.id != session.id),
          ],
          deliveryState: ChatDeliveryState.idle,
        ),
      );
      await send('请根据平台数据库信息，简要总结当前详情的关键信息、适合关注的方面和必要风险。');
    } on Object catch (error) {
      if (!_isCurrent(operation)) return;
      _emit(
        _state.copyWith(
          deliveryState: ChatDeliveryState.failed,
          errorMessage: _messageFor(error),
        ),
      );
    }
  }

  Future<void> deleteSession(ChatSession session) async {
    await cancelSend();
    final operation = ++_operation;
    _emit(_state.copyWith(clearError: true));
    try {
      await _repository.deleteSession(session.id);
      if (!_isCurrent(operation)) return;
      final isActive = _state.activeSession?.id == session.id;
      _emit(
        _state.copyWith(
          sessions: _state.sessions
              .where((item) => item.id != session.id)
              .toList(growable: false),
          clearActiveSession: isActive,
          messages: isActive ? const [] : null,
          deliveryState: isActive ? ChatDeliveryState.idle : null,
          clearLatestTurn: isActive,
        ),
      );
    } on Object catch (error) {
      if (!_isCurrent(operation)) return;
      _emit(_state.copyWith(errorMessage: _messageFor(error)));
      rethrow;
    }
  }

  Future<void> clearActiveMessages() async {
    final session = _state.activeSession;
    if (session == null) return;
    await cancelSend();
    final operation = ++_operation;
    _emit(_state.copyWith(clearError: true));
    try {
      await _repository.clearMessages(session.id);
      if (!_isCurrent(operation)) return;
      _emit(
        _state.copyWith(
          messages: const [],
          deliveryState: ChatDeliveryState.idle,
          clearLatestTurn: true,
        ),
      );
    } on Object catch (error) {
      if (!_isCurrent(operation)) return;
      _emit(_state.copyWith(errorMessage: _messageFor(error)));
      rethrow;
    }
  }

  Future<void> clearSessions() async {
    await cancelSend();
    final operation = ++_operation;
    _emit(_state.copyWith(clearError: true));
    try {
      await _repository.clearSessions(persona: persona);
      if (!_isCurrent(operation)) return;
      _emit(
        _state.copyWith(
          sessions: const [],
          clearActiveSession: true,
          messages: const [],
          deliveryState: ChatDeliveryState.idle,
          clearLatestTurn: true,
        ),
      );
    } on Object catch (error) {
      if (!_isCurrent(operation)) return;
      _emit(_state.copyWith(errorMessage: _messageFor(error)));
      rethrow;
    }
  }

  Future<void> send(String content) async {
    if (_state.deliveryState.isBusy) return;
    final normalized = content.trim();
    if (normalized.isEmpty || normalized.length > 5000) {
      _emit(_state.copyWith(errorMessage: '消息长度必须为 1–5000 字符'));
      return;
    }

    final operation = ++_operation;
    _emit(
      _state.copyWith(
        deliveryState: ChatDeliveryState.sending,
        clearError: true,
        clearLatestTurn: true,
      ),
    );
    try {
      final session = _state.activeSession ??
          await _repository.createSession(
            persona: persona,
            contextType: ChatContextType.general,
            title: normalized.length <= 20
                ? normalized
                : normalized.substring(0, 20),
          );
      if (!_isCurrent(operation)) return;
      final sessions = _state.sessions.any((item) => item.id == session.id)
          ? _state.sessions
          : [session, ..._state.sessions];
      final userMessage = _temporaryMessage(session.id, 'USER', normalized);
      _emit(
        _state.copyWith(
          activeSession: session,
          sessions: sessions,
          messages: _latest([..._state.messages, userMessage]),
        ),
      );

      if (streamingEnabled) {
        await _sendStreaming(session, normalized, operation);
      } else {
        await _sendNonStreaming(session, normalized, operation);
      }
    } on Object catch (error) {
      if (!_isCurrent(operation)) return;
      _emit(
        _state.copyWith(
          deliveryState: ChatDeliveryState.failed,
          errorMessage: _messageFor(error),
        ),
      );
    }
  }

  Future<void> _sendNonStreaming(
    ChatSession session,
    String content,
    int operation,
  ) async {
    // This is exactly one POST. Failure is surfaced for an explicit manual
    // retry; neither this controller nor ApiClient replays POST requests.
    final temporaryAssistant = _temporaryMessage(session.id, 'ASSISTANT', '');
    _emit(
      _state.copyWith(
        messages: _latest([..._state.messages, temporaryAssistant]),
        deliveryState: ChatDeliveryState.sending,
      ),
    );
    try {
      final turn = await _repository.sendMessage(session.id, content);
      if (!_isCurrent(operation)) {
        _removeMessage(temporaryAssistant.id);
        return;
      }
      _replaceMessage(temporaryAssistant.id, turn.message);
      _emit(
        _state.copyWith(
          deliveryState: ChatDeliveryState.completed,
          latestTurn: turn,
        ),
      );
    } on Object {
      // Do not leave an empty "正在思考" bubble after a failed POST. The
      // outer send() handler exposes the actual failure in the status banner.
      if (_isCurrent(operation)) {
        _removeMessage(temporaryAssistant.id);
      }
      rethrow;
    }
  }

  Future<void> _sendStreaming(
    ChatSession session,
    String content,
    int operation,
  ) async {
    final temporaryAssistant = _temporaryMessage(session.id, 'ASSISTANT', '');
    _emit(
      _state.copyWith(
        messages: _latest([..._state.messages, temporaryAssistant]),
        deliveryState: ChatDeliveryState.streaming,
      ),
    );
    var receivedTerminalEvent = false;
    try {
      // Opening the connection sends the one and only POST for this turn.
      final connection = await _repository.streamMessage(session.id, content);
      if (!_isCurrent(operation)) {
        await connection.cancel();
        return;
      }
      _activeConnection = connection;
      await for (final event in connection.events) {
        if (!_isCurrent(operation)) return;
        switch (event.type) {
          case ChatStreamEventType.delta:
            _appendDelta(temporaryAssistant.id, event.content ?? '');
          case ChatStreamEventType.done:
            final turn = event.turn!;
            receivedTerminalEvent = true;
            _replaceMessage(temporaryAssistant.id, turn.message);
            _emit(
              _state.copyWith(
                deliveryState: ChatDeliveryState.completed,
                latestTurn: turn,
              ),
            );
          case ChatStreamEventType.error:
            receivedTerminalEvent = true;
            _emit(
              _state.copyWith(
                deliveryState: ChatDeliveryState.failed,
                errorMessage: event.message ?? '流式响应失败',
              ),
            );
        }
        if (receivedTerminalEvent) break;
      }
      if (_isCurrent(operation) && !receivedTerminalEvent) {
        _emit(
          _state.copyWith(
            deliveryState: ChatDeliveryState.disconnected,
            errorMessage: '连接已中断，已保留收到的内容；如需重试请手动发送。',
          ),
        );
      }
    } on Object catch (error) {
      if (!_isCurrent(operation)) return;
      final hasPartial = _state.messages.any(
        (message) =>
            message.id == temporaryAssistant.id && message.content.isNotEmpty,
      );
      _emit(
        _state.copyWith(
          deliveryState: hasPartial
              ? ChatDeliveryState.disconnected
              : ChatDeliveryState.failed,
          errorMessage:
              hasPartial ? '连接已中断，已保留收到的内容；如需重试请手动发送。' : _messageFor(error),
        ),
      );
    } finally {
      if (_isCurrent(operation)) {
        _activeConnection = null;
      }
    }
  }

  Future<void> cancelSend() async {
    if (!_state.deliveryState.isBusy) return;
    final connection = _activeConnection;
    ++_operation;
    _activeConnection = null;
    _emit(
      _state.copyWith(
        deliveryState: ChatDeliveryState.cancelled,
        errorMessage: '已停止生成，已保留收到的内容。',
      ),
    );
    await connection?.cancel();
  }

  ChatMessage _temporaryMessage(
          String sessionId, String role, String content) =>
      ChatMessage(
        id: 'local-${++_localId}',
        sessionId: sessionId,
        role: role,
        content: content,
        createdAt: DateTime.now().toIso8601String(),
        isTemporary: true,
      );

  void _appendDelta(String messageId, String delta) {
    if (delta.isEmpty) return;
    _emit(
      _state.copyWith(
        messages: [
          for (final message in _state.messages)
            if (message.id == messageId)
              message.copyWith(content: '${message.content}$delta')
            else
              message,
        ],
      ),
    );
  }

  void _replaceMessage(String messageId, ChatMessage replacement) {
    _emit(
      _state.copyWith(
        messages: _latest(
          _deduplicate([
            for (final message in _state.messages)
              if (message.id == messageId) replacement else message,
          ]),
        ),
      ),
    );
  }

  void _removeMessage(String messageId) {
    _emit(
      _state.copyWith(
        messages: _state.messages
            .where((message) => message.id != messageId)
            .toList(growable: false),
      ),
    );
  }

  List<ChatMessage> _deduplicate(Iterable<ChatMessage> messages) {
    final seen = <String>{};
    return [
      for (final message in messages)
        if (seen.add(message.id)) message,
    ];
  }

  List<ChatMessage> _latest(Iterable<ChatMessage> messages) {
    final ordered = messages.toList(growable: false);
    if (ordered.length <= recentMessageLimit) return ordered;
    return ordered.sublist(ordered.length - recentMessageLimit);
  }

  bool _isCurrent(int operation) => !_disposed && operation == _operation;

  void _emit(AgentChatState state) {
    if (_disposed) return;
    _state = state;
    notifyListeners();
  }

  String _messageFor(Object error) => switch (error) {
        final ApiException exception => exception.message,
        final ArgumentError argument =>
          argument.message?.toString() ?? '请求参数错误',
        final FormatException format => format.message,
        _ => '请求失败，请稍后手动重试',
      };

  @override
  void dispose() {
    _disposed = true;
    ++_operation;
    unawaited(_activeConnection?.cancel());
    _activeConnection = null;
    super.dispose();
  }
}
