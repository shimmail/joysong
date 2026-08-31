import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_order_models.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_orders_repository.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_order_card.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_order_detail_page.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_orders_controller.dart';

class ConsultantOrdersPage extends StatefulWidget {
  const ConsultantOrdersPage({
    required this.repository,
    required this.onOpenServiceConversation,
    required this.onConsultantRoleRequired,
    this.initialStage = ConsultantOrderStage.active,
    super.key,
  });

  final ConsultantOrdersRepository repository;
  final Future<void> Function(String orderId) onOpenServiceConversation;
  final ConsultantRoleRequiredCallback onConsultantRoleRequired;
  final ConsultantOrderStage initialStage;

  @override
  State<ConsultantOrdersPage> createState() => _ConsultantOrdersPageState();
}

class _ConsultantOrdersPageState extends State<ConsultantOrdersPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;
  late ConsultantOrdersController _ordersController;
  late int _selectedStageIndex;

  @override
  void initState() {
    super.initState();
    _ordersController = _createOrdersController();
    _selectedStageIndex = widget.initialStage.index;
    _tabController = TabController(
      length: ConsultantOrderStage.values.length,
      initialIndex: _selectedStageIndex,
      vsync: this,
    )..addListener(_handleTabChanged);
    unawaited(_ordersController.load(widget.initialStage));
  }

  ConsultantOrdersController _createOrdersController() =>
      ConsultantOrdersController(
        widget.repository,
        onConsultantRoleRequired: widget.onConsultantRoleRequired,
      );

  void _handleTabChanged() {
    if (_tabController.indexIsChanging ||
        _selectedStageIndex == _tabController.index) {
      return;
    }
    _selectedStageIndex = _tabController.index;
    unawaited(
      _ordersController.load(
        ConsultantOrderStage.values[_selectedStageIndex],
      ),
    );
  }

  @override
  void didUpdateWidget(covariant ConsultantOrdersPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.repository != widget.repository ||
        oldWidget.onConsultantRoleRequired != widget.onConsultantRoleRequired) {
      _ordersController.dispose();
      _ordersController = _createOrdersController();
      unawaited(
        _ordersController.load(
          ConsultantOrderStage.values[_selectedStageIndex],
        ),
      );
    }
  }

  @override
  void dispose() {
    _tabController
      ..removeListener(_handleTabChanged)
      ..dispose();
    _ordersController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _ordersController,
        builder: (context, _) => Scaffold(
          appBar: AppBar(
            title: Text(context.localized('顾问订单', 'Consultant orders')),
            bottom: TabBar(
              controller: _tabController,
              isScrollable: true,
              tabs: ConsultantOrderStage.values
                  .map((stage) => Tab(text: _stageLabel(context, stage)))
                  .toList(growable: false),
            ),
          ),
          body: TabBarView(
            controller: _tabController,
            children: ConsultantOrderStage.values
                .map(
                  (stage) => _ConsultantOrderStageList(
                    stage: stage,
                    state: _ordersController.stateFor(stage),
                    onRefresh: () => _ordersController.refresh(stage),
                    onLoadMore: () => _ordersController.loadMore(stage),
                    onRetryLoadMore: () =>
                        _ordersController.loadMore(stage, retry: true),
                    onRetry: () => _ordersController.load(stage, force: true),
                    onOrderSelected: (summary) => _openOrderDetail(summary.id),
                  ),
                )
                .toList(growable: false),
          ),
        ),
      );

  void _openOrderDetail(String orderId) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ConsultantOrderDetailPage(
          repository: widget.repository,
          orderId: orderId,
          onOpenServiceConversation: widget.onOpenServiceConversation,
          onConsultantRoleRequired: widget.onConsultantRoleRequired,
        ),
      ),
    );
  }
}

class _ConsultantOrderStageList extends StatelessWidget {
  const _ConsultantOrderStageList({
    required this.stage,
    required this.state,
    required this.onRefresh,
    required this.onLoadMore,
    required this.onRetryLoadMore,
    required this.onRetry,
    required this.onOrderSelected,
  });

  final ConsultantOrderStage stage;
  final ConsultantOrderListState state;
  final Future<void> Function() onRefresh;
  final Future<void> Function() onLoadMore;
  final Future<void> Function() onRetryLoadMore;
  final Future<void> Function() onRetry;
  final ValueChanged<ConsultantOrderSummary> onOrderSelected;

  @override
  Widget build(BuildContext context) => RefreshIndicator(
        onRefresh: onRefresh,
        child: NotificationListener<ScrollNotification>(
          onNotification: (notification) {
            if (notification.metrics.extentAfter < 240 &&
                state.loadMoreFailure == null) {
              unawaited(onLoadMore());
            }
            return false;
          },
          child: CustomScrollView(
            key: Key('consultant-orders-${stage.name}-list'),
            physics: const AlwaysScrollableScrollPhysics(),
            slivers: _slivers(context),
          ),
        ),
      );

