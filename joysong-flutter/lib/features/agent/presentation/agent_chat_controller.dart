import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/domain/agent_repository.dart';

enum ChatDeliveryState {
  idle,
  loadingHistory,
  sending,
  completed,
  failed,
}

extension ChatDeliveryStateX on ChatDeliveryState {
  bool get isBusy => this == ChatDeliveryState.sending;
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
    this.streamingMessageId,
    this.failedMessageId,
  });

  final List<ChatSession> sessions;
  final ChatSession? activeSession;
  final List<ChatMessage> messages;
  final ChatDeliveryState deliveryState;
  final String? errorMessage;
  final bool isLoadingSessions;
  final ChatTurn? latestTurn;
  final String? streamingMessageId;
  final String? failedMessageId;

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
    String? streamingMessageId,
    bool clearStreamingMessage = false,
    String? failedMessageId,
    bool clearFailedMessage = false,
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
        streamingMessageId: clearStreamingMessage
            ? null
            : streamingMessageId ?? this.streamingMessageId,
        failedMessageId:
            clearFailedMessage ? null : failedMessageId ?? this.failedMessageId,
      );
}

class AgentChatController extends ChangeNotifier {
  AgentChatController({
    required AgentRepository repository,
    this.recentMessageLimit = 20,
  })  : assert(recentMessageLimit > 0),
        _repository = repository;

  final AgentRepository _repository;
  final int recentMessageLimit;

  AgentChatState _state = const AgentChatState();
  AgentChatState get state => _state;

  int _operation = 0;
  int _localId = 0;
  bool _disposed = false;
  _PendingSend? _pendingSend;
  _ActiveStream? _activeStream;

