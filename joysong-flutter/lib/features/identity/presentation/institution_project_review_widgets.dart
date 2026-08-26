import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/institution_project_preview_body.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';

final class InstitutionProjectLegacyReviewPreview {
  const InstitutionProjectLegacyReviewPreview({
    required this.model,
    required this.scheduleNote,
  });

  final InstitutionProjectPreviewModel model;
  final String scheduleNote;
}

abstract final class InstitutionProjectPreviewAdapters {
  static InstitutionProjectPreviewModel fromV2(
      DoctorProjectChangeRequest request) {
    if (request.payloadVersion != 2 || request.proposedProject == null) {
      throw ArgumentError.value(request, 'request', '需要有效的 v2 项目快照');
    }
    final project = request.proposedProject!;
    return InstitutionProjectPreviewModel(
      name: project.name,
      institutionName: request.institutionName,
      price: request.proposedDoctorPrice!.toDouble(),
      currency: 'USD',
      salesCount: project.salesCount,
      tags: project.tags,
      slogan: _blankToNull(project.slogan),
      description: _blankToNull(project.description),
      detailContent: project.detailContent,
      coverImage: _blankToNull(project.coverImage),
      images: institutionProjectPreviewImages(
        coverImage: project.coverImage,
        gallery: project.images,
      ),
    );
  }

  static InstitutionProjectPreviewModel fromCreation(
    InstitutionProjectRequestDraft request, {
    required String institutionName,
  }) =>
      InstitutionProjectPreviewModel(
        name: _blankToNull(request.name) ?? request.projectId,
        institutionName: institutionName,
        price: request.price.toDouble(),
        currency: request.currency.trim().toUpperCase(),
        salesCount: request.salesCount,
        tags: request.tags ?? const [],
        slogan: _blankToNull(request.slogan),
        description: _blankToNull(request.description),
        detailContent: _blankToNull(request.detailContent),
        coverImage: _blankToNull(request.coverImage),
        images: institutionProjectPreviewImages(
          coverImage: request.coverImage,
          gallery: request.images ?? const [],
        ),
      );

  static InstitutionProjectPreviewModel fromV2Current(
    DoctorProjectChangeRequest request,
  ) =>
      _fromV2Snapshot(
        request,
        request.currentProject,
        request.currentDoctorPrice,
      );

  static InstitutionProjectPreviewModel fromV2Latest(
    DoctorProjectChangeRequest request,
  ) =>
      _fromV2Snapshot(
        request,
        request.latestProject,
        request.latestDoctorPrice,
      );

  static InstitutionProjectPreviewModel _fromV2Snapshot(
    DoctorProjectChangeRequest request,
    DoctorInstitutionProjectSnapshot? project,
    num? doctorPrice,
  ) {
    if (request.payloadVersion != 2 || project == null || doctorPrice == null) {
      throw ArgumentError.value(request, 'request', '需要有效的 v2 项目快照');
    }
    return InstitutionProjectPreviewModel(
      name: project.name,
      institutionName: request.institutionName,
      price: doctorPrice.toDouble(),
      currency: 'USD',
      salesCount: project.salesCount,
      tags: project.tags,
      slogan: _blankToNull(project.slogan),
      description: _blankToNull(project.description),
      detailContent: project.detailContent,
      coverImage: _blankToNull(project.coverImage),
      images: institutionProjectPreviewImages(
        coverImage: project.coverImage,
        gallery: project.images,
      ),
    );
  }

  static InstitutionProjectLegacyReviewPreview fromV1(
    DoctorProjectChangeRequest request,
  ) =>
      InstitutionProjectLegacyReviewPreview(
        model: InstitutionProjectPreviewModel(
          name: request.projectName,
          institutionName: request.institutionName,
          price: request.priceSuggestion?.toDouble() ?? 0,
          currency: 'USD',
          salesCount: 0,
          tags: request.serviceTags,
          slogan: null,
          description: _blankToNull(request.serviceDescription),
          detailContent: null,
          coverImage: _blankToNull(request.coverImage),
          images: institutionProjectPreviewImages(
            coverImage: request.coverImage,
            gallery: request.images,
          ),
        ),
        scheduleNote: request.scheduleNote,
      );
}

