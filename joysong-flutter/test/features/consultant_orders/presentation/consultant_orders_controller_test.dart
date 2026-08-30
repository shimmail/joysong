import 'dart:async';
import 'dart:collection';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_order_models.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_orders_repository.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_orders_controller.dart';

void main() {
  group('ConsultantOrdersController', () {
    test('each stage owns independent offset and loading state', () async {
      final repository = FakeConsultantOrdersRepository()
        ..enqueuePage(
          ConsultantOrderStage.active,
          page(offset: 0, ids: const ['a1'], hasMore: true),
        )
        ..enqueuePage(
          ConsultantOrderStage.paused,
          page(offset: 7, ids: const ['p1']),
        );
      final activeCompleter = repository.replaceNextWithCompleter(
        ConsultantOrderStage.active,
      );
      final controller = ConsultantOrdersController(
        repository,
        onConsultantRoleRequired: _noOpRoleCallback,
      );
      addTearDown(controller.dispose);

      final activeLoad = controller.load(ConsultantOrderStage.active);
      final pausedLoad = controller.load(ConsultantOrderStage.paused);
      await pausedLoad;

      expect(
        controller.stateFor(ConsultantOrderStage.active).status,
        ConsultantOrderListStatus.loading,
      );
      expect(
        controller.stateFor(ConsultantOrderStage.paused).items.single.id,
        'p1',
      );
      expect(
        controller.stateFor(ConsultantOrderStage.history).items,
        isEmpty,
      );
      activeCompleter.complete(
        page(offset: 0, ids: const ['a1'], hasMore: true),
      );
      await Future.wait([activeLoad, pausedLoad]);

      expect(
        controller.stateFor(ConsultantOrderStage.active).items.single.id,
        'a1',
      );
      expect(controller.stateFor(ConsultantOrderStage.active).nextOffset, 1);
      expect(controller.stateFor(ConsultantOrderStage.paused).nextOffset, 8);
    });

    test('loadMore uses the server offset and stops on an empty page',
        () async {
      final repository = FakeConsultantOrdersRepository()
        ..enqueuePage(
          ConsultantOrderStage.active,
          page(offset: 4, ids: const ['a1'], hasMore: true),
        )
        ..enqueuePage(
          ConsultantOrderStage.active,
          page(offset: 99, ids: const [], hasMore: true),
        );
      final controller = ConsultantOrdersController(
        repository,
        onConsultantRoleRequired: _noOpRoleCallback,
      );
      addTearDown(controller.dispose);

      await controller.load(ConsultantOrderStage.active);
      await controller.loadMore(ConsultantOrderStage.active);

      expect(repository.listRequests.last.offset, 5);
      expect(controller.stateFor(ConsultantOrderStage.active).nextOffset, 99);
      expect(
        controller.stateFor(ConsultantOrderStage.active).hasMore,
        isFalse,
      );
    });

    test('loadMore stops when a nonempty page makes no offset progress',
        () async {
      final repository = FakeConsultantOrdersRepository()
        ..enqueuePage(
          ConsultantOrderStage.active,
          page(offset: 0, ids: const ['a1', 'a2'], hasMore: true),
        )
        ..enqueuePage(
          ConsultantOrderStage.active,
          page(offset: 0, ids: const ['duplicate-window'], hasMore: true),
        );
      final controller = ConsultantOrdersController(
        repository,
        onConsultantRoleRequired: _noOpRoleCallback,
      );
      addTearDown(controller.dispose);

      await controller.load(ConsultantOrderStage.active);
      await controller.loadMore(ConsultantOrderStage.active);

      expect(controller.stateFor(ConsultantOrderStage.active).nextOffset, 1);
      expect(
        controller.stateFor(ConsultantOrderStage.active).hasMore,
        isFalse,
      );
    });

    test('pages are deduplicated by ID while retaining first-seen order',
        () async {
      final repository = FakeConsultantOrdersRepository()
        ..enqueuePage(
          ConsultantOrderStage.active,
          page(offset: 3, ids: const ['a1', 'a1', 'a2'], hasMore: true),
        )
        ..enqueuePage(
          ConsultantOrderStage.active,
          page(offset: 6, ids: const ['a2', 'a3', 'a3']),
        );
      final controller = ConsultantOrdersController(
        repository,
        onConsultantRoleRequired: _noOpRoleCallback,
      );
      addTearDown(controller.dispose);

      await controller.load(ConsultantOrderStage.active);
      await controller.loadMore(ConsultantOrderStage.active);

      expect(
        controller
            .stateFor(ConsultantOrderStage.active)
            .items
            .map((item) => item.id),
        const ['a1', 'a2', 'a3'],
      );
      expect(repository.listRequests.last.offset, 6);
      expect(controller.stateFor(ConsultantOrderStage.active).nextOffset, 9);
    });

    test('load-more failure retains the successful page and paging cursor',
        () async {
      final repository = FakeConsultantOrdersRepository()
        ..enqueuePage(
          ConsultantOrderStage.active,
          page(offset: 8, ids: const ['a1', 'a2'], hasMore: true),
        )
        ..enqueueListError(
          ConsultantOrderStage.active,
          const ApiException(message: 'server text', httpStatus: 503),
        );
      final controller = ConsultantOrdersController(
        repository,
        onConsultantRoleRequired: _noOpRoleCallback,
      );
      addTearDown(controller.dispose);

      await controller.load(ConsultantOrderStage.active);
      final before = controller.stateFor(ConsultantOrderStage.active);
      await controller.loadMore(ConsultantOrderStage.active);
      final after = controller.stateFor(ConsultantOrderStage.active);

      expect(after.items, same(before.items));
      expect(after.nextOffset, before.nextOffset);
      expect(after.hasMore, before.hasMore);
      expect(after.status, before.status);
      expect(after.isLoadingMore, isFalse);
      expect(after.loadMoreFailure, ConsultantOrderFailure.retryRequired);
    });

    test('refresh discards an older in-flight response', () async {
      final repository = FakeConsultantOrdersRepository();
      final older = repository.enqueueListCompleter(
        ConsultantOrderStage.active,
      );
      repository.enqueuePage(
        ConsultantOrderStage.active,
        page(offset: 12, ids: const ['fresh']),
      );
      final controller = ConsultantOrdersController(
        repository,
        onConsultantRoleRequired: _noOpRoleCallback,
      );
      addTearDown(controller.dispose);

      final olderLoad = controller.load(ConsultantOrderStage.active);
      await controller.refresh(ConsultantOrderStage.active);
      older.complete(page(offset: 0, ids: const ['stale']));
      await olderLoad;

      final state = controller.stateFor(ConsultantOrderStage.active);
      expect(state.items.single.id, 'fresh');
      expect(state.nextOffset, 13);
      expect(repository.listRequests.map((request) => request.offset), [0, 0]);
    });

    test('refresh ignores role loss from a stale first-page request',
        () async {
      final repository = FakeConsultantOrdersRepository();
      final older = repository.enqueueListCompleter(
        ConsultantOrderStage.active,
      );
      repository.enqueuePage(
        ConsultantOrderStage.active,
        page(offset: 10, ids: const ['fresh']),
      );
      var roleRequiredCalls = 0;
      final controller = ConsultantOrdersController(
        repository,
        onConsultantRoleRequired: () async {
          roleRequiredCalls += 1;
        },
      );
      addTearDown(controller.dispose);

      final olderLoad = controller.load(ConsultantOrderStage.active);
      await controller.refresh(ConsultantOrderStage.active);
      older.completeError(roleRequiredException);
      await olderLoad;

      final state = controller.stateFor(ConsultantOrderStage.active);
      expect(state.status, ConsultantOrderListStatus.ready);
      expect(state.items.single.id, 'fresh');
      expect(state.nextOffset, 11);
      expect(roleRequiredCalls, 0);
    });

    test('refresh ignores role loss from a stale load-more request',
        () async {
      final repository = FakeConsultantOrdersRepository()
        ..enqueuePage(
          ConsultantOrderStage.active,
          page(offset: 0, ids: const ['initial'], hasMore: true),
        );
      final olderLoadMore = repository.enqueueListCompleter(
        ConsultantOrderStage.active,
      );
      repository.enqueuePage(
        ConsultantOrderStage.active,
        page(offset: 20, ids: const ['fresh']),
      );
      var roleRequiredCalls = 0;
      final controller = ConsultantOrdersController(
        repository,
        onConsultantRoleRequired: () async {
          roleRequiredCalls += 1;
        },
      );
      addTearDown(controller.dispose);

      await controller.load(ConsultantOrderStage.active);
      final olderRequest = controller.loadMore(ConsultantOrderStage.active);
      await controller.refresh(ConsultantOrderStage.active);
      olderLoadMore.completeError(roleRequiredException);
      await olderRequest;

      final state = controller.stateFor(ConsultantOrderStage.active);
      expect(state.status, ConsultantOrderListStatus.ready);
      expect(state.items.single.id, 'fresh');
      expect(state.nextOffset, 21);
      expect(roleRequiredCalls, 0);
    });

    test('force load is a fresh offset-zero reload and invalidates old work',
        () async {
      final repository = FakeConsultantOrdersRepository()
        ..enqueuePage(
          ConsultantOrderStage.active,
          page(offset: 5, ids: const ['initial']),
        );
      final controller = ConsultantOrdersController(
        repository,
        onConsultantRoleRequired: _noOpRoleCallback,
      );
      addTearDown(controller.dispose);

      await controller.load(ConsultantOrderStage.active);
      await controller.load(ConsultantOrderStage.active);
      expect(repository.listRequests, hasLength(1));

      final older = repository.enqueueListCompleter(
        ConsultantOrderStage.active,
      );
      repository.enqueuePage(
        ConsultantOrderStage.active,
        page(offset: 20, ids: const ['forced']),
      );
      final refresh = controller.refresh(ConsultantOrderStage.active);
      final forced = controller.load(ConsultantOrderStage.active, force: true);
      older.complete(page(offset: 0, ids: const ['refresh-stale']));
      await Future.wait([refresh, forced]);

      expect(repository.listRequests, hasLength(3));
      expect(repository.listRequests[1].offset, 0);
      expect(repository.listRequests[2].offset, 0);
      expect(
        controller.stateFor(ConsultantOrderStage.active).items.single.id,
        'forced',
      );
    });

    test('list failures expose only local failure categories', () async {
      final repository = FakeConsultantOrdersRepository()
        ..enqueueListError(
          ConsultantOrderStage.active,
          const ApiException(
            message: 'server parsing text',
            cause: FormatException('invalid payload'),
          ),
        )
        ..enqueueListError(
          ConsultantOrderStage.paused,
          const ApiException(message: 'server status text', httpStatus: 500),
        )
        ..enqueueListError(
          ConsultantOrderStage.history,
          ApiException(
            message: 'transport text',
            cause: TimeoutException('request timeout'),
          ),
        );
      final controller = ConsultantOrdersController(
        repository,
        onConsultantRoleRequired: _noOpRoleCallback,
      );
      addTearDown(controller.dispose);

      await Future.wait([
        controller.load(ConsultantOrderStage.active),
        controller.load(ConsultantOrderStage.paused),
        controller.load(ConsultantOrderStage.history),
      ]);

      expect(
        controller.stateFor(ConsultantOrderStage.active).failure,
        ConsultantOrderFailure.invalidResponse,
      );
      expect(
        controller.stateFor(ConsultantOrderStage.paused).failure,
        ConsultantOrderFailure.retryRequired,
      );
      expect(
        controller.stateFor(ConsultantOrderStage.history).failure,
        ConsultantOrderFailure.unavailable,
      );
    });

    test('role-required is terminal across stages and dispatches once',
        () async {
      final repository = FakeConsultantOrdersRepository();
      final active = repository.enqueueListCompleter(
        ConsultantOrderStage.active,
      );
      final paused = repository.enqueueListCompleter(
        ConsultantOrderStage.paused,
      );
      final callbackCompleter = Completer<void>();
      var roleRequiredCalls = 0;
      var notifications = 0;
      final controller = ConsultantOrdersController(
        repository,
        onConsultantRoleRequired: () {
          roleRequiredCalls += 1;
          return callbackCompleter.future;
        },
      )..addListener(() => notifications += 1);
      addTearDown(controller.dispose);

      final activeLoad = controller.load(ConsultantOrderStage.active);
      final pausedLoad = controller.load(ConsultantOrderStage.paused);
      final notificationsBeforeFailure = notifications;
      active.completeError(roleRequiredException);
      paused.completeError(roleRequiredException);
      await Future<void>.delayed(Duration.zero);

      for (final stage in ConsultantOrderStage.values) {
        expect(
          controller.stateFor(stage).status,
          ConsultantOrderListStatus.accessRevoked,
        );
      }
      expect(roleRequiredCalls, 1);
      expect(notifications, notificationsBeforeFailure + 1);

      callbackCompleter.complete();
      await Future.wait([activeLoad, pausedLoad]);
      final requestCount = repository.listRequests.length;
      await controller.load(ConsultantOrderStage.history);
      await controller.loadMore(ConsultantOrderStage.active);
      await controller.refresh(ConsultantOrderStage.paused);
      expect(repository.listRequests, hasLength(requestCount));
      expect(roleRequiredCalls, 1);
      expect(notifications, notificationsBeforeFailure + 1);
    });

    test('dispose blocks all post-await list state and callback work',
        () async {
      final successRepository = FakeConsultantOrdersRepository();
      final success = successRepository.enqueueListCompleter(
        ConsultantOrderStage.active,
      );
      var successNotifications = 0;
      final successController = ConsultantOrdersController(
        successRepository,
        onConsultantRoleRequired: _noOpRoleCallback,
      )..addListener(() => successNotifications += 1);
      final successLoad = successController.load(ConsultantOrderStage.active);
      successController.dispose();
      success.complete(page(offset: 0, ids: const ['late']));
      await successLoad;

      expect(successNotifications, 1);
      expect(
        successController.stateFor(ConsultantOrderStage.active).status,
        ConsultantOrderListStatus.loading,
      );

      final roleRepository = FakeConsultantOrdersRepository();
      final roleFailure = roleRepository.enqueueListCompleter(
        ConsultantOrderStage.active,
      );
      var roleRequiredCalls = 0;
      var roleNotifications = 0;
      final roleController = ConsultantOrdersController(
        roleRepository,
        onConsultantRoleRequired: () async {
          roleRequiredCalls += 1;
        },
      )..addListener(() => roleNotifications += 1);
      final roleLoad = roleController.load(ConsultantOrderStage.active);
      roleController.dispose();
      roleFailure.completeError(roleRequiredException);
      await roleLoad;

      expect(roleRequiredCalls, 0);
      expect(roleNotifications, 1);
      expect(
        roleController.stateFor(ConsultantOrderStage.active).status,
        ConsultantOrderListStatus.loading,
      );
    });
  });
}

