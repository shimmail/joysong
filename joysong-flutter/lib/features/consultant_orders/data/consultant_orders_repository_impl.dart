import 'package:joysong_flutter/features/consultant_orders/data/consultant_orders_remote_data_source.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_order_models.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_orders_repository.dart';

final class ConsultantOrdersRepositoryImpl
    implements ConsultantOrdersRepository {
  const ConsultantOrdersRepositoryImpl(this._remote);

  final ConsultantOrdersRemoteDataSource _remote;

  @override
  Future<ConsultantOrderPage> getOrders({
    required ConsultantOrderStage stage,
    String? institutionId,
    required int offset,
    required int limit,
  }) =>
      _remote.getOrders(
        stage: stage,
        institutionId: institutionId,
        offset: offset,
        limit: limit,
      );

  @override
  Future<ConsultantOrderDetail> getOrder(String orderId) =>
      _remote.getOrder(orderId);
}
