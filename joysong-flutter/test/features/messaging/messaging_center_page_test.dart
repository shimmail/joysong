import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_repository.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_controllers.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_pages.dart';

void main() {
  testWidgets('empty English hub shows the current no-messages baseline',
      (tester) async {
    final repository = _PageMessagingRepository();
    final controller = MessagingHubController(repository);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en', 'US'),
        supportedLocales: const [Locale('zh', 'CN'), Locale('en', 'US')],
        home: MessagingCenterPage(controller: controller),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('No messages'), findsOneWidget);
    expect(find.byKey(const Key('customer-service-start')), findsNothing);
    expect(repository.createCalls, 0);
  });

  testWidgets('delayed loading suppresses the empty state until completion',
      (tester) async {
    final repository = _PageMessagingRepository()
      ..conversationLoad = Completer<List<CustomerServiceConversation>>();
    final controller = MessagingHubController(repository);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en', 'US'),
        supportedLocales: const [Locale('zh', 'CN'), Locale('en', 'US')],
        home: MessagingCenterPage(controller: controller),
      ),
    );
    await tester.pump();
    expect(find.text('No messages'), findsNothing);
    expect(find.byKey(const Key('customer-service-start')), findsNothing);

    repository.conversationLoad!.complete(const []);
    await tester.pumpAndSettle();

    expect(find.text('No messages'), findsOneWidget);
    expect(find.byKey(const Key('customer-service-start')), findsNothing);
    expect(repository.createCalls, 0);
  });

  testWidgets('system and activity entries show their unread counts',
      (tester) async {
    final controller = MessagingHubController(_PageMessagingRepository());

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en', 'US'),
        home: MessagingCenterPage(
          controller: controller,
          systemUnreadCount: 7,
          activityUnreadCount: 105,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.descendant(
        of: find.byKey(const Key('message-center-system')),
        matching: find.text('7'),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: find.byKey(const Key('message-center-activity')),
        matching: find.text('99+'),
      ),
      findsOneWidget,
    );
  });

  testWidgets('notification counts refresh with the message hub lifecycle',
      (tester) async {
    var notificationRefreshes = 0;
    final controller = MessagingHubController(_PageMessagingRepository());

    await tester.pumpWidget(
      MaterialApp(
        home: MessagingCenterPage(
          controller: controller,
          onRefreshNotifications: () async => notificationRefreshes++,
        ),
      ),
    );
    await tester.pump();
    expect(notificationRefreshes, 1);

    await tester.pump(const Duration(seconds: 30));
    await tester.pump();
    expect(notificationRefreshes, 2);
  });

  testWidgets('order-service rows show the fixed English order-chat label',
      (tester) async {
    final repository = _PageMessagingRepository()
      ..dmConversations = const [
        _activeOrderConversation,
        _directConversation,
      ];
    final controller = MessagingHubController(
      repository,
      currentUserId: 'user-1',
    );

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        home: MessagingCenterPage(
          controller: controller,
          currentUserId: 'user-1',
        ),
      ),
    );
    await tester.pumpAndSettle();

    final orderCard = find.byKey(
      const ValueKey('dm-conversation-order-active'),
    );
    expect(
      find.descendant(of: orderCard, matching: find.text('Order chat')),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('dm-conversation-direct-1')),
        matching: find.text('Order chat'),
      ),
      findsNothing,
    );
    expect(find.text('订单沟通'), findsNothing);
  });

  testWidgets('order-service rows show the fixed Chinese order-chat label',
      (tester) async {
    final repository = _PageMessagingRepository()
      ..dmConversations = const [_activeOrderConversation];
    final controller = MessagingHubController(
      repository,
      currentUserId: 'user-1',
    );

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh'), Locale('en')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: MessagingCenterPage(
          controller: controller,
          currentUserId: 'user-1',
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.descendant(
        of: find.byKey(const ValueKey('dm-conversation-order-active')),
        matching: find.text('订单沟通'),
      ),
      findsOneWidget,
    );
    expect(find.text('Order chat'), findsNothing);
  });

  testWidgets('all order-service and direct rows expose local hide',
      (tester) async {
    final repository = _PageMessagingRepository()
      ..dmConversations = const [
        _activeOrderConversation,
        _endedOrderConversation,
        _directConversation,
      ];
    final controller = MessagingHubController(
      repository,
      currentUserId: 'user-1',
    );

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        home: MessagingCenterPage(
          controller: controller,
          currentUserId: 'user-1',
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.longPress(
      find.byKey(const ValueKey('dm-conversation-order-active')),
    );
    await tester.pumpAndSettle();
    expect(find.widgetWithText(ListTile, 'Pin chat'), findsOneWidget);
    expect(find.widgetWithText(ListTile, 'Mark as unread'), findsOneWidget);
    expect(find.widgetWithText(ListTile, 'Delete'), findsOneWidget);

    Navigator.of(tester.element(find.widgetWithText(ListTile, 'Delete'))).pop();
    await tester.pumpAndSettle();
    await tester.longPress(
      find.byKey(const ValueKey('dm-conversation-order-ended')),
    );
    await tester.pumpAndSettle();
    expect(find.widgetWithText(ListTile, 'Delete'), findsOneWidget);

    Navigator.of(tester.element(find.widgetWithText(ListTile, 'Delete'))).pop();
    await tester.pumpAndSettle();
    await tester.longPress(
      find.byKey(const ValueKey('dm-conversation-direct-1')),
    );
    await tester.pumpAndSettle();
    expect(find.widgetWithText(ListTile, 'Delete'), findsOneWidget);
  });

  testWidgets('active order row hides locally and a newer message restores it',
      (tester) async {
    final repository = _PageMessagingRepository()
      ..dmConversations = const [_activeOrderConversation];
    final controller = MessagingHubController(
      repository,
      currentUserId: 'user-1',
    );

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        home: MessagingCenterPage(
          controller: controller,
          currentUserId: 'user-1',
        ),
      ),
    );
    await tester.pumpAndSettle();

    final card = find.byKey(
      const ValueKey('dm-conversation-order-active'),
    );
    await tester.longPress(card);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(ListTile, 'Delete'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Delete'));
    await tester.pumpAndSettle();

    expect(card, findsNothing);

    repository.dmConversations = const [_activeOrderConversationWithNewMessage];
    await controller.refresh();
    await tester.pumpAndSettle();

    expect(card, findsOneWidget);
    expect(find.text('A newer order message'), findsOneWidget);
  });
}

