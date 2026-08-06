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
}
