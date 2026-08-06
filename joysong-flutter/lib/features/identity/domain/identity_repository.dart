import 'package:joysong_flutter/features/identity/domain/identity_models.dart';

abstract interface class IdentityRepository {
  Future<IdentityOverview> loadOverview();

  Future<PrivateIdentityFile> uploadPrivateFile(IdentityFileDraft file);

  Future<void> deletePrivateDraft(String fileId);

  Future<IdentityApplication> submitApplication(
    IdentityApplicationDraft application,
  );

  Future<ManagementContext> loadManagementContext();
}
