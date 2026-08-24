import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/network/optimized_network_image.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_controller.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_content_card.dart';
import 'package:joysong_flutter/features/discover/presentation/article_detail_view.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_institution_detail_view.dart';
import 'package:joysong_flutter/features/discover/presentation/doctor_detail_view.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_project_detail_view.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_review_section.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/presentation/diary_detail_page.dart';
import 'package:joysong_flutter/features/social/presentation/favorite_action_button.dart';
import 'package:joysong_flutter/features/social/presentation/report_action_button.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';

class DiscoverPage extends StatefulWidget {
  const DiscoverPage({
    this.repository,
    this.onBookProject,
    this.socialController,
    this.onOpenUser,
    this.onConsultDoctor,
    this.onConsultInstitution,
    this.onOpenAi,
    this.initialType = DiscoverContentType.all,
    super.key,
  });

  final DiscoverRepository? repository;
  final ValueChanged<DiscoverItem>? onBookProject;
  final SocialController? socialController;
  final ValueChanged<String>? onOpenUser;
  final ValueChanged<DiscoverItem>? onConsultDoctor;
  final ValueChanged<String>? onConsultInstitution;
  final ValueChanged<DiscoverItem>? onOpenAi;
  final DiscoverContentType initialType;

  @override
  State<DiscoverPage> createState() => _DiscoverPageState();
}

class _DiscoverPageState extends State<DiscoverPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;
  late Future<DiscoverFilterOptions> _filterOptions;
  final _searchController = TextEditingController();
  final _searchFocusNode = FocusNode();
  Timer? _searchDebounce;
  String _query = '';
  bool _searchFocused = false;
  final List<String> _searchHistory = [];
  late DiscoverContentType _selectedType;
  List<String> _categories = const [];
  List<String> _cities = const [];
  List<String> _tags = const [];

  @override
  void initState() {
    super.initState();
    _selectedType = widget.initialType;
    _tabController = TabController(
      length: DiscoverContentType.values.length,
      initialIndex: DiscoverContentType.values.indexOf(widget.initialType),
      vsync: this,
    )..addListener(_handleTabChange);
    _filterOptions = widget.repository?.loadFilterOptions() ??
        Future.value(const DiscoverFilterOptions());
    _searchFocusNode.addListener(_handleSearchFocus);
  }

  @override
  void didUpdateWidget(covariant DiscoverPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.repository != widget.repository) {
      _filterOptions = widget.repository?.loadFilterOptions() ??
          Future.value(const DiscoverFilterOptions());
    }
  }

  void _handleTabChange() {
    if (_tabController.indexIsChanging) {
      return;
    }
    final next = DiscoverContentType.values[_tabController.index];
    if (next != _selectedType) {
      setState(() => _selectedType = next);
    }
  }

  void _onSearchChanged(String value) {
    _searchDebounce?.cancel();
    _searchDebounce = Timer(const Duration(milliseconds: 350), () {
      if (mounted) {
        setState(() => _query = value.trim());
      }
    });
  }

  void _handleSearchFocus() {
    if (mounted) setState(() => _searchFocused = _searchFocusNode.hasFocus);
  }

  void _submitSearch(String value) {
    _searchDebounce?.cancel();
    final query = value.trim();
    setState(() {
      _query = query;
      if (query.isNotEmpty) {
        _searchHistory
          ..remove(query)
          ..insert(0, query);
        if (_searchHistory.length > 10) _searchHistory.removeLast();
      }
    });
    _searchFocusNode.unfocus();
  }

  void _useHistory(String value) {
    _searchController.text = value;
    _searchController.selection = TextSelection.collapsed(offset: value.length);
    _submitSearch(value);
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _searchFocusNode
      ..removeListener(_handleSearchFocus)
      ..dispose();
    _tabController
      ..removeListener(_handleTabChange)
      ..dispose();
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 10),
          child: SizedBox(
            height: 48,
            child: TextField(
              key: const Key('discover-search-field'),
              controller: _searchController,
              focusNode: _searchFocusNode,
              onChanged: _onSearchChanged,
              onSubmitted: _submitSearch,
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                hintText: context.localized(
                  '搜索项目、医生、机构、日记或文章',
                  'Search projects, doctors, institutions, diaries or articles',
                ),
                prefixIcon: const Icon(Icons.search_rounded, size: 20),
                suffixIcon: _searchController.text.isEmpty
                    ? null
                    : IconButton(
                        tooltip: context.localized('清空搜索', 'Clear search'),
                        onPressed: () {
                          _searchDebounce?.cancel();
                          _searchController.clear();
                          setState(() => _query = '');
                        },
                        icon: const Icon(Icons.close_rounded, size: 18),
                      ),
                fillColor: colors.surfaceContainerLow,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(20),
                  borderSide: BorderSide.none,
                ),
              ),
            ),
          ),
        ),
        if (_searchFocused &&
            _searchController.text.isEmpty &&
            _searchHistory.isNotEmpty)
          _SearchHistory(
            values: _searchHistory,
            onSelected: _useHistory,
            onRemoved: (value) => setState(() => _searchHistory.remove(value)),
            onClear: () => setState(_searchHistory.clear),
          ),
        TabBar(
          controller: _tabController,
          isScrollable: true,
          tabAlignment: TabAlignment.start,
          dividerHeight: 0.5,
          indicatorSize: TabBarIndicatorSize.label,
          indicatorWeight: 2,
          indicatorColor: colors.onSurface,
          labelColor: colors.onSurface,
          unselectedLabelColor: colors.onSurfaceVariant,
          labelStyle: theme.textTheme.labelLarge?.copyWith(
            fontWeight: FontWeight.w600,
          ),
          unselectedLabelStyle: theme.textTheme.labelLarge,
          tabs: [
            for (final type in DiscoverContentType.values)
              Tab(text: _typeLabel(context, type)),
          ],
        ),
        if (_selectedType == DiscoverContentType.project)
          FutureBuilder<DiscoverFilterOptions>(
            future: _filterOptions,
            builder: (context, snapshot) {
              final options = snapshot.data;
              if (options == null ||
                  (options.categories.isEmpty &&
                      options.cities.isEmpty &&
                      options.tags.isEmpty)) {
                return const SizedBox.shrink();
              }
              return _DiscoverFilters(
                options: options,
                categories: _categories,
                cities: _cities,
                tags: _tags,
                onChanged: (categories, cities, tags) => setState(() {
                  _categories = categories;
                  _cities = cities;
                  _tags = tags;
                }),
              );
            },
          ),
        Expanded(
          child: TabBarView(
            controller: _tabController,
            children: [
              for (final type in DiscoverContentType.values)
                _DiscoveryResultsPane(
                  repository: widget.repository,
                  type: type,
                  query: _query,
                  categories: _categories,
                  cities: _cities,
                  tags: _tags,
                  onBookProject: widget.onBookProject,
                  socialController: widget.socialController,
                  onOpenUser: widget.onOpenUser,
                  onConsultDoctor: widget.onConsultDoctor,
                  onConsultInstitution: widget.onConsultInstitution,
                  onOpenAi: widget.onOpenAi,
                ),
            ],
          ),
        ),
      ],
    );
  }
}

