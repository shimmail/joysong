import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_repository.dart';
import 'package:joysong_flutter/features/messaging/presentation/time_cursor_pager.dart';

class NotificationController extends ChangeNotifier {
  NotificationController(this._repository, {this.limit = 50});

  final MessagingRepository _repository;
  final int limit;
  List<AppNotification> _items = const [];
  List<AppNotification> get items => _items;
  int _unreadCount = 0;
  int get unreadCount => _unreadCount;
  bool _isLoading = false;
  bool get isLoading => _isLoading;
  String? _errorMessage;
  String? get errorMessage => _errorMessage;

  Future<void> refresh() async {
    if (_isLoading) return;
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final results = await Future.wait<Object>([
        _repository.getNotifications(limit: limit),
        _repository.getUnreadNotificationCount(),
      ]);
      _items = _deduplicate(results[0] as List<AppNotification>);
      _unreadCount = results[1] as int;
    } on Object catch (error) {
      _errorMessage = _messageFor(error);
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  Future<void> markRead(String notificationId) async {
    final notification = _items.where((item) => item.id == notificationId);
    if (notification.isEmpty || notification.first.isRead) return;
    try {
      await _repository.markNotificationRead(notificationId);
      _items = [
        for (final item in _items)
          if (item.id == notificationId) item.copyWith(isRead: true) else item,
      ];
      if (_unreadCount > 0) _unreadCount--;
      notifyListeners();
    } on Object catch (error) {
      _errorMessage = _messageFor(error);
      notifyListeners();
    }
  }

  Future<void> markAllRead() async {
    try {
      await _repository.markAllNotificationsRead();
      _items = [for (final item in _items) item.copyWith(isRead: true)];
      _unreadCount = 0;
      notifyListeners();
    } on Object catch (error) {
      _errorMessage = _messageFor(error);
      notifyListeners();
    }
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
  MessagingHubController(this._repository);

  final MessagingRepository _repository;
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
      final result = await Future.wait<Object>([
        _repository.getDmConversations(),
        _repository.getCustomerServiceConversations(),
      ]);
      dmConversations =
          _byId(result[0] as List<DmConversation>, (item) => item.id);
      customerServiceConversations = _byId(
        result[1] as List<CustomerServiceConversation>,
        (item) => item.id,
      );
      _hasLoadedCustomerServiceConversations = true;
    } on Object catch (error) {
      errorMessage = _messageFor(error);
    } finally {
      isLoading = false;
      notifyListeners();
    }
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

    isOpeningCustomerService = true;
    customerServiceErrorMessage = null;
    notifyListeners();
    try {
      if (!_hasLoadedCustomerServiceConversations) {
        final existing = _byId(
          await _repository.getCustomerServiceConversations(),
          (item) => item.id,
        );
        customerServiceConversations = existing;
        _hasLoadedCustomerServiceConversations = true;
        if (existing case [final conversation, ...]) {
          return conversation;
        }
      }

      final created = await _repository.createCustomerServiceConversation();
      customerServiceConversations = _byId(
        [created, ...customerServiceConversations],
        (item) => item.id,
      );
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
    int pageSize = 30,
  })  : _repository = repository,
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
  final TimeCursorPager<DmMessage> pager;
  bool isSending = false;
  String? sendError;

  Future<void> initialize() async {
    await pager.loadInitial();
    try {
      await _repository.markDmConversationRead(conversationId);
    } on Object {
      // Reading history remains useful even if the read receipt fails.
    }
  }

  Future<void> send(String content) async {
    if (isSending || content.trim().isEmpty) return;
    isSending = true;
    sendError = null;
    notifyListeners();
    try {
      // Message POST is issued once and never retried by this controller.
      final message = await _repository.sendDmMessage(conversationId, content);
      pager.addNewest(message);
    } on Object catch (error) {
      sendError = _messageFor(error);
    } finally {
      isSending = false;
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

  Future<void> send(String content) async {
    if (isSending || content.trim().isEmpty) return;
    isSending = true;
    sendError = null;
    notifyListeners();
    try {
      final message =
          await _repository.sendCustomerServiceMessage(conversationId, content);
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
