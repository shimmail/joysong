import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_preferences.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_repository.dart';
import 'package:joysong_flutter/features/messaging/domain/notification_target.dart';
import 'package:joysong_flutter/features/messaging/presentation/time_cursor_pager.dart';

class NotificationController extends ChangeNotifier {
  NotificationController(this._repository, {this.limit = 50});

  final MessagingRepository _repository;
  final int limit;
  List<AppNotification> _items = const [];
  List<AppNotification> get items => _items;
  int _unreadCount = 0;
  int get unreadCount => _unreadCount;
  int _systemUnreadCount = 0;
  int get systemUnreadCount => _systemUnreadCount;
  int _activityUnreadCount = 0;
  int get activityUnreadCount => _activityUnreadCount;
  final Set<String> _markReadInFlight = <String>{};
  int _stateVersion = 0;
  int _mutationsInFlight = 0;
  bool _isLoading = false;
  bool get isLoading => _isLoading;
  String? _errorMessage;
  String? get errorMessage => _errorMessage;

  Future<void> refresh() async {
    if (_isLoading) return;
    final requestVersion = _stateVersion;
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final results = await Future.wait<Object>([
        _repository.getNotifications(limit: limit),
        _repository.getUnreadNotificationCounts(),
      ]);
      if (requestVersion != _stateVersion || _mutationsInFlight > 0) return;
      _items = _deduplicate(results[0] as List<AppNotification>);
      final counts = results[1] as NotificationUnreadCounts;
      _unreadCount = counts.total;
      _systemUnreadCount = counts.system;
      _activityUnreadCount = counts.activity;
    } on Object catch (error) {
      _errorMessage = _messageFor(error);
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  Future<void> markRead(String notificationId) async {
    final matches = _items.where((item) => item.id == notificationId);
    if (matches.isEmpty ||
        matches.first.isRead ||
        !_markReadInFlight.add(notificationId)) {
      return;
    }
    _beginMutation();
    try {
      await _repository.markNotificationRead(notificationId);
      final current = _items.where((item) => item.id == notificationId);
      if (current.isEmpty || current.first.isRead) return;
      final notification = current.first;
      _items = [
        for (final item in _items)
          if (item.id == notificationId) item.copyWith(isRead: true) else item,
      ];
      if (_unreadCount > 0) _unreadCount--;
      if (isActivityNotificationType(notification.type)) {
        if (_activityUnreadCount > 0) _activityUnreadCount--;
      } else if (_systemUnreadCount > 0) {
        _systemUnreadCount--;
      }
      notifyListeners();
    } on Object catch (error) {
      _errorMessage = _messageFor(error);
      notifyListeners();
    } finally {
      _markReadInFlight.remove(notificationId);
      _endMutation();
    }
  }

  Future<void> markAllRead() async {
    _beginMutation();
    try {
      await _repository.markAllNotificationsRead();
      _items = [for (final item in _items) item.copyWith(isRead: true)];
      _unreadCount = 0;
      _systemUnreadCount = 0;
      _activityUnreadCount = 0;
      notifyListeners();
    } on Object catch (error) {
      _errorMessage = _messageFor(error);
      notifyListeners();
    } finally {
      _endMutation();
    }
  }

  void _beginMutation() {
    _mutationsInFlight++;
    _stateVersion++;
  }

  void _endMutation() {
    _mutationsInFlight--;
    _stateVersion++;
  }

  List<AppNotification> _deduplicate(Iterable<AppNotification> source) {
    final seen = <String>{};
    return [
      for (final item in source)
        if (seen.add(item.id)) item
    ];
  }
}

class MessagingHubController extends ChangeNotifier {
  MessagingHubController(
    this._repository, {
    this.currentUserId = '',
    this.peerLoader,
    this.preferencesStore,
  });

