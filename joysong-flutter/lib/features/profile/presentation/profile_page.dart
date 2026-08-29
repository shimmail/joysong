import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/files/app_file_picker.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/routing/app_router.dart';
import 'package:joysong_flutter/core/transient_message.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/consultant_orders/domain/consultant_orders_repository.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';
import 'package:joysong_flutter/features/identity/presentation/institution_relationships_page.dart';
import 'package:joysong_flutter/features/profile/domain/profile_models.dart';
import 'package:joysong_flutter/features/profile/domain/profile_repository.dart';
import 'package:joysong_flutter/features/profile/presentation/edit_profile_page.dart';
import 'package:joysong_flutter/features/profile/presentation/profile_controller.dart';
import 'package:joysong_flutter/features/profile/presentation/profile_support_pages.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';
import 'package:joysong_flutter/features/professional_management/data/professional_repository.dart';

class ProfilePage extends StatefulWidget {
  const ProfilePage({
    this.profileRepository,
    this.identityRepository,
    this.discoverRepository,
    this.socialRepository,
    this.professionalRepository,
    this.consultantOrdersRepository,
    this.onOpenConsultantOrderServiceConversation,
    this.onOrders,
    this.onWallet,
    this.onDiaries,
    this.onJourney,
    this.onCustomerService,
    this.onAccountSecurity,
    this.onOpenFavorite,
    this.onSwitchAccount,
    this.onLogout,
    super.key,
  });

  final ProfileRepository? profileRepository;
  final IdentityRepository? identityRepository;
  final DiscoverRepository? discoverRepository;
  final SocialRepository? socialRepository;
  final ProfessionalRepository? professionalRepository;
  final ConsultantOrdersRepository? consultantOrdersRepository;
  final ConsultantOrderServiceConversationLauncher?
      onOpenConsultantOrderServiceConversation;
  final VoidCallback? onOrders;
  final VoidCallback? onWallet;
  final VoidCallback? onDiaries;
  final VoidCallback? onJourney;
  final VoidCallback? onCustomerService;
  final VoidCallback? onAccountSecurity;
  final Future<void> Function(FavoriteItem item)? onOpenFavorite;
  final Future<void> Function(BuildContext context)? onSwitchAccount;
  final Future<void> Function()? onLogout;

  @override
  State<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<ProfilePage> {
  ProfileController? _controller;

  @override
  void initState() {
    super.initState();
    _createController();
  }

  @override
  void didUpdateWidget(covariant ProfilePage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.profileRepository != widget.profileRepository) {
      _createController();
    }
  }

