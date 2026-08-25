import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/orders/domain/money.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/presentation/order_detail_page.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_controller.dart';

import '../../core/translation/translation_test_fixtures.dart';
import 'order_test_fixtures.dart';

void main() {
  testWidgets(
      'detail translates snapshots persisted note refund fields and log remarks',
      (tester) async {
    await _useTallSurface(tester);
    final repository = FakeOrdersRepository()
      ..orders = [
        sampleOrder(
          status: OrderStatus.disputeMediation,
          refundStatus: RefundStatus.rejected,
          remark: '术后请电话联系',
        ),
      ]
      ..refundDetail = RefundDetail(
        id: 'refund-1',
        orderId: 'order-1',
        amount: Money.parse('1280.50'),
        reason: '行程调整',
        description: '无法按期到院\n[拒绝原因] 服务已开始',
        status: RefundStatus.rejected,
        createdAt: DateTime(2026, 8, 7),
        rejectReason: '服务已开始',
      )
      ..statusLogs = [
        _statusLog(
          id: 11,
          remark: '订单已创建',
          createdAt: DateTime(2026, 8, 6, 10),
        ),
        _statusLog(
          id: 12,
          remark: '机构已确认',
          createdAt: DateTime(2026, 8, 7, 11),
        ),
      ];
    final translations = RecordingTranslationRepository()..holdResponses = true;
    final controller = OrderDetailController(repository, orderId: 'order-1');
    addTearDown(controller.dispose);
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _detailHost(
        controller: controller,
        autoController: autoController,
        enableAutoTranslation: true,
      ),
    );
    await tester.pumpAndSettle();

    expect(_mountedRequests(tester), const {
      ('project', 'order:order-1', 'projectName', '光子嫩肤'),
      ('institution', 'order:order-1', 'institutionName', '娇颜颂医疗美容'),
      ('general', 'order:order-1', 'remark', '术后请电话联系'),
      ('general', 'refund:refund-1', 'reason', '行程调整'),
      ('general', 'refund:refund-1', 'description', '无法按期到院'),
      ('general', 'refund:refund-1', 'rejectReason', '服务已开始'),
      ('general', 'order-status-log:11', 'remark', '订单已创建'),
      ('general', 'order-status-log:12', 'remark', '机构已确认'),
    });
    expect(translations.calls.map((call) => call.text), isNot(contains('张医生')));
    expect(
      translations.calls.any((call) => call.text.contains('2026-')),
      isFalse,
    );
    expect(
      translations.calls.any((call) => call.text.contains('JOY2026')),
      isFalse,
    );
    expect(
      translations.calls.any((call) => call.text.contains('[拒绝原因]')),
      isFalse,
    );

    for (final entry in const {
      '光子嫩肤': 'Photofacial',
      '娇颜颂医疗美容': 'Joysong Medical Aesthetics',
      '术后请电话联系': 'Please call after treatment',
      '行程调整': 'Schedule changed',
      '无法按期到院': 'Unable to attend on time',
      '服务已开始': 'Service has started',
      '订单已创建': 'Order created',
      '机构已确认': 'Institution confirmed',
    }.entries) {
      translations.completeText(entry.key, entry.value);
    }
    await tester.pump();
    await tester.pump();

    for (final translated in const [
      'Photofacial',
      'Joysong Medical Aesthetics',
      'Please call after treatment',
      'Schedule changed',
      'Unable to attend on time',
      'Service has started',
      'Order created',
      'Institution confirmed',
    ]) {
      expect(find.text(translated, skipOffstage: false), findsOneWidget);
    }
  });

  testWidgets('detail sends only each log remark without its date',
      (tester) async {
    final repository = FakeOrdersRepository()
      ..orders = [sampleOrder(projectName: '', institutionName: '')]
      ..statusLogs = [
        _statusLog(
          id: 21,
          remark: '仅翻译状态备注',
          createdAt: DateTime(2026, 8, 9, 12, 34),
        ),
      ];
    final translations = RecordingTranslationRepository();
    final controller = OrderDetailController(repository, orderId: 'order-1');
    addTearDown(controller.dispose);
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _detailHost(
        controller: controller,
        autoController: autoController,
        enableAutoTranslation: true,
      ),
    );
    await tester.pumpAndSettle();

    expect(translations.calls.map((call) => call.text), ['仅翻译状态备注']);
    expect(find.text('2026-08-09 12:34', skipOffstage: false), findsOneWidget);
    expect(
      find.text('en-US:仅翻译状态备注', skipOffstage: false),
      findsOneWidget,
    );
  });

  testWidgets('detail failure keeps source text', (tester) async {
    final repository = FakeOrdersRepository()
      ..orders = [
        sampleOrder(projectName: '翻译失败项目', institutionName: ''),
      ];
    final translations = RecordingTranslationRepository()
      ..failuresRemaining = 1;
    final controller = OrderDetailController(repository, orderId: 'order-1');
    addTearDown(controller.dispose);
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _detailHost(
        controller: controller,
        autoController: autoController,
        enableAutoTranslation: true,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('翻译失败项目', skipOffstage: false), findsOneWidget);
    expect(translations.calls.map((call) => call.text), ['翻译失败项目']);
  });

  testWidgets('detail rejects a stale completion after reload', (tester) async {
    final repository = FakeOrdersRepository()
      ..orders = [sampleOrder(projectName: '刷新前项目', institutionName: '')];
    final translations = RecordingTranslationRepository()..holdResponses = true;
    final controller = OrderDetailController(repository, orderId: 'order-1');
    addTearDown(controller.dispose);
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _detailHost(
        controller: controller,
        autoController: autoController,
        enableAutoTranslation: true,
      ),
    );
    await tester.pumpAndSettle();
    expect(translations.calls.map((call) => call.text), ['刷新前项目']);

    repository.orderDetails = [
      sampleOrder(projectName: '刷新后项目', institutionName: ''),
    ];
    await controller.load();
    await tester.pump();
    expect(
      translations.calls.map((call) => call.text),
      ['刷新前项目', '刷新后项目'],
    );

    translations.completeText('刷新后项目', 'Refreshed project');
    await tester.pump();
    await tester.pump();
    expect(
      find.text('Refreshed project', skipOffstage: false),
      findsOneWidget,
    );

    translations.completeText('刷新前项目', 'Stale project');
    await tester.pump();
    await tester.pump();
    expect(find.text('Stale project', skipOffstage: false), findsNothing);
    expect(
      find.text('Refreshed project', skipOffstage: false),
      findsOneWidget,
    );
  });

  testWidgets('invalid order refund and duplicate log IDs remain source-only',
      (tester) async {
    await _useTallSurface(tester);
    final repository = FakeOrdersRepository()
      ..orders = [
        sampleOrder(
          id: ' ',
          projectName: '无效订单项目',
          institutionName: '无效订单机构',
          refundStatus: RefundStatus.rejected,
        ),
      ]
      ..refundDetail = RefundDetail(
        id: ' ',
        orderId: ' ',
        amount: Money.parse('20'),
        reason: '无效退款原因',
        description: '无效退款说明',
        status: RefundStatus.rejected,
        createdAt: DateTime(2026, 8, 8),
        rejectReason: '无效退款驳回原因',
      )
      ..statusLogs = [
        _statusLog(id: 0, remark: '零日志'),
        _statusLog(id: 31, remark: '重复日志甲'),
        _statusLog(id: 31, remark: '重复日志乙'),
        _statusLog(id: 32, remark: '唯一日志'),
      ];
    final translations = RecordingTranslationRepository();
    final controller = OrderDetailController(repository, orderId: ' ');
    addTearDown(controller.dispose);
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _detailHost(
        controller: controller,
        autoController: autoController,
        enableAutoTranslation: true,
      ),
    );
    await tester.pumpAndSettle();

    expect(controller.order?.id, ' ', reason: controller.errorMessage);
    expect(controller.statusLogs, hasLength(4),
        reason: controller.errorMessage);
    expect(translations.calls.map((call) => call.text), ['唯一日志']);
    expect(
      _mountedRequests(tester),
      const {('general', 'order-status-log:32', 'remark', '唯一日志')},
    );
    for (final source in const [
      '无效订单项目',
      '无效订单机构',
      '无效退款原因',
      '无效退款说明',
      '无效退款驳回原因',
      '零日志',
      '重复日志甲',
      '重复日志乙',
    ]) {
      expect(find.text(source, skipOffstage: false), findsOneWidget);
    }
  });

  testWidgets('detail defaults off under an active scope', (tester) async {
    await _useTallSurface(tester);
    final repository = FakeOrdersRepository()
      ..orders = [
        sampleOrder(
          refundStatus: RefundStatus.pending,
          remark: '默认关闭备注',
        ),
      ]
      ..refundDetail = RefundDetail(
        id: 'refund-default-off',
        orderId: 'order-1',
        amount: Money.parse('10'),
        reason: '默认关闭原因',
        description: '默认关闭说明',
        status: RefundStatus.pending,
        createdAt: DateTime(2026, 8, 8),
      )
      ..statusLogs = [_statusLog(id: 41, remark: '默认关闭日志')];
    final translations = RecordingTranslationRepository();
    final controller = OrderDetailController(repository, orderId: 'order-1');
    addTearDown(controller.dispose);
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _detailHost(controller: controller, autoController: autoController),
    );
    await tester.pumpAndSettle();

    expect(controller.statusLogs, hasLength(1),
        reason: controller.errorMessage);
    expect(translations.calls, isEmpty);
    expect(find.byType(AutoTranslationBuilder), findsNothing);
    for (final source in const [
      '光子嫩肤',
      '娇颜颂医疗美容',
      '默认关闭备注',
      '默认关闭原因',
      '默认关闭说明',
      '默认关闭日志',
    ]) {
      expect(find.text(source, skipOffstage: false), findsOneWidget);
    }
  });

  testWidgets('travel detail translates only its visible institution snapshot',
      (tester) async {
    final repository = FakeOrdersRepository()
      ..orders = [
        sampleOrder(
          paymentFlow: OrderPaymentFlow.travelGroundServiceOnly,
          status: OrderStatus.serviceActive,
          consultantDetailsVisible: true,
          consultantName: '王地接',
          consultantId: 'consultant-private',
          institutionName: '旅行服务机构',
          institutionId: 'institution-private',
        ),
      ];
    final translations = RecordingTranslationRepository();
    final controller = OrderDetailController(repository, orderId: 'order-1');
    addTearDown(controller.dispose);
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _detailHost(
        controller: controller,
        autoController: autoController,
        enableAutoTranslation: true,
      ),
    );
    await tester.pumpAndSettle();

    expect(
      _mountedRequests(tester),
      const {
        (
          'institution',
          'order:order-1',
          'institutionName',
          '旅行服务机构',
        ),
      },
    );
    expect(translations.calls.map((call) => call.text), ['旅行服务机构']);
    expect(find.text('王地接', skipOffstage: false), findsOneWidget);
    expect(
        find.text('consultant-private', skipOffstage: false), findsOneWidget);
    expect(
        find.text('institution-private', skipOffstage: false), findsOneWidget);
  });

  testWidgets('refund summary translates without translating form input',
      (tester) async {
    await _useTallSurface(tester);
    final ordersRepository = FakeOrdersRepository()
      ..orders = [sampleOrder(status: OrderStatus.consultationPaid)];
    final translations = RecordingTranslationRepository();
    final controller = OrderDetailController(
      ordersRepository,
      orderId: 'order-1',
    );
    addTearDown(controller.dispose);
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _detailHost(
        controller: controller,
        autoController: autoController,
        enableAutoTranslation: true,
      ),
    );
    await tester.pumpAndSettle();

    final refundButton = find.byKey(const Key('request-refund-button'));
    await tester.scrollUntilVisible(refundButton, 300);
    await tester.tap(refundButton);
    await tester.pumpAndSettle();

    expect(_mountedRequests(tester), const {
      ('project', 'order:order-1', 'projectName', '光子嫩肤'),
      ('institution', 'order:order-1', 'institutionName', '娇颜颂医疗美容'),
    });
    await tester.tap(find.byKey(const Key('refund-reason-5')));
    await tester.pump();
    await tester.enterText(
      find.byKey(const Key('refund-custom-reason-field')),
      '临时改变行程',
    );
    await tester.enterText(
      find.byKey(const Key('refund-description-field')),
      '需要延期处理',
    );

    expect(
        translations.calls.map((call) => call.text), isNot(contains('临时改变行程')));
    expect(
        translations.calls.map((call) => call.text), isNot(contains('需要延期处理')));

    await tester.tap(find.byKey(const Key('refund-submit-button')));
    await tester.pumpAndSettle();

    expect(ordersRepository.lastRefundReason, '临时改变行程');
    expect(ordersRepository.lastRefundDescription, '需要延期处理');
    expect(
      translations.calls.map((call) => call.text),
      isNot(contains('临时改变行程')),
    );
    expect(
      translations.calls.map((call) => call.text),
      isNot(contains('需要延期处理')),
    );
    expect(
      translations.calls.map((call) => call.text),
      ['光子嫩肤', '娇颜颂医疗美容'],
    );
  });
}