  final MessagingRepository _repository;
  final String currentUserId;
  final Future<MessagingPeer?> Function(String userId)? peerLoader;
  final MessagingPreferencesStore? preferencesStore;
  final Map<String, MessagingPeer> peers = {};
  MessagingPreferences _preferences = MessagingPreferences();
  List<DmConversation> _serverDmConversations = const [];
  List<CustomerServiceConversation> _serverCustomerServiceConversations =
      const [];
  bool _preferencesLoaded = false;

  MessagingPeer? peerFor(DmConversation conversation) =>
      peers[conversation.otherUserId(currentUserId)];
  List<DmConversation> dmConversations = const [];
  List<CustomerServiceConversation> customerServiceConversations = const [];
  bool isLoading = false;
  bool isOpeningCustomerService = false;
  String? errorMessage;
  String? customerServiceErrorMessage;
  bool _hasLoadedCustomerServiceConversations = false;

  Future<void> refresh() async {
    if (isLoading) return;
    isLoading = true;
    errorMessage = null;
    customerServiceErrorMessage = null;
    notifyListeners();
    try {
      await _ensurePreferencesLoaded();
      final result = await Future.wait<Object>([
        _repository.getDmConversations(),
        _repository.getCustomerServiceConversations(),
      ]);
      _serverDmConversations =
          _byId(result[0] as List<DmConversation>, (item) => item.id);
      _serverCustomerServiceConversations = _byId(
        result[1] as List<CustomerServiceConversation>,
        (item) => item.id,
      );
      final preferencesChanged = _rebuildConversations();
      await _loadPeers();
      if (preferencesChanged) await _savePreferences();
      _hasLoadedCustomerServiceConversations = true;
    } on Object catch (error) {
      errorMessage = _messageFor(error);
    } finally {
      isLoading = false;
      notifyListeners();
    }
  }

  Future<void> _loadPeers() async {
    final loader = peerLoader;
    if (loader == null) return;
    final ids = dmConversations
        .map((item) => item.otherUserId(currentUserId))
        .where((id) => id.isNotEmpty && !peers.containsKey(id))
        .toSet();
    await Future.wait([
      for (final id in ids)
        () async {
          try {
            final peer = await loader(id);
            if (peer != null) peers[id] = peer;
          } on Object {
            // A missing public profile must not block the conversation list.
          }
        }(),
    ]);
  }

  bool isConversationPinned(String conversationKey) =>
      _preferences.isPinned(conversationKey);

  bool isConversationLocallyUnread(String conversationKey) =>
      _preferences.isLocallyUnread(conversationKey);

  /// Direct-message helpers used by the conversation-list UI.
  bool isPinned(String conversationId) =>
      isConversationPinned(dmConversationPreferenceKey(conversationId));

  bool isLocallyUnread(String conversationId) => isConversationLocallyUnread(
        dmConversationPreferenceKey(conversationId),
      );

  bool isDmPinned(DmConversation conversation) =>
      isConversationPinned(dmConversationPreferenceKey(conversation.id));

  bool isDmLocallyUnread(DmConversation conversation) =>
      isConversationLocallyUnread(
        dmConversationPreferenceKey(conversation.id),
      );

  int unreadFor(DmConversation conversation) {
    final serverUnread = conversation.unreadFor(currentUserId);
    return isDmLocallyUnread(conversation) && serverUnread == 0
        ? 1
        : serverUnread;
  }

  bool isCustomerServicePinned(CustomerServiceConversation conversation) =>
      isConversationPinned(
        customerServiceConversationPreferenceKey(conversation.id),
      );

  int customerServiceUnreadFor(CustomerServiceConversation conversation) {
    final serverUnread = conversation.unreadCount;
    final locallyUnread = isConversationLocallyUnread(
      customerServiceConversationPreferenceKey(conversation.id),
    );
    return locallyUnread && serverUnread == 0 ? 1 : serverUnread;
  }

