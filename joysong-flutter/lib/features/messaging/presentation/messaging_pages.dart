import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_controllers.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_strings.dart';

class NotificationPage extends StatefulWidget {
  const NotificationPage({
    required this.controller,
    this.onOpenNotification,
    super.key,
  });
  final NotificationController controller;
  final ValueChanged<AppNotification>? onOpenNotification;

  @override
  State<NotificationPage> createState() => _NotificationPageState();
}

class _NotificationPageState extends State<NotificationPage> {
  @override
  void initState() {
    super.initState();
    unawaited(widget.controller.refresh());
  }

  @override
  Widget build(BuildContext context) {
    final strings = _strings(context);
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) => Scaffold(
        appBar: AppBar(
          title: Text(strings.notifications(widget.controller.unreadCount)),
          actions: [
            TextButton(
              onPressed: widget.controller.unreadCount == 0
                  ? null
                  : widget.controller.markAllRead,
              child: Text(strings.markAllRead),
            ),
          ],
        ),
        body: RefreshIndicator(
          onRefresh: widget.controller.refresh,
          child: _NotificationList(
            controller: widget.controller,
            onOpenNotification: widget.onOpenNotification,
          ),
        ),
      ),
    );
  }
}

class _NotificationList extends StatelessWidget {
  const _NotificationList({
    required this.controller,
    this.onOpenNotification,
  });
  final NotificationController controller;
  final ValueChanged<AppNotification>? onOpenNotification;

  @override
  Widget build(BuildContext context) {
    final strings = _strings(context);
    if (controller.isLoading && controller.items.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (controller.errorMessage != null && controller.items.isEmpty) {
      return ListView(
        children: [
          const SizedBox(height: 160),
          Center(child: Text(strings.localizedError(controller.errorMessage!))),
          Center(
            child: TextButton(
              onPressed: controller.refresh,
              child: Text(strings.retry),
            ),
          ),
        ],
      );
    }
    if (controller.items.isEmpty) {
      return ListView(
        children: [
          const SizedBox(height: 160),
          Center(child: Text(strings.noNotifications)),
        ],
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.all(16),
      itemCount: controller.items.length,
      separatorBuilder: (_, __) => const SizedBox(height: 8),
      itemBuilder: (context, index) {
        final item = controller.items[index];
        return Card(
          elevation: 0,
          color: item.isRead
              ? null
              : Theme.of(context).colorScheme.primaryContainer.withAlpha(80),
          child: ListTile(
            leading: CircleAvatar(child: Icon(_notificationIcon(item.type))),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
            ),
            title: Text(item.title),
            subtitle: Text(item.content, maxLines: 3),
            trailing: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text(
                  _compactTime(item.createdAt),
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                if (!item.isRead)
                  Icon(Icons.circle, size: 8, semanticLabel: strings.unread),
              ],
            ),
            onTap: () async {
              await controller.markRead(item.id);
              onOpenNotification?.call(item);
            },
          ),
        );
      },
    );
  }
}

class CustomerServicePage extends StatelessWidget {
  const CustomerServicePage({
    required this.onOnlineChat,
    this.onPhone,
    this.onHelp,
    super.key,
  });

  final VoidCallback onOnlineChat;
  final VoidCallback? onPhone;
  final VoidCallback? onHelp;

  @override
  Widget build(BuildContext context) {
    final strings = _strings(context);
    return Scaffold(
      appBar: AppBar(title: Text(strings.customerService)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(children: [
                Icon(Icons.support_agent,
                    size: 64, color: Theme.of(context).colorScheme.primary),
                const SizedBox(height: 14),
                Text(strings.serviceWelcome,
                    style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 6),
                Text(strings.serviceHours,
                    style: Theme.of(context).textTheme.bodySmall),
              ]),
            ),
          ),
          const SizedBox(height: 12),
          _ServiceAction(
            icon: Icons.chat_bubble_outline,
            title: strings.onlineService,
            description: strings.onlineServiceDescription,
            onTap: onOnlineChat,
          ),
          _ServiceAction(
            icon: Icons.phone_outlined,
            title: strings.phoneService,
            description: strings.phoneServiceDescription,
            onTap: onPhone,
          ),
          _ServiceAction(
            icon: Icons.help_center_outlined,
            title: strings.helpCenter,
            description: strings.helpCenterDescription,
            onTap: onHelp,
          ),
        ],
      ),
    );
  }
}

