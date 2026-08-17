import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/institution_relationships_page.dart';

class DoctorSelfProfilePage extends StatefulWidget {
  const DoctorSelfProfilePage({
    required this.repository,
    required this.pickAndUploadImage,
    super.key,
  });

  final IdentityRepository repository;
  final Future<String?> Function()? pickAndUploadImage;

  @override
  State<DoctorSelfProfilePage> createState() => _DoctorSelfProfilePageState();
}

class _DoctorSelfProfilePageState extends State<DoctorSelfProfilePage> {
  DoctorSelfProfile? _profile;
  String? _error;
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
      final profile = await widget.repository.loadDoctorSelfProfile();
      if (!mounted) return;
      setState(() {
        _profile = profile;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = '医生档案加载失败，请重试';
      });
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar:
            AppBar(title: Text(context.localized('医生档案', 'Doctor Profile'))),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : _error != null
                ? Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(_error!),
                        const SizedBox(height: 12),
                        FilledButton(
                          onPressed: _load,
                          child: Text(context.localized('重试', 'Retry')),
                        ),
                      ],
                    ),
                  )
                : _DoctorProfileForm(
                    repository: widget.repository,
                    profile: _profile!,
                    pickAndUploadImage: widget.pickAndUploadImage,
                    onSaved: (profile) => setState(() => _profile = profile),
                  ),
      );
}

class _DoctorProfileForm extends StatefulWidget {
  const _DoctorProfileForm({
    required this.repository,
    required this.profile,
    required this.pickAndUploadImage,
    required this.onSaved,
  });

  final IdentityRepository repository;
  final DoctorSelfProfile profile;
  final Future<String?> Function()? pickAndUploadImage;
  final ValueChanged<DoctorSelfProfile> onSaved;

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
  late String _avatar;
  late List<String> _credentialImages;
  var _saving = false;
  var _uploadingAvatar = false;
  var _uploadingCredential = false;
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
    _avatar = profile.avatar.trim();
    _credentialImages = _parseCsv(profile.credentialImages);
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
          _requestField(
            _name,
            context.localized('姓名', 'Name'),
            fieldKey: const Key('doctor-name'),
          ),
          _requestField(
            _title,
            context.localized('职称', 'Title'),
            fieldKey: const Key('doctor-title'),
          ),
          _requestField(
            _bio,
            context.localized('个人简介', 'Biography'),
            fieldKey: const Key('doctor-bio'),
            maxLines: 4,
          ),
          _requestField(
            _phone,
            context.localized('公开联系电话', 'Public phone'),
            fieldKey: const Key('doctor-contact-phone'),
          ),
          _requestField(
            _specialties,
            context.localized('擅长项目', 'Specialties'),
            fieldKey: const Key('doctor-specialties'),
            maxLines: 3,
          ),
          _requestField(
            _credentials,
            context.localized('公开资历', 'Public credentials'),
            fieldKey: const Key('doctor-credentials'),
            maxLines: 3,
          ),
          _requestField(
            _tags,
            context.localized('展示标签', 'Display tags'),
            fieldKey: const Key('doctor-certification-tags'),
          ),
          Text(
            context.localized('医生头像', 'Doctor avatar'),
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 8),
          Text(
            _avatar.isEmpty
                ? context.localized('暂未上传', 'Not uploaded')
                : _avatar,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              OutlinedButton.icon(
                key: const Key('doctor-avatar-upload'),
                onPressed: _uploadsDisabled || widget.pickAndUploadImage == null
                    ? null
                    : _pickAvatar,
                icon: const Icon(Icons.upload_outlined),
                label: Text(widget.pickAndUploadImage == null
                    ? context.localized('上传暂不可用', 'Upload unavailable')
                    : context.localized('上传/更换头像', 'Upload/replace avatar')),
              ),
              TextButton.icon(
                key: const Key('doctor-avatar-remove'),
                onPressed: _uploadsDisabled || _avatar.isEmpty
                    ? null
                    : () => setState(() => _avatar = ''),
                icon: const Icon(Icons.delete_outline),
                label: Text(context.localized('移除头像', 'Remove avatar')),
              ),
            ],
          ),
          const SizedBox(height: 20),
          Text(
            context.localized(
              '医生上传的证书图片/展示材料',
              'Doctor-uploaded certificate images/display materials',
            ),
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 6),
          Text(
            context.localized(
              '这些内容将公开展示，不代表平台认证。',
              'These materials are public display content and do not represent platform verification.',
            ),
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: 8),
          for (final (index, image) in _credentialImages.indexed)
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.image_outlined),
              title: Text(
                image,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
              trailing: IconButton(
                key: Key('doctor-credential-remove-$index'),
                tooltip: context.localized('移除材料', 'Remove material'),
                onPressed: _uploadsDisabled
                    ? null
                    : () => setState(() => _credentialImages.removeAt(index)),
                icon: const Icon(Icons.delete_outline),
              ),
            ),
          OutlinedButton.icon(
            key: const Key('doctor-credential-upload'),
            onPressed: _uploadsDisabled || widget.pickAndUploadImage == null
                ? null
                : _pickCredential,
            icon: const Icon(Icons.add_photo_alternate_outlined),
            label: Text(widget.pickAndUploadImage == null
                ? context.localized('上传暂不可用', 'Upload unavailable')
                : context.localized('添加展示材料', 'Add display material')),
          ),
          const SizedBox(height: 16),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Text(
                _error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          FilledButton.icon(
            onPressed: _uploadsDisabled ? null : _save,
            icon: const Icon(Icons.save_outlined),
            label: Text(context.localized('保存', 'Save')),
          ),
        ],
      );

  bool get _uploadsDisabled =>
      _saving || _uploadingAvatar || _uploadingCredential;

  Future<void> _pickAvatar() async {
    setState(() {
      _uploadingAvatar = true;
      _error = null;
    });
    try {
      final value = (await widget.pickAndUploadImage?.call())?.trim();
      if (mounted && value != null && value.isNotEmpty) {
        setState(() => _avatar = value);
      }
    } catch (_) {
      if (mounted) setState(() => _error = '图片上传失败，请重试');
    } finally {
      if (mounted) setState(() => _uploadingAvatar = false);
    }
  }

  Future<void> _pickCredential() async {
    setState(() {
      _uploadingCredential = true;
      _error = null;
    });
    try {
      final value = (await widget.pickAndUploadImage?.call())?.trim();
      if (mounted && value != null && value.isNotEmpty) {
        setState(() => _credentialImages.add(value));
      }
    } catch (_) {
      if (mounted) setState(() => _error = '图片上传失败，请重试');
    } finally {
      if (mounted) setState(() => _uploadingCredential = false);
    }
  }

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
      final saved = await widget.repository.updateDoctorSelfProfile(
        DoctorSelfProfileUpdate(
          name: _name.text,
          title: _title.text,
          bio: _bio.text,
          avatar: _avatar,
          contactPhone: _phone.text,
          specialties: _specialties.text,
          credentials: _credentials.text,
          credentialImages: _credentialImages.join(','),
          certificationTags: _tags.text,
        ),
      );
      if (!mounted) return;
      setState(() {
        _name.text = saved.name;
        _title.text = saved.title;
        _bio.text = saved.bio;
        _phone.text = saved.contactPhone;
        _specialties.text = saved.specialties;
        _credentials.text = saved.credentials;
        _tags.text = saved.certificationTags;
        _avatar = saved.avatar.trim();
        _credentialImages = _parseCsv(saved.credentialImages);
      });
      widget.onSaved(saved);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
              content: Text(context.localized('医生档案已保存', 'Profile saved'))),
        );
      }
    } catch (_) {
      if (mounted) setState(() => _error = '医生档案保存失败，请稍后重试');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }
}

@Deprecated('Use InstitutionRelationshipsPage with an explicit scope.')
class InstitutionMembershipRequestsPage extends StatelessWidget {
  InstitutionMembershipRequestsPage({
    required this.repository,
    required this.discoverRepository,
    required this.context,
    required String requestType,
    this.reviewMode = false,
    super.key,
  }) : requestType = InstitutionMembershipRequestType.fromCode(requestType);

  final IdentityRepository repository;

  // Retained only for source compatibility until Task 7 updates route call sites.
  final DiscoverRepository discoverRepository;
  final ManagementContext context;
  final InstitutionMembershipRequestType requestType;
  final bool reviewMode;

  @override
  Widget build(BuildContext context) => InstitutionRelationshipsPage(
        repository: repository,
        scope: reviewMode
            ? InstitutionRelationshipScope.legalRepresentative
            : requestType == InstitutionMembershipRequestType.doctor
                ? InstitutionRelationshipScope.doctor
                : InstitutionRelationshipScope.consultant,
      );
}

