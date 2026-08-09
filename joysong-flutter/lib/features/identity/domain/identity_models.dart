import 'dart:typed_data';

enum IdentityRoleType {
  doctor('DOCTOR', '医生'),
  consultant('CONSULTANT', '医美顾问'),
  institutionLegalRepresentative(
    'INSTITUTION_LEGAL_REPRESENTATIVE',
    '机构法人',
  ),
  unknown('UNKNOWN', '未知身份');

  const IdentityRoleType(this.code, this.label);

  final String code;
  final String label;

  static IdentityRoleType fromCode(Object? value) {
    final code = value?.toString().trim().toUpperCase();
    return IdentityRoleType.values.firstWhere(
      (role) => role.code == code,
      orElse: () => IdentityRoleType.unknown,
    );
  }
}

enum IdentityStatus {
  pending('PENDING', '审核中'),
  active('ACTIVE', '已认证'),
  rejected('REJECTED', '未通过'),
  revoked('REVOKED', '已撤销'),
  unknown('UNKNOWN', '未知状态');

  const IdentityStatus(this.code, this.label);

  final String code;
  final String label;

  static IdentityStatus fromCode(Object? value) {
    final code = value?.toString().trim().toUpperCase();
    return IdentityStatus.values.firstWhere(
      (status) => status.code == code,
      orElse: () => IdentityStatus.unknown,
    );
  }
}

enum IdentityDocumentType {
  businessLicense('BUSINESS_LICENSE', '营业执照'),
  idCardFront('ID_CARD_FRONT', '身份证人像面'),
  idCardBack('ID_CARD_BACK', '身份证国徽面'),
  idCardHandheld('ID_CARD_HANDHELD', '手持身份证照片'),
  doctorQualification('DOCTOR_QUALIFICATION', '医师资格证'),
  doctorPracticeCertificate('DOCTOR_PRACTICE_CERTIFICATE', '医师执业证'),
  consultantProof('CONSULTANT_PROOF', '医美顾问证明');

  const IdentityDocumentType(this.code, this.label);

  final String code;
  final String label;
}

final class IdentityRoleRecord {
  const IdentityRoleRecord({
    required this.role,
    required this.status,
    this.activatedAt,
    this.revokedAt,
  });

  factory IdentityRoleRecord.fromJson(Object? json) {
    final map = _jsonMap(json, '身份记录');
    return IdentityRoleRecord(
      role: IdentityRoleType.fromCode(map['roleCode']),
      status: IdentityStatus.fromCode(map['status']),
      activatedAt: _dateTime(map['activatedAt']),
      revokedAt: _dateTime(map['revokedAt']),
    );
  }

  final IdentityRoleType role;
  final IdentityStatus status;
  final DateTime? activatedAt;
  final DateTime? revokedAt;
}

final class IdentityApplication {
  const IdentityApplication({
    required this.id,
    required this.role,
    required this.status,
    this.reviewNote = '',
    this.submittedAt,
    this.reviewedAt,
  });

  factory IdentityApplication.fromJson(Object? json) {
    final map = _jsonMap(json, '身份申请');
    return IdentityApplication(
      id: _requiredText(map['id'], '申请 id'),
      role: IdentityRoleType.fromCode(map['roleCode']),
      status: IdentityStatus.fromCode(map['status']),
      reviewNote: map['reviewNote']?.toString() ?? '',
      submittedAt: _dateTime(map['submittedAt']),
      reviewedAt: _dateTime(map['reviewedAt']),
    );
  }

  final String id;
  final IdentityRoleType role;
  final IdentityStatus status;
  final String reviewNote;
  final DateTime? submittedAt;
  final DateTime? reviewedAt;
}

final class IdentityOverview {
  const IdentityOverview({this.roles = const [], this.applications = const []});

  factory IdentityOverview.fromJson(Object? json) {
    final map = _jsonMap(json, '身份概览');
    return IdentityOverview(
      roles: _objectList(map['roles'])
          .map(IdentityRoleRecord.fromJson)
          .toList(growable: false),
      applications: _objectList(map['applications'])
          .map(IdentityApplication.fromJson)
          .toList(growable: false),
    );
  }

  final List<IdentityRoleRecord> roles;
  final List<IdentityApplication> applications;

  bool get hasPendingApplication =>
      applications.any((item) => item.status == IdentityStatus.pending);
}