class _ServiceAction extends StatelessWidget {
  const _ServiceAction({
    required this.icon,
    required this.title,
    required this.description,
    required this.onTap,
  });
  final IconData icon;
  final String title;
  final String description;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => Card(
        child: ListTile(
          enabled: onTap != null,
          onTap: onTap,
          leading: CircleAvatar(child: Icon(icon)),
          title: Text(title),
          subtitle: Text(description),
          trailing: const Icon(Icons.chevron_right),
        ),
      );
}

class MessagingCenterPage extends StatefulWidget {
  const MessagingCenterPage({
    required this.controller,
    this.currentUserId = '',
    this.onOpenDm,
    this.onOpenCustomerService,
    this.onOpenUser,
    super.key,
  });

  final MessagingHubController controller;
  final String currentUserId;
  final ValueChanged<DmConversation>? onOpenDm;
  final ValueChanged<CustomerServiceConversation>? onOpenCustomerService;
  final ValueChanged<String>? onOpenUser;

  @override
  State<MessagingCenterPage> createState() => _MessagingCenterPageState();
}

class _MessagingCenterPageState extends State<MessagingCenterPage> {
  @override
  void initState() {
    super.initState();
    unawaited(widget.controller.refresh());
  }

  Future<void> _openCustomerService() async {
    final conversation = await widget.controller.openCustomerService();
    if (!mounted || conversation == null) return;
    widget.onOpenCustomerService?.call(conversation);
  }