class PlatformProjectRequestPage extends StatefulWidget {
  const PlatformProjectRequestPage({
    required this.repository,
    required this.context,
    this.reviewMode = false,
    this.pickAndUploadImage,
    super.key,
  });

  final IdentityRepository repository;
  final ManagementContext context;
  final bool reviewMode;
  final Future<String?> Function()? pickAndUploadImage;

  @override
  State<PlatformProjectRequestPage> createState() =>
      _PlatformProjectRequestPageState();
}

class _PlatformProjectRequestPageState
    extends State<PlatformProjectRequestPage> {
  final _name = TextEditingController();
  final _referencePrice = TextEditingController();
  final _slogan = TextEditingController();
  final _salesCount = TextEditingController();
  final _category = TextEditingController();
  final _description = TextEditingController();
  final _detailContent = TextEditingController();
  final _tags = TextEditingController();
  final _categoryTags = TextEditingController();
  final _notes = TextEditingController();
  String _currency = 'CNY';
  String _coverImage = '';
  List<String> _images = const [];
  List<ProfessionalProjectRequest> _requests = const [];
  var _saving = false, _uploading = false, _reviewing = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    for (final controller in [
      _name,
      _referencePrice,
      _slogan,
      _salesCount,
      _category,
      _description,
      _detailContent,
      _tags,
      _categoryTags,
      _notes,
    ]) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<bool> _load() async {
    try {
      final requests =
          await widget.repository.listProfessionalProjectRequests();
      if (!mounted) return false;
      setState(() {
        _requests = requests
            .where((item) => item.requestType == 'PLATFORM')
            .where(_isVisible)
            .toList(growable: false);
        _error = null;
      });
      return true;
    } catch (_) {
      if (mounted) setState(() => _error = '项目申请加载失败，请重试');
      return false;
    }
  }

  bool _isVisible(ProfessionalProjectRequest request) {
    if (widget.reviewMode) return widget.context.platformRole == 'ADMIN';
    return widget.context.doctorId != null &&
        request.doctorId == widget.context.doctorId;
  }

  bool get _canReview =>
      widget.reviewMode && widget.context.platformRole == 'ADMIN';

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: Text(context.localized(
            widget.reviewMode ? '平台项目申请审核' : '申请新增平台项目',
            widget.reviewMode
                ? 'Platform Project Reviews'
                : 'Request Platform Project',
          )),
        ),
        body: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (!widget.reviewMode) ...[
              _requestField(
                _name,
                context.localized('项目名称', 'Project name'),
                fieldKey: const Key('platform-name'),
              ),
              Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Expanded(
                  child: _requestField(
                    _referencePrice,
                    context.localized('参考价格', 'Reference price'),
                    fieldKey: const Key('platform-reference-price'),
                    keyboardType:
                        const TextInputType.numberWithOptions(decimal: true),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: KeyedSubtree(
                    key: const Key('platform-currency'),
                    child: DropdownButtonFormField<String>(
                      key: ValueKey('platform-currency-$_currency'),
                      initialValue: _currency,
                      decoration: InputDecoration(
                        labelText: context.localized('币种', 'Currency'),
                      ),
                      items: const [
                        DropdownMenuItem(value: 'CNY', child: Text('CNY')),
                        DropdownMenuItem(value: 'USD', child: Text('USD')),
                      ],
                      onChanged: _saving
                          ? null
                          : (value) =>
                              setState(() => _currency = value ?? 'CNY'),
                    ),
                  ),
                ),
              ]),
              const SizedBox(height: 12),
              _requestField(
                _slogan,
                context.localized('项目标语', 'Slogan'),
                fieldKey: const Key('platform-slogan'),
              ),
              _requestField(
                _salesCount,
                context.localized('销量', 'Sales count'),
                fieldKey: const Key('platform-sales-count'),
                keyboardType: TextInputType.number,
              ),
              _imageUploadField(
                context,
                title: context.localized('封面图', 'Cover image'),
                values: _coverImage.isEmpty ? const [] : [_coverImage],
                addKey: const Key('platform-cover-upload'),
                addLabel: context.localized('上传封面图', 'Upload cover'),
                removeLabel: context.localized('移除封面图', 'Remove cover image'),
                enabled: !_saving &&
                    !_uploading &&
                    widget.pickAndUploadImage != null,
                onAdd: () => _uploadImage(cover: true),
                onRemove: (_) => setState(() => _coverImage = ''),
              ),
              _imageUploadField(
                context,
                title: context.localized('项目图片', 'Gallery images'),
                values: _images,
                addKey: const Key('platform-gallery-upload'),
                addLabel: context.localized('添加项目图片', 'Add gallery image'),
                removeLabel:
                    context.localized('移除项目图片', 'Remove gallery image'),
                enabled: !_saving &&
                    !_uploading &&
                    widget.pickAndUploadImage != null,
                onAdd: () => _uploadImage(cover: false),
                onRemove: (value) => setState(
                  () =>
                      _images = _images.where((item) => item != value).toList(),
                ),
              ),
              _requestField(
                _category,
                context.localized('项目分类', 'Category'),
                fieldKey: const Key('platform-category'),
              ),
              _requestField(
                _description,
                context.localized('项目说明', 'Description'),
                fieldKey: const Key('platform-description'),
                maxLines: 4,
              ),
              _requestField(
                _detailContent,
                context.localized('项目详情（纯文本）', 'Detail (plain text)'),
                fieldKey: const Key('platform-detail-content'),
                maxLines: 6,
              ),
              _requestField(
                _tags,
                context.localized('项目标签（逗号分隔）', 'Tags (comma separated)'),
                fieldKey: const Key('platform-tags'),
              ),
              _requestField(
                _categoryTags,
                context.localized(
                  '分类标签（逗号分隔）',
                  'Category tags (comma separated)',
                ),
                fieldKey: const Key('platform-category-tags'),
              ),
              _requestField(
                _notes,
                context.localized('补充说明', 'Notes'),
                fieldKey: const Key('platform-notes'),
                maxLines: 3,
              ),
              FilledButton.icon(
                key: const Key('platform-submit'),
                onPressed: _saving || _uploading ? null : _submit,
                icon: const Icon(Icons.send_outlined),
                label: Text(context.localized('提交申请', 'Submit')),
              ),
            ],
            if (_error != null) ...[
              const SizedBox(height: 12),
              Text(_error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ],
            const SizedBox(height: 24),
            for (final request in _requests)
              _professionalRequestSnapshot(
                context,
                request,
                canReview: _canReview && request.isCreationReviewable,
                reviewEnabled: !_reviewing,
                onReview: () => _review(request),
              ),
          ],
        ),
      );

  Future<void> _submit() async {
    final referencePrice = num.tryParse(_referencePrice.text.trim());
    final salesCount = int.tryParse(_salesCount.text.trim());
    if (referencePrice == null || salesCount == null) {
      setState(() => _error = '请填写有效的参考价格和非负整数销量');
      return;
    }
    final draft = PlatformProjectRequestDraft(
      name: _name.text,
      category: _category.text,
      description: _description.text,
      referencePrice: referencePrice,
      currency: _currency,
      slogan: _slogan.text,
      salesCount: salesCount,
      coverImage: _coverImage,
      images: _images,
      detailContent: _detailContent.text,
      tags: _csv(_tags.text),
      categoryTags: _csv(_categoryTags.text),
      notes: _notes.text,
    );
    try {
      draft.validate();
    } on ArgumentError catch (error) {
      setState(() => _error = error.message.toString());
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await widget.repository.submitPlatformProjectRequest(draft);
      final refreshed = await _load();
      if (refreshed && mounted) _clearDraft();
    } catch (_) {
      if (mounted) setState(() => _error = '项目申请提交失败，请稍后重试');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _uploadImage({required bool cover}) async {
    setState(() {
      _uploading = true;
      _error = null;
    });
    try {
      final value = (await widget.pickAndUploadImage?.call())?.trim();
      if (!mounted || value == null || value.isEmpty) return;
      setState(() {
        if (cover) {
          _coverImage = value;
        } else {
          _images = [..._images, value];
        }
      });
    } catch (_) {
      if (mounted) setState(() => _error = '图片上传失败，请重试');
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  void _clearDraft() {
    if (!mounted) return;
    for (final controller in [
      _name,
      _referencePrice,
      _slogan,
      _salesCount,
      _category,
      _description,
      _detailContent,
      _tags,
      _categoryTags,
      _notes,
    ]) {
      controller.clear();
    }
    setState(() {
      _currency = 'CNY';
      _coverImage = '';
      _images = const [];
    });
  }

  Future<void> _review(ProfessionalProjectRequest request) async {
    if (_reviewing || !request.hasCompleteReviewSnapshot) return;
    setState(() {
      _reviewing = true;
      _error = null;
    });
    try {
      final review = await showProfessionalProjectCreationReviewDialog(context);
      if (!mounted || review == null) return;
      await widget.repository.reviewPlatformProjectRequest(
        id: request.id,
        decision: review.decision,
        reviewNote: review.note,
      );
      await _load();
    } on ApiException catch (error) {
      if (!mounted) return;
      if (error.httpStatus == 409) {
        final refreshed = await _load();
        if (!mounted) return;
        setState(() => _error = refreshed
            ? context.localized(
                '审核状态已变化，申请列表已刷新，请基于最新内容重试',
                'The review state changed. The request list was refreshed; retry from the latest content.',
              )
            : context.localized(
                '审核状态已变化，但列表刷新失败，请手动刷新后重试',
                'The review state changed, but refresh failed. Refresh manually and retry.',
              ));
      } else {
        setState(() => _error = '审核提交失败，请稍后重试');
      }
    } catch (_) {
      if (mounted) setState(() => _error = '审核提交失败，请稍后重试');
    } finally {
      if (mounted) setState(() => _reviewing = false);
    }
  }
}

class InstitutionProjectRequestsPage extends StatefulWidget {
  const InstitutionProjectRequestsPage({
    required this.repository,
    required this.context,
    this.reviewMode = false,
    this.pickAndUploadImage,
    super.key,
  });

  final IdentityRepository repository;
  final ManagementContext context;
  final bool reviewMode;
  final Future<String?> Function()? pickAndUploadImage;

  @override
  State<InstitutionProjectRequestsPage> createState() =>
      _InstitutionProjectRequestsPageState();
}

class _InstitutionProjectRequestsPageState
    extends State<InstitutionProjectRequestsPage> {
  final _name = TextEditingController();
  final _category = TextEditingController();
  final _description = TextEditingController();
  final _tags = TextEditingController();
  final _slogan = TextEditingController();
  final _detailContent = TextEditingController();
  final _price = TextEditingController();
  final _originalPrice = TextEditingController();
  final _cover = TextEditingController();
  final _salesCount = TextEditingController();
  final _consultationFee = TextEditingController();
  final _consultantRate = TextEditingController();
  final _institutionRate = TextEditingController();
  final _notes = TextEditingController();
  List<InstitutionOption> _institutions = const [];
  List<ManagementProjectOption> _projects = const [];
  List<ProfessionalProjectRequest> _requests = const [];
  List<String> _images = const [];
  String? _institutionId;
  String? _projectId;
  String _currency = 'CNY';
  bool _isActive = true;
  num? _platformRate;
  String? _error;
  var _loading = true;
  var _saving = false, _uploading = false, _reviewing = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    for (final controller in [
      _name,
      _category,
      _description,
      _tags,
      _slogan,
      _detailContent,
      _price,
      _originalPrice,
      _cover,
      _salesCount,
      _consultationFee,
      _consultantRate,
      _institutionRate,
      _notes,
    ]) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<bool> _load() async {
    try {
      final includeFormData = !widget.reviewMode;
      final values = !includeFormData
          ? <Object>[
              await widget.repository.listProfessionalProjectRequests(),
            ]
          : await Future.wait<Object>([
              widget.repository.listProfessionalProjectRequests(),
              widget.repository.listInstitutionOptions(),
              widget.repository.listManagementProjects(),
              widget.repository.loadInstitutionProjectApplicationFormConfig(),
            ]);
      if (!mounted) return false;
      final allowedIds = widget.context.doctorInstitutionIds.toSet();
      setState(() {
        _requests = (values[0] as List<ProfessionalProjectRequest>)
            .where((item) => item.requestType == 'INSTITUTION')
            .where(_isVisible)
            .toList(growable: false);
        if (includeFormData) {
          final options = values[1] as List<InstitutionOption>;
          if (!widget.reviewMode) {
            _institutions = options
                .where((item) => allowedIds.contains(item.id))
                .toList(growable: false);
          } else if (_institutions.isNotEmpty) {
            final retainedIds = _institutions.map((item) => item.id).toSet();
            _institutions = options
                .where((item) => retainedIds.contains(item.id))
                .toList(growable: false);
          }
          _projects = values[2] as List<ManagementProjectOption>;
          _platformRate = (values[3] as InstitutionProjectApplicationFormConfig)
              .platformRate;
        }
        _loading = false;
        _error = null;
      });
      return true;
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = '机构项目申请加载失败，请重试';
        });
      }
      return false;
    }
  }

  bool _isVisible(ProfessionalProjectRequest request) {
    if (widget.reviewMode) {
      if (widget.context.platformRole == 'ADMIN') return true;
      final legal = widget.context.activeRoles
          .contains(IdentityRoleType.institutionLegalRepresentative.code);
      return legal &&
          request.institutionId != null &&
          widget.context.managedInstitutionIds.contains(request.institutionId);
    }
    return widget.context.doctorId != null &&
        request.doctorId == widget.context.doctorId;
  }

  bool _canReview(ProfessionalProjectRequest request) {
    if (!widget.reviewMode || !request.isCreationReviewable) return false;
    if (widget.context.platformRole == 'ADMIN') return true;
    return widget.context.canReviewInstitutionProjectRequests &&
        request.institutionId != null &&
        widget.context.managedInstitutionIds.contains(request.institutionId);
  }

  ManagementProjectOption? get _selectedProject {
    for (final project in _projects) {
      if (project.id == _projectId) return project;
    }
    return null;
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
                      key: ValueKey('institution-id-$_institutionId'),
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
                      onChanged: _saving
                          ? null
                          : (value) => setState(() => _institutionId = value),
                    ),
                    const SizedBox(height: 12),
                    DropdownButtonFormField<String>(
                      key: ValueKey('institution-project-$_projectId'),
                      initialValue: _projectId,
                      decoration: InputDecoration(
                        labelText:
                            context.localized('平台项目', 'Platform project'),
                      ),
                      items: _projects
                          .map((item) => DropdownMenuItem(
                                value: item.id,
                                child: Text(item.name),
                              ))
                          .toList(),
                      onChanged: _saving
                          ? null
                          : (value) {
                              if (value != null) _selectProject(value);
                            },
                    ),
                    const SizedBox(height: 12),
                    Text(
                      context.localized(
                        '当前认证医生：${widget.context.doctorId ?? '-'}。该医生是唯一申请医生，审批后仅关联本人。',
                        'Current authenticated doctor: ${widget.context.doctorId ?? '-'}. This is the only applicant and binding created after approval.',
                      ),
                      key: const Key('institution-applicant-notice'),
                    ),
                    const SizedBox(height: 12),
                    if (_selectedProject != null)
                      _inheritancePreview(context, _selectedProject!),
                    _requestField(
                      _name,
                      context.localized('项目名称（可选覆盖）', 'Name override'),
                      fieldKey: const Key('institution-name'),
                      hintText: _selectedProject?.name,
                    ),
                    _requestField(
                      _category,
                      context.localized('项目分类（可选覆盖）', 'Category override'),
                      fieldKey: const Key('institution-category'),
                      hintText: _selectedProject?.category,
                    ),
                    _requestField(
                      _description,
                      context.localized('项目说明（可选覆盖）', 'Description override'),
                      fieldKey: const Key('institution-description'),
                      maxLines: 4,
                      hintText: _selectedProject?.description,
                    ),
                    _requestField(
                      _tags,
                      context.localized('项目标签（可选覆盖）', 'Tags override'),
                      fieldKey: const Key('institution-tags'),
                      hintText: _selectedProject?.tags,
                    ),
                    _requestField(
                      _slogan,
                      context.localized('项目标语（可选覆盖）', 'Slogan override'),
                      fieldKey: const Key('institution-slogan'),
                      hintText: _selectedProject?.slogan,
                    ),
                    _requestField(
                      _detailContent,
                      context.localized(
                        '项目详情（可选纯文本覆盖）',
                        'Detail override (plain text)',
                      ),
                      fieldKey: const Key('institution-detail-content'),
                      maxLines: 6,
                      hintText: _selectedProject?.detailContent,
                    ),
                    _requestField(
                      _price,
                      context.localized('价格', 'Price'),
                      fieldKey: const Key('institution-price'),
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true),
                      hintText: _selectedProject == null
                          ? null
                          : '${_selectedProject!.referencePrice}',
                    ),
                    _requestField(
                      _originalPrice,
                      context.localized('原价（可选）', 'Original price (optional)'),
                      fieldKey: const Key('institution-original-price'),
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true),
                    ),
                    DropdownButtonFormField<String>(
                      key: ValueKey('institution-currency-$_currency'),
                      initialValue: _currency,
                      decoration: InputDecoration(
                        labelText: context.localized('币种', 'Currency'),
                      ),
                      items: const [
                        DropdownMenuItem(value: 'CNY', child: Text('CNY')),
                        DropdownMenuItem(value: 'USD', child: Text('USD')),
                      ],
                      onChanged: _saving
                          ? null
                          : (value) =>
                              setState(() => _currency = value ?? 'CNY'),
                    ),
                    const SizedBox(height: 12),
                    _imageUploadField(
                      context,
                      title: context.localized('封面图（可选覆盖）', 'Cover override'),
                      values: _cover.text.trim().isEmpty
                          ? const []
                          : [_cover.text.trim()],
                      inheritedValues: _selectedProject == null ||
                              _selectedProject!.coverImage.isEmpty
                          ? const []
                          : [_selectedProject!.coverImage],
                      addKey: const Key('institution-cover-upload'),
                      addLabel: context.localized('上传封面图', 'Upload cover'),
                      removeLabel:
                          context.localized('移除封面图', 'Remove cover image'),
                      enabled: !_saving &&
                          !_uploading &&
                          widget.pickAndUploadImage != null,
                      onAdd: () => _uploadImage(cover: true),
                      onRemove: (_) => setState(_cover.clear),
                    ),
                    _imageUploadField(
                      context,
                      title:
                          context.localized('项目图片（可选覆盖）', 'Gallery override'),
                      values: _images,
                      inheritedValues: _selectedProject?.images ?? const [],
                      addKey: const Key('institution-gallery-upload'),
                      addLabel:
                          context.localized('添加项目图片', 'Add gallery image'),
                      removeLabel:
                          context.localized('移除项目图片', 'Remove gallery image'),
                      enabled: !_saving &&
                          !_uploading &&
                          widget.pickAndUploadImage != null,
                      onAdd: () => _uploadImage(cover: false),
                      onRemove: (value) => setState(
                        () => _images = _images
                            .where((item) => item != value)
                            .toList(growable: false),
                      ),
                    ),
                    _requestField(
                      _salesCount,
                      context.localized('销量', 'Sales count'),
                      fieldKey: const Key('institution-sales-count'),
                      keyboardType: TextInputType.number,
                      hintText: _selectedProject == null
                          ? null
                          : '${_selectedProject!.salesCount}',
                    ),
                    SwitchListTile(
                      key: const Key('institution-is-active'),
                      contentPadding: EdgeInsets.zero,
                      title: Text(context.localized(
                          '审批后立即上架', 'Active after approval')),
                      value: _isActive,
                      onChanged: _saving
                          ? null
                          : (value) => setState(() => _isActive = value),
                    ),
                    _requestField(
                      _consultationFee,
                      context.localized('面诊费', 'Consultation fee'),
                      fieldKey: const Key('institution-consultation-fee'),
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true),
                    ),
                    _requestField(
                      _consultantRate,
                      context.localized('医美顾问比例（%）', 'Consultant rate (%)'),
                      fieldKey: const Key('institution-consultant-rate'),
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true),
                      onChanged: (_) => setState(() {}),
                    ),
                    _requestField(
                      _institutionRate,
                      context.localized('机构比例（%）', 'Institution rate (%)'),
                      fieldKey: const Key('institution-rate'),
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true),
                      onChanged: (_) => setState(() {}),
                    ),
                    Text(
                      '${context.localized('平台比例（只读）', 'Platform rate (read only)')}：${_formatNumber(_platformRate)}%',
                      key: const Key('institution-platform-rate'),
                    ),
                    Text(
                      '${context.localized('医生比例（自动推导）', 'Doctor rate (derived)')}：${_formatRateHundredths(_derivedDoctorRateHundredths)}%',
                      key: const Key('institution-doctor-rate'),
                    ),
                    const SizedBox(height: 12),
                    _requestField(
                      _notes,
                      context.localized('说明', 'Notes'),
                      fieldKey: const Key('institution-notes'),
                      maxLines: 3,
                    ),
                    FilledButton.icon(
                      key: const Key('institution-submit'),
                      onPressed: _saving || _uploading ? null : _submit,
                      icon: const Icon(Icons.send_outlined),
                      label: Text(context.localized('提交申请', 'Submit')),
                    ),
                    const SizedBox(height: 24),
                  ],
                  if (_error != null)
                    Text(_error!,
                        style: TextStyle(
                            color: Theme.of(context).colorScheme.error)),
                  for (final request in _requests)
                    _professionalRequestSnapshot(
                      context,
                      request,
                      canReview: _canReview(request),
                      reviewEnabled: !_reviewing,
                      onReview: () => _review(request),
                    ),
                ],
              ),
      );

  Future<void> _submit() async {
    final institutionId = _institutionId;
    final projectId = _projectId;
    final price = num.tryParse(_price.text.trim());
    final originalPrice = _originalPrice.text.trim().isEmpty
        ? null
        : num.tryParse(_originalPrice.text.trim());
    final consultationFee = num.tryParse(_consultationFee.text.trim());
    final consultantRate = num.tryParse(_consultantRate.text.trim());
    final institutionRate = num.tryParse(_institutionRate.text.trim());
    final salesCount = _salesCount.text.trim().isEmpty
        ? _selectedProject?.salesCount
        : int.tryParse(_salesCount.text.trim());
    if (institutionId == null ||
        projectId == null ||
        price == null ||
        consultationFee == null ||
        consultantRate == null ||
        institutionRate == null ||
        salesCount == null ||
        _platformRate == null ||
        (_originalPrice.text.trim().isNotEmpty && originalPrice == null)) {
      setState(() => _error = '请选择机构和平台项目，并填写有效金额、销量与分账比例');
      return;
    }
    final draft = InstitutionProjectRequestDraft(
      institutionId: institutionId,
      projectId: projectId,
      name: _optionalText(_name.text),
      category: _optionalText(_category.text),
      description: _optionalText(_description.text),
      tags: _optionalItems(_tags.text),
      slogan: _optionalText(_slogan.text),
      detailContent: _optionalText(_detailContent.text),
      price: price,
      originalPrice: originalPrice,
      currency: _currency,
      coverImage: _optionalText(_cover.text),
      images: _images.isEmpty ? null : _images,
      salesCount: salesCount,
      isActive: _isActive,
      consultationFee: consultationFee,
      commissionRate: consultantRate,
      institutionRate: institutionRate,
      platformRate: _platformRate!,
      notes: _notes.text,
    );
    try {
      draft.validate();
    } on ArgumentError catch (error) {
      setState(() => _error = error.message.toString());
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await widget.repository.submitInstitutionProjectRequest(draft);
      if (await _refreshRequests()) _clearDraft();
    } catch (_) {
      if (mounted) setState(() => _error = '机构项目申请提交失败，请稍后重试');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _review(ProfessionalProjectRequest request) async {
    if (_reviewing || !request.hasCompleteReviewSnapshot) return;
    setState(() {
      _reviewing = true;
      _error = null;
    });
    try {
      final review = await showProfessionalProjectCreationReviewDialog(
        context,
        allowApproval: request.isCurrentlyApprovable,
      );
      if (!mounted || review == null) return;
      if (review.decision == 'APPROVED' && !request.isCurrentlyApprovable) {
        setState(() => _error = context.localized(
              '当前医生净比例为负，无法批准；可驳回申请并说明原因',
              'The current doctor net rate is negative and cannot be approved. Reject the request with a reason instead.',
            ));
        return;
      }
      await widget.repository.reviewInstitutionProjectRequest(
        id: request.id,
        decision: review.decision,
        reviewNote: review.note,
      );
      await _load();
    } on ApiException catch (error) {
      if (!mounted) return;
      if (error.httpStatus == 409) {
        final refreshed = await _refreshRequests();
        if (!mounted) return;
        setState(() => _error = refreshed
            ? context.localized(
                '审核状态已变化，申请列表已刷新，请基于最新内容重试',
                'The review state changed. The request list was refreshed; retry from the latest content.',
              )
            : context.localized(
                '审核状态已变化，但刷新失败，请手动刷新后重试',
                'The review state changed, but refresh failed. Refresh manually and retry.',
              ));
      } else {
        setState(() => _error = '审核提交失败，请稍后重试');
      }
    } catch (_) {
      if (mounted) setState(() => _error = '审核提交失败，请稍后重试');
    } finally {
      if (mounted) setState(() => _reviewing = false);
    }
  }

  void _selectProject(String projectId) {
    final project = _projects.firstWhere((item) => item.id == projectId);
    setState(() {
      _projectId = projectId;
      _currency = project.currency;
      _error = null;
    });
  }

  int? get _derivedDoctorRateHundredths {
    final platform = _rateHundredths('${_platformRate ?? ''}');
    final institution = _rateHundredths(_institutionRate.text);
    final consultant = _rateHundredths(_consultantRate.text);
    if (platform == null || institution == null || consultant == null) {
      return null;
    }
    return 10000 - platform - institution - consultant;
  }

  Future<bool> _refreshRequests() async {
    try {
      final requests =
          await widget.repository.listProfessionalProjectRequests();
      if (!mounted) return false;
      setState(() {
        _requests = requests
            .where((item) => item.requestType == 'INSTITUTION')
            .where(_isVisible)
            .toList(growable: false);
        _error = null;
      });
      return true;
    } catch (_) {
      if (mounted) setState(() => _error = '机构项目申请加载失败，请重试');
      return false;
    }
  }

  Future<void> _uploadImage({required bool cover}) async {
    setState(() {
      _uploading = true;
      _error = null;
    });
    try {
      final value = (await widget.pickAndUploadImage?.call())?.trim();
      if (!mounted || value == null || value.isEmpty) return;
      setState(() {
        if (cover) {
          _cover.text = value;
        } else {
          _images = [..._images, value];
        }
      });
    } catch (_) {
      if (mounted) setState(() => _error = '图片上传失败，请重试');
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  void _clearDraft() {
    if (!mounted) return;
    for (final controller in [
      _name,
      _category,
      _description,
      _tags,
      _slogan,
      _detailContent,
      _price,
      _originalPrice,
      _cover,
      _salesCount,
      _consultationFee,
      _consultantRate,
      _institutionRate,
      _notes,
    ]) {
      controller.clear();
    }
    setState(() {
      _institutionId = null;
      _projectId = null;
      _currency = 'CNY';
      _images = const [];
      _isActive = true;
    });
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

class DoctorProjectProfileUpdatePage extends StatefulWidget {
  const DoctorProjectProfileUpdatePage({
    required this.repository,
    this.pickAndUploadImage,
    super.key,
  });

  final IdentityRepository repository;
  final Future<String?> Function()? pickAndUploadImage;

  @override
  State<DoctorProjectProfileUpdatePage> createState() =>
      _DoctorProjectProfileUpdatePageState();
}

class _DoctorProjectProfileUpdatePageState
    extends State<DoctorProjectProfileUpdatePage> {
  final _price = TextEditingController();
  final _description = TextEditingController();
  final _tags = TextEditingController();
  final _schedule = TextEditingController();
  final _cover = TextEditingController();
  final _images = TextEditingController();
  final _consultationFee = TextEditingController();
  final _consultantRate = TextEditingController();
  final _institutionRate = TextEditingController();
  final _notes = TextEditingController();
  List<DoctorProjectProfileUpdateTarget> _targets = const [];
  List<DoctorProjectChangeRequest> _requests = const [];
  DoctorProjectProfileUpdateTarget? _selected;
  bool _loading = true, _saving = false, _uploading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    for (final controller in [
      _price,
      _description,
      _tags,
      _schedule,
      _cover,
      _images,
      _consultationFee,
      _consultantRate,
      _institutionRate,
      _notes,
    ]) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      Object? targetFailure;
      final values = await Future.wait<Object>([
        widget.repository.listDoctorProjectProfileUpdateTargets().catchError(
          (Object error) {
            targetFailure = error;
            return const <DoctorProjectProfileUpdateTarget>[];
          },
        ),
        widget.repository.listDoctorProjectChangeRequests(),
      ]);
      final targets = values[0] as List<DoctorProjectProfileUpdateTarget>;
      if (!mounted) return;
      setState(() {
        _targets = targets;
        _requests = values[1] as List<DoctorProjectChangeRequest>;
        _loading = false;
        if (targetFailure != null) {
          _error = context.localized('可修改项目加载失败，请重试',
              'Failed to load editable projects. Please retry.');
        }
      });
      if (targets.length == 1) _select(targets.single);
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = context.localized(
              '项目资料加载失败，请重试', 'Failed to load project profiles. Please retry.');
        });
      }
    }
  }

  void _select(DoctorProjectProfileUpdateTarget target) {
    setState(() {
      _selected = target;
      _error = null;
    });
    _price.text = '${target.currentPrice}';
    _description.text = target.serviceDescription;
    _tags.text = target.serviceTags.join(', ');
    _schedule.text = target.scheduleNote;
    _cover.text = target.coverImage;
    _images.text = target.images.join('\n');
    _consultationFee.text = '${target.consultationFee}';
    _consultantRate.text = '${target.commissionRate}';
    _institutionRate.text = '${target.institutionRate}';
    _notes.clear();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
            title: Text(context.localized(
                '医生项目资料变更', 'Doctor project profile update'))),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(padding: const EdgeInsets.all(16), children: [
                if (_error != null) ...[
                  Text(_error!,
                      style: TextStyle(
                          color: Theme.of(context).colorScheme.error)),
                  TextButton(
                      onPressed: _saving ? null : _load,
                      child: Text(context.localized('重试', 'Retry'))),
                ],
                if (_targets.isEmpty && _error == null)
                  Text(context.localized('暂无可修改的医生项目',
                      'No doctor project is available to update.')),
                if (_targets.isNotEmpty) ...[
                  DropdownButtonFormField<DoctorProjectProfileUpdateTarget>(
                    key: const Key('profile-update-target'),
                    initialValue: _selected,
                    decoration: InputDecoration(
                        labelText: context.localized(
                            '本人机构项目', 'My institution project')),
                    items: _targets
                        .map((target) => DropdownMenuItem(
                            value: target,
                            child: Text(
                                '${target.institutionName} · ${target.projectName}')))
                        .toList(),
                    onChanged: _saving
                        ? null
                        : (target) {
                            if (target != null) _select(target);
                          },
                  ),
                  const SizedBox(height: 12),
                  _requestField(
                      _price, context.localized('医生级价格', 'Doctor price'),
                      fieldKey: const Key('profile-update-price'),
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true)),
                  _requestField(_description,
                      context.localized('项目展示说明', 'Display description'),
                      maxLines: 4),
                  _requestField(
                      _tags,
                      context.localized(
                          '服务标签（逗号分隔）', 'Service tags (comma separated)')),
                  _requestField(
                      _schedule, context.localized('排期说明', 'Schedule note')),
                  _requestField(
                      _cover, context.localized('封面图片', 'Cover image')),
                  _requestField(
                      _images,
                      context.localized(
                          '项目图片（每行一个）', 'Project images (one per line)'),
                      maxLines: 3),
                  if (widget.pickAndUploadImage != null)
                    OutlinedButton.icon(
                        key: const Key('profile-update-upload'),
                        onPressed: _saving || _uploading ? null : _uploadImage,
                        icon: const Icon(Icons.upload_outlined),
                        label: Text(context.localized(
                            '上传项目图片', 'Upload project image'))),
                  _requestField(_consultationFee,
                      context.localized('面诊费', 'Consultation fee'),
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true)),
                  _requestField(_consultantRate,
                      context.localized('医美顾问比例（%）', 'Consultant rate (%)'),
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true)),
                  _requestField(_institutionRate,
                      context.localized('机构比例（%）', 'Institution rate (%)'),
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true)),
                  if (_selected != null) ...[
                    Text(
                        '${context.localized('平台比例（只读）', 'Platform rate (read only)')}: ${_selected!.platformRate}%'),
                    Text(
                        '${context.localized('医生净比例（自动推导）', 'Doctor net rate (derived)')}: ${_derivedDoctorRate()}%'),
                  ],
                  _requestField(
                      _notes, context.localized('申请说明', 'Request note'),
                      maxLines: 3),
                  FilledButton.icon(
                      key: const Key('submit-profile-update'),
                      onPressed: _saving ? null : _submit,
                      icon: _saving
                          ? const SizedBox.square(
                              dimension: 18,
                              child: CircularProgressIndicator(strokeWidth: 2))
                          : const Icon(Icons.send_outlined),
                      label: Text(context.localized(
                          _saving ? '提交中…' : '提交整体变更申请',
                          _saving
                              ? 'Submitting…'
                              : 'Submit profile update request'))),
                  const SizedBox(height: 16),
                ],
                const SizedBox(height: 16),
                Text(context.localized('我的项目申请', 'My project requests'),
                    style: Theme.of(context).textTheme.titleMedium),
                for (final request in _requests)
                  ListTile(
                    title: Text(
                        '${request.institutionName} · ${request.projectName}'),
                    subtitle: Text(request.status),
                    trailing: _canWithdraw(request)
                        ? TextButton(
                            key: Key('withdraw-${request.id}'),
                            onPressed:
                                _saving ? null : () => _withdraw(request),
                            child: Text(context.localized('撤回', 'Withdraw')),
                          )
                        : null,
                  ),
              ]),
      );

  num _derivedDoctorRate() =>
      100 -
      (_selected?.platformRate ?? 0) -
      (num.tryParse(_institutionRate.text) ?? 0) -
      (num.tryParse(_consultantRate.text) ?? 0);

  bool _canWithdraw(DoctorProjectChangeRequest request) {
    final status = request.status.trim().toUpperCase();
    final type = request.requestType.trim().toUpperCase();
    return status == 'PENDING' && (type == 'JOIN' || type == 'PROFILE_UPDATE');
  }

  Future<void> _withdraw(DoctorProjectChangeRequest request) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(context.localized('撤回申请', 'Withdraw request')),
        content: Text(context.localized(
            '确认撤回这条待处理申请？', 'Withdraw this pending request?')),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: Text(context.localized('取消', 'Cancel'))),
          FilledButton(
              onPressed: () => Navigator.pop(dialogContext, true),
              child: Text(context.localized('撤回', 'Withdraw'))),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() => _saving = true);
    try {
      await widget.repository.withdrawDoctorProjectChangeRequest(request.id);
      await _load();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(context.localized('申请已撤回', 'Request withdrawn.'))));
      }
    } catch (_) {
      if (mounted) {
        setState(() => _error =
            context.localized('撤回失败，请重试', 'Withdrawal failed. Please retry.'));
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _uploadImage() async {
    setState(() => _uploading = true);
    try {
      final image = await widget.pickAndUploadImage?.call();
      if (!mounted || image == null || image.trim().isEmpty) return;
      final existing = _lines(_images.text);
      _images.text = [...existing, image.trim()].join('\n');
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<void> _submit() async {
    final target = _selected;
    if (target == null) {
      setState(() => _error =
          context.localized('请选择机构项目', 'Select an institution project.'));
      return;
    }
    final values = [_price, _consultationFee, _consultantRate, _institutionRate]
        .map((controller) => num.tryParse(controller.text.trim()))
        .toList();
    if (values.any((value) => value == null)) {
      setState(() => _error =
          context.localized('请填写有效金额和比例', 'Enter valid amounts and rates.'));
      return;
    }
    final draft = DoctorProjectProfileUpdateDraft(
      institutionProjectId: target.institutionProjectId,
      priceSuggestion: values[0]!,
      serviceDescription: _description.text,
      serviceTags: _csv(_tags.text),
      scheduleNote: _schedule.text,
      coverImage: _cover.text,
      images: _lines(_images.text),
      consultationFee: values[1]!,
      commissionRate: values[2]!,
      institutionRate: values[3]!,
      platformRate: target.platformRate,
      notes: _notes.text,
    );
    try {
      draft.validate();
    } on ArgumentError catch (error) {
      setState(() => _error = error.message.toString());
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final request =
          await widget.repository.submitDoctorProjectProfileUpdate(draft);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(context.localized('整体变更申请已提交，当前状态：${request.status}',
              'Profile update request submitted. Status: ${request.status}'))));
    } on ApiException catch (error) {
      if (!mounted) return;
      setState(() => _error = error.httpStatus == 409
          ? context.localized('当前资料或待审核申请已变化，请刷新后重新申请',
              'The profile or pending request changed. Refresh and submit again.')
          : error.message);
    } catch (_) {
      if (mounted) {
        setState(() => _error = context.localized(
            '申请提交失败，请稍后重试', 'Failed to submit the request. Please retry.'));
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }
}

List<String> _csv(String value) => value
    .split(',')
    .map((item) => item.trim())
    .where((item) => item.isNotEmpty)
    .toList(growable: false);
List<String> _lines(String value) => value
    .split(RegExp(r'[\r\n]+'))
    .map((item) => item.trim())
    .where((item) => item.isNotEmpty)
    .toList(growable: false);

class DoctorProjectProfileReviewPage extends StatefulWidget {
  const DoctorProjectProfileReviewPage({
    required this.repository,
    required this.context,
    super.key,
  });

  final IdentityRepository repository;
  final ManagementContext context;

  @override
  State<DoctorProjectProfileReviewPage> createState() =>
      _DoctorProjectProfileReviewPageState();
}

class _DoctorProjectProfileReviewPageState
    extends State<DoctorProjectProfileReviewPage> {
  List<DoctorProjectChangeRequest> _requests = const [];
  bool _loading = true, _submitting = false;
  String? _error;
  bool get _isAdmin => widget.context.platformRole == 'ADMIN';

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
      final requests =
          await widget.repository.listDoctorProjectChangeRequests();
      if (!mounted) return;
      setState(() {
        _requests = requests
            .where((request) => request.requestType == 'PROFILE_UPDATE')
            .toList(growable: false);
        _loading = false;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = context.localized('变更申请加载失败，请重试',
              'Failed to load profile update requests. Please retry.');
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
            title: Text(context.localized(
                '医生项目资料审核', 'Doctor project profile reviews'))),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : RefreshIndicator(
                onRefresh: _load,
                child: ListView(
                  padding: const EdgeInsets.all(16),
                  children: [
                    if (_error != null) ...[
                      Text(_error!,
                          style: TextStyle(
                              color: Theme.of(context).colorScheme.error)),
                      TextButton(
                          onPressed: _load,
                          child: Text(context.localized('重试', 'Retry'))),
                    ],
                    if (_requests.isEmpty && _error == null)
                      Text(context.localized('暂无医生项目资料变更申请',
                          'No doctor project profile update requests.')),
                    for (final request in _requests)
                      Card(
                          child: Padding(
                        padding: const EdgeInsets.all(12),
                        child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                  '${request.institutionName} · ${request.projectName}',
                                  style:
                                      Theme.of(context).textTheme.titleMedium),
                              Text(
                                  '${context.localized('医生', 'Doctor')}: ${request.doctorName}'),
                              Text(
                                  '${context.localized('状态', 'Status')}: ${request.status}'),
                              const Divider(),
                              Text(
                                  context.localized(
                                      '提交时当前值', 'Current values at submission'),
                                  style:
                                      Theme.of(context).textTheme.titleSmall),
                              Text(
                                  '${context.localized('医生级价格', 'Doctor price')}: ${request.currentPrice ?? '-'}'),
                              Text(
                                  '${context.localized('项目展示说明', 'Display description')}: ${request.currentServiceDescription ?? '-'}'),
                              Text(
                                  '${context.localized('标签', 'Tags')}: ${request.currentServiceTags?.join(', ') ?? '-'}'),
                              Text(
                                  '${context.localized('排期', 'Schedule')}: ${request.currentScheduleNote ?? '-'}'),
                              Text(
                                  '${context.localized('面诊费', 'Consultation fee')}: ${request.currentConsultationFee ?? '-'}'),
                              Text(
                                  '${context.localized('医美顾问比例', 'Consultant rate')}: ${request.currentCommissionRate ?? '-'}%'),
                              Text(
                                  '${context.localized('机构比例', 'Institution rate')}: ${request.currentInstitutionRate ?? '-'}%'),
                              Text(
                                  '${context.localized('平台比例', 'Platform rate')}: ${request.currentPlatformRate ?? '-'}%'),
                              Text(
                                  '${context.localized('医生净比例', 'Doctor net rate')}: ${request.currentDoctorRate ?? '-'}%'),
                              const Divider(),
                              Text(context.localized('申请值', 'Proposed values'),
                                  style:
                                      Theme.of(context).textTheme.titleSmall),
                              Text(
                                  '${context.localized('医生级价格', 'Doctor price')}: ${request.priceSuggestion ?? '-'}'),
                              Text(
                                  '${context.localized('项目展示说明', 'Display description')}: ${request.serviceDescription}'),
                              Text(
                                  '${context.localized('标签', 'Tags')}: ${request.serviceTags.join(', ')}'),
                              Text(
                                  '${context.localized('排期', 'Schedule')}: ${request.scheduleNote}'),
                              Text(
                                  '${context.localized('面诊费', 'Consultation fee')}: ${request.consultationFee ?? '-'}'),
                              Text(
                                  '${context.localized('医美顾问比例', 'Consultant rate')}: ${request.commissionRate ?? '-'}%'),
                              Text(
                                  '${context.localized('机构比例', 'Institution rate')}: ${request.institutionRate ?? '-'}%'),
                              Text(
                                  '${context.localized('平台比例（只读）', 'Platform rate (read only)')}: ${request.platformRate ?? '-'}%'),
                              Text(
                                  '${context.localized('医生净比例（推导）', 'Doctor net rate (derived)')}: ${request.doctorRate ?? '-'}%'),
                              if (request.forceProcessed)
                                Text(context.localized('已由管理员强制处理',
                                    'Force-processed by an administrator')),
                              if (request.status == 'PENDING') ...[
                                const SizedBox(height: 12),
                                Wrap(spacing: 8, runSpacing: 8, children: [
                                  FilledButton(
                                      key: Key('approve-${request.id}'),
                                      onPressed: _submitting
                                          ? null
                                          : () => _review(
                                              request, 'APPROVED', false),
                                      child: Text(
                                          context.localized('批准', 'Approve'))),
                                  OutlinedButton(
                                      key: Key('reject-${request.id}'),
                                      onPressed: _submitting
                                          ? null
                                          : () => _review(
                                              request, 'REJECTED', false),
                                      child: Text(
                                          context.localized('驳回', 'Reject'))),
                                  OutlinedButton(
                                      key: Key('changes-${request.id}'),
                                      onPressed: _submitting
                                          ? null
                                          : () => _review(request,
                                              'CHANGES_REQUESTED', false),
                                      child: Text(context.localized(
                                          '要求修改', 'Request changes'))),
                                  if (_isAdmin)
                                    FilledButton.tonal(
                                        key: Key('force-${request.id}'),
                                        onPressed: _submitting
                                            ? null
                                            : () => _review(
                                                request, 'APPROVED', true),
                                        child: Text(context.localized(
                                            '强制批准', 'Force approve'))),
                                ]),
                              ],
                            ]),
                      )),
                  ],
                )),
      );

  Future<void> _review(
      DoctorProjectChangeRequest request, String decision, bool force) async {
    final result = await _showProfileReviewDialog(context,
        decision: decision, force: force);
    if (result == null) return;
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      await widget.repository.reviewDoctorProjectChangeRequest(
          id: request.id, decision: decision, reviewNote: result, force: force);
      await _load();
    } catch (_) {
      if (mounted) {
        setState(() => _error = context.localized('审核提交失败，请刷新后重试',
            'Failed to submit the review. Refresh and retry.'));
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }
}

Future<String?> _showProfileReviewDialog(BuildContext context,
    {required String decision, required bool force}) async {
  final note = TextEditingController();
  final accepted = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
            title: Text(force
                ? context.localized('确认强制批准', 'Confirm force approval')
                : context.localized('提交审核', 'Submit review')),
            content: Column(mainAxisSize: MainAxisSize.min, children: [
              if (force)
                Text(context.localized('强制批准会绕过关系失效或基线漂移阻断，但仍原子更新本人项目。此操作会被审计。',
                    'Force approval bypasses relationship or baseline drift checks, while retaining atomic scoped updates. This action is audited.')),
              TextField(
                  key: const Key('profile-review-note'),
                  controller: note,
                  decoration: InputDecoration(
                      labelText: context.localized('审核说明', 'Review note')),
                  maxLines: 3),
            ]),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(dialogContext, false),
                  child: Text(context.localized('取消', 'Cancel'))),
              FilledButton(
                  onPressed: () {
                    if ((force || decision != 'APPROVED') &&
                        note.text.trim().isEmpty) {
                      return;
                    }
                    Navigator.pop(dialogContext, true);
                  },
                  child: Text(context.localized('确认', 'Confirm'))),
            ],
          ));
  final value = note.text.trim();
  return accepted == true ? value : null;
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
            .where(
                (request) => widget.reviewMode || request.doctorId == doctorId)
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
                    style:
                        TextStyle(color: Theme.of(context).colorScheme.error),
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

