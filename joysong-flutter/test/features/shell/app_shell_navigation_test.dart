import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_pages.dart';
import 'package:joysong_flutter/features/orders/presentation/order_detail_page.dart';
import 'package:joysong_flutter/features/shell/presentation/app_shell.dart';

import '../orders/order_test_fixtures.dart';

void main() {
  testWidgets('system back pops nested content before leaving the app shell',
      (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () => Navigator.of(context).push<void>(
              MaterialPageRoute(
                builder: (_) => const AppShell(agentConfig: AgentConfig()),
              ),
            ),
            child: const Text('open shell'),
          ),
        ),
      ),
    );

    await tester.tap(find.text('open shell'));
    await tester.pumpAndSettle();

    final nestedNavigator = find.descendant(
      of: find.byType(AppShell),
      matching: find.byType(Navigator),
    );
    expect(nestedNavigator, findsOneWidget);
    final nestedNavigatorState = tester.state<NavigatorState>(nestedNavigator);
    nestedNavigatorState.push<void>(
      MaterialPageRoute(
        builder: (_) => const Scaffold(body: Text('nested detail')),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('nested detail'), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();

    expect(find.text('nested detail'), findsNothing);
    expect(find.byType(AppShell), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();

    expect(find.byType(AppShell), findsNothing);
    expect(find.text('open shell'), findsOneWidget);
  });

  testWidgets(
      'agent institution handoff opens the selected consultant direct message',
      (tester) async {
    final client = _AgentHandoffApiClient();
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: AppShell(
          agentConfig: const AgentConfig(),
          apiClient: client,
          currentUserId: 'user-1',
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('消息'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('message-center-yanyan')));
    await tester.pumpAndSettle();

    final consultationAction = find.byKey(
      const ValueKey('agent-human-consult-INSTITUTION-institution-1'),
    );
    expect(consultationAction, findsOneWidget);
    await tester.ensureVisible(consultationAction);
    await tester.tap(consultationAction);
    await tester.pumpAndSettle();

    final consultant = find.byKey(
      const ValueKey('institution-consultant-consultant-2'),
    );
    expect(consultant, findsOneWidget);
    await tester.tap(consultant);
    await tester.pumpAndSettle();

    final dmPost = client.posts.singleWhere(
      (request) => request.$1 == 'dm/conversations',
    );
    expect(dmPost.$2, {'targetId': 'consultant-2'});
  });

  testWidgets('order detail uses only the order-scoped conversation endpoint',
      (tester) async {
    _useLargeTestSurface(tester);
    final client = _OrderServiceApiClient(
      freshStatus: 'SERVICE_ACTIVE',
      freshReadable: true,
      freshSendEnabled: true,
    );
    await _pumpShell(tester, client);

    await tester.tap(find.text('我的'));
    await tester.pumpAndSettle();
    final ordersEntry = find.text('我的订单');
    await tester.ensureVisible(ordersEntry);
    await tester.tap(ordersEntry);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('order-1')));
    await tester.pumpAndSettle();

    final chatButton = find.byKey(const Key('service-chat-button'));
    await tester.ensureVisible(chatButton);
    await tester.tap(chatButton);
    await tester.pumpAndSettle();

    expect(
      client.posts,
      contains(('orders/order-1/service-conversation', null)),
    );
    expect(
      client.posts.where((request) => request.$1 == 'dm/conversations'),
      isEmpty,
    );
    expect(client.orderReads, 2);
    expect(find.byType(DmThreadPage), findsOneWidget);
    expect(find.byType(TextField), findsOneWidget);
  });

  testWidgets('message center refreshes order entitlement before read-only chat',
      (tester) async {
    _useLargeTestSurface(tester);
    final client = _OrderServiceApiClient(
      freshStatus: 'REFUND_PROCESSING',
      freshReadable: true,
      freshSendEnabled: false,
    );
    await _pumpShell(tester, client);

    await tester.tap(find.text('消息'));
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const ValueKey('dm-conversation-order-1')),
    );
    await tester.pumpAndSettle();

    expect(client.orderReads, 1);
    expect(client.posts, isEmpty);
    expect(find.byType(DmThreadPage), findsOneWidget);
    expect(find.byType(TextField), findsNothing);
  });

  testWidgets(
      'open order conversation becomes read-only when the app resumes after order completion',
      (tester) async {
    _useLargeTestSurface(tester);
    final client = _OrderServiceApiClient(
      freshStatus: 'SERVICE_ACTIVE',
      freshReadable: true,
      freshSendEnabled: true,
    );
    await _pumpShell(tester, client);

    await tester.tap(find.text('消息'));
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const ValueKey('dm-conversation-order-1')),
    );
    await tester.pumpAndSettle();

    expect(client.orderReads, 1);
    expect(find.byType(TextField), findsOneWidget);

    client
      ..freshStatus = 'COMPLETED'
      ..freshSendEnabled = false;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    await tester.pump();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();

    expect(client.orderReads, 2);
    expect(find.byType(TextField), findsNothing);
  });

  testWidgets(
      'older entitlement refresh cannot restore sending after a newer read-only result',
      (tester) async {
    _useLargeTestSurface(tester);
    final client = _OrderServiceApiClient(
      freshStatus: 'SERVICE_ACTIVE',
      freshReadable: true,
      freshSendEnabled: true,
    );
    await _pumpShell(tester, client);

    await tester.tap(find.text('消息'));
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const ValueKey('dm-conversation-order-1')),
    );
    await tester.pumpAndSettle();
    expect(find.byType(TextField), findsOneWidget);

    final olderActiveResponse = Completer<Map<String, Object?>>();
    final newerCompletedResponse = Completer<Map<String, Object?>>();
    client.queuedOrderReads.addAll([
      olderActiveResponse.future,
      newerCompletedResponse.future,
    ]);

    await tester.pump(const Duration(seconds: 15));
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    await tester.pump();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(client.orderReads, 3);

    newerCompletedResponse.complete(
      client.orderResponse(status: 'COMPLETED', sendEnabled: false),
    );
    await tester.pumpAndSettle();
    expect(find.byType(TextField), findsNothing);

    olderActiveResponse.complete(
      client.orderResponse(status: 'SERVICE_ACTIVE', sendEnabled: true),
    );
    await tester.pumpAndSettle();
    expect(find.byType(TextField), findsNothing);
  });

  testWidgets('dm notification rejects a server-filtered order conversation',
      (tester) async {
    _useLargeTestSurface(tester);
    final client = _OrderServiceApiClient(
      freshStatus: 'PENDING_SERVICE_FEE',
      freshReadable: false,
      freshSendEnabled: false,
      includeNotification: true,
    );
    await _pumpShell(tester, client);

    await tester.tap(find.text('消息'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('message-center-system')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('订单沟通更新'));
    await tester.pumpAndSettle();

    expect(client.orderReads, 0);
    expect(find.byType(DmThreadPage), findsNothing);
    expect(find.text('当前会话不可查看'), findsOneWidget);
  });

  testWidgets('opening the messages tab does not mark all notifications read',
      (tester) async {
    final client = _OrderServiceApiClient(
      freshStatus: 'SERVICE_ACTIVE',
      freshReadable: true,
      freshSendEnabled: true,
      includeNotification: true,
    );
    await _pumpShell(tester, client);

    await tester.tap(find.text('消息'));
    await tester.pumpAndSettle();

    expect(client.puts, isNot(contains('notifications/read-all')));
  });

  testWidgets('order notification is read once and opens its detail directly',
      (tester) async {
    _useLargeTestSurface(tester);
    final client = _OrderServiceApiClient(
      freshStatus: 'SERVICE_ACTIVE',
      freshReadable: true,
      freshSendEnabled: true,
      notifications: const [_orderNotificationJson],
    );
    await _pumpShell(tester, client);

    await tester.tap(find.text('消息'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('message-center-system')));
    await tester.pumpAndSettle();
    expect(find.text('订单服务已开启'), findsOneWidget);

    await tester.tap(find.text('订单服务已开启'));
    await tester.pumpAndSettle();

    expect(client.puts, contains('notifications/notification-order-detail/read'));
    expect(client.orderReads, 1);
    expect(find.byType(OrderDetailPage), findsOneWidget);
  });
}

