import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_controller.dart';

typedef OrderSelectedCallback = void Function(Order order);

class OrdersPage extends StatefulWidget {
  const OrdersPage({
    required this.controller,
    required this.onOrderSelected,
    this.title,
    this.initialFilter,
    super.key,
  });

  final OrdersController controller;
  final OrderSelectedCallback onOrderSelected;
  final String? title;
  final OrderStatus? initialFilter;

  @override
  State<OrdersPage> createState() => _OrdersPageState();
}

class _OrdersPageState extends State<OrdersPage> {
  final _scrollController = ScrollController();
  late _OrderListTab _selectedTab;

  @override
  void initState() {
    super.initState();
    _selectedTab = _tabForInitialStatus(widget.initialFilter);
    _scrollController.addListener(_onScroll);
    widget.controller.load();
  }

  void _onScroll() {
    if (_scrollController.position.extentAfter < 240) {
      widget.controller.loadMore();
    }
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        final controller = widget.controller;
        return Scaffold(
          appBar: AppBar(
            title: Text(widget.title ?? context.localized('我的订单', 'My orders')),
          ),
          body: Column(
            children: [
              _OrderFilters(
                selected: _selectedTab,
                onSelected: (tab) => setState(() => _selectedTab = tab),
              ),
              Expanded(child: _buildList(controller)),
            ],
          ),
        );
      },
    );
  }

  Widget _buildList(OrdersController controller) {
    if (controller.isLoading && controller.orders.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (controller.errorMessage != null && controller.orders.isEmpty) {
      return _OrdersMessageState(
        message: controller.errorMessage!,
        onRetry: controller.refresh,
      );
    }
    final orders = controller.orders
        .where((order) => _selectedTab.matches(order))
        .toList(growable: false);
    if (orders.isEmpty) {
      return _OrdersMessageState(
        message: context.localized('这里还没有订单', 'No orders yet'),
      );
    }
    return RefreshIndicator(
      onRefresh: controller.refresh,
      child: ListView.separated(
        controller: _scrollController,
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
        itemCount: orders.length + 1,
        separatorBuilder: (_, __) => const SizedBox(height: 12),
        itemBuilder: (context, index) {
          if (index == orders.length) {
            if (controller.isLoadingMore) {
              return const Padding(
                padding: EdgeInsets.all(16),
                child: Center(child: CircularProgressIndicator()),
              );
            }
            if (controller.errorMessage != null) {
              return TextButton(
                onPressed: controller.loadMore,
                child: Text(
                  context.isEnglish
                      ? '${controller.errorMessage}. Tap to retry'
                      : '${controller.errorMessage}，点击重试',
                ),
              );
            }
            return const SizedBox(height: 8);
          }
          final order = orders[index];
          return _OrderCard(
            key: ValueKey(order.id),
            order: order,
            onTap: () => widget.onOrderSelected(order),
          );
        },
      ),
    );
  }
}

class _OrderFilters extends StatelessWidget {
  const _OrderFilters({required this.selected, required this.onSelected});

  final _OrderListTab selected;
  final ValueChanged<_OrderListTab> onSelected;

