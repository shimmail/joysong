import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/files/app_file_picker.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_controller.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';

class OrderDetailPage extends StatefulWidget {
  const OrderDetailPage({
    required this.controller,
    this.socialController,
    this.filePicker = const AppFilePicker(),
    this.pickImage,
    this.uploadImage,
    this.onOrderRemoved,
    super.key,
  });

  final OrderDetailController controller;
  final SocialController? socialController;
  final AppFilePicker filePicker;
  final Future<AppPickedFile?> Function()? pickImage;
  final Future<String?> Function(PublicMediaPurpose purpose)? uploadImage;
  final VoidCallback? onOrderRemoved;

  @override
  State<OrderDetailPage> createState() => _OrderDetailPageState();
}

class _OrderDetailPageState extends State<OrderDetailPage> {
  @override
  void initState() {
    super.initState();
    widget.controller.load();
  }

  Future<void> _run(
    Future<bool> Function() action, {
    String? confirmation,
    bool closesAfterSuccess = false,
  }) async {
    if (confirmation != null) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('请确认'),
          content: Text(confirmation),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('暂不'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('确认'),
            ),
          ],
        ),
      );
      if (confirmed != true || !mounted) return;
    }
    final success = await action();
    if (success && mounted && closesAfterSuccess) {
      widget.onOrderRemoved?.call();
    }
  }

  Future<void> _requestRefund() async {
    final draft = await Navigator.of(context).push<OrderRefundDraft>(
      MaterialPageRoute(
        builder: (_) => RefundApplyPage(
          order: widget.controller.order!,
          canUploadEvidence: widget.socialController != null,
          onPickEvidence: () => _pickAndUpload(PublicMediaPurpose.review),
        ),
      ),
    );
    if (draft != null && mounted) {
      await widget.controller.requestRefund(
        reason: draft.reason,
        description: draft.description,
        evidenceUrl: draft.evidenceUrl,
      );
    }
  }

  Future<String?> _pickAndUpload(PublicMediaPurpose purpose) async {
    final uploadImage = widget.uploadImage;
    if (uploadImage != null) return uploadImage(purpose);
    final socialController = widget.socialController;
    if (socialController == null) return null;
    try {
      final selected =
          await (widget.pickImage?.call() ?? widget.filePicker.pickImage());
      if (selected == null) return null;
      final result = await socialController.uploadPublicMedia(
        PublicMediaDraft(
          bytes: selected.bytes,
          fileName: selected.fileName,
          mimeType: selected.mimeType,
          purpose: purpose,
        ),
      );
      if (result.succeeded) return result.value;
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              result.message ??
                  (_isEnglish(context) ? 'Image upload failed' : '图片上传失败'),
            ),
          ),
        );
      }
    } on Object catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(error.toString())),
        );
      }
    }
    return null;
  }

  Future<void> _submitReview() async {
    final socialController = widget.socialController;
    final order = widget.controller.order;
    if (socialController == null || order == null) return;
    final draft = await Navigator.of(context).push<ReviewDraft>(
      MaterialPageRoute(
        builder: (_) => ReviewOrderPage(
          order: order,
          onPickImage: () => _pickAndUpload(PublicMediaPurpose.review),
        ),
      ),
    );
    if (draft == null || !mounted) return;
    final result = await socialController.submitOrderReview(order.id, draft);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          result.succeeded
              ? (_isEnglish(context) ? 'Review submitted' : '评价已提交')
              : (result.message ??
                  (_isEnglish(context)
                      ? 'Review submission failed'
                      : '评价提交失败')),
        ),
      ),
    );
    if (result.succeeded) await widget.controller.load();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        final controller = widget.controller;
        return Scaffold(
          appBar: AppBar(
            title: Text(_isEnglish(context) ? 'Order details' : '订单详情'),
          ),
          body: _buildBody(controller),
        );
      },
    );
  }

  Widget _buildBody(OrderDetailController controller) {
    if (controller.isLoading && controller.order == null) {
      return const Center(child: CircularProgressIndicator());
    }
    final order = controller.order;
    if (order == null) {
      return _DetailMessageState(
        message: controller.errorMessage ?? '订单不存在',
        onRetry: controller.load,
      );
    }
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
      children: [
        if (controller.errorMessage != null)
          _ErrorBanner(message: controller.errorMessage!),
        _StatusHeader(order: order),
        const SizedBox(height: 12),
        _OrderInformation(order: order),
        const SizedBox(height: 12),
        _PaymentInformation(order: order),
        if (order.verifyCode != null &&
            (order.status == OrderStatus.consultationPaid ||
                order.status == OrderStatus.balancePaid)) ...[
          const SizedBox(height: 12),
          _VerificationCodeCard(
            code: order.verifyCode!,
            isCompletionCode: order.status == OrderStatus.balancePaid,
          ),
        ],
        if (controller.refund != null) ...[
          const SizedBox(height: 12),
          _RefundCard(refund: controller.refund!),
        ],
        if (controller.settlement != null) ...[
          const SizedBox(height: 12),
          _SettlementCard(settlement: controller.settlement!),
        ],
        if (controller.statusLogs.isNotEmpty) ...[
          const SizedBox(height: 20),
          Text(_isEnglish(context) ? 'Order progress' : '订单进度',
              style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          _StatusTimeline(logs: controller.statusLogs),
        ],
        const SizedBox(height: 20),
        _OrderActions(
          controller: controller,
          order: order,
          onPayConsultation: () => _openPayment(order, balance: false),
          onVerificationCode: () => _run(controller.requestVerificationCode),
          onPayBalance: () => _openPayment(order, balance: true),
          onConfirmCompletion: () => _run(
            controller.confirmCompletion,
            confirmation: '请确认项目服务已经全部完成。确认后将进入完成与结算流程。',
          ),
          onCancel: () => _run(
            controller.cancel,
            confirmation: '待支付订单取消后会从列表移除，确定继续吗？',
            closesAfterSuccess: true,
          ),
          onRefund: _requestRefund,
          onReview: _submitReview,
          canReview: widget.socialController != null &&
              order.status == OrderStatus.completed &&
              !order.hasReview,
          onCancelRefund: () => _run(
            controller.cancelRefund,
            confirmation: '确定撤销当前退款申请吗？',
          ),
          onDelete: () => _run(
            controller.delete,
            confirmation: '删除后订单将不再显示，确定继续吗？',
            closesAfterSuccess: true,
          ),
        ),
      ],
    );
  }

  Future<void> _openPayment(Order order, {required bool balance}) async {
    final paid = await Navigator.of(context).push<bool>(MaterialPageRoute(
      builder: (_) => OrderPaymentPage(
        order: order,
        balance: balance,
        onPay: balance
            ? widget.controller.payBalance
            : widget.controller.payConsultation,
      ),
    ));
    if (paid == true && mounted) await widget.controller.load();
  }
}

