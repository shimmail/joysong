import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/legal_documents/data/legal_document_repository_impl.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_models.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_repository.dart';

void main() {
  test(
    'requests the public path and strictly decodes every response field',
    () async {
      late Uri requestedUri;
      final server = await _serve((request) async {
        requestedUri = request.requestedUri;
        await _respond(request, data: _documentData());
      });
      addTearDown(() => server.close(force: true));
      final client = ApiClient(apiRoot: _apiRoot(server));
      addTearDown(client.close);
      final repository = ApiLegalDocumentRepository(client);

      final document = await repository.load(
        type: LegalDocumentType.privacyPolicy,
        locale: 'en-US',
      );

      expect(
        requestedUri.toString(),
        'http://${server.address.host}:${server.port}/api/'
        'public/legal-documents/privacy-policy?locale=en-US',
      );
      expect(document.type, LegalDocumentType.privacyPolicy);
      expect(document.locale, 'en-US');
      expect(document.version, 2);
      expect(document.title, 'Privacy policy');
      expect(document.contentHtml, '<h1>Privacy</h1><p>Body</p>');
      expect(document.publishedAt, DateTime(2026, 8, 25, 10));
      expect(document.contentSha256, 'hash-en');
    },
  );

  test('rejects missing, mistyped, invalid, or mismatched response fields', () {
    final valid = _documentData();
    final malformed = <Map<String, Object?>>[
      for (final key in valid.keys)
        Map<String, Object?>.from(valid)..remove(key),
      Map<String, Object?>.from(valid)..['type'] = 1,
      Map<String, Object?>.from(valid)..['type'] = 'terms',
      Map<String, Object?>.from(valid)..['locale'] = null,
      Map<String, Object?>.from(valid)..['locale'] = 'fr-FR',
      Map<String, Object?>.from(valid)..['version'] = 2.5,
      Map<String, Object?>.from(valid)..['version'] = 0,
      Map<String, Object?>.from(valid)..['title'] = '',
      Map<String, Object?>.from(valid)..['contentHtml'] = false,
      Map<String, Object?>.from(valid)..['publishedAt'] = 'not-a-date',
      Map<String, Object?>.from(valid)..['contentSha256'] = '',
    ];

    for (final json in malformed) {
      expect(
        () => LegalDocument.fromJson(json),
        throwsFormatException,
        reason: json.toString(),
      );
    }

    expect(
      () => LegalDocument.fromJson(
        valid,
      ).validateRequest(type: LegalDocumentType.userAgreement, locale: 'en-US'),
      throwsFormatException,
    );
    expect(
      () => LegalDocument.fromJson(
        valid,
      ).validateRequest(type: LegalDocumentType.privacyPolicy, locale: 'zh-CN'),
      throwsFormatException,
    );
  });

  test(
    'caches by type and locale and force refresh replaces the cached value',
    () async {
      var requestCount = 0;
      final server = await _serve((request) async {
        requestCount += 1;
        final locale = request.uri.queryParameters['locale']!;
        await _respond(
          request,
          data: _documentData(
            locale: locale,
            version: requestCount,
            title: '$locale version $requestCount',
          ),
        );
      });
      addTearDown(() => server.close(force: true));
      final client = ApiClient(apiRoot: _apiRoot(server));
      addTearDown(client.close);
      final repository = ApiLegalDocumentRepository(client);

      final first = await repository.load(
        type: LegalDocumentType.privacyPolicy,
        locale: 'en-US',
      );
      final cached = await repository.load(
        type: LegalDocumentType.privacyPolicy,
        locale: 'en-US',
      );
      final chinese = await repository.load(
        type: LegalDocumentType.privacyPolicy,
        locale: 'zh-CN',
      );
      final refreshed = await repository.load(
        type: LegalDocumentType.privacyPolicy,
        locale: 'en-US',
        forceRefresh: true,
      );
      final refreshedCached = await repository.load(
        type: LegalDocumentType.privacyPolicy,
        locale: 'en-US',
      );

      expect(first.version, 1);
      expect(cached, same(first));
      expect(chinese.locale, 'zh-CN');
      expect(refreshed.version, 3);
      expect(refreshedCached, same(refreshed));
      expect(requestCount, 3);
    },
  );

  test('translates only 404 failures into legal-document not found', () async {
    final server = await _serve((request) async {
      final missing = request.uri.queryParameters['locale'] == 'en-US';
      await _respond(
        request,
        status: missing ? 404 : 500,
        code: missing ? 404 : 500,
        message: missing ? '公开协议不存在' : 'server error',
      );
    });
    addTearDown(() => server.close(force: true));
    final client = ApiClient(apiRoot: _apiRoot(server));
    addTearDown(client.close);
    final repository = ApiLegalDocumentRepository(client);

    await expectLater(
      repository.load(type: LegalDocumentType.userAgreement, locale: 'en-US'),
      throwsA(isA<LegalDocumentNotFoundException>()),
    );
    await expectLater(
      repository.load(type: LegalDocumentType.userAgreement, locale: 'zh-CN'),
      throwsA(isA<ApiException>()),
    );
  });

  test('treats successful null data as a response-format failure', () async {
    final server = await _serve((request) => _respond(request));
    addTearDown(() => server.close(force: true));
    final client = ApiClient(apiRoot: _apiRoot(server));
    addTearDown(client.close);
    final repository = ApiLegalDocumentRepository(client);

    await expectLater(
      repository.load(type: LegalDocumentType.userAgreement, locale: 'zh-CN'),
      throwsA(
        isA<ApiException>().having(
          (error) => error.cause,
          'cause',
          isA<FormatException>(),
        ),
      ),
    );
  });
}

Map<String, Object?> _documentData({
  String locale = 'en-US',
  int version = 2,
  String title = 'Privacy policy',
}) => {
  'type': 'privacy-policy',
  'locale': locale,
  'version': version,
  'title': title,
  'contentHtml': '<h1>Privacy</h1><p>Body</p>',
  'publishedAt': '2026-08-25T10:00:00',
  'contentSha256': 'hash-en',
};

Future<HttpServer> _serve(
  Future<void> Function(HttpRequest request) handler,
) async {
  final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  server.listen((request) async {
    try {
      await handler(request);
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

Future<void> _respond(
  HttpRequest request, {
  int status = 200,
  int code = 200,
  String message = 'ok',
  Object? data,
}) async {
  await request.drain<void>();
  request.response.statusCode = status;
  request.response.headers.contentType = ContentType.json;
  request.response.write(
    jsonEncode({'code': code, 'message': message, 'data': data}),
  );
  await request.response.close();
}
