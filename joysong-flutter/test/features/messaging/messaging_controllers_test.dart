import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_repository.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_controllers.dart';

void main() {
  test('notifications are deduplicated and read state is updated', () async {
    final repository = _FakeMessagingRepository();
    final controller = NotificationController(repository);

    await controller.refresh();
    expect(controller.items, hasLength(1));
    expect(controller.unreadCount, 1);

    await controller.markRead('notification-1');
    expect(repository.markNotificationReadCalls, 1);
    expect(controller.items.single.isRead, isTrue);
    expect(controller.unreadCount, 0);
  });

  test('notification controller keeps category unread counts in sync',
      () async {
    final repository = _FakeMessagingRepository()
      ..notificationUnreadCounts = const NotificationUnreadCounts(
        total: 3,
        system: 2,
        activity: 1,
      );
    final controller = NotificationController(repository);

    await controller.refresh();
    expect(controller.unreadCount, 3);
    expect(controller.systemUnreadCount, 2);
    expect(controller.activityUnreadCount, 1);

    await controller.markRead('notification-1');
    expect(controller.unreadCount, 2);
    expect(controller.systemUnreadCount, 1);
    expect(controller.activityUnreadCount, 1);
  });

  test('reading an activity notification decrements only the activity count',
      () async {
    final repository = _FakeMessagingRepository()
      ..notifications = const [_activityNotification]
      ..notificationUnreadCounts = const NotificationUnreadCounts(
        total: 3,
        system: 2,
        activity: 1,
      );
    final controller = NotificationController(repository);

    await controller.refresh();
    await controller.markRead('notification-activity');

    expect(controller.unreadCount, 2);
    expect(controller.systemUnreadCount, 2);
    expect(controller.activityUnreadCount, 0);
  });

  test('marking all notifications read clears both category counts', () async {
    final repository = _FakeMessagingRepository()
      ..notificationUnreadCounts = const NotificationUnreadCounts(
        total: 3,
        system: 2,
        activity: 1,
      );
    final controller = NotificationController(repository);

    await controller.refresh();
    await controller.markAllRead();

    expect(controller.unreadCount, 0);
    expect(controller.systemUnreadCount, 0);
    expect(controller.activityUnreadCount, 0);
  });

  test('concurrent reads of one notification decrement its count once',
      () async {
    final completion = Completer<void>();
    final repository = _FakeMessagingRepository()
      ..markNotificationReadCompletion = completion
      ..notificationUnreadCounts = const NotificationUnreadCounts(
        total: 3,
        system: 2,
        activity: 1,
      );
    final controller = NotificationController(repository);
    await controller.refresh();

    final first = controller.markRead('notification-1');
    final second = controller.markRead('notification-1');
    await Future<void>.delayed(Duration.zero);
    completion.complete();
    await Future.wait([first, second]);

    expect(repository.markNotificationReadCalls, 1);
    expect(controller.unreadCount, 2);
    expect(controller.systemUnreadCount, 1);
    expect(controller.activityUnreadCount, 1);
  });

  test('a completed refresh prevents an in-flight read from decrementing again',
      () async {
    final completion = Completer<void>();
    final repository = _FakeMessagingRepository()
      ..markNotificationReadCompletion = completion
      ..notificationUnreadCounts = const NotificationUnreadCounts(
        total: 3,
        system: 2,
        activity: 1,
      );
    final controller = NotificationController(repository);
    await controller.refresh();

    final read = controller.markRead('notification-1');
    await Future<void>.delayed(Duration.zero);
    repository
      ..notifications = const [_readNotification]
      ..notificationUnreadCounts = const NotificationUnreadCounts(
        total: 2,
        system: 1,
        activity: 1,
      );
    await controller.refresh();
    completion.complete();
    await read;

    expect(controller.unreadCount, 2);
    expect(controller.systemUnreadCount, 1);
    expect(controller.activityUnreadCount, 1);
  });

  test('a mixed refresh during a read cannot apply mismatched unread state',
      () async {
    final readCompletion = Completer<void>();
    final repository = _FakeMessagingRepository()
      ..markNotificationReadCompletion = readCompletion
      ..notificationUnreadCounts = const NotificationUnreadCounts(
        total: 3,
        system: 2,
        activity: 1,
      );
    final controller = NotificationController(repository);
    await controller.refresh();

    final notificationsCompletion = Completer<List<AppNotification>>();
    final countsCompletion = Completer<NotificationUnreadCounts>();
    repository
      ..notificationLoadCompletion = notificationsCompletion
      ..notificationCountsCompletion = countsCompletion;

    final read = controller.markRead('notification-1');
    final refresh = controller.refresh();
    notificationsCompletion.complete(const [_notification]);
    countsCompletion.complete(
      const NotificationUnreadCounts(total: 2, system: 1, activity: 1),
    );
    await refresh;
    readCompletion.complete();
    await read;

    expect(controller.items.single.isRead, isTrue);
    expect(controller.unreadCount, 2);
    expect(controller.systemUnreadCount, 1);
    expect(controller.activityUnreadCount, 1);
  });

  test('DM thread passes before cursor, deduplicates, and sends once',
      () async {
    final repository = _FakeMessagingRepository();
    final controller = DmThreadController(
      repository: repository,
      conversationId: 'conversation-1',
      pageSize: 2,
    );

    await controller.initialize();
    await controller.pager.loadOlder();
    await controller.send('你好');

    expect(repository.dmBeforeValues, [null, '2026-08-06T10:02:00']);
    expect(controller.pager.items.map((item) => item.id),
        ['m1', 'm2', 'm3', 'm4']);
    expect(repository.dmSendCalls, 1);
    expect(repository.dmReadCalls, 1);
  });

  test('customer service uses the same cursor and id guarantees', () async {
    final repository = _FakeMessagingRepository();
    final controller = CustomerServiceThreadController(
      repository: repository,
      conversationId: 'cs-1',
      pageSize: 2,
    );

    await controller.initialize();
    await controller.pager.loadOlder();
    await controller.send('需要人工帮助');

    expect(repository.csBeforeValues, [null, '2026-08-06T10:02:00']);
    expect(controller.pager.items.map((item) => item.id),
        ['c1', 'c2', 'c3', 'c4']);
    expect(repository.csSendCalls, 1);
    expect(repository.csReadCalls, 1);
  });

  test('customer service reuses an existing conversation without POST',
      () async {
    final repository = _FakeMessagingRepository();
    final controller = MessagingHubController(repository)
      ..customerServiceConversations = const [_conversation];

    final result = await controller.openCustomerService();

    expect(result?.id, _conversation.id);
    expect(repository.csCreateCalls, 0);
  });

  test('empty customer service state creates once across concurrent taps',
      () async {
    final repository = _FakeMessagingRepository();
    final controller = MessagingHubController(repository);
    await controller.refresh();

    final results = await Future.wait([
      controller.openCustomerService(),
      controller.openCustomerService(),
    ]);

    expect(repository.csCreateCalls, 1);
    expect(results.whereType<CustomerServiceConversation>(), hasLength(1));
    expect(controller.customerServiceConversations, const [_conversation]);
    expect(controller.customerServiceErrorMessage, isNull);
  });

  test('customer service creation failure is exposed and can be retried',
      () async {
    final repository = _FakeMessagingRepository()..failCsCreate = true;
    final controller = MessagingHubController(repository);
    await controller.refresh();

    expect(await controller.openCustomerService(), isNull);
    expect(controller.customerServiceErrorMessage, isNotNull);
    expect(controller.isOpeningCustomerService, isFalse);

    repository.failCsCreate = false;
    expect((await controller.openCustomerService())?.id, _conversation.id);
    expect(repository.csCreateCalls, 2);
  });
}