class _StatusHeader extends StatelessWidget {
  const _StatusHeader({required this.order});

  final Order order;

  @override
  Widget build(BuildContext context) {
    final statusLabel = order.refundStatus == RefundStatus.pending
        ? _refundStatusText(context, order.refundStatus)
        : _orderStatusText(context, order.status);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Row(
          children: [
            Icon(
              Icons.receipt_long_outlined,
              color: Theme.of(context).colorScheme.primary,
              size: 30,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(statusLabel,
                      style: Theme.of(context).textTheme.titleLarge),
                  const SizedBox(height: 3),
                  Text(
                    order.orderNo.isEmpty
                        ? order.id
                        : '${_isEnglish(context) ? 'Order No.' : '订单号'} ${order.orderNo}',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _OrderInformation extends StatelessWidget {
  const _OrderInformation({required this.order});

  final Order order;

  @override
  Widget build(BuildContext context) => _DetailPanel(
        title: '预约信息',
        children: [
          _DetailLine(label: '项目', value: order.projectName),
          if (order.institutionName.isNotEmpty)
            _DetailLine(label: '机构', value: order.institutionName),
          if (order.doctorName.isNotEmpty)
            _DetailLine(label: '医生', value: order.doctorName),
          if (order.appointmentTime != null)
            _DetailLine(
              label: '预约时间',
              value: _detailDateTime(order.appointmentTime!),
            ),
          if (order.remark.isNotEmpty)
            _DetailLine(label: '备注', value: order.remark),
        ],
      );
}

class _PaymentInformation extends StatelessWidget {
  const _PaymentInformation({required this.order});

  final Order order;

  @override
  Widget build(BuildContext context) => _DetailPanel(
        title: '费用信息',
        children: [
          _DetailLine(label: '订单金额', value: order.amount.formatted),
          if (!order.discountAmount.isZero)
            _DetailLine(
                label: '优惠金额', value: '-${order.discountAmount.formatted}'),
          _DetailLine(label: '面诊费', value: order.consultationFee.formatted),
          _DetailLine(label: '尾款', value: order.remainingAmount.formatted),
          _DetailLine(label: '已支付', value: order.paidAmount.formatted),
          const SizedBox(height: 6),
          Text(
            '支付接口当前仅用于内部流程占位，不代表微信、支付宝等渠道已真实到账。',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
        ],
      );
}

class _VerificationCodeCard extends StatelessWidget {
  const _VerificationCodeCard({
    required this.code,
    required this.isCompletionCode,
  });

  final String code;
  final bool isCompletionCode;

  @override
  Widget build(BuildContext context) => Card(
        color: Theme.of(context).colorScheme.primaryContainer,
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            children: [
              Text(isCompletionCode
                  ? (_isEnglish(context)
                      ? 'Service completion code'
                      : '服务完成核销码')
                  : (_isEnglish(context)
                      ? 'First visit verification code'
                      : '首次到店核销码')),
              const SizedBox(height: 10),
              SelectionArea(
                child: Text(
                  code,
                  key: const Key('order-verification-code'),
                  style: Theme.of(context).textTheme.headlineLarge?.copyWith(
                        letterSpacing: 8,
                      ),
                ),
              ),
              const SizedBox(height: 8),
              Text(
                _isEnglish(context)
                    ? 'Show this code only to the doctor or institution staff on site.'
                    : '请仅向现场医生或机构人员展示。用户端不会自行核销。',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ),
      );
}

class _RefundCard extends StatelessWidget {
  const _RefundCard({required this.refund});

  final RefundDetail refund;

  @override
  Widget build(BuildContext context) => _DetailPanel(
        title: '退款信息',
        children: [
          _DetailLine(
            label: _isEnglish(context) ? 'Status' : '状态',
            value: _refundStatusText(context, refund.status),
          ),
          _DetailLine(label: '金额', value: refund.amount.formatted),
          _DetailLine(label: '原因', value: refund.reason),
          if (refund.description.isNotEmpty)
            _DetailLine(label: '说明', value: refund.description),
        ],
      );
}

class _SettlementCard extends StatelessWidget {
  const _SettlementCard({required this.settlement});

  final Settlement settlement;

  @override
  Widget build(BuildContext context) => _DetailPanel(
        title: '结算进度',
        children: [
          _DetailLine(label: '状态', value: settlement.status),
          _DetailLine(label: '订单总额', value: settlement.totalAmount.formatted),
          if (settlement.settledAt != null)
            _DetailLine(
              label: '结算时间',
              value: _detailDateTime(settlement.settledAt!),
            ),
        ],
      );
}

class _StatusTimeline extends StatelessWidget {
  const _StatusTimeline({required this.logs});

  final List<OrderStatusLog> logs;

  @override
  Widget build(BuildContext context) => Column(
        children: [
          for (final log in logs)
            ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.circle, size: 10),
              title: Text(_orderStatusText(context, log.toStatus)),
              subtitle: Text(
                '${_detailDateTime(log.createdAt)}'
                '${log.remark.isEmpty ? '' : ' · ${log.remark}'}',
              ),
            ),
        ],
      );
}

class _OrderActions extends StatelessWidget {
  const _OrderActions({
    required this.controller,
    required this.order,
    required this.onPayConsultation,
    required this.onVerificationCode,
    required this.onPayBalance,
    required this.onConfirmCompletion,
    required this.onCancel,
    required this.onRefund,
    required this.onReview,
    required this.canReview,
    required this.onCancelRefund,
    required this.onDelete,
  });

  final OrderDetailController controller;
  final Order order;
  final VoidCallback onPayConsultation;
  final VoidCallback onVerificationCode;
  final VoidCallback onPayBalance;
  final VoidCallback onConfirmCompletion;
  final VoidCallback onCancel;
  final VoidCallback onRefund;
  final VoidCallback onReview;
  final bool canReview;
  final VoidCallback onCancelRefund;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final busy = controller.isBusy;
    return Wrap(
      spacing: 10,
      runSpacing: 10,
      alignment: WrapAlignment.end,
      children: [
        if (order.canCancel)
          TextButton(
            key: const Key('order-cancel-button'),
            onPressed: busy ? null : onCancel,
            child: Text(_isEnglish(context) ? 'Cancel order' : '取消订单'),
          ),
        if (order.canPayConsultation)
          FilledButton(
            key: const Key('pay-consultation-button'),
            onPressed: busy ? null : onPayConsultation,
            child: Text(_isEnglish(context) ? 'Pay consultation fee' : '支付面诊费'),
          ),
        if (order.canRequestVerificationCode)
          FilledButton.tonal(
            key: const Key('verification-code-button'),
            onPressed: busy ? null : onVerificationCode,
            child: Text(order.verifyCode == null
                ? (_isEnglish(context) ? 'Get verification code' : '获取到店核销码')
                : (_isEnglish(context) ? 'Refresh code' : '刷新核销码')),
          ),
        if (order.canPayBalance)
          FilledButton(
            key: const Key('pay-balance-button'),
            onPressed: busy ? null : onPayBalance,
            child: Text(_isEnglish(context) ? 'Pay balance' : '支付尾款'),
          ),
        if (order.canConfirmCompletion)
          FilledButton(
            key: const Key('confirm-completion-button'),
            onPressed: busy ? null : onConfirmCompletion,
            child: Text(_isEnglish(context) ? 'Confirm completion' : '确认服务完成'),
          ),
        if (order.canRequestRefund)
          TextButton(
            key: const Key('request-refund-button'),
            onPressed: busy ? null : onRefund,
            child: Text(_isEnglish(context) ? 'Request refund' : '申请退款'),
          ),
        if (order.canCancelRefund)
          TextButton(
            key: const Key('cancel-refund-button'),
            onPressed: busy ? null : onCancelRefund,
            child: Text(_isEnglish(context) ? 'Cancel refund' : '撤销退款'),
          ),
        if (canReview)
          FilledButton.tonal(
            key: const Key('submit-review-button'),
            onPressed: busy ? null : onReview,
            child: Text(_isEnglish(context) ? 'Write a review' : '去评价'),
          ),
        if (order.canDelete)
          TextButton(
            key: const Key('delete-order-button'),
            onPressed: busy ? null : onDelete,
            child: Text(_isEnglish(context) ? 'Delete order' : '删除订单'),
          ),
        if (busy)
          const SizedBox.square(
            dimension: 22,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
      ],
    );
  }
}

class _DetailPanel extends StatelessWidget {
  const _DetailPanel({required this.title, required this.children});

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
              const SizedBox(height: 12),
              ...children,
            ],
          ),
        ),
      );
}

class _DetailLine extends StatelessWidget {
  const _DetailLine({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 76,
              child: Text(
                label,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ),
            Expanded(child: Text(value)),
          ],
        ),
      );
}

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Text(
          message,
          style: TextStyle(color: Theme.of(context).colorScheme.error),
        ),
      );
}

