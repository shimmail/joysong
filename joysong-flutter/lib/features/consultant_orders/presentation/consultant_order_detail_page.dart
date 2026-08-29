import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/core/transient_message.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_order_models.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_orders_repository.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_order_card.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_order_detail_controller.dart';
import 'package:joysong_flutter/features/consultant_orders/presentation/consultant_orders_controller.dart';

class ConsultantOrderDetailPage extends StatefulWidget {
  const ConsultantOrderDetailPage({
    required this.repository,
    required this.orderId,
    required this.onOpenServiceConversation,
    required this.onConsultantRoleRequired,
    super.key,
  });

  final ConsultantOrdersRepository repository;
  final String orderId;
  final Future<void> Function(String orderId) onOpenServiceConversation;
  final ConsultantRoleRequiredCallback onConsultantRoleRequired;

  @override
  State<ConsultantOrderDetailPage> createState() =>
      _ConsultantOrderDetailPageState();
}

class _ConsultantOrderDetailPageState
    extends State<ConsultantOrderDetailPage> {
  late ConsultantOrderDetailController _detailController;
  bool _conversationRoleRequiredHandled = false;

  @override
  void initState() {
    super.initState();
    _detailController = _createDetailController();
    unawaited(_detailController.load());
  }

  ConsultantOrderDetailController _createDetailController() =>
      ConsultantOrderDetailController(
        widget.repository,
        orderId: widget.orderId,
        onConsultantRoleRequired: widget.onConsultantRoleRequired,
      );

  @override
  void didUpdateWidget(covariant ConsultantOrderDetailPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.repository != widget.repository ||
        oldWidget.orderId != widget.orderId) {
      _detailController.dispose();
      _detailController = _createDetailController();
      _conversationRoleRequiredHandled = false;
      unawaited(_detailController.load());
    }
  }

  @override
  void dispose() {
    _detailController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _detailController,
        builder: (context, _) => Scaffold(
          appBar: AppBar(
            title: Text(context.localized('订单详情', 'Order details')),
          ),
          body: _body(context),
        ),
      );

  Widget _body(BuildContext context) => switch (_detailController.status) {
        ConsultantOrderDetailLoadStatus.idle ||
        ConsultantOrderDetailLoadStatus.loading =>
          const Center(child: CircularProgressIndicator()),
        ConsultantOrderDetailLoadStatus.failure => _DetailMessageState(
            message: _failureLabel(context, _detailController.failure),
            onRetry: _detailController.load,
          ),
        ConsultantOrderDetailLoadStatus.accessRevoked =>
          const SizedBox.shrink(),
        ConsultantOrderDetailLoadStatus.ready =>
          _detail(context, _detailController.detail!),
      };

  Widget _detail(BuildContext context, ConsultantOrderDetail detail) {
    final showConversation = detail.conversation.readable;
    final readOnly = showConversation && !detail.conversation.sendable;
    return ListView(
      key: const Key('consultant-order-detail-scroll'),
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
      children: [
        ConsultantOrderCard(summary: detail.summary),
        const SizedBox(height: 12),
        _DetailSection(
          title: context.localized('服务信息', 'Service information'),
          children: [
            _DetailRow(
              label: context.localized('医生', 'Doctor'),
              value: _valueOrFallback(
                context,
                detail.doctor.name,
                chineseFallback: '待安排',
                englishFallback: 'To be arranged',
              ),
            ),
            _DetailRow(
              label: context.localized('备注', 'Notes'),
              value: _valueOrFallback(
                context,
                detail.remark,
                chineseFallback: '无',
                englishFallback: 'None',
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        _DetailSection(
          title: context.localized('服务时间', 'Service timeline'),
          children: [
            _DetailRow(
              label: context.localized('创建时间', 'Created'),
              value: _formatDateTime(detail.createdAt),
            ),
            _DetailRow(
              label: context.localized('服务开始', 'Service activated'),
              value: _formatDateTime(detail.serviceActivatedAt),
            ),
            if (detail.completedAt != null)
              _DetailRow(
                label: context.localized('完成时间', 'Completed'),
                value: _formatDateTime(detail.completedAt!),
              ),
          ],
        ),
        if (showConversation) ...[
          const SizedBox(height: 20),
          if (readOnly) ...[
            Text(
              context.localized(
                '仅可查看历史消息',
                'History is read-only',
              ),
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 8),
          ],
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              key: const Key('consultant-order-conversation-action'),
              onPressed: _openConversation,
              child: Text(
                context.localized('订单沟通', 'Order conversation'),
                textAlign: TextAlign.center,
              ),
            ),
          ),
        ],
      ],
    );
  }

  Future<void> _openConversation() async {
    try {
      await widget.onOpenServiceConversation(widget.orderId);
      if (!mounted) return;
    } on ApiException catch (error) {
      if (error.errorCode == 'CONSULTANT_ROLE_REQUIRED') {
        if (_conversationRoleRequiredHandled) return;
        _conversationRoleRequiredHandled = true;
        await widget.onConsultantRoleRequired();
        if (!mounted) return;
        return;
      }
      if (!mounted) return;
      showTransientMessage(context, _localizedConversationFailure(context));
    } on Object {
      if (!mounted) return;
      showTransientMessage(context, _localizedConversationFailure(context));
    }
  }
}

class _DetailSection extends StatelessWidget {
  const _DetailSection({required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 10),
              ...children,
            ],
          ),
        ),
      );
}

class _DetailRow extends StatelessWidget {
  const _DetailRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 5),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: Theme.of(context).textTheme.labelMedium),
            const SizedBox(height: 2),
            Text(value),
          ],
        ),
      );
}

class _DetailMessageState extends StatelessWidget {
  const _DetailMessageState({
    required this.message,
    required this.onRetry,
  });

  final String message;
  final Future<void> Function() onRetry;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.cloud_off_outlined, size: 42),
              const SizedBox(height: 12),
              Text(message, textAlign: TextAlign.center),
              const SizedBox(height: 8),
              TextButton(
                onPressed: () => unawaited(onRetry()),
                child: Text(context.localized('重试', 'Retry')),
              ),
            ],
          ),
        ),
      );
}

String _valueOrFallback(
  BuildContext context,
  String value, {
  required String chineseFallback,
  required String englishFallback,
}) {
  final trimmed = value.trim();
  return trimmed.isEmpty
      ? context.localized(chineseFallback, englishFallback)
      : trimmed;
}

String _failureLabel(
  BuildContext context,
  ConsultantOrderFailure? failure,
) =>
    switch (failure) {
      ConsultantOrderFailure.invalidResponse => context.localized(
          '订单详情数据暂时无法显示，请重试',
          'Order details cannot be displayed. Try again.',
        ),
      ConsultantOrderFailure.unavailable => context.localized(
          '网络连接异常，请重试',
          'Network unavailable. Try again.',
        ),
      _ => context.localized(
          '订单详情加载失败，请重试',
          'Could not load order details. Try again.',
        ),
    };

String _localizedConversationFailure(BuildContext context) =>
    context.localized(
      '订单沟通暂时不可用，请稍后重试',
      'Order conversation is temporarily unavailable. Try again.',
    );

String _formatDateTime(DateTime value) =>
    '${value.year}-${value.month.toString().padLeft(2, '0')}-'
    '${value.day.toString().padLeft(2, '0')} '
    '${value.hour.toString().padLeft(2, '0')}:'
    '${value.minute.toString().padLeft(2, '0')}';
