import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/professional_management/data/professional_repository.dart';
import 'package:joysong_flutter/features/professional_management/domain/professional_models.dart';

typedef DoctorOrderDirectMessageOpener = Future<void> Function(
  String userId, {
  String? title,
});

class DoctorArticlesPage extends StatefulWidget {
  const DoctorArticlesPage({
    required this.repository,
    this.pickCoverImage,
    super.key,
  });
  final ProfessionalRepository repository;
  final Future<String?> Function()? pickCoverImage;
  @override
  State<DoctorArticlesPage> createState() => _DoctorArticlesPageState();
}

class _DoctorArticlesPageState extends State<DoctorArticlesPage> {
  List<DoctorArticle>? items;
  Object? error;
  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      items = null;
      error = null;
    });
    try {
      final value = await widget.repository.listArticles();
      if (mounted) setState(() => items = value);
    } catch (e) {
      if (mounted) setState(() => error = e);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: Text(context.localized('专业文章', 'Professional articles')),
        ),
        floatingActionButton: FloatingActionButton(
          onPressed: () => _edit(),
          child: const Icon(Icons.add),
        ),
        body: items == null
            ? error == null
                ? const Center(child: CircularProgressIndicator())
                : _Retry(error: error!, onRetry: _load)
            : RefreshIndicator(
                onRefresh: _load,
                child: ListView(
                  children: [
                    for (final item in items!)
                      ListTile(
                        title: Text(item.title),
                        subtitle: Text(item.summary),
                        onTap: () => _edit(item),
                        trailing: IconButton(
                          icon: const Icon(Icons.delete_outline),
                          onPressed: () async {
                            await widget.repository.deleteArticle(item.id);
                            if (mounted) _load();
                          },
                        ),
                      ),
                  ],
                ),
              ),
      );
  Future<void> _edit([DoctorArticle? article]) async {
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => DoctorArticleEditPage(
          repository: widget.repository,
          article: article,
          pickCoverImage: widget.pickCoverImage,
        ),
      ),
    );
    if (mounted) _load();
  }
}

class DoctorArticleEditPage extends StatefulWidget {
  const DoctorArticleEditPage({
    required this.repository,
    this.article,
    this.pickCoverImage,
    super.key,
  });
  final ProfessionalRepository repository;
  final DoctorArticle? article;
  final Future<String?> Function()? pickCoverImage;
  @override
  State<DoctorArticleEditPage> createState() => _DoctorArticleEditPageState();
}

class _DoctorArticleEditPageState extends State<DoctorArticleEditPage> {
  late final TextEditingController title = TextEditingController(
    text: widget.article?.title,
  );
  late final TextEditingController summary = TextEditingController(
    text: widget.article?.summary,
  );
  late final TextEditingController content = TextEditingController(
    text: widget.article?.content,
  );
  late String cover = widget.article?.coverImage ?? '';
  bool busy = false;
  @override
  void initState() {
    super.initState();
    title.addListener(_titleChanged);
  }

  @override
  void dispose() {
    title.removeListener(_titleChanged);
    title.dispose();
    summary.dispose();
    content.dispose();
    super.dispose();
  }

  void _titleChanged() => setState(() {});
  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(context.localized('编辑文章', 'Edit article'))),
        body: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            TextField(
              controller: title,
              decoration: InputDecoration(
                labelText: context.localized('标题', 'Title'),
              ),
            ),
            TextField(
              controller: summary,
              maxLines: 2,
              decoration: InputDecoration(
                labelText: context.localized('摘要', 'Summary'),
              ),
            ),
            TextField(
              controller: content,
              maxLines: 8,
              decoration: InputDecoration(
                labelText: context.localized('正文', 'Content'),
              ),
            ),
            OutlinedButton(
              onPressed: busy || widget.pickCoverImage == null
                  ? null
                  : () async {
                      final v = await widget.pickCoverImage!();
                      if (mounted && v != null) setState(() => cover = v);
                    },
              child: Text(context.localized('上传封面', 'Upload cover')),
            ),
            FilledButton(
              onPressed: busy || title.text.trim().isEmpty
                  ? null
                  : () async {
                      setState(() => busy = true);
                      try {
                        await widget.repository.saveArticle(
                          DoctorArticleDraft(
                            title: title.text,
                            summary: summary.text,
                            coverImage: cover,
                            publishDate:
                                widget.article?.publishDate ?? DateTime.now(),
                            content: content.text,
                          ),
                          id: widget.article?.id,
                        );
                        if (context.mounted) Navigator.pop(context);
                      } catch (_) {
                        if (mounted) setState(() => busy = false);
                      }
                    },
              child: busy
                  ? const SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : Text(context.localized('保存', 'Save')),
            ),
          ],
        ),
      );
}