  @override
  Widget build(BuildContext context) {
    const filters = _OrderListTab.values;
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return SizedBox(
      height: 48,
      child: ListView.separated(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        scrollDirection: Axis.horizontal,
        itemCount: filters.length,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (context, index) {
          final tab = filters[index];
          final isSelected = selected == tab;
          return InkWell(
            onTap: () => onSelected(tab),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  Text(
                    tab.label(context),
                    maxLines: 1,
                    softWrap: false,
                    style: theme.textTheme.labelLarge?.copyWith(
                      color: isSelected
                          ? colors.onSurface
                          : colors.onSurfaceVariant,
                      fontWeight:
                          isSelected ? FontWeight.w600 : FontWeight.normal,
                    ),
                  ),
                  const SizedBox(height: 10),
                  Container(
                    height: 2,
                    width: 28,
                    color: isSelected ? colors.onSurface : Colors.transparent,
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}

enum _OrderListTab {
  all,
  pendingPayment,
  underReview,
  rejected,
  pending,
  completed,
  cancelledOrRefunded;

  String label(BuildContext context) => switch (this) {
        all => context.localized('全部', 'All'),
        pendingPayment => context.localized('待支付', 'Pending Payment'),
        underReview => context.localized('审核中', 'Under Review'),
        rejected => context.localized('被驳回', 'Rejected'),
        pending => context.localized('待完成', 'Pending'),
        completed => context.localized('已完成', 'Completed'),
        cancelledOrRefunded =>
          context.localized('已取消/退款', 'Cancelled/Refunded'),
      };

  bool matches(Order order) => switch (this) {
        all => true,
        pendingPayment => order.refundStatus != RefundStatus.pending &&
            order.refundStatus != RefundStatus.rejected &&
            const {
              OrderStatus.pendingPayment,
              OrderStatus.consultationPaid,
              OrderStatus.verified,
              OrderStatus.balancePaid,
            }.contains(order.status),
        underReview => order.refundStatus == RefundStatus.pending,
        rejected => order.refundStatus == RefundStatus.rejected,
        pending => order.refundStatus != RefundStatus.pending &&
            order.refundStatus != RefundStatus.rejected &&
            const {
              OrderStatus.consultationPaid,
              OrderStatus.pendingCompletion,
            }.contains(order.status),
        completed => order.refundStatus != RefundStatus.pending &&
            const {
              OrderStatus.completed,
              OrderStatus.pendingSettlement,
              OrderStatus.settled,
            }.contains(order.status),
        cancelledOrRefunded => order.refundStatus != RefundStatus.rejected &&
            const {
              OrderStatus.cancelled,
              OrderStatus.refunded,
              OrderStatus.disputeMediation,
            }.contains(order.status),
      };
}

_OrderListTab _tabForInitialStatus(OrderStatus? status) => switch (status) {
      OrderStatus.completed ||
      OrderStatus.pendingSettlement ||
      OrderStatus.settled =>
        _OrderListTab.completed,
      OrderStatus.pendingPayment ||
      OrderStatus.verified ||
      OrderStatus.balancePaid =>
        _OrderListTab.pendingPayment,
      OrderStatus.consultationPaid ||
      OrderStatus.pendingCompletion =>
        _OrderListTab.pending,
      OrderStatus.cancelled ||
      OrderStatus.refunded ||
      OrderStatus.disputeMediation =>
        _OrderListTab.cancelledOrRefunded,
      _ => _OrderListTab.all,
    };

class _OrderCard extends StatelessWidget {
  const _OrderCard({
    required this.order,
    required this.onTap,
    super.key,
  });

  final Order order;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(18),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      order.institutionName.isEmpty
                          ? context.localized('娇颜颂预约', 'Joysong booking')
                          : order.institutionName,
                      style: Theme.of(context).textTheme.titleSmall,
                    ),
                  ),
                  Text(
                    order.refundStatus == RefundStatus.pending
                        ? _refundStatusLabel(context, order.refundStatus)
                        : _statusLabel(context, order.status),
                    style: Theme.of(context).textTheme.labelLarge?.copyWith(
                          color: Theme.of(context).colorScheme.primary,
                        ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(10),
                    child: SizedBox.square(
                      dimension: 68,
                      child: order.coverImage.isEmpty
                          ? ColoredBox(
                              color: Theme.of(context)
                                  .colorScheme
                                  .surfaceContainer,
                              child: const Icon(Icons.spa_outlined),
                            )
                          : Image.network(
                              order.coverImage,
                              fit: BoxFit.cover,
                              errorBuilder: (_, __, ___) =>
                                  const Icon(Icons.spa_outlined),
                            ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          order.projectName,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                        if (order.doctorName.isNotEmpty) ...[
                          const SizedBox(height: 5),
                          Text(
                            context.isEnglish
                                ? 'Dr. ${order.doctorName}'
                                : '${order.doctorName} 医生',
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ],
                        const SizedBox(height: 8),
                        Align(
                          alignment: Alignment.centerRight,
                          child: Text(
                            order.amount.formatted,
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              if (order.appointmentTime != null) ...[
                const SizedBox(height: 10),
                Text(
                  '${context.localized('预约时间：', 'Appointment: ')}${_formatOrderDateTime(order.appointmentTime!)}',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _OrdersMessageState extends StatelessWidget {
  const _OrdersMessageState({required this.message, this.onRetry});

  final String message;
  final Future<void> Function()? onRetry;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.receipt_long_outlined, size: 42),
              const SizedBox(height: 12),
              Text(message, textAlign: TextAlign.center),
              if (onRetry != null) ...[
                const SizedBox(height: 8),
                TextButton(
                  onPressed: onRetry,
                  child: Text(context.localized('重试', 'Retry')),
                ),
              ],
            ],
          ),
        ),
      );
}

String _formatOrderDateTime(DateTime value) =>
    '${value.year}-${value.month.toString().padLeft(2, '0')}-'
    '${value.day.toString().padLeft(2, '0')} '
    '${value.hour.toString().padLeft(2, '0')}:'
    '${value.minute.toString().padLeft(2, '0')}';

String _statusLabel(BuildContext context, OrderStatus status) =>
    switch (status) {
      OrderStatus.pendingPayment => context.localized('待付款', 'Pending payment'),
      OrderStatus.consultationPaid => context.localized('已付定金', 'Deposit paid'),
      OrderStatus.verified => context.localized('已核销', 'Verified'),
      OrderStatus.balancePaid => context.localized('已支付尾款', 'Balance paid'),
      OrderStatus.pendingCompletion =>
        context.localized('待完成', 'Pending completion'),
      OrderStatus.completed => context.localized('已完成', 'Completed'),
      OrderStatus.pendingSettlement =>
        context.localized('待结算', 'Pending settlement'),
      OrderStatus.settled => context.localized('已结算', 'Settled'),
      OrderStatus.disputeMediation =>
        context.localized('纠纷调解中', 'Dispute mediation'),
      OrderStatus.cancelled => context.localized('已取消', 'Cancelled'),
      OrderStatus.refunded => context.localized('已退款', 'Refunded'),
      OrderStatus.unknown => context.localized('未知状态', 'Unknown status'),
    };

String _refundStatusLabel(BuildContext context, RefundStatus status) =>
    switch (status) {
      RefundStatus.none => context.localized('无退款', 'No refund'),
      RefundStatus.pending => context.localized('退款中', 'Refund pending'),
      RefundStatus.approved => context.localized('退款成功', 'Refunded'),
      RefundStatus.rejected => context.localized('退款驳回', 'Refund rejected'),
      RefundStatus.cancelled => context.localized('退款已取消', 'Refund cancelled'),
      RefundStatus.unknown =>
        context.localized('退款状态未知', 'Unknown refund status'),
    };
