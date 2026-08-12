import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/core/files/app_file_picker.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/account_security/data/account_security_api.dart';
import 'package:joysong_flutter/features/account_security/data/account_security_repository_impl.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_controller.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_page.dart';
import 'package:joysong_flutter/features/agent/data/agent_remote_data_source.dart';
import 'package:joysong_flutter/features/agent/data/agent_repository_impl.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_controller.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_page.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_plan_controller.dart';
import 'package:joysong_flutter/features/booking/data/booking_remote_data_source.dart';
import 'package:joysong_flutter/features/booking/data/booking_repository_impl.dart';
import 'package:joysong_flutter/features/booking/domain/booking_repository.dart';
import 'package:joysong_flutter/features/booking/presentation/booking_controller.dart';
import 'package:joysong_flutter/features/booking/presentation/booking_page.dart';
import 'package:joysong_flutter/features/discover/data/discover_repository_impl.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_page.dart';
import 'package:joysong_flutter/features/home/data/home_repository_impl.dart';
import 'package:joysong_flutter/features/home/domain/home_models.dart';
import 'package:joysong_flutter/features/home/domain/home_repository.dart';
import 'package:joysong_flutter/features/home/presentation/home_page.dart';
import 'package:joysong_flutter/features/identity/data/identity_repository_impl.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/messaging/data/messaging_remote_data_source.dart';
import 'package:joysong_flutter/features/messaging/data/messaging_repository_impl.dart';
import 'package:joysong_flutter/features/messaging/data/secure_messaging_preferences_store.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_repository.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_controllers.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_pages.dart';
import 'package:joysong_flutter/features/orders/data/orders_remote_data_source.dart';
import 'package:joysong_flutter/features/orders/data/orders_repository_impl.dart';
import 'package:joysong_flutter/features/orders/domain/order_models.dart';
import 'package:joysong_flutter/features/orders/domain/orders_repository.dart';
import 'package:joysong_flutter/features/orders/presentation/order_detail_page.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_controller.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_page.dart';
import 'package:joysong_flutter/features/profile/presentation/profile_page.dart';
import 'package:joysong_flutter/features/professional_management/data/professional_repository.dart';
import 'package:joysong_flutter/features/profile/data/profile_repository_impl.dart';
import 'package:joysong_flutter/features/profile/domain/profile_repository.dart';
import 'package:joysong_flutter/features/social/data/api_public_media_uploader.dart';
import 'package:joysong_flutter/features/social/data/social_remote_data_source.dart';
import 'package:joysong_flutter/features/social/data/social_repository_impl.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';
import 'package:joysong_flutter/features/social/presentation/diary_detail_page.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';
import 'package:joysong_flutter/features/social/presentation/social_page.dart';
import 'package:joysong_flutter/features/social/presentation/public_user_page.dart';
import 'package:joysong_flutter/features/wallet/data/wallet_remote_data_source.dart';
import 'package:joysong_flutter/features/wallet/data/wallet_repository_impl.dart';
import 'package:joysong_flutter/features/wallet/domain/wallet_repository.dart';
import 'package:joysong_flutter/features/wallet/presentation/wallet_controller.dart';
import 'package:joysong_flutter/features/wallet/presentation/wallet_page.dart';

class AppShell extends StatefulWidget {
  const AppShell({
    required this.agentConfig,
    this.apiClient,
    this.allowPreviewData = false,
    this.currentUserId = '',
    this.onSwitchAccount,
    this.onLogout,
    super.key,
  });

  final AgentConfig agentConfig;
  final ApiClient? apiClient;
  final bool allowPreviewData;
  final String currentUserId;
  final Future<void> Function(BuildContext context)? onSwitchAccount;
  final Future<void> Function()? onLogout;

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  final GlobalKey<NavigatorState> _contentNavigatorKey =
      GlobalKey<NavigatorState>();
  int _selectedIndex = 0;
  DiscoverContentType _discoverType = DiscoverContentType.all;
  HomeRepository? _homeRepository;
  DiscoverRepository? _discoverRepository;
  IdentityRepository? _identityRepository;
  ProfileRepository? _profileRepository;
  BookingRepository? _bookingRepository;
  OrdersRepository? _ordersRepository;
  SocialRepository? _socialRepository;
  MessagingRepository? _messagingRepository;
  WalletRepository? _walletRepository;
  OrdersController? _ordersController;
  SocialController? _socialController;
  NotificationController? _notificationController;
  MessagingHubController? _messagingController;
  AgentChatController? _agentChatController;
  AgentPlanController? _agentPlanController;
  WalletController? _walletController;
  int _unreadNotificationCount = 0;

  NavigatorState get _contentNavigator =>
      _contentNavigatorKey.currentState ?? Navigator.of(context);

  @override
  void initState() {
    super.initState();
    _createDependencies();
  }