abstract final class ProfessionalProjectRequestPreviewAdapter {
  static InstitutionProjectPreviewModel fromRequest(
    ProfessionalProjectRequest request,
  ) =>
      InstitutionProjectPreviewModel(
        name: _blankToNull(request.name) ?? request.id,
        institutionName:
            _blankToNull(request.institutionName) ?? request.institutionId ?? '-',
        price: (request.price ?? 0).toDouble(),
        currency: request.currency.trim().toUpperCase(),
        salesCount: request.salesCount,
        tags: request.tags ?? const [],
        slogan: _blankToNull(request.slogan),
        description: _blankToNull(request.description),
        detailContent: _blankToNull(request.detailContent),
        coverImage: _blankToNull(request.coverImage),
        images: institutionProjectPreviewImages(
          coverImage: request.coverImage,
          gallery: request.images ?? const [],
        ),
      );
}

final class InstitutionProjectReviewItem {
  const InstitutionProjectReviewItem({
    required this.id,
    required this.institutionId,
    required this.institutionName,
    required this.projectName,
    required this.platformProjectName,
    required this.doctorName,
    required this.price,
    required this.currency,
    required this.requestStatus,
    required this.proposedDoctorActive,
    required this.preview,
    required this.valid,
    required this.creation,
    this.currentPreview,
    this.latestPreview,
    this.currentCategory,
    this.proposedCategory,
    this.latestCategory,
    this.currentDoctorActive,
    this.latestDoctorActive,
    this.proposedTravelGroundServiceFee,
    this.sharedChanged = false,
    this.legacyScheduleNote,
  });

  factory InstitutionProjectReviewItem.fromDoctorChange(
    DoctorProjectChangeRequest request,
  ) {
    final strictV2 = request.payloadVersion == 2 &&
        request.hasCompleteSnapshot &&
        request.currentProject != null &&
        request.proposedProject != null &&
        request.currentDoctorPrice != null &&
        request.proposedDoctorPrice != null;
    if (strictV2) {
      return InstitutionProjectReviewItem(
        id: request.id,
        institutionId: request.institutionId,
        institutionName: request.institutionName,
        projectName: request.projectName,
        platformProjectName: request.platformProjectName,
        doctorName: request.doctorName,
        price: request.proposedDoctorPrice!.toDouble(),
        currency: 'USD',
        requestStatus: request.status,
        proposedDoctorActive: request.proposedDoctorActive,
        currentDoctorActive: request.currentDoctorActive,
        latestDoctorActive: request.latestDoctorActive,
        currentCategory: request.currentProject!.category,
        proposedCategory: request.proposedProject!.category,
        latestCategory: request.latestProject?.category,
        proposedTravelGroundServiceFee:
            request.travelGroundServiceFee?.toDouble(),
        preview: InstitutionProjectPreviewAdapters.fromV2(request),
        currentPreview:
            InstitutionProjectPreviewAdapters.fromV2Current(request),
        latestPreview: request.latestProject == null ||
                request.latestDoctorPrice == null
            ? null
            : InstitutionProjectPreviewAdapters.fromV2Latest(request),
        valid: request.reviewable,
        creation: false,
        sharedChanged: request.sharedChanged,
      );
    }
    final legacy = InstitutionProjectPreviewAdapters.fromV1(request);
    return InstitutionProjectReviewItem(
      id: request.id,
      institutionId: request.institutionId,
      institutionName: request.institutionName,
      projectName: request.projectName,
      platformProjectName: request.platformProjectName,
      doctorName: request.doctorName,
      price: legacy.model.price,
      currency: legacy.model.currency,
      requestStatus: request.status,
      proposedDoctorActive: null,
      preview: legacy.model,
      valid: request.payloadVersion == 1 && request.reviewable,
      creation: false,
      legacyScheduleNote: legacy.scheduleNote,
    );
  }

  factory InstitutionProjectReviewItem.fromCreation(
    ProfessionalProjectRequest request,
  ) {
    final preview = ProfessionalProjectRequestPreviewAdapter.fromRequest(request);
    return InstitutionProjectReviewItem(
      id: request.id,
      institutionId: request.institutionId ?? '',
      institutionName:
          _blankToNull(request.institutionName) ?? request.institutionId ?? '-',
      projectName: preview.name,
      platformProjectName:
          _blankToNull(request.projectName) ?? request.projectId ?? '-',
      doctorName: request.doctorName,
      price: preview.price,
      currency: preview.currency,
      requestStatus: request.status,
      proposedDoctorActive: request.isActive,
      preview: preview,
      valid: request.hasCompleteReviewSnapshot,
      creation: true,
    );
  }

