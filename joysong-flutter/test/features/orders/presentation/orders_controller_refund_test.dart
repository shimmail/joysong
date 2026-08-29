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
}

final class _RecordingOrdersRepository implements OrdersRepository {
  _RecordingOrdersRepository({required this.order, this.failsServiceFee = false});

  final Order order;
  final bool failsServiceFee;
  List<RefundEvidenceDraft>? serviceFeeEvidenceFiles;
  String? legacyEvidenceUrl;

  @override
  Future<Order> getOrder(String id) async => order;

  @override
  Future<RefundDetail> requestServiceFeeRefund(
    String id, {
    required String reason,
    required String description,
    String? reasonCode,
    List<RefundEvidenceDraft> evidenceFiles = const [],
  }) async {
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
