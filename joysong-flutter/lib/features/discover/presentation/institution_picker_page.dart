import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_content_card.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_controller.dart';

final class InstitutionPickerSelection {
  const InstitutionPickerSelection({required this.id, required this.name});

  final String id;
  final String name;
}

class InstitutionPickerPage extends StatefulWidget {
  const InstitutionPickerPage({required this.repository, super.key});

  final DiscoverRepository repository;

  @override
  State<InstitutionPickerPage> createState() => _InstitutionPickerPageState();
}

class _InstitutionPickerPageState extends State<InstitutionPickerPage> {
  late final DiscoverController _controller;
  final _searchController = TextEditingController();
  final _scrollController = ScrollController();
  Timer? _debounce;

  @override
  void initState() {
    super.initState();
    _controller = DiscoverController(
      widget.repository,
      type: DiscoverContentType.institution,
    )..load();
    _scrollController.addListener(_loadMoreIfNeeded);
  }

  void _search(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 350), () {
      if (mounted) _controller.load(query: value.trim());
    });
  }

  void _loadMoreIfNeeded() {
    if (_scrollController.position.extentAfter < 240) {
      unawaited(_controller.loadMore());
    }
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _searchController.dispose();
    _scrollController
      ..removeListener(_loadMoreIfNeeded)
      ..dispose();
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: Text(context.localized('选择机构', 'Select institution')),
        ),
        body: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
              child: TextField(
                key: const Key('institution-picker-search'),
                controller: _searchController,
                onChanged: _search,
                textInputAction: TextInputAction.search,
                decoration: InputDecoration(
                  hintText: context.localized('搜索机构', 'Search institutions'),
                  prefixIcon: const Icon(Icons.search_rounded),
                ),
              ),
            ),
            Expanded(
              child: AnimatedBuilder(
                animation: _controller,
                builder: (context, _) => switch (_controller.status) {
                  DiscoverLoadStatus.idle ||
                  DiscoverLoadStatus.loading =>
                    const Center(child: CircularProgressIndicator()),
                  DiscoverLoadStatus.empty => Center(
                      child: Text(
                          context.localized('未找到机构', 'No institutions found')),
                    ),
                  DiscoverLoadStatus.failure => Center(
                      child: FilledButton.icon(
                        onPressed: () => _controller.load(
                          query: _searchController.text,
                          refresh: true,
                        ),
                        icon: const Icon(Icons.refresh_rounded),
                        label: Text(context.localized('重试', 'Retry')),
                      ),
                    ),
                  DiscoverLoadStatus.ready => ListView.separated(
                      controller: _scrollController,
                      padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                      itemCount: _controller.items.length +
                          (_controller.isLoadingMore ||
                                  _controller.errorMessage ==
                                      'discover_load_more_failed'
                              ? 1
                              : 0),
                      separatorBuilder: (_, __) => const SizedBox(height: 12),
                      itemBuilder: (context, index) {
                        if (index == _controller.items.length) {
                          if (!_controller.isLoadingMore) {
                            return Center(
                              child: TextButton.icon(
                                key: const Key('institution-picker-load-more-retry'),
                                onPressed: _controller.loadMore,
                                icon: const Icon(Icons.refresh_rounded),
                                label: Text(context.localized(
                                  '加载失败，点击重试',
                                  'Load failed. Tap to retry',
                                )),
                              ),
                            );
                          }
                          return const Center(
                            child: Padding(
                              padding: EdgeInsets.all(12),
                              child: CircularProgressIndicator(),
                            ),
                          );
                        }
                        final item = _controller.items[index];
                        return DiscoverContentCard(
                          item: item,
                          onTap: () => Navigator.of(context).pop(
                            InstitutionPickerSelection(
                              id: item.id,
                              name: item.title,
                            ),
                          ),
                        );
                      },
                    ),
                },
              ),
            ),
          ],
        ),
      );
}
