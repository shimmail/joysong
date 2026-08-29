import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/orders/data/orders_remote_data_source.dart';
import 'package:joysong_flutter/features/orders/domain/refund_evidence_models.dart';

void main() {
  test('service fee refund posts multipart fields and files in source order', () async {
    final client = RecordingApiClient();
    final source = ApiOrdersRemoteDataSource(client);
    final refund = await source.requestServiceFeeRefund(
      'order-1',
      reason: 'Changed plans',
      description: 'Cannot travel',
      reasonCode: ' CHANGE ',
      evidenceFiles: [
        RefundEvidenceDraft(
          bytes: Uint8List.fromList([1, 2]),
          fileName: 'first.jpg',
          contentType: 'image/jpeg',
        ),
        RefundEvidenceDraft(
          bytes: Uint8List.fromList([3]),
          fileName: 'second.pdf',
          contentType: 'application/pdf',
        ),
      ],
    );

    expect(client.multipartPath, 'orders/order-1/refund');
    expect(client.multipartFields, {
      'reason': 'Changed plans',
      'description': 'Cannot travel',
      'reasonCode': 'CHANGE',
    });
    expect(client.multipartFiles.map((file) => file.fieldName), [
      'evidenceFiles',
      'evidenceFiles',
    ]);
    expect(client.multipartFiles.map((file) => file.fileName), [
      'first.jpg',
      'second.pdf',
    ]);
    expect(client.multipartFiles.first.bytes, orderedEquals([1, 2]));
    expect(refund.id, 'refund-1');
    expect(refund.evidenceFiles.single.fileId, 'file-1');
  });

  test('service fee refund sends empty multipart files instead of JSON', () async {
    final client = RecordingApiClient();
    final source = ApiOrdersRemoteDataSource(client);
    final refund = await source.requestServiceFeeRefund(
      'order-1',
      reason: 'Changed plans',
      description: 'Cannot travel',
    );

    expect(client.multipartPath, 'orders/order-1/refund');
    expect(client.multipartFields, {
      'reason': 'Changed plans',
      'description': 'Cannot travel',
    });
    expect(client.multipartFiles, isEmpty);
    expect(client.jsonPath, isNull);
    expect(refund.reason, 'Changed plans');
  });

  test('legacy medical refund keeps JSON evidenceUrl request', () async {
    final client = RecordingApiClient();
    final source = ApiOrdersRemoteDataSource(client);
    final refund = await source.requestRefund(
      'order-1',
      reason: 'Changed plans',
      description: 'Cannot travel',
      evidenceUrl: 'https://media.example/receipt.jpg',
    );

    expect(client.jsonPath, 'orders/order-1/refund');
    expect(client.jsonBody, {
      'reason': 'Changed plans',
      'description': 'Cannot travel',
      'evidenceUrl': 'https://media.example/receipt.jpg',
    });
    expect(client.multipartPath, isNull);
    expect(refund.evidenceFiles.single.originalName, 'receipt.jpg');
  });

  test('real ApiClient encodes ordered multipart evidence and empty multipart', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final requests = <_CapturedRequest>[];
    final client = ApiClient(
      apiRoot: Uri(
        scheme: 'http',
        host: InternetAddress.loopbackIPv4.address,
        port: server.port,
        path: '/api/',
      ),
    );
    server.listen((request) async {
      final bytes = BytesBuilder(copy: false);
      await for (final chunk in request) {
        bytes.add(chunk);
      }
      requests.add(
        _CapturedRequest(
          method: request.method,
          path: request.uri.path,
          contentType: request.headers.contentType,
          body: bytes.takeBytes(),
        ),
      );
      request.response.headers.contentType = ContentType.json;
      request.response.write(jsonEncode({'code': 200, 'data': _refundJson}));
      await request.response.close();
    });

    try {
      final source = ApiOrdersRemoteDataSource(client);
      final multipartRefund = await source.requestServiceFeeRefund(
        'order-1',
        reason: 'Changed plans',
        description: 'Cannot travel',
        reasonCode: ' CHANGE ',
        evidenceFiles: [
          RefundEvidenceDraft(
            bytes: Uint8List.fromList([0, 255, 13, 10]),
            fileName: 'first.jpg',
            contentType: 'image/jpeg',
          ),
          RefundEvidenceDraft(
            bytes: Uint8List.fromList([37, 80, 68, 70, 45]),
            fileName: 'second.pdf',
            contentType: 'application/pdf',
          ),
        ],
      );
      final emptyRefund = await source.requestServiceFeeRefund(
        'order-1',
        reason: 'Changed plans',
        description: 'Cannot travel',
      );

      expect(multipartRefund.id, 'refund-1');
      expect(emptyRefund.evidenceFiles.single.fileId, 'file-1');
      expect(requests, hasLength(2));

      final firstRequest = requests.first;
      expect(firstRequest.method, 'POST');
      expect(firstRequest.path, '/api/orders/order-1/refund');
      expect(firstRequest.contentType?.mimeType, 'multipart/form-data');
      expect(firstRequest.contentType?.parameters['boundary'], isNotEmpty);
      final firstParts = _parseMultipart(firstRequest);
      expect(firstParts, hasLength(5));
      expect(firstParts[0].headers, contains('name="reason"'));
      expect(firstParts[0].body, orderedEquals(utf8.encode('Changed plans')));
      expect(firstParts[1].headers, contains('name="description"'));
      expect(firstParts[1].body, orderedEquals(utf8.encode('Cannot travel')));
      expect(firstParts[2].headers, contains('name="reasonCode"'));
      expect(firstParts[2].body, orderedEquals(utf8.encode('CHANGE')));
      expect(firstParts[3].headers, contains('name="evidenceFiles"; filename="first.jpg"'));
      expect(firstParts[3].contentType, 'image/jpeg');
      expect(firstParts[3].body, orderedEquals([0, 255, 13, 10]));
      expect(firstParts[4].headers, contains('name="evidenceFiles"; filename="second.pdf"'));
      expect(firstParts[4].contentType, 'application/pdf');
      expect(firstParts[4].body, orderedEquals([37, 80, 68, 70, 45]));
      expect(_endsWith(firstRequest.body, _ascii('--${firstRequest.boundary}--\r\n')), isTrue);

      final emptyRequest = requests.last;
      expect(emptyRequest.contentType?.mimeType, 'multipart/form-data');
      expect(emptyRequest.path, '/api/orders/order-1/refund');
      final emptyParts = _parseMultipart(emptyRequest);
      expect(emptyParts, hasLength(2));
      expect(emptyParts.map((part) => part.headers), everyElement(isNot(contains('filename='))));
    } finally {
      client.close();
      await server.close(force: true);
    }
  });

  test('rejects more than five drafts before opening a multipart request', () async {
    final client = RecordingApiClient();
    final source = ApiOrdersRemoteDataSource(client);
    final evidenceFiles = List.generate(
      RefundEvidenceDraft.maxCount + 1,
      (index) => RefundEvidenceDraft(
        bytes: Uint8List.fromList([index]),
        fileName: 'receipt-$index.jpg',
        contentType: 'image/jpeg',
      ),
    );

    await expectLater(
      source.requestServiceFeeRefund(
        'order-1',
        reason: 'Changed plans',
        description: 'Cannot travel',
        evidenceFiles: evidenceFiles,
      ),
      throwsArgumentError,
    );
    expect(client.multipartPath, isNull);
    expect(client.jsonPath, isNull);
  });
}

