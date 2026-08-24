import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/optimized_network_image.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_content_card.dart';
import 'package:joysong_flutter/features/home/domain/home_models.dart';
import 'package:joysong_flutter/features/home/domain/home_repository.dart';
import 'package:joysong_flutter/features/home/presentation/home_controller.dart';
import 'package:joysong_flutter/features/profile/domain/profile_repository.dart';

class HomePage extends StatefulWidget {
  const HomePage({
    this.repository,
    this.profileRepository,
    this.onOpenItem,
    this.onSearch,
    this.onNotifications,
    this.unreadNotificationCount = 0,
    this.onViewAll,
    this.allowPreviewData = false,
    super.key,
  });

  final HomeRepository? repository;
  final ProfileRepository? profileRepository;
  final ValueChanged<HomeContent>? onOpenItem;
  final VoidCallback? onSearch;
  final VoidCallback? onNotifications;
  final int unreadNotificationCount;
  final ValueChanged<HomeSectionKind>? onViewAll;
  final bool allowPreviewData;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  HomeController? _controller;
  String _nickname = '';

  @override
  void initState() {
    super.initState();
    _createController();
    _loadNickname();
  }

  @override
  void didUpdateWidget(covariant HomePage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.repository != widget.repository) {
      _controller?.dispose();
      _createController();
    }
    if (oldWidget.profileRepository != widget.profileRepository) {
      _loadNickname();
    }
  }

  Future<void> _loadNickname() async {
    final repository = widget.profileRepository;
    if (repository == null) return;
    try {
      final user = await repository.getProfile();
      if (mounted) setState(() => _nickname = user.nickname.trim());
    } catch (_) {
      // 首页主体仍可正常使用，昵称加载失败时保留占位文案。
    }
  }

  void _createController() {
    final repository = widget.repository;
    if (repository == null) {
      _controller = null;
      return;
    }
    _controller = HomeController(repository)..load();
  }

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = _controller;
    if (controller == null) {
      if (!widget.allowPreviewData) {
        return const _HomeDataUnavailable();
      }
      return _HomeFeedView(
        feed: _previewFeed,
        onRefresh: () async {},
        onOpenItem: widget.onOpenItem,
        onSearch: widget.onSearch,
        onNotifications: widget.onNotifications,
        unreadNotificationCount: widget.unreadNotificationCount,
        nickname: _nickname,
        onViewAll: widget.onViewAll,
      );
    }
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        return switch (controller.status) {
          HomeLoadStatus.idle || HomeLoadStatus.loading => const Center(
              child: CircularProgressIndicator(),
            ),
          HomeLoadStatus.failure => _HomeFailure(
              message: context.localized(
                '首页加载失败，请检查网络后重试',
                'Failed to load home. Check your connection and try again.',
              ),
              onRetry: () => controller.load(refresh: true),
            ),
          HomeLoadStatus.empty => _HomeEmpty(
              onRefresh: () => controller.load(refresh: true),
              hasPartialFailures: controller.feed.hasPartialFailures,
            ),
          HomeLoadStatus.ready => _HomeFeedView(
              feed: controller.feed,
              onRefresh: () => controller.load(refresh: true),
              onOpenItem: widget.onOpenItem,
              onSearch: widget.onSearch,
              onNotifications: widget.onNotifications,
              unreadNotificationCount: widget.unreadNotificationCount,
              nickname: _nickname,
              onViewAll: widget.onViewAll,
            ),
        };
      },
    );
  }
}

class _HomeFeedView extends StatelessWidget {
  const _HomeFeedView({
    required this.feed,
    required this.onRefresh,
    this.onOpenItem,
    this.onSearch,
    this.onNotifications,
    this.unreadNotificationCount = 0,
    this.nickname = '',
    this.onViewAll,
  });

