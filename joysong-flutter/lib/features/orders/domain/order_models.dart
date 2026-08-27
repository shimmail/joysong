import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/features/orders/domain/money.dart';

enum OrderStatus {
  pendingServiceFee('PENDING_SERVICE_FEE', '待支付旅游地接服务费'),
  serviceActive('SERVICE_ACTIVE', '旅游地接服务中'),
  refundReview('REFUND_REVIEW', '退款审核中'),
  refundProcessing('REFUND_PROCESSING', '退款处理中'),
  pendingPayment('PENDING_PAYMENT', '待支付面诊金'),
  consultationPaid('CONSULTATION_PAID', '待到店核销'),
  verified('VERIFIED', '待支付尾款'),
  balancePaid('BALANCE_PAID', '待完成核销'),
  pendingCompletion('PENDING_COMPLETION', '待确认完成'),
  completed('COMPLETED', '已完成'),
  pendingSettlement('PENDING_SETTLEMENT', '待结算'),
  settled('SETTLED', '已结算'),
  disputeMediation('DISPUTE_MEDIATION', '纠纷调解中'),
  cancelled('CANCELLED', '已取消'),
  refunded('REFUNDED', '已退款'),
  unknown('UNKNOWN', '未知状态');

  const OrderStatus(this.wireValue, this.label);

  final String wireValue;
  final String label;

  static OrderStatus fromWire(Object? value) {
    final raw = value?.toString().trim().toUpperCase() ?? '';
    return values.firstWhere(
      (status) => status.wireValue == raw,
      orElse: () => unknown,
    );
  }
}

enum OrderPaymentFlow {
  travelGroundServiceOnly('TRAVEL_GROUND_SERVICE_ONLY'),
  legacyMedical('LEGACY_MEDICAL'),
  unknown('UNKNOWN');

  const OrderPaymentFlow(this.wireValue);

  final String wireValue;

  static OrderPaymentFlow fromWire(Object? value) {
    final raw = value?.toString().trim().toUpperCase() ?? '';
    return values.firstWhere(
      (flow) => flow.wireValue == raw,
      orElse: () => unknown,
    );
  }
}

enum RefundStatus {
  none('NONE', '未申请'),
  pending('PENDING', '退款审核中'),
  processing('REFUND_PROCESSING', '退款处理中'),
  approved('APPROVED', '退款已批准'),
  rejected('REJECTED', '退款被拒绝'),
  cancelled('CANCELLED', '退款已取消'),
  unknown('UNKNOWN', '退款状态未知');

  const RefundStatus(this.wireValue, this.label);

  final String wireValue;
  final String label;

  static RefundStatus fromWire(Object? value) {
    final raw = value?.toString().trim().toUpperCase() ?? 'NONE';
    return values.firstWhere(
      (status) => status.wireValue == raw,
      orElse: () => unknown,
    );
  }
}

@immutable
final class Order {
  const Order({
    required this.id,
    required this.orderNo,
    required this.projectId,
    required this.institutionProjectId,
    required this.institutionId,
    this.consultantId = '',
    required this.doctorId,
    required this.projectName,
    required this.institutionName,
    this.consultantName = '',
    this.consultantAvatar,
    required this.doctorName,
    required this.coverImage,
    required this.amount,
    required this.paidAmount,
    required this.discountAmount,
    required this.consultationFee,
    required this.remainingAmount,
    required this.refundAmount,
    required this.status,
    this.paymentFlow = OrderPaymentFlow.legacyMedical,
    this.currency = 'USD',
    this.medicalListPriceMinor,
    this.platformServiceRateBps,
    this.travelGroundServiceFeeMinor,
    this.pricingPolicyRevision,
    this.consultantBound = false,
    this.serviceActivated = false,
    this.consultantDetailsVisible = false,
    this.serviceConversationReadable = false,
    this.serviceMessagingEnabled = false,
    required this.refundStatus,
    required this.quantity,
    required this.remark,
    required this.verifyCode,
    required this.hasReview,
    required this.createdAt,
    this.appointmentTime,
    this.paymentTime,
    this.completedAt,
  });

