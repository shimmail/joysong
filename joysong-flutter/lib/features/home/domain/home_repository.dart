import 'package:joysong_flutter/features/home/domain/home_models.dart';

abstract interface class HomeRepository {
  Future<HomeFeed> loadHome();
}
