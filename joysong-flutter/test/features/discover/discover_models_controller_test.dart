import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/discover_controller.dart';

void main() {
  test('maps nested doctor detail and comma-separated image safely', () {
    final item = DiscoverItem.fromJson(
      {
        'doctor': {
          'id': 'doctor-1',
          'name': '张医生',
          'specialties': '皮肤管理',
          'credentialImages': 'first.jpg,second.jpg',
        },
        'institutions': const [],
      },
      type: DiscoverContentType.doctor,
    );

    expect(item.id, 'doctor-1');
    expect(item.title, '张医生');
    expect(item.subtitle, '皮肤管理');
  });

  test('controller paginates and removes duplicate ids', () async {
    final repository = _FakeDiscoverRepository();
    final controller = DiscoverController(
      repository,
      type: DiscoverContentType.project,
      pageSize: 2,
    );

    await controller.load(query: '光电');
    expect(controller.items.map((item) => item.id), ['1', '2']);
    expect(controller.hasMore, isTrue);

    await controller.loadMore();
    expect(controller.items.map((item) => item.id), ['1', '2', '3']);
    expect(controller.hasMore, isFalse);
    expect(repository.queries, ['光电', '光电']);
  });
}

final class _FakeDiscoverRepository implements DiscoverRepository {
  final queries = <String>[];

  @override
  Future<DiscoverFilterOptions> loadFilterOptions() async {
    return const DiscoverFilterOptions();
  }

  @override
  Future<DiscoverItem> loadDetail({
    required DiscoverContentType type,
    required String id,
  }) {
    throw UnimplementedError();
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
    queries.add(query);
    if (offset == 0) {
      return const DiscoverPageResult(
        items: [
          DiscoverItem(
            id: '1',
            type: DiscoverContentType.project,
            title: '项目一',
          ),
          DiscoverItem(
            id: '2',
            type: DiscoverContentType.project,
            title: '项目二',
          ),
        ],
        hasMore: true,
      );
    }
    return const DiscoverPageResult(
      items: [
        DiscoverItem(
          id: '2',
          type: DiscoverContentType.project,
          title: '重复项目',
        ),
        DiscoverItem(
          id: '3',
          type: DiscoverContentType.project,
          title: '项目三',
        ),
      ],
      hasMore: false,
    );
  }
}
