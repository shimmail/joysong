import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/domain/agent_repository.dart';

abstract interface class AgentRemoteDataSource {
  Future<ChatSession> createSession({
    required ChatPersona persona,
    required ChatContextType contextType,
    required String contextId,
    required String title,
  });

  Future<List<ChatSession>> getSessions({ChatPersona? persona});

  Future<ChatTurn> sendMessage(String sessionId, String content);

  Future<ChatStreamConnection> streamMessage(
    String sessionId,
    String content,
  );

  Future<List<ChatMessage>> getMessages(
    String sessionId, {
    required int limit,
    String? before,
  });

  Future<void> deleteSession(String sessionId);

  Future<void> clearSessions(ChatPersona persona);

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

  Future<AgentCatalogReport> createCatalogReport(String query, String mode);
}

abstract interface class ChatStreamTransport {
  Future<ChatStreamConnection> open({
    required String path,
    required String content,
  });
}

final class ApiAgentRemoteDataSource implements AgentRemoteDataSource {
  ApiAgentRemoteDataSource({
    required ApiClient apiClient,
    required ChatStreamTransport streamTransport,
  })  : _apiClient = apiClient,
        _streamTransport = streamTransport;

  final ApiClient _apiClient;
  final ChatStreamTransport _streamTransport;

  @override
  Future<ChatSession> createSession({
    required ChatPersona persona,
    required ChatContextType contextType,
    required String contextId,
    required String title,
  }) async {
    if (contextType != ChatContextType.general && contextId.trim().isEmpty) {
      throw ArgumentError.value(contextId, 'contextId', '非通用会话必须指定上下文');
    }
    return _requireData(
      await _apiClient.post<ChatSession>(
        'chat/sessions',
        body: {
          'persona': persona.wireName,
          'contextType': contextType.wireName,
          'contextId': contextId,
          'title': title,
        },
        decodeData: ChatSession.fromJson,
      ),
      '创建会话',
    );
  }

  @override
  Future<List<ChatSession>> getSessions({ChatPersona? persona}) async =>
      await _apiClient.get<List<ChatSession>>(
        'chat/sessions',
        query: {'persona': persona?.wireName},
        decodeData: (json) =>
            jsonList(json).map(ChatSession.fromJson).toList(growable: false),
      ) ??
      const [];

  @override
  Future<ChatTurn> sendMessage(String sessionId, String content) async =>
      _requireData(
        await _apiClient.post<ChatTurn>(
          'chat/sessions/$sessionId/messages',
          body: {'content': _validateContent(content)},
          decodeData: ChatTurn.fromJson,
        ),
        '发送消息',
      );

  @override
  Future<ChatStreamConnection> streamMessage(
    String sessionId,
    String content,
  ) =>
      _streamTransport.open(
        path: 'chat/sessions/$sessionId/messages/stream',
        content: _validateContent(content),
      );

  @override
  Future<List<ChatMessage>> getMessages(
    String sessionId, {
    required int limit,
    String? before,
  }) async =>
      await _apiClient.get<List<ChatMessage>>(
        'chat/sessions/$sessionId/messages',
        query: {'limit': limit.clamp(1, 100), 'before': before},
        decodeData: (json) =>
            jsonList(json).map(ChatMessage.fromJson).toList(growable: false),
      ) ??
      const [];

  @override
  Future<void> deleteSession(String sessionId) =>
      _delete('chat/sessions/$sessionId');

  @override
  Future<void> clearSessions(ChatPersona persona) =>
      _delete('chat/sessions?persona=${persona.wireName}');

  @override
  Future<void> clearMessages(String sessionId) =>
      _delete('chat/sessions/$sessionId/messages');

  @override
  Future<void> deleteMessage(String messageId) =>
      _delete('chat/messages/$messageId');

  @override
  Future<AgentProfile> getProfile() async => _requireData(
        await _apiClient.get<AgentProfile>(
          'agent/profile',
          decodeData: AgentProfile.fromJson,
        ),
        '读取档案',
      );

  @override
  Future<AgentProfile> updateProfile(AgentProfileDraft draft) async =>
      _requireData(
        await _apiClient.put<AgentProfile>(
          'agent/profile',
          body: draft.toJson(),
          decodeData: AgentProfile.fromJson,
        ),
        '保存档案',
      );

  @override
  Future<AgentProfile> confirmProfile() async => _requireData(
        await _apiClient.post<AgentProfile>(
          'agent/profile/confirm',
          decodeData: AgentProfile.fromJson,
        ),
        '确认档案',
      );

  @override
  Future<AgentAssessment> createAssessment(
    AgentSafetyScreening screening,
  ) async =>
      _requireData(
        await _apiClient.post<AgentAssessment>(
          'agent/assessments',
          body: {'screening': screening.toJson()},
          decodeData: AgentAssessment.fromJson,
        ),
        '创建评估',
      );

  @override
  Future<AgentPlan> createPlan(String assessmentId) async => _requireData(
        await _apiClient.post<AgentPlan>(
          'agent/assessments/$assessmentId/plans',
          decodeData: AgentPlan.fromJson,
        ),
        '创建方案',
      );

  @override
  Future<List<AgentPlan>> getPlans() async =>
      await _apiClient.get<List<AgentPlan>>(
        'agent/plans',
        decodeData: (json) =>
            jsonList(json).map(AgentPlan.fromJson).toList(growable: false),
      ) ??
      const [];

  @override
  Future<AgentPlan> getPlan(String planId) async => _requireData(
        await _apiClient.get<AgentPlan>(
          'agent/plans/$planId',
          decodeData: AgentPlan.fromJson,
        ),
        '读取方案',
      );

  @override
  Future<void> deletePlan(String planId) => _delete('agent/plans/$planId');

  @override
  Future<void> clearPlans() => _delete('agent/plans');

  @override
  Future<AgentCatalogReport> createCatalogReport(
    String query,
    String mode,
  ) async =>
      _requireData(
        await _apiClient.post<AgentCatalogReport>(
          'agent/catalog/report',
          body: {'query': query, 'mode': mode},
          decodeData: AgentCatalogReport.fromJson,
        ),
        '生成对比报告',
      );

  Future<void> _delete(String path) async {
    await _apiClient.delete<Object?>(path, decodeData: (json) => json);
  }
}

T _requireData<T>(T? value, String action) {
  if (value == null) {
    throw FormatException('$action响应缺少 data');
  }
  return value;
}

String _validateContent(String content) {
  final normalized = content.trim();
  if (normalized.isEmpty || normalized.length > 5000) {
    throw ArgumentError.value(content, 'content', '消息长度必须为 1–5000 字符');
  }
  return normalized;
}
