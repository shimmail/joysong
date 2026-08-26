import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:joysong_flutter/core/transient_message.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';
import 'package:joysong_flutter/features/messaging/domain/notification_target.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_controllers.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_strings.dart';

class NotificationPage extends StatefulWidget {
  const NotificationPage({
    required this.controller,
    this.onOpenNotification,
    this.title,
    this.filter,
    this.enableAutoTranslation = false,
    super.key,
  });
  final NotificationController controller;
  final ValueChanged<AppNotification>? onOpenNotification;
  final String? title;
  final bool Function(AppNotification notification)? filter;
  final bool enableAutoTranslation;

  @override
  State<NotificationPage> createState() => _NotificationPageState();
}

class _NotificationPageState extends State<NotificationPage> {
  Object _refreshToken = Object();

  @override
  void initState() {
    super.initState();
    unawaited(_refreshNotifications());
  }

  Future<void> _refreshNotifications() async {
    final previousItems = widget.controller.items;
    await widget.controller.refresh();
    if (!mounted || identical(previousItems, widget.controller.items)) return;
    setState(() => _refreshToken = Object());
  }

  @override
  Widget build(BuildContext context) {
    final strings = _strings(context);
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) => Scaffold(
        appBar: AppBar(
          title: Text(widget.title ??
              strings.notifications(widget.controller.unreadCount)),
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
          onRefresh: _refreshNotifications,
          child: _NotificationList(
            controller: widget.controller,
            onOpenNotification: widget.onOpenNotification,
            filter: widget.filter,
            enableAutoTranslation: widget.enableAutoTranslation,
            retryToken: _refreshToken,
            onRefresh: _refreshNotifications,
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
    this.filter,
    required this.enableAutoTranslation,
    required this.retryToken,
    required this.onRefresh,
  });
  final NotificationController controller;
  final ValueChanged<AppNotification>? onOpenNotification;
  final bool Function(AppNotification notification)? filter;
  final bool enableAutoTranslation;
  final Object retryToken;
  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context) {
    final strings = _strings(context);
    final items = filter == null
        ? controller.items
        : controller.items.where(filter!).toList(growable: false);
    final idCounts = <String, int>{};
    for (final item in items) {
      final id = item.id.trim();
      if (id.isNotEmpty) idCounts[id] = (idCounts[id] ?? 0) + 1;
    }
    final duplicateIds = {
      for (final entry in idCounts.entries)
        if (entry.value > 1) entry.key,
    };
    if (controller.isLoading && items.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (controller.errorMessage != null && items.isEmpty) {
      return ListView(
        children: [
          const SizedBox(height: 160),
          Center(child: Text(strings.localizedError(controller.errorMessage!))),
          Center(
            child: TextButton(
              onPressed: onRefresh,
              child: Text(strings.retry),
            ),
          ),
        ],
      );
    }
    if (items.isEmpty) {
      return ListView(
        children: [
          const SizedBox(height: 160),
          Center(child: Text(strings.noNotifications)),
        ],
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.all(16),
      itemCount: items.length,
      findItemIndexCallback: (key) {
        if (key is! ValueKey<String>) return null;
        const prefix = 'notification-row:';
        if (!key.value.startsWith(prefix)) return null;
        final id = key.value.substring(prefix.length);
        final index = items.indexWhere((item) {
          final candidate = item.id.trim();
          return candidate == id && !duplicateIds.contains(candidate);
        });
        return index < 0 ? null : index;
      },
      separatorBuilder: (_, __) => const SizedBox(height: 8),
      itemBuilder: (context, index) {
        final item = items[index];
        final notificationId = item.id.trim();
        final identityStable =
            notificationId.isNotEmpty && !duplicateIds.contains(notificationId);
        return Card(
          key: identityStable
              ? ValueKey<String>('notification-row:$notificationId')
              : ObjectKey(item),
          elevation: 0,
          color: item.isRead
              ? null
              : Theme.of(context).colorScheme.primaryContainer.withAlpha(80),
          child: ListTile(
            leading: CircleAvatar(child: Icon(_notificationIcon(item.type))),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
            ),
            title: StableAutoTranslatedText(
              enabled: enableAutoTranslation &&
                  identityStable &&
                  _isOrderRelatedNotification(item),
              contentType: 'general',
              contentId: 'notification:$notificationId',
              field: 'title',
              sourceText: item.title,
              retryToken: retryToken,
            ),
            subtitle: StableAutoTranslatedText(
              enabled: enableAutoTranslation &&
                  identityStable &&
                  _isOrderRelatedNotification(item),
              contentType: 'general',
              contentId: 'notification:$notificationId',
              field: 'content',
              sourceText: item.content,
              retryToken: retryToken,
              maxLines: 3,
            ),
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

bool _isOrderRelatedNotification(AppNotification item) {
  final kind = NotificationTarget.parse(
    item.targetType,
    item.targetId,
  ).kind;
  return kind == NotificationTargetKind.orderDetail ||
      kind == NotificationTargetKind.orderServiceConversation;
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
    this.onOpenAi,
    this.onOpenSystemMessages,
    this.onOpenActivityMessages,
    this.onRefreshNotifications,
    this.systemUnreadCount = 0,
    this.activityUnreadCount = 0,
    super.key,
  });

  final MessagingHubController controller;
  final String currentUserId;
  final ValueChanged<DmConversation>? onOpenDm;
  final ValueChanged<CustomerServiceConversation>? onOpenCustomerService;
  final ValueChanged<String>? onOpenUser;
  final VoidCallback? onOpenAi;
  final VoidCallback? onOpenSystemMessages;
  final VoidCallback? onOpenActivityMessages;
  final Future<void> Function()? onRefreshNotifications;
  final int systemUnreadCount;
  final int activityUnreadCount;

  @override
  State<MessagingCenterPage> createState() => _MessagingCenterPageState();
}

class _MessagingCenterPageState extends State<MessagingCenterPage> {
  Timer? _refreshTimer;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
    _refreshTimer = Timer.periodic(
      const Duration(seconds: 30),
      (_) => unawaited(_refresh()),
    );
  }

  Future<void> _refresh() => Future.wait<void>([
        widget.controller.refresh(),
        if (widget.onRefreshNotifications case final refresh?) refresh(),
      ]);

  @override
  void dispose() {
    _refreshTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = _strings(context);
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) => Scaffold(
        appBar: AppBar(title: Text(strings.messages)),
        body: RefreshIndicator(
          onRefresh: _refresh,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _MessageCenterEntry(
                key: const Key('message-center-yanyan'),
                icon: Icons.auto_awesome_rounded,
                title: '颜颜',
                subtitle: strings.isEnglish
                    ? 'Your medical aesthetics AI assistant'
                    : '你的医美 AI 助手',
                badge: 'AI',
                onTap: widget.onOpenAi,
              ),
              _MessageCenterEntry(
                key: const Key('message-center-system'),
                icon: Icons.campaign_outlined,
                title: strings.isEnglish ? 'System messages' : '系统消息',
                subtitle: strings.isEnglish
                    ? 'Account, order and service updates'
                    : '账号、订单与服务通知',
                unreadCount: widget.systemUnreadCount,
                onTap: widget.onOpenSystemMessages,
              ),
              _MessageCenterEntry(
                key: const Key('message-center-activity'),
                icon: Icons.local_activity_outlined,
                title: strings.isEnglish ? 'Activity messages' : '活动消息',
                subtitle: strings.isEnglish
                    ? 'Offers and campaign updates'
                    : '优惠活动与平台动态',
                unreadCount: widget.activityUnreadCount,
                onTap: widget.onOpenActivityMessages,
              ),
              const SizedBox(height: 16),
              if (widget.controller.errorMessage case final error?)
                ListTile(
                  leading: const Icon(Icons.error_outline),
                  title: Text(strings.localizedError(error)),
                  trailing: TextButton(
                    onPressed: widget.controller.refresh,
                    child: Text(strings.retry),
                  ),
                ),
              if (widget.controller.customerServiceConversations.any(
                (item) => item.lastMessage?.trim().isNotEmpty == true,
              ))
                Text(
                  strings.customerService,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              for (final item
                  in widget.controller.customerServiceConversations.where(
                (item) => item.lastMessage?.trim().isNotEmpty == true,
              ))
                ListTile(
                  key: ValueKey('customer-service-${item.id}'),
                  leading: const CircleAvatar(child: Icon(Icons.support_agent)),
                  title: Text(strings.customerService),
                  subtitle: Text(item.lastMessage!),
                  trailing: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Text(
                        _compactTime(item.lastMessageAt),
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      _UnreadBadge(count: item.unreadCount),
                    ],
                  ),
                  onTap: () => widget.onOpenCustomerService?.call(item),
                ),
              if (widget.controller.customerServiceConversations.any(
                (item) => item.lastMessage?.trim().isNotEmpty == true,
              ))
                const SizedBox(height: 16),
              Text(
                strings.directMessages,
                style: Theme.of(context).textTheme.titleMedium,
              ),
              for (final item in widget.controller.dmConversations)
                Builder(builder: (context) {
                  final peer = widget.controller.peerFor(item);
                  final otherId = item.otherUserId(widget.currentUserId);
                  return _ConversationActionCard(
                    key: ValueKey('dm-${item.id}'),
                    isPinned: widget.controller.isPinned(item.id),
                    onMarkUnread: () => widget.controller.markUnread(item.id),
                    onTogglePin: () => widget.controller.togglePin(item.id),
                    onDelete: () => widget.controller.hideDmConversation(item),
                    onOpen: () {
                      widget.controller.clearUnread(item.id);
                      widget.onOpenDm?.call(item);
                    },
                    child: _ConversationTile(
                      leading: GestureDetector(
                        onTap: widget.onOpenUser == null
                            ? null
                            : () => widget.onOpenUser!(otherId),
                        child: CircleAvatar(
                          foregroundImage:
                              peer?.avatar.trim().isNotEmpty == true
                                  ? _resizedNetworkImage(
                                      context,
                                      peer!.avatar,
                                      logicalWidth: 40,
                                      logicalHeight: 40,
                                    )
                                  : null,
                          child: peer?.avatar.trim().isNotEmpty == true
                              ? null
                              : const Icon(Icons.person_outline),
                        ),
                      ),
                      title: peer?.name.trim().isNotEmpty == true
                          ? peer!.name
                          : _userLabel(otherId, strings),
                      label: item.conversationType ==
                              DmConversationType.orderService
                          ? strings.orderChat
                          : null,
                      subtitle: item.lastMessage ?? strings.noMessages,
                      time: _compactTime(item.lastMessageAt),
                      unreadCount: item.unreadFor(widget.currentUserId),
                      isLocallyUnread:
                          widget.controller.isLocallyUnread(item.id),
                      isPinned: widget.controller.isPinned(item.id),
                    ),
                  );
                }),
              if (!widget.controller.isLoading &&
                  widget.controller.dmConversations.isEmpty &&
                  widget.controller.customerServiceConversations.every(
                    (item) => item.lastMessage?.trim().isEmpty ?? true,
                  ))
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

class _MessageCenterEntry extends StatelessWidget {
  const _MessageCenterEntry({
    required this.icon,
    required this.title,
    required this.subtitle,
    this.badge,
    this.unreadCount = 0,
    this.onTap,
    super.key,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final String? badge;
  final int unreadCount;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => Card(
        elevation: 0,
        child: ListTile(
          onTap: onTap,
          leading: CircleAvatar(child: Icon(icon)),
          title: Row(
            children: [
              Flexible(child: Text(title)),
              if (badge != null) ...[
                const SizedBox(width: 8),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.primaryContainer,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Text(
                    badge!,
                    style: Theme.of(context).textTheme.labelSmall?.copyWith(
                          color:
                              Theme.of(context).colorScheme.onPrimaryContainer,
                        ),
                  ),
                ),
              ],
            ],
          ),
          subtitle: Text(subtitle),
          trailing: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (unreadCount > 0) ...[
                _UnreadBadge(count: unreadCount),
                const SizedBox(width: 8),
              ],
              const Icon(Icons.chevron_right),
            ],
          ),
        ),
      );
}

class _ConversationTile extends StatelessWidget {
  const _ConversationTile({
    required this.leading,
    required this.title,
    required this.subtitle,
    required this.time,
    required this.unreadCount,
    required this.isLocallyUnread,
    required this.isPinned,
    this.label,
  });

  final Widget leading;
  final String title;
  final String subtitle;
  final String time;
  final int unreadCount;
  final bool isLocallyUnread;
  final bool isPinned;
  final String? label;

  @override
  Widget build(BuildContext context) => Material(
        color: Theme.of(context).colorScheme.surface,
        borderRadius: BorderRadius.circular(12),
        clipBehavior: Clip.antiAlias,
        child: ListTile(
          contentPadding:
              const EdgeInsets.symmetric(horizontal: 14, vertical: 5),
          leading: leading,
          title: Row(
            children: [
              Flexible(
                child: Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              if (label case final label?) ...[
                const SizedBox(width: 6),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 6,
                    vertical: 2,
                  ),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.primaryContainer,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    label,
                    style: Theme.of(context).textTheme.labelSmall?.copyWith(
                          color:
                              Theme.of(context).colorScheme.onPrimaryContainer,
                        ),
                  ),
                ),
              ],
              if (isPinned) ...[
                const SizedBox(width: 4),
                const Icon(
                  Icons.push_pin_outlined,
                  size: 15,
                  color: Color(0xffff9800),
                ),
              ],
            ],
          ),
          subtitle: Text(
            subtitle,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          trailing: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(time, style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: 4),
              _UnreadBadge(
                count: unreadCount,
                showDot: isLocallyUnread,
              ),
            ],
          ),
        ),
      );
}

class _ConversationActionCard extends StatefulWidget {
  const _ConversationActionCard({
    required this.child,
    required this.isPinned,
    required this.onMarkUnread,
    required this.onTogglePin,
    required this.onDelete,
    required this.onOpen,
    super.key,
  });

  final Widget child;
  final bool isPinned;
  final VoidCallback onMarkUnread;
  final VoidCallback onTogglePin;
  final VoidCallback? onDelete;
  final VoidCallback onOpen;

  @override
  State<_ConversationActionCard> createState() =>
      _ConversationActionCardState();
}

class _ConversationActionCardState extends State<_ConversationActionCard> {
  static const _actionWidth = 80.0;
  double get _actionsWidth => _actionWidth * (widget.onDelete == null ? 2 : 3);
  double _offset = 0;

  void _close() {
    if (_offset == 0) return;
    setState(() => _offset = 0);
  }

  void _togglePin() {
    _close();
    widget.onTogglePin();
  }

  void _markUnread() {
    _close();
    widget.onMarkUnread();
  }

  Future<void> _requestDelete() async {
    final onDelete = widget.onDelete;
    if (onDelete == null) return;
    _close();
    final strings = _strings(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(strings.deleteConversation),
        content: Text(strings.deleteConversationConfirm),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(strings.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(strings.delete),
          ),
        ],
      ),
    );
    if (confirmed == true) onDelete();
  }

  Future<void> _showActions() async {
    final strings = _strings(context);
    final action = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(
                Icons.push_pin_outlined,
                color: Color(0xffff9800),
              ),
              title: Text(
                widget.isPinned ? strings.unpinChat : strings.pinChat,
              ),
              onTap: () => Navigator.pop(context, 'pin'),
            ),
            ListTile(
              leading: const Icon(
                Icons.mark_email_unread_outlined,
                color: Color(0xff2196f3),
              ),
              title: Text(strings.markUnread),
              onTap: () => Navigator.pop(context, 'unread'),
            ),
            if (widget.onDelete != null)
              ListTile(
                leading: const Icon(
                  Icons.delete_outline,
                  color: Color(0xfff44336),
                ),
                title: Text(
                  strings.delete,
                  style: const TextStyle(color: Color(0xfff44336)),
                ),
                onTap: () => Navigator.pop(context, 'delete'),
              ),
          ],
        ),
      ),
    );
    if (!mounted) return;
    switch (action) {
      case 'pin':
        _togglePin();
        break;
      case 'unread':
        _markUnread();
        break;
      case 'delete':
        await _requestDelete();
        break;
      default:
        break;
    }
  }

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(12),
          child: SizedBox(
            height: 76,
            child: Stack(
              children: [
                Positioned.fill(
                  child: Align(
                    alignment: Alignment.centerRight,
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        _ConversationActionButton(
                          width: _actionWidth,
                          color: const Color(0xff2196f3),
                          icon: Icons.mark_email_unread_outlined,
                          label: _strings(context).markUnread,
                          onTap: _markUnread,
                        ),
                        _ConversationActionButton(
                          width: _actionWidth,
                          color: const Color(0xffff9800),
                          icon: Icons.push_pin_outlined,
                          label: widget.isPinned
                              ? _strings(context).unpinChat
                              : _strings(context).pinChat,
                          onTap: _togglePin,
                        ),
                        if (widget.onDelete != null)
                          _ConversationActionButton(
                            width: _actionWidth,
                            color: const Color(0xfff44336),
                            icon: Icons.delete_outline,
                            label: _strings(context).delete,
                            onTap: () => unawaited(_requestDelete()),
                          ),
                      ],
                    ),
                  ),
                ),
                AnimatedContainer(
                  duration: const Duration(milliseconds: 180),
                  curve: Curves.easeOut,
                  transform: Matrix4.translationValues(_offset, 0, 0),
                  child: GestureDetector(
                    behavior: HitTestBehavior.translucent,
                    onTap: () {
                      if (_offset != 0) {
                        _close();
                      } else {
                        widget.onOpen();
                      }
                    },
                    onLongPress: () => unawaited(_showActions()),
                    onHorizontalDragUpdate: (details) {
                      setState(() {
                        _offset = (_offset + details.delta.dx)
                            .clamp(-_actionsWidth, 0)
                            .toDouble();
                      });
                    },
                    onHorizontalDragEnd: (_) {
                      setState(() {
                        _offset =
                            _offset <= -_actionsWidth / 2 ? -_actionsWidth : 0;
                      });
                    },
                    child: widget.child,
                  ),
                ),
              ],
            ),
          ),
        ),
      );
}

