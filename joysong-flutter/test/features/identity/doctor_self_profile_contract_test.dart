import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/identity/data/identity_repository_impl.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';

void main() {
  test('doctor self profile maps editable, institution, and platform data', () {
    final profile = DoctorSelfProfile.fromJson(_profileJson);

    expect(profile.id, 'doctor-1');
    expect(profile.userId, 'user-1');
    expect(profile.name, '李医生');
    expect(profile.title, '主任医师');
    expect(profile.bio, '');
    expect(profile.institutionId, 'institution-legacy');
    expect(profile.institutions.map((item) => item.name), ['第一医院', '第二医院']);
    expect(profile.primaryInstitution?.id, 'institution-1');
    expect(profile.institutionCount, 2);
    expect(profile.rating, 4.8);
    expect(profile.reviewCount, 13);
    expect(profile.isVerified, isTrue);
    expect(profile.consultationCount, 21);
    expect(profile.caseCount, 8);
    expect(profile.toUpdate().bio, '');
  });

  test('doctor self profile update emits only the normalized nine-key payload',
      () {
    const update = DoctorSelfProfileUpdate(
      name: ' 李医生 ',
      title: ' 主任医师 ',
      bio: ' 擅长皮肤修复 ',
      avatar: ' https://cdn.example/avatar.png ',
      contactPhone: ' 13800000000 ',
      specialties: ' 皮肤修复, , 注射美容 ',
      credentials: ' 公开展示说明 ',
      credentialImages: ' cert-1.png, ,cert-2.png ',
      certificationTags: ' 专家, 三甲, ',
    );

    expect(update.toJson(), {
      'name': '李医生',
      'title': '主任医师',
      'bio': '擅长皮肤修复',
      'avatar': 'https://cdn.example/avatar.png',
      'contactPhone': '13800000000',
      'specialties': '皮肤修复,注射美容',
      'credentials': '公开展示说明',
      'credentialImages': 'cert-1.png,cert-2.png',
      'certificationTags': '专家,三甲',
    });
  });

  test('repository uses the single doctor profile route for GET and PUT',
      () async {
    final client = _RecordingApiClient();
    final repository = ApiIdentityRepository(client);

    final loaded = await repository.loadDoctorSelfProfile();
    final updated = await repository.updateDoctorSelfProfile(loaded.toUpdate());

    expect(loaded.id, 'doctor-1');
    expect(updated.userId, 'user-1');
    expect(client.requests, [
      const _Request('GET', '/management/doctor-profile'),
      _Request(
        'PUT',
        '/management/doctor-profile',
        body: loaded.toUpdate().toJson(),
      ),
    ]);
  });
}

const _profileJson = <String, Object?>{
  'id': 'doctor-1',
  'userId': 'user-1',
  'name': '李医生',
  'title': '主任医师',
  'bio': '',
  'avatar': 'https://cdn.example/avatar.png',
  'contactPhone': '13800000000',
  'specialties': '皮肤修复,注射美容',
  'credentials': '公开展示说明',
  'credentialImages': 'cert-1.png,cert-2.png',
  'certificationTags': '专家,三甲',
  'institutionId': 'institution-legacy',
  'institutionName': '示例医美中心',
  'institutions': [
    {'id': 'institution-1', 'name': '第一医院'},
    {'id': 'institution-2', 'name': '第二医院'},
  ],
  'primaryInstitution': {'id': 'institution-1', 'name': '第一医院'},
  'institutionCount': 2,
  'rating': 4.8,
  'reviewCount': 13,
  'isVerified': true,
  'consultationCount': 21,
  'caseCount': 8,
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
    return decodeData(_profileJson);
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
  return left == right;
}