Future<({String decision, String note})?>
    showProfessionalProjectCreationReviewDialog(
  BuildContext context, {
  bool allowApproval = true,
}) =>
        _showReviewDialog(
          context,
          decisionKey: const Key('creation-review-decision'),
          noteKey: const Key('creation-review-note'),
          confirmKey: const Key('creation-review-confirm'),
          warning: allowApproval
              ? null
              : context.localized(
                  '当前医生净比例为负，无法批准；可驳回申请并说明原因',
                  'The current doctor net rate is negative and cannot be approved. Reject the request with a reason instead.',
                ),
          decisions: [
            if (allowApproval)
              const DropdownMenuItem(
                key: Key('creation-review-APPROVED'),
                value: 'APPROVED',
                child: Text('通过'),
              ),
            const DropdownMenuItem(
              key: Key('creation-review-REJECTED'),
              value: 'REJECTED',
              child: Text('驳回'),
            ),
          ],
        );

Future<({String decision, String note})?> _showReviewDialog(
  BuildContext context, {
  required List<DropdownMenuItem<String>> decisions,
  Key? decisionKey,
  Key? noteKey,
  Key? confirmKey,
  String? warning,
}) =>
    showDialog<({String decision, String note})>(
      context: context,
      builder: (_) => _ReviewDialog(
        decisions: decisions,
        decisionKey: decisionKey,
        noteKey: noteKey,
        confirmKey: confirmKey,
        warning: warning,
      ),
    );

