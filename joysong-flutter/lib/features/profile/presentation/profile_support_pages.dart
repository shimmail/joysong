import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/core/transient_message.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';

class FavoritesPage extends StatefulWidget {
  const FavoritesPage({
    required this.repository,
    this.onOpen,
    super.key,
  });

  final SocialRepository repository;
  final Future<void> Function(FavoriteItem item)? onOpen;

  @override
  State<FavoritesPage> createState() => _FavoritesPageState();
}

class _FavoritesPageState extends State<FavoritesPage> {
  static const _pageSize = 30;
  static const _tabs = <FavoriteTargetType?>[
    null,
    FavoriteTargetType.project,
    FavoriteTargetType.institution,
    FavoriteTargetType.doctor,
    FavoriteTargetType.diary,
    FavoriteTargetType.article,
  ];
  final _items = <FavoriteItem>[];
  bool _loading = false;
  bool _loadingMore = false;
  bool _hasMore = true;
  String? _errorMessage;
  final _removingIds = <String>{};
  FavoriteTargetType? _selectedType;

  @override
  void initState() {
    super.initState();
    _load(refresh: true);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        centerTitle: true,
        title: Text(context.localized('我的收藏', 'My favorites')),
      ),
      body: Column(
        children: [
          _categoryBar(),
          Expanded(child: _body()),
        ],
      ),
    );
  }

  Widget _categoryBar() {
    return Material(
      color: Theme.of(context).colorScheme.surface,
      child: DefaultTabController(
        length: _tabs.length,
        child: TabBar(
          isScrollable: true,
          tabAlignment: TabAlignment.start,
          padding: EdgeInsets.zero,
          labelPadding: const EdgeInsets.symmetric(horizontal: 18),
          indicatorColor: Theme.of(context).colorScheme.onSurface,
          indicatorWeight: 2,
          labelColor: Theme.of(context).colorScheme.onSurface,
          unselectedLabelColor: Theme.of(context).colorScheme.onSurfaceVariant,
          labelStyle: const TextStyle(fontWeight: FontWeight.w700),
          unselectedLabelStyle: const TextStyle(fontWeight: FontWeight.w400),
          onTap: (index) => setState(() => _selectedType = _tabs[index]),
          tabs: [
            for (final type in _tabs)
              Tab(
                text: type == null
                    ? context.localized('全部', 'All')
                    : _targetLabel(context, type),
              ),
          ],
        ),
      ),
    );
  }

  Widget _body() {
    if (_loading && _items.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_errorMessage != null && _items.isEmpty) {
      return _MessageState(
        icon: Icons.cloud_off_outlined,
        message: _errorMessage!,
        actionLabel: context.localized('重试', 'Retry'),
        onAction: () => _load(refresh: true),
      );
    }
    if (_items.isEmpty) {
      return RefreshIndicator(
        onRefresh: () => _load(refresh: true),
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: [
            const SizedBox(height: 180),
            const Icon(Icons.favorite_border_rounded, size: 48),
            const SizedBox(height: 12),
            Center(
              child: Text(
                context.localized('还没有收藏内容', 'No favorites yet'),
              ),
            ),
          ],
        ),
      );
    }
    final visibleItems = _selectedType == null
        ? _items
        : _items.where((item) => item.targetType == _selectedType).toList();
    return visibleItems.isEmpty
        ? RefreshIndicator(
            onRefresh: () => _load(refresh: true),
            child: ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              children: [
                const SizedBox(height: 150),
                const Icon(Icons.favorite_border_rounded, size: 44),
                const SizedBox(height: 12),
                Center(
                  child: Text(
                    context.isEnglish
                        ? 'No ${_targetLabel(context, _selectedType!).toLowerCase()} favorites yet'
                        : '暂无${_targetLabel(context, _selectedType!)}收藏',
                  ),
                ),
              ],
            ),
          )
        : _favoritesList(visibleItems);
  }

  Widget _favoritesList(List<FavoriteItem> visibleItems) {
    return RefreshIndicator(
      onRefresh: () => _load(refresh: true),
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
        itemCount: visibleItems.length + 1,
        separatorBuilder: (_, __) => const SizedBox(height: 8),
        itemBuilder: (context, index) {
          if (index == visibleItems.length) {
            if (_loadingMore) {
              return const Padding(
                padding: EdgeInsets.all(16),
                child: Center(child: CircularProgressIndicator()),
              );
            }
            if (_errorMessage != null) {
              return TextButton(
                onPressed: _loadMore,
                child: Text(
                  context.isEnglish
                      ? '${_errorMessage!} Tap to retry'
                      : '${_errorMessage!}，点击重试',
                ),
              );
            }
            if (_hasMore) {
              return TextButton(
                onPressed: _loadMore,
                child: Text(context.localized('加载更多', 'Load more')),
              );
            }
            return Padding(
              padding: const EdgeInsets.all(12),
              child: Center(
                child: Text(context.localized('没有更多了', 'No more items')),
              ),
            );
          }
          final item = visibleItems[index];
          return Card(
            margin: EdgeInsets.zero,
            elevation: 0,
            color: Colors.white,
            child: ListTile(
              leading: _FavoriteImage(item: item),
              title: Text(
                item.targetName.isEmpty
                    ? _targetLabel(context, item.targetType)
                    : item.targetName,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
              subtitle: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const SizedBox(height: 4),
                  Text(_targetLabel(context, item.targetType)),
                  if (item.createdAt != null) ...[
                    const SizedBox(height: 3),
                    Text(
                      context.isEnglish
                          ? 'Saved ${_favoriteDate(item.createdAt!)}'
                          : '收藏于 ${_favoriteDate(item.createdAt!)}',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ],
              ),
              onTap: widget.onOpen == null
                  ? null
                  : () async {
                      await widget.onOpen!(item);
                      if (mounted) await _load(refresh: true);
                    },
              trailing: IconButton(
                tooltip: context.localized('取消收藏', 'Remove from favorites'),
                onPressed: _removingIds.contains(item.id) ||
                        item.targetType == FavoriteTargetType.unknown
                    ? null
                    : () => _remove(item),
                icon: _removingIds.contains(item.id)
                    ? const SizedBox.square(
                        dimension: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.delete_outline_rounded),
              ),
            ),
          );
        },
      ),
    );
  }

  Future<void> _load({required bool refresh}) async {
    if (_loading || _loadingMore) return;
    setState(() {
      _loading = true;
      _errorMessage = null;
    });
    try {
      final page = await widget.repository.getFavorites(
        offset: refresh ? 0 : _items.length,
        limit: _pageSize,
      );
      if (!mounted) return;
      setState(() {
        if (refresh) _items.clear();
        final ids = _items.map((item) => item.id).toSet();
        _items.addAll(page.where((item) => ids.add(item.id)));
        _hasMore = page.length == _pageSize;
      });
    } catch (error) {
      if (mounted) setState(() => _errorMessage = _messageFor(context, error));
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _loadMore() async {
    if (_loading || _loadingMore || !_hasMore) return;
    setState(() {
      _loadingMore = true;
      _errorMessage = null;
    });
    try {
      final page = await widget.repository.getFavorites(
        offset: _items.length,
        limit: _pageSize,
      );
      if (!mounted) return;
      setState(() {
        final ids = _items.map((item) => item.id).toSet();
        _items.addAll(page.where((item) => ids.add(item.id)));
        _hasMore = page.length == _pageSize;
      });
    } catch (error) {
      if (mounted) setState(() => _errorMessage = _messageFor(context, error));
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  Future<void> _remove(FavoriteItem item) async {
    final index = _items.indexWhere((candidate) => candidate.id == item.id);
    if (index < 0) return;
    setState(() {
      _removingIds.add(item.id);
      _items.removeAt(index);
    });
    try {
      await widget.repository.setFavorited(
        item.targetType,
        item.targetId,
        false,
      );
      if (!mounted) return;
      showTransientMessage(
        context,
        context.localized('已取消收藏', 'Removed from favorites'),
      );
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _items.insert(index.clamp(0, _items.length), item);
        _errorMessage = _messageFor(context, error);
      });
      showTransientMessage(context, _errorMessage!);
    } finally {
      if (mounted) setState(() => _removingIds.remove(item.id));
    }
  }
}

class AboutJoysongPage extends StatelessWidget {
  const AboutJoysongPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('关于我们')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(24, 24, 24, 32),
        children: [
          Center(
            child: CircleAvatar(
              radius: 46,
              backgroundColor: Theme.of(context).colorScheme.primaryContainer,
              foregroundColor: Theme.of(context).colorScheme.onPrimaryContainer,
              child: const Icon(Icons.spa_outlined, size: 44),
            ),
          ),
          const SizedBox(height: 16),
          Text(
            '娇颜颂',
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 4),
          Text(
            '版本 0.1.0 (1)',
            textAlign: TextAlign.center,
            style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant),
          ),
          const SizedBox(height: 28),
          const Card(
            child: Padding(
              padding: EdgeInsets.all(18),
              child: Text(
                '娇颜颂致力于为用户提供清晰、审慎的医美信息与服务连接。'
                '平台内容仅供参考，医疗决策请以具有合法资质的专业人员当面评估为准。',
              ),
            ),
          ),
          const SizedBox(height: 12),
          const Card(
            child: Column(
              children: [
                ListTile(
                  leading: Icon(Icons.privacy_tip_outlined),
                  title: Text('隐私保护'),
                  subtitle: Text('敏感资料与公开内容使用独立上传边界'),
                ),
                ListTile(
                  leading: Icon(Icons.verified_user_outlined),
                  title: Text('安全声明'),
                  subtitle: Text('不在日志中记录令牌、验证码和证件信息'),
                ),
              ],
            ),
          ),
          const SizedBox(height: 24),
          Text(
            '© 2026 娇颜颂',
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
    );
  }
}

