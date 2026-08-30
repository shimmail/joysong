import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/public_media_url.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_order_models.dart';

abstract interface class ConsultantOrdersRemoteDataSource {
  Future<ConsultantOrderPage> getOrders({
    required ConsultantOrderStage stage,
    String? institutionId,
    required int offset,
    required int limit,
  });

  Future<ConsultantOrderDetail> getOrder(String orderId);
}

final class ApiConsultantOrdersRemoteDataSource
    implements ConsultantOrdersRemoteDataSource {
  ApiConsultantOrdersRemoteDataSource(this._apiClient)
      : _mediaResolver = ApiPublicMediaUrlResolver(_apiClient.apiRoot);

  final ApiClient _apiClient;
  final PublicMediaUrlResolver _mediaResolver;

  @override
  Future<ConsultantOrderPage> getOrders({
    required ConsultantOrderStage stage,
    String? institutionId,
    required int offset,
    required int limit,
  }) async {
    final result = await _apiClient.get<ConsultantOrderPage>(
      'consultant/orders',
      query: {
        'stage': stage.wireValue,
        'institutionId': institutionId,
        'offset': offset,
        'limit': limit,
      },
      decodeData: (json) => ConsultantOrderPage.fromJson(
        resolvePublicMediaUrlsInJson(json, resolver: _mediaResolver),
      ),
    );
    if (result == null) {
      throw const FormatException('顾问订单列表 data 为空');
    }
    return result;
  }

  @override
  Future<ConsultantOrderDetail> getOrder(String orderId) async {
    final result = await _apiClient.get<ConsultantOrderDetail>(
      'consultant/orders/${Uri.encodeComponent(orderId.trim())}',
      decodeData: (json) => ConsultantOrderDetail.fromJson(
        resolvePublicMediaUrlsInJson(json, resolver: _mediaResolver),
      ),
    );
    if (result == null) {
      throw const FormatException('顾问订单详情 data 为空');
    }
    return result;
  }
}