  final String id;
  final String orderNo;
  final String projectId;
  final String institutionProjectId;
  final String institutionId;
  final String consultantId;
  final String doctorId;
  final String projectName;
  final String institutionName;
  final String consultantName;
  final String? consultantAvatar;
  final String doctorName;
  final String coverImage;
  final Money amount;
  final Money paidAmount;
  final Money discountAmount;
  final Money consultationFee;
  final Money remainingAmount;
  final Money refundAmount;
  final OrderStatus status;
  final OrderPaymentFlow paymentFlow;
  final String currency;
  final int? medicalListPriceMinor;
  final int? platformServiceRateBps;
  final int? travelGroundServiceFeeMinor;
  final String? pricingPolicyRevision;
  final bool consultantBound;
  final bool serviceActivated;
  final bool consultantDetailsVisible;
  final bool serviceConversationReadable;
  final bool serviceMessagingEnabled;
  final RefundStatus refundStatus;
  final int quantity;
  final String remark;
  final String? verifyCode;
  final bool hasReview;
  final DateTime createdAt;
  final DateTime? appointmentTime;
  final DateTime? paymentTime;
  final DateTime? completedAt;

  bool get isTravelGroundServiceOnly =>
      paymentFlow == OrderPaymentFlow.travelGroundServiceOnly;
  bool get canPayTravelGroundServiceFee =>
      isTravelGroundServiceOnly && status == OrderStatus.pendingServiceFee;
  bool get canOpenServiceConversation => serviceConversationReadable;
  bool get canPayConsultation =>
      !isTravelGroundServiceOnly && status == OrderStatus.pendingPayment;
  bool get canCancel => const {
        OrderStatus.pendingPayment,
        OrderStatus.pendingServiceFee,
      }.contains(status);
  bool get canRequestVerificationCode => status == OrderStatus.consultationPaid;
  bool get canPayBalance => status == OrderStatus.verified;
  bool get showsCompletionCode => status == OrderStatus.balancePaid;
  bool get canConfirmCompletion => isTravelGroundServiceOnly
      ? status == OrderStatus.serviceActive
      : status == OrderStatus.pendingCompletion;
  bool get canRequestRefund {
    final hasActiveRefund = const {
      RefundStatus.pending,
      RefundStatus.processing,
      RefundStatus.approved,
    }.contains(refundStatus);
    if (hasActiveRefund) return false;
    if (isTravelGroundServiceOnly) {
      return const {
        OrderStatus.serviceActive,
        OrderStatus.completed,
      }.contains(status);
    }
    return const {
      OrderStatus.consultationPaid,
      OrderStatus.verified,
      OrderStatus.balancePaid,
      OrderStatus.pendingCompletion,
      OrderStatus.completed,
    }.contains(status);
  }

  bool get canCancelRefund => refundStatus == RefundStatus.pending;
  bool get canDelete {
    if (isTravelGroundServiceOnly && status == OrderStatus.completed) {
      return false;
    }
    return const {
      OrderStatus.cancelled,
      OrderStatus.completed,
      OrderStatus.pendingSettlement,
      OrderStatus.settled,
      OrderStatus.refunded,
    }.contains(status);
  }

  Order copyWith({
    OrderStatus? status,
    RefundStatus? refundStatus,
    bool? serviceMessagingEnabled,
  }) =>
      Order(
        id: id,
        orderNo: orderNo,
        projectId: projectId,
        institutionProjectId: institutionProjectId,
        institutionId: institutionId,
        consultantId: consultantId,
        doctorId: doctorId,
        projectName: projectName,
        institutionName: institutionName,
        consultantName: consultantName,
        consultantAvatar: consultantAvatar,
        doctorName: doctorName,
        coverImage: coverImage,
        amount: amount,
        paidAmount: paidAmount,
        discountAmount: discountAmount,
        consultationFee: consultationFee,
        remainingAmount: remainingAmount,
        refundAmount: refundAmount,
        status: status ?? this.status,
        paymentFlow: paymentFlow,
        currency: currency,
        medicalListPriceMinor: medicalListPriceMinor,
        platformServiceRateBps: platformServiceRateBps,
        travelGroundServiceFeeMinor: travelGroundServiceFeeMinor,
        pricingPolicyRevision: pricingPolicyRevision,
        consultantBound: consultantBound,
        serviceActivated: serviceActivated,
        consultantDetailsVisible: consultantDetailsVisible,
        serviceConversationReadable: serviceConversationReadable,
        serviceMessagingEnabled:
            serviceMessagingEnabled ?? this.serviceMessagingEnabled,
        refundStatus: refundStatus ?? this.refundStatus,
        quantity: quantity,
        remark: remark,
        verifyCode: verifyCode,
        hasReview: hasReview,
        createdAt: createdAt,
        appointmentTime: appointmentTime,
        paymentTime: paymentTime,
        completedAt: completedAt,
      );

