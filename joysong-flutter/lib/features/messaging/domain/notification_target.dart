enum NotificationTargetKind {
  directMessage,
  user,
  orderDetail,
  orderServiceConversation,
  identityManagement,
  identityApplication,
  professionalDoctorReview,
  professionalConsultantReview,
  professionalDoctorApplication,
  professionalConsultantApplication,
  professionalDoctorRelationships,
  professionalConsultantRelationships,
  institutionProjectReview,
  institutionProjectApplication,
  discover,
  unknown,
}

class NotificationTarget {
  const NotificationTarget({required this.kind, required this.id});

  final NotificationTargetKind kind;
  final String id;

  factory NotificationTarget.parse(String targetType, String targetId) =>
      NotificationTarget(
        kind: switch (targetType.trim().toLowerCase()) {
          'dm_conversation' => NotificationTargetKind.directMessage,
          'user' => NotificationTargetKind.user,
          'order' || 'order_refund' => NotificationTargetKind.orderDetail,
          'order_service_conversation' =>
            NotificationTargetKind.orderServiceConversation,
          'identity_management' => NotificationTargetKind.identityManagement,
          'identity_application' => NotificationTargetKind.identityApplication,
          'professional_doctor_review' =>
            NotificationTargetKind.professionalDoctorReview,
          'professional_consultant_review' =>
            NotificationTargetKind.professionalConsultantReview,
          'professional_doctor_application' =>
            NotificationTargetKind.professionalDoctorApplication,
          'professional_consultant_application' =>
            NotificationTargetKind.professionalConsultantApplication,
          'professional_doctor_relationships' =>
            NotificationTargetKind.professionalDoctorRelationships,
          'professional_consultant_relationships' =>
            NotificationTargetKind.professionalConsultantRelationships,
          'institution_project_review' =>
            NotificationTargetKind.institutionProjectReview,
          'institution_project_application' =>
            NotificationTargetKind.institutionProjectApplication,
          'project' ||
          'institution' ||
          'doctor' ||
          'article' ||
          'diary' =>
            NotificationTargetKind.discover,
          _ => NotificationTargetKind.unknown,
        },
        id: targetId.trim(),
      );
}

bool isActivityNotificationType(String type) => const {
      'activity',
      'promotion',
      'marketing',
      'campaign',
      'offer',
    }.contains(type.trim().toLowerCase());
