import 'dart:convert';
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
  pending('PENDING', '审核中', 'Pending review'),
  approved('APPROVED', '已通过', 'Approved'),
  active('ACTIVE', '已认证', 'Verified'),
  rejected('REJECTED', '未通过', 'Rejected'),
  withdrawn('WITHDRAWN', '已撤回', 'Withdrawn'),
  revoked('REVOKED', '已撤销', 'Revoked'),
  unknown('UNKNOWN', '未知状态', 'Unknown status');

  const IdentityStatus(this.code, this.label, this.englishLabel);

  final String code;
  final String label;
  final String englishLabel;

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
    if (role == IdentityRoleType.doctor &&
        applicationData.containsKey('hospitalName')) {
      throw ArgumentError('医生身份申请不得填写执业机构');
    }
    final requiredFields = switch (role) {
      IdentityRoleType.doctor => const [
          'realName',
          'idNumber',
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
    this.doctorInstitutionIds = const [],
    this.consultantInstitutionIds = const [],
    this.doctorId,
    this.canManageDoctors = false,
    this.canManageInstitutions = false,
    this.canManageInstitutionProjects = false,
    this.canManageArticles = false,
    this.canManageSplitConfigs = false,
    this.canManageOrders = false,
    this.canApplyToInstitutions = false,
    this.canReviewInstitutionRequests = false,
    this.canSubmitPlatformProjectRequests = false,
    this.canSubmitInstitutionProjectRequests = false,
    this.canReviewInstitutionProjectRequests = false,
    this.canViewAffiliations = false,
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
      doctorInstitutionIds: _stringList(map['doctorInstitutionIds']),
      consultantInstitutionIds: _optionalWireStringList(
        map,
        'consultantInstitutionIds',
        '顾问机构列表',
      ),
      canManageDoctors: _boolean(map['canManageDoctors']),
      canManageInstitutions: _boolean(map['canManageInstitutions']),
      canManageInstitutionProjects:
          _boolean(map['canManageInstitutionProjects']),
      canManageArticles: _boolean(map['canManageArticles']),
      canManageSplitConfigs: _boolean(map['canManageSplitConfigs']),
      canManageOrders: _boolean(map['canManageOrders']),
      canApplyToInstitutions: _boolean(map['canApplyToInstitutions']),
      canReviewInstitutionRequests:
          _boolean(map['canReviewInstitutionRequests']),
      canSubmitPlatformProjectRequests:
          _boolean(map['canSubmitPlatformProjectRequests']),
      canSubmitInstitutionProjectRequests:
          _boolean(map['canSubmitInstitutionProjectRequests']),
      canReviewInstitutionProjectRequests:
          _boolean(map['canReviewInstitutionProjectRequests']),
      canViewAffiliations: _boolean(map['canViewAffiliations']),
    );
  }

  final String userId;
  final String platformRole;
  final List<String> activeRoles;
  final String? doctorId;
  final List<String> managedInstitutionIds;
  final List<String> visibleInstitutionIds;
  final List<String> doctorInstitutionIds;
  final List<String> consultantInstitutionIds;
  final bool canManageDoctors;
  final bool canManageInstitutions;
  final bool canManageInstitutionProjects;
  final bool canManageArticles;
  final bool canManageSplitConfigs;
  final bool canManageOrders;
  final bool canApplyToInstitutions;
  final bool canReviewInstitutionRequests;
  final bool canSubmitPlatformProjectRequests;
  final bool canSubmitInstitutionProjectRequests;
  final bool canReviewInstitutionProjectRequests;
  final bool canViewAffiliations;

  bool get hasAnyCapability =>
      canManageDoctors ||
      canManageInstitutions ||
      canManageInstitutionProjects ||
      canManageArticles ||
      canManageSplitConfigs ||
      canManageOrders ||
      canApplyToInstitutions ||
      canReviewInstitutionRequests ||
      canSubmitPlatformProjectRequests ||
      canSubmitInstitutionProjectRequests ||
      canReviewInstitutionProjectRequests ||
      canViewAffiliations;
}

final class ManagedInstitutionSummary {
  const ManagedInstitutionSummary({
    required this.id,
    required this.name,
    this.city = '',
    this.address = '',
    this.coverImage = '',
    this.isVerified = false,
    this.rating = 0,
    this.reviewCount = 0,
    this.projectCount = 0,
    this.doctorCount = 0,
  });

  factory ManagedInstitutionSummary.fromJson(Object? json) {
    final map = _jsonMap(json, '机构摘要');
    return ManagedInstitutionSummary(
      id: _requiredText(map['id'], '机构 id'),
      name: map['name']?.toString() ?? '',
      city: _normalizeCity(map['city']?.toString() ?? ''),
      address: map['address']?.toString() ?? '',
      coverImage: map['coverImage']?.toString() ?? '',
      isVerified: _boolean(map['isVerified']),
      rating: _decimal(map['rating']),
      reviewCount: _integer(map['reviewCount']),
      projectCount: _integer(map['projectCount']),
      doctorCount: _integer(map['doctorCount']),
    );
  }

  final String id;
  final String name;
  final String city;
  final String address;
  final String coverImage;
  final bool isVerified;
  final num rating;
  final int reviewCount;
  final int projectCount;
  final int doctorCount;
}

final class ManagedInstitutionProfile {
  const ManagedInstitutionProfile({
    required this.id,
    required this.name,
    this.address = '',
    this.city = '',
    this.description = '',
    this.coverImage = '',
    this.images = const [],
    this.establishedYear,
    this.credentials = '',
    this.credentialImages = const [],
    this.specialties = const [],
    this.tags = const [],
    this.contactPhone = '',
    this.businessHours = '',
    this.rating = 0,
    this.reviewCount = 0,
    this.isVerified = false,
    this.certificationTime,
    this.projectCount = 0,
    this.doctorCount = 0,
    this.consultationCount = 0,
    this.userCount = 0,
    this.caseCount = 0,
    this.createdAt,
    this.updatedAt,
  });

  factory ManagedInstitutionProfile.fromJson(Object? json) {
    final map = _jsonMap(json, '机构档案');
    return ManagedInstitutionProfile(
      id: _requiredText(map['id'], '机构 id'),
      name: map['name']?.toString() ?? '',
      address: map['address']?.toString() ?? '',
      city: _normalizeCity(map['city']?.toString() ?? ''),
      description: map['description']?.toString() ?? '',
      coverImage: map['coverImage']?.toString() ?? '',
      images: _stringList(map['images']),
      establishedYear: _nullableInteger(map['establishedYear']),
      credentials: map['credentials']?.toString() ?? '',
      credentialImages: _stringList(map['credentialImages']),
      specialties: _stringList(map['specialties']),
      tags: _stringList(map['tags']),
      contactPhone: map['contactPhone']?.toString() ?? '',
      businessHours: map['businessHours']?.toString() ?? '',
      rating: _decimal(map['rating']),
      reviewCount: _integer(map['reviewCount']),
      isVerified: _boolean(map['isVerified']),
      certificationTime: _dateTime(map['certificationTime']),
      projectCount: _integer(map['projectCount']),
      doctorCount: _integer(map['doctorCount']),
      consultationCount: _integer(map['consultationCount']),
      userCount: _integer(map['userCount']),
      caseCount: _integer(map['caseCount']),
      createdAt: _dateTime(map['createdAt']),
      updatedAt: _dateTime(map['updatedAt']),
    );
  }

  final String id;
  final String name;
  final String address;
  final String city;
  final String description;
  final String coverImage;
  final List<String> images;
  final int? establishedYear;
  final String credentials;
  final List<String> credentialImages;
  final List<String> specialties;
  final List<String> tags;
  final String contactPhone;
  final String businessHours;
  final num rating;
  final int reviewCount;
  final bool isVerified;
  final DateTime? certificationTime;
  final int projectCount;
  final int doctorCount;
  final int consultationCount;
  final int userCount;
  final int caseCount;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  ManagedInstitutionProfileUpdate toUpdate() =>
      ManagedInstitutionProfileUpdate.fromProfile(this);
}

final class ManagedInstitutionProfileUpdate {
  const ManagedInstitutionProfileUpdate({
    required this.name,
    required this.address,
    required this.city,
    required this.description,
    required this.coverImage,
    required this.images,
    required this.establishedYear,
    required this.credentials,
    required this.credentialImages,
    required this.specialties,
    required this.tags,
    required this.contactPhone,
    required this.businessHours,
  });

  factory ManagedInstitutionProfileUpdate.fromProfile(
    ManagedInstitutionProfile profile,
  ) =>
      ManagedInstitutionProfileUpdate(
        name: profile.name,
        address: profile.address,
        city: profile.city,
        description: profile.description,
        coverImage: profile.coverImage,
        images: profile.images,
        establishedYear: profile.establishedYear,
        credentials: profile.credentials,
        credentialImages: profile.credentialImages,
        specialties: profile.specialties,
        tags: profile.tags,
        contactPhone: profile.contactPhone,
        businessHours: profile.businessHours,
      );

  final String name;
  final String address;
  final String city;
  final String description;
  final String coverImage;
  final List<String> images;
  final int? establishedYear;
  final String credentials;
  final List<String> credentialImages;
  final List<String> specialties;
  final List<String> tags;
  final String contactPhone;
  final String businessHours;

  ManagedInstitutionProfileUpdate copyWith({
    String? name,
    String? address,
    String? city,
    String? description,
    String? coverImage,
    List<String>? images,
    int? establishedYear,
    bool clearEstablishedYear = false,
    String? credentials,
    List<String>? credentialImages,
    List<String>? specialties,
    List<String>? tags,
    String? contactPhone,
    String? businessHours,
  }) =>
      ManagedInstitutionProfileUpdate(
        name: name ?? this.name,
        address: address ?? this.address,
        city: city ?? this.city,
        description: description ?? this.description,
        coverImage: coverImage ?? this.coverImage,
        images: images ?? this.images,
        establishedYear: clearEstablishedYear
            ? null
            : establishedYear ?? this.establishedYear,
        credentials: credentials ?? this.credentials,
        credentialImages: credentialImages ?? this.credentialImages,
        specialties: specialties ?? this.specialties,
        tags: tags ?? this.tags,
        contactPhone: contactPhone ?? this.contactPhone,
        businessHours: businessHours ?? this.businessHours,
      );

  Map<String, Object?> toJson() => {
        'name': name.trim(),
        'address': address.trim(),
        'city': _normalizeCity(city),
        'description': description.trim(),
        'coverImage': coverImage.trim(),
        'images': _normalizedStrings(images),
        'establishedYear': establishedYear,
        'credentials': credentials.trim(),
        'credentialImages': _normalizedStrings(credentialImages),
        'specialties': _normalizedStrings(specialties),
        'tags': _normalizedStrings(tags),
        'contactPhone': contactPhone.trim(),
        'businessHours': businessHours.trim(),
      };
}

final class DoctorInstitutionSummary {
  const DoctorInstitutionSummary({required this.id, required this.name});

  factory DoctorInstitutionSummary.fromJson(Object? json) {
    final map = _jsonMap(json, '医生机构摘要');
    return DoctorInstitutionSummary(
      id: _requiredText(map['id'], '机构 id'),
      name: _requiredText(map['name'], '机构名称'),
    );
  }

  final String id;
  final String name;
}

final class DoctorSelfProfile {
  const DoctorSelfProfile({
    required this.id,
    required this.userId,
    required this.name,
    this.title = '',
    this.bio = '',
    this.avatar = '',
    this.contactPhone = '',
    this.specialties = '',
    this.credentials = '',
    this.credentialImages = '',
    this.certificationTags = '',
    this.institutionId = '',
    this.institutionName = '',
    this.institutions = const [],
    this.primaryInstitution,
    this.institutionCount = 0,
    this.rating = 0,
    this.reviewCount = 0,
    this.isVerified = false,
    this.consultationCount = 0,
    this.caseCount = 0,
  });

