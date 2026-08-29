import 'dart:async';
import 'dart:collection';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_order_models.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_orders_repository.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_order_detail_page.dart';

void main() {
  testWidgets(
    'active detail opens conversation and exposes no forbidden actions',
    (tester) async {
      final openedOrderIds = <String>[];
      final repository = FakeDetailRepository()
        ..enqueueDetail(detail(id: 'order-1'));

      await tester.pumpWidget(
        detailApp(
          repository,
          orderId: 'order-1',
          onOpenServiceConversation: (orderId) async {
            openedOrderIds.add(orderId);
          },
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('订单沟通'), findsOneWidget);
      for (final forbidden in [
        '确认完成',
        '核销',
        '退款',
        '电话',
        '真实姓名',
        '验证码',
        '二维码',
        '金额',
        '价格',
        '支付',
        '结算',
      ]) {
        expect(find.textContaining(forbidden), findsNothing);
      }

      await tester.ensureVisible(find.text('订单沟通'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('订单沟通'));
      await tester.pump();
      expect(openedOrderIds, ['order-1']);
    },
  );

  testWidgets('readonly history shows existing conversation but no send claim', (
    tester,
  ) async {
    final repository = FakeDetailRepository()
      ..enqueueDetail(
        detail(
          id: 'history-1',
          stage: ConsultantOrderStage.history,
          readable: true,
          sendable: false,
        ),
      );

    await tester.pumpWidget(detailApp(repository, orderId: 'history-1'));
    await tester.pumpAndSettle();

    expect(find.text('仅可查看历史消息'), findsOneWidget);
    expect(find.text('订单沟通'), findsOneWidget);
  });

  testWidgets('detail without a readable conversation hides the action', (
    tester,
  ) async {
    final repository = FakeDetailRepository()
      ..enqueueDetail(
        detail(
          id: 'history-2',
          stage: ConsultantOrderStage.history,
          readable: false,
          sendable: true,
        ),
      );

    await tester.pumpWidget(detailApp(repository, orderId: 'history-2'));
    await tester.pumpAndSettle();

    expect(find.text('订单沟通'), findsNothing);
    expect(find.text('仅可查看历史消息'), findsNothing);
  });

  testWidgets('reloads canonical detail only when repository or order ID changes', (
    tester,
  ) async {
    final firstRepository = FakeDetailRepository()
      ..enqueueDetail(detail(id: 'order-1', projectName: '项目一'))
      ..enqueueDetail(detail(id: 'order-2', projectName: '项目二'));
    final secondRepository = FakeDetailRepository()
      ..enqueueDetail(detail(id: 'order-2', projectName: '项目二新版本'));
    var repository = firstRepository;
    var orderId = 'order-1';
    Future<void> Function(String) openConversation = (_) async {};
    late StateSetter setHostState;

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: supportedTestLocales,
        localizationsDelegates: testLocalizationDelegates,
        home: StatefulBuilder(
          builder: (context, setState) {
            setHostState = setState;
            return ConsultantOrderDetailPage(
              repository: repository,
              orderId: orderId,
              onOpenServiceConversation: openConversation,
              onConsultantRoleRequired: () async {},
            );
          },
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(firstRepository.orderIds, ['order-1']);
    expect(find.text('项目一'), findsOneWidget);

    setHostState(() => openConversation = (_) async {});
    await tester.pumpAndSettle();
    expect(firstRepository.orderIds, ['order-1']);

    setHostState(() => orderId = 'order-2');
    await tester.pumpAndSettle();
    expect(firstRepository.orderIds, ['order-1', 'order-2']);
    expect(find.text('项目二'), findsOneWidget);

    setHostState(() => repository = secondRepository);
    await tester.pumpAndSettle();
    expect(secondRepository.orderIds, ['order-2']);
    expect(find.text('项目二新版本'), findsOneWidget);
  });

  testWidgets(
    'role-required conversation awaits one callback and stays silent after removal',
    (tester) async {
      final repository = FakeDetailRepository()
        ..enqueueDetail(detail(id: 'order-role'));
      final roleCallbackCompleter = Completer<void>();
      var showPage = true;
      var roleRequiredCalls = 0;
      late StateSetter setHostState;

      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          supportedLocales: supportedTestLocales,
          localizationsDelegates: testLocalizationDelegates,
          home: StatefulBuilder(
            builder: (context, setState) {
              setHostState = setState;
              return showPage
                  ? ConsultantOrderDetailPage(
                      repository: repository,
                      orderId: 'order-role',
                      onOpenServiceConversation: (_) => Future.error(
                        roleRequiredException,
                      ),
                      onConsultantRoleRequired: () async {
                        roleRequiredCalls += 1;
                        setHostState(() => showPage = false);
                        await roleCallbackCompleter.future;
                      },
                    )
                  : const Scaffold(body: Text('removed'));
            },
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('订单沟通'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('订单沟通'));
      await tester.pump();

      expect(roleRequiredCalls, 1);
      expect(find.text('removed'), findsOneWidget);
      roleCallbackCompleter.complete();
      await tester.pumpAndSettle();

      expect(find.byType(SnackBar), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('other conversation failures show only local bilingual messages', (
    tester,
  ) async {
    final chineseRepository = FakeDetailRepository()
      ..enqueueDetail(detail(id: 'zh-order'));
    await tester.pumpWidget(
      detailApp(
        chineseRepository,
        orderId: 'zh-order',
        onOpenServiceConversation: (_) => Future.error(
          const ApiException(message: 'private backend wording'),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('订单沟通'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('订单沟通'));
    await tester.pump();
    expect(find.text('订单沟通暂时不可用，请稍后重试'), findsOneWidget);
    expect(find.text('private backend wording'), findsNothing);

    final englishRepository = FakeDetailRepository()
      ..enqueueDetail(detail(id: 'en-order'));
    await tester.pumpWidget(
      detailApp(
        englishRepository,
        orderId: 'en-order',
        locale: const Locale('en'),
        onOpenServiceConversation: (_) => Future.error(StateError('private')),
      ),
    );
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Order conversation'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Order conversation'));
    await tester.pump();
    expect(
      find.text('Order conversation is temporarily unavailable. Try again.'),
      findsOneWidget,
    );
    expect(find.textContaining('private'), findsNothing);
  });

  testWidgets('detail failure retries with the same canonical order ID', (
    tester,
  ) async {
    final repository = FakeDetailRepository()
      ..enqueueDetailError(
        const ApiException(message: 'server detail', httpStatus: 500),
      )
      ..enqueueDetail(detail(id: 'retry-order'));

    await tester.pumpWidget(detailApp(repository, orderId: 'retry-order'));
    await tester.pumpAndSettle();
    expect(find.text('订单详情加载失败，请重试'), findsOneWidget);
    expect(find.text('server detail'), findsNothing);

    await tester.tap(find.text('重试'));
    await tester.pumpAndSettle();
    expect(repository.orderIds, ['retry-order', 'retry-order']);
    expect(find.text('示例项目'), findsOneWidget);
  });

  testWidgets('large text scrolls to the sole detail action without overflow', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repository = FakeDetailRepository()
      ..enqueueDetail(
        detail(
          id: 'large-detail',
          projectName: '适合大字号阅读的完整项目名称',
          remark: '需要在服务前充分沟通的公开备注内容',
        ),
      );

    await tester.pumpWidget(
      detailApp(
        repository,
        orderId: 'large-detail',
        locale: const Locale('en'),
        mediaQueryData: const MediaQueryData(
          size: Size(320, 640),
          textScaler: TextScaler.linear(2),
        ),
      ),
    );
    await tester.pumpAndSettle();
    final detailScrollable = find.descendant(
      of: find.byKey(const Key('consultant-order-detail-scroll')),
      matching: find.byType(Scrollable),
    );
    expect(detailScrollable, findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('Order conversation'),
      220,
      scrollable: detailScrollable,
    );
    await tester.pump();

    expect(find.text('Order conversation'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

Widget detailApp(
  FakeDetailRepository repository, {
  required String orderId,
  Locale locale = const Locale('zh'),
  MediaQueryData? mediaQueryData,
  Future<void> Function(String)? onOpenServiceConversation,
}) =>
    MaterialApp(
      locale: locale,
      supportedLocales: supportedTestLocales,
      localizationsDelegates: testLocalizationDelegates,
      home: mediaQueryData == null
          ? ConsultantOrderDetailPage(
              repository: repository,
              orderId: orderId,
              onOpenServiceConversation:
                  onOpenServiceConversation ?? (_) async {},
              onConsultantRoleRequired: () async {},
            )
          : MediaQuery(
              data: mediaQueryData,
              child: ConsultantOrderDetailPage(
                repository: repository,
                orderId: orderId,
                onOpenServiceConversation:
                    onOpenServiceConversation ?? (_) async {},
                onConsultantRoleRequired: () async {},
              ),
            ),
    );

const List<Locale> supportedTestLocales = [Locale('zh'), Locale('en')];

const List<LocalizationsDelegate<dynamic>> testLocalizationDelegates = [
  GlobalMaterialLocalizations.delegate,
  GlobalWidgetsLocalizations.delegate,
  GlobalCupertinoLocalizations.delegate,
];

ConsultantOrderDetail detail({
  required String id,
  String projectName = '示例项目',
  String remark = '公开备注',
  ConsultantOrderStage stage = ConsultantOrderStage.active,
  bool readable = true,
  bool sendable = true,
}) =>
    ConsultantOrderDetail(
      summary: ConsultantOrderSummary(
        id: id,
        orderNo: 'PRIVATE-$id',
        stage: stage,
        status: stage == ConsultantOrderStage.history
            ? 'COMPLETED'
            : 'SERVICE_ACTIVE',
        refundStatus: 'NONE',
        project: ConsultantOrderProject(
          id: 'project-$id',
          name: projectName,
          coverImage: '',
        ),
        institution: const ConsultantOrderInstitution(
          id: 'institution-1',
          name: '示例机构',
        ),
        customer: const ConsultantOrderCustomer(
          displayName: '公开顾客',
          avatar: null,
        ),
        appointmentTime: DateTime.utc(2026, 9, 1, 10),
        updatedAt: DateTime.utc(2026, 8, 29),
        conversationReadable: readable,
        messageSendable: sendable,
        readOnly: !sendable,
      ),
      doctor: const ConsultantOrderDoctor(id: 'doctor-1', name: '公开医生'),
      remark: remark,
      createdAt: DateTime.utc(2026, 8, 28, 8),
      serviceActivatedAt: DateTime.utc(2026, 8, 29, 9),
      completedAt: stage == ConsultantOrderStage.history
          ? DateTime.utc(2026, 8, 30, 12)
          : null,
      conversation: ConsultantOrderConversationAccess(
        readable: readable,
        sendable: sendable,
      ),
    );

const roleRequiredException = ApiException(
  message: 'server role text',
  httpStatus: 403,
  businessCode: 403,
  errorCode: 'CONSULTANT_ROLE_REQUIRED',
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
