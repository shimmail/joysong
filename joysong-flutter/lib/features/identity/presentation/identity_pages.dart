import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/files/app_file_picker.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_controller.dart';
import 'package:joysong_flutter/features/identity/presentation/professional_request_pages.dart';

typedef IdentityFilePicker = Future<IdentityFileDraft?> Function(
  IdentityDocumentType type,
);
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

  @override
  void initState() {
    super.initState();
    _controller = IdentityController(widget.repository)..load();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('身份认证')),
      body: ListenableBuilder(
        listenable: _controller,
        builder: (context, _) {
          return switch (_controller.status) {
            IdentityLoadStatus.idle ||
            IdentityLoadStatus.loading =>
              const Center(child: CircularProgressIndicator()),
            IdentityLoadStatus.failure => _IdentityFailure(
                message: _controller.errorMessage ?? '身份信息加载失败',
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
            label: Text(
              overview.hasPendingApplication ? '已有申请正在审核' : '申请专业身份',
            ),
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
      appBar: AppBar(title: Text('申请${widget.role.label}身份')),
      body: ListenableBuilder(
        listenable: widget.controller,
        builder: (context, _) => Form(
          key: _formKey,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              const _InfoCard(
                text: '请仅提交真实、必要的认证信息。证件号码与材料不会显示在公开主页。',
              ),
              const SizedBox(height: 16),
              for (final field in _fieldsFor(widget.role)) ...[
                TextFormField(
                  key: Key('identity-field-${field.name}'),
                  controller: _fields[field.name],
                  obscureText: field.name == 'idNumber',
                  maxLines: field.multiline ? 3 : 1,
                  decoration: InputDecoration(labelText: field.label),
                  validator: (value) =>
                      (value ?? '').trim().isEmpty ? '请填写${field.label}' : null,
                ),
                const SizedBox(height: 12),
              ],
              const SizedBox(height: 8),
              Text('私有认证材料', style: Theme.of(context).textTheme.titleMedium),
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
                    '当前构建未接入系统文件选择器，材料上传入口暂不可用。',
                    style:
                        TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ),
              if (widget.controller.errorMessage != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(
                    widget.controller.errorMessage!,
                    textAlign: TextAlign.center,
                    style:
                        TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ),
              const SizedBox(height: 20),
              FilledButton(
                key: const Key('submit-identity-application'),
                onPressed: widget.controller.isSubmitting ? null : _submit,
                child: Text(
                  widget.controller.isSubmitting ? '提交中…' : '提交审核',
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

Future<IdentityFileDraft?> _pickIdentityFile(
  IdentityDocumentType type,
) async {
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
    super.key,
  });

  final IdentityRepository repository;
  final DiscoverRepository discoverRepository;
  final InstitutionProfileImagePicker? institutionImagePicker;
  final Future<String?> Function()? doctorImagePicker;

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
                    context.localized(
                      '没有专业管理权限',
                      'No professional access',
                    ),
                onRetry: _controller.enter,
              ),
            ManagementLoadStatus.ready => _ManagementCapabilities(
                context: _controller.context!,
                repository: widget.repository,
                discoverRepository: widget.discoverRepository,
                institutionImagePicker: widget.institutionImagePicker,
                doctorImagePicker: widget.doctorImagePicker,
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
  });

  final ManagementContext context;
  final IdentityRepository repository;
  final DiscoverRepository discoverRepository;
  final InstitutionProfileImagePicker? institutionImagePicker;
  final Future<String?> Function()? doctorImagePicker;

  @override
  Widget build(BuildContext buildContext) {
    final isAdmin = context.platformRole == 'ADMIN';
    final isLegalRepresentative = isAdmin ||
        context.activeRoles.contains(
          IdentityRoleType.institutionLegalRepresentative.code,
        );
    final isDoctor =
        isAdmin || context.activeRoles.contains(IdentityRoleType.doctor.code);
    final isConsultant = isAdmin ||
        context.activeRoles.contains(IdentityRoleType.consultant.code);
    final groups = <({
      IconData icon,
      String title,
      String emptyText,
      String? membershipRequestType,
      List<({IconData icon, String label, bool enabled})> items,
    })>[
      (
        icon: Icons.apartment_outlined,
        title: buildContext.localized('法人', 'Legal representative'),
        membershipRequestType: null,
        emptyText: buildContext.localized(
          '暂无机构法人可用配置',
          'No legal representative settings available',
        ),
        items: [
          (
            icon: Icons.apartment_outlined,
            label: buildContext.localized('机构档案', 'Institution profile'),
            enabled: isLegalRepresentative && context.canManageInstitutions,
          ),
          (
            icon: Icons.how_to_reg_outlined,
            label: buildContext.localized('成员加入审核', 'Membership reviews'),
            enabled:
                isLegalRepresentative && context.canReviewInstitutionRequests,
          ),
          (
            icon: Icons.fact_check_outlined,
            label: buildContext.localized(
              '机构项目申请审核',
              'Institution project reviews',
            ),
            enabled: isLegalRepresentative &&
                context.canReviewInstitutionProjectRequests,
          ),
          (
            icon: Icons.group_add_outlined,
            label: buildContext.localized(
              '机构项目加入审核',
              'Institution project join reviews',
            ),
            enabled: isLegalRepresentative &&
                context.canReviewInstitutionProjectRequests,
          ),
        ],
      ),
      (
        icon: Icons.medical_services_outlined,
        title: buildContext.localized('医生', 'Doctor'),
        membershipRequestType: 'DOCTOR',
        emptyText: buildContext.localized(
          '暂无医生可用配置',
          'No doctor settings available',
        ),
        items: [
          (
            icon: Icons.medical_services_outlined,
            label: buildContext.localized('医生档案', 'Doctor profile'),
            enabled:
                context.activeRoles.contains(IdentityRoleType.doctor.code) &&
                    context.canManageDoctors,
          ),
          (
            icon: Icons.add_business_outlined,
            label: buildContext.localized('申请加入机构', 'Apply to institution'),
            enabled: isDoctor && context.canApplyToInstitutions,
          ),
          (
            icon: Icons.post_add_outlined,
            label: buildContext.localized(
              '申请新增平台项目',
              'Request platform project',
            ),
            enabled: isDoctor && context.canSubmitPlatformProjectRequests,
          ),
          (
            icon: Icons.add_task_outlined,
            label: buildContext.localized(
              '申请新增机构项目',
              'Request institution project',
            ),
            enabled: isDoctor && context.canSubmitInstitutionProjectRequests,
          ),
          (
            icon: Icons.group_add_outlined,
            label: buildContext.localized(
              '申请加入机构项目',
              'Join institution project',
            ),
            enabled: isDoctor && context.canSubmitInstitutionProjectRequests,
          ),
          (
            icon: Icons.article_outlined,
            label: buildContext.localized('专业文章', 'Professional articles'),
            enabled: isDoctor && context.canManageArticles,
          ),
          (
            icon: Icons.receipt_long_outlined,
            label: buildContext.localized('专业订单', 'Professional orders'),
            enabled: isDoctor && context.canManageOrders,
          ),
        ],
      ),
      (
        icon: Icons.support_agent_outlined,
        title: buildContext.localized('顾问', 'Consultant'),
        membershipRequestType: 'CONSULTANT',
        emptyText: buildContext.localized(
          '暂无顾问可用配置',
          'No consultant settings available',
        ),
        items: [
          (
            icon: Icons.add_business_outlined,
            label: buildContext.localized('申请加入机构', 'Apply to institution'),
            enabled: isConsultant && context.canApplyToInstitutions,
          ),
          (
            icon: Icons.badge_outlined,
            label: buildContext.localized('机构归属', 'Institution affiliation'),
            enabled: isConsultant && context.canViewAffiliations,
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
            leading: Icon(group.icon),
            title: Text(group.title),
            initiallyExpanded:
                group.items.any((capability) => capability.enabled),
            children: [
              if (group.items.every((capability) => !capability.enabled))
                ListTile(
                  enabled: false,
                  title: Text(group.emptyText),
                )
              else
                for (final capability
                    in group.items.where((item) => item.enabled))
                  ListTile(
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
                      capability.label,
                      membershipRequestType: group.membershipRequestType,
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
    String label, {
    String? membershipRequestType,
  }) {
    if ((label == '申请加入机构' || label == 'Apply to institution') &&
        membershipRequestType != null) {
      Navigator.of(context).push<void>(MaterialPageRoute(
        builder: (_) => InstitutionMembershipRequestsPage(
          repository: repository,
          discoverRepository: discoverRepository,
          context: this.context,
          requestType: membershipRequestType,
        ),
      ));
      return;
    }
    if (label == '医生档案' || label == 'Doctor profile') {
      Navigator.of(context).push<void>(MaterialPageRoute(
        builder: (_) => DoctorSelfProfilePage(
          repository: repository,
          pickAndUploadImage: doctorImagePicker,
        ),
      ));
      return;
    }
    if (label == '机构档案' || label == 'Institution profile') {
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
    if (label == '成员加入审核' || label == 'Membership reviews') {
      Navigator.of(context).push<void>(
        MaterialPageRoute(
          builder: (_) => InstitutionMembershipRequestsPage(
            repository: repository,
            discoverRepository: discoverRepository,
            context: this.context,
            requestType: 'DOCTOR',
            reviewMode: true,
          ),
        ),
      );
      return;
    }
    if (label == '机构归属' || label == 'Institution affiliation') {
      Navigator.of(context).push<void>(MaterialPageRoute(
        builder: (_) => InstitutionMembershipRequestsPage(
          repository: repository,
          discoverRepository: discoverRepository,
          context: this.context,
          requestType: 'CONSULTANT',
          affiliationOnly: true,
        ),
      ));
      return;
    }
    if (label == '申请新增平台项目' || label == 'Request platform project') {
      Navigator.of(context).push<void>(MaterialPageRoute(
        builder: (_) => PlatformProjectRequestPage(repository: repository),
      ));
      return;
    }
    if (label == '申请新增机构项目' || label == 'Request institution project') {
      Navigator.of(context).push<void>(MaterialPageRoute(
        builder: (_) => InstitutionProjectRequestsPage(
          repository: repository,
          context: this.context,
        ),
      ));
      return;
    }
    if (label == '机构项目申请审核' || label == 'Institution project reviews') {
      Navigator.of(context).push<void>(MaterialPageRoute(
        builder: (_) => InstitutionProjectRequestsPage(
          repository: repository,
          context: this.context,
          reviewMode: true,
        ),
      ));
      return;
    }
    if (label == '申请加入机构项目' || label == 'Join institution project') {
      Navigator.of(context).push<void>(MaterialPageRoute(
        builder: (_) => InstitutionProjectJoinRequestsPage(
          repository: repository,
          context: this.context,
        ),
      ));
      return;
    }
    if (label == '机构项目加入审核' || label == 'Institution project join reviews') {
      Navigator.of(context).push<void>(MaterialPageRoute(
        builder: (_) => InstitutionProjectJoinRequestsPage(
          repository: repository,
          context: this.context,
          reviewMode: true,
        ),
      ));
      return;
    }
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('$label页面正在接入受限接口')),
    );
  }
}

class ManagedInstitutionProjectsPage extends StatefulWidget {
  const ManagedInstitutionProjectsPage({
    required this.repository,
    required this.context,
    super.key,
  });

  final IdentityRepository repository;
  final ManagementContext context;

  @override
  State<ManagedInstitutionProjectsPage> createState() =>
      _ManagedInstitutionProjectsPageState();
}

class _ManagedInstitutionProjectsPageState
    extends State<ManagedInstitutionProjectsPage> {
  late final InstitutionProjectManagementController _controller;

  @override
  void initState() {
    super.initState();
    _controller = InstitutionProjectManagementController(
      widget.repository,
      context: widget.context,
    )..load();
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
          title: Text(context.localized('机构项目', 'Institution Projects'))),
      floatingActionButton: ListenableBuilder(
        listenable: _controller,
        builder: (context, _) => _controller.canPublish
            ? FloatingActionButton.extended(
                onPressed:
                    _controller.status == InstitutionProjectLoadStatus.ready
                        ? () => _edit(null)
                        : null,
                icon: const Icon(Icons.add_rounded),
                label: Text(context.localized('发布', 'Publish')),
              )
            : const SizedBox.shrink(),
      ),
      body: ListenableBuilder(
        listenable: _controller,
        builder: (context, _) {
          return switch (_controller.status) {
            InstitutionProjectLoadStatus.idle ||
            InstitutionProjectLoadStatus.loading =>
              const Center(child: CircularProgressIndicator()),
            InstitutionProjectLoadStatus.failure => _IdentityFailure(
                message: _controller.errorMessage ??
                    context.localized(
                      '机构项目加载失败',
                      'Unable to load institution projects',
                    ),
                onRetry: _controller.load,
              ),
            InstitutionProjectLoadStatus.ready => RefreshIndicator(
                onRefresh: _controller.load,
                child: ListView.separated(
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.all(16),
                  itemCount: _controller.institutionProjects.length +
                      (_controller.institutionProjects.isEmpty ? 1 : 0),
                  separatorBuilder: (_, __) => const SizedBox(height: 8),
                  itemBuilder: (context, index) {
                    if (_controller.institutionProjects.isEmpty) {
                      return _InfoCard(
                        text: context.localized(
                          '暂无机构项目',
                          'No institution projects yet',
                        ),
                      );
                    }
                    final item = _controller.institutionProjects[index];
                    final institution = _controller.institutions
                        .where((profile) => profile.id == item.institutionId)
                        .firstOrNull;
                    return Card(
                      child: ListTile(
                        leading: const Icon(Icons.spa_outlined),
                        title: Text(item.effectiveName),
                        subtitle: Text(
                          [
                            if (institution != null) institution.name,
                            '${context.localized('价格', 'Price')} ${item.price}',
                            item.isActive
                                ? context.localized('已上架', 'Active')
                                : context.localized('已下架', 'Inactive'),
                          ].join(' · '),
                        ),
                        trailing: _controller.canPublish
                            ? const Icon(Icons.edit_outlined)
                            : null,
                        onTap:
                            _controller.canPublish ? () => _edit(item) : null,
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

  Future<void> _edit(ManagedInstitutionProject? project) async {
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => ManagedInstitutionProjectEditPage(
          controller: _controller,
          project: project,
        ),
      ),
    );
  }
}

class ManagedInstitutionProjectEditPage extends StatefulWidget {
  const ManagedInstitutionProjectEditPage({
    required this.controller,
    this.project,
    super.key,
  });

  final InstitutionProjectManagementController controller;
  final ManagedInstitutionProject? project;

  @override
  State<ManagedInstitutionProjectEditPage> createState() =>
      _ManagedInstitutionProjectEditPageState();
}

class _ManagedInstitutionProjectEditPageState
    extends State<ManagedInstitutionProjectEditPage> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _newProjectName;
  late final TextEditingController _newProjectCategory;
  late final TextEditingController _name;
  late final TextEditingController _category;
  late final TextEditingController _description;
  late final TextEditingController _tags;
  late final TextEditingController _slogan;
  late final TextEditingController _detailContent;
  late final TextEditingController _price;
  late final TextEditingController _originalPrice;
  late final TextEditingController _coverImage;
  late final TextEditingController _images;
  late final TextEditingController _consultationFee;
  late final TextEditingController _commissionRate;
  late final TextEditingController _institutionRate;
  String? _institutionId;
  String? _projectId;
  var _isActive = true;

  bool get _editing => widget.project != null;

  @override
  void initState() {
    super.initState();
    final project = widget.project;
    _institutionId = project?.institutionId ??
        widget.controller.context.doctorInstitutionIds.firstOrNull;
    _projectId = project?.projectId;
    _isActive = project?.isActive ?? true;
    _newProjectName = TextEditingController();
    _newProjectCategory = TextEditingController();
    _name = TextEditingController(text: project?.name ?? '');
    _category = TextEditingController(text: project?.category ?? '');
    _description = TextEditingController(text: project?.description ?? '');
    _tags = TextEditingController(text: project?.tags ?? '');
    _slogan = TextEditingController(text: project?.slogan ?? '');
    _detailContent = TextEditingController(text: project?.detailContent ?? '');
    _price = TextEditingController(text: (project?.price ?? 0).toString());
    _originalPrice =
        TextEditingController(text: project?.originalPrice?.toString() ?? '');
    _coverImage = TextEditingController(text: project?.coverImage ?? '');
    _images = TextEditingController(text: project?.images ?? '');
    _consultationFee = TextEditingController(text: '0');
    _commissionRate = TextEditingController(text: '0');
    _institutionRate = TextEditingController(text: '40');
  }

  @override
  void dispose() {
    for (final controller in [
      _newProjectName,
      _newProjectCategory,
      _name,
      _category,
      _description,
      _tags,
      _slogan,
      _detailContent,
      _price,
      _originalPrice,
      _coverImage,
      _images,
      _consultationFee,
      _commissionRate,
      _institutionRate,
    ]) {
      controller.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final doctorInstitutionIds = widget.controller.context.doctorInstitutionIds;
    final institutionOptions = widget.controller.institutions
        .where((institution) => doctorInstitutionIds.contains(institution.id))
        .toList(growable: false);
    return Scaffold(
      appBar: AppBar(
        title: Text(context.localized(
          _editing ? '编辑机构项目' : '发布机构项目',
          _editing ? 'Edit Institution Project' : 'Publish Institution Project',
        )),
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
                  '项目和机构项目仅支持认证医生发布；机构下拉框只显示医生本人已绑定机构。分账配置会作为提案提交，需双方确认后生效。',
                  'Only verified doctors can publish projects. The institution picker only shows institutions bound to this doctor. Split settings are submitted as proposals and take effect after both sides confirm.',
                ),
              ),
              const SizedBox(height: 16),
              DropdownButtonFormField<String>(
                initialValue: _institutionId,
                decoration: InputDecoration(
                  labelText: context.localized('所属机构', 'Institution'),
                ),
                items: institutionOptions
                    .map((item) => DropdownMenuItem(
                          value: item.id,
                          child: Text(item.name),
                        ))
                    .toList(),
                onChanged: _editing
                    ? null
                    : (value) => setState(() => _institutionId = value),
                validator: (value) => value == null || value.isEmpty
                    ? context.localized('请选择所属机构', 'Select an institution')
                    : null,
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                initialValue: _projectId,
                decoration: InputDecoration(
                  labelText: context.localized('关联公共项目', 'Base project'),
                ),
                items: widget.controller.projects
                    .map((item) => DropdownMenuItem(
                          value: item.id,
                          child: Text(item.name),
                        ))
                    .toList(),
                onChanged: _editing
                    ? null
                    : (value) => setState(() => _projectId = value),
              ),
              if (!_editing) ...[
                const SizedBox(height: 12),
                _field(
                  _newProjectName,
                  context.localized('新项目名称', 'New project name'),
                ),
                _field(
                  _newProjectCategory,
                  context.localized('新项目分类', 'New project category'),
                ),
              ],
              const SizedBox(height: 8),
              Text(
                context.localized('机构项目独立详情', 'Institution project details'),
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 12),
              _field(_name, context.localized('独立名称', 'Custom name')),
              _field(_category, context.localized('独立分类', 'Custom category')),
              _field(
                _description,
                context.localized('独立简介', 'Custom description'),
                maxLines: 3,
              ),
              _field(_tags,
                  context.localized('标签（逗号分隔）', 'Tags, comma separated')),
              _field(_slogan, context.localized('宣传语', 'Slogan')),
              _field(
                _detailContent,
                context.localized('详情正文', 'Detail content'),
                maxLines: 4,
              ),
              _field(
                _price,
                context.localized('价格', 'Price'),
                keyboardType: TextInputType.number,
                required: true,
              ),
              _field(
                _originalPrice,
                context.localized('原价', 'Original price'),
                keyboardType: TextInputType.number,
              ),
              _field(
                  _coverImage, context.localized('封面图 URL', 'Cover image URL')),
              _field(
                _images,
                context.localized(
                    '图集 URL（逗号分隔）', 'Gallery URLs, comma separated'),
                maxLines: 2,
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(context.localized('是否上架', 'Active')),
                value: _isActive,
                onChanged: (value) => setState(() => _isActive = value),
              ),
              const SizedBox(height: 8),
              Text(
                context.localized('分账管理', 'Split configuration'),
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 12),
              _field(
                _consultationFee,
                context.localized('面诊金', 'Consultation fee'),
                keyboardType: TextInputType.number,
              ),
              _field(
                _commissionRate,
                context.localized('医美顾问分账比例（%）', 'Consultant split rate (%)'),
                keyboardType: TextInputType.number,
              ),
              _field(
                _institutionRate,
                context.localized('机构分账比例（%）', 'Institution split rate (%)'),
                keyboardType: TextInputType.number,
              ),
              if (widget.controller.errorMessage != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    widget.controller.errorMessage!,
                    style:
                        TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ),
              const SizedBox(height: 20),
              FilledButton(
                onPressed: widget.controller.isSaving ? null : _save,
                child: Text(widget.controller.isSaving
                    ? context.localized('保存中…', 'Saving...')
                    : context.localized('保存并提交分账', 'Save and submit split')),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _field(
    TextEditingController controller,
    String label, {
    int maxLines = 1,
    TextInputType? keyboardType,
    bool required = false,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TextFormField(
        controller: controller,
        maxLines: maxLines,
        keyboardType: keyboardType,
        decoration: InputDecoration(labelText: label),
        validator: required
            ? (value) => (value ?? '').trim().isEmpty
                ? context.localized('请填写$label', 'Please enter $label')
                : null
            : null,
      ),
    );
  }

  Future<void> _save() async {
    FocusManager.instance.primaryFocus?.unfocus();
    if (!(_formKey.currentState?.validate() ?? false)) return;
    var projectId = _projectId?.trim() ?? '';
    if (!_editing && projectId.isEmpty) {
      final name = _newProjectName.text.trim();
      if (name.isEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(context.localized(
              '请选择公共项目，或填写新项目名称',
              'Select a base project or enter a new project name',
            )),
          ),
        );
        return;
      }
      final created = await widget.controller.createBaseProject(
        ManagementProjectDraft(
          name: name,
          category: _newProjectCategory.text.trim(),
          description: _description.text.trim(),
          tags: _tags.text.trim(),
          coverImage: _coverImage.text.trim(),
          referencePrice: _num(_price.text),
        ),
      );
      if (created == null) return;
      projectId = created.id;
    }
    final draft = ManagedInstitutionProjectDraft(
      id: widget.project?.id,
      institutionId: _institutionId?.trim() ?? '',
      projectId: projectId,
      name: _name.text,
      category: _category.text,
      description: _description.text,
      tags: _tags.text,
      slogan: _slogan.text,
      detailContent: _detailContent.text,
      price: _num(_price.text),
      originalPrice: _nullableNum(_originalPrice.text),
      coverImage: _coverImage.text,
      images: _images.text,
      isActive: _isActive,
    );
    final split = SplitConfigProposalDraft(
      doctorId: widget.controller.context.doctorId ?? '',
      institutionProjectId: widget.project?.id ?? 'pending',
      consultationFee: _num(_consultationFee.text),
      commissionRate: _num(_commissionRate.text),
      institutionRate: _num(_institutionRate.text),
    );
    if (await widget.controller.saveInstitutionProject(
          project: draft,
          split: split,
        ) &&
        mounted) {
      Navigator.of(context).pop();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(context.localized(
            '机构项目已保存，分账提案已提交',
            'Institution project saved and split proposal submitted',
          )),
        ),
      );
    }
  }

  num _num(String value) => num.tryParse(value.trim()) ?? 0;

  num? _nullableNum(String value) {
    final text = value.trim();
    return text.isEmpty ? null : num.tryParse(text);
  }
}

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
            return Text(context.localized(
              editing ? '编辑机构档案' : '机构档案',
              editing ? 'Edit institution profile' : 'Institution profile',
            ));
          },
        ),
      ),
      body: ListenableBuilder(
        listenable: _controller,
        builder: (context, _) {
          return switch (_controller.status) {
            InstitutionProfileLoadStatus.idle ||
            InstitutionProfileLoadStatus.loading =>
              const Center(child: CircularProgressIndicator()),
            InstitutionProfileLoadStatus.empty => Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Text(context.localized(
                    '当前账号暂无可管理机构',
                    'No institutions are available to manage',
                  )),
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
                        title: Text(institution.name.isEmpty
                            ? context.localized('未命名机构', 'Unnamed institution')
                            : institution.name),
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
      'establishedYear':
          TextEditingController(text: update.establishedYear?.toString() ?? ''),
      'description': TextEditingController(text: update.description),
      'tags': TextEditingController(text: update.tags.join(',')),
      'specialties': TextEditingController(text: update.specialties.join(',')),
      'credentials': TextEditingController(text: update.credentials),
      'credentialImages':
          TextEditingController(text: update.credentialImages.join(',')),
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
                  label:
                      Text(context.localized('返回机构列表', 'Back to institutions')),
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
            _textField('name', context.localized('机构名称', 'Institution name'),
                required: true),
            _imagePickerField(
              fieldName: 'coverImage',
              title: context.localized('封面图', 'Cover image'),
              addLabel: context.localized(
                  '从相册选择封面图', 'Choose cover image from gallery'),
              values: _singleImage('coverImage'),
              onAdd: () => _pickSingleImage('coverImage'),
              onDelete: (_) => _setField('coverImage', ''),
            ),
            _textField('city', context.localized('城市', 'City'),
                helperText: context.localized(
                    '填写城市名即可，无需输入“市”', 'Enter the city name only')),
            _textField('address', context.localized('地址', 'Address'),
                maxLines: 2),
            _textField(
                'contactPhone', context.localized('联系电话', 'Contact phone')),
            _textField(
                'businessHours', context.localized('营业时间', 'Business hours')),
            _textField('establishedYear',
                context.localized('成立年份', 'Established year'),
                keyboardType: TextInputType.number,
                validator: _validateEstablishedYear),
            _textField('description',
                context.localized('机构介绍', 'Institution description'),
                maxLines: 4),
            _textField('tags',
                context.localized('标签（逗号分隔）', 'Tags (comma-separated)')),
            _textField(
                'specialties',
                context.localized(
                    '擅长领域（逗号分隔）', 'Specialties (comma-separated)')),
            _textField('credentials', context.localized('资质文本', 'Credentials'),
                maxLines: 3),
            _imagePickerField(
              fieldName: 'credentialImages',
              title: context.localized('资质证书图片', 'Credential images'),
              addLabel: context.localized(
                  '从相册添加资质图片', 'Add credential image from gallery'),
              values: _csv('credentialImages'),
              onAdd: () => _pickListImage('credentialImages'),
              onDelete: (image) => _removeListImage('credentialImages', image),
            ),
            _imagePickerField(
              fieldName: 'images',
              title: context.localized('环境图片', 'Facility images'),
              addLabel: context.localized(
                  '从相册添加环境图片', 'Add facility image from gallery'),
              values: _csv('images'),
              onAdd: () => _pickListImage('images'),
              onDelete: (image) => _removeListImage('images', image),
            ),
            if (widget.controller.failure != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  _institutionProfileFailureMessage(
                      context, widget.controller.failure!),
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            const SizedBox(height: 20),
            FilledButton(
              key: const Key('institution-save'),
              onPressed: widget.controller.isSaving ? null : _save,
              child: Text(widget.controller.isSaving
                  ? context.localized('保存中…', 'Saving…')
                  : context.localized('保存档案', 'Save profile')),
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
            Text(profile.isVerified
                ? context.localized('已认证', 'Verified')
                : context.localized('未认证', 'Not verified')),
            Text('${context.localized('评分', 'Rating')} ${profile.rating}'),
            Text(
                '${context.localized('评价', 'Reviews')} ${profile.reviewCount}'),
            Text(
                '${context.localized('项目', 'Projects')} ${profile.projectCount}'),
            Text(
                '${context.localized('医生', 'Doctors')} ${profile.doctorCount}'),
            Text(
                '${context.localized('咨询', 'Consultations')} ${profile.consultationCount}'),
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
          content:
              Text(context.localized('机构档案已保存', 'Institution profile saved')),
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
        title: Text(type.label),
        subtitle: Text(file?.originalName ?? '仅支持 JPG、PNG、WebP 或 PDF，最大 10MB'),
        trailing: isUploading
            ? const SizedBox.square(
                dimension: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : file == null
                ? TextButton(
                    onPressed: canPick ? onUpload : null,
                    child: const Text('上传'),
                  )
                : IconButton(
                    tooltip: '删除未提交材料',
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
  const _IdentityField(this.name, this.label, {this.multiline = false});

  final String name;
  final String label;
  final bool multiline;
}

List<_IdentityField> _fieldsFor(IdentityRoleType role) {
  const common = [
    _IdentityField('realName', '真实姓名'),
    _IdentityField('idNumber', '证件号码'),
  ];
  return switch (role) {
    IdentityRoleType.doctor => const [
        ...common,
        _IdentityField('hospitalName', '执业机构'),
        _IdentityField('department', '科室'),
        _IdentityField('title', '职称'),
        _IdentityField('qualificationNo', '医师资格证编号'),
        _IdentityField('practiceNo', '医师执业证编号'),
        _IdentityField('reason', '申请理由', multiline: true),
      ],
    IdentityRoleType.consultant => const [
        ...common,
        _IdentityField('phone', '联系电话'),
        _IdentityField('experience', '从业经历', multiline: true),
        _IdentityField('proofDescription', '证明材料说明', multiline: true),
        _IdentityField('reason', '申请理由', multiline: true),
      ],
    IdentityRoleType.institutionLegalRepresentative => const [
        ...common,
        _IdentityField('phone', '联系电话'),
        _IdentityField('institutionName', '机构名称'),
        _IdentityField('businessLicenseNo', '统一社会信用代码'),
        _IdentityField('region', '所在地区'),
        _IdentityField('address', '详细地址', multiline: true),
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

IconData _roleIcon(IdentityRoleType role) => switch (role) {
      IdentityRoleType.doctor => Icons.medical_services_outlined,
      IdentityRoleType.consultant => Icons.support_agent_outlined,
      IdentityRoleType.institutionLegalRepresentative =>
        Icons.apartment_outlined,
      IdentityRoleType.unknown => Icons.help_outline,
    };
