import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/files/app_file_picker.dart';
import 'package:joysong_flutter/core/transient_message.dart';
import 'package:joysong_flutter/core/translation/auto_translation_builder.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/domain/payment_models.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_controller.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_action_launcher.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_controller.dart';
import 'package:joysong_flutter/features/orders/presentation/payment_page.dart';
import 'package:joysong_flutter/features/orders/presentation/review_order_controller.dart';
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
    this.onOpenServiceConversation,
    this.enableAutoTranslation = false,
    super.key,
  });

  final OrderDetailController controller;
  final SocialController? socialController;
  final AppFilePicker filePicker;
  final Future<AppPickedFile?> Function()? pickImage;
  final Future<String?> Function(PublicMediaPurpose purpose)? uploadImage;
  final VoidCallback? onOrderRemoved;
  final ValueChanged<String>? onOpenServiceConversation;
  final bool enableAutoTranslation;

  @override
  State<OrderDetailPage> createState() => _OrderDetailPageState();
}

class _OrderDetailPageState extends State<OrderDetailPage> {
  bool _reviewBusy = false;

  @override
  void initState() {
    super.initState();
    if (widget.controller.order == null) {
      widget.controller.load();
    }
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
          title: Text(_isEnglish(context) ? 'Please confirm' : '请确认'),
          content: Text(confirmation),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: Text(_isEnglish(context) ? 'Not now' : '暂不'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: Text(_isEnglish(context) ? 'Confirm' : '确认'),
            ),
          ],
        ),
      );
      if (confirmed != true || !mounted) return;
    }
    final success = await action();
    if (success && mounted && closesAfterSuccess) {
      Navigator.of(context).pop();
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
          enableAutoTranslation: widget.enableAutoTranslation,
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

  Future<String?> _pickAndUpload(
    PublicMediaPurpose purpose, {
    bool propagateError = false,
  }) async {
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
      if (propagateError) {
        throw StateError(result.message ?? 'Image upload failed');
      }
      if (mounted) {
        showTransientMessage(
          context,
          result.message ??
              (_isEnglish(context) ? 'Image upload failed' : '图片上传失败'),
        );
      }
    } on Object catch (error) {
      if (propagateError) rethrow;
      if (mounted) {
        showTransientMessage(context, error.toString());
      }
    }
    return null;
  }

  Future<void> _submitReview() async {
    final socialController = widget.socialController;
    final order = widget.controller.order;
    if (socialController == null || order == null || _reviewBusy) return;
    setState(() => _reviewBusy = true);
    final draft = await Navigator.of(context).push<ReviewDraft>(
      MaterialPageRoute(
        builder: (_) => ReviewOrderPage(
          order: order,
          enableAutoTranslation: widget.enableAutoTranslation,
          onPickImage: () => _pickAndUpload(
            PublicMediaPurpose.review,
            propagateError: true,
          ),
        ),
      ),
    );
    if (draft == null || !mounted) {
      if (mounted) setState(() => _reviewBusy = false);
      return;
    }
    final result = await socialController.submitOrderReview(order.id, draft);
    if (!mounted) return;
    setState(() => _reviewBusy = false);
    showTransientMessage(
      context,
      result.succeeded
          ? (_isEnglish(context) ? 'Review submitted' : '评价已提交')
          : (result.message ??
              (_isEnglish(context) ? 'Review submission failed' : '评价提交失败')),
    );
    if (result.succeeded) await widget.controller.load();
  }

  Future<void> _editReview() async {
    final socialController = widget.socialController;
    final order = widget.controller.order;
    if (socialController == null || order == null || _reviewBusy) return;
    setState(() => _reviewBusy = true);
    final loaded = await socialController.loadOrderReview(order.id);
    if (!mounted) return;
    final review = loaded.value;
    if (!loaded.succeeded || review == null) {
      setState(() => _reviewBusy = false);
      showTransientMessage(
        context,
        loaded.message ??
            (_isEnglish(context) ? 'Unable to load the review' : '评价加载失败'),
      );
      return;
    }
    final draft = await Navigator.of(context).push<ReviewDraft>(
      MaterialPageRoute(
        builder: (_) => ReviewOrderPage(
          order: order,
          initialReview: review,
          enableAutoTranslation: widget.enableAutoTranslation,
          onPickImage: () => _pickAndUpload(
            PublicMediaPurpose.review,
            propagateError: true,
          ),
        ),
      ),
    );
    if (!mounted) return;
    if (draft == null) {
      setState(() => _reviewBusy = false);
      return;
    }
    final result = await socialController.updateReview(review.id, draft);
    if (!mounted) return;
    setState(() => _reviewBusy = false);
    showTransientMessage(
      context,
      result.succeeded
          ? (_isEnglish(context) ? 'Review updated' : '评价已修改')
          : (result.message ??
              (_isEnglish(context) ? 'Review update failed' : '评价修改失败')),
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
        if (order.isTravelGroundServiceOnly)
          _TravelGroundServiceInformation(
            order: order,
            onOpenServiceConversation: widget.onOpenServiceConversation,
            enableAutoTranslation: widget.enableAutoTranslation,
          )
        else
          _OrderInformation(
            order: order,
            enableAutoTranslation: widget.enableAutoTranslation,
          ),
        const SizedBox(height: 12),
        if (order.isTravelGroundServiceOnly)
          _TravelGroundServicePaymentInformation(order: order)
        else
          _PaymentInformation(order: order),
        if (!order.isTravelGroundServiceOnly &&
            order.verifyCode != null &&
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
          _RefundCard(
            refund: controller.refund!,
            enableAutoTranslation: widget.enableAutoTranslation,
          ),
        ],
        if (!order.isTravelGroundServiceOnly &&
            controller.settlement != null) ...[
          const SizedBox(height: 12),
          _SettlementCard(settlement: controller.settlement!),
        ],
        if (!order.isTravelGroundServiceOnly &&
            controller.isSettlementGenerationPending) ...[
          const SizedBox(height: 12),
          _SettlementPendingCard(),
        ],
        if (!order.isTravelGroundServiceOnly &&
            controller.settlementErrorMessage != null) ...[
          const SizedBox(height: 12),
          _SettlementErrorCard(
            message: controller.settlementErrorMessage!,
            onRetry: controller.retrySettlement,
          ),
        ],
        if (controller.statusLogs.isNotEmpty) ...[
          const SizedBox(height: 20),
          Text(_isEnglish(context) ? 'Order progress' : '订单进度',
              style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          _StatusTimeline(
            logs: controller.statusLogs,
            enableAutoTranslation: widget.enableAutoTranslation,
          ),
        ],
        const SizedBox(height: 20),
        _OrderActions(
          controller: controller,
          order: order,
          onPayServiceFee: () => _openServiceFeePayment(order),
          onPayConsultation: () => _openPayment(order, balance: false),
          onVerificationCode: () => _run(controller.requestVerificationCode),
          onPayBalance: () => _openPayment(order, balance: true),
          onConfirmCompletion: () => _run(
            controller.confirmCompletion,
            confirmation: order.isTravelGroundServiceOnly
                ? (_isEnglish(context)
                    ? 'Confirm that the travel ground service is complete?'
                    : '请确认旅游地接服务已完成？')
                : (_isEnglish(context)
                    ? 'Confirm that the service is complete. The order will then enter completion and settlement.'
                    : '请确认项目服务已经全部完成。确认后将进入完成与结算流程。'),
          ),
          onCancel: () => _run(
            controller.cancel,
            confirmation: _isEnglish(context)
                ? 'The unpaid order will be removed from the list after cancellation. Continue?'
                : '待支付订单取消后会从列表移除，确定继续吗？',
            closesAfterSuccess: true,
          ),
          onRefund: _requestRefund,
          onReview: _submitReview,
          onEditReview: _editReview,
          canReview: widget.socialController != null &&
              const {
                OrderStatus.completed,
                OrderStatus.pendingSettlement,
                OrderStatus.settled,
              }.contains(order.status) &&
              !order.hasReview,
          canEditReview: widget.socialController != null && order.hasReview,
          reviewBusy: _reviewBusy,
          onCancelRefund: () => _run(
            controller.cancelRefund,
            confirmation: _isEnglish(context)
                ? 'Withdraw the current refund request?'
                : '确定撤销当前退款申请吗？',
          ),
          onDelete: () => _run(
            controller.delete,
            confirmation: _isEnglish(context)
                ? 'This order will no longer be shown after deletion. Continue?'
                : '删除后订单将不再显示，确定继续吗？',
            closesAfterSuccess: true,
          ),
        ),
      ],
    );
  }

  Future<void> _openPayment(Order order, {required bool balance}) async {
    final controller = PaymentController(
      repository: widget.controller.repository,
      order: order,
      paymentType: balance ? PaymentType.balance : PaymentType.consultationFee,
      providers: const [PaymentProvider.stripe],
      actionLauncher: const MobilePaymentActionLauncher(
        allowedRedirectHosts: {'checkout.stripe.com'},
      ),
    );
    bool? paid;
    try {
      paid = await Navigator.of(context).push<bool>(MaterialPageRoute(
        builder: (_) => PaymentPage(
          controller: controller,
          enableAutoTranslation: widget.enableAutoTranslation,
        ),
      ));
    } finally {
      controller.dispose();
    }
    if (paid == true && mounted) await widget.controller.load();
  }

  Future<void> _openServiceFeePayment(Order order) async {
    final controller = PaymentController(
      repository: widget.controller.repository,
      order: order,
      actionLauncher: const MobilePaymentActionLauncher(
        allowedRedirectHosts: {'cashier.alipayplus.com'},
      ),
    );
    try {
      await Navigator.of(context).push<bool>(MaterialPageRoute(
        builder: (_) => PaymentPage(
          controller: controller,
          enableAutoTranslation: widget.enableAutoTranslation,
        ),
      ));
    } finally {
      controller.dispose();
    }
    if (mounted) await widget.controller.load();
  }
}

