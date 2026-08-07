Map<String, Object?> paymentAttemptJson({
  String paymentType = 'CONSULTATION_FEE',
  String provider = 'STRIPE',
  String status = 'CREATED',
  Object? nextAction,
}) =>
    {
      'id': 'payment-1',
      'orderId': 'order-1',
      'paymentType': paymentType,
      'provider': provider,
      'paymentMethod': 'CARD',
      'currency': 'CNY',
      'amountMinor': 10000,
      'status': status,
      'providerPaymentId': 'provider-payment-1',
      'failureCode': null,
      'failureMessage': null,
      'nextAction': nextAction,
      'expiresAt': null,
      'createdAt': '2026-08-07T12:00:00',
      'updatedAt': '2026-08-07T12:00:01',
    };