  final HomeFeed feed;
  final Future<void> Function() onRefresh;
  final ValueChanged<HomeContent>? onOpenItem;
  final VoidCallback? onSearch;
  final VoidCallback? onNotifications;
  final int unreadNotificationCount;
  final String nickname;
  final ValueChanged<HomeSectionKind>? onViewAll;

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      onRefresh: onRefresh,
      child: CustomScrollView(
        key: const PageStorageKey<String>('home'),
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          SliverToBoxAdapter(
            child: _GreetingHeader(
              nickname: nickname,
              onNotifications: onNotifications,
              unreadNotificationCount: unreadNotificationCount,
            ),
          ),
          SliverToBoxAdapter(child: _SearchEntry(onTap: onSearch)),
          if (feed.hasPartialFailures)
            const SliverToBoxAdapter(child: _PartialHomeWarning()),
          if (feed.banners.isNotEmpty)
            SliverToBoxAdapter(
              child: _BannerCarousel(
                items: feed.banners,
                onOpenItem: onOpenItem,
              ),
            ),
          if (feed.recommendedInstitutionProjects.isNotEmpty) ...[
            SliverToBoxAdapter(
              child: _SectionTitle(
                title: context.localized('AI 推荐机构项目', 'AI recommendations'),
                action: context.localized('查看全部', 'View all'),
                onAction: () => onViewAll?.call(
                  HomeSectionKind.recommendedInstitutionProject,
                ),
              ),
            ),
            SliverToBoxAdapter(
              child: _AiProjectList(
                items: feed.recommendedInstitutionProjects,
                onOpenItem: onOpenItem,
              ),
            ),
          ],
          if (feed.hotProjects.isNotEmpty) ...[
            SliverToBoxAdapter(
              child: _SectionTitle(
                title: context.localized('热门项目', 'Hot projects'),
                action: context.localized('查看全部', 'View all'),
                onAction: () => onViewAll?.call(HomeSectionKind.hotProject),
              ),
            ),
            SliverToBoxAdapter(
              child: _HorizontalContentList(
                items: feed.hotProjects,
                onOpenItem: onOpenItem,
              ),
            ),
          ],
          if (feed.expertArticles.isNotEmpty) ...[
            SliverToBoxAdapter(
              child: _SectionTitle(
                title: context.localized('专家文章', 'Expert insights'),
                action: context.localized('更多', 'More'),
                onAction: () => onViewAll?.call(HomeSectionKind.expertArticle),
              ),
            ),
            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              sliver: SliverList.separated(
                itemCount: feed.expertArticles.length,
                separatorBuilder: (_, __) => const SizedBox(height: 10),
                itemBuilder: (context, index) {
                  final item = feed.expertArticles[index];
                  return _ArticleItem(
                    item: item,
                    onTap: () => onOpenItem?.call(item),
                  );
                },
              ),
            ),
          ],
          if (feed.userDiaries.isNotEmpty) ...[
            SliverToBoxAdapter(
              child: _SectionTitle(
                title: context.localized('精选日记', 'Featured diaries'),
                action: context.localized('查看更多', 'View more'),
                onAction: () => onViewAll?.call(HomeSectionKind.userDiary),
              ),
            ),
            SliverToBoxAdapter(
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    for (var index = 0;
                        index < feed.userDiaries.length;
                        index++) ...[
                      if (index > 0) const SizedBox(width: 12),
                      _DiaryCard(
                        item: feed.userDiaries[index],
                        onTap: () => onOpenItem?.call(feed.userDiaries[index]),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ],
          if (feed.institutions.isNotEmpty) ...[
            SliverToBoxAdapter(
              child: _SectionTitle(
                title: context.localized('认证机构', 'Verified institutions'),
                action: context.localized('查看全部', 'View all'),
                onAction: () => onViewAll?.call(HomeSectionKind.institution),
              ),
            ),
            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              sliver: SliverList.separated(
                itemCount: feed.institutions.length,
                separatorBuilder: (_, __) => const SizedBox(height: 10),
                itemBuilder: (context, index) {
                  final item = feed.institutions[index];
                  return _InstitutionItem(
                    item: item,
                    onTap: () => onOpenItem?.call(item),
                  );
                },
              ),
            ),
          ],
          if (feed.doctors.isNotEmpty) ...[
            SliverToBoxAdapter(
              child: _SectionTitle(
                title: context.localized('推荐医生', 'Recommended doctors'),
                action: context.localized('查看全部', 'View all'),
                onAction: () => onViewAll?.call(HomeSectionKind.doctor),
              ),
            ),
            SliverToBoxAdapter(
              child: _DoctorList(
                items: feed.doctors,
                onOpenItem: onOpenItem,
              ),
            ),
          ],
          const SliverToBoxAdapter(child: SizedBox(height: 28)),
        ],
      ),
    );
  }
}