void _useLargeTestSurface(WidgetTester tester) {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

Future<void> _pumpShell(
  WidgetTester tester,
  ApiClient client,
) async {
  FlutterSecureStorage.setMockInitialValues(const {});
  await tester.pumpWidget(
    MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: const [Locale('zh')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: AppShell(
        agentConfig: const AgentConfig(),
        apiClient: client,
        currentUserId: 'user-1',
      ),
    ),
  );
  await tester.pumpAndSettle();
}

final class _AgentHandoffApiClient extends ApiClient {
  _AgentHandoffApiClient()
      : super(
          apiRoot: Uri.parse('http://localhost/api/'),
          httpClient: _TestHttpClient(),
        );

  final posts = <(String, Object?)>[];

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    final Object data = switch (path) {
      'notifications/unread-count' => 0,
      'notifications' ||
      'dm/conversations' ||
      'cs/conversations' =>
        const <Object?>[],
      '/discover/filter-options' => const <String, Object?>{
          'categories': <String>[],
          'tags': <String>[],
          'cities': <String>[],
        },
      'chat/sessions' => const <Object?>[_agentSessionJson],
      'chat/sessions/session-human/messages' => const <Object?>[
          _agentInstitutionMessageJson
        ],
      'discover/institutions/institution-1/consultants' => const <Object?>[
          <String, Object?>{'id': 'consultant-2', 'name': '林顾问'},
        ],
      _ => const <Object?>[],
    };
    return decodeData(data);
  }

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    posts.add((path, body));
    if (path == 'dm/conversations') return decodeData(_dmConversationJson);
    return decodeData(const <String, Object?>{});
  }

  @override
  Future<T?> put<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async =>
      decodeData(null);
}