  factory DoctorSelfProfile.fromJson(Object? json) {
    final map = _jsonMap(json, '医生本人档案');
    final primaryInstitution = map['primaryInstitution'];
    return DoctorSelfProfile(
      id: _requiredText(map['id'], '医生 id'),
      userId: _requiredText(map['userId'], '用户 id'),
      name: _requiredText(map['name'], '医生姓名'),
      title: map['title']?.toString() ?? '',
      bio: map['bio']?.toString() ?? '',
      avatar: map['avatar']?.toString() ?? '',
      contactPhone: map['contactPhone']?.toString() ?? '',
      specialties: map['specialties']?.toString() ?? '',
      credentials: map['credentials']?.toString() ?? '',
      credentialImages: map['credentialImages']?.toString() ?? '',
      certificationTags: map['certificationTags']?.toString() ?? '',
      institutionId: map['institutionId']?.toString() ?? '',
      institutionName: map['institutionName']?.toString() ?? '',
      institutions: _objectList(map['institutions'])
          .map(DoctorInstitutionSummary.fromJson)
          .toList(growable: false),
      primaryInstitution: primaryInstitution == null
          ? null
          : DoctorInstitutionSummary.fromJson(primaryInstitution),
      institutionCount: _integer(map['institutionCount']),
      rating: _decimal(map['rating']),
      reviewCount: _integer(map['reviewCount']),
      isVerified: _boolean(map['isVerified']),
      consultationCount: _integer(map['consultationCount']),
      caseCount: _integer(map['caseCount']),
    );
  }

  final String id;
  final String userId;
  final String name;
  final String title;
  final String bio;
  final String avatar;
  final String contactPhone;
  final String specialties;
  final String credentials;
  final String credentialImages;
  final String certificationTags;
  final String institutionId;
  final String institutionName;
  final List<DoctorInstitutionSummary> institutions;
  final DoctorInstitutionSummary? primaryInstitution;
  final int institutionCount;
  final num rating;
  final int reviewCount;
  final bool isVerified;
  final int consultationCount;
  final int caseCount;

  DoctorSelfProfileUpdate toUpdate() => DoctorSelfProfileUpdate(
        name: name,
        title: title,
        bio: bio,
        avatar: avatar,
        contactPhone: contactPhone,
        specialties: specialties,
        credentials: credentials,
        credentialImages: credentialImages,
        certificationTags: certificationTags,
      );
}

final class DoctorSelfProfileUpdate {
  const DoctorSelfProfileUpdate({
    required this.name,
    required this.title,
    required this.bio,
    required this.avatar,
    required this.contactPhone,
    required this.specialties,
    required this.credentials,
    required this.credentialImages,
    required this.certificationTags,
  });

  final String name;
  final String title;
  final String bio;
  final String avatar;
  final String contactPhone;
  final String specialties;
  final String credentials;
  final String credentialImages;
  final String certificationTags;

  Map<String, Object?> toJson() => {
        'name': name.trim(),
        'title': title.trim(),
        'bio': bio.trim(),
        'avatar': avatar.trim(),
        'contactPhone': contactPhone.trim(),
        'specialties': _csvText(specialties),
        'credentials': credentials.trim(),
        'credentialImages': _csvText(credentialImages),
        'certificationTags': _csvText(certificationTags),
      };
}

final class ManagementProjectOption {
  const ManagementProjectOption({
    required this.id,
    required this.name,
    this.category = '',
    this.description = '',
    this.tags = '',
    this.categoryTags = '',
    this.coverImage = '',
    this.referencePrice = 0,
    this.currency = '',
    this.slogan = '',
    this.detailContent,
    this.images = const [],
    this.salesCount = 0,
  });

  factory ManagementProjectOption.fromJson(Object? json) {
    final map = _jsonMap(json, '项目');
    return ManagementProjectOption(
      id: _requiredText(map['id'], '项目 id'),
      name: _requiredText(map['name'], '项目名称'),
      category: map['category']?.toString() ?? '',
      description: map['description']?.toString() ?? '',
      tags: map['tags']?.toString() ?? '',
      categoryTags: _csvText(map['categoryTags']),
      coverImage: map['coverImage']?.toString() ?? '',
      referencePrice: _decimal(map['referencePrice']),
      currency: _requiredText(map['currency'], '币种'),
      slogan: map['slogan']?.toString() ?? '',
      detailContent: _nullableText(map['detailContent']),
      images: _jsonStringList(map['images']),
      salesCount: _integer(map['salesCount']),
    );
  }

  final String id;
  final String name;
  final String category;
  final String description;
  final String tags;
  final String categoryTags;
  final String coverImage;
  final num referencePrice;
  final String currency;
  final String slogan;
  final String? detailContent;
  final List<String> images;
  final int salesCount;
}

final class ManagementProjectDraft {
  const ManagementProjectDraft({
    required this.name,
    this.category = '',
    this.description = '',
    this.tags = '',
    this.coverImage = '',
    this.referencePrice = 0,
  });

  final String name;
  final String category;
  final String description;
  final String tags;
  final String coverImage;
  final num referencePrice;

  Map<String, Object?> toJson() => {
        'name': name.trim(),
        'category': category.trim(),
        'description': description.trim(),
        'tags': tags.trim(),
        'coverImage': coverImage.trim(),
        'referencePrice': referencePrice,
      };
}

final class ManagedInstitutionProject {
  const ManagedInstitutionProject({
    required this.id,
    required this.institutionId,
    required this.projectId,
    required this.effectiveName,
    this.name = '',
    this.category = '',
    this.description = '',
    this.tags = '',
    this.slogan = '',
    this.detailContent = '',
    this.price = 0,
    this.originalPrice,
    this.coverImage = '',
    this.images = '',
    this.isActive = true,
    this.doctorIds = const <String>{},
  });

  factory ManagedInstitutionProject.fromJson(Object? json) {
    final map = _jsonMap(json, '机构项目');
    return ManagedInstitutionProject(
      id: _requiredText(map['id'], '机构项目 id'),
      institutionId: _requiredText(map['institutionId'], '机构 id'),
      projectId: _requiredText(map['projectId'], '项目 id'),
      effectiveName: map['effectiveName']?.toString() ??
          map['projectName']?.toString() ??
          map['name']?.toString() ??
          '',
      name: map['name']?.toString() ?? '',
      category: map['category']?.toString() ?? '',
      description: map['description']?.toString() ?? '',
      tags: map['tags']?.toString() ?? '',
      slogan: map['slogan']?.toString() ?? '',
      detailContent: map['detailContent']?.toString() ?? '',
      price: _decimal(map['price']),
      originalPrice: _nullableDecimal(map['originalPrice']),
      coverImage: map['coverImage']?.toString() ?? '',
      images: map['images']?.toString() ?? '',
      isActive: map['isActive'] != false,
      doctorIds: _objectList(map['doctors'])
          .map((doctor) => _jsonMap(doctor, '项目医生'))
          .map((doctor) => _requiredText(doctor['id'], '医生 id'))
          .toSet(),
    );
  }

  final String id;
  final String institutionId;
  final String projectId;
  final String effectiveName;
  final String name;
  final String category;
  final String description;
  final String tags;
  final String slogan;
  final String detailContent;
  final num price;
  final num? originalPrice;
  final String coverImage;
  final String images;
  final bool isActive;
  final Set<String> doctorIds;

  bool hasDoctor(String? doctorId) {
    final id = doctorId?.trim() ?? '';
    return id.isNotEmpty && doctorIds.contains(id);
  }

  ManagedInstitutionProjectDraft toDraft() => ManagedInstitutionProjectDraft(
        id: id,
        institutionId: institutionId,
        projectId: projectId,
        name: name,
        category: category,
        description: description,
        tags: tags,
        slogan: slogan,
        detailContent: detailContent,
        price: price,
        originalPrice: originalPrice,
        coverImage: coverImage,
        images: images,
        isActive: isActive,
      );
}

final class ManagedInstitutionProjectDraft {
  const ManagedInstitutionProjectDraft({
    this.id,
    required this.institutionId,
    required this.projectId,
    this.name = '',
    this.category = '',
    this.description = '',
    this.tags = '',
    this.slogan = '',
    this.detailContent = '',
    this.price = 0,
    this.originalPrice,
    this.coverImage = '',
    this.images = '',
    this.isActive = true,
  });

  final String? id;
  final String institutionId;
  final String projectId;
  final String name;
  final String category;
  final String description;
  final String tags;
  final String slogan;
  final String detailContent;
  final num price;
  final num? originalPrice;
  final String coverImage;
  final String images;
  final bool isActive;

  Map<String, Object?> toJson({String? doctorId}) => {
        'institutionId': institutionId,
        'projectId': projectId,
        'name': _nullableTrimmed(name),
        'category': _nullableTrimmed(category),
        'description': _nullableTrimmed(description),
        'tags': _nullableTrimmed(tags),
        'slogan': _nullableTrimmed(slogan),
        'detailContent': _nullableTrimmed(detailContent),
        'price': price,
        'originalPrice': originalPrice,
        'coverImage': coverImage.trim(),
        'images': images.trim(),
        'isActive': isActive,
        if (doctorId != null && doctorId.trim().isNotEmpty)
          'doctorBindings': [
            {'doctorId': doctorId.trim()}
          ],
      };
}

final class SplitConfigProposalDraft {
  const SplitConfigProposalDraft({
    required this.doctorId,
    required this.institutionProjectId,
    this.consultationFee = 0,
    this.commissionRate = 0,
    this.institutionRate = 40,
    this.proposerSide = 'DOCTOR',
  });

  final String doctorId;
  final String institutionProjectId;
  final num consultationFee;
  final num commissionRate;
  final num institutionRate;
  final String proposerSide;

  bool get canSubmit =>
      doctorId.trim().isNotEmpty && institutionProjectId.trim().isNotEmpty;

  Map<String, Object?> toJson() => {
        'doctorId': doctorId.trim(),
        'institutionProjectId': institutionProjectId.trim(),
        'consultationFee': consultationFee,
        'commissionRate': commissionRate,
        'institutionRate': institutionRate,
        'proposerSide': proposerSide,
      };
}

Map<String, Object?> _jsonMap(Object? json, String label) {
  if (json is! Map) {
    throw FormatException('$label 响应不是 JSON 对象');
  }
  return json.map((key, value) => MapEntry(key.toString(), value));
}

List<Object?> _objectList(Object? value) => value is List ? value : const [];

List<String> _stringList(Object? value) {
  if (value is List) {
    return value
        .map((item) => item.toString().trim())
        .where((item) => item.isNotEmpty)
        .toList(growable: false);
  }
  if (value is String) {
    return value
        .split(',')
        .map((item) => item.trim())
        .where((item) => item.isNotEmpty)
        .toList(growable: false);
  }
  return const [];
}

List<String> _jsonStringList(Object? value) {
  if (value is! String) return _stringList(value);
  final normalized = value.trim();
  if (normalized.isEmpty) return const [];
  try {
    return _stringList(jsonDecode(normalized));
  } on FormatException {
    return _stringList(normalized);
  }
}

List<String> _optionalWireStringList(
  Map<String, Object?> map,
  String key,
  String field,
) {
  if (!map.containsKey(key)) return const [];
  final value = map[key];
  if (value is! List) throw FormatException('响应包含无效的 $field');
  return value.map((item) {
    if (item is! String || item.trim().isEmpty) {
      throw FormatException('响应包含无效的 $field');
    }
    return item.trim();
  }).toList(growable: false);
}

List<String> _normalizedStrings(Iterable<String> values) => values
    .map((value) => value.trim())
    .where((value) => value.isNotEmpty)
    .toList(growable: false);

final class InstitutionOption {
  const InstitutionOption({required this.id, required this.name});

  factory InstitutionOption.fromJson(Object? json) {
    final map = _jsonMap(json, '机构');
    return InstitutionOption(
      id: _requiredText(map['id'], '机构 id'),
      name: _requiredText(map['name'], '机构名称'),
    );
  }

  final String id;
  final String name;
}

enum InstitutionMembershipRequestType {
  doctor('DOCTOR'),
  consultant('CONSULTANT');

  const InstitutionMembershipRequestType(this.code);

  final String code;

  static InstitutionMembershipRequestType fromCode(Object? value) =>
      _protocolEnum(
        value,
        InstitutionMembershipRequestType.values,
        (item) => item.code,
        'requestType',
      );
}

enum InstitutionMembershipAction {
  join('JOIN'),
  leave('LEAVE');

  const InstitutionMembershipAction(this.code);

  final String code;