class _ConversationActionButton extends StatelessWidget {
  const _ConversationActionButton({
    required this.width,
    required this.color,
    required this.icon,
    required this.label,
    required this.onTap,
  });

  final double width;
  final Color color;
  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => SizedBox(
        width: width,
        height: double.infinity,
        child: Material(
          color: color,
          child: InkWell(
            onTap: onTap,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(icon, color: Colors.white, size: 20),
                const SizedBox(height: 3),
                Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: Colors.white, fontSize: 11),
                ),
              ],
            ),
          ),
        ),
      );
}

class DmThreadPage extends StatelessWidget {
  const DmThreadPage({
    required this.controller,
    required this.currentUserId,
    required this.myPeer,
    required this.otherPeer,
    this.title,
    this.onOtherAvatarTap,
    this.onPickImage,
    this.onTranslate,
    this.conversationType = DmConversationType.direct,
    this.sendEnabled = true,
    this.refreshSendEnabled,
    super.key,
  });

  final DmThreadController controller;
  final String currentUserId;
  final MessagingPeer myPeer;
  final MessagingPeer otherPeer;
  final String? title;
  final VoidCallback? onOtherAvatarTap;
  final Future<String?> Function()? onPickImage;
  final Future<String?> Function(String text)? onTranslate;
  final DmConversationType conversationType;
  final bool sendEnabled;
  final Future<bool> Function()? refreshSendEnabled;