OrderStatusLog _statusLog({
  required int id,
  required String remark,
  DateTime? createdAt,
}) =>
    OrderStatusLog(
      id: id,
      fromStatus: OrderStatus.pendingPayment,
      toStatus: OrderStatus.consultationPaid,
      operatorType: 'SYSTEM',
      remark: remark,
      createdAt: createdAt ?? DateTime(2026, 8, 6, 10),
    );

Set<(String, String, String, String)> _mountedRequests(WidgetTester tester) =>
    tester
        .widgetList<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
        .map((widget) => (
              widget.request.contentType,
              widget.request.contentId,
              widget.request.field,
              widget.request.sourceText,
            ))
        .toSet();

Future<void> _useTallSurface(WidgetTester tester) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1200, 2400);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetPhysicalSize);
}

AutoTranslationController _activeAutoController(
  TranslationRepository repository,
) {
  final controller = AutoTranslationController(
    repository: repository,
    maxConcurrent: 10,
  );
  controller.synchronize(
    enabled: true,
    authenticated: true,
    targetLanguage: 'en-US',
  );
  return controller;
}

Widget _detailHost({
  required OrderDetailController controller,
  required AutoTranslationController autoController,
  bool enableAutoTranslation = false,
}) =>
    AutoTranslationScope(
      controller: autoController,
      enabled: true,
      targetLanguage: 'en-US',
      child: MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('en'), Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: OrderDetailPage(
          controller: controller,
          enableAutoTranslation: enableAutoTranslation,
        ),
      ),
    );
