import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show ScrollCacheExtent;
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_controller.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_catalog_cards.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_plan_controller.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_plan_view.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_profile_safety_page.dart';

enum _AgentMenuAction {
  profile,
  history,
  plans,
  newChat,
  clearCurrent,
  deleteCurrent,
  clearAll,
}

class AgentChatPage extends StatefulWidget {
  const AgentChatPage({
    required this.chatController,
    required this.planController,
    this.initialContextType,
    this.initialContextId,
    this.initialContextName,
    this.onOpenCatalogItem,
    this.onHumanConsult,
    super.key,
  });

  final AgentChatController chatController;
  final AgentPlanController planController;
  final ChatContextType? initialContextType;
  final String? initialContextId;
  final String? initialContextName;
  final AgentCatalogItemAction? onOpenCatalogItem;
  final AgentCatalogItemAction? onHumanConsult;

  @override
  State<AgentChatPage> createState() => _AgentChatPageState();
}

class _AgentChatPageState extends State<AgentChatPage> {
  final _inputController = TextEditingController();
  final _scrollController = ScrollController();
  Timer? _scrollDebounce;
  bool _stickToBottom = true;

  @override
  void initState() {
    super.initState();
    widget.chatController.addListener(_scrollToEnd);
    _scrollController.addListener(_trackScrollPosition);
    unawaited(_initializeChat());
  }

  Future<void> _initializeChat() async {
    await widget.chatController.loadSessions();
    final contextType = widget.initialContextType;
    final contextId = widget.initialContextId?.trim() ?? '';
    final contextName = widget.initialContextName?.trim() ?? '';
    if (contextType != null && contextId.isNotEmpty && contextName.isNotEmpty) {
      try {
        await widget.chatController.startContextSummary(
          contextType: contextType,
          contextId: contextId,
          contextName: contextName,
        );
      } on StateError catch (error) {
        if (error.message != 'CHAT_SEND_IN_PROGRESS') rethrow;
        // A shared controller may still be delivering its current REST turn.
        // Keep that session instead of replacing it with context initialization.
      }
    }
  }

