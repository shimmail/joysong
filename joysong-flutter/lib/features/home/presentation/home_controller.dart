import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/features/home/domain/home_models.dart';
import 'package:joysong_flutter/features/home/domain/home_repository.dart';

enum HomeLoadStatus { idle, loading, ready, empty, failure }

final class HomeController extends ChangeNotifier {
  HomeController(this._repository);

  final HomeRepository _repository;

  HomeLoadStatus _status = HomeLoadStatus.idle;
  HomeFeed _feed = const HomeFeed();
  String? _errorMessage;

  HomeLoadStatus get status => _status;
  HomeFeed get feed => _feed;
  String? get errorMessage => _errorMessage;

  Future<void> load({bool refresh = false}) async {
    if (_status == HomeLoadStatus.loading) {
      return;
    }
    if (!refresh &&
        (_status == HomeLoadStatus.ready || _status == HomeLoadStatus.empty)) {
      return;
    }
    _status = HomeLoadStatus.loading;
    _errorMessage = null;
    notifyListeners();
    try {
      _feed = await _repository.loadHome();
      _status = _feed.isEmpty ? HomeLoadStatus.empty : HomeLoadStatus.ready;
    } catch (_) {
      _status = HomeLoadStatus.failure;
      _errorMessage = 'home_feed_unavailable';
    }
    notifyListeners();
  }
}
