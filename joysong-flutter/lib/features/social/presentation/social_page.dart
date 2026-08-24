import 'dart:async';

import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/files/app_file_picker.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_content_card.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/domain/orders_repository.dart';
import 'package:joysong_flutter/features/social/domain/content_safety.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';
import 'package:joysong_flutter/features/social/presentation/diary_detail_page.dart';
import 'package:joysong_flutter/features/social/presentation/diary_share.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';

typedef DiaryImagePicker = Future<PublicMediaDraft?> Function();

final class SocialPage extends StatefulWidget {
  const SocialPage({
    required this.controller,
    this.onCreateDiary,
    this.onEditDiary,
    this.onOpenUser,
    this.onOpenProject,
    this.onOpenDoctor,
    this.onOpenInstitution,
    super.key,
  });

  final SocialController controller;
  final VoidCallback? onCreateDiary;
  final ValueChanged<Diary>? onEditDiary;
  final ValueChanged<String>? onOpenUser;
  final void Function(String institutionId, String projectId)? onOpenProject;
  final ValueChanged<String>? onOpenDoctor;
  final ValueChanged<String>? onOpenInstitution;

  @override
  State<SocialPage> createState() => _SocialPageState();
}

final class _SocialPageState extends State<SocialPage> {
  @override
  void initState() {
    super.initState();
    if (widget.controller.diaries.isEmpty) {
      unawaited(widget.controller.loadMyDiaries(refresh: true));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(context.localized('我的日记', 'My diaries'))),
      floatingActionButton: widget.onCreateDiary == null
          ? null
          : FloatingActionButton.extended(
              onPressed: widget.onCreateDiary,
              icon: const Icon(Icons.edit_outlined),
              label: Text(context.localized('写日记', 'Write a diary')),
            ),
      body: AnimatedBuilder(
        animation: widget.controller,
        builder: (context, _) {
          final controller = widget.controller;
          if (controller.isLoadingDiaries && controller.diaries.isEmpty) {
            return const Center(child: CircularProgressIndicator());
          }
          if (controller.diaries.isEmpty) {
            return _EmptyDiaries(
              message: controller.errorMessage,
              onRetry: () => controller.loadMyDiaries(refresh: true),
              onCreate: widget.onCreateDiary,
            );
          }
          return RefreshIndicator(
            onRefresh: () async {
              await controller.loadMyDiaries(refresh: true);
            },
            child: ListView.separated(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
              itemCount: controller.diaries.length + 1,
              separatorBuilder: (_, __) => const SizedBox(height: 12),
              itemBuilder: (context, index) {
                if (index == controller.diaries.length) {
                  return _LoadMore(
                    isLoading: controller.isLoadingDiaries,
                    hasMore: controller.hasMoreDiaries,
                    onLoad: controller.loadMyDiaries,
                  );
                }
                final diary = controller.diaries[index];
                return _DiaryCard(
                  diary: diary,
                  repository: controller.repository,
                  onOpen: () => Navigator.of(context).push<void>(
                    MaterialPageRoute(
                      builder: (_) => DiaryDetailPage(
                        controller: controller,
                        diary: diary,
                        onAuthorTap:
                            diary.userId.isEmpty || widget.onOpenUser == null
                                ? null
                                : () => widget.onOpenUser!(diary.userId),
                        onProjectTap: widget.onOpenProject,
                        onDoctorTap: widget.onOpenDoctor,
                        onInstitutionTap: widget.onOpenInstitution,
                      ),
                    ),
                  ),
                  onEdit: widget.onEditDiary == null
                      ? null
                      : () => widget.onEditDiary!(diary),
                  onDelete: () => _confirmDelete(diary),
                );
              },
            ),
          );
        },
      ),
    );
  }

  Future<void> _confirmDelete(Diary diary) async {
    final confirmed = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: Text(context.localized('删除日记？', 'Delete diary?')),
            content: Text(context.localized(
              '删除后无法恢复。若网络失败，页面会自动恢复这条日记。',
              'This cannot be undone. If the request fails, the diary will be restored automatically.',
            )),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: Text(context.localized('取消', 'Cancel')),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: Text(context.localized('删除', 'Delete')),
              ),
            ],
          ),
        ) ??
        false;
    if (!confirmed) {
      return;
    }
    final result = await widget.controller.deleteDiary(diary.id);
    if (!mounted) {
      return;
    }
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(result.succeeded
            ? context.localized('日记已删除', 'Diary deleted')
            : result.message!),
      ),
    );
  }
}

