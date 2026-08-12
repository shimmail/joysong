import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/professional_management/data/professional_repository.dart';
import 'package:joysong_flutter/features/professional_management/domain/professional_models.dart';

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
  const DoctorOrdersPage({required this.repository, super.key});
  final ProfessionalRepository repository;
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
                child: ListView(
                  children: [
                    for (final item in items!)
                      ListTile(
                        title: Text(item.projectName),
                        subtitle: Text('${item.orderNo} · ${item.status}'),
                        onTap: () => Navigator.push(
                          context,
                          MaterialPageRoute(
                            builder: (_) => DoctorOrderDetailPage(
                              repository: widget.repository,
                              id: item.id,
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
      );
}

class DoctorOrderDetailPage extends StatefulWidget {
  const DoctorOrderDetailPage({
    required this.repository,
    required this.id,
    super.key,
  });
  final ProfessionalRepository repository;
  final String id;
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
    final c = TextEditingController();
    final code = await showDialog<String>(
      context: context,
      builder: (x) => AlertDialog(
        title: Text(context.localized('输入6位核销码', 'Enter 6-digit code')),
        content: TextField(
          controller: c,
          maxLength: 6,
          keyboardType: TextInputType.number,
        ),
        actions: [
          FilledButton(
            onPressed: () => RegExp(r'^\d{6}$').hasMatch(c.text)
                ? Navigator.pop(x, c.text)
                : null,
            child: Text(context.localized('确认', 'Confirm')),
          ),
        ],
      ),
    );
    c.dispose();
    if (code == null || !mounted) return;
    setState(() => busy = true);
    try {
      final v = await widget.repository.actOnOrder(
        widget.id,
        code,
        completion: completion,
      );
      if (mounted) setState(() => order = v);
    } catch (e) {
      if (mounted) setState(() => error = e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
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
                  Text(
                    order!.projectName,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  Text(order!.orderNo),
                  Text(order!.status),
                  if (error != null) Text('$error'),
                  if (order!.canVerify)
                    FilledButton(
                      onPressed: busy ? null : () => act(false),
                      child: Text(context.localized('到店核销', 'Verify visit')),
                    ),
                  if (order!.canRequestCompletion)
                    FilledButton(
                      onPressed: busy ? null : () => act(true),
                      child:
                          Text(context.localized('申请完成', 'Request completion')),
                    ),
                ],
              ),
      );
}

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
