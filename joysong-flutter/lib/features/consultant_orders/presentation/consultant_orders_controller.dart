import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_order_models.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_orders_repository.dart';

typedef ConsultantRoleRequiredCallback = Future<void> Function();

enum ConsultantOrderListStatus {
  idle,
  loading,
  ready,
  failure,
  accessRevoked,
}

enum ConsultantOrderFailure { unavailable, invalidResponse, retryRequired }

final class ConsultantOrderListState {
  const ConsultantOrderListState({
    this.status = ConsultantOrderListStatus.idle,
    this.items = const [],
    this.nextOffset = 0,
    this.hasMore = true,
    this.isLoadingMore = false,
    this.failure,
    this.loadMoreFailure,
  });

  final ConsultantOrderListStatus status;
  final List<ConsultantOrderSummary> items;
  final int nextOffset;
  final bool hasMore;
  final bool isLoadingMore;
  final ConsultantOrderFailure? failure;
  final ConsultantOrderFailure? loadMoreFailure;

  ConsultantOrderListState _copyWith({
    ConsultantOrderListStatus? status,
    List<ConsultantOrderSummary>? items,
    int? nextOffset,
    bool? hasMore,
    bool? isLoadingMore,
    Object? failure = _unchanged,
    Object? loadMoreFailure = _unchanged,
  }) =>
      ConsultantOrderListState(
        status: status ?? this.status,
        items: items ?? this.items,
        nextOffset: nextOffset ?? this.nextOffset,
        hasMore: hasMore ?? this.hasMore,
        isLoadingMore: isLoadingMore ?? this.isLoadingMore,
        failure: identical(failure, _unchanged)
            ? this.failure
            : failure as ConsultantOrderFailure?,
        loadMoreFailure: identical(loadMoreFailure, _unchanged)
            ? this.loadMoreFailure
            : loadMoreFailure as ConsultantOrderFailure?,
      );
}

final class ConsultantOrdersController extends ChangeNotifier {
  ConsultantOrdersController(
    this._repository, {
    required ConsultantRoleRequiredCallback onConsultantRoleRequired,
    this.pageSize = 20,
  }) : _onConsultantRoleRequired = onConsultantRoleRequired,
       _states = {
         for (final stage in ConsultantOrderStage.values)
           stage: const ConsultantOrderListState(),
       },
       _generations = {
         for (final stage in ConsultantOrderStage.values) stage: 0,
       };

  final ConsultantOrdersRepository _repository;
  final ConsultantRoleRequiredCallback _onConsultantRoleRequired;
  final int pageSize;
  final Map<ConsultantOrderStage, ConsultantOrderListState> _states;
  final Map<ConsultantOrderStage, int> _generations;

  bool _accessRevoked = false;
  bool _roleCallbackDispatched = false;
  bool _disposed = false;
  int _lifecycleGeneration = 0;

  ConsultantOrderListState stateFor(ConsultantOrderStage stage) =>
      _states[stage]!;

  Future<void> load(
    ConsultantOrderStage stage, {
    bool force = false,
  }) async {
    if (_disposed || _accessRevoked) return;
    if (!force && stateFor(stage).status != ConsultantOrderListStatus.idle) {
      return;
    }
    await _loadFirstPage(stage);
  }

  Future<void> refresh(ConsultantOrderStage stage) =>
      load(stage, force: true);

