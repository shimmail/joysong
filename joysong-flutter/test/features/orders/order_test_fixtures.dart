import 'package:joysong_flutter/features/orders/domain/money.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/domain/orders_repository.dart';

Order sampleOrder({
  String id = 'order-1',
  OrderStatus status = OrderStatus.pendingPayment,
  RefundStatus refundStatus = RefundStatus.none,
  String? verifyCode,
}) =>
    Order(
      id: id,
      orderNo: 'JOY202608060001',
      projectId: 'project-1',
      institutionProjectId: 'ip-1',
      institutionId: 'institution-1',
      doctorId: 'doctor-1',
      projectName: '光子嫩肤',
      institutionName: '娇颜颂医疗美容',
      doctorName: '张医生',
      coverImage: '',
      amount: Money.parse('1280.50'),
      paidAmount: Money.zero,
      discountAmount: Money.zero,
      consultationFee: Money.parse('100'),
      remainingAmount: Money.parse('1180.50'),
      refundAmount: Money.zero,
      status: status,
      refundStatus: refundStatus,
      quantity: 1,
      remark: '',
      verifyCode: verifyCode,
      hasReview: false,
      createdAt: DateTime(2026, 8, 6, 10),
      appointmentTime: DateTime(2026, 8, 8, 14, 30),
    );

Map<String, Object?> sampleOrderJson({
  String id = 'order-1',
  String status = 'PENDING_PAYMENT',
  String? verifyCode,
}) =>
    {
      'id': id,
      'orderNo': 'JOY202608060001',
      'userId': 'user-1',
      'projectId': 'project-1',
      'institutionId': 'institution-1',
      'doctorId': 'doctor-1',
      'institutionProjectId': 'ip-1',
      'projectName': '光子嫩肤',
      'institutionName': '娇颜颂医疗美容',
      'coverImage': '',
      'amount': '1280.50',
      'paidAmount': 0,
      'discountAmount': '20.10',
      'status': status,
      'quantity': 1,
      'remark': '',
      'consultationFee': 100,
      'remainingAmount': '1180.50',
      'verifyCode': verifyCode,
      'hasReview': false,
      'refundStatus': 'NONE',
      'refundAmount': 0,
      'doctorName': '张医生',
      'createdAt': '2026-08-06T10:00:00',
      'appointmentTime': '2026-08-08T14:30:00',
    };

class FakeOrdersRepository implements OrdersRepository {
  List<Order> orders = [sampleOrder()];
  int getOrdersCalls = 0;
  int actionCalls = 0;
  int? lastOffset;
  int? lastLimit;
  OrderStatus? lastStatus;
  Future<Order>? actionFuture;
  String? lastRefundReason;
  String? lastRefundDescription;
  String? lastRefundEvidenceUrl;

  @override
  Future<List<Order>> getOrders({
    OrderStatus? status,
    int offset = 0,
    int limit = 20,
  }) async {
    getOrdersCalls += 1;
    lastOffset = offset;
    lastLimit = limit;
    lastStatus = status;
    return orders.skip(offset).take(limit).toList();
  }

  @override
  Future<Order> getOrder(String id) async =>
      orders.firstWhere((order) => order.id == id);

  @override
  Future<Order> payConsultation(String id) {
    actionCalls += 1;
    return actionFuture ??
        Future.value(sampleOrder(status: OrderStatus.consultationPaid));
  }

  @override
  Future<Order> requestVerificationCode(String id) async {
    actionCalls += 1;
    return sampleOrder(
      status: OrderStatus.consultationPaid,
      verifyCode: '123456',
    );
  }

  @override
  Future<Order> payBalance(String id) async {
    actionCalls += 1;
    return sampleOrder(status: OrderStatus.balancePaid, verifyCode: '654321');
  }

  @override
  Future<Order> confirmCompletion(String id) async {
    actionCalls += 1;
    return sampleOrder(status: OrderStatus.completed);
  }

  @override
  Future<void> cancelOrder(String id) async {
    actionCalls += 1;
  }

  @override
  Future<RefundDetail> requestRefund(
    String id, {
    required String reason,
    String description = '',
    String evidenceUrl = '',
  }) async {
    actionCalls += 1;
    lastRefundReason = reason;
    lastRefundDescription = description;
    lastRefundEvidenceUrl = evidenceUrl;
    return RefundDetail(
      id: 'refund-1',
      orderId: id,
      amount: Money.parse('100'),
      reason: reason,
      description: description,
      status: RefundStatus.pending,
      createdAt: DateTime(2026, 8, 6),
    );
  }

  @override
  Future<RefundDetail> getRefund(String id) async => RefundDetail(
        id: 'refund-1',
        orderId: id,
        amount: Money.parse('100'),
        reason: '行程变化',
        description: '',
        status: RefundStatus.pending,
        createdAt: DateTime(2026, 8, 6),
      );

  @override
  Future<void> cancelRefund(String id) async {
    actionCalls += 1;
  }

  @override
  Future<void> deleteOrder(String id) async {
    actionCalls += 1;
  }

  @override
  Future<List<OrderStatusLog>> getStatusLogs(String id) async => const [];

  @override
  Future<Settlement> getSettlement(String id) async => Settlement(
        id: 1,
        orderId: id,
        totalAmount: Money.parse('1280.50'),
        status: 'PENDING',
        createdAt: DateTime(2026, 8, 6),
      );
}