class DoctorOrdersPage extends StatefulWidget {
  const DoctorOrdersPage({
    required this.repository,
    this.onOpenDirectMessage,
    super.key,
  });

  final ProfessionalRepository repository;
  final DoctorOrderDirectMessageOpener? onOpenDirectMessage;

  @override
  State<DoctorOrdersPage> createState() => _DoctorOrdersPageState();
}

class _DoctorOrdersPageState extends State<DoctorOrdersPage> {
  List<DoctorOrder>? items;
  Object? error;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    setState(() {
      items = null;
      error = null;
    });
    try {
      final v = await widget.repository.listOrders();
      if (mounted) setState(() => items = v);
    } catch (e) {
      if (mounted) setState(() => error = e);
    }
  }

  Future<void> _openDetail(DoctorOrder order) async {
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => DoctorOrderDetailPage(
          repository: widget.repository,
          id: order.id,
          onOpenDirectMessage: widget.onOpenDirectMessage,
        ),
      ),
    );
    if (mounted) await load();
  }

  Future<void> _openMessage(DoctorOrder order) async {
    final openDirectMessage = widget.onOpenDirectMessage;
    final userId = order.userId.trim();
    if (openDirectMessage == null || userId.isEmpty) return;
    await openDirectMessage(
      userId,
      title: _doctorOrderMessageTitle(context, order),
    );
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: Text(context.localized('专业订单', 'Professional orders')),
        ),
        body: items == null
            ? error == null
                ? const Center(child: CircularProgressIndicator())
                : _Retry(error: error!, onRetry: load)
            : RefreshIndicator(
                onRefresh: load,
                child: items!.isEmpty
                    ? ListView(
                        physics: const AlwaysScrollableScrollPhysics(),
                        padding: const EdgeInsets.all(24),
                        children: [
                          const SizedBox(height: 120),
                          const Icon(Icons.receipt_long_outlined, size: 44),
                          const SizedBox(height: 12),
                          Text(
                            context.localized(
                              '暂无专业订单',
                              'No professional orders yet',
                            ),
                            textAlign: TextAlign.center,
                          ),
                        ],
                      )
                    : ListView.separated(
                        physics: const AlwaysScrollableScrollPhysics(),
                        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
                        itemCount: items!.length,
                        separatorBuilder: (_, __) => const SizedBox(height: 12),
                        itemBuilder: (context, index) {
                          final order = items![index];
                          return _DoctorOrderCard(
                            order: order,
                            onTap: () => _openDetail(order),
                            onMessage: widget.onOpenDirectMessage == null ||
                                    order.userId.trim().isEmpty
                                ? null
                                : () => _openMessage(order),
                          );
                        },
                      ),
              ),
      );
}

class _DoctorOrderCard extends StatelessWidget {
  const _DoctorOrderCard({
    required this.order,
    required this.onTap,
    this.onMessage,
  });

  final DoctorOrder order;
  final VoidCallback onTap;
  final VoidCallback? onMessage;