  @override
  Widget build(BuildContext context) => _ThreadScaffold<DmMessage>(
        title: title ??
            (otherPeer.name.trim().isEmpty
                ? _strings(context).directMessageTitle
                : otherPeer.name),
        controller: controller,
        items: () => controller.pager.items,
        isLoading: () => controller.pager.isLoading,
        hasMore: () => controller.pager.hasMore,
        error: () => controller.pager.errorMessage ?? controller.sendError,
        loadInitial: controller.initialize,
        refresh: controller.refresh,
        loadOlder: controller.pager.loadOlder,
        send: controller.send,
        pickImage: onPickImage,
        sendImage: controller.sendImage,
        translate: onTranslate,
        isSending: () => controller.isSending,
        contentOf: (item) => item.content,
        createdAtOf: (item) => item.createdAt,
        typeOf: (item) => item.messageType,
        idOf: (item) => item.id,
        delete: conversationType == DmConversationType.direct
            ? controller.deleteMessage
            : null,
        isMine: (item) => item.senderId == currentUserId,
        myPeer: myPeer,
        otherPeer: otherPeer,
        onOtherAvatarTap: onOtherAvatarTap,
        waitingForReply: () => controller.waitingForReply,
        enableAutoTranslation: true,
        sendEnabled: sendEnabled,
        refreshSendEnabled: refreshSendEnabled,
        showRemovalActions: conversationType == DmConversationType.direct,
      );
}

