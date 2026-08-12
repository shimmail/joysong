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
      'multi-role entries submit their explicit membership request type',
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
    expect(identityRepository.submittedTypes, ['DOCTOR']);
    await tester.pageBack();
    await tester.pumpAndSettle();

    await _applyFromEntry(
      tester,
      entry: find.text('Apply to institution').last,
      expectedNote: 'consultant own request',
    );
    expect(identityRepository.submittedTypes, ['DOCTOR', 'CONSULTANT']);
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
  final submittedTypes = <String>[];
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
        InstitutionMembershipRequest.fromJson({
          'id': 'doctor-own',
          'requestType': 'DOCTOR',
          'userId': 'professional-1',
          'institutionId': 'inst-1',
          'status': 'PENDING',
          'requestNote': 'doctor own request',
        }),
        InstitutionMembershipRequest.fromJson({
          'id': 'consultant-own',
          'requestType': 'CONSULTANT',
          'userId': 'professional-1',
          'institutionId': 'inst-1',
          'status': 'PENDING',
          'requestNote': 'consultant own request',
        }),
        InstitutionMembershipRequest.fromJson({
          'id': 'other-role',
          'requestType': 'CONSULTANT',
          'userId': 'other-user',
          'institutionId': 'inst-1',
          'status': 'PENDING',
          'requestNote': 'other role request',
        }),
        InstitutionMembershipRequest.fromJson({
          'id': 'other-user',
          'requestType': 'DOCTOR',
          'userId': 'other-user',
          'institutionId': 'inst-1',
          'status': 'PENDING',
          'requestNote': 'other user request',
        }),
      ];

  @override
  Future<void> submitInstitutionMembershipRequest({
    required String requestType,
    required String institutionId,
    required String requestNote,
  }) async {
    submittedTypes.add(requestType);
  }

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