class _FakeMessagingRepository extends Fake implements MessagingRepository {
  int markNotificationReadCalls = 0;
  Completer<void>? markNotificationReadCompletion;
  Completer<List<AppNotification>>? notificationLoadCompletion;
  Completer<NotificationUnreadCounts>? notificationCountsCompletion;
  int dmSendCalls = 0;
  int dmReadCalls = 0;
  int csSendCalls = 0;
  int csReadCalls = 0;
  int csCreateCalls = 0;
  bool failCsCreate = false;
  final dmBeforeValues = <String?>[];
  final csBeforeValues = <String?>[];
  List<AppNotification> notifications = const [_notification, _notification];
  NotificationUnreadCounts notificationUnreadCounts =
      const NotificationUnreadCounts(total: 1, system: 1, activity: 0);

  @override
  Future<List<DmConversation>> getDmConversations() async => const [];

  @override
  Future<List<CustomerServiceConversation>>
      getCustomerServiceConversations() async => const [];

  @override
  Future<CustomerServiceConversation>
      createCustomerServiceConversation() async {
    csCreateCalls++;
    if (failCsCreate) throw StateError('offline');
    return _conversation;
  }

  @override
  Future<List<AppNotification>> getNotifications({int limit = 50}) =>
      notificationLoadCompletion?.future ??
      Future<List<AppNotification>>.value(notifications);

  @override
  Future<int> getUnreadNotificationCount() async => 1;