  static InstitutionMembershipAction fromCode(Object? value) => _protocolEnum(
        value,
        InstitutionMembershipAction.values,
        (item) => item.code,
        'action',
      );
}

enum InstitutionMembershipRequestStatus {
  pending('PENDING'),
  approved('APPROVED'),
  rejected('REJECTED'),
  withdrawn('WITHDRAWN');

  const InstitutionMembershipRequestStatus(this.code);

  final String code;

  static InstitutionMembershipRequestStatus fromCode(Object? value) =>
      _protocolEnum(
        value,
        InstitutionMembershipRequestStatus.values,
        (item) => item.code,
        'status',
      );
}

enum InstitutionMembershipDecision {
  approved('APPROVED'),
  rejected('REJECTED');

  const InstitutionMembershipDecision(this.code);

  final String code;

  static InstitutionMembershipDecision fromCode(Object? value) => _protocolEnum(
        value,
        InstitutionMembershipDecision.values,
        (item) => item.code,
        'decision',
      );
}

enum InstitutionRelationshipStatus {
  approved('APPROVED'),
  none('NONE');

  const InstitutionRelationshipStatus(this.code);

  final String code;

  static InstitutionRelationshipStatus fromCode(Object? value) => _protocolEnum(
        value,
        InstitutionRelationshipStatus.values,
        (item) => item.code,
        'relationshipStatus',
      );
}

final class InstitutionMembershipRequest {
  const InstitutionMembershipRequest({
    required this.id,
    required this.requestType,
    required this.applicantId,
    required this.applicantName,
    required this.institutionId,
    required this.institutionName,
    required this.action,
    required this.status,
    required this.relationshipStatus,
    required this.requestNote,
    required this.reviewNote,
    required this.submittedBy,
    required this.submittedAt,
    required this.createdAt,
    required this.updatedAt,
    this.reviewedBy,
    this.reviewedAt,
  });

  factory InstitutionMembershipRequest.fromJson(Object? json) {
    final map = _jsonMap(json, '机构加入申请');
    return InstitutionMembershipRequest(
      id: _requiredWireText(map['id'], '申请 id'),
      requestType: InstitutionMembershipRequestType.fromCode(
        map['requestType'],
      ),
      applicantId: _requiredWireText(map['applicantId'], '申请人 id'),
      applicantName: _requiredWireText(map['applicantName'], '申请人名称'),
      institutionId: _requiredWireText(map['institutionId'], '机构'),
      institutionName: _requiredWireText(map['institutionName'], '机构名称'),
      action: InstitutionMembershipAction.fromCode(map['action']),
      status: InstitutionMembershipRequestStatus.fromCode(map['status']),
      relationshipStatus:
          InstitutionRelationshipStatus.fromCode(map['relationshipStatus']),
      requestNote: _requiredWireString(map, 'requestNote', '申请说明'),
      reviewNote: _requiredWireString(map, 'reviewNote', '审核意见'),
      submittedBy: _requiredWireText(map['submittedBy'], '提交人'),
      reviewedBy: _nullableWireString(map, 'reviewedBy', '审核人'),
      submittedAt: _requiredWireDateTime(map['submittedAt'], '提交时间'),
      reviewedAt: _nullableWireDateTime(map, 'reviewedAt', '审核时间'),
      createdAt: _requiredWireDateTime(map['createdAt'], '创建时间'),
      updatedAt: _requiredWireDateTime(map['updatedAt'], '更新时间'),
    );
  }

  final String id;
  final InstitutionMembershipRequestType requestType;
  final String applicantId;
  final String applicantName;
  final String institutionId;
  final String institutionName;
  final InstitutionMembershipAction action;
  final InstitutionMembershipRequestStatus status;
  final InstitutionRelationshipStatus relationshipStatus;
  final String requestNote;
  final String reviewNote;
  final String submittedBy;
  final String? reviewedBy;
  final DateTime submittedAt;
  final DateTime? reviewedAt;
  final DateTime createdAt;
  final DateTime updatedAt;
}

final class InstitutionMembershipRequestDraft {
  const InstitutionMembershipRequestDraft({
    required this.requestType,
    required this.action,
    required this.institutionId,
    this.requestNote = '',
  });

  final InstitutionMembershipRequestType requestType;
  final InstitutionMembershipAction action;
  final String institutionId;
  final String requestNote;

  Map<String, Object?> toJson() => {
        'requestType': requestType.code,
        'action': action.code,
        'institutionId': institutionId.trim(),
        'requestNote': requestNote.trim(),
      };
}

final class InstitutionMembershipCandidate {
  const InstitutionMembershipCandidate({required this.id, required this.name});

  factory InstitutionMembershipCandidate.fromJson(Object? json) {
    final map = _jsonMap(json, '候选机构');
    return InstitutionMembershipCandidate(
      id: _requiredWireText(map['id'], '机构 id'),
      name: _requiredWireText(map['name'], '机构名称'),
    );
  }

  final String id;
  final String name;
}

final class InstitutionMembershipCandidatePage {
  const InstitutionMembershipCandidatePage({
    required this.items,
    required this.offset,
    required this.limit,
    required this.hasMore,
  });

  factory InstitutionMembershipCandidatePage.fromJson(Object? json) {
    final map = _jsonMap(json, '候选机构分页');
    final rawItems = map['items'];
    if (rawItems is! List) {
      throw const FormatException('响应缺少候选机构列表');
    }
    return InstitutionMembershipCandidatePage(
      items: rawItems
          .map(InstitutionMembershipCandidate.fromJson)
          .toList(growable: false),
      offset: _requiredWireInteger(map['offset'], 'offset'),
      limit: _requiredWireInteger(map['limit'], 'limit'),
      hasMore: _requiredWireBoolean(map['hasMore'], 'hasMore'),
    );
  }

  final List<InstitutionMembershipCandidate> items;
  final int offset;
  final int limit;
  final bool hasMore;
}

final class DoctorInstitutionChangeRequest {
  const DoctorInstitutionChangeRequest({
    required this.id,
    required this.requestType,
    required this.userId,
    required this.institutionId,
    required this.status,
    required this.action,
    this.requestNote = '',
    this.reviewNote = '',
    this.doctorName = '',
    this.institutionName = '',
    this.createdAt = '',
    this.updatedAt = '',
    this.submittedAt = '',
    this.reviewedAt = '',
    this.deleted = false,
  });

  factory DoctorInstitutionChangeRequest.fromJson(Object? json) {
    final map = _jsonMap(json, '医生机构关系申请');
    return DoctorInstitutionChangeRequest(
      id: _requiredText(map['id'], '申请 id'),
      requestType: _requiredText(map['requestType'], '申请类型'),
      userId: _requiredText(map['userId'], '申请人'),
      institutionId: _requiredText(map['institutionId'], '机构'),
      status: _requiredText(map['status'], '申请状态'),
      action: _requiredText(map['action'], '关系操作'),
      requestNote: map['requestNote']?.toString() ?? '',
      reviewNote: map['reviewNote']?.toString() ?? '',
      doctorName: map['doctorName']?.toString() ?? '',
      institutionName: map['institutionName']?.toString() ?? '',
      createdAt: map['createdAt']?.toString() ?? '',
      updatedAt: map['updatedAt']?.toString() ?? '',
      submittedAt: map['submittedAt']?.toString() ?? '',
      reviewedAt: map['reviewedAt']?.toString() ?? '',
      deleted: _boolean(map['deleted']),
    );
  }

  factory DoctorInstitutionChangeRequest.fromMembershipRequest(
    InstitutionMembershipRequest request,
  ) =>
      DoctorInstitutionChangeRequest(
        id: request.id,
        requestType: request.requestType.code,
        userId: request.applicantId,
        institutionId: request.institutionId,
        status: request.status.code,
        action: request.action.code,
        requestNote: request.requestNote,
        reviewNote: request.reviewNote,
        doctorName: request.applicantName,
        institutionName: request.institutionName,
        createdAt: request.createdAt.toIso8601String(),
        updatedAt: request.updatedAt.toIso8601String(),
        submittedAt: request.submittedAt.toIso8601String(),
        reviewedAt: request.reviewedAt?.toIso8601String() ?? '',
      );

  final String id;
  final String requestType;
  final String userId;
  final String institutionId;
  final String status;
  final String action;
  final String requestNote;
  final String reviewNote;
  final String doctorName;
  final String institutionName;
  final String createdAt;
  final String updatedAt;
  final String submittedAt;
  final String reviewedAt;
  final bool deleted;
}

final class DoctorInstitutionChangeRequestDraft {
  const DoctorInstitutionChangeRequestDraft({
    required this.institutionId,
    required this.action,
    this.requestNote = '',
  });

  final String institutionId;
  final String action;
  final String requestNote;

  InstitutionMembershipRequestDraft toNormalizedDraft() =>
      InstitutionMembershipRequestDraft(
        requestType: InstitutionMembershipRequestType.doctor,
        action: InstitutionMembershipAction.fromCode(action),
        institutionId: institutionId,
        requestNote: requestNote,
      );

  Map<String, Object?> toJson() => toNormalizedDraft().toJson();
}

const _professionalProjectRequestResponseKeys = <String>{
  'id',
  'requestType',
  'doctorId',
  'doctorName',
  'institutionId',
  'institutionName',
  'projectId',
  'projectName',
  'name',
  'category',
  'description',
  'tags',
  'slogan',
  'detailContent',
  'currency',
  'coverImage',
  'images',
  'salesCount',
  'referencePrice',
  'categoryTags',
  'price',
  'originalPrice',
  'isActive',
  'institutionSplit',
  'notes',
  'status',
  'reviewNote',
  'reviewedBy',
  'reviewedAt',
  'resultingProjectId',
  'resultingInstitutionProjectId',
  'submittedAt',
  'updatedAt',
};

void _requireProfessionalProjectRequestKeys(Map<String, Object?> map) {
  for (final key in _professionalProjectRequestResponseKeys) {
    if (!map.containsKey(key)) {
      throw FormatException('项目申请响应缺少 $key');
    }
  }
}

String? _nullableProfessionalSnapshotText(
  Map<String, Object?> map,
  String key,
  String field,
) {
  final value = map[key];
  if (value == null) return null;
  if (value is! String) throw FormatException('响应包含无效的 $field');
  return value;
}

String _requiredProfessionalSnapshotText(
  Map<String, Object?> map,
  String key,
  String field,
) {
  final value = map[key];
  if (value is! String) throw FormatException('响应包含无效的 $field');
  return value;
}

List<String>? _nullableProfessionalSnapshotItems(
  Map<String, Object?> map,
  String key,
  String field,
) {
  final value = map[key];
  if (value == null) return null;
  if (value is! List || value.any((item) => item is! String)) {
    throw FormatException('响应包含无效的 $field');
  }
  final items = value.cast<String>().toList(growable: false);
  if (items.any((item) => item.trim().isEmpty)) {
    throw FormatException('响应包含无效的 $field');
  }
  return items;
}

num _requiredProfessionalSnapshotDecimal(Object? value, String field) {
  if (value is! num || !value.isFinite) {
    throw FormatException('响应包含无效的 $field');
  }
  return value;
}

num? _nullableProfessionalSnapshotDecimal(
  Map<String, Object?> map,
  String key,
  String field,
) =>
    map[key] == null
        ? null
        : _requiredProfessionalSnapshotDecimal(map[key], field);

bool? _nullableProfessionalSnapshotBoolean(
  Map<String, Object?> map,
  String key,
  String field,
) {
  final value = map[key];
  if (value == null) return null;
  if (value is! bool) throw FormatException('响应包含无效的 $field');
  return value;
}

final _professionalSnapshotIsoTime = RegExp(
  r'^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?$',
);

DateTime _requiredProfessionalSnapshotDateTime(Object? value, String field) {
  if (value is! String) throw FormatException('响应包含无效的 $field');
  final match = _professionalSnapshotIsoTime.firstMatch(value);
  final parsed = DateTime.tryParse(value);
  if (match == null || parsed == null) {
    throw FormatException('响应包含无效的 $field');
  }
  final parts = [
    parsed.year,
    parsed.month,
    parsed.day,
    parsed.hour,
    parsed.minute,
    parsed.second,
  ];
  for (var index = 0; index < parts.length; index++) {
    if (parts[index] != int.parse(match.group(index + 1)!)) {
      throw FormatException('响应包含无效的 $field');
    }
  }
  return parsed;
}

