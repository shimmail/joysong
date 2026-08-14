import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/institution_picker_page.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';

class ConsultantMembershipPage extends StatefulWidget {
  const ConsultantMembershipPage({
    required this.repository,
    required this.discoverRepository,
    super.key,
  });

  final IdentityRepository repository;
  final DiscoverRepository discoverRepository;

  @override
  State<ConsultantMembershipPage> createState() =>
      _ConsultantMembershipPageState();
}

class _ConsultantMembershipPageState extends State<ConsultantMembershipPage> {
  final _note = TextEditingController();
  List<ConsultantMembership> _items = const [];
  InstitutionPickerSelection? _selection;
  Object? _error;
  var _loading = true;
  var _submitting = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final items = await widget.repository.listConsultantMemberships();
      if (!mounted) return;
      setState(() {
        _items = items;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = error;
        _loading = false;
      });
    }
  }

  Future<void> _pickInstitution() async {
    final selection = await Navigator.of(context)
        .push<InstitutionPickerSelection>(MaterialPageRoute(
      builder: (_) => InstitutionPickerPage(
        loadPage: ({required query, required offset, required limit}) =>
            widget.repository.listInstitutionMembershipCandidates(
          requestType: InstitutionMembershipRequestType.consultant,
          action: InstitutionMembershipAction.join,
          query: query,
          offset: offset,
          limit: limit,
        ),
      ),
    ));
    if (mounted && selection != null) {
      setState(() => _selection = selection);
    }
  }

  Future<void> _submit() async {
    final selection = _selection;
    if (selection == null) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(context.localized('请先选择机构', 'Select an institution')),
      ));
      return;
    }
    setState(() => _submitting = true);
    try {
      await widget.repository.submitConsultantMembership(
        ConsultantMembershipDraft(
          institutionId: selection.id,
          requestNote: _note.text,
        ),
      );
      if (!mounted) return;
      _note.clear();
      setState(() => _selection = null);
      await _load();
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(context.localized(
          '机构申请提交失败，请重试',
          'Unable to submit the request. Try again.',
        )),
      ));
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: Text(context.localized('顾问机构归属', 'Consultant affiliations')),
        ),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : _error != null
                ? _RetryState(
                    message: context.localized(
                      '机构归属加载失败',
                      'Unable to load affiliations',
                    ),
                    onRetry: _load,
                  )
                : RefreshIndicator(
                    onRefresh: _load,
                    child: ListView(
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: const EdgeInsets.all(16),
                      children: [
                        ListTile(
                          key: const Key('consultant-institution-picker'),
                          enabled: !_submitting,
                          contentPadding: EdgeInsets.zero,
                          leading: const Icon(Icons.apartment_outlined),
                          title: Text(_selection?.name ??
                              context.localized('选择机构', 'Select institution')),
                          subtitle: Text(context.localized(
                              '搜索全部机构', 'Search all institutions')),
                          trailing: const Icon(Icons.chevron_right_rounded),
                          onTap: _submitting ? null : _pickInstitution,
                        ),
                        TextField(
                          key: const Key('consultant-request-note'),
                          controller: _note,
                          enabled: !_submitting,
                          maxLines: 3,
                          decoration: InputDecoration(
                            labelText:
                                context.localized('申请说明', 'Request note'),
                          ),
                        ),
                        const SizedBox(height: 12),
                        FilledButton.icon(
                          key: const Key('consultant-submit'),
                          onPressed: _submitting ? null : _submit,
                          icon: _submitting
                              ? const SizedBox.square(
                                  dimension: 18,
                                  child:
                                      CircularProgressIndicator(strokeWidth: 2),
                                )
                              : const Icon(Icons.send_outlined),
                          label: Text(context.localized(
                            _submitting ? '提交中…' : '提交申请',
                            _submitting ? 'Submitting...' : 'Submit request',
                          )),
                        ),
                        const SizedBox(height: 24),
                        Text(
                          context.localized('申请记录', 'Request history'),
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        if (_items.isEmpty)
                          Padding(
                            padding: const EdgeInsets.only(top: 16),
                            child: Text(
                                context.localized('暂无申请记录', 'No requests yet')),
                          )
                        else
                          for (final item in _items)
                            Card(child: _membershipTile(context, item)),
                      ],
                    ),
                  ),
      );

  Widget _membershipTile(BuildContext context, ConsultantMembership item) {
    final status = switch (item.status) {
      'PENDING' => context.localized('审核中', 'Pending'),
      'APPROVED' => context.localized('已通过', 'Approved'),
      'REJECTED' => context.localized('已拒绝', 'Rejected'),
      'REVOKED' => context.localized('已撤销', 'Revoked'),
      _ => item.status,
    };
    return ListTile(
      title: Text(item.institutionName),
      subtitle: Text([
        status,
        if (item.requestNote.isNotEmpty)
          context.localized(
              '申请说明：${item.requestNote}', 'Request: ${item.requestNote}'),
        if (item.reviewNote.isNotEmpty)
          context.localized(
              '审核意见：${item.reviewNote}', 'Review: ${item.reviewNote}'),
        context.localized(
          '申请时间：${_timestamp(item.createdAt)}',
          'Created: ${_timestamp(item.createdAt)}',
        ),
        context.localized(
          '更新时间：${_timestamp(item.updatedAt)}',
          'Updated: ${_timestamp(item.updatedAt)}',
        ),
        if (item.confirmedBy != null)
          context.localized(
            '审核人：${item.confirmedBy}',
            'Confirmed by: ${item.confirmedBy}',
          ),
        if (item.confirmedAt != null)
          context.localized(
            '审核时间：${_timestamp(item.confirmedAt!)}',
            'Confirmed: ${_timestamp(item.confirmedAt!)}',
          ),
        if (item.revokedAt != null)
          context.localized(
            '撤销时间：${_timestamp(item.revokedAt!)}',
            'Revoked: ${_timestamp(item.revokedAt!)}',
          ),
      ].join('\n')),
    );
  }
}

