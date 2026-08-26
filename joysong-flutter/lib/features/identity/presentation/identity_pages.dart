import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/files/app_file_picker.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/transient_message.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/professional_catalog_page.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/consultant_management_pages.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_controller.dart';
import 'package:joysong_flutter/features/identity/presentation/institution_relationships_page.dart';
import 'package:joysong_flutter/features/identity/presentation/professional_request_pages.dart';
import 'package:joysong_flutter/features/professional_management/data/professional_repository.dart';
import 'package:joysong_flutter/features/professional_management/presentation/professional_pages.dart';

typedef IdentityFilePicker = Future<IdentityFileDraft?> Function(
    IdentityDocumentType type);
typedef InstitutionProfileImagePicker = Future<String?> Function();

class IdentityCenterPage extends StatefulWidget {
  const IdentityCenterPage({
    required this.repository,
    this.filePicker,
    this.initialApplicationId,
    super.key,
  });

  final IdentityRepository repository;
  final IdentityFilePicker? filePicker;
  final String? initialApplicationId;

  @override
  State<IdentityCenterPage> createState() => _IdentityCenterPageState();
}

class _IdentityCenterPageState extends State<IdentityCenterPage> {
  late final IdentityController _controller;
  bool _hasLoaded = false;

  @override
  void initState() {
    super.initState();
    _controller = IdentityController(widget.repository);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _controller.setMessageResolver(
      (chinese, english) => context.localized(chinese, english),
    );
    if (!_hasLoaded) {
      _hasLoaded = true;
      _controller.load();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(context.localized('身份认证', 'Identity verification')),
      ),
      body: ListenableBuilder(
        listenable: _controller,
        builder: (context, _) {
          return switch (_controller.status) {
            IdentityLoadStatus.idle ||
            IdentityLoadStatus.loading =>
              const Center(child: CircularProgressIndicator()),
            IdentityLoadStatus.failure => _IdentityFailure(
                message: _controller.errorMessage ??
                    context.localized(
                      '身份信息加载失败',
                      'Unable to load identity information.',
                    ),
                onRetry: _controller.load,
              ),
            IdentityLoadStatus.ready => _IdentityOverviewView(
                controller: _controller,
                filePicker: widget.filePicker ?? _pickIdentityFile,
                initialApplicationId: widget.initialApplicationId,
              ),
          };
        },
      ),
    );
  }
}

class _IdentityOverviewView extends StatefulWidget {
  const _IdentityOverviewView({
    required this.controller,
    required this.filePicker,
    this.initialApplicationId,
  });

  final IdentityController controller;
  final IdentityFilePicker? filePicker;
  final String? initialApplicationId;

  @override
  State<_IdentityOverviewView> createState() => _IdentityOverviewViewState();
}

class _IdentityOverviewViewState extends State<_IdentityOverviewView> {
  var _applicationsExpanded = false;
  var _hasUserToggledApplications = false;

  @override
  Widget build(BuildContext context) {
    final overview = widget.controller.overview;
    final applications = overview.applications;
    final focusedApplicationId = widget.initialApplicationId?.trim();
    final isFocusedApplicationPresent = focusedApplicationId != null &&
        focusedApplicationId.isNotEmpty &&
        applications.any(
          (application) => application.id == focusedApplicationId,
        );
    final applicationsExpanded = _hasUserToggledApplications
        ? _applicationsExpanded
        : isFocusedApplicationPresent;
    final historyToggleText = applicationsExpanded
        ? context.localized('收起申请记录', 'Hide application history')
        : context.localized(
            '展开全部申请记录（${applications.length}）',
            'Show all applications (${applications.length})',
          );
    return RefreshIndicator(
      onRefresh: widget.controller.load,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
        children: [
          _SectionHeading(
            icon: Icons.badge_outlined,
            title: context.localized('我的专业身份', 'My professional identities'),
          ),
          const SizedBox(height: 10),
          if (overview.roles.isEmpty)
            _InfoCard(
              text: context.localized(
                '尚未取得专业身份，普通用户功能不受影响。',
                'You do not have a professional identity yet. Regular user features are unaffected.',
              ),
            )
          else
            for (final role in overview.roles)
              _StatusCard(
                title: _identityRoleLabel(context, role.role),
                status: role.status,
                detail: role.status == IdentityStatus.revoked
                    ? context.localized(
                        '该身份已撤销，专业入口将立即关闭',
                        'This identity was revoked and professional access is now closed.',
                      )
                    : context.localized(
                        '认证状态由平台审核结果决定',
                        'Verification status is determined by the platform review.',
                      ),
              ),
          const SizedBox(height: 20),
          if (applications.isEmpty)
            Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _SectionHeading(
                  icon: Icons.history_rounded,
                  title: context.localized('申请记录', 'Application history'),
                ),
                const SizedBox(height: 10),
                _InfoCard(
                  text: context.localized(
                    '暂无身份申请记录',
                    'No identity applications yet',
                  ),
                ),
              ],
            )
          else
            Card(
              key: const Key('identity-application-history'),
              margin: EdgeInsets.zero,
              clipBehavior: Clip.antiAlias,
              child: Column(
                children: [
                  Semantics(
                    key: const Key('identity-application-history-toggle'),
                    container: true,
                    button: true,
                    expanded: applicationsExpanded,
                    label: historyToggleText,
                    child: ListTile(
                      leading: const Icon(Icons.history_rounded),
                      title: Text(
                        context.localized('申请记录', 'Application history'),
                      ),
                      subtitle: Text(historyToggleText),
                      trailing: Icon(
                        applicationsExpanded
                            ? Icons.expand_less_rounded
                            : Icons.expand_more_rounded,
                      ),
                      onTap: () => setState(() {
                        _hasUserToggledApplications = true;
                        _applicationsExpanded = !applicationsExpanded;
                      }),
                    ),
                  ),
                  if (applicationsExpanded) ...[
                    const Divider(height: 1),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
                      child: Column(
                        children: [
                          for (final application in applications)
                            _StatusCard(
                              key: ValueKey(
                                'identity-application-${application.id}',
                              ),
                              title: _identityRoleLabel(
                                context,
                                application.role,
                              ),
                              status: application.status,
                              detail: _identityApplicationDetail(
                                context,
                                application,
                              ),
                              metadata: _identityApplicationTimestamp(
                                context,
                                application,
                              ),
                            ),
                        ],
                      ),
                    ),
                  ],
                ],
              ),
            ),
          const SizedBox(height: 20),
          FilledButton.icon(
            key: const Key('start-identity-application'),
            onPressed: overview.hasPendingApplication
                ? null
                : () => _selectRole(context),
            icon: const Icon(Icons.verified_user_outlined),
            label: Text(
              overview.hasPendingApplication
                  ? context.localized(
                      '已有申请正在审核',
                      'An application is under review',
                    )
                  : context.localized(
                      '申请专业身份',
                      'Apply for a professional identity',
                    ),
            ),
          ),
          const SizedBox(height: 12),
          Text(
            context.localized(
              '认证材料通过私有接口上传，不会进入公开图片库，也不会生成可公开访问的 URL。',
              'Verification documents are uploaded privately and never added to the public media library or exposed through a public URL.',
            ),
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
        ],
      ),
    );
  }

  Future<void> _selectRole(BuildContext context) async {
    final role = await showModalBottomSheet<IdentityRoleType>(
      context: context,
      showDragHandle: true,
      constraints: const BoxConstraints(maxWidth: 420),
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              title: Text(
                context.localized(
                  '选择申请身份',
                  'Choose a professional identity',
                ),
              ),
            ),
            for (final role in IdentityRoleType.values.where(
              (item) => item != IdentityRoleType.unknown,
            ))
              ListTile(
                leading: Icon(_roleIcon(role)),
                title: Text(_identityRoleLabel(context, role)),
                onTap: () => Navigator.pop(context, role),
              ),
          ],
        ),
      ),
    );
    if (role == null || !context.mounted) {
      return;
    }
    await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) => IdentityApplicationPage(
          controller: widget.controller,
          role: role,
          filePicker: widget.filePicker,
        ),
      ),
    );
  }
}