class _SearchHistory extends StatelessWidget {
  const _SearchHistory({
    required this.values,
    required this.onSelected,
    required this.onRemoved,
    required this.onClear,
  });

  final List<String> values;
  final ValueChanged<String> onSelected;
  final ValueChanged<String> onRemoved;
  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  context.localized('搜索历史', 'Search history'),
                  style: Theme.of(context).textTheme.labelLarge,
                ),
              ),
              IconButton(
                tooltip: context.localized('清空搜索历史', 'Clear search history'),
                onPressed: onClear,
                visualDensity: VisualDensity.compact,
                icon: const Icon(Icons.delete_outline_rounded, size: 19),
              ),
            ],
          ),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final value in values)
                InputChip(
                  label: Text(value),
                  onPressed: () => onSelected(value),
                  onDeleted: () => onRemoved(value),
                  deleteIcon: const Icon(Icons.close_rounded, size: 16),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _DiscoverFilters extends StatelessWidget {
  const _DiscoverFilters({
    required this.options,
    required this.categories,
    required this.cities,
    required this.tags,
    required this.onChanged,
  });

  final DiscoverFilterOptions options;
  final List<String> categories;
  final List<String> cities;
  final List<String> tags;
  final void Function(
    List<String> categories,
    List<String> cities,
    List<String> tags,
  ) onChanged;

  @override
  Widget build(BuildContext context) {
    final selected = [...categories, ...tags, ...cities];
    return SizedBox(
      height: 52,
      child: ListView(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
        scrollDirection: Axis.horizontal,
        children: [
          OutlinedButton.icon(
            onPressed: () async {
              final result = await showModalBottomSheet<
                  ({
                    List<String> categories,
                    List<String> cities,
                    List<String> tags,
                  })>(
                context: context,
                isScrollControlled: true,
                useSafeArea: true,
                constraints: const BoxConstraints(maxWidth: 420),
                builder: (_) => _FilterSheet(
                  options: options,
                  categories: categories,
                  cities: cities,
                  tags: tags,
                ),
              );
              if (result != null) {
                onChanged(result.categories, result.cities, result.tags);
              }
            },
            icon: const Icon(Icons.tune_rounded, size: 18),
            label: Text(
              selected.isEmpty
                  ? context.localized('筛选', 'Filters')
                  : context.isEnglish
                      ? 'Filters (${selected.length})'
                      : '筛选 (${selected.length})',
            ),
          ),
          for (final value in selected) ...[
            const SizedBox(width: 8),
            Chip(label: Text(value)),
          ],
        ],
      ),
    );
  }
}

enum _FilterKind { category, city, tag }

class _FilterSheet extends StatefulWidget {
  const _FilterSheet({
    required this.options,
    required this.categories,
    required this.cities,
    required this.tags,
  });

  final DiscoverFilterOptions options;
  final List<String> categories;
  final List<String> cities;
  final List<String> tags;

  @override
  State<_FilterSheet> createState() => _FilterSheetState();
}

class _FilterSheetState extends State<_FilterSheet> {
  _FilterKind _kind = _FilterKind.category;
  late Set<String> _categories;
  late Set<String> _cities;
  late Set<String> _tags;

  @override
  void initState() {
    super.initState();
    _categories = widget.categories.toSet();
    _cities = widget.cities.toSet();
    _tags = widget.tags.toSet();
  }

  Set<String> get _selected => switch (_kind) {
        _FilterKind.category => _categories,
        _FilterKind.city => _cities,
        _FilterKind.tag => _tags,
      };

  List<String> get _values => switch (_kind) {
        _FilterKind.category => widget.options.categories,
        _FilterKind.city => widget.options.cities,
        _FilterKind.tag => widget.options.tags,
      };

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: MediaQuery.sizeOf(context).height * .78,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 12, 8),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    context.localized('项目筛选', 'Project filters'),
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                ),
                IconButton(
                  tooltip: context.localized('关闭', 'Close'),
                  onPressed: () => Navigator.of(context).pop(),
                  icon: const Icon(Icons.close_rounded),
                ),
              ],
            ),
          ),
          const Divider(height: 1),
          Expanded(
            child: Row(
              children: [
                SizedBox(
                  width: 112,
                  child: ColoredBox(
                    color: Theme.of(context).colorScheme.surfaceContainerLow,
                    child: ListView(
                      children: [
                        for (final kind in _FilterKind.values)
                          _FilterNavigationItem(
                            label: switch (kind) {
                              _FilterKind.category =>
                                context.localized('项目类别', 'Categories'),
                              _FilterKind.city =>
                                context.localized('城市', 'Cities'),
                              _FilterKind.tag =>
                                context.localized('项目标签', 'Tags'),
                            },
                            count: switch (kind) {
                              _FilterKind.category => _categories.length,
                              _FilterKind.city => _cities.length,
                              _FilterKind.tag => _tags.length,
                            },
                            selected: _kind == kind,
                            onTap: () => setState(() => _kind = kind),
                          ),
                      ],
                    ),
                  ),
                ),
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.all(16),
                    child: Wrap(
                      spacing: 9,
                      runSpacing: 9,
                      children: [
                        for (final value in _values)
                          FilterChip(
                            label: Text(value),
                            selected: _selected.contains(value),
                            onSelected: (selected) => setState(() {
                              if (selected) {
                                _selected.add(value);
                              } else {
                                _selected.remove(value);
                              }
                            }),
                          ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
          const Divider(height: 1),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 14),
            child: Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: () => setState(() {
                      _categories.clear();
                      _cities.clear();
                      _tags.clear();
                    }),
                    child: Text(context.localized('重置', 'Reset')),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  flex: 2,
                  child: FilledButton(
                    onPressed: () => Navigator.of(context).pop((
                      categories: _categories.toList(growable: false),
                      cities: _cities.toList(growable: false),
                      tags: _tags.toList(growable: false),
                    )),
                    child: Text(context.localized('查看结果', 'Show results')),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _FilterNavigationItem extends StatelessWidget {
  const _FilterNavigationItem({
    required this.label,
    required this.count,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final int count;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: selected ? Theme.of(context).colorScheme.surface : null,
          border: Border(
            left: BorderSide(
              width: 3,
              color: selected
                  ? Theme.of(context).colorScheme.primary
                  : Colors.transparent,
            ),
          ),
        ),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label),
            if (count > 0)
              Text(
                context.isEnglish ? '$count selected' : '已选$count项',
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      color: Theme.of(context).colorScheme.primary,
                    ),
              ),
          ],
        ),
      ),
    );
  }
}