const roleRequiredException = ApiException(
  message: 'server role text',
  httpStatus: 403,
  businessCode: 403,
  errorCode: 'CONSULTANT_ROLE_REQUIRED',
);

Future<void> _noOpRoleCallback() async {}

ConsultantOrderPage page({
  required int offset,
  required List<String> ids,
  bool hasMore = false,
}) =>
    ConsultantOrderPage(
      items: List.unmodifiable(ids.map(summary)),
      offset: offset,
      limit: 20,
      hasMore: hasMore,
    );

ConsultantOrderSummary summary(String id) => ConsultantOrderSummary(
      id: id,
      orderNo: 'NO-$id',
      stage: ConsultantOrderStage.active,
      status: 'SERVICE_ACTIVE',
      refundStatus: 'NONE',
      project: const ConsultantOrderProject(
        id: 'project-1',
        name: 'Project',
        coverImage: '',
      ),
      institution: const ConsultantOrderInstitution(
        id: 'institution-1',
        name: 'Institution',
      ),
      customer: const ConsultantOrderCustomer(
        displayName: 'Customer',
        avatar: null,
      ),
      appointmentTime: null,
      updatedAt: DateTime.utc(2026, 8, 29),
      conversationReadable: true,
      messageSendable: true,
      readOnly: false,
    );

