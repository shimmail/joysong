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
}

final class _FakeIdentityRepository implements IdentityRepository {
  var contextCalls = 0;
  var allowManagement = true;

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
  Future<IdentityApplication> submitApplication(
    IdentityApplicationDraft application,
  ) {
    throw UnimplementedError();
  }

  @override
  Future<PrivateIdentityFile> uploadPrivateFile(IdentityFileDraft file) {
    throw UnimplementedError();
  }
}
