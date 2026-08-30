import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/consultant_orders/data/consultant_orders_remote_data_source.dart';
import 'package:joysong_flutter/features/consultant_orders/data/consultant_orders_repository_impl.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_order_models.dart';

const pausedPageJson = <String, Object?>{
  'items': <Object?>[
    <String, Object?>{
      'id': 'order-1',
      'orderNo': 'JS202608290001',
      'stage': 'PAUSED',
      'status': 'SERVICE_PAUSED',
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
        'avatar': null,
      },
      'appointmentTime': null,
      'updatedAt': '2026-08-29T12:30:00',
      'conversationReadable': true,
      'messageSendable': false,
      'readOnly': true,
    },
  ],
  'offset': 20,
  'limit': 20,
  'hasMore': false,
};

const remoteDetailJson = <String, Object?>{
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
  'appointmentTime': null,
  'remark': '',
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

void main() {
  group('ApiConsultantOrdersRemoteDataSource', () {
    test('uses the scoped list path and resolves public media URLs', () async {
      final client = RecordingApiClient({
        'consultant/orders': pausedPageJson,
      });
      addTearDown(client.close);
      final dataSource = ApiConsultantOrdersRemoteDataSource(client);

      final page = await dataSource.getOrders(
        stage: ConsultantOrderStage.paused,
        institutionId: 'institution-1',
        offset: 20,
        limit: 20,
      );

      expect(client.requests.first.path, 'consultant/orders');
      expect(client.requests.first.query, {
        'stage': 'PAUSED',
        'institutionId': 'institution-1',
        'offset': 20,
        'limit': 20,
      });
      expect(client.requests.first.query.containsKey('consultantId'), isFalse);
      expect(
        page.items.single.project.coverImage,
        'https://api.example.com/images/p.jpg',
      );
    });

    test('preserves an empty cover image after public media resolution',
        () async {
      final pausedSummary = Map<String, Object?>.from(
        (pausedPageJson['items']! as List<Object?>).single! as Map,
      );
      final response = <String, Object?>{
        ...pausedPageJson,
        'items': <Object?>[
          <String, Object?>{
            ...pausedSummary,
            'project': <String, Object?>{
              'id': 'project-1',
              'name': '',
              'coverImage': '',
            },
          },
        ],
      };
      final client = RecordingApiClient({'consultant/orders': response});
      addTearDown(client.close);

      final page = await ApiConsultantOrdersRemoteDataSource(client).getOrders(
        stage: ConsultantOrderStage.paused,
        offset: 20,
        limit: 20,
      );

      expect(page.items.single.project.coverImage, '');
    });

    test('uses the scoped detail path without a consultant id', () async {
      final client = RecordingApiClient({
        'consultant/orders/order-1': remoteDetailJson,
      });
      addTearDown(client.close);
      final dataSource = ApiConsultantOrdersRemoteDataSource(client);

      final detail = await dataSource.getOrder(' order-1 ');

      expect(client.requests.single.path, 'consultant/orders/order-1');
      expect(client.requests.single.query, isEmpty);
      expect(client.requests.single.query.containsKey('consultantId'), isFalse);
      expect(
        detail.summary.customer.avatar,
        'https://api.example.com/images/a.jpg',
      );
    });
  });

  test('repository delegates list and detail requests without policy logic',
      () async {
    final page = ConsultantOrderPage.fromJson(pausedPageJson);
    final detail = ConsultantOrderDetail.fromJson(remoteDetailJson);
    final remote = RecordingConsultantOrdersRemoteDataSource(page, detail);
    final repository = ConsultantOrdersRepositoryImpl(remote);

    expect(
      await repository.getOrders(
        stage: ConsultantOrderStage.history,
        institutionId: 'institution-2',
        offset: 40,
        limit: 10,
      ),
      same(page),
    );
    expect(await repository.getOrder('order-2'), same(detail));
    expect(
      remote.listRequest,
      (
        stage: ConsultantOrderStage.history,
        institutionId: 'institution-2',
        offset: 40,
        limit: 10,
      ),
    );
    expect(remote.detailOrderId, 'order-2');
  });
}

final class RecordingApiClient extends ApiClient {
  RecordingApiClient(this.responses)
      : super(apiRoot: Uri.parse('https://api.example.com/api/'));

  final Map<String, Object?> responses;
  final List<({String path, Map<String, Object?> query})> requests = [];

  @override
  Future<T?> get<T>(
    String requestPath, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    requests.add((path: requestPath, query: Map.unmodifiable(query)));
    return decodeData(responses[requestPath]);
  }
}

final class RecordingConsultantOrdersRemoteDataSource
    implements ConsultantOrdersRemoteDataSource {
  RecordingConsultantOrdersRemoteDataSource(this.page, this.detail);

  final ConsultantOrderPage page;
  final ConsultantOrderDetail detail;
  ({
    ConsultantOrderStage stage,
    String? institutionId,
    int offset,
    int limit,
  })? listRequest;
  String? detailOrderId;

  @override
  Future<ConsultantOrderPage> getOrders({
    required ConsultantOrderStage stage,
    String? institutionId,
    required int offset,
    required int limit,
  }) async {
    listRequest = (
      stage: stage,
      institutionId: institutionId,
      offset: offset,
      limit: limit,
    );
    return page;
  }

  @override
  Future<ConsultantOrderDetail> getOrder(String orderId) async {
    detailOrderId = orderId;
    return detail;
  }
}
