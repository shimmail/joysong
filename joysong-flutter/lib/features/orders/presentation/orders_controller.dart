import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/domain/orders_repository.dart';

final class OrdersController extends ChangeNotifier {
  OrdersController(this._repository, {this.pageSize = 20});

  final OrdersRepository _repository;
  final int pageSize;

  List<Order> _orders = const [];
  OrderStatus? _filter;
  String? _errorMessage;
  bool _isLoading = false;
  bool _isLoadingMore = false;
  bool _hasMore = true;

  List<Order> get orders => _orders;
  OrderStatus? get filter => _filter;
  String? get errorMessage => _errorMessage;
  bool get isLoading => _isLoading;
  bool get isLoadingMore => _isLoadingMore;
  bool get hasMore => _hasMore;

  Future<void> load({OrderStatus? filter}) async {
    if (_isLoading) return;
    _filter = filter;
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final page = await _repository.getOrders(
        status: _filter,
        offset: 0,
        limit: pageSize,
      );
      _orders = page;
      _hasMore = page.length == pageSize;
    } catch (error) {
      _orders = const [];
      _hasMore = false;
      _errorMessage = _orderMessageFor(error, '订单加载失败');
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  Future<void> refresh() => load(filter: _filter);

  Future<void> loadMore() async {
    if (_isLoading || _isLoadingMore || !_hasMore) return;
    _isLoadingMore = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final page = await _repository.getOrders(
        status: _filter,
        offset: _orders.length,
        limit: pageSize,
      );
      final existingIds = _orders.map((order) => order.id).toSet();
      _orders = [
        ..._orders,
        ...page.where((order) => existingIds.add(order.id)),
      ];
      _hasMore = page.length == pageSize;
    } catch (error) {
      _errorMessage = _orderMessageFor(error, '更多订单加载失败');
    } finally {
      _isLoadingMore = false;
      notifyListeners();
    }
  }
}

enum OrderAction {
  verificationCode,
  confirmCompletion,
  cancel,
  refund,
  cancelRefund,
  delete,
}

final class OrderDetailController extends ChangeNotifier {
  OrderDetailController(this._repository, {required this.orderId});

  final OrdersRepository _repository;
  final String orderId;

  OrdersRepository get repository => _repository;

  Order? _order;
  RefundDetail? _refund;
  Settlement? _settlement;
  List<OrderStatusLog> _statusLogs = const [];
  String? _errorMessage;
  String? _settlementErrorMessage;
  bool _isSettlementGenerationPending = false;
  bool _isLoading = false;
  OrderAction? _activeAction;
  bool _isRemoved = false;
  int _detailGeneration = 0;

  Order? get order => _order;
  RefundDetail? get refund => _refund;
  Settlement? get settlement => _settlement;
  List<OrderStatusLog> get statusLogs => _statusLogs;
  String? get errorMessage => _errorMessage;
  String? get settlementErrorMessage => _settlementErrorMessage;
  bool get isSettlementGenerationPending => _isSettlementGenerationPending;
  bool get isLoading => _isLoading;
  OrderAction? get activeAction => _activeAction;
  bool get isBusy => _isLoading || _activeAction != null;
  bool get isRemoved => _isRemoved;

