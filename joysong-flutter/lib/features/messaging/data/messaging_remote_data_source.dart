import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/public_media_url.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';

abstract interface class MessagingRemoteDataSource {
  Future<List<AppNotification>> getNotifications({required int limit});
  Future<int> getUnreadNotificationCount();
  Future<void> markNotificationRead(String notificationId);
  Future<void> markAllNotificationsRead();
  Future<List<DmConversation>> getDmConversations();
  Future<DmConversation> createDmConversation(String targetId);
  Future<DmConversation> createOrderServiceConversation(String orderId);
  Future<List<DmMessage>> getDmMessages(
    String conversationId, {
    required int limit,
    String? before,
  });
  Future<DmMessage> sendDmMessage(
    String conversationId,
    String content,
    String messageType,
  );
  Future<void> markDmConversationRead(String conversationId);
  Future<void> deleteDmMessage(String messageId);
  Future<List<CustomerServiceConversation>> getCustomerServiceConversations();
  Future<CustomerServiceConversation> createCustomerServiceConversation();
  Future<List<CustomerServiceMessage>> getCustomerServiceMessages(
    String conversationId, {
    required int limit,
    String? before,
  });
  Future<CustomerServiceMessage> sendCustomerServiceMessage(
    String conversationId,
    String content,
    String messageType,
  );
  Future<void> markCustomerServiceConversationRead(String conversationId);
}

final class ApiMessagingRemoteDataSource implements MessagingRemoteDataSource {
  ApiMessagingRemoteDataSource(this._apiClient)
      : _mediaResolver = ApiPublicMediaUrlResolver(_apiClient.apiRoot);

  final ApiClient _apiClient;
  final PublicMediaUrlResolver _mediaResolver;

  @override
  Future<List<AppNotification>> getNotifications({required int limit}) async =>
      await _apiClient.get<List<AppNotification>>(
        'notifications',
        query: {'limit': limit.clamp(1, 100)},
        decodeData: (json) => _list(json, AppNotification.fromJson),
      ) ??
      const [];

  @override
  Future<int> getUnreadNotificationCount() async =>
      await _apiClient.get<int>(
        'notifications/unread-count',
        decodeData: (json) => switch (json) {
          final int value => value,
          final num value => value.toInt(),
          _ => int.tryParse(json.toString()) ?? 0,
        },
      ) ??
      0;

  @override
  Future<void> markNotificationRead(String notificationId) async {
    await _apiClient.put<Object?>(
      'notifications/$notificationId/read',
      decodeData: (json) => json,
    );
  }

  @override
  Future<void> markAllNotificationsRead() async {
    await _apiClient.put<Object?>(
      'notifications/read-all',
      decodeData: (json) => json,
    );
  }

  @override
  Future<List<DmConversation>> getDmConversations() async =>
      await _apiClient.get<List<DmConversation>>(
        'dm/conversations',
        decodeData: (json) => _list(json, DmConversation.fromJson),
      ) ??
      const [];

  @override
  Future<DmConversation> createDmConversation(String targetId) async =>
      _required(
        await _apiClient.post<DmConversation>(
          'dm/conversations',
          body: {'targetId': targetId},
          decodeData: DmConversation.fromJson,
        ),
      );

  @override
  Future<DmConversation> createOrderServiceConversation(String orderId) async {
    final expectedOrderId = orderId.trim();
    if (expectedOrderId.isEmpty) {
      throw ArgumentError.value(orderId, 'orderId', '订单 ID 不能为空');
    }
    final conversation = _required(
      await _apiClient.post<DmConversation>(
        'orders/$expectedOrderId/service-conversation',
        decodeData: DmConversation.fromJson,
      ),
    );
    if (conversation.conversationType != DmConversationType.orderService ||
        conversation.orderId != expectedOrderId) {
      throw const FormatException('订单会话响应与请求不匹配');
    }
    return conversation;
  }

