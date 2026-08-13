import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';

typedef AgentStreamByteSource = Stream<List<int>> Function();

abstract interface class AgentRemoteDataSource {
  Stream<AgentStreamEvent> streamMessage({
    required String sessionId,
    required String content,
    required String idempotencyKey,
  });

  Future<ChatSession> createSession({
    required ChatPersona persona,
    required ChatContextType contextType,
    required String contextId,
    required String title,
  });

  Future<List<ChatSession>> getSessions({ChatPersona? persona});

  Future<ChatTurn> sendMessage(
    String sessionId,
    String content, {
    required String idempotencyKey,
  });

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

final class ApiAgentRemoteDataSource implements AgentRemoteDataSource {
  ApiAgentRemoteDataSource({
    required ApiClient apiClient,
    AccessTokenProvider? accessTokenProvider,
    HttpClient? streamHttpClient,
    AgentStreamByteSource? streamByteSource,
  })  : _apiClient = apiClient,
        _accessTokenProvider = accessTokenProvider,
        _streamHttpClient = streamHttpClient ?? HttpClient(),
        _streamByteSource = streamByteSource;

  final ApiClient _apiClient;
  final AccessTokenProvider? _accessTokenProvider;
  final HttpClient _streamHttpClient;
  final AgentStreamByteSource? _streamByteSource;

  @override
  Stream<AgentStreamEvent> streamMessage({
    required String sessionId,
    required String content,
    required String idempotencyKey,
  }) {
    late final StreamController<AgentStreamEvent> controller;
    StreamSubscription<String>? lines;
    HttpClientRequest? request;
    HttpClientResponse? response;
    var cancelled = false;

    Future<void> start() async {
      var terminal = false;
      var eventName = '';
      final dataLines = <String>[];
      try {
        final byteStream = _streamByteSource?.call() ??
            await _openStream(
              sessionId: sessionId,
              content: content,
              idempotencyKey: idempotencyKey,
              onRequest: (activeRequest) => request = activeRequest,
              onResponse: (activeResponse) => response = activeResponse,
            );
        if (cancelled) return;

        void dispatch() {
          if (eventName.isEmpty && dataLines.isEmpty) return;
          final event = _decodeStreamEvent(eventName, dataLines.join('\n'));
          if (event != null) {
            controller.add(event);
            terminal =
                event is AgentStreamCompleted || event is AgentStreamFailed;
          }
          eventName = '';
          dataLines.clear();
        }

        lines = utf8.decoder
            .bind(byteStream)
            .transform(const LineSplitter())
            .listen(
              (line) {
                if (line.isEmpty) {
                  dispatch();
                } else if (!line.startsWith(':')) {
                  final colon = line.indexOf(':');
                  final field = colon < 0 ? line : line.substring(0, colon);
                  var value = colon < 0 ? '' : line.substring(colon + 1);
                  if (value.startsWith(' ')) value = value.substring(1);
                  if (field == 'event') eventName = value;
                  if (field == 'data') dataLines.add(value);
                }
              },
              onError: controller.addError,
              onDone: () {
                if (cancelled) return;
                dispatch();
                if (!terminal) {
                  controller
                      .addError(StateError('AGENT_STREAM_MISSING_TERMINAL'));
                }
                controller.close();
              },
              cancelOnError: true,
            );
      } on Object catch (error, stackTrace) {
        if (!cancelled) {
          controller.addError(error, stackTrace);
          await controller.close();
        }
      }
    }

    controller = StreamController<AgentStreamEvent>(
      onListen: start,
      onCancel: () async {
        cancelled = true;
        await lines?.cancel();
        final activeResponse = response;
        if (activeResponse == null) {
          request?.abort();
        } else {
          final socket = await activeResponse.detachSocket();
          socket.destroy();
        }
      },
    );
    return controller.stream;
  }

  Future<Stream<List<int>>> _openStream({
    required String sessionId,
    required String content,
    required String idempotencyKey,
    required void Function(HttpClientRequest request) onRequest,
    required void Function(HttpClientResponse response) onResponse,
  }) async {
    final uri = _apiClient.apiRoot.resolve(
      'chat/sessions/${Uri.encodeComponent(sessionId)}/messages/stream',
    );
    final request = await _streamHttpClient.postUrl(uri);
    onRequest(request);
    request.headers
      ..set(HttpHeaders.acceptHeader, 'text/event-stream')
      ..contentType = ContentType.json;
    final token = await _accessTokenProvider?.call();
    if (token != null && token.isNotEmpty) {
      request.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
    }
    request.write(jsonEncode({
      'content': _validateContent(content),
      'idempotencyKey': idempotencyKey,
    }));
    final response = await request.close();
    onResponse(response);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      response.detachSocket().then((socket) => socket.destroy());
      throw HttpException('Agent stream request failed', uri: uri);
    }
    return response;
  }

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
  Future<ChatTurn> sendMessage(
    String sessionId,
    String content, {
    required String idempotencyKey,
  }) async =>
      _requireData(
        await _apiClient.postIdempotent<ChatTurn>(
          'chat/sessions/$sessionId/messages',
          idempotencyKey: idempotencyKey,
          body: {
            'content': _validateContent(content),
            'idempotencyKey': idempotencyKey,
          },
          decodeData: ChatTurn.fromJson,
        ),
        '发送消息',
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

AgentStreamEvent? _decodeStreamEvent(String name, String data) {
  if (data.isEmpty) return null;
  final json = requireJsonMap(jsonDecode(data), 'Agent stream event');
  return switch (name) {
    'started' => AgentStreamStarted(
        traceId: requireString(json, 'traceId'),
        turnId: requireString(json, 'turnId'),
        userMessage: ChatMessage.fromJson(json['userMessage']),
      ),
    'delta' => AgentStreamDelta(content: stringValue(json['content'])),
    'completed' => AgentStreamCompleted(turn: ChatTurn.fromJson(json['turn'])),
    'error' || 'failed' => AgentStreamFailed(
        code: requireString(json, 'code'),
        traceId: nullableString(json['traceId']),
        retryable: json['retryable'] == true,
      ),
    _ => null,
  };
}
