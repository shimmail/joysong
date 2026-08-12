import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';
import 'package:joysong_flutter/features/identity/presentation/institution_relationships_page.dart';
import 'package:joysong_flutter/features/profile/presentation/profile_page.dart';

void main() {
  testWidgets('doctor submits a leave relationship request', (tester) async {
    final repository = _FakeIdentityRepository()
      ..context = const ManagementContext(
        userId: 'doctor-user',
        platformRole: 'USER',
        activeRoles: ['DOCTOR'],
        doctorId: 'doctor-1',
        managedInstitutionIds: [],
        visibleInstitutionIds: [],
        doctorInstitutionIds: ['inst-1'],
        canApplyToInstitutions: true,
      );

    await tester.pumpWidget(MaterialApp(
      home: InstitutionRelationshipsPage(
        repository: repository,
        discoverRepository: const _FakeDiscoverRepository(),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Leave institution'));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(DropdownButtonFormField<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Joysong Clinic').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Submit request'));
    await tester.pumpAndSettle();

    expect(repository.submittedDraft?.action, 'LEAVE');
    expect(repository.submittedDraft?.institutionId, 'inst-1');
  });

  testWidgets('legal representative rejects with a required reason',
      (tester) async {
    final repository = _FakeIdentityRepository()
      ..context = const ManagementContext(
        userId: 'legal-user',
        platformRole: 'USER',
        activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
        managedInstitutionIds: ['inst-1'],
        visibleInstitutionIds: ['inst-1'],
        canReviewInstitutionRequests: true,
      )
      ..requests = [
        DoctorInstitutionChangeRequest.fromJson({
          'id': 'request-1',
          'requestType': 'DOCTOR',
          'userId': 'doctor-user',
          'institutionId': 'inst-1',
          'status': 'PENDING',
          'action': 'JOIN',
          'doctorName': 'Dr. Lin',
          'institutionName': 'Joysong Clinic',
        }),
      ];

    await tester.pumpWidget(MaterialApp(
      home: InstitutionRelationshipsPage(
        repository: repository,
        discoverRepository: const _FakeDiscoverRepository(),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('审核'));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(DropdownButtonFormField<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('驳回'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认'));
    await tester.pumpAndSettle();

    expect(repository.reviewNote, isNull);
    await tester.enterText(find.byType(TextField), '材料不完整');
    await tester.tap(find.text('确认'));
    await tester.pumpAndSettle();
    expect(repository.reviewNote, '材料不完整');
    expect(repository.reviewDecision, 'REJECTED');
  });

  testWidgets(
      'doctor without apply capability sees history without submit form',
      (tester) async {
    final repository = _FakeIdentityRepository()
      ..context = const ManagementContext(
        userId: 'doctor-user',
        platformRole: 'USER',
        activeRoles: ['DOCTOR'],
        doctorId: 'doctor-1',
        managedInstitutionIds: [],
        visibleInstitutionIds: [],
        doctorInstitutionIds: ['inst-1'],
        canApplyToInstitutions: false,
      );

    await tester.pumpWidget(MaterialApp(
      home: InstitutionRelationshipsPage(
        repository: repository,
        discoverRepository: const _FakeDiscoverRepository(),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('当前机构'), findsOneWidget);
    expect(find.text('Joysong Clinic'), findsOneWidget);
    expect(find.text('提交申请'), findsNothing);
    expect(find.text('加入机构'), findsNothing);
    expect(find.text('离开机构'), findsNothing);
  });

  testWidgets('consultant sees institution affiliation without doctor actions',
      (tester) async {
    final repository = _FakeIdentityRepository()
      ..context = const ManagementContext(
        userId: 'consultant-user',
        platformRole: 'USER',
        activeRoles: ['CONSULTANT'],
        managedInstitutionIds: [],
        visibleInstitutionIds: [],
        canApplyToInstitutions: true,
        canViewAffiliations: true,
      )
      ..membershipRequests = [
        InstitutionMembershipRequest.fromJson({
          'id': 'membership-1',
          'requestType': 'CONSULTANT',
          'userId': 'consultant-user',
          'institutionId': 'inst-1',
          'status': 'APPROVED',
          'requestNote': '希望加入',
        }),
      ];

    await tester.pumpWidget(MaterialApp(
      home: InstitutionRelationshipsPage(
        repository: repository,
        discoverRepository: const _FakeDiscoverRepository(),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('机构归属'), findsOneWidget);
    expect(find.text('Joysong Clinic'), findsOneWidget);
    expect(find.text('提交申请'), findsNothing);
    expect(find.text('加入机构'), findsNothing);
    expect(find.text('离开机构'), findsNothing);
  });

  testWidgets(
      'profile keeps identity verification without discover repository',
      (tester) async {
    tester.view.physicalSize = const Size(900, 4000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repository = _FakeIdentityRepository()
      ..context = const ManagementContext(
        userId: 'doctor-user',
        platformRole: 'USER',
        activeRoles: ['DOCTOR'],
        managedInstitutionIds: [],
        visibleInstitutionIds: [],
      );

    await tester.pumpWidget(MaterialApp(
      locale: const Locale('zh'),
      home: ProfilePage(
        identityRepository: repository,
      ),
    ));
    await tester.pumpAndSettle();

    final identityEntry = find.textContaining(RegExp('身份认证|Identity verification'));
    expect(identityEntry, findsOneWidget);
    await tester.tap(identityEntry);
    await tester.pumpAndSettle();
    expect(find.byType(IdentityCenterPage), findsOneWidget);
  });
}

final class _FakeDiscoverRepository implements DiscoverRepository {
  const _FakeDiscoverRepository();

  @override
  Future<DiscoverFilterOptions> loadFilterOptions() async =>
      const DiscoverFilterOptions();

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
      const DiscoverPageResult(items: [], hasMore: false);

  @override
  Future<DiscoverItem> loadDetail({
    required DiscoverContentType type,
    required String id,
  }) =>
      throw UnimplementedError();
}

final class _FakeIdentityRepository implements IdentityRepository {
  ManagementContext? context;
  List<DoctorInstitutionChangeRequest> requests = const [];
  List<InstitutionMembershipRequest> membershipRequests = const [];
  DoctorInstitutionChangeRequestDraft? submittedDraft;
  String? reviewDecision;
  String? reviewNote;

  @override
  Future<ManagementContext> loadManagementContext() async => context!;

  @override
  Future<List<InstitutionOption>> listInstitutionOptions() async => const [
        InstitutionOption(id: 'inst-1', name: 'Joysong Clinic'),
      ];

  @override
  Future<List<DoctorInstitutionChangeRequest>>
      listDoctorInstitutionChangeRequests() async => requests;

  @override
  Future<List<InstitutionMembershipRequest>>
      listInstitutionMembershipRequests() async => membershipRequests;

  @override
  Future<void> submitDoctorInstitutionChangeRequest(
    DoctorInstitutionChangeRequestDraft draft,
  ) async {
    submittedDraft = draft;
  }

  @override
  Future<void> reviewDoctorInstitutionChangeRequest({
    required String id,
    required String decision,
    required String reviewNote,
  }) async {
    reviewDecision = decision;
    this.reviewNote = reviewNote;
  }

  @override
  Future<void> withdrawDoctorInstitutionChangeRequest(String id) async {}

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