class _FavoriteImage extends StatelessWidget {
  const _FavoriteImage({required this.item});

  final FavoriteItem item;

  @override
  Widget build(BuildContext context) {
    final isDoctor = item.targetType == FavoriteTargetType.doctor;
    final imageSize = isDoctor ? 56.0 : 72.0;
    final radius = isDoctor ? imageSize / 2 : 10.0;
    final fallback = Container(
      width: imageSize,
      height: imageSize,
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.primaryContainer,
        borderRadius: BorderRadius.circular(radius),
      ),
      alignment: Alignment.center,
      child: Icon(_targetIcon(item.targetType)),
    );
    if (item.targetImage.isEmpty) return fallback;
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: Image.network(
        item.targetImage,
        width: imageSize,
        height: imageSize,
        fit: BoxFit.cover,
        errorBuilder: (_, __, ___) => fallback,
      ),
    );
  }
}

class _MessageState extends StatelessWidget {
  const _MessageState({
    required this.icon,
    required this.message,
    required this.actionLabel,
    required this.onAction,
  });

  final IconData icon;
  final String message;
  final String actionLabel;
  final VoidCallback onAction;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 44),
          const SizedBox(height: 12),
          Text(message),
          const SizedBox(height: 14),
          FilledButton.tonal(onPressed: onAction, child: Text(actionLabel)),
        ],
      ),
    );
  }
}