class IdentityApplicationPage extends StatefulWidget {
  const IdentityApplicationPage({
    required this.controller,
    required this.role,
    this.filePicker,
    super.key,
  });

  final IdentityController controller;
  final IdentityRoleType role;
  final IdentityFilePicker? filePicker;

  @override
  State<IdentityApplicationPage> createState() =>
      _IdentityApplicationPageState();
}

class _IdentityApplicationPageState extends State<IdentityApplicationPage> {
  final _formKey = GlobalKey<FormState>();
  late final Map<String, TextEditingController> _fields;
  final Map<IdentityDocumentType, PrivateIdentityFile> _documents = {};

  @override
  void initState() {
    super.initState();
    _fields = {
      for (final field in _fieldsFor(widget.role))
        field.name: TextEditingController(),
    };
  }

  @override
  void dispose() {
    for (final controller in _fields.values) {
      controller.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final requiredDocuments = _requiredDocuments(widget.role);
    return Scaffold(
      appBar: AppBar(
        title: Text(
          context.localized(
            '申请${widget.role.label}身份',
            'Apply for ${_englishRoleLabel(widget.role)} identity',
          ),
        ),
      ),
      body: ListenableBuilder(
        listenable: widget.controller,
        builder: (context, _) => Form(
          key: _formKey,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _InfoCard(
                text: context.localized(
                  '请仅提交真实、必要的认证信息。证件号码与材料不会显示在公开主页。',
                  'Submit only accurate, necessary verification information. ID numbers and documents are never shown publicly.',
                ),
              ),
              const SizedBox(height: 16),
              for (final field in _fieldsFor(widget.role)) ...[
                TextFormField(
                  key: Key('identity-field-${field.name}'),
                  controller: _fields[field.name],
                  obscureText: field.name == 'idNumber',
                  maxLines: field.multiline ? 3 : 1,
                  decoration: InputDecoration(
                    labelText: context.localized(
                      field.label,
                      field.englishLabel,
                    ),
                  ),
                  validator: (value) => (value ?? '').trim().isEmpty
                      ? context.localized(
                          '请填写${field.label}',
                          '${field.englishLabel} is required.',
                        )
                      : null,
                ),
                const SizedBox(height: 12),
              ],
              const SizedBox(height: 8),
              Text(
                context.localized(
                  '私有认证材料',
                  'Private verification documents',
                ),
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 8),
              for (final type in requiredDocuments)
                _DocumentTile(
                  type: type,
                  file: _documents[type],
                  isUploading: widget.controller.isUploading(type),
                  canPick: widget.filePicker != null,
                  onUpload: () => _upload(type),
                  onDelete: () => _delete(type),
                ),
              if (widget.filePicker == null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    context.localized(
                      '当前构建未接入系统文件选择器，材料上传入口暂不可用。',
                      'Document selection is unavailable in this build.',
                    ),
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ),
              if (widget.controller.errorMessage != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(
                    widget.controller.errorMessage!,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ),
              const SizedBox(height: 20),
              FilledButton(
                key: const Key('submit-identity-application'),
                onPressed: widget.controller.isSubmitting ? null : _submit,
                child: Text(
                  widget.controller.isSubmitting
                      ? context.localized('提交中…', 'Submitting…')
                      : context.localized('提交审核', 'Submit for review'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _upload(IdentityDocumentType type) async {
    try {
      final draft = await widget.filePicker?.call(type);
      if (draft == null) return;
      final file = await widget.controller.upload(draft);
      if (file != null && mounted) {
        setState(() => _documents[type] = file);
      }
    } catch (_) {
      if (!mounted) return;
      final english = Localizations.localeOf(context).languageCode == 'en';
      showTransientMessage(
        context,
        english
            ? 'Unable to read the selected file. Please try another file.'
            : '无法读取所选文件，请更换文件后重试',
      );
    }
  }

  Future<void> _delete(IdentityDocumentType type) async {
    final file = _documents[type];
    if (file == null) {
      return;
    }
    if (await widget.controller.deleteDraft(file.fileId) && mounted) {
      setState(() => _documents.remove(type));
    }
  }

  Future<void> _submit() async {
    FocusManager.instance.primaryFocus?.unfocus();
    if (!(_formKey.currentState?.validate() ?? false)) {
      return;
    }
    final application = IdentityApplicationDraft(
      role: widget.role,
      applicationData: {
        for (final entry in _fields.entries) entry.key: entry.value.text.trim(),
      },
      documents: [
        for (final entry in _documents.entries)
          IdentityDocumentReference(
            fileId: entry.value.fileId,
            type: entry.key,
          ),
      ],
    );
    if (await widget.controller.submit(application) && mounted) {
      Navigator.of(context).pop(true);
    }
  }
}

Future<IdentityFileDraft?> _pickIdentityFile(IdentityDocumentType type) async {
  final selected = await const AppFilePicker().pickIdentityDocument();
  if (selected == null) return null;
  return IdentityFileDraft(
    bytes: selected.bytes,
    fileName: selected.fileName,
    contentType: selected.mimeType,
    purpose: type,
  );
}

Future<String?> _pickInstitutionProfileImage() async {
  final selected = await const AppFilePicker().pickImage();
  return selected?.fileName.trim();
}

class ManagementCenterPage extends StatefulWidget {
  const ManagementCenterPage({
    required this.repository,
    required this.discoverRepository,
    this.institutionImagePicker,
    this.doctorImagePicker,
    this.professionalRepository,
    super.key,
  });

  final IdentityRepository repository;
  final DiscoverRepository discoverRepository;
  final InstitutionProfileImagePicker? institutionImagePicker;
  final Future<String?> Function()? doctorImagePicker;
  final ProfessionalRepository? professionalRepository;

  @override
  State<ManagementCenterPage> createState() => _ManagementCenterPageState();
}

class _ManagementCenterPageState extends State<ManagementCenterPage> {
  late final ManagementController _controller;

  Future<ManagementContext?> _refreshManagementContext() async {
    await _controller.enter();
    return _controller.context;
  }

  @override
  void initState() {
    super.initState();
    _controller = ManagementController(widget.repository)..enter();
  }

  @override
  void dispose() {
    _controller
      ..clear()
      ..dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(context.localized('专业管理中心', 'Professional Center')),
        actions: [
          IconButton(
            tooltip: context.localized('刷新权限', 'Refresh access'),
            onPressed: _controller.enter,
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: ListenableBuilder(
        listenable: _controller,
        builder: (context, _) {
          return switch (_controller.status) {
            ManagementLoadStatus.idle ||
            ManagementLoadStatus.loading =>
              const Center(child: CircularProgressIndicator()),
            ManagementLoadStatus.denied ||
            ManagementLoadStatus.failure =>
              _IdentityFailure(
                message: _controller.errorMessage ??
                    context.localized('没有专业管理权限', 'No professional access'),
                onRetry: _controller.enter,
              ),
            ManagementLoadStatus.ready => _ManagementCapabilities(
                context: _controller.context!,
                repository: widget.repository,
                discoverRepository: widget.discoverRepository,
                onRefresh: _controller.enter,
                refreshManagementContext: _refreshManagementContext,
                institutionImagePicker: widget.institutionImagePicker,
                doctorImagePicker: widget.doctorImagePicker,
                professionalRepository: widget.professionalRepository,
              ),
          };
        },
      ),
    );
  }
}

class _ManagementCapabilities extends StatelessWidget {
  const _ManagementCapabilities({
    required this.context,
    required this.repository,
    required this.discoverRepository,
    required this.onRefresh,
    required this.refreshManagementContext,
    this.institutionImagePicker,
    this.doctorImagePicker,
    this.professionalRepository,
  });

  final ManagementContext context;
  final IdentityRepository repository;
  final DiscoverRepository discoverRepository;
  final Future<void> Function() onRefresh;
  final Future<ManagementContext?> Function() refreshManagementContext;
  final InstitutionProfileImagePicker? institutionImagePicker;
  final Future<String?> Function()? doctorImagePicker;
  final ProfessionalRepository? professionalRepository;

  @override
  Widget build(BuildContext buildContext) {
    final isAdmin = context.platformRole == 'ADMIN';
    final isLegalRepresentative = context.activeRoles.contains(
      IdentityRoleType.institutionLegalRepresentative.code,
    );
    final isDoctor = context.activeRoles.contains(IdentityRoleType.doctor.code);
    final isConsultant = context.activeRoles.contains(
      IdentityRoleType.consultant.code,
    );
    final groups = <({
      String id,
      IconData icon,
      String title,
      List<
          ({
            IconData icon,
            String label,
            bool enabled,
            _ManagementAction action,
          })> items,
    })>[
      (
        id: 'legal-representative',
        icon: Icons.apartment_outlined,
        title: buildContext.localized('法人', 'Legal representative'),
        items: [
          (
            icon: Icons.menu_book_outlined,
            label: buildContext.localized('专业目录', 'Professional catalog'),
            enabled: isLegalRepresentative &&
                context.visibleInstitutionIds.isNotEmpty &&
                discoverRepository is ProfessionalCatalogRepository,
            action: _ManagementAction.legalProfessionalCatalog,
          ),
          (
            icon: Icons.apartment_outlined,
            label: buildContext.localized('机构档案', 'Institution profile'),
            enabled: isLegalRepresentative && context.canManageInstitutions,
            action: _ManagementAction.institutionProfile,
          ),
          (
            icon: Icons.how_to_reg_outlined,
            label: buildContext.localized(
              '机构关系审核',
              'Institution relationship reviews',
            ),
            enabled:
                isLegalRepresentative && context.canReviewInstitutionRequests,
            action: _ManagementAction.legalInstitutionRelationships,
          ),
          (
            icon: Icons.fact_check_outlined,
            label: buildContext.localized(
              '机构项目申请审核',
              'Institution project reviews',
            ),
            enabled: isLegalRepresentative &&
                context.canReviewInstitutionProjectRequests,
            action: _ManagementAction.institutionProjectReviews,
          ),
          (
            icon: Icons.group_add_outlined,
            label: buildContext.localized(
              '机构项目加入审核',
              'Institution project join reviews',
            ),
            enabled: isLegalRepresentative &&
                context.canReviewInstitutionProjectRequests,
            action: _ManagementAction.institutionProjectJoinReviews,
          ),
          (
            icon: Icons.compare_arrows_outlined,
            label: buildContext.localized(
              '医生项目资料审核',
              'Doctor project profile reviews',
            ),
            enabled: !isAdmin &&
                isLegalRepresentative &&
                context.canReviewInstitutionProjectRequests,
            action: _ManagementAction.doctorProjectProfileReviews,
          ),
        ],
      ),
      (
        id: 'platform-admin',
        icon: Icons.admin_panel_settings_outlined,
        title: buildContext.localized('平台管理', 'Platform administration'),
        items: [
          (
            icon: Icons.fact_check_outlined,
            label: buildContext.localized(
              '平台项目申请审核',
              'Platform project creation reviews',
            ),
            enabled: isAdmin && context.canReviewInstitutionProjectRequests,
            action: _ManagementAction.platformProjectReviews,
          ),
          (
            icon: Icons.add_task_outlined,
            label: buildContext.localized(
              '机构项目申请审核',
              'Institution project creation reviews',
            ),
            enabled: isAdmin && context.canReviewInstitutionProjectRequests,
            action: _ManagementAction.institutionProjectReviews,
          ),
          (
            icon: Icons.compare_arrows_outlined,
            label: buildContext.localized(
              '医生项目资料审核',
              'Doctor project profile reviews',
            ),
            enabled: isAdmin && context.canReviewInstitutionProjectRequests,
            action: _ManagementAction.doctorProjectProfileReviews,
          ),
        ],
      ),
      (
        id: 'doctor',
        icon: Icons.medical_services_outlined,
        title: buildContext.localized('医生', 'Doctor'),
        items: [
          (
            icon: Icons.menu_book_outlined,
            label: buildContext.localized('专业目录', 'Professional catalog'),
            enabled: isDoctor &&
                context.doctorId != null &&
                discoverRepository is ProfessionalCatalogRepository,
            action: _ManagementAction.doctorProfessionalCatalog,
          ),
          (
            icon: Icons.medical_services_outlined,
            label: buildContext.localized('医生档案', 'Doctor profile'),
            enabled: context.activeRoles.contains(
                  IdentityRoleType.doctor.code,
                ) &&
                context.canManageDoctors,
            action: _ManagementAction.doctorProfile,
          ),
          (
            icon: Icons.add_business_outlined,
            label: buildContext.localized('机构关系', 'Institution relationships'),
            enabled: isDoctor,
            action: _ManagementAction.doctorInstitutionRelationships,
          ),
          (
            icon: Icons.post_add_outlined,
            label: buildContext.localized(
              '申请新增平台项目',
              'Request platform project',
            ),
            enabled: isDoctor && context.canSubmitPlatformProjectRequests,
            action: _ManagementAction.platformProjectRequest,
          ),
          (
            icon: Icons.add_task_outlined,
            label: buildContext.localized(
              '新增机构项目',
              'Add institution project',
            ),
            enabled: isDoctor && context.canSubmitInstitutionProjectRequests,
            action: _ManagementAction.institutionProjectRequest,
          ),
          (
            icon: Icons.group_add_outlined,
            label: buildContext.localized(
              '加入机构项目',
              'Join institution project',
            ),
            enabled: isDoctor && context.canSubmitInstitutionProjectRequests,
            action: _ManagementAction.institutionProjectJoinRequest,
          ),
          (
            icon: Icons.edit_note_outlined,
            label: buildContext.localized(
              '编辑机构项目',
              'Edit institution project',
            ),
            enabled: isDoctor && context.canSubmitInstitutionProjectRequests,
            action: _ManagementAction.doctorProjectProfileUpdate,
          ),
          (
            icon: Icons.article_outlined,
            label: buildContext.localized('专业文章', 'Professional articles'),
            enabled: isDoctor &&
                context.canManageArticles &&
                professionalRepository != null,
            action: _ManagementAction.doctorArticles,
          ),
          (
            icon: Icons.receipt_long_outlined,
            label: buildContext.localized('专业订单', 'Professional orders'),
            enabled: isDoctor &&
                context.canManageOrders &&
                professionalRepository != null,
            action: _ManagementAction.doctorOrders,
          ),
        ],
      ),
      (
        id: 'consultant',
        icon: Icons.support_agent_outlined,
        title: buildContext.localized('顾问', 'Consultant'),
        items: [
          (
            icon: Icons.account_tree_outlined,
            label: buildContext.localized('机构关系', 'Institution relationships'),
            enabled: isConsultant,
            action: _ManagementAction.consultantInstitutionRelationships,
          ),
          (
            icon: Icons.spa_outlined,
            label: buildContext.localized('项目目录', 'Project catalog'),
            enabled: isConsultant,
            action: _ManagementAction.consultantProjects,
          ),
        ],
      ),
    ];
    final availableGroups = [
      for (final group in groups)
        if (group.items.any((item) => item.enabled))
          (
            id: group.id,
            icon: group.icon,
            title: group.title,
            items: group.items
                .where((item) => item.enabled)
                .toList(growable: false),
          ),
    ];
    final capabilityCount = availableGroups.fold<int>(
      0,
      (total, group) => total + group.items.length,
    );

    return RefreshIndicator(
      onRefresh: onRefresh,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
        children: [
          _SectionHeading(
            icon: Icons.dashboard_customize_outlined,
            title: buildContext.localized('当前可用功能', 'Available tools'),
          ),
          const SizedBox(height: 10),
          _InfoCard(
            text: buildContext.localized(
              '${availableGroups.length} 个身份模块 · $capabilityCount 项功能。仅显示当前账号可使用的管理功能，权限会在进入和刷新时重新校验。',
              '$capabilityCount available ${capabilityCount == 1 ? 'tool' : 'tools'} across ${availableGroups.length} ${availableGroups.length == 1 ? 'identity group' : 'identity groups'}. Access is checked again on entry and refresh.',
            ),
          ),
          const SizedBox(height: 16),
          if (availableGroups.isEmpty)
            _InfoCard(
              text: buildContext.localized(
                '当前没有可展示的管理功能，请刷新权限或联系平台。',
                'No management tools are currently available. Refresh access or contact the platform.',
              ),
            )
          else
            for (var index = 0; index < availableGroups.length; index++) ...[
              Card(
                margin: const EdgeInsets.only(bottom: 12),
                clipBehavior: Clip.antiAlias,
                child: ExpansionTile(
                  key: ValueKey(
                    'management-group-${availableGroups[index].id}',
                  ),
                  tilePadding:
                      const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                  childrenPadding: const EdgeInsets.only(bottom: 8),
                  leading: Container(
                    width: 40,
                    height: 40,
                    decoration: BoxDecoration(
                      color:
                          Theme.of(buildContext).colorScheme.primaryContainer,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Icon(
                      availableGroups[index].icon,
                      color:
                          Theme.of(buildContext).colorScheme.onPrimaryContainer,
                    ),
                  ),
                  title: Text(availableGroups[index].title),
                  subtitle: Text(
                    buildContext.localized(
                      '${availableGroups[index].items.length} 项可用功能',
                      '${availableGroups[index].items.length} available ${availableGroups[index].items.length == 1 ? 'tool' : 'tools'}',
                    ),
                  ),
                  initiallyExpanded: true,
                  children: [
                    for (final capability in availableGroups[index].items)
                      ListTile(
                        key: _managementActionKey(capability.action),
                        leading: Icon(capability.icon),
                        title: Text(capability.label),
                        trailing: const Icon(Icons.chevron_right_rounded),
                        onTap: () => _openCapability(
                          buildContext,
                          capability.action,
                        ),
                      ),
                  ],
                ),
              ),
            ],
        ],
      ),
    );
  }

  void _openCapability(
    BuildContext context,
    _ManagementAction action,
  ) {
    final catalogScope = switch (action) {
      _ManagementAction.doctorProfessionalCatalog =>
        ProfessionalCatalogScope.doctor,
      _ManagementAction.legalProfessionalCatalog =>
        ProfessionalCatalogScope.legalRepresentative,
      _ => null,
    };
    if (catalogScope != null &&
        discoverRepository is ProfessionalCatalogRepository) {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => ProfessionalCatalogPage(
            repository: discoverRepository as ProfessionalCatalogRepository,
            scope: catalogScope,
          ),
        ),
      );
      return;
    }
    final relationshipScope = switch (action) {
      _ManagementAction.doctorInstitutionRelationships =>
        InstitutionRelationshipScope.doctor,
      _ManagementAction.consultantInstitutionRelationships =>
        InstitutionRelationshipScope.consultant,
      _ManagementAction.legalInstitutionRelationships =>
        InstitutionRelationshipScope.legalRepresentative,
      _ => null,
    };
    if (relationshipScope != null) {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => InstitutionRelationshipsPage(
            repository: repository,
            scope: relationshipScope,
          ),
        ),
      );
      return;
    }
    if (action == _ManagementAction.doctorArticles &&
        professionalRepository != null) {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => DoctorArticlesPage(
            repository: professionalRepository!,
            pickCoverImage: doctorImagePicker,
          ),
        ),
      );
      return;
    }
    if (action == _ManagementAction.doctorOrders &&
        professionalRepository != null) {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => DoctorOrdersPage(repository: professionalRepository!),
        ),
      );
      return;
    }
    if (action == _ManagementAction.doctorProfile) {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => DoctorSelfProfilePage(
            repository: repository,
            pickAndUploadImage: doctorImagePicker,
          ),
        ),
      );
      return;
    }
    if (action == _ManagementAction.institutionProfile) {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => ManagedInstitutionProfilesPage(
            repository: repository,
            imagePicker: institutionImagePicker ?? _pickInstitutionProfileImage,
          ),
        ),
      );
      return;
    }
    if (action == _ManagementAction.consultantProjects) {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => ConsultantProjectCatalogPage(repository: repository),
        ),
      );
      return;
    }
    if (action == _ManagementAction.platformProjectRequest) {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => PlatformProjectRequestPage(
            repository: repository,
            context: this.context,
            pickAndUploadImage: doctorImagePicker,
          ),
        ),
      );
      return;
    }
    if (action == _ManagementAction.platformProjectReviews) {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => PlatformProjectRequestPage(
            repository: repository,
            context: this.context,
            reviewMode: true,
            pickAndUploadImage: doctorImagePicker,
          ),
        ),
      );
      return;
    }
    if (action == _ManagementAction.institutionProjectRequest) {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => InstitutionProjectRequestsPage(
            repository: repository,
            context: this.context,
            pickAndUploadImage: doctorImagePicker,
          ),
        ),
      );
      return;
    }
    if (action == _ManagementAction.institutionProjectReviews) {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => InstitutionProjectRequestsPage(
            repository: repository,
            context: this.context,
            reviewMode: true,
            pickAndUploadImage: doctorImagePicker,
            onRefreshManagementContext: refreshManagementContext,
          ),
        ),
      );
      return;
    }
    if (action == _ManagementAction.institutionProjectJoinRequest) {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => InstitutionProjectJoinRequestsPage(
            repository: repository,
            context: this.context,
          ),
        ),
      );
      return;
    }
    if (action == _ManagementAction.doctorProjectProfileUpdate) {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => DoctorProjectProfileUpdatePage(
            repository: repository,
            pickAndUploadImage: doctorImagePicker,
          ),
        ),
      );
      return;
    }
    if (action == _ManagementAction.institutionProjectJoinReviews) {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => InstitutionProjectJoinRequestsPage(
            repository: repository,
            context: this.context,
            reviewMode: true,
          ),
        ),
      );
      return;
    }
    if (action == _ManagementAction.doctorProjectProfileReviews) {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => DoctorProjectProfileReviewPage(
            repository: repository,
            context: this.context,
            onRefreshManagementContext: refreshManagementContext,
          ),
        ),
      );
      return;
    }
    showTransientMessage(
      context,
      context.localized(
        '该功能当前不可用',
        'This feature is currently unavailable',
      ),
    );
  }
}

