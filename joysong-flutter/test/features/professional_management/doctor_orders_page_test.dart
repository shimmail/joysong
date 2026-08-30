import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/profile/presentation/profile_page.dart';
import 'package:joysong_flutter/features/professional_management/data/professional_repository.dart';
import 'package:joysong_flutter/features/professional_management/presentation/professional_pages.dart';

void main() {
  testWidgets('doctor order cards show the information needed at a glance', (
    tester,
  ) async {
    final client = _FixtureApiClient([
      _managementOrderJson(),
      _managementOrderJson(
        id: 'order-2',
        orderNo: 'JS202608290002',
        projectName: '术后复诊',
        appointmentTime: null,
      ),
    ]);
    addTearDown(client.close);

    await _pumpOrdersPage(tester, client: client);

    expect(find.text('面部护理'), findsOneWidget);
    expect(find.text('娇颜颂医疗中心'), findsNWidgets(2));
    expect(find.text('2026-09-01 14:30'), findsOneWidget);
    expect(find.text('Appointment not confirmed'), findsOneWidget);
    expect(find.text('CNY 1280.0'), findsNWidgets(2));
    expect(tester.takeException(), isNull);
  });

  testWidgets('message action opens a direct message with the order customer', (
    tester,
  ) async {
    final client = _FixtureApiClient([_managementOrderJson()]);
    addTearDown(client.close);
    String? openedUserId;
    String? openedTitle;

    await _pumpOrdersPage(
      tester,
      client: client,
      onOpenDirectMessage: (userId, {title}) async {
        openedUserId = userId;
        openedTitle = title;
      },
    );
    await tester.tap(
      find.byKey(const Key('doctor-order-message-order-1')),
    );
    await tester.pump();

    expect(openedUserId, 'user-1');
    expect(openedTitle, '+8613900000001');
  });

  testWidgets(
      'order card opens details with the customer note and message action', (
    tester,
  ) async {
    final client = _FixtureApiClient([_managementOrderJson()]);
    addTearDown(client.close);
    String? openedUserId;

    await _pumpOrdersPage(
      tester,
      client: client,
      onOpenDirectMessage: (userId, {title}) async {
        openedUserId = userId;
      },
    );
    await tester.tap(find.byKey(const Key('doctor-order-card-order-1')));
    await tester.pumpAndSettle();

    expect(find.text('皮肤敏感，请提前准备舒缓用品'), findsOneWidget);
    expect(find.text('+8613900000001'), findsOneWidget);
    expect(find.text('2'), findsOneWidget);
    final verifyButton = find.byKey(const Key('doctor-order-verify-button'));
    await tester.scrollUntilVisible(
      verifyButton,
      240,
      scrollable: find.byType(Scrollable).last,
    );
    expect(verifyButton, findsOneWidget);

    await tester.ensureVisible(
      find.byKey(const Key('doctor-order-detail-message')),
    );
    await tester.tap(find.byKey(const Key('doctor-order-detail-message')));
    await tester.pump();

    expect(openedUserId, 'user-1');
  });

  testWidgets('order list refreshes after returning from order details', (
    tester,
  ) async {
    final client = _FixtureApiClient([_managementOrderJson()]);
    addTearDown(client.close);
    await _pumpOrdersPage(tester, client: client);

    expect(find.text('Awaiting visit'), findsOneWidget);
    await tester.tap(find.byKey(const Key('doctor-order-card-order-1')));
    await tester.pumpAndSettle();
    client.orders[0] = {
      ...client.orders[0],
      'status': 'COMPLETED',
      'canVerify': false,
    };
    Navigator.of(tester.element(find.byType(DoctorOrderDetailPage))).pop();
    await tester.pumpAndSettle();

    expect(find.text('Completed'), findsOneWidget);
    expect(find.text('Awaiting visit'), findsNothing);
  });

  testWidgets('order cards and details avoid overflow with large text', (
    tester,
  ) async {
    final order = _managementOrderJson(
      projectName: 'Comprehensive facial rejuvenation treatment package',
    )
      ..['status'] = 'PENDING_SERVICE_FEE'
      ..['orderNo'] = 'JS202608290001-EXTRA-LONG-ORDER-NUMBER'
      ..['amount'] = '999999999999999999999999.99'
      ..['currency'] = 'SUPERLONGCURRENCY';
    final client = _FixtureApiClient([order]);
    addTearDown(client.close);
    await tester.binding.setSurfaceSize(const Size(280, 900));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(
      MaterialApp(
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context).copyWith(
            textScaler: TextScaler.linear(2),
          ),
          child: child!,
        ),
        home: DoctorOrdersPage(repository: ProfessionalRepository(client)),
      ),
    );
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    await tester.tap(find.byKey(const Key('doctor-order-card-order-1')));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('successful retry clears a previous order action error', (
    tester,
  ) async {
    final client = _FixtureApiClient([_managementOrderJson()])
      ..failingActionAttempts = 1;
    addTearDown(client.close);
    await tester.binding.setSurfaceSize(const Size(430, 1200));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      MaterialApp(
        home: DoctorOrderDetailPage(
          repository: ProfessionalRepository(client),
          id: 'order-1',
        ),
      ),
    );
    await tester.pumpAndSettle();

    Future<void> verifyOrder() async {
      final button = find.byKey(const Key('doctor-order-verify-button'));
      await tester.ensureVisible(button);
      await tester.tap(button);
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '123456');
      await tester.tap(find.text('Confirm'));
      await tester.pumpAndSettle();
    }

    await verifyOrder();
    expect(find.textContaining('Temporary action failure'), findsOneWidget);

    await verifyOrder();
    expect(find.textContaining('Temporary action failure'), findsNothing);
    expect(find.text('Completed'), findsOneWidget);
  });

  testWidgets('profile management forwards the customer message action', (
    tester,
  ) async {
    final client = _FixtureApiClient([_managementOrderJson()]);
    addTearDown(client.close);
    String? openedUserId;
    await tester.binding.setSurfaceSize(const Size(600, 1200));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(
      MaterialApp(
        home: ProfilePage(
          identityRepository: const _DoctorIdentityRepository(),
          discoverRepository: const _UnusedDiscoverRepository(),
          professionalRepository: ProfessionalRepository(client),
          onOpenDirectMessage: (userId, {title}) async {
            openedUserId = userId;
          },
        ),
      ),
    );
    await tester.pumpAndSettle();
    final managementEntry = find.text('Professional management');
    await tester.scrollUntilVisible(
      managementEntry,
      240,
      scrollable: find
          .ancestor(of: managementEntry, matching: find.byType(Scrollable))
          .first,
    );
    await tester.tap(managementEntry);
    await tester.pumpAndSettle();
    final ordersEntry = find.text('Professional orders');
    await tester.scrollUntilVisible(
      ordersEntry,
      200,
      scrollable: find
          .ancestor(of: ordersEntry, matching: find.byType(Scrollable))
          .first,
    );
    await tester.tap(ordersEntry);
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const Key('doctor-order-message-order-1')),
    );
    await tester.pump();

    expect(openedUserId, 'user-1');
  });
}