final class DiaryEditorPage extends StatefulWidget {
  const DiaryEditorPage({
    required this.controller,
    this.discoverRepository,
    this.ordersRepository,
    this.diary,
    this.imagePicker,
    this.contentSafety = const SocialContentSafety(),
    super.key,
  });

  final SocialController controller;
  final DiscoverRepository? discoverRepository;
  final OrdersRepository? ordersRepository;
  final Diary? diary;
  final DiaryImagePicker? imagePicker;
  final SocialContentSafety contentSafety;

  @override
  State<DiaryEditorPage> createState() => _DiaryEditorPageState();
}

final class _DiaryEditorPageState extends State<DiaryEditorPage> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _titleController;
  late final TextEditingController _contentController;
  late final TextEditingController _tagsController;
  late DiaryStatus _status;
  late int _rating;
  Order? _order;
  late String _orderId;
  late String _institutionProjectId;
  late String _projectId;
  late String _doctorId;
  late String _institutionId;
  late String _projectName;
  late String _doctorName;
  late String _institutionName;
  late final List<String> _imageUrls;
  late final List<String> _beforeImageUrls;
  late final List<String> _afterImageUrls;
  final List<PublicMediaDraft> _pendingImages = [];
  final List<PublicMediaDraft> _pendingBeforeImages = [];
  final List<PublicMediaDraft> _pendingAfterImages = [];
  bool _isSubmitting = false;
  bool _isPickingImage = false;
  ContentSafetyAssessment? _assessment;

  @override
  void initState() {
    super.initState();
    final diary = widget.diary;
    _titleController = TextEditingController(text: diary?.title ?? '');
    _contentController = TextEditingController(text: diary?.content ?? '');
    _tagsController = TextEditingController(text: diary?.tags.join(', ') ?? '');
    _rating = diary?.rating ?? 0;
    _orderId = diary?.orderId ?? '';
    _institutionProjectId = diary?.institutionProjectId ?? '';
    _projectId = diary?.projectId ?? '';
    _doctorId = diary?.doctorId ?? '';
    _institutionId = diary?.institutionId ?? '';
    _projectName = diary?.projectName ?? '';
    _doctorName = diary?.doctorName ?? '';
    _institutionName = diary?.institutionName ?? '';
    _imageUrls = List.of(diary?.images ?? const []);
    _beforeImageUrls = List.of(diary?.beforeImages ?? const []);
    _afterImageUrls = List.of(diary?.afterImages ?? const []);
    _status = diary?.status.toLowerCase() == DiaryStatus.draft.wireValue
        ? DiaryStatus.draft
        : DiaryStatus.published;
  }

  @override
  void dispose() {
    _titleController.dispose();
    _contentController.dispose();
    _tagsController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final editing = widget.diary != null;
    return Scaffold(
      appBar: AppBar(
        title: Text(editing
            ? context.localized('编辑日记', 'Edit diary')
            : context.localized('写日记', 'Write a diary')),
      ),
      body: SafeArea(
        child: Form(
          key: _formKey,
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              TextFormField(
                controller: _titleController,
                maxLength: 200,
                textInputAction: TextInputAction.next,
                decoration: InputDecoration(
                  labelText: context.localized('标题', 'Title'),
                  hintText: context.localized('用一句话概括你的体验',
                      'Summarize your experience in one sentence'),
                ),
                validator: (value) => value == null || value.trim().isEmpty
                    ? context.localized('请输入标题', 'Please enter a title')
                    : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _contentController,
                minLines: 8,
                maxLines: 16,
                maxLength: 20000,
                decoration: InputDecoration(
                  labelText: context.localized('正文', 'Content'),
                  alignLabelWithHint: true,
                  hintText: context.localized('分享真实体验，避免填写隐私信息',
                      'Share your real experience without including private information'),
                ),
                validator: (value) => value == null || value.trim().isEmpty
                    ? context.localized('请输入正文', 'Please enter the content')
                    : null,
              ),
              const SizedBox(height: 8),
              _AssociationPicker(
                order: _order,
                orderId: _orderId,
                projectName: _projectName,
                doctorName: _doctorName,
                institutionName: _institutionName,
                canPickOrder: widget.ordersRepository != null && !_isSubmitting,
                canPickManually:
                    widget.discoverRepository != null && !_isSubmitting,
                canClear: !_isSubmitting &&
                    [_orderId, _projectId, _doctorId, _institutionId]
                        .any((value) => value.isNotEmpty),
                onPickOrder: _pickOrder,
                onPick: _pickAssociation,
                onClear: _clearAssociations,
              ),
              const SizedBox(height: 12),
              Text(Localizations.localeOf(context).languageCode == 'en'
                  ? 'Rating (optional)'
                  : '体验评分（选填）'),
              Row(
                children: [
                  for (var value = 1; value <= 5; value++)
                    IconButton(
                      onPressed: _isSubmitting
                          ? null
                          : () => setState(
                              () => _rating = _rating == value ? 0 : value),
                      icon: Icon(value <= _rating
                          ? Icons.star_rounded
                          : Icons.star_border_rounded),
                    ),
                ],
              ),
              TextFormField(
                controller: _tagsController,
                decoration: InputDecoration(
                  labelText:
                      Localizations.localeOf(context).languageCode == 'en'
                          ? 'Tags (comma separated)'
                          : '标签（逗号分隔）',
                  prefixIcon: const Icon(Icons.tag),
                ),
              ),
              const SizedBox(height: 8),
              _buildImages(context),
              const SizedBox(height: 12),
              _buildImageGroup(
                context,
                title: Localizations.localeOf(context).languageCode == 'en'
                    ? 'Before photos'
                    : '术前照片',
                urls: _beforeImageUrls,
                pending: _pendingBeforeImages,
                keyName: 'before',
              ),
              const SizedBox(height: 12),
              _buildImageGroup(
                context,
                title: Localizations.localeOf(context).languageCode == 'en'
                    ? 'After photos'
                    : '术后照片',
                urls: _afterImageUrls,
                pending: _pendingAfterImages,
                keyName: 'after',
              ),
              const SizedBox(height: 16),
              SegmentedButton<DiaryStatus>(
                segments: [
                  ButtonSegment(
                    value: DiaryStatus.published,
                    label: Text(context.localized('公开发布', 'Publish publicly')),
                    icon: const Icon(Icons.public),
                  ),
                  ButtonSegment(
                    value: DiaryStatus.draft,
                    label: Text(context.localized('设为私密', 'Set as private')),
                    icon: const Icon(Icons.lock_outline),
                  ),
                ],
                selected: {_status},
                onSelectionChanged: _isSubmitting
                    ? null
                    : (selection) => setState(() => _status = selection.first),
              ),
              const SizedBox(height: 16),
              const _SafetyNotice(),
              if (_assessment?.needsConfirmation ?? false) ...[
                const SizedBox(height: 12),
                _AssessmentNotice(assessment: _assessment!),
              ],
              const SizedBox(height: 24),
              FilledButton(
                key: const Key('diary-submit'),
                onPressed: _isSubmitting ? null : _submit,
                child: _isSubmitting
                    ? const SizedBox.square(
                        dimension: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : Text(_status == DiaryStatus.draft
                        ? context.localized('设为私密', 'Set as private')
                        : context.localized('发布', 'Publish')),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildImageGroup(
    BuildContext context, {
    required String title,
    required List<String> urls,
    required List<PublicMediaDraft> pending,
    required String keyName,
  }) {
    final count = urls.length + pending.length;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Expanded(child: Text('$title ($count/9)')),
        TextButton.icon(
          key: Key('diary-add-$keyName-image'),
          onPressed:
              _isSubmitting || count >= 9 ? null : () => _pickImageFor(pending),
          icon: const Icon(Icons.add_photo_alternate_outlined),
          label: Text(Localizations.localeOf(context).languageCode == 'en'
              ? 'Add'
              : '添加'),
        ),
      ]),
      Wrap(spacing: 8, runSpacing: 8, children: [
        for (var i = 0; i < urls.length; i++)
          _DiaryImageTile(
            label: title,
            onRemove: () => setState(() => urls.removeAt(i)),
          ),
        for (var i = 0; i < pending.length; i++)
          _DiaryImageTile(
            label: pending[i].fileName,
            bytes: pending[i].bytes,
            onRemove: () => setState(() => pending.removeAt(i)),
          ),
      ]),
    ]);
  }

  Future<void> _pickImageFor(List<PublicMediaDraft> target) async {
    final selected = await (widget.imagePicker ?? _pickDiaryImage)();
    if (selected != null && mounted) setState(() => target.add(selected));
  }

  Future<void> _pickAssociation(DiscoverContentType type) async {
    final repository = widget.discoverRepository;
    if (repository == null) return;
    final selected = await Navigator.of(context).push<DiscoverItem>(
      MaterialPageRoute(
        builder: (_) => _DiaryEntityPickerPage(
          repository: repository,
          type: type,
        ),
      ),
    );
    if (selected == null || !mounted) return;
    setState(() {
      _order = null;
      _orderId = '';
      _institutionProjectId = '';
      switch (type) {
        case DiscoverContentType.project:
          _projectId = selected.id;
          _projectName = selected.title;
        case DiscoverContentType.doctor:
          _doctorId = selected.id;
          _doctorName = selected.title;
        case DiscoverContentType.institution:
          _institutionId = selected.id;
          _institutionName = selected.title;
        default:
          break;
      }
    });
  }

  Future<void> _pickOrder() async {
    final repository = widget.ordersRepository;
    if (repository == null) return;
    final selected = await Navigator.of(context).push<Order>(
      MaterialPageRoute(builder: (_) => _DiaryOrderPickerPage(repository)),
    );
    if (selected == null || !mounted) return;
    setState(() {
      _order = selected;
      _orderId = selected.id;
      _institutionProjectId = selected.institutionProjectId;
      _projectId = selected.projectId;
      _doctorId = selected.doctorId;
      _institutionId = selected.institutionId;
      _projectName = selected.projectName;
      _doctorName = selected.doctorName;
      _institutionName = selected.institutionName;
    });
  }

  void _clearAssociations() {
    setState(() {
      _order = null;
      _orderId = '';
      _institutionProjectId = '';
      _projectId = '';
      _doctorId = '';
      _institutionId = '';
      _projectName = '';
      _doctorName = '';
      _institutionName = '';
    });
  }

  Widget _buildImages(BuildContext context) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final count = _imageUrls.length + _pendingImages.length;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                english ? 'Photos ($count/9)' : '图片（$count/9）',
                style: Theme.of(context).textTheme.titleSmall,
              ),
            ),
            TextButton.icon(
              key: const Key('diary-add-image'),
              onPressed: _isSubmitting || _isPickingImage || count >= 9
                  ? null
                  : _pickImage,
              icon: _isPickingImage
                  ? const SizedBox.square(
                      dimension: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.add_photo_alternate_outlined),
              label: Text(english ? 'Add photo' : '添加图片'),
            ),
          ],
        ),
        if (count == 0)
          Text(
            english
                ? 'You can upload up to 9 JPG, PNG, or WebP images.'
                : '最多上传 9 张 JPG、PNG 或 WebP 图片。',
            style: Theme.of(context).textTheme.bodySmall,
          )
        else
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (var index = 0; index < _imageUrls.length; index += 1)
                _DiaryImageTile(
                  label: english ? 'Uploaded photo' : '已上传图片',
                  onRemove: _isSubmitting
                      ? null
                      : () => setState(() => _imageUrls.removeAt(index)),
                ),
              for (var index = 0; index < _pendingImages.length; index += 1)
                _DiaryImageTile(
                  label: _pendingImages[index].fileName,
                  bytes: _pendingImages[index].bytes,
                  onRemove: _isSubmitting
                      ? null
                      : () => setState(() => _pendingImages.removeAt(index)),
                ),
            ],
          ),
      ],
    );
  }

  Future<void> _pickImage() async {
    final picker = widget.imagePicker ?? _pickDiaryImage;
    setState(() => _isPickingImage = true);
    try {
      final selected = await picker();
      if (selected != null && mounted) {
        setState(() => _pendingImages.add(selected));
      }
    } catch (_) {
      if (!mounted) return;
      final english = Localizations.localeOf(context).languageCode == 'en';
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            english
                ? 'Unable to read the selected image.'
                : '无法读取所选图片，请更换图片后重试',
          ),
        ),
      );
    } finally {
      if (mounted) setState(() => _isPickingImage = false);
    }
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) {
      return;
    }
    final assessment = widget.contentSafety.assess(
      '${_titleController.text}\n${_contentController.text}',
    );
    if (assessment.needsConfirmation && _assessment == null) {
      setState(() => _assessment = assessment);
      return;
    }

    setState(() => _isSubmitting = true);
    final title = _titleController.text.trim();
    final content = _contentController.text.trim();
    final tags = _tagsController.text
        .split(RegExp(r'[,，]'))
        .map((value) => value.trim())
        .where((value) => value.isNotEmpty)
        .toSet()
        .take(10)
        .toList(growable: false);
    final uploadedUrls = List<String>.of(_imageUrls);
    for (final image in _pendingImages) {
      final upload = await widget.controller.uploadPublicMedia(image);
      if (!upload.succeeded || upload.value == null) {
        if (!mounted) return;
        setState(() => _isSubmitting = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(upload.message ??
                context.localized('图片上传失败', 'Failed to upload image')),
          ),
        );
        return;
      }
      uploadedUrls.add(upload.value!);
    }
    final beforeUrls =
        await _uploadGroup(_beforeImageUrls, _pendingBeforeImages);
    final afterUrls = await _uploadGroup(_afterImageUrls, _pendingAfterImages);
    if (beforeUrls == null || afterUrls == null || !mounted) {
      if (mounted) setState(() => _isSubmitting = false);
      return;
    }
    final existing = widget.diary;
    final SocialActionResult<Diary> result;
    if (existing == null) {
      result = await widget.controller.publishDiary(
        DiaryDraft(
          title: title,
          content: content,
          images: uploadedUrls,
          tags: tags,
          rating: _rating == 0 ? null : _rating,
          projectId: _projectId,
          doctorId: _doctorId,
          institutionId: _institutionId,
          institutionProjectId: _institutionProjectId,
          orderId: _orderId,
          beforeImages: beforeUrls,
          afterImages: afterUrls,
          status: _status,
        ),
      );
    } else {
      result = await widget.controller.updateDiary(
        existing.id,
        DiaryUpdate(
          title: title,
          content: content,
          images: uploadedUrls,
          tags: tags,
          rating: _rating,
          projectId: _projectId,
          doctorId: _doctorId,
          institutionId: _institutionId,
          institutionProjectId: _institutionProjectId,
          orderId: _orderId,
          beforeImages: beforeUrls,
          afterImages: afterUrls,
          status: _status,
        ),
      );
    }
    if (!mounted) {
      return;
    }
    setState(() => _isSubmitting = false);
    if (result.succeeded) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(existing == null
              ? context.localized('日记已保存', 'Diary saved')
              : context.localized('日记已更新', 'Diary updated')),
        ),
      );
      Navigator.maybePop(context, result.value);
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(result.message!)),
      );
    }
  }

  Future<List<String>?> _uploadGroup(
    List<String> existing,
    List<PublicMediaDraft> pending,
  ) async {
    final result = List<String>.of(existing);
    for (final image in pending) {
      final upload = await widget.controller.uploadPublicMedia(image);
      if (!upload.succeeded || upload.value == null) return null;
      result.add(upload.value!);
    }
    return result;
  }
}