  factory Order.fromJson(Object? json) {
    final map = jsonMap(json, '订单');
    return Order(
      id: requiredString(map, 'id', '订单'),
      orderNo: stringValue(map['orderNo']),
      projectId: requiredString(map, 'projectId', '订单'),
      institutionProjectId: stringValue(map['institutionProjectId']),
      institutionId: stringValue(map['institutionId']),
      consultantId: stringValue(map['consultantId']),
      doctorId: stringValue(map['doctorId']),
      projectName: stringValue(map['projectName'], fallback: '项目'),
      institutionName: stringValue(map['institutionName']),
      consultantName: stringValue(map['consultantName']),
      consultantAvatar: nullableString(map['consultantAvatar']),
      doctorName: stringValue(map['doctorName']),
      coverImage: stringValue(map['coverImage']),
      amount: Money.parse(map['amount'] ?? map['price'], field: '订单金额'),
      paidAmount: Money.fromJsonOrZero(map['paidAmount'], field: '已付金额'),
      discountAmount:
          Money.fromJsonOrZero(map['discountAmount'], field: '优惠金额'),
      consultationFee:
          Money.fromJsonOrZero(map['consultationFee'], field: '面诊金'),
      remainingAmount:
          Money.fromJsonOrZero(map['remainingAmount'], field: '尾款'),
      refundAmount: Money.fromJsonOrZero(map['refundAmount'], field: '退款金额'),
      status: OrderStatus.fromWire(map['status']),
      paymentFlow: OrderPaymentFlow.fromWire(map['paymentFlow']),
      currency: stringValue(map['currency'], fallback: 'USD').toUpperCase(),
      medicalListPriceMinor: nullableInt(map['medicalListPriceMinor']),
      platformServiceRateBps: nullableInt(map['platformServiceRateBps']),
      travelGroundServiceFeeMinor:
          nullableInt(map['travelGroundServiceFeeMinor']),
      pricingPolicyRevision: nullableString(map['pricingPolicyRevision']),
      consultantBound: map['consultantBound'] == true,
      serviceActivated: map['serviceActivated'] == true,
      consultantDetailsVisible: map['consultantDetailsVisible'] == true,
      serviceConversationReadable: map['serviceConversationReadable'] == true,
      serviceMessagingEnabled: map['serviceMessagingEnabled'] == true,
      refundStatus: RefundStatus.fromWire(map['refundStatus']),
      quantity: intValue(map['quantity'], fallback: 1),
      remark: stringValue(map['remark']),
      verifyCode: nullableString(map['verifyCode']),
      hasReview: map['hasReview'] == true,
      createdAt: requiredLocalDateTime(map['createdAt'], '订单创建时间'),
      appointmentTime: localDateTime(map['appointmentTime']),
      paymentTime: localDateTime(map['paymentTime']),
      completedAt: localDateTime(map['completedAt']),
    );
  }
}

@immutable
final class RefundDetail {
  const RefundDetail({
    required this.id,
    required this.orderId,
    required this.amount,
    required this.reason,
    required this.description,
    required this.status,
    required this.createdAt,
    this.processedAt,
    this.rejectReason,
  });

  final String id;
  final String orderId;
  final Money amount;
  final String reason;
  final String description;
  final RefundStatus status;
  final DateTime createdAt;
  final DateTime? processedAt;
  final String? rejectReason;

  factory RefundDetail.fromJson(Object? json) {
    final map = jsonMap(json, '退款详情');
    return RefundDetail(
      id: requiredString(map, 'id', '退款详情'),
      orderId: requiredString(map, 'orderId', '退款详情'),
      amount: Money.parse(map['amount'], field: '退款金额'),
      reason: stringValue(map['reason']),
      description: stringValue(map['description']),
      status: RefundStatus.fromWire(map['status']),
      createdAt: requiredLocalDateTime(map['createdAt'], '退款申请时间'),
      processedAt: localDateTime(map['processedAt']),
      rejectReason: nullableString(map['rejectReason']),
    );
  }
}

