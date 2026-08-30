import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_order_models.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_orders_repository.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_order_detail_controller.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_order_detail_page.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_orders_controller.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_orders_page.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';

void main() {
  testWidgets('shows consultant orders only when every product gate is met', (
    tester,
  ) async {
    await tester.pumpWidget(
      managementApp(
        identityRepository: FakeIdentityRepository([
          managementContext(),
        ]),
        consultantOrdersRepository: fakeConsultantOrdersRepository(),
        onOpenConversation: (_) async {},
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const Key('management-consultant-orders')),
      findsOneWidget,
    );
  });

  testWidgets('consultant role alone does not expose consultant orders', (
    tester,
  ) async {
    await tester.pumpWidget(
      managementApp(
        identityRepository: FakeIdentityRepository([
          managementContext(
            canAccessConsultantOrderWorkbench: false,
            canViewAffiliations: false,
          ),
        ]),
        consultantOrdersRepository: fakeConsultantOrdersRepository(),
        onOpenConversation: (_) async {},
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const Key('management-consultant-orders')),
      findsNothing,
    );
  });

  testWidgets('does not substitute canManageOrders for consultant capability', (
    tester,
  ) async {
    await tester.pumpWidget(
      managementApp(
        identityRepository: FakeIdentityRepository([
          managementContext(
            canAccessConsultantOrderWorkbench: false,
            canManageOrders: true,
          ),
        ]),
        consultantOrdersRepository: fakeConsultantOrdersRepository(),
        onOpenConversation: (_) async {},
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const Key('management-consultant-orders')),
      findsNothing,
    );
  });

  testWidgets('hides consultant orders without an active consultant role', (
    tester,
  ) async {
    await tester.pumpWidget(
      managementApp(
        identityRepository: FakeIdentityRepository([
          managementContext(isConsultant: false),
        ]),
        consultantOrdersRepository: fakeConsultantOrdersRepository(),
        onOpenConversation: (_) async {},
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const Key('management-consultant-orders')),
      findsNothing,
    );
  });

  testWidgets('hides consultant orders when repository is unavailable', (
    tester,
  ) async {
    await tester.pumpWidget(
      managementApp(
        identityRepository: FakeIdentityRepository([
          managementContext(),
        ]),
        onOpenConversation: (_) async {},
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const Key('management-consultant-orders')),
      findsNothing,
    );
  });

  testWidgets('hides consultant orders when conversation callback is missing', (
    tester,
  ) async {
    await tester.pumpWidget(
      managementApp(
        identityRepository: FakeIdentityRepository([
          managementContext(),
        ]),
        consultantOrdersRepository: fakeConsultantOrdersRepository(),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const Key('management-consultant-orders')),
      findsNothing,
    );
  });

  testWidgets('opens the consultant orders workbench from management center', (
    tester,
  ) async {
    await tester.pumpWidget(
      managementApp(
        identityRepository: FakeIdentityRepository([
          managementContext(),
        ]),
        consultantOrdersRepository: fakeConsultantOrdersRepository(),
        onOpenConversation: (_) async {},
      ),
    );
    await tester.pumpAndSettle();

    final entry = find.byKey(const Key('management-consultant-orders'));
    await tester.ensureVisible(entry);
    await tester.tap(entry);
    await tester.pumpAndSettle();

    expect(find.byType(ConsultantOrdersPage), findsOneWidget);
  });

  testWidgets(
    'initial management load can finish after page removal without exception',
    (tester) async {
      final initialLoad = Completer<ManagementContext>();
      final identityRepository = FakeIdentityRepository([
        managementContext(),
      ])
        ..blockedInitialLoad = initialLoad;
      final showManagement = ValueNotifier(true);
      addTearDown(showManagement.dispose);

      await tester.pumpWidget(
        removableManagementApp(
          showManagement: showManagement,
          identityRepository: identityRepository,
        ),
      );
      await tester.pump();
      expect(identityRepository.loadManagementContextCalls, 1);

      showManagement.value = false;
      await tester.pump();
      expect(
        find.byType(ManagementCenterPage, skipOffstage: false),
        findsNothing,
      );
      initialLoad.complete(identityRepository.contexts.single);
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'manual management refresh can finish after page removal without exception',
    (tester) async {
      final identityRepository = FakeIdentityRepository([
        managementContext(),
        managementContext(),
      ]);
      final refresh = Completer<ManagementContext>();
      final showManagement = ValueNotifier(true);
      addTearDown(showManagement.dispose);

      await tester.pumpWidget(
        removableManagementApp(
          showManagement: showManagement,
          identityRepository: identityRepository,
        ),
      );
      await tester.pumpAndSettle();
      identityRepository.blockedRefresh = refresh;

      await tester.tap(find.byTooltip('刷新权限'));
      await tester.tap(find.byTooltip('刷新权限'));
      await tester.pump();
      expect(identityRepository.loadManagementContextCalls, 2);

      showManagement.value = false;
      await tester.pump();
      expect(
        find.byType(ManagementCenterPage, skipOffstage: false),
        findsNothing,
      );
      refresh.completeError(StateError('controlled refresh failure'));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'concurrent list detail and conversation role loss refreshes once and pops children',
    (tester) async {
      final identityRepository = FakeIdentityRepository([
        managementContext(),
        managementContext(
          isConsultant: false,
          canAccessConsultantOrderWorkbench: false,
        ),
      ]);
      final refreshCompleter = Completer<ManagementContext>();
      final listRoleFailure = Completer<ConsultantOrderPage>();
      final detailRoleFailure = Completer<ConsultantOrderDetail>();
      final consultantOrdersRepository = FakeConsultantOrdersRepository(
        page: ConsultantOrderPage(
          items: [consultantOrderSummary()],
          offset: 0,
          limit: 20,
          hasMore: false,
        ),
        detail: consultantOrderDetail(),
      );
      var conversationCalls = 0;

      await tester.pumpWidget(
        managementApp(
          identityRepository: identityRepository,
          consultantOrdersRepository: consultantOrdersRepository,
          onOpenConversation: (_) {
            conversationCalls += 1;
            return Future.error(roleRequiredException);
          },
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(const Key('management-consultant-orders')),
      );
      await tester.pumpAndSettle();

      final sharedRoleHandler = tester
          .widget<ConsultantOrdersPage>(find.byType(ConsultantOrdersPage))
          .onConsultantRoleRequired;
      await tester.tap(find.text('测试项目'));
      await tester.pumpAndSettle();
      final conversationButton = await revealConversationAction(tester);
      final detailPage = tester.widget<ConsultantOrderDetailPage>(
        find.byType(ConsultantOrderDetailPage),
      );
      expect(
        identical(
          detailPage.onConsultantRoleRequired,
          sharedRoleHandler,
        ),
        isTrue,
      );

      consultantOrdersRepository
        ..blockedOrders = listRoleFailure
        ..blockedDetail = detailRoleFailure;
      final listController = ConsultantOrdersController(
        consultantOrdersRepository,
        onConsultantRoleRequired: sharedRoleHandler,
      );
      final detailController = ConsultantOrderDetailController(
        consultantOrdersRepository,
        orderId: 'order-1',
        onConsultantRoleRequired: sharedRoleHandler,
      );
      final listLoad = listController.load(ConsultantOrderStage.active);
      final detailLoad = detailController.load();
      identityRepository.blockedRefresh = refreshCompleter;
      await tester.tap(conversationButton);
      await tester.pump(const Duration(milliseconds: 1));

      expect(consultantOrdersRepository.getOrdersCalls, 2);
      expect(consultantOrdersRepository.getOrderCalls, 2);
      expect(identityRepository.loadManagementContextCalls, 2);
      listRoleFailure.completeError(roleRequiredException);
      detailRoleFailure.completeError(roleRequiredException);
      await tester.pump();
      expect(
        listController.stateFor(ConsultantOrderStage.active).status,
        ConsultantOrderListStatus.accessRevoked,
      );
      expect(
        detailController.status,
        ConsultantOrderDetailLoadStatus.accessRevoked,
      );
      expect(identityRepository.loadManagementContextCalls, 2);
      refreshCompleter.complete(identityRepository.contexts.last);
      await tester.pumpAndSettle();
      await Future.wait([listLoad, detailLoad]);
      listController.dispose();
      detailController.dispose();

      expect(conversationCalls, 1);
      expect(identityRepository.loadManagementContextCalls, 2);
      expect(find.byType(ManagementCenterPage), findsOneWidget);
      expect(find.byType(ConsultantOrdersPage), findsNothing);
      expect(find.byType(ConsultantOrderDetailPage), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'role refresh completion after management removal has no async exception',
    (tester) async {
      final identityRepository = FakeIdentityRepository([
        managementContext(),
        managementContext(
          isConsultant: false,
          canAccessConsultantOrderWorkbench: false,
        ),
      ]);
      final refreshCompleter = Completer<ManagementContext>();
      final consultantOrdersRepository = FakeConsultantOrdersRepository(
        page: ConsultantOrderPage(
          items: [consultantOrderSummary()],
          offset: 0,
          limit: 20,
          hasMore: false,
        ),
        detail: consultantOrderDetail(),
      );
      var showManagement = true;
      late StateSetter setHostState;

      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          supportedLocales: supportedTestLocales,
          localizationsDelegates: testLocalizationDelegates,
          home: StatefulBuilder(
            builder: (context, setState) {
              setHostState = setState;
              return showManagement
                  ? ManagementCenterPage(
                      repository: identityRepository,
                      discoverRepository: FakeDiscoverRepository(),
                      consultantOrdersRepository: consultantOrdersRepository,
                      onOpenConsultantOrderServiceConversation: (_, __) =>
                          Future.error(roleRequiredException),
                    )
                  : const Scaffold(
                      body: SizedBox(key: Key('management-removed')),
                    );
            },
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(const Key('management-consultant-orders')),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('测试项目'));
      await tester.pumpAndSettle();
      final conversationButton = await revealConversationAction(tester);

      identityRepository.blockedRefresh = refreshCompleter;
      await tester.tap(conversationButton);
      await tester.pump(const Duration(milliseconds: 1));
      expect(identityRepository.loadManagementContextCalls, 2);

      setHostState(() => showManagement = false);
      await tester.pump();
      expect(
        find.byType(ManagementCenterPage, skipOffstage: false),
        findsNothing,
      );
      refreshCompleter.complete(identityRepository.contexts.last);
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'retained role callback after management removal is a completed no-op',
    (tester) async {
      final identityRepository = FakeIdentityRepository([
        managementContext(),
      ]);
      final showManagement = ValueNotifier(true);
      addTearDown(showManagement.dispose);

      await tester.pumpWidget(
        removableManagementApp(
          showManagement: showManagement,
          identityRepository: identityRepository,
          consultantOrdersRepository: fakeConsultantOrdersRepository(),
          onOpenConversation: (_) async {},
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(const Key('management-consultant-orders')),
      );
      await tester.pumpAndSettle();
      final retainedRoleHandler = tester
          .widget<ConsultantOrdersPage>(find.byType(ConsultantOrdersPage))
          .onConsultantRoleRequired;
      final callsBeforeRemoval = identityRepository.loadManagementContextCalls;

      showManagement.value = false;
      await tester.pump();
      expect(
        find.byType(ManagementCenterPage, skipOffstage: false),
        findsNothing,
      );

      var callbackCompleted = false;
      unawaited(
        retainedRoleHandler().then((_) => callbackCompleted = true),
      );
      await tester.pump();

      expect(callbackCompleted, isTrue);
      expect(
        identityRepository.loadManagementContextCalls,
        callsBeforeRemoval,
      );
      expect(find.byType(ConsultantOrdersPage), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'back-to-back role handlers return the same future before refresh starts',
    (tester) async {
      final identityRepository = FakeIdentityRepository([
        managementContext(),
        managementContext(
          isConsultant: false,
          canAccessConsultantOrderWorkbench: false,
        ),
      ]);

      await tester.pumpWidget(
        managementApp(
          identityRepository: identityRepository,
          consultantOrdersRepository: fakeConsultantOrdersRepository(),
          onOpenConversation: (_) async {},
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(const Key('management-consultant-orders')),
      );
      await tester.pumpAndSettle();
      final roleHandler = tester
          .widget<ConsultantOrdersPage>(find.byType(ConsultantOrdersPage))
          .onConsultantRoleRequired;

      final first = roleHandler();
      final second = roleHandler();

      expect(identical(first, second), isTrue);
      expect(identityRepository.loadManagementContextCalls, 1);
      await tester.pumpAndSettle();
      await first;
      expect(identityRepository.loadManagementContextCalls, 2);
      expect(find.byType(ManagementCenterPage), findsOneWidget);
      expect(find.byType(ConsultantOrdersPage), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
}

Future<Finder> revealConversationAction(WidgetTester tester) async {
  final detailScrollable = find.descendant(
    of: find.byKey(const Key('consultant-order-detail-scroll')),
    matching: find.byType(Scrollable),
  );
  expect(detailScrollable, findsOneWidget);
  final conversationButton = find.byKey(
    const Key('consultant-order-conversation-action'),
  );
  await tester.scrollUntilVisible(
    conversationButton,
    220,
    scrollable: detailScrollable,
  );
  await tester.pumpAndSettle();
  expect(conversationButton, findsOneWidget);
  return conversationButton;
}

Widget managementApp({
  required FakeIdentityRepository identityRepository,
  ConsultantOrdersRepository? consultantOrdersRepository,
  Future<void> Function(String orderId)? onOpenConversation,
}) =>
    MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: supportedTestLocales,
      localizationsDelegates: testLocalizationDelegates,
      home: ManagementCenterPage(
        repository: identityRepository,
        discoverRepository: FakeDiscoverRepository(),
        consultantOrdersRepository: consultantOrdersRepository,
        onOpenConsultantOrderServiceConversation: onOpenConversation == null
            ? null
            : (orderId, _) => onOpenConversation(orderId),
      ),
    );

Widget removableManagementApp({
  required ValueNotifier<bool> showManagement,
  required FakeIdentityRepository identityRepository,
  ConsultantOrdersRepository? consultantOrdersRepository,
  Future<void> Function(String orderId)? onOpenConversation,
}) =>
    MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: supportedTestLocales,
      localizationsDelegates: testLocalizationDelegates,
      home: ValueListenableBuilder<bool>(
        valueListenable: showManagement,
        builder: (context, visible, _) => visible
            ? ManagementCenterPage(
                repository: identityRepository,
                discoverRepository: FakeDiscoverRepository(),
                consultantOrdersRepository: consultantOrdersRepository,
                onOpenConsultantOrderServiceConversation:
                    onOpenConversation == null
                        ? null
                        : (orderId, _) => onOpenConversation(orderId),
              )
            : const Scaffold(
                body: SizedBox(key: Key('management-removed')),
              ),
      ),
    );

const supportedTestLocales = [Locale('zh'), Locale('en')];

const List<LocalizationsDelegate<dynamic>> testLocalizationDelegates = [
  GlobalMaterialLocalizations.delegate,
  GlobalWidgetsLocalizations.delegate,
  GlobalCupertinoLocalizations.delegate,
];

ManagementContext managementContext({
  bool isConsultant = true,
  bool canAccessConsultantOrderWorkbench = true,
  bool canManageOrders = false,
  bool canViewAffiliations = true,
}) =>
    ManagementContext(
      userId: 'user-1',
      platformRole: 'USER',
      activeRoles: isConsultant ? const ['CONSULTANT'] : const [],
      managedInstitutionIds: const [],
      visibleInstitutionIds: const [],
      canAccessConsultantOrderWorkbench: canAccessConsultantOrderWorkbench,
      canManageOrders: canManageOrders,
      canViewAffiliations: canViewAffiliations,
    );

FakeConsultantOrdersRepository fakeConsultantOrdersRepository() =>
    FakeConsultantOrdersRepository(
      page: const ConsultantOrderPage(
        items: [],
        offset: 0,
        limit: 20,
        hasMore: false,
      ),
      detail: consultantOrderDetail(),
    );

ConsultantOrderSummary consultantOrderSummary() => ConsultantOrderSummary(
      id: 'order-1',
      orderNo: 'PRIVATE-order-1',
      stage: ConsultantOrderStage.active,
      status: 'SERVICE_ACTIVE',
      refundStatus: 'NONE',
      project: const ConsultantOrderProject(
        id: 'project-1',
        name: '测试项目',
        coverImage: '',
      ),
      institution: const ConsultantOrderInstitution(
        id: 'institution-1',
        name: '测试机构',
      ),
      customer: const ConsultantOrderCustomer(
        displayName: '公开顾客',
        avatar: null,
      ),
      appointmentTime: DateTime.utc(2026, 9, 1, 10),
      updatedAt: DateTime.utc(2026, 8, 29),
      conversationReadable: true,
      messageSendable: true,
      readOnly: false,
    );

ConsultantOrderDetail consultantOrderDetail() => ConsultantOrderDetail(
      summary: consultantOrderSummary(),
      doctor: const ConsultantOrderDoctor(id: 'doctor-1', name: '测试医生'),
      remark: '公开备注',
      createdAt: DateTime.utc(2026, 8, 28),
      serviceActivatedAt: DateTime.utc(2026, 8, 29),
      completedAt: null,
      conversation: const ConsultantOrderConversationAccess(
        readable: true,
        sendable: true,
      ),
    );

const roleRequiredException = ApiException(
  message: 'server role text',
  httpStatus: 403,
  businessCode: 403,
  errorCode: 'CONSULTANT_ROLE_REQUIRED',
);

final class FakeIdentityRepository implements IdentityRepository {
  FakeIdentityRepository(this.contexts) : assert(contexts.isNotEmpty);

  final List<ManagementContext> contexts;
  int loadManagementContextCalls = 0;
  Completer<ManagementContext>? blockedInitialLoad;
  Completer<ManagementContext>? blockedRefresh;

  @override
  Future<ManagementContext> loadManagementContext() async {
    final index = loadManagementContextCalls < contexts.length
        ? loadManagementContextCalls
        : contexts.length - 1;
    loadManagementContextCalls += 1;
    final initialBlocker = blockedInitialLoad;
    if (loadManagementContextCalls == 1 && initialBlocker != null) {
      return initialBlocker.future;
    }
    final blocker = blockedRefresh;
    if (loadManagementContextCalls > 1 && blocker != null) {
      return blocker.future;
    }
    return contexts[index];
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class FakeDiscoverRepository implements DiscoverRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class FakeConsultantOrdersRepository
    implements ConsultantOrdersRepository {
  FakeConsultantOrdersRepository({
    required this.page,
    required this.detail,
  });

  final ConsultantOrderPage page;
  final ConsultantOrderDetail detail;
  int getOrdersCalls = 0;
  int getOrderCalls = 0;
  Completer<ConsultantOrderPage>? blockedOrders;
  Completer<ConsultantOrderDetail>? blockedDetail;

  @override
  Future<ConsultantOrderPage> getOrders({
    required ConsultantOrderStage stage,
    String? institutionId,
    required int offset,
    required int limit,
  }) async {
    getOrdersCalls += 1;
    final blocker = blockedOrders;
    if (getOrdersCalls > 1 && blocker != null) {
      return blocker.future;
    }
    return page;
  }

  @override
  Future<ConsultantOrderDetail> getOrder(String orderId) async {
    getOrderCalls += 1;
    final blocker = blockedDetail;
    if (getOrderCalls > 1 && blocker != null) {
      return blocker.future;
    }
    return detail;
  }
}