  Future<void> toggleConversationPin(String conversationKey) async {
    await _ensurePreferencesLoaded();
    final pinned = {..._preferences.pinnedConversationKeys};
    if (!pinned.remove(conversationKey)) pinned.add(conversationKey);
    _preferences = _preferences.copyWith(pinnedConversationKeys: pinned);
    _rebuildConversations();
    notifyListeners();
    await _savePreferences();
  }

  Future<void> toggleDmPin(DmConversation conversation) =>
      toggleConversationPin(dmConversationPreferenceKey(conversation.id));

  Future<void> togglePin(String conversationId) =>
      toggleConversationPin(dmConversationPreferenceKey(conversationId));

  Future<void> markConversationUnread(String conversationKey) async {
    await _ensurePreferencesLoaded();
    final unread = {..._preferences.localUnreadConversationKeys}
      ..add(conversationKey);
    _preferences = _preferences.copyWith(localUnreadConversationKeys: unread);
    notifyListeners();
    await _savePreferences();
  }

  Future<void> markDmUnread(DmConversation conversation) =>
      markConversationUnread(dmConversationPreferenceKey(conversation.id));

  Future<void> markUnread(String conversationId) =>
      markConversationUnread(dmConversationPreferenceKey(conversationId));

  Future<void> clearConversationUnread(String conversationKey) async {
    await _ensurePreferencesLoaded();
    final unread = {..._preferences.localUnreadConversationKeys};
    if (!unread.remove(conversationKey)) return;
    _preferences = _preferences.copyWith(localUnreadConversationKeys: unread);
    notifyListeners();
    await _savePreferences();
  }

  Future<void> clearDmUnread(DmConversation conversation) =>
      clearConversationUnread(dmConversationPreferenceKey(conversation.id));

  Future<void> clearUnread(String conversationId) =>
      clearConversationUnread(dmConversationPreferenceKey(conversationId));

  /// Hides a conversation on this device without deleting any server data.
  ///
  /// It becomes visible again only when the server reports a last-message
  /// timestamp later than the stored server activity cursor.
  Future<void> hideConversation(String conversationId) =>
      _hideConversationKey(dmConversationPreferenceKey(conversationId));

  Future<void> _hideConversationKey(
    String conversationKey, {
    String? activityCursor,
  }) async {
    await _ensurePreferencesLoaded();
    final pinned = {..._preferences.pinnedConversationKeys}
      ..remove(conversationKey);
    final unread = {..._preferences.localUnreadConversationKeys}
      ..remove(conversationKey);
    final cursor = activityCursor?.trim() ?? '';
    final hiddenAt = parseMessagingServerTime(cursor) == null
        ? DateTime.now().toUtc().toIso8601String()
        : cursor;
    final hidden = {..._preferences.hiddenAtByConversationKey}
      ..[conversationKey] = hiddenAt;
    _preferences = _preferences.copyWith(
      pinnedConversationKeys: pinned,
      localUnreadConversationKeys: unread,
      hiddenAtByConversationKey: hidden,
    );
    _rebuildConversations();
    notifyListeners();
    await _savePreferences();
  }

  Future<void> hideDmConversation(DmConversation conversation) {
    final lastMessageAt = conversation.lastMessageAt?.trim();
    return _hideConversationKey(
      dmConversationPreferenceKey(conversation.id),
      activityCursor: lastMessageAt?.isNotEmpty == true
          ? lastMessageAt
          : conversation.updatedAt,
    );
  }

  Future<void> _ensurePreferencesLoaded() async {
    if (_preferencesLoaded) return;
    final store = preferencesStore;
    if (store != null) {
      try {
        _preferences = await store.read(currentUserId);
      } on Object {
        // A secure-storage failure must not make messaging unavailable.
        _preferences = MessagingPreferences();
      }
    }
    _preferencesLoaded = true;
  }

  Future<void> _savePreferences() async {
    final store = preferencesStore;
    if (store == null) return;
    try {
      await store.write(currentUserId, _preferences);
    } on Object catch (error) {
      errorMessage = _messageFor(error);
      notifyListeners();
    }
  }

