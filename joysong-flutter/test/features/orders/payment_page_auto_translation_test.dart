import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/orders/domain/payment_models.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_controller.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_page.dart';

import '../../core/translation/translation_test_fixtures.dart';
import 'order_test_fixtures.dart';

void main() {
  testWidgets(
      'payment summary translates project and institution once across timer rebuilds',
      (tester) async {
    final startedAt = DateTime(2026, 8, 7, 12);
    final repository = FakeOrdersRepository()
      ..paymentAttempt = samplePaymentAttempt(
        status: PaymentStatus.requiresAction,
        nextAction: const PaymentNextAction(
          type: PaymentNextActionType.redirect,
          url: 'https://cashier.alipayplus.com/pay/session-1',
        ),
        expiresAt: startedAt.add(const Duration(hours: 1)),
      );
    final paymentController = PaymentController(
      repository: repository,
      order: sampleOrder(consultantDetailsVisible: true),
      pollingDelays: const [],
      now: () => startedAt,
    );
    addTearDown(paymentController.dispose);
    final translations = RecordingTranslationRepository();
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _paymentHost(
        autoController: autoController,
        page: PaymentPage(
          controller: paymentController,
          now: () => startedAt,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(_mountedRequests(tester), const {
      ('project', 'order:order-1', 'projectName', '光子嫩肤'),
      ('institution', 'order:order-1', 'institutionName', '娇颜颂医疗美容'),
    });
    expect(translations.calls, hasLength(2));

    await tester.pump(const Duration(seconds: 3));
    expect(translations.calls, hasLength(2));

    await paymentController.refresh();
    await tester.pump();
    expect(translations.calls, hasLength(2));
  });

  testWidgets('payment page defaults off under an active scope',
      (tester) async {
    final repository = FakeOrdersRepository()
      ..paymentAttempt = samplePaymentAttempt(status: PaymentStatus.failed);
    final paymentController = PaymentController(
      repository: repository,
      order: sampleOrder(consultantDetailsVisible: true),
      pollingDelays: const [],
    );
    addTearDown(paymentController.dispose);
    final translations = RecordingTranslationRepository();
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _paymentHost(
        autoController: autoController,
        page: PaymentPage(controller: paymentController),
      ),
    );
    await tester.pumpAndSettle();

    expect(translations.calls, isEmpty);
    expect(find.byType(AutoTranslationBuilder), findsNothing);
    expect(find.text('光子嫩肤'), findsOneWidget);
    expect(find.text('娇颜颂医疗美容'), findsOneWidget);
  });

  testWidgets(
      'payment state provider amount url and errors never create translation requests',
      (tester) async {
    final repository = FakeOrdersRepository()
      ..paymentAttempt = PaymentAttempt(
        id: 'payment-sensitive',
        orderId: 'order-1',
        paymentType: PaymentType.travelGroundServiceFee,
        provider: PaymentProvider.alipayPlus,
        paymentMethod: '支付方式原值',
        currency: 'USD',
        amountMinor: 43210,
        refundedAmountMinor: 1234,
        status: PaymentStatus.failed,
        providerPaymentId: '支付渠道编号',
        failureCode: '支付失败代码',
        failureMessage: '支付失败详情',
        nextAction: const PaymentNextAction(
          type: PaymentNextActionType.redirect,
          url: 'https://cashier.example/支付地址',
        ),
        expiresAt: DateTime(2026, 8, 7, 13),
        createdAt: DateTime(2026, 8, 7, 12),
        updatedAt: DateTime(2026, 8, 7, 12, 1),
      );
    final paymentController = PaymentController(
      repository: repository,
      order: sampleOrder(consultantDetailsVisible: true),
      pollingDelays: const [],
    );
    addTearDown(paymentController.dispose);
    final translations = RecordingTranslationRepository();
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _paymentHost(
        autoController: autoController,
        page: PaymentPage(
          controller: paymentController,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      translations.calls.map((call) => call.text),
      ['光子嫩肤', '娇颜颂医疗美容'],
    );
    for (final excluded in const [
      '支付方式原值',
      '支付渠道编号',
      '支付失败代码',
      '支付失败详情',
      'https://cashier.example/支付地址',
      r'$432.10',
    ]) {
      expect(translations.calls.map((call) => call.text),
          isNot(contains(excluded)));
    }
  });
}

Set<(String, String, String, String)> _mountedRequests(WidgetTester tester) =>
    tester
        .widgetList<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
        .map((widget) => (
              widget.request.contentType,
              widget.request.contentId,
              widget.request.field,
              widget.request.sourceText,
            ))
        .toSet();

AutoTranslationController _activeAutoController(
  TranslationRepository repository,
) {
  final controller = AutoTranslationController(
    repository: repository,
    maxConcurrent: 10,
  );
  controller.synchronize(
    enabled: true,
    authenticated: true,
    targetLanguage: 'en-US',
  );
  return controller;
}

Widget _paymentHost({
  required AutoTranslationController autoController,
  required Widget page,
}) =>
    AutoTranslationScope(
      controller: autoController,
      enabled: true,
      targetLanguage: 'en-US',
      child: MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('en'), Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: page,
      ),
    );