class CustomerServiceThreadPage extends StatelessWidget {
  const CustomerServiceThreadPage({
    required this.controller,
    required this.currentUserId,
    required this.myPeer,
    required this.otherPeer,
    this.onPickImage,
    super.key,
  });

  final CustomerServiceThreadController controller;
  final String currentUserId;
  final MessagingPeer myPeer;
  final MessagingPeer otherPeer;
  final Future<String?> Function()? onPickImage;

  @override
  Widget build(BuildContext context) => _ThreadScaffold<CustomerServiceMessage>(
        title: _strings(context).customerService,
        controller: controller,
        items: () => controller.pager.items,
        isLoading: () => controller.pager.isLoading,
        hasMore: () => controller.pager.hasMore,
        error: () => controller.pager.errorMessage ?? controller.sendError,
        loadInitial: controller.initialize,
        refresh: controller.refresh,
        loadOlder: controller.pager.loadOlder,
        send: controller.send,
        pickImage: onPickImage,
        sendImage: controller.sendImage,
        isSending: () => controller.isSending,
        contentOf: (item) => item.content,
        createdAtOf: (item) => item.createdAt,
        typeOf: (item) => item.messageType,
        idOf: (item) => item.id,
        isMine: (item) => item.senderId == currentUserId,
        myPeer: myPeer,
        otherPeer: otherPeer,
      );
}