final class _TestHttpClient extends Fake implements HttpClient {}

final class _OrderServiceApiClient extends ApiClient {
  _OrderServiceApiClient({
    required this.freshStatus,
    required this.freshReadable,
    required this.freshSendEnabled,
    this.includeNotification = false,
    this.notifications = const [],
  }) : super(
          apiRoot: Uri.parse('http://localhost/api/'),
          httpClient: _TestHttpClient(),
        );

  String freshStatus;
  final bool freshReadable;
  bool freshSendEnabled;
  final bool includeNotification;
  final List<Map<String, Object?>> notifications;
  final gets = <String>[];
  final posts = <(String, Object?)>[];
  final puts = <String>[];
  final queuedOrderReads = <Future<Map<String, Object?>>>[];

  int get orderReads => gets.where((path) => path == 'orders/order-1').length;

  Map<String, Object?> get _freshOrder => sampleOrderJson(
        status: freshStatus,
        paymentFlow: 'TRAVEL_GROUND_SERVICE_ONLY',
        consultantBound: true,
        serviceActivated: freshStatus != 'PENDING_SERVICE_FEE',
        consultantDetailsVisible:
            freshStatus != 'PENDING_SERVICE_FEE' && freshStatus != 'REFUNDED',
        serviceConversationReadable: freshReadable,
        serviceMessagingEnabled: freshSendEnabled,
      );