DateTime? _nullableProfessionalSnapshotDateTime(
  Map<String, Object?> map,
  String key,
  String field,
) =>
    map[key] == null
        ? null
        : _requiredProfessionalSnapshotDateTime(map[key], field);

bool _validProfessionalSnapshotText(
  String? value,
  int maxLength, {
  bool required = false,
  bool allowEmpty = false,
}) {
  if (value == null) return !required;
  return value == value.trim() &&
      value.length <= maxLength &&
      (allowEmpty || value.isNotEmpty);
}

bool _validProfessionalSnapshotItems(
  List<String>? values,
  int maxItems,
  int maxItemLength,
  int maxTargetLength, {
  bool required = false,
}) {
  if (values == null) return !required;
  if (values.length > maxItems ||
      values.any((value) =>
          value.isEmpty ||
          value != value.trim() ||
          value.length > maxItemLength)) {
    return false;
  }
  return values.join(',').length <= maxTargetLength;
}

bool _validProfessionalSnapshotMoney(num? value, {bool required = false}) {
  if (value == null) return !required;
  return value.isFinite &&
      value >= 0 &&
      value <= 99999999.99 &&
      _decimalHundredths(value) != null;
}

BigInt? _professionalSnapshotRateHundredths(
  num value, {
  required num minimum,
  required num maximum,
}) {
  if (!value.isFinite || value < minimum || value > maximum) return null;
  return _decimalHundredths(value);
}

final class ProfessionalProjectRequest {
  const ProfessionalProjectRequest({
    required this.id,
    required this.requestType,
    required this.doctorId,
    required this.status,
    this.doctorName = '',
    this.institutionId,
    this.institutionName,
    this.projectId,
    this.projectName,
    this.name,
    this.category,
    this.description,
    this.tags,
    this.slogan,
    this.detailContent,
    required this.currency,
    this.coverImage,
    this.images,
    required this.salesCount,
    this.referencePrice,
    this.categoryTags,
    this.price,
    this.originalPrice,
    this.isActive,
    this.institutionSplit,
    this.notes,
    this.reviewNote,
    this.reviewedBy,
    this.reviewedAt,
    this.resultingProjectId,
    this.resultingInstitutionProjectId,
    required this.submittedAt,
    required this.updatedAt,
  });

  factory ProfessionalProjectRequest.fromJson(Object? json) {
    final map = _jsonMap(json, '项目申请');
    _requireProfessionalProjectRequestKeys(map);
    final request = ProfessionalProjectRequest(
      id: _requiredProfessionalSnapshotText(map, 'id', '申请 id'),
      requestType:
          _requiredProfessionalSnapshotText(map, 'requestType', '申请类型'),
      doctorId: _requiredProfessionalSnapshotText(map, 'doctorId', '医生'),
      doctorName: _requiredProfessionalSnapshotText(map, 'doctorName', '医生名称'),
      institutionId:
          _nullableProfessionalSnapshotText(map, 'institutionId', '机构 id'),
      institutionName:
          _nullableProfessionalSnapshotText(map, 'institutionName', '机构名称'),
      projectId: _nullableProfessionalSnapshotText(map, 'projectId', '平台项目 id'),
      projectName:
          _nullableProfessionalSnapshotText(map, 'projectName', '平台项目名称'),
      name: _nullableProfessionalSnapshotText(map, 'name', '项目名称'),
      category: _nullableProfessionalSnapshotText(map, 'category', '项目分类'),
      description:
          _nullableProfessionalSnapshotText(map, 'description', '项目说明'),
      tags: _nullableProfessionalSnapshotItems(map, 'tags', '项目标签'),
      slogan: _nullableProfessionalSnapshotText(map, 'slogan', '项目标语'),
      detailContent:
          _nullableProfessionalSnapshotText(map, 'detailContent', '项目详情'),
      currency: _requiredProfessionalSnapshotText(map, 'currency', '币种'),
      coverImage: _nullableProfessionalSnapshotText(map, 'coverImage', '封面图'),
      images: _nullableProfessionalSnapshotItems(map, 'images', '项目图片'),
      salesCount: _requiredWireInteger(map['salesCount'], '销量'),
      referencePrice:
          _nullableProfessionalSnapshotDecimal(map, 'referencePrice', '参考价格'),
      categoryTags:
          _nullableProfessionalSnapshotItems(map, 'categoryTags', '分类标签'),
      price: _nullableProfessionalSnapshotDecimal(map, 'price', '价格'),
      originalPrice:
          _nullableProfessionalSnapshotDecimal(map, 'originalPrice', '原价'),
      isActive: _nullableProfessionalSnapshotBoolean(map, 'isActive', '上架状态'),
      institutionSplit: map['institutionSplit'] == null
          ? null
          : InstitutionProjectSplit.fromJson(map['institutionSplit']),
      notes: _nullableProfessionalSnapshotText(map, 'notes', '申请说明'),
      status: _requiredProfessionalSnapshotText(map, 'status', '申请状态'),
      reviewNote: _nullableProfessionalSnapshotText(map, 'reviewNote', '审核意见'),
      reviewedBy: _nullableProfessionalSnapshotText(map, 'reviewedBy', '审核人'),
      reviewedAt:
          _nullableProfessionalSnapshotDateTime(map, 'reviewedAt', '审核时间'),
      resultingProjectId: _nullableProfessionalSnapshotText(
          map, 'resultingProjectId', '生成平台项目'),
      resultingInstitutionProjectId: _nullableProfessionalSnapshotText(
          map, 'resultingInstitutionProjectId', '生成机构项目'),
      submittedAt:
          _requiredProfessionalSnapshotDateTime(map['submittedAt'], '提交时间'),
      updatedAt:
          _requiredProfessionalSnapshotDateTime(map['updatedAt'], '更新时间'),
    );
    if (!request.hasCompleteReviewSnapshot) {
      throw const FormatException('响应包含不完整的项目申请快照');
    }
    return request;
  }

  final String id;
  final String requestType;
  final String doctorId;
  final String doctorName;
  final String? institutionId;
  final String? institutionName;
  final String? projectId;
  final String? projectName;
  final String? name;
  final String? category;
  final String? description;
  final List<String>? tags;
  final String? slogan;
  final String? detailContent;
  final String currency;
  final String? coverImage;
  final List<String>? images;
  final int salesCount;
  final num? referencePrice;
  final List<String>? categoryTags;
  final num? price;
  final num? originalPrice;
  final bool? isActive;
  final InstitutionProjectSplit? institutionSplit;
  final String? notes;
  final String status;
  final String? reviewNote;
  final String? reviewedBy;
  final DateTime? reviewedAt;
  final String? resultingProjectId;
  final String? resultingInstitutionProjectId;
  final DateTime submittedAt;
  final DateTime updatedAt;

  // Compatibility aliases for the pre-full-snapshot presentation. Task 8
  // switches the page to the canonical fields above.
  String? get serviceContent => description;
  num? get priceSuggestion => price;

  bool get isCreationReviewable => status == 'PENDING';

  bool get isCurrentlyApprovable =>
      hasCompleteReviewSnapshot &&
      (requestType != 'INSTITUTION' ||
          _decimalHundredths(institutionSplit!.doctorRate)! >= BigInt.zero);

  bool get hasCompleteReviewSnapshot {
    if (!_validProfessionalSnapshotText(id, 200, required: true) ||
        !_validProfessionalSnapshotText(doctorId, 200, required: true) ||
        !_validProfessionalSnapshotText(doctorName, 200, required: true) ||
        !const {'PLATFORM', 'INSTITUTION'}.contains(requestType) ||
        !const {'PENDING', 'APPROVED', 'REJECTED', 'CHANGES_REQUESTED'}
            .contains(status) ||
        !const {'CNY', 'USD'}.contains(currency) ||
        salesCount < 0 ||
        salesCount > 2147483647 ||
        !_validProfessionalSnapshotText(notes, 2000) ||
        !_validProfessionalSnapshotText(reviewNote, 1000) ||
        !_validProfessionalSnapshotText(reviewedBy, 200) ||
        !_validProfessionalSnapshotText(resultingProjectId, 200) ||
        !_validProfessionalSnapshotText(resultingInstitutionProjectId, 200)) {
      return false;
    }
    if (requestType == 'PLATFORM') {
      return institutionId == null &&
          institutionName == null &&
          projectId == null &&
          projectName == null &&
          price == null &&
          originalPrice == null &&
          isActive == null &&
          institutionSplit == null &&
          _validProfessionalSnapshotText(name, 200, required: true) &&
          _validProfessionalSnapshotText(category, 100, required: true) &&
          _validProfessionalSnapshotText(description, 5000, required: true) &&
          _validProfessionalSnapshotItems(tags, 20, 100, 500, required: true) &&
          _validProfessionalSnapshotText(slogan, 500,
              required: true, allowEmpty: true) &&
          _validProfessionalSnapshotText(detailContent, 20000) &&
          _validProfessionalSnapshotText(coverImage, 500,
              required: true, allowEmpty: true) &&
          _validProfessionalSnapshotItems(images, 20, 500, 2000,
              required: true) &&
          _validProfessionalSnapshotMoney(referencePrice, required: true) &&
          _validProfessionalSnapshotItems(categoryTags, 20, 100, 500,
              required: true);
    }
    if (requestType == 'INSTITUTION') {
      final split = institutionSplit;
      return referencePrice == null &&
          categoryTags == null &&
          _validProfessionalSnapshotText(institutionId, 200, required: true) &&
          _validProfessionalSnapshotText(institutionName, 200) &&
          _validProfessionalSnapshotText(projectId, 200, required: true) &&
          _validProfessionalSnapshotText(projectName, 200) &&
          _validProfessionalSnapshotText(name, 200) &&
          _validProfessionalSnapshotText(category, 100) &&
          _validProfessionalSnapshotText(description, 5000) &&
          _validProfessionalSnapshotItems(tags, 20, 100, 500) &&
          _validProfessionalSnapshotText(slogan, 500) &&
          _validProfessionalSnapshotText(detailContent, 20000) &&
          _validProfessionalSnapshotText(coverImage, 500) &&
          _validProfessionalSnapshotItems(images, 20, 500, 2000) &&
          _validProfessionalSnapshotMoney(price, required: true) &&
          _validProfessionalSnapshotMoney(originalPrice) &&
          isActive != null &&
          split != null &&
          split.hasCompleteReviewSnapshot;
    }
    return false;
  }
}

final class InstitutionProjectSplit {
  const InstitutionProjectSplit({
    required this.consultationFee,
    required this.commissionRate,
    required this.institutionRate,
    required this.platformRate,
    required this.doctorRate,
  });

  factory InstitutionProjectSplit.fromJson(Object? json) {
    final map = _jsonMap(json, '机构项目分账');
    for (final key in const {
      'consultationFee',
      'commissionRate',
      'institutionRate',
      'platformRate',
      'doctorRate',
    }) {
      if (!map.containsKey(key)) {
        throw const FormatException('响应包含不完整的机构项目分账快照');
      }
    }
    return InstitutionProjectSplit(
      consultationFee:
          _requiredProfessionalSnapshotDecimal(map['consultationFee'], '咨询费'),
      commissionRate:
          _requiredProfessionalSnapshotDecimal(map['commissionRate'], '顾问比例'),
      institutionRate:
          _requiredProfessionalSnapshotDecimal(map['institutionRate'], '机构比例'),
      platformRate:
          _requiredProfessionalSnapshotDecimal(map['platformRate'], '平台比例'),
      doctorRate:
          _requiredProfessionalSnapshotDecimal(map['doctorRate'], '医生比例'),
    );
  }

  final num consultationFee;
  final num commissionRate;
  final num institutionRate;
  final num platformRate;
  final num doctorRate;

  bool get hasCompleteReviewSnapshot {
    final commission = _professionalSnapshotRateHundredths(commissionRate,
        minimum: 0, maximum: 100);
    final institution = _professionalSnapshotRateHundredths(institutionRate,
        minimum: 0, maximum: 100);
    final platform = _professionalSnapshotRateHundredths(platformRate,
        minimum: 0, maximum: 100);
    final doctor = _professionalSnapshotRateHundredths(doctorRate,
        minimum: -100, maximum: 100);
    if (!_validProfessionalSnapshotMoney(consultationFee, required: true) ||
        commission == null ||
        institution == null ||
        platform == null ||
        doctor == null ||
        commission + institution > BigInt.from(10000)) {
      return false;
    }
    return commission + institution + platform + doctor == BigInt.from(10000);
  }
}

