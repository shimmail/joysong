/// User-facing copy for the cross-platform payment flow.
///
/// Keep server error codes and provider identifiers out of widgets. Use
/// [errorMessage], [providerLabel], and [statusTitle] to translate them before
/// displaying them to the user.
final class PaymentStrings {
  const PaymentStrings({required this.isEnglish});

  const PaymentStrings.chinese() : isEnglish = false;

  const PaymentStrings.english() : isEnglish = true;

  final bool isEnglish;

  String pick(String chinese, String english) => isEnglish ? english : chinese;

  String get paymentTitle => pick('确认支付', 'Confirm payment');
  String get selectPaymentMethod => pick('选择支付方式', 'Select a payment method');
  String get paymentMethod => pick('支付方式', 'Payment method');
  String get paymentValidUntil => pick('支付有效期至', 'Payment valid until');
  String get amountDue => pick('应付金额', 'Amount due');
  String get paymentAmount => pick('支付金额', 'Payment amount');
  String get travelGroundServiceFee =>
      pick('旅游地接服务费', 'Travel ground service fee');
  String get consultationFee => pick('面诊金', 'Consultation fee');
  String get balancePayment => pick('支付尾款', 'Balance payment');
  String get continuePayment => pick('继续支付', 'Continue to payment');
  String get payNow => pick('立即支付', 'Pay now');
  String get cancel => pick('取消', 'Cancel');
  String get retry => pick('重试', 'Try again');
  String get refreshStatus => pick('刷新支付状态', 'Refresh payment status');
  String get backToOrder => pick('返回订单', 'Back to order');
  String get done => pick('完成', 'Done');

  String get openingPayment => pick('正在打开支付…', 'Opening payment…');
  String get processing => pick('支付处理中…', 'Processing payment…');
  String get confirming => pick('正在确认支付结果…', 'Confirming your payment…');
  String get doNotClose => pick('请勿关闭当前页面', 'Please keep this page open.');
  String get processingHint => pick(
        '银行或支付平台正在确认结果，请稍候。',
        'Your bank or payment provider is confirming the result. Please wait.',
      );

  String get paymentSuccessful => pick('支付成功', 'Payment successful');
  String get paymentSuccessHint => pick(
      '款项已到账，订单状态已更新。', 'Your payment was received and the order was updated.');
  String get paymentFailed => pick('支付失败', 'Payment failed');
  String get paymentFailedHint => pick('未完成扣款，请重试或更换支付方式。',
      'You were not charged. Try again or use another payment method.');
  String get paymentCancelled => pick('已取消支付', 'Payment cancelled');
  String get paymentCancelledHint => pick('本次支付已取消，订单仍保留。',
      'This payment was cancelled. Your order is still available.');
  String get paymentExpired => pick('支付已超时', 'Payment expired');
  String get paymentExpiredHint => pick('本次支付请求已失效，请重新发起支付。',
      'This payment request expired. Start a new payment.');
  String get paymentUnavailable =>
      pick('支付服务暂不可用', 'Payment temporarily unavailable');
  String get paymentStatusUnknown => pick('支付结果待确认', 'Payment result pending');
  String get paymentStatusUnknownHint => pick(
        '请勿重复支付。我们正在向支付平台确认结果。',
        'Do not pay again. We are confirming the result with the payment provider.',
      );
  String get partiallyRefunded => pick('已部分退款', 'Partially refunded');
  String get refunded => pick('已退款', 'Refunded');

  String amount(String formattedAmount) =>
      pick('支付金额：$formattedAmount', 'Amount: $formattedAmount');

  String providerLabel(String provider) =>
      switch (provider.trim().toUpperCase()) {
        'ALIPAY_PLUS' => 'Alipay+',
        'STRIPE' => pick('银行卡', 'Card'),
        'PAYPAL' => 'PayPal',
        'WECHAT_PAY' => pick('微信支付', 'WeChat Pay'),
        'ALIPAY' => pick('支付宝', 'Alipay'),
        _ => pick('其他支付方式', 'Other payment method'),
      };

  String paymentTypeLabel(String paymentType) =>
      switch (paymentType.trim().toUpperCase()) {
        'TRAVEL_GROUND_SERVICE_FEE' => travelGroundServiceFee,
        'CONSULTATION_FEE' => consultationFee,
        'BALANCE' => balancePayment,
        _ => pick('订单支付', 'Order payment'),
      };

