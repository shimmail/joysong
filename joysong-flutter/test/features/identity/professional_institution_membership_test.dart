import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/institution_relationships_page.dart';
import 'package:joysong_flutter/features/identity/presentation/professional_request_pages.dart';

void main() {
  final cases = [
    const (
      scope: InstitutionRelationshipScope.doctor,
      type: InstitutionMembershipRequestType.doctor,
      candidateId: 'doctor-candidate',
      candidateName: 'Doctor Candidate Clinic',
    ),
    const (
      scope: InstitutionRelationshipScope.consultant,
      type: InstitutionMembershipRequestType.consultant,
      candidateId: 'consultant-candidate',
      candidateName: 'Consultant Candidate Clinic',
    ),
  ];

  for (final testCase in cases) {
    testWidgets(
      'dual identity page obeys explicit ${testCase.type.code} scope for owned candidates and submit',
      (tester) async {
        final repository = _ExplicitScopeRepository(
          candidateId: testCase.candidateId,
          candidateName: testCase.candidateName,
        );
        await _mount(
          tester,
          InstitutionRelationshipsPage(
            repository: repository,
            scope: testCase.scope,
          ),
        );

        expect(find.text('${testCase.type.code} owned row'), findsOneWidget);
        expect(
          find.text(
            testCase.type == InstitutionMembershipRequestType.doctor
                ? 'CONSULTANT owned row'
                : 'DOCTOR owned row',
          ),
          findsNothing,
        );
        expect(repository.candidateTypes, [testCase.type]);

        await tester.tap(
          find.byKey(const Key('relationship-institution-picker')),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.text(testCase.candidateName));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('relationship-submit')));
        await tester.pumpAndSettle();

        expect(repository.submitted.single.requestType, testCase.type);
        expect(repository.submitted.single.action,
            InstitutionMembershipAction.join);
        expect(repository.submitted.single.institutionId, testCase.candidateId);
        expect(
          repository.candidateTypes.every((type) => type == testCase.type),
          isTrue,
        );
      },
    );
  }

  testWidgets('legacy membership symbol is only a scoped unified-page wrapper',
      (tester) async {
    final repository = _ExplicitScopeRepository(
      candidateId: 'candidate',
      candidateName: 'Candidate Clinic',
    );
    final legacyContext = const ManagementContext(
      userId: 'legacy-context-user',
      platformRole: 'USER',
      activeRoles: ['DOCTOR'],
      managedInstitutionIds: ['legacy-managed'],
      visibleInstitutionIds: ['legacy-managed'],
    );

    await _mount(
      tester,
      InstitutionMembershipRequestsPage(
        repository: repository,
        discoverRepository: const _UnusedDiscoverRepository(),
        context: legacyContext,
        requestType: 'CONSULTANT',
      ),
    );
    expect(find.byType(InstitutionRelationshipsPage), findsOneWidget);
    expect(
      tester
          .widget<InstitutionRelationshipsPage>(
            find.byType(InstitutionRelationshipsPage),
          )
          .scope,
      InstitutionRelationshipScope.consultant,
    );

    await _mount(
      tester,
      InstitutionMembershipRequestsPage(
        key: const ValueKey('legal-wrapper'),
        repository: repository,
        discoverRepository: const _UnusedDiscoverRepository(),
        context: legacyContext,
        requestType: 'DOCTOR',
        reviewMode: true,
      ),
    );
    expect(
      tester
          .widget<InstitutionRelationshipsPage>(
            find.byType(InstitutionRelationshipsPage),
          )
          .scope,
      InstitutionRelationshipScope.legalRepresentative,
    );
  });
}

Future<void> _mount(WidgetTester tester, Widget child) async {
  tester.view.physicalSize = const Size(900, 4000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(locale: const Locale('en'), home: child));
  await tester.pumpAndSettle();
}

InstitutionMembershipRequest _request({
  required String id,
  required InstitutionMembershipRequestType type,
  required String note,
}) =>
    InstitutionMembershipRequest(
      id: id,
      requestType: type,
      applicantId: '${type.code.toLowerCase()}-profile-id',
      applicantName: '${type.code} Applicant',
      institutionId: '${type.code.toLowerCase()}-pending-id',
      institutionName: '${type.code} Pending Clinic',
      action: InstitutionMembershipAction.join,
      status: InstitutionMembershipRequestStatus.pending,
      relationshipStatus: InstitutionRelationshipStatus.none,
      requestNote: note,
      reviewNote: '',
      submittedBy: '${type.code.toLowerCase()}-profile-id',
      submittedAt: DateTime.utc(2026, 8, 14, 9),
      createdAt: DateTime.utc(2026, 8, 14, 9),
      updatedAt: DateTime.utc(2026, 8, 14, 9),
    );

final class _ExplicitScopeRepository implements IdentityRepository {
  _ExplicitScopeRepository({
    required this.candidateId,
    required this.candidateName,
  });

  final String candidateId;
  final String candidateName;
  final candidateTypes = <InstitutionMembershipRequestType>[];
  final submitted = <InstitutionMembershipRequestDraft>[];

  @override
  Future<ManagementContext> loadManagementContext() async =>
      const ManagementContext(
        userId: 'dual-session-user',
        platformRole: 'USER',
        activeRoles: ['CONSULTANT', 'DOCTOR'],
        doctorId: 'doctor-profile-id',
        managedInstitutionIds: [],
        visibleInstitutionIds: [],
        canApplyToInstitutions: true,
      );

  @override
  Future<List<InstitutionMembershipRequest>>
      listOwnedInstitutionMembershipRequests() async => [
            _request(
              id: 'doctor-owned',
              type: InstitutionMembershipRequestType.doctor,
              note: 'DOCTOR owned row',
            ),
            _request(
              id: 'consultant-owned',
              type: InstitutionMembershipRequestType.consultant,
              note: 'CONSULTANT owned row',
            ),
          ];

  @override
  Future<InstitutionMembershipCandidatePage>
      listInstitutionMembershipCandidates({
    required InstitutionMembershipRequestType requestType,
    required InstitutionMembershipAction action,
    required String query,
    required int offset,
    required int limit,
  }) async {
    candidateTypes.add(requestType);
    return InstitutionMembershipCandidatePage(
      items: [
        InstitutionMembershipCandidate(id: candidateId, name: candidateName),
      ],
      offset: offset,
      limit: limit,
      hasMore: false,
    );
  }

  @override
  Future<InstitutionMembershipRequest> submitInstitutionMembershipRequest(
    InstitutionMembershipRequestDraft draft,
  ) async {
    submitted.add(draft);
    return _request(
      id: 'submitted',
      type: draft.requestType,
      note: 'submitted',
    );
  }

  @override
  Future<List<InstitutionMembershipRequest>>
      listReviewableInstitutionMembershipRequests() async => const [];

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

final class _UnusedDiscoverRepository implements DiscoverRepository {
  const _UnusedDiscoverRepository();

  @override
  Future<DiscoverFilterOptions> loadFilterOptions() =>
      throw StateError('legacy discover dependency must not be used');

  @override
  Future<DiscoverPageResult> loadPage({
    required DiscoverContentType type,
    required int offset,
    required int limit,
    String query = '',
    List<String> categories = const [],
    List<String> cities = const [],
    List<String> tags = const [],
  }) =>
      throw StateError('legacy discover dependency must not be used');

  @override
  Future<DiscoverItem> loadDetail({
    required DiscoverContentType type,
    required String id,
  }) =>
      throw StateError('legacy discover dependency must not be used');
}
