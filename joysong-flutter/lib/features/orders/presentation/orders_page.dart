import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_controller.dart';

typedef OrderSelectedCallback = void Function(Order order);

class OrdersPage extends StatefulWidget {
  const OrdersPage({
    required this.controller,
    required this.onOrderSelected,
    this.onEditReview,
    this.title,
    this.initialFilter,
    this.enableAutoTranslation = false,
    super.key,
  });

  final OrdersController controller;
  final OrderSelectedCallback onOrderSelected;
  final Future<void> Function(Order order)? onEditReview;
  final String? title;
  final OrderStatus? initialFilter;
  final bool enableAutoTranslation;

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
    final duplicateIds = _duplicateOrderIds(orders);
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
        findChildIndexCallback: (key) {
          if (key is! ValueKey<String>) return null;
          const prefix = 'consumer-order:';
          if (!key.value.startsWith(prefix)) return null;
          final id = key.value.substring(prefix.length);
          final index = orders.indexWhere((order) {
            final candidate = order.id.trim();
            return candidate == id && !duplicateIds.contains(candidate);
          });
          return index < 0 ? null : index;
        },
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
          final id = order.id.trim();
          final identityStable = id.isNotEmpty && !duplicateIds.contains(id);
          return _OrderCard(
            key: identityStable
                ? ValueKey<String>('consumer-order:$id')
                : ObjectKey(order),
            order: order,
            enableAutoTranslation:
                widget.enableAutoTranslation && identityStable,
            onTap: () => widget.onOrderSelected(order),
            onEditReview: order.hasReview && widget.onEditReview != null
                ? () => widget.onEditReview!(order)
                : null,
          );
        },
      ),
    );
  }
}

