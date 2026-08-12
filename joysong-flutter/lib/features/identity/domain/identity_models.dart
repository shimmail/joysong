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
    this.doctorInstitutionIds = const [],
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
    this.coverImage = '',
  });

  factory ManagementProjectOption.fromJson(Object? json) {
    final map = _jsonMap(json, '项目');
    return ManagementProjectOption(
      id: _requiredText(map['id'], '项目 id'),
      name: map['name']?.toString() ?? '',
      category: map['category']?.toString() ?? '',
      description: map['description']?.toString() ?? '',
      tags: map['tags']?.toString() ?? '',
      coverImage: map['coverImage']?.toString() ?? '',
    );
  }

  final String id;
  final String name;
  final String category;
  final String description;
  final String tags;
  final String coverImage;
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

final class InstitutionMembershipRequest {
  const InstitutionMembershipRequest({
    required this.id,
    required this.requestType,
    required this.userId,
    required this.institutionId,
    required this.status,
    this.requestNote = '',
    this.reviewNote = '',
  });

  factory InstitutionMembershipRequest.fromJson(Object? json) {
    final map = _jsonMap(json, '机构加入申请');
    return InstitutionMembershipRequest(
      id: _requiredText(map['id'], '申请 id'),
      requestType: _requiredText(map['requestType'], '申请类型'),
      userId: _requiredText(map['userId'], '申请人'),
      institutionId: _requiredText(map['institutionId'], '机构'),
      status: _requiredText(map['status'], '申请状态'),
      requestNote: map['requestNote']?.toString() ?? '',
      reviewNote: map['reviewNote']?.toString() ?? '',
    );
  }

  final String id;
  final String requestType;
  final String userId;
  final String institutionId;
  final String status;
  final String requestNote;
  final String reviewNote;
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

  Map<String, Object?> toJson() => {
        'requestType': 'DOCTOR',
        'institutionId': institutionId.trim(),
        'action': action,
        'requestNote': requestNote.trim(),
      };
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
    this.serviceContent,
    this.priceSuggestion,
    this.notes,
    this.reviewNote,
  });

  factory ProfessionalProjectRequest.fromJson(Object? json) {
    final map = _jsonMap(json, '项目申请');
    return ProfessionalProjectRequest(
      id: _requiredText(map['id'], '申请 id'),
      requestType: _requiredText(map['requestType'], '申请类型'),
      doctorId: _requiredText(map['doctorId'], '医生'),
      doctorName: map['doctorName']?.toString() ?? '',
      institutionId: _nullableText(map['institutionId']),
      institutionName: _nullableText(map['institutionName']),
      projectId: _nullableText(map['projectId']),
      projectName: _nullableText(map['projectName']),
      name: _nullableText(map['name']),
      category: _nullableText(map['category']),
      description: _nullableText(map['description']),
      serviceContent: _nullableText(map['serviceContent']),
      priceSuggestion: _nullableDecimal(map['priceSuggestion']),
      notes: _nullableText(map['notes']),
      status: _requiredText(map['status'], '申请状态'),
      reviewNote: _nullableText(map['reviewNote']),
    );
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
  final String? serviceContent;
  final num? priceSuggestion;
  final String? notes;
  final String status;
  final String? reviewNote;
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
    this.notes = '',
  });

  final String institutionProjectId;
  final String serviceDescription;
  final num priceSuggestion;
  final String notes;

  void validate() {
    if (institutionProjectId.trim().isEmpty) {
      throw ArgumentError('请选择机构项目');
    }
    if (serviceDescription.trim().isEmpty) {
      throw ArgumentError('请填写服务说明');
    }
    if (!priceSuggestion.isFinite || priceSuggestion < 0) {
      throw ArgumentError('价格建议必须明确填写且不能小于 0');
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

final class PlatformProjectRequestDraft {
  const PlatformProjectRequestDraft({
    required this.name,
    required this.category,
    required this.description,
    this.notes = '',
  });

  final String name;
  final String category;
  final String description;
  final String notes;

  Map<String, Object?> toJson() => {
        'name': name.trim(),
        'category': category.trim(),
        'description': description.trim(),
        'notes': notes.trim(),
      };
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

  Map<String, Object?> toJson() => {
        'institutionId': institutionId.trim(),
        'requestNote': requestNote.trim(),
      };
}

final class ConsultantProjectSummary {
  const ConsultantProjectSummary({
    required this.id,
    required this.name,
    required this.category,
    required this.description,
    required this.tags,
    required this.categoryTags,
    required this.coverImage,
    required this.referencePrice,
    required this.currency,
  });

  factory ConsultantProjectSummary.fromJson(Object? json) {
    final map = _jsonMap(json, '专业项目');
    return ConsultantProjectSummary(
      id: _requiredText(map['id'], '项目 id'),
      name: _requiredText(map['name'], '项目名称'),
      category: map['category']?.toString() ?? '',
      description: map['description']?.toString() ?? '',
      tags: _csvText(map['tags']),
      categoryTags: _csvText(map['categoryTags']),
      coverImage: map['coverImage']?.toString() ?? '',
      referencePrice: _decimal(map['referencePrice']),
      currency: _requiredText(map['currency'], '币种'),
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
}

final class InstitutionProjectRequestDraft {
  const InstitutionProjectRequestDraft({
    required this.institutionId,
    required this.projectId,
    required this.serviceContent,
    required this.priceSuggestion,
    this.notes = '',
  });

  final String institutionId;
  final String projectId;
  final String serviceContent;
  final num priceSuggestion;
  final String notes;

  Map<String, Object?> toJson() => {
        'projectId': projectId.trim(),
        'serviceContent': serviceContent.trim(),
        'priceSuggestion': priceSuggestion,
        'notes': notes.trim(),
      };
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