final class ListRequest {
  const ListRequest({
    required this.stage,
    required this.offset,
    required this.limit,
  });

  final ConsultantOrderStage stage;
  final int offset;
  final int limit;
}

final class FakeConsultantOrdersRepository
    implements ConsultantOrdersRepository {
  final Map<
    ConsultantOrderStage,
    Queue<Future<ConsultantOrderPage> Function()>
  > _listResponses = {};
  final List<ListRequest> listRequests = [];

  void enqueuePage(ConsultantOrderStage stage, ConsultantOrderPage value) {
    _queueFor(stage).add(() => Future.value(value));
  }

  void enqueueListError(ConsultantOrderStage stage, Object error) {
    _queueFor(stage).add(() => Future.error(error));
  }

  Completer<ConsultantOrderPage> enqueueListCompleter(
    ConsultantOrderStage stage,
  ) {
    final completer = Completer<ConsultantOrderPage>();
    _queueFor(stage).add(() => completer.future);
    return completer;
  }

  Completer<ConsultantOrderPage> replaceNextWithCompleter(
    ConsultantOrderStage stage,
  ) {
    final queue = _queueFor(stage);
    queue.removeFirst();
    final completer = Completer<ConsultantOrderPage>();
    queue.addFirst(() => completer.future);
    return completer;
  }

  Queue<Future<ConsultantOrderPage> Function()> _queueFor(
    ConsultantOrderStage stage,
  ) =>
      _listResponses.putIfAbsent(stage, Queue.new);

  @override
  Future<ConsultantOrderPage> getOrders({
    required ConsultantOrderStage stage,
    String? institutionId,
    required int offset,
    required int limit,
  }) {
    listRequests.add(ListRequest(stage: stage, offset: offset, limit: limit));
    final queue = _queueFor(stage);
    if (queue.isEmpty) {
      return Future.error(StateError('No list response for $stage'));
    }
    return queue.removeFirst()();
  }

  @override
  Future<ConsultantOrderDetail> getOrder(String orderId) =>
      Future.error(StateError('No detail response for $orderId'));
}
