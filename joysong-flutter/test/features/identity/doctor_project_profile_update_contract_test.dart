import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/identity/data/identity_repository_impl.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';

void main() {
  late Map<String, dynamic> fixture;

  setUpAll(() async {
    fixture = jsonDecode(
        await File('../test-fixtures/doctor-project-change-v2.json')
            .readAsString()) as Map<String, dynamic>;
  });

  test('decodes every frozen v2 golden variant and keeps damaged rows visible',
      () {
    final target =
        DoctorProjectProfileUpdateTarget.fromJson(fixture['targetV2']);
    final request = DoctorProjectChangeRequest.fromJson(fixture['requestV2']);
    final damaged = DoctorProjectChangeRequest.fromJson(
        fixture['requestV2InvalidSnapshot']);
    expect(target.payloadVersion, 2);
    expect(target.baseRevision, isNotEmpty);
    expect(target.currentProject!.source['institutionProjectVersion'], 7);
    expect(request.requestStatus, 'PENDING');
    expect(request.currentDoctorActive, isTrue);
    expect(request.hasCompleteSnapshot, isTrue);
    expect(damaged.hasCompleteSnapshot, isFalse);
    expect(damaged.snapshotError, 'REQUEST_SNAPSHOT_INVALID');
    expect(damaged.reviewable, isFalse);
  });

  test('PROFILE_UPDATE emits the exact frozen fifteen-key v2 body', () {
    const draft = DoctorProjectProfileUpdateDraft(
      institutionProjectId: ' ip-1 ',
      baseRevision: ' rev-1 ',
      name: ' Name ',
      category: ' Skin ',
      description: ' Description ',
      tags: [' one '],
      slogan: ' Slogan ',
      detailContent: ' Detail ',
      price: 1100,
      salesCount: 12,
      doctorActive: false,
      coverImage: ' cover.jpg ',
      images: [' image.jpg '],
      notes: ' note ',
    );
    expect(draft.toJson(), {
      'requestType': 'PROFILE_UPDATE',
      'institutionProjectId': 'ip-1',
      'baseRevision': 'rev-1',
      'name': 'Name',
      'category': 'Skin',
      'description': 'Description',
      'tags': ['one'],
      'slogan': 'Slogan',
      'detailContent': 'Detail',
      'price': 1100,
      'salesCount': 12,
      'doctorActive': false,
      'coverImage': 'cover.jpg',
      'images': ['image.jpg'],
      'notes': 'note',
    });
  });

  test('repository uses v2 paths and the exact four-key review body', () async {
    final client = _RecordingApiClient(fixture);
    final repository = ApiIdentityRepository(client);
    const draft = DoctorProjectProfileUpdateDraft(
      institutionProjectId: 'ip-1',
      baseRevision: 'rev-1',
      name: 'Name',
      category: 'Skin',
      description: 'Description',
      tags: [],
      slogan: '',
      price: 1100,
      salesCount: 0,
      doctorActive: true,
      coverImage: '',
      images: [],
      notes: '',
    );
    final created = await repository.submitDoctorProjectProfileUpdate(draft);
    await repository.reviewDoctorProjectChangeRequest(
      id: created.id,
      decision: 'APPROVED',
      reviewNote: ' approved ',
      force: false,
      forceBaseRevision: null,
    );
    await repository.reviewInstitutionProjectJoinRequest(
      id: 'join-v1',
      decision: 'REJECTED',
      reviewNote: ' no ',
    );
    expect(client.requests, [
      _Request(
          'POST', '/v2/admin/institution-project-requests', draft.toJson()),
      const _Request(
          'POST', '/v2/admin/institution-project-requests/request-v2/review', {
        'decision': 'APPROVED',
        'reviewNote': 'approved',
        'force': false,
        'forceBaseRevision': null
      }),
      const _Request(
          'POST', '/v2/admin/institution-project-requests/join-v1/review', {
        'decision': 'REJECTED',
        'reviewNote': 'no',
        'force': false,
        'forceBaseRevision': null
      }),
    ]);
  });
}

final class _RecordingApiClient extends ApiClient {
  _RecordingApiClient(this.fixture)
      : super(apiRoot: Uri.parse('http://localhost/api/'));
  final Map<String, dynamic> fixture;
  final List<_Request> requests = [];
  @override
  Future<T?> post<T>(String path,
      {Object? body, required T Function(Object? json) decodeData}) async {
    requests.add(_Request('POST', path, body));
    return decodeData(path.endsWith('/review') ? null : fixture['requestV2']);
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
  if (left is Map && right is Map)
    return left.length == right.length &&
        left.entries.every((entry) =>
            right.containsKey(entry.key) &&
            _deepEquals(entry.value, right[entry.key]));
  if (left is List && right is List)
    return left.length == right.length &&
        Iterable.generate(left.length)
            .every((index) => _deepEquals(left[index], right[index]));
  return left == right;
}
