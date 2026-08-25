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

  test('decodes every frozen golden variant and retains v1/v2 metadata', () {
    final dynamic target =
        DoctorProjectProfileUpdateTarget.fromJson(fixture['targetV2']);
    final dynamic legacy =
        DoctorProjectChangeRequest.fromJson(fixture['requestV1']);
    final dynamic request =
        DoctorProjectChangeRequest.fromJson(fixture['requestV2']);
    final damaged = DoctorProjectChangeRequest.fromJson(
        fixture['requestV2InvalidSnapshot']);

    expect(target.payloadVersion, 2);
    expect(target.platformProjectId, 'pp-1');
    expect(target.platformProjectName, 'Face Lift');
    expect(target.doctorId, 'doctor-1');
    expect(target.doctorName, 'Doctor One');
    expect(target.currentProject!.source['institutionProjectVersion'], 7);
    expect(legacy.payloadVersion, 1);
    expect(legacy.scheduleNote, 'Weekdays');
    expect(legacy.currentScheduleNote, 'Current weekdays');
    expect(legacy.submittedBy, 'doctor-1');
    expect(legacy.submittedAt, DateTime.parse('2026-08-24T01:02:03'));
    expect(request.platformProjectId, 'pp-1');
    expect(request.platformProjectName, 'Face Lift');
    expect(request.submittedBy, 'doctor-1');
    expect(request.submittedAt, DateTime.parse('2026-08-24T01:02:03.456Z'));
    expect(request.reviewedBy, isNull);
    expect(request.reviewerName, isNull);
    expect(request.reviewedAt, isNull);
    expect(request.updatedAt, DateTime.parse('2026-08-24T01:02:03.456Z'));
    expect(request.snapshotState, 'VALID');
    expect(request.requestStatus, 'PENDING');
    expect(request.currentDoctorActive, isTrue);
    expect(request.hasCompleteSnapshot, isTrue);
    expect(damaged.hasCompleteSnapshot, isFalse);
    expect(damaged.snapshotError, 'REQUEST_SNAPSHOT_INVALID');
    expect(damaged.reviewable, isFalse);
  });

  test('payload discriminators select only explicit integer v1 or v2', () {
    for (final badVersion in <Object?>[null, '2', 2.0, 3]) {
      final target = _cloneMap(fixture['targetV2']);
      final request = _cloneMap(fixture['requestV1']);
      if (badVersion == null) {
        target.remove('payloadVersion');
        request.remove('payloadVersion');
      } else {
        target['payloadVersion'] = badVersion;
        request['payloadVersion'] = badVersion;
      }
      expect(
        () => DoctorProjectProfileUpdateTarget.fromJson(target),
        throwsFormatException,
        reason: 'target discriminator $badVersion must fail closed',
      );
      expect(
        () => DoctorProjectChangeRequest.fromJson(request),
        throwsFormatException,
        reason: 'request discriminator $badVersion must fail closed',
      );
    }
  });

  test('v2 decoding rejects coercible scalar and collection values', () {
    final wrongName = _cloneMap(fixture['targetV2'])
      ..['institutionName'] = 42;
    final nullName = _cloneMap(fixture['targetV2'])
      ..['institutionName'] = null;
    final wrongPrice = _cloneMap(fixture['targetV2'])
      ..['currentDoctorPrice'] = '1000.00';
    final missingPrice = _cloneMap(fixture['targetV2'])
      ..remove('currentDoctorPrice');
    final wrongActive = _cloneMap(fixture['targetV2'])
      ..['currentDoctorActive'] = 1;
    final wrongSnapshotVersion = _cloneMap(fixture['targetV2']);
    (wrongSnapshotVersion['currentProject'] as Map<String, dynamic>)[
        'schemaVersion'] = 2.0;
    final wrongSales = _cloneMap(fixture['targetV2']);
    (wrongSales['currentProject'] as Map<String, dynamic>)['effective'] =
        Map<String, dynamic>.of(
      ((wrongSales['currentProject'] as Map<String, dynamic>)['effective']
          as Map<String, dynamic>),
    )..['salesCount'] = 12.5;
    final wrongTags = _cloneMap(fixture['targetV2']);
    (wrongTags['currentProject'] as Map<String, dynamic>)['effective'] =
        Map<String, dynamic>.of(
      ((wrongTags['currentProject'] as Map<String, dynamic>)['effective']
          as Map<String, dynamic>),
    )..['tags'] = 'local,updated';
    final wrongTagMember = _cloneMap(fixture['targetV2']);
    (wrongTagMember['currentProject'] as Map<String, dynamic>)['effective'] =
        Map<String, dynamic>.of(
      ((wrongTagMember['currentProject'] as Map<String, dynamic>)['effective']
          as Map<String, dynamic>),
    )..['tags'] = ['local', 7];

    for (final malformed in [
      wrongName,
      nullName,
      wrongPrice,
      missingPrice,
      wrongActive,
      wrongSnapshotVersion,
      wrongSales,
      wrongTags,
      wrongTagMember,
    ]) {
      expect(
        () => DoctorProjectProfileUpdateTarget.fromJson(malformed),
        throwsFormatException,
      );
    }

    for (final malformed in <Map<String, dynamic>>[
      _cloneMap(fixture['requestV2'])..['notes'] = null,
      _cloneMap(fixture['requestV2'])..['sharedChanged'] = 'true',
      _cloneMap(fixture['requestV2'])..['proposedDoctorPrice'] = '1100.00',
      _cloneMap(fixture['requestV2'])..['latestDoctorPrice'] = '1000.00',
      _cloneMap(fixture['requestV2'])..['latestDoctorActive'] = 1,
      _cloneMap(fixture['requestV2'])..['latestRevision'] = 7,
    ]) {
      expect(
        () => DoctorProjectChangeRequest.fromJson(malformed),
        throwsFormatException,
      );
    }
  });

  test('damaged non-null snapshots preserve outer metadata and other slots', () {
    final json = _cloneMap(fixture['requestV2'])
      ..['currentProject'] = <String, Object?>{'schemaVersion': 2}
      ..['snapshotError'] = null;

    final request = DoctorProjectChangeRequest.fromJson(json);

    expect(request.id, 'request-v2');
    expect(request.currentProject, isNull);
    expect(request.proposedProject, isNotNull);
    expect(request.latestProject, isNotNull);
    expect(request.hasCompleteSnapshot, isFalse);
    expect(request.snapshotError, 'REQUEST_SNAPSHOT_INVALID');
    expect(request.reviewable, isFalse);
  });

  test('v2 latest snapshot revision price and active may all be null', () {
    final json = _cloneMap(fixture['requestV2'])
      ..['latestProject'] = null
      ..['latestRevision'] = null
      ..['latestDoctorPrice'] = null
      ..['latestDoctorActive'] = null;

    final request = DoctorProjectChangeRequest.fromJson(json);

    expect(request.latestProject, isNull);
    expect(request.latestRevision, isNull);
    expect(request.latestDoctorPrice, isNull);
    expect(request.latestDoctorActive, isNull);
    expect(request.hasCompleteSnapshot, isTrue);
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

  test('repository parses the mixed list once and filters each consumer',
      () async {
    final client = _RecordingApiClient(fixture);
    final repository = ApiIdentityRepository(client);

    final joins = await repository.listInstitutionProjectJoinRequests();
    final profiles = await repository.listDoctorProjectChangeRequests();

    expect(joins.map((request) => request.id), ['join-v1']);
    expect(profiles.map((request) => request.id), [
      'leave-v1',
      'request-v1',
      'request-v2',
    ]);
    expect(profiles[1].scheduleNote, 'Weekdays');
    expect(profiles.last.requestType, 'EDIT');
  });

  test('repository uses every frozen v2 path and exact request body', () async {
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
      detailContent: null,
      price: 1100,
      salesCount: 0,
      doctorActive: true,
      coverImage: '',
      images: [],
      notes: '',
    );
    await repository.listInstitutionProjectJoinRequests();
    await repository.listDoctorProjectChangeRequests();
    await repository.listDoctorProjectProfileUpdateTargets();
    final created = await repository.submitDoctorProjectProfileUpdate(draft);
    await repository.submitInstitutionProjectJoinRequest(
      const InstitutionProjectJoinRequestDraft(
        institutionProjectId: 'ip-1',
        serviceDescription: 'Join service',
        priceSuggestion: 1000,
        platformRate: 40,
        notes: 'join',
      ),
    );
    await repository.submitDoctorProjectLeave(institutionProjectId: ' ip-1 ');
    await repository.withdrawDoctorProjectChangeRequest(' request-v2 ');
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
      const _Request('GET', '/v2/admin/institution-project-requests', null),
      const _Request('GET', '/v2/admin/institution-project-requests', null),
      const _Request(
        'GET',
        '/v2/admin/institution-project-requests/profile-update-targets',
        null,
      ),
      _Request(
          'POST', '/v2/admin/institution-project-requests', draft.toJson()),
      const _Request('POST', '/v2/admin/institution-project-requests', {
        'requestType': 'JOIN',
        'institutionProjectId': 'ip-1',
        'serviceDescription': 'Join service',
        'priceSuggestion': 1000,
        'notes': 'join',
      }),
      const _Request('POST', '/v2/admin/institution-project-requests', {
        'requestType': 'LEAVE',
        'institutionProjectId': 'ip-1',
      }),
      const _Request(
        'POST',
        '/v2/admin/institution-project-requests/request-v2/withdraw',
        null,
      ),
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
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    requests.add(_Request('GET', path, null));
    if (path.endsWith('/profile-update-targets')) {
      return decodeData([fixture['targetV2']]);
    }
    return decodeData(_mixedRequests());
  }

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    requests.add(_Request('POST', path, body));
    if (path.endsWith('/review') || path.endsWith('/withdraw')) {
      return decodeData(null);
    }
    final requestType = (body as Map?)?['requestType'];
    if (requestType == 'JOIN' || requestType == 'LEAVE') {
      final legacy = _cloneMap(fixture['requestV1'])
        ..['id'] = requestType == 'JOIN' ? 'join-v1' : 'leave-v1'
        ..['requestType'] = requestType;
      return decodeData(legacy);
    }
    return decodeData(fixture['requestV2']);
  }

  List<Map<String, dynamic>> _mixedRequests() {
    final join = _cloneMap(fixture['requestV1'])
      ..['id'] = 'join-v1'
      ..['requestType'] = 'JOIN';
    final leave = _cloneMap(fixture['requestV1'])
      ..['id'] = 'leave-v1'
      ..['requestType'] = 'LEAVE';
    return [
      join,
      leave,
      _cloneMap(fixture['requestV1']),
      _cloneMap(fixture['requestV2']),
    ];
  }
}

Map<String, dynamic> _cloneMap(Object? value) =>
    jsonDecode(jsonEncode(value)) as Map<String, dynamic>;

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