  @override
  Future<List<DmMessage>> getDmMessages(
    String conversationId, {
    required int limit,
    String? before,
  }) async =>
      await _apiClient.get<List<DmMessage>>(
        'dm/conversations/$conversationId/messages',
        query: {'limit': limit.clamp(1, 100), 'before': before},
        decodeData: (json) => _list(json, _dmMessage),
      ) ??
      const [];

  @override
  Future<DmMessage> sendDmMessage(
    String conversationId,
    String content,
    String messageType,
  ) async =>
      _required(
        await _apiClient.post<DmMessage>(
          'dm/conversations/$conversationId/messages',
          body: {'content': _content(content), 'messageType': messageType},
          decodeData: _dmMessage,
        ),
      );

  @override
  Future<void> markDmConversationRead(String conversationId) async {
    await _apiClient.put<Object?>(
      'dm/conversations/$conversationId/read',
      decodeData: (json) => json,
    );
  }

  @override
  Future<void> deleteDmMessage(String messageId) async {
    await _apiClient.delete<Object?>(
      'dm/messages/$messageId',
      decodeData: (json) => json,
    );
  }

  @override
  Future<List<CustomerServiceConversation>>
      getCustomerServiceConversations() async =>
          await _apiClient.get<List<CustomerServiceConversation>>(
            'cs/conversations',
            decodeData: (json) =>
                _list(json, CustomerServiceConversation.fromJson),
          ) ??
          const [];

  @override
  Future<CustomerServiceConversation>
      createCustomerServiceConversation() async => _required(
            await _apiClient.post<CustomerServiceConversation>(
              'cs/conversations',
              decodeData: CustomerServiceConversation.fromJson,
            ),
          );

  @override
  Future<List<CustomerServiceMessage>> getCustomerServiceMessages(
    String conversationId, {
    required int limit,
    String? before,
  }) async =>
      await _apiClient.get<List<CustomerServiceMessage>>(
        'cs/conversations/$conversationId/messages',
        query: {'limit': limit.clamp(1, 100), 'before': before},
        decodeData: (json) => _list(json, _customerServiceMessage),
      ) ??
      const [];

  @override
  Future<CustomerServiceMessage> sendCustomerServiceMessage(
    String conversationId,
    String content,
    String messageType,
  ) async =>
      _required(
        await _apiClient.post<CustomerServiceMessage>(
          'cs/conversations/$conversationId/messages',
          body: {'content': _content(content), 'messageType': messageType},
          decodeData: _customerServiceMessage,
        ),
      );

  @override
  Future<void> markCustomerServiceConversationRead(
    String conversationId,
  ) async {
    await _apiClient.put<Object?>(
      'cs/conversations/$conversationId/read',
      decodeData: (json) => json,
    );
  }

  DmMessage _dmMessage(Object? json) {
    final message = DmMessage.fromJson(json);
    if (message.messageType.toUpperCase() != 'IMAGE') return message;
    return DmMessage(
      id: message.id,
      conversationId: message.conversationId,
      senderId: message.senderId,
      content: _mediaResolver.resolve(message.content),
      messageType: message.messageType,
      isRead: message.isRead,
      createdAt: message.createdAt,
    );
  }

  CustomerServiceMessage _customerServiceMessage(Object? json) {
    final message = CustomerServiceMessage.fromJson(json);
    if (message.messageType.toUpperCase() != 'IMAGE') return message;
    return CustomerServiceMessage(
      id: message.id,
      senderId: message.senderId,
      senderName: message.senderName,
      content: _mediaResolver.resolve(message.content),
      messageType: message.messageType,
      isRead: message.isRead,
      createdAt: message.createdAt,
    );
  }
}

List<T> _list<T>(Object? json, T Function(Object? json) decode) =>
    json is List ? json.map(decode).toList(growable: false) : const [];

T _required<T>(T? value) {
  if (value == null) throw const FormatException('响应缺少 data');
  return value;
}

String _content(String value) {
  final content = value.trim();
  if (content.isEmpty || content.length > 5000) {
    throw ArgumentError.value(value, 'content', '消息长度必须为 1–5000 字符');
  }
  return content;
}
