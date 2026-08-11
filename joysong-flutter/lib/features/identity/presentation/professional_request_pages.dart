import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';

class ManagedDoctorProfilePage extends StatefulWidget {
  const ManagedDoctorProfilePage({required this.repository, super.key});

  final IdentityRepository repository;

  @override
  State<ManagedDoctorProfilePage> createState() =>
      _ManagedDoctorProfilePageState();
}

class _ManagedDoctorProfilePageState extends State<ManagedDoctorProfilePage> {
  ManagedDoctorProfile? _profile;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final profiles = await widget.repository.listManagedDoctorProfiles();
      if (mounted) setState(() => _profile = profiles.firstOrNull);
    } catch (_) {
      if (mounted) setState(() => _error = '医生档案加载失败，请重试');
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(context.localized('医生档案', 'Doctor Profile'))),
        body: _error != null
            ? Center(child: Text(_error!))
            : _profile == null
                ? const Center(child: CircularProgressIndicator())
                : _DoctorProfileForm(
                    repository: widget.repository,
                    profile: _profile!,
                    onSaved: (profile) => setState(() => _profile = profile),
                  ),
      );
}

class _DoctorProfileForm extends StatefulWidget {
  const _DoctorProfileForm({
    required this.repository,
    required this.profile,
    required this.onSaved,
  });

  final IdentityRepository repository;
  final ManagedDoctorProfile profile;
  final ValueChanged<ManagedDoctorProfile> onSaved;

  @override
  State<_DoctorProfileForm> createState() => _DoctorProfileFormState();
}

class _DoctorProfileFormState extends State<_DoctorProfileForm> {
  late final TextEditingController _name;
  late final TextEditingController _title;
  late final TextEditingController _bio;
  late final TextEditingController _phone;
  late final TextEditingController _specialties;
  late final TextEditingController _credentials;
  late final TextEditingController _tags;
  var _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final profile = widget.profile;
    _name = TextEditingController(text: profile.name);
    _title = TextEditingController(text: profile.title);
    _bio = TextEditingController(text: profile.bio);
    _phone = TextEditingController(text: profile.contactPhone);
    _specialties = TextEditingController(text: profile.specialties);
    _credentials = TextEditingController(text: profile.credentials);
    _tags = TextEditingController(text: profile.certificationTags);
  }

  @override
  void dispose() {
    for (final controller in [
      _name,
      _title,
      _bio,
      _phone,
      _specialties,
      _credentials,
      _tags,
    ]) {
      controller.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _requestField(_name, context.localized('姓名', 'Name')),
          _requestField(_title, context.localized('职称', 'Title')),
          _requestField(_bio, context.localized('个人简介', 'Biography'), maxLines: 4),
          _requestField(_phone, context.localized('公开联系电话', 'Public phone')),
          _requestField(
            _specialties,
            context.localized('擅长项目', 'Specialties'),
            maxLines: 3,
          ),
          _requestField(
            _credentials,
            context.localized('公开资历', 'Public credentials'),
            maxLines: 3,
          ),
          _requestField(_tags, context.localized('认证标签', 'Certification tags')),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Text(
                _error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          FilledButton.icon(
            onPressed: _saving ? null : _save,
            icon: const Icon(Icons.save_outlined),
            label: Text(context.localized('保存', 'Save')),
          ),
        ],
      );

  Future<void> _save() async {
    if (_name.text.trim().isEmpty) {
      setState(() => _error = '请填写姓名');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final current = widget.profile;
      final saved = await widget.repository.updateManagedDoctorProfile(
        ManagedDoctorProfileDraft(
          id: current.id,
          name: _name.text,
          title: _title.text,
          bio: _bio.text,
          avatar: current.avatar,
          contactPhone: _phone.text,
          specialties: _specialties.text,
          credentials: _credentials.text,
          credentialImages: current.credentialImages,
          certificationTags: _tags.text,
        ),
      );
      widget.onSaved(saved);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(context.localized('医生档案已保存', 'Profile saved'))),
        );
      }
    } catch (_) {
      if (mounted) setState(() => _error = '医生档案保存失败，请稍后重试');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }
}

class InstitutionMembershipRequestsPage extends StatefulWidget {
  const InstitutionMembershipRequestsPage({
    required this.repository,
    required this.context,
    this.reviewMode = false,
    this.affiliationOnly = false,
    super.key,
  });

  final IdentityRepository repository;
  final ManagementContext context;
  final bool reviewMode;
  final bool affiliationOnly;

