import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/booking/domain/booking_models.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

abstract interface class BookingRemoteDataSource {
  Future<List<InstitutionProject>> getInstitutionProjects(String institutionId);

  Future<InstitutionProject> getInstitutionProject(
    String institutionId,
    String projectId,
  );

  Future<List<BookingDoctor>> getDoctors(String institutionProjectId);

  Future<List<BookingConsultant>> getConsultants(String institutionId);

  Future<TravelGroundServiceQuote> getTravelGroundServiceQuote({
    required String doctorId,
    required String institutionProjectId,
  });

  Future<Order> createOrder(CreateOrderCommand command);
}

final class ApiBookingRemoteDataSource implements BookingRemoteDataSource {
  const ApiBookingRemoteDataSource(this._apiClient);

  final ApiClient _apiClient;

  @override
  Future<List<InstitutionProject>> getInstitutionProjects(
    String institutionId,
  ) async {
    final result = await _apiClient.get<List<InstitutionProject>>(
      'discover/institutions/$institutionId/projects',
      decodeData: (json) =>
          _list(json, '机构项目列表').map(InstitutionProject.fromJson).toList(),
    );
    return result ?? const [];
  }

  @override
  Future<InstitutionProject> getInstitutionProject(
    String institutionId,
    String projectId,
  ) async {
    final result = await _apiClient.get<InstitutionProject>(
      'discover/institutions/$institutionId/projects/$projectId',
      decodeData: InstitutionProject.fromJson,
    );
    if (result == null) throw const FormatException('机构项目详情 data 为空');
    return result;
  }

  @override
  Future<List<BookingDoctor>> getDoctors(String institutionProjectId) async {
    final result = await _apiClient.get<List<BookingDoctor>>(
      'discover/institution-projects/$institutionProjectId/doctors',
      decodeData: (json) =>
          _list(json, '医生列表').map(BookingDoctor.fromJson).toList(),
    );
    return result ?? const [];
  }

  @override
  Future<List<BookingConsultant>> getConsultants(
    String institutionId,
  ) async {
    final result = await _apiClient.get<List<BookingConsultant>>(
      'discover/institutions/$institutionId/consultants',
      decodeData: (json) =>
          _list(json, '机构医美顾问列表').map(BookingConsultant.fromJson).toList(),
    );
    return result ?? const [];
  }

  @override
  Future<TravelGroundServiceQuote> getTravelGroundServiceQuote({
    required String doctorId,
    required String institutionProjectId,
  }) async {
    final result = await _apiClient.get<TravelGroundServiceQuote>(
      'discover/travel-ground-service-quote',
      query: {
        'doctorId': doctorId,
        'institutionProjectId': institutionProjectId,
      },
      decodeData: TravelGroundServiceQuote.fromJson,
    );
    if (result == null) throw const FormatException('旅游地接服务费报价 data 为空');
    return result;
  }

  @override
  Future<Order> createOrder(CreateOrderCommand command) async {
    final result = await _apiClient.post<Order>(
      'orders',
      body: command.toJson(),
      decodeData: Order.fromJson,
    );
    if (result == null) throw const FormatException('创建订单响应 data 为空');
    return result;
  }
}

List<Object?> _list(Object? value, String label) {
  if (value is! List) throw FormatException('$label不是 JSON 数组');
  return value;
}
