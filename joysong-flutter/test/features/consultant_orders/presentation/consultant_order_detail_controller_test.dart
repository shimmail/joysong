import 'dart:async';
import 'dart:collection';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_order_models.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_orders_repository.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_order_detail_controller.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_orders_controller.dart';

void main() {
  group('ConsultantOrderDetailController', () {
    test('surfaces a canonical server-refreshed detail', () async {
      final repository = FakeDetailRepository()..enqueueDetail(detail('o1'));
      final controller = ConsultantOrderDetailController(
        repository,
        orderId: 'o1',
        onConsultantRoleRequired: _noOpRoleCallback,
      );
      addTearDown(controller.dispose);

      await controller.load();

      expect(repository.orderIds, const ['o1']);
      expect(controller.detail?.summary.id, 'o1');
      expect(controller.status, ConsultantOrderDetailLoadStatus.ready);
      expect(controller.failure, isNull);
    });

    test('a newer load discards an older in-flight detail', () async {
      final repository = FakeDetailRepository();
      final older = repository.enqueueDetailCompleter();
      repository.enqueueDetail(detail('fresh'));
      final controller = ConsultantOrderDetailController(
        repository,
        orderId: 'o1',
        onConsultantRoleRequired: _noOpRoleCallback,
      );
      addTearDown(controller.dispose);

      final olderLoad = controller.load();
      await controller.load();
      older.complete(detail('stale'));
      await olderLoad;

      expect(repository.orderIds, const ['o1', 'o1']);
      expect(controller.detail?.summary.id, 'fresh');
      expect(controller.status, ConsultantOrderDetailLoadStatus.ready);
    });

    test('detail failures expose only local failure categories', () async {
      final cases = <(Object, ConsultantOrderFailure)>[
        (
          const ApiException(
            message: 'server parsing text',
            cause: FormatException('invalid payload'),
          ),
          ConsultantOrderFailure.invalidResponse,
        ),
        (
          const ApiException(message: 'server status text', httpStatus: 503),
          ConsultantOrderFailure.retryRequired,
        ),
        (
          ApiException(
            message: 'transport text',
            cause: TimeoutException('request timeout'),
          ),
          ConsultantOrderFailure.unavailable,
        ),
      ];

      for (final testCase in cases) {
        final repository = FakeDetailRepository()
          ..enqueueDetailError(testCase.$1);
        final controller = ConsultantOrderDetailController(
          repository,
          orderId: 'o1',
          onConsultantRoleRequired: _noOpRoleCallback,
        );

        await controller.load();

        expect(controller.status, ConsultantOrderDetailLoadStatus.failure);
        expect(controller.failure, testCase.$2);
        expect(controller.detail, isNull);
        controller.dispose();
      }
    });

    test('role-required is terminal and dispatches the callback once',
        () async {
      final repository = FakeDetailRepository();
      final first = repository.enqueueDetailCompleter();
      final second = repository.enqueueDetailCompleter();
      final callbackCompleter = Completer<void>();
      var roleRequiredCalls = 0;
      var notifications = 0;
      final controller = ConsultantOrderDetailController(
        repository,
        orderId: 'o1',
        onConsultantRoleRequired: () {
          roleRequiredCalls += 1;
          return callbackCompleter.future;
        },
      )..addListener(() => notifications += 1);
      addTearDown(controller.dispose);

      final firstLoad = controller.load();
      final secondLoad = controller.load();
      final notificationsBeforeFailure = notifications;
      first.completeError(roleRequiredException);
      second.completeError(roleRequiredException);
      await Future<void>.delayed(Duration.zero);

      expect(controller.status, ConsultantOrderDetailLoadStatus.accessRevoked);
      expect(controller.detail, isNull);
      expect(controller.failure, isNull);
      expect(roleRequiredCalls, 1);
      expect(notifications, notificationsBeforeFailure + 1);

      callbackCompleter.complete();
      await Future.wait([firstLoad, secondLoad]);
      await controller.load();
      expect(repository.orderIds, hasLength(2));
      expect(roleRequiredCalls, 1);
      expect(notifications, notificationsBeforeFailure + 1);
    });

    test('dispose blocks post-await detail state, notification, and callback',
        () async {
      final successRepository = FakeDetailRepository();
      final success = successRepository.enqueueDetailCompleter();
      var successNotifications = 0;
      final successController = ConsultantOrderDetailController(
        successRepository,
        orderId: 'o1',
        onConsultantRoleRequired: _noOpRoleCallback,
      )..addListener(() => successNotifications += 1);
      final successLoad = successController.load();
      successController.dispose();
      success.complete(detail('late'));
      await successLoad;

      expect(successNotifications, 1);
      expect(successController.detail, isNull);
      expect(
        successController.status,
        ConsultantOrderDetailLoadStatus.loading,
      );

      final roleRepository = FakeDetailRepository();
      final roleFailure = roleRepository.enqueueDetailCompleter();
      var roleRequiredCalls = 0;
      var roleNotifications = 0;
      final roleController = ConsultantOrderDetailController(
        roleRepository,
        orderId: 'o1',
        onConsultantRoleRequired: () async {
          roleRequiredCalls += 1;
        },
      )..addListener(() => roleNotifications += 1);
      final roleLoad = roleController.load();
      roleController.dispose();
      roleFailure.completeError(roleRequiredException);
      await roleLoad;

      expect(roleRequiredCalls, 0);
      expect(roleNotifications, 1);
      expect(
        roleController.status,
        ConsultantOrderDetailLoadStatus.loading,
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

ConsultantOrderDetail detail(String id) => ConsultantOrderDetail(
      summary: summary(id),
      doctor: const ConsultantOrderDoctor(id: 'doctor-1', name: 'Doctor'),
      remark: '',
      createdAt: DateTime.utc(2026, 8, 28),
      serviceActivatedAt: DateTime.utc(2026, 8, 29),
      completedAt: null,
      conversation: const ConsultantOrderConversationAccess(
        readable: true,
        sendable: true,
      ),
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

final class FakeDetailRepository implements ConsultantOrdersRepository {
  final Queue<Future<ConsultantOrderDetail> Function()> _detailResponses =
      Queue();
  final List<String> orderIds = [];

  void enqueueDetail(ConsultantOrderDetail value) {
    _detailResponses.add(() => Future.value(value));
  }

  void enqueueDetailError(Object error) {
    _detailResponses.add(() => Future.error(error));
  }

  Completer<ConsultantOrderDetail> enqueueDetailCompleter() {
    final completer = Completer<ConsultantOrderDetail>();
    _detailResponses.add(() => completer.future);
    return completer;
  }

  @override
  Future<ConsultantOrderPage> getOrders({
    required ConsultantOrderStage stage,
    String? institutionId,
    required int offset,
    required int limit,
  }) =>
      Future.error(StateError('Unexpected list request'));

  @override
  Future<ConsultantOrderDetail> getOrder(String orderId) {
    orderIds.add(orderId);
    if (_detailResponses.isEmpty) {
      return Future.error(StateError('No detail response for $orderId'));
    }
    return _detailResponses.removeFirst()();
  }
}
