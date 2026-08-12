import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_controller.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';
import 'package:joysong_flutter/features/identity/presentation/professional_request_pages.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';

void main() {
  test('uses medical aesthetics consultant product labels', () {
    expect(IdentityRoleType.fromCode('CONSULTANT').label, '医美顾问');
    expect(IdentityDocumentType.consultantProof.label, '医美顾问证明');
  });

  test('management capabilities fail closed when booleans are missing', () {
    final context = ManagementContext.fromJson({
      'userId': 'user-1',
      'platformRole': 'USER',
      'activeRoles': ['DOCTOR'],
      'managedInstitutionIds': const [],
      'visibleInstitutionIds': const [],
    });

    expect(context.canManageDoctors, isFalse);
    expect(context.canManageOrders, isFalse);
    expect(context.hasAnyCapability, isFalse);
  });

  test('private identity file rejects mismatched content signatures', () {
    final file = IdentityFileDraft(
      bytes: Uint8List.fromList(List.filled(20, 0)),
      fileName: 'identity.png',
      contentType: 'image/png',
      purpose: IdentityDocumentType.idCardFront,
    );

    expect(file.validate, throwsArgumentError);
  });

  test('institution profile images have a public media purpose', () {
    expect(PublicMediaPurpose.institutionProfile.name, 'institutionProfile');
  });

  test('doctor institution change request parses audit fields and action', () {
    final request = DoctorInstitutionChangeRequest.fromJson({
      'id': 'request-1',
      'requestType': 'DOCTOR',
      'userId': 'doctor-user-1',
      'institutionId': 'institution-1',
      'status': 'PENDING',
      'action': 'LEAVE',
      'doctorName': 'Dr. Lin',
      'institutionName': 'Joysong Clinic',
      'requestNote': 'Moving practices',
      'reviewNote': 'Acknowledged',
      'createdAt': '2026-08-10T09:00:00Z',
      'updatedAt': '2026-08-10T10:00:00Z',
      'submittedAt': '2026-08-10T09:00:00Z',
      'reviewedAt': '2026-08-10T10:00:00Z',
      'deleted': false,
    });

    expect(request.action, 'LEAVE');
    expect(request.doctorName, 'Dr. Lin');
    expect(request.institutionName, 'Joysong Clinic');
    expect(request.reviewedAt, '2026-08-10T10:00:00Z');
    expect(
      const DoctorInstitutionChangeRequestDraft(
        institutionId: 'institution-1',
        action: 'JOIN',
        requestNote: 'Please add me',
      ).toJson(),
      {
        'requestType': 'DOCTOR',
        'institutionId': 'institution-1',
        'action': 'JOIN',
        'requestNote': 'Please add me',
      },
    );
  });

  test('management context is fetched again on every entry', () async {
    final repository = _FakeIdentityRepository();
    final controller = ManagementController(repository);

    await controller.enter();
    expect(controller.status, ManagementLoadStatus.ready);
    expect(repository.contextCalls, 1);

    repository.allowManagement = false;
    await controller.enter();
    expect(controller.status, ManagementLoadStatus.denied);
    expect(controller.context, isNull);
    expect(repository.contextCalls, 2);
  });

  test('institution project exposes joined doctors from admin response', () {
    final project = ManagedInstitutionProject.fromJson({
      'id': 'institution-project-1',
      'institutionId': 'inst-1',
      'projectId': 'project-1',
      'effectiveName': '水光护理',
      'doctors': [
        {'id': 'doctor-1', 'name': '已加入医生', 'price': 399},
      ],
    });

    expect(project.doctorIds, {'doctor-1'});
    expect(project.hasDoctor('doctor-1'), isTrue);
    expect(project.hasDoctor('doctor-2'), isFalse);
  });

  test('join institution project draft uses exact backend contract', () {
    const draft = InstitutionProjectJoinRequestDraft(
      institutionProjectId: ' institution-project-1 ',
      serviceDescription: ' 擅长面部年轻化 ',
      priceSuggestion: 699,
      notes: ' 周末可约 ',
    );

    expect(draft.toJson(), {
      'requestType': 'JOIN',
      'institutionProjectId': 'institution-project-1',
      'serviceDescription': '擅长面部年轻化',
      'priceSuggestion': 699,
      'notes': '周末可约',
    });
    expect(draft.validate, returnsNormally);
    expect(
      () => const InstitutionProjectJoinRequestDraft(
        institutionProjectId: 'institution-project-1',
        serviceDescription: '服务说明',
        priceSuggestion: -1,
      ).validate(),
      throwsArgumentError,
    );
  });

  test('join request parses review queue fields', () {
    final request = InstitutionProjectJoinRequest.fromJson({
      'id': 'request-1',
      'doctorId': 'doctor-1',
      'doctorName': '测试医生',
      'institutionId': 'inst-1',
      'institutionName': '悦美医疗美容',
      'institutionProjectId': 'institution-project-1',
      'projectName': '水光护理',
      'requestType': 'JOIN',
      'serviceDescription': '面部年轻化服务',
      'priceSuggestion': 699,
      'notes': '周末可约',
      'status': 'PENDING',
      'reviewNote': '',
    });

    expect(request.requestType, 'JOIN');
    expect(request.projectName, '水光护理');
    expect(request.priceSuggestion, 699);
  });

  test('institution profile controller loads and saves managed institution',
      () async {
    final repository = _FakeIdentityRepository();
    final controller = InstitutionProfileController(repository);

    await controller.load();
    expect(controller.status, InstitutionProfileLoadStatus.ready);
    expect(controller.summaries.single.id, 'inst-1');

    final selected = await controller.select(controller.summaries.single.id);
    expect(selected, isTrue);
    expect(controller.selectedProfile?.id, 'inst-1');

    final saved = await controller.save(
      controller.selectedProfile!.toUpdate().copyWith(city: '宁波市'),
    );

    expect(saved, isTrue);
    expect(repository.savedUpdates.single.toJson()['city'], '宁波');
    expect(controller.selectedProfile?.description, '服务端保存结果');
  });

  test('doctor project management uses professional-visible institutions',
      () async {
    final repository = _FakeIdentityRepository()
      ..rejectLegalRepresentativeInstitutionList = true;
    final controller = InstitutionProjectManagementController(
      repository,
      context: const ManagementContext(
        userId: 'doctor-user-1',
        platformRole: 'USER',
        activeRoles: ['DOCTOR'],
        doctorId: 'doctor-1',
        managedInstitutionIds: [],
        visibleInstitutionIds: ['inst-1'],
      ),
    );

    await controller.load();

    expect(controller.status, InstitutionProjectLoadStatus.ready);
    expect(controller.institutions.single.id, 'inst-1');
  });

  testWidgets('institution profile edit uses public info and album image copy',
      (tester) async {
    tester.view.physicalSize = const Size(800, 2200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repository = _FakeIdentityRepository();
    final pickedImages = [
      'https://cdn.example.com/institutions/cover.jpg',
      'https://cdn.example.com/institutions/license.png',
    ];

    await tester.pumpWidget(
      _localizedApp(
        home: ManagedInstitutionProfilesPage(
          repository: repository,
          imagePicker: () async => pickedImages.removeAt(0),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('悦美医疗美容'));
    await tester.pumpAndSettle();

    expect(find.text('这是向用户公开展示的信息，请勿填写内部管理或隐私资料。'), findsOneWidget);
    expect(find.text('评分、评价数与认证状态由平台管理，此处仅维护公开机构档案。'), findsNothing);
    expect(find.text('封面图 URL'), findsNothing);
    expect(find.text('资质证书图片 URL（逗号分隔）'), findsNothing);
    expect(find.text('环境图片 URL（逗号分隔）'), findsNothing);
    expect(find.text('从相册选择封面图'), findsOneWidget);
    expect(find.text('从相册添加资质图片'), findsOneWidget);
    expect(find.text('从相册添加环境图片'), findsOneWidget);

    await tester.tap(find.text('从相册选择封面图'));
    await tester.pump();
    await tester.tap(find.text('从相册添加资质图片'));
    await tester.pump();

    expect(
      find.text('https://cdn.example.com/institutions/cover.jpg'),
      findsOneWidget,
    );
    expect(
      find.text('https://cdn.example.com/institutions/license.png'),
      findsOneWidget,
    );
  });

  testWidgets('legal representative cannot publish institution projects',
      (tester) async {
    final repository = _FakeIdentityRepository()
      ..managementContext = const ManagementContext(
        userId: 'user-legal',
        platformRole: 'USER',
        activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
        managedInstitutionIds: ['inst-1'],
        visibleInstitutionIds: ['inst-1'],
        canManageInstitutionProjects: true,
      );

    await tester.pumpWidget(
      _localizedApp(
        home: ManagedInstitutionProjectsPage(
          repository: repository,
          context: repository.managementContext!,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('发布'), findsNothing);
    expect(find.byIcon(Icons.add_rounded), findsNothing);
  });

  testWidgets(
      'membership reviews offer approve and reject while project reviews retain request changes',
      (tester) async {
    final repository = _FakeIdentityRepository()
      ..membershipRequests = [
        InstitutionMembershipRequest.fromJson({
          'id': 'membership-1',
          'requestType': 'CONSULTANT',
          'userId': 'consultant-1',
          'institutionId': 'inst-1',
          'status': 'PENDING',
        }),
      ];
    const context = ManagementContext(
      userId: 'legal-1',
      platformRole: 'USER',
      activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
      managedInstitutionIds: ['inst-1'],
      visibleInstitutionIds: ['inst-1'],
    );

    await tester.pumpWidget(_localizedApp(
      home: InstitutionMembershipRequestsPage(
        repository: repository,
        discoverRepository: const _FakeDiscoverRepository(),
        context: context,
        requestType: 'DOCTOR',
        reviewMode: true,
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('审核'));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(DropdownButtonFormField<String>));
    await tester.pumpAndSettle();
    expect(find.text('通过'), findsWidgets);
    expect(find.text('驳回'), findsOneWidget);
    expect(find.text('要求修改'), findsNothing);
    await tester.tap(find.text('通过').last);
    await tester.pumpAndSettle();

    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    await tester.pumpWidget(_localizedApp(
      home: Builder(
        builder: (context) => TextButton(
          onPressed: () => showProfessionalProjectReviewDialog(context),
          child: const Text('open project review'),
        ),
      ),
    ));
    await tester.tap(find.text('open project review'));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(DropdownButtonFormField<String>));
    await tester.pumpAndSettle();
    expect(find.text('要求修改'), findsOneWidget);
  });

  testWidgets(
      'legal representative sees only institution profile and review queues',
      (tester) async {
    final repository = _FakeIdentityRepository()
      ..managementContext = const ManagementContext(
        userId: 'user-legal',
        platformRole: 'USER',
        activeRoles: ['INSTITUTION_LEGAL_REPRESENTATIVE'],
        managedInstitutionIds: ['inst-1'],
        visibleInstitutionIds: ['inst-1'],
        canManageInstitutions: true,
        canManageInstitutionProjects: true,
        canManageOrders: true,
        canReviewInstitutionRequests: true,
        canReviewInstitutionProjectRequests: true,
      );

    await tester.pumpWidget(
      _localizedApp(
        home: ManagementCenterPage(
          repository: repository,
          discoverRepository: const _FakeDiscoverRepository(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('机构档案'), findsOneWidget);
    expect(find.text('成员加入审核'), findsOneWidget);
    expect(find.text('机构项目申请审核'), findsOneWidget);
    expect(find.text('机构项目加入审核'), findsOneWidget);
    expect(find.text('机构项目'), findsNothing);
    expect(find.text('专业订单'), findsNothing);
  });

  testWidgets('doctor sees self profile and request capabilities',
      (tester) async {
    final repository = _FakeIdentityRepository()
      ..managementContext = const ManagementContext(
        userId: 'doctor-1',
        platformRole: 'USER',
        activeRoles: ['DOCTOR'],
        doctorId: 'doctor-1',
        managedInstitutionIds: [],
        visibleInstitutionIds: [],
        canManageDoctors: true,
        canManageArticles: true,
        canManageOrders: true,
        canApplyToInstitutions: true,
        canSubmitPlatformProjectRequests: true,
        canSubmitInstitutionProjectRequests: true,
      );

    await tester.pumpWidget(_localizedApp(
      home: ManagementCenterPage(
        repository: repository,
        discoverRepository: const _FakeDiscoverRepository(),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('医生档案'), findsOneWidget);
    expect(find.text('申请加入机构'), findsOneWidget);
    expect(find.text('申请新增平台项目'), findsOneWidget);
    expect(find.text('申请新增机构项目'), findsOneWidget);
    expect(find.text('申请加入机构项目'), findsOneWidget);
    expect(find.text('机构项目'), findsNothing);
  });

  testWidgets('admin without a doctor role does not see the self profile entry',
      (tester) async {
    tester.view.physicalSize = const Size(800, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repository = _FakeIdentityRepository()
      ..managementContext = const ManagementContext(
        userId: 'admin-1',
        platformRole: 'ADMIN',
        activeRoles: [],
        managedInstitutionIds: [],
        visibleInstitutionIds: [],
        canManageDoctors: true,
      );

    await tester.pumpWidget(_localizedApp(
      home: ManagementCenterPage(
        repository: repository,
        discoverRepository: const _FakeDiscoverRepository(),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('医生档案'), findsNothing);
    expect(find.text('Doctor profile'), findsNothing);
  });

  testWidgets('consultant sees only institution application and affiliation',
      (tester) async {
    final repository = _FakeIdentityRepository()
      ..managementContext = const ManagementContext(
        userId: 'consultant-1',
        platformRole: 'USER',
        activeRoles: ['CONSULTANT'],
        managedInstitutionIds: [],
        visibleInstitutionIds: [],
        canApplyToInstitutions: true,
        canViewAffiliations: true,
      );

    await tester.pumpWidget(_localizedApp(
      home: ManagementCenterPage(
        repository: repository,
        discoverRepository: const _FakeDiscoverRepository(),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('申请加入机构'), findsOneWidget);
    expect(find.text('机构归属'), findsOneWidget);
    expect(find.text('专业订单'), findsNothing);
    expect(find.text('机构项目'), findsNothing);
  });
}

Widget _localizedApp(
        {required Widget home, Locale locale = const Locale('zh')}) =>
    MaterialApp(
      locale: locale,
      supportedLocales: const [Locale('zh'), Locale('en')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: home,
    );

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
  var contextCalls = 0;
  var allowManagement = true;
  ManagementContext? managementContext;
  final savedUpdates = <ManagedInstitutionProfileUpdate>[];
  List<InstitutionMembershipRequest> membershipRequests = const [];
  InstitutionProjectJoinRequestDraft? submittedJoinRequest;
  bool rejectLegalRepresentativeInstitutionList = false;

  @override
  Future<void> deletePrivateDraft(String fileId) async {}

  @override
  Future<IdentityOverview> loadOverview() async => const IdentityOverview();

  @override
  Future<ManagementContext> loadManagementContext() async {
    contextCalls += 1;
    if (!allowManagement) {
      throw Exception('revoked');
    }
    return managementContext ??
        const ManagementContext(
          userId: 'user-1',
          platformRole: 'USER',
          activeRoles: ['DOCTOR'],
          managedInstitutionIds: [],
          visibleInstitutionIds: [],
          canManageDoctors: true,
        );
  }

  @override
  Future<List<ManagedInstitutionSummary>> listManagedInstitutions() async {
    if (rejectLegalRepresentativeInstitutionList) {
      throw Exception('legal representative endpoint denied');
    }
    return [
      const ManagedInstitutionSummary(
        id: 'inst-1',
        name: '悦美医疗美容',
        city: '杭州',
      ),
    ];
  }

  @override
  Future<List<ManagedInstitutionSummary>>
      listProfessionalVisibleInstitutions() async => const [
            ManagedInstitutionSummary(
              id: 'inst-1',
              name: '悦美医疗美容',
              city: '杭州',
            ),
          ];

  @override
  Future<ManagedInstitutionProfile> loadManagedInstitution(String id) async {
    return ManagedInstitutionProfile.fromJson({
      'id': id,
      'name': '悦美医疗美容',
      'city': '杭州',
    });
  }

  @override
  Future<ManagedInstitutionProfile> updateManagedInstitution(
    String id,
    ManagedInstitutionProfileUpdate update,
  ) async {
    savedUpdates.add(update);
    return ManagedInstitutionProfile.fromJson({
      ...update.toJson(),
      'id': id,
      'description': '服务端保存结果',
    });
  }

  @override
  Future<IdentityApplication> submitApplication(
    IdentityApplicationDraft application,
  ) {
    throw UnimplementedError();
  }

  @override
  Future<PrivateIdentityFile> uploadPrivateFile(IdentityFileDraft file) {
    throw UnimplementedError();
  }

  @override
  Future<ManagedInstitutionProject> createManagedInstitutionProject(
    ManagedInstitutionProjectDraft draft,
  ) async {
    return ManagedInstitutionProject.fromJson({
      ...draft.toJson(),
      'id': 'institution-project-1',
      'effectiveName': draft.name,
    });
  }

  @override
  Future<ManagementProjectOption> createManagementProject(
    ManagementProjectDraft draft,
  ) async {
    return ManagementProjectOption.fromJson({
      ...draft.toJson(),
      'id': 'project-1',
    });
  }

  @override
  Future<List<ManagedInstitutionProject>>
      listManagedInstitutionProjects() async => [
            ManagedInstitutionProject.fromJson({
              'id': 'institution-project-1',
              'institutionId': 'inst-1',
              'projectId': 'project-1',
              'effectiveName': '水光护理',
            }),
          ];

  @override
  Future<List<ManagementProjectOption>> listManagementProjects() async =>
      const [];

  @override
  Future<void> submitSplitConfigProposal(
      SplitConfigProposalDraft draft) async {}

  @override
  Future<ManagedInstitutionProject> updateManagedInstitutionProject(
    ManagedInstitutionProjectDraft draft,
  ) async {
    return ManagedInstitutionProject.fromJson({
      ...draft.toJson(),
      'id': draft.id ?? 'institution-project-1',
      'effectiveName': draft.name,
    });
  }

  @override
  Future<DoctorSelfProfile> loadDoctorSelfProfile() {
    throw UnimplementedError();
  }

  @override
  Future<DoctorSelfProfile> updateDoctorSelfProfile(
    DoctorSelfProfileUpdate update,
  ) {
    throw UnimplementedError();
  }

  @override
  Future<List<InstitutionOption>> listInstitutionOptions() async => const [
        InstitutionOption(id: 'inst-1', name: '悦美医疗美容'),
      ];

  @override
  Future<List<InstitutionMembershipRequest>>
      listInstitutionMembershipRequests() async => membershipRequests;

  @override
  Future<void> submitInstitutionMembershipRequest({
    required String requestType,
    required String institutionId,
    required String requestNote,
  }) async {}

  @override
  Future<void> reviewInstitutionMembershipRequest({
    required String requestType,
    required String id,
    required String decision,
    required String reviewNote,
  }) async {}

  @override
  Future<List<DoctorInstitutionChangeRequest>>
      listDoctorInstitutionChangeRequests() async => const [];

  @override
  Future<void> submitDoctorInstitutionChangeRequest(
    DoctorInstitutionChangeRequestDraft draft,
  ) async {}

  @override
  Future<void> withdrawDoctorInstitutionChangeRequest(String id) async {}

  @override
  Future<void> reviewDoctorInstitutionChangeRequest({
    required String id,
    required String decision,
    required String reviewNote,
  }) async {}

  @override
  Future<List<ProfessionalProjectRequest>>
      listProfessionalProjectRequests() async => const [];

  @override
  Future<void> submitPlatformProjectRequest(
    PlatformProjectRequestDraft draft,
  ) async {}

  @override
  Future<void> submitInstitutionProjectRequest(
    InstitutionProjectRequestDraft draft,
  ) async {}

  @override
  Future<void> reviewInstitutionProjectRequest({
    required String id,
    required String decision,
    required String reviewNote,
  }) async {}

  @override
  Future<List<InstitutionProjectJoinRequest>>
      listInstitutionProjectJoinRequests() async => const [];

  @override
  Future<void> submitInstitutionProjectJoinRequest(
    InstitutionProjectJoinRequestDraft draft,
  ) async {
    submittedJoinRequest = draft;
  }

  @override
  Future<void> reviewInstitutionProjectJoinRequest({
    required String id,
    required String decision,
    required String reviewNote,
  }) async {}
}
