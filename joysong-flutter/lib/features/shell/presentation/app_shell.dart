import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/account_security/data/account_security_api.dart';
import 'package:joysong_flutter/features/account_security/data/account_security_repository_impl.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_controller.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_page.dart';
import 'package:joysong_flutter/features/agent/data/agent_remote_data_source.dart';
import 'package:joysong_flutter/features/agent/data/agent_repository_impl.dart';
import 'package:joysong_flutter/features/agent/data/chat_sse_transport.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_controller.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_page.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_plan_controller.dart';
import 'package:joysong_flutter/features/assistant/presentation/assistant_page.dart';
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

class AppShell extends StatefulWidget {
  const AppShell({
    this.apiClient,
    this.apiRoot,
    this.accessTokenProvider,
    this.languageTagProvider,
    this.allowPreviewData = false,
    this.currentUserId = '',
    this.onLogout,
    super.key,
  });

  final ApiClient? apiClient;
  final Uri? apiRoot;
  final AccessTokenProvider? accessTokenProvider;
  final LanguageTagProvider? languageTagProvider;
  final bool allowPreviewData;
  final String currentUserId;
  final Future<void> Function()? onLogout;

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
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
  OrdersController? _ordersController;
  SocialController? _socialController;
  NotificationController? _notificationController;
  MessagingHubController? _messagingController;
  AgentChatController? _agentChatController;
  AgentPlanController? _agentPlanController;

  @override
  void initState() {
    super.initState();
    _createDependencies();
  }