class _AssociationPicker extends StatelessWidget {
  const _AssociationPicker({
    required this.order,
    required this.orderId,
    required this.projectName,
    required this.doctorName,
    required this.institutionName,
    required this.canPickOrder,
    required this.canPickManually,
    required this.canClear,
    required this.onPickOrder,
    required this.onPick,
    required this.onClear,
  });
  final Order? order;
  final String orderId;
  final String projectName;
  final String doctorName;
  final String institutionName;
  final bool canPickOrder;
  final bool canPickManually;
  final bool canClear;
  final VoidCallback onPickOrder;
  final ValueChanged<DiscoverContentType> onPick;
  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  english ? 'Diary associations' : '日记关联',
                  style: Theme.of(context).textTheme.titleSmall,
                ),
              ),
              if (canClear)
                TextButton(
                  onPressed: onClear,
                  child: Text(english ? 'Clear all' : '取消关联'),
                ),
            ],
          ),
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.receipt_long_outlined),
            title: Text(english ? 'Related order' : '关联订单'),
            subtitle: Text(
              order == null
                  ? (orderId.isEmpty
                      ? (english ? 'Select from my orders' : '从我的订单中选择')
                      : orderId)
                  : '${order!.projectName} · ${order!.orderNo}',
            ),
            trailing: const Icon(Icons.chevron_right),
            onTap: canPickOrder ? onPickOrder : null,
          ),
          const Divider(height: 1),
          Align(
            alignment: AlignmentDirectional.centerStart,
            child: Padding(
              padding: const EdgeInsets.only(top: 12, bottom: 4),
              child: Text(
                english ? 'Manual association' : '手动关联',
                style: Theme.of(context).textTheme.labelLarge,
              ),
            ),
          ),
          _row(context, DiscoverContentType.project,
              english ? 'Related project' : '关联项目', projectName),
          _row(context, DiscoverContentType.doctor,
              english ? 'Related doctor' : '关联医生', doctorName),
          _row(context, DiscoverContentType.institution,
              english ? 'Related institution' : '关联机构', institutionName),
        ]),
      ),
    );
  }

  Widget _row(BuildContext context, DiscoverContentType type, String label,
          String value) =>
      ListTile(
        contentPadding: EdgeInsets.zero,
        title: Text(label),
        subtitle: Text(value.isEmpty
            ? (Localizations.localeOf(context).languageCode == 'en'
                ? 'Optional'
                : '选填')
            : value),
        trailing: const Icon(Icons.chevron_right),
        onTap: canPickManually ? () => onPick(type) : null,
      );
}