Future<void> _pumpOrdersPage(
  WidgetTester tester, {
  required _FixtureApiClient client,
  Future<void> Function(String userId, {String? title})? onOpenDirectMessage,
}) async {
  await tester.binding.setSurfaceSize(const Size(430, 900));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(
    MaterialApp(
      home: DoctorOrdersPage(
        repository: ProfessionalRepository(client),
        onOpenDirectMessage: onOpenDirectMessage,
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Map<String, Object?> _managementOrderJson({
  String id = 'order-1',
  String orderNo = 'JS202608290001',
  String projectName = '面部护理',
  String? appointmentTime = '2026-09-01T14:30:00',
}) =>
    {
      'id': id,
      'orderNo': orderNo,
      'userId': 'user-1',
      'projectId': 'project-1',
      'institutionId': 'institution-1',
      'consultantId': '',
      'doctorId': 'doctor-1',
      'institutionProjectId': 'institution-project-1',
      'projectName': projectName,
      'institutionName': '娇颜颂医疗中心',
      'consultantName': '',
      'consultantAvatar': null,
      'coverImage': '',
      'amount': 1280.0,
      'currency': 'CNY',
      'status': 'CONSULTATION_PAID',
      'quantity': 2,
      'remark': '皮肤敏感，请提前准备舒缓用品',
      'userPhone': '+8613900000001',
      'appointmentTime': appointmentTime,
      'createdAt': '2026-08-29T10:00:00',
      'updatedAt': '2026-08-29T10:00:00',
      'canVerify': true,
      'canRequestCompletion': false,
    };

final class _FixtureApiClient extends ApiClient {
  _FixtureApiClient(this.orders)
      : super(apiRoot: Uri.parse('https://example.test/api/'));

  final List<Map<String, Object?>> orders;
  int failingActionAttempts = 0;

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    if (path == '/management/orders') {
      return decodeData(orders);
    }
    if (path.startsWith('/management/orders/')) {
      final id = path.substring('/management/orders/'.length);
      return decodeData(orders.singleWhere((order) => order['id'] == id));
    }
    throw StateError('Unexpected GET $path');
  }

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    if (!path.startsWith('/management/orders/')) {
      throw StateError('Unexpected POST $path');
    }
    if (failingActionAttempts > 0) {
      failingActionAttempts--;
      throw StateError('Temporary action failure');
    }
    final id = path.split('/')[3];
    final index = orders.indexWhere((order) => order['id'] == id);
    final updated = {
      ...orders[index],
      'status': 'COMPLETED',
      'canVerify': false,
      'canRequestCompletion': false,
    };
    orders[index] = updated;
    return decodeData(updated);
  }
}

final class _DoctorIdentityRepository implements IdentityRepository {
  const _DoctorIdentityRepository();

  @override
  Future<ManagementContext> loadManagementContext() async =>
      const ManagementContext(
        userId: 'doctor-user-1',
        platformRole: 'USER',
        activeRoles: ['DOCTOR'],
        doctorId: 'doctor-1',
        managedInstitutionIds: [],
        visibleInstitutionIds: [],
        canManageOrders: true,
      );

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnsupportedError(invocation.memberName.toString());
}

final class _UnusedDiscoverRepository implements DiscoverRepository {
  const _UnusedDiscoverRepository();

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnsupportedError(invocation.memberName.toString());
}