String _targetLabel(BuildContext context, FavoriteTargetType type) =>
    switch (type) {
      FavoriteTargetType.diary => context.localized('日记', 'Diaries'),
      FavoriteTargetType.project => context.localized('项目', 'Projects'),
      FavoriteTargetType.institution => context.localized('机构', 'Institutions'),
      FavoriteTargetType.doctor => context.localized('医生', 'Doctors'),
      FavoriteTargetType.article => context.localized('文章', 'Articles'),
      FavoriteTargetType.unknown => context.localized('其他', 'Other'),
    };

IconData _targetIcon(FavoriteTargetType type) => switch (type) {
      FavoriteTargetType.diary => Icons.auto_stories_outlined,
      FavoriteTargetType.project => Icons.spa_outlined,
      FavoriteTargetType.institution => Icons.apartment_outlined,
      FavoriteTargetType.doctor => Icons.medical_services_outlined,
      FavoriteTargetType.article => Icons.article_outlined,
      FavoriteTargetType.unknown => Icons.favorite_border_rounded,
    };

String _favoriteDate(DateTime value) {
  final local = value.toLocal();
  final month = local.month.toString().padLeft(2, '0');
  final day = local.day.toString().padLeft(2, '0');
  return '${local.year}-$month-$day';
}

String _messageFor(BuildContext context, Object error) =>
    error is ApiException && error.message.isNotEmpty
        ? error.message
        : context.localized('收藏内容加载失败', 'Failed to load favorites');
