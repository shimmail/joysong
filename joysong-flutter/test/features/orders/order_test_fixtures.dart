import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/orders/domain/money.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/domain/orders_repository.dart';
import 'package:joysong_flutter/features/orders/domain/payment_models.dart';

Order sampleOrder({
  String id = 'order-1',
  OrderStatus status = OrderStatus.pendingPayment,
  OrderPaymentFlow paymentFlow = OrderPaymentFlow.legacyMedical,
  RefundStatus refundStatus = RefundStatus.none,
  String institutionId = 'institution-1',
  String consultantId = 'consultant-1',
  String institutionName = '娇颜颂医疗美容',
  String consultantName = '李咨询师',
  String? consultantAvatar,
  bool consultantBound = false,
  bool serviceActivated = false,
  bool consultantDetailsVisible = false,
  bool serviceConversationReadable = false,
  bool serviceMessagingEnabled = false,
  String? verifyCode,
  bool hasReview = false,
}) =>
    Order(
      id: id,
      orderNo: 'JOY202608060001',
      projectId: 'project-1',
      institutionProjectId: 'ip-1',
      institutionId: institutionId,
      consultantId: consultantId,
      doctorId: 'doctor-1',
      projectName: '光子嫩肤',
      institutionName: institutionName,
      consultantName: consultantName,
      consultantAvatar: consultantAvatar,
      doctorName: '张医生',
      coverImage: '',
      amount: Money.parse('1280.50'),
      paidAmount: Money.zero,
      discountAmount: Money.zero,
      consultationFee: Money.parse('100'),
      remainingAmount: Money.parse('1180.50'),
      refundAmount: Money.zero,
      status: status,
      paymentFlow: paymentFlow,
      medicalListPriceMinor: 100000,
      platformServiceRateBps: 4000,
      travelGroundServiceFeeMinor: 40000,
      consultantBound: consultantBound,
      serviceActivated: serviceActivated,
      consultantDetailsVisible: consultantDetailsVisible,
      serviceConversationReadable: serviceConversationReadable,
      serviceMessagingEnabled: serviceMessagingEnabled,
      refundStatus: refundStatus,
      quantity: 1,
      remark: '',
      verifyCode: verifyCode,
      hasReview: hasReview,
      createdAt: DateTime(2026, 8, 6, 10),
      appointmentTime: DateTime(2026, 8, 8, 14, 30),
    );

PaymentAttempt samplePaymentAttempt({
  String id = 'payment-1',
  String orderId = 'order-1',
  PaymentType paymentType = PaymentType.travelGroundServiceFee,
  PaymentProvider provider = PaymentProvider.alipayPlus,
  PaymentStatus status = PaymentStatus.succeeded,
  PaymentNextAction? nextAction,
  int? amountMinor = 40000,
  int refundedAmountMinor = 0,
  DateTime? expiresAt,
}) =>
    PaymentAttempt(
      id: id,
      orderId: orderId,
      paymentType: paymentType,
      provider: provider,
      paymentMethod: 'ALIPAY_PLUS_CASHIER',
      currency: 'USD',
      amountMinor: amountMinor,
      refundedAmountMinor: refundedAmountMinor,
      status: status,
      providerPaymentId: 'provider-payment-1',
      nextAction: nextAction,
      expiresAt: expiresAt,
      createdAt: DateTime(2026, 8, 7, 12),
      updatedAt: DateTime(2026, 8, 7, 12, 0, 1),
    );