class _GreetingHeader extends StatelessWidget {
  const _GreetingHeader({
    required this.nickname,
    required this.unreadNotificationCount,
    this.onNotifications,
  });

  final String nickname;
  final int unreadNotificationCount;
  final VoidCallback? onNotifications;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 8, 4),
      child: Row(
        children: [
          Expanded(
            child: Text(
              context.localized(
                '你好！${nickname.isEmpty ? '...' : nickname}',
                'Hello! ${nickname.isEmpty ? '...' : nickname}',
              ),
              style: Theme.of(context).textTheme.titleLarge,
            ),
          ),
          Stack(
            clipBehavior: Clip.none,
            children: [
              IconButton(
                tooltip: unreadNotificationCount > 0
                    ? context.localized(
                        '消息，$unreadNotificationCount 条未读',
                        'Messages, $unreadNotificationCount unread',
                      )
                    : context.localized('消息', 'Messages'),
                onPressed: onNotifications,
                icon: const Icon(Icons.notifications_none_rounded),
              ),
              if (unreadNotificationCount > 0)
                Positioned(
                  right: 10,
                  top: 10,
                  child: Container(
                    width: 7,
                    height: 7,
                    decoration: BoxDecoration(
                      color: Theme.of(context).colorScheme.error,
                      shape: BoxShape.circle,
                    ),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _SearchEntry extends StatelessWidget {
  const _SearchEntry({this.onTap});

  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
      child: Material(
        color: colors.surface,
        borderRadius: BorderRadius.circular(24),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(24),
          child: SizedBox(
            height: 48,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Row(
                children: [
                  const Icon(Icons.search_rounded, size: 20),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      context.localized(
                        '搜索项目、机构、医生或文章',
                        'Search projects, institutions, doctors or articles',
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _BannerCarousel extends StatelessWidget {
  const _BannerCarousel({required this.items, this.onOpenItem});

  final List<HomeContent> items;
  final ValueChanged<HomeContent>? onOpenItem;

  @override
  Widget build(BuildContext context) {
    final cardWidth = MediaQuery.sizeOf(context).width.clamp(320, 408) - 48;
    return SizedBox(
      height: 154,
      child: ListView.separated(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        scrollDirection: Axis.horizontal,
        itemCount: items.length,
        separatorBuilder: (_, __) => const SizedBox(width: 12),
        itemBuilder: (context, index) {
          final item = items[index];
          return SizedBox(
            width: cardWidth.toDouble(),
            child: _RecommendationBanner(
              item: item,
              onTap: () => onOpenItem?.call(item),
            ),
          );
        },
      ),
    );
  }
}

class _RecommendationBanner extends StatelessWidget {
  const _RecommendationBanner({required this.item, required this.onTap});

  final HomeContent item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Material(
      color: colors.primaryContainer,
      borderRadius: BorderRadius.circular(16),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: SizedBox(
          height: 150,
          child: Stack(
            fit: StackFit.expand,
            children: [
              if (item.imageUrl.isNotEmpty)
                OptimizedNetworkImage(
                  url: item.imageUrl,
                  width: double.infinity,
                  height: 150,
                  errorBuilder: (_, __, ___) => const SizedBox.shrink(),
                ),
              DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    colors: [
                      colors.primaryContainer.withValues(alpha: .98),
                      colors.primaryContainer.withValues(
                        alpha: item.imageUrl.isEmpty ? .9 : .32,
                      ),
                    ],
                  ),
                ),
              ),
              Padding(
                padding: const EdgeInsets.all(20),
                child: Align(
                  alignment: Alignment.bottomLeft,
                  child: SizedBox(
                    width: 220,
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        AutoTranslatedText(
                          request: _homeRequest(item, 'title', item.title),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.titleLarge?.copyWith(
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        if (item.subtitle.isNotEmpty) ...[
                          const SizedBox(height: 5),
                          AutoTranslatedText(
                            request: _homeRequest(
                              item,
                              'subtitle',
                              item.subtitle,
                            ),
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: colors.onSurfaceVariant,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _HorizontalContentList extends StatelessWidget {
  const _HorizontalContentList({required this.items, this.onOpenItem});

  final List<HomeContent> items;
  final ValueChanged<HomeContent>? onOpenItem;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 214,
      child: ListView.separated(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        scrollDirection: Axis.horizontal,
        itemCount: items.length,
        separatorBuilder: (_, __) => const SizedBox(width: 12),
        itemBuilder: (context, index) {
          final item = items[index];
          return _ProjectCard(
            item: item,
            onTap: () => onOpenItem?.call(item),
          );
        },
      ),
    );
  }
}

class _AiProjectList extends StatelessWidget {
  const _AiProjectList({required this.items, this.onOpenItem});

  final List<HomeContent> items;
  final ValueChanged<HomeContent>? onOpenItem;

  @override
  Widget build(BuildContext context) {
    final visibleItems = items.take(4).toList(growable: false);
    return SizedBox(
      height: 132,
      child: ListView.separated(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        scrollDirection: Axis.horizontal,
        itemCount: visibleItems.length,
        separatorBuilder: (_, __) => const SizedBox(width: 12),
        itemBuilder: (context, index) {
          final item = visibleItems[index];
          return _AiProjectCard(
            item: item,
            onTap: () => onOpenItem?.call(item),
          );
        },
      ),
    );
  }
}

class _AiProjectCard extends StatelessWidget {
  const _AiProjectCard({required this.item, required this.onTap});

  final HomeContent item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final institution = item.text(const ['institutionName']);
    return SizedBox(
      width: 280,
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(12),
                  child: _ContentImage(
                    imageUrl: item.imageUrl,
                    width: 80,
                    height: 80,
                    icon: Icons.auto_awesome_outlined,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (institution.isNotEmpty)
                        DecoratedBox(
                          decoration: BoxDecoration(
                            color: theme.colorScheme.primaryContainer,
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 6,
                              vertical: 2,
                            ),
                            child: AutoTranslatedText(
                              request: _homeRequest(
                                item,
                                'institutionName',
                                institution,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: theme.textTheme.labelSmall,
                            ),
                          ),
                        ),
                      const SizedBox(height: 5),
                      AutoTranslatedText(
                        request: _homeRequest(item, 'name', item.title),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.titleSmall,
                      ),
                      if (item.subtitle.isNotEmpty)
                        AutoTranslatedText(
                          request: _homeRequest(
                            item,
                            'slogan',
                            item.subtitle,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.labelSmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      if (item.category.isNotEmpty)
                        AutoTranslatedText(
                          request: _homeRequest(
                            item,
                            'category',
                            item.category,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.labelSmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      if (item.priceText.isNotEmpty) ...[
                        const SizedBox(height: 3),
                        Text(
                          item.priceText,
                          style: theme.textTheme.bodyMedium?.copyWith(
                            color: theme.colorScheme.primary,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle({
    required this.title,
    required this.action,
    this.onAction,
  });

  final String title;
  final String action;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 22, 16, 12),
      child: Row(
        children: [
          Expanded(child: Text(title, style: theme.textTheme.titleMedium)),
          TextButton(
            onPressed: onAction,
            style: TextButton.styleFrom(
              minimumSize: const Size(48, 40),
              padding: const EdgeInsets.symmetric(horizontal: 8),
              foregroundColor: theme.colorScheme.onSurfaceVariant,
              textStyle: theme.textTheme.bodySmall,
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(action),
                const SizedBox(width: 2),
                const Icon(Icons.chevron_right_rounded, size: 17),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ProjectCard extends StatelessWidget {
  const _ProjectCard({required this.item, required this.onTap});

  final HomeContent item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SizedBox(
      width: 160,
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _ContentImage(
                imageUrl: item.imageUrl,
                width: double.infinity,
                height: 100,
                icon: Icons.spa_outlined,
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(12, 10, 12, 8),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    AutoTranslatedText(
                      request: _homeRequest(item, 'name', item.title),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.titleSmall,
                    ),
                    const SizedBox(height: 3),
                    if (item.category.isNotEmpty)
                      AutoTranslatedText(
                        request: _homeRequest(
                          item,
                          'category',
                          item.category,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.labelSmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    const SizedBox(height: 5),
                    Text(
                      item.priceText.isEmpty
                          ? context.localized('价格咨询', 'Ask for price')
                          : item.priceText,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: theme.colorScheme.primary,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ArticleItem extends StatelessWidget {
  const _ArticleItem({required this.item, required this.onTap});

  final HomeContent item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final author = item.text(const ['authorName']);
    final publishDate = item.text(const ['publishDate', 'createdAt']);
    final readCount = item.count(const ['readCount']);
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            AspectRatio(
              aspectRatio: 2,
              child: _ContentImage(
                imageUrl: item.imageUrl,
                width: double.infinity,
                height: double.infinity,
                icon: Icons.auto_stories_outlined,
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 12, 14, 14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  AutoTranslatedText(
                    request: _homeRequest(item, 'title', item.title),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  if (item.category.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    AutoTranslatedText(
                      request: _homeRequest(
                        item,
                        'category',
                        item.category,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: theme.colorScheme.primary,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                  if (item.subtitle.isNotEmpty) ...[
                    const SizedBox(height: 5),
                    AutoTranslatedText(
                      request: _homeRequest(
                        item,
                        'summary',
                        item.subtitle,
                      ),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      if (author.isNotEmpty)
                        Expanded(
                          child: Text(
                            author,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.labelMedium?.copyWith(
                              color: theme.colorScheme.primary,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        )
                      else
                        const Spacer(),
                      if (publishDate.isNotEmpty)
                        Text(
                          publishDate,
                          style: theme.textTheme.labelSmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      if (readCount > 0) ...[
                        const SizedBox(width: 10),
                        Icon(
                          Icons.visibility_outlined,
                          size: 14,
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                        const SizedBox(width: 3),
                        Text(
                          '$readCount',
                          style: theme.textTheme.labelSmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _DiaryCard extends StatelessWidget {
  const _DiaryCard({required this.item, required this.onTap});

  final HomeContent item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final author = item.text(const ['authorName', 'nickname']);
    final avatar = item.text(
      const ['authorAvatar', 'avatarUrl', 'avatar'],
    );
    final publishDate = item.text(const ['publishDate', 'createdAt']);
    final project = item.text(const ['projectName']);
    final likes = item.count(const ['likeCount']);
    final favorites = item.count(const ['favoriteCount']);
    final comments = item.count(const ['commentCount']);
    final images = item.values(const ['imageUrls', 'images']);
    final beforeImages = item.values(const ['beforeImageUrls', 'beforeImages']);
    final afterImages = item.values(const ['afterImageUrls', 'afterImages']);
    return SizedBox(
      width: 300,
      child: DiaryPreviewCard(
        enableAutoTranslation: true,
        autoTranslationContentId: '${item.kind.name}:${item.id}',
        title: item.title,
        content: item.subtitle,
        authorName: author,
        authorAvatar: avatar,
        publishDate: publishDate,
        projectName: project,
        images: images,
        beforeImages: beforeImages,
        afterImages: afterImages,
        likeCount: likes,
        favoriteCount: favorites,
        commentCount: comments,
        isLiked: item.flag(const ['isLiked']),
        isFavorited: item.flag(const ['isFavorited']),
        onTap: onTap,
      ),
    );
  }
}

class _InstitutionItem extends StatelessWidget {
  const _InstitutionItem({required this.item, required this.onTap});

  final HomeContent item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final address = item.text(const ['address', 'city']);
    final description = item.text(const ['description']);
    final rating = item.number(const ['rating']);
    final reviews = item.count(const ['reviewCount']);
    return Card(
      margin: EdgeInsets.zero,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(10),
                child: _ContentImage(
                  imageUrl: item.imageUrl,
                  width: 72,
                  height: 72,
                  icon: Icons.local_hospital_outlined,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: AutoTranslatedText(
                            request: _homeRequest(item, 'name', item.title),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.titleSmall,
                          ),
                        ),
                        if (item.flag(const ['isVerified'])) ...[
                          const SizedBox(width: 4),
                          Icon(
                            Icons.verified_rounded,
                            size: 17,
                            color: theme.colorScheme.primary,
                          ),
                        ],
                      ],
                    ),
                    if (address.isNotEmpty) ...[
                      const SizedBox(height: 4),
                      AutoTranslatedText(
                        request: _homeRequest(item, 'address', address),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                    if (description.isNotEmpty) ...[
                      const SizedBox(height: 4),
                      AutoTranslatedText(
                        request: _homeRequest(
                          item,
                          'description',
                          description,
                        ),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                    if (rating > 0 || reviews > 0) ...[
                      const SizedBox(height: 7),
                      Row(
                        children: [
                          Icon(
                            Icons.star_rounded,
                            size: 16,
                            color: theme.colorScheme.primary,
                          ),
                          const SizedBox(width: 3),
                          Text(
                            rating.toStringAsFixed(1),
                            style: theme.textTheme.labelMedium,
                          ),
                          if (reviews > 0) ...[
                            const SizedBox(width: 8),
                            Text(
                              context.localized(
                                  '$reviews 条评价', '$reviews reviews'),
                              style: theme.textTheme.labelSmall?.copyWith(
                                color: theme.colorScheme.onSurfaceVariant,
                              ),
                            ),
                          ],
                        ],
                      ),
                    ],
                  ],
                ),
              ),
              const Icon(Icons.chevron_right_rounded),
            ],
          ),
        ),
      ),
    );
  }
}

class _DoctorList extends StatelessWidget {
  const _DoctorList({required this.items, this.onOpenItem});

  final List<HomeContent> items;
  final ValueChanged<HomeContent>? onOpenItem;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 206,
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        scrollDirection: Axis.horizontal,
        itemCount: items.length,
        separatorBuilder: (_, __) => const SizedBox(width: 12),
        itemBuilder: (context, index) {
          final item = items[index];
          return _DoctorCard(
            item: item,
            onTap: () => onOpenItem?.call(item),
          );
        },
      ),
    );
  }
}

class _DoctorCard extends StatelessWidget {
  const _DoctorCard({required this.item, required this.onTap});

  final HomeContent item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final title =
        item.text(const ['professionalTitle', 'doctorTitle', 'title']);
    final specialties = item.values(const ['specialties']);
    final institution = item.text(const ['institutionName']);
    return SizedBox(
      width: 132,
      child: Card(
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(12),
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              children: [
                ClipOval(
                  child: _ContentImage(
                    imageUrl: item.imageUrl,
                    width: 58,
                    height: 58,
                    icon: Icons.person_outline_rounded,
                  ),
                ),
                const SizedBox(height: 8),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Flexible(
                      child: Text(
                        item.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.titleSmall,
                      ),
                    ),
                    if (item.flag(const ['isVerified'])) ...[
                      const SizedBox(width: 3),
                      Icon(
                        Icons.verified_rounded,
                        size: 15,
                        color: theme.colorScheme.primary,
                      ),
                    ],
                  ],
                ),
                const SizedBox(height: 3),
                if (title.isNotEmpty)
                  AutoTranslatedText(
                    request: _homeRequest(
                      item,
                      'professionalTitle',
                      title,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                if (specialties.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  AutoTranslatedText(
                    request: _homeRequest(
                      item,
                      'specialties',
                      specialties.take(2).join(' · '),
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
                if (institution.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  AutoTranslatedText(
                    request: _homeRequest(
                      item,
                      'institutionName',
                      institution,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ContentImage extends StatelessWidget {
  const _ContentImage({
    required this.imageUrl,
    required this.width,
    required this.height,
    required this.icon,
  });

  final String imageUrl;
  final double width;
  final double height;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final fallback = Container(
      width: width,
      height: height,
      color: Theme.of(context).colorScheme.surfaceContainerLow,
      alignment: Alignment.center,
      child: Icon(icon, color: Theme.of(context).colorScheme.primary),
    );
    if (imageUrl.isEmpty) {
      return fallback;
    }
    return SizedBox(
      width: width,
      height: height,
      child: OptimizedNetworkImage(
        url: imageUrl,
        width: width,
        height: height,
        errorBuilder: (_, __, ___) => fallback,
      ),
    );
  }
}

class _HomeFailure extends StatelessWidget {
  const _HomeFailure({required this.message, required this.onRetry});

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
            const Icon(Icons.cloud_off_outlined, size: 44),
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

class _HomeEmpty extends StatelessWidget {
  const _HomeEmpty({
    required this.onRefresh,
    required this.hasPartialFailures,
  });

  final Future<void> Function() onRefresh;
  final bool hasPartialFailures;

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      onRefresh: onRefresh,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        children: [
          const SizedBox(height: 180),
          const Icon(Icons.inbox_outlined, size: 44),
          const SizedBox(height: 12),
          Center(
            child: Text(
              _isEnglish(context)
                  ? hasPartialFailures
                      ? 'Some sections are temporarily unavailable. Pull down to retry.'
                      : 'No recommendations yet. Pull down to refresh.'
                  : hasPartialFailures
                      ? '部分内容暂时无法加载，下拉重试'
                      : '暂时没有推荐内容，下拉刷新试试',
              textAlign: TextAlign.center,
            ),
          ),
        ],
      ),
    );
  }
}

class _PartialHomeWarning extends StatelessWidget {
  const _PartialHomeWarning();

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: colors.secondaryContainer,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          child: Row(
            children: [
              Icon(Icons.info_outline_rounded,
                  color: colors.onSecondaryContainer),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  _isEnglish(context)
                      ? 'Some sections are temporarily unavailable. Pull down to retry.'
                      : '部分板块暂时无法加载，可下拉重试',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: colors.onSecondaryContainer,
                      ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _HomeDataUnavailable extends StatelessWidget {
  const _HomeDataUnavailable();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Text(
          _isEnglish(context)
              ? 'Home data is unavailable. Check the service connection and try again.'
              : '首页数据暂不可用，请检查服务连接后重试',
          textAlign: TextAlign.center,
        ),
      ),
    );
  }
}

bool _isEnglish(BuildContext context) =>
    Localizations.localeOf(context).languageCode == 'en';

AutoTranslationRequest _homeRequest(
  HomeContent item,
  String field,
  String source,
) {
  return AutoTranslationRequest(
    contentType: switch (item.kind) {
      HomeSectionKind.banner => 'general',
      HomeSectionKind.hotProject ||
      HomeSectionKind.recommendedInstitutionProject =>
        'project',
      HomeSectionKind.expertArticle => 'article',
      HomeSectionKind.userDiary => 'diary',
      HomeSectionKind.institution => 'institution',
      HomeSectionKind.doctor => 'doctor',
    },
    contentId: '${item.kind.name}:${item.id}',
    field: field,
    sourceText: source,
  );
}

const _previewFeed = HomeFeed(
  banners: [
    HomeContent(
      id: 'preview-banner',
      title: '安心变美，从了解开始',
      subtitle: '先了解适应症、风险与恢复期，再做适合自己的决定',
      kind: HomeSectionKind.banner,
    ),
  ],
  hotProjects: [
    HomeContent(
      id: 'preview-project-1',
      title: '光电焕肤',
      subtitle: '温和改善肤色',
      kind: HomeSectionKind.hotProject,
    ),
    HomeContent(
      id: 'preview-project-2',
      title: '皮肤管理',
      subtitle: '日常维养方案',
      kind: HomeSectionKind.hotProject,
    ),
  ],
  expertArticles: [
    HomeContent(
      id: 'preview-article',
      title: '医美项目前，需要确认哪些关键信息',
      subtitle: '资质、适应症、风险与恢复期都值得提前了解',
      kind: HomeSectionKind.expertArticle,
    ),
  ],
  userDiaries: [
    HomeContent(
      id: 'preview-diary',
      title: '真实记录 · 理性分享',
      subtitle: '从咨询、面诊到恢复，把每一步的感受和问题记录下来。',
      kind: HomeSectionKind.userDiary,
    ),
  ],
);
