import 'package:joysong_flutter/core/network/public_media_url.dart';

enum HomeSectionKind {
  banner,
  hotProject,
  expertArticle,
  userDiary,
  recommendedInstitutionProject,
  institution,
  doctor,
}

final class HomeContent {
  const HomeContent({
    required this.id,
    required this.title,
    required this.subtitle,
    required this.kind,
    this.imageUrl = '',
    this.category = '',
    this.priceText = '',
    this.raw = const {},
  });

  factory HomeContent.fromJson(
    Object? json, {
    required HomeSectionKind kind,
    Uri? mediaBaseUri,
    PublicMediaUrlResolver? mediaUrlResolver,
  }) {
    final map = _jsonMap(json);
    final nestedProject = _optionalMap(map['project']);
    final nestedInstitutionProject = _optionalMap(map['institutionProject']);
    final source = <String, Object?>{
      ...nestedProject,
      ...nestedInstitutionProject,
      ...map,
    };
    final id = _firstText(source, const [
      'id',
      'institutionProjectId',
      'projectId',
      'articleId',
      'diaryId',
      'institutionId',
      'doctorId',
    ]);
    if (id.isEmpty) {
      throw const FormatException('首页内容缺少 id');
    }
    final titleKeys = switch (kind) {
      HomeSectionKind.doctor => const ['name', 'doctorName', 'title'],
      HomeSectionKind.institution => const ['name', 'institutionName', 'title'],
      _ => const ['title', 'name', 'projectName', 'slogan'],
    };
    final subtitleKeys = switch (kind) {
      HomeSectionKind.doctor => const [
          'title',
          'institutionName',
          'specialties',
          'bio'
        ],
      HomeSectionKind.institution => const ['address', 'city', 'description'],
      _ => const [
          'subtitle',
          'description',
          'summary',
          'content',
          'institutionName',
          'authorName',
        ],
    };
    return HomeContent(
      id: id,
      title: _firstText(source, titleKeys, fallback: '未命名内容'),
      subtitle: _firstText(source, subtitleKeys),
      imageUrl: _firstImage(
        source,
        mediaUrlResolver: mediaUrlResolver ??
            (mediaBaseUri == null
                ? null
                : ApiPublicMediaUrlResolver(mediaBaseUri)),
      ),
      category: _firstText(source, const ['category', 'type', 'city']),
      priceText: _priceText(source['price'] ?? source['referencePrice']),
      kind: kind,
      raw: Map.unmodifiable(source),
    );
  }

  final String id;
  final String title;
  final String subtitle;
  final String imageUrl;
  final String category;
  final String priceText;
  final HomeSectionKind kind;
  final Map<String, Object?> raw;

  String text(List<String> keys, {String fallback = ''}) =>
      _firstText(raw, keys, fallback: fallback);

  int count(List<String> keys) {
    for (final key in keys) {
      final value = raw[key];
      if (value is num) return value.toInt();
      final parsed = int.tryParse(value?.toString() ?? '');
      if (parsed != null) return parsed;
    }
    return 0;
  }

  double number(List<String> keys) {
    for (final key in keys) {
      final value = raw[key];
      if (value is num) return value.toDouble();
      final parsed = double.tryParse(value?.toString() ?? '');
      if (parsed != null) return parsed;
    }
    return 0;
  }

  bool flag(List<String> keys) {
    for (final key in keys) {
      final value = raw[key];
      if (value is bool) return value;
      if (value?.toString().toLowerCase() == 'true' || value == 1) return true;
    }
    return false;
  }

  List<String> values(List<String> keys) {
    for (final key in keys) {
      final value = raw[key];
      if (value is List) {
        return value
            .map((item) => item.toString().trim())
            .where((item) => item.isNotEmpty)
            .toList(growable: false);
      }
      if (value is String && value.trim().isNotEmpty) {
        return value
            .split(',')
            .map((item) => item.trim())
            .where((item) => item.isNotEmpty)
            .toList(growable: false);
      }
    }
    return const [];
  }
}

final class HomeFeed {
  const HomeFeed({
    this.banners = const [],
    this.hotProjects = const [],
    this.expertArticles = const [],
    this.userDiaries = const [],
    this.recommendedInstitutionProjects = const [],
    this.institutions = const [],
    this.doctors = const [],
    this.failedSections = const {},
  });

  final List<HomeContent> banners;
  final List<HomeContent> hotProjects;
  final List<HomeContent> expertArticles;
  final List<HomeContent> userDiaries;
  final List<HomeContent> recommendedInstitutionProjects;
  final List<HomeContent> institutions;
  final List<HomeContent> doctors;
  final Set<HomeSectionKind> failedSections;

  bool get hasPartialFailures => failedSections.isNotEmpty;

  bool get isEmpty =>
      banners.isEmpty &&
      hotProjects.isEmpty &&
      expertArticles.isEmpty &&
      userDiaries.isEmpty &&
      recommendedInstitutionProjects.isEmpty &&
      institutions.isEmpty &&
      doctors.isEmpty;
}

Map<String, Object?> _jsonMap(Object? json) {
  if (json is! Map) {
    throw const FormatException('首页内容不是 JSON 对象');
  }
  return json.map((key, value) => MapEntry(key.toString(), value));
}

Map<String, Object?> _optionalMap(Object? json) {
  if (json is! Map) {
    return const {};
  }
  return json.map((key, value) => MapEntry(key.toString(), value));
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
    'imageUrl',
    'coverImage',
    'image',
    'bannerImage',
    'avatar',
    'logo',
    'images',
  ]) {
    final value = map[key];
    if (value is List) {
      for (final item in value) {
        final candidate = item.toString().trim();
        if (candidate.isNotEmpty) {
          return mediaUrlResolver?.resolve(candidate) ?? candidate;
        }
      }
    } else if (value != null) {
      final first = value
          .toString()
          .split(',')
          .map((item) => item.trim())
          .firstWhere((item) => item.isNotEmpty, orElse: () => '');
      if (first.isNotEmpty) {
        return mediaUrlResolver?.resolve(first) ?? first;
      }
    }
  }
  return '';
}

String _priceText(Object? value) {
  if (value == null || value.toString().trim().isEmpty) {
    return '';
  }
  if (value is num && value == value.roundToDouble()) {
    return '¥${value.toInt()}';
  }
  return '¥${value.toString().trim()}';
}
