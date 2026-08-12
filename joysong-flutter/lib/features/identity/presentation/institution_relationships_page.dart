import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/institution_picker_page.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';

class InstitutionRelationshipsPage extends StatefulWidget {
  const InstitutionRelationshipsPage({
    required this.repository,
    required this.discoverRepository,
    super.key,
  });

  final IdentityRepository repository;
  final DiscoverRepository discoverRepository;

  @override
  State<InstitutionRelationshipsPage> createState() =>
      _InstitutionRelationshipsPageState();
}

class _InstitutionRelationshipsPageState
    extends State<InstitutionRelationshipsPage> {
  final _requestNote = TextEditingController();
  ManagementContext? _managementContext;
  List<InstitutionOption> _institutions = const [];
  List<DoctorInstitutionChangeRequest> _requests = const [];
  String _action = 'JOIN';
  String? _institutionId;
  String? _selectedInstitutionName;
  String? _error;
  var _loading = true;
  var _saving = false;
  var _legalView = false;

  bool get _canUseDoctorView =>
      _managementContext?.activeRoles.contains('DOCTOR') ?? false;
  bool get _canUseLegalView =>
      (_managementContext?.canReviewInstitutionRequests ?? false) &&
      (_managementContext?.managedInstitutionIds.isNotEmpty ?? false);

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _requestNote.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final values = await Future.wait([
        widget.repository.loadManagementContext(),
        widget.repository.listInstitutionOptions(),
        widget.repository.listDoctorInstitutionChangeRequests(),
      ]);
      if (!mounted) return;
      final context = values[0] as ManagementContext;
      setState(() {
        _managementContext = context;
        _institutions = values[1] as List<InstitutionOption>;
        _requests = values[2] as List<DoctorInstitutionChangeRequest>;
        _legalView = !_canUseDoctorView && _canUseLegalView;
        _loading = false;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = '机构关系加载失败，请重试';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: Text(context.localized('机构关系', 'Institution relationships')),
        ),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : RefreshIndicator(
                onRefresh: _load,
                child: ListView(
                  padding: const EdgeInsets.all(16),
                  children: [
                    if (_canUseDoctorView && _canUseLegalView)
                      SegmentedButton<bool>(
                        segments: [
                          ButtonSegment(
                            value: false,
                            label: Text(
                                context.localized('我的关系', 'My relationships')),
                          ),
                          ButtonSegment(
                            value: true,
                            label: Text(
                                context.localized('审核申请', 'Review requests')),
                          ),
                        ],
                        selected: {_legalView},
                        onSelectionChanged: (value) =>
                            setState(() => _legalView = value.first),
                      ),
                    if (_error != null) _ErrorMessage(message: _error!),
                    if (_legalView && _canUseLegalView)
                      _buildLegalView(context)
                    else if (_canUseDoctorView)
                      _buildDoctorView(context)
                    else
                      Padding(
                        padding: const EdgeInsets.only(top: 24),
                        child: Text(context.localized(
                          '暂无可用的机构关系权限',
                          'No institution relationship access is available',
                        )),
                      ),
                  ],
                ),
              ),
      );

  Widget _buildDoctorView(BuildContext context) {
    final contextData = _managementContext!;
    final canApply = contextData.canApplyToInstitutions;
    final currentIds = contextData.doctorInstitutionIds;
    final available = _action == 'LEAVE'
        ? _institutions.where((item) => currentIds.contains(item.id)).toList()
        : _institutions;
    final history =
        _requests.where((item) => item.userId == contextData.userId).toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 16),
        Text(context.localized('当前机构', 'Current institutions'),
            style: Theme.of(context).textTheme.titleMedium),
        if (currentIds.isEmpty)
          Text(context.localized('暂无已确认机构归属', 'No confirmed affiliations'))
        else
          for (final id in currentIds)
            ListTile(title: Text(_institutionName(id))),
        if (canApply) ...[
          const SizedBox(height: 16),
          SegmentedButton<String>(
            segments: [
              ButtonSegment(
                  value: 'JOIN',
                  label: Text(context.localized('加入机构', 'Join institution'))),
              ButtonSegment(
                  value: 'LEAVE',
                  label: Text(context.localized('离开机构', 'Leave institution'))),
            ],
            selected: {_action},
            onSelectionChanged: (value) => setState(() {
              _action = value.first;
              _institutionId = null;
              _selectedInstitutionName = null;
            }),
          ),
          const SizedBox(height: 12),
          if (_action == 'JOIN')
            ListTile(
              key: const Key('doctor-join-institution-picker'),
              enabled: !_saving,
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.apartment_outlined),
              title: Text(_selectedInstitutionName ??
                  context.localized('选择机构', 'Select institution')),
              subtitle: _institutionId == null
                  ? Text(context.localized('搜索全部机构', 'Search all institutions'))
                  : null,
              trailing: const Icon(Icons.chevron_right_rounded),
              onTap: _saving ? null : _selectInstitution,
            )
          else
            DropdownButtonFormField<String>(
              initialValue: _institutionId,
              decoration: InputDecoration(
                labelText: context.localized('目标机构', 'Institution'),
              ),
              items: available
                  .map((item) => DropdownMenuItem(
                        value: item.id,
                        child: Text(item.name),
                      ))
                  .toList(),
              onChanged: _saving
                  ? null
                  : (value) => setState(() => _institutionId = value),
            ),
          const SizedBox(height: 12),
          TextField(
            controller: _requestNote,
            maxLines: 3,
            decoration: InputDecoration(
                labelText: context.localized('申请说明', 'Request note')),
          ),
          const SizedBox(height: 12),
          FilledButton.icon(
            onPressed: _saving || (_action == 'LEAVE' && available.isEmpty)
                ? null
                : _submit,
            icon: const Icon(Icons.send_outlined),
            label: Text(context.localized('提交申请', 'Submit request')),
          ),
        ],
        const SizedBox(height: 24),
        Text(context.localized('申请记录', 'Request history'),
            style: Theme.of(context).textTheme.titleMedium),
        for (final request in history)
          _requestTile(context, request, canWithdraw: true),
      ],
    );
  }

  Widget _buildLegalView(BuildContext context) {
    final managedIds = _managementContext!.managedInstitutionIds;
    final requests = _requests
        .where((item) => managedIds.contains(item.institutionId))
        .toList();
    final pending = requests.where((item) => item.status == 'PENDING').toList();
    final history = requests.where((item) => item.status != 'PENDING').toList();
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      const SizedBox(height: 16),
      Text(context.localized('待审核', 'Pending'),
          style: Theme.of(context).textTheme.titleMedium),
      if (pending.isEmpty)
        Text(context.localized('暂无待审核申请', 'No pending requests')),
      for (final request in pending)
        _requestTile(context, request, canReview: true),
      const SizedBox(height: 20),
      Text(context.localized('审核历史', 'Review history'),
          style: Theme.of(context).textTheme.titleMedium),
      for (final request in history) _requestTile(context, request),
    ]);
  }

  Widget _requestTile(
          BuildContext context, DoctorInstitutionChangeRequest request,
          {bool canWithdraw = false, bool canReview = false}) =>
      ListTile(
        contentPadding: EdgeInsets.zero,
        leading: const Icon(Icons.apartment_outlined),
        title: Text(
            '${_actionLabel(context, request.action)} · ${request.institutionName.isEmpty ? _institutionName(request.institutionId) : request.institutionName}'),
        subtitle: Text([
          if (request.doctorName.isNotEmpty) request.doctorName,
          _statusLabel(context, request.status),
          if (request.requestNote.isNotEmpty) request.requestNote,
          if (request.reviewNote.isNotEmpty)
            context.localized(
                '审核意见：${request.reviewNote}', 'Review: ${request.reviewNote}'),
        ].join('\n')),
        trailing: canReview
            ? IconButton(
                tooltip: context.localized('审核', 'Review'),
                icon: const Icon(Icons.fact_check_outlined),
                onPressed: () => _review(request))
            : canWithdraw && request.status == 'PENDING'
                ? IconButton(
                    tooltip: context.localized('撤回', 'Withdraw'),
                    icon: const Icon(Icons.undo_outlined),
                    onPressed: () => _withdraw(request))
                : null,
      );

  Future<void> _submit() async {
    final institutionId = _institutionId;
    if (institutionId == null) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(context.localized('请选择机构', 'Select an institution'))));
      return;
    }
    setState(() => _saving = true);
    try {
      await widget.repository.submitDoctorInstitutionChangeRequest(
        DoctorInstitutionChangeRequestDraft(
            institutionId: institutionId,
            action: _action,
            requestNote: _requestNote.text),
      );
      _requestNote.clear();
      await _load();
    } catch (_) {
      if (mounted) setState(() => _error = '机构关系申请提交失败，请稍后重试');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _selectInstitution() async {
    final selection = await Navigator.of(context)
        .push<InstitutionPickerSelection>(MaterialPageRoute(
      builder: (_) => InstitutionPickerPage(
        repository: widget.discoverRepository,
      ),
    ));
    if (!mounted || selection == null) return;
    setState(() {
      _institutionId = selection.id;
      _selectedInstitutionName = selection.name;
    });
  }

  Future<void> _withdraw(DoctorInstitutionChangeRequest request) async {
    try {
      await widget.repository
          .withdrawDoctorInstitutionChangeRequest(request.id);
      await _load();
    } catch (_) {
      if (mounted) setState(() => _error = '申请撤回失败，请稍后重试');
    }
  }

  Future<void> _review(DoctorInstitutionChangeRequest request) async {
    final result = await _showReviewDialog(context);
    if (result == null) return;
    try {
      await widget.repository.reviewDoctorInstitutionChangeRequest(
          id: request.id, decision: result.decision, reviewNote: result.note);
      await _load();
    } catch (_) {
      if (mounted) setState(() => _error = '审核提交失败，请稍后重试');
    }
  }

  Future<({String decision, String note})?> _showReviewDialog(
      BuildContext context) async {
    final note = TextEditingController();
    var decision = 'APPROVED';
    final result = await showDialog<({String decision, String note})>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
          builder: (context, setState) => AlertDialog(
                title: Text(context.localized('审核申请', 'Review request')),
                content: Column(mainAxisSize: MainAxisSize.min, children: [
                  DropdownButtonFormField<String>(
                      initialValue: decision,
                      items: const [
                        DropdownMenuItem(value: 'APPROVED', child: Text('通过')),
                        DropdownMenuItem(value: 'REJECTED', child: Text('驳回')),
                      ],
                      onChanged: (value) =>
                          setState(() => decision = value ?? 'APPROVED')),
                  const SizedBox(height: 12),
                  TextField(
                      controller: note,
                      maxLines: 3,
                      decoration: InputDecoration(
                          labelText: context.localized('审核意见', 'Review note'))),
                ]),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.of(dialogContext).pop(),
                      child: Text(context.localized('取消', 'Cancel'))),
                  FilledButton(
                      onPressed: () {
                        if (decision == 'REJECTED' &&
                            note.text.trim().isEmpty) {
                          return;
                        }
                        Navigator.of(dialogContext)
                            .pop((decision: decision, note: note.text.trim()));
                      },
                      child: Text(context.localized('确认', 'Confirm'))),
                ],
              )),
    );
    note.dispose();
    return result;
  }

  String _institutionName(String id) {
    for (final item in _institutions) {
      if (item.id == id) return item.name;
    }
    return id;
  }

  String _actionLabel(BuildContext context, String action) => action == 'LEAVE'
      ? context.localized('离开机构', 'Leave institution')
      : context.localized('加入机构', 'Join institution');

  String _statusLabel(BuildContext context, String status) => switch (status) {
        'PENDING' => context.localized('待审核', 'Pending'),
        'APPROVED' => context.localized('已通过', 'Approved'),
        'REJECTED' => context.localized('已驳回', 'Rejected'),
        'WITHDRAWN' => context.localized('已撤回', 'Withdrawn'),
        _ => status,
      };
}

class _ErrorMessage extends StatelessWidget {
  const _ErrorMessage({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 16),
        child: Text(message,
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
      );
}
