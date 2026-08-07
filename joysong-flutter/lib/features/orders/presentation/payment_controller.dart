import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/domain/orders_repository.dart';
import 'package:joysong_flutter/features/orders/domain/payment_models.dart';

enum PaymentFlowStage {
  ready,
  loading,
  creating,
  requiresAction,
  processing,
  succeeded,
  failed,
  cancelled,
  expired,
  partiallyRefunded,
  refunded,
}

enum PaymentActionOutcome { completed, launched, cancelled, unavailable }

final class PaymentActionResult {
  const PaymentActionResult(this.outcome, {this.errorCode});

  final PaymentActionOutcome outcome;
  final String? errorCode;
}

abstract interface class PaymentActionLauncher {
  Future<PaymentActionResult> launch(PaymentAttempt payment);
}

final class UnavailablePaymentActionLauncher implements PaymentActionLauncher {
  const UnavailablePaymentActionLauncher();

  @override
  Future<PaymentActionResult> launch(PaymentAttempt payment) async =>
      const PaymentActionResult(
        PaymentActionOutcome.unavailable,
        errorCode: 'PAYMENT_SDK_NOT_CONFIGURED',
      );
}

final class PaymentController extends ChangeNotifier {
  PaymentController({
    required OrdersRepository repository,
    required this.order,
    required this.paymentType,
    required List<PaymentProvider> providers,
    PaymentActionLauncher actionLauncher =
        const UnavailablePaymentActionLauncher(),
    List<Duration> pollingDelays = const [
      Duration.zero,
      Duration(seconds: 1),
      Duration(seconds: 2),
      Duration(seconds: 3),
      Duration(seconds: 5),
    ],
  })  : assert(providers.isNotEmpty),
        _repository = repository,
        _providers = List.unmodifiable(providers),
        _selectedProvider = providers.first,
        _actionLauncher = actionLauncher,
        _pollingDelays = List.unmodifiable(pollingDelays),
        _createIdempotencyKey = generateApiRequestId();

  final OrdersRepository _repository;
  final Order order;
  final PaymentType paymentType;
  final List<PaymentProvider> _providers;
  final PaymentActionLauncher _actionLauncher;
  final List<Duration> _pollingDelays;

  PaymentProvider _selectedProvider;
  PaymentAttempt? _payment;
  PaymentFlowStage _stage = PaymentFlowStage.ready;
  String? _errorCode;
  String? _errorMessage;
  late String _createIdempotencyKey;
  bool _disposed = false;
  bool _awaitingExternalReturn = false;
  int _operation = 0;

  List<PaymentProvider> get providers => _providers;
  PaymentProvider get selectedProvider => _selectedProvider;
  PaymentAttempt? get payment => _payment;
  PaymentFlowStage get stage => _stage;
  String? get errorCode => _errorCode;
  String? get errorMessage => _errorMessage;
  bool get isBusy => const {
        PaymentFlowStage.loading,
        PaymentFlowStage.creating,
        PaymentFlowStage.requiresAction,
        PaymentFlowStage.processing,
      }.contains(_stage);
  bool get canSubmit =>
      !isBusy &&
      _stage != PaymentFlowStage.succeeded &&
      _stage != PaymentFlowStage.partiallyRefunded &&
      _stage != PaymentFlowStage.refunded;

  void selectProvider(PaymentProvider provider) {
    if (isBusy ||
        !_providers.contains(provider) ||
        provider == _selectedProvider) {
      return;
    }
    _selectedProvider = provider;
    _payment = null;
    _stage = PaymentFlowStage.ready;
    _errorCode = null;
    _errorMessage = null;
    _createIdempotencyKey = generateApiRequestId();
    _notify();
  }

  Future<void> load() async {
    if (isBusy) return;
    final operation = ++_operation;
    _stage = PaymentFlowStage.loading;
    _clearError();
    _notify();
    try {
      final latest = await _repository.getLatestPayment(
        order.id,
        paymentType: paymentType,
        refresh: true,
      );
      if (!_isCurrent(operation)) return;
      _payment = latest;
      _selectedProvider = latest.provider;
      _applyStatus(latest);
    } on Object catch (error) {
      if (!_isCurrent(operation)) return;
      if (_isMissingPayment(error)) {
        // No previous attempt is the expected state for a new payment. The
        // create endpoint remains the source of truth for conflict prevention.
        _stage = PaymentFlowStage.ready;
      } else {
        // A failed restore query must not enable a second payment attempt.
        _stage = PaymentFlowStage.processing;
        _errorCode = 'PAYMENT_STATUS_UNAVAILABLE';
        _errorMessage = _messageFor(error);
      }
    }
    _notify();
  }

