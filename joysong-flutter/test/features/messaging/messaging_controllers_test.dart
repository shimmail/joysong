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
  int dmSendCalls = 0;
  int dmReadCalls = 0;
  int csSendCalls = 0;
  int csReadCalls = 0;
  int csCreateCalls = 0;
  bool failCsCreate = false;
  final dmBeforeValues = <String?>[];
  final csBeforeValues = <String?>[];

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
  Future<List<AppNotification>> getNotifications({int limit = 50}) async =>
      const [_notification, _notification];

  @override
  Future<int> getUnreadNotificationCount() async => 1;

  @override
  Future<void> markNotificationRead(String notificationId) async {
    markNotificationReadCalls++;
  }

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