class _DiscoveryResultsPane extends StatefulWidget {
  const _DiscoveryResultsPane({
    required this.repository,
    required this.type,
    required this.query,
    required this.categories,
    required this.cities,
    required this.tags,
    this.onBookProject,
    this.socialController,
    this.onOpenUser,
    this.onConsultDoctor,
    this.onConsultInstitution,
    this.onOpenAi,
  });

  final DiscoverRepository? repository;
  final DiscoverContentType type;
  final String query;
  final List<String> categories;
  final List<String> cities;
  final List<String> tags;
  final ValueChanged<DiscoverItem>? onBookProject;
  final SocialController? socialController;
  final ValueChanged<String>? onOpenUser;
  final ValueChanged<DiscoverItem>? onConsultDoctor;
  final ValueChanged<String>? onConsultInstitution;
  final ValueChanged<DiscoverItem>? onOpenAi;

  @override
  State<_DiscoveryResultsPane> createState() => _DiscoveryResultsPaneState();
}

class _DiscoveryResultsPaneState extends State<_DiscoveryResultsPane> {
  DiscoverController? _controller;
  final _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    _createController();
  }

  @override
  void didUpdateWidget(covariant _DiscoveryResultsPane oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.repository != widget.repository ||
        oldWidget.type != widget.type) {
      _controller?.dispose();
      _createController();
      return;
    }
    if (oldWidget.query != widget.query ||
        !listEquals(oldWidget.categories, widget.categories) ||
        !listEquals(oldWidget.cities, widget.cities) ||
        !listEquals(oldWidget.tags, widget.tags)) {
      _load(refresh: true);
    }
  }

  void _createController() {
    final repository = widget.repository;
    if (repository == null) {
      _controller = null;
      return;
    }
    _controller = DiscoverController(repository, type: widget.type);
    _load();
  }

  Future<void> _load({bool refresh = false}) {
    return _controller?.load(
          query: widget.query,
          categories: widget.categories,
          cities: widget.cities,
          tags: widget.tags,
          refresh: refresh,
        ) ??
        Future.value();
  }

  void _onScroll() {
    if (_scrollController.position.extentAfter < 240) {
      _controller?.loadMore();
    }
  }

  @override
  void dispose() {
    _scrollController
      ..removeListener(_onScroll)
      ..dispose();
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = _controller;
    if (controller == null) {
      return _DiscoverList(
        controller: _scrollController,
        items: _previewItems
            .where(
              (item) =>
                  widget.type == DiscoverContentType.all ||
                  item.type == widget.type,
            )
            .where(
              (item) =>
                  widget.query.isEmpty ||
                  '${item.title}${item.subtitle}'.contains(widget.query),
            )
            .toList(),
        onRefresh: () async {},
        onOpen: _openDetail,
        onInstitutionProjectOpen: _openInstitutionProject,
      );
    }
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        return switch (controller.status) {
          DiscoverLoadStatus.idle || DiscoverLoadStatus.loading => const Center(
              child: CircularProgressIndicator(),
            ),
          DiscoverLoadStatus.failure => _DiscoverFailure(
              message: context.localized(
                '内容加载失败，请稍后重试',
                'Failed to load content. Please try again.',
              ),
              onRetry: () => _load(refresh: true),
            ),
          DiscoverLoadStatus.empty => _EmptySearch(query: widget.query),
          DiscoverLoadStatus.ready => _DiscoverList(
              controller: _scrollController,
              items: controller.items,
              isLoadingMore: controller.isLoadingMore,
              onRefresh: () => _load(refresh: true),
              onOpen: _openDetail,
              onInstitutionProjectOpen: _openInstitutionProject,
            ),
        };
      },
    );
  }

  void _openDetail(DiscoverItem item) {
    final repository = widget.repository;
    if (repository == null) {
      return;
    }
    final socialController = widget.socialController;
    if (item.type == DiscoverContentType.diary && socialController != null) {
      final diary = diaryFromDiscover(item);
      Navigator.of(context).push<void>(MaterialPageRoute(
        builder: (_) => DiaryDetailPage(
          controller: socialController,
          diary: diary,
          onAuthorTap: diary.userId.isEmpty || widget.onOpenUser == null
              ? null
              : () => widget.onOpenUser!(diary.userId),
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
      return;
    }
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => DiscoverDetailPage(
          repository: repository,
          type: item.type,
          id: item.id,
          initialItem: item,
          onBookProject: widget.onBookProject,
          socialController: widget.socialController,
          onOpenUser: widget.onOpenUser,
          onConsultDoctor: widget.onConsultDoctor,
          onConsultInstitution: widget.onConsultInstitution,
          onOpenAi: widget.onOpenAi,
        ),
      ),
    );
  }

  void _openInstitutionProject(String institutionId, String projectId) {
    final repository = widget.repository;
    if (repository == null ||
        institutionId.trim().isEmpty ||
        projectId.trim().isEmpty) {
      return;
    }
    Navigator.of(context).push<void>(MaterialPageRoute(
      builder: (_) => DiscoverDetailPage(
        repository: repository,
        type: DiscoverContentType.project,
        id: projectId.trim(),
        institutionId: institutionId.trim(),
        projectId: projectId.trim(),
        onBookProject: widget.onBookProject,
        socialController: widget.socialController,
        onOpenUser: widget.onOpenUser,
        onConsultDoctor: widget.onConsultDoctor,
        onConsultInstitution: widget.onConsultInstitution,
        onOpenAi: widget.onOpenAi,
      ),
    ));
  }

  void _openDiaryAssociation(
    DiscoverContentType type,
    String id, {
    String institutionId = '',
  }) {
    final repository = widget.repository;
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
        onBookProject: widget.onBookProject,
        socialController: widget.socialController,
        onOpenUser: widget.onOpenUser,
        onConsultDoctor: widget.onConsultDoctor,
        onConsultInstitution: widget.onConsultInstitution,
        onOpenAi: widget.onOpenAi,
      ),
    ));
  }
}