  void _createController() {
    _controller?.dispose();
    final repository = widget.profileRepository;
    _controller = repository == null ? null : ProfileController(repository);
    if (_controller != null) unawaited(_controller!.load());
  }

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = _controller;
    if (controller == null) return _content(null);
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) => _content(controller),
    );
  }

  Widget _content(ProfileController? controller) {
    return RefreshIndicator(
      onRefresh: controller == null
          ? () async {}
          : () => controller.load(refresh: true),
      child: ListView(
        key: const PageStorageKey<String>('profile'),
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
        children: [
          _UserHeaderCard(
            controller: controller,
            onEdit: controller?.user == null ? null : _openEditProfile,
          ),
          const SizedBox(height: 12),
          _ServiceGrid(
            onOrders: widget.onOrders,
            onDiaries: widget.onDiaries,
            onJourney: widget.onJourney,
            onFavorites:
                widget.socialRepository == null ? null : _openFavorites,
          ),
          const SizedBox(height: 12),
          _MenuCard(
            items: [
              _MenuItem(
                icon: Icons.account_balance_wallet_outlined,
                title: context.localized('钱包', 'Wallet'),
                subtitle: context.localized(
                    '查看专业收益与流水', 'View professional earnings and ledger'),
                onTap: widget.onWallet,
              ),
              _MenuItem(
                icon: Icons.support_agent_outlined,
                title: context.localized('联系客服', 'Customer service'),
                subtitle:
                    context.localized('咨询服务与订单问题', 'Service and order support'),
                onTap: widget.onCustomerService,
              ),
              _MenuItem(
                icon: Icons.help_center_outlined,
                title: context.localized('帮助与反馈', 'Help & feedback'),
                subtitle:
                    context.localized('常见问题和客服留言', 'FAQs and customer support'),
                onTap: _openHelp,
              ),
              if (widget.identityRepository != null) ...[
                _MenuItem(
                  icon: Icons.verified_user_outlined,
                  title: context.localized('身份认证', 'Identity verification'),
                  subtitle: context.localized(
                    '医生、医美顾问与机构法人认证',
                    'Doctor, medical aesthetics consultant and institution verification',
                  ),
                  onTap: _openIdentityCenter,
                ),
              ],
              if (widget.identityRepository != null &&
                  widget.discoverRepository != null)
                _MenuItem(
                  icon: Icons.admin_panel_settings_outlined,
                  title: context.localized('专业管理', 'Professional management'),
                  subtitle: context.localized('进入时实时校验专业能力',
                      'Professional access is verified on entry'),
                  onTap: _openManagementCenter,
                ),
              if (widget.identityRepository != null)
                _MenuItem(
                  icon: Icons.account_tree_outlined,
                  title: context.localized('机构关系', 'Institution relationships'),
                  subtitle: context.localized(
                    '查看机构归属与关系申请',
                    'View affiliations and relationship requests',
                  ),
                  onTap: _openInstitutionRelationships,
                ),
            ],
          ),
          const SizedBox(height: 12),
          _MenuCard(
            items: [
              _MenuItem(
                icon: Icons.settings_outlined,
                title: context.localized('设置', 'Settings'),
                subtitle: context.localized(
                    '外观、通知与本地缓存', 'Appearance, notifications and local cache'),
                onTap: () =>
                    Navigator.of(context).pushNamed(AppRoutes.settings),
              ),
              _MenuItem(
                icon: Icons.info_outline,
                title: context.localized('关于我们', 'About us'),
                onTap: _openAbout,
              ),
              _MenuItem(
                icon: Icons.lock_outline,
                title: context.localized('账号与安全', 'Account & security'),
                subtitle: context.localized(
                    '手机号、密码与账号注销', 'Phone, password and account deletion'),
                onTap: widget.onAccountSecurity,
              ),
            ],
          ),
          const SizedBox(height: 12),
          if (widget.onSwitchAccount != null) ...[
            SizedBox(
              height: 48,
              child: FilledButton.tonalIcon(
                key: const Key('switch-account-button'),
                onPressed: () => widget.onSwitchAccount?.call(context),
                style: FilledButton.styleFrom(
                  backgroundColor: Theme.of(context).colorScheme.surface,
                  foregroundColor: Theme.of(context).colorScheme.onSurface,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                icon: const Icon(Icons.switch_account_rounded, size: 20),
                label: Text(context.localized('切换账号', 'Switch account')),
              ),
            ),
            const SizedBox(height: 10),
          ],
          SizedBox(
            height: 48,
            child: FilledButton.tonalIcon(
              key: const Key('logout-button'),
              onPressed: widget.onLogout == null ? null : _confirmLogout,
              style: FilledButton.styleFrom(
                backgroundColor: Theme.of(context).colorScheme.surface,
                foregroundColor: Theme.of(context).colorScheme.onSurface,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
              icon: const Icon(Icons.logout_rounded, size: 20),
              label: Text(context.localized('退出登录', 'Sign out')),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _confirmLogout() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(context.localized('退出登录', 'Sign out')),
        content: Text(context.localized(
          '确定要退出当前账号吗？已记住的密码不会自动删除。',
          'Sign out of this account? Your saved password will not be deleted automatically.',
        )),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(context.localized('取消', 'Cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(context.localized('确定', 'Confirm')),
          ),
        ],
      ),
    );
    if (confirmed == true) await widget.onLogout?.call();
  }

  Future<void> _openEditProfile() async {
    final controller = _controller;
    if (controller == null || controller.user == null) return;
    await Navigator.of(context).push<bool>(
      MaterialPageRoute(
          builder: (_) => EditProfilePage(
                controller: controller,
                socialRepository: widget.socialRepository,
              )),
    );
  }

  void _openFavorites() {
    Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => FavoritesPage(
          repository: widget.socialRepository!,
          onOpen: widget.onOpenFavorite,
        ),
      ),
    );
  }

  void _openHelp() {
    Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => HelpAndFeedbackPage(
          onCustomerService: widget.onCustomerService,
        ),
      ),
    );
  }

  void _openAbout() {
    Navigator.of(context).push<void>(
      MaterialPageRoute(builder: (_) => const AboutJoysongPage()),
    );
  }

  void _openIdentityCenter() {
    Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => IdentityCenterPage(
          repository: widget.identityRepository!,
        ),
      ),
    );
  }

  void _openManagementCenter() {
    Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => ManagementCenterPage(
          repository: widget.identityRepository!,
          discoverRepository: widget.discoverRepository!,
          consultantOrdersRepository: widget.consultantOrdersRepository,
          onOpenConsultantOrderServiceConversation:
              widget.onOpenConsultantOrderServiceConversation,
          institutionImagePicker:
              widget.socialRepository == null ? null : _pickInstitutionImage,
          doctorImagePicker:
              widget.socialRepository == null ? null : _pickDoctorImage,
          professionalRepository: widget.professionalRepository,
        ),
      ),
    );
  }

  Future<void> _openInstitutionRelationships() async {
    final repository = widget.identityRepository!;
    late final ManagementContext managementContext;
    try {
      managementContext = await repository.loadManagementContext();
    } catch (_) {
      if (!mounted) return;
      showTransientMessage(
        context,
        context.localized(
          '机构关系权限加载失败，请重试',
          'Unable to load institution relationship access. Try again.',
        ),
      );
      return;
    }
    if (!mounted) return;

    final scopes = <InstitutionRelationshipScope>[
      if (managementContext.activeRoles.contains(IdentityRoleType.doctor.code))
        InstitutionRelationshipScope.doctor,
      if (managementContext.activeRoles
          .contains(IdentityRoleType.consultant.code))
        InstitutionRelationshipScope.consultant,
      if (managementContext.activeRoles.contains(
        IdentityRoleType.institutionLegalRepresentative.code,
      ))
        InstitutionRelationshipScope.legalRepresentative,
    ];
    if (scopes.isEmpty) {
      showTransientMessage(
        context,
        context.localized(
          '当前没有可用的专业身份',
          'No eligible professional identity is available.',
        ),
      );
      return;
    }

    var scope = scopes.first;
    if (scopes.length > 1) {
      final selected = await showDialog<InstitutionRelationshipScope>(
        context: context,
        builder: (dialogContext) => SimpleDialog(
          key: const Key('institution-relationship-role-picker'),
          title: Text(
            dialogContext.localized('选择专业身份', 'Choose professional role'),
          ),
          children: [
            for (final option in scopes)
              SimpleDialogOption(
                key: Key(
                  'institution-relationship-role-${_relationshipScopeCode(option)}',
                ),
                onPressed: () => Navigator.of(dialogContext).pop(option),
                child: Text(_relationshipScopeLabel(dialogContext, option)),
              ),
          ],
        ),
      );
      if (!mounted || selected == null) return;
      scope = selected;
    }

    Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => InstitutionRelationshipsPage(
          repository: repository,
          scope: scope,
        ),
      ),
    );
  }

  Future<String?> _pickInstitutionImage() =>
      _pickPublicProfileImage(PublicMediaPurpose.institutionProfile);

  Future<String?> _pickDoctorImage() =>
      _pickPublicProfileImage(PublicMediaPurpose.doctorProfile);

  Future<String?> _pickPublicProfileImage(PublicMediaPurpose purpose) async {
    final selected = await const AppFilePicker().pickImage();
    if (selected == null) return null;
    String? url;
    await for (final progress in widget.socialRepository!.uploadPublicMedia(
      PublicMediaDraft(
        bytes: selected.bytes,
        fileName: selected.fileName,
        mimeType: selected.mimeType,
        purpose: purpose,
        privacy: MediaPrivacy.publicContent,
      ),
    )) {
      if (progress.stage == UploadStage.failed) {
        if (mounted) {
          showTransientMessage(context, progress.message ?? '图片上传失败');
        }
        return null;
      }
      if (progress.stage == UploadStage.complete) {
        url = progress.url?.trim();
      }
    }
    return url?.isEmpty == true ? null : url;
  }
}