enum _MessageAction { copy, showTranslation, showOriginal, delete }

class _ThreadScaffold<T> extends StatefulWidget {
  const _ThreadScaffold({
    required this.title,
    required this.controller,
    required this.items,
    required this.isLoading,
    required this.hasMore,
    required this.error,
    required this.loadInitial,
    required this.refresh,
    required this.loadOlder,
    required this.send,
    required this.isSending,
    required this.contentOf,
    required this.createdAtOf,
    required this.typeOf,
    required this.idOf,
    required this.isMine,
    this.delete,
    this.pickImage,
    this.sendImage,
    this.translate,
    this.myPeer,
    this.otherPeer,
    this.onOtherAvatarTap,
    this.waitingForReply,
    this.sendEnabled = true,
    this.refreshSendEnabled,
    this.showRemovalActions = true,
    this.enableAutoTranslation = false,
  });

  final String title;
  final ChangeNotifier controller;
  final List<T> Function() items;
  final bool Function() isLoading;
  final bool Function() hasMore;
  final String? Function() error;
  final Future<void> Function() loadInitial;
  final Future<void> Function() refresh;
  final Future<void> Function() loadOlder;
  final Future<void> Function(String content) send;
  final bool Function() isSending;
  final String Function(T item) contentOf;
  final String Function(T item) createdAtOf;
  final String Function(T item) typeOf;
  final String Function(T item) idOf;
  final bool Function(T item) isMine;
  final Future<void> Function(String messageId)? delete;
  final Future<String?> Function()? pickImage;
  final Future<void> Function(String imageUrl)? sendImage;
  final Future<String?> Function(String text)? translate;
  final MessagingPeer? myPeer;
  final MessagingPeer? otherPeer;
  final VoidCallback? onOtherAvatarTap;
  final bool Function()? waitingForReply;
  final bool sendEnabled;
  final Future<bool> Function()? refreshSendEnabled;
  final bool showRemovalActions;
  final bool enableAutoTranslation;

  @override
  State<_ThreadScaffold<T>> createState() => _ThreadScaffoldState<T>();
}