  Future<void> loadMore(ConsultantOrderStage stage) async {
    if (_disposed || _accessRevoked) return;
    final current = stateFor(stage);
    if (current.status != ConsultantOrderListStatus.ready ||
        current.isLoadingMore ||
        !current.hasMore) {
      return;
    }

    final generation = _generations[stage]!;
    final requestedOffset = current.nextOffset;
    _states[stage] = current._copyWith(
      isLoadingMore: true,
      loadMoreFailure: null,
    );
    _notifyIfAlive();
    if (!_isCurrent(stage, generation)) return;

    try {
      final page = await _repository.getOrders(
        stage: stage,
        offset: requestedOffset,
        limit: pageSize,
      );
      if (!_isCurrent(stage, generation)) return;
      final nextOffset = page.offset + page.items.length;
      final knownIds = current.items.map((item) => item.id).toSet();
      final merged = List<ConsultantOrderSummary>.unmodifiable([
        ...current.items,
        ...page.items.where((item) => knownIds.add(item.id)),
      ]);
      _states[stage] = current._copyWith(
        status: ConsultantOrderListStatus.ready,
        items: merged,
        nextOffset: nextOffset,
        hasMore: page.items.isNotEmpty &&
            page.hasMore &&
            nextOffset > requestedOffset,
        isLoadingMore: false,
        loadMoreFailure: null,
      );
      _notifyIfAlive();
    } on Object catch (error) {
      if (!_isCurrent(stage, generation)) return;
      if (_isRoleRequired(error)) {
        await _revokeAccess();
        return;
      }
      _states[stage] = current._copyWith(
        isLoadingMore: false,
        loadMoreFailure: consultantOrderFailureFor(error),
      );
      _notifyIfAlive();
    }
  }

  Future<void> _loadFirstPage(ConsultantOrderStage stage) async {
    final generation = _generations[stage]! + 1;
    _generations[stage] = generation;
    _states[stage] = const ConsultantOrderListState(
      status: ConsultantOrderListStatus.loading,
    );
    _notifyIfAlive();
    if (!_isCurrent(stage, generation)) return;

    try {
      final page = await _repository.getOrders(
        stage: stage,
        offset: 0,
        limit: pageSize,
      );
      if (!_isCurrent(stage, generation)) return;
      final nextOffset = page.offset + page.items.length;
      _states[stage] = ConsultantOrderListState(
        status: ConsultantOrderListStatus.ready,
        items: _dedupe(page.items),
        nextOffset: nextOffset,
        hasMore: page.items.isNotEmpty && page.hasMore && nextOffset > 0,
      );
      _notifyIfAlive();
    } on Object catch (error) {
      if (!_isCurrent(stage, generation)) return;
      if (_isRoleRequired(error)) {
        await _revokeAccess();
        return;
      }
      _states[stage] = ConsultantOrderListState(
        status: ConsultantOrderListStatus.failure,
        hasMore: false,
        failure: consultantOrderFailureFor(error),
      );
      _notifyIfAlive();
    }
  }

  Future<void> _revokeAccess() async {
    if (_disposed || _accessRevoked || _roleCallbackDispatched) return;
    _accessRevoked = true;
    _roleCallbackDispatched = true;
    final lifecycleGeneration = ++_lifecycleGeneration;
    for (final stage in ConsultantOrderStage.values) {
      _generations[stage] = _generations[stage]! + 1;
      _states[stage] = const ConsultantOrderListState(
        status: ConsultantOrderListStatus.accessRevoked,
        hasMore: false,
      );
    }
    _notifyIfAlive();
    if (_disposed ||
        !_accessRevoked ||
        lifecycleGeneration != _lifecycleGeneration) {
      return;
    }
    await _onConsultantRoleRequired();
  }

  bool _isCurrent(ConsultantOrderStage stage, int generation) =>
      !_disposed &&
      !_accessRevoked &&
      generation == _generations[stage];

  void _notifyIfAlive() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    if (_disposed) return;
    _disposed = true;
    _lifecycleGeneration += 1;
    for (final stage in ConsultantOrderStage.values) {
      _generations[stage] = _generations[stage]! + 1;
    }
    super.dispose();
  }
}

ConsultantOrderFailure consultantOrderFailureFor(Object error) {
  if (error is FormatException ||
      error is ApiException && error.cause is FormatException) {
    return ConsultantOrderFailure.invalidResponse;
  }
  if (error is ApiException &&
      (error.httpStatus != null || error.businessCode != null)) {
    return ConsultantOrderFailure.retryRequired;
  }
  return ConsultantOrderFailure.unavailable;
}

List<ConsultantOrderSummary> _dedupe(
  Iterable<ConsultantOrderSummary> items,
) {
  final ids = <String>{};
  return List.unmodifiable(items.where((item) => ids.add(item.id)));
}

bool _isRoleRequired(Object error) =>
    error is ApiException &&
    error.errorCode == 'CONSULTANT_ROLE_REQUIRED';

const _unchanged = Object();
