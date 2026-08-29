import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/domain/money.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/domain/orders_repository.dart';
import 'package:joysong_flutter/features/orders/domain/refund_evidence_models.dart';
import 'package:joysong_flutter/features/orders/presentation/order_detail_page.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_controller.dart';

void main() {
  testWidgets('service fee page shows optional evidence and preserves jpg pdf order',
      (tester) async {
    final picks = [
      _evidence('first.jpg', 'image/jpeg', [0xff, 0xd8, 0xff]),
      _evidence('second.pdf', 'application/pdf', [0x25, 0x50, 0x44, 0x46]),
    ];
    await _pumpRefundPage(tester, onPickRefundEvidence: () async => picks.removeAt(0));

    expect(find.text('退款凭证（选填）'), findsOneWidget);
    expect(find.byKey(const Key('refund-evidence-count')), findsOneWidget);
    expect(find.text('0/5'), findsOneWidget);

    await tester.tap(find.byKey(const Key('refund-evidence-add')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('refund-evidence-add')));
    await tester.pumpAndSettle();

    expect(find.text('2/5'), findsOneWidget);
    expect(find.text('first.jpg'), findsOneWidget);
    expect(find.text('second.pdf'), findsOneWidget);
    expect(
      tester.getTopLeft(find.text('first.jpg')).dy,
      lessThan(tester.getTopLeft(find.text('second.pdf')).dy),
    );
  });

  testWidgets('service fee page removes evidence and disables add at five',
      (tester) async {
    var next = 0;
    await _pumpRefundPage(
      tester,
      onPickRefundEvidence: () async => _evidence(
        'evidence-${next++}.jpg',
        'image/jpeg',
        [0xff, 0xd8, 0xff],
      ),
    );

    for (var index = 0; index < RefundEvidenceDraft.maxCount; index++) {
      await tester.tap(find.byKey(const Key('refund-evidence-add')));
      await tester.pumpAndSettle();
    }

    expect(find.text('5/5'), findsOneWidget);
    expect(
      tester.widget<IconButton>(find.byKey(const Key('refund-evidence-add'))).onPressed,
      isNull,
    );

    await tester.tap(find.byKey(const Key('refund-evidence-remove-2')));
    await tester.pumpAndSettle();

    expect(find.text('4/5'), findsOneWidget);
    expect(find.text('evidence-2.jpg'), findsNothing);
    expect(
      tester.widget<IconButton>(find.byKey(const Key('refund-evidence-add'))).onPressed,
      isNotNull,
    );
  });

  testWidgets('service fee page rejects invalid evidence without changing selected files',
      (tester) async {
    final picks = [
      _evidence('valid.jpg', 'image/jpeg', [0xff, 0xd8, 0xff]),
      _evidence(
        'oversized.jpg',
        'image/jpeg',
        Uint8List(RefundEvidenceDraft.maxBytes + 1),
      ),
      _evidence('unsupported.txt', 'text/plain', [1]),
    ];
    await _pumpRefundPage(tester, onPickRefundEvidence: () async => picks.removeAt(0));

    for (var index = 0; index < 3; index++) {
      await tester.tap(find.byKey(const Key('refund-evidence-add')));
      await tester.pumpAndSettle();
    }

    expect(find.text('1/5'), findsOneWidget);
    expect(find.text('valid.jpg'), findsOneWidget);
    expect(find.text('oversized.jpg'), findsNothing);
    expect(find.text('unsupported.txt'), findsNothing);
    expect(find.byKey(const Key('refund-evidence-remove-0')), findsOneWidget);
  });

  testWidgets('legacy medical page keeps the public evidence picker without private list',
      (tester) async {
    var legacyPickCalls = 0;
    await _pumpRefundPage(
      tester,
      order: _order(flow: OrderPaymentFlow.legacyMedical),
      canUploadLegacyEvidence: true,
      onPickLegacyEvidence: () async {
        legacyPickCalls += 1;
        return 'https://media.example/evidence.jpg';
      },
    );

    expect(find.text('退款凭证（选填）'), findsNothing);
    expect(find.byKey(const Key('refund-evidence-add')), findsNothing);

    await tester.tap(find.byKey(const Key('refund-legacy-evidence-add')));
    await tester.pumpAndSettle();

    expect(legacyPickCalls, 1);
    expect(find.text('已添加凭证'), findsOneWidget);
  });

  testWidgets('submit disables edits and sends the exact ordered immutable draft',
      (tester) async {
    final submit = Completer<bool>();
    OrderRefundDraft? submitted;
    await _pumpRefundPage(
      tester,
      onPickRefundEvidence: () async => _evidence(
        'ordered.jpg',
        'image/jpeg',
        [0xff, 0xd8, 0xff],
      ),
      onSubmit: (draft) {
        submitted = draft;
        return submit.future;
      },
    );

    await tester.tap(find.byKey(const Key('refund-reason-0')));
    await tester.enterText(
      find.byKey(const Key('refund-description-field')),
      'Needs review',
    );
    await tester.tap(find.byKey(const Key('refund-evidence-add')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('refund-submit')));
    await tester.pump();

    expect(submitted?.reason, '不想去了');
    expect(submitted?.description, 'Needs review');
    expect(submitted?.evidenceUrl, isEmpty);
    expect(submitted?.evidenceFiles.map((file) => file.fileName), ['ordered.jpg']);
    expect(() => submitted!.evidenceFiles.add(_evidence('x.jpg', 'image/jpeg', [1])),
        throwsUnsupportedError);
    expect(
      tester.widget<IconButton>(find.byKey(const Key('refund-evidence-add'))).onPressed,
      isNull,
    );
    expect(
      tester.widget<IconButton>(find.byKey(const Key('refund-evidence-remove-0'))).onPressed,
      isNull,
    );
    expect(
      tester.widget<FilledButton>(find.byKey(const Key('refund-submit'))).onPressed,
      isNull,
    );

    submit.complete(false);
    await tester.pumpAndSettle();
  });

  testWidgets('failed submit preserves fields and selected evidence with its error',
      (tester) async {
    await _pumpRefundPage(
      tester,
      onPickRefundEvidence: () async => _evidence(
        'retain.pdf',
        'application/pdf',
        [0x25, 0x50, 0x44, 0x46],
      ),
      onSubmit: (_) async => false,
    );

    await tester.tap(find.byKey(const Key('refund-reason-0')));
    await tester.enterText(
      find.byKey(const Key('refund-description-field')),
      'Keep this explanation',
    );
    await tester.tap(find.byKey(const Key('refund-evidence-add')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('refund-submit')));
    await tester.pumpAndSettle();

    expect(find.text('退款申请失败'), findsOneWidget);
    expect(find.text('Keep this explanation'), findsOneWidget);
    expect(find.text('retain.pdf'), findsOneWidget);
    expect(find.text('1/5'), findsOneWidget);
  });

  testWidgets('successful submit pops exactly once', (tester) async {
    final observer = _CountingNavigatorObserver();
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh')],
        navigatorObservers: [observer],
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              key: const Key('open-refund-page'),
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => RefundApplyPage(
                    order: _order(flow: OrderPaymentFlow.travelGroundServiceOnly),
                    onSubmit: (_) async => true,
                  ),
                ),
              ),
              child: const Text('Open'),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.byKey(const Key('open-refund-page')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('refund-reason-0')));
    await tester.tap(find.byKey(const Key('refund-submit')));
    await tester.pumpAndSettle();

    expect(observer.popCount, 1);
  });

  testWidgets('read only refund card shows evidence count and position ordered names',
      (tester) async {
    final order = _order(
      flow: OrderPaymentFlow.travelGroundServiceOnly,
      refundStatus: RefundStatus.pending,
    );
    final controller = OrderDetailController(
      _RefundDetailRepository(order),
      orderId: order.id,
      initialOrder: order,
    );
    await controller.load();
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh')],
        home: OrderDetailPage(controller: controller),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('退款凭证（2）'), findsOneWidget);
    expect(find.text('first-by-position.pdf'), findsOneWidget);
    expect(find.text('second-by-position.jpg'), findsOneWidget);
    expect(
      tester.getTopLeft(find.text('first-by-position.pdf')).dy,
      lessThan(tester.getTopLeft(find.text('second-by-position.jpg')).dy),
    );
  });
}

