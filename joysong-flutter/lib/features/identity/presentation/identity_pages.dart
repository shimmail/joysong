import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/files/app_file_picker.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
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
    super.key,
  });

  final IdentityRepository repository;
  final IdentityFilePicker? filePicker;

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
              ),
          };
        },
      ),
    );
  }
}

class _IdentityOverviewView extends StatelessWidget {
  const _IdentityOverviewView({
    required this.controller,
    required this.filePicker,
  });

  final IdentityController controller;
  final IdentityFilePicker? filePicker;

  @override
  Widget build(BuildContext context) {
    final overview = controller.overview;
    return RefreshIndicator(
      onRefresh: controller.load,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        children: [
          Text('我的专业身份', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 10),
          if (overview.roles.isEmpty)
            const _InfoCard(text: '尚未取得专业身份，普通用户功能不受影响。')
          else
            for (final role in overview.roles)
              _StatusCard(
                title: role.role.label,
                status: role.status,
                detail: role.status == IdentityStatus.revoked
                    ? '该身份已撤销，专业入口将立即关闭'
                    : '认证状态由平台审核结果决定',
              ),
          const SizedBox(height: 20),
          Text('申请记录', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 10),
          if (overview.applications.isEmpty)
            const _InfoCard(text: '暂无身份申请记录')
          else
            for (final application in overview.applications)
              _StatusCard(
                title: application.role.label,
                status: application.status,
                detail: application.reviewNote.isEmpty
                    ? '已提交，审核结果会在此更新'
                    : application.reviewNote,
              ),
          const SizedBox(height: 20),
          FilledButton.icon(
            key: const Key('start-identity-application'),
            onPressed: overview.hasPendingApplication
                ? null
                : () => _selectRole(context),
            icon: const Icon(Icons.verified_user_outlined),
            label: Text(overview.hasPendingApplication ? '已有申请正在审核' : '申请专业身份'),
          ),
          const SizedBox(height: 12),
          Text(
            '认证材料通过私有接口上传，不会进入公开图片库，也不会生成可公开访问的 URL。',
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
            const ListTile(title: Text('选择申请身份')),
            for (final role in IdentityRoleType.values.where(
              (item) => item != IdentityRoleType.unknown,
            ))
              ListTile(
                leading: Icon(_roleIcon(role)),
                title: Text(role.label),
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
          controller: controller,
          role: role,
          filePicker: filePicker,
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
                  validator: (value) =>
                      (value ?? '').trim().isEmpty
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
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            english
                ? 'Unable to read the selected file. Please try another file.'
                : '无法读取所选文件，请更换文件后重试',
          ),
        ),
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
    this.institutionImagePicker,
    this.doctorImagePicker,
    this.professionalRepository,
  });

  final ManagementContext context;
  final IdentityRepository repository;
  final DiscoverRepository discoverRepository;
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
      String key,
      IconData icon,
      String title,
      String emptyText,
      List<
          ({
            IconData icon,
            String label,
            bool enabled,
            _ManagementAction action,
          })> items,
    })>[
      (
        key: 'management-group-legal-representative',
        icon: Icons.apartment_outlined,
        title: buildContext.localized('法人', 'Legal representative'),
        emptyText: buildContext.localized(
          '暂无机构法人可用配置',
          'No legal representative settings available',
        ),
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
        key: 'management-group-platform-administration',
        icon: Icons.admin_panel_settings_outlined,
        title: buildContext.localized('平台管理', 'Platform administration'),
        emptyText: buildContext.localized(
          '暂无平台管理权限',
          'No platform administration access',
        ),
        items: [
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
        key: 'management-group-doctor',
        icon: Icons.medical_services_outlined,
        title: buildContext.localized('医生', 'Doctor'),
        emptyText: buildContext.localized(
          '暂无医生可用配置',
          'No doctor settings available',
        ),
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
              '申请新增机构项目',
              'Request institution project',
            ),
            enabled: isDoctor && context.canSubmitInstitutionProjectRequests,
            action: _ManagementAction.institutionProjectRequest,
          ),
          (
            icon: Icons.group_add_outlined,
            label: buildContext.localized(
              '申请加入机构项目',
              'Join institution project',
            ),
            enabled: isDoctor && context.canSubmitInstitutionProjectRequests,
            action: _ManagementAction.institutionProjectJoinRequest,
          ),
          (
            icon: Icons.edit_note_outlined,
            label: buildContext.localized(
              '修改本人项目资料',
              'Update my project profile',
            ),
            enabled: isDoctor && context.canSubmitInstitutionProjectRequests,
            action: _ManagementAction.doctorProjectProfileUpdate,
          ),
          (
            icon: Icons.article_outlined,
            label: buildContext.localized('专业文章', 'Professional articles'),
            enabled: isDoctor && context.canManageArticles,
            action: _ManagementAction.doctorArticles,
          ),
          (
            icon: Icons.receipt_long_outlined,
            label: buildContext.localized('专业订单', 'Professional orders'),
            enabled: isDoctor && context.canManageOrders,
            action: _ManagementAction.doctorOrders,
          ),
        ],
      ),
      (
        key: 'management-group-consultant',
        icon: Icons.support_agent_outlined,
        title: buildContext.localized('顾问', 'Consultant'),
        emptyText: buildContext.localized(
          '暂无顾问可用配置',
          'No consultant settings available',
        ),
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
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _InfoCard(
          text: buildContext.localized(
            '以下能力来自服务端实时权限上下文。客户端不会根据身份名称自行推导权限。',
            'Capabilities come from the live server access context. The app never infers access from role names alone.',
          ),
        ),
        const SizedBox(height: 16),
        for (final group in groups) ...[
          ExpansionTile(
            key: Key(group.key),
            leading: Icon(group.icon),
            title: Text(group.title),
            initiallyExpanded: group.items.any(
              (capability) => capability.enabled,
            ),
            children: [
              if (group.items.every((capability) => !capability.enabled))
                ListTile(enabled: false, title: Text(group.emptyText))
              else
                for (final capability in group.items.where(
                  (item) => item.enabled,
                ))
                  ListTile(
                    key: _managementActionKey(capability.action),
                    leading: Icon(capability.icon),
                    title: Text(capability.label),
                    subtitle: Text(
                      buildContext.localized(
                        '数据范围由服务端逐对象校验',
                        'Data scope is checked by the server per record',
                      ),
                    ),
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: () => _openCapability(
                      buildContext,
                      capability.action,
                    ),
                  ),
            ],
          ),
          const Divider(height: 1),
        ],
      ],
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
          builder: (_) => PlatformProjectRequestPage(repository: repository),
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
          ),
        ),
      );
      return;
    }
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          context.localized(
            '该功能当前不可用',
            'This feature is currently unavailable',
          ),
        ),
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
      final messenger = ScaffoldMessenger.of(context);
      if (!widget.embedded) Navigator.of(context).pop();
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            context.localized('机构档案已保存', 'Institution profile saved'),
          ),
        ),
      );
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

class _StatusCard extends StatelessWidget {
  const _StatusCard({
    required this.title,
    required this.status,
    required this.detail,
  });

  final String title;
  final IdentityStatus status;
  final String detail;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        title: Text(title),
        subtitle: Text(detail),
        trailing: Chip(label: Text(status.label)),
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
        _IdentityField('hospitalName', '执业机构', 'Practicing institution'),
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
