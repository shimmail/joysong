import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/discover/presentation/institution_picker_page.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';

enum InstitutionRelationshipScope { doctor, consultant, legalRepresentative }

class InstitutionRelationshipsPage extends StatefulWidget {
  const InstitutionRelationshipsPage({
    required this.repository,
    required this.scope,
    super.key,
  });

  final IdentityRepository repository;
  final InstitutionRelationshipScope scope;

  @override
  State<InstitutionRelationshipsPage> createState() =>
      _InstitutionRelationshipsPageState();
}

class _InstitutionRelationshipsPageState
    extends State<InstitutionRelationshipsPage> {
  static const _candidateLimit = 20;

  final _requestNote = TextEditingController();
  ManagementContext? _managementContext;
  List<InstitutionMembershipRequest> _requests = const [];
  Map<String, String> _institutionNames = const {};
  final _candidateCache =
      <InstitutionMembershipAction, InstitutionMembershipCandidatePage>{};
  InstitutionMembershipAction _action = InstitutionMembershipAction.join;
  InstitutionPickerSelection? _selection;
  String? _error;
  String? _activeMutation;
  InstitutionMembershipAction? _loadingCandidatesFor;
  var _initialLoading = true;
  var _historyExpanded = false;
  var _loadGeneration = 0;
  var _candidateRequestToken = 0;

  bool get _isLegal =>
      widget.scope == InstitutionRelationshipScope.legalRepresentative;

  InstitutionMembershipRequestType? get _requestType => switch (widget.scope) {
        InstitutionRelationshipScope.doctor =>
          InstitutionMembershipRequestType.doctor,
        InstitutionRelationshipScope.consultant =>
          InstitutionMembershipRequestType.consultant,
        InstitutionRelationshipScope.legalRepresentative => null,
      };

  @override
  void initState() {
    super.initState();
    _refresh(initial: true);
  }

  @override
  void dispose() {
    _loadGeneration += 1;
    _candidateRequestToken += 1;
    _requestNote.dispose();
    super.dispose();
  }

  Future<void> _refresh({bool initial = false}) async {
    final generation = ++_loadGeneration;
    final refreshCandidateToken = ++_candidateRequestToken;
    if (!initial && mounted) {
      setState(() {
        _error = null;
        _loadingCandidatesFor = null;
      });
    }
    try {
      final managementContext = await widget.repository.loadManagementContext();
      if (generation != _loadGeneration || !mounted) return;

      if (_isLegal) {
        final reviewable = await widget.repository
            .listReviewableInstitutionMembershipRequests();
        if (generation != _loadGeneration || !mounted) return;
        final managed = managementContext.managedInstitutionIds.toSet();
        final requests = reviewable
            .where((request) => managed.contains(request.institutionId))
            .toList(growable: false);
        setState(() {
          _managementContext = managementContext;
          _requests = requests;
          _institutionNames = _namesFromRequests(requests);
          _initialLoading = false;
          _error = null;
        });
        return;
      }

      final requestType = _requestType!;
      final owned =
          await widget.repository.listOwnedInstitutionMembershipRequests();
      if (generation != _loadGeneration || !mounted) return;
      final requests = owned
          .where((request) => request.requestType == requestType)
          .toList(growable: false);
      final refreshOwnsCandidateState =
          refreshCandidateToken == _candidateRequestToken;
      final requestNames = _namesFromRequests(requests);
      setState(() {
        _managementContext = managementContext;
        _requests = requests;
        _institutionNames = refreshOwnsCandidateState
            ? requestNames
            : {..._institutionNames, ...requestNames};
        if (refreshOwnsCandidateState) {
          _candidateCache.clear();
        }
        _loadingCandidatesFor = null;
        _initialLoading = false;
        _error = null;
      });
      if (!managementContext.canApplyToInstitutions) return;

      final requestedAction = _action;
      final candidateToken = ++_candidateRequestToken;
      InstitutionMembershipCandidatePage raw;
      try {
        raw = await widget.repository.listInstitutionMembershipCandidates(
          requestType: requestType,
          action: requestedAction,
          query: '',
          offset: 0,
          limit: _candidateLimit,
        );
      } catch (_) {
        return;
      }
      if (!mounted ||
          generation != _loadGeneration ||
          candidateToken != _candidateRequestToken ||
          requestedAction != _action) {
        return;
      }
      final candidatePage = _filterCandidates(
        raw,
        action: requestedAction,
        managementContext: managementContext,
        requests: requests,
      );
      setState(() {
        _institutionNames = _mergeCandidateNames(_institutionNames, raw.items);
        _candidateCache[requestedAction] = candidatePage;
      });
    } catch (_) {
      if (generation != _loadGeneration || !mounted) return;
      setState(() {
        _initialLoading = false;
        _error = context.localized(
          '机构申请加载失败，请重试',
          'Unable to load institution requests',
        );
      });
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: Text(context.localized(
            _isLegal ? '成员关系审核' : '机构关系',
            _isLegal ? 'Relationship reviews' : 'Institution relationships',
          )),
        ),
        body: _initialLoading
            ? const Center(child: CircularProgressIndicator())
            : _managementContext == null
                ? _FatalLoadState(
                    message: _error ??
                        context.localized(
                          '机构申请加载失败，请重试',
                          'Unable to load institution requests',
                        ),
                    onRetry: _refresh,
                  )
                : RefreshIndicator(
                    onRefresh: _refresh,
                    child: ListView(
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: const EdgeInsets.all(16),
                      children: [
                        if (_error != null)
                          _InlineError(message: _error!, onRetry: _refresh),
                        if (_isLegal)
                          _buildLegalView(context)
                        else
                          _buildApplicantView(context),
                      ],
                    ),
                  ),
      );

  Widget _buildApplicantView(BuildContext context) {
    final managementContext = _managementContext!;
    final canApply = managementContext.canApplyToInstitutions;
    final currentIds = _currentInstitutionIds(managementContext);
    final pending = _requests
        .where((request) =>
            request.status == InstitutionMembershipRequestStatus.pending)
        .toList(growable: false);
    final history = _requests
        .where((request) =>
            request.status != InstitutionMembershipRequestStatus.pending)
        .toList(growable: false);
    final pendingLeaveIds = pending
        .where((request) => request.action == InstitutionMembershipAction.leave)
        .map((request) => request.institutionId)
        .toSet();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SectionTitle(
          text: context.localized('当前机构', 'Current institutions'),
        ),
        if (currentIds.isEmpty)
          _EmptyText(
            text: context.localized(
              '暂无已确认机构归属',
              'No confirmed affiliations',
            ),
          )
        else
          for (final id in currentIds)
            ListTile(
              key: ValueKey('current-institution-$id'),
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.apartment_outlined),
              title: Text(_institutionName(context, id)),
              subtitle: pendingLeaveIds.contains(id)
                  ? Text(context.localized(
                      '退出审核中',
                      'Awaiting exit review',
                    ))
                  : null,
            ),
        const SizedBox(height: 16),
        _SectionTitle(
          text: context.localized('待审核申请', 'Pending requests'),
        ),
        if (pending.isEmpty)
          _EmptyText(
            text: context.localized('暂无待审核申请', 'No pending requests'),
          )
        else
          for (final request in pending)
            _applicantRequestTile(
              context,
              request,
              canWithdraw: canApply,
            ),
        if (canApply) ...[
          const SizedBox(height: 20),
          SegmentedButton<InstitutionMembershipAction>(
            segments: [
              ButtonSegment(
                value: InstitutionMembershipAction.join,
                label: Text(_actionLabel(
                  context,
                  InstitutionMembershipAction.join,
                )),
              ),
              ButtonSegment(
                value: InstitutionMembershipAction.leave,
                label: Text(_actionLabel(
                  context,
                  InstitutionMembershipAction.leave,
                )),
              ),
            ],
            selected: {_action},
            onSelectionChanged: _activeMutation == 'submit'
                ? null
                : (selection) => _changeAction(selection.first),
          ),
          const SizedBox(height: 12),
          ListTile(
            key: const Key('relationship-institution-picker'),
            enabled:
                _activeMutation != 'submit' && _loadingCandidatesFor != _action,
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.apartment_outlined),
            title: Text(
              _selection?.name ??
                  context.localized('选择机构', 'Select institution'),
            ),
            subtitle: _selection == null
                ? Text(context.localized(
                    _action == InstitutionMembershipAction.join
                        ? '搜索可加入机构'
                        : '选择当前机构',
                    _action == InstitutionMembershipAction.join
                        ? 'Search institutions available to join'
                        : 'Select a current institution',
                  ))
                : null,
            trailing: _loadingCandidatesFor == _action
                ? const SizedBox.square(
                    dimension: 22,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.chevron_right_rounded),
            onTap:
                _activeMutation == 'submit' || _loadingCandidatesFor == _action
                    ? null
                    : _selectInstitution,
          ),
          const SizedBox(height: 12),
          TextField(
            key: const Key('relationship-request-note'),
            controller: _requestNote,
            enabled: _activeMutation != 'submit',
            maxLines: 3,
            decoration: InputDecoration(
              labelText: context.localized('申请说明', 'Request note'),
            ),
          ),
          const SizedBox(height: 12),
          FilledButton.icon(
            key: const Key('relationship-submit'),
            onPressed: _activeMutation == 'submit' ? null : _submit,
            icon: _activeMutation == 'submit'
                ? const SizedBox.square(
                    dimension: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.send_outlined),
            label: Text(context.localized(
              _activeMutation == 'submit' ? '提交中' : '提交申请',
              _activeMutation == 'submit' ? 'Submitting' : 'Submit request',
            )),
          ),
        ],
        const SizedBox(height: 20),
        _history(
          context,
          history: history,
          emptyText: context.localized('暂无申请历史', 'No request history'),
          builder: (request) => _historyTile(context, request),
        ),
      ],
    );
  }

  Widget _buildLegalView(BuildContext context) {
    final pending = _requests
        .where((request) =>
            request.status == InstitutionMembershipRequestStatus.pending)
        .toList(growable: false);
    final history = _requests
        .where((request) =>
            request.status != InstitutionMembershipRequestStatus.pending)
        .toList(growable: false);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SectionTitle(text: context.localized('待审核', 'Pending')),
        if (pending.isEmpty)
          _EmptyText(
            text: context.localized('暂无待审核申请', 'No pending requests'),
          )
        else
          for (final request in pending) _legalPendingTile(context, request),
        const SizedBox(height: 20),
        _history(
          context,
          history: history,
          emptyText: context.localized('暂无审核历史', 'No review history'),
          builder: (request) => _historyTile(
            context,
            request,
            showApplicant: true,
          ),
        ),
      ],
    );
  }

  Widget _history(
    BuildContext context, {
    required List<InstitutionMembershipRequest> history,
    required String emptyText,
    required Widget Function(InstitutionMembershipRequest) builder,
  }) =>
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Semantics(
            key: const Key('relationship-history-toggle'),
            container: true,
            button: true,
            expanded: _historyExpanded,
            label: _isLegal
                ? context.localized('审核历史', 'Review history')
                : context.localized('申请历史', 'Request history'),
            child: ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(
                _isLegal
                    ? context.localized('审核历史', 'Review history')
                    : context.localized('申请历史', 'Request history'),
                style: Theme.of(context).textTheme.titleMedium,
              ),
              subtitle: history.isEmpty ? Text(emptyText) : null,
              trailing: Icon(_historyExpanded
                  ? Icons.expand_less_rounded
                  : Icons.expand_more_rounded),
              onTap: () => setState(() => _historyExpanded = !_historyExpanded),
            ),
          ),
          if (_historyExpanded) ...[
            if (history.isEmpty) _EmptyText(text: emptyText),
            for (final request in history) builder(request),
          ],
        ],
      );

  Widget _applicantRequestTile(
    BuildContext context,
    InstitutionMembershipRequest request, {
    required bool canWithdraw,
  }) =>
      ListTile(
        key: ValueKey('pending-${request.id}'),
        contentPadding: EdgeInsets.zero,
        leading: const Icon(Icons.schedule_outlined),
        title: Text(_institutionNameForRequest(context, request)),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(_actionLabel(context, request.action)),
            Text(_statusLabel(context, request.status)),
            if (request.requestNote.trim().isNotEmpty)
              Text(request.requestNote.trim()),
          ],
        ),
        trailing: canWithdraw
            ? IconButton(
                key: ValueKey('withdraw-${request.id}'),
                tooltip: context.localized('撤回', 'Withdraw'),
                onPressed: _activeMutation == 'withdraw-${request.id}'
                    ? null
                    : () => _withdraw(request),
                icon: _activeMutation == 'withdraw-${request.id}'
                    ? const SizedBox.square(
                        dimension: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.undo_rounded),
              )
            : null,
      );

  Widget _historyTile(
    BuildContext context,
    InstitutionMembershipRequest request, {
    bool showApplicant = false,
  }) =>
      ListTile(
        key: ValueKey('history-${request.id}'),
        contentPadding: EdgeInsets.zero,
        leading: const Icon(Icons.history_rounded),
        title: Text(_institutionNameForRequest(context, request)),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (showApplicant) Text(_applicantName(context, request)),
            Wrap(
              spacing: 6,
              children: [
                Text(_requestTypeLabel(context, request.requestType)),
                Text(_actionLabel(context, request.action)),
                Text(_statusLabel(context, request.status)),
              ],
            ),
            if (request.requestNote.trim().isNotEmpty)
              Text(request.requestNote.trim()),
            if (request.reviewNote.trim().isNotEmpty)
              Text(context.localized(
                '审核意见：${request.reviewNote.trim()}',
                'Review: ${request.reviewNote.trim()}',
              )),
            Text(context.localized(
              '提交时间：${_timeLabel(request.submittedAt)}',
              'Submitted: ${_timeLabel(request.submittedAt)}',
            )),
          ],
        ),
      );

  Widget _legalPendingTile(
    BuildContext context,
    InstitutionMembershipRequest request,
  ) {
    final busy = _activeMutation == 'review-${request.id}';
    return Card(
      key: ValueKey('pending-${request.id}'),
      margin: const EdgeInsets.only(top: 8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              _institutionNameForRequest(context, request),
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 4),
            Text(_applicantName(context, request)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                Chip(
                    label: Text(_requestTypeLabel(
                  context,
                  request.requestType,
                ))),
                Chip(label: Text(_actionLabel(context, request.action))),
              ],
            ),
            if (request.requestNote.trim().isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(request.requestNote.trim()),
            ],
            const SizedBox(height: 8),
            Text(request.action == InstitutionMembershipAction.leave
                ? context.localized(
                    '批准后将解除当前机构关系',
                    'Approval will end the current relationship',
                  )
                : context.localized(
                    '批准后将建立机构关系',
                    'Approval will add this relationship',
                  )),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton(
                  key: ValueKey('reject-${request.id}'),
                  onPressed: busy ? null : () => _reject(request),
                  child: Text(context.localized('驳回', 'Reject')),
                ),
                const SizedBox(width: 8),
                FilledButton(
                  key: ValueKey('approve-${request.id}'),
                  onPressed: busy
                      ? null
                      : () => _review(
                            request,
                            InstitutionMembershipDecision.approved,
                            '',
                          ),
                  child: busy
                      ? const SizedBox.square(
                          dimension: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : Text(context.localized('批准', 'Approve')),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _changeAction(InstitutionMembershipAction action) async {
    if (action == _action) return;
    _candidateRequestToken += 1;
    setState(() {
      _action = action;
      _selection = null;
      _candidateCache.remove(action);
      _loadingCandidatesFor = null;
    });
    await _primeCandidates(action);
  }

  Future<void> _primeCandidates(InstitutionMembershipAction action) async {
    final managementContext = _managementContext;
    final requestType = _requestType;
    if (managementContext == null ||
        requestType == null ||
        !managementContext.canApplyToInstitutions) {
      return;
    }
    final candidateToken = ++_candidateRequestToken;
    setState(() => _loadingCandidatesFor = action);
    try {
      final raw = await widget.repository.listInstitutionMembershipCandidates(
        requestType: requestType,
        action: action,
        query: '',
        offset: 0,
        limit: _candidateLimit,
      );
      if (!mounted ||
          candidateToken != _candidateRequestToken ||
          action != _action) {
        return;
      }
      setState(() {
        _institutionNames = _mergeCandidateNames(_institutionNames, raw.items);
        _candidateCache[action] = _filterCandidates(
          raw,
          action: action,
          managementContext: managementContext,
          requests: _requests,
        );
      });
    } catch (_) {
      if (mounted &&
          candidateToken == _candidateRequestToken &&
          action == _action) {
        setState(() => _candidateCache.remove(action));
      }
    } finally {
      if (mounted &&
          candidateToken == _candidateRequestToken &&
          action == _action &&
          _loadingCandidatesFor == action) {
        setState(() => _loadingCandidatesFor = null);
      }
    }
  }

  Future<InstitutionMembershipCandidatePage> _loadCandidatePage({
    required String query,
    required int offset,
    required int limit,
  }) async {
    final cached =
        query.isEmpty && offset == 0 ? _candidateCache[_action] : null;
    if (cached != null) return cached;
    final managementContext = _managementContext!;
    final requestType = _requestType!;
    final action = _action;
    final requests = _requests;
    final candidateToken = ++_candidateRequestToken;
    final raw = await widget.repository.listInstitutionMembershipCandidates(
      requestType: requestType,
      action: action,
      query: query,
      offset: offset,
      limit: limit,
    );
    final filtered = _filterCandidates(
      raw,
      action: action,
      managementContext: managementContext,
      requests: requests,
    );
    if (mounted &&
        candidateToken == _candidateRequestToken &&
        action == _action) {
      setState(() {
        _institutionNames = _mergeCandidateNames(_institutionNames, raw.items);
        if (query.isEmpty && offset == 0) _candidateCache[action] = filtered;
      });
    }
    return filtered;
  }

  InstitutionMembershipCandidatePage _filterCandidates(
    InstitutionMembershipCandidatePage page, {
    required InstitutionMembershipAction action,
    required ManagementContext managementContext,
    required List<InstitutionMembershipRequest> requests,
  }) {
    final current = _currentInstitutionIds(managementContext).toSet();
    final pending = requests
        .where((request) =>
            request.status == InstitutionMembershipRequestStatus.pending)
        .map((request) => request.institutionId)
        .toSet();
    final items = page.items.where((candidate) {
      if (pending.contains(candidate.id)) return false;
      return action == InstitutionMembershipAction.join
          ? !current.contains(candidate.id)
          : current.contains(candidate.id);
    }).toList(growable: false);
    return InstitutionMembershipCandidatePage(
      items: items,
      offset: page.offset + page.items.length - items.length,
      limit: page.limit,
      hasMore: page.hasMore,
    );
  }

  Future<void> _selectInstitution() async {
    final selection = await Navigator.of(context)
        .push<InstitutionPickerSelection>(MaterialPageRoute(
      builder: (_) => InstitutionPickerPage(loadPage: _loadCandidatePage),
    ));
    if (!mounted || selection == null) return;
    setState(() => _selection = selection);
  }

  Future<void> _submit() async {
    final selection = _selection;
    if (selection == null) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(context.localized('请选择机构', 'Select an institution')),
      ));
      return;
    }
    if (_activeMutation == 'submit') return;
    setState(() {
      _activeMutation = 'submit';
      _error = null;
    });
    try {
      await widget.repository.submitInstitutionMembershipRequest(
        InstitutionMembershipRequestDraft(
          requestType: _requestType!,
          action: _action,
          institutionId: selection.id,
          requestNote: _requestNote.text,
        ),
      );
      if (!mounted) return;
      _requestNote.clear();
      setState(() => _selection = null);
      await _refresh();
    } catch (error) {
      if (mounted) {
        setState(() => _error = _mutationError(
              error,
              '机构关系申请提交失败，请稍后重试',
              'Unable to submit the relationship request. Try again.',
            ));
      }
    } finally {
      if (mounted && _activeMutation == 'submit') {
        setState(() => _activeMutation = null);
      }
    }
  }

  Future<void> _withdraw(InstitutionMembershipRequest request) async {
    final mutation = 'withdraw-${request.id}';
    if (_activeMutation == mutation) return;
    setState(() {
      _activeMutation = mutation;
      _error = null;
    });
    try {
      await widget.repository.withdrawInstitutionMembershipRequest(
        requestType: request.requestType,
        id: request.id,
      );
      await _refresh();
    } catch (error) {
      if (mounted) {
        setState(() => _error = _mutationError(
              error,
              '申请撤回失败，请稍后重试',
              'Unable to withdraw the request. Try again.',
            ));
      }
    } finally {
      if (mounted && _activeMutation == mutation) {
        setState(() => _activeMutation = null);
      }
    }
  }

  Future<void> _reject(InstitutionMembershipRequest request) async {
    final reason = await _showRejectDialog();
    if (reason == null || !mounted) return;
    await _review(
      request,
      InstitutionMembershipDecision.rejected,
      reason,
    );
  }

  Future<String?> _showRejectDialog() async {
    var reason = '';
    String? validation;
    return showDialog<String>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: Text(context.localized('驳回申请', 'Reject request')),
          content: TextField(
            key: const Key('review-reason'),
            autofocus: true,
            maxLines: 3,
            onChanged: (value) => reason = value,
            decoration: InputDecoration(
              labelText: context.localized('驳回原因', 'Rejection reason'),
              errorText: validation,
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: Text(context.localized('取消', 'Cancel')),
            ),
            FilledButton(
              key: const Key('review-confirm'),
              onPressed: () {
                final trimmedReason = reason.trim();
                if (trimmedReason.isEmpty) {
                  setDialogState(() {
                    validation = context.localized(
                      '请填写驳回原因',
                      'Rejection reason is required',
                    );
                  });
                  return;
                }
                Navigator.of(dialogContext).pop(trimmedReason);
              },
              child: Text(context.localized('确认', 'Confirm')),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _review(
    InstitutionMembershipRequest request,
    InstitutionMembershipDecision decision,
    String reviewNote,
  ) async {
    final mutation = 'review-${request.id}';
    if (_activeMutation == mutation) return;
    setState(() {
      _activeMutation = mutation;
      _error = null;
    });
    try {
      await widget.repository.reviewInstitutionMembershipRequest(
        requestType: request.requestType,
        id: request.id,
        decision: decision,
        reviewNote: reviewNote,
      );
      await _refresh();
    } catch (error) {
      if (mounted) {
        setState(() => _error = _mutationError(
              error,
              '审核提交失败，请稍后重试',
              'Unable to submit the review. Try again.',
            ));
      }
    } finally {
      if (mounted && _activeMutation == mutation) {
        setState(() => _activeMutation = null);
      }
    }
  }

  List<String> _currentInstitutionIds(ManagementContext context) =>
      widget.scope == InstitutionRelationshipScope.doctor
          ? context.doctorInstitutionIds
          : context.consultantInstitutionIds;

  Map<String, String> _namesFromRequests(
    List<InstitutionMembershipRequest> requests,
  ) {
    final names = <String, String>{};
    for (final request in requests) {
      final name = _usableName(request.institutionName, request.institutionId);
      if (name != null) names[request.institutionId] = name;
    }
    return names;
  }

  Map<String, String> _mergeCandidateNames(
    Map<String, String> current,
    List<InstitutionMembershipCandidate> candidates,
  ) {
    final names = Map<String, String>.of(current);
    for (final candidate in candidates) {
      final name = _usableName(candidate.name, candidate.id);
      if (name != null) names[candidate.id] = name;
    }
    return names;
  }

  String _institutionName(BuildContext context, String id) =>
      _institutionNames[id] ??
      context.localized(
        '机构名称不可用',
        'Institution name unavailable',
      );

  String _institutionNameForRequest(
    BuildContext context,
    InstitutionMembershipRequest request,
  ) =>
      _usableName(request.institutionName, request.institutionId) ??
      _institutionName(context, request.institutionId);

  String _applicantName(
    BuildContext context,
    InstitutionMembershipRequest request,
  ) =>
      _usableName(request.applicantName, request.applicantId) ??
      context.localized(
        '申请人名称不可用',
        'Applicant name unavailable',
      );

  String? _usableName(String value, String id) {
    final name = value.trim();
    if (name.isEmpty || name == id || _uuidPattern.hasMatch(name)) return null;
    return name;
  }

  String _actionLabel(
    BuildContext context,
    InstitutionMembershipAction action,
  ) =>
      action == InstitutionMembershipAction.leave
          ? context.localized('申请退出', 'Leave institution')
          : context.localized('申请加入', 'Join institution');

  String _statusLabel(
    BuildContext context,
    InstitutionMembershipRequestStatus status,
  ) =>
      switch (status) {
        InstitutionMembershipRequestStatus.pending =>
          context.localized('待审核', 'Pending'),
        InstitutionMembershipRequestStatus.approved =>
          context.localized('已批准', 'Approved'),
        InstitutionMembershipRequestStatus.rejected =>
          context.localized('已驳回', 'Rejected'),
        InstitutionMembershipRequestStatus.withdrawn =>
          context.localized('已撤回', 'Withdrawn'),
      };

  String _requestTypeLabel(
    BuildContext context,
    InstitutionMembershipRequestType type,
  ) =>
      type == InstitutionMembershipRequestType.doctor
          ? context.localized('医生', 'Doctor')
          : context.localized('顾问', 'Consultant');

  String _mutationError(Object error, String chinese, String english) {
    if (error is ApiException && error.message.trim().isNotEmpty) {
      return error.message.trim();
    }
    return context.localized(chinese, english);
  }

  String _timeLabel(DateTime value) =>
      value.toLocal().toIso8601String().replaceFirst('T', ' ').split('.').first;
}

final _uuidPattern = RegExp(
  r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
);

class _SectionTitle extends StatelessWidget {
  const _SectionTitle({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Text(text, style: Theme.of(context).textTheme.titleMedium),
      );
}

class _EmptyText extends StatelessWidget {
  const _EmptyText({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Text(text),
      );
}

class _InlineError extends StatelessWidget {
  const _InlineError({required this.message, required this.onRetry});

  final String message;
  final Future<void> Function() onRetry;

  @override
  Widget build(BuildContext context) => Card(
        color: Theme.of(context).colorScheme.errorContainer,
        margin: const EdgeInsets.only(bottom: 12),
        child: ListTile(
          title: Text(message),
          trailing: TextButton(
            key: const Key('relationship-retry'),
            onPressed: onRetry,
            child: Text(context.localized('重试', 'Retry')),
          ),
        ),
      );
}

class _FatalLoadState extends StatelessWidget {
  const _FatalLoadState({required this.message, required this.onRetry});

  final String message;
  final Future<void> Function() onRetry;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(message, textAlign: TextAlign.center),
              const SizedBox(height: 12),
              FilledButton.icon(
                key: const Key('relationship-retry'),
                onPressed: onRetry,
                icon: const Icon(Icons.refresh_rounded),
                label: Text(context.localized('重试', 'Retry')),
              ),
            ],
          ),
        ),
      );
}
