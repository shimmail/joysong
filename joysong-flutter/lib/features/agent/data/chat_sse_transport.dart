import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/agent/data/agent_remote_data_source.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/domain/agent_repository.dart';

typedef AgentAccessTokenProvider = Future<String?> Function();

final class HttpChatStreamTransport implements ChatStreamTransport {
  const HttpChatStreamTransport({
    required Uri apiRoot,
    required AgentAccessTokenProvider accessTokenProvider,
    LanguageTagProvider? languageTagProvider,
    RequestIdProvider? requestIdProvider,
    Duration connectionTimeout = const Duration(seconds: 30),
  })  : _apiRoot = apiRoot,
        _accessTokenProvider = accessTokenProvider,
        _languageTagProvider = languageTagProvider,
        _requestIdProvider = requestIdProvider ?? generateApiRequestId,
        _connectionTimeout = connectionTimeout;

  final Uri _apiRoot;
  final AgentAccessTokenProvider _accessTokenProvider;
  final LanguageTagProvider? _languageTagProvider;
  final RequestIdProvider _requestIdProvider;
  final Duration _connectionTimeout;

  @override
  Future<ChatStreamConnection> open({
    required String path,
    required String content,
  }) async {
    // Each stream owns its HttpClient. Cancelling a stream can therefore close
    // its socket without disturbing JSON requests or another conversation.
    final client = HttpClient();
    try {
      final uri =
          _apiRoot.resolve(path.startsWith('/') ? path.substring(1) : path);
      final request = await client.postUrl(uri).timeout(_connectionTimeout);
      request.headers.set(HttpHeaders.acceptHeader, 'text/event-stream');
      request.headers.set('X-Request-ID', _requestIdProvider());
      request.headers.set('X-Client', 'joysong-flutter');
      request.headers.set('X-Client-Platform', Platform.operatingSystem);
      request.headers.set(
        HttpHeaders.acceptLanguageHeader,
        _languageTagProvider?.call() ?? 'zh-CN',
      );
      request.headers.contentType = ContentType.json;
      final token = await _accessTokenProvider();
      if (token != null && token.isNotEmpty) {
        request.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
      }
      request.write(jsonEncode({'content': content}));
      final response = await request.close().timeout(_connectionTimeout);
      if (response.statusCode < 200 || response.statusCode >= 300) {
        final body = await utf8.decoder.bind(response).join();
        client.close(force: true);
        throw ApiException(
          message: _errorMessage(body),
          httpStatus: response.statusCode,
        );
      }
      return _HttpChatStreamConnection(client, response);
    } on Object {
      client.close(force: true);
      rethrow;
    }
  }
}

final class AgentSseDecoder {
  String? _eventName;
  final List<String> _dataLines = [];

  List<ChatStreamEvent> addLine(String line) {
    final normalized =
        line.endsWith('\r') ? line.substring(0, line.length - 1) : line;
    if (normalized.isEmpty) {
      final event = _flush();
      return event == null ? const [] : [event];
    }
    if (normalized.startsWith(':')) {
      return const [];
    }
    final separator = normalized.indexOf(':');
    final field =
        separator < 0 ? normalized : normalized.substring(0, separator);
    var value = separator < 0 ? '' : normalized.substring(separator + 1);
    if (value.startsWith(' ')) {
      value = value.substring(1);
    }
    if (field == 'event') {
      _eventName = value;
    } else if (field == 'data') {
      _dataLines.add(value);
    }
    return const [];
  }

  List<ChatStreamEvent> close() {
    final event = _flush();
    return event == null ? const [] : [event];
  }

  ChatStreamEvent? _flush() {
    final name = _eventName;
    final data = _dataLines.join('\n');
    _eventName = null;
    _dataLines.clear();
    if (name == null || data.isEmpty) {
      return null;
    }
    final decoded = jsonDecode(data);
    final map = requireJsonMap(decoded, 'SSE $name');
    return switch (name) {
      'delta' => ChatStreamEvent.delta(stringValue(map['content'])),
      'done' => ChatStreamEvent.done(ChatTurn.fromJson(map)),
      'error' => ChatStreamEvent.error(
          stringValue(map['message'], fallback: '流式响应失败'),
        ),
      _ => null,
    };
  }
}

final class _HttpChatStreamConnection implements ChatStreamConnection {
  _HttpChatStreamConnection(this._client, this._response) {
    _events = _readEvents();
  }

  final HttpClient _client;
  final HttpClientResponse _response;
  late final Stream<ChatStreamEvent> _events;
  bool _cancelled = false;

  @override
  Stream<ChatStreamEvent> get events => _events;

  Stream<ChatStreamEvent> _readEvents() async* {
    final decoder = AgentSseDecoder();
    try {
      await for (final line in _response
          .transform(utf8.decoder)
          .transform(const LineSplitter())) {
        if (_cancelled) {
          return;
        }
        for (final event in decoder.addLine(line)) {
          yield event;
        }
      }
      if (!_cancelled) {
        for (final event in decoder.close()) {
          yield event;
        }
      }
    } finally {
      _client.close(force: _cancelled);
    }
  }

  @override
  Future<void> cancel() async {
    if (_cancelled) {
      return;
    }
    _cancelled = true;
    _client.close(force: true);
  }
}

String _errorMessage(String body) {
  try {
    final decoded = jsonDecode(body);
    if (decoded is Map && decoded['message'] != null) {
      final message = decoded['message'].toString();
      if (message.isNotEmpty) {
        return message;
      }
    }
  } on FormatException {
    // Fall through to a safe message. Never expose an HTML/proxy response.
  }
  return '流式请求失败，请稍后手动重试';
}