  @override
  void didUpdateWidget(covariant AppShell oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.apiClient != widget.apiClient ||
        oldWidget.agentConfig.recentMessageLimit !=
            widget.agentConfig.recentMessageLimit ||
        oldWidget.currentUserId != widget.currentUserId) {
      _createDependencies();
    }
  }

  void _createDependencies() {
    _disposeControllers();
    _unreadNotificationCount = 0;
    final apiClient = widget.apiClient;
    if (apiClient == null) {
      _homeRepository = null;
      _discoverRepository = null;
      _identityRepository = null;
      _profileRepository = null;
      _bookingRepository = null;
      _ordersRepository = null;
      _socialRepository = null;
      _messagingRepository = null;
      _walletRepository = null;
      return;
    }
    _homeRepository = ApiHomeRepository(apiClient);
    _discoverRepository = ApiDiscoverRepository(apiClient);
    _identityRepository = ApiIdentityRepository(apiClient);
    _profileRepository = ApiProfileRepository(apiClient);
    _bookingRepository = BookingRepositoryImpl(
      ApiBookingRemoteDataSource(apiClient),
    );
    _ordersRepository =
        OrdersRepositoryImpl(ApiOrdersRemoteDataSource(apiClient));
    _ordersController = OrdersController(_ordersRepository!);
    _socialRepository = SocialRepositoryImpl(
      remoteDataSource: ApiSocialRemoteDataSource(apiClient),
      imagePreprocessor: const PassthroughPublicImagePreprocessor(),
      mediaUploader: ApiPublicMediaUploader(apiClient),
    );
    _socialController = SocialController(
      _socialRepository!,
      currentUserId: widget.currentUserId,
    );
    _messagingRepository = MessagingRepositoryImpl(
      ApiMessagingRemoteDataSource(apiClient),
    );
    _walletRepository = WalletRepositoryImpl(ApiWalletRemoteDataSource(apiClient));
    _walletController = WalletController(_walletRepository!);
    _notificationController = NotificationController(_messagingRepository!);
    _notificationController!.addListener(_handleNotificationStateChanged);
    unawaited(_notificationController!.refresh());
    _messagingController = MessagingHubController(
      _messagingRepository!,
      currentUserId: widget.currentUserId,
      preferencesStore: SecureMessagingPreferencesStore(),
      peerLoader: (userId) async {
        final profile = await _socialRepository!.getPublicUserProfile(userId);
        return MessagingPeer(
          id: profile.id,
          name: profile.nickname,
          avatar: profile.avatar,
        );
      },
    );

    final agentRepository = AgentRepositoryImpl(
      ApiAgentRemoteDataSource(apiClient: apiClient),
    );
    _agentChatController = AgentChatController(
      repository: agentRepository,
      recentMessageLimit: widget.agentConfig.recentMessageLimit,
    );
    _agentPlanController = AgentPlanController(agentRepository);
  }

  void _disposeControllers() {
    _ordersController?.dispose();
    _socialController?.dispose();
    _notificationController?.removeListener(_handleNotificationStateChanged);
    _notificationController?.dispose();
    _messagingController?.dispose();
    _agentChatController?.dispose();
    _agentPlanController?.dispose();
    _walletController?.dispose();
    _ordersController = null;
    _socialController = null;
    _notificationController = null;
    _messagingController = null;
    _agentChatController = null;
    _agentPlanController = null;
    _walletController = null;
  }

  void _handleNotificationStateChanged() {
    final nextCount = _notificationController?.unreadCount ?? 0;
    if (mounted && nextCount != _unreadNotificationCount) {
      setState(() => _unreadNotificationCount = nextCount);
    }
  }

  @override
  void dispose() {
    _disposeControllers();
    super.dispose();
  }

  List<Widget> get _pages => [
        HomePage(
          repository: _homeRepository,
          profileRepository: _profileRepository,
          allowPreviewData: widget.allowPreviewData,
          onSearch: () => _openDiscover(DiscoverContentType.all),
          unreadNotificationCount: _unreadNotificationCount,
          onNotifications:
              _messagingController == null ? null : _openMessagesTab,
          onOpenItem: _openHomeItem,
          onViewAll: _openHomeSection,
        ),
        DiscoverPage(
          key: ValueKey(_discoverType),
          repository: _discoverRepository,
          initialType: _discoverType,
          onBookProject: _bookingRepository == null ? null : _openBooking,
          socialController: _socialController,
          onOpenUser: _openPublicUser,
          onConsultDoctor: _openDoctorChat,
          onOpenAi: _openAiChat,
        ),
        if (_messagingController != null)
          MessagingCenterPage(
            controller: _messagingController!,
            currentUserId: widget.currentUserId,
            onOpenDm: (conversation) => _openDmThread(
              conversation,
              title: _messagingController?.peerFor(conversation)?.name,
            ),
            onOpenCustomerService: _openCustomerServiceThread,
            onOpenUser: _openPublicUser,
            onOpenAi: _openAiChat,
            onOpenSystemMessages: () => _openNotificationCategory(false),
            onOpenActivityMessages: () => _openNotificationCategory(true),
          )
        else if (_agentChatController != null && _agentPlanController != null)
          AgentChatPage(
            chatController: _agentChatController!,
            planController: _agentPlanController!,
            onOpenCatalogItem: _openAgentCatalogItem,
          )
        else
          const SizedBox.shrink(),
        ProfilePage(
          profileRepository: _profileRepository,
          identityRepository: _identityRepository,
          discoverRepository: _discoverRepository,
          socialRepository: _socialRepository,
          professionalRepository: widget.apiClient == null
              ? null
              : ProfessionalRepository(widget.apiClient!),
          onOrders: _ordersController == null ? null : _openOrders,
          onWallet: _walletController == null ? null : _openWallet,
          onDiaries: _socialController == null ? null : _openSocial,
          onJourney: _showJourneyComingSoon,
          onCustomerService:
              _messagingController == null ? null : _openCustomerService,
          onAccountSecurity:
              widget.apiClient == null ? null : _openAccountSecurity,
          onOpenFavorite: _discoverRepository == null ? null : _openFavorite,
          onSwitchAccount: widget.onSwitchAccount,
          onLogout: widget.onLogout,
        ),
      ];

  void _openDiscover(DiscoverContentType type) {
    setState(() {
      _discoverType = type;
      _selectedIndex = 1;
    });
  }

  void _openHomeSection(HomeSectionKind kind) {
    final type = switch (kind) {
      HomeSectionKind.hotProject ||
      HomeSectionKind.recommendedInstitutionProject =>
        DiscoverContentType.project,
      HomeSectionKind.expertArticle => DiscoverContentType.article,
      HomeSectionKind.userDiary => DiscoverContentType.diary,
      HomeSectionKind.institution => DiscoverContentType.institution,
      HomeSectionKind.doctor => DiscoverContentType.doctor,
      HomeSectionKind.banner => DiscoverContentType.all,
    };
    _openDiscover(type);
  }

  void _openHomeItem(HomeContent item) {
    final repository = _discoverRepository;
    if (repository == null || item.kind == HomeSectionKind.banner) {
      return;
    }
    if (item.kind == HomeSectionKind.recommendedInstitutionProject) {
      final institutionId = _text(item.raw['institutionId']);
      final projectId = _text(item.raw['projectId']);
      if (institutionId != null && projectId != null) {
        _contentNavigator.push(
          MaterialPageRoute<void>(
            builder: (_) => DiscoverDetailPage(
              repository: repository,
              type: DiscoverContentType.project,
              id: projectId,
              institutionId: institutionId,
              projectId: projectId,
              onBookProject: _bookingRepository == null ? null : _openBooking,
              socialController: _socialController,
              onOpenUser: _openPublicUser,
              onConsultDoctor: _openDoctorChat,
              onOpenAi: _openAiChat,
            ),
          ),
        );
        return;
      }
    }
    if (item.kind == HomeSectionKind.userDiary) {
      unawaited(_openHomeDiary(item));
      return;
    }
    final type = switch (item.kind) {
      HomeSectionKind.hotProject ||
      HomeSectionKind.recommendedInstitutionProject =>
        DiscoverContentType.project,
      HomeSectionKind.expertArticle => DiscoverContentType.article,
      HomeSectionKind.userDiary => DiscoverContentType.diary,
      HomeSectionKind.institution => DiscoverContentType.institution,
      HomeSectionKind.doctor => DiscoverContentType.doctor,
      HomeSectionKind.banner => DiscoverContentType.all,
    };
    _contentNavigator.push(
      MaterialPageRoute<void>(
        builder: (_) => DiscoverDetailPage(
          repository: repository,
          type: type,
          id: item.id,
          onBookProject: _bookingRepository == null ? null : _openBooking,
          socialController: _socialController,
          onOpenUser: _openPublicUser,
          onConsultDoctor: _openDoctorChat,
          onOpenAi: _openAiChat,
        ),
      ),
    );
  }

  Future<void> _openHomeDiary(HomeContent item) => _openDiaryDetail(item.id);

  void _openDiaryAssociation(
    DiscoverContentType type,
    String id, {
    String institutionId = '',
  }) {
    final repository = _discoverRepository;
    if (repository == null || id.trim().isEmpty) return;
    _contentNavigator.push<void>(MaterialPageRoute(
      builder: (_) => DiscoverDetailPage(
        repository: repository,
        type: type,
        id: id.trim(),
        institutionId:
            type == DiscoverContentType.project && institutionId.isNotEmpty
                ? institutionId
                : null,
        projectId:
            type == DiscoverContentType.project && institutionId.isNotEmpty
                ? id.trim()
                : null,
        onBookProject: _bookingRepository == null ? null : _openBooking,
        socialController: _socialController,
        onOpenUser: _openPublicUser,
        onConsultDoctor: _openDoctorChat,
        onOpenAi: _openAiChat,
      ),
    ));
  }

  Future<void> _openDiaryDetail(String diaryId) async {
    final repository = _discoverRepository;
    final socialController = _socialController;
    if (repository == null || socialController == null) return;
    try {
      final detail = await repository.loadDetail(
        type: DiscoverContentType.diary,
        id: diaryId,
      );
      if (!mounted) return;
      final diary = diaryFromDiscover(detail);
      await _contentNavigator.push<void>(MaterialPageRoute(
        builder: (_) => DiaryDetailPage(
          controller: socialController,
          diary: diary,
          onAuthorTap:
              diary.userId.isEmpty ? null : () => _openPublicUser(diary.userId),
          onProjectTap: (institutionId, projectId) => _openDiaryAssociation(
            DiscoverContentType.project,
            projectId,
            institutionId: institutionId,
          ),
          onDoctorTap: (id) =>
              _openDiaryAssociation(DiscoverContentType.doctor, id),
          onInstitutionTap: (id) =>
              _openDiaryAssociation(DiscoverContentType.institution, id),
        ),
      ));
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            context.localized(
              '日记详情加载失败，请稍后重试',
              'Unable to load diary details. Please try again.',
            ),
          ),
        ),
      );
    }
  }

  void _openAiChat([DiscoverItem? detail]) {
    final chatController = _agentChatController;
    final planController = _agentPlanController;
    if (chatController == null || planController == null) return;
    final context = detail == null ? null : _agentContextFor(detail);
    _contentNavigator.push<void>(
      MaterialPageRoute(
        builder: (_) => AgentChatPage(
          chatController: chatController,
          planController: planController,
          initialContextType: context?.$1,
          initialContextId: context?.$2,
          initialContextName: context?.$3,
          onOpenCatalogItem: _openAgentCatalogItem,
        ),
      ),
    );
  }

  void _openAgentCatalogItem(AgentCatalogItem item) {
    final repository = _discoverRepository;
    if (repository == null) return;
    final type = item.type.trim().toUpperCase();
    final id = item.id.trim();
    if (id.isEmpty) return;

    final DiscoverContentType contentType;
    String? institutionId;
    String? projectId;
    switch (type) {
      case 'PROJECT':
        contentType = DiscoverContentType.project;
      case 'INSTITUTION_PROJECT':
        institutionId = item.institutionId?.trim();
        projectId = item.projectId?.trim();
        if (institutionId == null ||
            institutionId.isEmpty ||
            projectId == null ||
            projectId.isEmpty) {
          return;
        }
        contentType = DiscoverContentType.project;
      case 'DOCTOR':
        contentType = DiscoverContentType.doctor;
      case 'INSTITUTION':
        contentType = DiscoverContentType.institution;
      default:
        return;
    }

    _contentNavigator.push<void>(
      MaterialPageRoute(
        builder: (_) => buildAgentCatalogDetailPage(
          repository: repository,
          type: contentType,
          id: projectId ?? id,
          institutionId: institutionId,
          projectId: projectId,
          onBookProject: _bookingRepository == null ? null : _openBooking,
          socialController: _socialController,
          onOpenUser: _openPublicUser,
          onOpenAi: _openAiChat,
        ),
      ),
    );
  }

  (ChatContextType, String, String) _agentContextFor(DiscoverItem detail) {
    final institutionProject = detail.raw['institutionProject'];
    if (institutionProject is Map) {
      final id = institutionProject['id']?.toString().trim() ?? '';
      if (id.isNotEmpty) {
        return (ChatContextType.institutionProject, id, detail.title);
      }
    }
    final type = switch (detail.type) {
      DiscoverContentType.doctor => ChatContextType.doctor,
      DiscoverContentType.institution => ChatContextType.institution,
      _ => ChatContextType.project,
    };
    return (type, detail.id, detail.title);
  }

  Future<void> _openBooking(DiscoverItem item) async {
    final repository = _bookingRepository;
    if (repository == null) {
      return;
    }
    final target = _bookingTarget(item);
    if (target == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            context.localized(
              '请先选择提供该项目的机构',
              'Please select an institution that offers this service first.',
            ),
          ),
        ),
      );
      return;
    }
    final controller = BookingController(
      repository: repository,
      institutionId: target.institutionId,
      projectId: target.projectId,
    );
    await controller.load();
    if (!mounted) {
      controller.dispose();
      return;
    }
    if (controller.project == null || controller.errorMessage != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(context.localized(
            '暂时无法获取该机构项目的预约信息，请稍后重试',
            'Booking information for this institution service is temporarily unavailable. Please try again later.',
          )),
        ),
      );
      controller.dispose();
      return;
    }
    if (controller.doctors.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(context.localized(
            '该机构项目尚未配置可预约医生，因此暂时无法预约。请联系机构或稍后再试',
            'This institution service has no bookable doctors assigned yet. Please contact the institution or try again later.',
          )),
        ),
      );
      controller.dispose();
      return;
    }
    await _contentNavigator.push<void>(
      MaterialPageRoute(
        builder: (_) => BookingPage(
          controller: controller,
          onOrderCreated: (order) {
            _contentNavigator.pop();
            _openOrderDetail(order);
          },
        ),
      ),
    );
    controller.dispose();
  }

  Future<void> _openOrders() async {
    final controller = _ordersController;
    if (controller == null) {
      return;
    }
    await _contentNavigator.push<void>(
      MaterialPageRoute(
        builder: (_) => OrdersPage(
          controller: controller,
          onOrderSelected: _openOrderDetail,
          onEditReview: _openOrderReviewEditor,
        ),
      ),
    );
  }

  Future<void> _openWallet() async {
    final controller = _walletController;
    if (controller == null) return;
    await _contentNavigator.push<void>(
      MaterialPageRoute(builder: (_) => WalletPage(controller: controller)),
    );
  }

  Future<void> _openOrderReviewEditor(Order order) async {
    final socialController = _socialController;
    if (socialController == null) return;
    final loaded = await socialController.loadOrderReview(order.id);
    if (!mounted) return;
    final review = loaded.value;
    if (!loaded.succeeded || review == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            loaded.message ??
                context.localized('评价加载失败', 'Unable to load the review'),
          ),
        ),
      );
      return;
    }
    final draft = await _contentNavigator.push<ReviewDraft>(
      MaterialPageRoute(
        builder: (_) => ReviewOrderPage(
          order: order,
          initialReview: review,
          onPickImage: _pickAndUploadReviewImage,
        ),
      ),
    );
    if (draft == null || !mounted) return;
    final result = await socialController.updateReview(review.id, draft);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          result.succeeded
              ? context.localized('评价已修改', 'Review updated')
              : (result.message ??
                  context.localized('评价修改失败', 'Review update failed')),
        ),
      ),
    );
    if (result.succeeded) await _ordersController?.refresh();
  }

  Future<String?> _pickAndUploadReviewImage() async {
    final controller = _socialController;
    if (controller == null) return null;
    final selected = await const AppFilePicker().pickImage();
    if (selected == null) return null;
    final result = await controller.uploadPublicMedia(
      PublicMediaDraft(
        bytes: selected.bytes,
        fileName: selected.fileName,
        mimeType: selected.mimeType,
        purpose: PublicMediaPurpose.review,
      ),
    );
    if (!result.succeeded || result.value?.trim().isEmpty != false) {
      throw StateError(result.message ?? '图片上传失败');
    }
    return result.value!.trim();
  }

  void _showJourneyComingSoon() {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(context.localized('开发中', 'Coming soon'))),
    );
  }

  Future<void> _openAccountSecurity() async {
    final apiClient = widget.apiClient;
    if (apiClient == null) return;
    final controller = AccountSecurityController(
      AccountSecurityRepositoryImpl(
        ApiAccountSecurityRemoteDataSource(apiClient),
      ),
    );
    await _contentNavigator.push<void>(
      MaterialPageRoute(
        builder: (_) => AccountSecurityPage(
          controller: controller,
          onSessionInvalidated: () {
            _contentNavigator.popUntil((route) => route.isFirst);
            final logout = widget.onLogout;
            if (logout != null) unawaited(logout());
          },
        ),
      ),
    );
    controller.dispose();
  }

  Future<void> _openFavorite(FavoriteItem favorite) async {
    final repository = _discoverRepository;
    if (repository == null) return;
    if (favorite.targetType == FavoriteTargetType.diary) {
      await _openDiaryDetail(favorite.targetId);
      return;
    }
    final type = switch (favorite.targetType) {
      FavoriteTargetType.project => DiscoverContentType.project,
      FavoriteTargetType.institution => DiscoverContentType.institution,
      FavoriteTargetType.doctor => DiscoverContentType.doctor,
      FavoriteTargetType.article => DiscoverContentType.article,
      FavoriteTargetType.diary => DiscoverContentType.diary,
      FavoriteTargetType.unknown => null,
    };
    if (type == null) return;
    await _contentNavigator.push<void>(
      MaterialPageRoute(
        builder: (_) => DiscoverDetailPage(
          repository: repository,
          type: type,
          id: favorite.targetId,
          onBookProject: _bookingRepository == null ? null : _openBooking,
          socialController: _socialController,
          onOpenUser: _openPublicUser,
          onConsultDoctor: _openDoctorChat,
          onOpenAi: _openAiChat,
        ),
      ),
    );
  }

  Future<void> _openOrderDetail(Order order) async {
    final repository = _ordersRepository;
    if (repository == null) {
      return;
    }
    final controller = OrderDetailController(repository, orderId: order.id);
    await _contentNavigator.push<void>(
      MaterialPageRoute(
        builder: (_) => OrderDetailPage(
          controller: controller,
          socialController: _socialController,
        ),
      ),
    );
    controller.dispose();
    await _ordersController?.refresh();
  }

  Future<void> _openSocial() async {
    final controller = _socialController;
    if (controller == null) {
      return;
    }
    await _contentNavigator.push<void>(
      MaterialPageRoute(
        builder: (_) => SocialPage(
          controller: controller,
          onCreateDiary: () => _openDiaryEditor(controller),
          onEditDiary: (diary) => _openDiaryEditor(controller, diary: diary),
          onOpenUser: _openPublicUser,
          onOpenProject: (institutionId, projectId) => _openDiaryAssociation(
            DiscoverContentType.project,
            projectId,
            institutionId: institutionId,
          ),
          onOpenDoctor: (id) =>
              _openDiaryAssociation(DiscoverContentType.doctor, id),
          onOpenInstitution: (id) =>
              _openDiaryAssociation(DiscoverContentType.institution, id),
        ),
      ),
    );
  }

  Future<void> _openPublicUser(String userId) async {
    final repository = _socialRepository;
    final controller = _socialController;
    if (repository == null || controller == null || userId.trim().isEmpty) {
      return;
    }
    await _contentNavigator.push<void>(MaterialPageRoute(
      builder: (_) => PublicUserPage(
        userId: userId,
        repository: repository,
        socialController: controller,
        currentUserId: widget.currentUserId,
        onMessage: (profile) => _openDirectMessage(
          userId,
          title: profile.nickname,
        ),
        onOpenProject: (institutionId, projectId) => _openDiaryAssociation(
          DiscoverContentType.project,
          projectId,
          institutionId: institutionId,
        ),
        onOpenDoctor: (id) =>
            _openDiaryAssociation(DiscoverContentType.doctor, id),
        onOpenInstitution: (id) =>
            _openDiaryAssociation(DiscoverContentType.institution, id),
      ),
    ));
  }

  Future<void> _openDoctorChat(DiscoverItem doctor) => _openDirectMessage(
        doctor.id,
        title: doctor.title,
      );

  Future<void> _openDirectMessage(
    String targetId, {
    String? title,
  }) async {
    final repository = _messagingRepository;
    final id = targetId.trim();
    if (repository == null || id.isEmpty) return;
    if (id == widget.currentUserId.trim()) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(context.localized(
            '不能给自己发送私信',
            'You cannot message yourself.',
          )),
        ),
      );
      return;
    }
    try {
      final conversation = await repository.createDmConversation(id);
      if (!mounted) return;
      await _openDmThread(conversation, title: title);
      await _messagingController?.refresh();
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(context.localized(
            '暂时无法发起私信，请稍后重试',
            'Unable to start this conversation. Please try again.',
          )),
        ),
      );
    }
  }

  Future<void> _openDiaryEditor(
    SocialController controller, {
    Diary? diary,
  }) {
    return _contentNavigator.push<void>(
      MaterialPageRoute(
        builder: (_) => DiaryEditorPage(
          controller: controller,
          diary: diary,
          discoverRepository: _discoverRepository,
          ordersRepository: _ordersRepository,
        ),
      ),
    );
  }

  void _openMessagesTab() {
    setState(() => _selectedIndex = 2);
    final notificationController = _notificationController;
    if (notificationController != null) {
      // Opening the notification entry acknowledges the current notification
      // badge. The controller updates the local count immediately after the
      // server confirms the operation.
      unawaited(notificationController.markAllRead());
    }
    final messagingController = _messagingController;
    if (messagingController != null) {
      unawaited(messagingController.refresh());
    }
  }

  Future<void> _openNotificationCategory(bool activity) async {
    final controller = _notificationController;
    if (controller == null) return;
    final english = Localizations.localeOf(context).languageCode == 'en';
    await _contentNavigator.push<void>(
      MaterialPageRoute(
        builder: (_) => NotificationPage(
          controller: controller,
          title: activity
              ? (english ? 'Activity messages' : '活动消息')
              : (english ? 'System messages' : '系统消息'),
          filter: (notification) {
            final type = notification.type.trim().toLowerCase();
            final isActivity = const {
              'activity',
              'promotion',
              'marketing',
              'campaign',
              'offer',
            }.contains(type);
            return activity ? isActivity : !isActivity;
          },
          onOpenNotification: _openNotificationTarget,
        ),
      ),
    );
  }

  void _openNotificationTarget(AppNotification notification) {
    final targetId = notification.targetId.trim();
    if (targetId.isEmpty) return;
    final type = notification.targetType.trim().toLowerCase();
    if (type == 'dm_conversation') {
      unawaited(_openDmConversation(targetId));
      return;
    }
    if (type == 'user') {
      _openPublicUser(targetId);
      return;
    }
    if (type == 'order') {
      _openOrders();
      return;
    }
    final discoverType = switch (type) {
      'project' => DiscoverContentType.project,
      'institution' => DiscoverContentType.institution,
      'doctor' => DiscoverContentType.doctor,
      'article' => DiscoverContentType.article,
      'diary' => DiscoverContentType.diary,
      _ => null,
    };
    final repository = _discoverRepository;
    if (discoverType == null || repository == null) return;
    _contentNavigator.push<void>(MaterialPageRoute(
      builder: (_) => DiscoverDetailPage(
        repository: repository,
        type: discoverType,
        id: targetId,
        onBookProject: _bookingRepository == null ? null : _openBooking,
        socialController: _socialController,
        onOpenUser: _openPublicUser,
        onConsultDoctor: _openDoctorChat,
        onOpenAi: _openAiChat,
      ),
    ));
  }

  Future<void> _openDmConversation(String conversationId) async {
    final repository = _messagingRepository;
    if (repository == null) return;
    try {
      final conversations = await repository.getDmConversations();
      final conversation =
          conversations.where((item) => item.id == conversationId).firstOrNull;
      if (!mounted || conversation == null) return;
      final peer = _messagingController?.peerFor(conversation);
      await _openDmThread(conversation, title: peer?.name);
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(context.localized(
            '暂时无法打开该私信，请稍后重试',
            'Unable to open this conversation. Please try again.',
          )),
        ),
      );
    }
  }

  Future<void> _openCustomerService() async {
    final controller = _messagingController;
    if (controller == null) return;
    await _contentNavigator.push<void>(MaterialPageRoute(
      builder: (_) => CustomerServicePage(
        onOnlineChat: () async {
          final conversation = await controller.openCustomerService();
          if (!mounted || conversation == null) return;
          await _openCustomerServiceThread(conversation);
        },
      ),
    ));
  }

  Future<void> _openDmThread(
    DmConversation conversation, {
    String? title,
  }) async {
    final repository = _messagingRepository;
    if (repository == null) {
      return;
    }
    final otherUserId = conversation.otherUserId(widget.currentUserId);
    final currentUserFallback = context.localized('我', 'Me');
    final peers = await Future.wait<MessagingPeer>([
      _loadCurrentMessagingPeer(currentUserFallback),
      _loadOtherMessagingPeer(otherUserId, fallbackName: title),
    ]);
    if (!mounted) return;
    unawaited(_messagingController?.clearUnread(conversation.id));
    final controller = DmThreadController(
      repository: repository,
      conversationId: conversation.id,
      currentUserId: widget.currentUserId,
      firstMessageLimitApplies: conversation.firstMessageLimitApplies,
      waitingForReply: conversation.waitingForReply,
    );
    await _contentNavigator.push<void>(
      MaterialPageRoute(
        builder: (_) => DmThreadPage(
          controller: controller,
          currentUserId: widget.currentUserId,
          myPeer: peers[0],
          otherPeer: peers[1],
          onOtherAvatarTap:
              otherUserId.isEmpty ? null : () => _openPublicUser(otherUserId),
          onPickImage: _pickAndUploadDmImage,
          onTranslate: _translateDmMessage,
          title:
              title?.trim().isNotEmpty == true ? title!.trim() : peers[1].name,
        ),
      ),
    );
    controller.dispose();
    await _messagingController?.refresh();
  }

  Future<MessagingPeer> _loadCurrentMessagingPeer(String fallbackName) async {
    try {
      final profile = await _profileRepository?.getProfile();
      if (profile != null) {
        return MessagingPeer(
          id: profile.id,
          name: profile.nickname,
          avatar: profile.avatar,
        );
      }
    } on Object {
      // The thread remains available when profile loading fails.
    }
    return MessagingPeer(
      id: widget.currentUserId,
      name: fallbackName,
    );
  }

  Future<MessagingPeer> _loadOtherMessagingPeer(
    String userId, {
    String? fallbackName,
  }) async {
    final cached = _messagingController?.peers[userId];
    if (cached != null) return cached;
    try {
      final profile = await _socialRepository?.getPublicUserProfile(userId);
      if (profile != null) {
        final peer = MessagingPeer(
          id: profile.id,
          name: profile.nickname,
          avatar: profile.avatar,
        );
        _messagingController?.peers[userId] = peer;
        return peer;
      }
    } on Object {
      // Fall back to the route title or user id when the profile is private.
    }
    final name = fallbackName?.trim();
    return MessagingPeer(
      id: userId,
      name: name?.isNotEmpty == true ? name! : userId,
    );
  }

  Future<String?> _pickAndUploadDmImage() async {
    final controller = _socialController;
    if (controller == null) return null;
    try {
      final selected = await const AppFilePicker().pickImage();
      if (selected == null) return null;
      final result = await controller.uploadPublicMedia(
        PublicMediaDraft(
          bytes: selected.bytes,
          fileName: selected.fileName,
          mimeType: selected.mimeType,
          purpose: PublicMediaPurpose.directMessage,
        ),
      );
      if (!result.succeeded || result.value?.trim().isEmpty != false) {
        throw StateError(result.message ?? '图片上传失败');
      }
      return result.value!.trim();
    } on Object {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(context.localized(
              '图片发送失败，请稍后重试',
              'Unable to send the image. Please try again.',
            )),
          ),
        );
      }
      return null;
    }
  }

  Future<String?> _translateDmMessage(String text) async {
    final repository = _socialRepository;
    if (repository is! SocialTranslationRepository) return null;
    final translationRepository = repository as SocialTranslationRepository;
    try {
      final english = Localizations.localeOf(context).languageCode == 'en';
      final translation = await translationRepository.translateText(
        text: text,
        targetLanguage: english ? 'zh-CN' : 'en',
        contentType: 'direct_message',
      );
      return translation.translatedText;
    } on Object {
      return null;
    }
  }

  Future<void> _openCustomerServiceThread(
    CustomerServiceConversation conversation,
  ) async {
    final repository = _messagingRepository;
    if (repository == null) {
      return;
    }
    final currentUserFallback = context.localized('我', 'Me');
    final customerServiceName = context.localized('平台客服', 'Customer Service');
    final myPeer = await _loadCurrentMessagingPeer(currentUserFallback);
    if (!mounted) return;
    final serviceUserId = conversation.userAId == widget.currentUserId
        ? conversation.userBId
        : conversation.userAId;
    final customerServicePeer = MessagingPeer(
      id: serviceUserId.isEmpty ? 'CS_ADMIN' : serviceUserId,
      name: customerServiceName,
    );
    final controller = CustomerServiceThreadController(
      repository: repository,
      conversationId: conversation.id,
    );
    await _contentNavigator.push<void>(
      MaterialPageRoute(
        builder: (_) => CustomerServiceThreadPage(
          controller: controller,
          currentUserId: widget.currentUserId,
          myPeer: myPeer,
          otherPeer: customerServicePeer,
          onPickImage: _pickAndUploadDmImage,
        ),
      ),
    );
    controller.dispose();
    await _messagingController?.refresh();
  }

  @override
  Widget build(BuildContext context) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final authenticatedRouteFactory = Navigator.of(
      context,
      rootNavigator: true,
    ).widget.onGenerateRoute;
    return WillPopScope(
      onWillPop: () async {
        if (_contentNavigator.canPop()) {
          _contentNavigator.pop();
          return false;
        }
        return true;
      },
      child: Scaffold(
        body: SafeArea(
          bottom: false,
          child: Navigator(
            key: _contentNavigatorKey,
            pages: [
              MaterialPage<void>(
                key: const ValueKey('shell-content-root'),
                child: IndexedStack(index: _selectedIndex, children: _pages),
              ),
            ],
            onGenerateRoute: authenticatedRouteFactory,
            onPopPage: (route, result) => route.didPop(result),
          ),
        ),
        bottomNavigationBar: _KeyboardAwareBottomNavigation(
          english: english,
          selectedIndex: _selectedIndex,
          onDestinationSelected: _selectDestination,
        ),
      ),
    );
  }

  void _selectDestination(int index) {
    _contentNavigator.popUntil((route) => route.isFirst);
    setState(() => _selectedIndex = index);
  }
}