  Future<bool> submit() async {
    if (!canSubmit) return false;
    final operation = ++_operation;
    _stage = PaymentFlowStage.creating;
    _clearError();
    _notify();
    try {
      final created = await _repository.createPaymentAttempt(
        order.id,
        paymentType: paymentType,
        provider: _selectedProvider,
        paymentMethod: _paymentMethod(_selectedProvider),
        idempotencyKey: _createIdempotencyKey,
      );
      if (!_isCurrent(operation)) return false;
      _payment = created;
      return _continuePayment(operation, created);
    } on Object catch (error) {
      if (_isCurrent(operation)) {
        if (_outcomeMayBeUnknown(error)) {
          _stage = PaymentFlowStage.processing;
          _errorCode = 'PAYMENT_STATUS_UNAVAILABLE';
          _errorMessage = _messageFor(error);
          _notify();
        } else {
          _failWith(error);
        }
      }
      return false;
    }
  }

  Future<bool> continueCurrent() async {
    final current = _payment;
    if (current == null || current.status != PaymentStatus.requiresAction) {
      return false;
    }
    final operation = ++_operation;
    _clearError();
    try {
      return await _continuePayment(operation, current);
    } on Object catch (error) {
      if (_isCurrent(operation)) {
        _stage = PaymentFlowStage.processing;
        _errorCode = 'PAYMENT_STATUS_UNAVAILABLE';
        _errorMessage = _messageFor(error);
        _notify();
      }
      return false;
    }
  }

  Future<bool> refresh({bool queryProvider = true}) async {
    final current = _payment;
    if (stage == PaymentFlowStage.succeeded) return false;
    final operation = ++_operation;
    _stage = PaymentFlowStage.processing;
    _clearError();
    _notify();
    try {
      final refreshed = current == null
          ? await _repository.getLatestPayment(
              order.id,
              paymentType: paymentType,
              refresh: queryProvider,
            )
          : await _repository.getPayment(
              current.id,
              refresh: queryProvider,
            );
      if (!_isCurrent(operation)) return false;
      _payment = refreshed;
      _applyStatus(refreshed);
      _notify();
      return refreshed.status == PaymentStatus.succeeded;
    } on Object catch (error) {
      if (_isCurrent(operation)) {
        _stage = PaymentFlowStage.processing;
        _errorCode = 'PAYMENT_STATUS_UNAVAILABLE';
        _errorMessage = _messageFor(error);
        _notify();
      }
      return false;
    }
  }

  Future<bool> resumeAfterExternalAction() async {
    final current = _payment;
    if (current == null) return refresh();
    if (!_awaitingExternalReturn ||
        current.provider != PaymentProvider.paypal) {
      return refresh();
    }
    final operation = ++_operation;
    _awaitingExternalReturn = false;
    _stage = PaymentFlowStage.processing;
    _clearError();
    _notify();
    try {
      final confirmed = await _repository.confirmPayment(
        current.id,
        idempotencyKey: 'payment-confirm-${current.id}',
      );
      if (!_isCurrent(operation)) return false;
      _payment = confirmed;
      if (confirmed.status == PaymentStatus.succeeded) {
        _applyStatus(confirmed);
        _notify();
        return true;
      }
      _applyStatus(confirmed);
      _notify();
      if (confirmed.status == PaymentStatus.created ||
          confirmed.status == PaymentStatus.processing) {
        return _poll(operation);
      }
      return false;
    } on Object catch (error) {
      if (_isCurrent(operation)) {
        _stage = PaymentFlowStage.processing;
        _errorCode = 'PAYMENT_STATUS_UNAVAILABLE';
        _errorMessage = _messageFor(error);
        _notify();
      }
      return false;
    }
  }

  Future<bool> _continuePayment(
    int operation,
    PaymentAttempt current,
  ) async {
    _applyStatus(current);
    _notify();
    if (current.status == PaymentStatus.succeeded) return true;
    if (current.status == PaymentStatus.requiresAction) {
      if (current.nextAction == null) {
        _errorCode = 'PAYMENT_NEXT_ACTION_MISSING';
        _stage = PaymentFlowStage.failed;
        _notify();
        return false;
      }
      _stage = PaymentFlowStage.requiresAction;
      _notify();
      final actionResult = await _actionLauncher.launch(current);
      if (!_isCurrent(operation)) return false;
      switch (actionResult.outcome) {
        case PaymentActionOutcome.cancelled:
          _stage = PaymentFlowStage.cancelled;
          _notify();
          return false;
        case PaymentActionOutcome.unavailable:
          _errorCode = actionResult.errorCode ?? 'PAYMENT_ACTION_UNAVAILABLE';
          _stage = PaymentFlowStage.failed;
          _notify();
          return false;
        case PaymentActionOutcome.completed:
        case PaymentActionOutcome.launched:
          break;
      }
      _awaitingExternalReturn = current.provider == PaymentProvider.paypal &&
          actionResult.outcome == PaymentActionOutcome.launched;
      if (current.provider == PaymentProvider.paypal &&
          actionResult.outcome == PaymentActionOutcome.completed) {
        final confirmed = await _repository.confirmPayment(
          current.id,
          idempotencyKey: 'payment-confirm-${current.id}',
        );
        if (!_isCurrent(operation)) return false;
        _payment = confirmed;
        if (confirmed.status == PaymentStatus.succeeded) {
          _applyStatus(confirmed);
          _notify();
          return true;
        }
      }
    }
    return _poll(operation);
  }

