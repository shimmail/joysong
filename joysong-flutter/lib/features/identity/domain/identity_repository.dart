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

  Future<DoctorSelfProfile> loadDoctorSelfProfile();

  Future<DoctorSelfProfile> updateDoctorSelfProfile(
    DoctorSelfProfileUpdate update,
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

  Future<List<InstitutionOption>> listInstitutionOptions();

  Future<List<InstitutionMembershipRequest>>
      listInstitutionMembershipRequests();

  Future<void> submitInstitutionMembershipRequest({
    required String requestType,
    required String institutionId,
    required String requestNote,
  });

  Future<void> reviewInstitutionMembershipRequest({
    required String requestType,
    required String id,
    required String decision,
    required String reviewNote,
  });

  Future<List<DoctorInstitutionChangeRequest>>
      listDoctorInstitutionChangeRequests();

  Future<void> submitDoctorInstitutionChangeRequest(
    DoctorInstitutionChangeRequestDraft draft,
  );

  Future<void> withdrawDoctorInstitutionChangeRequest(String id);

  Future<void> reviewDoctorInstitutionChangeRequest({
    required String id,
    required String decision,
    required String reviewNote,
  });

  Future<List<ProfessionalProjectRequest>> listProfessionalProjectRequests();

  Future<void> submitPlatformProjectRequest(
    PlatformProjectRequestDraft draft,
  );

  Future<void> submitInstitutionProjectRequest(
    InstitutionProjectRequestDraft draft,
  );

  Future<void> reviewInstitutionProjectRequest({
    required String id,
    required String decision,
    required String reviewNote,
  });

  Future<List<InstitutionProjectJoinRequest>>
      listInstitutionProjectJoinRequests();

  Future<void> submitInstitutionProjectJoinRequest(
    InstitutionProjectJoinRequestDraft draft,
  );

  Future<void> reviewInstitutionProjectJoinRequest({
    required String id,
    required String decision,
    required String reviewNote,
  });
}
