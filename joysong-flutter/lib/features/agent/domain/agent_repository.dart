import 'package:joysong_flutter/features/agent/domain/agent_models.dart';

abstract interface class AgentRepository {
  Future<ChatSession> createSession({
    required ChatPersona persona,
    required ChatContextType contextType,
    String contextId = '',
    String title = '',
  });

  Future<List<ChatSession>> getSessions({ChatPersona? persona});

  Future<ChatTurn> sendMessage(String sessionId, String content);

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