  Future<bool> _poll(int operation) async {
    final initial = _payment;
    if (initial == null) return false;
    _stage = PaymentFlowStage.processing;
    _notify();
    for (final delay in _pollingDelays) {
      if (delay > Duration.zero) await Future<void>.delayed(delay);
      if (!_isCurrent(operation)) return false;
      try {
        final refreshed = await _repository.getPayment(
          initial.id,
          refresh: true,
        );
        if (!_isCurrent(operation)) return false;
        _payment = refreshed;
        _applyStatus(refreshed);
        _notify();
        if (refreshed.status == PaymentStatus.succeeded) return true;
        if (!_isPending(refreshed.status)) return false;
      } on Object catch (error) {
        if (!_isCurrent(operation)) return false;
        _errorMessage = _messageFor(error);
        _notify();
      }
    }
    // Processing is not a failure: webhook or reconciliation may still finish
    // the payment. The user can leave safely and refresh from the order later.
    _stage = PaymentFlowStage.processing;
    _notify();
    return false;
  }

  void retryWithNewAttempt() {
    if (isBusy) return;
    _payment = null;
    _awaitingExternalReturn = false;
    if (!_providers.contains(_selectedProvider)) {
      _selectedProvider = _providers.first;
    }
    _createIdempotencyKey = generateApiRequestId();
    _stage = PaymentFlowStage.ready;
    _clearError();
    _notify();
  }

  void _applyStatus(PaymentAttempt value) {
    _stage = switch (value.status) {
      PaymentStatus.succeeded => PaymentFlowStage.succeeded,
      PaymentStatus.requiresAction => PaymentFlowStage.requiresAction,
      PaymentStatus.created ||
      PaymentStatus.processing =>
        PaymentFlowStage.processing,
      PaymentStatus.cancelled => PaymentFlowStage.cancelled,
      PaymentStatus.expired => PaymentFlowStage.expired,
      PaymentStatus.partiallyRefunded => PaymentFlowStage.partiallyRefunded,
      PaymentStatus.refunded => PaymentFlowStage.refunded,
      PaymentStatus.unknown => PaymentFlowStage.processing,
      PaymentStatus.failed => PaymentFlowStage.failed,
    };
    _errorCode = value.failureCode;
    _errorMessage = value.failureMessage;
  }

  void _failWith(Object error) {
    _stage = PaymentFlowStage.failed;
    _errorMessage = _messageFor(error);
    _notify();
  }

  void _clearError() {
    _errorCode = null;
    _errorMessage = null;
  }

  bool _isCurrent(int operation) => !_disposed && operation == _operation;

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _operation += 1;
    super.dispose();
  }
}

bool _isPending(PaymentStatus status) => const {
      PaymentStatus.created,
      PaymentStatus.requiresAction,
      PaymentStatus.processing,
      PaymentStatus.unknown,
    }.contains(status);

String _paymentMethod(PaymentProvider provider) => switch (provider) {
      PaymentProvider.demo => 'ONLINE',
      PaymentProvider.stripe => 'CARD',
      PaymentProvider.paypal => 'PAYPAL',
      PaymentProvider.wechatPay => 'WECHAT_PAY',
      PaymentProvider.alipay => 'ALIPAY',
      PaymentProvider.unknown => 'ONLINE',
    };

String _messageFor(Object error) {
  if (error is ApiException && error.message.trim().isNotEmpty) {
    return error.message.trim();
  }
  if (error is FormatException && error.message.trim().isNotEmpty) {
    return error.message.trim();
  }
  return 'PAYMENT_REQUEST_FAILED';
}

bool _outcomeMayBeUnknown(Object error) {
  if (error is FormatException) return true;
  return error is ApiException &&
      (error.httpStatus == null || error.businessCode == 503);
}

bool _isMissingPayment(Object error) {
  if (error is! ApiException) return false;
  return error.message.trim().toUpperCase() == 'PAYMENT_NOT_FOUND';
}
