import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/orders/data/orders_remote_data_source.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/domain/payment_models.dart';

import 'order_test_fixtures.dart';
import 'payment_test_fixtures.dart';

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

  test('creates service-fee attempt at fixed endpoint without a body',
      () async {
    final client = _FakeApiClient()..responseData = paymentAttemptJson();
    final dataSource = ApiOrdersRemoteDataSource(client);

    final payment = await dataSource.createTravelGroundServicePaymentAttempt(
      'order-1',
      idempotencyKey: 'payment-key-1',
    );

    expect(payment.id, 'payment-1');
    expect(client.lastMethod, 'POST_IDEMPOTENT');
    expect(client.lastPath, 'orders/order-1/service-fee-payment-attempts');
    expect(client.lastIdempotencyKey, 'payment-key-1');
    expect(client.lastBody, isNull);
  });

  test('queries payment and fixed service-fee latest attempt', () async {
    final client = _FakeApiClient()..responseData = paymentAttemptJson();
    final dataSource = ApiOrdersRemoteDataSource(client);

    await dataSource.getPayment('payment-1', refresh: true);
    expect(client.lastPath, 'payments/payment-1');
    expect(client.lastQuery, {'refresh': true});

    await dataSource.getLatestTravelGroundServicePayment('order-1');
    expect(client.lastPath, 'orders/order-1/payments/latest');
    expect(client.lastQuery, {
      'paymentType': 'TRAVEL_GROUND_SERVICE_FEE',
      'refresh': false,
    });
  });

  test('gets the consumer settlement using its current response contract',
      () async {
    final client = _FakeApiClient()
      ..responseData = {
        'settlementId': 1,
        'orderId': 'order-1',
        'currency': 'USD',
        'grossTotalPaid': {'currency': 'USD', 'minor': 1200},
        'netSettled': {'currency': 'USD', 'minor': 1000},
        'state': 'PENDING_RELEASE',
        'settlementDueAt': '2026-08-12T10:00:00',
        'settlementCreatedAt': '2026-08-11T10:00:00',
        'releasedAt': null,
      };
    final dataSource = ApiOrdersRemoteDataSource(client);

    final settlement = await dataSource.getSettlement('order-1');

    expect(client.lastMethod, 'GET');
    expect(client.lastPath, 'orders/order-1/settlement');
    expect(settlement, isA<Settlement>());
    expect(settlement.grossTotalPaidMinor, 1200);
    expect(settlement.netSettledMinor, 1000);
  });
}

final class _FakeApiClient extends ApiClient {
  _FakeApiClient() : super(apiRoot: Uri.parse('http://localhost/api/'));

  String? lastMethod;
  String? lastPath;
  Object? lastBody;
  Map<String, Object?>? lastQuery;
  String? lastIdempotencyKey;
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

  @override
  Future<T?> postIdempotent<T>(
    String path, {
    required String idempotencyKey,
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    lastMethod = 'POST_IDEMPOTENT';
    lastPath = path;
    lastBody = body;
    lastIdempotencyKey = idempotencyKey;
    return decodeData(responseData);
  }
}
