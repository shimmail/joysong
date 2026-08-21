import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/booking/data/booking_remote_data_source.dart';
import 'package:joysong_flutter/features/booking/domain/booking_models.dart';

import '../orders/order_test_fixtures.dart';

void main() {
  test('create order omits quantity and coupon fields', () {
    final json = CreateOrderCommand(
      projectId: 'project-1',
      institutionProjectId: 'ip-1',
      consultantId: 'consultant-1',
      doctorId: 'doctor-1',
      appointmentTime: DateTime(2026, 8, 22, 10),
    ).toJson();

    expect(json['consultantId'], 'consultant-1');
    expect(json.containsKey('quantity'), isFalse);
    expect(json.containsKey('userCouponId'), isFalse);
  });

  test('parses and formats USD travel ground service quote', () {
    final quote = TravelGroundServiceQuote.fromJson({
      'currency': 'USD',
      'medicalListPriceMinor': 100000,
      'platformServiceRateBps': 4000,
      'travelGroundServiceFeeMinor': 40000,
    });

    expect(quote.medicalListPriceMinor, 100000);
    expect(quote.platformServiceRateBps, 4000);
    expect(quote.travelGroundServiceFeeMinor, 40000);
    expect(quote.travelGroundServiceFeeFormatted, r'$400.00');
  });

  test('rejects non-USD travel ground service quote', () {
    expect(
      () => TravelGroundServiceQuote.fromJson({
        'currency': 'EUR',
        'medicalListPriceMinor': 100000,
        'platformServiceRateBps': 4000,
        'travelGroundServiceFeeMinor': 40000,
      }),
      throwsFormatException,
    );
  });

  test('rejects negative travel ground service quote minor units', () {
    expect(
      () => TravelGroundServiceQuote.fromJson({
        'currency': 'USD',
        'medicalListPriceMinor': -1,
        'platformServiceRateBps': 4000,
        'travelGroundServiceFeeMinor': 40000,
      }),
      throwsFormatException,
    );
    expect(
      () => TravelGroundServiceQuote.fromJson({
        'currency': 'USD',
        'medicalListPriceMinor': 100000,
        'platformServiceRateBps': 4000,
        'travelGroundServiceFeeMinor': -1,
      }),
      throwsFormatException,
    );
  });

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

  test('loads travel ground service quote from doctor project contract',
      () async {
    final client = _FakeApiClient()
      ..responseData = {
        'currency': 'USD',
        'medicalListPriceMinor': 100000,
        'platformServiceRateBps': 4000,
        'travelGroundServiceFeeMinor': 40000,
      };
    final dataSource = ApiBookingRemoteDataSource(client);

    final quote = await dataSource.getTravelGroundServiceQuote(
      doctorId: 'doctor-1',
      institutionProjectId: 'ip-1',
    );

    expect(quote.travelGroundServiceFeeFormatted, r'$400.00');
    expect(client.lastPath, 'discover/travel-ground-service-quote');
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
        appointmentTime: appointment,
      ),
    );

    expect(client.lastPath, 'orders');
    final body = client.lastBody! as Map<String, Object?>;
    expect(body['appointmentTime'], '2026-08-08T14:30:00');
    expect(body['consultantId'], 'consultant-1');
    expect(body.containsKey('quantity'), isFalse);
    expect(body.containsKey('userCouponId'), isFalse);
    expect(body.containsKey('price'), isFalse);
    expect(body.containsKey('amount'), isFalse);
    expect(body.containsKey('consultationFee'), isFalse);
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
