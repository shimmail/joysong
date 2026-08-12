import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';

final class ApiIdentityRepository implements IdentityRepository {
  const ApiIdentityRepository(this._apiClient);

  final ApiClient _apiClient;

  @override
  Future<IdentityOverview> loadOverview() async {
    return await _apiClient.get<IdentityOverview>(
          '/identity/overview',
          decodeData: IdentityOverview.fromJson,
        ) ??
        const IdentityOverview();
  }

  @override
  Future<PrivateIdentityFile> uploadPrivateFile(IdentityFileDraft file) async {
    file.validate();
    final result = await _apiClient.postMultipart<PrivateIdentityFile>(
      '/identity/files',
      fields: {'purpose': file.purpose.code},
      files: [
        MultipartFilePart(
          fieldName: 'file',
          fileName: file.fileName,
          contentType: file.contentType,
          bytes: file.bytes,
        ),
      ],
      decodeData: PrivateIdentityFile.fromJson,
    );
    if (result == null) {
      throw const FormatException('认证材料上传响应为空');
    }
    return result;
  }

  @override
  Future<void> deletePrivateDraft(String fileId) async {
    await _apiClient.delete<void>(
      '/identity/files/$fileId',
      decodeData: (_) {},
    );
  }

  @override
  Future<IdentityApplication> submitApplication(
    IdentityApplicationDraft application,
  ) async {
    application.validate();
    final result = await _apiClient.post<IdentityApplication>(
      '/identity/applications',
      body: application.toJson(),
      decodeData: IdentityApplication.fromJson,
    );
    if (result == null) {
      throw const FormatException('身份申请响应为空');
    }
    return result;
  }

  @override
  Future<ManagementContext> loadManagementContext() async {
    final context = await _apiClient.get<ManagementContext>(
      '/management/context',
      decodeData: ManagementContext.fromJson,
    );
    if (context == null) {
      throw const FormatException('管理上下文响应为空');
    }
    return context;
  }