class _DetailMessageState extends StatelessWidget {
  const _DetailMessageState({required this.message, required this.onRetry});

  final String message;
  final Future<void> Function() onRetry;

  @override
  Widget build(BuildContext context) => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(message),
            const SizedBox(height: 8),
            TextButton(onPressed: onRetry, child: const Text('重试')),
          ],
        ),
      );
}

String _detailDateTime(DateTime value) =>
    '${value.year}-${value.month.toString().padLeft(2, '0')}-'
    '${value.day.toString().padLeft(2, '0')} '
    '${value.hour.toString().padLeft(2, '0')}:'
    '${value.minute.toString().padLeft(2, '0')}';

String _orderStatusText(BuildContext context, OrderStatus status) {
  if (!_isEnglish(context)) return status.label;
  return switch (status) {
    OrderStatus.pendingPayment => 'Consultation fee due',
    OrderStatus.consultationPaid => 'Awaiting visit',
    OrderStatus.verified => 'Balance due',
    OrderStatus.balancePaid => 'Awaiting completion',
    OrderStatus.pendingCompletion => 'Confirm completion',
    OrderStatus.completed => 'Completed',
    OrderStatus.pendingSettlement => 'Settlement pending',
    OrderStatus.settled => 'Settled',
    OrderStatus.disputeMediation => 'Dispute mediation',
    OrderStatus.cancelled => 'Cancelled',
    OrderStatus.refunded => 'Refunded',
    OrderStatus.unknown => 'Unknown',
  };
}