Diary diaryFromDiscover(DiscoverItem item) {
  final raw = item.raw;
  return Diary(
    id: item.id,
    title: item.title,
    userId: _rawText(raw['userId']),
    authorName: _rawText(raw['authorName'], fallback: item.meta),
    authorAvatar: _rawText(raw['authorAvatar']),
    content: _rawText(raw['content'], fallback: item.subtitle),
    coverImage: item.imageUrl,
    images: _rawList(raw['imageUrls'] ?? raw['images']),
    tags: _rawList(raw['tags']),
    likeCount: _rawInt(raw['likeCount']),
    commentCount: _rawInt(raw['commentCount']),
    favoriteCount: _rawInt(raw['favoriteCount']),
    isLiked: raw['isLiked'] == true,
    publishDate: _rawText(raw['publishDate']),
    createdAt: DateTime.tryParse(_rawText(raw['createdAt'])),
    rating: _rawInt(raw['rating']),
    status: _rawText(raw['status'], fallback: 'published'),
    doctorId: _rawText(raw['doctorId']),
    projectId: _rawText(raw['projectId']),
    institutionId: _rawText(raw['institutionId']),
    institutionProjectId: _rawText(raw['institutionProjectId']),
    orderId: _rawText(raw['orderId']),
    projectName: _rawText(raw['projectName']),
    doctorName: _rawText(raw['doctorName']),
    institutionName: _rawText(raw['institutionName']),
    beforeImages: _rawList(raw['beforeImageUrls'] ?? raw['beforeImages']),
    afterImages: _rawList(raw['afterImageUrls'] ?? raw['afterImages']),
  );
}

String _rawText(Object? value, {String fallback = ''}) {
  final text = value?.toString().trim() ?? '';
  return text.isEmpty ? fallback : text;
}

int _rawInt(Object? value) =>
    value is num ? value.toInt() : int.tryParse(value?.toString() ?? '') ?? 0;

List<String> _rawList(Object? value) => (value is List
        ? value.map((item) => item.toString())
        : (value?.toString() ?? '').split(','))
    .map((item) => item.trim())
    .where((item) => item.isNotEmpty)
    .toList(growable: false);

class _DiscoverList extends StatelessWidget {
  const _DiscoverList({
    required this.controller,
    required this.items,
    required this.onRefresh,
    required this.onOpen,
    required this.onInstitutionProjectOpen,
    this.isLoadingMore = false,
  });

  final ScrollController controller;
  final List<DiscoverItem> items;
  final bool isLoadingMore;
  final Future<void> Function() onRefresh;
  final ValueChanged<DiscoverItem> onOpen;
  final void Function(String institutionId, String projectId)
      onInstitutionProjectOpen;

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) {
      return const _EmptySearch(query: '');
    }
    return RefreshIndicator(
      onRefresh: onRefresh,
      child: ListView.separated(
        controller: controller,
        key: PageStorageKey<String>('discover-${items.first.type.name}'),
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 28),
        itemCount: items.length + (isLoadingMore ? 1 : 0),
        separatorBuilder: (_, __) => const SizedBox(height: 10),
        itemBuilder: (context, index) {
          if (index == items.length) {
            return const Center(
              child: Padding(
                padding: EdgeInsets.all(12),
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            );
          }
          final item = items[index];
          return DiscoverContentCard(
            key: ValueKey('discover-${item.type.name}-${item.id}'),
            item: item,
            onTap: () => onOpen(item),
            onInstitutionProjectTap: onInstitutionProjectOpen,
          );
        },
      ),
    );
  }
}

class DiscoverDetailPage extends StatefulWidget {
  const DiscoverDetailPage({
    required this.repository,
    required this.type,
    required this.id,
    this.initialItem,
    this.onBookProject,
    this.institutionId,
    this.projectId,
    this.socialController,
    this.onOpenUser,
    this.onConsultDoctor,
    this.onConsultInstitution,
    this.onOpenAi,
    super.key,
  });

  final DiscoverRepository repository;
  final DiscoverContentType type;
  final String id;
  final DiscoverItem? initialItem;
  final ValueChanged<DiscoverItem>? onBookProject;
  final String? institutionId;
  final String? projectId;
  final SocialController? socialController;
  final ValueChanged<String>? onOpenUser;
  final ValueChanged<DiscoverItem>? onConsultDoctor;
  final ValueChanged<String>? onConsultInstitution;
  final ValueChanged<DiscoverItem>? onOpenAi;

  @override
  State<DiscoverDetailPage> createState() => _DiscoverDetailPageState();
}