class _ReviewDialog extends StatefulWidget {
  const _ReviewDialog({
    required this.decisions,
    this.decisionKey,
    this.noteKey,
    this.confirmKey,
    this.warning,
  });

  final List<DropdownMenuItem<String>> decisions;
  final Key? decisionKey;
  final Key? noteKey;
  final Key? confirmKey;
  final String? warning;

  @override
  State<_ReviewDialog> createState() => _ReviewDialogState();
}

class _ReviewDialogState extends State<_ReviewDialog> {
  final _note = TextEditingController();
  late String _decision;
  String? _noteError;

  @override
  void initState() {
    super.initState();
    _decision = widget.decisions.first.value!;
  }

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: Text(context.localized('审核申请', 'Review Request')),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (widget.warning != null) ...[
              Text(
                widget.warning!,
                key: const Key('creation-review-approval-blocked'),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
              const SizedBox(height: 12),
            ],
            DropdownButtonFormField<String>(
              key: widget.decisionKey,
              initialValue: _decision,
              decoration: InputDecoration(
                labelText: context.localized('审核决定', 'Review decision'),
              ),
              items: widget.decisions,
              onChanged: (value) => setState(() {
                _decision = value ?? _decision;
                _noteError = null;
              }),
            ),
            const SizedBox(height: 12),
            TextField(
              key: widget.noteKey,
              controller: _note,
              maxLines: 3,
              onChanged: (value) {
                if (_noteError != null && value.trim().isNotEmpty) {
                  setState(() => _noteError = null);
                }
              },
              decoration: InputDecoration(
                labelText: context.localized('审核意见', 'Review note'),
                errorText: _noteError,
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(context.localized('取消', 'Cancel')),
          ),
          FilledButton(
            key: widget.confirmKey,
            onPressed: () {
              if (_decision != 'APPROVED' && _note.text.trim().isEmpty) {
                setState(() {
                  _noteError = _decision == 'REJECTED'
                      ? context.localized('驳回时必须填写审核意见',
                          'A review note is required when rejecting.')
                      : context.localized('要求修改时必须填写审核意见',
                          'A review note is required when requesting changes.');
                });
                return;
              }
              Navigator.of(context).pop((
                decision: _decision,
                note: _note.text.trim(),
              ));
            },
            child: Text(context.localized('确认', 'Confirm')),
          ),
        ],
      );
}