class _DiaryOrderPickerPage extends StatefulWidget {
  const _DiaryOrderPickerPage(this.repository);

  final OrdersRepository repository;

  @override
  State<_DiaryOrderPickerPage> createState() => _DiaryOrderPickerPageState();
}

class _DiaryOrderPickerPageState extends State<_DiaryOrderPickerPage> {
  List<Order> _orders = const [];
  bool _loading = true;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final orders = await widget.repository.getOrders(offset: 0, limit: 100);
      if (mounted) setState(() => _orders = orders);
    } catch (error) {
      if (mounted) setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final english = context.isEnglish;
    return Scaffold(
      appBar: AppBar(title: Text(english ? 'Select order' : '选择关联订单')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? Center(
                  child: FilledButton.tonalIcon(
                    onPressed: _load,
                    icon: const Icon(Icons.refresh),
                    label: Text(english ? 'Try again' : '重新加载'),
                  ),
                )
              : _orders.isEmpty
                  ? Center(child: Text(english ? 'No orders' : '暂无订单'))
                  : ListView.separated(
                      padding: const EdgeInsets.symmetric(vertical: 8),
                      itemCount: _orders.length,
                      separatorBuilder: (_, __) => const Divider(height: 1),
                      itemBuilder: (context, index) {
                        final order = _orders[index];
                        final details = [
                          order.institutionName,
                          order.doctorName,
                        ].where((value) => value.isNotEmpty).join(' · ');
                        return ListTile(
                          leading: const CircleAvatar(
                            child: Icon(Icons.receipt_long_outlined),
                          ),
                          title: Text(order.projectName),
                          subtitle: Text(
                            [order.orderNo, details]
                                .where((value) => value.isNotEmpty)
                                .join('\n'),
                          ),
                          isThreeLine: details.isNotEmpty,
                          trailing: const Icon(Icons.chevron_right),
                          onTap: () => Navigator.pop(context, order),
                        );
                      },
                    ),
    );
  }
}