DiscoverDetailPage buildAgentCatalogDetailPage({
  required DiscoverRepository repository,
  required DiscoverContentType type,
  required String id,
  String? institutionId,
  String? projectId,
  ValueChanged<DiscoverItem>? onBookProject,
  SocialController? socialController,
  ValueChanged<String>? onOpenUser,
  ValueChanged<DiscoverItem>? onOpenAi,
}) =>
    DiscoverDetailPage(
      repository: repository,
      type: type,
      id: id,
      institutionId: institutionId,
      projectId: projectId,
      onBookProject: onBookProject,
      socialController: socialController,
      onOpenUser: onOpenUser,
      onOpenAi: onOpenAi,
    );

class _KeyboardAwareBottomNavigation extends StatelessWidget {
  const _KeyboardAwareBottomNavigation({
    required this.english,
    required this.selectedIndex,
    required this.onDestinationSelected,
  });

  final bool english;
  final int selectedIndex;
  final ValueChanged<int> onDestinationSelected;

  @override
  Widget build(BuildContext context) {
    if (MediaQuery.viewInsetsOf(context).bottom > 0) {
      return const SizedBox.shrink();
    }
    return NavigationBar(
      indicatorColor: Colors.transparent,
      labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
      selectedIndex: selectedIndex,
      onDestinationSelected: onDestinationSelected,
      destinations: [
        NavigationDestination(
          icon: const Icon(Icons.home_outlined),
          selectedIcon: const Icon(Icons.home),
          label: english ? 'Home' : '首页',
        ),
        NavigationDestination(
          icon: const Icon(Icons.search_outlined),
          selectedIcon: const Icon(Icons.search_rounded),
          label: english ? 'Discover' : '发现',
        ),
        NavigationDestination(
          icon: const Icon(Icons.forum_outlined),
          selectedIcon: const Icon(Icons.forum_rounded),
          label: english ? 'Messages' : '消息',
        ),
        NavigationDestination(
          icon: const Icon(Icons.person_outline),
          selectedIcon: const Icon(Icons.person),
          label: english ? 'Profile' : '我的',
        ),
      ],
    );
  }
}