class _StatusHeader extends StatelessWidget {
  const _StatusHeader({required this.order});

  final Order order;

  @override
  Widget build(BuildContext context) {
    final statusLabel = !order.isTravelGroundServiceOnly &&
            order.refundStatus == RefundStatus.pending
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
                  Text(
                    statusLabel,
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(
                          color: _orderStatusColor(
                            context,
                            order.status,
                            order.refundStatus,
                          ),
                        ),
                  ),
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

class _OrderInformation extends StatelessWidget {
  const _OrderInformation({
    required this.order,
    required this.enableAutoTranslation,
  });

  final Order order;
  final bool enableAutoTranslation;

  @override
  Widget build(BuildContext context) {
    final orderId = order.id.trim();
    final translateOrder = enableAutoTranslation && orderId.isNotEmpty;
    final contentId = 'order:$orderId';
    return _DetailPanel(
      title: _isEnglish(context) ? 'Appointment' : '预约信息',
      children: [
        _DetailLine.widget(
          label: _isEnglish(context) ? 'Service' : '项目',
          value: StableAutoTranslatedText(
            enabled: translateOrder,
            contentType: 'project',
            contentId: contentId,
            field: 'projectName',
            sourceText: order.projectName,
            retryToken: order,
          ),
        ),
        if (order.institutionName.isNotEmpty)
          _DetailLine.widget(
            label: _isEnglish(context) ? 'Institution' : '机构',
            value: StableAutoTranslatedText(
              enabled: translateOrder,
              contentType: 'institution',
              contentId: contentId,
              field: 'institutionName',
              sourceText: order.institutionName,
              retryToken: order,
            ),
          ),
        if (order.doctorName.isNotEmpty)
          _DetailLine(
            label: _isEnglish(context) ? 'Doctor' : '医生',
            value: order.doctorName,
          ),
        if (order.appointmentTime != null)
          _DetailLine(
            label: _isEnglish(context) ? 'Appointment time' : '预约时间',
            value: _detailDateTime(order.appointmentTime!),
          ),
        if (order.remark.isNotEmpty)
          _DetailLine.widget(
            label: _isEnglish(context) ? 'Notes' : '备注',
            value: StableAutoTranslatedText(
              enabled: translateOrder,
              contentType: 'general',
              contentId: contentId,
              field: 'remark',
              sourceText: order.remark,
              retryToken: order,
            ),
          ),
      ],
    );
  }
}

class _TravelGroundServiceInformation extends StatelessWidget {
  const _TravelGroundServiceInformation({
    required this.order,
    required this.enableAutoTranslation,
    this.onOpenServiceConversation,
  });

  final Order order;
  final bool enableAutoTranslation;
  final ValueChanged<String>? onOpenServiceConversation;

  @override
  Widget build(BuildContext context) {
    final orderId = order.id.trim();
    final translateOrder = enableAutoTranslation && orderId.isNotEmpty;
    if (!order.consultantDetailsVisible) {
      final message = order.status == OrderStatus.pendingServiceFee
          ? (_isEnglish(context)
              ? 'Pay the travel ground service fee to view and contact your consultant.'
              : '支付旅游地接服务费后可查看地接资料并沟通')
          : (_isEnglish(context)
              ? 'Travel ground service details are no longer available.'
              : '地接资料当前不可查看');
      return _DetailPanel(
        title: _isEnglish(context) ? 'Travel ground service' : '地接服务',
        children: [
          Text(message),
          if (order.serviceConversationReadable)
            _serviceConversationAction(context),
        ],
      );
    }
    return _DetailPanel(
      title: _isEnglish(context) ? 'Travel ground service' : '地接服务',
      children: [
        if (order.consultantAvatar != null) ...[
          CircleAvatar(
            key: const Key('service-consultant-avatar'),
            backgroundImage: NetworkImage(order.consultantAvatar!),
          ),
          const SizedBox(height: 8),
        ],
        if (order.consultantName.isNotEmpty)
          _DetailLine(
            label: _isEnglish(context) ? 'Consultant' : '地接人员',
            value: order.consultantName,
          ),
        if (order.consultantId.isNotEmpty)
          _DetailLine(
            label: _isEnglish(context) ? 'Consultant ID' : '地接人员编号',
            value: order.consultantId,
          ),
        if (order.institutionName.isNotEmpty)
          _DetailLine.widget(
            label: _isEnglish(context) ? 'Institution' : '服务机构',
            value: StableAutoTranslatedText(
              enabled: translateOrder,
              contentType: 'institution',
              contentId: 'order:$orderId',
              field: 'institutionName',
              sourceText: order.institutionName,
              retryToken: order,
            ),
          ),
        if (order.institutionId.isNotEmpty)
          _DetailLine(
            label: _isEnglish(context) ? 'Institution ID' : '机构编号',
            value: order.institutionId,
          ),
        if (order.serviceConversationReadable)
          _serviceConversationAction(context),
      ],
    );
  }

  Widget _serviceConversationAction(BuildContext context) => Align(
        alignment: Alignment.centerLeft,
        child: FilledButton.tonalIcon(
          key: const Key('service-chat-button'),
          onPressed: onOpenServiceConversation == null
              ? null
              : () => onOpenServiceConversation!(order.id),
          icon: const Icon(Icons.chat_bubble_outline),
          label: Text(
            order.serviceMessagingEnabled
                ? (_isEnglish(context) ? 'Service chat' : '地接沟通')
                : (_isEnglish(context) ? 'View service history' : '查看沟通记录'),
          ),
        ),
      );
}

class _TravelGroundServicePaymentInformation extends StatelessWidget {
  const _TravelGroundServicePaymentInformation({required this.order});

  final Order order;

  @override
  Widget build(BuildContext context) => _DetailPanel(
        title: _isEnglish(context) ? 'Payment details' : '费用信息',
        children: [
          _DetailLine(
            label:
                _isEnglish(context) ? 'Travel ground service fee' : '旅游地接服务费',
            value: order.currency == 'USD' &&
                    order.travelGroundServiceFeeMinor != null &&
                    order.travelGroundServiceFeeMinor! >= 0
                ? _minorUsd(order.travelGroundServiceFeeMinor!)
                : '--',
          ),
          const SizedBox(height: 6),
          Text(
            _isEnglish(context)
                ? 'Pay medical fees directly to the hospital after arrival.'
                : '医疗费到院后直接向医院支付',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
        ],
      );
}

class _PaymentInformation extends StatelessWidget {
  const _PaymentInformation({required this.order});

  final Order order;

  @override
  Widget build(BuildContext context) => _DetailPanel(
        title: _isEnglish(context) ? 'Payment details' : '费用信息',
        children: [
          _DetailLine(
            label: _isEnglish(context) ? 'Order total' : '订单金额',
            value: order.amount.formatted,
          ),
          if (!order.discountAmount.isZero)
            _DetailLine(
              label: _isEnglish(context) ? 'Discount' : '优惠金额',
              value: '-${order.discountAmount.formatted}',
            ),
          _DetailLine(
            label: _isEnglish(context) ? 'Consultation fee' : '面诊费',
            value: order.consultationFee.formatted,
          ),
          _DetailLine(
            label: _isEnglish(context) ? 'Balance' : '尾款',
            value: order.remainingAmount.formatted,
          ),
          _DetailLine(
            label: _isEnglish(context) ? 'Paid' : '已支付',
            value: order.paidAmount.formatted,
          ),
          const SizedBox(height: 6),
          Text(
            _isEnglish(context)
                ? 'The final payment result is confirmed securely by the server. Do not pay again while a payment is processing.'
                : '支付结果由服务端安全确认。支付处理中请勿重复发起付款。',
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
  const _RefundCard({
    required this.refund,
    required this.enableAutoTranslation,
  });

  final RefundDetail refund;
  final bool enableAutoTranslation;

  @override
  Widget build(BuildContext context) {
    final refundId = refund.id.trim();
    final translateRefund = enableAutoTranslation && refundId.isNotEmpty;
    final contentId = 'refund:$refundId';
    final visibleDescription = _refundDescription(refund);
    return _DetailPanel(
      title: _isEnglish(context) ? 'Refund details' : '退款信息',
      children: [
        _DetailLine(
          label: _isEnglish(context) ? 'Status' : '状态',
          value: _refundStatusText(context, refund.status),
        ),
        _DetailLine(
          label: _isEnglish(context) ? 'Amount' : '金额',
          value: refund.amount.formatted,
        ),
        if (refund.reason.trim().isNotEmpty)
          _DetailLine.widget(
            label: _isEnglish(context) ? 'Reason' : '原因',
            value: StableAutoTranslatedText(
              enabled: translateRefund,
              contentType: 'general',
              contentId: contentId,
              field: 'reason',
              sourceText: refund.reason,
              retryToken: refund,
            ),
          ),
        if (visibleDescription.trim().isNotEmpty)
          _DetailLine.widget(
            label: _isEnglish(context) ? 'Details' : '说明',
            value: StableAutoTranslatedText(
              enabled: translateRefund,
              contentType: 'general',
              contentId: contentId,
              field: 'description',
              sourceText: visibleDescription,
              retryToken: refund,
            ),
          ),
        if (refund.rejectReason?.trim().isNotEmpty == true)
          _DetailLine.widget(
            label: _isEnglish(context) ? 'Rejection reason' : '驳回原因',
            value: StableAutoTranslatedText(
              enabled: translateRefund,
              contentType: 'general',
              contentId: contentId,
              field: 'rejectReason',
              sourceText: refund.rejectReason!,
              retryToken: refund,
            ),
          ),
      ],
    );
  }
}

String _refundDescription(RefundDetail refund) {
  final rejectReason = refund.rejectReason?.trim();
  if (rejectReason == null || rejectReason.isEmpty) return refund.description;
  final suffix = '[拒绝原因] $rejectReason';
  final suffixIndex = refund.description.lastIndexOf(suffix);
  return suffixIndex >= 0 &&
          suffixIndex + suffix.length == refund.description.trimRight().length
      ? refund.description.substring(0, suffixIndex).trimRight()
      : refund.description;
}

class _SettlementCard extends StatelessWidget {
  const _SettlementCard({required this.settlement});

  final Settlement settlement;

  @override
  Widget build(BuildContext context) => _DetailPanel(
        title: _isEnglish(context) ? 'Settlement progress' : '结算进度',
        children: [
          _DetailLine(
            label: _isEnglish(context) ? 'Status' : '状态',
            value: settlement.state,
          ),
          _DetailLine(
            label: _isEnglish(context) ? 'Total paid' : '实付总额',
            value: _minorUsd(settlement.grossTotalPaidMinor),
          ),
          _DetailLine(
            label: _isEnglish(context) ? 'Net settled' : '净结算额',
            value: _minorUsd(settlement.netSettledMinor),
          ),
          if (settlement.releasedAt != null)
            _DetailLine(
              label: _isEnglish(context) ? 'Released at' : '释放时间',
              value: _detailDateTime(settlement.releasedAt!),
            ),
        ],
      );
}

class _SettlementPendingCard extends StatelessWidget {
  @override
  Widget build(BuildContext context) => _DetailPanel(
        title: _isEnglish(context) ? 'Settlement progress' : '结算进度',
        children: [
          Text(_isEnglish(context)
              ? 'Settlement is being generated.'
              : '结算信息生成中。'),
        ],
      );
}

class _SettlementErrorCard extends StatelessWidget {
  const _SettlementErrorCard({required this.message, required this.onRetry});

  final String message;
  final Future<void> Function() onRetry;

  @override
  Widget build(BuildContext context) => _DetailPanel(
        title: _isEnglish(context) ? 'Settlement progress' : '结算进度',
        children: [
          Text(message),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              key: const Key('settlement-retry-button'),
              onPressed: onRetry,
              child: Text(_isEnglish(context) ? 'Retry' : '重试'),
            ),
          ),
        ],
      );
}

class _StatusTimeline extends StatelessWidget {
  const _StatusTimeline({
    required this.logs,
    required this.enableAutoTranslation,
  });

  final List<OrderStatusLog> logs;
  final bool enableAutoTranslation;

  @override
  Widget build(BuildContext context) {
    final positiveIdCounts = <int, int>{};
    for (final log in logs) {
      if (log.id > 0) {
        positiveIdCounts.update(log.id, (count) => count + 1,
            ifAbsent: () => 1);
      }
    }
    return Column(
      children: [
        for (final log in logs)
          _buildLog(
            context,
            log,
            identityStable: log.id > 0 && positiveIdCounts[log.id] == 1,
          ),
      ],
    );
  }

  Widget _buildLog(
    BuildContext context,
    OrderStatusLog log, {
    required bool identityStable,
  }) =>
      ListTile(
        key: identityStable
            ? ValueKey<String>('order-status-log:${log.id}')
            : ObjectKey(log),
        dense: true,
        contentPadding: EdgeInsets.zero,
        leading: const Icon(Icons.circle, size: 10),
        title: Text(_orderStatusText(context, log.toStatus)),
        subtitle: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(_detailDateTime(log.createdAt)),
            if (log.remark.isNotEmpty) ...[
              const Text(' · '),
              Expanded(
                child: StableAutoTranslatedText(
                  enabled: enableAutoTranslation && identityStable,
                  contentType: 'general',
                  contentId: 'order-status-log:${log.id}',
                  field: 'remark',
                  sourceText: log.remark,
                  retryToken: log,
                ),
              ),
            ],
          ],
        ),
      );
}

class _OrderActions extends StatelessWidget {
  const _OrderActions({
    required this.controller,
    required this.order,
    required this.onPayServiceFee,
    required this.onPayConsultation,
    required this.onVerificationCode,
    required this.onPayBalance,
    required this.onConfirmCompletion,
    required this.onCancel,
    required this.onRefund,
    required this.onReview,
    required this.onEditReview,
    required this.canReview,
    required this.canEditReview,
    required this.reviewBusy,
    required this.onCancelRefund,
    required this.onDelete,
  });

  final OrderDetailController controller;
  final Order order;
  final VoidCallback onPayServiceFee;
  final VoidCallback onPayConsultation;
  final VoidCallback onVerificationCode;
  final VoidCallback onPayBalance;
  final VoidCallback onConfirmCompletion;
  final VoidCallback onCancel;
  final VoidCallback onRefund;
  final VoidCallback onReview;
  final VoidCallback onEditReview;
  final bool canReview;
  final bool canEditReview;
  final bool reviewBusy;
  final VoidCallback onCancelRefund;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final busy = controller.isBusy;
    if (order.isTravelGroundServiceOnly) {
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
          if (order.canPayTravelGroundServiceFee)
            FilledButton(
              key: const Key('pay-service-fee-button'),
              onPressed: busy ? null : onPayServiceFee,
              child: Text(
                _isEnglish(context)
                    ? 'Pay travel ground service fee'
                    : '支付旅游地接服务费',
              ),
            ),
          if (order.canConfirmCompletion)
            FilledButton(
              key: const Key('confirm-completion-button'),
              onPressed: busy ? null : onConfirmCompletion,
              child: Text(
                _isEnglish(context)
                    ? 'Confirm travel ground service completion'
                    : '确认旅游地接服务已完成',
              ),
            ),
          if (order.canRequestRefund)
            TextButton(
              key: const Key('request-refund-button'),
              onPressed: busy ? null : onRefund,
              child: Text(
                _isEnglish(context)
                    ? 'Request full travel ground service fee refund'
                    : '申请全额旅游地接服务费退款',
              ),
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
              onPressed: busy || reviewBusy ? null : onReview,
              child: Text(_isEnglish(context) ? 'Write a review' : '去评价'),
            ),
          if (canEditReview)
            FilledButton.tonal(
              key: const Key('edit-review-button'),
              onPressed: busy || reviewBusy ? null : onEditReview,
              child: Text(_isEnglish(context) ? 'Edit review' : '修改评价'),
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
            onPressed: busy || reviewBusy ? null : onReview,
            child: Text(_isEnglish(context) ? 'Write a review' : '去评价'),
          ),
        if (canEditReview)
          FilledButton.tonal(
            key: const Key('edit-review-button'),
            onPressed: busy || reviewBusy ? null : onEditReview,
            child: Text(_isEnglish(context) ? 'Edit review' : '修改评价'),
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
  const _DetailLine({required this.label, required String value})
      : value = null,
        textValue = value;

  const _DetailLine.widget({required this.label, required this.value})
      : textValue = null;

  final String label;
  final Widget? value;
  final String? textValue;

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
            Expanded(child: value ?? Text(textValue!)),
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
    OrderStatus.pendingServiceFee => 'Travel ground service fee due',
    OrderStatus.serviceActive => 'Travel ground service active',
    OrderStatus.refundReview => 'Refund under review',
    OrderStatus.refundProcessing => 'Refund processing',
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
    RefundStatus.processing => 'Refund processing',
    RefundStatus.approved => 'Approved',
    RefundStatus.rejected => 'Rejected',
    RefundStatus.cancelled => 'Cancelled',
    RefundStatus.unknown => 'Unknown',
  };
}

String _minorUsd(int minor) {
  final negative = minor < 0;
  final digits = minor.abs().toString().padLeft(3, '0');
  final rawDollars = digits.substring(0, digits.length - 2);
  final dollars = rawDollars.replaceAllMapped(
    RegExp(r'(?=(\d{3})+(?!\d))'),
    (_) => ',',
  );
  final cents = digits.substring(digits.length - 2);
  return '${negative ? '-' : ''}\$$dollars.$cents';
}

bool _isEnglish(BuildContext context) =>
    Localizations.localeOf(context).languageCode == 'en';

class _FlowOrderSummary extends StatelessWidget {
  const _FlowOrderSummary({
    required this.order,
    required this.enableAutoTranslation,
  });

  final Order order;
  final bool enableAutoTranslation;

  @override
  Widget build(BuildContext context) {
    final orderId = order.id.trim();
    final translateOrder = enableAutoTranslation && orderId.isNotEmpty;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
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
                  StableAutoTranslatedText(
                    enabled: translateOrder,
                    contentType: 'project',
                    contentId: 'order:$orderId',
                    field: 'projectName',
                    sourceText: order.projectName,
                    retryToken: order,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  if (order.institutionName.isNotEmpty)
                    StableAutoTranslatedText(
                      enabled: translateOrder,
                      contentType: 'institution',
                      contentId: 'order:$orderId',
                      field: 'institutionName',
                      sourceText: order.institutionName,
                      retryToken: order,
                    ),
                  if (order.orderNo.isNotEmpty)
                    Text(
                      order.orderNo,
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
    this.enableAutoTranslation = false,
    super.key,
  });

  final Order order;
  final bool canUploadEvidence;
  final Future<String?> Function() onPickEvidence;
  final bool enableAutoTranslation;

  @override
  State<RefundApplyPage> createState() => _RefundApplyPageState();
}

class _RefundApplyPageState extends State<RefundApplyPage> {
  final _customReason = TextEditingController();
  final _description = TextEditingController();
  int? _selectedReasonIndex;
  String _evidenceUrl = '';
  bool _uploading = false;

  @override
  void dispose() {
    _customReason.dispose();
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
    final reasons = english
        ? const [
            'Changed my mind',
            'Wrong appointment time',
            'Wrong institution',
            'Wrong project',
            'Wrong doctor',
            'Other',
          ]
        : const ['不想去了', '选错时间', '选错机构', '选错项目', '选错医生', '其他'];
    final selectedReason =
        _selectedReasonIndex == null ? '' : reasons[_selectedReasonIndex!];
    final requiresCustomReason = _selectedReasonIndex == reasons.length - 1;
    final canSubmit = !_uploading &&
        selectedReason.isNotEmpty &&
        (!requiresCustomReason || _customReason.text.trim().isNotEmpty);
    return Scaffold(
      appBar: AppBar(title: Text(english ? 'Request a refund' : '申请退款')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          _FlowOrderSummary(
            order: widget.order,
            enableAutoTranslation: widget.enableAutoTranslation,
          ),
          const SizedBox(height: 20),
          Text(
            english ? 'Refund reason' : '退款原因',
            style: Theme.of(context)
                .textTheme
                .titleMedium
                ?.copyWith(fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 8),
          Card(
            margin: EdgeInsets.zero,
            clipBehavior: Clip.antiAlias,
            child: RadioGroup<int>(
              groupValue: _selectedReasonIndex,
              onChanged: (value) => setState(() {
                _selectedReasonIndex = value;
                if (value != reasons.length - 1) {
                  _customReason.clear();
                }
              }),
              child: Column(
                children: [
                  for (var index = 0; index < reasons.length; index++) ...[
                    RadioListTile<int>(
                      key: Key('refund-reason-$index'),
                      value: index,
                      title: Text(reasons[index]),
                      dense: true,
                      contentPadding: const EdgeInsets.symmetric(horizontal: 8),
                    ),
                    if (index != reasons.length - 1)
                      const Divider(height: 1, indent: 48),
                  ],
                ],
              ),
            ),
          ),
          if (requiresCustomReason) ...[
            const SizedBox(height: 14),
            TextField(
              key: const Key('refund-custom-reason-field'),
              controller: _customReason,
              onChanged: (_) => setState(() {}),
              maxLines: 2,
              maxLength: 100,
              decoration: InputDecoration(
                labelText: english ? 'Other reason (required)' : '其他原因（必填）',
                hintText:
                    english ? 'Please enter your refund reason' : '请输入退款原因',
              ),
            ),
          ],
          const SizedBox(height: 10),
          TextField(
            key: const Key('refund-description-field'),
            controller: _description,
            maxLines: 3,
            maxLength: 500,
            decoration: InputDecoration(
              labelText: english ? 'Additional details (optional)' : '退款说明（选填）',
              hintText: english
                  ? 'Add any information that may help with the review'
                  : '可补充有助于审核的信息',
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
            onPressed: !canSubmit
                ? null
                : () => Navigator.pop(
                      context,
                      OrderRefundDraft(
                        reason: requiresCustomReason
                            ? _customReason.text.trim()
                            : selectedReason,
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
    this.initialReview,
    this.controller,
    this.enableAutoTranslation = false,
    super.key,
  });

  final Order order;
  final Future<String?> Function() onPickImage;
  final Review? initialReview;
  final ReviewOrderController? controller;
  final bool enableAutoTranslation;

  @override
  State<ReviewOrderPage> createState() => _ReviewOrderPageState();
}

class _ReviewOrderPageState extends State<ReviewOrderPage> {
  late final TextEditingController _content;
  late final TextEditingController _tags;
  late final ReviewOrderController _controller;
  late final bool _ownsController;

  @override
  void initState() {
    super.initState();
    _ownsController = widget.controller == null;
    _controller = widget.controller ??
        ReviewOrderController(initialReview: widget.initialReview);
    _content = TextEditingController(text: _controller.content);
    _tags = TextEditingController(text: _controller.tagsText);
  }

  @override
  void dispose() {
    _content.dispose();
    _tags.dispose();
    if (_ownsController) _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final english = _isEnglish(context);
    final editing = widget.initialReview != null;
    return Scaffold(
      appBar: AppBar(
        title: Text(
          editing
              ? (english ? 'Edit review' : '修改评价')
              : (english ? 'Write a review' : '发表评价'),
        ),
      ),
      body: AnimatedBuilder(
        animation: _controller,
        builder: (context, _) => ListView(
          padding: const EdgeInsets.all(20),
          children: [
            _FlowOrderSummary(
              order: widget.order,
              enableAutoTranslation: widget.enableAutoTranslation,
            ),
            const SizedBox(height: 20),
            Text(english ? 'Rating' : '评分'),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (var value = 1; value <= 5; value++)
                  IconButton(
                    key: Key('review-rating-$value'),
                    tooltip: '$value',
                    onPressed: () => _controller.setRating(value),
                    icon: Icon(
                      value <= _controller.rating
                          ? Icons.star
                          : Icons.star_border,
                    ),
                  ),
              ],
            ),
            TextField(
              key: const Key('review-content-field'),
              controller: _content,
              onChanged: _controller.setContent,
              maxLines: 4,
              maxLength: 2000,
              decoration: InputDecoration(
                labelText: english ? 'Review (required)' : '评价内容（必填）',
              ),
            ),
            TextField(
              key: const Key('review-tags-field'),
              controller: _tags,
              onChanged: _controller.setTags,
              decoration: InputDecoration(
                labelText: english ? 'Tags (separated by spaces)' : '标签（空格分隔）',
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: Text(
                    english ? 'Photos (optional)' : '图片（选填）',
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                ),
                Text(
                  '${_controller.imageUrls.length}/${ReviewDraft.maxImageCount}',
                  key: const Key('review-image-count'),
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 10,
              runSpacing: 10,
              children: [
                for (var index = 0;
                    index < _controller.imageUrls.length;
                    index++)
                  _ReviewImagePreview(
                    index: index,
                    url: _controller.imageUrls[index],
                    english: english,
                    onRemove: () => _controller.removeImage(
                      _controller.imageUrls[index],
                    ),
                  ),
                if (_controller.imageUrls.length < ReviewDraft.maxImageCount)
                  _ReviewImageAddTile(
                    uploading: _controller.uploading,
                    english: english,
                    onTap: _controller.canAddImage
                        ? () => _controller.pickAndUploadImage(
                              widget.onPickImage,
                            )
                        : null,
                  ),
              ],
            ),
            if (_controller.uploadFailed) ...[
              const SizedBox(height: 8),
              Text(
                english
                    ? 'Image upload failed. Please try again.'
                    : '图片上传失败，请重试。',
                key: const Key('review-image-upload-error'),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            const SizedBox(height: 8),
            Text(
              english
                  ? 'You can upload up to ${ReviewDraft.maxImageCount} images.'
                  : '最多可上传 ${ReviewDraft.maxImageCount} 张图片。',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
      bottomNavigationBar: SafeArea(
        child: AnimatedBuilder(
          animation: _controller,
          builder: (context, _) => Padding(
            padding: const EdgeInsets.all(16),
            child: FilledButton(
              key: const Key('review-submit-button'),
              onPressed: !_controller.canSubmit
                  ? null
                  : () => Navigator.pop(context, _controller.createDraft()),
              child: Text(
                editing
                    ? (english ? 'Save changes' : '保存修改')
                    : (english ? 'Submit' : '提交评价'),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ReviewImagePreview extends StatelessWidget {
  const _ReviewImagePreview({
    required this.index,
    required this.url,
    required this.english,
    required this.onRemove,
  });

  final int index;
  final String url;
  final bool english;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    return SizedBox.square(
      dimension: 92,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned.fill(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(10),
              child: Image.network(
                url,
                key: Key('review-image-preview-$index'),
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => ColoredBox(
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                  child: const Icon(Icons.broken_image_outlined),
                ),
              ),
            ),
          ),
          Positioned(
            right: -8,
            top: -8,
            child: IconButton.filled(
              key: Key('review-image-remove-$index'),
              visualDensity: VisualDensity.compact,
              constraints: const BoxConstraints.tightFor(width: 30, height: 30),
              padding: EdgeInsets.zero,
              tooltip: english ? 'Remove image' : '删除图片',
              onPressed: onRemove,
              icon: const Icon(Icons.close, size: 18),
            ),
          ),
        ],
      ),
    );
  }
}

class _ReviewImageAddTile extends StatelessWidget {
  const _ReviewImageAddTile({
    required this.uploading,
    required this.english,
    required this.onTap,
  });

  final bool uploading;
  final bool english;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return SizedBox.square(
      dimension: 92,
      child: OutlinedButton(
        key: const Key('review-image-add-button'),
        onPressed: onTap,
        style: OutlinedButton.styleFrom(
          padding: const EdgeInsets.all(8),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10),
          ),
        ),
        child: uploading
            ? Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const SizedBox.square(
                    dimension: 24,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                  const SizedBox(height: 8),
                  Text(english ? 'Uploading' : '上传中'),
                ],
              )
            : Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(Icons.add_photo_alternate_outlined),
                  const SizedBox(height: 6),
                  Text(english ? 'Add image' : '添加图片'),
                ],
              ),
      ),
    );
  }
}
