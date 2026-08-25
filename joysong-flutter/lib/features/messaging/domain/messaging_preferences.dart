import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';

/// A stable local key for a direct-message conversation.
String dmConversationPreferenceKey(String conversationId) =>
    'dm:$conversationId';

/// A stable local key for a customer-service conversation.
String customerServiceConversationPreferenceKey(String conversationId) =>
    'customer-service:$conversationId';

/// Per-account, device-local presentation state for the messaging hub.
///
/// Server unread counts remain authoritative. [localUnreadConversationKeys]
/// only adds a local unread marker requested by the user, while
/// [hiddenAtByConversationKey] implements local-only conversation deletion.
class MessagingPreferences {
  MessagingPreferences({
    Set<String> pinnedConversationKeys = const <String>{},
    Set<String> localUnreadConversationKeys = const <String>{},
    Map<String, String> hiddenAtByConversationKey = const <String, String>{},
  })  : pinnedConversationKeys = Set.unmodifiable(pinnedConversationKeys),
        localUnreadConversationKeys =
            Set.unmodifiable(localUnreadConversationKeys),
        hiddenAtByConversationKey = Map.unmodifiable(hiddenAtByConversationKey);

  final Set<String> pinnedConversationKeys;
  final Set<String> localUnreadConversationKeys;
  final Map<String, String> hiddenAtByConversationKey;

  bool isPinned(String conversationKey) =>
      pinnedConversationKeys.contains(conversationKey);

  bool isLocallyUnread(String conversationKey) =>
      localUnreadConversationKeys.contains(conversationKey);

  DateTime? hiddenAt(String conversationKey) =>
      parseMessagingServerTime(hiddenAtByConversationKey[conversationKey]);

  MessagingPreferences copyWith({
    Set<String>? pinnedConversationKeys,
    Set<String>? localUnreadConversationKeys,
    Map<String, String>? hiddenAtByConversationKey,
  }) {
    return MessagingPreferences(
      pinnedConversationKeys:
          pinnedConversationKeys ?? this.pinnedConversationKeys,
      localUnreadConversationKeys:
          localUnreadConversationKeys ?? this.localUnreadConversationKeys,
      hiddenAtByConversationKey:
          hiddenAtByConversationKey ?? this.hiddenAtByConversationKey,
    );
  }

  Map<String, Object?> toJson() => <String, Object?>{
        'pinnedConversationKeys': pinnedConversationKeys.toList(),
        'localUnreadConversationKeys': localUnreadConversationKeys.toList(),
        'hiddenAtByConversationKey': hiddenAtByConversationKey,
      };

  factory MessagingPreferences.fromJson(Object? json) {
    if (json is! Map) {
      throw const FormatException('消息偏好设置不是 JSON 对象');
    }
    final map = json.cast<Object?, Object?>();
    return MessagingPreferences(
      pinnedConversationKeys: _stringSet(map['pinnedConversationKeys']),
      localUnreadConversationKeys:
          _stringSet(map['localUnreadConversationKeys']),
      hiddenAtByConversationKey: _dateTimeMap(map['hiddenAtByConversationKey']),
    );
  }
}

abstract interface class MessagingPreferencesStore {
  Future<MessagingPreferences> read(String currentUserId);

  Future<void> write(
    String currentUserId,
    MessagingPreferences preferences,
  );

  Future<void> clear(String currentUserId);
}

Set<String> _stringSet(Object? value) {
  if (value == null) return const <String>{};
  if (value is! List) throw const FormatException('消息偏好列表格式错误');
  return value
      .whereType<String>()
      .map((item) => item.trim())
      .where((item) => item.isNotEmpty)
      .toSet();
}

Map<String, String> _dateTimeMap(Object? value) {
  if (value == null) return const <String, String>{};
  if (value is! Map) throw const FormatException('消息隐藏时间格式错误');
  final result = <String, String>{};
  for (final entry in value.entries) {
    final key = entry.key;
    final dateText = entry.value;
    if (key is! String || dateText is! String || key.trim().isEmpty) continue;
    final normalizedText = dateText.trim();
    if (parseMessagingServerTime(normalizedText) != null) {
      result[key.trim()] = normalizedText;
    }
  }
  return result;
}
