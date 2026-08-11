import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/identity/data/identity_repository_impl.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';

void main() {
  test(
      'managed institution summary and detail preserve the legal profile contract',
      () {
    final summary = ManagedInstitutionSummary.fromJson(_summaryJson);
    final profile = ManagedInstitutionProfile.fromJson(_profileJson);

    expect(summary.id, 'institution-1');
    expect(summary.name, '上海娇颜颂医美中心');
    expect(summary.projectCount, 12);
    expect(summary.doctorCount, 6);
    expect(profile.establishedYear, isNull);
    expect(profile.images, ['lobby.jpg', 'treatment-room.jpg']);
    expect(profile.credentialImages, ['license.jpg']);
    expect(profile.specialties, ['注射美容', '皮肤管理']);
    expect(profile.tags, ['三甲合作', '连锁']);
    expect(profile.consultationCount, 310);
    expect(profile.createdAt, DateTime.parse('2026-01-01T09:00:00Z'));
  });

  test('managed institution update serializes the exact editable payload', () {
    const update = ManagedInstitutionProfileUpdate(
      name: ' 上海娇颜颂医美中心 ',
      address: ' 静安区南京西路 100 号 ',
      city: ' 上海市 ',
      description: ' 提供注射美容与皮肤管理服务。 ',
      coverImage: ' https://cdn.example.com/cover.jpg ',
      images: [' lobby.jpg ', 'treatment-room.jpg'],
      establishedYear: null,
      credentials: ' 医疗机构执业许可证 ',
      credentialImages: [' license.jpg '],
      specialties: [' 注射美容 ', '皮肤管理'],
      tags: [' 三甲合作 ', '连锁'],
      contactPhone: ' 13800000000 ',
      businessHours: ' 周一至周日 09:00-18:00 ',
    );

    expect(update.toJson(), {
      'name': '上海娇颜颂医美中心',
      'address': '静安区南京西路 100 号',
      'city': '上海',
      'description': '提供注射美容与皮肤管理服务。',
      'coverImage': 'https://cdn.example.com/cover.jpg',
      'images': ['lobby.jpg', 'treatment-room.jpg'],
      'establishedYear': null,
      'credentials': '医疗机构执业许可证',
      'credentialImages': ['license.jpg'],
      'specialties': ['注射美容', '皮肤管理'],
      'tags': ['三甲合作', '连锁'],
      'contactPhone': '13800000000',
      'businessHours': '周一至周日 09:00-18:00',
    });
  });

  test(
      'repository uses only the managed institution list detail and update paths',
      () async {
    final client = _RecordingApiClient();
    final repository = ApiIdentityRepository(client);

    final summaries = await repository.listManagedInstitutions();
    final profile = await repository.loadManagedInstitution('institution-1');
    await repository.updateManagedInstitution(
      profile.id,
      ManagedInstitutionProfileUpdate.fromProfile(profile),
    );

    expect(summaries.single.id, 'institution-1');
    expect(profile.doctorCount, 6);
    expect(client.requests, [
      const _Request('GET', '/management/institutions'),
      const _Request('GET', '/management/institutions/institution-1'),
      _Request(
        'PUT',
        '/management/institutions/institution-1',
        body: ManagedInstitutionProfileUpdate.fromProfile(profile).toJson(),
      ),
    ]);
  });

  test('professional-visible institution summaries keep the compatibility path',
      () async {
    final client = _RecordingApiClient();
    final repository = ApiIdentityRepository(client);

    final summaries = await repository.listProfessionalVisibleInstitutions();

    expect(summaries.single.id, 'institution-1');
    expect(client.requests, [
      const _Request('GET', '/admin/institutions'),
    ]);
  });
}

const _summaryJson = <String, Object?>{
  'id': 'institution-1',
  'name': '上海娇颜颂医美中心',
  'address': '静安区南京西路 100 号',
  'city': '上海市',
  'coverImage': 'https://cdn.example.com/cover.jpg',
  'rating': 4.8,
  'reviewCount': 126,
  'isVerified': true,
  'projectCount': 12,
  'doctorCount': 6,
};

const _profileJson = <String, Object?>{
  ..._summaryJson,
  'description': '提供注射美容与皮肤管理服务。',
  'images': ['lobby.jpg', 'treatment-room.jpg'],
  'establishedYear': null,
  'credentials': '医疗机构执业许可证',
  'credentialImages': ['license.jpg'],
  'specialties': ['注射美容', '皮肤管理'],
  'tags': ['三甲合作', '连锁'],
  'contactPhone': '13800000000',
  'businessHours': '周一至周日 09:00-18:00',
  'certificationTime': '2026-01-15',
  'consultationCount': 310,
  'userCount': 280,
  'caseCount': 96,
  'createdAt': '2026-01-01T09:00:00Z',
  'updatedAt': '2026-08-11T10:30:00Z',
};

final class _RecordingApiClient extends ApiClient {
  _RecordingApiClient() : super(apiRoot: Uri.parse('http://localhost/api/'));

  final List<_Request> requests = [];

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    requests.add(_Request('GET', path));
    return decodeData(
        path == '/management/institutions' || path == '/admin/institutions'
            ? [_summaryJson]
            : _profileJson);
  }

  @override
  Future<T?> put<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    requests.add(_Request('PUT', path, body: body));
    return decodeData(_profileJson);
  }
}

final class _Request {
  const _Request(this.method, this.path, {this.body});

  final String method;
  final String path;
  final Object? body;

  @override
  bool operator ==(Object other) =>
      other is _Request &&
      other.method == method &&
      other.path == path &&
      _deepEquals(other.body, body);

  @override
  int get hashCode => Object.hash(method, path, body);
}

bool _deepEquals(Object? left, Object? right) {
  if (left is Map && right is Map) {
    return left.length == right.length &&
        left.entries.every(
          (entry) =>
              right.containsKey(entry.key) &&
              _deepEquals(entry.value, right[entry.key]),
        );
  }
  if (left is List && right is List) {
    return left.length == right.length &&
        Iterable.generate(left.length)
            .every((index) => _deepEquals(left[index], right[index]));
  }
  return left == right;
}
