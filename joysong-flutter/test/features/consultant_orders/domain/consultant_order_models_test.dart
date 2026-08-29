import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_order_models.dart';

const summaryJson = <String, Object?>{
  'id': 'order-1',
  'orderNo': 'JS202608290001',
  'stage': 'ACTIVE',
  'status': 'SERVICE_ACTIVE',
  'refundStatus': 'NONE',
  'project': <String, Object?>{
    'id': 'project-1',
    'name': '皮肤管理',
    'coverImage': '/images/p.jpg',
  },
  'institution': <String, Object?>{
    'id': 'institution-1',
    'name': '示例机构',
  },
  'customer': <String, Object?>{
    'displayName': '用户一',
    'avatar': '/images/a.jpg',
  },
  'appointmentTime': '2026-09-01T10:00:00',
  'updatedAt': '2026-08-29T12:30:00',
  'conversationReadable': true,
  'messageSendable': true,
  'readOnly': false,
};

const activePageJson = <String, Object?>{
  'items': <Object?>[summaryJson],
  'offset': 0,
  'limit': 20,
  'hasMore': false,
};

const detailJson = <String, Object?>{
  'id': 'order-1',
  'orderNo': 'JS202608290001',
  'stage': 'ACTIVE',
  'status': 'SERVICE_ACTIVE',
  'refundStatus': 'NONE',
  'project': <String, Object?>{
    'id': 'project-1',
    'name': '皮肤管理',
    'coverImage': '/images/p.jpg',
  },
  'institution': <String, Object?>{
    'id': 'institution-1',
    'name': '示例机构',
  },
  'customer': <String, Object?>{
    'displayName': '用户一',
    'avatar': '/images/a.jpg',
  },
  'doctor': <String, Object?>{
    'id': 'doctor-1',
    'name': '医生一',
  },
  'appointmentTime': '2026-09-01T10:00:00',
  'remark': '请提前联系',
  'createdAt': '2026-08-28T09:00:00',
  'updatedAt': '2026-08-29T12:30:00',
  'serviceActivatedAt': '2026-08-29T10:00:00',
  'completedAt': null,
  'conversationReadable': true,
  'messageSendable': true,
  'readOnly': false,
  'conversation': <String, Object?>{
    'readable': true,
    'sendable': true,
  },
};

const approvedSummaryKeys = <String>{
  'id',
  'orderNo',
  'stage',
  'status',
  'refundStatus',
  'project',
  'institution',
  'customer',
  'appointmentTime',
  'updatedAt',
  'conversationReadable',
  'messageSendable',
  'readOnly',
};

const forbiddenKeys = <String>{
  'phone',
  'realName',
  'verificationCode',
  'qrCode',
  'amount',
  'price',
  'payment',
  'refundEvidence',
  'split',
  'settlement',
  'statusLogs',
};