class _DiaryEntityPickerPage extends StatefulWidget {
  const _DiaryEntityPickerPage({required this.repository, required this.type});
  final DiscoverRepository repository;
  final DiscoverContentType type;

  @override
  State<_DiaryEntityPickerPage> createState() => _DiaryEntityPickerPageState();
}

class _DiaryEntityPickerPageState extends State<_DiaryEntityPickerPage> {
  final _query = TextEditingController();
  List<DiscoverItem> _items = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _query.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    final page = await widget.repository.loadPage(
      type: widget.type,
      offset: 0,
      limit: 50,
      query: _query.text.trim(),
    );
    if (mounted) {
      setState(() {
        _items = page.items;
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(_entityTypeLabel(context, widget.type))),
        body: Column(children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: SearchBar(
              controller: _query,
              hintText: Localizations.localeOf(context).languageCode == 'en'
                  ? 'Search'
                  : '搜索',
              onSubmitted: (_) => _load(),
            ),
          ),
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : ListView.builder(
                    itemCount: _items.length,
                    itemBuilder: (_, index) {
                      final item = _items[index];
                      return ListTile(
                        leading: item.imageUrl.isEmpty
                            ? const CircleAvatar(
                                child: Icon(Icons.image_outlined))
                            : CircleAvatar(
                                backgroundImage: NetworkImage(item.imageUrl)),
                        title: Text(item.title),
                        subtitle: Text(item.subtitle, maxLines: 2),
                        onTap: () => Navigator.pop(context, item),
                      );
                    },
                  ),
          ),
        ]),
      );
}

