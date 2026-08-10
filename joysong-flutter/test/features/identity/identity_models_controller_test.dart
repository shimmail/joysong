import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_controller.dart';

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
}

final class _FakeIdentityRepository implements IdentityRepository {
  var contextCalls = 0;
  var allowManagement = true;
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
    return const ManagementContext(
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
      listManagedInstitutionProjects() async => const [];

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