  @override
  Widget build(BuildContext context) => Card(
        margin: EdgeInsets.zero,
        clipBehavior: Clip.antiAlias,
        child: Column(
          children: [
            InkWell(
              key: Key('doctor-order-card-${order.id}'),
              onTap: onTap,
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _DoctorOrderTitleAndStatus(
                      order: order,
                      titleStyle: Theme.of(context)
                          .textTheme
                          .titleMedium
                          ?.copyWith(fontWeight: FontWeight.w700),
                    ),
                    const SizedBox(height: 14),
                    _DoctorOrderSummaryLine(
                      icon: Icons.event_outlined,
                      label: context.localized('预约时间', 'Appointment'),
                      value: order.appointmentTime == null
                          ? context.localized(
                              '预约时间待确认',
                              'Appointment not confirmed',
                            )
                          : _formatDoctorOrderDateTime(order.appointmentTime!),
                    ),
                    if (order.institutionName.trim().isNotEmpty) ...[
                      const SizedBox(height: 8),
                      _DoctorOrderSummaryLine(
                        icon: Icons.apartment_outlined,
                        label: context.localized('机构', 'Institution'),
                        value: order.institutionName,
                      ),
                    ],
                    const SizedBox(height: 12),
                    Text(
                      order.orderNo,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    const SizedBox(height: 6),
                    Align(
                      alignment: Alignment.centerRight,
                      child: Text(
                        _formatDoctorOrderAmount(order),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        textAlign: TextAlign.end,
                        style: Theme.of(context)
                            .textTheme
                            .titleSmall
                            ?.copyWith(fontWeight: FontWeight.w700),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            if (onMessage != null) ...[
              const Divider(height: 1),
              Align(
                alignment: Alignment.centerRight,
                child: TextButton.icon(
                  key: Key('doctor-order-message-${order.id}'),
                  onPressed: onMessage,
                  icon: const Icon(Icons.chat_bubble_outline_rounded, size: 18),
                  label: Text(context.localized('联系用户', 'Message customer')),
                ),
              ),
            ],
          ],
        ),
      );
}

class _DoctorOrderSummaryLine extends StatelessWidget {
  const _DoctorOrderSummaryLine({
    required this.icon,
    required this.label,
    required this.value,
  });

  final IconData icon;
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            icon,
            size: 18,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
          const SizedBox(width: 8),
          SizedBox(
            width: 92,
            child: Text(
              label,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          Expanded(child: Text(value)),
        ],
      );
}

class _DoctorOrderStatusBadge extends StatelessWidget {
  const _DoctorOrderStatusBadge({required this.status});

  final String status;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.primaryContainer,
          borderRadius: BorderRadius.circular(999),
        ),
        child: Text(
          _doctorOrderStatusLabel(context, status),
          maxLines: 3,
          overflow: TextOverflow.ellipsis,
          textAlign: TextAlign.end,
          style: Theme.of(context).textTheme.labelMedium?.copyWith(
                color: Theme.of(context).colorScheme.onPrimaryContainer,
              ),
        ),
      );
}

class _DoctorOrderTitleAndStatus extends StatelessWidget {
  const _DoctorOrderTitleAndStatus({
    required this.order,
    required this.titleStyle,
  });

  final DoctorOrder order;
  final TextStyle? titleStyle;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            order.projectName,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: titleStyle,
          ),
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerRight,
            child: _DoctorOrderStatusBadge(status: order.status),
          ),
        ],
      );
}

class DoctorOrderDetailPage extends StatefulWidget {
  const DoctorOrderDetailPage({
    required this.repository,
    required this.id,
    this.onOpenDirectMessage,
    super.key,
  });

  final ProfessionalRepository repository;
  final String id;
  final DoctorOrderDirectMessageOpener? onOpenDirectMessage;

  @override
  State<DoctorOrderDetailPage> createState() => _DoctorOrderDetailPageState();
}