Future<void> _pumpRefundPage(
  WidgetTester tester, {
  Order? order,
  bool canUploadLegacyEvidence = false,
  Future<String?> Function()? onPickLegacyEvidence,
  Future<RefundEvidenceDraft?> Function()? onPickRefundEvidence,
  Future<bool> Function(OrderRefundDraft draft)? onSubmit,
}) => tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh')],
        home: RefundApplyPage(
          order: order ?? _order(flow: OrderPaymentFlow.travelGroundServiceOnly),
          canUploadLegacyEvidence: canUploadLegacyEvidence,
          onPickLegacyEvidence: onPickLegacyEvidence,
          onPickRefundEvidence: onPickRefundEvidence,
          onSubmit: onSubmit ?? (_) async => false,
        ),
      ),
    );

RefundEvidenceDraft _evidence(
  String fileName,
  String contentType,
  List<int> bytes,
) => RefundEvidenceDraft(
      bytes: Uint8List.fromList(bytes),
      fileName: fileName,
      contentType: contentType,
    );

Order _order({
  required OrderPaymentFlow flow,
  RefundStatus refundStatus = RefundStatus.none,
}) => Order(
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
          ? (refundStatus == RefundStatus.none
              ? OrderStatus.serviceActive
              : OrderStatus.refundReview)
          : OrderStatus.consultationPaid,
      paymentFlow: flow,
      refundStatus: refundStatus,
      quantity: 1,
      remark: '',
      verifyCode: null,
      hasReview: false,
      createdAt: DateTime.parse('2026-08-30T10:00:00Z'),
    );

final class _CountingNavigatorObserver extends NavigatorObserver {
  int popCount = 0;

  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) {
    popCount += 1;
    super.didPop(route, previousRoute);
  }
}

final class _RefundDetailRepository implements OrdersRepository {
  _RefundDetailRepository(this.order);

  final Order order;

  @override
  Future<Order> getOrder(String id) async => order;

  @override
  Future<RefundDetail> getRefund(String id) async => RefundDetail.fromJson(const {
        'id': 'refund-1',
        'orderId': 'order-1',
        'amount': '23.50',
        'reason': 'Changed plans',
        'description': 'Cannot travel',
        'status': 'PENDING',
        'createdAt': '2026-08-30T10:00:00Z',
        'evidenceFiles': [
          {
            'fileId': 'file-2',
            'originalName': 'second-by-position.jpg',
            'contentType': 'image/jpeg',
            'sizeBytes': 20,
            'position': 1,
          },
          {
            'fileId': 'file-1',
            'originalName': 'first-by-position.pdf',
            'contentType': 'application/pdf',
            'sizeBytes': 10,
            'position': 0,
          },
        ],
      });

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnsupportedError(invocation.memberName.toString());
}
