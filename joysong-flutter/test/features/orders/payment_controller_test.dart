import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/orders/domain/orders_repository.dart';
import 'package:joysong_flutter/features/orders/domain/payment_models.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_controller.dart';

import 'order_test_fixtures.dart';

void main() {
  test('requires a server restore before creating a service-fee attempt',
      () async {
    final repository = _PaymentRepository();
    final controller = _controller(repository);

    expect(controller.canSubmit, isFalse);
    expect(await controller.submit(), isFalse);
    expect(repository.createCalls, 0);

    await controller.load();
    expect(controller.stage, PaymentFlowStage.ready);
    expect(controller.canSubmit, isTrue);
    controller.dispose();
  });

  test('trusts only a server-confirmed successful service-fee attempt',
      () async {
    final repository = _PaymentRepository();
    final controller = _controller(repository);
    await controller.load();

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
    final controller = _controller(repository);
    await controller.load();

    final first = controller.submit();
    final second = await controller.submit();
    expect(second, isFalse);
    expect(repository.createCalls, 1);

    completer.complete(samplePaymentAttempt());
    expect(await first, isTrue);
    controller.dispose();
  });

  test('explicit service-fee type cannot opt into the legacy provider path',
      () async {
    final repository = _PaymentRepository();
    final controller = PaymentController(
      repository: repository,
      order: sampleOrder(),
      paymentType: PaymentType.travelGroundServiceFee,
      providers: const [PaymentProvider.stripe, PaymentProvider.paypal],
      pollingDelays: const [],
    );

    expect(controller.isServiceFeeFlow, isTrue);
    expect(controller.providers, const [PaymentProvider.alipayPlus]);
    controller.selectProvider(PaymentProvider.stripe);
    expect(controller.selectedProvider, PaymentProvider.alipayPlus);

    await controller.load();
    expect(await controller.submit(), isTrue);
    expect(repository.createCalls, 1);
    expect(repository.legacyCreateCalls, 0);
    controller.dispose();
  });

  test('maps provider 503 to unavailable without a local attempt', () async {
    final repository = _PaymentRepository(
      latestError: const ApiException(
        message: 'Payment is temporarily unavailable',
        httpStatus: 200,
        businessCode: 503,
      ),
    );
    final controller = _controller(repository);

    await controller.load();

    expect(controller.stage, PaymentFlowStage.unavailable);
    expect(controller.errorCode, 'PAYMENT_PROVIDER_UNAVAILABLE');
    expect(controller.payment, isNull);
    expect(controller.canSubmit, isFalse);
    expect(repository.createCalls, 0);
    controller.dispose();
  });

  test('maps create provider 503 to unavailable without a fake attempt',
      () async {
    final repository = _PaymentRepository(
      createError: const ApiException(
        message: 'Payment is temporarily unavailable',
        httpStatus: 200,
        businessCode: 503,
      ),
    );
    final controller = _controller(repository);
    await controller.load();

    expect(await controller.submit(), isFalse);
    expect(controller.stage, PaymentFlowStage.unavailable);
    expect(controller.errorCode, 'PAYMENT_PROVIDER_UNAVAILABLE');
    expect(controller.payment, isNull);
    controller.dispose();
  });

  test('does not overlap an attempt whose redirect cannot be rehydrated',
      () async {
    final repository = _PaymentRepository(
      latestResult: samplePaymentAttempt(
        status: PaymentStatus.requiresAction,
      ),
    );
    final controller = _controller(repository);

    await controller.load();

    expect(controller.stage, PaymentFlowStage.unavailable);
    expect(controller.errorCode, 'PAYMENT_NEXT_ACTION_MISSING');
    expect(controller.retryWithNewAttempt(), isFalse);
    expect(await controller.submit(), isFalse);
    expect(repository.createCalls, 0);
    controller.dispose();
  });

  test('preserves redirect for the same attempt across a refresh', () async {
    const action = PaymentNextAction(
      type: PaymentNextActionType.redirect,
      url: 'https://cashier.alipayplus.com/pay/session-1',
    );
    final repository = _PaymentRepository(
      latestResult: samplePaymentAttempt(
        status: PaymentStatus.requiresAction,
        nextAction: action,
      ),
      queryResult: samplePaymentAttempt(
        status: PaymentStatus.requiresAction,
      ),
    );
    final controller = _controller(repository);

    await controller.load();
    await controller.refresh();

    expect(controller.stage, PaymentFlowStage.requiresAction);
    expect(controller.payment?.nextAction?.url, action.url);
    expect(controller.canSubmit, isFalse);
    controller.dispose();
  });

  test('retry creates a new attempt after server FAILED, CANCELLED or EXPIRED',
      () async {
    for (final retryableStatus in [
      PaymentStatus.failed,
      PaymentStatus.cancelled,
      PaymentStatus.expired,
    ]) {
      final repository = _PaymentRepository(
        latestResult: samplePaymentAttempt(status: retryableStatus),
      );
      final controller = _controller(repository);

      await controller.load();
      expect(controller.retryWithNewAttempt(), isTrue);
      expect(await controller.submit(), isTrue);
      expect(repository.createCalls, 1);
      controller.dispose();
    }
  });

  test('created and processing attempts cannot be replaced', () async {
    for (final status in [
      PaymentStatus.created,
      PaymentStatus.processing,
    ]) {
      final repository = _PaymentRepository(
        latestResult: samplePaymentAttempt(status: status),
      );
      final controller = _controller(repository);

      await controller.load();
      expect(controller.retryWithNewAttempt(), isFalse, reason: status.name);
      expect(await controller.submit(), isFalse, reason: status.name);
      expect(repository.createCalls, 0, reason: status.name);
      controller.dispose();
    }
  });

  test('app resume only refreshes server truth and never confirms', () async {
    final repository = _PaymentRepository(
      latestResult: samplePaymentAttempt(status: PaymentStatus.processing),
      queryResult: samplePaymentAttempt(status: PaymentStatus.succeeded),
    );
    final controller = _controller(repository);

    await controller.load();
    expect(await controller.resumeAfterExternalAction(), isTrue);

    expect(repository.queryCalls, 1);
    expect(repository.confirmCalls, 0);
    expect(controller.stage, PaymentFlowStage.succeeded);
    controller.dispose();
  });

  test('keeps an unknown network outcome in processing state', () async {
    final repository = _PaymentRepository(
      createError: const ApiException(message: 'timeout'),
    );
    final controller = _controller(repository);
    await controller.load();

    expect(await controller.submit(), isFalse);
    expect(controller.stage, PaymentFlowStage.processing);
    expect(controller.errorCode, 'PAYMENT_STATUS_UNAVAILABLE');
    controller.dispose();
  });
}