class _ThreadScaffoldState<T> extends State<_ThreadScaffold<T>>
    with WidgetsBindingObserver {
  final _input = TextEditingController();
  final _scrollController = ScrollController();
  final Map<String, String> _translations = <String, String>{};
  final Map<String, String> _automaticTranslations = <String, String>{};
  final Set<String> _translating = <String>{};
  final Set<String> _showingTranslations = <String>{};
  final Set<String> _translationSourceOverrides = <String>{};
  Timer? _refreshTimer;
  bool _isPickingImage = false;
  bool? _refreshedSendEnabled;
  int _entitlementRefreshGeneration = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    widget.controller.addListener(_handleControllerUpdate);
    unawaited(widget.loadInitial().then((_) => _scrollToLatest()));
    _refreshTimer = Timer.periodic(
      const Duration(seconds: 15),
      (_) => unawaited(_refresh()),
    );
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    widget.controller.removeListener(_handleControllerUpdate);
    WidgetsBinding.instance.removeObserver(this);
    _scrollController.dispose();
    _input.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) unawaited(_refresh());
  }

  Future<void> _refresh() async {
    final refreshGeneration = ++_entitlementRefreshGeneration;
    await widget.refresh();
    final refreshSendEnabled = widget.refreshSendEnabled;
    if (refreshSendEnabled == null) return;
    try {
      final sendEnabled = await refreshSendEnabled();
      if (!mounted ||
          refreshGeneration != _entitlementRefreshGeneration ||
          sendEnabled == (_refreshedSendEnabled ?? widget.sendEnabled)) {
        return;
      }
      setState(() => _refreshedSendEnabled = sendEnabled);
    } on Object {
      // The server still enforces send permission if entitlement refresh fails.
    }
  }

  void _handleControllerUpdate() {
    if (!mounted) return;
    if (!_scrollController.hasClients ||
        _scrollController.position.maxScrollExtent -
                _scrollController.position.pixels <
            140) {
      _scrollToLatest();
    }
  }

  void _scrollToLatest() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scrollController.hasClients) return;
      _scrollController.animateTo(
        _scrollController.position.maxScrollExtent,
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOut,
      );
    });
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
                controller: _scrollController,
                padding: const EdgeInsets.all(16),
                itemCount: widget.items().length + (widget.hasMore() ? 1 : 0),
                findChildIndexCallback: _findMessageChildIndex,
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
                  final messageId = widget.idOf(item);
                  final sourceText = widget.contentOf(item);
                  final rowKey = _messageRowKey(item);
                  return Padding(
                    key: rowKey,
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    child: Column(
                      children: [
                        _MessageTimePill(
                          value: _fullTime(widget.createdAtOf(item)),
                        ),
                        const SizedBox(height: 6),
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisAlignment: mine
                              ? MainAxisAlignment.end
                              : MainAxisAlignment.start,
                          children: [
                            if (!mine) ...[
                              _PeerAvatar(
                                peer: widget.otherPeer,
                                onTap: widget.onOtherAvatarTap,
                              ),
                              const SizedBox(width: 8),
                            ],
                            Flexible(
                              child: GestureDetector(
                                onLongPress: () =>
                                    _showMessageActions(item, mine),
                                child: Container(
                                  constraints:
                                      const BoxConstraints(maxWidth: 280),
                                  padding: messageType == 'IMAGE'
                                      ? EdgeInsets.zero
                                      : const EdgeInsets.symmetric(
                                          horizontal: 14,
                                          vertical: 11,
                                        ),
                                  decoration: BoxDecoration(
                                    color: mine
                                        ? Theme.of(context).colorScheme.primary
                                        : Theme.of(context)
                                            .colorScheme
                                            .surfaceContainerLow,
                                    borderRadius: BorderRadius.only(
                                      topLeft: const Radius.circular(16),
                                      topRight: const Radius.circular(16),
                                      bottomLeft:
                                          Radius.circular(mine ? 16 : 4),
                                      bottomRight:
                                          Radius.circular(mine ? 4 : 16),
                                    ),
                                  ),
                                  child: messageType == 'IMAGE'
                                      ? GestureDetector(
                                          onTap: () => _openImage(
                                            widget.contentOf(item),
                                          ),
                                          child: ClipRRect(
                                            borderRadius:
                                                BorderRadius.circular(14),
                                            child: ConstrainedBox(
                                              constraints: const BoxConstraints(
                                                maxWidth: 260,
                                                maxHeight: 320,
                                              ),
                                              child: Image.network(
                                                widget.contentOf(item),
                                                fit: BoxFit.contain,
                                                filterQuality:
                                                    FilterQuality.medium,
                                                gaplessPlayback: true,
                                                errorBuilder: (_, __, ___) =>
                                                    const SizedBox(
                                                  width: 180,
                                                  height: 120,
                                                  child: Icon(
                                                    Icons.broken_image_outlined,
                                                  ),
                                                ),
                                              ),
                                            ),
                                          ),
                                        )
                                      : _buildMessageText(
                                          messageId: messageId,
                                          sourceText: sourceText,
                                          automaticEligible:
                                              widget.enableAutoTranslation &&
                                                  !mine &&
                                                  messageType == 'TEXT' &&
                                                  rowKey != null,
                                          style: TextStyle(
                                            color: mine
                                                ? Theme.of(context)
                                                    .colorScheme
                                                    .onPrimary
                                                : Theme.of(context)
                                                    .colorScheme
                                                    .onSurface,
                                          ),
                                        ),
                                ),
                              ),
                            ),
                            if (mine) ...[
                              const SizedBox(width: 8),
                              _PeerAvatar(peer: widget.myPeer),
                            ],
                          ],
                        ),
                        if (_translating.contains(messageId))
                          Padding(
                            padding: EdgeInsets.only(
                              top: 4,
                              left: mine ? 0 : 44,
                              right: mine ? 44 : 0,
                            ),
                            child: Row(
                              mainAxisAlignment: mine
                                  ? MainAxisAlignment.end
                                  : MainAxisAlignment.start,
                              children: [
                                const SizedBox.square(
                                  dimension: 12,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 1.5,
                                  ),
                                ),
                                const SizedBox(width: 6),
                                Text(
                                  strings.translating,
                                  style: Theme.of(context).textTheme.labelSmall,
                                ),
                              ],
                            ),
                          ),
                      ],
                    ),
                  );
                },
              ),
            ),
            if (_refreshedSendEnabled ?? widget.sendEnabled)
              _MessageComposer(
                input: _input,
                isLoading: widget.isLoading(),
                isSending: widget.isSending(),
                isPickingImage: _isPickingImage,
                waitingForReply: widget.waitingForReply?.call() ?? false,
                canPickImage:
                    widget.pickImage != null && widget.sendImage != null,
                onPickImage: _pickAndSendImage,
                onSend: _sendText,
                strings: strings,
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildMessageText({
    required String messageId,
    required String sourceText,
    required bool automaticEligible,
    required TextStyle style,
  }) {
    Widget buildText(BuildContext context, String automaticText) {
      if (_usableMessageTranslation(automaticText, sourceText)) {
        _automaticTranslations[messageId] = automaticText.trim();
      } else {
        _automaticTranslations.remove(messageId);
      }
      return Text(
        _visibleMessageText(messageId, sourceText),
        style: style,
      );
    }

    if (!automaticEligible) return buildText(context, sourceText);
    return _StableAutomaticMessageText(
      key: ValueKey('automatic-message:$messageId'),
      messageId: messageId,
      sourceText: sourceText,
      builder: buildText,
    );
  }

  String _visibleMessageText(String messageId, String sourceText) {
    if (_translationSourceOverrides.contains(messageId)) return sourceText;
    final manual = _translations[messageId];
    if (_showingTranslations.contains(messageId) &&
        _usableMessageTranslation(manual, sourceText)) {
      return manual!.trim();
    }
    final automatic = _automaticTranslations[messageId];
    if (_usableMessageTranslation(automatic, sourceText)) {
      return automatic!.trim();
    }
    return sourceText;
  }

  Key? _messageRowKey(T item) {
    final id = widget.idOf(item).trim();
    if (id.isEmpty) return null;
    var matches = 0;
    for (final candidate in widget.items()) {
      if (widget.idOf(candidate).trim() == id) matches += 1;
      if (matches > 1) return null;
    }
    return ValueKey<String>('message-row:$id');
  }

  int? _findMessageChildIndex(Key key) {
    final items = widget.items();
    final offset = widget.hasMore() ? 1 : 0;
    for (var index = 0; index < items.length; index += 1) {
      if (_messageRowKey(items[index]) == key) return index + offset;
    }
    return null;
  }

  Future<void> _sendText() async {
    if (widget.waitingForReply?.call() ?? false) return;
    final text = _input.text;
    if (text.trim().isEmpty) return;
    _input.clear();
    await widget.send(text);
    if (!mounted) return;
    if (widget.error() != null) {
      _input.text = text;
      _input.selection = TextSelection.collapsed(
        offset: _input.text.length,
      );
    }
  }

  Future<void> _showMessageActions(T item, bool mine) async {
    final strings = _strings(context);
    final messageId = widget.idOf(item);
    final isText = widget.typeOf(item).toUpperCase() == 'TEXT';
    final sourceText = widget.contentOf(item);
    final showingTranslation =
        _visibleMessageText(messageId, sourceText) != sourceText;
    final action = await showModalBottomSheet<_MessageAction>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.copy_outlined),
              title: Text(strings.copy),
              onTap: () => Navigator.pop(context, _MessageAction.copy),
            ),
            ListTile(
              enabled: false,
              leading: const Icon(Icons.format_quote_outlined),
              title: Text(strings.quote),
            ),
            if (isText && widget.translate != null)
              ListTile(
                enabled: !_translating.contains(messageId),
                leading: const Icon(Icons.translate),
                title: Text(
                  showingTranslation ? strings.showOriginal : strings.translate,
                ),
                onTap: () => Navigator.pop(
                  context,
                  showingTranslation
                      ? _MessageAction.showOriginal
                      : _MessageAction.showTranslation,
                ),
              ),
            if (widget.showRemovalActions)
              ListTile(
                enabled: false,
                leading: const Icon(Icons.undo_outlined),
                title: Text(strings.unsend),
              ),
            if (widget.showRemovalActions && mine && widget.delete != null)
              ListTile(
                leading: const Icon(Icons.delete_outline),
                title: Text(strings.delete),
                textColor: Theme.of(context).colorScheme.error,
                iconColor: Theme.of(context).colorScheme.error,
                onTap: () => Navigator.pop(context, _MessageAction.delete),
              ),
          ],
        ),
      ),
    );
    if (!mounted) return;
    if (action == _MessageAction.copy) {
      await Clipboard.setData(ClipboardData(text: widget.contentOf(item)));
      if (mounted) {
        showTransientMessage(context, strings.copied);
      }
    } else if (action == _MessageAction.showTranslation) {
      await _showMessageTranslation(item);
    } else if (action == _MessageAction.showOriginal) {
      _showMessageOriginal(item);
    } else if (action == _MessageAction.delete) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(strings.deleteMessage),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: Text(strings.cancel),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: Text(strings.delete),
            ),
          ],
        ),
      );
      if (confirmed == true) {
        await widget.delete?.call(widget.idOf(item));
      }
    }
  }

  Future<void> _pickAndSendImage() async {
    final pickImage = widget.pickImage;
    final sendImage = widget.sendImage;
    if (pickImage == null || sendImage == null || _isPickingImage) return;
    setState(() => _isPickingImage = true);
    try {
      final url = await pickImage();
      if (url?.trim().isNotEmpty == true) await sendImage(url!.trim());
    } finally {
      if (mounted) setState(() => _isPickingImage = false);
    }
  }

  void _showMessageOriginal(T item) {
    final messageId = widget.idOf(item);
    setState(() => _translationSourceOverrides.add(messageId));
  }

  Future<void> _showMessageTranslation(T item) async {
    final translate = widget.translate;
    final messageId = widget.idOf(item);
    final sourceText = widget.contentOf(item);
    final manual = _translations[messageId];
    final automatic = _automaticTranslations[messageId];
    final hasManual = _usableMessageTranslation(manual, sourceText);
    final hasAutomatic = _usableMessageTranslation(automatic, sourceText);
    if (hasManual || hasAutomatic) {
      setState(() {
        _translationSourceOverrides.remove(messageId);
        if (hasManual) _showingTranslations.add(messageId);
      });
      return;
    }
    if (translate == null || !_translating.add(messageId)) return;
    setState(() {});
    try {
      final translated = await translate(sourceText);
      if (!mounted) return;
      if (translated?.trim().isNotEmpty == true) {
        setState(() {
          _translations[messageId] = translated!.trim();
          _showingTranslations.add(messageId);
          _translationSourceOverrides.remove(messageId);
        });
      } else {
        showTransientMessage(context, _strings(context).translationFailed);
      }
    } finally {
      _translating.remove(messageId);
      if (mounted) setState(() {});
    }
  }

  Future<void> _openImage(String url) async {
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => Scaffold(
          backgroundColor: Colors.black,
          appBar: AppBar(
            backgroundColor: Colors.black,
            foregroundColor: Colors.white,
          ),
          body: Center(
            child: InteractiveViewer(
              minScale: 0.8,
              maxScale: 5,
              child: Image.network(url, fit: BoxFit.contain),
            ),
          ),
        ),
      ),
    );
  }
}