class _DiscoverDetailPageState extends State<DiscoverDetailPage> {
  late Future<DiscoverItem> _detail;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    final institutionId = widget.institutionId;
    final projectId = widget.projectId;
    final repository = widget.repository;
    final InstitutionProjectDetailRepository? institutionProjectRepository =
        repository is InstitutionProjectDetailRepository
            ? repository as InstitutionProjectDetailRepository
            : null;
    if (institutionId != null &&
        projectId != null &&
        institutionProjectRepository != null) {
      _detail = institutionProjectRepository.loadInstitutionProjectDetail(
        institutionId: institutionId,
        projectId: projectId,
      );
      return;
    }
    _detail = repository.loadDetail(type: widget.type, id: widget.id);
  }

  void _openRelated(DiscoverContentType type, String id) {
    Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => DiscoverDetailPage(
          repository: widget.repository,
          type: type,
          id: id,
          onBookProject: widget.onBookProject,
          socialController: widget.socialController,
          onOpenUser: widget.onOpenUser,
          onConsultDoctor: widget.onConsultDoctor,
          onConsultInstitution: widget.onConsultInstitution,
          onOpenAi: widget.onOpenAi,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isInstitutionProject = widget.type == DiscoverContentType.project &&
        widget.institutionId?.trim().isNotEmpty == true &&
        widget.projectId?.trim().isNotEmpty == true;
    return FutureBuilder<DiscoverItem>(
      future: _detail,
      initialData: widget.initialItem,
      builder: (context, snapshot) {
        final item = snapshot.data;
        final favoriteType =
            item == null ? null : _favoriteTargetType(item.type);
        final favoriteTargetId = item?.type == DiscoverContentType.project &&
                widget.projectId?.trim().isNotEmpty == true
            ? widget.projectId!.trim()
            : (item?.id.trim() ?? '');
        return Scaffold(
          appBar: AppBar(
            title: Text(
              isInstitutionProject
                  ? context.localized(
                      '机构项目详情',
                      'Institution project details',
                    )
                  : '${_typeLabel(context, widget.type)}${context.localized('详情', ' details')}',
            ),
            actions: [
              if (item != null &&
                  widget.socialController != null &&
                  favoriteType != null &&
                  favoriteTargetId.isNotEmpty)
                FavoriteActionButton(
                  controller: widget.socialController!,
                  type: favoriteType,
                  targetId: favoriteTargetId,
                  targetName: item.title,
                  targetImage: item.imageUrl,
                ),
            ],
          ),
          body: snapshot.connectionState == ConnectionState.waiting &&
                  !snapshot.hasData
              ? const Center(child: CircularProgressIndicator())
              : snapshot.hasError && !snapshot.hasData
                  ? _DiscoverFailure(
                      message: context.localized(
                        '详情加载失败，请重试',
                        'Failed to load details. Please retry.',
                      ),
                      onRetry: () => setState(_load),
                    )
                  : switch (item!.type) {
                      DiscoverContentType.project => CatalogProjectDetailView(
                          item: item,
                          enableAutoTranslation: true,
                          socialController: widget.socialController,
                          onBook: widget.onBookProject,
                          onInstitutionTap: (id) =>
                              _openRelated(DiscoverContentType.institution, id),
                          onDiaryTap: _openDiary,
                          onDoctorTap: (id) =>
                              _openRelated(DiscoverContentType.doctor, id),
                          onViewAllInstitutions: () =>
                              _openAllRelatedGroup(item, 'institutionProjects'),
                          onViewAllDiaries: () =>
                              _openAllRelatedGroup(item, 'diaries'),
                          onViewAllReviews: () =>
                              _openAllRelatedGroup(item, 'reviews'),
                          onAiChat: widget.onOpenAi == null
                              ? null
                              : () => widget.onOpenAi!(item),
                          onInstitutionProjectTap: (institutionId, projectId) =>
                              Navigator.of(context).push<void>(
                            MaterialPageRoute(
                              builder: (_) => DiscoverDetailPage(
                                repository: widget.repository,
                                type: DiscoverContentType.project,
                                id: projectId,
                                institutionId: institutionId,
                                projectId: projectId,
                                onBookProject: widget.onBookProject,
                                socialController: widget.socialController,
                                onOpenUser: widget.onOpenUser,
                                onConsultDoctor: widget.onConsultDoctor,
                                onConsultInstitution:
                                    widget.onConsultInstitution,
                                onOpenAi: widget.onOpenAi,
                              ),
                            ),
                          ),
                        ),
                      DiscoverContentType.doctor => DoctorDetailView(
                          item: item,
                          onInstitutionTap: (id) =>
                              _openRelated(DiscoverContentType.institution, id),
                          onProjectTap: (institutionId, projectId) =>
                              Navigator.of(context).push<void>(
                            MaterialPageRoute(
                              builder: (_) => DiscoverDetailPage(
                                repository: widget.repository,
                                type: DiscoverContentType.project,
                                id: projectId,
                                institutionId: institutionId,
                                projectId: projectId,
                                onBookProject: widget.onBookProject,
                                socialController: widget.socialController,
                                onOpenUser: widget.onOpenUser,
                                onConsultDoctor: widget.onConsultDoctor,
                                onConsultInstitution:
                                    widget.onConsultInstitution,
                                onOpenAi: widget.onOpenAi,
                              ),
                            ),
                          ),
                          onDiaryTap: _openDiary,
                          onConsult: widget.onConsultDoctor == null
                              ? null
                              : () => widget.onConsultDoctor!(item),
                          onAiChat: widget.onOpenAi == null
                              ? null
                              : () => widget.onOpenAi!(item),
                          onViewAllProjects: () =>
                              _openAllRelatedGroup(item, 'institutionProjects'),
                          onViewAllDiaries: () =>
                              _openAllRelatedGroup(item, 'diaries'),
                          onViewAllReviews: () =>
                              _openAllRelatedGroup(item, 'reviews'),
                          socialController: widget.socialController,
                        ),
                      DiscoverContentType.institution =>
                        CatalogInstitutionDetailView(
                          item: item,
                          socialController: widget.socialController,
                          onConsultInstitution: widget.onConsultInstitution,
                          onProjectTap: (institutionId, projectId) =>
                              Navigator.of(context).push<void>(
                            MaterialPageRoute(
                              builder: (_) => DiscoverDetailPage(
                                repository: widget.repository,
                                type: DiscoverContentType.project,
                                id: projectId,
                                institutionId: institutionId,
                                projectId: projectId,
                                onBookProject: widget.onBookProject,
                                socialController: widget.socialController,
                                onOpenUser: widget.onOpenUser,
                                onConsultDoctor: widget.onConsultDoctor,
                                onConsultInstitution:
                                    widget.onConsultInstitution,
                                onOpenAi: widget.onOpenAi,
                              ),
                            ),
                          ),
                          onDoctorTap: (id) =>
                              _openRelated(DiscoverContentType.doctor, id),
                          onDiaryTap: _openDiary,
                          onViewAllProjects: () =>
                              _openAllRelatedGroup(item, 'projects'),
                          onViewAllDiaries: () =>
                              _openAllRelatedGroup(item, 'diaries'),
                          onViewAllReviews: () =>
                              _openAllRelatedGroup(item, 'reviews'),
                          onViewAllDoctors: () =>
                              _openAllRelatedGroup(item, 'doctors'),
                          onAiChat: widget.onOpenAi == null
                              ? null
                              : () => widget.onOpenAi!(item),
                        ),
                      DiscoverContentType.article =>
                        ArticleDetailView(item: item),
                      DiscoverContentType.diary =>
                        _GenericDetailView(item: item),
                      DiscoverContentType.all => _GenericDetailView(item: item),
                    },
        );
      },
    );
  }

  Future<void> _openDiary(String diaryId) async {
    final socialController = widget.socialController;
    if (socialController == null) {
      _openRelated(DiscoverContentType.diary, diaryId);
      return;
    }
    try {
      final item = await widget.repository.loadDetail(
        type: DiscoverContentType.diary,
        id: diaryId,
      );
      if (!mounted) return;
      final diary = diaryFromDiscover(item);
      await Navigator.of(context).push<void>(MaterialPageRoute(
        builder: (_) => DiaryDetailPage(
          controller: socialController,
          diary: diary,
          onAuthorTap: diary.userId.isEmpty || widget.onOpenUser == null
              ? null
              : () => widget.onOpenUser!(diary.userId),
          onProjectTap: (institutionId, projectId) {
            if (institutionId.isNotEmpty) {
              Navigator.of(context).push<void>(MaterialPageRoute(
                builder: (_) => DiscoverDetailPage(
                  repository: widget.repository,
                  type: DiscoverContentType.project,
                  id: projectId,
                  institutionId: institutionId,
                  projectId: projectId,
                  onBookProject: widget.onBookProject,
                  socialController: widget.socialController,
                  onOpenUser: widget.onOpenUser,
                  onConsultDoctor: widget.onConsultDoctor,
                  onConsultInstitution: widget.onConsultInstitution,
                  onOpenAi: widget.onOpenAi,
                ),
              ));
            } else {
              _openRelated(DiscoverContentType.project, projectId);
            }
          },
          onDoctorTap: (id) => _openRelated(DiscoverContentType.doctor, id),
          onInstitutionTap: (id) =>
              _openRelated(DiscoverContentType.institution, id),
        ),
      ));
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(context.localized(
            '日记详情加载失败，请稍后重试',
            'Unable to load diary details. Please try again.',
          )),
        ),
      );
    }
  }

  void _openAllRelatedGroup(DiscoverItem item, String key) {
    final entries = _relatedGroups(item.raw)[key];
    if (entries == null || entries.isEmpty) return;
    Navigator.of(context).push<void>(MaterialPageRoute(
      builder: (_) => key == 'reviews'
          ? _AllReviewsPage(
              reviews: entries,
              socialController: widget.socialController,
            )
          : _AllRelatedContentPage(
              groups: {key: entries},
              onOpen: _openRelatedEntry,
              socialController: widget.socialController,
            ),
    ));
  }

  void _openRelatedEntry(String key, Map<String, Object?> entry) {
    if (key == 'diaries') {
      final id = _rawText(entry['id'], fallback: _rawText(entry['diaryId']));
      if (id.isNotEmpty) unawaited(_openDiary(id));
      return;
    }
    if (key == 'doctors') {
      final id = _rawText(entry['id'], fallback: _rawText(entry['doctorId']));
      if (id.isNotEmpty) _openRelated(DiscoverContentType.doctor, id);
      return;
    }
    if (key == 'institutions') {
      final id =
          _rawText(entry['id'], fallback: _rawText(entry['institutionId']));
      if (id.isNotEmpty) _openRelated(DiscoverContentType.institution, id);
      return;
    }
    if (key == 'projects') {
      final id = _rawText(entry['projectId'], fallback: _rawText(entry['id']));
      if (id.isNotEmpty) _openRelated(DiscoverContentType.project, id);
      return;
    }
    if (key == 'institutionProjects') {
      final institution = _relatedMap(entry['institution']);
      final binding = _relatedMap(entry['institutionProject']);
      final institutionId = _rawText(
        entry['institutionId'],
        fallback: _rawText(binding['institutionId'],
            fallback: _rawText(institution['id'])),
      );
      final projectId = _rawText(
        entry['projectId'],
        fallback: _rawText(binding['projectId']),
      );
      if (institutionId.isEmpty || projectId.isEmpty) return;
      Navigator.of(context).push<void>(MaterialPageRoute(
        builder: (_) => DiscoverDetailPage(
          repository: widget.repository,
          type: DiscoverContentType.project,
          id: projectId,
          institutionId: institutionId,
          projectId: projectId,
          onBookProject: widget.onBookProject,
          socialController: widget.socialController,
          onOpenUser: widget.onOpenUser,
          onConsultDoctor: widget.onConsultDoctor,
          onConsultInstitution: widget.onConsultInstitution,
          onOpenAi: widget.onOpenAi,
        ),
      ));
    }
  }
}