Future<PublicMediaDraft?> _pickDiaryImage() async {
  final selected = await const AppFilePicker().pickImage();
  if (selected == null) return null;
  return PublicMediaDraft(
    bytes: selected.bytes,
    fileName: selected.fileName,
    mimeType: selected.mimeType,
    purpose: PublicMediaPurpose.diary,
  );
}

String _entityTypeLabel(BuildContext context, DiscoverContentType type) =>
    switch (type) {
      DiscoverContentType.project => context.localized('项目', 'Project'),
      DiscoverContentType.doctor => context.localized('医生', 'Doctor'),
      DiscoverContentType.institution => context.localized('机构', 'Institution'),
      _ => context.localized('关联内容', 'Related item'),
    };

String _safetyNotice(BuildContext context, String notice) {
  if (!context.isEnglish) return notice;
  return switch (notice) {
    '内容疑似包含身份证号，请确认已移除敏感身份信息。' =>
      'The content may include an ID number. Please remove sensitive identity information.',
    '内容疑似包含手机号，公开发布可能带来隐私风险。' =>
      'The content may include a phone number, which could create privacy risks if published.',
    '请避免使用绝对化医疗效果描述，以真实体验为准。' =>
      'Avoid absolute medical claims and describe only your actual experience.',
    _ => notice,
  };
}