  Future<void> loadSessions() async {
    _emit(_state.copyWith(isLoadingSessions: true, clearError: true));
    try {
      final sessions = await _repository.getSessions(
        persona: ChatPersona.consultant,
      );
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
    await _cancelActiveStream();
    final operation = ++_operation;
    _emit(
      _state.copyWith(
        activeSession: session,
        messages: const [],
        deliveryState: ChatDeliveryState.loadingHistory,
        clearError: true,
        clearLatestTurn: true,
        clearStreamingMessage: true,
        clearFailedMessage: true,
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
    _rejectSessionMutationWhileBusy();
    ++_operation;
    _emit(
      _state.copyWith(
        clearActiveSession: true,
        messages: const [],
        deliveryState: ChatDeliveryState.idle,
        clearError: true,
        clearLatestTurn: true,
        clearStreamingMessage: true,
        clearFailedMessage: true,
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
    _rejectSessionMutationWhileBusy();
    final operation = ++_operation;
    _emit(
      _state.copyWith(
        clearActiveSession: true,
        messages: const [],
        deliveryState: ChatDeliveryState.sending,
        clearError: true,
        clearLatestTurn: true,
        clearStreamingMessage: true,
        clearFailedMessage: true,
      ),
    );
    try {
      final session = await _repository.createSession(
        persona: ChatPersona.consultant,
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
    _rejectSessionMutationWhileBusy();
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
    _rejectSessionMutationWhileBusy();
    final session = _state.activeSession;
    if (session == null) return;
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
    _rejectSessionMutationWhileBusy();
    final operation = ++_operation;
    _emit(_state.copyWith(clearError: true));
    try {
      await _repository.clearSessions(persona: ChatPersona.consultant);
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
        clearStreamingMessage: true,
        clearFailedMessage: true,
      ),
    );
    try {
      final session = _state.activeSession ??
          await _repository.createSession(
            persona: ChatPersona.consultant,
            contextType: ChatContextType.general,
            title: normalized.length <= 20
                ? normalized
                : normalized.substring(0, 20),
          );
      if (!_isCurrent(operation)) return;
      final sessions = _state.sessions.any((item) => item.id == session.id)
          ? _state.sessions
          : [session, ..._state.sessions];
      final idempotencyKey = _idempotencyKeyFor(session.id, normalized);
      final userMessage = _temporaryMessage(session.id, 'USER', normalized);
      _emit(
        _state.copyWith(
          activeSession: session,
          sessions: sessions,
          messages: _latest([..._state.messages, userMessage]),
        ),
      );

      await _streamSend(
        session,
        normalized,
        operation,
        idempotencyKey,
        userMessageId: userMessage.id,
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

  Future<void> retry() async {
    if (_state.deliveryState.isBusy) return;
    final pending = _pendingSend;
    final session = _state.activeSession;
    final failedMessageId = _state.failedMessageId;
    if (pending == null ||
        session == null ||
        pending.sessionId != session.id ||
        failedMessageId == null) {
      return;
    }

    final operation = ++_operation;
    final placeholder = _temporaryMessage(session.id, 'ASSISTANT', '');
    _emit(
      _state.copyWith(
        messages: _latest([
          for (final message in _state.messages)
            if (message.id == failedMessageId) placeholder else message,
        ]),
        deliveryState: ChatDeliveryState.sending,
        clearError: true,
        clearLatestTurn: true,
        streamingMessageId: placeholder.id,
        clearFailedMessage: true,
      ),
    );
    await _listenToStream(
      session: session,
      content: pending.content,
      operation: operation,
      idempotencyKey: pending.idempotencyKey,
      placeholderId: placeholder.id,
    );
  }

  Future<void> _streamSend(
      ChatSession session, String content, int operation, String idempotencyKey,
      {required String userMessageId}) async {
    final temporaryAssistant = _temporaryMessage(session.id, 'ASSISTANT', '');
    _emit(
      _state.copyWith(
        messages: _latest([..._state.messages, temporaryAssistant]),
        deliveryState: ChatDeliveryState.sending,
        streamingMessageId: temporaryAssistant.id,
        clearFailedMessage: true,
      ),
    );
    await _listenToStream(
      session: session,
      content: content,
      operation: operation,
      idempotencyKey: idempotencyKey,
      placeholderId: temporaryAssistant.id,
      userMessageId: userMessageId,
    );
  }

  Future<void> _listenToStream({
    required ChatSession session,
    required String content,
    required int operation,
    required String idempotencyKey,
    required String placeholderId,
    String? userMessageId,
  }) async {
    final finished = Completer<void>();
    var terminalEventReceived = false;
    bool isActive() =>
        _isCurrent(operation) &&
        _state.activeSession?.id == session.id &&
        _state.streamingMessageId == placeholderId;

    void finish() {
      if (!finished.isCompleted) finished.complete();
    }

    void fail([Object? error]) {
      if (!isActive()) {
        finish();
        return;
      }
      terminalEventReceived = true;
      _emit(
        _state.copyWith(
          deliveryState: ChatDeliveryState.failed,
          errorMessage: error == null ? null : _messageFor(error),
          failedMessageId: placeholderId,
          clearStreamingMessage: true,
        ),
      );
      finish();
    }

    late final StreamSubscription<AgentStreamEvent> subscription;
    subscription = _repository
        .streamMessage(
          sessionId: session.id,
          content: content,
          idempotencyKey: idempotencyKey,
        )
        .listen(
          (event) {
            if (!isActive()) return;
            switch (event) {
              case AgentStreamStarted(:final userMessage):
                if (userMessageId != null) {
                  _replaceMessage(userMessageId, userMessage);
                }
              case AgentStreamDelta(:final content):
                final placeholder = _state.messages
                    .where((message) => message.id == placeholderId)
                    .firstOrNull;
                if (placeholder != null) {
                  _replaceMessage(
                    placeholderId,
                    placeholder.copyWith(
                        content: placeholder.content + content),
                  );
                }
              case AgentStreamCompleted(:final turn):
                terminalEventReceived = true;
                _clearPendingSend(session.id, content, idempotencyKey);
                final assistantMessage = turn.message.copyWith(
                  catalogItems: turn.message.catalogItems.isNotEmpty
                      ? turn.message.catalogItems
                      : turn.catalogItems,
                );
                _replaceMessage(placeholderId, assistantMessage);
                _emit(
                  _state.copyWith(
                    deliveryState: ChatDeliveryState.completed,
                    latestTurn: turn,
                    clearStreamingMessage: true,
                    clearFailedMessage: true,
                  ),
                );
                finish();
              case AgentStreamFailed():
                fail();
            }
          },
          onError: (Object error, StackTrace stackTrace) => fail(error),
          onDone: () {
            if (!terminalEventReceived) fail(StateError('STREAM_INTERRUPTED'));
            finish();
          },
          cancelOnError: false,
        );
    _activeStream = _ActiveStream(
      operation: operation,
      subscription: subscription,
      finished: finished,
    );
    await finished.future;
    if (_activeStream?.operation == operation) {
      _activeStream = null;
      await subscription.cancel();
    }
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

  String _idempotencyKeyFor(String sessionId, String content) {
    final pending = _pendingSend;
    if (pending != null &&
        pending.sessionId == sessionId &&
        pending.content == content) {
      return pending.idempotencyKey;
    }
    final key = 'agent-${DateTime.now().microsecondsSinceEpoch}-${++_localId}';
    _pendingSend = _PendingSend(
      sessionId: sessionId,
      content: content,
      idempotencyKey: key,
    );
    return key;
  }

  void _clearPendingSend(
    String sessionId,
    String content,
    String idempotencyKey,
  ) {
    final pending = _pendingSend;
    if (pending != null &&
        pending.sessionId == sessionId &&
        pending.content == content &&
        pending.idempotencyKey == idempotencyKey) {
      _pendingSend = null;
    }
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

  Future<void> _cancelActiveStream() async {
    final active = _activeStream;
    _activeStream = null;
    if (active == null) return;
    if (!active.finished.isCompleted) active.finished.complete();
    await active.subscription.cancel();
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

  void _rejectSessionMutationWhileBusy() {
    if (_state.deliveryState.isBusy) {
      throw StateError('CHAT_SEND_IN_PROGRESS');
    }
  }

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
    unawaited(_cancelActiveStream());
    super.dispose();
  }
}

class _PendingSend {
  const _PendingSend({
    required this.sessionId,
    required this.content,
    required this.idempotencyKey,
  });

  final String sessionId;
  final String content;
  final String idempotencyKey;
}

class _ActiveStream {
  const _ActiveStream({
    required this.operation,
    required this.subscription,
    required this.finished,
  });

  final int operation;
  final StreamSubscription<AgentStreamEvent> subscription;
  final Completer<void> finished;
}