enum _ManagementAction {
  doctorProfessionalCatalog,
  legalProfessionalCatalog,
  doctorInstitutionRelationships,
  consultantInstitutionRelationships,
  legalInstitutionRelationships,
  institutionProfile,
  institutionProjectReviews,
  institutionProjectJoinReviews,
  doctorProjectProfileReviews,
  doctorProfile,
  platformProjectRequest,
  platformProjectReviews,
  institutionProjectRequest,
  institutionProjectJoinRequest,
  doctorProjectProfileUpdate,
  consultantProjects,
  doctorArticles,
  doctorOrders,
}

Key? _managementActionKey(_ManagementAction action) => switch (action) {
      _ManagementAction.doctorInstitutionRelationships =>
        const Key('management-institution-relationships-doctor'),
      _ManagementAction.consultantInstitutionRelationships =>
        const Key('management-institution-relationships-consultant'),
      _ManagementAction.legalInstitutionRelationships =>
        const Key('management-institution-relationships-legal-representative'),
      _ManagementAction.doctorProfessionalCatalog =>
        const Key('management-professional-catalog-doctor'),
      _ManagementAction.legalProfessionalCatalog =>
        const Key('management-professional-catalog-legal-representative'),
      _ => null,
    };

class ManagedInstitutionProfilesPage extends StatefulWidget {
  const ManagedInstitutionProfilesPage({
    required this.repository,
    this.imagePicker,
    super.key,
  });

