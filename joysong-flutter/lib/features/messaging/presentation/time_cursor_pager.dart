import 'package:flutter/foundation.dart';

typedef CursorPageLoader<T> = Future<List<T>> Function({
  required int limit,
  String? before,
});

class TimeCursorPager<T> extends ChangeNotifier {
  TimeCursorPager({
    required CursorPageLoader<T> loader,
    required String Function(T item) idOf,
    required String Function(T item) createdAtOf,
    this.pageSize = 30,
  })  : _loader = loader,
        _idOf = idOf,
        _createdAtOf = createdAtOf;

  final CursorPageLoader<T> _loader;
  final String Function(T item) _idOf;
  final String Function(T item) _createdAtOf;
  final int pageSize;

  List<T> _items = const [];
  List<T> get items => _items;
  bool _isLoading = false;
  bool get isLoading => _isLoading;
  bool _hasMore = true;
  bool get hasMore => _hasMore;
  String? _errorMessage;
  String? get errorMessage => _errorMessage;

  Future<void> loadInitial() async {
    if (_isLoading) return;
    _setLoading();
    try {
      final page = await _loader(limit: pageSize);
      _items = _deduplicate(page);
      _hasMore = page.length == pageSize;
      _finish();
    } on Object {
      _fail();
    }
  }

  Future<void> loadOlder() async {
    if (_isLoading || !_hasMore) return;
    final before = _items.isEmpty ? null : _createdAtOf(_items.first);
    _setLoading();
    try {
      final page = await _loader(limit: pageSize, before: before);
      final previousLength = _items.length;
      _items = _deduplicate([...page, ..._items]);
      _hasMore = page.length == pageSize && _items.length > previousLength;
      _finish();
    } on Object {
      _fail();
    }
  }

  void addNewest(T item) {
    _items = _deduplicate([..._items, item]);
    _errorMessage = null;
    notifyListeners();
  }

  void removeById(String id) {
    _items = _items.where((item) => _idOf(item) != id).toList();
    notifyListeners();
  }

  List<T> _deduplicate(Iterable<T> source) {
    final seen = <String>{};
    return [
      for (final item in source)
        if (seen.add(_idOf(item))) item
    ];
  }

  void _setLoading() {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();
  }

  void _finish() {
    _isLoading = false;
    _errorMessage = null;
    notifyListeners();
  }

  void _fail() {
    _isLoading = false;
    _errorMessage = '加载失败，请手动重试';
    notifyListeners();
  }
}
