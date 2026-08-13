import 'package:joysong_flutter/features/agent/data/agent_remote_data_source.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/domain/agent_repository.dart';

final class AgentRepositoryImpl implements AgentRepository {
  const AgentRepositoryImpl(this._remote);

  final AgentRemoteDataSource _remote;

  @override
  Stream<AgentStreamEvent> streamMessage({
    required String sessionId,
    required String content,
    required String idempotencyKey,
  }) =>
      _remote.streamMessage(
        sessionId: sessionId,
        content: content,
        idempotencyKey: idempotencyKey,
      );

  @override
  Future<ChatSession> createSession({
    required ChatPersona persona,
    required ChatContextType contextType,
    String contextId = '',
    String title = '',
  }) =>
      _remote.createSession(
        persona: persona,
        contextType: contextType,
        contextId: contextId,
        title: title,
      );

  @override
  Future<List<ChatSession>> getSessions({ChatPersona? persona}) =>
      _remote.getSessions(persona: persona);

  @override
  Future<ChatTurn> sendMessage(
    String sessionId,
    String content, {
    required String idempotencyKey,
  }) =>
      _remote.sendMessage(
        sessionId,
        content,
        idempotencyKey: idempotencyKey,
      );

  @override
  Future<List<ChatMessage>> getMessages(
    String sessionId, {
    int limit = 30,
    String? before,
  }) =>
      _remote.getMessages(sessionId, limit: limit, before: before);

  @override
  Future<void> deleteSession(String sessionId) =>
      _remote.deleteSession(sessionId);

  @override
  Future<void> clearSessions({
    ChatPersona persona = ChatPersona.consultant,
  }) =>
      _remote.clearSessions(persona);

  @override
  Future<void> clearMessages(String sessionId) =>
      _remote.clearMessages(sessionId);

  @override
  Future<void> deleteMessage(String messageId) =>
      _remote.deleteMessage(messageId);

  @override
  Future<AgentProfile> getProfile() => _remote.getProfile();

  @override
  Future<AgentProfile> updateProfile(AgentProfileDraft draft) =>
      _remote.updateProfile(draft);

  @override
  Future<AgentProfile> confirmProfile() => _remote.confirmProfile();

  @override
  Future<AgentAssessment> createAssessment(AgentSafetyScreening screening) =>
      _remote.createAssessment(screening);

  @override
  Future<AgentPlan> createPlan(String assessmentId) =>
      _remote.createPlan(assessmentId);

  @override
  Future<List<AgentPlan>> getPlans() => _remote.getPlans();

  @override
  Future<AgentPlan> getPlan(String planId) => _remote.getPlan(planId);

  @override
  Future<void> deletePlan(String planId) => _remote.deletePlan(planId);

  @override
  Future<void> clearPlans() => _remote.clearPlans();

  @override
  Future<AgentCatalogReport> createCatalogReport(
    String query, {
    String mode = 'AUTO',
  }) =>
      _remote.createCatalogReport(query, mode);
}
