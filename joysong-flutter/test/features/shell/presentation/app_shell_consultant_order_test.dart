import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_orders_page.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_pages.dart';
import 'package:joysong_flutter/features/shell/presentation/app_shell.dart';

void main() {
  testWidgets(
    'consultant conversation permission refresh ejects through management handler',
    (tester) async {
      final apiClient = ConsultantRoleApiClient(
        roleFailure: ConsultantRoleFailure.entitlementRefresh,
      );
      addTearDown(apiClient.close);

      await openConsultantOrderConversation(tester, apiClient);
      expect(apiClient.managementContextCalls, 1);
      expect(apiClient.orderConversationCalls, 1);

      tester.binding.handleAppLifecycleStateChanged(
        AppLifecycleState.paused,
      );
      await tester.pump();
      tester.binding.handleAppLifecycleStateChanged(
        AppLifecycleState.hidden,
      );
      await tester.pump();
      tester.binding.handleAppLifecycleStateChanged(
        AppLifecycleState.inactive,
      );
      await tester.pump();
      tester.binding.handleAppLifecycleStateChanged(
        AppLifecycleState.resumed,
      );
      await tester.pumpAndSettle();

      expect(apiClient.orderConversationCalls, 2);
      expect(apiClient.managementContextCalls, 2);
      expect(find.byType(DmThreadPage), findsNothing);
      expect(find.byType(ConsultantOrdersPage), findsNothing);
      expect(find.byType(ManagementCenterPage), findsOneWidget);
      expect(
        find.byKey(const Key('management-consultant-orders')),
        findsNothing,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'consultant send role error ejects immediately through management handler',
    (tester) async {
      final apiClient = ConsultantRoleApiClient(
        roleFailure: ConsultantRoleFailure.send,
      );
      addTearDown(apiClient.close);

      await openConsultantOrderConversation(tester, apiClient);
      expect(apiClient.managementContextCalls, 1);

      await tester.enterText(find.byType(TextField), '发送后立即撤销');
      await tester.tap(find.byTooltip('发送'));
      await tester.pumpAndSettle();

      expect(apiClient.sendMessageCalls, 1);
      expect(apiClient.managementContextCalls, 2);
      expect(find.byType(DmThreadPage), findsNothing);
      expect(find.byType(ConsultantOrdersPage), findsNothing);
      expect(find.byType(ManagementCenterPage), findsOneWidget);
      expect(
        find.byKey(const Key('management-consultant-orders')),
        findsNothing,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'late consultant send success does not update the disposed thread',
    (tester) async {
      final sendMessageCompleter = Completer<Object?>();
      final apiClient = ConsultantRoleApiClient(
        sendMessageCompleter: sendMessageCompleter,
      );
      addTearDown(apiClient.close);

      await openConsultantOrderConversation(tester, apiClient);
      final threadController =
          tester.widget<DmThreadPage>(find.byType(DmThreadPage)).controller;

      await tester.enterText(find.byType(TextField), '发送后返回');
      await tester.tap(find.byTooltip('发送'));
      await tester.pump();

      expect(apiClient.sendMessageCalls, 1);
      expect(sendMessageCompleter.isCompleted, isFalse);
      expect(threadController.pager.items, isEmpty);

      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();

      expect(find.byType(DmThreadPage), findsNothing);
      expect(find.text('订单沟通'), findsOneWidget);

      sendMessageCompleter.complete({
        ...sentMessageJson,
        'content': '发送后返回',
      });
      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.byType(DmThreadPage), findsNothing);
      expect(threadController.pager.items, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );
}

Future<void> openConsultantOrderConversation(
  WidgetTester tester,
  ConsultantRoleApiClient apiClient,
) async {
  await tester.pumpWidget(
    MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: const [Locale('zh'), Locale('en')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: AppShell(
        agentConfig: const AgentConfig(),
        apiClient: apiClient,
        currentUserId: 'consultant-1',
      ),
    ),
  );
  await tester.pumpAndSettle();

  await tester.tap(find.text('我的'));
  await tester.pumpAndSettle();
  final profileScrollable = find
      .descendant(
        of: find.byKey(const PageStorageKey<String>('profile')),
        matching: find.byType(Scrollable),
      )
      .first;
  expect(profileScrollable, findsOneWidget);
  final managementEntry = find.text('专业管理');
  await tester.scrollUntilVisible(
    managementEntry,
    220,
    scrollable: profileScrollable,
  );
  await tester.pumpAndSettle();
  await tester.tap(managementEntry);
  await tester.pumpAndSettle();
  final consultantOrdersEntry = find.byKey(
    const Key('management-consultant-orders'),
  );
  await tester.ensureVisible(consultantOrdersEntry);
  await tester.tap(consultantOrdersEntry);
  await tester.pumpAndSettle();
  await tester.tap(find.text('测试项目'));
  await tester.pumpAndSettle();
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
  await tester.tap(conversationButton);
  await tester.pumpAndSettle();

  expect(find.byType(DmThreadPage), findsOneWidget);
}

enum ConsultantRoleFailure { entitlementRefresh, send }

final class ConsultantRoleApiClient extends ApiClient {
  ConsultantRoleApiClient({this.roleFailure, this.sendMessageCompleter})
      : super(apiRoot: Uri.parse('https://api.example.com/api/'));

  final ConsultantRoleFailure? roleFailure;
  final Completer<Object?>? sendMessageCompleter;

  int managementContextCalls = 0;
  int orderConversationCalls = 0;
  int sendMessageCalls = 0;

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    if (path == '/management/context') {
      managementContextCalls += 1;
      return decodeData(
        managementContextCalls == 1
            ? activeManagementContextJson
            : revokedManagementContextJson,
      );
    }
    if (path == 'consultant/orders') {
      return decodeData(consultantOrderPageJson);
    }
    if (path == 'consultant/orders/order-1') {
      return decodeData(consultantOrderDetailJson);
    }
    if (path == 'notifications' ||
        path == 'dm/conversations' ||
        path == 'cs/conversations' ||
        path == 'dm/conversations/conversation-1/messages') {
      return decodeData(const <Object?>[]);
    }
    if (path == 'notifications/unread-counts') {
      return decodeData(const {'total': 0, 'system': 0, 'activity': 0});
    }
    if (path == 'user/profile') {
      return decodeData(const {
        'id': 'consultant-1',
        'phone': null,
        'email': null,
        'nickname': '顾问',
        'avatar': '',
        'gender': '',
        'city': '',
        'bio': '',
        'birthday': null,
        'role': 'USER',
        'hasPassword': true,
      });
    }
    return null;
  }

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    if (path == 'orders/order-1/service-conversation') {
      orderConversationCalls += 1;
      if (roleFailure == ConsultantRoleFailure.entitlementRefresh &&
          orderConversationCalls > 1) {
        throw roleRequiredException;
      }
      return decodeData(orderConversationJson);
    }
    if (path == 'dm/conversations/conversation-1/messages') {
      sendMessageCalls += 1;
      if (roleFailure == ConsultantRoleFailure.send) {
        throw roleRequiredException;
      }
      final completer = sendMessageCompleter;
      if (completer != null) {
        return decodeData(await completer.future);
      }
      return decodeData(sentMessageJson);
    }
    return null;
  }

  @override
  Future<T?> put<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async =>
      null;
}

const activeManagementContextJson = <String, Object?>{
  'userId': 'consultant-1',
  'platformRole': 'USER',
  'activeRoles': <String>['CONSULTANT'],
  'managedInstitutionIds': <String>[],
  'visibleInstitutionIds': <String>[],
  'canAccessConsultantOrderWorkbench': true,
};

const revokedManagementContextJson = <String, Object?>{
  'userId': 'consultant-1',
  'platformRole': 'USER',
  'activeRoles': <String>[],
  'managedInstitutionIds': <String>[],
  'visibleInstitutionIds': <String>[],
  'canAccessConsultantOrderWorkbench': false,
};

const consultantOrderSummaryJson = <String, Object?>{
  'id': 'order-1',
  'orderNo': 'JS202608300001',
  'stage': 'ACTIVE',
  'status': 'SERVICE_ACTIVE',
  'refundStatus': 'NONE',
  'project': <String, Object?>{
    'id': 'project-1',
    'name': '测试项目',
    'coverImage': '',
  },
  'institution': <String, Object?>{
    'id': 'institution-1',
    'name': '测试机构',
  },
  'customer': <String, Object?>{
    'displayName': '公开顾客',
    'avatar': null,
  },
  'appointmentTime': '2026-09-01T10:00:00',
  'updatedAt': '2026-08-30T12:00:00',
  'conversationReadable': true,
  'messageSendable': true,
  'readOnly': false,
};

const consultantOrderPageJson = <String, Object?>{
  'items': <Object?>[consultantOrderSummaryJson],
  'offset': 0,
  'limit': 20,
  'hasMore': false,
};

const consultantOrderDetailJson = <String, Object?>{
  ...consultantOrderSummaryJson,
  'doctor': <String, Object?>{'id': 'doctor-1', 'name': '测试医生'},
  'remark': '公开备注',
  'createdAt': '2026-08-29T08:00:00',
  'serviceActivatedAt': '2026-08-30T09:00:00',
  'completedAt': null,
  'conversation': <String, Object?>{'readable': true, 'sendable': true},
};

const orderConversationJson = <String, Object?>{
  'id': 'conversation-1',
  'userAId': 'consumer-1',
  'userBId': 'consultant-1',
  'lastMessage': 'cached service message',
  'lastMessageAt': '2026-08-30T12:00:00',
  'userAUnread': 0,
  'userBUnread': 0,
  'createdAt': '2026-08-30T10:00:00',
  'updatedAt': '2026-08-30T12:00:00',
  'conversationType': 'ORDER_SERVICE',
  'orderId': 'order-1',
  'firstMessageLimitApplies': false,
  'waitingForReply': false,
  'canHide': false,
  'serviceMessagingEnabled': true,
};

const sentMessageJson = <String, Object?>{
  'id': 'message-1',
  'conversationId': 'conversation-1',
  'senderId': 'consultant-1',
  'content': '发送后立即撤销',
  'messageType': 'TEXT',
  'isRead': false,
  'createdAt': '2026-08-30T12:01:00',
};

const roleRequiredException = ApiException(
  message: 'server role text',
  httpStatus: 403,
  businessCode: 403,
  errorCode: 'CONSULTANT_ROLE_REQUIRED',
);