final class InstitutionProjectJoinRequest {
  const InstitutionProjectJoinRequest({
    required this.id,
    required this.doctorId,
    required this.institutionId,
    required this.institutionProjectId,
    required this.projectName,
    required this.requestType,
    required this.status,
    required this.priceSuggestion,
    this.doctorName = '',
    this.institutionName = '',
    this.serviceDescription = '',
    this.notes = '',
    this.reviewNote = '',
  });

  factory InstitutionProjectJoinRequest.fromJson(Object? json) {
    final map = _jsonMap(json, '机构项目加入申请');
    return InstitutionProjectJoinRequest(
      id: _requiredText(map['id'], '申请 id'),
      doctorId: _requiredText(map['doctorId'], '医生 id'),
      doctorName: map['doctorName']?.toString() ?? '',
      institutionId: _requiredText(map['institutionId'], '机构 id'),
      institutionName: map['institutionName']?.toString() ?? '',
      institutionProjectId:
          _requiredText(map['institutionProjectId'], '机构项目 id'),
      projectName: _requiredText(map['projectName'], '项目名称'),
      requestType: _requiredText(map['requestType'], '申请类型'),
      serviceDescription: map['serviceDescription']?.toString() ?? '',
      priceSuggestion: _decimal(map['priceSuggestion']),
      notes: map['notes']?.toString() ?? '',
      status: _requiredText(map['status'], '申请状态'),
      reviewNote: map['reviewNote']?.toString() ?? '',
    );
  }

  factory InstitutionProjectJoinRequest.fromChangeRequest(
    DoctorProjectChangeRequest request,
  ) {
    if (!request.isJoin) {
      throw const FormatException('机构项目加入申请类型无效');
    }
    return InstitutionProjectJoinRequest(
      id: request.id,
      doctorId: request.doctorId,
      doctorName: request.doctorName,
      institutionId: request.institutionId,
      institutionName: request.institutionName,
      institutionProjectId: request.institutionProjectId,
      projectName: request.projectName,
      requestType: request.requestType,
      serviceDescription: request.serviceDescription,
      priceSuggestion: request.priceSuggestion ?? 0,
      notes: request.notes,
      status: request.status,
      reviewNote: request.reviewNote ?? '',
    );
  }

  final String id;
  final String doctorId;
  final String doctorName;
  final String institutionId;
  final String institutionName;
  final String institutionProjectId;
  final String projectName;
  final String requestType;
  final String serviceDescription;
  final num priceSuggestion;
  final String notes;
  final String status;
  final String reviewNote;
}

final class InstitutionProjectJoinRequestDraft {
  const InstitutionProjectJoinRequestDraft({
    required this.institutionProjectId,
    required this.serviceDescription,
    required this.priceSuggestion,
    required this.platformRate,
    this.notes = '',
  });

  final String institutionProjectId;
  final String serviceDescription;
  final num priceSuggestion;
  final num platformRate;
  final String notes;

  void validate() {
    if (institutionProjectId.trim().isEmpty) {
      throw ArgumentError('请选择机构项目');
    }
    if (serviceDescription.trim().isEmpty) {
      throw ArgumentError('请填写服务说明');
    }
    if (calculateTravelGroundServiceFeeMinor(
          doctorProjectPrice: priceSuggestion,
          platformRate: platformRate,
        ) ==
        null) {
      throw ArgumentError('医生项目价格必须能按当前比例计算出至少 USD 0.01 的旅游地接服务费');
    }
  }

  Map<String, Object?> toJson() => {
        'requestType': 'JOIN',
        'institutionProjectId': institutionProjectId.trim(),
        'serviceDescription': serviceDescription.trim(),
        'priceSuggestion': priceSuggestion,
        'notes': notes.trim(),
      };
}

final class DoctorProjectProfileUpdateDraft {
  const DoctorProjectProfileUpdateDraft({
    required this.institutionProjectId,
    required this.baseRevision,
    required this.name,
    required this.category,
    required this.description,
    required this.tags,
    required this.slogan,
    required this.detailContent,
    required this.price,
    required this.salesCount,
    required this.doctorActive,
    required this.coverImage,
    required this.images,
    this.notes = '',
    this.platformRate = 0,
  });

  final String institutionProjectId;
  final String baseRevision;
  final String? name;
  final String? category;
  final String? description;
  final List<String>? tags;
  final String? slogan;
  final String? detailContent;
  final num price;
  final int salesCount;
  final bool doctorActive;
  final String? coverImage;
  final List<String>? images;
  final num platformRate;
  final String notes;

  void validate() {
    if (institutionProjectId.trim().isEmpty) {
      throw ArgumentError('请选择机构项目');
    }
    if (baseRevision.trim().isEmpty) {
      throw ArgumentError('项目资料已失效，请刷新后重试');
    }
    if (!_validDecimal(price, 99999999.99) ||
        salesCount < 0) {
      throw ArgumentError('项目价格或销量不合法');
    }
    if (platformRate > 0 &&
        calculateTravelGroundServiceFeeMinor(
              doctorProjectPrice: price,
              platformRate: platformRate,
            ) ==
            null) {
      throw ArgumentError('医生项目价格必须能按当前比例计算出至少 USD 0.01 的旅游地接服务费');
    }
  }

  Map<String, Object?> toJson() {
    validate();
    return <String, Object?>{
      'requestType': 'PROFILE_UPDATE',
      'institutionProjectId': institutionProjectId.trim(),
      'baseRevision': baseRevision.trim(),
      'name': _nullableTrimmedValue(name),
      'category': _nullableTrimmedValue(category),
      'description': _nullableTrimmedValue(description),
      'tags': tags == null ? null : _normalizedItems(tags!),
      'slogan': _nullableTrimmedValue(slogan),
      'detailContent': _nullableTrimmedValue(detailContent),
      'price': price,
      'salesCount': salesCount,
      'doctorActive': doctorActive,
      'coverImage': _nullableTrimmedValue(coverImage),
      'images': images == null ? null : _normalizedItems(images!),
      'notes': notes.trim(),
    };
  }
}

final class DoctorInstitutionProjectSnapshot {
  const DoctorInstitutionProjectSnapshot({
    required this.rawOverrides,
    required this.effective,
    required this.source,
  });

  factory DoctorInstitutionProjectSnapshot.fromJson(Object? json) {
    final map = _jsonMap(json, '机构项目快照');
    _requireExactKeys(
        map,
        const {
          'schemaVersion',
          'association',
          'rawOverrides',
          'effective',
          'source'
        },
        '机构项目快照');
    if (map['schemaVersion'] is! int || map['schemaVersion'] != 2) {
      throw const FormatException('机构项目快照版本无效');
    }
    final association = _jsonMap(map['association'], '项目关联');
    _requireExactKeys(
        association,
        const {'institutionProjectId', 'institutionId', 'platformProjectId'},
        '项目关联');
    final raw = _jsonMap(map['rawOverrides'], '原始覆盖值');
    _requireExactKeys(
        raw,
        const {
          'name',
          'category',
          'description',
          'tags',
          'slogan',
          'detailContent',
          'coverImage',
          'images'
        },
        '原始覆盖值');
    final effective = _jsonMap(map['effective'], '有效项目值');
    _requireExactKeys(
        effective,
        const {
          'name',
          'category',
          'description',
          'tags',
          'slogan',
          'detailContent',
          'salesCount',
          'coverImage',
          'images'
        },
        '有效项目值');
    final source = _jsonMap(map['source'], '继承来源');
    _requireExactKeys(
      source,
      const {'institutionProjectVersion', 'platformInheritanceHash'},
      '继承来源',
    );
    _requiredWireText(association['institutionProjectId'], '机构项目 id');
    _requiredWireText(association['institutionId'], '机构 id');
    _requiredWireText(association['platformProjectId'], '平台项目 id');
    for (final key in const [
      'name',
      'category',
      'description',
      'slogan',
      'detailContent',
      'coverImage',
    ]) {
      _strictNullableString(raw, key, '原始覆盖值 $key');
    }
    _strictNullableStringList(raw, 'tags', '原始覆盖值 tags');
    _strictNullableStringList(raw, 'images', '原始覆盖值 images');
    _requiredWireText(effective['name'], '有效项目名称');
    _requiredWireText(effective['category'], '有效项目分类');
    for (final key in const [
      'description',
      'slogan',
      'detailContent',
      'coverImage',
    ]) {
      _strictNullableString(effective, key, '有效项目值 $key');
    }
    _strictStringList(effective['tags'], '有效项目标签');
    _strictStringList(effective['images'], '有效项目图片');
    _requiredWireInteger(effective['salesCount'], '有效项目销量');
    _requiredWireInteger(source['institutionProjectVersion'], '机构项目版本');
    _requiredWireText(source['platformInheritanceHash'], '平台继承来源');
    return DoctorInstitutionProjectSnapshot(
      rawOverrides: Map.unmodifiable(raw),
      effective: Map.unmodifiable(effective),
      source: Map.unmodifiable(source),
    );
  }

  final Map<String, dynamic> rawOverrides;
  final Map<String, dynamic> effective;
  final Map<String, dynamic> source;

  String get name => effective['name'] as String;
  String get category => effective['category'] as String;
  String get description => effective['description']?.toString() ?? '';
  List<String> get tags => _stringList(effective['tags']);
  String get slogan => effective['slogan']?.toString() ?? '';
  String? get detailContent => _nullableText(effective['detailContent']);
  int get salesCount => (effective['salesCount'] as num).toInt();
  String get coverImage => effective['coverImage']?.toString() ?? '';
  List<String> get images => _strictStringList(effective['images'], '有效项目图片');
  String? get rawName => rawOverrides['name'] as String?;
  String? get rawCategory => rawOverrides['category'] as String?;
  String? get rawSlogan => rawOverrides['slogan'] as String?;
  String? get rawDetailContent => rawOverrides['detailContent'] as String?;
}

final class DoctorProjectProfileUpdateTarget {
  const DoctorProjectProfileUpdateTarget({
    required this.institutionProjectId,
    required this.projectName,
    required this.institutionId,
    required this.institutionName,
    required this.currentPrice,
    required this.serviceDescription,
    required this.serviceTags,
    required this.scheduleNote,
    required this.coverImage,
    required this.images,
    required this.consultationFee,
    required this.commissionRate,
    required this.institutionRate,
    required this.platformRate,
    required this.doctorRate,
    this.payloadVersion = 1,
    this.baseRevision,
    this.currentProject,
    this.currentDoctorActive = true,
    this.pricingPolicyRevision,
    this.travelGroundServiceFee,
    this.platformProjectId = '',
    this.platformProjectName = '',
    this.doctorId = '',
    this.doctorName = '',
  });

  factory DoctorProjectProfileUpdateTarget.fromJson(Object? json) {
    final map = _jsonMap(json, '医生项目资料修改目标');
    final payloadVersion = map['payloadVersion'];
    if (payloadVersion is! int) {
      throw const FormatException('医生项目资料修改目标版本无效');
    }
    return switch (payloadVersion) {
      1 => DoctorProjectProfileUpdateTarget._fromV1(map),
      2 => DoctorProjectProfileUpdateTarget._fromV2(map),
      _ => throw const FormatException('医生项目资料修改目标版本无效'),
    };
  }

