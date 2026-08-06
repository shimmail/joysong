import 'package:joysong_flutter/features/orders/data/orders_remote_data_source.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/domain/orders_repository.dart';

final class OrdersRepositoryImpl implements OrdersRepository {
  const OrdersRepositoryImpl(this._remote);

  final OrdersRemoteDataSource _remote;

  @override
  Future<List<Order>> getOrders({
    OrderStatus? status,
    int offset = 0,
    int limit = 20,
  }) =>
      _remote.getOrders(
        status: status == null || status == OrderStatus.unknown
            ? null
            : status.wireValue,
        offset: offset,
        limit: limit,
      );

  @override
  Future<Order> getOrder(String id) => _remote.getOrder(id);

  @override
  Future<Order> payConsultation(String id) => _remote.payConsultation(id);

  @override
  Future<Order> requestVerificationCode(String id) =>
      _remote.requestVerificationCode(id);

  @override
  Future<Order> payBalance(String id) => _remote.payBalance(id);

  @override
  Future<Order> confirmCompletion(String id) => _remote.confirmCompletion(id);

  @override
  Future<void> cancelOrder(String id) => _remote.cancelOrder(id);

  @override
  Future<RefundDetail> requestRefund(
    String id, {
    required String reason,
    String description = '',
    String evidenceUrl = '',
  }) =>
      _remote.requestRefund(
        id,
        reason: reason,
        description: description,
        evidenceUrl: evidenceUrl,
      );

  @override
  Future<RefundDetail> getRefund(String id) => _remote.getRefund(id);

  @override
  Future<void> cancelRefund(String id) => _remote.cancelRefund(id);

  @override
  Future<void> deleteOrder(String id) => _remote.deleteOrder(id);

  @override
  Future<List<OrderStatusLog>> getStatusLogs(String id) =>
      _remote.getStatusLogs(id);

  @override
  Future<Settlement> getSettlement(String id) => _remote.getSettlement(id);
}