  final IdentityRepository repository;
  final InstitutionProfileImagePicker? imagePicker;

  @override
  State<ManagedInstitutionProfilesPage> createState() =>
      _ManagedInstitutionProfilesPageState();
}

class _ManagedInstitutionProfilesPageState
    extends State<ManagedInstitutionProfilesPage> {
  late final InstitutionProfileController _controller;

  @override
  void initState() {
    super.initState();
    _controller = InstitutionProfileController(widget.repository);
    _load();
  }

  Future<void> _load() async {
    await _controller.load();
    if (!mounted || _controller.summaries.length != 1) return;
    await _controller.select(_controller.summaries.single.id);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: ListenableBuilder(
          listenable: _controller,
          builder: (context, _) {
            final editing = _controller.selectedProfile != null;
            return Text(
              context.localized(
                editing ? '编辑机构档案' : '机构档案',
                editing ? 'Edit institution profile' : 'Institution profile',
              ),
            );
          },
        ),
      ),
      body: ListenableBuilder(
        listenable: _controller,
        builder: (context, _) {
          return switch (_controller.status) {
            InstitutionProfileLoadStatus.idle ||
            InstitutionProfileLoadStatus.loading =>
              const Center(
                child: CircularProgressIndicator(),
              ),
            InstitutionProfileLoadStatus.empty => Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Text(
                    context.localized(
                      '当前账号暂无可管理机构',
                      'No institutions are available to manage',
                    ),
                  ),
                ),
              ),
            InstitutionProfileLoadStatus.failure => _IdentityFailure(
                message: _institutionProfileFailureMessage(
                  context,
                  _controller.failure ?? InstitutionProfileFailure.load,
                ),
                onRetry: _load,
              ),
            InstitutionProfileLoadStatus.ready
                when _controller.selectedProfile != null =>
              ManagedInstitutionProfileEditPage(
                controller: _controller,
                profile: _controller.selectedProfile!,
                imagePicker: widget.imagePicker ?? _pickInstitutionProfileImage,
                embedded: true,
              ),
            InstitutionProfileLoadStatus.ready => RefreshIndicator(
                onRefresh: _load,
                child: ListView.separated(
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.all(16),
                  itemCount: _controller.summaries.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 8),
                  itemBuilder: (context, index) {
                    final institution = _controller.summaries[index];
                    return Card(
                      child: ListTile(
                        leading: const Icon(Icons.apartment_outlined),
                        title: Text(
                          institution.name.isEmpty
                              ? context.localized(
                                  '未命名机构', 'Unnamed institution')
                              : institution.name,
                        ),
                        subtitle: Text(
                          [
                            if (institution.city.isNotEmpty) institution.city,
                            if (institution.address.isNotEmpty)
                              institution.address,
                          ].join(' · '),
                        ),
                        trailing: const Icon(Icons.edit_outlined),
                        onTap: () => _edit(institution),
                      ),
                    );
                  },
                ),
              ),
          };
        },
      ),
    );
  }

  Future<void> _edit(ManagedInstitutionSummary institution) async {
    await _controller.select(institution.id);
  }
}

