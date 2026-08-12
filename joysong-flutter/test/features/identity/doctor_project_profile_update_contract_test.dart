import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/identity/data/identity_repository_impl.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';

void main() {
  test('PROFILE_UPDATE draft emits the exact twelve-key request', () {
    const draft = DoctorProjectProfileUpdateDraft(
      institutionProjectId: ' ip-1 ',
      priceSuggestion: 12800,
      serviceDescription: ' 服务说明 ',
      serviceTags: [' 自然 ', '精细化'],
      scheduleNote: ' 周二、四 ',
      coverImage: ' cover.jpg ',
      images: [' one.jpg ', 'two.jpg'],
      consultationFee: 300,
      commissionRate: 10,
      institutionRate: 40,
      notes: ' 整体调整 ',
    );

    expect(draft.toJson(), {
      'requestType': 'PROFILE_UPDATE',
      'institutionProjectId': 'ip-1',
      'priceSuggestion': 12800,
      'serviceDescription': '服务说明',
      'serviceTags': ['自然', '精细化'],
      'scheduleNote': '周二、四',
      'coverImage': 'cover.jpg',
      'images': ['one.jpg', 'two.jpg'],
      'consultationFee': 300,
      'commissionRate': 10,
      'institutionRate': 40,
      'notes': '整体调整',
    });
  });

  test('request view decodes arrays, rates, and force audit fields', () {
    final request = DoctorProjectChangeRequest.fromJson(_requestJson);

    expect(request.serviceTags, ['自然', '精细化']);
    expect(request.images, ['one.jpg']);
    expect(request.platformRate, 10);
    expect(request.doctorRate, 40);
    expect(request.forceProcessed, isTrue);
  });

  test(
      'repository submits on the existing path and reviews with explicit force',
      () async {
    final client = _RecordingApiClient();
    final repository = ApiIdentityRepository(client);
    const draft = DoctorProjectProfileUpdateDraft(
      institutionProjectId: 'ip-1',
      priceSuggestion: 12800,
      serviceDescription: '服务说明',
      serviceTags: ['自然'],
      scheduleNote: '',
      coverImage: '',
      images: [],
      consultationFee: 300,
      commissionRate: 10,
      institutionRate: 40,
      notes: '',
    );

    final created = await repository.submitDoctorProjectProfileUpdate(draft);
    await repository.reviewDoctorProjectChangeRequest(
      id: created.id,
      decision: 'APPROVED',
      reviewNote: '管理员强制处置',
      force: true,
    );

    expect(client.requests, [
      _Request('POST', '/admin/institution-project-requests', draft.toJson()),
      const _Request(
        'POST',
        '/admin/institution-project-requests/request-1/review',
        {'decision': 'APPROVED', 'reviewNote': '管理员强制处置', 'force': true},
      ),
    ]);
  });

  test('repository loads the server-owned current profile update target',
      () async {
    final client = _RecordingApiClient();
    final targets = await ApiIdentityRepository(client)
        .listDoctorProjectProfileUpdateTargets();

    expect(targets.single.currentPrice, 12000);
    expect(targets.single.platformRate, 10);
    expect(client.requests.single.path,
        '/admin/institution-project-requests/profile-update-targets');
  });
}

const _requestJson = <String, Object?>{
  'id': 'request-1',
  'doctorId': 'doctor-1',
  'doctorName': '李医生',
  'institutionId': 'institution-1',
  'institutionName': '娇颜颂',
  'institutionProjectId': 'ip-1',
  'projectName': '项目一',
  'requestType': 'PROFILE_UPDATE',
  'serviceDescription': '服务说明',
  'priceSuggestion': 12800,
  'notes': '',
  'serviceTags': ['自然', '精细化'],
  'scheduleNote': '',
  'coverImage': '',
  'images': ['one.jpg'],
  'consultationFee': 300,
  'commissionRate': 10,
  'institutionRate': 40,
  'platformRate': 10,
  'doctorRate': 40,
  'forceProcessed': true,
  'currentPrice': 12000,
  'currentServiceDescription': '当前说明',
  'currentServiceTags': ['当前标签'],
  'currentScheduleNote': '周二',
  'currentCoverImage': 'old-cover.jpg',
  'currentImages': ['old.jpg'],
  'currentConsultationFee': 200,
  'currentCommissionRate': 5,
  'currentInstitutionRate': 40,
  'currentPlatformRate': 10,
  'currentDoctorRate': 45,
  'status': 'PENDING',
  'submittedBy': 'user-1',
  'reviewedBy': null,
  'reviewerName': null,
  'reviewNote': '',
  'submittedAt': '2026-08-12T08:00:00',
  'reviewedAt': null,
  'updatedAt': '2026-08-12T08:00:00',
};

const _targetJson = <String, Object?>{
  'institutionProjectId': 'ip-1',
  'projectName': '项目一',
  'institutionId': 'institution-1',
  'institutionName': '娇颜颂',
  'currentPrice': 12000,
  'serviceDescription': '当前说明',
  'serviceTags': ['自然'],
  'scheduleNote': '',
  'coverImage': '',
  'images': <String>[],
  'consultationFee': 200,
  'commissionRate': 10,
  'institutionRate': 40,
  'platformRate': 10,
  'doctorRate': 40,
};

final class _RecordingApiClient extends ApiClient {
  _RecordingApiClient() : super(apiRoot: Uri.parse('http://localhost/api/'));
  final List<_Request> requests = [];

  @override
  Future<T?> get<T>(String path,
      {Map<String, Object?> query = const {},
      required T Function(Object? json) decodeData}) async {
    requests.add(_Request('GET', path, null));
    return decodeData([_targetJson]);
  }

  @override
  Future<T?> post<T>(String path,
      {Object? body, required T Function(Object? json) decodeData}) async {
    requests.add(_Request('POST', path, body));
    return decodeData(path.endsWith('/review') ? null : _requestJson);
  }
}

final class _Request {
  const _Request(this.method, this.path, this.body);
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
        left.entries.every((entry) =>
            right.containsKey(entry.key) &&
            _deepEquals(entry.value, right[entry.key]));
  }
  if (left is List && right is List) {
    return left.length == right.length &&
        Iterable.generate(left.length)
            .every((index) => _deepEquals(left[index], right[index]));
  }
  return left == right;
}