Widget _requestField(
  TextEditingController controller,
  String label, {
  Key? fieldKey,
  int maxLines = 1,
  TextInputType? keyboardType,
  String? hintText,
  ValueChanged<String>? onChanged,
}) =>
    Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TextField(
        key: fieldKey,
        controller: controller,
        maxLines: maxLines,
        keyboardType: keyboardType,
        onChanged: onChanged,
        decoration: InputDecoration(labelText: label, hintText: hintText),
      ),
    );

Widget _imageUploadField(
  BuildContext context, {
  required String title,
  required List<String> values,
  required Key addKey,
  required String addLabel,
  required String removeLabel,
  required bool enabled,
  required Future<void> Function() onAdd,
  required ValueChanged<String> onRemove,
  List<String> inheritedValues = const [],
}) =>
    Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleSmall),
            if (values.isEmpty && inheritedValues.isNotEmpty)
              Text(
                '${context.localized('继承值', 'Inherited')}: ${inheritedValues.join(', ')}',
              ),
            for (final entry in values.indexed)
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.image_outlined),
                title: Text(entry.$2),
                trailing: Semantics(
                  label: _indexedImageRemoveLabel(
                    context,
                    removeLabel,
                    entry.$1,
                    entry.$2,
                  ),
                  button: true,
                  child: IconButton(
                    key: ValueKey('${addKey.toString()}-remove-${entry.$1}'),
                    tooltip: _indexedImageRemoveLabel(
                      context,
                      removeLabel,
                      entry.$1,
                      entry.$2,
                    ),
                    onPressed: enabled ? () => onRemove(entry.$2) : null,
                    icon: const Icon(Icons.delete_outline),
                  ),
                ),
              ),
            OutlinedButton.icon(
              key: addKey,
              onPressed: enabled ? onAdd : null,
              icon: const Icon(Icons.add_photo_alternate_outlined),
              label: Text(addLabel),
            ),
          ],
        ),
      ),
    );