({String institutionId, String projectId})? _bookingTarget(
  DiscoverItem item,
) {
  final raw = item.raw;
  final directInstitution = _text(raw['institutionId']);
  final directProject = _text(raw['projectId']);
  if (directInstitution != null) {
    return (
      institutionId: directInstitution,
      projectId: directProject ?? item.id,
    );
  }
  final detailInstitutionProject = raw['institutionProject'];
  final detailInstitution = raw['institution'];
  final detailProject = raw['project'];
  final detailInstitutionId = detailInstitutionProject is Map
      ? _text(detailInstitutionProject['institutionId'])
      : null;
  final nestedInstitutionId =
      detailInstitution is Map ? _text(detailInstitution['id']) : null;
  final detailProjectId = detailInstitutionProject is Map
      ? _text(detailInstitutionProject['projectId'])
      : null;
  final nestedProjectId =
      detailProject is Map ? _text(detailProject['id']) : null;
  final resolvedInstitutionId = detailInstitutionId ?? nestedInstitutionId;
  if (resolvedInstitutionId != null) {
    return (
      institutionId: resolvedInstitutionId,
      projectId: detailProjectId ?? nestedProjectId ?? item.id,
    );
  }
  final entries = raw['institutionProjects'];
  if (entries is! List || entries.isEmpty || entries.first is! Map) {
    return null;
  }
  final first = entries.first as Map;
  final institution = first['institution'];
  final institutionProject = first['institutionProject'];
  final institutionId = _text(first['institutionId']) ??
      (institution is Map ? _text(institution['id']) : null) ??
      (institutionProject is Map
          ? _text(institutionProject['institutionId'])
          : null);
  final projectId = _text(first['projectId']) ??
      (institutionProject is Map
          ? _text(institutionProject['projectId'])
          : null) ??
      item.id;
  if (institutionId == null) {
    return null;
  }
  return (institutionId: institutionId, projectId: projectId);
}

String? _text(Object? value) {
  final text = value?.toString().trim();
  return text == null || text.isEmpty ? null : text;
}