class ManagedInstitutionProfileEditPage extends StatefulWidget {
  const ManagedInstitutionProfileEditPage({
    required this.controller,
    required this.profile,
    required this.imagePicker,
    this.embedded = false,
    super.key,
  });

  final InstitutionProfileController controller;
  final ManagedInstitutionProfile profile;
  final InstitutionProfileImagePicker imagePicker;
  final bool embedded;

  @override
  State<ManagedInstitutionProfileEditPage> createState() =>
      _ManagedInstitutionProfileEditPageState();
}

class _ManagedInstitutionProfileEditPageState
    extends State<ManagedInstitutionProfileEditPage> {
  final _formKey = GlobalKey<FormState>();
  late final Map<String, TextEditingController> _fields;

  @override
  void initState() {
    super.initState();
    final update = widget.profile.toUpdate();
    _fields = {
      'name': TextEditingController(text: update.name),
      'coverImage': TextEditingController(text: update.coverImage),
      'address': TextEditingController(text: update.address),
      'city': TextEditingController(text: update.city),
      'contactPhone': TextEditingController(text: update.contactPhone),
      'businessHours': TextEditingController(text: update.businessHours),
      'establishedYear': TextEditingController(
        text: update.establishedYear?.toString() ?? '',
      ),
      'description': TextEditingController(text: update.description),
      'tags': TextEditingController(text: update.tags.join(',')),
      'specialties': TextEditingController(text: update.specialties.join(',')),
      'credentials': TextEditingController(text: update.credentials),
      'credentialImages': TextEditingController(
        text: update.credentialImages.join(','),
      ),
      'images': TextEditingController(text: update.images.join(',')),
    };
  }

  @override
  void dispose() {
    for (final controller in _fields.values) {
      controller.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final content = ListenableBuilder(
      listenable: widget.controller,
      builder: (context, _) => Form(
        key: _formKey,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (widget.embedded && widget.controller.summaries.length > 1) ...[
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  key: const Key('institution-back-to-list'),
                  onPressed: widget.controller.clearSelection,
                  icon: const Icon(Icons.arrow_back_rounded),
                  label: Text(
                    context.localized('返回机构列表', 'Back to institutions'),
                  ),
                ),
              ),
              const SizedBox(height: 8),
            ],
            _InfoCard(
              text: context.localized(
                '这是向用户公开展示的信息，请勿填写内部管理或隐私资料。',
                'This information is public. Do not include internal or private data.',
              ),
            ),
            const SizedBox(height: 16),
            _platformFacts(),
            const SizedBox(height: 16),
            _textField(
              'name',
              context.localized('机构名称', 'Institution name'),
              required: true,
            ),
            _imagePickerField(
              fieldName: 'coverImage',
              title: context.localized('封面图', 'Cover image'),
              addLabel: context.localized(
                '从相册选择封面图',
                'Choose cover image from gallery',
              ),
              values: _singleImage('coverImage'),
              onAdd: () => _pickSingleImage('coverImage'),
              onDelete: (_) => _setField('coverImage', ''),
            ),
            _textField(
              'city',
              context.localized('城市', 'City'),
              helperText: context.localized(
                '填写城市名即可，无需输入“市”',
                'Enter the city name only',
              ),
            ),
            _textField(
              'address',
              context.localized('地址', 'Address'),
              maxLines: 2,
            ),
            _textField(
              'contactPhone',
              context.localized('联系电话', 'Contact phone'),
            ),
            _textField(
              'businessHours',
              context.localized('营业时间', 'Business hours'),
            ),
            _textField(
              'establishedYear',
              context.localized('成立年份', 'Established year'),
              keyboardType: TextInputType.number,
              validator: _validateEstablishedYear,
            ),
            _textField(
              'description',
              context.localized('机构介绍', 'Institution description'),
              maxLines: 4,
            ),
            _textField(
              'tags',
              context.localized('标签（逗号分隔）', 'Tags (comma-separated)'),
            ),
            _textField(
              'specialties',
              context.localized('擅长领域（逗号分隔）', 'Specialties (comma-separated)'),
            ),
            _textField(
              'credentials',
              context.localized('资质文本', 'Credentials'),
              maxLines: 3,
            ),
            _imagePickerField(
              fieldName: 'credentialImages',
              title: context.localized('资质证书图片', 'Credential images'),
              addLabel: context.localized(
                '从相册添加资质图片',
                'Add credential image from gallery',
              ),
              values: _csv('credentialImages'),
              onAdd: () => _pickListImage('credentialImages'),
              onDelete: (image) => _removeListImage('credentialImages', image),
            ),
            _imagePickerField(
              fieldName: 'images',
              title: context.localized('环境图片', 'Facility images'),
              addLabel: context.localized(
                '从相册添加环境图片',
                'Add facility image from gallery',
              ),
              values: _csv('images'),
              onAdd: () => _pickListImage('images'),
              onDelete: (image) => _removeListImage('images', image),
            ),
            if (widget.controller.failure != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  _institutionProfileFailureMessage(
                    context,
                    widget.controller.failure!,
                  ),
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            const SizedBox(height: 20),
            FilledButton(
              key: const Key('institution-save'),
              onPressed: widget.controller.isSaving ? null : _save,
              child: Text(
                widget.controller.isSaving
                    ? context.localized('保存中…', 'Saving…')
                    : context.localized('保存档案', 'Save profile'),
              ),
            ),
          ],
        ),
      ),
    );
    if (widget.embedded) return content;
    return Scaffold(
      appBar: AppBar(
        title: Text(context.localized('编辑机构档案', 'Edit institution profile')),
      ),
      body: content,
    );
  }

  Widget _textField(
    String name,
    String label, {
    bool required = false,
    int maxLines = 1,
    TextInputType? keyboardType,
    String? helperText,
    String? Function(String?)? validator,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TextFormField(
        key: Key('institution-$name'),
        controller: _fields[name],
        maxLines: maxLines,
        keyboardType: keyboardType,
        decoration: InputDecoration(labelText: label, helperText: helperText),
        validator: validator ??
            (required
                ? (value) => (value ?? '').trim().isEmpty
                    ? context.localized('请填写$label', '$label is required')
                    : null
                : null),
      ),
    );
  }

  Widget _imagePickerField({
    required String fieldName,
    required String title,
    required String addLabel,
    required List<String> values,
    required Future<void> Function() onAdd,
    required void Function(String image) onDelete,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Card(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: 8),
              if (values.isEmpty)
                Text(context.localized('暂无图片', 'No images'))
              else
                for (final entry in values.indexed)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.image_outlined),
                    title: Text(
                      entry.$2,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    trailing: IconButton(
                      key: Key('institution-$fieldName-remove-${entry.$1}'),
                      tooltip: context.localized('移除图片', 'Remove image'),
                      onPressed: () => onDelete(entry.$2),
                      icon: const Icon(Icons.close_rounded),
                    ),
                  ),
              Align(
                alignment: Alignment.centerLeft,
                child: OutlinedButton.icon(
                  key: Key('institution-$fieldName-add'),
                  onPressed: onAdd,
                  icon: const Icon(Icons.photo_library_outlined),
                  label: Text(addLabel),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  List<String> _singleImage(String name) {
    final value = _text(name);
    return value.isEmpty ? const [] : [value];
  }

  Widget _platformFacts() {
    final profile = widget.profile;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Wrap(
          spacing: 12,
          runSpacing: 8,
          children: [
            Text(
              profile.isVerified
                  ? context.localized('已认证', 'Verified')
                  : context.localized('未认证', 'Not verified'),
            ),
            Text('${context.localized('评分', 'Rating')} ${profile.rating}'),
            Text(
              '${context.localized('评价', 'Reviews')} ${profile.reviewCount}',
            ),
            Text(
              '${context.localized('项目', 'Projects')} ${profile.projectCount}',
            ),
            Text(
              '${context.localized('医生', 'Doctors')} ${profile.doctorCount}',
            ),
            Text(
              '${context.localized('咨询', 'Consultations')} ${profile.consultationCount}',
            ),
            Text('${context.localized('用户', 'Users')} ${profile.userCount}'),
            Text('${context.localized('案例', 'Cases')} ${profile.caseCount}'),
          ],
        ),
      ),
    );
  }

  Future<void> _pickSingleImage(String name) async {
    final image = await widget.imagePicker();
    if (image == null || image.trim().isEmpty || !mounted) return;
    setState(() => _setField(name, image));
  }

  Future<void> _pickListImage(String name) async {
    final image = await widget.imagePicker();
    if (image == null || image.trim().isEmpty || !mounted) return;
    final images = [..._csv(name), image.trim()];
    setState(() => _setField(name, images.join(',')));
  }

  void _removeListImage(String name, String image) {
    final images = _csv(name).where((item) => item != image).toList();
    setState(() => _setField(name, images.join(',')));
  }

  void _setField(String name, String value) {
    _fields[name]!.text = value.trim();
  }

  Future<void> _save() async {
    FocusManager.instance.primaryFocus?.unfocus();
    if (!(_formKey.currentState?.validate() ?? false)) return;
    final establishedYear = _optionalInt(_fields['establishedYear']!.text);
    final update = widget.profile.toUpdate().copyWith(
          name: _text('name'),
          coverImage: _text('coverImage'),
          city: _text('city'),
          address: _text('address'),
          contactPhone: _text('contactPhone'),
          businessHours: _text('businessHours'),
          establishedYear: establishedYear,
          clearEstablishedYear: establishedYear == null,
          description: _text('description'),
          tags: _csv('tags'),
          specialties: _csv('specialties'),
          credentials: _text('credentials'),
          credentialImages: _csv('credentialImages'),
          images: _csv('images'),
        );
    if (await widget.controller.save(update) && mounted) {
      showTransientMessage(
        context,
        context.localized('机构档案已保存', 'Institution profile saved'),
      );
      if (!widget.embedded) Navigator.of(context).pop();
    }
  }

  String _text(String name) => _fields[name]!.text.trim();

  int? _optionalInt(String value) => int.tryParse(value.trim());

  String? _validateEstablishedYear(String? value) {
    final text = (value ?? '').trim();
    if (text.isEmpty) return null;
    final year = int.tryParse(text);
    final currentYear = DateTime.now().year;
    if (year == null || year < 1800 || year > currentYear) {
      return context.localized(
        '请输入 1800 至 $currentYear 之间的整数年份',
        'Enter a whole year between 1800 and $currentYear',
      );
    }
    return null;
  }

  List<String> _csv(String name) => _fields[name]!
      .text
      .split(',')
      .map((item) => item.trim())
      .where((item) => item.isNotEmpty)
      .toList(growable: false);
}

