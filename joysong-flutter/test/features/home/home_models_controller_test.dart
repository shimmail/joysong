import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/public_media_url.dart';
import 'package:joysong_flutter/features/home/data/home_repository_impl.dart';
import 'package:joysong_flutter/features/home/domain/home_models.dart';
import 'package:joysong_flutter/features/home/domain/home_repository.dart';
import 'package:joysong_flutter/features/home/presentation/home_controller.dart';
import 'package:joysong_flutter/features/home/presentation/home_page.dart';

void main() {
  test('maps a nested institution project without using floating point math',
      () {
    final item = HomeContent.fromJson(
      {
        'institutionProjectId': 'ip-1',
        'institutionName': '测试机构',
        'price': '1280.00',
        'project': {
          'name': '光电项目',
          'description': '项目说明',
          'coverImage': 'https://example.test/cover.jpg',
        },
      },
      kind: HomeSectionKind.recommendedInstitutionProject,
    );

    expect(item.id, 'ip-1');
    expect(item.title, '光电项目');
    expect(item.subtitle, '项目说明');
    expect(item.priceText, '¥1280.00');
  });

  test('maps uploaded localhost covers to the active Android API host', () {
    final item = HomeContent.fromJson(
      {
        'id': 'project-1',
        'name': '光电项目',
        'coverImage': 'http://localhost:8080/images/projects/cover.jpg',
      },
      kind: HomeSectionKind.hotProject,
      mediaUrlResolver: ApiPublicMediaUrlResolver(
        Uri.parse('http://10.0.2.2:8080/api/'),
      ),
    );

    expect(
      item.imageUrl,
      'http://10.0.2.2:8080/images/projects/cover.jpg',
    );
  });

  test('maps emulator cover URLs to the active physical-device API host', () {
    final item = HomeContent.fromJson(
      {
        'id': 'project-physical-device',
        'name': '真机封面',
        'coverImage': 'http://10.0.2.2:8080/images/projects/cover.jpg',
      },
      kind: HomeSectionKind.hotProject,
      mediaUrlResolver: ApiPublicMediaUrlResolver(
        Uri.parse('http://192.168.2.54:8080/api/'),
      ),
    );

    expect(
      item.imageUrl,
      'http://192.168.2.54:8080/images/projects/cover.jpg',
    );
  });

  test('maps relative uploaded covers and preserves remote CDN covers', () {
    final relative = HomeContent.fromJson(
      {'id': 'diary-1', 'title': '恢复记录', 'images': '/images/diary/a.jpg,b.jpg'},
      kind: HomeSectionKind.userDiary,
      mediaBaseUri: Uri.parse('https://api.example.test/api/'),
    );
    final remote = HomeContent.fromJson(
      {
        'id': 'article-1',
        'title': '护理指南',
        'coverImage': 'https://cdn.example.test/a.webp'
      },
      kind: HomeSectionKind.expertArticle,
      mediaBaseUri: Uri.parse('https://api.example.test/api/'),
    );

    expect(relative.imageUrl, 'https://api.example.test/images/diary/a.jpg');
    expect(remote.imageUrl, 'https://cdn.example.test/a.webp');
  });

  test('controller exposes empty and retryable failure states', () async {
    final repository = _FakeHomeRepository();
    final controller = HomeController(repository);

    await controller.load();
    expect(controller.status, HomeLoadStatus.failure);
    expect(controller.errorMessage, isNotEmpty);

    repository.feed = const HomeFeed();
    await controller.load(refresh: true);
    expect(controller.status, HomeLoadStatus.empty);
  });

  test('home repository keeps successful sections when one section fails',
      () async {
    final client = _FakeHomeApiClient()
      ..responses['/home/banners'] = [
        {'id': 'banner-1', 'title': '真实 Banner'},
      ]
      ..failedPaths.add('/home/hot-projects');
    final repository = ApiHomeRepository(client);

    final feed = await repository.loadHome();

    expect(feed.banners.single.id, 'banner-1');
    expect(feed.hotProjects, isEmpty);
    expect(feed.failedSections, {HomeSectionKind.hotProject});
    expect(feed.hasPartialFailures, isTrue);
  });

  test('home repository fails when every section is unavailable', () async {
    final client = _FakeHomeApiClient()
      ..failedPaths.addAll(_FakeHomeApiClient.paths);

    await expectLater(
      ApiHomeRepository(client).loadHome(),
      throwsA(isA<HomeFeedUnavailableException>()),
    );
  });

  testWidgets('home page does not expose preview content when disabled',
      (tester) async {
    await tester.pumpWidget(
      const MaterialApp(locale: Locale('zh'), home: HomePage()),
    );

    expect(find.text('安心变美，从了解开始'), findsNothing);
    final unavailableMessages = [
      ...find.text('首页数据暂不可用，请检查服务连接后重试').evaluate(),
      ...find
          .text(
            'Home data is unavailable. Check the service connection and try again.',
          )
          .evaluate(),
    ];
    expect(unavailableMessages, hasLength(1));
  });

  testWidgets('home section action reports the selected section',
      (tester) async {
    HomeSectionKind? selectedSection;
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        home: HomePage(
          allowPreviewData: true,
          onViewAll: (section) => selectedSection = section,
        ),
      ),
    );

    await tester.tap(find.text('查看全部'));
    await tester.pump();

    expect(selectedSection, HomeSectionKind.hotProject);
  });
}

final class _FakeHomeRepository implements HomeRepository {
  HomeFeed? feed;

  @override
  Future<HomeFeed> loadHome() async {
    final value = feed;
    if (value == null) {
      throw Exception('offline');
    }
    return value;
  }
}

final class _FakeHomeApiClient extends ApiClient {
  _FakeHomeApiClient() : super(apiRoot: Uri.parse('http://localhost/api/'));

  static const paths = {
    '/home/banners',
    '/home/hot-projects',
    '/home/expert-articles',
    '/home/user-diaries',
    '/home/recommended-institution-projects',
    '/discover/institutions',
    '/discover/doctors',
  };

  final Map<String, Object?> responses = {
    for (final path in paths) path: const <Object?>[],
  };
  final Set<String> failedPaths = {};

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    if (failedPaths.contains(path)) {
      throw Exception('offline: $path');
    }
    return decodeData(responses[path]);
  }
}
