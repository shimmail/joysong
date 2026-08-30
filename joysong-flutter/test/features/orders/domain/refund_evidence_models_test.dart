import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/domain/refund_evidence_models.dart';

void main() {
  test('accepts supported MIME-extension pairs at exactly 10 MiB', () {
    for (final entry in const {
      'receipt.jpg': 'image/jpeg',
      'receipt.JPEG': 'image/jpeg',
      'receipt.png': 'image/png',
      'receipt.webp': 'image/webp',
      'receipt.pdf': 'application/pdf',
    }.entries) {
      RefundEvidenceDraft(
        bytes: Uint8List(RefundEvidenceDraft.maxBytes),
        fileName: entry.key,
        contentType: entry.value,
      ).validate();
    }
  });

  test('rejects empty, oversized, unsupported, and mismatched evidence', () {
    final invalidDrafts = <RefundEvidenceDraft>[
      RefundEvidenceDraft(
        bytes: Uint8List(0),
        fileName: 'empty.jpg',
        contentType: 'image/jpeg',
      ),
      RefundEvidenceDraft(
        bytes: Uint8List(RefundEvidenceDraft.maxBytes + 1),
        fileName: 'large.pdf',
        contentType: 'application/pdf',
      ),
      RefundEvidenceDraft(
        bytes: Uint8List(1),
        fileName: 'text.txt',
        contentType: 'text/plain',
      ),
      RefundEvidenceDraft(
        bytes: Uint8List(1),
        fileName: 'image.png',
        contentType: 'image/jpeg',
      ),
    ];

    for (final draft in invalidDrafts) {
      expect(draft.validate, throwsArgumentError);
    }
  });

  test('copies source bytes so later caller mutations cannot alter a draft', () {
    final sourceBytes = Uint8List.fromList([1, 2, 3]);
    final draft = RefundEvidenceDraft(
      bytes: sourceBytes,
      fileName: 'receipt.jpg',
      contentType: 'image/jpeg',
    );

    sourceBytes[0] = 99;

    expect(draft.bytes, orderedEquals([1, 2, 3]));
  });

  test('parses evidence metadata in position order and defaults missing arrays', () {
    final refund = RefundDetail.fromJson({
      ..._refundJson,
      'evidenceFiles': [
        {
          'fileId': 'file-2',
          'originalName': 'second.pdf',
          'contentType': 'application/pdf',
          'sizeBytes': 2,
          'position': 1,
        },
        {
          'fileId': 'file-1',
          'originalName': 'first.jpg',
          'contentType': 'image/jpeg',
          'sizeBytes': 1,
          'position': 0,
        },
      ],
    });
    final withoutEvidence = RefundDetail.fromJson(_refundJson);

    expect(refund.evidenceFiles.map((file) => file.fileId), ['file-1', 'file-2']);
    expect(() => refund.evidenceFiles.add(refund.evidenceFiles.first), throwsUnsupportedError);
    expect(withoutEvidence.evidenceFiles, isEmpty);
  });

  test('rejects metadata with empty fields, negative size, or invalid position', () {
    final invalidMetadata = [
      {
        'fileId': '',
        'originalName': 'receipt.jpg',
        'contentType': 'image/jpeg',
        'sizeBytes': 1,
        'position': 0,
      },
      {
        'fileId': 'file-1',
        'originalName': '',
        'contentType': 'image/jpeg',
        'sizeBytes': 1,
        'position': 0,
      },
      {
        'fileId': 'file-1',
        'originalName': 'receipt.jpg',
        'contentType': '',
        'sizeBytes': 1,
        'position': 0,
      },
      {
        'fileId': 'file-1',
        'originalName': 'receipt.jpg',
        'contentType': 'image/jpeg',
        'sizeBytes': -1,
        'position': 0,
      },
      {
        'fileId': 'file-1',
        'originalName': 'receipt.jpg',
        'contentType': 'image/jpeg',
        'sizeBytes': 1,
        'position': RefundEvidenceDraft.maxCount,
      },
    ];

    for (final metadata in invalidMetadata) {
      expect(() => RefundEvidenceFile.fromJson(metadata), throwsFormatException);
    }
  });
}

const _refundJson = <String, Object>{
  'id': 'refund-1',
  'orderId': 'order-1',
  'amount': '23.50',
  'reason': 'Changed plans',
  'description': 'Cannot travel',
  'status': 'PENDING',
  'createdAt': '2026-08-30T10:00:00Z',
};
