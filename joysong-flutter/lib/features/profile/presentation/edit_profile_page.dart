import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/files/app_file_picker.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/transient_message.dart';
import 'package:joysong_flutter/features/profile/domain/profile_models.dart';
import 'package:joysong_flutter/features/profile/presentation/profile_controller.dart';
import 'package:joysong_flutter/features/profile/presentation/avatar_crop_page.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';

class EditProfilePage extends StatefulWidget {
  const EditProfilePage({
    required this.controller,
    this.socialRepository,
    super.key,
  });

  final ProfileController controller;
  final SocialRepository? socialRepository;

  @override
  State<EditProfilePage> createState() => _EditProfilePageState();
}

class _EditProfilePageState extends State<EditProfilePage> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nicknameController;
  late final TextEditingController _cityController;
  late final TextEditingController _bioController;
  late ProfileGender _gender;
  DateTime? _birthday;
  late String _avatarUrl;
  Uint8List? _avatarBytes;
  bool _uploadingAvatar = false;

  @override
  void initState() {
    super.initState();
    final user = widget.controller.user;
    _nicknameController = TextEditingController(text: user?.nickname ?? '');
    _cityController = TextEditingController(text: user?.city ?? '');
    _bioController = TextEditingController(text: user?.bio ?? '');
    _gender = ProfileGender.fromWireValue(user?.gender ?? '');
    _birthday = user?.birthday;
    _avatarUrl = user?.avatar ?? '';
  }

  @override
  void dispose() {
    _nicknameController.dispose();
    _cityController.dispose();
    _bioController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        final controller = widget.controller;
        return Scaffold(
          appBar:
              AppBar(title: Text(context.localized('编辑资料', 'Edit profile'))),
          body: SafeArea(
            child: Form(
              key: _formKey,
              child: ListView(
                padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
                children: [
                  Center(
                    child: GestureDetector(
                      onTap: widget.socialRepository == null || _uploadingAvatar
                          ? null
                          : _pickAvatar,
                      child: SizedBox.square(
                        dimension: 92,
                        child: Stack(
                          clipBehavior: Clip.none,
                          children: [
                            Positioned.fill(
                              child: Center(
                                child: _AvatarPreview(
                                  url: _avatarUrl,
                                  bytes: _avatarBytes,
                                ),
                              ),
                            ),
                            Positioned(
                              right: 0,
                              bottom: 0,
                              child: CircleAvatar(
                                radius: 16,
                                child: _uploadingAvatar
                                    ? const Padding(
                                        padding: EdgeInsets.all(7),
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                        ),
                                      )
                                    : const Icon(
                                        Icons.camera_alt_outlined,
                                        size: 17,
                                      ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    context.localized(
                      '点击头像选择图片并裁剪。',
                      'Tap the avatar to select and crop a photo.',
                    ),
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                  ),
                  const SizedBox(height: 24),
                  TextFormField(
                    key: const Key('profile-nickname'),
                    controller: _nicknameController,
                    maxLength: 100,
                    textInputAction: TextInputAction.next,
                    decoration: InputDecoration(
                      labelText: context.localized('昵称', 'Nickname'),
                    ),
                    validator: (value) => (value ?? '').trim().isEmpty
                        ? context.localized('请输入昵称', 'Enter a nickname')
                        : null,
                  ),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<ProfileGender>(
                        key: const Key('profile-gender'),
                        initialValue: _gender,
                        isExpanded: true,
                        borderRadius: BorderRadius.circular(12),
                        menuMaxHeight: 320,
                        dropdownColor: Theme.of(context).colorScheme.surface,
                        decoration: InputDecoration(
                          labelText: context.localized('性别', 'Gender'),
                        ),
                        items: [
                          for (final gender in ProfileGender.values)
                            DropdownMenuItem(
                              value: gender,
                              child: Text(_genderLabel(context, gender)),
                            ),
                        ],
                        onChanged: controller.isSaving
                            ? null
                            : (gender) =>
                                setState(() => _gender = gender ?? _gender),
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    key: const Key('profile-city'),
                    controller: _cityController,
                    maxLength: 100,
                    textInputAction: TextInputAction.next,
                    decoration: InputDecoration(
                      labelText: context.localized('所在城市', 'City'),
                    ),
                  ),
                  const SizedBox(height: 12),
                  _BirthdayField(
                    value: _birthday,
                    enabled: !controller.isSaving,
                    onChanged: (value) => setState(() => _birthday = value),
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    key: const Key('profile-bio'),
                    controller: _bioController,
                    minLines: 3,
                    maxLines: 6,
                    maxLength: 500,
                    decoration: InputDecoration(
                      labelText: context.localized('个人简介', 'Bio'),
                      alignLabelWithHint: true,
                    ),
                  ),
                  if (controller.errorMessage != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      controller.errorMessage!,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ],
                  const SizedBox(height: 20),
                  SizedBox(
                    height: 48,
                    child: FilledButton(
                      key: const Key('save-profile'),
                      onPressed: controller.isSaving ? null : _save,
                      child: controller.isSaving
                          ? const SizedBox.square(
                              dimension: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : Text(context.localized('保存', 'Save')),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Future<void> _save() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    final success = await widget.controller.save(
      ProfileUpdate(
        nickname: _nicknameController.text,
        gender: _gender,
        city: _cityController.text,
        bio: _bioController.text,
        birthday: _birthday,
        avatar: _avatarUrl,
      ),
    );
    if (!mounted || !success) return;
    showTransientMessage(
      context,
      context.localized('个人资料已更新', 'Profile updated'),
    );
    Navigator.of(context).pop(true);
  }

  Future<void> _pickAvatar() async {
    final selected = await const AppFilePicker().pickImage();
    if (selected == null || !mounted) return;
    final cropped = await Navigator.of(context).push<Uint8List>(
      MaterialPageRoute(builder: (_) => AvatarCropPage(bytes: selected.bytes)),
    );
    if (cropped == null || !mounted) return;
    setState(() => _uploadingAvatar = true);
    String? url;
    await for (final progress in widget.socialRepository!.uploadPublicMedia(
      PublicMediaDraft(
        bytes: cropped,
        fileName: 'avatar.png',
        mimeType: 'image/png',
        purpose: PublicMediaPurpose.avatar,
      ),
    )) {
      if (progress.stage == UploadStage.complete) url = progress.url;
    }
    if (!mounted) return;
    setState(() {
      _uploadingAvatar = false;
      if (url != null) {
        _avatarUrl = url;
        _avatarBytes = cropped;
      }
    });
    if (url == null) {
      showTransientMessage(
        context,
        context.localized('头像上传失败', 'Avatar upload failed'),
      );
    }
  }
}

class _AvatarPreview extends StatelessWidget {
  const _AvatarPreview({required this.url, this.bytes});

  final String url;
  final Uint8List? bytes;

  @override
  Widget build(BuildContext context) {
    final fallback = CircleAvatar(
      radius: 42,
      backgroundColor: Theme.of(context).colorScheme.primaryContainer,
      foregroundColor: Theme.of(context).colorScheme.onPrimaryContainer,
      child: const Icon(Icons.person_outline_rounded, size: 42),
    );
    final ImageProvider<Object>? image;
    if (bytes != null) {
      image = MemoryImage(bytes!);
    } else if (url.trim().isNotEmpty) {
      image = NetworkImage(url);
    } else {
      image = null;
    }
    if (image == null) return fallback;
    return CircleAvatar(
      radius: 42,
      backgroundColor: Theme.of(context).colorScheme.primaryContainer,
      foregroundImage: image,
      onForegroundImageError: (_, __) {},
      child: const Icon(Icons.person_outline_rounded, size: 42),
    );
  }
}

class _BirthdayField extends StatelessWidget {
  const _BirthdayField({
    required this.value,
    required this.enabled,
    required this.onChanged,
  });

  final DateTime? value;
  final bool enabled;
  final ValueChanged<DateTime?> onChanged;

  @override
  Widget build(BuildContext context) {
    final text = value == null
        ? context.localized('未设置', 'Not set')
        : '${value!.year}-${value!.month.toString().padLeft(2, '0')}-'
            '${value!.day.toString().padLeft(2, '0')}';
    return Semantics(
      button: true,
      label: '${context.localized('生日', 'Birthday')}, $text',
      child: ListTile(
        key: const Key('profile-birthday'),
        contentPadding: const EdgeInsets.symmetric(horizontal: 12),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        tileColor: Theme.of(context).colorScheme.surfaceContainerLow,
        title: Text(context.localized('生日', 'Birthday')),
        subtitle: Text(text),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (value != null)
              IconButton(
                tooltip: context.localized('清除生日', 'Clear birthday'),
                onPressed: enabled ? () => onChanged(null) : null,
                icon: const Icon(Icons.close_rounded),
              ),
            const Icon(Icons.calendar_month_outlined),
          ],
        ),
        onTap: enabled
            ? () async {
                final now = DateTime.now();
                final selected = await showDatePicker(
                  context: context,
                  initialDate: value ?? DateTime(now.year - 25),
                  firstDate: DateTime(1900),
                  lastDate: now,
                  helpText: context.localized('选择生日', 'Select birthday'),
                );
                if (selected != null) onChanged(selected);
              }
            : null,
      ),
    );
  }
}

String _genderLabel(BuildContext context, ProfileGender gender) =>
    switch (gender) {
      ProfileGender.unspecified => context.localized('不公开', 'Private'),
      ProfileGender.male => context.localized('男', 'Male'),
      ProfileGender.female => context.localized('女', 'Female'),
      ProfileGender.other => context.localized('其他', 'Other'),
    };