FavoriteTargetType? _favoriteTargetType(DiscoverContentType type) =>
    switch (type) {
      DiscoverContentType.project => FavoriteTargetType.project,
      DiscoverContentType.institution => FavoriteTargetType.institution,
      DiscoverContentType.doctor => FavoriteTargetType.doctor,
      DiscoverContentType.diary => FavoriteTargetType.diary,
      DiscoverContentType.article => FavoriteTargetType.article,
      DiscoverContentType.all => null,
    };

Map<String, Object?> _relatedMap(Object? value) => value is Map
    ? value.map((key, value) => MapEntry(key.toString(), value))
    : const {};

class _GenericDetailView extends StatelessWidget {
  const _GenericDetailView({required this.item});

  final DiscoverItem item;

  @override
  Widget build(BuildContext context) {
    final titleField = _matchingVisibleField(
      item.raw,
      item.title,
      const ['title', 'name', 'projectName'],
    );
    final metaField = _matchingVisibleField(
      item.raw,
      item.meta,
      const ['city', 'category', 'institutionName', 'department'],
    );
    final subtitleField = _matchingVisibleField(
      item.raw,
      item.subtitle,
      const ['description', 'summary', 'content', 'specialties', 'address'],
    );
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        if (item.imageUrl.isNotEmpty)
          ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: OptimizedNetworkImage(
              url: item.imageUrl,
              width: double.infinity,
              height: 220,
              errorBuilder: (_, __, ___) => const SizedBox.shrink(),
            ),
          ),
        const SizedBox(height: 20),
        titleField == null
            ? Text(item.title, style: Theme.of(context).textTheme.headlineSmall)
            : AutoTranslatedText(
                request: _genericDetailRequest(item, titleField, item.title),
                style: Theme.of(context).textTheme.headlineSmall,
              ),
        if (item.meta.isNotEmpty) ...[
          const SizedBox(height: 8),
          metaField == null
              ? Text(
                  item.meta,
                  style:
                      TextStyle(color: Theme.of(context).colorScheme.primary),
                )
              : AutoTranslatedText(
                  request: _genericDetailRequest(item, metaField, item.meta),
                  style:
                      TextStyle(color: Theme.of(context).colorScheme.primary),
                ),
        ],
        const SizedBox(height: 16),
        if (subtitleField != null)
          AutoTranslatedText(
            request: _genericDetailRequest(
              item,
              subtitleField,
              item.subtitle,
            ),
          )
        else
          Text(
            item.subtitle.isEmpty
                ? context.localized('暂无更多介绍', 'No additional information')
                : item.subtitle,
          ),
      ],
    );
  }
}

String? _matchingVisibleField(
  Map<String, Object?> data,
  String visible,
  List<String> fields,
) {
  if (visible.isEmpty) return null;
  for (final field in fields) {
    if (data[field]?.toString().trim() == visible) return field;
  }
  return null;
}

AutoTranslationRequest _genericDetailRequest(
  DiscoverItem item,
  String field,
  String source,
) {
  return AutoTranslationRequest(
    contentType:
        item.type == DiscoverContentType.all ? 'general' : item.type.name,
    contentId: '${item.type.name}:${item.id}',
    field: field,
    sourceText: source,
  );
}

class _AllReviewsPage extends StatefulWidget {
  const _AllReviewsPage({required this.reviews, this.socialController});

  final List<Map<String, Object?>> reviews;
  final SocialController? socialController;

  @override
  State<_AllReviewsPage> createState() => _AllReviewsPageState();
}

class _AllReviewsPageState extends State<_AllReviewsPage> {
  CatalogReviewFilter _filter = CatalogReviewFilter.all;

  @override
  Widget build(BuildContext context) {
    final filtered = filterCatalogReviews(widget.reviews, _filter);
    return Scaffold(
      appBar: AppBar(title: Text(context.localized('全部评价', 'All reviews'))),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
            child: CatalogReviewFilters(
              value: _filter,
              onChanged: (value) => setState(() => _filter = value),
            ),
          ),
          Expanded(
            child: filtered.isEmpty
                ? Center(
                    child: Text(context.localized(
                      '暂无符合条件的评价',
                      'No matching reviews',
                    )),
                  )
                : ListView.separated(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                    itemCount: filtered.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 10),
                    itemBuilder: (_, index) => CatalogReviewCard(
                      review: filtered[index],
                      socialController: widget.socialController,
                    ),
                  ),
          ),
        ],
      ),
    );
  }
}

