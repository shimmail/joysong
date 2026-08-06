import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/files/app_file_picker.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_controller.dart';

typedef IdentityFilePicker = Future<IdentityFileDraft?> Function(
  IdentityDocumentType type,
);

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

class ManagementCenterPage extends StatefulWidget {
  const ManagementCenterPage({required this.repository, super.key});

  final IdentityRepository repository;

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
        title: const Text('专业管理中心'),
        actions: [
          IconButton(
            tooltip: '刷新权限',
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
                message: _controller.errorMessage ?? '没有专业管理权限',
                onRetry: _controller.enter,
              ),
            ManagementLoadStatus.ready => _ManagementCapabilities(
                context: _controller.context!,
              ),
          };
        },
      ),
    );
  }
}

class _ManagementCapabilities extends StatelessWidget {
  const _ManagementCapabilities({required this.context});

  final ManagementContext context;

  @override
  Widget build(BuildContext buildContext) {
    final capabilities = <({IconData icon, String label, bool enabled})>[
      (
        icon: Icons.medical_services_outlined,
        label: '医生档案',
        enabled: context.canManageDoctors,
      ),
      (
        icon: Icons.apartment_outlined,
        label: '机构档案',
        enabled: context.canManageInstitutions,
      ),
      (
        icon: Icons.spa_outlined,
        label: '机构项目',
        enabled: context.canManageInstitutionProjects,
      ),
      (
        icon: Icons.article_outlined,
        label: '专业文章',
        enabled: context.canManageArticles,
      ),
      (
        icon: Icons.pie_chart_outline,
        label: '分账提案',
        enabled: context.canManageSplitConfigs,
      ),
      (
        icon: Icons.receipt_long_outlined,
        label: '专业订单',
        enabled: context.canManageOrders,
      ),
    ];
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const _InfoCard(
          text: '以下能力来自服务端实时权限上下文。客户端不会根据身份名称自行推导权限。',
        ),
        const SizedBox(height: 16),
        for (final capability in capabilities.where((item) => item.enabled))
          Card(
            child: ListTile(
              leading: Icon(capability.icon),
              title: Text(capability.label),
              subtitle: const Text('数据范围由服务端逐对象校验'),
              trailing: const Icon(Icons.chevron_right_rounded),
              onTap: () => ScaffoldMessenger.of(buildContext).showSnackBar(
                SnackBar(content: Text('${capability.label}页面正在接入受限接口')),
              ),
            ),
          ),
      ],
    );
  }
}

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
            FilledButton.tonal(onPressed: onRetry, child: const Text('重试')),
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
