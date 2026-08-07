import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';

enum PaymentType {
  consultationFee('CONSULTATION_FEE'),
  balance('BALANCE'),
  unknown('UNKNOWN');

  const PaymentType(this.wireValue);

  final String wireValue;

  static PaymentType fromWire(Object? value) => _enumFromWire(
        values,
        value,
        (item) => item.wireValue,
        unknown,
      );
}

enum PaymentProvider {
  demo('DEMO'),
  stripe('STRIPE'),
  paypal('PAYPAL'),
  wechatPay('WECHAT_PAY'),
  alipay('ALIPAY'),
  unknown('UNKNOWN');

  const PaymentProvider(this.wireValue);

  final String wireValue;

  static PaymentProvider fromWire(Object? value) => _enumFromWire(
        values,
        value,
        (item) => item.wireValue,
        unknown,
      );
}

enum PaymentStatus {
  created('CREATED'),
  requiresAction('REQUIRES_ACTION'),
  processing('PROCESSING'),
  succeeded('SUCCEEDED'),
  failed('FAILED'),
  cancelled('CANCELLED'),
  expired('EXPIRED'),
  partiallyRefunded('PARTIALLY_REFUNDED'),
  refunded('REFUNDED'),
  unknown('UNKNOWN');

  const PaymentStatus(this.wireValue);

  final String wireValue;

  bool get isSuccessful => this == succeeded;

  bool get isTerminal => const {
        succeeded,
        failed,
        cancelled,
        expired,
        partiallyRefunded,
        refunded,
      }.contains(this);

  bool get shouldPoll =>
      this == created || this == processing || this == unknown;

  static PaymentStatus fromWire(Object? value) => _enumFromWire(
        values,
        value,
        (item) => item.wireValue,
        unknown,
      );
}

enum PaymentNextActionType {
  stripeClientSecret('STRIPE_CLIENT_SECRET'),
  redirect('REDIRECT'),
  wechatSdkParams('WECHAT_SDK_PARAMS'),
  alipayOrderString('ALIPAY_ORDER_STRING'),
  unknown('UNKNOWN');

  const PaymentNextActionType(this.wireValue);

  final String wireValue;

  static PaymentNextActionType fromWire(Object? value) => _enumFromWire(
        values,
        value,
        (item) => item.wireValue,
        unknown,
      );
}

@immutable
final class PaymentNextAction {
  const PaymentNextAction({
    required this.type,
    this.clientSecret,
    this.url,
    this.params,
    this.orderString,
  });

  final PaymentNextActionType type;
  final String? clientSecret;
  final String? url;
  final Map<String, String>? params;
  final String? orderString;

  factory PaymentNextAction.fromJson(Object? json) {
    final map = jsonMap(json, '支付下一步操作');
    return PaymentNextAction(
      type: PaymentNextActionType.fromWire(map['type']),
      clientSecret: nullableString(map['clientSecret']),
      url: nullableString(map['url']),
      params: _stringMap(map['params']),
      orderString: nullableString(map['orderString']),
    );
  }
}

@immutable
final class PaymentAttempt {
  const PaymentAttempt({
    required this.id,
    required this.orderId,
    required this.paymentType,
    required this.provider,
    required this.currency,
    required this.status,
    required this.createdAt,
    this.paymentMethod,
    this.amountMinor,
    this.providerPaymentId,
    this.failureCode,
    this.failureMessage,
    this.nextAction,
    this.expiresAt,
    this.updatedAt,
  });

  final String id;
  final String orderId;
  final PaymentType paymentType;
  final PaymentProvider provider;
  final String? paymentMethod;
  final String currency;
  final int? amountMinor;
  final PaymentStatus status;
  final String? providerPaymentId;
  final String? failureCode;
  final String? failureMessage;
  final PaymentNextAction? nextAction;
  final DateTime? expiresAt;
  final DateTime createdAt;
  final DateTime? updatedAt;

  bool get requiresAction => status == PaymentStatus.requiresAction;

  factory PaymentAttempt.fromJson(Object? json) {
    final map = jsonMap(json, '支付尝试');
    return PaymentAttempt(
      id: requiredString(map, 'id', '支付尝试'),
      orderId: requiredString(map, 'orderId', '支付尝试'),
      paymentType: PaymentType.fromWire(map['paymentType']),
      provider: PaymentProvider.fromWire(map['provider']),
      paymentMethod: nullableString(map['paymentMethod']),
      currency: requiredString(map, 'currency', '支付尝试').toUpperCase(),
      amountMinor: _nullableInt(map['amountMinor']),
      status: PaymentStatus.fromWire(map['status']),
      providerPaymentId: nullableString(map['providerPaymentId']),
      failureCode: nullableString(map['failureCode']),
      failureMessage: nullableString(map['failureMessage']),
      nextAction: map['nextAction'] == null
          ? null
          : PaymentNextAction.fromJson(map['nextAction']),
      expiresAt: localDateTime(map['expiresAt']),
      createdAt: requiredLocalDateTime(map['createdAt'], '支付创建时间'),
      updatedAt: localDateTime(map['updatedAt']),
    );
  }
}

T _enumFromWire<T>(
  Iterable<T> values,
  Object? value,
  String Function(T item) wireValue,
  T fallback,
) {
  final raw = value?.toString().trim().toUpperCase() ?? '';
  return values.firstWhere(
    (item) => wireValue(item) == raw,
    orElse: () => fallback,
  );
}

int? _nullableInt(Object? value) {
  if (value == null) return null;
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value.toString());
}

Map<String, String>? _stringMap(Object? value) {
  if (value == null) return null;
  if (value is! Map) throw const FormatException('支付渠道参数不是 JSON 对象');
  final result = <String, String>{};
  for (final entry in value.entries) {
    if (entry.key is! String || entry.value is! String) {
      throw const FormatException('支付渠道参数包含无效字段');
    }
    result[entry.key as String] = entry.value as String;
  }
  return Map.unmodifiable(result);
}