class _AllRelatedContentPage extends StatelessWidget {
  const _AllRelatedContentPage({
    required this.groups,
    this.onOpen,
    this.socialController,
  });
  final Map<String, List<Map<String, Object?>>> groups;
  final void Function(String key, Map<String, Object?> entry)? onOpen;
  final SocialController? socialController;

  @override
  Widget build(BuildContext context) => DefaultTabController(
        length: groups.length,
        child: Scaffold(
          appBar: AppBar(
            title: Text(context.localized('全部内容', 'All content')),
            bottom: TabBar(
              isScrollable: true,
              tabs: [
                for (final key in groups.keys)
                  Tab(text: _groupLabel(context, key))
              ],
            ),
          ),
          body: TabBarView(
            children: [
              for (final group in groups.entries)
                ListView.separated(
                  padding: const EdgeInsets.all(16),
                  itemCount: group.value.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 8),
                  itemBuilder: (_, index) {
                    final entry = group.value[index];
                    if (group.key == 'diaries') {
                      return DiaryPreviewCard.fromData(
                        data: entry,
                        onTap: () => onOpen?.call(group.key, entry),
                      );
                    }
                    if (group.key == 'reviews') {
                      return _AllReviewCard(
                        entry: entry,
                        socialController: socialController,
                      );
                    }
                    final item = _relatedDiscoverItem(group.key, entry);
                    if (item != null) {
                      return DiscoverContentCard(
                        key: ValueKey('all-${group.key}-${item.id}'),
                        item: item,
                        onTap: () => onOpen?.call(group.key, entry),
                      );
                    }
                    return const SizedBox.shrink();
                  },
                ),
            ],
          ),
        ),
      );
}

class _AllReviewCard extends StatelessWidget {
  const _AllReviewCard({required this.entry, this.socialController});

  final Map<String, Object?> entry;
  final SocialController? socialController;

  @override
  Widget build(BuildContext context) {
    final id = _rawText(entry['id'], fallback: _rawText(entry['reviewId']));
    final userName = _rawText(
      entry['userName'],
      fallback: context.localized('匿名用户', 'Anonymous user'),
    );
    final content = _rawText(entry['content']);
    final avatar = _rawText(
      entry['userAvatar'],
      fallback: _rawText(
        entry['avatarUrl'],
        fallback: _rawText(entry['avatar']),
      ),
    );
    final projectName = _rawText(
      entry['projectName'],
      fallback: _rawText(entry['serviceName']),
    );
    final createdAt = _rawText(entry['createdAt']);
    final rating = _rawInt(entry['rating']);
    final tags = _rawList(entry['tags']);
    final images = _rawList(entry['imageUrls'] ?? entry['images']);
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                CircleAvatar(
                  radius: 20,
                  foregroundImage: avatar.isEmpty
                      ? null
                      : optimizedNetworkImageProvider(
                          context,
                          avatar,
                          width: 40,
                          height: 40,
                        ),
                  child: Text(userName.characters.first),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(userName,
                          style: const TextStyle(fontWeight: FontWeight.w700)),
                      if (createdAt.isNotEmpty)
                        Text(
                          createdAt.length >= 10
                              ? createdAt.substring(0, 10)
                              : createdAt,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                    ],
                  ),
                ),
                if (rating > 0)
                  Text(
                    '★ ${rating.toStringAsFixed(1)}',
                    style: const TextStyle(
                      color: Color(0xffffa000),
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                if (id.isNotEmpty && socialController != null)
                  ReportActionButton(
                    key: Key('review-report-$id'),
                    controller: socialController!,
                    type: ReportTargetType.review,
                    targetId: id,
                    compact: true,
                    iconSize: 20,
                  ),
              ],
            ),
            if (content.isNotEmpty) ...[
              const SizedBox(height: 10),
              Text(content, style: const TextStyle(height: 1.45)),
            ],
            if (projectName.isNotEmpty) ...[
              const SizedBox(height: 7),
              Text(
                projectName,
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
            ],
            if (tags.isNotEmpty) ...[
              const SizedBox(height: 10),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final tag in tags)
                    Chip(
                      label: Text(tag),
                      visualDensity: VisualDensity.compact,
                    ),
                ],
              ),
            ],
            if (images.isNotEmpty) ...[
              const SizedBox(height: 10),
              SizedBox(
                height: 84,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  itemCount: images.length,
                  separatorBuilder: (_, __) => const SizedBox(width: 8),
                  itemBuilder: (_, index) => ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: OptimizedNetworkImage(
                      url: images[index],
                      width: 84,
                      height: 84,
                      errorBuilder: (_, __, ___) => const SizedBox.shrink(),
                    ),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

DiscoverItem? _relatedDiscoverItem(
  String key,
  Map<String, Object?> entry,
) {
  final type = switch (key) {
    'doctors' => DiscoverContentType.doctor,
    'institutions' => DiscoverContentType.institution,
    'projects' || 'institutionProjects' => DiscoverContentType.project,
    _ => null,
  };
  if (type == null) return null;
  final doctor = _relatedMap(entry['doctor']);
  final institution = _relatedMap(entry['institution']);
  final project = _relatedMap(entry['project']);
  final institutionProject = _relatedMap(entry['institutionProject']);
  final source = <String, Object?>{
    ..._nonEmptyRelatedValues(doctor),
    ..._nonEmptyRelatedValues(institution),
    ..._nonEmptyRelatedValues(project),
    ..._nonEmptyRelatedValues(institutionProject),
    ..._nonEmptyRelatedValues(entry),
  };
  if (type == DiscoverContentType.doctor) {
    final doctorName = _rawText(
      entry['doctorName'],
      fallback: _rawText(
        doctor['doctorName'],
        fallback: _rawText(doctor['name'], fallback: _rawText(entry['name'])),
      ),
    );
    if (doctorName.isNotEmpty) source['name'] = doctorName;
    final doctorAvatar = _rawText(
      entry['doctorAvatar'],
      fallback: _rawText(
        entry['avatarUrl'],
        fallback: _rawText(
          doctor['doctorAvatar'],
          fallback: _rawText(doctor['avatarUrl']),
        ),
      ),
    );
    if (doctorAvatar.isNotEmpty) source['avatar'] = doctorAvatar;
    final doctorInstitution = _rawText(
      entry['institutionName'],
      fallback: _rawText(
        doctor['institutionName'],
        fallback: _rawText(institution['name']),
      ),
    );
    if (doctorInstitution.isNotEmpty) {
      source['institutionName'] = doctorInstitution;
    }
    source['isVerified'] = entry['isVerified'] ??
        doctor['isVerified'] ??
        entry['verified'] ??
        doctor['verified'] ??
        entry['certified'] ??
        doctor['certified'];
    source['specialties'] = entry['specialties'] ??
        doctor['specialties'] ??
        entry['specialty'] ??
        doctor['specialty'] ??
        entry['tags'] ??
        doctor['tags'];
  } else if (type == DiscoverContentType.institution) {
    final institutionName = _rawText(
      entry['institutionName'],
      fallback: _rawText(
        institution['name'],
        fallback: _rawText(entry['name']),
      ),
    );
    if (institutionName.isNotEmpty) source['name'] = institutionName;
    source['isVerified'] = entry['isVerified'] ??
        institution['isVerified'] ??
        entry['verified'] ??
        institution['verified'];
  } else if (type == DiscoverContentType.project) {
    final projectName = _rawText(
      entry['projectName'],
      fallback: _rawText(
        institutionProject['name'],
        fallback: _rawText(project['name'], fallback: _rawText(entry['name'])),
      ),
    );
    if (projectName.isNotEmpty) source['projectName'] = projectName;
  }
  final id = switch (type) {
    DiscoverContentType.doctor =>
      _rawText(source['doctorId'], fallback: _rawText(source['id'])),
    DiscoverContentType.institution =>
      _rawText(source['institutionId'], fallback: _rawText(source['id'])),
    DiscoverContentType.project =>
      _rawText(source['projectId'], fallback: _rawText(source['id'])),
    _ => '',
  };
  if (id.isEmpty) return null;
  final title = _rawText(
    source['projectName'],
    fallback: _rawText(
      source['name'],
      fallback: _rawText(
        source['nickname'],
        fallback: type == DiscoverContentType.doctor ? 'Doctor' : 'Untitled',
      ),
    ),
  );
  final imageUrl = _relatedFirstImage(source);
  final subtitle = _rawText(
    source['description'],
    fallback: _rawText(
      source['bio'],
      fallback: _rawText(source['specialties']),
    ),
  );
  final meta = switch (type) {
    DiscoverContentType.doctor => _rawText(
        source['title'],
        fallback: _rawText(source['institutionName']),
      ),
    DiscoverContentType.institution => _rawText(source['city']),
    DiscoverContentType.project => _rawText(
        source['category'],
        fallback: _rawText(source['institutionName']),
      ),
    _ => '',
  };
  return DiscoverItem(
    id: id,
    type: type,
    title: title,
    subtitle: subtitle,
    imageUrl: imageUrl,
    meta: meta,
    raw: Map.unmodifiable(source),
  );
}

Map<String, Object?> _nonEmptyRelatedValues(Map<String, Object?> source) => {
      for (final entry in source.entries)
        if (entry.value != null && entry.value.toString().trim().isNotEmpty)
          entry.key: entry.value,
    };

String _relatedFirstImage(Map<String, Object?> source) {
  for (final key in const [
    'avatar',
    'avatarUrl',
    'doctorAvatar',
    'userAvatar',
    'coverImage',
    'coverImageUrl',
    'logo',
    'imageUrl',
    'imageUrls',
    'images',
  ]) {
    final values = _rawList(source[key]);
    if (values.isNotEmpty) return values.first;
  }
  return '';
}

Map<String, List<Map<String, Object?>>> _relatedGroups(
    Map<String, Object?> raw) {
  final result = <String, List<Map<String, Object?>>>{};
  for (final key in const [
    'diaries',
    'institutionProjects',
    'projects',
    'institutions',
    'doctors',
    'reviews'
  ]) {
    final value = raw[key];
    if (value is List) {
      final entries = value
          .whereType<Map>()
          .map((entry) =>
              entry.map((key, value) => MapEntry(key.toString(), value)))
          .toList();
      if (entries.isNotEmpty) result[key] = entries;
    }
  }
  return result;
}

String _groupLabel(BuildContext context, String key) => switch (key) {
      'diaries' => context.localized('日记', 'Diaries'),
      'doctors' => context.localized('医生', 'Doctors'),
      'reviews' => context.localized('评价', 'Reviews'),
      'institutions' => context.localized('机构', 'Institutions'),
      _ => context.localized('项目', 'Projects'),
    };

class _DiscoverFailure extends StatelessWidget {
  const _DiscoverFailure({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.cloud_off_outlined, size: 44),
          const SizedBox(height: 12),
          Text(message),
          const SizedBox(height: 16),
          FilledButton.tonal(
            onPressed: onRetry,
            child: Text(context.localized('重试', 'Retry')),
          ),
        ],
      ),
    );
  }
}