class _PageMessagingRepository extends Fake implements MessagingRepository {
  Completer<List<CustomerServiceConversation>>? conversationLoad;
  int createCalls = 0;
  List<DmConversation> dmConversations = const [];

  @override
  Future<List<DmConversation>> getDmConversations() async => dmConversations;

  @override
  Future<List<CustomerServiceConversation>> getCustomerServiceConversations() =>
      conversationLoad?.future ??
      Future<List<CustomerServiceConversation>>.value(const []);

  @override
  Future<CustomerServiceConversation>
      createCustomerServiceConversation() async {
    createCalls++;
    return _conversation;
  }
}

const _conversation = CustomerServiceConversation(
  id: 'cs-1',
  userAId: 'me',
  userBId: 'support',
  lastMessage: null,
  lastMessageAt: null,
  unreadCount: 0,
  createdAt: '2026-08-06T10:00:00',
  updatedAt: '2026-08-06T10:00:00',
);

const _activeOrderConversation = DmConversation(
  id: 'conversation-order-active',
  conversationType: DmConversationType.orderService,
  orderId: 'order-1',
  userAId: 'consultant-1',
  userBId: 'user-1',
  lastMessage: 'Order service',
  lastMessageAt: '2026-08-21T10:00:00',
  userAUnread: 0,
  userBUnread: 1,
  createdAt: '2026-08-21T09:00:00',
  updatedAt: '2026-08-21T10:00:00',
);

const _endedOrderConversation = DmConversation(
  id: 'conversation-order-ended',
  conversationType: DmConversationType.orderService,
  orderId: 'order-2',
  userAId: 'consultant-1',
  userBId: 'user-1',
  lastMessage: 'Service completed',
  lastMessageAt: '2026-08-20T10:00:00',
  userAUnread: 0,
  userBUnread: 0,
  createdAt: '2026-08-20T09:00:00',
  updatedAt: '2026-08-20T10:00:00',
  canHide: true,
);

const _activeOrderConversationWithNewMessage = DmConversation(
  id: 'conversation-order-active',
  conversationType: DmConversationType.orderService,
  orderId: 'order-1',
  userAId: 'consultant-1',
  userBId: 'user-1',
  lastMessage: 'A newer order message',
  lastMessageAt: '2099-08-21T10:00:00',
  userAUnread: 0,
  userBUnread: 1,
  createdAt: '2026-08-21T09:00:00',
  updatedAt: '2099-08-21T10:00:00',
);

const _directConversation = DmConversation(
  id: 'conversation-direct-1',
  userAId: 'friend-1',
  userBId: 'user-1',
  lastMessage: 'Direct message',
  lastMessageAt: '2026-08-21T09:30:00',
  userAUnread: 0,
  userBUnread: 0,
  createdAt: '2026-08-21T08:00:00',
  updatedAt: '2026-08-21T09:30:00',
);