  @override
  Future<NotificationUnreadCounts> getUnreadNotificationCounts() =>
      notificationCountsCompletion?.future ??
      Future<NotificationUnreadCounts>.value(notificationUnreadCounts);

  @override
  Future<void> markNotificationRead(String notificationId) async {
    markNotificationReadCalls++;
    await markNotificationReadCompletion?.future;
  }

  @override
  Future<void> markAllNotificationsRead() async {}

  @override
  Future<List<DmMessage>> getDmMessages(
    String conversationId, {
    int limit = 30,
    String? before,
  }) async {
    dmBeforeValues.add(before);
    return before == null
        ? const [
            _dm2,
            DmMessage(
              id: 'm3',
              conversationId: 'conversation-1',
              senderId: 'other',
              content: '3',
              messageType: 'TEXT',
              isRead: true,
              createdAt: '2026-08-06T10:03:00',
            ),
          ]
        : const [_dm1, _dm2];
  }

  @override
  Future<void> markDmConversationRead(String conversationId) async {
    dmReadCalls++;
  }

  @override
  Future<DmMessage> sendDmMessage(
    String conversationId,
    String content, {
    String messageType = 'TEXT',
  }) async {
    dmSendCalls++;
    return DmMessage(
      id: 'm4',
      conversationId: conversationId,
      senderId: 'me',
      content: content,
      messageType: messageType,
      isRead: true,
      createdAt: '2026-08-06T10:04:00',
    );
  }

  @override
  Future<List<CustomerServiceMessage>> getCustomerServiceMessages(
    String conversationId, {
    int limit = 50,
    String? before,
  }) async {
    csBeforeValues.add(before);
    return before == null
        ? const [
            _cs2,
            CustomerServiceMessage(
              id: 'c3',
              senderId: 'cs',
              senderName: '客服',
              content: '3',
              messageType: 'TEXT',
              isRead: true,
              createdAt: '2026-08-06T10:03:00',
            ),
          ]
        : const [_cs1, _cs2];
  }

  @override
  Future<void> markCustomerServiceConversationRead(
      String conversationId) async {
    csReadCalls++;
  }

  @override
  Future<CustomerServiceMessage> sendCustomerServiceMessage(
    String conversationId,
    String content, {
    String messageType = 'TEXT',
  }) async {
    csSendCalls++;
    return CustomerServiceMessage(
      id: 'c4',
      senderId: 'me',
      senderName: '我',
      content: content,
      messageType: messageType,
      isRead: true,
      createdAt: '2026-08-06T10:04:00',
    );
  }
}

const _notification = AppNotification(
  id: 'notification-1',
  userId: 'me',
  type: 'SYSTEM',
  title: '通知',
  content: '内容',
  targetType: '',
  targetId: '',
  isRead: false,
  createdAt: '2026-08-06T10:00:00',
);

const _activityNotification = AppNotification(
  id: 'notification-activity',
  userId: 'me',
  type: 'PROMOTION',
  title: '活动通知',
  content: '内容',
  targetType: '',
  targetId: '',
  isRead: false,
  createdAt: '2026-08-06T10:00:00',
);

const _readNotification = AppNotification(
  id: 'notification-1',
  userId: 'me',
  type: 'SYSTEM',
  title: '通知',
  content: '内容',
  targetType: '',
  targetId: '',
  isRead: true,
  createdAt: '2026-08-06T10:00:00',
);

const _conversation = CustomerServiceConversation(
  id: 'cs-conversation-1',
  userAId: 'me',
  userBId: 'support',
  lastMessage: null,
  lastMessageAt: null,
  unreadCount: 0,
  createdAt: '2026-08-06T10:00:00',
  updatedAt: '2026-08-06T10:00:00',
);

const _dm1 = DmMessage(
  id: 'm1',
  conversationId: 'conversation-1',
  senderId: 'other',
  content: '1',
  messageType: 'TEXT',
  isRead: true,
  createdAt: '2026-08-06T10:01:00',
);

const _dm2 = DmMessage(
  id: 'm2',
  conversationId: 'conversation-1',
  senderId: 'other',
  content: '2',
  messageType: 'TEXT',
  isRead: true,
  createdAt: '2026-08-06T10:02:00',
);

const _cs1 = CustomerServiceMessage(
  id: 'c1',
  senderId: 'cs',
  senderName: '客服',
  content: '1',
  messageType: 'TEXT',
  isRead: true,
  createdAt: '2026-08-06T10:01:00',
);

const _cs2 = CustomerServiceMessage(
  id: 'c2',
  senderId: 'cs',
  senderName: '客服',
  content: '2',
  messageType: 'TEXT',
  isRead: true,
  createdAt: '2026-08-06T10:02:00',
);