String _refundStatusText(BuildContext context, RefundStatus status) {
  if (!_isEnglish(context)) return status.label;
  return switch (status) {
    RefundStatus.none => 'Not requested',
    RefundStatus.pending => 'Under review',
    RefundStatus.approved => 'Approved',
    RefundStatus.rejected => 'Rejected',
    RefundStatus.cancelled => 'Cancelled',
    RefundStatus.unknown => 'Unknown',
  };
}

bool _isEnglish(BuildContext context) =>
    Localizations.localeOf(context).languageCode == 'en';

class OrderPaymentPage extends StatefulWidget {
  const OrderPaymentPage({
    required this.order,
    required this.balance,
    required this.onPay,
    super.key,
  });

  final Order order;
  final bool balance;
  final Future<bool> Function() onPay;

  @override
  State<OrderPaymentPage> createState() => _OrderPaymentPageState();
}

class _OrderPaymentPageState extends State<OrderPaymentPage> {
  bool _paying = false;
  bool _succeeded = false;

  Future<void> _pay() async {
    setState(() => _paying = true);
    final success = await widget.onPay();
    if (!mounted) return;
    setState(() {
      _paying = false;
      _succeeded = success;
    });
  }

  @override
  Widget build(BuildContext context) {
    final english = _isEnglish(context);
    final amount = widget.balance
        ? widget.order.remainingAmount
        : widget.order.consultationFee;
    if (_succeeded) {
      return Scaffold(
        appBar: AppBar(
          automaticallyImplyLeading: false,
          title: Text(english ? 'Payment result' : '支付结果'),
        ),
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(32),
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              Icon(Icons.check_circle,
                  size: 76, color: Theme.of(context).colorScheme.primary),
              const SizedBox(height: 18),
              Text(english ? 'Payment completed' : '支付完成',
                  style: Theme.of(context).textTheme.headlineSmall),
              const SizedBox(height: 8),
              Text(english
                  ? 'The order status has been updated.'
                  : '订单状态已经更新，请按预约时间到店。'),
              const SizedBox(height: 24),
              FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: Text(english ? 'Back to order' : '返回订单'),
              ),
            ]),
          ),
        ),
      );
    }
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.balance
            ? (english ? 'Pay balance' : '支付尾款')
            : (english ? 'Pay consultation fee' : '支付面诊金')),
      ),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          _FlowOrderSummary(order: widget.order),
          const SizedBox(height: 18),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(children: [
                Text(english ? 'Amount due' : '应付金额'),
                const SizedBox(height: 8),
                Text(amount.formatted,
                    style: Theme.of(context).textTheme.headlineMedium),
              ]),
            ),
          ),
          const SizedBox(height: 12),
          ListTile(
            leading: const Icon(Icons.shield_outlined),
            title: Text(english ? 'Payment notice' : '支付说明'),
            subtitle: Text(english
                ? 'The current endpoint is a workflow placeholder and does not represent a real third-party charge.'
                : '当前后端支付接口为流程占位，不代表微信、支付宝等第三方渠道已经真实扣款。'),
          ),
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: FilledButton(
            onPressed: _paying ? null : _pay,
            child: _paying
                ? const SizedBox.square(
                    dimension: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : Text('${english ? 'Pay' : '确认支付'} ${amount.formatted}'),
          ),
        ),
      ),
    );
  }
}

