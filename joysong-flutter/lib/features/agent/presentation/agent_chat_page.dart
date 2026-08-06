import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_catalog_cards.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_controller.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_plan_controller.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_plan_view.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_profile_safety_page.dart';

class AgentChatPage extends StatefulWidget {
  const AgentChatPage({
    required this.chatController,
    required this.planController,
    this.onOpenCatalogItem,
    this.onHumanChat,
    super.key,
  });

  final AgentChatController chatController;
  final AgentPlanController planController;
  final AgentCatalogItemAction? onOpenCatalogItem;
  final AgentCatalogItemAction? onHumanChat;

  @override
  State<AgentChatPage> createState() => _AgentChatPageState();
}

class _AgentChatPageState extends State<AgentChatPage> {
  final _inputController = TextEditingController();
  final _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    widget.chatController.addListener(_scrollToEnd);
    unawaited(widget.chatController.loadSessions());
  }

  @override
  void dispose() {
    widget.chatController.removeListener(_scrollToEnd);
    _inputController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _scrollToEnd() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scrollController.hasClients) return;
      _scrollController.animateTo(
        _scrollController.position.maxScrollExtent,
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeOut,
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.chatController,
      builder: (context, _) {
        final state = widget.chatController.state;
        final english = Localizations.localeOf(context).languageCode == 'en';
        return Scaffold(
          appBar: AppBar(
            title: Text(english ? 'Aesthetic AI' : '医美 AI'),
            actions: [
              IconButton(
                tooltip: english ? 'Profile & safety' : '档案与安全筛查',
                onPressed: () => Navigator.of(context).push<void>(
                  MaterialPageRoute(
                    builder: (_) => AgentProfileSafetyPage(
                      controller: widget.planController,
                    ),
                  ),
                ),
                icon: const Icon(Icons.health_and_safety_outlined),
              ),
              IconButton(
                tooltip: english ? 'History' : '历史会话',
                onPressed: () => _showHistory(state.sessions),
                icon: const Icon(Icons.history),
              ),
              IconButton(
                tooltip: english ? 'My plans' : '我的方案',
                onPressed: _showPlans,
                icon: const Icon(Icons.assignment_outlined),
              ),
              IconButton(
                tooltip: english ? 'New chat' : '新会话',
                onPressed: widget.chatController.startNewSession,
                icon: const Icon(Icons.add_comment_outlined),
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
                  state: state.deliveryState,
                ),
              Expanded(
                child: state.messages.isEmpty
                    ? _EmptyChat(english: english)
                    : ListView.builder(
                        controller: _scrollController,
                        padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                        itemCount: state.messages.length +
                            (state.hasOlderMessages ? 1 : 0),
                        itemBuilder: (context, index) {
                          if (state.hasOlderMessages && index == 0) {
                            return TextButton(
                              onPressed: state.deliveryState ==
                                      ChatDeliveryState.loadingHistory
                                  ? null
                                  : widget.chatController.loadOlderMessages,
                              child: Text(
                                english ? 'Load earlier messages' : '加载更早消息',
                              ),
                            );
                          }
                          final offset = state.hasOlderMessages ? 1 : 0;
                          return _ChatBubble(
                            message: state.messages[index - offset],
                          );
                        },
                      ),
              ),
              if (state.latestTurn?.catalogReport case final report?)
                AgentCatalogReportCard(
                  report: report,
                  onOpen: widget.onOpenCatalogItem,
                  onHumanChat: widget.onHumanChat,
                )
              else if (state.latestTurn?.catalogItems.isNotEmpty ?? false)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  child: AgentCatalogReferenceList(
                    items: state.latestTurn!.catalogItems,
                    onOpen: widget.onOpenCatalogItem,
                  ),
                ),
              _Composer(
                inputController: _inputController,
                isBusy: state.deliveryState.isBusy,
                isStreaming: state.deliveryState == ChatDeliveryState.streaming,
                onCancel: widget.chatController.cancelSend,
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

  Future<void> _showHistory(List<ChatSession> sessions) async {
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: sessions.isEmpty
            ? const Padding(
                padding: EdgeInsets.all(24),
                child: Text('暂无历史会话'),
              )
            : ListView(
                shrinkWrap: true,
                children: [
                  for (final session in sessions)
                    ListTile(
                      title:
                          Text(session.title.isEmpty ? '未命名会话' : session.title),
                      subtitle: Text(
                        session.lastMessage,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      onTap: () {
                        Navigator.pop(context);
                        unawaited(widget.chatController.openSession(session));
                      },
                    ),
                ],
              ),
      ),
    );
  }

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
  const _StatusBanner({required this.message, required this.state});
  final String message;
  final ChatDeliveryState state;

  @override
  Widget build(BuildContext context) => Semantics(
        liveRegion: true,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
          child: Row(
            children: [
              Icon(
                state == ChatDeliveryState.cancelled
                    ? Icons.stop_circle_outlined
                    : Icons.info_outline,
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
    required this.isStreaming,
    required this.onCancel,
    required this.onSend,
  });
  final TextEditingController inputController;
  final bool isBusy;
  final bool isStreaming;
  final Future<void> Function() onCancel;
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
                tooltip: isBusy ? '停止生成' : '发送',
                onPressed: isBusy ? onCancel : onSend,
                icon: Icon(isBusy ? Icons.stop : Icons.send),
              ),
            ],
          ),
        ),
      );
}