Set<String> _duplicateOrderIds(Iterable<Order> orders) {
  final seen = <String>{};
  final duplicates = <String>{};
  for (final order in orders) {
    final id = order.id.trim();
    if (id.isNotEmpty && !seen.add(id)) duplicates.add(id);
  }
  return duplicates;
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
            order.refundStatus != RefundStatus.processing &&
            order.refundStatus != RefundStatus.rejected &&
            const {
              OrderStatus.pendingServiceFee,
              OrderStatus.pendingPayment,
              OrderStatus.consultationPaid,
              OrderStatus.verified,
              OrderStatus.balancePaid,
            }.contains(order.status),
        underReview => order.refundStatus == RefundStatus.pending ||
            order.refundStatus == RefundStatus.processing ||
            const {
              OrderStatus.refundReview,
              OrderStatus.refundProcessing,
            }.contains(order.status),
        rejected => order.refundStatus == RefundStatus.rejected,
        pending => order.refundStatus != RefundStatus.pending &&
            order.refundStatus != RefundStatus.processing &&
            order.refundStatus != RefundStatus.rejected &&
            const {
              OrderStatus.serviceActive,
              OrderStatus.consultationPaid,
              OrderStatus.pendingCompletion,
            }.contains(order.status),
        completed => order.refundStatus != RefundStatus.pending &&
            order.refundStatus != RefundStatus.processing &&
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
      OrderStatus.pendingServiceFee ||
      OrderStatus.verified ||
      OrderStatus.balancePaid =>
        _OrderListTab.pendingPayment,
      OrderStatus.consultationPaid ||
      OrderStatus.serviceActive ||
      OrderStatus.pendingCompletion =>
        _OrderListTab.pending,
      OrderStatus.refundReview ||
      OrderStatus.refundProcessing =>
        _OrderListTab.underReview,
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
    required this.enableAutoTranslation,
    this.onEditReview,
    super.key,
  });

  final Order order;
  final VoidCallback onTap;
  final bool enableAutoTranslation;
  final VoidCallback? onEditReview;

  @override
  Widget build(BuildContext context) {
    final showInstitutionName =
        !(order.isTravelGroundServiceOnly && !order.consultantDetailsVisible) &&
            order.institutionName.isNotEmpty;
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
                    child: showInstitutionName
                        ? StableAutoTranslatedText(
                            enabled: enableAutoTranslation,
                            contentType: 'institution',
                            contentId: 'order:${order.id.trim()}',
                            field: 'institutionName',
                            sourceText: order.institutionName,
                            style: Theme.of(context).textTheme.titleSmall,
                          )
                        : Text(
                            context.localized('娇颜颂预约', 'Joysong booking'),
                            style: Theme.of(context).textTheme.titleSmall,
                          ),
                  ),
                  Text(
                    !order.isTravelGroundServiceOnly &&
                            order.refundStatus == RefundStatus.pending
                        ? _refundStatusLabel(context, order.refundStatus)
                        : _statusLabel(context, order.status),
                    style: Theme.of(context).textTheme.labelLarge?.copyWith(
                          color: _orderStatusColor(
                            context,
                            order.status,
                            order.refundStatus,
                          ),
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
                        StableAutoTranslatedText(
                          enabled: enableAutoTranslation,
                          contentType: 'project',
                          contentId: 'order:${order.id.trim()}',
                          field: 'projectName',
                          sourceText: order.projectName,
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
                            order.isTravelGroundServiceOnly
                                ? _travelServiceFee(order)
                                : order.amount.formatted,
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
              if (onEditReview != null) ...[
                const SizedBox(height: 8),
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton.icon(
                    key: Key('edit-review-card-${order.id}'),
                    onPressed: onEditReview,
                    icon: const Icon(Icons.edit_outlined, size: 18),
                    label: Text(
                      context.localized('修改评价', 'Edit review'),
                    ),
                  ),
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
      OrderStatus.pendingServiceFee => context.localized(
          '待支付旅游地接服务费',
          'Travel ground service fee due',
        ),
      OrderStatus.serviceActive =>
        context.localized('旅游地接服务中', 'Travel ground service active'),
      OrderStatus.refundReview =>
        context.localized('退款审核中', 'Refund under review'),
      OrderStatus.refundProcessing =>
        context.localized('退款处理中', 'Refund processing'),
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

Color _orderStatusColor(
  BuildContext context,
  OrderStatus status,
  RefundStatus refundStatus,
) {
  final colors = Theme.of(context).colorScheme;
  if (refundStatus == RefundStatus.pending ||
      refundStatus == RefundStatus.processing ||
      refundStatus == RefundStatus.approved) {
    return refundStatus == RefundStatus.approved
        ? colors.onSurfaceVariant
        : colors.tertiary;
  }
  return switch (status) {
    OrderStatus.completed || OrderStatus.settled => Colors.green.shade700,
    OrderStatus.cancelled || OrderStatus.refunded => colors.onSurfaceVariant,
    OrderStatus.disputeMediation => colors.error,
    OrderStatus.refundReview || OrderStatus.refundProcessing => colors.tertiary,
    OrderStatus.pendingPayment ||
    OrderStatus.pendingServiceFee ||
    OrderStatus.verified =>
      colors.primary,
    _ => colors.secondary,
  };
}

String _refundStatusLabel(BuildContext context, RefundStatus status) =>
    switch (status) {
      RefundStatus.none => context.localized('无退款', 'No refund'),
      RefundStatus.pending => context.localized('退款中', 'Refund pending'),
      RefundStatus.processing =>
        context.localized('退款处理中', 'Refund processing'),
      RefundStatus.approved => context.localized('退款成功', 'Refunded'),
      RefundStatus.rejected => context.localized('退款驳回', 'Refund rejected'),
      RefundStatus.cancelled => context.localized('退款已取消', 'Refund cancelled'),
      RefundStatus.unknown =>
        context.localized('退款状态未知', 'Unknown refund status'),
    };

String _travelServiceFee(Order order) {
  final minor = order.travelGroundServiceFeeMinor;
  if (order.currency != 'USD' || minor == null || minor < 0) return '--';
  final digits = minor.toString().padLeft(3, '0');
  return '\$${digits.substring(0, digits.length - 2)}.'
      '${digits.substring(digits.length - 2)}';
}