final class _StableAutomaticMessageText extends StatefulWidget {
  const _StableAutomaticMessageText({
    required this.messageId,
    required this.sourceText,
    required this.builder,
    super.key,
  });

  final String messageId;
  final String sourceText;
  final Widget Function(BuildContext context, String automaticText) builder;

  @override
  State<_StableAutomaticMessageText> createState() =>
      _StableAutomaticMessageTextState();
}

final class _StableAutomaticMessageTextState
    extends State<_StableAutomaticMessageText> {
  AutoTranslationRequest? _request;

  @override
  void initState() {
    super.initState();
    _synchronizeRequest();
  }

  @override
  void didUpdateWidget(_StableAutomaticMessageText oldWidget) {
    super.didUpdateWidget(oldWidget);
    _synchronizeRequest();
  }

  void _synchronizeRequest() {
    final contentId = 'message:${widget.messageId}';
    final previous = _request;
    if (widget.messageId.trim().isEmpty || widget.sourceText.trim().isEmpty) {
      _request = null;
    } else if (previous == null ||
        previous.contentType != 'message' ||
        previous.contentId != contentId ||
        previous.field != 'content' ||
        previous.sourceText != widget.sourceText) {
      _request = AutoTranslationRequest(
        contentType: 'message',
        contentId: contentId,
        field: 'content',
        sourceText: widget.sourceText,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final request = _request;
    if (request == null) return widget.builder(context, widget.sourceText);
    return AutoTranslationBuilder(
      request: request,
      builder: widget.builder,
    );
  }
}

bool _usableMessageTranslation(String? candidate, String sourceText) {
  final value = candidate?.trim() ?? '';
  return value.isNotEmpty && value != sourceText;
}

class _MessageTimePill extends StatelessWidget {
  const _MessageTimePill({required this.value});

  final String value;

  @override
  Widget build(BuildContext context) {
    if (value.isEmpty) return const SizedBox.shrink();
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        value,
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
      ),
    );
  }
}