  final String id;
  final String institutionId;
  final String institutionName;
  final String projectName;
  final String platformProjectName;
  final String doctorName;
  final double price;
  final String currency;
  final String requestStatus;
  final bool? proposedDoctorActive;
  final bool? currentDoctorActive;
  final bool? latestDoctorActive;
  final num? proposedTravelGroundServiceFee;
  final InstitutionProjectPreviewModel preview;
  final InstitutionProjectPreviewModel? currentPreview;
  final InstitutionProjectPreviewModel? latestPreview;
  final String? currentCategory;
  final String? proposedCategory;
  final String? latestCategory;
  final bool valid;
  final bool creation;
  final bool sharedChanged;
  final String? legacyScheduleNote;
}

class InstitutionProjectReviewGroup extends StatelessWidget {
  const InstitutionProjectReviewGroup({
    required this.institutionId,
    required this.institutionName,
    required this.items,
    required this.onOpen,
    this.summaryActionBuilder,
    this.summaryDetailsBuilder,
    super.key,
  });

  final String institutionId;
  final String institutionName;
  final List<InstitutionProjectReviewItem> items;
  final ValueChanged<InstitutionProjectReviewItem> onOpen;
  final Widget Function(BuildContext, InstitutionProjectReviewItem)?
      summaryActionBuilder;
  final Widget Function(BuildContext, InstitutionProjectReviewItem)?
      summaryDetailsBuilder;

  @override
  Widget build(BuildContext context) => Card(
        margin: const EdgeInsets.only(bottom: 12),
        child: ExpansionTile(
          key: Key('institution-review-group-$institutionId'),
          initiallyExpanded: true,
          title: Text(institutionName),
          subtitle: Text(context.localized(
            '${items.length} 条申请',
            '${items.length} ${items.length == 1 ? 'request' : 'requests'}',
          )),
          children: [
            for (final item in items)
              KeyedSubtree(
                key: Key('professional-request-${item.id}'),
                child: Card(
                  key: Key('institution-review-card-${item.id}'),
                  margin: const EdgeInsets.fromLTRB(12, 0, 12, 12),
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(item.projectName,
                            style: Theme.of(context).textTheme.titleMedium),
                        Text(
                          '${context.localized('平台项目', 'Platform project')}: ${item.platformProjectName}',
                        ),
                        Text('${context.localized('医生', 'Doctor')}: ${item.doctorName}'),
                        Text(_reviewMoney(item.price, item.currency)),
                        if (summaryDetailsBuilder != null)
                          summaryDetailsBuilder!(context, item),
                        const SizedBox(height: 8),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            Chip(
                              key: Key('request-status-${item.id}'),
                              label: Text(context.localized(
                                '申请：${item.requestStatus}',
                                'Request: ${item.requestStatus}',
                              )),
                            ),
                            if (item.proposedDoctorActive != null)
                              Chip(
                                key: Key('doctor-active-status-${item.id}'),
                                label: Text(context.localized(
                                  item.proposedDoctorActive!
                                      ? '申请后医生上架'
                                      : '申请后医生下架',
                                  item.proposedDoctorActive!
                                      ? 'Doctor active after approval'
                                      : 'Doctor inactive after approval',
                                )),
                              ),
                          ],
                        ),
                        if (!item.valid)
                          Padding(
                            padding: const EdgeInsets.only(top: 8),
                            child: Text(
                              context.localized(
                                '申请快照不完整，无法审核，请刷新后重试',
                                'The request snapshot is incomplete and cannot be reviewed. Refresh and retry.',
                              ),
                              key: Key(
                                  'malformed-review-snapshot-${item.id}'),
                              style: TextStyle(
                                  color: Theme.of(context).colorScheme.error),
                            ),
                          ),
                        const SizedBox(height: 8),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            OutlinedButton.icon(
                              key: Key('institution-review-detail-${item.id}'),
                              onPressed: () => onOpen(item),
                              icon: const Icon(Icons.visibility_outlined),
                              label: Text(
                                  context.localized('查看详情', 'View details')),
                            ),
                            if (summaryActionBuilder != null)
                              summaryActionBuilder!(context, item),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ),
          ],
        ),
      );
}