PaymentController _controller(_PaymentRepository repository) =>
    PaymentController(
      repository: repository,
      order: sampleOrder(),
      pollingDelays: const [],
    );

final class _PaymentRepository implements OrdersRepository {
  _PaymentRepository({
    PaymentAttempt? createResult,
    this.latestResult,
    PaymentAttempt? queryResult,
    this.createFuture,
    this.createError,
    this.latestError,
  })  : _createResult = createResult ?? samplePaymentAttempt(),
        _queryResult = queryResult ?? samplePaymentAttempt();

  final PaymentAttempt _createResult;
  final PaymentAttempt? latestResult;
  final PaymentAttempt _queryResult;
  final Future<PaymentAttempt>? createFuture;
  final Object? createError;
  final Object? latestError;
  int createCalls = 0;
  int legacyCreateCalls = 0;
  int queryCalls = 0;
  int confirmCalls = 0;
  String? lastIdempotencyKey;

  @override
  Future<PaymentAttempt> createTravelGroundServicePaymentAttempt(
    String orderId, {
    required String idempotencyKey,
  }) {
    createCalls += 1;
    lastIdempotencyKey = idempotencyKey;
    final error = createError;
    if (error != null) return Future<PaymentAttempt>.error(error);
    return createFuture ?? Future.value(_createResult);
  }

  @override
  Future<PaymentAttempt> createPaymentAttempt(
    String orderId, {
    required PaymentType paymentType,
    required PaymentProvider provider,
    required String paymentMethod,
    required String idempotencyKey,
  }) {
    legacyCreateCalls += 1;
    return Future.value(_createResult);
  }

  @override
  Future<PaymentAttempt> getLatestTravelGroundServicePayment(
    String orderId, {
    bool refresh = false,
  }) async {
    final error = latestError;
    if (error != null) throw error;
    final result = latestResult;
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
  Future<PaymentAttempt> getPayment(
    String paymentId, {
    bool refresh = false,
  }) async {
    queryCalls += 1;
    return _queryResult;
  }

  @override
  Future<PaymentAttempt> confirmPayment(
    String paymentId, {
    required String idempotencyKey,
  }) {
    confirmCalls += 1;
    return Future.value(_queryResult);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
