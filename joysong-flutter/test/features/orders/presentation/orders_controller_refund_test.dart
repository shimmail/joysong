import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/domain/money.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/domain/orders_repository.dart';
import 'package:joysong_flutter/features/orders/domain/refund_evidence_models.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_controller.dart';

void main() {
  test('travel service order uses service fee refund with empty evidence files', () async {
    final order = _order(flow: OrderPaymentFlow.travelGroundServiceOnly);
    final repository = _RecordingOrdersRepository(order: order);
    final controller = OrderDetailController(
      repository,
      orderId: order.id,
      initialOrder: order,
    );

    final success = await controller.requestRefund(
      reason: 'Changed plans',
      description: 'Cannot travel',
    );

    expect(success, isTrue);
    expect(repository.serviceFeeEvidenceFiles, isEmpty);
    expect(repository.legacyEvidenceUrl, isNull);
    expect(controller.refund?.id, 'refund-1');
    expect(controller.activeAction, isNull);
  });

  test('legacy medical order uses JSON refund and preserves evidenceUrl', () async {
    final order = _order(flow: OrderPaymentFlow.legacyMedical);
    final repository = _RecordingOrdersRepository(order: order);
    final controller = OrderDetailController(
      repository,
      orderId: order.id,
      initialOrder: order,
    );

    final success = await controller.requestRefund(
      reason: 'Changed plans',
      description: 'Cannot travel',
      evidenceUrl: ' https://media.example/receipt.jpg ',
    );

    expect(success, isTrue);
    expect(repository.serviceFeeEvidenceFiles, isNull);
    expect(repository.legacyEvidenceUrl, 'https://media.example/receipt.jpg');
    expect(controller.refund?.id, 'refund-1');
    expect(controller.activeAction, isNull);
  });

  test('failed service fee refund reports error without mutating caller draft', () async {
    final order = _order(flow: OrderPaymentFlow.travelGroundServiceOnly);
    final repository = _RecordingOrdersRepository(order: order, failsServiceFee: true);
    final controller = OrderDetailController(
      repository,
      orderId: order.id,
      initialOrder: order,
    );
    final draft = RefundEvidenceDraft(
      bytes: Uint8List.fromList([1, 2, 3]),
      fileName: 'receipt.jpg',
      contentType: 'image/jpeg',
    );
    final drafts = [draft];

    final success = await controller.requestRefund(
      reason: 'Changed plans',
      description: 'Cannot travel',
      evidenceFiles: drafts,
    );

    expect(success, isFalse);
    expect(controller.errorMessage, '退款申请失败');
    expect(drafts, hasLength(1));
    expect(identical(drafts.single, draft), isTrue);
    expect(draft.bytes, orderedEquals([1, 2, 3]));
    expect(repository.serviceFeeEvidenceFiles, same(drafts));
  });

  test(
      'service fee refund reconciles an accepted request when its response is lost',
      () async {
    final order = _order(flow: OrderPaymentFlow.travelGroundServiceOnly);
    final repository = _RecordingOrdersRepository(
      order: order,
      failsServiceFee: true,
      refreshedOrder: order.copyWith(
        status: OrderStatus.refundReview,
        refundStatus: RefundStatus.pending,
        serviceMessagingEnabled: false,
      ),
    );
    final controller = OrderDetailController(
      repository,
      orderId: order.id,
      initialOrder: order,
    );

    final success = await controller.requestRefund(
      reason: 'Changed plans',
      description: 'Cannot travel',
    );

    expect(success, isTrue);
    expect(repository.serviceFeeRefundCalls, 1);
    expect(repository.getOrderCalls, 1);
    expect(repository.getRefundCalls, 1);
    expect(controller.order?.refundStatus, RefundStatus.pending);
    expect(controller.refund?.id, 'refund-1');
    expect(controller.errorMessage, isNull);
  });

  test(
      'unconfirmed service fee refund blocks resubmission until order refresh succeeds',
      () async {
    final order = _order(flow: OrderPaymentFlow.travelGroundServiceOnly);
    final repository = _RecordingOrdersRepository(
      order: order,
      failsServiceFee: true,
      failsGetOrder: true,
    );
    final controller = OrderDetailController(
      repository,
      orderId: order.id,
      initialOrder: order,
    );

    final first = await controller.requestRefund(reason: 'Changed plans');
    final blockedRetry =
        await controller.requestRefund(reason: 'Changed plans');

    expect(first, isFalse);
    expect(blockedRetry, isFalse);
    expect(repository.serviceFeeRefundCalls, 1);
    expect(repository.getOrderCalls, 1);
    expect(controller.errorMessage, '退款申请结果待确认，请刷新订单详情后再试');

    repository.failsGetOrder = false;
    await controller.load();
    await controller.requestRefund(reason: 'Changed plans');

    expect(repository.serviceFeeRefundCalls, 2);
    expect(repository.getOrderCalls, 3);
  });

  test('unloaded order reports an error without selecting either refund API', () async {
    final order = _order(flow: OrderPaymentFlow.travelGroundServiceOnly);
    final repository = _RecordingOrdersRepository(order: order);
    final controller = OrderDetailController(repository, orderId: order.id);

    final success = await controller.requestRefund(
      reason: 'Changed plans',
      description: 'Cannot travel',
    );

    expect(success, isFalse);
    expect(controller.errorMessage, '订单详情尚未加载，无法申请退款');
    expect(repository.serviceFeeRefundCalls, 0);
    expect(repository.legacyRefundCalls, 0);
  });
}