  @override
  State<InstitutionMembershipRequestsPage> createState() =>
      _InstitutionMembershipRequestsPageState();
}

class _InstitutionMembershipRequestsPageState
    extends State<InstitutionMembershipRequestsPage> {
  final _note = TextEditingController();
  List<InstitutionOption> _institutions = const [];
  List<InstitutionMembershipRequest> _requests = const [];
  String? _institutionId;
  String? _error;
  var _loading = true;
  var _saving = false;

  String get _requestType => widget.context.activeRoles.contains('DOCTOR')
      ? 'DOCTOR'
      : 'CONSULTANT';

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
      final values = await Future.wait([
        widget.repository.listInstitutionOptions(),
        widget.repository.listInstitutionMembershipRequests(),
      ]);
      if (!mounted) return;
      setState(() {
        _institutions = values[0] as List<InstitutionOption>;
        _requests = values[1] as List<InstitutionMembershipRequest>;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = '机构申请加载失败，请重试';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final visibleRequests = widget.affiliationOnly
        ? _requests.where((item) => item.status == 'APPROVED').toList()
        : _requests;
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.reviewMode
            ? context.localized('成员加入审核', 'Membership Reviews')
            : widget.affiliationOnly
                ? context.localized('机构归属', 'Institution Affiliation')
                : context.localized('申请加入机构', 'Apply to Institution')),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _load,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  if (!widget.reviewMode && !widget.affiliationOnly) ...[
                    DropdownButtonFormField<String>(
                      initialValue: _institutionId,
                      decoration: InputDecoration(
                        labelText: context.localized('目标机构', 'Institution'),
                      ),
                      items: _institutions
                          .map((item) => DropdownMenuItem(
                                value: item.id,
                                child: Text(item.name),
                              ))
                          .toList(),
                      onChanged: (value) =>
                          setState(() => _institutionId = value),
                    ),
                    const SizedBox(height: 12),
                    TextField(
                      controller: _note,
                      maxLines: 3,
                      decoration: InputDecoration(
                        labelText: context.localized('申请说明', 'Application note'),
                      ),
                    ),
                    const SizedBox(height: 12),
                    FilledButton.icon(
                      onPressed: _saving ? null : _submit,
                      icon: const Icon(Icons.send_outlined),
                      label: Text(context.localized('提交申请', 'Submit')),
                    ),
                    const SizedBox(height: 24),
                  ],
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(
                        _error!,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    ),
                  if (visibleRequests.isEmpty)
                    ListTile(
                      enabled: false,
                      title: Text(context.localized(
                        widget.affiliationOnly ? '暂无已确认机构归属' : '暂无申请记录',
                        widget.affiliationOnly
                            ? 'No approved affiliation'
                            : 'No requests',
                      )),
                    )
                  else
                    for (final request in visibleRequests)
                      ListTile(
                        contentPadding: EdgeInsets.zero,
                        leading: const Icon(Icons.apartment_outlined),
                        title: Text(widget.reviewMode
                            ? '${_institutionName(request.institutionId)} · ${request.userId}'
                            : _institutionName(request.institutionId)),
                        subtitle: Text([
                          _statusLabel(request.status),
                          if (request.requestNote.isNotEmpty) request.requestNote,
                          if (request.reviewNote.isNotEmpty)
                            context.localized(
                              '审核意见：${request.reviewNote}',
                              'Review: ${request.reviewNote}',
                            ),
                        ].join('\n')),
                        trailing: widget.reviewMode && request.status == 'PENDING'
                            ? IconButton(
                                tooltip: context.localized('审核', 'Review'),
                                icon: const Icon(Icons.fact_check_outlined),
                                onPressed: () => _review(request),
                              )
                            : null,
                      ),
                ],
              ),
            ),
    );
  }

  Future<void> _submit() async {
    final institutionId = _institutionId;
    if (institutionId == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(context.localized('请选择机构', 'Select an institution'))),
      );
      return;
    }
    setState(() => _saving = true);
    try {
      await widget.repository.submitInstitutionMembershipRequest(
        requestType: _requestType,
        institutionId: institutionId,
        requestNote: _note.text,
      );
      _note.clear();
      await _load();
    } catch (_) {
      if (mounted) setState(() => _error = '机构申请提交失败，请稍后重试');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _review(InstitutionMembershipRequest request) async {
    final review = await showInstitutionMembershipReviewDialog(context);
    if (review == null) return;
    try {
      await widget.repository.reviewInstitutionMembershipRequest(
        requestType: request.requestType,
        id: request.id,
        decision: review.decision,
        reviewNote: review.note,
      );
      await _load();
    } catch (_) {
      if (mounted) setState(() => _error = '审核提交失败，请稍后重试');
    }
  }

  String _institutionName(String id) => _institutions
      .where((item) => item.id == id)
      .map((item) => item.name)
      .firstOrNull ?? id;
}