class InstitutionProjectReviewDetailPage extends StatelessWidget {
  const InstitutionProjectReviewDetailPage({
    required this.item,
    this.showLatest = false,
    this.actions,
    super.key,
  });

  final InstitutionProjectReviewItem item;
  final bool showLatest;
  final Widget? actions;

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: Text(context.localized('审核详情', 'Review details')),
        ),
        body: ListView(
          padding: const EdgeInsets.only(bottom: 24),
          children: [
            if (item.currentPreview == null)
              InstitutionProjectPreviewBody(model: item.preview),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (item.currentPreview != null) ...[
                    _ReviewComparisonSection(
                      key: Key('review-comparison-current-${item.id}'),
                      title: context.localized(
                        '提交时当前值',
                        'Current values at submission',
                      ),
                      model: item.currentPreview!,
                      category: item.currentCategory,
                      doctorActive: item.currentDoctorActive,
                      travelGroundServiceFee: null,
                    ),
                    _ReviewComparisonSection(
                      key: Key('review-comparison-proposed-${item.id}'),
                      title: context.localized('申请值', 'Proposed values'),
                      model: item.preview,
                      category: item.proposedCategory,
                      doctorActive: item.proposedDoctorActive,
                      travelGroundServiceFee:
                          item.proposedTravelGroundServiceFee,
                    ),
                  ],
                  if (showLatest && item.latestPreview != null)
                    _ReviewComparisonSection(
                      key: Key('review-comparison-latest-${item.id}'),
                      title: context.localized('最新值', 'Latest values'),
                      model: item.latestPreview!,
                      category: item.latestCategory,
                      doctorActive: item.latestDoctorActive,
                      travelGroundServiceFee: null,
                    ),
                  if (item.creation)
                    Padding(
                      padding: const EdgeInsets.only(top: 16),
                      child: Text(context.localized(
                        '这是新增申请，因此没有变更前快照。',
                        'This is a creation request, so there is no before snapshot.',
                      )),
                    )
                  else ...[
                    const SizedBox(height: 16),
                    if (item.sharedChanged) ...[
                      Text(context.localized(
                        '共享项目变更会影响该机构项目下的全部医生。',
                        'Shared project changes affect every doctor offering this institution project.',
                      )),
                      const SizedBox(height: 8),
                    ],
                    Text(context.localized(
                      '医生价格与上架状态只影响申请医生。',
                      'Doctor price and availability affect only the applying doctor.',
                    )),
                  ],
                  if ((item.legacyScheduleNote ?? '').trim().isNotEmpty) ...[
                    const SizedBox(height: 12),
                    Text(context.localized(
                      '历史排期：${item.legacyScheduleNote}',
                      'Legacy schedule: ${item.legacyScheduleNote}',
                    )),
                  ],
                  if (!item.valid) ...[
                    const SizedBox(height: 12),
                    Text(
                      context.localized(
                        '申请快照异常，无法执行审核操作。',
                        'The request snapshot is damaged and cannot be reviewed.',
                      ),
                      style:
                          TextStyle(color: Theme.of(context).colorScheme.error),
                    ),
                  ],
                  if (actions != null) ...[
                    const SizedBox(height: 20),
                    const Divider(),
                    const SizedBox(height: 8),
                    actions!,
                  ],
                ],
              ),
            ),
          ],
        ),
      );
}

class InstitutionProjectReviewActions extends StatefulWidget {
  const InstitutionProjectReviewActions({
    required this.item,
    required this.enabled,
    required this.allowForce,
    required this.onSubmit,
    super.key,
  });

  final InstitutionProjectReviewItem item;
  final bool enabled;
  final bool allowForce;
  final Future<bool> Function(String decision, String note, bool force) onSubmit;

  @override
  State<InstitutionProjectReviewActions> createState() =>
      _InstitutionProjectReviewActionsState();
}

