import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/translation/translation.dart';

void main() {
  test('posts the translation contract and decodes the response', () async {
    final client = _RecordingApiClient(const {
      'translatedText': 'Recovery is progressing well',
      'detectedLanguage': 'zh',
      'targetLanguage': 'en-US',
      'provider': 'qwen',
      'cached': true,
    });
    addTearDown(client.close);

    final result = await ApiTranslationRepository(client).translateText(
      text: ' 恢复得很好 ',
      targetLanguage: ' en-US ',
      contentType: ' diary ',
    );

    expect(client.path, 'translations');
    expect(client.body, {
      'text': '恢复得很好',
      'targetLanguage': 'en-US',
      'contentType': 'diary',
    });
    expect(result.translatedText, 'Recovery is progressing well');
    expect(result.detectedLanguage, 'zh');
    expect(result.targetLanguage, 'en-US');
    expect(result.provider, 'qwen');
    expect(result.cached, isTrue);
  });

  test('rejects text that is empty after trimming', () async {
    final client = _RecordingApiClient(const {});
    addTearDown(client.close);

    expect(
      () => ApiTranslationRepository(client).translateText(
        text: ' \n\t ',
        targetLanguage: 'en-US',
        contentType: 'diary',
      ),
      throwsArgumentError,
    );
    expect(client.path, isNull);
  });

  test('rejects trimmed source text longer than 12000 characters', () async {
    final client = _RecordingApiClient(const {});
    addTearDown(client.close);
    final oversized = ' ${List<String>.filled(12001, '中').join()} ';

    expect(
      () => ApiTranslationRepository(client).translateText(
        text: oversized,
        targetLanguage: 'en-US',
        contentType: 'diary',
      ),
      throwsArgumentError,
    );
    expect(client.path, isNull);
  });

  final malformedResponses = <String, Map<String, Object?>>{
    'translatedText is missing': const {
      'detectedLanguage': 'zh',
      'targetLanguage': 'en-US',
      'provider': 'qwen',
      'cached': false,
    },
    'detectedLanguage has the wrong type': const {
      'translatedText': 'Recovery is progressing well',
      'detectedLanguage': 1,
      'targetLanguage': 'en-US',
      'provider': 'qwen',
      'cached': false,
    },
    'targetLanguage has the wrong type': const {
      'translatedText': 'Recovery is progressing well',
      'detectedLanguage': 'zh',
      'targetLanguage': true,
      'provider': 'qwen',
      'cached': false,
    },
    'provider has the wrong type': const {
      'translatedText': 'Recovery is progressing well',
      'detectedLanguage': 'zh',
      'targetLanguage': 'en-US',
      'provider': 1,
      'cached': false,
    },
    'cached has the wrong type': const {
      'translatedText': 'Recovery is progressing well',
      'detectedLanguage': 'zh',
      'targetLanguage': 'en-US',
      'provider': 'qwen',
      'cached': 'false',
    },
  };

  for (final entry in malformedResponses.entries) {
    test('throws FormatException when ${entry.key}', () async {
      final client = _RecordingApiClient(entry.value);
      addTearDown(client.close);

      await expectLater(
        ApiTranslationRepository(client).translateText(
          text: '恢复得很好',
          targetLanguage: 'en-US',
          contentType: 'diary',
        ),
        throwsFormatException,
      );
    });
  }

  test('unwraps malformed translation data from the real ApiClient', () async {
    final server = await _serveEnvelope({
      'code': 200,
      'message': 'ok',
      'data': {
        'detectedLanguage': 'zh',
        'targetLanguage': 'en-US',
        'provider': 'qwen',
        'cached': false,
      },
    });
    addTearDown(() => server.close(force: true));
    final client = ApiClient(apiRoot: _apiRoot(server));
    addTearDown(client.close);

    await expectLater(
      ApiTranslationRepository(client).translateText(
        text: '恢复得很好',
        targetLanguage: 'en-US',
        contentType: 'diary',
      ),
      throwsA(
        isA<FormatException>().having(
          (error) => error.message,
          'message',
          '翻译响应缺少有效的 translatedText',
        ),
      ),
    );
  });

  test('throws FormatException when the real API envelope omits data',
      () async {
    final server = await _serveEnvelope({
      'code': 200,
      'message': 'ok',
    });
    addTearDown(() => server.close(force: true));
    final client = ApiClient(apiRoot: _apiRoot(server));
    addTearDown(client.close);

    await expectLater(
      ApiTranslationRepository(client).translateText(
        text: '恢复得很好',
        targetLanguage: 'en-US',
        contentType: 'diary',
      ),
      throwsA(
        isA<FormatException>().having(
          (error) => error.message,
          'message',
          '翻译响应 data 为空',
        ),
      ),
    );
  });
}

final class _RecordingApiClient extends ApiClient {
  _RecordingApiClient(this.response)
      : super(apiRoot: Uri.parse('http://localhost/api/'));

  final Object? response;
  String? path;
  Object? body;

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    this.path = path;
    this.body = body;
    return decodeData(response);
  }
}

Future<HttpServer> _serveEnvelope(Map<String, Object?> envelope) async {
  final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  server.listen((request) async {
    try {
      await request.drain<void>();
      request.response.headers.contentType = ContentType.json;
      request.response.write(jsonEncode(envelope));
      await request.response.close();
    } on Object catch (error, stackTrace) {
      request.response.statusCode = 500;
      request.response.write('$error\n$stackTrace');
      await request.response.close();
    }
  });
  return server;
}

Uri _apiRoot(HttpServer server) =>
    Uri.parse('http://${server.address.host}:${server.port}/api/');