final class _CapturedRequest {
  const _CapturedRequest({
    required this.method,
    required this.path,
    required this.contentType,
    required this.body,
  });

  final String method;
  final String path;
  final ContentType? contentType;
  final List<int> body;

  String get boundary => contentType!.parameters['boundary']!;
}

final class _MultipartPart {
  const _MultipartPart({required this.headers, required this.body});

  final String headers;
  final List<int> body;

  String? get contentType => _headerValue(headers, 'content-type');
}

List<_MultipartPart> _parseMultipart(_CapturedRequest request) {
  final opening = _ascii('--${request.boundary}\r\n');
  final delimiter = _ascii('\r\n--${request.boundary}');
  final headerSeparator = _ascii('\r\n\r\n');
  final body = request.body;
  expect(_startsWith(body, opening), isTrue);
  final parts = <_MultipartPart>[];
  var cursor = opening.length;
  while (true) {
    final headerEnd = _indexOf(body, headerSeparator, cursor);
    expect(headerEnd, isNonNegative);
    final nextBoundary = _indexOf(body, delimiter, headerEnd + headerSeparator.length);
    expect(nextBoundary, isNonNegative);
    parts.add(
      _MultipartPart(
        headers: latin1.decode(body.sublist(cursor, headerEnd)),
        body: body.sublist(headerEnd + headerSeparator.length, nextBoundary),
      ),
    );
    cursor = nextBoundary + delimiter.length;
    if (_startsWith(body, _ascii('--\r\n'), cursor)) {
      expect(cursor + 4, body.length);
      return parts;
    }
    expect(_startsWith(body, _ascii('\r\n'), cursor), isTrue);
    cursor += 2;
  }
}

String? _headerValue(String headers, String name) {
  final prefix = '${name.toLowerCase()}:';
  for (final line in headers.split('\r\n')) {
    if (line.toLowerCase().startsWith(prefix)) {
      return line.substring(prefix.length).trim();
    }
  }
  return null;
}

List<int> _ascii(String value) => latin1.encode(value);

bool _startsWith(List<int> value, List<int> prefix, [int offset = 0]) {
  if (offset < 0 || value.length - offset < prefix.length) return false;
  for (var index = 0; index < prefix.length; index += 1) {
    if (value[offset + index] != prefix[index]) return false;
  }
  return true;
}

bool _endsWith(List<int> value, List<int> suffix) =>
    _startsWith(value, suffix, value.length - suffix.length);

int _indexOf(List<int> value, List<int> needle, int start) {
  for (var index = start; index <= value.length - needle.length; index += 1) {
    if (_startsWith(value, needle, index)) return index;
  }
  return -1;
}

final class RecordingApiClient extends ApiClient {
  RecordingApiClient() : super(apiRoot: Uri.parse('https://example.test/api/'));

  String? jsonPath;
  Object? jsonBody;
  String? multipartPath;
  Map<String, String> multipartFields = const {};
  List<MultipartFilePart> multipartFiles = const [];

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    jsonPath = path;
    jsonBody = body;
    return decodeData(_refundJson);
  }

  @override
  Future<T?> postMultipart<T>(
    String path, {
    Map<String, String> fields = const {},
    required List<MultipartFilePart> files,
    required T Function(Object? json) decodeData,
    void Function(int bytesSent, int totalBytes)? onProgress,
  }) async {
    multipartPath = path;
    multipartFields = Map.unmodifiable(fields);
    multipartFiles = List.unmodifiable(files);
    return decodeData(_refundJson);
  }
}

const _refundJson = <String, Object>{
  'id': 'refund-1',
  'orderId': 'order-1',
  'amount': '23.50',
  'reason': 'Changed plans',
  'description': 'Cannot travel',
  'status': 'PENDING',
  'createdAt': '2026-08-30T10:00:00Z',
  'evidenceFiles': [
    {
      'fileId': 'file-1',
      'originalName': 'receipt.jpg',
      'contentType': 'image/jpeg',
      'sizeBytes': 2,
      'position': 0,
    },
  ],
};
