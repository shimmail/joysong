import 'package:joysong_flutter/core/network/public_media_url.dart';

enum DiscoverContentType {
  all('综合', ''),
  project('项目', 'projects'),
  doctor('医生', 'doctors'),
  institution('机构', 'institutions'),
  diary('日记', 'diaries'),
  article('文章', 'articles');

  const DiscoverContentType(this.label, this.pathSegment);

  final String label;
  final String pathSegment;
}

final class DiscoverFilterOptions {
  const DiscoverFilterOptions({
    this.categories = const [],
    this.tags = const [],
    this.cities = const [],
  });

  factory DiscoverFilterOptions.fromJson(Object? json) {
    final map = _jsonMap(json);
    return DiscoverFilterOptions(
      categories: _stringList(map['categories']),
      tags: _stringList(map['tags']),
      cities: _stringList(map['cities']),
    );
  }

  final List<String> categories;
  final List<String> tags;
  final List<String> cities;
}

final class DiscoverItem {
  const DiscoverItem({
    required this.id,
    required this.type,
    required this.title,
    this.subtitle = '',
    this.imageUrl = '',
    this.meta = '',
    this.raw = const {},
  });

  factory DiscoverItem.fromJson(
    Object? json, {
    required DiscoverContentType type,
    PublicMediaUrlResolver? mediaUrlResolver,
  }) {
    final map = _jsonMap(json);
    final nested = _nestedEntity(map, type);
    final source = <String, Object?>{...nested, ...map};
    final id = _firstText(source, const [
      'id',
      'projectId',
      'doctorId',
      'institutionId',
      'diaryId',
      'articleId',
    ]);
    if (id.isEmpty) {
      throw const FormatException('发现内容缺少 id');
    }
    return DiscoverItem(
      id: id,
      type: type,
      title: _firstText(
        source,
        const ['title', 'name', 'projectName', 'nickname'],
        fallback: '未命名内容',
      ),
      subtitle: _firstText(source, const [
        'description',
        'summary',
        'content',
        'specialties',
        'address',
        'authorName',
      ]),
      imageUrl: _firstImage(source, mediaUrlResolver: mediaUrlResolver),
      meta: _firstText(source, const [
        'city',
        'category',
        'institutionName',
        'department',
        'publishDate',
      ]),
      raw: Map.unmodifiable(source),
    );
  }

  final String id;
  final DiscoverContentType type;
  final String title;
  final String subtitle;
  final String imageUrl;
  final String meta;
  final Map<String, Object?> raw;
}

final class DiscoverPageResult {
  const DiscoverPageResult({required this.items, required this.hasMore});

  final List<DiscoverItem> items;
  final bool hasMore;
}

Map<String, Object?> _nestedEntity(
  Map<String, Object?> map,
  DiscoverContentType type,
) {
  final key = switch (type) {
    DiscoverContentType.project => 'project',
    DiscoverContentType.doctor => 'doctor',
    DiscoverContentType.institution => 'institution',
    DiscoverContentType.diary => 'diary',
    DiscoverContentType.article => 'article',
    DiscoverContentType.all => '',
  };
  final value = map[key];
  if (value is! Map) {
    return const {};
  }
  return value.map((key, value) => MapEntry(key.toString(), value));
}

Map<String, Object?> _jsonMap(Object? json) {
  if (json is! Map) {
    throw const FormatException('发现内容不是 JSON 对象');
  }
  return json.map((key, value) => MapEntry(key.toString(), value));
}

List<String> _stringList(Object? value) {
  if (value is! List) {
    return const [];
  }
  return value
      .map((item) => item.toString().trim())
      .where((item) => item.isNotEmpty)
      .toList(growable: false);
}

String _firstText(
  Map<String, Object?> map,
  List<String> keys, {
  String fallback = '',
}) {
  for (final key in keys) {
    final value = map[key];
    if (value != null && value.toString().trim().isNotEmpty) {
      return value.toString().trim();
    }
  }
  return fallback;
}

String _firstImage(
  Map<String, Object?> map, {
  PublicMediaUrlResolver? mediaUrlResolver,
}) {
  for (final key in const [
    'coverImage',
    'avatar',
    'logo',
    'imageUrl',
    'images',
  ]) {
    final value = map[key];
    final candidates = value is List
        ? value.map((item) => item.toString())
        : (value?.toString() ?? '').split(',');
    for (final candidate in candidates) {
      final normalized = candidate.trim();
      if (normalized.isNotEmpty) {
        return mediaUrlResolver?.resolve(normalized) ?? normalized;
      }
    }
  }
  return '';
}