class _InstitutionProjectReviewActionsState
    extends State<InstitutionProjectReviewActions> {
  bool _submitting = false;

  Future<void> _submit(String decision, bool force) async {
    if (_submitting || !widget.enabled) return;
    final note = await showInstitutionProjectReviewNoteDialog(
      context,
      decision: decision,
      force: force,
    );
    if (note == null || !mounted) return;
    setState(() => _submitting = true);
    try {
      final close = await widget.onSubmit(decision, note, force);
      if (close && mounted) Navigator.of(context).pop();
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final enabled = widget.enabled && !_submitting;
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        FilledButton(
          key: Key('approve-${widget.item.id}'),
          onPressed: enabled ? () => _submit('APPROVED', false) : null,
          child: Text(context.localized('批准', 'Approve')),
        ),
        OutlinedButton(
          key: Key('reject-${widget.item.id}'),
          onPressed: enabled ? () => _submit('REJECTED', false) : null,
          child: Text(context.localized('驳回', 'Reject')),
        ),
        OutlinedButton(
          key: Key('changes-${widget.item.id}'),
          onPressed:
              enabled ? () => _submit('CHANGES_REQUESTED', false) : null,
          child: Text(context.localized('要求修改', 'Request changes')),
        ),
        if (widget.allowForce)
          FilledButton.tonal(
            key: Key('force-${widget.item.id}'),
            onPressed: enabled ? () => _submit('APPROVED', true) : null,
            child: Text(context.localized('强制批准', 'Force approve')),
          ),
      ],
    );
  }
}

Future<String?> showInstitutionProjectReviewNoteDialog(
  BuildContext context, {
  required String decision,
  required bool force,
}) async {
  final note = TextEditingController();
  final accepted = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: Text(force
          ? context.localized('确认强制批准', 'Confirm force approval')
          : context.localized('提交审核', 'Submit review')),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (force)
            Text(context.localized(
              '请核对最新值与申请值。强制批准仅跳过允许的基线冲突，且会被审计。',
              'Compare the latest and proposed values. Force approval only bypasses eligible baseline conflicts and is audited.',
            )),
          TextField(
            key: const Key('profile-review-note'),
            controller: note,
            decoration: InputDecoration(
                labelText: context.localized('审核说明', 'Review note')),
            maxLines: 3,
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(dialogContext, false),
          child: Text(context.localized('取消', 'Cancel')),
        ),
        FilledButton(
          onPressed: () {
            if ((force || decision != 'APPROVED') &&
                note.text.trim().isEmpty) {
              return;
            }
            Navigator.pop(dialogContext, true);
          },
          child: Text(context.localized('确认', 'Confirm')),
        ),
      ],
    ),
  );
  final value = note.text.trim();
  return accepted == true ? value : null;
}

class _ReviewComparisonSection extends StatelessWidget {
  const _ReviewComparisonSection({
    required this.title,
    required this.model,
    required this.category,
    required this.doctorActive,
    required this.travelGroundServiceFee,
    super.key,
  });

  final String title;
  final InstitutionProjectPreviewModel model;
  final String? category;
  final bool? doctorActive;
  final num? travelGroundServiceFee;

  @override
  Widget build(BuildContext context) => Card(
        margin: const EdgeInsets.only(top: 16),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleSmall),
              Text('${context.localized('项目名称', 'Name')}: ${model.name}'),
              if ((category ?? '').trim().isNotEmpty)
                Text(
                  '${context.localized('项目分类', 'Category')}: ${category!.trim()}',
                ),
              Text('${context.localized('价格', 'Price')}: ${_reviewMoney(model.price, model.currency)}'),
              InstitutionProjectPreviewBody(model: model),
              if (travelGroundServiceFee != null)
                Text(
                  '${context.localized('旅游地接服务费', 'Travel ground service fee')}: ${_reviewMoney(travelGroundServiceFee!.toDouble(), model.currency)}',
                ),
              if (doctorActive != null)
                Text(context.localized(
                  doctorActive! ? '医生上架：是' : '医生上架：否',
                  doctorActive! ? 'Doctor active: Yes' : 'Doctor active: No',
                )),
            ],
          ),
        ),
      );
}

String _reviewMoney(double value, String currency) {
  final normalized = currency.trim().toUpperCase();
  return '$normalized ${value.toStringAsFixed(2)}';
}

String? _blankToNull(String? value) {
  final text = value?.trim() ?? '';
  return text.isEmpty ? null : text;
}
