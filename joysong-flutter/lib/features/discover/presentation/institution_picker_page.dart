import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';

typedef InstitutionMembershipCandidatePageLoader
    = Future<InstitutionMembershipCandidatePage> Function({
  required String query,
  required int offset,
  required int limit,
});

final class InstitutionPickerSelection {
  const InstitutionPickerSelection({required this.id, required this.name});

  final String id;
  final String name;
}

class InstitutionPickerPage extends StatefulWidget {
  const InstitutionPickerPage({required this.loadPage, super.key});

  final InstitutionMembershipCandidatePageLoader loadPage;

  @override
  State<InstitutionPickerPage> createState() => _InstitutionPickerPageState();
}

enum _InstitutionPickerStatus { loading, ready, empty, failure }

class _InstitutionPickerPageState extends State<InstitutionPickerPage> {
  static const _initialLimit = 20;

  final _searchController = TextEditingController();
  final _scrollController = ScrollController();
  final _items = <InstitutionMembershipCandidate>[];
  Timer? _debounce;
  _InstitutionPickerStatus _status = _InstitutionPickerStatus.loading;
  String _query = '';
  int _nextOffset = 0;
  int _limit = _initialLimit;
  int _generation = 0;
  bool _hasMore = false;
  bool _isLoadingMore = false;
  bool _loadMoreFailed = false;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_loadMoreIfNeeded);
    unawaited(_loadFirstPage(''));
  }

  void _search(String value) {
    _debounce?.cancel();
    _generation += 1;
    _debounce = Timer(
      const Duration(milliseconds: 350),
      () => unawaited(_loadFirstPage(value.trim())),
    );
  }

  Future<void> _loadFirstPage(String query) async {
    final generation = ++_generation;
    setState(() {
      _query = query;
      _status = _InstitutionPickerStatus.loading;
      _items.clear();
      _nextOffset = 0;
      _limit = _initialLimit;
      _hasMore = false;
      _isLoadingMore = false;
      _loadMoreFailed = false;
    });

    try {
      final page = await widget.loadPage(
        query: query,
        offset: 0,
        limit: _initialLimit,
      );
      if (!mounted || generation != _generation) return;

      var shouldContinue = false;
      setState(() {
        _items.addAll(_dedupe(page.items));
        _nextOffset = page.offset + page.items.length;
        _limit = page.limit;
        _hasMore = page.hasMore && _nextOffset > 0;
        _status = _items.isEmpty && !_hasMore
            ? _InstitutionPickerStatus.empty
            : _InstitutionPickerStatus.ready;
        shouldContinue = _items.isEmpty && _hasMore;
      });
      if (shouldContinue) await _loadMore();
    } catch (_) {
      if (!mounted || generation != _generation) return;
      setState(() => _status = _InstitutionPickerStatus.failure);
    }
  }

  Future<void> _loadMore() async {
    if (_status != _InstitutionPickerStatus.ready ||
        _isLoadingMore ||
        !_hasMore) {
      return;
    }

    final generation = _generation;
    final offset = _nextOffset;
    final limit = _limit;
    setState(() {
      _isLoadingMore = true;
      _loadMoreFailed = false;
    });

    try {
      final page = await widget.loadPage(
        query: _query,
        offset: offset,
        limit: limit,
      );
      if (!mounted || generation != _generation) return;

      final knownIds = _items.map((item) => item.id).toSet();
      final newItems =
          page.items.where((item) => knownIds.add(item.id)).toList();
      var shouldContinue = false;
      setState(() {
        _items.addAll(newItems);
        _nextOffset = page.offset + page.items.length;
        _limit = page.limit;
        _hasMore = page.hasMore && _nextOffset > offset;
        _isLoadingMore = false;
        _status = _items.isEmpty && !_hasMore
            ? _InstitutionPickerStatus.empty
            : _InstitutionPickerStatus.ready;
        shouldContinue = newItems.isEmpty && _hasMore;
      });
      if (shouldContinue) await _loadMore();
    } catch (_) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _isLoadingMore = false;
        _loadMoreFailed = true;
      });
    }
  }

  Iterable<InstitutionMembershipCandidate> _dedupe(
    Iterable<InstitutionMembershipCandidate> candidates,
  ) {
    final ids = <String>{};
    return candidates.where((candidate) => ids.add(candidate.id));
  }

  void _loadMoreIfNeeded() {
    if (_scrollController.position.extentAfter < 240 && !_loadMoreFailed) {
      unawaited(_loadMore());
    }
  }

  @override
  void dispose() {
    _generation += 1;
    _debounce?.cancel();
    _searchController.dispose();
    _scrollController
      ..removeListener(_loadMoreIfNeeded)
      ..dispose();
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
            Expanded(child: _buildResults(context)),
          ],
        ),
      );

  Widget _buildResults(BuildContext context) => switch (_status) {
        _InstitutionPickerStatus.loading =>
          const Center(child: CircularProgressIndicator()),
        _InstitutionPickerStatus.empty => Center(
            child: Text(
              context.localized('未找到机构', 'No institutions found'),
            ),
          ),
        _InstitutionPickerStatus.failure => Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  context.localized('无法加载机构', 'Unable to load institutions'),
                ),
                const SizedBox(height: 12),
                FilledButton.icon(
                  key: const Key('institution-picker-retry'),
                  onPressed: () => unawaited(_loadFirstPage(_query)),
                  icon: const Icon(Icons.refresh_rounded),
                  label: Text(context.localized('重试', 'Retry')),
                ),
              ],
            ),
          ),
        _InstitutionPickerStatus.ready => Column(
            children: [
              Expanded(
                child: ListView.separated(
                  controller: _scrollController,
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                  itemCount: _items.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 12),
                  itemBuilder: (context, index) {
                    final item = _items[index];
                    return SizedBox(
                      height: 72,
                      child: Card(
                        clipBehavior: Clip.antiAlias,
                        child: ListTile(
                          title: Text(item.name),
                          trailing: const Icon(Icons.chevron_right_rounded),
                          onTap: () => Navigator.of(context).pop(
                            InstitutionPickerSelection(
                              id: item.id,
                              name: item.name,
                            ),
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
              if (_isLoadingMore)
                const Padding(
                  padding: EdgeInsets.all(12),
                  child: CircularProgressIndicator(),
                ),
              if (_loadMoreFailed)
                TextButton.icon(
                  key: const Key('institution-picker-load-more-retry'),
                  onPressed: () => unawaited(_loadMore()),
                  icon: const Icon(Icons.refresh_rounded),
                  label: Text(context.localized(
                    '加载失败，点击重试',
                    'Load failed. Tap to retry',
                  )),
                ),
            ],
          ),
      };
}