  String statusTitle(String status) => switch (status.trim().toUpperCase()) {
        'CREATED' || 'REQUIRES_ACTION' => paymentTitle,
        'PROCESSING' => processing,
        'SUCCEEDED' || 'SUCCESS' => paymentSuccessful,
        'FAILED' => paymentFailed,
        'CANCELLED' => paymentCancelled,
        'EXPIRED' => paymentExpired,
        'PARTIALLY_REFUNDED' => partiallyRefunded,
        'REFUNDED' => refunded,
        _ => paymentStatusUnknown,
      };

  /// Converts a server or provider error code into safe bilingual copy.
  /// Unknown values intentionally fall back to a generic message so raw error
  /// codes and provider diagnostics are never exposed to the customer.
  String errorMessage(String? code) => switch (code?.trim().toUpperCase()) {
        'PAYMENT_PROVIDER_UNAVAILABLE' => pick(
            '支付服务暂不可用，请稍后刷新重试。',
            'Payment is temporarily unavailable. Refresh and try again later.',
          ),
        'UNSUPPORTED_PAYMENT_PROVIDER' || 'INVALID_PAYMENT_METHOD' => pick(
            '该支付方式不受支持，请更换支付方式。',
            'This payment method is not supported. Choose another method.',
          ),
        'INVALID_IDEMPOTENCY_KEY' || 'IDEMPOTENCY_KEY_CONFLICT' => pick(
            '无法继续本次支付，请返回订单后重试。',
            'This payment cannot continue. Return to the order and try again.',
          ),
        'PAYMENT_AMOUNT_NOT_POSITIVE' ||
        'PAYMENT_AMOUNT_MISSING' ||
        'INVALID_PAYMENT_AMOUNT' ||
        'INVALID_PAYMENT_AMOUNT_PRECISION' ||
        'PAYMENT_AMOUNT_MISMATCH' =>
          pick(
            '支付金额有变化，请刷新订单后重试。',
            'The payment amount changed. Refresh the order and try again.',
          ),
        'INVALID_CURRENCY' ||
        'UNSUPPORTED_CURRENCY' ||
        'PAYMENT_CURRENCY_MISMATCH' =>
          pick(
            '当前币种暂无法支付，请联系客服。',
            'This currency cannot be processed. Contact customer service.',
          ),
        'PAYMENT_NOT_FOUND' || 'ORDER_NOT_FOUND' => pick(
            '未找到支付信息，请返回订单刷新后重试。',
            'Payment details were not found. Return to the order, refresh, and try again.',
          ),
        'INVALID_PAYMENT_STATUS' || 'PAYMENT_ATTEMPT_IN_PROGRESS' => pick(
            '支付状态已更新，请刷新后查看。',
            'The payment status changed. Refresh to see the latest result.',
          ),
        'PAYMENT_ACCESS_DENIED' || 'ORDER_ACCESS_DENIED' => pick(
            '无法查看该支付，请返回订单。',
            'This payment cannot be viewed. Return to the order.',
          ),
        'PAYMENT_QUERY_UNAVAILABLE' ||
        'PAYMENT_CONFIRM_UNAVAILABLE' ||
        'PROVIDER_REQUEST_UNCERTAIN' ||
        'PAYMENT_STATUS_UNAVAILABLE' =>
          paymentStatusUnknownHint,
        'PAYMENT_NEXT_ACTION_MISSING' => pick(
            '支付信息需要重新获取，请刷新支付状态。',
            'Payment details need to be refreshed. Check the payment status again.',
          ),
        'PAYMENT_SDK_NOT_CONFIGURED' || 'PAYMENT_ACTION_UNAVAILABLE' => pick(
            '该支付方式尚未完成客户端配置，请更换支付方式。',
            'This payment method is not configured in the app yet. Choose another method.',
          ),
        'PAYMENT_REDIRECT_INVALID' || 'PAYMENT_REDIRECT_UNAVAILABLE' => pick(
            '无法安全打开支付页面，请更换支付方式。',
            'The secure payment page could not be opened. Choose another method.',
          ),
        'NETWORK_ERROR' || 'TIMEOUT' || 'CONNECTION_ERROR' => pick(
            '网络连接异常，请检查网络后重试。',
            'Check your connection and try again.',
          ),
        'USER_CANCELLED' ||
        'PAYMENT_CANCELLED' ||
        'CANCELLED' =>
          paymentCancelledHint,
        _ => pick(
            '支付未完成，请稍后重试。',
            'The payment could not be completed. Try again later.',
          ),
      };
}
