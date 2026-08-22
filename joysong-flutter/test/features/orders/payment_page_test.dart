import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/orders/domain/payment_models.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_controller.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_page.dart';

import 'order_test_fixtures.dart';

void main() {
  testWidgets('renders fixed USD Alipay+ service-fee checkout in Chinese', (
    tester,
  ) async {
    final repository = FakeOrdersRepository()
      ..paymentAttempt = samplePaymentAttempt(
        status: PaymentStatus.failed,
        expiresAt: DateTime(2026, 8, 7, 12, 30),
      );
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      pollingDelays: const [],
    );
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('en'), Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: PaymentPage(controller: controller),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('确认支付'), findsOneWidget);
    expect(find.text('Alipay+'), findsOneWidget);
    expect(find.text(r'$400.00'), findsOneWidget);
    expect(find.text('旅游地接服务费'), findsOneWidget);
    expect(find.text('支付有效期至'), findsOneWidget);
    expect(find.text('2026-08-07 12:30'), findsOneWidget);
    expect(find.text('支付失败'), findsOneWidget);
    expect(find.text('选择支付方式'), findsNothing);
    expect(find.text('银行卡'), findsNothing);
    expect(find.textContaining('Stripe'), findsNothing);
    expect(find.textContaining('PayPal'), findsNothing);

    repository.paymentAttempt = samplePaymentAttempt();
    await tester.tap(find.byKey(const Key('payment-retry')));
    await tester.pumpAndSettle();

    expect(find.text('支付成功'), findsOneWidget);
    expect(find.text('返回订单'), findsOneWidget);
  });

  testWidgets('does not fall back to order consultation or balance amounts', (
    tester,
  ) async {
    final repository = FakeOrdersRepository();
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      pollingDelays: const [],
    );
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        supportedLocales: const [Locale('en'), Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: PaymentPage(controller: controller),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('payment-amount')), findsOneWidget);
    expect(find.text('--'), findsOneWidget);
    expect(find.text(r'$100.00'), findsNothing);
    expect(find.text(r'$1,180.50'), findsNothing);
    expect(find.text('Travel ground service fee'), findsOneWidget);
    expect(find.byKey(const Key('payment-submit')), findsOneWidget);
  });

  testWidgets('offers a new attempt for cancelled server status', (
    tester,
  ) async {
    final repository = FakeOrdersRepository()
      ..paymentAttempt = samplePaymentAttempt(status: PaymentStatus.cancelled);
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      pollingDelays: const [],
    );
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        supportedLocales: const [Locale('en')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: PaymentPage(controller: controller),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Payment cancelled'), findsOneWidget);
    expect(find.byKey(const Key('payment-retry')), findsOneWidget);
    expect(find.byKey(const Key('payment-submit')), findsNothing);
    expect(find.byKey(const Key('payment-refresh')), findsNothing);

    repository.paymentAttempt = samplePaymentAttempt();
    await tester.tap(find.byKey(const Key('payment-retry')));
    await tester.pumpAndSettle();

    expect(find.text('Payment successful'), findsOneWidget);
    expect(repository.paymentCalls, 2);
  });

  testWidgets('shows provider 503 as unavailable with refresh only', (
    tester,
  ) async {
    final repository = FakeOrdersRepository()
      ..latestPaymentError = const ApiException(
        message: 'Payment is temporarily unavailable',
        httpStatus: 200,
        businessCode: 503,
      );
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      pollingDelays: const [],
    );
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: PaymentPage(controller: controller),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('支付服务暂不可用'), findsOneWidget);
    expect(find.byKey(const Key('payment-refresh')), findsOneWidget);
    expect(find.byKey(const Key('payment-retry')), findsNothing);
    expect(find.byKey(const Key('payment-submit')), findsNothing);
  });
}