  @override
  Widget build(BuildContext context) {
    final strings = _strings(context);
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) => Scaffold(
        appBar: AppBar(title: Text(strings.messages)),
        body: RefreshIndicator(
          onRefresh: widget.controller.refresh,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              if (widget.controller.errorMessage case final error?)
                ListTile(
                  leading: const Icon(Icons.error_outline),
                  title: Text(strings.localizedError(error)),
                  trailing: TextButton(
                    onPressed: widget.controller.refresh,
                    child: Text(strings.retry),
                  ),
                ),
              Text(
                strings.customerService,
                style: Theme.of(context).textTheme.titleMedium,
              ),
              if (widget.controller.isLoading &&
                  widget.controller.customerServiceConversations.isEmpty)
                ListTile(
                  key: const Key('customer-service-loading'),
                  leading: const CircleAvatar(
                    child: Icon(Icons.support_agent),
                  ),
                  title: Text(strings.customerService),
                  subtitle: Text(strings.creatingConversation),
                  trailing: const SizedBox.square(
                    dimension: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                ),
              for (final item in widget.controller.customerServiceConversations)
                ListTile(
                  key: ValueKey('customer-service-${item.id}'),
                  leading: const CircleAvatar(child: Icon(Icons.support_agent)),
                  title: Text(strings.customerService),
                  subtitle: Text(item.lastMessage ?? strings.startConsultation),
                  trailing: _UnreadBadge(count: item.unreadCount),
                  onTap: () => widget.onOpenCustomerService?.call(item),
                ),
              if (!widget.controller.isLoading &&
                  widget.controller.customerServiceConversations.isEmpty)
                ListTile(
                  key: const Key('customer-service-start'),
                  leading: const CircleAvatar(
                    child: Icon(Icons.support_agent),
                  ),
                  title: Text(strings.customerService),
                  subtitle: Text(
                    widget.controller.customerServiceErrorMessage == null
                        ? strings.customerServiceReady
                        : strings.localizedError(
                            widget.controller.customerServiceErrorMessage!,
                          ),
                  ),
                  trailing: widget.controller.isOpeningCustomerService
                      ? const SizedBox.square(
                          dimension: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.chevron_right),
                  onTap: widget.controller.isOpeningCustomerService
                      ? null
                      : _openCustomerService,
                ),
              const SizedBox(height: 16),
              Text(
                strings.directMessages,
                style: Theme.of(context).textTheme.titleMedium,
              ),
              for (final item in widget.controller.dmConversations)
                ListTile(
                  leading: GestureDetector(
                    onTap: widget.onOpenUser == null
                        ? null
                        : () => widget.onOpenUser!(
                              item.otherUserId(widget.currentUserId),
                            ),
                    child: const CircleAvatar(
                      child: Icon(Icons.person_outline),
                    ),
                  ),
                  title: Text(_userLabel(
                    item.otherUserId(widget.currentUserId),
                    strings,
                  )),
                  subtitle: Text(item.lastMessage ?? strings.noMessages),
                  trailing: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Text(_compactTime(item.lastMessageAt),
                          style: Theme.of(context).textTheme.bodySmall),
                      _UnreadBadge(
                        count: item.unreadFor(widget.currentUserId),
                      ),
                    ],
                  ),
                  onTap: () => widget.onOpenDm?.call(item),
                ),
              if (!widget.controller.isLoading &&
                  widget.controller.dmConversations.isEmpty &&
                  widget.controller.customerServiceConversations.isEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 100),
                  child: Center(child: Text(strings.noMessages)),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class DmThreadPage extends StatelessWidget {
  const DmThreadPage({
    required this.controller,
    required this.currentUserId,
    this.title,
    super.key,
  });

  final DmThreadController controller;
  final String currentUserId;
  final String? title;

  @override
  Widget build(BuildContext context) => _ThreadScaffold<DmMessage>(
        title: title ?? _strings(context).directMessageTitle,
        controller: controller,
        items: () => controller.pager.items,
        isLoading: () => controller.pager.isLoading,
        hasMore: () => controller.pager.hasMore,
        error: () => controller.pager.errorMessage ?? controller.sendError,
        loadInitial: controller.initialize,
        loadOlder: controller.pager.loadOlder,
        send: controller.send,
        contentOf: (item) => item.content,
        createdAtOf: (item) => item.createdAt,
        typeOf: (item) => item.messageType,
        idOf: (item) => item.id,
        delete: controller.deleteMessage,
        isMine: (item) => item.senderId == currentUserId,
      );
}

class CustomerServiceThreadPage extends StatelessWidget {
  const CustomerServiceThreadPage({
    required this.controller,
    required this.currentUserId,
    super.key,
  });

  final CustomerServiceThreadController controller;
  final String currentUserId;

  @override
  Widget build(BuildContext context) => _ThreadScaffold<CustomerServiceMessage>(
        title: _strings(context).customerService,
        controller: controller,
        items: () => controller.pager.items,
        isLoading: () => controller.pager.isLoading,
        hasMore: () => controller.pager.hasMore,
        error: () => controller.pager.errorMessage ?? controller.sendError,
        loadInitial: controller.initialize,
        loadOlder: controller.pager.loadOlder,
        send: controller.send,
        contentOf: (item) => item.content,
        createdAtOf: (item) => item.createdAt,
        typeOf: (item) => item.messageType,
        idOf: (item) => item.id,
        isMine: (item) => item.senderId == currentUserId,
      );
}

class _ThreadScaffold<T> extends StatefulWidget {
  const _ThreadScaffold({
    required this.title,
    required this.controller,
    required this.items,
    required this.isLoading,
    required this.hasMore,
    required this.error,
    required this.loadInitial,
    required this.loadOlder,
    required this.send,
    required this.contentOf,
    required this.createdAtOf,
    required this.typeOf,
    required this.idOf,
    required this.isMine,
    this.delete,
  });

  final String title;
  final ChangeNotifier controller;
  final List<T> Function() items;
  final bool Function() isLoading;
  final bool Function() hasMore;
  final String? Function() error;
  final Future<void> Function() loadInitial;
  final Future<void> Function() loadOlder;
  final Future<void> Function(String content) send;
  final String Function(T item) contentOf;
  final String Function(T item) createdAtOf;
  final String Function(T item) typeOf;
  final String Function(T item) idOf;
  final bool Function(T item) isMine;
  final Future<void> Function(String messageId)? delete;

  @override
  State<_ThreadScaffold<T>> createState() => _ThreadScaffoldState<T>();
}

class _ThreadScaffoldState<T> extends State<_ThreadScaffold<T>> {
  final _input = TextEditingController();

  @override
  void initState() {
    super.initState();
    unawaited(widget.loadInitial());
  }

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = _strings(context);
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) => Scaffold(
        appBar: AppBar(title: Text(widget.title)),
        body: Column(
          children: [
            if (widget.error() case final error?)
              Padding(
                padding: const EdgeInsets.all(8),
                child: Text(error),
              ),
            Expanded(
              child: ListView.builder(
                padding: const EdgeInsets.all(16),
                itemCount: widget.items().length + (widget.hasMore() ? 1 : 0),
                itemBuilder: (context, index) {
                  if (widget.hasMore() && index == 0) {
                    return TextButton(
                      onPressed: widget.isLoading() ? null : widget.loadOlder,
                      child: Text(strings.loadEarlier),
                    );
                  }
                  final offset = widget.hasMore() ? 1 : 0;
                  final item = widget.items()[index - offset];
                  final mine = widget.isMine(item);
                  final messageType = widget.typeOf(item).toUpperCase();
                  return Align(
                    alignment:
                        mine ? Alignment.centerRight : Alignment.centerLeft,
                    child: GestureDetector(
                      onLongPress: () => _showMessageActions(item, mine),
                      child: Container(
                        margin: const EdgeInsets.symmetric(vertical: 4),
                        padding: const EdgeInsets.symmetric(
                          horizontal: 14,
                          vertical: 10,
                        ),
                        decoration: BoxDecoration(
                          color: mine
                              ? Theme.of(context).colorScheme.primaryContainer
                              : Theme.of(context)
                                  .colorScheme
                                  .surfaceContainerLow,
                          borderRadius: BorderRadius.circular(16),
                        ),
                        child: Column(
                          crossAxisAlignment: mine
                              ? CrossAxisAlignment.end
                              : CrossAxisAlignment.start,
                          children: [
                            if (messageType == 'IMAGE')
                              ClipRRect(
                                borderRadius: BorderRadius.circular(12),
                                child: Image.network(
                                  widget.contentOf(item),
                                  width: 220,
                                  height: 180,
                                  fit: BoxFit.cover,
                                  errorBuilder: (_, __, ___) => const SizedBox(
                                    width: 180,
                                    height: 120,
                                    child: Icon(Icons.broken_image_outlined),
                                  ),
                                ),
                              )
                            else
                              Text(widget.contentOf(item)),
                            const SizedBox(height: 4),
                            Text(
                              _compactTime(widget.createdAtOf(item)),
                              style: Theme.of(context).textTheme.labelSmall,
                            ),
                          ],
                        ),
                      ),
                    ),
                  );
                },
              ),
            ),
            SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                child: Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _input,
                        maxLength: 5000,
                        decoration: InputDecoration(
                          hintText: strings.messageHint,
                          counterText: '',
                        ),
                      ),
                    ),
                    IconButton.filled(
                      tooltip: strings.send,
                      onPressed: widget.isLoading()
                          ? null
                          : () {
                              final text = _input.text;
                              if (text.trim().isEmpty) return;
                              _input.clear();
                              unawaited(widget.send(text));
                            },
                      icon: const Icon(Icons.send),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _showMessageActions(T item, bool mine) async {
    final strings = _strings(context);
    final action = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          if (widget.typeOf(item).toUpperCase() == 'TEXT')
            ListTile(
              leading: const Icon(Icons.copy_outlined),
              title: Text(strings.copy),
              onTap: () => Navigator.pop(context, 'copy'),
            ),
          if (mine && widget.delete != null)
            ListTile(
              leading: const Icon(Icons.delete_outline),
              title: Text(strings.delete),
              onTap: () => Navigator.pop(context, 'delete'),
            ),
        ]),
      ),
    );
    if (!mounted) return;
    if (action == 'copy') {
      await Clipboard.setData(ClipboardData(text: widget.contentOf(item)));
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(strings.copied)));
      }
    } else if (action == 'delete') {
      await widget.delete?.call(widget.idOf(item));
    }
  }
}

class _UnreadBadge extends StatelessWidget {
  const _UnreadBadge({required this.count});
  final int count;
  @override
  Widget build(BuildContext context) =>
      count <= 0 ? const SizedBox.shrink() : Badge(label: Text('$count'));
}

MessagingStrings _strings(BuildContext context) => MessagingStrings(
      isEnglish: Localizations.localeOf(context).languageCode == 'en',
    );

IconData _notificationIcon(String type) => switch (type.toLowerCase()) {
      'like' => Icons.favorite_outline,
      'comment' || 'reply' => Icons.chat_bubble_outline,
      'order' || 'payment' => Icons.receipt_long_outlined,
      'system' => Icons.campaign_outlined,
      _ => Icons.notifications_none,
    };

String _compactTime(String? value) {
  final date = DateTime.tryParse(value ?? '')?.toLocal();
  if (date == null) return '';
  final now = DateTime.now();
  if (date.year == now.year && date.month == now.month && date.day == now.day) {
    return '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
  }
  return '${date.month}/${date.day}';
}

String _userLabel(String id, MessagingStrings strings) {
  if (id.isEmpty) return strings.directMessageConversation;
  final short = id.length > 8 ? id.substring(0, 8) : id;
  return '${strings.directMessageConversation} · $short';
}
