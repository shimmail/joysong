import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/domain/payment_models.dart';

abstract interface class OrdersRepository {
  Future<List<Order>> getOrders({OrderStatus? status, int offset, int limit});

  Future<Order> getOrder(String id);

  Future<PaymentAttempt> createPaymentAttempt(
    String orderId, {
    required PaymentType paymentType,
    required PaymentProvider provider,
    required String paymentMethod,
    required String idempotencyKey,
  });

  Future<PaymentAttempt> getPayment(
    String paymentId, {
    bool refresh = false,
  });

  Future<PaymentAttempt> confirmPayment(
    String paymentId, {
    required String idempotencyKey,
  });

  Future<PaymentAttempt> getLatestPayment(
    String orderId, {
    required PaymentType paymentType,
    bool refresh = false,
  });

  Future<Order> payConsultation(String id);

  Future<Order> requestVerificationCode(String id);

  Future<Order> payBalance(String id);

  Future<Order> confirmCompletion(String id);

  Future<void> cancelOrder(String id);

  Future<RefundDetail> requestRefund(
    String id, {
    required String reason,
    String description,
    String evidenceUrl,
  });

  Future<RefundDetail> getRefund(String id);

  Future<void> cancelRefund(String id);

  Future<void> deleteOrder(String id);

  Future<List<OrderStatusLog>> getStatusLogs(String id);

  Future<Settlement> getSettlement(String id);
}
