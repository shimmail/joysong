import 'package:joysong_flutter/features/messaging/data/messaging_remote_data_source.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_repository.dart';

final class MessagingRepositoryImpl implements MessagingRepository {
  const MessagingRepositoryImpl(this._remote);

  final MessagingRemoteDataSource _remote;

  @override
  Future<List<AppNotification>> getNotifications({int limit = 50}) =>
      _remote.getNotifications(limit: limit);
  @override
  Future<int> getUnreadNotificationCount() =>
      _remote.getUnreadNotificationCount();
  @override
  Future<void> markNotificationRead(String notificationId) =>
      _remote.markNotificationRead(notificationId);
  @override
  Future<void> markAllNotificationsRead() => _remote.markAllNotificationsRead();
  @override
  Future<List<DmConversation>> getDmConversations() =>
      _remote.getDmConversations();
  @override
  Future<DmConversation> createDmConversation(String targetId) =>
      _remote.createDmConversation(targetId);
  @override
  Future<List<DmMessage>> getDmMessages(
    String conversationId, {
    int limit = 30,
    String? before,
  }) =>
      _remote.getDmMessages(conversationId, limit: limit, before: before);
  @override
  Future<DmMessage> sendDmMessage(
    String conversationId,
    String content, {
    String messageType = 'TEXT',
  }) =>
      _remote.sendDmMessage(conversationId, content, messageType);
  @override
  Future<void> markDmConversationRead(String conversationId) =>
      _remote.markDmConversationRead(conversationId);
  @override
  Future<void> deleteDmMessage(String messageId) =>
      _remote.deleteDmMessage(messageId);
  @override
  Future<List<CustomerServiceConversation>> getCustomerServiceConversations() =>
      _remote.getCustomerServiceConversations();
  @override
  Future<CustomerServiceConversation> createCustomerServiceConversation() =>
      _remote.createCustomerServiceConversation();
  @override
  Future<List<CustomerServiceMessage>> getCustomerServiceMessages(
    String conversationId, {
    int limit = 50,
    String? before,
  }) =>
      _remote.getCustomerServiceMessages(
        conversationId,
        limit: limit,
        before: before,
      );
  @override
  Future<CustomerServiceMessage> sendCustomerServiceMessage(
    String conversationId,
    String content, {
    String messageType = 'TEXT',
  }) =>
      _remote.sendCustomerServiceMessage(conversationId, content, messageType);
  @override
  Future<void> markCustomerServiceConversationRead(String conversationId) =>
      _remote.markCustomerServiceConversationRead(conversationId);
}