  Map<String, Object?> orderResponse({
    required String status,
    required bool sendEnabled,
  }) =>
      sampleOrderJson(
        status: status,
        paymentFlow: 'TRAVEL_GROUND_SERVICE_ONLY',
        consultantBound: true,
        serviceActivated: true,
        consultantDetailsVisible: true,
        serviceConversationReadable: true,
        serviceMessagingEnabled: sendEnabled,
      );

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    gets.add(path);
    if (path == 'orders/order-1' && queuedOrderReads.isNotEmpty) {
      return decodeData(await queuedOrderReads.removeAt(0));
    }
    final Object data = switch (path) {
      'notifications/unread-count' =>
        notifications.isNotEmpty || includeNotification ? 1 : 0,
      'notifications' =>
        notifications.isNotEmpty
            ? notifications
            : (includeNotification ? const [_dmNotificationJson] : const <Object?>[]),
      'dm/conversations' => freshReadable
          ? const [_orderServiceConversationJson]
          : const <Object?>[],
      'cs/conversations' => const <Object?>[],
      'orders' => [_freshOrder],
      'orders/order-1' => _freshOrder,
      'orders/order-1/status-logs' => const <Object?>[],
      'dm/conversations/conversation-order-1/messages' => const <Object?>[],
      '/discover/filter-options' => const <String, Object?>{
          'categories': <String>[],
          'tags': <String>[],
          'cities': <String>[],
        },
      _ => const <Object?>[],
    };
    return decodeData(data);
  }

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    posts.add((path, body));
    if (path == 'orders/order-1/service-conversation') {
      return decodeData(_orderServiceConversationJson);
    }
    return decodeData(const <String, Object?>{});
  }

  @override
  Future<T?> put<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    puts.add(path);
    return decodeData(null);
  }
}

const _agentSessionJson = <String, Object?>{
  'id': 'session-human',
  'persona': 'CONSULTANT',
  'contextType': 'GENERAL',
  'contextId': '',
  'title': '真人咨询',
  'lastMessage': '请选择希望咨询的机构。',
  'createdAt': '2026-08-17T09:00:00Z',
  'updatedAt': '2026-08-17T09:01:00Z',
};

const _agentInstitutionMessageJson = <String, Object?>{
  'id': 'assistant-human',
  'sessionId': 'session-human',
  'role': 'ASSISTANT',
  'content': '请选择希望咨询的机构，随后可查看当前可联系的咨询师。',
  'createdAt': '2026-08-17T09:01:00Z',
  'catalogItems': <Object?>[
    <String, Object?>{
      'type': 'INSTITUTION',
      'id': 'institution-1',
      'name': '机构一',
      'subtitle': '上海',
      'summary': '',
      'attributes': <String, String>{'City': '上海'},
      'institutionId': 'institution-1',
      'projectId': null,
      'canChatWithHuman': true,
    },
  ],
  'comparisonRequest': null,
  'catalogReport': null,
};

const _dmConversationJson = <String, Object?>{
  'id': 'dm-human',
  'userAId': 'user-1',
  'userBId': 'consultant-2',
  'lastMessage': null,
  'lastMessageAt': null,
  'userAUnread': 0,
  'userBUnread': 0,
  'createdAt': '2026-08-17T09:02:00Z',
  'updatedAt': '2026-08-17T09:02:00Z',
  'firstMessageLimitApplies': false,
  'waitingForReply': false,
};

const _orderServiceConversationJson = <String, Object?>{
  'id': 'conversation-order-1',
  'conversationType': 'ORDER_SERVICE',
  'orderId': 'order-1',
  'userAId': 'consultant-1',
  'userBId': 'user-1',
  'lastMessage': '订单服务记录',
  'lastMessageAt': '2026-08-21T10:05:00',
  'userAUnread': 0,
  'userBUnread': 1,
  'createdAt': '2026-08-21T10:00:00',
  'updatedAt': '2026-08-21T10:05:00',
  'firstMessageLimitApplies': false,
  'waitingForReply': false,
};

const _dmNotificationJson = <String, Object?>{
  'id': 'notification-order-1',
  'userId': 'user-1',
  'type': 'DM_NEW',
  'title': '订单沟通更新',
  'content': '您有一条订单服务消息',
  'targetType': 'dm_conversation',
  'targetId': 'conversation-order-1',
  'isRead': false,
  'createdAt': '2026-08-21T10:06:00',
};

const _orderNotificationJson = <String, Object?>{
  'id': 'notification-order-detail',
  'userId': 'user-1',
  'type': 'ORDER_SERVICE_ACTIVATED',
  'title': '订单服务已开启',
  'content': '服务已开始，请查看订单详情',
  'targetType': 'order',
  'targetId': 'order-1',
  'isRead': false,
  'createdAt': '2026-08-25T10:00:00',
};
