import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/identity/data/identity_repository_impl.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';

void main() {
  test('repository uses explicit owned reviewable and candidate scopes',
      () async {
    final client = _RecordingApiClient();
    final repository = ApiIdentityRepository(client);

    final owned = await repository.listOwnedInstitutionMembershipRequests();
    final reviewable =
        await repository.listReviewableInstitutionMembershipRequests();
    final candidates = await repository.listInstitutionMembershipCandidates(
      requestType: InstitutionMembershipRequestType.consultant,
      action: InstitutionMembershipAction.leave,
      query: '',
      offset: 40,
      limit: 150,
    );

    expect(owned.single.requestType, InstitutionMembershipRequestType.doctor);
    expect(
      reviewable.single.requestType,
      InstitutionMembershipRequestType.consultant,
    );
    expect(candidates.items.single.name, 'Joysong Clinic');
    expect(candidates.offset, 40);
    expect(candidates.limit, 100);
    expect(candidates.hasMore, isFalse);
    expect(client.requests, [
      const _Request(
        'GET',
        '/management/institution-membership-requests/owned',
      ),
      const _Request(
        'GET',
        '/management/institution-membership-requests/reviewable',
      ),
      const _Request(
        'GET',
        '/management/institution-membership-candidates',
        query: {
          'requestType': 'CONSULTANT',
          'action': 'LEAVE',
          'query': '',
          'offset': 40,
          'limit': 150,
        },
      ),
    ]);
  });

  test('legacy normalized adapters never decode revoked root rows', () async {
    final client = _RecordingApiClient();
    final repository = ApiIdentityRepository(client);

    final compatibility = await repository.listInstitutionMembershipRequests();
    final doctors = await repository.listDoctorInstitutionChangeRequests();

    expect(compatibility.single.requestType,
        InstitutionMembershipRequestType.doctor);
    expect(doctors.single.id, 'request-1');
    expect(client.requests, [
      const _Request(
        'GET',
        '/management/institution-membership-requests/owned',
      ),
      const _Request(
        'GET',
        '/management/institution-membership-requests/owned',
      ),
    ]);
  });

  test('doctor and consultant mutations share one normalized wire contract',
      () async {
    final client = _RecordingApiClient();
    final repository = ApiIdentityRepository(client);

    final doctorLeave = await repository.submitInstitutionMembershipRequest(
      const InstitutionMembershipRequestDraft(
        requestType: InstitutionMembershipRequestType.doctor,
        action: InstitutionMembershipAction.leave,
        institutionId: ' institution-1 ',
        requestNote: ' Please add me ',
      ),
    );
    final consultantLeave = await repository.submitInstitutionMembershipRequest(
      const InstitutionMembershipRequestDraft(
        requestType: InstitutionMembershipRequestType.consultant,
        action: InstitutionMembershipAction.leave,
        institutionId: ' institution-1 ',
        requestNote: ' Please add me ',
      ),
    );
    final doctorWithdrawal =
        await repository.withdrawInstitutionMembershipRequest(
      requestType: InstitutionMembershipRequestType.doctor,
      id: ' request-1 ',
    );
    final consultantWithdrawal =
        await repository.withdrawInstitutionMembershipRequest(
      requestType: InstitutionMembershipRequestType.consultant,
      id: ' request-1 ',
    );
    final review = await repository.reviewInstitutionMembershipRequest(
      requestType: InstitutionMembershipRequestType.consultant,
      id: ' request-1 ',
      decision: InstitutionMembershipDecision.rejected,
      reviewNote: ' Not this time ',
    );

    expect(doctorLeave.requestType, InstitutionMembershipRequestType.doctor);
    expect(doctorLeave.action, InstitutionMembershipAction.leave);
    expect(
      consultantLeave.requestType,
      InstitutionMembershipRequestType.consultant,
    );
    expect(consultantLeave.action, InstitutionMembershipAction.leave);
    expect(
      doctorWithdrawal.status,
      InstitutionMembershipRequestStatus.withdrawn,
    );
    expect(
      consultantWithdrawal.requestType,
      InstitutionMembershipRequestType.consultant,
    );
    expect(review.status, InstitutionMembershipRequestStatus.rejected);
    expect(client.requests, [
      const _Request(
        'POST',
        '/management/institution-membership-requests',
        body: {
          'requestType': 'DOCTOR',
          'action': 'LEAVE',
          'institutionId': 'institution-1',
          'requestNote': 'Please add me',
        },
      ),
      const _Request(
        'POST',
        '/management/institution-membership-requests',
        body: {
          'requestType': 'CONSULTANT',
          'action': 'LEAVE',
          'institutionId': 'institution-1',
          'requestNote': 'Please add me',
        },
      ),
      const _Request(
        'POST',
        '/management/institution-membership-requests/DOCTOR/request-1/withdraw',
      ),
      const _Request(
        'POST',
        '/management/institution-membership-requests/CONSULTANT/request-1/withdraw',
      ),
      const _Request(
        'POST',
        '/management/institution-membership-requests/CONSULTANT/request-1/review',
        body: {
          'decision': 'REJECTED',
          'reviewNote': 'Not this time',
        },
      ),
    ]);
  });

  test('every membership mutation rejects a success envelope without data',
      () async {
    final client = _RecordingApiClient()..hasMutationData = false;
    final repository = ApiIdentityRepository(client);

    await expectLater(
      repository.submitInstitutionMembershipRequest(
        const InstitutionMembershipRequestDraft(
          requestType: InstitutionMembershipRequestType.doctor,
          action: InstitutionMembershipAction.join,
          institutionId: 'institution-1',
        ),
      ),
      throwsFormatException,
    );
    await expectLater(
      repository.withdrawInstitutionMembershipRequest(
        requestType: InstitutionMembershipRequestType.doctor,
        id: 'request-1',
      ),
      throwsFormatException,
    );
    await expectLater(
      repository.reviewInstitutionMembershipRequest(
        requestType: InstitutionMembershipRequestType.doctor,
        id: 'request-1',
        decision: InstitutionMembershipDecision.approved,
        reviewNote: '',
      ),
      throwsFormatException,
    );
  });
}