class PlatformProjectRequestPage extends StatefulWidget {
  const PlatformProjectRequestPage({required this.repository, super.key});

  final IdentityRepository repository;

  @override
  State<PlatformProjectRequestPage> createState() =>
      _PlatformProjectRequestPageState();
}

class _PlatformProjectRequestPageState
    extends State<PlatformProjectRequestPage> {
  final _name = TextEditingController();
  final _category = TextEditingController();
  final _description = TextEditingController();
  final _notes = TextEditingController();
  List<ProfessionalProjectRequest> _requests = const [];
  var _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _name.dispose();
    _category.dispose();
    _description.dispose();
    _notes.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final requests = await widget.repository.listProfessionalProjectRequests();
      if (mounted) {
        setState(() => _requests =
            requests.where((item) => item.requestType == 'PLATFORM').toList());
      }
    } catch (_) {
      if (mounted) setState(() => _error = '项目申请加载失败，请重试');
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: Text(context.localized('申请新增平台项目', 'Request Platform Project')),
        ),
        body: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            _requestField(_name, context.localized('项目名称', 'Project name')),
            _requestField(_category, context.localized('项目分类', 'Category')),
            _requestField(
              _description,
              context.localized('项目说明', 'Description'),
              maxLines: 4,
            ),
            _requestField(_notes, context.localized('补充说明', 'Notes'), maxLines: 3),
            FilledButton.icon(
              onPressed: _saving ? null : _submit,
              icon: const Icon(Icons.send_outlined),
              label: Text(context.localized('提交申请', 'Submit')),
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ],
            const SizedBox(height: 24),
            for (final request in _requests)
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(request.name ?? ''),
                subtitle: Text([
                  request.category ?? '',
                  _statusLabel(request.status),
                  if ((request.reviewNote ?? '').isNotEmpty)
                    '审核意见：${request.reviewNote}',
                ].where((item) => item.isNotEmpty).join('\n')),
              ),
          ],
        ),
      );

  Future<void> _submit() async {
    if ([_name, _category, _description]
        .any((controller) => controller.text.trim().isEmpty)) {
      setState(() => _error = '请完整填写项目名称、分类和说明');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await widget.repository.submitPlatformProjectRequest(
        PlatformProjectRequestDraft(
          name: _name.text,
          category: _category.text,
          description: _description.text,
          notes: _notes.text,
        ),
      );
      _name.clear();
      _category.clear();
      _description.clear();
      _notes.clear();
      await _load();
    } catch (_) {
      if (mounted) setState(() => _error = '项目申请提交失败，请稍后重试');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }
}

class InstitutionProjectRequestsPage extends StatefulWidget {
  const InstitutionProjectRequestsPage({
    required this.repository,
    required this.context,
    this.reviewMode = false,
    super.key,
  });

  final IdentityRepository repository;
  final ManagementContext context;
  final bool reviewMode;

  @override
  State<InstitutionProjectRequestsPage> createState() =>
      _InstitutionProjectRequestsPageState();
}

