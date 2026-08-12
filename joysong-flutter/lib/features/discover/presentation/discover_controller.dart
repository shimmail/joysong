import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';

enum DiscoverLoadStatus { idle, loading, ready, empty, failure }

final class DiscoverController extends ChangeNotifier {
  DiscoverController(
    this._repository, {
    required this.type,
    this.pageSize = 20,
  });

  final DiscoverRepository _repository;
  final DiscoverContentType type;
  final int pageSize;

  DiscoverLoadStatus _status = DiscoverLoadStatus.idle;
  List<DiscoverItem> _items = const [];
  bool _hasMore = true;
  bool _isLoadingMore = false;
  String _query = '';
  List<String> _categories = const [];
  List<String> _cities = const [];
  List<String> _tags = const [];
  int _generation = 0;
  String? _errorMessage;

  DiscoverLoadStatus get status => _status;
  List<DiscoverItem> get items => _items;
  bool get hasMore => _hasMore;
  bool get isLoadingMore => _isLoadingMore;
  String? get errorMessage => _errorMessage;

  Future<void> load({
    String query = '',
    List<String> categories = const [],
    List<String> cities = const [],
    List<String> tags = const [],
    bool refresh = false,
  }) async {
    final normalizedQuery = query.trim();
    if (!refresh && _status == DiscoverLoadStatus.loading) {
      return;
    }
    final generation = ++_generation;
    _query = normalizedQuery;
    _categories = List.unmodifiable(categories);
    _cities = List.unmodifiable(cities);
    _tags = List.unmodifiable(tags);
    _status = DiscoverLoadStatus.loading;
    _errorMessage = null;
    notifyListeners();
    try {
      final page = await _repository.loadPage(
        type: type,
        offset: 0,
        limit: pageSize,
        query: normalizedQuery,
        categories: categories,
        cities: cities,
        tags: tags,
      );
      if (generation != _generation) {
        return;
      }
      _items = page.items;
      _hasMore = page.hasMore;
      _status = page.items.isEmpty
          ? DiscoverLoadStatus.empty
          : DiscoverLoadStatus.ready;
    } catch (_) {
      if (generation != _generation) {
        return;
      }
      _status = DiscoverLoadStatus.failure;
      _errorMessage = 'discover_load_failed';
    }
    notifyListeners();
  }

  Future<void> loadMore() async {
    if (_isLoadingMore || !_hasMore || _status != DiscoverLoadStatus.ready) {
      return;
    }
    _isLoadingMore = true;
    _errorMessage = null;
    final generation = _generation;
    notifyListeners();
    try {
      final page = await _repository.loadPage(
        type: type,
        offset: _items.length,
        limit: pageSize,
        query: _query,
        categories: _categories,
        cities: _cities,
        tags: _tags,
      );
      if (generation != _generation) {
        return;
      }
      final ids = _items.map((item) => item.id).toSet();
      _items = [
        ..._items,
        ...page.items.where((item) => ids.add(item.id)),
      ];
      _hasMore = page.hasMore;
    } catch (_) {
      _errorMessage = 'discover_load_more_failed';
    } finally {
      _isLoadingMore = false;
      notifyListeners();
    }
  }
}