void main() {
  group('ConsultantOrderPage', () {
    test('parses a redacted active order page', () {
      final page = ConsultantOrderPage.fromJson(activePageJson);

      expect(page.items.single.stage, ConsultantOrderStage.active);
      expect(page.items.single.messageSendable, isTrue);
      expect(page.items.single.customer.displayName, '用户一');
      expect(page.items.single.appointmentTime?.isUtc, isFalse);
      expect(page.offset, 0);
      expect(page.limit, 20);
      expect(page.hasMore, isFalse);
    });

    test('requires every pagination field with its exact type', () {
      final missingLimit = Map<String, Object?>.from(activePageJson)
        ..remove('limit');
      final missingHasMore = Map<String, Object?>.from(activePageJson)
        ..remove('hasMore');

      for (final invalid in <Map<String, Object?>>[
        missingLimit,
        missingHasMore,
        {...activePageJson, 'items': 'not-a-list'},
        {...activePageJson, 'offset': 0.0},
        {...activePageJson, 'limit': '20'},
        {...activePageJson, 'hasMore': 0},
      ]) {
        expect(
          () => ConsultantOrderPage.fromJson(invalid),
          throwsFormatException,
        );
      }
    });
  });

  group('ConsultantOrderSummary', () {
    test('keeps an unknown nonblank status as a displayable raw code', () {
      final summary = ConsultantOrderSummary.fromJson({
        ...summaryJson,
        'status': 'FUTURE_STATUS',
      });

      expect(summary.status, 'FUTURE_STATUS');
      expect(summary.stage, ConsultantOrderStage.active);
    });

    test('rejects an unknown or blank stage', () {
      for (final stage in <Object?>['UNKNOWN', '', '   ']) {
        expect(
          () => ConsultantOrderSummary.fromJson({
            ...summaryJson,
            'stage': stage,
          }),
          throwsFormatException,
        );
      }
    });

    test('allows blank required display strings without losing their keys', () {
      final summary = ConsultantOrderSummary.fromJson({
        ...summaryJson,
        'orderNo': ' ',
        'project': <String, Object?>{
          'id': 'project-1',
          'name': '',
          'coverImage': '   ',
        },
        'institution': <String, Object?>{
          'id': 'institution-1',
          'name': '',
        },
      });

      expect(summary.orderNo, ' ');
      expect(summary.project.name, '');
      expect(summary.project.coverImage, '   ');
      expect(summary.institution.name, '');
    });

    test('rejects missing, null, or wrongly typed display strings', () {
      final missingOrderNo = Map<String, Object?>.from(summaryJson)
        ..remove('orderNo');
      final missingProjectName = {
        ...summaryJson,
        'project': <String, Object?>{
          'id': 'project-1',
          'coverImage': '/images/p.jpg',
        },
      };
      final missingCoverImage = {
        ...summaryJson,
        'project': <String, Object?>{
          'id': 'project-1',
          'name': '皮肤管理',
        },
      };
      final missingInstitutionName = {
        ...summaryJson,
        'institution': <String, Object?>{'id': 'institution-1'},
      };

      for (final invalid in <Map<String, Object?>>[
        missingOrderNo,
        {...summaryJson, 'orderNo': null},
        {...summaryJson, 'orderNo': 1},
        missingProjectName,
        {
          ...summaryJson,
          'project': <String, Object?>{
            'id': 'project-1',
            'name': null,
            'coverImage': '/images/p.jpg',
          },
        },
        missingCoverImage,
        {
          ...summaryJson,
          'project': <String, Object?>{
            'id': 'project-1',
            'name': '皮肤管理',
            'coverImage': 1,
          },
        },
        missingInstitutionName,
        {
          ...summaryJson,
          'institution': <String, Object?>{
            'id': 'institution-1',
            'name': false,
          },
        },
      ]) {
        expect(
          () => ConsultantOrderSummary.fromJson(invalid),
          throwsFormatException,
        );
      }
    });

    test('rejects blank core identity and code fields', () {
      for (final invalid in <Map<String, Object?>>[
        {...summaryJson, 'id': ' '},
        {...summaryJson, 'status': ''},
        {...summaryJson, 'refundStatus': '   '},
        {
          ...summaryJson,
          'project': <String, Object?>{
            'id': '',
            'name': '皮肤管理',
            'coverImage': '/images/p.jpg',
          },
        },
        {
          ...summaryJson,
          'institution': <String, Object?>{
            'id': ' ',
            'name': '示例机构',
          },
        },
        {
          ...summaryJson,
          'customer': <String, Object?>{
            'displayName': '',
            'avatar': null,
          },
        },
      ]) {
        expect(
          () => ConsultantOrderSummary.fromJson(invalid),
          throwsFormatException,
        );
      }
    });

    test('requires strict core field and conversation flag types', () {
      for (final invalid in <Map<String, Object?>>[
        {...summaryJson, 'id': 1},
        {...summaryJson, 'status': null},
        {...summaryJson, 'appointmentTime': 1},
        {...summaryJson, 'conversationReadable': 'true'},
        {...summaryJson, 'messageSendable': 1},
        {...summaryJson, 'readOnly': 0},
      ]) {
        expect(
          () => ConsultantOrderSummary.fromJson(invalid),
          throwsFormatException,
        );
      }
    });

    test('requires readOnly to be the inverse of messageSendable', () {
      expect(
        () => ConsultantOrderSummary.fromJson({
          ...summaryJson,
          'messageSendable': false,
          'readOnly': false,
        }),
        throwsFormatException,
      );
    });

    test('rejects invalid required and optional timestamps', () {
      for (final invalid in <Map<String, Object?>>[
        {...summaryJson, 'updatedAt': 'not-a-time'},
        {...summaryJson, 'appointmentTime': 'not-a-time'},
      ]) {
        expect(
          () => ConsultantOrderSummary.fromJson(invalid),
          throwsFormatException,
        );
      }
    });
  });

  group('ConsultantOrderDetail', () {
    test('builds its summary projection from the flat detail fields', () {
      final detail = ConsultantOrderDetail.fromJson(detailJson);

      expect(detail.summary.id, 'order-1');
      expect(detail.summary.updatedAt, DateTime(2026, 8, 29, 12, 30));
      expect(detail.doctor.id, 'doctor-1');
      expect(detail.createdAt.isUtc, isFalse);
      expect(detail.completedAt, isNull);
      expect(detail.conversation.readable, isTrue);
      expect(detail.conversation.sendable, isTrue);
    });

    test('allows nullable doctor id and blank display values', () {
      final detail = ConsultantOrderDetail.fromJson({
        ...detailJson,
        'orderNo': '',
        'project': <String, Object?>{
          'id': 'project-1',
          'name': ' ',
          'coverImage': '',
        },
        'institution': <String, Object?>{
          'id': 'institution-1',
          'name': '',
        },
        'customer': <String, Object?>{
          'displayName': '用户一',
          'avatar': ' ',
        },
        'doctor': <String, Object?>{'id': null, 'name': ''},
        'remark': ' ',
      });

      expect(detail.summary.orderNo, '');
      expect(detail.summary.project.coverImage, '');
      expect(detail.summary.customer.avatar, ' ');
      expect(detail.doctor.id, isNull);
      expect(detail.doctor.name, '');
      expect(detail.remark, ' ');
    });

    test('rejects blank doctor id and invalid avatar values', () {
      for (final invalid in <Map<String, Object?>>[
        {
          ...detailJson,
          'doctor': <String, Object?>{'id': ' ', 'name': '医生一'},
        },
        {
          ...detailJson,
          'doctor': <String, Object?>{'name': '医生一'},
        },
        {
          ...detailJson,
          'customer': <String, Object?>{
            'displayName': '用户一',
            'avatar': 1,
          },
        },
        {
          ...detailJson,
          'customer': <String, Object?>{'displayName': '用户一'},
        },
      ]) {
        expect(
          () => ConsultantOrderDetail.fromJson(invalid),
          throwsFormatException,
        );
      }
    });

    test('requires detail-only display fields to exist as strings', () {
      final missingDoctorName = {
        ...detailJson,
        'doctor': <String, Object?>{'id': 'doctor-1'},
      };
      final missingRemark = Map<String, Object?>.from(detailJson)
        ..remove('remark');

      for (final invalid in <Map<String, Object?>>[
        missingDoctorName,
        {
          ...detailJson,
          'doctor': <String, Object?>{'id': 'doctor-1', 'name': null},
        },
        missingRemark,
        {...detailJson, 'remark': false},
      ]) {
        expect(
          () => ConsultantOrderDetail.fromJson(invalid),
          throwsFormatException,
        );
      }
    });

    test('rejects top-level and nested conversation mismatches', () {
      for (final invalid in <Map<String, Object?>>[
        {
          ...detailJson,
          'conversation': <String, Object?>{
            'readable': false,
            'sendable': true,
          },
        },
        {
          ...detailJson,
          'conversation': <String, Object?>{
            'readable': true,
            'sendable': false,
          },
        },
        {...detailJson, 'readOnly': true},
      ]) {
        expect(
          () => ConsultantOrderDetail.fromJson(invalid),
          throwsFormatException,
        );
      }
    });

    test('rejects invalid detail timestamps', () {
      for (final invalid in <Map<String, Object?>>[
        {...detailJson, 'createdAt': 'not-a-time'},
        {...detailJson, 'serviceActivatedAt': 1},
        {...detailJson, 'completedAt': 'not-a-time'},
      ]) {
        expect(
          () => ConsultantOrderDetail.fromJson(invalid),
          throwsFormatException,
        );
      }
    });
  });

  test('fixtures expose only the approved redacted DTO keys', () {
    expect(jsonKeys(activePageJson).intersection(forbiddenKeys), isEmpty);
    expect(jsonKeys(detailJson).intersection(forbiddenKeys), isEmpty);
    expect(summaryJson.keys.toSet(), approvedSummaryKeys);
  });
}

Set<String> jsonKeys(Object? value) {
  final keys = <String>{};
  if (value is Map) {
    for (final entry in value.entries) {
      keys.add(entry.key.toString());
      keys.addAll(jsonKeys(entry.value));
    }
  }
  if (value is List) {
    for (final item in value) {
      keys.addAll(jsonKeys(item));
    }
  }
  return keys;
}
