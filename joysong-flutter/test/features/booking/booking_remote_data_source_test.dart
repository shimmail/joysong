import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/booking/data/booking_remote_data_source.dart';
import 'package:joysong_flutter/features/booking/domain/booking_models.dart';
import 'package:joysong_flutter/features/orders/domain/money.dart';

import '../orders/order_test_fixtures.dart';

void main() {
  test('loads institution consultants from discover contract', () async {
    final client = _FakeApiClient()
      ..responseData = [
        {'id': 'consultant-1', 'name': '李咨询师'},
      ];
    final dataSource = ApiBookingRemoteDataSource(client);

    final consultants = await dataSource.getConsultants('institution-1');

    expect(consultants.single.id, 'consultant-1');
    expect(client.lastPath, 'discover/institutions/institution-1/consultants');
  });

  test('loads doctor fee from discover contract', () async {
    final client = _FakeApiClient()
      ..responseData = {'consultationFee': '88.50'};
    final dataSource = ApiBookingRemoteDataSource(client);

    final fee = await dataSource.getConsultationFee(
      doctorId: 'doctor-1',
      institutionProjectId: 'ip-1',
    );

    expect(fee, Money.parse('88.5'));
    expect(client.lastPath, 'discover/consultation-fee');
    expect(client.lastQuery, {
      'doctorId': 'doctor-1',
      'institutionProjectId': 'ip-1',
    });
  });

  test('creates order without sending any client-computed price', () async {
    final client = _FakeApiClient()..responseData = sampleOrderJson();
    final dataSource = ApiBookingRemoteDataSource(client);
    final appointment = DateTime(2026, 8, 8, 14, 30);

    await dataSource.createOrder(
      CreateOrderCommand(
        projectId: 'project-1',
        institutionProjectId: 'ip-1',
        consultantId: 'consultant-1',
        doctorId: 'doctor-1',
        userCouponId: 11,
        appointmentTime: appointment,
      ),
    );

    expect(client.lastPath, 'orders');
    final body = client.lastBody! as Map<String, Object?>;
    expect(body['appointmentTime'], '2026-08-08T14:30:00');
    expect(body['userCouponId'], 11);
    expect(body['consultantId'], 'consultant-1');
    expect(body.containsKey('price'), isFalse);
    expect(body.containsKey('amount'), isFalse);
    expect(body.containsKey('consultationFee'), isFalse);
  });

  test('uses coupon definition id for quote and decimal query string',
      () async {
    final client = _FakeApiClient()
      ..responseData = {
        'couponId': 22,
        'originalPrice': '1280.50',
        'discountAmount': '100.00',
      };
    final dataSource = ApiBookingRemoteDataSource(client);

    await dataSource.calculateDiscount(
      couponId: 22,
      originalPrice: Money.parse('1280.50'),
    );

    expect(client.lastPath, 'coupons/22/discount');
    expect(client.lastQuery, {'originalPrice': '1280.5'});
  });
}

final class _FakeApiClient extends ApiClient {
  _FakeApiClient() : super(apiRoot: Uri.parse('http://localhost/api/'));

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
    lastPath = path;
    lastBody = body;
    return decodeData(responseData);
  }
}
