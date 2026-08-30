import 'package:joysong_flutter/features/consultant_orders/domain/consultant_order_models.dart';

abstract interface class ConsultantOrdersRepository {
  Future<ConsultantOrderPage> getOrders({
    required ConsultantOrderStage stage,
    String? institutionId,
    required int offset,
    required int limit,
  });

  Future<ConsultantOrderDetail> getOrder(String orderId);
}
