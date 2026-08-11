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
  Future<List<ManagedInstitutionProfile>>
      listManagedInstitutionProfiles() async {
    return await _apiClient.get<List<ManagedInstitutionProfile>>(
          '/admin/institutions',
          decodeData: (json) => _objectList(json)
              .map(ManagedInstitutionProfile.fromJson)
              .toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<ManagedInstitutionProfile> updateManagedInstitutionProfile(
    ManagedInstitutionProfileDraft draft,
  ) async {
    final result = await _apiClient.put<ManagedInstitutionProfile>(
      '/admin/institutions/${draft.id}',
      body: draft.toJson(),
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
  Future<List<ManagementProjectOption>> listManagementProjects() async {
    return await _apiClient.get<List<ManagementProjectOption>>(
          '/admin/projects',
          decodeData: (json) => _objectList(json)
              .map(ManagementProjectOption.fromJson)
              .toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<ManagementProjectOption> createManagementProject(
    ManagementProjectDraft draft,
  ) async {
    final result = await _apiClient.post<ManagementProjectOption>(
      '/admin/projects',
      body: draft.toJson(),
      decodeData: ManagementProjectOption.fromJson,
    );
    if (result == null) {
      throw const FormatException('项目响应为空');
    }
    return result;
  }

  @override
  Future<List<ManagedInstitutionProject>>
      listManagedInstitutionProjects() async {
    return await _apiClient.get<List<ManagedInstitutionProject>>(
          '/admin/institution-projects',
          decodeData: (json) => _objectList(json)
              .map(ManagedInstitutionProject.fromJson)
              .toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<ManagedInstitutionProject> createManagedInstitutionProject(
    ManagedInstitutionProjectDraft draft,
  ) async {
    final result = await _apiClient.post<ManagedInstitutionProject>(
      '/admin/institution-projects',
      body: draft.toJson(),
      decodeData: ManagedInstitutionProject.fromJson,
    );
    if (result == null) {
      throw const FormatException('机构项目响应为空');
    }
    return result;
  }

  @override
  Future<ManagedInstitutionProject> updateManagedInstitutionProject(
    ManagedInstitutionProjectDraft draft,
  ) async {
    final id = draft.id?.trim();
    if (id == null || id.isEmpty) {
      throw ArgumentError('机构项目 id 不能为空');
    }
    final result = await _apiClient.put<ManagedInstitutionProject>(
      '/admin/institution-projects/$id',
      body: draft.toJson(),
      decodeData: ManagedInstitutionProject.fromJson,
    );
    if (result == null) {
      throw const FormatException('机构项目响应为空');
    }
    return result;
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
  Future<List<ManagedDoctorProfile>> listManagedDoctorProfiles() async {
    return await _apiClient.get<List<ManagedDoctorProfile>>(
          '/admin/doctors',
          decodeData: (json) => _objectList(json)
              .map(ManagedDoctorProfile.fromJson)
              .toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<ManagedDoctorProfile> updateManagedDoctorProfile(
    ManagedDoctorProfileDraft draft,
  ) async {
    final result = await _apiClient.put<ManagedDoctorProfile>(
      '/admin/doctors/${draft.id}',
      body: draft.toJson(),
      decodeData: ManagedDoctorProfile.fromJson,
    );
    if (result == null) throw const FormatException('医生档案响应为空');
    return result;
  }

  @override
  Future<List<InstitutionOption>> listInstitutionOptions() async {
    return await _apiClient.get<List<InstitutionOption>>(
          '/discover/institutions',
          query: const {'offset': 0, 'limit': 100},
          decodeData: (json) => _objectList(json)
              .map(InstitutionOption.fromJson)
              .toList(growable: false),
        ) ??
        const [];
  }

  @override
  Future<List<InstitutionMembershipRequest>>
      listInstitutionMembershipRequests() async {
    return await _apiClient.get<List<InstitutionMembershipRequest>>(
          '/management/institution-membership-requests',
          decodeData: (json) => _objectList(json)
              .map(InstitutionMembershipRequest.fromJson)
              .toList(growable: false),
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
          decodeData: (json) => _objectList(json)
              .map(ProfessionalProjectRequest.fromJson)
              .toList(growable: false),
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
          decodeData: (json) => _objectList(json)
              .map(InstitutionProjectJoinRequest.fromJson)
              .toList(growable: false),
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
}

List<Object?> _objectList(Object? value) => value is List ? value : const [];

bool _isDoctorInstitutionRequest(Object? value) =>
    value is Map &&
    value['requestType']?.toString().trim().toUpperCase() == 'DOCTOR';
