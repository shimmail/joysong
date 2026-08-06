import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/public_media_url.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';

final class ApiDiscoverRepository
    implements DiscoverRepository, InstitutionProjectDetailRepository {
  ApiDiscoverRepository(
    this._apiClient, {
    PublicMediaUrlResolver? mediaUrlResolver,
  }) : _mediaUrlResolver =
            mediaUrlResolver ?? ApiPublicMediaUrlResolver(_apiClient.apiRoot);

  final ApiClient _apiClient;
  final PublicMediaUrlResolver _mediaUrlResolver;

  @override
  Future<DiscoverFilterOptions> loadFilterOptions() async {
    return await _apiClient.get<DiscoverFilterOptions>(
          '/discover/filter-options',
          decodeData: DiscoverFilterOptions.fromJson,
        ) ??
        const DiscoverFilterOptions();
  }

  @override
  Future<DiscoverPageResult> loadPage({
    required DiscoverContentType type,
    required int offset,
    required int limit,
    String query = '',
    List<String> categories = const [],
    List<String> cities = const [],
    List<String> tags = const [],
  }) async {
    if (type == DiscoverContentType.all) {
      final groups = await Future.wait([
        for (final childType in DiscoverContentType.values.skip(1))
          loadPage(
            type: childType,
            offset: 0,
            limit: (limit / 5).ceil(),
            query: query,
            categories: categories,
            cities: cities,
            tags: tags,
          ),
      ]);
      final items = groups.expand((group) => group.items).take(limit).toList();
      return DiscoverPageResult(items: items, hasMore: false);
    }
    final data = await _apiClient.get<List<DiscoverItem>>(
          '/discover/${type.pathSegment}',
          query: {
            'offset': offset,
            'limit': limit,
            if (query.isNotEmpty) 'query': query,
            if (type == DiscoverContentType.project && categories.isNotEmpty)
              'categories': categories.join(','),
            if (type == DiscoverContentType.project && cities.isNotEmpty)
              'cities': cities.join(','),
            if (type == DiscoverContentType.project && tags.isNotEmpty)
              'tags': tags.join(','),
          },
          decodeData: (json) {
            if (json is! List) {
              throw const FormatException('发现列表响应格式错误');
            }
            return [
              for (final item in json)
                DiscoverItem.fromJson(
                  resolvePublicMediaUrlsInJson(
                    item,
                    resolver: _mediaUrlResolver,
                  ),
                  type: type,
                  mediaUrlResolver: _mediaUrlResolver,
                ),
            ];
          },
        ) ??
        const [];
    return DiscoverPageResult(items: data, hasMore: data.length == limit);
  }

  @override
  Future<DiscoverItem> loadDetail({
    required DiscoverContentType type,
    required String id,
  }) async {
    if (type == DiscoverContentType.all) {
      throw ArgumentError.value(type, 'type', '综合内容没有详情端点');
    }
    final detail = await _apiClient.get<DiscoverItem>(
      '/discover/${type.pathSegment}/$id',
      decodeData: (json) => DiscoverItem.fromJson(
        resolvePublicMediaUrlsInJson(
          json,
          resolver: _mediaUrlResolver,
        ),
        type: type,
        mediaUrlResolver: _mediaUrlResolver,
      ),
    );
    if (detail == null) {
      throw const FormatException('详情响应为空');
    }
    return detail;
  }

  @override
  Future<DiscoverItem> loadInstitutionProjectDetail({
    required String institutionId,
    required String projectId,
  }) async {
    final detail = await _apiClient.get<DiscoverItem>(
      '/discover/institutions/$institutionId/projects/$projectId',
      decodeData: (json) => DiscoverItem.fromJson(
        resolvePublicMediaUrlsInJson(
          json,
          resolver: _mediaUrlResolver,
        ),
        type: DiscoverContentType.project,
        mediaUrlResolver: _mediaUrlResolver,
      ),
    );
    if (detail == null) {
      throw const FormatException('机构项目详情响应为空');
    }
    return detail;
  }
}