String _indexedImageRemoveLabel(
  BuildContext context,
  String removeLabel,
  int index,
  String identity,
) =>
    context.localized(
      '$removeLabel ${index + 1}：$identity',
      '$removeLabel ${index + 1}: $identity',
    );

Widget _inheritancePreview(
  BuildContext context,
  ManagementProjectOption project,
) =>
    Card(
      key: const Key('institution-inheritance-preview'),
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Text([
          '${context.localized('继承项目', 'Inherited project')}: ${project.name}',
          '${context.localized('分类', 'Category')}: ${project.category}',
          '${context.localized('说明', 'Description')}: ${project.description}',
          '${context.localized('标签', 'Tags')}: ${project.tags}',
          '${context.localized('分类标签', 'Category tags')}: ${project.categoryTags}',
          '${context.localized('封面', 'Cover')}: ${project.coverImage}',
          '${context.localized('参考价格', 'Reference price')}: ${project.referencePrice}',
          '${context.localized('币种', 'Currency')}: ${project.currency}',
          '${context.localized('标语', 'Slogan')}: ${project.slogan}',
          '${context.localized('详情', 'Detail')}: ${project.detailContent ?? ''}',
          '${context.localized('图片', 'Images')}: ${project.images.join(', ')}',
          '${context.localized('销量', 'Sales')}: ${project.salesCount}',
        ].join('\n')),
      ),
    );