final class _RecordingApiClient extends ApiClient {
  _RecordingApiClient() : super(apiRoot: Uri.parse('http://localhost/api/'));

  final List<_Request> requests = [];
  bool hasMutationData = true;

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    requests.add(_Request('GET', path, query: query));
    if (path == '/management/institution-membership-candidates') {
      return decodeData({
        'items': [
          {'id': 'institution-1', 'name': 'Joysong Clinic'},
        ],
        'offset': 40,
        'limit': 100,
        'hasMore': false,
      });
    }
    if (path == '/management/institution-membership-requests') {
      return decodeData([
        _membershipResponse(
          requestType: 'CONSULTANT',
          action: 'JOIN',
          status: 'REVOKED',
        ),
      ]);
    }
    return decodeData([
      _membershipResponse(
        requestType: path.endsWith('/reviewable') ? 'CONSULTANT' : 'DOCTOR',
        action: path.endsWith('/reviewable') ? 'LEAVE' : 'JOIN',
      ),
    ]);
  }

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    requests.add(_Request('POST', path, body: body));
    if (!hasMutationData) return null;
    final requestBody = body is Map ? body : const <Object?, Object?>{};
    final pathSegments = path.split('/');
    final requestType = path == '/management/institution-membership-requests'
        ? requestBody['requestType']!.toString()
        : pathSegments[pathSegments.length - 3];
    final action = requestBody['action']?.toString() ?? 'LEAVE';
    final status = path.endsWith('/withdraw')
        ? 'WITHDRAWN'
        : path.endsWith('/review')
            ? requestBody['decision']!.toString()
            : 'PENDING';
    return decodeData(_membershipResponse(
      requestType: requestType,
      action: action,
      status: status,
      reviewNote: requestBody['reviewNote']?.toString() ?? '',
    ));
  }
}

Map<String, Object?> _membershipResponse({
  required String requestType,
  required String action,
  String status = 'PENDING',
  String reviewNote = '',
}) =>
    {
      'id': 'request-1',
      'requestType': requestType,
      'applicantId': 'professional-1',
      'applicantName': 'Alex Chen',
      'institutionId': 'institution-1',
      'institutionName': 'Joysong Clinic',
      'action': action,
      'status': status,
      'relationshipStatus': action == 'LEAVE' ? 'APPROVED' : 'NONE',
      'requestNote': '',
      'reviewNote': reviewNote,
      'submittedBy': 'professional-user-1',
      'reviewedBy':
          status == 'APPROVED' || status == 'REJECTED' ? 'legal-user-1' : null,
      'submittedAt': '2026-08-10T09:00:00',
      'reviewedAt': status == 'APPROVED' || status == 'REJECTED'
          ? '2026-08-10T10:00:00'
          : null,
      'createdAt': '2026-08-10T09:00:00',
      'updatedAt': '2026-08-10T10:00:00',
    };

final class _Request {
  const _Request(
    this.method,
    this.path, {
    this.query = const {},
    this.body,
  });

  final String method;
  final String path;
  final Map<String, Object?> query;
  final Object? body;

  @override
  bool operator ==(Object other) =>
      other is _Request &&
      other.method == method &&
      other.path == path &&
      _deepEquals(other.query, query) &&
      _deepEquals(other.body, body);

  @override
  int get hashCode => Object.hash(method, path, query, body);

  @override
  String toString() => '$method $path query=$query body=$body';
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
