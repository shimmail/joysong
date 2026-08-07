import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/orders/domain/orders_repository.dart';
import 'package:joysong_flutter/features/orders/domain/payment_models.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_controller.dart';

import 'order_test_fixtures.dart';

void main() {
    test('trusts only a server-confirmed successful Stripe attempt',
      () async {
    final repository = _PaymentRepository();
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      paymentType: PaymentType.consultationFee,
        providers: const [PaymentProvider.stripe],
      pollingDelays: const [],
    );

    final succeeded = await controller.submit();

    expect(succeeded, isTrue);
    expect(controller.stage, PaymentFlowStage.succeeded);
    expect(repository.createCalls, 1);
    expect(repository.lastIdempotencyKey, isNotEmpty);
    controller.dispose();
  });

  test('prevents a double tap from creating duplicate attempts', () async {
    final completer = Completer<PaymentAttempt>();
    final repository = _PaymentRepository(createFuture: completer.future);
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      paymentType: PaymentType.consultationFee,
      providers: const [PaymentProvider.stripe],
      pollingDelays: const [],
    );

    final first = controller.submit();
    final second = await controller.submit();
    expect(second, isFalse);
    expect(repository.createCalls, 1);

    completer.complete(samplePaymentAttempt());
    expect(await first, isTrue);
    controller.dispose();
  });

  test('never treats a missing next action as payment success', () async {
    final repository = _PaymentRepository(
      createResult: samplePaymentAttempt(
        provider: PaymentProvider.stripe,
        status: PaymentStatus.requiresAction,
      ),
    );
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      paymentType: PaymentType.consultationFee,
      providers: const [PaymentProvider.stripe],
      pollingDelays: const [],
    );

    expect(await controller.submit(), isFalse);
    expect(controller.stage, PaymentFlowStage.failed);
    expect(controller.errorCode, 'PAYMENT_NEXT_ACTION_MISSING');
    controller.dispose();
  });

  test('unknown provider status remains pending and cannot be retried',
      () async {
    final repository = _PaymentRepository(
      latestResult: samplePaymentAttempt(status: PaymentStatus.unknown),
    );
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      paymentType: PaymentType.consultationFee,
      providers: const [PaymentProvider.stripe],
      pollingDelays: const [],
    );

    await controller.load();

    expect(controller.stage, PaymentFlowStage.processing);
    expect(controller.canSubmit, isFalse);
    controller.dispose();
  });

  test('cancelled attempt can be replaced with a new idempotent attempt',
      () async {
    final repository = _PaymentRepository(
      latestResult: samplePaymentAttempt(status: PaymentStatus.cancelled),
    );
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      paymentType: PaymentType.consultationFee,
      providers: const [PaymentProvider.stripe],
      pollingDelays: const [],
    );

    await controller.load();
    expect(controller.stage, PaymentFlowStage.cancelled);
    controller.retryWithNewAttempt();
    expect(await controller.submit(), isTrue);
    expect(controller.stage, PaymentFlowStage.succeeded);
    controller.dispose();
  });

  test('restores and continues a requires-action attempt', () async {
    final action = const PaymentNextAction(
      type: PaymentNextActionType.stripeClientSecret,
      clientSecret: 'client-secret',
    );
    final repository = _PaymentRepository(
      latestResult: samplePaymentAttempt(
        provider: PaymentProvider.stripe,
        status: PaymentStatus.requiresAction,
        nextAction: action,
      ),
      queryResult: samplePaymentAttempt(provider: PaymentProvider.stripe),
    );
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      paymentType: PaymentType.consultationFee,
      providers: const [PaymentProvider.stripe],
      actionLauncher: const _CompletedAction(),
      pollingDelays: const [Duration.zero],
    );

    await controller.load();
    expect(controller.stage, PaymentFlowStage.requiresAction);
    expect(await controller.continueCurrent(), isTrue);
    expect(controller.stage, PaymentFlowStage.succeeded);
    controller.dispose();
  });

  test('refreshes latest attempt through provider when restoring flow',
      () async {
    final repository = _PaymentRepository(
      latestResult: samplePaymentAttempt(
        status: PaymentStatus.processing,
      ),
    );
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      paymentType: PaymentType.consultationFee,
      providers: const [PaymentProvider.stripe],
      pollingDelays: const [],
    );

    await controller.load();

    expect(repository.lastLatestRefresh, isTrue);
    expect(controller.stage, PaymentFlowStage.processing);
    controller.dispose();
  });

  test('enables a new payment only after server confirms none exists',
      () async {
    final repository = _PaymentRepository();
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      paymentType: PaymentType.consultationFee,
      providers: const [PaymentProvider.stripe],
      pollingDelays: const [],
    );

    await controller.load();

    expect(controller.stage, PaymentFlowStage.ready);
    expect(controller.canSubmit, isTrue);
    controller.dispose();
  });

  test('keeps an unknown network outcome in processing state', () async {
    final repository = _PaymentRepository(
      createFuture: Future<PaymentAttempt>.error(
        const ApiException(message: 'timeout'),
      ),
    );
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      paymentType: PaymentType.consultationFee,
      providers: const [PaymentProvider.stripe],
      pollingDelays: const [],
    );

    expect(await controller.submit(), isFalse);
    expect(controller.stage, PaymentFlowStage.processing);
    expect(controller.errorCode, 'PAYMENT_STATUS_UNAVAILABLE');
    controller.dispose();
  });

  test('confirms PayPal after returning from an external approval page',
      () async {
    final repository = _PaymentRepository(
      createResult: samplePaymentAttempt(
        provider: PaymentProvider.paypal,
        status: PaymentStatus.requiresAction,
        nextAction: const PaymentNextAction(
          type: PaymentNextActionType.redirect,
          url: 'https://sandbox.paypal.com/approve',
        ),
      ),
      confirmResult: samplePaymentAttempt(
        provider: PaymentProvider.paypal,
      ),
    );
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      paymentType: PaymentType.consultationFee,
      providers: const [PaymentProvider.paypal],
      actionLauncher: const _LaunchedAction(),
      pollingDelays: const [],
    );

    expect(await controller.submit(), isFalse);
    expect(controller.stage, PaymentFlowStage.processing);
    expect(await controller.resumeAfterExternalAction(), isTrue);
    expect(repository.confirmCalls, 1);
    expect(controller.stage, PaymentFlowStage.succeeded);
    controller.dispose();
  });
}

