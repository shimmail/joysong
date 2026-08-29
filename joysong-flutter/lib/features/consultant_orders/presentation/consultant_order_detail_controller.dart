import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_order_models.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_orders_repository.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_orders_controller.dart';

enum ConsultantOrderDetailLoadStatus {
  idle,
  loading,
  ready,
  failure,
  accessRevoked,
}

final class ConsultantOrderDetailController extends ChangeNotifier {
  ConsultantOrderDetailController(
    this._repository, {
    required this.orderId,
    required ConsultantRoleRequiredCallback onConsultantRoleRequired,
  }) : _onConsultantRoleRequired = onConsultantRoleRequired;

  final ConsultantOrdersRepository _repository;
  final String orderId;
  final ConsultantRoleRequiredCallback _onConsultantRoleRequired;

  ConsultantOrderDetailLoadStatus _status =
      ConsultantOrderDetailLoadStatus.idle;
  ConsultantOrderDetail? _detail;
  ConsultantOrderFailure? _failure;
  int _generation = 0;
  bool _accessRevoked = false;
  bool _roleCallbackDispatched = false;
  bool _disposed = false;

  ConsultantOrderDetailLoadStatus get status => _status;
  ConsultantOrderDetail? get detail => _detail;
  ConsultantOrderFailure? get failure => _failure;

  Future<void> load() async {
    if (_disposed || _accessRevoked) return;
    final generation = ++_generation;
    _status = ConsultantOrderDetailLoadStatus.loading;
    _detail = null;
    _failure = null;
    _notifyIfAlive();
    if (!_isCurrent(generation)) return;

    try {
      final detail = await _repository.getOrder(orderId);
      if (!_isCurrent(generation)) return;
      _detail = detail;
      _status = ConsultantOrderDetailLoadStatus.ready;
      _notifyIfAlive();
    } on Object catch (error) {
      if (!_isCurrent(generation)) return;
      if (_isRoleRequired(error)) {
        await _revokeAccess();
        return;
      }
      _status = ConsultantOrderDetailLoadStatus.failure;
      _failure = consultantOrderFailureFor(error);
      _notifyIfAlive();
    }
  }

  Future<void> _revokeAccess() async {
    if (_disposed || _accessRevoked || _roleCallbackDispatched) return;
    _accessRevoked = true;
    _roleCallbackDispatched = true;
    final terminalGeneration = ++_generation;
    _status = ConsultantOrderDetailLoadStatus.accessRevoked;
    _detail = null;
    _failure = null;
    _notifyIfAlive();
    if (_disposed ||
        !_accessRevoked ||
        terminalGeneration != _generation) {
      return;
    }
    await _onConsultantRoleRequired();
  }

  bool _isCurrent(int generation) =>
      !_disposed &&
      !_accessRevoked &&
      generation == _generation;

  void _notifyIfAlive() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    if (_disposed) return;
    _disposed = true;
    _generation += 1;
    super.dispose();
  }
}

bool _isRoleRequired(Object error) =>
    error is ApiException &&
    error.errorCode == 'CONSULTANT_ROLE_REQUIRED';
