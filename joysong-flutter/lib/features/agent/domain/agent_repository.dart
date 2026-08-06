import 'package:joysong_flutter/features/agent/domain/agent_models.dart';

enum ChatStreamEventType { delta, done, error }

class ChatStreamEvent {
  const ChatStreamEvent.delta(this.content)
      : type = ChatStreamEventType.delta,
        turn = null,
        message = null;

  const ChatStreamEvent.done(this.turn)
      : type = ChatStreamEventType.done,
        content = null,
        message = null;

  const ChatStreamEvent.error(this.message)
      : type = ChatStreamEventType.error,
        content = null,
        turn = null;

  final ChatStreamEventType type;
  final String? content;
  final ChatTurn? turn;
  final String? message;
}

abstract interface class ChatStreamConnection {
  Stream<ChatStreamEvent> get events;

  Future<void> cancel();
}

abstract interface class AgentRepository {
  Future<ChatSession> createSession({
    required ChatPersona persona,
    required ChatContextType contextType,
    String contextId = '',
    String title = '',
  });

  Future<List<ChatSession>> getSessions({ChatPersona? persona});

  Future<ChatTurn> sendMessage(String sessionId, String content);

  Future<ChatStreamConnection> streamMessage(
    String sessionId,
    String content,
  );

  Future<List<ChatMessage>> getMessages(
    String sessionId, {
    int limit = 30,
    String? before,
  });

  Future<void> deleteSession(String sessionId);

  Future<void> clearSessions({ChatPersona persona = ChatPersona.consultant});

  Future<void> clearMessages(String sessionId);

  Future<void> deleteMessage(String messageId);

  Future<AgentProfile> getProfile();

  Future<AgentProfile> updateProfile(AgentProfileDraft draft);

  Future<AgentProfile> confirmProfile();

  Future<AgentAssessment> createAssessment(AgentSafetyScreening screening);

  Future<AgentPlan> createPlan(String assessmentId);

  Future<List<AgentPlan>> getPlans();

  Future<AgentPlan> getPlan(String planId);

  Future<void> deletePlan(String planId);

  Future<void> clearPlans();

  Future<AgentCatalogReport> createCatalogReport(
    String query, {
    String mode = 'AUTO',
  });
}