class _DiaryImageTile extends StatelessWidget {
  const _DiaryImageTile({
    required this.label,
    required this.onRemove,
    this.bytes,
  });

  final String label;
  final Uint8List? bytes;
  final VoidCallback? onRemove;

  @override
  Widget build(BuildContext context) {
    final imageBytes = bytes;
    return SizedBox.square(
      dimension: 88,
      child: Stack(
        fit: StackFit.expand,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: imageBytes == null
                ? ColoredBox(
                    color: Theme.of(context).colorScheme.surfaceContainerHigh,
                    child: const Icon(Icons.image_outlined),
                  )
                : Image.memory(
                    imageBytes,
                    fit: BoxFit.cover,
                    errorBuilder: (_, __, ___) => const Icon(
                      Icons.broken_image_outlined,
                    ),
                    semanticLabel: label,
                  ),
          ),
          Positioned(
            right: 2,
            top: 2,
            child: IconButton.filled(
              tooltip: Localizations.localeOf(context).languageCode == 'en'
                  ? 'Remove photo'
                  : '移除图片',
              onPressed: onRemove,
              icon: const Icon(Icons.close, size: 16),
              constraints: const BoxConstraints.tightFor(
                width: 30,
                height: 30,
              ),
              padding: EdgeInsets.zero,
            ),
          ),
        ],
      ),
    );
  }
}

final class _DiaryCard extends StatefulWidget {
  const _DiaryCard({
    required this.diary,
    required this.repository,
    required this.onOpen,
    required this.onDelete,
    this.onEdit,
  });

  final Diary diary;
  final SocialRepository repository;
  final VoidCallback onOpen;
  final VoidCallback? onEdit;
  final VoidCallback onDelete;

  @override
  State<_DiaryCard> createState() => _DiaryCardState();
}

