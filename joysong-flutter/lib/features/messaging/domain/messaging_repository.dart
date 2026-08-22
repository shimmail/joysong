import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';

abstract interface class MessagingRepository {
  Future<List<AppNotification>> getNotifications({int limit = 50});

  Future<int> getUnreadNotificationCount();

  Future<void> markNotificationRead(String notificationId);

  Future<void> markAllNotificationsRead();

  Future<List<DmConversation>> getDmConversations();

  Future<DmConversation> createDmConversation(String targetId);

  Future<DmConversation> createOrderServiceConversation(String orderId);

  Future<List<DmMessage>> getDmMessages(
    String conversationId, {
    int limit = 30,
    String? before,
  });

  Future<DmMessage> sendDmMessage(
    String conversationId,
    String content, {
    String messageType = 'TEXT',
  });

  Future<void> markDmConversationRead(String conversationId);

  Future<void> deleteDmMessage(String messageId);

  Future<List<CustomerServiceConversation>> getCustomerServiceConversations();

  Future<CustomerServiceConversation> createCustomerServiceConversation();

  Future<List<CustomerServiceMessage>> getCustomerServiceMessages(
    String conversationId, {
    int limit = 50,
    String? before,
  });

  Future<CustomerServiceMessage> sendCustomerServiceMessage(
    String conversationId,
    String content, {
    String messageType = 'TEXT',
  });

  Future<void> markCustomerServiceConversationRead(String conversationId);
}