class _PeerAvatar extends StatelessWidget {
  const _PeerAvatar({this.peer, this.onTap});

  final MessagingPeer? peer;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final avatar = peer?.avatar.trim() ?? '';
    final name = peer?.name.trim() ?? '';
    final isCustomerService = peer?.id.trim().toUpperCase() == 'CS_ADMIN';
    final content = CircleAvatar(
      radius: 18,
      backgroundColor: isCustomerService
          ? Theme.of(context).colorScheme.primaryContainer
          : null,
      foregroundImage: avatar.isEmpty
          ? null
          : _resizedNetworkImage(
              context,
              avatar,
              logicalWidth: 36,
              logicalHeight: 36,
            ),
      child: avatar.isNotEmpty
          ? null
          : isCustomerService
              ? Icon(
                  Icons.support_agent_rounded,
                  size: 21,
                  color: Theme.of(context).colorScheme.onPrimaryContainer,
                )
              : Text(name.isEmpty ? '?' : name.substring(0, 1)),
    );
    if (onTap == null) return content;
    return GestureDetector(onTap: onTap, child: content);
  }
}

int _physicalPixels(BuildContext context, double logicalPixels) =>
    (logicalPixels * MediaQuery.devicePixelRatioOf(context)).ceil();

ImageProvider<Object> _resizedNetworkImage(
  BuildContext context,
  String url, {
  required double logicalWidth,
  required double logicalHeight,
}) =>
    ResizeImage.resizeIfNeeded(
      _physicalPixels(context, logicalWidth),
      _physicalPixels(context, logicalHeight),
      NetworkImage(url),
    );

class _MessageComposer extends StatelessWidget {
  const _MessageComposer({
    required this.input,
    required this.isLoading,
    required this.isSending,
    required this.isPickingImage,
    required this.waitingForReply,
    required this.canPickImage,
    required this.onPickImage,
    required this.onSend,
    required this.strings,
  });

  final TextEditingController input;
  final bool isLoading;
  final bool isSending;
  final bool isPickingImage;
  final bool waitingForReply;
  final bool canPickImage;
  final VoidCallback onPickImage;
  final VoidCallback onSend;
  final MessagingStrings strings;

  @override
  Widget build(BuildContext context) => Material(
        color: Theme.of(context).colorScheme.surface,
        child: SafeArea(
          top: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (waitingForReply)
                Container(
                  width: double.infinity,
                  color: const Color(0xfffff3e0),
                  padding:
                      const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                  child: Row(
                    children: [
                      const Text('⏳'),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          strings.rateLimitHint,
                          style: const TextStyle(
                            color: Color(0xffe65100),
                            fontSize: 12,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              Padding(
                padding: const EdgeInsets.fromLTRB(12, 8, 12, 10),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    if (canPickImage)
                      IconButton(
                        tooltip: strings.chooseImage,
                        onPressed:
                            waitingForReply || isSending || isPickingImage
                                ? null
                                : onPickImage,
                        icon: isPickingImage
                            ? const SizedBox.square(
                                dimension: 20,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : const Icon(Icons.add_photo_alternate_outlined),
                      ),
                    Expanded(
                      child: TextField(
                        controller: input,
                        enabled: !waitingForReply,
                        minLines: 1,
                        maxLines: 4,
                        maxLength: 2000,
                        textInputAction: TextInputAction.send,
                        onSubmitted: (_) => onSend(),
                        decoration: InputDecoration(
                          hintText: waitingForReply
                              ? strings.waitingForReply
                              : strings.messageHint,
                          counterText: '',
                          filled: true,
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(22),
                            borderSide: BorderSide.none,
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    IconButton.filled(
                      tooltip: strings.send,
                      onPressed: waitingForReply || isLoading || isSending
                          ? null
                          : onSend,
                      icon: isSending
                          ? const SizedBox.square(
                              dimension: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.send),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      );
}

class _UnreadBadge extends StatelessWidget {
  const _UnreadBadge({required this.count, this.showDot = false});
  final int count;
  final bool showDot;
  @override
  Widget build(BuildContext context) {
    if (count > 0) {
      return Badge(label: Text(count >= 100 ? '99+' : '$count'));
    }
    return showDot
        ? Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.error,
              shape: BoxShape.circle,
            ),
          )
        : const SizedBox.shrink();
  }
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

String _fullTime(String? value) {
  final date = DateTime.tryParse(value ?? '')?.toLocal();
  if (date == null) {
    final fallback = (value ?? '').replaceFirst('T', ' ');
    return fallback.length > 16 ? fallback.substring(0, 16) : fallback;
  }
  String two(int part) => part.toString().padLeft(2, '0');
  return '${date.year}-${two(date.month)}-${two(date.day)} '
      '${two(date.hour)}:${two(date.minute)}';
}

String _userLabel(String id, MessagingStrings strings) {
  if (id.isEmpty) return strings.directMessageConversation;
  final short = id.length > 8 ? id.substring(0, 8) : id;
  return '${strings.directMessageConversation} · $short';
}