  factory DoctorProjectProfileUpdateTarget._fromV2(
    Map<String, dynamic> map,
  ) {
      _requireExactKeys(
          map,
          const {
            'payloadVersion',
            'institutionProjectId',
            'institutionId',
            'institutionName',
            'platformProjectId',
            'platformProjectName',
            'doctorId',
            'doctorName',
            'baseRevision',
            'currentProject',
            'currentDoctorPrice',
            'currentDoctorActive',
            'platformRate',
            'pricingPolicyRevision',
            'travelGroundServiceFee',
          },
          '医生项目资料修改目标');
    final snapshot =
        DoctorInstitutionProjectSnapshot.fromJson(map['currentProject']);
    return DoctorProjectProfileUpdateTarget(
        institutionProjectId: _requiredWireText(
          map['institutionProjectId'],
          '机构项目 id',
        ),
        projectName: snapshot.name,
        institutionId: _requiredWireText(map['institutionId'], '机构 id'),
        institutionName: _requiredWireText(map['institutionName'], '机构名称'),
        currentPrice:
            _requiredWireDecimal(map['currentDoctorPrice'], '当前医生价格'),
        serviceDescription: snapshot.description,
        serviceTags: snapshot.tags,
        scheduleNote: '',
        coverImage: snapshot.coverImage,
        images: snapshot.images,
        consultationFee: 0,
        commissionRate: 0,
        institutionRate: 0,
        platformRate: _requiredWireDecimal(map['platformRate'], '平台比例'),
        doctorRate: 0,
        payloadVersion: 2,
        baseRevision: _requiredWireText(map['baseRevision'], '基线版本'),
        currentProject: snapshot,
        currentDoctorActive:
            _requiredWireBoolean(map['currentDoctorActive'], '当前医生上架状态'),
        pricingPolicyRevision: _requiredWireText(
          map['pricingPolicyRevision'],
          '定价策略版本',
        ),
        travelGroundServiceFee: _requiredWireDecimal(
          map['travelGroundServiceFee'],
          '旅游地接服务费',
        ),
        platformProjectId:
            _requiredWireText(map['platformProjectId'], '平台项目 id'),
        platformProjectName:
            _requiredWireText(map['platformProjectName'], '平台项目名称'),
        doctorId: _requiredWireText(map['doctorId'], '医生 id'),
        doctorName: _requiredWireText(map['doctorName'], '医生名称'),
      );
  }

  factory DoctorProjectProfileUpdateTarget._fromV1(
    Map<String, dynamic> map,
  ) {
    return DoctorProjectProfileUpdateTarget(
      institutionProjectId:
          _requiredText(map['institutionProjectId'], '机构项目 id'),
      projectName: _requiredText(map['projectName'], '项目名称'),
      institutionId: _requiredText(map['institutionId'], '机构 id'),
      institutionName: _requiredText(map['institutionName'], '机构名称'),
      currentPrice: _decimal(map['currentPrice']),
      serviceDescription: map['serviceDescription']?.toString() ?? '',
      serviceTags: _stringList(map['serviceTags']),
      scheduleNote: map['scheduleNote']?.toString() ?? '',
      coverImage: map['coverImage']?.toString() ?? '',
      images: _stringList(map['images']),
      consultationFee: _decimal(map['consultationFee']),
      commissionRate: _decimal(map['commissionRate']),
      institutionRate: _decimal(map['institutionRate']),
      platformRate: _decimal(map['platformRate']),
      doctorRate: _decimal(map['doctorRate']),
      payloadVersion: 1,
    );
  }

  final String institutionProjectId, projectName, institutionId;
  final String institutionName, serviceDescription, scheduleNote, coverImage;
  final num currentPrice, consultationFee, commissionRate, institutionRate;
  final num platformRate, doctorRate;
  final List<String> serviceTags, images;
  final int payloadVersion;
  final String? baseRevision;
  final DoctorInstitutionProjectSnapshot? currentProject;
  final bool currentDoctorActive;
  final String? pricingPolicyRevision;
  final num? travelGroundServiceFee;
  final String platformProjectId, platformProjectName, doctorId, doctorName;
}

final class DoctorProjectChangeRequest {
  const DoctorProjectChangeRequest({
    required this.id,
    required this.doctorId,
    required this.doctorName,
    required this.institutionId,
    required this.institutionName,
    required this.institutionProjectId,
    required this.projectName,
    required this.requestType,
    required this.serviceDescription,
    required this.priceSuggestion,
    required this.notes,
    required this.serviceTags,
    required this.scheduleNote,
    required this.coverImage,
    required this.images,
    required this.consultationFee,
    required this.commissionRate,
    required this.institutionRate,
    required this.platformRate,
    required this.doctorRate,
    required this.forceProcessed,
    this.currentPrice,
    this.currentServiceDescription,
    this.currentServiceTags,
    this.currentScheduleNote,
    this.currentCoverImage,
    this.currentImages,
    this.currentConsultationFee,
    this.currentCommissionRate,
    this.currentInstitutionRate,
    this.currentPlatformRate,
    this.currentDoctorRate,
    required this.status,
    required this.reviewNote,
    this.payloadVersion = 1,
    this.baseRevision,
    this.currentProject,
    this.proposedProject,
    this.latestProject,
    this.latestRevision,
    this.sharedChanged = false,
    this.currentDoctorPrice,
    this.proposedDoctorPrice,
    this.latestDoctorPrice,
    this.currentDoctorActive,
    this.proposedDoctorActive,
    this.latestDoctorActive,
    this.pricingPolicyRevision,
    this.travelGroundServiceFee,
    this.requestStatus,
    this.hasCompleteSnapshot = true,
    this.snapshotError,
    this.reviewable = true,
    this.platformProjectId = '',
    this.platformProjectName = '',
    this.submittedBy = '',
    this.submittedAt,
    this.reviewedBy,
    this.reviewerName,
    this.reviewedAt,
    this.updatedAt,
    this.snapshotState,
  });

  factory DoctorProjectChangeRequest.fromJson(Object? json) {
    final map = _jsonMap(json, '医生项目变更申请');
    final payloadVersion = map['payloadVersion'];
    if (payloadVersion is! int) {
      throw const FormatException('医生项目变更申请版本无效');
    }
    return switch (payloadVersion) {
      1 => DoctorProjectChangeRequest._fromV1(map),
      2 => DoctorProjectChangeRequest._fromV2(map),
      _ => throw const FormatException('医生项目变更申请版本无效'),
    };
  }

  factory DoctorProjectChangeRequest._fromV2(Map<String, dynamic> map) {
    _requireExactKeys(map, _doctorProjectChangeV2Keys, '医生项目变更申请');
    var snapshotDamaged = false;
    DoctorInstitutionProjectSnapshot? parseSnapshot(Object? value) {
      if (value == null) return null;
      try {
        return DoctorInstitutionProjectSnapshot.fromJson(value);
      } on FormatException {
        snapshotDamaged = true;
        return null;
      }
    }

    final current = parseSnapshot(map['currentProject']);
    final proposed = parseSnapshot(map['proposedProject']);
    final latest = parseSnapshot(map['latestProject']);
    final snapshotState = _requiredWireText(map['snapshotState'], '快照状态');
    final complete = !snapshotDamaged &&
        snapshotState == 'VALID' &&
        current != null &&
        proposed != null;
    final wireSnapshotError =
        _nullableWireString(map, 'snapshotError', '快照错误码');
    final currentPrice =
        _requiredWireDecimal(map['currentDoctorPrice'], '当前医生价格');
    final proposedPrice =
        _requiredWireDecimal(map['proposedDoctorPrice'], '申请医生价格');
    final platformRate = _requiredWireDecimal(map['platformRate'], '平台比例');
    final status = _requiredWireText(map['requestStatus'], '申请状态');
    return DoctorProjectChangeRequest(
      id: _requiredWireText(map['id'], '申请 id'),
      doctorId: _requiredWireText(map['doctorId'], '医生 id'),
      doctorName: _requiredWireText(map['doctorName'], '医生名称'),
      institutionId: _requiredWireText(map['institutionId'], '机构 id'),
      institutionName: _requiredWireText(map['institutionName'], '机构名称'),
      institutionProjectId:
          _requiredWireText(map['institutionProjectId'], '机构项目 id'),
      projectName:
          _requiredWireText(map['institutionProjectName'], '机构项目名称'),
      requestType: _requiredWireText(map['requestType'], '申请类型'),
      serviceDescription: proposed?.description ?? '',
      priceSuggestion: proposedPrice,
      notes: _requiredWireString(map, 'notes', '申请说明'),
      serviceTags: proposed?.tags ?? const [],
      scheduleNote: '',
      coverImage: proposed?.coverImage ?? '',
      images: proposed?.images ?? const [],
      consultationFee: null,
      commissionRate: null,
      institutionRate: null,
      platformRate: platformRate,
      doctorRate: null,
      forceProcessed:
          _requiredWireBoolean(map['forceProcessed'], '强制处理状态'),
      currentPrice: currentPrice,
      currentServiceDescription: current?.description,
      currentServiceTags: current?.tags,
      currentScheduleNote: null,
      currentCoverImage: current?.coverImage,
      currentImages: current?.images,
      currentConsultationFee: null,
      currentCommissionRate: null,
      currentInstitutionRate: null,
      currentPlatformRate: platformRate,
      currentDoctorRate: null,
      status: status,
      reviewNote: _nullableWireString(map, 'reviewNote', '审核意见'),
      payloadVersion: 2,
      baseRevision: _requiredWireText(map['baseRevision'], '基线版本'),
      currentProject: current,
      proposedProject: proposed,
      latestProject: latest,
      latestRevision:
          _nullableWireText(map, 'latestRevision', '最新版本'),
      sharedChanged:
          _requiredWireBoolean(map['sharedChanged'], '共享字段变更状态'),
      currentDoctorPrice: currentPrice,
      proposedDoctorPrice: proposedPrice,
      latestDoctorPrice:
          _nullableWireDecimal(map, 'latestDoctorPrice', '最新医生价格'),
      currentDoctorActive:
          _requiredWireBoolean(map['currentDoctorActive'], '当前医生上架状态'),
      proposedDoctorActive:
          _requiredWireBoolean(map['proposedDoctorActive'], '申请医生上架状态'),
      latestDoctorActive:
          _nullableWireBoolean(map, 'latestDoctorActive', '最新医生上架状态'),
      pricingPolicyRevision:
          _requiredWireText(map['pricingPolicyRevision'], '定价策略版本'),
      travelGroundServiceFee: _requiredWireDecimal(
        map['travelGroundServiceFee'],
        '旅游地接服务费',
      ),
      requestStatus: status,
      hasCompleteSnapshot: complete,
      snapshotError:
          complete ? wireSnapshotError : wireSnapshotError ?? 'REQUEST_SNAPSHOT_INVALID',
      reviewable:
          _requiredWireBoolean(map['reviewable'], '可审核状态') && complete,
      platformProjectId:
          _requiredWireText(map['platformProjectId'], '平台项目 id'),
      platformProjectName:
          _requiredWireText(map['platformProjectName'], '平台项目名称'),
      submittedBy: _requiredWireText(map['submittedBy'], '提交人'),
      submittedAt: _requiredWireDateTime(map['submittedAt'], '提交时间'),
      reviewedBy: _nullableWireText(map, 'reviewedBy', '审核人'),
      reviewerName: _nullableWireText(map, 'reviewerName', '审核人名称'),
      reviewedAt: _nullableWireDateTime(map, 'reviewedAt', '审核时间'),
      updatedAt: _requiredWireDateTime(map['updatedAt'], '更新时间'),
      snapshotState: snapshotState,
    );
  }