String _institutionProfileFailureMessage(
  BuildContext context,
  InstitutionProfileFailure failure,
) =>
    switch (failure) {
      InstitutionProfileFailure.load => context.localized(
          '机构档案加载失败，请重试',
          'Failed to load institution profile. Please try again.',
        ),
      InstitutionProfileFailure.save => context.localized(
          '机构档案保存失败，请稍后重试',
          'Failed to save institution profile. Please try again later.',
        ),
    };

class _DocumentTile extends StatelessWidget {
  const _DocumentTile({
    required this.type,
    required this.file,
    required this.isUploading,
    required this.canPick,
    required this.onUpload,
    required this.onDelete,
  });

  final IdentityDocumentType type;
  final PrivateIdentityFile? file;
  final bool isUploading;
  final bool canPick;
  final VoidCallback onUpload;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        leading: const Icon(Icons.lock_outline),
        title: Text(
          context.localized(type.label, _englishDocumentLabel(type)),
        ),
        subtitle: Text(
          file?.originalName ??
              context.localized(
                '仅支持 JPG、PNG、WebP 或 PDF，最大 10MB',
                'JPG, PNG, WebP, or PDF only; maximum 10 MB',
              ),
        ),
        trailing: isUploading
            ? const SizedBox.square(
                dimension: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : file == null
                ? TextButton(
                    onPressed: canPick ? onUpload : null,
                    child: Text(context.localized('上传', 'Upload')),
                  )
                : IconButton(
                    tooltip: context.localized(
                      '删除未提交材料',
                      'Delete unsubmitted document',
                    ),
                    onPressed: onDelete,
                    icon: const Icon(Icons.delete_outline),
                  ),
      ),
    );
  }
}

