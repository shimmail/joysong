import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/orders/data/orders_remote_data_source.dart';

import 'order_test_fixtures.dart';

void main() {
  test('uses current user verification-code endpoint only', () async {
    final client = _FakeApiClient()
      ..responseData = sampleOrderJson(
        status: 'CONSULTATION_PAID',
        verifyCode: '123456',
      );
    final dataSource = ApiOrdersRemoteDataSource(client);

    final order = await dataSource.requestVerificationCode('order-1');

    expect(client.lastMethod, 'POST');
    expect(client.lastPath, 'orders/order-1/verification-code');
    expect(client.lastPath, isNot(contains('/management/')));
    expect(client.lastPath, isNot(endsWith('/verify')));
    expect(order.verifyCode, '123456');
  });

  test('maps status filter with offset and limit', () async {
    final client = _FakeApiClient()..responseData = [sampleOrderJson()];
    final dataSource = ApiOrdersRemoteDataSource(client);

    final orders = await dataSource.getOrders(
      status: 'PENDING_PAYMENT',
      offset: 20,
      limit: 20,
    );

    expect(orders, hasLength(1));
    expect(client.lastPath, 'orders');
    expect(client.lastQuery, {
      'status': 'PENDING_PAYMENT',
      'offset': 20,
      'limit': 20,
    });
  });
}

final class _FakeApiClient extends ApiClient {
  _FakeApiClient() : super(apiRoot: Uri.parse('http://localhost/api/'));

  String? lastMethod;
  String? lastPath;
  Object? lastBody;
  Map<String, Object?>? lastQuery;
  Object? responseData;

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    lastMethod = 'GET';
    lastPath = path;
    lastQuery = query;
    return decodeData(responseData);
  }

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    lastMethod = 'POST';
    lastPath = path;
    lastBody = body;
    return decodeData(responseData);
  }
}