final class PrivateIdentityFile {
  const PrivateIdentityFile({
    required this.fileId,
    required this.purpose,
    required this.originalName,
    required this.contentType,
    required this.sizeBytes,
  });

  factory PrivateIdentityFile.fromJson(Object? json) {
    final map = _jsonMap(json, '私有认证文件');
    return PrivateIdentityFile(
      fileId: _requiredText(map['fileId'], 'fileId'),
      purpose: IdentityDocumentType.values.firstWhere(
        (type) => type.code == map['purpose']?.toString().toUpperCase(),
        orElse: () => throw const FormatException('未知认证材料类型'),
      ),
      originalName: map['originalName']?.toString() ?? '',
      contentType: _requiredText(map['contentType'], 'contentType'),
      sizeBytes: _integer(map['sizeBytes']),
    );
  }

  final String fileId;
  final IdentityDocumentType purpose;
  final String originalName;
  final String contentType;
  final int sizeBytes;
}

final class IdentityFileDraft {
  IdentityFileDraft({
    required Uint8List bytes,
    required this.fileName,
    required this.contentType,
    required this.purpose,
  }) : bytes = Uint8List.fromList(bytes);

  static const maxBytes = 10 * 1024 * 1024;

  final Uint8List bytes;
  final String fileName;
  final String contentType;
  final IdentityDocumentType purpose;

  void validate() {
    if (bytes.isEmpty || bytes.length > maxBytes) {
      throw ArgumentError('认证材料不能为空且不能超过 10MB');
    }
    const allowed = {
      'image/jpeg',
      'image/png',
      'image/webp',
      'application/pdf',
    };
    if (!allowed.contains(contentType.toLowerCase())) {
      throw ArgumentError('认证材料仅支持 JPG、PNG、WebP 或 PDF');
    }
    if (!_hasValidSignature(bytes, contentType.toLowerCase())) {
      throw ArgumentError('文件内容与扩展格式不匹配');
    }
  }
}

final class IdentityDocumentReference {
  const IdentityDocumentReference({required this.fileId, required this.type});

  final String fileId;
  final IdentityDocumentType type;

  Map<String, Object?> toJson() => {
        'fileId': fileId,
        'documentType': type.code,
      };
}

final class IdentityApplicationDraft {
  const IdentityApplicationDraft({
    required this.role,
    required this.applicationData,
    required this.documents,
  });

  final IdentityRoleType role;
  final Map<String, String> applicationData;
  final List<IdentityDocumentReference> documents;

  List<IdentityDocumentType> get requiredDocuments => switch (role) {
        IdentityRoleType.institutionLegalRepresentative => const [
            IdentityDocumentType.businessLicense,
            IdentityDocumentType.idCardFront,
            IdentityDocumentType.idCardBack,
          ],
        IdentityRoleType.doctor => const [
            IdentityDocumentType.idCardFront,
            IdentityDocumentType.idCardBack,
            IdentityDocumentType.idCardHandheld,
            IdentityDocumentType.doctorQualification,
            IdentityDocumentType.doctorPracticeCertificate,
          ],
        IdentityRoleType.consultant => const [
            IdentityDocumentType.idCardFront,
            IdentityDocumentType.idCardBack,
            IdentityDocumentType.consultantProof,
          ],
        IdentityRoleType.unknown => const [],
      };

  void validate() {
    if (role == IdentityRoleType.unknown) {
      throw ArgumentError('不支持申请该身份');
    }
    final requiredFields = switch (role) {
      IdentityRoleType.doctor => const [
          'realName',
          'idNumber',
          'hospitalName',
          'department',
          'title',
          'qualificationNo',
          'practiceNo',
          'reason',
        ],
      IdentityRoleType.consultant => const [
          'realName',
          'idNumber',
          'phone',
          'experience',
          'proofDescription',
          'reason',
        ],
      IdentityRoleType.institutionLegalRepresentative => const [
          'realName',
          'idNumber',
          'phone',
          'institutionName',
          'businessLicenseNo',
          'region',
          'address',
        ],
      IdentityRoleType.unknown => const <String>[],
    };
    for (final field in requiredFields) {
      if ((applicationData[field] ?? '').trim().isEmpty) {
        throw ArgumentError('请完整填写认证信息');
      }
    }
    if (!RegExp(r'^[0-9A-Za-z]{6,30}$')
        .hasMatch(applicationData['idNumber']!.trim())) {
      throw ArgumentError('证件号码格式不正确');
    }
    final uploaded = documents.map((document) => document.type).toSet();
    if (!uploaded.containsAll(requiredDocuments)) {
      throw ArgumentError('请完整上传认证材料');
    }
  }