class _SectionHeading extends StatelessWidget {
  const _SectionHeading({required this.icon, required this.title});

  final IconData icon;
  final String title;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(icon, size: 20, color: Theme.of(context).colorScheme.primary),
        const SizedBox(width: 8),
        Expanded(
          child: Text(title, style: Theme.of(context).textTheme.titleMedium),
        ),
      ],
    );
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({
    super.key,
    required this.title,
    required this.status,
    required this.detail,
    this.metadata,
  });

  final String title;
  final IdentityStatus status;
  final String detail;
  final String? metadata;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: Theme.of(context).textTheme.titleSmall),
                  const SizedBox(height: 4),
                  Text(
                    detail,
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                  ),
                  if (metadata != null && metadata!.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Text(
                      metadata!,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color:
                                Theme.of(context).colorScheme.onSurfaceVariant,
                          ),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(width: 12),
            _IdentityStatusBadge(status: status),
          ],
        ),
      ),
    );
  }
}

class _IdentityStatusBadge extends StatelessWidget {
  const _IdentityStatusBadge({required this.status});

  final IdentityStatus status;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final (background, foreground) = switch (status) {
      IdentityStatus.pending => (
          colors.tertiaryContainer,
          colors.onTertiaryContainer
        ),
      IdentityStatus.active || IdentityStatus.approved => (
          colors.primaryContainer,
          colors.onPrimaryContainer
        ),
      IdentityStatus.rejected || IdentityStatus.revoked => (
          colors.errorContainer,
          colors.onErrorContainer
        ),
      IdentityStatus.withdrawn || IdentityStatus.unknown => (
          colors.surfaceContainerHighest,
          colors.onSurfaceVariant
        ),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(_identityStatusIcon(status), size: 14, color: foreground),
          const SizedBox(width: 4),
          Text(
            _identityStatusLabel(context, status),
            style: Theme.of(context).textTheme.labelMedium?.copyWith(
                  color: foreground,
                  fontWeight: FontWeight.w600,
                ),
          ),
        ],
      ),
    );
  }
}

class _InfoCard extends StatelessWidget {
  const _InfoCard({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(text),
    );
  }
}