final class _PaymentRepository implements OrdersRepository {
  _PaymentRepository({
    PaymentAttempt? createResult,
    PaymentAttempt? latestResult,
    PaymentAttempt? confirmResult,
    PaymentAttempt? queryResult,
    this.createFuture,
  })  : _createResult = createResult ?? samplePaymentAttempt(),
        _latestResult = latestResult,
        _confirmResult = confirmResult ?? samplePaymentAttempt(),
        _queryResult = queryResult ?? samplePaymentAttempt();

  final PaymentAttempt _createResult;
  final PaymentAttempt? _latestResult;
  final PaymentAttempt _confirmResult;
  final PaymentAttempt _queryResult;
  final Future<PaymentAttempt>? createFuture;
  int createCalls = 0;
  int confirmCalls = 0;
  String? lastIdempotencyKey;
  bool? lastLatestRefresh;

  @override
  Future<PaymentAttempt> createPaymentAttempt(
    String orderId, {
    required PaymentType paymentType,
    required PaymentProvider provider,
    required String paymentMethod,
    required String idempotencyKey,
  }) {
    createCalls += 1;
    lastIdempotencyKey = idempotencyKey;
    return createFuture ?? Future.value(_createResult);
  }

  @override
  Future<PaymentAttempt> getLatestPayment(
    String orderId, {
    required PaymentType paymentType,
    bool refresh = false,
  }) async {
    lastLatestRefresh = refresh;
    final result = _latestResult;
    if (result == null) {
      throw const ApiException(
        message: 'PAYMENT_NOT_FOUND',
        httpStatus: 200,
        businessCode: 404,
      );
    }
    return result;
  }

  @override
  Future<PaymentAttempt> confirmPayment(
    String paymentId, {
    required String idempotencyKey,
  }) async {
    confirmCalls += 1;
    lastIdempotencyKey = idempotencyKey;
    return _confirmResult;
  }

  @override
  Future<PaymentAttempt> getPayment(
    String paymentId, {
    bool refresh = false,
  }) async =>
      _queryResult;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _LaunchedAction implements PaymentActionLauncher {
  const _LaunchedAction();

  @override
  Future<PaymentActionResult> launch(PaymentAttempt payment) async =>
      const PaymentActionResult(PaymentActionOutcome.launched);
}

final class _CompletedAction implements PaymentActionLauncher {
  const _CompletedAction();

  @override
  Future<PaymentActionResult> launch(PaymentAttempt payment) async =>
      const PaymentActionResult(PaymentActionOutcome.completed);
}