  Map<String, Object?> toJson() => {
        'roleCode': role.code,
        'applicationData': applicationData,
        'documents': documents.map((document) => document.toJson()).toList(),
      };
}

final class ManagementContext {
  const ManagementContext({
    required this.userId,
    required this.platformRole,
    required this.activeRoles,
    required this.managedInstitutionIds,
    required this.visibleInstitutionIds,
    this.doctorId,
    this.canManageDoctors = false,
    this.canManageInstitutions = false,
    this.canManageInstitutionProjects = false,
    this.canManageArticles = false,
    this.canManageSplitConfigs = false,
    this.canManageOrders = false,
  });

  factory ManagementContext.fromJson(Object? json) {
    final map = _jsonMap(json, '管理上下文');
    return ManagementContext(
      userId: _requiredText(map['userId'], 'userId'),
      platformRole: _requiredText(map['platformRole'], 'platformRole'),
      activeRoles: _stringList(map['activeRoles']),
      doctorId: _nullableText(map['doctorId']),
      managedInstitutionIds: _stringList(map['managedInstitutionIds']),
      visibleInstitutionIds: _stringList(map['visibleInstitutionIds']),
      canManageDoctors: _boolean(map['canManageDoctors']),
      canManageInstitutions: _boolean(map['canManageInstitutions']),
      canManageInstitutionProjects:
          _boolean(map['canManageInstitutionProjects']),
      canManageArticles: _boolean(map['canManageArticles']),
      canManageSplitConfigs: _boolean(map['canManageSplitConfigs']),
      canManageOrders: _boolean(map['canManageOrders']),
    );
  }

  final String userId;
  final String platformRole;
  final List<String> activeRoles;
  final String? doctorId;
  final List<String> managedInstitutionIds;
  final List<String> visibleInstitutionIds;
  final bool canManageDoctors;
  final bool canManageInstitutions;
  final bool canManageInstitutionProjects;
  final bool canManageArticles;
  final bool canManageSplitConfigs;
  final bool canManageOrders;

  bool get hasAnyCapability =>
      canManageDoctors ||
      canManageInstitutions ||
      canManageInstitutionProjects ||
      canManageArticles ||
      canManageSplitConfigs ||
      canManageOrders;
}

Map<String, Object?> _jsonMap(Object? json, String label) {
  if (json is! Map) {
    throw FormatException('$label 响应不是 JSON 对象');
  }
  return json.map((key, value) => MapEntry(key.toString(), value));
}

List<Object?> _objectList(Object? value) => value is List ? value : const [];

List<String> _stringList(Object? value) => value is List
    ? value.map((item) => item.toString()).toList(growable: false)
    : const [];

String _requiredText(Object? value, String field) {
  final result = value?.toString().trim() ?? '';
  if (result.isEmpty) {
    throw FormatException('响应缺少 $field');
  }
  return result;
}

String? _nullableText(Object? value) {
  final result = value?.toString().trim();
  return result == null || result.isEmpty ? null : result;
}

DateTime? _dateTime(Object? value) {
  final text = value?.toString().trim();
  return text == null || text.isEmpty ? null : DateTime.tryParse(text);
}

int _integer(Object? value) => switch (value) {
      final int number => number,
      final num number => number.toInt(),
      _ => int.tryParse(value?.toString() ?? '') ?? 0,
    };

bool _boolean(Object? value) => value == true;

bool _hasValidSignature(Uint8List bytes, String contentType) {
  if (bytes.length < 12) {
    return false;
  }
  return switch (contentType) {
    'image/jpeg' => bytes[0] == 0xff && bytes[1] == 0xd8 && bytes[2] == 0xff,
    'image/png' => bytes[0] == 0x89 &&
        bytes[1] == 0x50 &&
        bytes[2] == 0x4e &&
        bytes[3] == 0x47,
    'image/webp' => String.fromCharCodes(bytes.take(4)) == 'RIFF' &&
        String.fromCharCodes(bytes.skip(8).take(4)) == 'WEBP',
    'application/pdf' => String.fromCharCodes(bytes.take(5)) == '%PDF-',
    _ => false,
  };
}