  Future<void> load() async {
    if (_isLoading || _activeAction != null) return;
    final generation = ++_detailGeneration;
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final detail = await _repository.getOrder(orderId);
      if (!_isCurrent(generation)) return;
      _order = detail;
      _isRemoved = false;
      await _loadSupportingData(detail, generation);
    } catch (error) {
      if (_isCurrent(generation)) {
        _errorMessage = _orderMessageFor(error, '订单详情加载失败');
      }
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  Future<bool> requestVerificationCode() => _runOrderAction(
        OrderAction.verificationCode,
        () => _repository.requestVerificationCode(orderId),
      );

  Future<bool> confirmCompletion() => _runOrderAction(
        OrderAction.confirmCompletion,
        () => _repository.confirmCompletion(orderId),
        refreshDetail: true,
      );

  Future<bool> cancel() => _runVoidAction(
        OrderAction.cancel,
        () => _repository.cancelOrder(orderId),
        removesOrder: true,
      );

  Future<bool> requestRefund({
    required String reason,
    String description = '',
    String evidenceUrl = '',
  }) async {
    if (reason.trim().isEmpty) {
      _errorMessage = '请选择或填写退款原因';
      notifyListeners();
      return false;
    }
    if (_activeAction != null) return false;
    final generation = ++_detailGeneration;
    _activeAction = OrderAction.refund;
    _errorMessage = null;
    notifyListeners();
    try {
      _refund = await _repository.requestRefund(
        orderId,
        reason: reason.trim(),
        description: description.trim(),
        evidenceUrl: evidenceUrl.trim(),
      );
      final detail = await _repository.getOrder(orderId);
      if (!_isCurrent(generation)) return false;
      _order = detail;
      return true;
    } catch (error) {
      _errorMessage = _orderMessageFor(error, '退款申请失败');
      return false;
    } finally {
      _activeAction = null;
      notifyListeners();
    }
  }

  Future<bool> cancelRefund() async {
    final success = await _runVoidAction(
      OrderAction.cancelRefund,
      () => _repository.cancelRefund(orderId),
    );
    if (success) await _reloadOrderOnly();
    return success;
  }

  Future<bool> delete() => _runVoidAction(
        OrderAction.delete,
        () => _repository.deleteOrder(orderId),
        removesOrder: true,
      );

  Future<void> retrySettlement() async {
    if (isBusy || _order == null || !_supportsSettlement(_order!)) return;
    final generation = ++_detailGeneration;
    await _loadSettlement(generation);
    notifyListeners();
  }

  Future<bool> _runOrderAction(
    OrderAction action,
    Future<Order> Function() operation, {
    bool refreshDetail = false,
  }) async {
    if (_activeAction != null) return false;
    final generation = ++_detailGeneration;
    _activeAction = action;
    _errorMessage = null;
    notifyListeners();
    try {
      _order = await operation();
      if (refreshDetail) {
        final detail = await _repository.getOrder(orderId);
        if (!_isCurrent(generation)) return false;
        _order = detail;
        await _loadSupportingData(detail, generation);
      }
      return true;
    } catch (error) {
      _errorMessage = _orderMessageFor(error, '订单操作失败');
      return false;
    } finally {
      _activeAction = null;
      notifyListeners();
    }
  }

  Future<bool> _runVoidAction(
    OrderAction action,
    Future<void> Function() operation, {
    bool removesOrder = false,
  }) async {
    if (_activeAction != null) return false;
    ++_detailGeneration;
    _activeAction = action;
    _errorMessage = null;
    notifyListeners();
    try {
      await operation();
      if (removesOrder) _isRemoved = true;
      return true;
    } catch (error) {
      _errorMessage = _orderMessageFor(error, '订单操作失败');
      return false;
    } finally {
      _activeAction = null;
      notifyListeners();
    }
  }

  Future<void> _reloadOrderOnly() async {
    final generation = ++_detailGeneration;
    try {
      final detail = await _repository.getOrder(orderId);
      if (!_isCurrent(generation)) return;
      _order = detail;
      _refund = null;
    } catch (error) {
      if (_isCurrent(generation)) {
        _errorMessage = _orderMessageFor(error, '订单刷新失败');
      }
    }
    notifyListeners();
  }

  Future<void> _loadSupportingData(Order detail, int generation) async {
    List<OrderStatusLog> statusLogs;
    try {
      statusLogs = await _repository.getStatusLogs(orderId);
    } catch (_) {
      statusLogs = const [];
    }
    if (!_isCurrent(generation)) return;
    _statusLogs = statusLogs;
    if (detail.refundStatus != RefundStatus.none &&
        detail.refundStatus != RefundStatus.unknown) {
      try {
        final refund = await _repository.getRefund(orderId);
        if (!_isCurrent(generation)) return;
        _refund = refund;
      } catch (_) {
        if (!_isCurrent(generation)) return;
        _refund = null;
      }
    } else {
      _refund = null;
    }
    if (_supportsSettlement(detail)) {
      await _loadSettlement(generation);
    } else {
      if (!_isCurrent(generation)) return;
      _settlement = null;
      _settlementErrorMessage = null;
      _isSettlementGenerationPending = false;
    }
  }

  bool _supportsSettlement(Order order) =>
      !order.isTravelGroundServiceOnly &&
      const {
        OrderStatus.completed,
        OrderStatus.pendingSettlement,
        OrderStatus.settled,
      }.contains(order.status);

  Future<void> _loadSettlement(int generation) async {
    if (!_isCurrent(generation)) return;
    _settlement = null;
    _settlementErrorMessage = null;
    _isSettlementGenerationPending = false;
    try {
      final settlement = await _repository.getSettlement(orderId);
      if (!_isCurrent(generation)) return;
      _settlement = settlement;
    } catch (error) {
      if (!_isCurrent(generation)) return;
      if (_isSettlementNotGenerated(error)) {
        _isSettlementGenerationPending = true;
      } else {
        _settlementErrorMessage = _orderMessageFor(error, '结算详情加载失败');
      }
    }
  }

  bool _isSettlementNotGenerated(Object error) =>
      error is ApiException &&
      error.httpStatus == 409 &&
      error.message == 'SETTLEMENT_NOT_GENERATED';

  bool _isCurrent(int generation) => generation == _detailGeneration;
}

String _orderMessageFor(Object error, String fallback) {
  if (error is ApiException && error.message.isNotEmpty) return error.message;
  if (error is FormatException && error.message.isNotEmpty) {
    return error.message;
  }
  return fallback;
}
