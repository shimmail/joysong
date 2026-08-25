import 'dart:async';

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

  testWidgets('falls back to the order service fee before an attempt exists', (
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
    expect(find.text(r'$400.00'), findsOneWidget);
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

  testWidgets('counts down to expiry then refreshes the server payment state', (
    tester,
  ) async {
    final startedAt = DateTime(2026, 8, 7, 12);
    var now = startedAt;
    final expiresAt = startedAt.add(const Duration(seconds: 1));
    final repository = FakeOrdersRepository()
      ..paymentAttempt = samplePaymentAttempt(
        status: PaymentStatus.requiresAction,
        nextAction: const PaymentNextAction(
          type: PaymentNextActionType.redirect,
          url: 'https://cashier.alipayplus.com/pay/session-1',
        ),
        expiresAt: expiresAt,
      );
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      pollingDelays: const [],
      now: () => now,
    );
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        supportedLocales: const [Locale('en')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: PaymentPage(controller: controller, now: () => now),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Payment expires in 00:00:01'), findsOneWidget);
    expect(find.byKey(const Key('payment-continue')), findsOneWidget);

    repository.paymentAttempt = samplePaymentAttempt(status: PaymentStatus.expired);
    now = expiresAt;
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpAndSettle();

    expect(find.text('Payment expired'), findsOneWidget);
    expect(find.byKey(const Key('payment-continue')), findsNothing);
    expect(repository.paymentCalls, 2);
  });

  testWidgets(
      'allows a manual status refresh after an expired action auto-refreshes',
      (tester) async {
    final startedAt = DateTime(2026, 8, 7, 12);
    var now = startedAt;
    final expiresAt = startedAt.add(const Duration(seconds: 1));
    final repository = FakeOrdersRepository()
      ..paymentAttempt = samplePaymentAttempt(
        status: PaymentStatus.requiresAction,
        nextAction: const PaymentNextAction(
          type: PaymentNextActionType.redirect,
          url: 'https://cashier.alipayplus.com/pay/session-1',
        ),
        expiresAt: expiresAt,
      );
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      actionLauncher: const _ThrowingPageActionLauncher(),
      pollingDelays: const [],
      now: () => now,
    );
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        supportedLocales: const [Locale('en')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: PaymentPage(controller: controller, now: () => now),
      ),
    );
    await tester.pumpAndSettle();

    now = expiresAt;
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpAndSettle();

    expect(repository.paymentCalls, 2);
    expect(find.text('Refresh payment status'), findsOneWidget);

    await tester.tap(find.byKey(const Key('payment-continue')));
    await tester.pumpAndSettle();

    expect(repository.paymentCalls, 3);
    expect(controller.stage, PaymentFlowStage.requiresAction);
  });

  testWidgets('uses one status query when resuming after expiry', (tester) async {
    final startedAt = DateTime(2026, 8, 7, 12);
    var now = startedAt;
    final expiresAt = startedAt.add(const Duration(seconds: 1));
    final repository = _DelayedPaymentRepository()
      ..paymentAttempt = samplePaymentAttempt(
        status: PaymentStatus.requiresAction,
        nextAction: const PaymentNextAction(
          type: PaymentNextActionType.redirect,
          url: 'https://cashier.alipayplus.com/pay/session-1',
        ),
        expiresAt: expiresAt,
      );
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      pollingDelays: const [],
      now: () => now,
    );
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        supportedLocales: const [Locale('en')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: PaymentPage(controller: controller, now: () => now),
      ),
    );
    await tester.pumpAndSettle();

    now = expiresAt;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();

    expect(repository.statusQueryCalls, 1);
    repository.statusQuery.complete(samplePaymentAttempt(status: PaymentStatus.expired));
    await tester.pumpAndSettle();

    expect(find.text('Payment expired'), findsOneWidget);
    expect(find.byKey(const Key('payment-continue')), findsNothing);
  });

  testWidgets('rebinds a replacement controller without old events refreshing it',
      (tester) async {
    final startedAt = DateTime(2026, 8, 7, 12);
    var now = startedAt;
    final expiresAt = startedAt.add(const Duration(seconds: 1));
    final firstRepository = FakeOrdersRepository()
      ..paymentAttempt = samplePaymentAttempt(status: PaymentStatus.failed);
    final secondRepository = FakeOrdersRepository()
      ..paymentAttempt = samplePaymentAttempt(
        status: PaymentStatus.requiresAction,
        nextAction: const PaymentNextAction(
          type: PaymentNextActionType.redirect,
          url: 'https://cashier.alipayplus.com/pay/session-1',
        ),
        expiresAt: expiresAt,
      );
    final first = PaymentController(
      repository: firstRepository,
      order: sampleOrder(),
      pollingDelays: const [],
      now: () => now,
    );
    final second = PaymentController(
      repository: secondRepository,
      order: sampleOrder(),
      pollingDelays: const [],
      now: () => now,
    );
    addTearDown(first.dispose);
    addTearDown(second.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        supportedLocales: const [Locale('en')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: PaymentPage(
          key: const ValueKey('payment-page'),
          controller: first,
          now: () => now,
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        supportedLocales: const [Locale('en')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: PaymentPage(
          key: const ValueKey('payment-page'),
          controller: second,
          now: () => now,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('payment-continue')), findsOneWidget);
    expect(secondRepository.paymentCalls, 1);

    now = expiresAt;
    await first.refresh();
    await tester.pump();

    expect(secondRepository.paymentCalls, 1);
  });
}

final class _DelayedPaymentRepository extends FakeOrdersRepository {
  final statusQuery = Completer<PaymentAttempt>();
  int statusQueryCalls = 0;

  @override
  Future<PaymentAttempt> getPayment(
    String paymentId, {
    bool refresh = false,
  }) {
    paymentCalls += 1;
    lastPaymentRefresh = refresh;
    statusQueryCalls += 1;
    return statusQuery.future;
  }
}

final class _ThrowingPageActionLauncher implements PaymentActionLauncher {
  const _ThrowingPageActionLauncher();

  @override
  Future<PaymentActionResult> launch(PaymentAttempt payment) =>
      Future<PaymentActionResult>.error(StateError('stale action was opened'));
}