  factory DoctorProjectChangeRequest._fromV1(Map<String, dynamic> map) {
    _requireExactKeys(map, _doctorProjectChangeV1Keys, '版本 1 医生项目变更申请');
    final requestType = _requiredText(map['requestType'], '申请类型');
    final requiresProfileValues = requestType == 'PROFILE_UPDATE';
    num? proposedDecimal(String field) => requiresProfileValues
        ? _decimal(map[field])
        : _nullableDecimal(map[field]);
    return DoctorProjectChangeRequest(
      id: _requiredText(map['id'], '申请 id'),
      doctorId: _requiredText(map['doctorId'], '医生 id'),
      doctorName: map['doctorName']?.toString() ?? '',
      institutionId: _requiredText(map['institutionId'], '机构 id'),
      institutionName: map['institutionName']?.toString() ?? '',
      institutionProjectId:
          _requiredText(map['institutionProjectId'], '机构项目 id'),
      projectName: _requiredText(map['projectName'], '项目名称'),
      requestType: requestType,
      serviceDescription: map['serviceDescription']?.toString() ?? '',
      priceSuggestion: proposedDecimal('priceSuggestion'),
      notes: map['notes']?.toString() ?? '',
      serviceTags: _stringList(map['serviceTags']),
      scheduleNote: map['scheduleNote']?.toString() ?? '',
      coverImage: map['coverImage']?.toString() ?? '',
      images: _stringList(map['images']),
      consultationFee: proposedDecimal('consultationFee'),
      commissionRate: proposedDecimal('commissionRate'),
      institutionRate: proposedDecimal('institutionRate'),
      platformRate: proposedDecimal('platformRate'),
      doctorRate: proposedDecimal('doctorRate'),
      forceProcessed: _boolean(map['forceProcessed']),
      currentPrice: _nullableDecimal(map['currentPrice']),
      currentServiceDescription:
          _nullableText(map['currentServiceDescription']),
      currentServiceTags: map['currentServiceTags'] is List
          ? _stringList(map['currentServiceTags'])
          : null,
      currentScheduleNote: _nullableText(map['currentScheduleNote']),
      currentCoverImage: _nullableText(map['currentCoverImage']),
      currentImages: map['currentImages'] is List
          ? _stringList(map['currentImages'])
          : null,
      currentConsultationFee: _nullableDecimal(map['currentConsultationFee']),
      currentCommissionRate: _nullableDecimal(map['currentCommissionRate']),
      currentInstitutionRate: _nullableDecimal(map['currentInstitutionRate']),
      currentPlatformRate: _nullableDecimal(map['currentPlatformRate']),
      currentDoctorRate: _nullableDecimal(map['currentDoctorRate']),
      status: _requiredText(map['status'], '申请状态'),
      reviewNote: _requiredWireString(map, 'reviewNote', '审核意见'),
      payloadVersion: 1,
      submittedBy: _requiredWireText(map['submittedBy'], '提交人'),
      submittedAt: _requiredWireDateTime(map['submittedAt'], '提交时间'),
      reviewedBy: _nullableWireText(map, 'reviewedBy', '审核人'),
      reviewerName: _nullableWireText(map, 'reviewerName', '审核人名称'),
      reviewedAt: _nullableWireDateTime(map, 'reviewedAt', '审核时间'),
      updatedAt: _requiredWireDateTime(map['updatedAt'], '更新时间'),
    );
  }

  final String id, doctorId, doctorName, institutionId, institutionName;
  final String institutionProjectId, projectName, requestType;
  final String serviceDescription, notes, scheduleNote, coverImage;
  final num? priceSuggestion, consultationFee, commissionRate;
  final num? institutionRate, platformRate, doctorRate;
  final List<String> serviceTags, images;
  final bool forceProcessed;
  final num? currentPrice, currentConsultationFee, currentCommissionRate;
  final num? currentInstitutionRate, currentPlatformRate, currentDoctorRate;
  final String? currentServiceDescription, currentScheduleNote;
  final String? currentCoverImage;
  final List<String>? currentServiceTags, currentImages;
  final String status;
  final String? reviewNote;
  final int payloadVersion;
  final String? baseRevision,
      latestRevision,
      pricingPolicyRevision,
      requestStatus;
  final DoctorInstitutionProjectSnapshot? currentProject,
      proposedProject,
      latestProject;
  final bool sharedChanged, hasCompleteSnapshot, reviewable;
  final num? currentDoctorPrice, proposedDoctorPrice, latestDoctorPrice;
  final bool? currentDoctorActive, proposedDoctorActive, latestDoctorActive;
  final num? travelGroundServiceFee;
  final String? snapshotError;
  final String platformProjectId, platformProjectName, submittedBy;
  final DateTime? submittedAt, reviewedAt, updatedAt;
  final String? reviewedBy, reviewerName, snapshotState;

  bool get isJoin => requestType == 'JOIN';
  bool get isLeave => requestType == 'LEAVE';
  bool get isProfileUpdate =>
      requestType == 'EDIT' || requestType == 'PROFILE_UPDATE';
}

const _doctorProjectChangeV2Keys = <String>{
  'payloadVersion',
  'id',
  'requestType',
  'doctorId',
  'doctorName',
  'institutionId',
  'institutionName',
  'institutionProjectId',
  'institutionProjectName',
  'platformProjectId',
  'platformProjectName',
  'baseRevision',
  'currentProject',
  'proposedProject',
  'latestProject',
  'latestRevision',
  'sharedChanged',
  'currentDoctorPrice',
  'proposedDoctorPrice',
  'latestDoctorPrice',
  'currentDoctorActive',
  'proposedDoctorActive',
  'latestDoctorActive',
  'platformRate',
  'pricingPolicyRevision',
  'travelGroundServiceFee',
  'requestStatus',
  'notes',
  'forceProcessed',
  'submittedBy',
  'submittedAt',
  'reviewedBy',
  'reviewerName',
  'reviewNote',
  'reviewedAt',
  'updatedAt',
  'snapshotState',
  'snapshotError',
  'reviewable',
};

const _doctorProjectChangeV1Keys = <String>{
  'payloadVersion',
  'id',
  'doctorId',
  'doctorName',
  'institutionId',
  'institutionName',
  'institutionProjectId',
  'projectName',
  'requestType',
  'serviceDescription',
  'priceSuggestion',
  'notes',
  'serviceTags',
  'scheduleNote',
  'coverImage',
  'images',
  'consultationFee',
  'commissionRate',
  'institutionRate',
  'medicalListPrice',
  'platformRate',
  'doctorRate',
  'forceProcessed',
  'currentPrice',
  'currentServiceDescription',
  'currentServiceTags',
  'currentScheduleNote',
  'currentCoverImage',
  'currentImages',
  'currentConsultationFee',
  'currentMedicalListPrice',
  'currentCommissionRate',
  'currentInstitutionRate',
  'currentPlatformRate',
  'currentDoctorRate',
  'status',
  'submittedBy',
  'reviewedBy',
  'reviewerName',
  'reviewNote',
  'submittedAt',
  'reviewedAt',
  'updatedAt',
};

void _requireExactKeys(
  Map<String, dynamic> map,
  Set<String> expected,
  String label,
) {
  if (map.length != expected.length ||
      !map.keys.toSet().containsAll(expected)) {
    throw FormatException('$label 字段集无效');
  }
}

bool _validDecimal(num value, num max) {
  final scaled = _decimalHundredths(value);
  final scaledMax = _decimalHundredths(max);
  return scaled != null &&
      scaledMax != null &&
      scaled >= BigInt.zero &&
      scaled <= scaledMax;
}

BigInt? _decimalHundredths(num value) {
  if (!value.isFinite) return null;
  final match = RegExp(
    r'^([+-]?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$',
  ).firstMatch(value.toString());
  if (match == null) return null;

  final fraction = match.group(3) ?? '';
  final exponent = int.tryParse(match.group(4) ?? '0');
  if (exponent == null) return null;
  var unscaled = BigInt.parse('${match.group(2)}$fraction');
  if (match.group(1) == '-') unscaled = -unscaled;
  if (unscaled == BigInt.zero) return BigInt.zero;

  var scale = fraction.length - exponent;
  while (scale > 2 && unscaled.remainder(BigInt.from(10)) == BigInt.zero) {
    unscaled ~/= BigInt.from(10);
    scale--;
  }
  if (scale > 2) return null;
  if (scale < 2) unscaled *= BigInt.from(10).pow(2 - scale);
  return unscaled;
}

int? calculateTravelGroundServiceFeeMinor({
  required num doctorProjectPrice,
  required num platformRate,
}) {
  final priceMinor = _decimalHundredths(doctorProjectPrice);
  final rateHundredths = _decimalHundredths(platformRate);
  if (priceMinor == null ||
      priceMinor <= BigInt.zero ||
      priceMinor > BigInt.from(9999999999) ||
      rateHundredths == null ||
      rateHundredths < BigInt.zero ||
      rateHundredths > BigInt.from(10000)) {
    return null;
  }

  final feeMinor =
      (priceMinor * rateHundredths + BigInt.from(5000)) ~/ BigInt.from(10000);
  return feeMinor >= BigInt.one ? feeMinor.toInt() : null;
}

final class PlatformProjectRequestDraft {
  const PlatformProjectRequestDraft({
    required this.name,
    required this.category,
    required this.description,
    this.referencePrice = 0,
    this.slogan = '',
    this.salesCount = 0,
    this.coverImage = '',
    this.images = const [],
    this.detailContent,
    this.tags = const [],
    this.categoryTags = const [],
    this.notes = '',
  });

  final String name;
  final String category;
  final String description;
  final num referencePrice;
  final String slogan;
  final int salesCount;
  final String coverImage;
  final List<String> images;
  final String? detailContent;
  final List<String> tags;
  final List<String> categoryTags;
  final String notes;

  void validate() {
    _validateText('项目名称', name, 200, required: true);
    _validateText('项目分类', category, 100, required: true);
    _validateText('项目说明', description, 5000, required: true);
    _validateText('项目标语', slogan, 500);
    _validateText('封面图片', coverImage, 500);
    _validateText('项目详情', detailContent, 20000);
    _validateText('申请备注', notes, 2000);
    if (!_validDecimal(referencePrice, 99999999.99)) {
      throw ArgumentError('参考价格必须在 0 到 99999999.99 之间且最多两位小数');
    }
    if (salesCount < 0 || salesCount > 2147483647) {
      throw ArgumentError('销量必须在 0 到 2147483647 之间');
    }
    _validateItems('项目图片', images, 20, 500, 2000);
    _validateItems('项目标签', tags, 20, 100, 500);
    _validateItems('分类标签', categoryTags, 20, 100, 500);
  }

  Map<String, Object?> toJson() {
    validate();
    final normalizedDetailContent = detailContent?.trim();
    return {
      'name': name.trim(),
      'category': category.trim(),
      'description': description.trim(),
      'referencePrice': referencePrice,
      'currency': 'USD',
      'slogan': slogan.trim(),
      'salesCount': salesCount,
      'coverImage': coverImage.trim(),
      'images': _normalizedItems(images),
      'detailContent':
          normalizedDetailContent == null || normalizedDetailContent.isEmpty
              ? null
              : normalizedDetailContent,
      'tags': _normalizedItems(tags),
      'categoryTags': _normalizedItems(categoryTags),
      'notes': notes.trim(),
    };
  }
}

final class ConsultantMembership {
  const ConsultantMembership({
    required this.id,
    required this.institutionId,
    required this.institutionName,
    required this.status,
    required this.requestNote,
    required this.reviewNote,
    required this.createdAt,
    required this.updatedAt,
    this.confirmedBy,
    this.confirmedAt,
    this.revokedAt,
  });

  factory ConsultantMembership.fromJson(Object? json) {
    final map = _jsonMap(json, '顾问机构关系');
    return ConsultantMembership(
      id: _requiredText(map['id'], '关系 id'),
      institutionId: _requiredText(map['institutionId'], '机构 id'),
      institutionName: _requiredText(map['institutionName'], '机构名称'),
      status: _requiredText(map['status'], '状态'),
      requestNote: map['requestNote']?.toString() ?? '',
      reviewNote: map['reviewNote']?.toString() ?? '',
      createdAt: _dateTime(map['createdAt']) ??
          (throw const FormatException('响应缺少创建时间')),
      updatedAt: _dateTime(map['updatedAt']) ??
          (throw const FormatException('响应缺少更新时间')),
      confirmedBy: _nullableText(map['confirmedBy']),
      confirmedAt: _dateTime(map['confirmedAt']),
      revokedAt: _dateTime(map['revokedAt']),
    );
  }

  factory ConsultantMembership.fromMembershipRequest(
    InstitutionMembershipRequest request,
  ) =>
      ConsultantMembership(
        id: request.id,
        institutionId: request.institutionId,
        institutionName: request.institutionName,
        status: request.status.code,
        requestNote: request.requestNote,
        reviewNote: request.reviewNote,
        createdAt: request.createdAt,
        updatedAt: request.updatedAt,
        confirmedBy: request.reviewedBy,
        confirmedAt: request.action == InstitutionMembershipAction.join &&
                request.status == InstitutionMembershipRequestStatus.approved
            ? request.reviewedAt
            : null,
        revokedAt: request.action == InstitutionMembershipAction.leave &&
                request.status == InstitutionMembershipRequestStatus.approved
            ? request.reviewedAt
            : null,
      );

