import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/public_media_url.dart';
import 'package:joysong_flutter/features/home/domain/home_models.dart';
import 'package:joysong_flutter/features/home/domain/home_repository.dart';

final class ApiHomeRepository implements HomeRepository {
  ApiHomeRepository(
    this._apiClient, {
    PublicMediaUrlResolver? mediaUrlResolver,
  }) : _mediaUrlResolver =
            mediaUrlResolver ?? ApiPublicMediaUrlResolver(_apiClient.apiRoot);

  final ApiClient _apiClient;
  final PublicMediaUrlResolver _mediaUrlResolver;

  @override
  Future<HomeFeed> loadHome() async {
    final results = await Future.wait<_HomeSectionResult>([
      _loadSafely('/home/banners', HomeSectionKind.banner),
      _loadSafely('/home/hot-projects', HomeSectionKind.hotProject),
      _loadSafely('/home/expert-articles', HomeSectionKind.expertArticle),
      _loadSafely('/home/user-diaries', HomeSectionKind.userDiary),
      _loadSafely(
        '/home/recommended-institution-projects',
        HomeSectionKind.recommendedInstitutionProject,
      ),
      _loadSafely(
        '/discover/institutions',
        HomeSectionKind.institution,
        query: const {'offset': 0, 'limit': 3},
      ),
      _loadSafely(
        '/discover/doctors',
        HomeSectionKind.doctor,
        query: const {'offset': 0, 'limit': 5},
      ),
    ]);
    if (results.every((result) => !result.succeeded)) {
      throw const HomeFeedUnavailableException();
    }
    return HomeFeed(
      banners: results[0].items,
      hotProjects: results[1].items,
      expertArticles: results[2].items,
      userDiaries: results[3].items,
      recommendedInstitutionProjects: results[4].items,
      institutions: results[5].items,
      doctors: results[6].items,
      failedSections: {
        for (final result in results)
          if (!result.succeeded) result.kind,
      },
    );
  }

  Future<_HomeSectionResult> _loadSafely(
    String path,
    HomeSectionKind kind, {
    Map<String, Object?> query = const {},
  }) async {
    try {
      final items = await _apiClient.get<List<HomeContent>>(
            path,
            query: query,
            decodeData: (json) {
              if (json is! List) {
                throw const FormatException('首页列表响应格式错误');
              }
              return [
                for (final item in json)
                  HomeContent.fromJson(
                    item,
                    kind: kind,
                    mediaUrlResolver: _mediaUrlResolver,
                  ),
              ];
            },
          ) ??
          const [];
      return _HomeSectionResult(kind: kind, items: items, succeeded: true);
    } on Object {
      return _HomeSectionResult(
        kind: kind,
        items: const [],
        succeeded: false,
      );
    }
  }
}

final class HomeFeedUnavailableException implements Exception {
  const HomeFeedUnavailableException();

  @override
  String toString() => 'HomeFeedUnavailableException(all sections failed)';
}

final class _HomeSectionResult {
  const _HomeSectionResult({
    required this.kind,
    required this.items,
    required this.succeeded,
  });

  final HomeSectionKind kind;
  final List<HomeContent> items;
  final bool succeeded;
}
