import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/discover/presentation/institution_picker_page.dart';
import 'package:joysong_flutter/features/identity/data/identity_repository_impl.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/consultant_management_pages.dart';

void main() {
  test(
      'legacy consultant list preserves revoked history while submit is unified',
      () async {
    final client = _RecordingApiClient();
    final repository = ApiIdentityRepository(client);

    final memberships = await repository.listConsultantMemberships();
    final submitted = await repository.submitConsultantMembership(
      const ConsultantMembershipDraft(
        institutionId: ' institution-2 ',
        requestNote: ' 希望加入 ',
      ),
    );
    final projects = await repository.listManagementProjects();

    final approvedJoin = memberships.firstWhere(
      (membership) => membership.id == 'membership-1',
    );
    final revoked = memberships.firstWhere(
      (membership) => membership.id == 'membership-revoked',
    );
    expect(memberships, hasLength(2));
    expect(approvedJoin.institutionName, 'Joysong Clinic');
    expect(approvedJoin.confirmedBy, 'legal-1');
    expect(
      approvedJoin.confirmedAt,
      DateTime.parse('2026-08-12T10:05:00'),
    );
    expect(approvedJoin.revokedAt, isNull);
    expect(revoked.status, 'REVOKED');
    expect(revoked.confirmedAt, isNull);
    expect(
      revoked.revokedAt,
      DateTime.parse('2026-08-13T11:30:00'),
    );
    expect(submitted.institutionId, 'institution-2');
    expect(projects.single.categoryTags, 'skin,laser');
    expect(projects.single.referencePrice, 980.50);
    expect(projects.single.currency, 'CNY');
    for (final requiredKey in ['id', 'name', 'currency']) {
      expect(
        () => ManagementProjectOption.fromJson(
          Map<String, Object?>.from(_projectJson)..remove(requiredKey),
        ),
        throwsFormatException,
      );
    }
    expect(client.requests, [
      const _Request(
        'GET',
        '/management/consultant-memberships',
      ),
      const _Request(
        'POST',
        '/management/institution-membership-requests',
        body: {
          'requestType': 'CONSULTANT',
          'action': 'JOIN',
          'institutionId': 'institution-2',
          'requestNote': '希望加入',
        },
      ),
      const _Request('GET', '/management/projects'),
    ]);
  });

  testWidgets('consultant page picks an institution and submits a fixed JOIN',
      (tester) async {
    tester.view.physicalSize = const Size(900, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repository = _FakeIdentityRepository();

    await tester.pumpWidget(MaterialApp(
      home: ConsultantMembershipPage(
        repository: repository,
        discoverRepository: _FakeDiscoverRepository(),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Joysong Clinic'), findsOneWidget);
    expect(find.textContaining('Approved'), findsOneWidget);
    expect(find.textContaining('Welcome'), findsOneWidget);
    expect(find.textContaining('legal-1'), findsOneWidget);
    await tester.tap(find.byKey(const Key('consultant-institution-picker')));
    await tester.pumpAndSettle();
    final picker = tester.widget<InstitutionPickerPage>(
      find.byType(InstitutionPickerPage),
    );
    expect(picker.role, IdentityRoleType.consultant);
    await tester.tap(find.text('New Clinic'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('consultant-request-note')),
      'Please review',
    );
    await tester.tap(find.byKey(const Key('consultant-submit')));
    await tester.pumpAndSettle();

    expect(repository.submitted.single.institutionId, 'institution-2');
    expect(repository.submitted.single.requestNote, 'Please review');
    expect(repository.listCalls, 2);
  });

  testWidgets('professional project catalog is read-only and renders metadata',
      (tester) async {
    final repository = _FakeIdentityRepository();

    await tester.pumpWidget(MaterialApp(
      home: ConsultantProjectCatalogPage(repository: repository),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Skin Renewal'), findsOneWidget);
    expect(find.textContaining('980.50 CNY'), findsOneWidget);
    expect(find.textContaining('skin'), findsOneWidget);
    expect(find.byType(FloatingActionButton), findsNothing);
  });
}

const _membershipJson = <String, Object?>{
  'id': 'membership-1',
  'institutionId': 'institution-1',
  'institutionName': 'Joysong Clinic',
  'status': 'APPROVED',
  'requestNote': 'Experienced consultant',
  'reviewNote': 'Welcome',
  'createdAt': '2026-08-12T10:00:00',
  'updatedAt': '2026-08-12T10:05:00',
  'confirmedBy': 'legal-1',
  'confirmedAt': '2026-08-12T10:05:00',
  'revokedAt': null,
};

const _revokedMembershipJson = <String, Object?>{
  'id': 'membership-revoked',
  'institutionId': 'institution-legacy',
  'institutionName': 'Legacy Clinic',
  'status': 'REVOKED',
  'requestNote': 'Historical membership',
  'reviewNote': 'Membership ended',
  'createdAt': '2026-08-01T09:00:00',
  'updatedAt': '2026-08-13T11:30:00',
  'confirmedBy': 'legal-legacy',
  'confirmedAt': null,
  'revokedAt': '2026-08-13T11:30:00',
};

const _normalizedMembershipJson = <String, Object?>{
  'id': 'membership-1',
  'requestType': 'CONSULTANT',
  'applicantId': 'consultant-1',
  'applicantName': 'Alex Chen',
  'institutionId': 'institution-1',
  'institutionName': 'Joysong Clinic',
  'action': 'JOIN',
  'status': 'APPROVED',
  'relationshipStatus': 'APPROVED',
  'requestNote': 'Experienced consultant',
  'reviewNote': 'Welcome',
  'submittedBy': 'consultant-1',
  'reviewedBy': 'legal-1',
  'submittedAt': '2026-08-12T10:00:00',
  'reviewedAt': '2026-08-12T10:05:00',
  'createdAt': '2026-08-12T10:00:00',
  'updatedAt': '2026-08-12T10:05:00',
};

const _projectJson = <String, Object?>{
  'id': 'project-1',
  'name': 'Skin Renewal',
  'category': 'Skin',
  'description': 'Gentle renewal treatment',
  'tags': 'renewal,care',
  'categoryTags': 'skin,laser',
  'coverImage': '',
  'referencePrice': 980.50,
  'currency': 'CNY',
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
    if (path.endsWith('/projects')) return decodeData([_projectJson]);
    if (path == '/management/consultant-memberships') {
      return decodeData([_membershipJson, _revokedMembershipJson]);
    }
    throw StateError('unexpected GET $path');
  }

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    requests.add(_Request('POST', path, body: body));
    return decodeData({
      ..._normalizedMembershipJson,
      'id': 'membership-2',
      'institutionId': 'institution-2',
      'institutionName': 'New Clinic',
      'status': 'PENDING',
      'relationshipStatus': 'NONE',
      'requestNote': '希望加入',
      'reviewNote': '',
      'reviewedBy': null,
      'reviewedAt': null,
    });
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
      method == other.method &&
      path == other.path &&
      _deepEquals(body, other.body);

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

final class _FakeIdentityRepository implements IdentityRepository {
  final submitted = <ConsultantMembershipDraft>[];
  var listCalls = 0;

  @override
  Future<List<ConsultantMembership>> listConsultantMemberships() async {
    listCalls += 1;
    return [ConsultantMembership.fromJson(_membershipJson)];
  }

  @override
  Future<ConsultantMembership> submitConsultantMembership(
    ConsultantMembershipDraft draft,
  ) async {
    submitted.add(draft);
    return ConsultantMembership.fromJson({
      ..._membershipJson,
      'institutionId': draft.institutionId.trim(),
      'institutionName': 'New Clinic',
      'status': 'PENDING',
      'requestNote': draft.requestNote.trim(),
      'reviewNote': '',
      'confirmedBy': null,
      'confirmedAt': null,
    });
  }

  @override
  Future<List<ManagementProjectOption>> listManagementProjects() async =>
      [ManagementProjectOption.fromJson(_projectJson)];

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _FakeDiscoverRepository implements DiscoverRepository {
  @override
  Future<DiscoverPageResult> loadPage({
    required DiscoverContentType type,
    required int offset,
    required int limit,
    String query = '',
    List<String> categories = const [],
    List<String> cities = const [],
    List<String> tags = const [],
  }) async =>
      const DiscoverPageResult(
        items: [
          DiscoverItem(
            id: 'institution-2',
            type: DiscoverContentType.institution,
            title: 'New Clinic',
          ),
        ],
        hasMore: false,
      );

  @override
  Future<DiscoverItem> loadDetail({
    required DiscoverContentType type,
    required String id,
  }) =>
      throw UnimplementedError();

  @override
  Future<DiscoverFilterOptions> loadFilterOptions() async =>
      const DiscoverFilterOptions();
}
