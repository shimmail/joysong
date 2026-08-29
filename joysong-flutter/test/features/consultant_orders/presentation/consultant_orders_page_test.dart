import 'dart:async';
import 'dart:collection';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/core/network/optimized_network_image.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_order_models.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_orders_repository.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_order_card.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_orders_page.dart';

void main() {
  testWidgets('renders three localized tabs with independent empty states', (
    tester,
  ) async {
    final repository = FakeWorkbenchRepository()
      ..enqueuePage(ConsultantOrderStage.active, emptyPage())
      ..enqueuePage(ConsultantOrderStage.paused, emptyPage())
      ..enqueuePage(ConsultantOrderStage.history, emptyPage());

    await tester.pumpWidget(testApp(workbench(repository)));
    await tester.pumpAndSettle();

    expect(find.text('服务中'), findsOneWidget);
    expect(find.text('退款处理中'), findsOneWidget);
    expect(find.text('历史订单'), findsOneWidget);
    expect(
      find.byKey(const Key('consultant-orders-active-list')),
      findsOneWidget,
    );
    expect(find.text('暂无服务中订单'), findsOneWidget);

    await tester.tap(find.text('退款处理中'));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const Key('consultant-orders-paused-list')),
      findsOneWidget,
    );
    expect(find.text('暂无退款处理中订单'), findsOneWidget);

    await tester.tap(find.text('历史订单'));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const Key('consultant-orders-history-list')),
      findsOneWidget,
    );
    expect(find.text('暂无历史订单'), findsOneWidget);
    expect(
      repository.listRequests.map((request) => request.stage),
      [
        ConsultantOrderStage.active,
        ConsultantOrderStage.paused,
        ConsultantOrderStage.history,
      ],
    );
  });

  testWidgets('renders the three tab labels in English', (tester) async {
    final repository = FakeWorkbenchRepository()
      ..enqueuePage(ConsultantOrderStage.active, emptyPage());

    await tester.pumpWidget(
      testApp(workbench(repository), locale: const Locale('en')),
    );
    await tester.pumpAndSettle();

    expect(find.text('In service'), findsOneWidget);
    expect(find.text('Refund processing'), findsOneWidget);
    expect(find.text('Order history'), findsOneWidget);
    expect(find.text('No in-service orders'), findsOneWidget);
  });

  testWidgets('card renders only public fields with fixed media fallbacks', (
    tester,
  ) async {
    final order = summary(id: 'public', projectName: '公开项目').copyWithMedia(
      coverImage: 'https://example.invalid/cover.png',
    );

    await tester.pumpWidget(
      testApp(Scaffold(body: ConsultantOrderCard(summary: order))),
    );
    await tester.pump();

    final cover = tester.widget<OptimizedNetworkImage>(
      find.byType(OptimizedNetworkImage),
    );
    final avatar = tester.widget<CircleAvatar>(find.byType(CircleAvatar));
    expect(cover.width, 72);
    expect(cover.height, 72);
    expect(avatar.foregroundImage, isNull);
    expect(avatar.onForegroundImageError, isNull);
    expect(find.text('公'), findsOneWidget);
    expect(find.text('公开顾客'), findsOneWidget);
    expect(find.text('公开项目'), findsOneWidget);
    expect(find.text('示例机构'), findsOneWidget);
    expect(find.text('服务中'), findsOneWidget);
    expect(find.text('PRIVATE-public'), findsNothing);
    expect(find.text('SERVICE_ACTIVE'), findsNothing);
  });

  testWidgets('pull to refresh replaces the current stage contents', (
    tester,
  ) async {
    final repository = FakeWorkbenchRepository()
      ..enqueuePage(
        ConsultantOrderStage.active,
        orderPage([summary(id: 'before', projectName: '刷新前项目')]),
      )
      ..enqueuePage(
        ConsultantOrderStage.active,
        orderPage([summary(id: 'after', projectName: '刷新后项目')]),
      );

    await tester.pumpWidget(testApp(workbench(repository)));
    await tester.pumpAndSettle();
    expect(find.text('刷新前项目'), findsOneWidget);

    await tester.drag(
      find.byKey(const Key('consultant-orders-active-list')),
      const Offset(0, 320),
    );
    await tester.pumpAndSettle();

    expect(find.text('刷新前项目'), findsNothing);
    expect(find.text('刷新后项目'), findsOneWidget);
    expect(repository.listRequests, hasLength(2));
    expect(repository.listRequests.map((request) => request.offset), [0, 0]);
  });

  testWidgets('load-more failure keeps cards and exposes an explicit retry', (
    tester,
  ) async {
    final initial = List.generate(
      8,
      (index) => summary(id: 'order-$index', projectName: '项目 $index'),
    );
    final repository = FakeWorkbenchRepository()
      ..enqueuePage(
        ConsultantOrderStage.active,
        orderPage(initial, hasMore: true),
      )
      ..enqueueListError(
        ConsultantOrderStage.active,
        const ApiException(message: 'private server text', httpStatus: 503),
      )
      ..enqueuePage(
        ConsultantOrderStage.active,
        orderPage(
          [summary(id: 'order-8', projectName: '重试新增项目')],
          offset: 8,
        ),
      );

    await tester.pumpWidget(testApp(workbench(repository)));
    await tester.pumpAndSettle();
    await tester.fling(
      find.byKey(const Key('consultant-orders-active-list')),
      const Offset(0, -1600),
      3000,
    );
    await tester.pumpAndSettle();

    expect(find.text('项目 7'), findsOneWidget);
    expect(find.text('加载更多失败，当前订单已保留'), findsOneWidget);
    expect(find.text('private server text'), findsNothing);

    await tester.tap(
      find.byKey(const Key('consultant-orders-load-more-retry-active')),
    );
    await tester.pumpAndSettle();

    expect(find.text('重试新增项目'), findsOneWidget);
    final listScrollable = find.descendant(
      of: find.byKey(const Key('consultant-orders-active-list')),
      matching: find.byType(Scrollable),
    );
    expect(listScrollable, findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('项目 0'),
      -300,
      scrollable: listScrollable,
    );
    expect(find.text('项目 0'), findsOneWidget);
  });

  testWidgets('initial failure retries into the localized empty state', (
    tester,
  ) async {
    final repository = FakeWorkbenchRepository()
      ..enqueueListError(
        ConsultantOrderStage.active,
        const ApiException(message: 'untrusted server text', httpStatus: 500),
      )
      ..enqueuePage(ConsultantOrderStage.active, emptyPage());

    await tester.pumpWidget(testApp(workbench(repository)));
    await tester.pumpAndSettle();

    expect(find.text('订单加载失败，请重试'), findsOneWidget);
    expect(find.text('untrusted server text'), findsNothing);
    await tester.tap(find.text('重试'));
    await tester.pumpAndSettle();
    expect(find.text('暂无服务中订单'), findsOneWidget);
  });

  testWidgets('role loss awaits the page callback once and can remove it', (
    tester,
  ) async {
    final repository = FakeWorkbenchRepository()
      ..enqueueListError(
        ConsultantOrderStage.active,
        roleRequiredException,
      );
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
                ? ConsultantOrdersPage(
                    repository: repository,
                    onOpenServiceConversation: (_) async {},
                    onConsultantRoleRequired: () async {
                      roleRequiredCalls += 1;
                      setHostState(() => showPage = false);
                      await Future<void>.delayed(Duration.zero);
                    },
                  )
                : const Scaffold(body: Text('removed'));
          },
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(roleRequiredCalls, 1);
    expect(find.text('removed'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('large text can scroll through every card without overflow', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repository = FakeWorkbenchRepository()
      ..enqueuePage(
        ConsultantOrderStage.active,
        orderPage(
          List.generate(
            10,
            (index) => summary(
              id: 'large-$index',
              projectName: '大字号项目 $index 的完整名称',
              customerName: '顾客 $index',
            ),
          ),
        ),
      );

    await tester.pumpWidget(
      testApp(
        MediaQuery(
          data: const MediaQueryData(
            size: Size(320, 640),
            textScaler: TextScaler.linear(2),
          ),
          child: workbench(repository),
        ),
        locale: const Locale('en'),
      ),
    );
    await tester.pumpAndSettle();
    final listScrollable = find.descendant(
      of: find.byKey(const Key('consultant-orders-active-list')),
      matching: find.byType(Scrollable),
    );
    expect(listScrollable, findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('大字号项目 9 的完整名称'),
      240,
      scrollable: listScrollable,
    );
    await tester.pump();

    expect(find.text('大字号项目 9 的完整名称'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

Widget workbench(FakeWorkbenchRepository repository) =>
    ConsultantOrdersPage(
      repository: repository,
      onOpenServiceConversation: (_) async {},
      onConsultantRoleRequired: () async {},
    );

Widget testApp(
  Widget home, {
  Locale locale = const Locale('zh'),
}) =>
    MaterialApp(
      locale: locale,
      supportedLocales: supportedTestLocales,
      localizationsDelegates: testLocalizationDelegates,
      home: home,
    );

const List<Locale> supportedTestLocales = [Locale('zh'), Locale('en')];

const List<LocalizationsDelegate<dynamic>> testLocalizationDelegates = [
  GlobalMaterialLocalizations.delegate,
  GlobalWidgetsLocalizations.delegate,
  GlobalCupertinoLocalizations.delegate,
];

ConsultantOrderPage emptyPage() => orderPage(const []);

ConsultantOrderPage orderPage(
  List<ConsultantOrderSummary> items, {
  int offset = 0,
  bool hasMore = false,
}) =>
    ConsultantOrderPage(
      items: List.unmodifiable(items),
      offset: offset,
      limit: 20,
      hasMore: hasMore,
    );

ConsultantOrderSummary summary({
  required String id,
  required String projectName,
  String customerName = '公开顾客',
}) =>
    ConsultantOrderSummary(
      id: id,
      orderNo: 'PRIVATE-$id',
      stage: ConsultantOrderStage.active,
      status: 'SERVICE_ACTIVE',
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
      customer: ConsultantOrderCustomer(
        displayName: customerName,
        avatar: null,
      ),
      appointmentTime: DateTime.utc(2026, 8, 30, 9, 30),
      updatedAt: DateTime.utc(2026, 8, 29),
      conversationReadable: true,
      messageSendable: true,
      readOnly: false,
    );

extension on ConsultantOrderSummary {
  ConsultantOrderSummary copyWithMedia({required String coverImage}) =>
      ConsultantOrderSummary(
        id: id,
        orderNo: orderNo,
        stage: stage,
        status: status,
        refundStatus: refundStatus,
        project: ConsultantOrderProject(
          id: project.id,
          name: project.name,
          coverImage: coverImage,
        ),
        institution: institution,
        customer: customer,
        appointmentTime: appointmentTime,
        updatedAt: updatedAt,
        conversationReadable: conversationReadable,
        messageSendable: messageSendable,
        readOnly: readOnly,
      );
}

const roleRequiredException = ApiException(
  message: 'server role text',
  httpStatus: 403,
  businessCode: 403,
  errorCode: 'CONSULTANT_ROLE_REQUIRED',
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

final class FakeWorkbenchRepository implements ConsultantOrdersRepository {
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
      Future.error(StateError('Unexpected detail request for $orderId'));
}