String _relationshipScopeCode(InstitutionRelationshipScope scope) =>
    switch (scope) {
      InstitutionRelationshipScope.doctor => 'doctor',
      InstitutionRelationshipScope.consultant => 'consultant',
      InstitutionRelationshipScope.legalRepresentative =>
        'legal-representative',
    };

String _relationshipScopeLabel(
  BuildContext context,
  InstitutionRelationshipScope scope,
) =>
    switch (scope) {
      InstitutionRelationshipScope.doctor => context.localized('医生', 'Doctor'),
      InstitutionRelationshipScope.consultant =>
        context.localized('顾问', 'Consultant'),
      InstitutionRelationshipScope.legalRepresentative =>
        context.localized('机构法人', 'Legal representative'),
    };

class _UserHeaderCard extends StatelessWidget {
  const _UserHeaderCard({required this.controller, required this.onEdit});

  final ProfileController? controller;
  final VoidCallback? onEdit;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final user = controller?.user;
    final loading =
        controller?.status == ProfileLoadStatus.loading && user == null;
    final failed =
        controller?.status == ProfileLoadStatus.failure && user == null;
    return Card(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          children: [
            _ProfileAvatar(user: user),
            const SizedBox(height: 12),
            if (loading)
              const SizedBox(
                height: 28,
                width: 28,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            else ...[
              Text(
                profileDisplayName(user),
                style: theme.textTheme.headlineMedium,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 3),
              Text(
                failed
                    ? controller?.errorMessage ??
                        context.localized('个人资料加载失败', 'Failed to load profile')
                    : maskedProfilePhone(user?.phone),
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: failed ? colors.error : colors.onSurfaceVariant,
                ),
                textAlign: TextAlign.center,
              ),
            ],
            if (failed) ...[
              const SizedBox(height: 10),
              TextButton.icon(
                onPressed: () => controller?.load(refresh: true),
                icon: const Icon(Icons.refresh_rounded),
                label: Text(context.localized('重试', 'Retry')),
              ),
            ] else ...[
              const SizedBox(height: 12),
              SizedBox(
                height: 36,
                child: FilledButton.icon(
                  key: const Key('edit-profile-button'),
                  onPressed: onEdit,
                  style: FilledButton.styleFrom(
                    minimumSize: const Size(0, 36),
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    shape: const StadiumBorder(),
                  ),
                  icon: const Icon(Icons.edit_outlined, size: 16),
                  label: Text(context.localized('编辑资料', 'Edit profile')),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _ProfileAvatar extends StatelessWidget {
  const _ProfileAvatar({required this.user});

  final AuthUser? user;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return CircleAvatar(
      radius: 32,
      backgroundColor: colors.surfaceContainerLow,
      foregroundColor: colors.primary,
      foregroundImage: user?.avatar.trim().isEmpty ?? true
          ? null
          : NetworkImage(user!.avatar),
      onForegroundImageError:
          user?.avatar.trim().isEmpty ?? true ? null : (_, __) {},
      child: const Icon(Icons.person_outline_rounded, size: 34),
    );
  }
}

class _ServiceGrid extends StatelessWidget {
  const _ServiceGrid({
    this.onOrders,
    this.onDiaries,
    this.onJourney,
    this.onFavorites,
  });

  final VoidCallback? onOrders;
  final VoidCallback? onDiaries;
  final VoidCallback? onJourney;
  final VoidCallback? onFavorites;

  @override
  Widget build(BuildContext context) {
    final items = [
      (
        Icons.receipt_long_outlined,
        context.localized('我的订单', 'My orders'),
        onOrders
      ),
      (
        Icons.article_outlined,
        context.localized('我的日记', 'My diaries'),
        onDiaries
      ),
      (
        Icons.travel_explore_outlined,
        context.localized('医美旅程', 'Aesthetic journey'),
        onJourney
      ),
      (
        Icons.favorite_border_rounded,
        context.localized('我的收藏', 'Favorites'),
        onFavorites
      ),
    ];
    return Card(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(context.localized('我的服务', 'My services'),
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            GridView.builder(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              itemCount: items.length,
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 2,
                childAspectRatio: 2.1,
                mainAxisSpacing: 4,
                crossAxisSpacing: 4,
              ),
              itemBuilder: (context, index) {
                final (icon, label, onTap) = items[index];
                return _ServiceItem(icon: icon, label: label, onTap: onTap);
              },
            ),
          ],
        ),
      ),
    );
  }
}

class _ServiceItem extends StatelessWidget {
  const _ServiceItem({required this.icon, required this.label, this.onTap});

  final IconData icon;
  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Semantics(
      button: onTap != null,
      enabled: onTap != null,
      label: label,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: colors.primaryContainer,
                borderRadius: BorderRadius.circular(12),
              ),
              alignment: Alignment.center,
              child: Icon(icon, size: 24, color: colors.onPrimaryContainer),
            ),
            const SizedBox(height: 7),
            Text(label, style: theme.textTheme.labelLarge),
          ],
        ),
      ),
    );
  }
}

class _MenuItem {
  const _MenuItem({
    required this.icon,
    required this.title,
    this.subtitle = '',
    this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback? onTap;
}

class _MenuCard extends StatelessWidget {
  const _MenuCard({required this.items});

  final List<_MenuItem> items;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Card(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Column(
          children: [
            for (var index = 0; index < items.length; index++) ...[
              _MenuListTile(item: items[index]),
              if (index < items.length - 1)
                Divider(color: colors.surfaceContainerLow),
            ],
          ],
        ),
      ),
    );
  }
}

class _MenuListTile extends StatelessWidget {
  const _MenuListTile({required this.item});

  final _MenuItem item;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Semantics(
      button: item.onTap != null,
      enabled: item.onTap != null,
      child: InkWell(
        onTap: item.onTap,
        borderRadius: BorderRadius.circular(10),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 14),
          child: Row(
            children: [
              Icon(item.icon, size: 22, color: colors.primary),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(item.title, style: theme.textTheme.titleSmall),
                    if (item.subtitle.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        item.subtitle,
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: colors.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              if (item.onTap != null)
                Icon(
                  Icons.chevron_right_rounded,
                  size: 18,
                  color: colors.onSurfaceVariant,
                ),
            ],
          ),
        ),
      ),
    );
  }
}
