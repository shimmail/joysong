import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';
import 'package:joysong_flutter/features/identity/presentation/professional_request_pages.dart';

void main() {
  testWidgets(
      'doctor entry submits its explicit membership request type and legal review stays mixed',
      (tester) async {
    tester.view.physicalSize = const Size(900, 4000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final identityRepository = _FakeIdentityRepository();
    final discoverRepository = _FakeDiscoverRepository();

    await tester.pumpWidget(MaterialApp(
      home: ManagementCenterPage(
        repository: identityRepository,
        discoverRepository: discoverRepository,
      ),
    ));
    await tester.pumpAndSettle();
    await _applyFromEntry(
      tester,
      entry: find.text('Apply to institution').first,
      expectedNote: 'doctor own request',
    );
    expect(
      identityRepository.submittedTypes,
      [InstitutionMembershipRequestType.doctor],
    );
    expect(identityRepository.optionCalls, 0);

    await tester.pumpWidget(MaterialApp(
      key: const ValueKey('mixed-membership-review-app'),
      home: InstitutionMembershipRequestsPage(
        key: const ValueKey('mixed-membership-review'),
        requestType: 'DOCTOR',
        reviewMode: true,
        context: const ManagementContext(
          userId: 'legal-user',
          platformRole: 'USER',
          activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
          managedInstitutionIds: ['inst-1'],
          visibleInstitutionIds: ['inst-1'],
        ),
        repository: identityRepository,
        discoverRepository: discoverRepository,
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.textContaining('doctor own request'), findsOneWidget);
    expect(find.textContaining('consultant own request'), findsOneWidget);
  });
}

Future<void> _applyFromEntry(
  WidgetTester tester, {
  required Finder entry,
  required String expectedNote,
}) async {
  await tester.tap(entry);
  await tester.pumpAndSettle();
  expect(find.textContaining(expectedNote), findsOneWidget);
  expect(find.textContaining('other role request'), findsNothing);
  expect(find.textContaining('other user request'), findsNothing);
  await tester.tap(find.byKey(const Key('membership-institution-picker')));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Joysong Clinic'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Submit'));
  await tester.pumpAndSettle();
}

final class _FakeIdentityRepository implements IdentityRepository {
  final submittedTypes = <InstitutionMembershipRequestType>[];
  var optionCalls = 0;

  @override
  Future<ManagementContext> loadManagementContext() async =>
      const ManagementContext(
        userId: 'professional-1',
        platformRole: 'USER',
        activeRoles: ['DOCTOR', 'CONSULTANT'],
        managedInstitutionIds: [],
        visibleInstitutionIds: [],
        canApplyToInstitutions: true,
      );

  @override
  Future<List<InstitutionOption>> listInstitutionOptions() async {
    optionCalls += 1;
    throw StateError('legacy institution options must not be loaded');
  }

  @override
  Future<List<InstitutionMembershipRequest>>
      listInstitutionMembershipRequests() async => [
            _membershipRequest(
              id: 'doctor-own',
              requestType: InstitutionMembershipRequestType.doctor,
              applicantId: 'professional-1',
              requestNote: 'doctor own request',
            ),
            _membershipRequest(
              id: 'consultant-own',
              requestType: InstitutionMembershipRequestType.consultant,
              applicantId: 'professional-1',
              requestNote: 'consultant own request',
            ),
            _membershipRequest(
              id: 'other-role',
              requestType: InstitutionMembershipRequestType.consultant,
              applicantId: 'other-user',
              requestNote: 'other role request',
            ),
            _membershipRequest(
              id: 'other-user',
              requestType: InstitutionMembershipRequestType.doctor,
              applicantId: 'other-user',
              requestNote: 'other user request',
            ),
          ];

  @override
  Future<List<InstitutionMembershipRequest>>
      listOwnedInstitutionMembershipRequests() =>
          listInstitutionMembershipRequests();

  @override
  Future<List<InstitutionMembershipRequest>>
      listReviewableInstitutionMembershipRequests() =>
          listInstitutionMembershipRequests();

  @override
  Future<InstitutionMembershipRequest> submitInstitutionMembershipRequest(
    InstitutionMembershipRequestDraft draft,
  ) async {
    submittedTypes.add(draft.requestType);
    return _membershipRequest(
      id: 'submitted-request',
      requestType: draft.requestType,
      applicantId: 'professional-1',
      requestNote: draft.requestNote.trim(),
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

InstitutionMembershipRequest _membershipRequest({
  required String id,
  required InstitutionMembershipRequestType requestType,
  required String applicantId,
  required String requestNote,
}) =>
    InstitutionMembershipRequest.fromJson({
      'id': id,
      'requestType': requestType.code,
      'applicantId': applicantId,
      'applicantName': 'Alex Chen',
      'institutionId': 'inst-1',
      'institutionName': 'Joysong Clinic',
      'action': 'JOIN',
      'status': 'PENDING',
      'relationshipStatus': 'NONE',
      'requestNote': requestNote,
      'reviewNote': '',
      'submittedBy': applicantId,
      'reviewedBy': null,
      'submittedAt': '2026-08-10T09:00:00',
      'reviewedAt': null,
      'createdAt': '2026-08-10T09:00:00',
      'updatedAt': '2026-08-10T09:00:00',
    });

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
            id: 'inst-1',
            type: DiscoverContentType.institution,
            title: 'Joysong Clinic',
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
