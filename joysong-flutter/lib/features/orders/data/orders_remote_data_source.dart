import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/public_media_url.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/domain/payment_models.dart';

abstract interface class OrdersRemoteDataSource {
  Future<List<Order>> getOrders({String? status, int offset, int limit});

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

  Future<Order> requestVerificationCode(String id);

  Future<Order> confirmCompletion(String id);

  Future<void> cancelOrder(String id);

  Future<RefundDetail> requestRefund(
    String id, {
    required String reason,
    required String description,
    required String evidenceUrl,
  });

  Future<RefundDetail> getRefund(String id);

  Future<void> cancelRefund(String id);

  Future<void> deleteOrder(String id);

  Future<List<OrderStatusLog>> getStatusLogs(String id);

  Future<Settlement> getSettlement(String id);
}

final class ApiOrdersRemoteDataSource implements OrdersRemoteDataSource {
  ApiOrdersRemoteDataSource(this._apiClient)
      : _mediaResolver = ApiPublicMediaUrlResolver(_apiClient.apiRoot);

  final ApiClient _apiClient;
  final PublicMediaUrlResolver _mediaResolver;

  @override
  Future<List<Order>> getOrders({
    String? status,
    int offset = 0,
    int limit = 20,
  }) async {
    final result = await _apiClient.get<List<Order>>(
      'orders',
      query: {'status': status, 'offset': offset, 'limit': limit},
      decodeData: (json) => _list(json, '订单列表').map(_resolvedOrder).toList(),
    );
    return result ?? const [];
  }

  @override
  Future<Order> getOrder(String id) => _orderPostOrGet(id, method: 'GET');

  @override
  Future<PaymentAttempt> createPaymentAttempt(
    String orderId, {
    required PaymentType paymentType,
    required PaymentProvider provider,
    required String paymentMethod,
    required String idempotencyKey,
  }) async {
    final result = await _apiClient.postIdempotent<PaymentAttempt>(
      'orders/$orderId/payment-attempts',
      idempotencyKey: idempotencyKey,
      body: {
        'paymentType': paymentType.wireValue,
        'provider': provider.wireValue,
        'paymentMethod': paymentMethod,
      },
      decodeData: PaymentAttempt.fromJson,
    );
    return _requiredPayment(result);
  }

  @override
  Future<PaymentAttempt> getPayment(
    String paymentId, {
    bool refresh = false,
  }) async {
    final result = await _apiClient.get<PaymentAttempt>(
      'payments/$paymentId',
      query: {'refresh': refresh},
      decodeData: PaymentAttempt.fromJson,
    );
    return _requiredPayment(result);
  }

  @override
  Future<PaymentAttempt> confirmPayment(
    String paymentId, {
    required String idempotencyKey,
  }) async {
    final result = await _apiClient.postIdempotent<PaymentAttempt>(
      'payments/$paymentId/confirm',
      idempotencyKey: idempotencyKey,
      decodeData: PaymentAttempt.fromJson,
    );
    return _requiredPayment(result);
  }

  @override
  Future<PaymentAttempt> getLatestPayment(
    String orderId, {
    required PaymentType paymentType,
    bool refresh = false,
  }) async {
    final result = await _apiClient.get<PaymentAttempt>(
      'orders/$orderId/payments/latest',
      query: {
        'paymentType': paymentType.wireValue,
        'refresh': refresh,
      },
      decodeData: PaymentAttempt.fromJson,
    );
    return _requiredPayment(result);
  }

  @override
  Future<Order> requestVerificationCode(String id) =>
      _orderPostOrGet('$id/verification-code');

  @override
  Future<Order> confirmCompletion(String id) =>
      _orderPostOrGet('$id/confirm-completion');

  @override
  Future<void> cancelOrder(String id) => _unitPost('$id/cancel');

  @override
  Future<RefundDetail> requestRefund(
    String id, {
    required String reason,
    required String description,
    required String evidenceUrl,
  }) async {
    final result = await _apiClient.post<RefundDetail>(
      'orders/$id/refund',
      body: {
        'reason': reason,
        'description': description,
        'evidenceUrl': evidenceUrl,
      },
      decodeData: RefundDetail.fromJson,
    );
    if (result == null) throw const FormatException('退款响应 data 为空');
    return result;
  }

  @override
  Future<RefundDetail> getRefund(String id) async {
    final result = await _apiClient.get<RefundDetail>(
      'orders/$id/refund',
      decodeData: RefundDetail.fromJson,
    );
    if (result == null) throw const FormatException('退款详情 data 为空');
    return result;
  }

  @override
  Future<void> cancelRefund(String id) => _unitPost('$id/cancel-refund');

  @override
  Future<void> deleteOrder(String id) async {
    await _apiClient.delete<Object?>(
      'orders/$id',
      decodeData: (json) => json,
    );
  }

  @override
  Future<List<OrderStatusLog>> getStatusLogs(String id) async {
    final result = await _apiClient.get<List<OrderStatusLog>>(
      'orders/$id/status-logs',
      decodeData: (json) =>
          _list(json, '状态日志').map(OrderStatusLog.fromJson).toList(),
    );
    return result ?? const [];
  }

  @override
  Future<Settlement> getSettlement(String id) async {
    final result = await _apiClient.get<Settlement>(
      'orders/$id/settlement',
      decodeData: Settlement.fromJson,
    );
    if (result == null) throw const FormatException('结算详情 data 为空');
    return result;
  }

  Future<Order> _orderPostOrGet(String suffix, {String method = 'POST'}) async {
    final path = 'orders/$suffix';
    final result = method == 'GET'
        ? await _apiClient.get<Order>(path, decodeData: _resolvedOrder)
        : await _apiClient.post<Order>(path, decodeData: _resolvedOrder);
    if (result == null) throw const FormatException('订单响应 data 为空');
    return result;
  }

  Future<void> _unitPost(String suffix) async {
    await _apiClient.post<Object?>(
      'orders/$suffix',
      decodeData: (json) => json,
    );
  }

  Order _resolvedOrder(Object? json) => Order.fromJson(
        resolvePublicMediaUrlsInJson(json, resolver: _mediaResolver),
      );

  PaymentAttempt _requiredPayment(PaymentAttempt? payment) {
    if (payment == null) {
      throw const FormatException('支付响应 data 为空');
    }
    return payment;
  }
}

List<Object?> _list(Object? value, String label) {
  if (value is! List) throw FormatException('$label不是 JSON 数组');
  return value;
}