Map<String, Object?> sampleOrderJson({
  String id = 'order-1',
  String status = 'PENDING_PAYMENT',
  String paymentFlow = 'LEGACY_MEDICAL',
  Object? paidAmount = 0,
  Object? institutionId = 'institution-1',
  Object? consultantId = 'consultant-1',
  Object? institutionName = '娇颜颂医疗美容',
  Object? consultantName = '李咨询师',
  Object? consultantAvatar,
  bool consultantBound = false,
  bool serviceActivated = false,
  bool consultantDetailsVisible = false,
  bool serviceConversationReadable = false,
  bool serviceMessagingEnabled = false,
  String? verifyCode,
}) =>
    {
      'id': id,
      'orderNo': 'JOY202608060001',
      'userId': 'user-1',
      'projectId': 'project-1',
      'institutionId': institutionId,
      'consultantId': consultantId,
      'doctorId': 'doctor-1',
      'institutionProjectId': 'ip-1',
      'projectName': '光子嫩肤',
      'institutionName': institutionName,
      'consultantName': consultantName,
      'consultantAvatar': consultantAvatar,
      'coverImage': '',
      'amount': '1280.50',
      'currency': 'USD',
      'paidAmount': paidAmount,
      'discountAmount': '20.10',
      'status': status,
      'paymentFlow': paymentFlow,
      'medicalListPriceMinor': 100000,
      'platformServiceRateBps': 4000,
      'travelGroundServiceFeeMinor': 40000,
      'consultantBound': consultantBound,
      'serviceActivated': serviceActivated,
      'consultantDetailsVisible': consultantDetailsVisible,
      'serviceConversationReadable': serviceConversationReadable,
      'serviceMessagingEnabled': serviceMessagingEnabled,
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
  List<Order> orderDetails = const [];
  List<Future<Order>> delayedOrderDetails = [];
  int getOrdersCalls = 0;
  int getOrderCalls = 0;
  int actionCalls = 0;
  int paymentCalls = 0;
  int settlementCalls = 0;
  int? lastOffset;
  int? lastLimit;
  OrderStatus? lastStatus;
  Future<Order>? actionFuture;
  Order? completionOrder;
  RefundDetail? refundDetail;
  PaymentAttempt? paymentAttempt;
  Object? latestPaymentError;
  Object? getOrderError;
  String? lastPaymentIdempotencyKey;
  PaymentType? lastPaymentType;
  PaymentProvider? lastPaymentProvider;
  bool? lastPaymentRefresh;
  String? lastRefundReason;
  String? lastRefundDescription;
  String? lastRefundEvidenceUrl;
  Object? settlementError;

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
  Future<Order> getOrder(String id) async {
    getOrderCalls += 1;
    if (delayedOrderDetails.isNotEmpty) {
      return delayedOrderDetails.removeAt(0);
    }
    final error = getOrderError;
    if (error != null) throw error;
    if (orderDetails.isNotEmpty) return orderDetails.removeAt(0);
    return orders.firstWhere((order) => order.id == id);
  }

  @override
  Future<PaymentAttempt> createPaymentAttempt(
    String orderId, {
    required PaymentType paymentType,
    required PaymentProvider provider,
    required String paymentMethod,
    required String idempotencyKey,
  }) async {
    paymentCalls += 1;
    lastPaymentType = paymentType;
    lastPaymentProvider = provider;
    lastPaymentIdempotencyKey = idempotencyKey;
    return paymentAttempt ??
        samplePaymentAttempt(
          orderId: orderId,
          paymentType: paymentType,
          provider: provider,
        );
  }

  @override
  Future<PaymentAttempt> createTravelGroundServicePaymentAttempt(
    String orderId, {
    required String idempotencyKey,
  }) async {
    paymentCalls += 1;
    lastPaymentType = PaymentType.travelGroundServiceFee;
    lastPaymentProvider = PaymentProvider.alipayPlus;
    lastPaymentIdempotencyKey = idempotencyKey;
    return paymentAttempt ?? samplePaymentAttempt(orderId: orderId);
  }

  @override
  Future<PaymentAttempt> getPayment(
    String paymentId, {
    bool refresh = false,
  }) async {
    paymentCalls += 1;
    lastPaymentRefresh = refresh;
    return paymentAttempt ?? samplePaymentAttempt(id: paymentId);
  }

  @override
  Future<PaymentAttempt> confirmPayment(
    String paymentId, {
    required String idempotencyKey,
  }) async {
    paymentCalls += 1;
    lastPaymentIdempotencyKey = idempotencyKey;
    return paymentAttempt ?? samplePaymentAttempt(id: paymentId);
  }

  @override
  Future<PaymentAttempt> getLatestPayment(
    String orderId, {
    required PaymentType paymentType,
    bool refresh = false,
  }) async {
    paymentCalls += 1;
    lastPaymentType = paymentType;
    lastPaymentRefresh = refresh;
    return paymentAttempt ??
        samplePaymentAttempt(orderId: orderId, paymentType: paymentType);
  }

  @override
  Future<PaymentAttempt> getLatestTravelGroundServicePayment(
    String orderId, {
    bool refresh = false,
  }) async {
    paymentCalls += 1;
    lastPaymentType = PaymentType.travelGroundServiceFee;
    lastPaymentProvider = PaymentProvider.alipayPlus;
    lastPaymentRefresh = refresh;
    final error = latestPaymentError;
    if (error != null) throw error;
    final attempt = paymentAttempt;
    if (attempt == null) {
      throw const ApiException(
        message: 'PAYMENT_NOT_FOUND',
        httpStatus: 200,
        businessCode: 404,
      );
    }
    return attempt;
  }

  @override
  Future<Order> requestVerificationCode(String id) {
    actionCalls += 1;
    return actionFuture ??
        Future.value(
          sampleOrder(
            status: OrderStatus.consultationPaid,
            verifyCode: '123456',
          ),
        );
  }

  @override
  Future<Order> confirmCompletion(String id) async {
    actionCalls += 1;
    return completionOrder ?? sampleOrder(status: OrderStatus.completed);
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
  Future<RefundDetail> getRefund(String id) async =>
      refundDetail ??
      RefundDetail(
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
  Future<Settlement> getSettlement(String id) async {
    settlementCalls += 1;
    final error = settlementError;
    if (error != null) throw error;
    return Settlement(
      settlementId: 1,
      orderId: id,
      currency: 'USD',
      grossTotalPaidMinor: 128050,
      netSettledMinor: 128050,
      state: 'PENDING_RELEASE',
      settlementCreatedAt: DateTime(2026, 8, 6),
    );
  }
}
