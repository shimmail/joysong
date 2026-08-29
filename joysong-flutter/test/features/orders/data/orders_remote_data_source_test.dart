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