class _DoctorOrderDetailPageState extends State<DoctorOrderDetailPage> {
  DoctorOrder? order;
  Object? error;
  bool busy = false;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    try {
      final v = await widget.repository.getOrder(widget.id);
      if (mounted) {
        setState(() {
          order = v;
          error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = e);
    }
  }

  Future<void> act(bool completion) async {
    var verificationCode = '';
    final code = await showDialog<String>(
      context: context,
      builder: (x) => AlertDialog(
        title: Text(context.localized('输入6位核销码', 'Enter 6-digit code')),
        content: TextField(
          onChanged: (value) => verificationCode = value,
          maxLength: 6,
          keyboardType: TextInputType.number,
        ),
        actions: [
          FilledButton(
            onPressed: () => RegExp(r'^\d{6}$').hasMatch(verificationCode)
                ? Navigator.pop(x, verificationCode)
                : null,
            child: Text(context.localized('确认', 'Confirm')),
          ),
        ],
      ),
    );
    if (code == null || !mounted) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final v = await widget.repository.actOnOrder(
        widget.id,
        code,
        completion: completion,
      );
      if (mounted) {
        setState(() {
          order = v;
          error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> _openMessage() async {
    final current = order;
    final openDirectMessage = widget.onOpenDirectMessage;
    final userId = current?.userId.trim() ?? '';
    if (current == null || openDirectMessage == null || userId.isEmpty) return;
    await openDirectMessage(
      userId,
      title: _doctorOrderMessageTitle(context, current),
    );
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(context.localized('订单详情', 'Order details'))),
        body: order == null
            ? error == null
                ? const Center(child: CircularProgressIndicator())
                : _Retry(error: error!, onRetry: load)
            : ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  _DoctorOrderDetailHeader(order: order!),
                  const SizedBox(height: 12),
                  _DoctorOrderDetailSection(
                    title: context.localized('预约信息', 'Appointment'),
                    children: [
                      _DoctorOrderDetailLine(
                        label: context.localized('项目', 'Project'),
                        value: order!.projectName,
                      ),
                      _DoctorOrderDetailLine(
                        label: context.localized('机构', 'Institution'),
                        value: order!.institutionName.trim().isEmpty
                            ? context.localized('暂无机构信息', 'Not available')
                            : order!.institutionName,
                      ),
                      _DoctorOrderDetailLine(
                        label: context.localized('预约时间', 'Appointment time'),
                        value: order!.appointmentTime == null
                            ? context.localized(
                                '预约时间待确认',
                                'Appointment not confirmed',
                              )
                            : _formatDoctorOrderDateTime(
                                order!.appointmentTime!,
                              ),
                      ),
                      _DoctorOrderDetailLine(
                        label: context.localized('数量', 'Quantity'),
                        value: '${order!.quantity}',
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  _DoctorOrderDetailSection(
                    title: context.localized('用户信息', 'Customer'),
                    children: [
                      _DoctorOrderDetailLine(
                        label: context.localized('手机号', 'Phone'),
                        value: order!.userPhone.trim().isEmpty
                            ? context.localized('暂无手机号', 'Not available')
                            : order!.userPhone,
                      ),
                      _DoctorOrderDetailLine(
                        label: context.localized('用户备注', 'Customer note'),
                        value: order!.remark.trim().isEmpty
                            ? context.localized('暂无备注', 'No customer note')
                            : order!.remark,
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  _DoctorOrderDetailSection(
                    title: context.localized('订单信息', 'Order information'),
                    children: [
                      _DoctorOrderDetailLine(
                        label: context.localized('订单号', 'Order number'),
                        value: order!.orderNo,
                      ),
                      _DoctorOrderDetailLine(
                        label: context.localized('订单金额', 'Amount'),
                        value: _formatDoctorOrderAmount(order!),
                      ),
                      _DoctorOrderDetailLine(
                        label: context.localized('下单时间', 'Created at'),
                        value: _formatDoctorOrderDateTime(order!.createdAt),
                      ),
                    ],
                  ),
                  if (error != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      '$error',
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ],
                  if (widget.onOpenDirectMessage != null &&
                      order!.userId.trim().isNotEmpty) ...[
                    const SizedBox(height: 16),
                    OutlinedButton.icon(
                      key: const Key('doctor-order-detail-message'),
                      onPressed: _openMessage,
                      icon: const Icon(Icons.chat_bubble_outline_rounded),
                      label: Text(
                        context.localized('联系用户', 'Message customer'),
                      ),
                    ),
                  ],
                  if (order!.canVerify)
                    FilledButton(
                      key: const Key('doctor-order-verify-button'),
                      onPressed: busy ? null : () => act(false),
                      child: Text(context.localized('到店核销', 'Verify visit')),
                    ),
                  if (order!.canRequestCompletion)
                    FilledButton(
                      key: const Key('doctor-order-completion-button'),
                      onPressed: busy ? null : () => act(true),
                      child:
                          Text(context.localized('申请完成', 'Request completion')),
                    ),
                  if (busy) ...[
                    const SizedBox(height: 12),
                    const Center(child: CircularProgressIndicator()),
                  ],
                ],
              ),
      );
}

class _DoctorOrderDetailHeader extends StatelessWidget {
  const _DoctorOrderDetailHeader({required this.order});

  final DoctorOrder order;

  @override
  Widget build(BuildContext context) => Card(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: _DoctorOrderTitleAndStatus(
            order: order,
            titleStyle: Theme.of(context)
                .textTheme
                .titleLarge
                ?.copyWith(fontWeight: FontWeight.w700),
          ),
        ),
      );
}

class _DoctorOrderDetailSection extends StatelessWidget {
  const _DoctorOrderDetailSection({
    required this.title,
    required this.children,
  });

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Card(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: Theme.of(context)
                    .textTheme
                    .titleMedium
                    ?.copyWith(fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 10),
              ...children,
            ],
          ),
        ),
      );
}

class _DoctorOrderDetailLine extends StatelessWidget {
  const _DoctorOrderDetailLine({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 5),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 92,
              child: Text(
                label,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(child: Text(value)),
          ],
        ),
      );
}

String _formatDoctorOrderDateTime(DateTime value) =>
    '${value.year}-${value.month.toString().padLeft(2, '0')}-'
    '${value.day.toString().padLeft(2, '0')} '
    '${value.hour.toString().padLeft(2, '0')}:'
    '${value.minute.toString().padLeft(2, '0')}';

String _formatDoctorOrderAmount(DoctorOrder order) {
  final amount = order.amount.trim();
  if (amount.isEmpty) return '--';
  final currency = order.currency.trim();
  return currency.isEmpty ? amount : '$currency $amount';
}

String _doctorOrderMessageTitle(BuildContext context, DoctorOrder order) {
  final phone = order.userPhone.trim();
  return phone.isEmpty ? context.localized('订单用户', 'Order customer') : phone;
}

String _doctorOrderStatusLabel(BuildContext context, String value) =>
    switch (value.trim().toUpperCase()) {
      'PENDING_SERVICE_FEE' => context.localized(
          '待支付旅游地接服务费',
          'Travel service fee due',
        ),
      'SERVICE_ACTIVE' => context.localized('旅游地接服务中', 'Service active'),
      'REFUND_REVIEW' => context.localized('退款审核中', 'Refund under review'),
      'REFUND_PROCESSING' => context.localized('退款处理中', 'Refund processing'),
      'PENDING_PAYMENT' => context.localized('待支付面诊金', 'Payment due'),
      'CONSULTATION_PAID' => context.localized('待到店核销', 'Awaiting visit'),
      'VERIFIED' => context.localized('待支付尾款', 'Balance due'),
      'BALANCE_PAID' => context.localized('待完成核销', 'Awaiting completion'),
      'PENDING_COMPLETION' => context.localized('待确认完成', 'Confirm completion'),
      'COMPLETED' => context.localized('已完成', 'Completed'),
      'PENDING_SETTLEMENT' => context.localized('待结算', 'Settlement pending'),
      'SETTLED' => context.localized('已结算', 'Settled'),
      'DISPUTE_MEDIATION' => context.localized('纠纷调解中', 'Dispute mediation'),
      'CANCELLED' => context.localized('已取消', 'Cancelled'),
      'REFUNDED' => context.localized('已退款', 'Refunded'),
      _ => context.localized('未知状态', 'Unknown'),
    };

class _Retry extends StatelessWidget {
  const _Retry({required this.error, required this.onRetry});
  final Object error;
  final Future<void> Function() onRetry;
  @override
  Widget build(BuildContext context) => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('$error'),
            TextButton(
              onPressed: onRetry,
              child: Text(context.localized('重试', 'Retry')),
            ),
          ],
        ),
      );
}