  bool _rebuildConversations() {
    final hidden = {..._preferences.hiddenAtByConversationKey};
    dmConversations = [
      for (final item in _serverDmConversations)
        if (_isVisibleAfterLocalHide(
          dmConversationPreferenceKey(item.id),
          item.lastMessageAt,
          hidden,
        ))
          item,
    ]..sort(_compareDmConversations);
    customerServiceConversations = [
      for (final item in _serverCustomerServiceConversations)
        if (_isVisibleAfterLocalHide(
          customerServiceConversationPreferenceKey(item.id),
          item.lastMessageAt,
          hidden,
        ))
          item,
    ]..sort(_compareCustomerServiceConversations);

    if (hidden.length == _preferences.hiddenAtByConversationKey.length) {
      return false;
    }
    _preferences = _preferences.copyWith(hiddenAtByConversationKey: hidden);
    return true;
  }

  bool _isVisibleAfterLocalHide(
    String conversationKey,
    String? lastMessageAt,
    Map<String, String> hidden,
  ) {
    final hiddenAt = parseMessagingServerTime(hidden[conversationKey]);
    if (hiddenAt == null) return true;
    final messageAt = parseMessagingServerTime(lastMessageAt);
    if (messageAt == null || !messageAt.isAfter(hiddenAt)) return false;
    hidden.remove(conversationKey);
    return true;
  }

  int _compareDmConversations(DmConversation left, DmConversation right) {
    final pinnedComparison = _comparePinned(
      dmConversationPreferenceKey(left.id),
      dmConversationPreferenceKey(right.id),
    );
    if (pinnedComparison != 0) return pinnedComparison;
    return _compareActivity(
      leftId: left.id,
      leftLastMessageAt: left.lastMessageAt,
      leftUpdatedAt: left.updatedAt,
      leftCreatedAt: left.createdAt,
      rightId: right.id,
      rightLastMessageAt: right.lastMessageAt,
      rightUpdatedAt: right.updatedAt,
      rightCreatedAt: right.createdAt,
    );
  }

  int _compareCustomerServiceConversations(
    CustomerServiceConversation left,
    CustomerServiceConversation right,
  ) {
    final pinnedComparison = _comparePinned(
      customerServiceConversationPreferenceKey(left.id),
      customerServiceConversationPreferenceKey(right.id),
    );
    if (pinnedComparison != 0) return pinnedComparison;
    return _compareActivity(
      leftId: left.id,
      leftLastMessageAt: left.lastMessageAt,
      leftUpdatedAt: left.updatedAt,
      leftCreatedAt: left.createdAt,
      rightId: right.id,
      rightLastMessageAt: right.lastMessageAt,
      rightUpdatedAt: right.updatedAt,
      rightCreatedAt: right.createdAt,
    );
  }

  int _comparePinned(String leftKey, String rightKey) {
    final leftPinned = _preferences.isPinned(leftKey);
    final rightPinned = _preferences.isPinned(rightKey);
    if (leftPinned == rightPinned) return 0;
    return leftPinned ? -1 : 1;
  }

  int _compareActivity({
    required String leftId,
    required String? leftLastMessageAt,
    required String leftUpdatedAt,
    required String leftCreatedAt,
    required String rightId,
    required String? rightLastMessageAt,
    required String rightUpdatedAt,
    required String rightCreatedAt,
  }) {
    final leftAt = _activityAt(
      leftLastMessageAt,
      leftUpdatedAt,
      leftCreatedAt,
    );
    final rightAt = _activityAt(
      rightLastMessageAt,
      rightUpdatedAt,
      rightCreatedAt,
    );
    final timeComparison = rightAt.compareTo(leftAt);
    return timeComparison != 0 ? timeComparison : leftId.compareTo(rightId);
  }