class _EmptySearch extends StatelessWidget {
  const _EmptySearch({required this.query});

  final String query;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.search_off_rounded, size: 44),
            const SizedBox(height: 16),
            Text(context.localized('暂未找到相关内容', 'No results found'),
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 4),
            Text(query.isEmpty
                ? context.localized('下拉刷新试试', 'Pull down to refresh')
                : context.isEnglish
                    ? 'Try another keyword for “$query”'
                    : '换个关键词搜索“$query”试试'),
          ],
        ),
      ),
    );
  }
}

String _typeLabel(BuildContext context, DiscoverContentType type) =>
    switch (type) {
      DiscoverContentType.all => context.localized('全部', 'All'),
      DiscoverContentType.project => context.localized('项目', 'Project'),
      DiscoverContentType.doctor => context.localized('医生', 'Doctor'),
      DiscoverContentType.institution => context.localized('机构', 'Institution'),
      DiscoverContentType.diary => context.localized('日记', 'Diary'),
      DiscoverContentType.article => context.localized('文章', 'Article'),
    };

const _previewItems = [
  DiscoverItem(
    id: 'preview-project',
    type: DiscoverContentType.project,
    title: '理性了解热门项目',
    subtitle: '先看适应症、风险和恢复周期，再决定是否进一步咨询',
  ),
  DiscoverItem(
    id: 'preview-doctor',
    type: DiscoverContentType.doctor,
    title: '核验医生执业信息',
    subtitle: '查看专业方向、执业机构和公开可核验的信息',
  ),
  DiscoverItem(
    id: 'preview-institution',
    type: DiscoverContentType.institution,
    title: '选择正规医疗机构',
    subtitle: '结合资质、项目备案和真实服务信息进行比较',
  ),
  DiscoverItem(
    id: 'preview-diary',
    type: DiscoverContentType.diary,
    title: '查看真实经历',
    subtitle: '个人体验不能替代专业医疗建议',
  ),
  DiscoverItem(
    id: 'preview-article',
    type: DiscoverContentType.article,
    title: '阅读专业科普',
    subtitle: '把营销表达还原成可以核验的事实',
  ),
];
