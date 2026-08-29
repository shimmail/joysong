import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/optimized_network_image.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_order_models.dart';

class ConsultantOrderCard extends StatelessWidget {
  const ConsultantOrderCard({
    required this.summary,
    this.onTap,
    super.key,
  });

  final ConsultantOrderSummary summary;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final content = Padding(
      padding: const EdgeInsets.all(14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ProjectCover(url: summary.project.coverImage.trim()),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  summary.project.name,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 8),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    _CustomerAvatar(customer: summary.customer),
                    const SizedBox(width: 8),
                    Expanded(child: Text(summary.customer.displayName)),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  summary.institution.name,
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
                const SizedBox(height: 6),
                Text(
                  _appointmentLabel(context, summary.appointmentTime),
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 8),
                Text(
                  _serviceStateLabel(context, summary),
                  style: Theme.of(context).textTheme.labelLarge?.copyWith(
                        color: Theme.of(context).colorScheme.primary,
                        fontWeight: FontWeight.w600,
                      ),
                ),
              ],
            ),
          ),
        ],
      ),
    );

    return Card(
      clipBehavior: Clip.antiAlias,
      child: onTap == null
          ? content
          : InkWell(
              onTap: onTap,
              child: content,
            ),
    );
  }
}

class _ProjectCover extends StatelessWidget {
  const _ProjectCover({required this.url});

  final String url;

  @override
  Widget build(BuildContext context) => ClipRRect(
        borderRadius: BorderRadius.circular(10),
        child: url.isEmpty
            ? const _ProjectCoverPlaceholder()
            : OptimizedNetworkImage(
                url: url,
                width: 72,
                height: 72,
                errorBuilder: (_, __, ___) =>
                    const _ProjectCoverPlaceholder(),
              ),
      );
}

class _ProjectCoverPlaceholder extends StatelessWidget {
  const _ProjectCoverPlaceholder();

  @override
  Widget build(BuildContext context) => SizedBox.square(
        dimension: 72,
        child: ColoredBox(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          child: const Center(child: Icon(Icons.spa_outlined)),
        ),
      );
}

class _CustomerAvatar extends StatelessWidget {
  const _CustomerAvatar({required this.customer});

  final ConsultantOrderCustomer customer;

  @override
  Widget build(BuildContext context) {
    final avatar = customer.avatar?.trim() ?? '';
    return CircleAvatar(
      radius: 18,
      foregroundImage: avatar.isEmpty ? null : NetworkImage(avatar),
      onForegroundImageError: avatar.isEmpty ? null : (_, __) {},
      child: Text(_firstCharacter(customer.displayName)),
    );
  }
}

String _firstCharacter(String value) {
  final trimmed = value.trim();
  if (trimmed.isEmpty) return '?';
  return String.fromCharCode(trimmed.runes.first);
}

String _appointmentLabel(BuildContext context, DateTime? appointmentTime) {
  if (appointmentTime == null) {
    return context.localized('预约时间：待安排', 'Appointment: To be arranged');
  }
  return '${context.localized('预约时间：', 'Appointment: ')}'
      '${_formatDateTime(appointmentTime)}';
}

String _serviceStateLabel(
  BuildContext context,
  ConsultantOrderSummary summary,
) =>
    switch (summary.status) {
    'SERVICE_ACTIVE' || 'PENDING_COMPLETION' =>
      context.localized('服务中', 'In service'),
    'REFUND_REVIEW' => context.localized('退款审核中', 'Refund under review'),
    'REFUND_PROCESSING' =>
      context.localized('退款处理中', 'Refund processing'),
    'COMPLETED' || 'PENDING_SETTLEMENT' || 'SETTLED' =>
      context.localized('已完成', 'Completed'),
    'CANCELLED' => context.localized('已取消', 'Cancelled'),
    'REFUNDED' => context.localized('已退款', 'Refunded'),
    _ => switch (summary.stage) {
        ConsultantOrderStage.active =>
          context.localized('服务处理中', 'Service in progress'),
        ConsultantOrderStage.paused =>
          context.localized('退款处理中', 'Refund processing'),
        ConsultantOrderStage.history =>
          context.localized('服务已结束', 'Service ended'),
      },
  };

String _formatDateTime(DateTime value) =>
    '${value.year}-${value.month.toString().padLeft(2, '0')}-'
    '${value.day.toString().padLeft(2, '0')} '
    '${value.hour.toString().padLeft(2, '0')}:'
    '${value.minute.toString().padLeft(2, '0')}';