class _FlowOrderSummary extends StatelessWidget {
  const _FlowOrderSummary({required this.order});
  final Order order;

  @override
  Widget build(BuildContext context) => Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(10),
              child: SizedBox.square(
                dimension: 64,
                child: order.coverImage.isEmpty
                    ? const Icon(Icons.spa_outlined)
                    : Image.network(
                        order.coverImage,
                        fit: BoxFit.cover,
                        errorBuilder: (_, __, ___) =>
                            const Icon(Icons.broken_image_outlined),
                      ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(order.projectName,
                      style: Theme.of(context).textTheme.titleMedium),
                  if (order.institutionName.isNotEmpty)
                    Text(order.institutionName),
                  if (order.orderNo.isNotEmpty)
                    Text(order.orderNo,
                        style: Theme.of(context).textTheme.bodySmall),
                ],
              ),
            ),
          ]),
        ),
      );
}

final class OrderRefundDraft {
  const OrderRefundDraft({
    required this.reason,
    required this.description,
    required this.evidenceUrl,
  });

  final String reason;
  final String description;
  final String evidenceUrl;
}

class RefundApplyPage extends StatefulWidget {
  const RefundApplyPage({
    required this.order,
    required this.canUploadEvidence,
    required this.onPickEvidence,
    super.key,
  });