  @override
  Future<List<ManagedInstitutionSummary>> listManagedInstitutions() async {
    return await _apiClient.get<List<ManagedInstitutionSummary>>(
          '/management/institutions',
          decodeData: (json) => _objectList(
            json,
          ).map(ManagedInstitutionSummary.fromJson).toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<List<ManagedInstitutionSummary>>
      listProfessionalVisibleInstitutions() async {
    return await _apiClient.get<List<ManagedInstitutionSummary>>(
          '/admin/institutions',
          decodeData: (json) => _objectList(
            json,
          ).map(ManagedInstitutionSummary.fromJson).toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<ManagedInstitutionProfile> loadManagedInstitution(String id) async {
    final result = await _apiClient.get<ManagedInstitutionProfile>(
      '/management/institutions/${id.trim()}',
      decodeData: ManagedInstitutionProfile.fromJson,
    );
    if (result == null) {
      throw const FormatException('机构档案响应为空');
    }
    return result;
  }

  @override
  Future<ManagedInstitutionProfile> updateManagedInstitution(
    String id,
    ManagedInstitutionProfileUpdate update,
  ) async {
    final result = await _apiClient.put<ManagedInstitutionProfile>(
      '/management/institutions/${id.trim()}',
      body: update.toJson(),
      decodeData: ManagedInstitutionProfile.fromJson,
    );
    if (result == null) {
      throw const FormatException('机构档案响应为空');
    }
    return result;
  }

  @override
  Future<DoctorSelfProfile> loadDoctorSelfProfile() async {
    final result = await _apiClient.get<DoctorSelfProfile>(
      '/management/doctor-profile',
      decodeData: DoctorSelfProfile.fromJson,
    );
    if (result == null) {
      throw const FormatException('医生档案响应为空');
    }
    return result;
  }

  @override
  Future<DoctorSelfProfile> updateDoctorSelfProfile(
    DoctorSelfProfileUpdate update,
  ) async {
    final result = await _apiClient.put<DoctorSelfProfile>(
      '/management/doctor-profile',
      body: update.toJson(),
      decodeData: DoctorSelfProfile.fromJson,
    );
    if (result == null) {
      throw const FormatException('医生档案响应为空');
    }
    return result;
  }

  @override
  Future<List<ConsultantMembership>> listConsultantMemberships() async {
    return await _apiClient.get<List<ConsultantMembership>>(
          '/management/consultant-memberships',
          decodeData: (json) => _objectList(
            json,
          ).map(ConsultantMembership.fromJson).toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<ConsultantMembership> submitConsultantMembership(
    ConsultantMembershipDraft draft,
  ) async {
    final result = await _apiClient.post<ConsultantMembership>(
      '/management/consultant-memberships',
      body: draft.toJson(),
      decodeData: ConsultantMembership.fromJson,
    );
    if (result == null) {
      throw const FormatException('顾问机构申请响应为空');
    }
    return result;
  }

  @override
  Future<List<ManagementProjectOption>> listManagementProjects() async {
    return await _apiClient.get<List<ManagementProjectOption>>(
          '/management/projects',
          decodeData: (json) => _objectList(
            json,
          ).map(ManagementProjectOption.fromJson).toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<List<ManagedInstitutionProject>>
      listManagedInstitutionProjects() async {
    return await _apiClient.get<List<ManagedInstitutionProject>>(
          '/admin/institution-projects',
          decodeData: (json) => _objectList(
            json,
          ).map(ManagedInstitutionProject.fromJson).toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<void> submitSplitConfigProposal(SplitConfigProposalDraft draft) async {
    if (!draft.canSubmit) {
      throw ArgumentError('分账提案缺少医生或机构项目');
    }
    await _apiClient.post<void>(
      '/admin/doctor-institution-project-config-proposals',
      body: draft.toJson(),
      decodeData: (_) {},
    );
  }

  @override
  Future<List<InstitutionOption>> listInstitutionOptions() async {
    return await _apiClient.get<List<InstitutionOption>>(
          '/discover/institutions',
          query: const {'offset': 0, 'limit': 100},
          decodeData: (json) => _objectList(
            json,
          ).map(InstitutionOption.fromJson).toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<List<InstitutionMembershipRequest>>
      listInstitutionMembershipRequests() async {
    return await _apiClient.get<List<InstitutionMembershipRequest>>(
          '/management/institution-membership-requests',
          decodeData: (json) => _objectList(
            json,
          ).map(InstitutionMembershipRequest.fromJson).toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<void> submitInstitutionMembershipRequest({
    required String requestType,
    required String institutionId,
    required String requestNote,
  }) async {
    await _apiClient.post<void>(
      '/management/institution-membership-requests',
      body: {
        'requestType': requestType,
        'institutionId': institutionId,
        'requestNote': requestNote,
      },
      decodeData: (_) {},
    );
  }

  @override
  Future<void> reviewInstitutionMembershipRequest({
    required String requestType,
    required String id,
    required String decision,
    required String reviewNote,
  }) async {
    await _apiClient.post<void>(
      '/management/institution-membership-requests/$requestType/$id/review',
      body: {'decision': decision, 'reviewNote': reviewNote},
      decodeData: (_) {},
    );
  }

  @override
  Future<List<DoctorInstitutionChangeRequest>>
      listDoctorInstitutionChangeRequests() async {
    return await _apiClient.get<List<DoctorInstitutionChangeRequest>>(
          '/management/institution-membership-requests',
          decodeData: (json) => _objectList(json)
              .where(_isDoctorInstitutionRequest)
              .map(DoctorInstitutionChangeRequest.fromJson)
              .toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<void> submitDoctorInstitutionChangeRequest(
    DoctorInstitutionChangeRequestDraft draft,
  ) async {
    await _apiClient.post<void>(
      '/management/institution-membership-requests',
      body: draft.toJson(),
      decodeData: (_) {},
    );
  }

  @override
  Future<void> withdrawDoctorInstitutionChangeRequest(String id) async {
    await _apiClient.post<void>(
      '/management/institution-membership-requests/DOCTOR/$id/withdraw',
      decodeData: (_) {},
    );
  }

  @override
  Future<void> reviewDoctorInstitutionChangeRequest({
    required String id,
    required String decision,
    required String reviewNote,
  }) async {
    await _apiClient.post<void>(
      '/management/institution-membership-requests/DOCTOR/$id/review',
      body: {'decision': decision, 'reviewNote': reviewNote},
      decodeData: (_) {},
    );
  }

  @override
  Future<List<ProfessionalProjectRequest>>
      listProfessionalProjectRequests() async {
    return await _apiClient.get<List<ProfessionalProjectRequest>>(
          '/management/project-requests',
          decodeData: (json) => _objectList(
            json,
          ).map(ProfessionalProjectRequest.fromJson).toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<void> submitPlatformProjectRequest(
    PlatformProjectRequestDraft draft,
  ) async {
    await _apiClient.post<void>(
      '/management/project-requests/platform',
      body: draft.toJson(),
      decodeData: (_) {},
    );
  }

  @override
  Future<void> submitInstitutionProjectRequest(
    InstitutionProjectRequestDraft draft,
  ) async {
    await _apiClient.post<void>(
      '/management/project-requests/institutions/${draft.institutionId}',
      body: draft.toJson(),
      decodeData: (_) {},
    );
  }

  @override
  Future<void> reviewInstitutionProjectRequest({
    required String id,
    required String decision,
    required String reviewNote,
  }) async {
    await _apiClient.post<void>(
      '/management/project-requests/$id/review',
      body: {'decision': decision, 'reviewNote': reviewNote},
      decodeData: (_) {},
    );
  }

  @override
  Future<List<InstitutionProjectJoinRequest>>
      listInstitutionProjectJoinRequests() async {
    return await _apiClient.get<List<InstitutionProjectJoinRequest>>(
          '/admin/institution-project-requests',
          decodeData: (json) => _objectList(
            json,
          ).map(InstitutionProjectJoinRequest.fromJson).toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<void> submitInstitutionProjectJoinRequest(
    InstitutionProjectJoinRequestDraft draft,
  ) async {
    draft.validate();
    await _apiClient.post<void>(
      '/admin/institution-project-requests',
      body: draft.toJson(),
      decodeData: (_) {},
    );
  }

  @override
  Future<void> reviewInstitutionProjectJoinRequest({
    required String id,
    required String decision,
    required String reviewNote,
  }) async {
    await _apiClient.post<void>(
      '/admin/institution-project-requests/$id/review',
      body: {'decision': decision, 'reviewNote': reviewNote},
      decodeData: (_) {},
    );
  }

  @override
  Future<DoctorProjectChangeRequest> submitDoctorProjectProfileUpdate(
    DoctorProjectProfileUpdateDraft draft,
  ) async {
    final result = await _apiClient.post<DoctorProjectChangeRequest>(
      '/admin/institution-project-requests',
      body: draft.toJson(),
      decodeData: DoctorProjectChangeRequest.fromJson,
    );
    if (result == null) throw const FormatException('医生项目变更申请响应为空');
    return result;
  }

  @override
  Future<List<DoctorProjectProfileUpdateTarget>>
      listDoctorProjectProfileUpdateTargets() async {
    return await _apiClient.get<List<DoctorProjectProfileUpdateTarget>>(
          '/admin/institution-project-requests/profile-update-targets',
          decodeData: (json) => _objectList(json)
              .map(DoctorProjectProfileUpdateTarget.fromJson)
              .toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<List<DoctorProjectChangeRequest>>
      listDoctorProjectChangeRequests() async {
    return await _apiClient.get<List<DoctorProjectChangeRequest>>(
          '/admin/institution-project-requests',
          decodeData: (json) => _objectList(
            json,
          ).map(DoctorProjectChangeRequest.fromJson).toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<void> withdrawDoctorProjectChangeRequest(String id) async {
    final normalizedId = id.trim();
    if (normalizedId.isEmpty) throw ArgumentError.value(id, 'id');
    await _apiClient.post<void>(
      '/admin/institution-project-requests/$normalizedId/withdraw',
      decodeData: (_) {},
    );
  }

  @override
  Future<void> reviewDoctorProjectChangeRequest({
    required String id,
    required String decision,
    required String reviewNote,
    required bool force,
  }) async {
    await _apiClient.post<void>(
      '/admin/institution-project-requests/$id/review',
      body: {
        'decision': decision,
        'reviewNote': reviewNote.trim(),
        'force': force,
      },
      decodeData: (_) {},
    );
  }
}

List<Object?> _objectList(Object? value) => value is List ? value : const [];

bool _isDoctorInstitutionRequest(Object? value) =>
    value is Map &&
    value['requestType']?.toString().trim().toUpperCase() == 'DOCTOR';