  DateTime _activityAt(
      String? lastMessageAt, String updatedAt, String createdAt) {
    return DateTime.tryParse(lastMessageAt ?? '') ??
        DateTime.tryParse(updatedAt) ??
        DateTime.tryParse(createdAt) ??
        DateTime.fromMillisecondsSinceEpoch(0, isUtc: true);
  }

  /// Reuses the first server conversation, or creates one when none exists.
  ///
  /// The extra lookup after a failed/unfinished hub refresh prevents creating
  /// a duplicate conversation merely because the current screen has no data.
  /// Concurrent taps are collapsed into a single server request.
  Future<CustomerServiceConversation?> openCustomerService() async {
    if (isLoading || isOpeningCustomerService) return null;
    if (customerServiceConversations case [final existing, ...]) {
      return existing;
    }
    if (_serverCustomerServiceConversations case [final hidden, ...]) {
      return hidden;
    }

    isOpeningCustomerService = true;
    customerServiceErrorMessage = null;
    notifyListeners();
    try {
      if (!_hasLoadedCustomerServiceConversations) {
        final existing = _byId(
          await _repository.getCustomerServiceConversations(),
          (item) => item.id,
        );
        _serverCustomerServiceConversations = existing;
        _rebuildConversations();
        _hasLoadedCustomerServiceConversations = true;
        if (customerServiceConversations case [final conversation, ...]) {
          return conversation;
        }
        if (existing case [final hidden, ...]) {
          return hidden;
        }
      }

      final created = await _repository.createCustomerServiceConversation();
      _serverCustomerServiceConversations = _byId(
        [created, ..._serverCustomerServiceConversations],
        (item) => item.id,
      );
      _rebuildConversations();
      return created;
    } on Object catch (error) {
      customerServiceErrorMessage = _messageFor(error);
      return null;
    } finally {
      isOpeningCustomerService = false;
      notifyListeners();
    }
  }
}

class DmThreadController extends ChangeNotifier {
  DmThreadController({
    required MessagingRepository repository,
    required this.conversationId,
    this.currentUserId = '',
    this.firstMessageLimitApplies = false,
    bool waitingForReply = false,
    int pageSize = 30,
  })  : _repository = repository,
        _waitingForReply = waitingForReply,
        pager = TimeCursorPager<DmMessage>(
          loader: ({required limit, before}) => repository.getDmMessages(
            conversationId,
            limit: limit,
            before: before,
          ),
          idOf: (message) => message.id,
          createdAtOf: (message) => message.createdAt,
          pageSize: pageSize,
        ) {
    pager.addListener(notifyListeners);
  }

  final MessagingRepository _repository;
  final String conversationId;
  final String currentUserId;
  final bool firstMessageLimitApplies;
  final TimeCursorPager<DmMessage> pager;
  bool _waitingForReply;
  bool _hasConversationHistory = false;
  bool get waitingForReply => firstMessageLimitApplies && _waitingForReply;
  bool get canSend => !isSending && !waitingForReply;
  bool isSending = false;
  String? sendError;

  Future<void> initialize() async {
    await pager.loadInitial();
    _hasConversationHistory = pager.items.isNotEmpty;
    _reconcileWaitingForReply();
    try {
      await _repository.markDmConversationRead(conversationId);
    } on Object {
      // Reading history remains useful even if the read receipt fails.
    }
  }

  Future<void> refresh() async {
    await pager.refreshNewest();
    _hasConversationHistory = _hasConversationHistory || pager.items.isNotEmpty;
    _reconcileWaitingForReply();
    try {
      await _repository.markDmConversationRead(conversationId);
    } on Object {
      // Keep the thread available when the read receipt fails.
    }
  }

  Future<void> send(String content) async {
    await _send(content, messageType: 'TEXT');
  }

  Future<void> sendImage(String imageUrl) async {
    await _send(imageUrl, messageType: 'IMAGE');
  }

