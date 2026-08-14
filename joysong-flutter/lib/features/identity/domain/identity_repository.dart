import 'package:joysong_flutter/features/identity/domain/identity_models.dart';

abstract interface class IdentityRepository {
  Future<IdentityOverview> loadOverview();

  Future<PrivateIdentityFile> uploadPrivateFile(IdentityFileDraft file);

  Future<void> deletePrivateDraft(String fileId);

  Future<IdentityApplication> submitApplication(
    IdentityApplicationDraft application,
  );

  Future<ManagementContext> loadManagementContext();

  Future<List<ManagedInstitutionSummary>> listManagedInstitutions();

  Future<List<ManagedInstitutionSummary>> listProfessionalVisibleInstitutions();

  Future<ManagedInstitutionProfile> loadManagedInstitution(String id);

  Future<ManagedInstitutionProfile> updateManagedInstitution(
    String id,
    ManagedInstitutionProfileUpdate update,
  );

  Future<DoctorSelfProfile> loadDoctorSelfProfile();

  Future<DoctorSelfProfile> updateDoctorSelfProfile(
    DoctorSelfProfileUpdate update,
  );

  Future<List<ConsultantMembership>> listConsultantMemberships();

  Future<ConsultantMembership> submitConsultantMembership(
    ConsultantMembershipDraft draft,
  );

  Future<List<ManagementProjectOption>> listManagementProjects();

  Future<List<ManagedInstitutionProject>> listManagedInstitutionProjects();

  Future<void> submitSplitConfigProposal(SplitConfigProposalDraft draft);

  Future<List<InstitutionOption>> listInstitutionOptions();

  @Deprecated('Use listOwnedInstitutionMembershipRequests instead')
  Future<List<InstitutionMembershipRequest>>
      listInstitutionMembershipRequests();

  Future<List<InstitutionMembershipRequest>>
      listOwnedInstitutionMembershipRequests();

  Future<List<InstitutionMembershipRequest>>
      listReviewableInstitutionMembershipRequests();

  Future<InstitutionMembershipCandidatePage>
      listInstitutionMembershipCandidates({
    required InstitutionMembershipRequestType requestType,
    required InstitutionMembershipAction action,
    required String query,
    required int offset,
    required int limit,
  });

  Future<InstitutionMembershipRequest> submitInstitutionMembershipRequest(
    InstitutionMembershipRequestDraft draft,
  );

  Future<InstitutionMembershipRequest> withdrawInstitutionMembershipRequest({
    required InstitutionMembershipRequestType requestType,
    required String id,
  });

  Future<InstitutionMembershipRequest> reviewInstitutionMembershipRequest({
    required InstitutionMembershipRequestType requestType,
    required String id,
    required InstitutionMembershipDecision decision,
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

  Future<void> submitPlatformProjectRequest(PlatformProjectRequestDraft draft);

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

  Future<DoctorProjectChangeRequest> submitDoctorProjectProfileUpdate(
    DoctorProjectProfileUpdateDraft draft,
  );

  Future<List<DoctorProjectProfileUpdateTarget>>
      listDoctorProjectProfileUpdateTargets();

  Future<List<DoctorProjectChangeRequest>> listDoctorProjectChangeRequests();

  Future<void> withdrawDoctorProjectChangeRequest(String id);

  Future<void> reviewDoctorProjectChangeRequest({
    required String id,
    required String decision,
    required String reviewNote,
    required bool force,
  });
}