class ConsultantProjectCatalogPage extends StatefulWidget {
  const ConsultantProjectCatalogPage({required this.repository, super.key});

  final IdentityRepository repository;

  @override
  State<ConsultantProjectCatalogPage> createState() =>
      _ConsultantProjectCatalogPageState();
}

class _ConsultantProjectCatalogPageState
    extends State<ConsultantProjectCatalogPage> {
  List<ManagementProjectOption> _items = const [];
  Object? _error;
  var _loading = true;

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
      final items = await widget.repository.listManagementProjects();
      if (!mounted) return;
      setState(() {
        _items = items;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = error;
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: Text(context.localized('项目目录', 'Project catalog')),
        ),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : _error != null
                ? _RetryState(
                    message: context.localized(
                        '项目目录加载失败', 'Unable to load the project catalog'),
                    onRetry: _load,
                  )
                : RefreshIndicator(
                    onRefresh: _load,
                    child: ListView.builder(
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: const EdgeInsets.all(16),
                      itemCount: _items.isEmpty ? 1 : _items.length,
                      itemBuilder: (context, index) {
                        if (_items.isEmpty) {
                          return Padding(
                            padding: const EdgeInsets.only(top: 48),
                            child: Center(
                              child: Text(context.localized(
                                  '暂无可用项目', 'No projects available')),
                            ),
                          );
                        }
                        final item = _items[index];
                        return Card(
                          child: ListTile(
                            leading: const Icon(Icons.spa_outlined),
                            title: Text(item.name),
                            subtitle: Text([
                              if (item.category.isNotEmpty) item.category,
                              if (item.description.isNotEmpty) item.description,
                              if (item.tags.isNotEmpty) item.tags,
                              if (item.categoryTags.isNotEmpty)
                                item.categoryTags,
                              '${item.referencePrice.toStringAsFixed(2)} ${item.currency}',
                            ].join('\n')),
                          ),
                        );
                      },
                    ),
                  ),
      );
}

class _RetryState extends StatelessWidget {
  const _RetryState({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(message),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: onRetry,
              child: Text(context.localized('重试', 'Retry')),
            ),
          ],
        ),
      );
}

String _timestamp(DateTime value) => value.toIso8601String();