  final Order order;
  final bool canUploadEvidence;
  final Future<String?> Function() onPickEvidence;

  @override
  State<RefundApplyPage> createState() => _RefundApplyPageState();
}

class _RefundApplyPageState extends State<RefundApplyPage> {
  final _reason = TextEditingController();
  final _description = TextEditingController();
  String _evidenceUrl = '';
  bool _uploading = false;

  @override
  void dispose() {
    _reason.dispose();
    _description.dispose();
    super.dispose();
  }

  Future<void> _pickEvidence() async {
    setState(() => _uploading = true);
    final url = await widget.onPickEvidence();
    if (!mounted) return;
    setState(() {
      _uploading = false;
      if (url != null) _evidenceUrl = url;
    });
  }

  @override
  Widget build(BuildContext context) {
    final english = _isEnglish(context);
    return Scaffold(
      appBar: AppBar(title: Text(english ? 'Request a refund' : '申请退款')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          _FlowOrderSummary(order: widget.order),
          const SizedBox(height: 20),
          TextField(
            key: const Key('refund-reason-field'),
            controller: _reason,
            onChanged: (_) => setState(() {}),
            maxLength: 100,
            decoration: InputDecoration(
              labelText: english ? 'Refund reason (required)' : '退款原因（必填）',
            ),
          ),
          const SizedBox(height: 10),
          TextField(
            controller: _description,
            maxLines: 3,
            maxLength: 500,
            decoration: InputDecoration(
              labelText: english ? 'Additional details' : '补充说明',
            ),
          ),
          if (widget.canUploadEvidence)
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: _uploading
                  ? const SizedBox.square(
                      dimension: 22,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.add_photo_alternate_outlined),
              title: Text(
                _evidenceUrl.isEmpty
                    ? (english ? 'Add optional evidence' : '添加可选凭证')
                    : (english ? 'Evidence attached' : '已添加凭证'),
              ),
              onTap: _uploading ? null : _pickEvidence,
            ),
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: FilledButton(
            key: const Key('refund-submit-button'),
            onPressed: _uploading || _reason.text.trim().isEmpty
                ? null
                : () => Navigator.pop(
                      context,
                      OrderRefundDraft(
                        reason: _reason.text.trim(),
                        description: _description.text.trim(),
                        evidenceUrl: _evidenceUrl,
                      ),
                    ),
            child: Text(english ? 'Submit' : '提交申请'),
          ),
        ),
      ),
    );
  }
}

