import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_controller.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';

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

  test('institution profile draft normalizes city and omits protected fields',
      () {
    final profile = ManagedInstitutionProfile.fromJson({
      'id': 'inst-1',
      'name': '悦美医疗美容',
      'city': '杭州市',
      'address': '西湖区 1 号',
      'rating': 4.9,
      'reviewCount': 128,
      'isVerified': true,
    });

    final draft = profile.toDraft().copyWith(city: ' 杭州市 ');

    expect(draft.city, '杭州');
    expect(draft.toJson(), isNot(contains('rating')));
    expect(draft.toJson(), isNot(contains('reviewCount')));
    expect(draft.toJson(), isNot(contains('isVerified')));
  });

  test('institution profile controller loads and saves managed institution',
      () async {
    final repository = _FakeIdentityRepository();
    final controller = InstitutionProfileController(repository);

    await controller.load();
    expect(controller.status, InstitutionProfileLoadStatus.ready);
    expect(controller.profiles.single.id, 'inst-1');

    final saved = await controller.save(
      controller.profiles.single.toDraft().copyWith(city: '宁波市'),
    );

    expect(saved, isTrue);
    expect(repository.savedDrafts.single.city, '宁波');
  });

  testWidgets('institution profile edit uses public info and album image copy',
      (tester) async {
    tester.view.physicalSize = const Size(800, 2200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repository = _FakeIdentityRepository();
    final pickedImages = ['cover.jpg', 'license.png'];

    await tester.pumpWidget(
      MaterialApp(
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

    expect(find.text('cover.jpg'), findsOneWidget);
    expect(find.text('license.png'), findsOneWidget);
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
      MaterialApp(
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
}

final class _FakeIdentityRepository implements IdentityRepository {
  var contextCalls = 0;
  var allowManagement = true;
  ManagementContext? managementContext;
  final savedDrafts = <ManagedInstitutionProfileDraft>[];

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
  Future<List<ManagedInstitutionProfile>>
      listManagedInstitutionProfiles() async {
    return [
      ManagedInstitutionProfile.fromJson({
        'id': 'inst-1',
        'name': '悦美医疗美容',
        'city': '杭州',
      }),
    ];
  }

  @override
  Future<ManagedInstitutionProfile> updateManagedInstitutionProfile(
    ManagedInstitutionProfileDraft draft,
  ) async {
    savedDrafts.add(draft);
    return ManagedInstitutionProfile.fromJson({
      ...draft.toJson(),
      'id': draft.id,
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
}
