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
    expect(context.consultantInstitutionIds, isEmpty);
    expect(context.hasAnyCapability, isFalse);
  });

  test('management context only defaults absent consultant institutions', () {
    const base = <String, Object?>{
      'userId': 'consultant-user-1',
      'platformRole': 'USER',
      'activeRoles': ['CONSULTANT'],
      'managedInstitutionIds': <String>[],
      'visibleInstitutionIds': <String>[],
    };

    for (final invalid in [null, 'institution-1', const <String, Object?>{}]) {
      expect(
        () => ManagementContext.fromJson({
          ...base,
          'consultantInstitutionIds': invalid,
        }),
        throwsFormatException,
      );
    }
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

  test('unified membership request parses both professional scopes and audits',
      () {
    final doctorRequest = InstitutionMembershipRequest.fromJson({
      'id': 'request-1',
      'requestType': 'DOCTOR',
      'applicantId': 'doctor-1',
      'applicantName': 'Dr. Lin',
      'institutionId': 'institution-1',
      'institutionName': 'Joysong Clinic',
      'action': 'JOIN',
      'status': 'PENDING',
      'relationshipStatus': 'NONE',
      'requestNote': 'Please add me',
      'reviewNote': '',
      'submittedBy': 'doctor-user-1',
      'reviewedBy': null,
      'submittedAt': '2026-08-10T09:00:00Z',
      'reviewedAt': null,
      'createdAt': '2026-08-10T09:00:00Z',
      'updatedAt': '2026-08-10T09:01:00Z',
    });
    final consultantRequest = InstitutionMembershipRequest.fromJson({
      'id': 'request-2',
      'requestType': 'CONSULTANT',
      'applicantId': 'consultant-1',
      'applicantName': 'Ms. Chen',
      'institutionId': 'institution-2',
      'institutionName': 'Harbor Clinic',
      'action': 'LEAVE',
      'status': 'APPROVED',
      'relationshipStatus': 'APPROVED',
      'requestNote': 'Changing practices',
      'reviewNote': 'Approved',
      'submittedBy': 'consultant-user-1',
      'reviewedBy': 'legal-user-1',
      'submittedAt': '2026-08-11T09:00:00',
      'reviewedAt': '2026-08-11T10:00:00',
      'createdAt': '2026-08-11T09:00:00',
      'updatedAt': '2026-08-11T10:00:00',
    });

    expect(doctorRequest.requestType, InstitutionMembershipRequestType.doctor);
    expect(doctorRequest.action, InstitutionMembershipAction.join);
    expect(doctorRequest.status, InstitutionMembershipRequestStatus.pending);
    expect(
        doctorRequest.relationshipStatus, InstitutionRelationshipStatus.none);
    expect(doctorRequest.applicantName, 'Dr. Lin');
    expect(doctorRequest.institutionName, 'Joysong Clinic');
    expect(doctorRequest.submittedAt, DateTime.parse('2026-08-10T09:00:00Z'));
    expect(doctorRequest.reviewedBy, isNull);
    expect(doctorRequest.reviewedAt, isNull);
    expect(
      consultantRequest.requestType,
      InstitutionMembershipRequestType.consultant,
    );
    expect(consultantRequest.action, InstitutionMembershipAction.leave);
    expect(
      consultantRequest.relationshipStatus,
      InstitutionRelationshipStatus.approved,
    );
    expect(consultantRequest.reviewedBy, 'legal-user-1');
    expect(
      consultantRequest.reviewedAt,
      DateTime.parse('2026-08-11T10:00:00'),
    );
  });

  test('membership protocol parses every status and rejects malformed values',
      () {
    final statuses = <String, InstitutionMembershipRequestStatus>{
      'PENDING': InstitutionMembershipRequestStatus.pending,
      'APPROVED': InstitutionMembershipRequestStatus.approved,
      'REJECTED': InstitutionMembershipRequestStatus.rejected,
      'WITHDRAWN': InstitutionMembershipRequestStatus.withdrawn,
    };
    for (final entry in statuses.entries) {
      expect(
        InstitutionMembershipRequest.fromJson({
          ..._normalizedMembershipRequestJson,
          'status': entry.key,
        }).status,
        entry.value,
      );
    }

    for (final malformed in const <String, String>{
      'requestType': 'NURSE',
      'action': 'TRANSFER',
      'status': 'UNKNOWN',
      'relationshipStatus': 'REVOKED',
    }.entries) {
      expect(
        () => InstitutionMembershipRequest.fromJson({
          ..._normalizedMembershipRequestJson,
          malformed.key: malformed.value,
        }),
        throwsFormatException,
      );
    }
    for (final requiredField in const [
      'id',
      'requestType',
      'applicantId',
      'applicantName',
      'institutionId',
      'institutionName',
      'action',
      'status',
      'relationshipStatus',
      'requestNote',
      'reviewNote',
      'submittedBy',
      'reviewedBy',
      'submittedAt',
      'reviewedAt',
      'createdAt',
      'updatedAt',
    ]) {
      final json = Map<String, Object?>.of(_normalizedMembershipRequestJson)
        ..remove(requiredField);
      expect(
        () => InstitutionMembershipRequest.fromJson(json),
        throwsFormatException,
        reason: '$requiredField is required by the normalized response',
      );
    }
    expect(
      () => InstitutionMembershipRequest.fromJson({
        ..._normalizedMembershipRequestJson,
        'updatedAt': 'not-a-date',
      }),
      throwsFormatException,
    );
  });

  test('membership draft and candidate page use the normalized wire contract',
      () {
    expect(
      const InstitutionMembershipRequestDraft(
        requestType: InstitutionMembershipRequestType.consultant,
        action: InstitutionMembershipAction.leave,
        institutionId: ' institution-1 ',
        requestNote: ' Please remove me ',
      ).toJson(),
      {
        'requestType': 'CONSULTANT',
        'action': 'LEAVE',
        'institutionId': 'institution-1',
        'requestNote': 'Please remove me',
      },
    );

    final page = InstitutionMembershipCandidatePage.fromJson({
      'items': [
        {'id': 'institution-1', 'name': 'Joysong Clinic'},
      ],
      'offset': 20,
      'limit': 100,
      'hasMore': true,
    });
    expect(page.items.single.id, 'institution-1');
    expect(page.items.single.name, 'Joysong Clinic');
    expect(page.offset, 20);
    expect(page.limit, 100);
    expect(page.hasMore, isTrue);

    final context = ManagementContext.fromJson({
      'userId': 'consultant-user-1',
      'platformRole': 'USER',
      'activeRoles': ['CONSULTANT'],
      'managedInstitutionIds': const [],
      'visibleInstitutionIds': ['institution-1'],
      'consultantInstitutionIds': ['institution-1'],
    });
    expect(context.consultantInstitutionIds, ['institution-1']);
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

  testWidgets(
      'membership reviews offer approve and reject while project reviews retain request changes',
      (tester) async {
    final repository = _FakeIdentityRepository()
      ..membershipRequests = [
        InstitutionMembershipRequest.fromJson({
          ..._normalizedMembershipRequestJson,
          'id': 'membership-1',
          'requestType': 'CONSULTANT',
          'applicantId': 'consultant-1',
          'applicantName': '顾问一号',
          'institutionId': 'inst-1',
          'institutionName': '悦美医疗美容',
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
      ..rejectProfessionalProjectCatalog = true
      ..professionalProjectRequests = [
        ProfessionalProjectRequest.fromJson({
          'id': 'request-1',
          'requestType': 'INSTITUTION',
          'doctorId': 'doctor-1',
          'doctorName': 'Doctor Joy',
          'institutionId': 'inst-1',
          'institutionName': 'Joysong Clinic',
          'projectId': 'project-1',
          'projectName': 'Skin Renewal',
          'status': 'PENDING',
        }),
      ]
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

    await tester.tap(find.text('机构项目申请审核'));
    await tester.pumpAndSettle();
    expect(find.text('Skin Renewal'), findsOneWidget);
    expect(find.text('机构项目申请加载失败，请重试'), findsNothing);
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

const _normalizedMembershipRequestJson = <String, Object?>{
  'id': 'request-1',
  'requestType': 'DOCTOR',
  'applicantId': 'doctor-1',
  'applicantName': 'Dr. Lin',
  'institutionId': 'institution-1',
  'institutionName': 'Joysong Clinic',
  'action': 'JOIN',
  'status': 'PENDING',
  'relationshipStatus': 'NONE',
  'requestNote': '',
  'reviewNote': '',
  'submittedBy': 'doctor-user-1',
  'reviewedBy': null,
  'submittedAt': '2026-08-10T09:00:00',
  'reviewedAt': null,
  'createdAt': '2026-08-10T09:00:00',
  'updatedAt': '2026-08-10T09:01:00',
};

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
  bool rejectProfessionalProjectCatalog = false;
  List<ProfessionalProjectRequest> professionalProjectRequests = const [];

  @override
  Future<List<ConsultantMembership>> listConsultantMemberships() async =>
      const [];

  @override
  Future<ConsultantMembership> submitConsultantMembership(
    ConsultantMembershipDraft draft,
  ) =>
      throw UnimplementedError();
  var contextCalls = 0;
  var allowManagement = true;
  ManagementContext? managementContext;
  final savedUpdates = <ManagedInstitutionProfileUpdate>[];
  List<InstitutionMembershipRequest> membershipRequests = const [];
  InstitutionProjectJoinRequestDraft? submittedJoinRequest;

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
  Future<List<ManagedInstitutionSummary>> listManagedInstitutions() async => [
        const ManagedInstitutionSummary(
          id: 'inst-1',
          name: '悦美医疗美容',
          city: '杭州',
        ),
      ];

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
  Future<List<ManagementProjectOption>> listManagementProjects() async {
    if (rejectProfessionalProjectCatalog) {
      throw Exception('403 forbidden');
    }
    return const [];
  }

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
  Future<List<InstitutionMembershipRequest>>
      listOwnedInstitutionMembershipRequests() async => membershipRequests;

  @override
  Future<List<InstitutionMembershipRequest>>
      listReviewableInstitutionMembershipRequests() async => membershipRequests;

  @override
  Future<InstitutionMembershipCandidatePage>
      listInstitutionMembershipCandidates({
    required InstitutionMembershipRequestType requestType,
    required InstitutionMembershipAction action,
    required String query,
    required int offset,
    required int limit,
  }) =>
          throw UnimplementedError();

  @override
  Future<InstitutionMembershipRequest> submitInstitutionMembershipRequest(
    InstitutionMembershipRequestDraft draft,
  ) async =>
      InstitutionMembershipRequest.fromJson({
        ..._normalizedMembershipRequestJson,
        'requestType': draft.requestType.code,
        'action': draft.action.code,
        'institutionId': draft.institutionId.trim(),
        'requestNote': draft.requestNote.trim(),
      });

  @override
  Future<InstitutionMembershipRequest> withdrawInstitutionMembershipRequest({
    required InstitutionMembershipRequestType requestType,
    required String id,
  }) async =>
      InstitutionMembershipRequest.fromJson({
        ..._normalizedMembershipRequestJson,
        'id': id.trim(),
        'requestType': requestType.code,
        'status': 'WITHDRAWN',
      });

  @override
  Future<InstitutionMembershipRequest> reviewInstitutionMembershipRequest({
    required InstitutionMembershipRequestType requestType,
    required String id,
    required InstitutionMembershipDecision decision,
    required String reviewNote,
  }) async =>
      InstitutionMembershipRequest.fromJson({
        ..._normalizedMembershipRequestJson,
        'id': id.trim(),
        'requestType': requestType.code,
        'status': decision.code,
        'reviewNote': reviewNote.trim(),
        'reviewedBy': 'reviewer-1',
        'reviewedAt': '2026-08-10T10:00:00',
      });

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
      listProfessionalProjectRequests() async => professionalProjectRequests;

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

  @override
  Future<DoctorProjectChangeRequest> submitDoctorProjectProfileUpdate(
    DoctorProjectProfileUpdateDraft draft,
  ) =>
      throw UnimplementedError();

  @override
  Future<List<DoctorProjectProfileUpdateTarget>>
      listDoctorProjectProfileUpdateTargets() async => const [];

  @override
  Future<List<DoctorProjectChangeRequest>>
      listDoctorProjectChangeRequests() async => const [];

  @override
  Future<void> withdrawDoctorProjectChangeRequest(String id) async {}

  @override
  Future<void> reviewDoctorProjectChangeRequest({
    required String id,
    required String decision,
    required String reviewNote,
    required bool force,
  }) async {}
}