@immutable
final class OrderStatusLog {
  const OrderStatusLog({
    required this.id,
    required this.fromStatus,
    required this.toStatus,
    required this.operatorType,
    required this.remark,
    required this.createdAt,
  });

  final int id;
  final OrderStatus fromStatus;
  final OrderStatus toStatus;
  final String operatorType;
  final String remark;
  final DateTime createdAt;

  factory OrderStatusLog.fromJson(Object? json) {
    final map = jsonMap(json, '订单状态日志');
    return OrderStatusLog(
      id: intValue(map['id']),
      fromStatus: OrderStatus.fromWire(map['fromStatus']),
      toStatus: OrderStatus.fromWire(map['toStatus']),
      operatorType: stringValue(map['operatorType']),
      remark: stringValue(map['remark']),
      createdAt: requiredLocalDateTime(map['createdAt'], '状态变更时间'),
    );
  }
}

@immutable
final class Settlement {
  const Settlement({
    required this.settlementId,
    required this.orderId,
    required this.currency,
    required this.grossTotalPaidMinor,
    required this.netSettledMinor,
    required this.state,
    required this.settlementCreatedAt,
    this.settlementDueAt,
    this.releasedAt,
  });

  final int settlementId;
  final String orderId;
  final String currency;
  final int grossTotalPaidMinor;
  final int netSettledMinor;
  final String state;
  final DateTime? settlementDueAt;
  final DateTime settlementCreatedAt;
  final DateTime? releasedAt;

  factory Settlement.fromJson(Object? json) {
    final map = jsonMap(json, '结算详情');
    final currency = requiredString(map, 'currency', '结算详情');
    return Settlement(
      settlementId: requiredInt(map, 'settlementId', '结算详情'),
      orderId: requiredString(map, 'orderId', '结算详情'),
      currency: currency,
      grossTotalPaidMinor: _settlementMoneyMinor(
        map['grossTotalPaid'],
        currency: currency,
        field: '结算总额',
      ),
      netSettledMinor: _settlementMoneyMinor(
        map['netSettled'],
        currency: currency,
        field: '净结算额',
      ),
      state: requiredString(map, 'state', '结算详情'),
      settlementDueAt: localDateTime(map['settlementDueAt']),
      settlementCreatedAt:
          requiredLocalDateTime(map['settlementCreatedAt'], '结算创建时间'),
      releasedAt: localDateTime(map['releasedAt']),
    );
  }
}

int _settlementMoneyMinor(
  Object? value, {
  required String currency,
  required String field,
}) {
  final map = jsonMap(value, field);
  if (requiredString(map, 'currency', field) != currency) {
    throw FormatException('$field币种不匹配');
  }
  return requiredInt(map, 'minor', field);
}

Map<String, dynamic> jsonMap(Object? value, String label) {
  if (value is! Map) throw FormatException('$label不是 JSON 对象');
  try {
    return value.cast<String, dynamic>();
  } on TypeError {
    throw FormatException('$label包含无效字段');
  }
}

String requiredString(Map<String, dynamic> map, String key, String label) {
  final value = map[key]?.toString().trim() ?? '';
  if (value.isEmpty) throw FormatException('$label缺少 $key');
  return value;
}

String stringValue(Object? value, {String fallback = ''}) {
  final result = value?.toString() ?? '';
  return result.isEmpty ? fallback : result;
}

String? nullableString(Object? value) {
  final result = value?.toString().trim() ?? '';
  return result.isEmpty ? null : result;
}

int intValue(Object? value, {int fallback = 0}) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '') ?? fallback;
}

int requiredInt(Map<String, dynamic> map, String key, String label) {
  final value = map[key];
  if (value is int) return value;
  if (value is num && value == value.toInt()) return value.toInt();
  final parsed = int.tryParse(value?.toString() ?? '');
  if (parsed == null) throw FormatException('$label缺少有效的 $key');
  return parsed;
}

int? nullableInt(Object? value) {
  if (value == null) return null;
  if (value is int) return value;
  if (value is num && value == value.toInt()) return value.toInt();
  return int.tryParse(value.toString());
}

DateTime? localDateTime(Object? value) {
  final raw = value?.toString().trim() ?? '';
  return raw.isEmpty ? null : DateTime.tryParse(raw);
}

DateTime requiredLocalDateTime(Object? value, String label) {
  final result = localDateTime(value);
  if (result == null) throw FormatException('$label无效');
  return result;
}