class _InstitutionProjectRequestsPageState
    extends State<InstitutionProjectRequestsPage> {
  final _serviceContent = TextEditingController();
  final _price = TextEditingController();
  final _notes = TextEditingController();
  List<InstitutionOption> _institutions = const [];
  List<ManagementProjectOption> _projects = const [];
  List<ProfessionalProjectRequest> _requests = const [];
  String? _institutionId;
  String? _projectId;
  String? _error;
  var _loading = true;
  var _saving = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _serviceContent.dispose();
    _price.dispose();
    _notes.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final values = await Future.wait([
        widget.repository.listInstitutionOptions(),
        widget.repository.listManagementProjects(),
        widget.repository.listProfessionalProjectRequests(),
      ]);
      if (!mounted) return;
      final allowedIds = widget.context.doctorInstitutionIds.toSet();
      setState(() {
        _institutions = (values[0] as List<InstitutionOption>)
            .where((item) => widget.reviewMode || allowedIds.contains(item.id))
            .toList();
        _projects = values[1] as List<ManagementProjectOption>;
        _requests = (values[2] as List<ProfessionalProjectRequest>)
            .where((item) => item.requestType == 'INSTITUTION')
            .toList();
        _loading = false;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = '机构项目申请加载失败，请重试';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: Text(widget.reviewMode
              ? context.localized('机构项目申请审核', 'Institution Project Reviews')
              : context.localized('申请新增机构项目', 'Request Institution Project')),
        ),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  if (!widget.reviewMode) ...[
                    DropdownButtonFormField<String>(
                      initialValue: _institutionId,
                      decoration: InputDecoration(
                        labelText: context.localized('目标机构', 'Institution'),
                      ),
                      items: _institutions
                          .map((item) => DropdownMenuItem(
                                value: item.id,
                                child: Text(item.name),
                              ))
                          .toList(),
                      onChanged: (value) => setState(() => _institutionId = value),
                    ),
                    const SizedBox(height: 12),
                    DropdownButtonFormField<String>(
                      initialValue: _projectId,
                      decoration: InputDecoration(
                        labelText: context.localized('平台项目', 'Platform project'),
                      ),
                      items: _projects
                          .map((item) => DropdownMenuItem(
                                value: item.id,
                                child: Text(item.name),
                              ))
                          .toList(),
                      onChanged: (value) => setState(() => _projectId = value),
                    ),
                    const SizedBox(height: 12),
                    _requestField(
                      _serviceContent,
                      context.localized('服务内容', 'Service content'),
                      maxLines: 4,
                    ),
                    _requestField(
                      _price,
                      context.localized('价格建议', 'Price suggestion'),
                      keyboardType: TextInputType.number,
                    ),
                    _requestField(_notes, context.localized('说明', 'Notes'), maxLines: 3),
                    FilledButton.icon(
                      onPressed: _saving ? null : _submit,
                      icon: const Icon(Icons.send_outlined),
                      label: Text(context.localized('提交申请', 'Submit')),
                    ),
                    const SizedBox(height: 24),
                  ],
                  if (_error != null)
                    Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                  for (final request in _requests)
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(Icons.medical_services_outlined),
                      title: Text(request.projectName ?? request.projectId ?? ''),
                      subtitle: Text([
                        request.institutionName ?? request.institutionId ?? '',
                        if ((request.doctorName).isNotEmpty) request.doctorName,
                        if ((request.serviceContent ?? '').isNotEmpty)
                          request.serviceContent!,
                        if (request.priceSuggestion != null)
                          '价格建议：${request.priceSuggestion}',
                        _statusLabel(request.status),
                        if ((request.reviewNote ?? '').isNotEmpty)
                          '审核意见：${request.reviewNote}',
                      ].where((item) => item.isNotEmpty).join('\n')),
                      trailing: widget.reviewMode && request.status == 'PENDING'
                          ? IconButton(
                              tooltip: context.localized('审核', 'Review'),
                              icon: const Icon(Icons.fact_check_outlined),
                              onPressed: () => _review(request),
                            )
                          : null,
                    ),
                ],
              ),
      );

  Future<void> _submit() async {
    final institutionId = _institutionId;
    final projectId = _projectId;
    final price = num.tryParse(_price.text.trim());
    if (institutionId == null ||
        projectId == null ||
        _serviceContent.text.trim().isEmpty ||
        price == null ||
        price < 0) {
      setState(() => _error = '请选择机构和平台项目，并填写服务内容与有效价格建议');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await widget.repository.submitInstitutionProjectRequest(
        InstitutionProjectRequestDraft(
          institutionId: institutionId,
          projectId: projectId,
          serviceContent: _serviceContent.text,
          priceSuggestion: price,
          notes: _notes.text,
        ),
      );
      _serviceContent.clear();
      _price.clear();
      _notes.clear();
      await _load();
    } catch (_) {
      if (mounted) setState(() => _error = '机构项目申请提交失败，请稍后重试');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _review(ProfessionalProjectRequest request) async {
    final review = await showProfessionalProjectReviewDialog(context);
    if (review == null) return;
    try {
      await widget.repository.reviewInstitutionProjectRequest(
        id: request.id,
        decision: review.decision,
        reviewNote: review.note,
      );
      await _load();
    } catch (_) {
      if (mounted) setState(() => _error = '审核提交失败，请稍后重试');
    }
  }
}

class InstitutionProjectJoinRequestsPage extends StatefulWidget {
  const InstitutionProjectJoinRequestsPage({
    required this.repository,
    required this.context,
    this.reviewMode = false,
    super.key,
  });

  final IdentityRepository repository;
  final ManagementContext context;
  final bool reviewMode;

  @override
  State<InstitutionProjectJoinRequestsPage> createState() =>
      _InstitutionProjectJoinRequestsPageState();
}

class _InstitutionProjectJoinRequestsPageState
    extends State<InstitutionProjectJoinRequestsPage> {
  final _serviceDescription = TextEditingController();
  final _priceSuggestion = TextEditingController();
  final _notes = TextEditingController();
  List<ManagedInstitutionProject> _projects = const [];
  List<InstitutionProjectJoinRequest> _requests = const [];
  String? _institutionProjectId;
  String? _error;
  var _loading = true;
  var _saving = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _serviceDescription.dispose();
    _priceSuggestion.dispose();
    _notes.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final requests =
          await widget.repository.listInstitutionProjectJoinRequests();
      final projects = widget.reviewMode
          ? const <ManagedInstitutionProject>[]
          : await widget.repository.listManagedInstitutionProjects();
      if (!mounted) return;
      final institutionIds = (widget.reviewMode
              ? widget.context.managedInstitutionIds
              : widget.context.doctorInstitutionIds)
          .toSet();
      final doctorId = widget.context.doctorId;
      setState(() {
        _requests = requests
            .where((request) => request.requestType == 'JOIN')
            .where((request) => institutionIds.contains(request.institutionId))
            .where((request) =>
                widget.reviewMode || request.doctorId == doctorId)
            .toList(growable: false);
        _projects = projects
            .where((project) => institutionIds.contains(project.institutionId))
            .toList(growable: false);
        _loading = false;
        _error = null;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = '机构项目加入申请加载失败，请重试';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final pendingProjectIds = _requests
        .where((request) => request.status == 'PENDING')
        .map((request) => request.institutionProjectId)
        .toSet();
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.reviewMode
            ? context.localized('机构项目加入审核', 'Project Join Reviews')
            : context.localized('申请加入机构项目', 'Join Institution Project')),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                if (!widget.reviewMode) ...[
                  DropdownButtonFormField<String>(
                    initialValue: _institutionProjectId,
                    isExpanded: true,
                    decoration: InputDecoration(
                      labelText:
                          context.localized('机构项目', 'Institution project'),
                    ),
                    items: _projects.map((project) {
                      final joined = project.hasDoctor(widget.context.doctorId);
                      final pending = pendingProjectIds.contains(project.id);
                      final suffix = joined
                          ? context.localized('（已加入）', ' (joined)')
                          : pending
                              ? context.localized('（审核中）', ' (pending)')
                              : '';
                      return DropdownMenuItem(
                        value: project.id,
                        enabled: !joined && !pending,
                        child: Text(
                          '${project.effectiveName}$suffix',
                          overflow: TextOverflow.ellipsis,
                        ),
                      );
                    }).toList(),
                    onChanged: (value) =>
                        setState(() => _institutionProjectId = value),
                  ),
                  const SizedBox(height: 12),
                  _requestField(
                    _serviceDescription,
                    context.localized('服务说明', 'Service description'),
                    maxLines: 4,
                  ),
                  _requestField(
                    _priceSuggestion,
                    context.localized(
                      '价格建议（必填，不能小于 0）',
                      'Price suggestion (required, non-negative)',
                    ),
                    keyboardType:
                        const TextInputType.numberWithOptions(decimal: true),
                  ),
                  _requestField(
                    _notes,
                    context.localized('补充说明', 'Notes'),
                    maxLines: 3,
                  ),
                  FilledButton.icon(
                    onPressed: _saving ? null : _submit,
                    icon: const Icon(Icons.send_outlined),
                    label: Text(context.localized('提交申请', 'Submit')),
                  ),
                  const SizedBox(height: 24),
                ],
                if (_error != null)
                  Text(
                    _error!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                if (_requests.isEmpty && _error == null)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(context.localized(
                      '暂无机构项目加入申请',
                      'No institution project join requests',
                    )),
                  ),
                for (final request in _requests)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.group_add_outlined),
                    title: Text(request.projectName),
                    subtitle: Text([
                      request.institutionName,
                      if (widget.reviewMode) request.doctorName,
                      request.serviceDescription,
                      '价格建议：${request.priceSuggestion}',
                      if (request.notes.isNotEmpty) request.notes,
                      _statusLabel(request.status),
                      if (request.reviewNote.isNotEmpty)
                        '审核意见：${request.reviewNote}',
                    ].where((item) => item.isNotEmpty).join('\n')),
                    trailing: widget.reviewMode && request.status == 'PENDING'
                        ? IconButton(
                            tooltip: context.localized('审核', 'Review'),
                            icon: const Icon(Icons.fact_check_outlined),
                            onPressed: () => _review(request),
                          )
                        : null,
                  ),
              ],
            ),
    );
  }

  Future<void> _submit() async {
    final price = num.tryParse(_priceSuggestion.text.trim());
    if (price == null) {
      setState(() => _error = '请明确填写非负价格建议');
      return;
    }
    final draft = InstitutionProjectJoinRequestDraft(
      institutionProjectId: _institutionProjectId ?? '',
      serviceDescription: _serviceDescription.text,
      priceSuggestion: price,
      notes: _notes.text,
    );
    try {
      draft.validate();
    } on ArgumentError catch (error) {
      setState(() => _error = error.message.toString());
      return;
    }
    final project = _projects.firstWhere(
      (item) => item.id == draft.institutionProjectId.trim(),
    );
    if (project.hasDoctor(widget.context.doctorId)) {
      setState(() => _error = '您已加入该机构项目，不能重复申请');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await widget.repository.submitInstitutionProjectJoinRequest(draft);
      _institutionProjectId = null;
      _serviceDescription.clear();
      _priceSuggestion.clear();
      _notes.clear();
      await _load();
    } catch (_) {
      if (mounted) setState(() => _error = '机构项目加入申请提交失败，请稍后重试');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _review(InstitutionProjectJoinRequest request) async {
    final review = await showProfessionalProjectReviewDialog(context);
    if (review == null) return;
    try {
      await widget.repository.reviewInstitutionProjectJoinRequest(
        id: request.id,
        decision: review.decision,
        reviewNote: review.note,
      );
      await _load();
    } catch (_) {
      if (mounted) setState(() => _error = '审核提交失败，请稍后重试');
    }
  }
}

Future<({String decision, String note})?> showInstitutionMembershipReviewDialog(
  BuildContext context,
) =>
    _showReviewDialog(
      context,
      decisions: const [
        DropdownMenuItem(value: 'APPROVED', child: Text('通过')),
        DropdownMenuItem(value: 'REJECTED', child: Text('驳回')),
      ],
    );

Future<({String decision, String note})?> showProfessionalProjectReviewDialog(
  BuildContext context,
) =>
    _showReviewDialog(
      context,
      decisions: const [
        DropdownMenuItem(value: 'APPROVED', child: Text('通过')),
        DropdownMenuItem(value: 'REJECTED', child: Text('驳回')),
        DropdownMenuItem(
          value: 'CHANGES_REQUESTED',
          child: Text('要求修改'),
        ),
      ],
    );

Future<({String decision, String note})?> _showReviewDialog(
  BuildContext context, {
  required List<DropdownMenuItem<String>> decisions,
}) async {
  final note = TextEditingController();
  var decision = 'APPROVED';
  final result = await showDialog<({String decision, String note})>(
    context: context,
    builder: (dialogContext) => StatefulBuilder(
      builder: (context, setDialogState) => AlertDialog(
        title: Text(context.localized('审核申请', 'Review Request')),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            DropdownButtonFormField<String>(
              initialValue: decision,
              items: decisions,
              onChanged: (value) =>
                  setDialogState(() => decision = value ?? 'APPROVED'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: note,
              maxLines: 3,
              decoration: InputDecoration(
                labelText: context.localized('审核意见', 'Review note'),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: Text(context.localized('取消', 'Cancel')),
          ),
          FilledButton(
            onPressed: () {
              if (decision != 'APPROVED' && note.text.trim().isEmpty) return;
              Navigator.of(dialogContext).pop((
                decision: decision,
                note: note.text.trim(),
              ));
            },
            child: Text(context.localized('确认', 'Confirm')),
          ),
        ],
      ),
    ),
  );
  note.dispose();
  return result;
}

Widget _requestField(
  TextEditingController controller,
  String label, {
  int maxLines = 1,
  TextInputType? keyboardType,
}) =>
    Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TextField(
        controller: controller,
        maxLines: maxLines,
        keyboardType: keyboardType,
        decoration: InputDecoration(labelText: label),
      ),
    );

String _statusLabel(String status) => switch (status) {
      'PENDING' => '待审核',
      'APPROVED' => '已通过',
      'REJECTED' => '已驳回',
      'CHANGES_REQUESTED' => '待修改',
      'REVOKED' => '已撤销',
      _ => status,
    };
