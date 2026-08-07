import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/domain/payment_models.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_controller.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_page.dart';

import 'order_test_fixtures.dart';

void main() {
  testWidgets('renders and completes the same payment flow in English', (
    tester,
  ) async {
    final repository = FakeOrdersRepository()
      ..paymentAttempt = samplePaymentAttempt(status: PaymentStatus.failed);
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      paymentType: PaymentType.consultationFee,
      providers: const [PaymentProvider.demo],
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

    expect(find.text('Confirm payment'), findsOneWidget);
    expect(find.text('Demo payment'), findsOneWidget);
    expect(find.text('Payment failed'), findsOneWidget);

    repository.paymentAttempt = samplePaymentAttempt();
    await tester.tap(find.byKey(const Key('payment-retry')));
    await tester.pumpAndSettle();

    expect(find.text('Payment successful'), findsOneWidget);
    expect(find.text('Back to order'), findsOneWidget);
  });

  testWidgets('renders Chinese payment copy without a separate platform page', (
    tester,
  ) async {
    final repository = FakeOrdersRepository()
      ..paymentAttempt = samplePaymentAttempt(status: PaymentStatus.failed);
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      paymentType: PaymentType.balance,
      providers: const [PaymentProvider.demo],
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
    expect(find.text('演示支付'), findsOneWidget);
    expect(find.text('支付尾款'), findsOneWidget);
    expect(find.text('支付失败'), findsOneWidget);
  });
}
