class AppNotification {
  const AppNotification({
    required this.id,
    required this.userId,
    required this.type,
    required this.title,
    required this.content,
    required this.targetType,
    required this.targetId,
    required this.isRead,
    required this.createdAt,
  });

  final String id;
  final String userId;
  final String type;
  final String title;
  final String content;
  final String targetType;
  final String targetId;
  final bool isRead;
  final String createdAt;

  AppNotification copyWith({bool? isRead}) => AppNotification(
        id: id,
        userId: userId,
        type: type,
        title: title,
        content: content,
        targetType: targetType,
        targetId: targetId,
        isRead: isRead ?? this.isRead,
        createdAt: createdAt,
      );

  factory AppNotification.fromJson(Object? json) {
    final map = _map(json, '通知');
    return AppNotification(
      id: _required(map, 'id'),
      userId: _text(map['userId']),
      type: _text(map['type']),
      title: _text(map['title']),
      content: _text(map['content']),
      targetType: _text(map['targetType']),
      targetId: _text(map['targetId']),
      isRead: map['isRead'] == true,
      createdAt: _text(map['createdAt']),
    );
  }
}

enum DmConversationType { direct, orderService }

class DmConversation {
  const DmConversation({
    required this.id,
    required this.userAId,
    required this.userBId,
    required this.lastMessage,
    required this.lastMessageAt,
    required this.userAUnread,
    required this.userBUnread,
    required this.createdAt,
    required this.updatedAt,
    this.conversationType = DmConversationType.direct,
    this.orderId,
    this.firstMessageLimitApplies = false,
    this.waitingForReply = false,
  });

  final String id;
  final String userAId;
  final String userBId;
  final String? lastMessage;
  final String? lastMessageAt;
  final int userAUnread;
  final int userBUnread;
  final String createdAt;
  final String updatedAt;
  final DmConversationType conversationType;
  final String? orderId;
  final bool firstMessageLimitApplies;
  final bool waitingForReply;

  String otherUserId(String currentUserId) =>
      userAId == currentUserId ? userBId : userAId;

  int unreadFor(String currentUserId) =>
      userAId == currentUserId ? userAUnread : userBUnread;

  factory DmConversation.fromJson(Object? json) {
    final map = _map(json, '私信会话');
    final conversationType = _dmConversationType(map['conversationType']);
    final orderId = _nullableTrimmedText(map['orderId']);
    if (conversationType == DmConversationType.orderService &&
        orderId == null) {
      throw const FormatException('ORDER_SERVICE 会话缺少 orderId');
    }
    return DmConversation(
      id: _required(map, 'id'),
      userAId: _text(map['userAId']),
      userBId: _text(map['userBId']),
      lastMessage: _nullableText(map['lastMessage']),
      lastMessageAt: _nullableText(map['lastMessageAt']),
      userAUnread: _integer(map['userAUnread']),
      userBUnread: _integer(map['userBUnread']),
      createdAt: _text(map['createdAt']),
      updatedAt: _text(map['updatedAt']),
      conversationType: conversationType,
      orderId: orderId,
      firstMessageLimitApplies: _boolean(map['firstMessageLimitApplies']),
      waitingForReply: _boolean(map['waitingForReply']),
    );
  }
}

class MessagingPeer {
  const MessagingPeer({
    required this.id,
    required this.name,
    this.avatar = '',
  });

  final String id;
  final String name;
  final String avatar;
}

class DmMessage {
  const DmMessage({
    required this.id,
    required this.conversationId,
    required this.senderId,
    required this.content,
    required this.messageType,
    required this.isRead,
    required this.createdAt,
  });

  final String id;
  final String conversationId;
  final String senderId;
  final String content;
  final String messageType;
  final bool isRead;
  final String createdAt;

  factory DmMessage.fromJson(Object? json) {
    final map = _map(json, '私信');
    return DmMessage(
      id: _required(map, 'id'),
      conversationId: _text(map['conversationId']),
      senderId: _text(map['senderId']),
      content: _text(map['content']),
      messageType: _text(map['messageType'], fallback: 'TEXT'),
      isRead: map['isRead'] == true,
      createdAt: _text(map['createdAt']),
    );
  }
}

class CustomerServiceConversation {
  const CustomerServiceConversation({
    required this.id,
    required this.userAId,
    required this.userBId,
    required this.lastMessage,
    required this.lastMessageAt,
    required this.unreadCount,
    required this.createdAt,
    required this.updatedAt,
  });

  final String id;
  final String userAId;
  final String userBId;
  final String? lastMessage;
  final String? lastMessageAt;
  final int unreadCount;
  final String createdAt;
  final String updatedAt;

  factory CustomerServiceConversation.fromJson(Object? json) {
    final map = _map(json, '客服会话');
    return CustomerServiceConversation(
      id: _required(map, 'id'),
      userAId: _text(map['userAId']),
      userBId: _text(map['userBId']),
      lastMessage: _nullableText(map['lastMessage']),
      lastMessageAt: _nullableText(map['lastMessageAt']),
      unreadCount: _integer(map['unreadCount']),
      createdAt: _text(map['createdAt']),
      updatedAt: _text(map['updatedAt']),
    );
  }
}

class CustomerServiceMessage {
  const CustomerServiceMessage({
    required this.id,
    required this.senderId,
    required this.senderName,
    required this.content,
    required this.messageType,
    required this.isRead,
    required this.createdAt,
  });

  final String id;
  final String senderId;
  final String senderName;
  final String content;
  final String messageType;
  final bool isRead;
  final String createdAt;

  factory CustomerServiceMessage.fromJson(Object? json) {
    final map = _map(json, '客服消息');
    return CustomerServiceMessage(
      id: _required(map, 'id'),
      senderId: _text(map['senderId']),
      senderName: _text(map['senderName']),
      content: _text(map['content']),
      messageType: _text(map['messageType'], fallback: 'TEXT'),
      isRead: map['isRead'] == true,
      createdAt: _text(map['createdAt']),
    );
  }
}

Map<String, dynamic> _map(Object? json, String label) {
  if (json is! Map) throw FormatException('$label不是 JSON 对象');
  return json.cast<String, dynamic>();
}

String _required(Map<String, dynamic> map, String key) {
  final value = _text(map[key]);
  if (value.isEmpty) throw FormatException('响应缺少 $key');
  return value;
}

String _text(Object? value, {String fallback = ''}) {
  final text = value?.toString() ?? '';
  return text.isEmpty ? fallback : text;
}

String? _nullableText(Object? value) {
  final text = value?.toString();
  return text == null || text.isEmpty ? null : text;
}

String? _nullableTrimmedText(Object? value) {
  final text = value?.toString().trim();
  return text == null || text.isEmpty ? null : text;
}

DmConversationType _dmConversationType(Object? value) =>
    switch (value?.toString().trim().toUpperCase() ?? '') {
      '' || 'DIRECT' => DmConversationType.direct,
      'ORDER_SERVICE' => DmConversationType.orderService,
      final value => throw FormatException('不支持的会话类型: $value'),
    };

int _integer(Object? value) => switch (value) {
      final int number => number,
      final num number => number.toInt(),
      final String text => int.tryParse(text) ?? 0,
      _ => 0,
    };

bool _boolean(Object? value) => switch (value) {
      final bool boolean => boolean,
      final num number => number != 0,
      final String text => text.toLowerCase() == 'true' || text.trim() == '1',
      _ => false,
    };