Widget _professionalRequestSnapshot(
  BuildContext context,
  ProfessionalProjectRequest request, {
  required bool canReview,
  required bool reviewEnabled,
  required VoidCallback onReview,
}) {
  final split = request.institutionSplit;
  final hasCompleteSnapshot = request.hasCompleteReviewSnapshot;
  final rows = <String>[
    '${context.localized('申请编号', 'Request ID')}：${request.id}',
    '${context.localized('申请类型', 'Request type')}：${request.requestType}',
    '${context.localized('申请医生编号', 'Applicant doctor ID')}：${request.doctorId}',
    '${context.localized('当前医生名称', 'Current doctor name')}：${_snapshotText(context, request.doctorName)}',
    '${context.localized('机构编号', 'Institution ID')}：${_snapshotText(context, request.institutionId)}',
    '${context.localized('当前机构名称', 'Current institution name')}：${_snapshotText(context, request.institutionName)}',
    '${context.localized('平台项目编号', 'Platform project ID')}：${_snapshotText(context, request.projectId)}',
    '${context.localized('当前平台项目名称', 'Current platform project name')}：${_snapshotText(context, request.projectName)}',
    '${context.localized('项目名称', 'Name')}：${_snapshotText(context, request.name)}',
    '${context.localized('项目分类', 'Category')}：${_snapshotText(context, request.category)}',
    '${context.localized('项目说明', 'Description')}：${_snapshotText(context, request.description)}',
    '${context.localized('项目标签', 'Tags')}：${_snapshotItems(context, request.tags)}',
    '${context.localized('项目标语', 'Slogan')}：${_snapshotText(context, request.slogan)}',
    '${context.localized('项目详情', 'Detail')}：${_snapshotText(context, request.detailContent)}',
    '${context.localized('币种', 'Currency')}：${request.currency}',
    '${context.localized('封面图', 'Cover')}：${_snapshotText(context, request.coverImage)}',
    '${context.localized('项目图片', 'Images')}：${_snapshotItems(context, request.images)}',
    '${context.localized('销量', 'Sales count')}：${request.salesCount}',
    '${context.localized('参考价格', 'Reference price')}：${request.referencePrice ?? '-'}',
    '${context.localized('分类标签', 'Category tags')}：${_snapshotItems(context, request.categoryTags)}',
    '${context.localized('价格', 'Price')}：${request.price ?? '-'}',
    '${context.localized('原价', 'Original price')}：${request.originalPrice ?? '-'}',
    '${context.localized('上架', 'Active')}：${request.isActive ?? '-'}',
    if (split != null) ...[
      '${context.localized('面诊费', 'Consultation fee')}：${_formatNumber(split.consultationFee)}',
      '${context.localized('顾问比例', 'Consultant rate')}：${_formatNumber(split.commissionRate)}%',
      '${context.localized('机构比例', 'Institution rate')}：${_formatNumber(split.institutionRate)}%',
      '${context.localized('当前平台比例', 'Current platform rate')}：${_formatNumber(split.platformRate)}%',
      '${context.localized('按当前平台比例推导的医生净比例', 'Doctor net rate derived from the current platform rate')}：${_formatNumber(split.doctorRate)}%',
    ],
    '${context.localized('申请说明', 'Notes')}：${_snapshotText(context, request.notes)}',
    '${context.localized('状态', 'Status')}：${_statusLabel(request.status)}',
    '${context.localized('审核意见', 'Review note')}：${_snapshotText(context, request.reviewNote)}',
    '${context.localized('提交时间', 'Submitted at')}：${request.submittedAt.toIso8601String()}',
    '${context.localized('更新时间', 'Updated at')}：${request.updatedAt.toIso8601String()}',
    '${context.localized('审核人', 'Reviewed by')}：${_snapshotText(context, request.reviewedBy)}',
    '${context.localized('审核时间', 'Reviewed at')}：${request.reviewedAt?.toIso8601String() ?? '-'}',
    '${context.localized('生成平台项目', 'Resulting platform project')}：${_snapshotText(context, request.resultingProjectId)}',
    '${context.localized('生成机构项目', 'Resulting institution project')}：${_snapshotText(context, request.resultingInstitutionProjectId)}',
  ];
  return Card(
    key: Key('professional-request-${request.id}'),
    margin: const EdgeInsets.only(bottom: 12),
    child: Padding(
      padding: const EdgeInsets.all(12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(request.name ?? request.id,
              style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          for (final row in rows)
            Padding(
              padding: const EdgeInsets.only(bottom: 3),
              child: Text(row),
            ),
          if (!hasCompleteSnapshot)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                context.localized(
                  '申请快照不完整，无法审核，请刷新后重试',
                  'The request snapshot is incomplete and cannot be reviewed. Refresh and retry.',
                ),
                key: Key('malformed-review-snapshot-${request.id}'),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          if (canReview && hasCompleteSnapshot)
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton.tonalIcon(
                key: Key('review-creation-${request.id}'),
                onPressed: reviewEnabled ? onReview : null,
                icon: const Icon(Icons.fact_check_outlined),
                label: Text(context.localized('审核', 'Review')),
              ),
            ),
        ],
      ),
    ),
  );
}