  Future<void> _send(
    String content, {
    required String messageType,
  }) async {
    if (isSending || content.trim().isEmpty) return;
    if (waitingForReply) {
      sendError = '请等待对方回复后再发送消息';
      notifyListeners();
      return;
    }
    isSending = true;
    sendError = null;
    notifyListeners();
    try {
      // Message POST is issued once and never retried by this controller.
      final isFirstSuccessfulMessage = !_hasConversationHistory;
      final message = await _repository.sendDmMessage(
        conversationId,
        content,
        messageType: messageType,
      );
      pager.addNewest(message);
      _hasConversationHistory = true;
      if (firstMessageLimitApplies && isFirstSuccessfulMessage) {
        _waitingForReply = true;
      }
    } on Object catch (error) {
      sendError = _messageFor(error);
    } finally {
      isSending = false;
      notifyListeners();
    }
  }

  void _reconcileWaitingForReply() {
    if (!firstMessageLimitApplies || currentUserId.isEmpty) return;
    final hasReply = pager.items.any(
      (message) =>
          message.senderId.isNotEmpty && message.senderId != currentUserId,
    );
    if (hasReply) {
      _waitingForReply = false;
      if (sendError == '请等待对方回复后再发送消息') sendError = null;
      notifyListeners();
    }
  }

  Future<void> deleteMessage(String messageId) async {
    try {
      await _repository.deleteDmMessage(messageId);
      pager.removeById(messageId);
    } on Object catch (error) {
      sendError = _messageFor(error);
      notifyListeners();
    }
  }

  @override
  void dispose() {
    pager.removeListener(notifyListeners);
    pager.dispose();
    super.dispose();
  }
}

class CustomerServiceThreadController extends ChangeNotifier {
  CustomerServiceThreadController({
    required MessagingRepository repository,
    required this.conversationId,
    int pageSize = 50,
  })  : _repository = repository,
        pager = TimeCursorPager<CustomerServiceMessage>(
          loader: ({required limit, before}) =>
              repository.getCustomerServiceMessages(
            conversationId,
            limit: limit,
            before: before,
          ),
          idOf: (message) => message.id,
          createdAtOf: (message) => message.createdAt,
          pageSize: pageSize,
        ) {
    pager.addListener(notifyListeners);
  }

  final MessagingRepository _repository;
  final String conversationId;
  final TimeCursorPager<CustomerServiceMessage> pager;
  bool isSending = false;
  String? sendError;

  Future<void> initialize() async {
    await pager.loadInitial();
    try {
      await _repository.markCustomerServiceConversationRead(conversationId);
    } on Object {
      // The thread remains usable if the read receipt fails.
    }
  }

  Future<void> refresh() async {
    await pager.refreshNewest();
    try {
      await _repository.markCustomerServiceConversationRead(conversationId);
    } on Object {
      // Keep the thread available when the read receipt fails.
    }
  }

  Future<void> send(String content) async {
    await _send(content, messageType: 'TEXT');
  }

  Future<void> sendImage(String imageUrl) async {
    await _send(imageUrl, messageType: 'IMAGE');
  }

  Future<void> _send(
    String content, {
    required String messageType,
  }) async {
    if (isSending || content.trim().isEmpty) return;
    isSending = true;
    sendError = null;
    notifyListeners();
    try {
      final message = await _repository.sendCustomerServiceMessage(
        conversationId,
        content,
        messageType: messageType,
      );
      pager.addNewest(message);
    } on Object catch (error) {
      sendError = _messageFor(error);
    } finally {
      isSending = false;
      notifyListeners();
    }
  }

  @override
  void dispose() {
    pager.removeListener(notifyListeners);
    pager.dispose();
    super.dispose();
  }
}

List<T> _byId<T>(Iterable<T> source, String Function(T item) idOf) {
  final seen = <String>{};
  return [
    for (final item in source)
      if (seen.add(idOf(item))) item
  ];
}

String _messageFor(Object error) =>
    error is ApiException ? error.message : '操作失败，请稍后重试';