  @override
  void dispose() {
    widget.chatController.removeListener(_scrollToEnd);
    _scrollDebounce?.cancel();
    _scrollController.removeListener(_trackScrollPosition);
    _inputController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _trackScrollPosition() {
    if (!_scrollController.hasClients) return;
    _stickToBottom = _scrollController.position.extentAfter < 140;
  }

  void _scrollToEnd() {
    if (!_stickToBottom) return;
    // Coalesce rapid chat state changes so they do not repeatedly restart a
    // scroll animation within the same frame window.
    _scrollDebounce ??= Timer(const Duration(milliseconds: 80), () {
      _scrollDebounce = null;
      if (!mounted || !_stickToBottom) return;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted || !_scrollController.hasClients) return;
        _scrollController.jumpTo(_scrollController.position.maxScrollExtent);
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.chatController,
      builder: (context, _) {
        final state = widget.chatController.state;
        final english = Localizations.localeOf(context).languageCode == 'en';
        final canMutateSessions = !state.deliveryState.isBusy;
        return Scaffold(
          appBar: AppBar(
            title: const Text('颜颜'),
            actions: [
              PopupMenuButton<_AgentMenuAction>(
                tooltip: english ? 'More options' : '更多操作',
                icon: const Icon(Icons.more_vert),
                onSelected: (action) => _handleMenuAction(action, english),
                itemBuilder: (context) => [
                  _menuItem(
                    _AgentMenuAction.profile,
                    Icons.health_and_safety_outlined,
                    english ? 'Profile & safety' : '档案与安全筛查',
                  ),
                  _menuItem(
                    _AgentMenuAction.history,
                    Icons.history,
                    english ? 'Chat history' : '历史会话',
                  ),
                  _menuItem(
                    _AgentMenuAction.plans,
                    Icons.assignment_outlined,
                    english ? 'My plans' : '我的方案',
                  ),
                  _menuItem(
                    _AgentMenuAction.newChat,
                    Icons.add_comment_outlined,
                    english ? 'New chat' : '新会话',
                    enabled: canMutateSessions,
                  ),
                  const PopupMenuDivider(),
                  _menuItem(
                    _AgentMenuAction.clearCurrent,
                    Icons.delete_sweep_outlined,
                    english ? 'Clear current messages' : '清空当前消息',
                    enabled: canMutateSessions &&
                        state.activeSession != null &&
                        state.messages.isNotEmpty,
                  ),
                  _menuItem(
                    _AgentMenuAction.deleteCurrent,
                    Icons.delete_outline,
                    english ? 'Delete current chat' : '删除当前会话',
                    enabled: canMutateSessions && state.activeSession != null,
                    destructive: true,
                  ),
                  _menuItem(
                    _AgentMenuAction.clearAll,
                    Icons.delete_forever_outlined,
                    english ? 'Clear all history' : '清空全部历史',
                    enabled: canMutateSessions && state.sessions.isNotEmpty,
                    destructive: true,
                  ),
                ],
              ),
            ],
          ),
          body: Column(
            children: [
              _SafetyNotice(english: english),
              if (state.deliveryState == ChatDeliveryState.loadingHistory)
                const LinearProgressIndicator(),
              if (state.errorMessage != null)
                _StatusBanner(
                  message: state.errorMessage!,
                ),
              Expanded(
                child: state.messages.isEmpty
                    ? _EmptyChat(english: english)
                    : ListView(
                        controller: _scrollController,
                        scrollCacheExtent: const ScrollCacheExtent.pixels(600),
                        padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                        children: [
                          for (final message in state.messages) ...[
                            _ChatBubble(message: message),
                            if (message.id == state.failedMessageId)
                              Align(
                                alignment: Alignment.centerLeft,
                                child: TextButton.icon(
                                  onPressed: state.deliveryState.isBusy
                                      ? null
                                      : () => unawaited(
                                            widget.chatController.retry(),
                                          ),
                                  icon: const Icon(Icons.refresh, size: 18),
                                  label: const Text('生成中断，可重试'),
                                ),
                              ),
                            if (!message.isUser &&
                                _supportedCatalogItems(message).isNotEmpty)
                              AgentCatalogLinkList(
                                items: _supportedCatalogItems(message),
                                onOpen: widget.onOpenCatalogItem,
                                canOpen: _canOpenCatalogItem,
                              ),
                          ],
                        ],
                      ),
              ),
              _Composer(
                inputController: _inputController,
                isBusy: state.deliveryState.isBusy,
                onSend: () {
                  final text = _inputController.text;
                  if (text.trim().isEmpty) return;
                  _inputController.clear();
                  unawaited(widget.chatController.send(text));
                },
              ),
            ],
          ),
        );
      },
    );
  }

  bool _canOpenCatalogItem(AgentCatalogItem item) {
    if (item.id.trim().isEmpty) return false;
    return switch (item.type.trim().toUpperCase()) {
      'PROJECT' || 'DOCTOR' || 'INSTITUTION' => true,
      'INSTITUTION_PROJECT' =>
        (item.institutionId?.trim().isNotEmpty ?? false) &&
            (item.projectId?.trim().isNotEmpty ?? false),
      _ => false,
    };
  }

  List<AgentCatalogItem> _supportedCatalogItems(ChatMessage message) {
    const supported = {
      'DOCTOR',
      'INSTITUTION',
      'PROJECT',
      'INSTITUTION_PROJECT',
    };
    return message.catalogItems
        .where((item) => supported.contains(item.type.trim().toUpperCase()))
        .toList(growable: false);
  }

  PopupMenuItem<_AgentMenuAction> _menuItem(
    _AgentMenuAction value,
    IconData icon,
    String label, {
    bool enabled = true,
    bool destructive = false,
  }) =>
      PopupMenuItem(
        value: value,
        enabled: enabled,
        child: Row(
          children: [
            Icon(
              icon,
              size: 20,
              color: destructive ? Theme.of(context).colorScheme.error : null,
            ),
            const SizedBox(width: 12),
            Text(
              label,
              style: destructive
                  ? TextStyle(color: Theme.of(context).colorScheme.error)
                  : null,
            ),
          ],
        ),
      );

  Future<void> _handleMenuAction(
    _AgentMenuAction action,
    bool english,
  ) async {
    switch (action) {
      case _AgentMenuAction.profile:
        await Navigator.of(context).push<void>(
          MaterialPageRoute(
            builder: (_) => AgentProfileSafetyPage(
              controller: widget.planController,
            ),
          ),
        );
      case _AgentMenuAction.history:
        await _showHistory(widget.chatController.state.sessions);
      case _AgentMenuAction.plans:
        await _showPlans();
      case _AgentMenuAction.newChat:
        try {
          widget.chatController.startNewSession();
        } on StateError catch (error) {
          if (error.message != 'CHAT_SEND_IN_PROGRESS') rethrow;
          // A send can start after the menu was built. Keep the current chat.
        }
      case _AgentMenuAction.clearCurrent:
        await _confirmClearCurrent(english);
      case _AgentMenuAction.deleteCurrent:
        final session = widget.chatController.state.activeSession;
        if (session != null) await _confirmDeleteSession(session);
      case _AgentMenuAction.clearAll:
        await _confirmClearAll(english);
    }
  }

  Future<void> _showHistory(List<ChatSession> sessions) async {
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (sheetContext) => SafeArea(
        child: sessions.isEmpty
            ? Padding(
                padding: const EdgeInsets.all(24),
                child: Text(
                  Localizations.localeOf(context).languageCode == 'en'
                      ? 'No chat history'
                      : '暂无历史会话',
                ),
              )
            : ListView(
                shrinkWrap: true,
                children: [
                  for (final session in sessions)
                    ListTile(
                      title: Text(
                        session.title.isEmpty
                            ? Localizations.localeOf(context).languageCode ==
                                    'en'
                                ? 'Untitled chat'
                                : '未命名会话'
                            : session.title,
                      ),
                      subtitle: Text(
                        session.lastMessage,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      onTap: () {
                        Navigator.pop(sheetContext);
                        unawaited(widget.chatController.openSession(session));
                      },
                      trailing: IconButton(
                        tooltip:
                            Localizations.localeOf(context).languageCode == 'en'
                                ? 'Delete chat'
                                : '删除会话',
                        icon: const Icon(Icons.delete_outline),
                        onPressed: () async {
                          final deleted = await _confirmDeleteSession(session);
                          if (deleted && sheetContext.mounted) {
                            Navigator.pop(sheetContext);
                          }
                        },
                      ),
                    ),
                ],
              ),
      ),
    );
  }

  Future<bool> _confirmDeleteSession(ChatSession session) async {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final confirmed = await _confirmDestructiveAction(
      title: english ? 'Delete this chat?' : '删除这条历史会话？',
      message: english
          ? 'This chat and all of its messages will be permanently deleted.'
          : '该会话及其中的全部消息将被永久删除。',
      confirmLabel: english ? 'Delete' : '删除',
      cancelLabel: english ? 'Cancel' : '取消',
    );
    if (!confirmed) return false;
    try {
      await widget.chatController.deleteSession(session);
      return true;
    } on Object {
      return false;
    }
  }

  Future<void> _confirmClearCurrent(bool english) async {
    final confirmed = await _confirmDestructiveAction(
      title: english ? 'Clear current messages?' : '清空当前消息？',
      message: english
          ? 'All messages in this chat will be permanently deleted.'
          : '当前会话中的全部消息将被永久删除。',
      confirmLabel: english ? 'Clear' : '清空',
      cancelLabel: english ? 'Cancel' : '取消',
    );
    if (!confirmed) return;
    try {
      await widget.chatController.clearActiveMessages();
    } on Object {
      // The controller exposes the localized request error in the page banner.
    }
  }

  Future<void> _confirmClearAll(bool english) async {
    final confirmed = await _confirmDestructiveAction(
      title: english ? 'Clear all chat history?' : '清空全部历史？',
      message: english
          ? 'Every chat and message with 颜颜 will be permanently deleted.'
          : '与颜颜的全部会话和消息将被永久删除。',
      confirmLabel: english ? 'Clear all' : '全部清空',
      cancelLabel: english ? 'Cancel' : '取消',
    );
    if (!confirmed) return;
    try {
      await widget.chatController.clearSessions();
    } on Object {
      // The controller exposes the localized request error in the page banner.
    }
  }

  Future<bool> _confirmDestructiveAction({
    required String title,
    required String message,
    required String confirmLabel,
    required String cancelLabel,
  }) async =>
      await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text(title),
          content: Text(message),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: Text(cancelLabel),
            ),
            TextButton(
              onPressed: () => Navigator.pop(dialogContext, true),
              style: TextButton.styleFrom(
                foregroundColor: Theme.of(context).colorScheme.error,
              ),
              child: Text(confirmLabel),
            ),
          ],
        ),
      ) ??
      false;

  Future<void> _showPlans() async {
    unawaited(widget.planController.load());
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) => FractionallySizedBox(
        heightFactor: 0.9,
        child: AnimatedBuilder(
          animation: widget.planController,
          builder: (context, _) {
            final state = widget.planController.state;
            if (state.isLoading && state.plans.isEmpty) {
              return const Center(child: CircularProgressIndicator());
            }
            if (state.selectedPlan case final plan?) {
              return AgentPlanView(plan: plan);
            }
            if (state.plans.isEmpty) {
              return const Center(child: Text('暂无方案，请先完善档案并完成安全筛查。'));
            }
            return ListView(
              children: [
                for (final plan in state.plans)
                  ListTile(
                    title: Text('方案 V${plan.version}'),
                    subtitle: Text(plan.summary, maxLines: 2),
                    onTap: () => widget.planController.selectPlan(plan),
                  ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _SafetyNotice extends StatelessWidget {
  const _SafetyNotice({required this.english});
  final bool english;
  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        margin: const EdgeInsets.fromLTRB(16, 8, 16, 4),
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Text(english
            ? 'AI helps organize information only. It is not a diagnosis, treatment recommendation, or outcome guarantee.'
            : 'AI 仅辅助梳理信息，不构成诊断、治疗建议或效果承诺。'),
      );
}

class _EmptyChat extends StatelessWidget {
  const _EmptyChat({required this.english});
  final bool english;
  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Text(english
              ? 'Tell me your goals, budget, and acceptable downtime.\nI will also explain risks, limitations, alternatives, and items to confirm.'
              : '说说你的目标、预算和可接受恢复期。\n我会同时说明风险、限制、替代方案与待确认项。'),
        ),
      );
}

class _ChatBubble extends StatelessWidget {
  const _ChatBubble({required this.message});
  final ChatMessage message;

  @override
  Widget build(BuildContext context) => Align(
        alignment:
            message.isUser ? Alignment.centerRight : Alignment.centerLeft,
        child: Container(
          constraints: const BoxConstraints(maxWidth: 320),
          margin: const EdgeInsets.symmetric(vertical: 5),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: message.isUser
                ? Theme.of(context).colorScheme.primaryContainer
                : Theme.of(context).colorScheme.surfaceContainerLow,
            borderRadius: BorderRadius.circular(16),
          ),
          child: Text(
            message.content.isEmpty && message.isTemporary
                ? '正在思考…'
                : message.content,
          ),
        ),
      );
}

class _StatusBanner extends StatelessWidget {
  const _StatusBanner({required this.message});
  final String message;

  @override
  Widget build(BuildContext context) => Semantics(
        liveRegion: true,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
          child: Row(
            children: [
              Icon(
                Icons.info_outline,
                size: 18,
              ),
              const SizedBox(width: 8),
              Expanded(child: Text(message)),
            ],
          ),
        ),
      );
}

class _Composer extends StatelessWidget {
  const _Composer({
    required this.inputController,
    required this.isBusy,
    required this.onSend,
  });
  final TextEditingController inputController;
  final bool isBusy;
  final VoidCallback onSend;

  @override
  Widget build(BuildContext context) => SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: TextField(
                  controller: inputController,
                  minLines: 1,
                  maxLines: 4,
                  maxLength: 5000,
                  decoration: const InputDecoration(
                    hintText: '输入你的问题',
                    counterText: '',
                  ),
                ),
              ),
              const SizedBox(width: 8),
              IconButton.filled(
                tooltip: '发送',
                onPressed: isBusy ? null : onSend,
                icon: const Icon(Icons.send),
              ),
            ],
          ),
        ),
      );
}