final class _DiaryCardState extends State<_DiaryCard> {
  DiaryPreviewCard? _cachedCard;
  Locale? _locale;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final locale = Localizations.localeOf(context);
    if (_locale != locale) {
      _locale = locale;
      _cachedCard = null;
    }
  }

  @override
  void didUpdateWidget(_DiaryCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!_sameDiaryCard(oldWidget.diary, widget.diary) ||
        oldWidget.repository != widget.repository ||
        (oldWidget.onEdit == null) != (widget.onEdit == null)) {
      _cachedCard = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final diary = widget.diary;
    return _cachedCard ??= DiaryPreviewCard(
      key: Key('open-diary-${diary.id}'),
      title: diary.title,
      content: diary.content,
      authorName: diary.authorName,
      authorAvatar: diary.authorAvatar,
      publishDate: diary.publishDate.isNotEmpty
          ? diary.publishDate
          : diary.createdAt?.toIso8601String() ?? '',
      projectName: diary.projectName,
      images: diary.images,
      beforeImages: diary.beforeImages,
      afterImages: diary.afterImages,
      likeCount: diary.likeCount,
      favoriteCount: diary.favoriteCount,
      commentCount: diary.commentCount,
      enableAutoTranslation: true,
      autoTranslationContentId: 'diary:${diary.id}',
      isLiked: diary.isLiked,
      isFavorited: false,
      onTap: () => widget.onOpen(),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _StatusChip(status: diary.status),
          PopupMenuButton<String>(
            tooltip: context.localized('更多操作', 'More actions'),
            onSelected: (value) {
              if (value == 'share') {
                shareDiary(context, diary, repository: widget.repository);
              } else if (value == 'edit') {
                widget.onEdit?.call();
              } else if (value == 'delete') {
                widget.onDelete();
              }
            },
            itemBuilder: (_) => [
              PopupMenuItem(
                value: 'share',
                child: Row(
                  children: [
                    const Icon(Icons.share_outlined, size: 20),
                    const SizedBox(width: 12),
                    Text(context.localized('分享', 'Share')),
                  ],
                ),
              ),
              if (widget.onEdit != null)
                PopupMenuItem(
                  value: 'edit',
                  child: Text(context.localized('编辑', 'Edit')),
                ),
              PopupMenuItem(
                value: 'delete',
                child: Text(context.localized('删除', 'Delete')),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

bool _sameDiaryCard(Diary first, Diary second) =>
    first.id == second.id &&
    first.title == second.title &&
    first.content == second.content &&
    first.authorName == second.authorName &&
    first.authorAvatar == second.authorAvatar &&
    first.publishDate == second.publishDate &&
    first.createdAt == second.createdAt &&
    first.projectName == second.projectName &&
    _sameStrings(first.images, second.images) &&
    _sameStrings(first.beforeImages, second.beforeImages) &&
    _sameStrings(first.afterImages, second.afterImages) &&
    first.likeCount == second.likeCount &&
    first.favoriteCount == second.favoriteCount &&
    first.commentCount == second.commentCount &&
    first.isLiked == second.isLiked &&
    first.status == second.status;

bool _sameStrings(List<String> first, List<String> second) {
  if (first.length != second.length) return false;
  for (var index = 0; index < first.length; index += 1) {
    if (first[index] != second[index]) return false;
  }
  return true;
}

final class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.status});
  final String status;

  @override
  Widget build(BuildContext context) {
    final published = status.toLowerCase() == DiaryStatus.published.wireValue;
    return Chip(
      visualDensity: VisualDensity.compact,
      avatar: Icon(published ? Icons.public : Icons.lock_outline, size: 16),
      label: Text(published
          ? context.localized('已发布', 'Published')
          : context.localized('私密', 'Private')),
    );
  }
}

final class _LoadMore extends StatelessWidget {
  const _LoadMore({
    required this.isLoading,
    required this.hasMore,
    required this.onLoad,
  });
  final bool isLoading;
  final bool hasMore;
  final Future<Object?> Function() onLoad;

  @override
  Widget build(BuildContext context) {
    if (!hasMore) {
      return Center(child: Text(context.localized('没有更多了', 'No more diaries')));
    }
    return Center(
      child: TextButton(
        onPressed: isLoading ? null : () => onLoad(),
        child: Text(isLoading
            ? context.localized('加载中…', 'Loading…')
            : context.localized('加载更多', 'Load more')),
      ),
    );
  }
}

final class _EmptyDiaries extends StatelessWidget {
  const _EmptyDiaries({required this.onRetry, this.message, this.onCreate});
  final String? message;
  final Future<Object?> Function() onRetry;
  final VoidCallback? onCreate;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.auto_stories_outlined, size: 48),
            const SizedBox(height: 12),
            Text(message ??
                context.localized(
                  '还没有日记，记录你的第一次体验吧',
                  'No diaries yet. Record your first experience.',
                )),
            const SizedBox(height: 16),
            if (message != null)
              OutlinedButton(
                  onPressed: () => onRetry(),
                  child: Text(context.localized('重试', 'Retry')))
            else if (onCreate != null)
              FilledButton(
                  onPressed: onCreate,
                  child: Text(context.localized('写日记', 'Write a diary'))),
          ],
        ),
      ),
    );
  }
}

final class _SafetyNotice extends StatelessWidget {
  const _SafetyNotice();

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: context.localized('内容安全提示', 'Content safety notice'),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.privacy_tip_outlined, size: 20),
          const SizedBox(width: 8),
          Expanded(
              child: Text(context.localized(
            SocialContentSafety.publicationNotice,
            'Published content may be viewed, commented on, or reported. Do not include ID numbers, contact details, or verification documents.',
          ))),
        ],
      ),
    );
  }
}

final class _AssessmentNotice extends StatelessWidget {
  const _AssessmentNotice({required this.assessment});
  final ContentSafetyAssessment assessment;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.errorContainer,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(context.localized('请确认以下风险，再次点击即可继续：',
                'Please review these risks, then tap again to continue:')),
            const SizedBox(height: 4),
            for (final notice in assessment.notices)
              Text('• ${_safetyNotice(context, notice)}'),
          ],
        ),
      ),
    );
  }
}