  final String id;
  final String institutionId;
  final String institutionName;
  final String status;
  final String requestNote;
  final String reviewNote;
  final DateTime createdAt;
  final DateTime updatedAt;
  final String? confirmedBy;
  final DateTime? confirmedAt;
  final DateTime? revokedAt;
}

final class ConsultantMembershipDraft {
  const ConsultantMembershipDraft({
    required this.institutionId,
    required this.requestNote,
  });

  final String institutionId;
  final String requestNote;

  InstitutionMembershipRequestDraft toNormalizedDraft() =>
      InstitutionMembershipRequestDraft(
        requestType: InstitutionMembershipRequestType.consultant,
        action: InstitutionMembershipAction.join,
        institutionId: institutionId,
        requestNote: requestNote,
      );

  Map<String, Object?> toJson() => toNormalizedDraft().toJson();
}

final class InstitutionProjectRequestDraft {
  const InstitutionProjectRequestDraft({
    required this.institutionId,
    required this.projectId,
    this.name,
    this.category,
    String? description,
    String? serviceContent,
    this.tags,
    this.slogan,
    this.detailContent,
    num? price,
    num? priceSuggestion,
    this.originalPrice,
    this.currency = 'USD',
    this.coverImage,
    this.images,
    this.salesCount = 0,
    this.isActive = true,
    this.consultationFee = 0,
    this.commissionRate = 0,
    this.institutionRate = 0,
    this.platformRate = 0,
    this.notes = '',
  })  : description = description ?? serviceContent,
        price = price ?? priceSuggestion ?? 0;

  final String institutionId;
  final String projectId;
  final String? name;
  final String? category;
  final String? description;
  final List<String>? tags;
  final String? slogan;
  final String? detailContent;
  final num price;
  final num? originalPrice;
  final String currency;
  final String? coverImage;
  final List<String>? images;
  final int salesCount;
  final bool isActive;
  final num consultationFee;
  final num commissionRate;
  final num institutionRate;
  final num platformRate;
  final String notes;

  String? get serviceContent => description;
  num get priceSuggestion => price;
  num get doctorRate => 100 - platformRate - institutionRate - commissionRate;

  void validate() {
    _validateText('机构', institutionId, 200, required: true);
    _validateText('平台项目', projectId, 200, required: true);
    _validateText('项目名称', name, 200);
    _validateText('项目分类', category, 100);
    _validateText('服务内容', description, 5000);
    _validateText('项目标语', slogan, 500);
    _validateText('项目详情', detailContent, 20000);
    _validateText('封面图片', coverImage, 500);
    _validateText('申请备注', notes, 2000);
    if (calculateTravelGroundServiceFeeMinor(
          doctorProjectPrice: price,
          platformRate: platformRate,
        ) ==
        null) {
      throw ArgumentError('医生项目价格必须能按当前比例计算出至少 USD 0.01 的旅游地接服务费');
    }
    for (final amount in [
      consultationFee,
      if (originalPrice != null) originalPrice!
    ]) {
      if (!_validDecimal(amount, 99999999.99)) {
        throw ArgumentError('金额必须在 0 到 99999999.99 之间且最多两位小数');
      }
    }
    if (salesCount < 0 || salesCount > 2147483647) {
      throw ArgumentError('销量必须在 0 到 2147483647 之间');
    }
    _validateCurrency(currency);
    if (currency.trim().toUpperCase() != 'USD') {
      throw ArgumentError('医生项目价格必须使用 USD');
    }
    if (tags != null) _validateItems('项目标签', tags!, 20, 100, 500);
    if (images != null) _validateItems('项目图片', images!, 20, 500, 2000);
    for (final rate in [commissionRate, institutionRate, platformRate]) {
      if (!_validDecimal(rate, 100)) {
        throw ArgumentError('分账比例必须在 0 到 100 之间且最多两位小数');
      }
    }
    final totalRate = _decimalHundredths(platformRate)! +
        _decimalHundredths(institutionRate)! +
        _decimalHundredths(commissionRate)!;
    if (totalRate > BigInt.from(10000)) {
      throw ArgumentError('平台、机构和顾问比例合计不能超过 100%');
    }
  }

  Map<String, Object?> toJson() {
    validate();
    return {
      'projectId': projectId.trim(),
      'name': name?.trim(),
      'category': category?.trim(),
      'description': description?.trim(),
      'tags': tags == null ? null : _normalizedItems(tags!),
      'slogan': slogan?.trim(),
      'detailContent': detailContent?.trim(),
      'price': price,
      'originalPrice': originalPrice,
      'currency': currency.trim().toUpperCase(),
      'coverImage': coverImage?.trim(),
      'images': images == null ? null : _normalizedItems(images!),
      'salesCount': salesCount,
      'isActive': isActive,
      'consultationFee': consultationFee,
      'commissionRate': commissionRate,
      'institutionRate': institutionRate,
      'notes': notes.trim(),
    };
  }
}

final class InstitutionProjectApplicationFormConfig {
  const InstitutionProjectApplicationFormConfig({required this.platformRate});

  factory InstitutionProjectApplicationFormConfig.fromJson(Object? json) {
    final map = _jsonMap(json, '机构项目申请配置');
    final platformRate = _requiredDecimal(map['platformRate'], '平台比例');
    if (!_validDecimal(platformRate, 100)) {
      throw const FormatException('响应包含无效的平台比例');
    }
    return InstitutionProjectApplicationFormConfig(platformRate: platformRate);
  }

  final num platformRate;
}

void _validateText(
  String label,
  String? value,
  int maxLength, {
  bool required = false,
}) {
  final normalized = value?.trim() ?? '';
  if (required && normalized.isEmpty) throw ArgumentError('$label不能为空');
  if (normalized.length > maxLength) {
    throw ArgumentError('$label不能超过 $maxLength 个字符');
  }
}

void _validateCurrency(String value) {
  if (!const {'USD', 'CNY'}.contains(value.trim().toUpperCase())) {
    throw ArgumentError('币种仅支持 USD 或 CNY');
  }
}

void _validateItems(
  String label,
  List<String> values,
  int maxItems,
  int maxItemLength,
  int maxTargetLength,
) {
  if (values.length > maxItems ||
      values.any((value) {
        final normalized = value.trim();
        return normalized.isEmpty || normalized.length > maxItemLength;
      })) {
    throw ArgumentError('$label不能超过 $maxItems 项，每项最多 $maxItemLength 个字符');
  }
  if (_normalizedItems(values).join(',').length > maxTargetLength) {
    throw ArgumentError('$label不能超过 $maxTargetLength 个字符');
  }
}

List<String> _normalizedItems(List<String> values) =>
    values.map((value) => value.trim()).toList(growable: false);

String? _nullableTrimmedValue(String? value) {
  final normalized = value?.trim();
  return normalized == null || normalized.isEmpty ? null : normalized;
}

String _csvText(Object? value) {
  final values = switch (value) {
    final String text => text.split(','),
    final Iterable<Object?> items => items,
    _ => const <Object?>[],
  };
  return values
      .map((item) => item?.toString().trim() ?? '')
      .where((item) => item.isNotEmpty)
      .join(',');
}

String _requiredText(Object? value, String field) {
  final result = value?.toString().trim() ?? '';
  if (result.isEmpty) {
    throw FormatException('响应缺少 $field');
  }
  return result;
}

T _protocolEnum<T>(
  Object? value,
  Iterable<T> values,
  String Function(T value) codeOf,
  String field,
) {
  final code = value is String ? value : '';
  for (final candidate in values) {
    if (codeOf(candidate) == code) return candidate;
  }
  throw FormatException('响应包含无效的 $field');
}

String _requiredWireString(
  Map<String, Object?> map,
  String key,
  String field,
) {
  if (!map.containsKey(key) || map[key] is! String) {
    throw FormatException('响应缺少 $field');
  }
  return map[key]! as String;
}

String _requiredWireText(Object? value, String field) {
  if (value is! String) throw FormatException('响应缺少 $field');
  final normalized = value.trim();
  if (normalized.isEmpty) throw FormatException('响应缺少 $field');
  return normalized;
}

String? _nullableWireString(
  Map<String, Object?> map,
  String key,
  String field,
) {
  if (!map.containsKey(key)) throw FormatException('响应缺少 $field');
  if (map[key] == null) return null;
  final value = map[key];
  if (value is! String) throw FormatException('响应包含无效的 $field');
  final normalized = value.trim();
  return normalized.isEmpty ? null : normalized;
}

String? _nullableWireText(
  Map<String, Object?> map,
  String key,
  String field,
) =>
    _nullableWireString(map, key, field);

DateTime _requiredWireDateTime(Object? value, String field) {
  final parsed = _nullableWireDateTimeValue(value);
  if (parsed == null) throw FormatException('响应缺少有效的 $field');
  return parsed;
}

DateTime? _nullableWireDateTime(
  Map<String, Object?> map,
  String key,
  String field,
) {
  if (!map.containsKey(key)) throw FormatException('响应缺少 $field');
  if (map[key] == null) return null;
  final parsed = _nullableWireDateTimeValue(map[key]);
  if (parsed == null) throw FormatException('响应包含无效的 $field');
  return parsed;
}

DateTime? _nullableWireDateTimeValue(Object? value) {
  if (value is! String) return null;
  final text = value.trim();
  return text.isEmpty ? null : DateTime.tryParse(text);
}

int _requiredWireInteger(Object? value, String field) {
  if (value is int) return value;
  throw FormatException('响应缺少有效的 $field');
}

bool _requiredWireBoolean(Object? value, String field) {
  if (value is bool) return value;
  throw FormatException('响应缺少有效的 $field');
}

bool? _nullableWireBoolean(
  Map<String, Object?> map,
  String key,
  String field,
) {
  if (!map.containsKey(key)) throw FormatException('响应缺少 $field');
  final value = map[key];
  if (value == null) return null;
  if (value is bool) return value;
  throw FormatException('响应包含无效的 $field');
}

num _requiredWireDecimal(Object? value, String field) {
  if (value is num && value.isFinite) return value;
  throw FormatException('响应缺少有效的 $field');
}

num? _nullableWireDecimal(
  Map<String, Object?> map,
  String key,
  String field,
) {
  if (!map.containsKey(key)) throw FormatException('响应缺少 $field');
  final value = map[key];
  if (value == null) return null;
  if (value is num && value.isFinite) return value;
  throw FormatException('响应包含无效的 $field');
}

String? _strictNullableString(
  Map<String, Object?> map,
  String key,
  String field,
) {
  if (!map.containsKey(key)) throw FormatException('响应缺少 $field');
  final value = map[key];
  if (value == null) return null;
  if (value is String) return value;
  throw FormatException('响应包含无效的 $field');
}

List<String> _strictStringList(Object? value, String field) {
  if (value is! List || value.any((item) => item is! String)) {
    throw FormatException('响应包含无效的 $field');
  }
  return value.cast<String>().toList(growable: false);
}

List<String>? _strictNullableStringList(
  Map<String, Object?> map,
  String key,
  String field,
) {
  if (!map.containsKey(key)) throw FormatException('响应缺少 $field');
  final value = map[key];
  return value == null ? null : _strictStringList(value, field);
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

int? _nullableInteger(Object? value) {
  final text = value?.toString().trim();
  if (text == null || text.isEmpty) return null;
  return int.tryParse(text);
}

bool _boolean(Object? value) => value == true;

num _decimal(Object? value) => switch (value) {
      final num number => number,
      _ => num.tryParse(value?.toString() ?? '') ?? 0,
    };

num _requiredDecimal(Object? value, String field) {
  final parsed = switch (value) {
    final num number => number,
    final String text => num.tryParse(text.trim()),
    _ => null,
  };
  if (parsed == null || !parsed.isFinite) {
    throw FormatException('响应缺少有效的 $field');
  }
  return parsed;
}

num? _nullableDecimal(Object? value) {
  final text = value?.toString().trim();
  if (text == null || text.isEmpty) return null;
  return num.tryParse(text);
}

String? _nullableTrimmed(String value) {
  final text = value.trim();
  return text.isEmpty ? null : text;
}

String _normalizeCity(String value) {
  var text = value.trim();
  while (text.endsWith('市') && text.length > 1) {
    text = text.substring(0, text.length - 1).trim();
  }
  return text;
}

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