  @override
  void didUpdateWidget(covariant AppShell oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.apiClient != widget.apiClient ||
        oldWidget.apiRoot != widget.apiRoot ||
        oldWidget.accessTokenProvider != widget.accessTokenProvider) {
      _createDependencies();
    }
  }

  void _createDependencies() {
    _disposeControllers();
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
    _notificationController = NotificationController(_messagingRepository!);
    _messagingController = MessagingHubController(_messagingRepository!);

    final apiRoot = widget.apiRoot;
    final accessTokenProvider = widget.accessTokenProvider;
    if (apiRoot != null && accessTokenProvider != null) {
      final agentRepository = AgentRepositoryImpl(
        ApiAgentRemoteDataSource(
          apiClient: apiClient,
          streamTransport: HttpChatStreamTransport(
            apiRoot: apiRoot,
            accessTokenProvider: accessTokenProvider,
            languageTagProvider: widget.languageTagProvider,
          ),
        ),
      );
      _agentChatController = AgentChatController(repository: agentRepository);
      _agentPlanController = AgentPlanController(agentRepository);
    }
  }

  void _disposeControllers() {
    _ordersController?.dispose();
    _socialController?.dispose();
    _notificationController?.dispose();
    _messagingController?.dispose();
    _agentChatController?.dispose();
    _agentPlanController?.dispose();
    _ordersController = null;
    _socialController = null;
    _notificationController = null;
    _messagingController = null;
    _agentChatController = null;
    _agentPlanController = null;
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
          onNotifications:
              _notificationController == null ? null : _openNotifications,
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
        ),
        if (_agentChatController != null && _agentPlanController != null)
          AgentChatPage(
            chatController: _agentChatController!,
            planController: _agentPlanController!,
            onOpenCatalogItem: _openAgentCatalogItem,
            onHumanChat: _openAgentHumanChat,
          )
        else
          const AssistantPage(),
        ProfilePage(
          profileRepository: _profileRepository,
          identityRepository: _identityRepository,
          socialRepository: _socialRepository,
          onOrders: _ordersController == null ? null : _openOrders,
          onDiaries: _socialController == null ? null : _openSocial,
          onJourney: _showJourneyComingSoon,
          onMessages: _messagingController == null ? null : _openMessages,
          onCustomerService:
              _messagingController == null ? null : _openCustomerService,
          onAccountSecurity:
              widget.apiClient == null ? null : _openAccountSecurity,
          onOpenFavorite: _discoverRepository == null ? null : _openFavorite,
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
        Navigator.of(context).push(
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
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => DiscoverDetailPage(
          repository: repository,
          type: type,
          id: item.id,
          onBookProject: _bookingRepository == null ? null : _openBooking,
          socialController: _socialController,
          onOpenUser: _openPublicUser,
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
    Navigator.of(context).push<void>(MaterialPageRoute(
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
      await Navigator.of(context).push<void>(MaterialPageRoute(
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

  void _openAgentCatalogItem(AgentCatalogItem item) {
    final repository = _discoverRepository;
    if (repository == null || item.id.isEmpty) return;
    final type = switch (item.type.toUpperCase()) {
      'DOCTOR' => DiscoverContentType.doctor,
      'INSTITUTION' => DiscoverContentType.institution,
      'PROJECT' || 'INSTITUTION_PROJECT' => DiscoverContentType.project,
      _ => null,
    };
    if (type == null) return;
    Navigator.of(context).push<void>(MaterialPageRoute(
      builder: (_) => DiscoverDetailPage(
        repository: repository,
        type: type,
        id: item.projectId ?? item.id,
        institutionId: item.institutionId,
        projectId: item.projectId,
        onBookProject: _bookingRepository == null ? null : _openBooking,
        socialController: _socialController,
        onOpenUser: _openPublicUser,
      ),
    ));
  }

  Future<void> _openAgentHumanChat(AgentCatalogItem item) async {
    final repository = _messagingRepository;
    if (repository == null) return;
    final targetId = item.type.toUpperCase() == 'DOCTOR'
        ? item.id
        : item.institutionId ?? item.id;
    if (targetId.isEmpty) return;
    try {
      final conversation = await repository.createDmConversation(targetId);
      if (!mounted) return;
      await _openDmThread(conversation);
    } on Object {
      if (!mounted) return;
      final english = Localizations.localeOf(context).languageCode == 'en';
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(english
            ? 'Unable to start a consultation. Please try again.'
            : '暂时无法发起咨询，请稍后重试'),
      ));
    }
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
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => BookingPage(
          controller: controller,
          onOrderCreated: (order) {
            Navigator.of(context).pop();
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
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => OrdersPage(
          controller: controller,
          onOrderSelected: _openOrderDetail,
        ),
      ),
    );
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
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => AccountSecurityPage(
          controller: controller,
          onSessionInvalidated: () {
            Navigator.of(context).popUntil((route) => route.isFirst);
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
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => DiscoverDetailPage(
          repository: repository,
          type: type,
          id: favorite.targetId,
          onBookProject: _bookingRepository == null ? null : _openBooking,
          socialController: _socialController,
          onOpenUser: _openPublicUser,
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
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => OrderDetailPage(
          controller: controller,
          socialController: _socialController,
        ),
      ),
    );
    controller.dispose();
  }

  Future<void> _openSocial() async {
    final controller = _socialController;
    if (controller == null) {
      return;
    }
    await Navigator.of(context).push<void>(
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
    await Navigator.of(context).push<void>(MaterialPageRoute(
      builder: (_) => PublicUserPage(
        userId: userId,
        repository: repository,
        socialController: controller,
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

  Future<void> _openDiaryEditor(
    SocialController controller, {
    Diary? diary,
  }) {
    return Navigator.of(context).push<void>(
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

  Future<void> _openNotifications() async {
    final controller = _notificationController;
    if (controller == null) {
      return;
    }
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => NotificationPage(
          controller: controller,
          onOpenNotification: _openNotificationTarget,
        ),
      ),
    );
  }

  void _openNotificationTarget(AppNotification notification) {
    final targetId = notification.targetId.trim();
    if (targetId.isEmpty) return;
    final type = notification.targetType.toLowerCase();
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
    Navigator.of(context).push<void>(MaterialPageRoute(
      builder: (_) => DiscoverDetailPage(
        repository: repository,
        type: discoverType,
        id: targetId,
        onBookProject: _bookingRepository == null ? null : _openBooking,
        socialController: _socialController,
        onOpenUser: _openPublicUser,
      ),
    ));
  }

  Future<void> _openCustomerService() async {
    final controller = _messagingController;
    if (controller == null) return;
    await Navigator.of(context).push<void>(MaterialPageRoute(
      builder: (_) => CustomerServicePage(
        onOnlineChat: () async {
          final conversation = await controller.openCustomerService();
          if (!mounted || conversation == null) return;
          await _openCustomerServiceThread(conversation);
        },
      ),
    ));
  }

  Future<void> _openMessages() async {
    final controller = _messagingController;
    if (controller == null) {
      return;
    }
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => MessagingCenterPage(
          controller: controller,
          currentUserId: widget.currentUserId,
          onOpenDm: _openDmThread,
          onOpenCustomerService: _openCustomerServiceThread,
          onOpenUser: _openPublicUser,
        ),
      ),
    );
  }

  Future<void> _openDmThread(DmConversation conversation) async {
    final repository = _messagingRepository;
    if (repository == null) {
      return;
    }
    final controller = DmThreadController(
      repository: repository,
      conversationId: conversation.id,
    );
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => DmThreadPage(
          controller: controller,
          currentUserId: widget.currentUserId,
          title: conversation.otherUserId(widget.currentUserId),
        ),
      ),
    );
    controller.dispose();
  }

  Future<void> _openCustomerServiceThread(
    CustomerServiceConversation conversation,
  ) async {
    final repository = _messagingRepository;
    if (repository == null) {
      return;
    }
    final controller = CustomerServiceThreadController(
      repository: repository,
      conversationId: conversation.id,
    );
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => CustomerServiceThreadPage(
          controller: controller,
          currentUserId: widget.currentUserId,
        ),
      ),
    );
    controller.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: IndexedStack(index: _selectedIndex, children: _pages),
      ),
      bottomNavigationBar: NavigationBar(
        indicatorColor: Colors.transparent,
        labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
        selectedIndex: _selectedIndex,
        onDestinationSelected: (index) =>
            setState(() => _selectedIndex = index),
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
          const NavigationDestination(
            icon: Icon(Icons.chat_bubble_outline_rounded),
            selectedIcon: Icon(Icons.chat_bubble_rounded),
            label: 'AI',
          ),
          NavigationDestination(
            icon: const Icon(Icons.person_outline),
            selectedIcon: const Icon(Icons.person),
            label: english ? 'Profile' : '我的',
          ),
        ],
      ),
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
