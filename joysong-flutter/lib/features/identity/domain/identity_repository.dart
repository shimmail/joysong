import 'package:joysong_flutter/features/identity/domain/identity_models.dart';

abstract interface class IdentityRepository {
  Future<IdentityOverview> loadOverview();

  Future<PrivateIdentityFile> uploadPrivateFile(IdentityFileDraft file);

  Future<void> deletePrivateDraft(String fileId);

  Future<IdentityApplication> submitApplication(
    IdentityApplicationDraft application,
  );

  Future<ManagementContext> loadManagementContext();

  Future<List<ManagedInstitutionProfile>> listManagedInstitutionProfiles();

  Future<ManagedInstitutionProfile> updateManagedInstitutionProfile(
    ManagedInstitutionProfileDraft draft,
  );

  Future<List<ManagementProjectOption>> listManagementProjects();

  Future<ManagementProjectOption> createManagementProject(
    ManagementProjectDraft draft,
  );

  Future<List<ManagedInstitutionProject>> listManagedInstitutionProjects();

  Future<ManagedInstitutionProject> createManagedInstitutionProject(
    ManagedInstitutionProjectDraft draft,
  );

  Future<ManagedInstitutionProject> updateManagedInstitutionProject(
    ManagedInstitutionProjectDraft draft,
  );

  Future<void> submitSplitConfigProposal(SplitConfigProposalDraft draft);
}