final class _RecordingOrdersRepository implements OrdersRepository {
  _RecordingOrdersRepository({
    required this.order,
    this.failsServiceFee = false,
    this.failsGetOrder = false,
    Order? refreshedOrder,
  }) : refreshedOrder = refreshedOrder ?? order;

  final Order order;
  final Order refreshedOrder;
  final bool failsServiceFee;
  bool failsGetOrder;
  List<RefundEvidenceDraft>? serviceFeeEvidenceFiles;
  String? legacyEvidenceUrl;
  int serviceFeeRefundCalls = 0;
  int legacyRefundCalls = 0;
  int getOrderCalls = 0;
  int getRefundCalls = 0;

  @override
  Future<Order> getOrder(String id) async {
    getOrderCalls += 1;
    if (failsGetOrder) throw StateError('offline');
    return refreshedOrder;
  }

  @override
  Future<RefundDetail> getRefund(String id) async {
    getRefundCalls += 1;
    return _refund;
  }

  @override
  Future<RefundDetail> requestServiceFeeRefund(
    String id, {
    required String reason,
    required String description,
    String? reasonCode,
    List<RefundEvidenceDraft> evidenceFiles = const [],
  }) async {
    serviceFeeRefundCalls += 1;
    serviceFeeEvidenceFiles = evidenceFiles;
    if (failsServiceFee) throw StateError('offline');
    return _refund;
  }

  @override
  Future<RefundDetail> requestRefund(
    String id, {
    required String reason,
    String description = '',
    String evidenceUrl = '',
  }) async {
    legacyRefundCalls += 1;
    legacyEvidenceUrl = evidenceUrl;
    return _refund;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnsupportedError(invocation.memberName.toString());
}

Order _order({required OrderPaymentFlow flow}) => Order(
      id: 'order-1',
      orderNo: 'O-1',
      projectId: 'project-1',
      institutionProjectId: 'institution-project-1',
      institutionId: 'institution-1',
      doctorId: 'doctor-1',
      projectName: 'Project',
      institutionName: 'Institution',
      doctorName: 'Doctor',
      coverImage: '',
      amount: Money.parse('23.50'),
      paidAmount: Money.parse('23.50'),
      discountAmount: Money.zero,
      consultationFee: Money.zero,
      remainingAmount: Money.zero,
      refundAmount: Money.parse('23.50'),
      status: flow == OrderPaymentFlow.travelGroundServiceOnly
          ? OrderStatus.serviceActive
          : OrderStatus.consultationPaid,
      paymentFlow: flow,
      refundStatus: RefundStatus.none,
      quantity: 1,
      remark: '',
      verifyCode: null,
      hasReview: false,
      createdAt: DateTime.parse('2026-08-30T10:00:00Z'),
    );

final _refund = RefundDetail.fromJson(const {
  'id': 'refund-1',
  'orderId': 'order-1',
  'amount': '23.50',
  'reason': 'Changed plans',
  'description': 'Cannot travel',
  'status': 'PENDING',
  'createdAt': '2026-08-30T10:00:00Z',
});