class ReviewOrderPage extends StatefulWidget {
  const ReviewOrderPage({
    required this.order,
    required this.onPickImage,
    super.key,
  });

  final Order order;
  final Future<String?> Function() onPickImage;

  @override
  State<ReviewOrderPage> createState() => _ReviewOrderPageState();
}

class _ReviewOrderPageState extends State<ReviewOrderPage> {
  final _content = TextEditingController();
  final _tags = TextEditingController();
  int _rating = 5;
  String _imageUrl = '';
  bool _uploading = false;

  @override
  void dispose() {
    _content.dispose();
    _tags.dispose();
    super.dispose();
  }

  Future<void> _pickImage() async {
    setState(() => _uploading = true);
    final url = await widget.onPickImage();
    if (!mounted) return;
    setState(() {
      _uploading = false;
      if (url != null) _imageUrl = url;
    });
  }

  @override
  Widget build(BuildContext context) {
    final english = _isEnglish(context);
    return Scaffold(
      appBar: AppBar(title: Text(english ? 'Write a review' : '发表评价')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          _FlowOrderSummary(order: widget.order),
          const SizedBox(height: 20),
          Text(english ? 'Rating' : '评分'),
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (var value = 1; value <= 5; value++)
                IconButton(
                  key: Key('review-rating-$value'),
                  tooltip: '$value',
                  onPressed: () => setState(() => _rating = value),
                  icon: Icon(
                    value <= _rating ? Icons.star : Icons.star_border,
                  ),
                ),
            ],
          ),
          TextField(
            key: const Key('review-content-field'),
            controller: _content,
            onChanged: (_) => setState(() {}),
            maxLines: 4,
            maxLength: 2000,
            decoration: InputDecoration(
              labelText: english ? 'Review (required)' : '评价内容（必填）',
            ),
          ),
          TextField(
            controller: _tags,
            decoration: InputDecoration(
              labelText: english ? 'Tags (separated by spaces)' : '标签（空格分隔）',
            ),
          ),
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: _uploading
                ? const SizedBox.square(
                    dimension: 22,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.add_photo_alternate_outlined),
            title: Text(
              _imageUrl.isEmpty
                  ? (english ? 'Add optional image' : '添加可选图片')
                  : (english ? 'Image attached' : '已添加图片'),
            ),
            onTap: _uploading ? null : _pickImage,
          ),
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: FilledButton(
            key: const Key('review-submit-button'),
            onPressed: _uploading || _content.text.trim().isEmpty
                ? null
                : () => Navigator.pop(
                      context,
                      ReviewDraft(
                        rating: _rating,
                        content: _content.text.trim(),
                        tags: _tags.text
                            .trim()
                            .split(RegExp(r'\s+'))
                            .where((tag) => tag.isNotEmpty)
                            .toList(),
                        images: _imageUrl.isEmpty ? const [] : [_imageUrl],
                      ),
                    ),
            child: Text(english ? 'Submit' : '提交评价'),
          ),
        ),
      ),
    );
  }
}