String? _optionalText(String value) =>
    value.trim().isEmpty ? null : value.trim();

List<String>? _optionalItems(String value) {
  final items = _csv(value);
  return items.isEmpty ? null : items;
}

String _formatNumber(num? value) {
  if (value == null) return '-';
  return value == value.roundToDouble()
      ? value.toInt().toString()
      : value.toString();
}

String _snapshotText(BuildContext context, String? value) {
  if (value == null) return context.localized('未提供', 'Not provided');
  if (value.isEmpty) return context.localized('空字符串', 'Empty string');
  return value;
}

String _snapshotItems(BuildContext context, List<String>? values) {
  if (values == null) return context.localized('未提供', 'Not provided');
  if (values.isEmpty) return context.localized('空列表', 'Empty list');
  return values.join(', ');
}

int? _rateHundredths(String value) {
  final parsed = num.tryParse(value.trim());
  if (parsed == null || !parsed.isFinite || parsed < 0 || parsed > 100) {
    return null;
  }
  final hundredths = (parsed * 100).round();
  if (parsed != hundredths / 100) return null;
  return hundredths;
}

String _formatRateHundredths(int? value) {
  if (value == null) return '-';
  final remainder = value.abs() % 100;
  final precision = remainder == 0
      ? 0
      : remainder % 10 == 0
          ? 1
          : 2;
  return (value / 100).toStringAsFixed(precision);
}

List<String> _parseCsv(String value) => value
    .split(',')
    .map((item) => item.trim())
    .where((item) => item.isNotEmpty)
    .toList();

String _statusLabel(String status) => switch (status) {
      'PENDING' => '待审核',
      'APPROVED' => '已通过',
      'REJECTED' => '已驳回',
      'CHANGES_REQUESTED' => '待修改',
      'REVOKED' => '已撤销',
      _ => status,
    };