  List<Widget> _slivers(BuildContext context) {
    if (state.status == ConsultantOrderListStatus.loading ||
        state.status == ConsultantOrderListStatus.idle) {
      return const [
        SliverFillRemaining(
          hasScrollBody: false,
          child: Center(child: CircularProgressIndicator()),
        ),
      ];
    }
    if (state.status == ConsultantOrderListStatus.failure) {
      return [
        SliverFillRemaining(
          hasScrollBody: false,
          child: _ListMessageState(
            icon: Icons.cloud_off_outlined,
            message: _failureLabel(context, state.failure),
            onRetry: onRetry,
          ),
        ),
      ];
    }
    if (state.status == ConsultantOrderListStatus.accessRevoked) {
      return const [
        SliverFillRemaining(
          hasScrollBody: false,
          child: SizedBox.shrink(),
        ),
      ];
    }
    if (state.items.isEmpty) {
      return [
        SliverFillRemaining(
          hasScrollBody: false,
          child: _ListMessageState(
            icon: Icons.receipt_long_outlined,
            message: _emptyLabel(context, stage),
          ),
        ),
      ];
    }
    return [
      SliverPadding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
        sliver: SliverList(
          delegate: SliverChildBuilderDelegate(
            (context, index) {
              if (index == state.items.length) {
                return _ListFooter(
                  stage: stage,
                  state: state,
                  onRetry: onRetryLoadMore,
                );
              }
              final summary = state.items[index];
              return Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: ConsultantOrderCard(
                  key: ValueKey<String>('consultant-order:${summary.id}'),
                  summary: summary,
                  onTap: () => onOrderSelected(summary),
                ),
              );
            },
            childCount: state.items.length + 1,
          ),
        ),
      ),
    ];
  }
}

class _ListFooter extends StatelessWidget {
  const _ListFooter({
    required this.stage,
    required this.state,
    required this.onRetry,
  });

  final ConsultantOrderStage stage;
  final ConsultantOrderListState state;
  final Future<void> Function() onRetry;

  @override
  Widget build(BuildContext context) {
    if (state.isLoadingMore) {
      return const Padding(
        padding: EdgeInsets.all(16),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    if (state.loadMoreFailure != null) {
      return Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Column(
          children: [
            Text(
              context.localized(
                '加载更多失败，当前订单已保留',
                'Could not load more. Current orders are preserved.',
              ),
              textAlign: TextAlign.center,
            ),
            TextButton(
              key: Key('consultant-orders-load-more-retry-${stage.name}'),
              onPressed: () => unawaited(onRetry()),
              child: Text(context.localized('重试', 'Retry')),
            ),
          ],
        ),
      );
    }
    return const SizedBox(height: 8);
  }
}

class _ListMessageState extends StatelessWidget {
  const _ListMessageState({
    required this.icon,
    required this.message,
    this.onRetry,
  });

  final IconData icon;
  final String message;
  final Future<void> Function()? onRetry;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 42),
              const SizedBox(height: 12),
              Text(message, textAlign: TextAlign.center),
              if (onRetry != null) ...[
                const SizedBox(height: 8),
                TextButton(
                  onPressed: () => unawaited(onRetry!()),
                  child: Text(context.localized('重试', 'Retry')),
                ),
              ],
            ],
          ),
        ),
      );
}

String _stageLabel(BuildContext context, ConsultantOrderStage stage) =>
    switch (stage) {
      ConsultantOrderStage.active => context.localized('服务中', 'In service'),
      ConsultantOrderStage.paused =>
        context.localized('退款处理中', 'Refund processing'),
      ConsultantOrderStage.history =>
        context.localized('历史订单', 'Order history'),
    };

String _emptyLabel(BuildContext context, ConsultantOrderStage stage) =>
    switch (stage) {
      ConsultantOrderStage.active =>
        context.localized('暂无服务中订单', 'No in-service orders'),
      ConsultantOrderStage.paused => context.localized(
          '暂无退款处理中订单',
          'No orders with refunds processing',
        ),
      ConsultantOrderStage.history =>
        context.localized('暂无历史订单', 'No order history'),
    };

String _failureLabel(
  BuildContext context,
  ConsultantOrderFailure? failure,
) =>
    switch (failure) {
      ConsultantOrderFailure.invalidResponse => context.localized(
          '订单数据暂时无法显示，请重试',
          'Order data cannot be displayed. Try again.',
        ),
      ConsultantOrderFailure.unavailable => context.localized(
          '网络连接异常，请重试',
          'Network unavailable. Try again.',
        ),
      _ => context.localized(
          '订单加载失败，请重试',
          'Could not load orders. Try again.',
        ),
    };