class _IdentityFailure extends StatelessWidget {
  const _IdentityFailure({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.gpp_maybe_outlined, size: 44),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 16),
            FilledButton.tonal(
              onPressed: onRetry,
              child: Text(context.localized('重试', 'Retry')),
            ),
          ],
        ),
      ),
    );
  }
}

final class _IdentityField {
  const _IdentityField(
    this.name,
    this.label,
    this.englishLabel, {
    this.multiline = false,
  });

  final String name;
  final String label;
  final String englishLabel;
  final bool multiline;
}

List<_IdentityField> _fieldsFor(IdentityRoleType role) {
  const common = [
    _IdentityField('realName', '真实姓名', 'Real name'),
    _IdentityField('idNumber', '证件号码', 'ID number'),
  ];
  return switch (role) {
    IdentityRoleType.doctor => const [
        ...common,
        _IdentityField('department', '科室', 'Department'),
        _IdentityField('title', '职称', 'Professional title'),
        _IdentityField(
          'qualificationNo',
          '医师资格证编号',
          'Doctor qualification certificate number',
        ),
        _IdentityField(
          'practiceNo',
          '医师执业证编号',
          'Medical practice certificate number',
        ),
        _IdentityField(
          'reason',
          '申请理由',
          'Application reason',
          multiline: true,
        ),
      ],
    IdentityRoleType.consultant => const [
        ...common,
        _IdentityField('phone', '联系电话', 'Contact phone number'),
        _IdentityField(
          'experience',
          '从业经历',
          'Professional experience',
          multiline: true,
        ),
        _IdentityField(
          'proofDescription',
          '证明材料说明',
          'Supporting document description',
          multiline: true,
        ),
        _IdentityField(
          'reason',
          '申请理由',
          'Application reason',
          multiline: true,
        ),
      ],
    IdentityRoleType.institutionLegalRepresentative => const [
        ...common,
        _IdentityField('phone', '联系电话', 'Contact phone number'),
        _IdentityField('institutionName', '机构名称', 'Institution name'),
        _IdentityField(
          'businessLicenseNo',
          '统一社会信用代码',
          'Unified social credit code',
        ),
        _IdentityField('region', '所在地区', 'Region'),
        _IdentityField('address', '详细地址', 'Address', multiline: true),
      ],
    IdentityRoleType.unknown => const [],
  };
}

List<IdentityDocumentType> _requiredDocuments(IdentityRoleType role) {
  return IdentityApplicationDraft(
    role: role,
    applicationData: const {},
    documents: const [],
  ).requiredDocuments;
}

String _englishRoleLabel(IdentityRoleType role) => switch (role) {
      IdentityRoleType.doctor => 'doctor',
      IdentityRoleType.consultant => 'medical aesthetics consultant',
      IdentityRoleType.institutionLegalRepresentative =>
        'institution legal representative',
      IdentityRoleType.unknown => 'professional',
    };

String _identityRoleLabel(BuildContext context, IdentityRoleType role) =>
    context.localized(
      role.label,
      switch (role) {
        IdentityRoleType.doctor => 'Doctor',
        IdentityRoleType.consultant => 'Medical aesthetics consultant',
        IdentityRoleType.institutionLegalRepresentative =>
          'Institution legal representative',
        IdentityRoleType.unknown => 'Unknown identity',
      },
    );

String _identityStatusLabel(BuildContext context, IdentityStatus status) =>
    context.localized(
      status.label,
      switch (status) {
        IdentityStatus.pending => 'Under review',
        IdentityStatus.active => 'Verified',
        IdentityStatus.approved => 'Approved',
        IdentityStatus.rejected => 'Not approved',
        IdentityStatus.withdrawn => 'Withdrawn',
        IdentityStatus.revoked => 'Revoked',
        IdentityStatus.unknown => 'Unknown status',
      },
    );

IconData _identityStatusIcon(IdentityStatus status) => switch (status) {
      IdentityStatus.pending => Icons.schedule_rounded,
      IdentityStatus.active => Icons.verified_rounded,
      IdentityStatus.approved => Icons.check_circle_outline_rounded,
      IdentityStatus.rejected => Icons.cancel_outlined,
      IdentityStatus.withdrawn => Icons.undo_rounded,
      IdentityStatus.revoked => Icons.block_rounded,
      IdentityStatus.unknown => Icons.help_outline_rounded,
    };

String _identityApplicationDetail(
  BuildContext context,
  IdentityApplication application,
) {
  final reviewNote = application.reviewNote.trim();
  if (reviewNote.isNotEmpty) return reviewNote;
  return switch (application.status) {
    IdentityStatus.pending => context.localized(
        '已提交，审核结果会在此更新',
        'Submitted. The review result will appear here.',
      ),
    IdentityStatus.approved || IdentityStatus.active => context.localized(
        '审核已通过，专业身份已更新',
        'Approved. Your professional identity has been updated.',
      ),
    IdentityStatus.rejected => context.localized(
        '审核未通过，可完善材料后重新申请',
        'Not approved. You can update your documents and apply again.',
      ),
    IdentityStatus.withdrawn => context.localized(
        '该申请已撤回',
        'This application was withdrawn.',
      ),
    IdentityStatus.revoked => context.localized(
        '该申请对应的身份已撤销',
        'The identity associated with this application was revoked.',
      ),
    IdentityStatus.unknown => context.localized(
        '申请状态正在同步，请稍后刷新',
        'The application status is syncing. Refresh shortly.',
      ),
  };
}

String? _identityApplicationTimestamp(
  BuildContext context,
  IdentityApplication application,
) {
  final value = application.reviewedAt ?? application.submittedAt;
  if (value == null) return null;
  final local = value.toLocal();
  String twoDigits(int number) => number.toString().padLeft(2, '0');
  final formatted = '${local.year}-${twoDigits(local.month)}-'
      '${twoDigits(local.day)} ${twoDigits(local.hour)}:'
      '${twoDigits(local.minute)}';
  return application.reviewedAt == null
      ? context.localized('提交于 $formatted', 'Submitted $formatted')
      : context.localized('审核于 $formatted', 'Reviewed $formatted');
}

String _englishDocumentLabel(IdentityDocumentType type) => switch (type) {
      IdentityDocumentType.businessLicense => 'Business license',
      IdentityDocumentType.idCardFront => 'Front of ID card',
      IdentityDocumentType.idCardBack => 'Back of ID card',
      IdentityDocumentType.idCardHandheld => 'Photo holding ID card',
      IdentityDocumentType.doctorQualification =>
        'Doctor qualification certificate',
      IdentityDocumentType.doctorPracticeCertificate =>
        'Medical practice certificate',
      IdentityDocumentType.consultantProof =>
        'Medical aesthetics consultant proof',
    };

IconData _roleIcon(IdentityRoleType role) => switch (role) {
      IdentityRoleType.doctor => Icons.medical_services_outlined,
      IdentityRoleType.consultant => Icons.support_agent_outlined,
      IdentityRoleType.institutionLegalRepresentative =>
        Icons.apartment_outlined,
      IdentityRoleType.unknown => Icons.help_outline,
    };
