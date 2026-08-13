enum ChatPersona { bestie, consultant }

sealed class AgentStreamEvent {
  const AgentStreamEvent();
}

final class AgentStreamStarted extends AgentStreamEvent {
  const AgentStreamStarted({
    required this.traceId,
    required this.turnId,
    required this.userMessage,
  });

  final String traceId;
  final String turnId;
  final ChatMessage userMessage;
}

final class AgentStreamDelta extends AgentStreamEvent {
  const AgentStreamDelta({required this.content});

  final String content;
}

final class AgentStreamCompleted extends AgentStreamEvent {
  const AgentStreamCompleted({required this.turn});

  final ChatTurn turn;
}

final class AgentStreamFailed extends AgentStreamEvent {
  const AgentStreamFailed({
    required this.code,
    required this.traceId,
    required this.retryable,
  });

  final String code;
  final String? traceId;
  final bool retryable;
}

extension ChatPersonaWire on ChatPersona {
  String get wireName => switch (this) {
        ChatPersona.bestie => 'BESTIE',
        ChatPersona.consultant => 'CONSULTANT',
      };
}

enum ChatContextType {
  general,
  doctor,
  project,
  institution,
  institutionProject,
}

extension ChatContextTypeWire on ChatContextType {
  String get wireName => switch (this) {
        ChatContextType.institutionProject => 'INSTITUTION_PROJECT',
        _ => name.toUpperCase(),
      };
}

class ChatSession {
  const ChatSession({
    required this.id,
    required this.persona,
    required this.contextType,
    required this.contextId,
    required this.title,
    required this.lastMessage,
    required this.createdAt,
    required this.updatedAt,
  });

  final String id;
  final String persona;
  final String contextType;
  final String contextId;
  final String title;
  final String lastMessage;
  final String createdAt;
  final String updatedAt;

  factory ChatSession.fromJson(Object? json) {
    final map = requireJsonMap(json, '会话');
    return ChatSession(
      id: requireString(map, 'id'),
      persona: stringValue(map['persona']),
      contextType: stringValue(map['contextType']),
      contextId: stringValue(map['contextId']),
      title: stringValue(map['title']),
      lastMessage: stringValue(map['lastMessage']),
      createdAt: stringValue(map['createdAt']),
      updatedAt: stringValue(map['updatedAt']),
    );
  }
}

class ChatMessage {
  const ChatMessage({
    required this.id,
    required this.sessionId,
    required this.role,
    required this.content,
    required this.createdAt,
    this.catalogItems = const [],
    this.isTemporary = false,
  });

  final String id;
  final String sessionId;
  final String role;
  final String content;
  final String createdAt;
  final List<AgentCatalogItem> catalogItems;
  final bool isTemporary;

  bool get isUser => role.toUpperCase() == 'USER';

  ChatMessage copyWith({
    String? content,
    List<AgentCatalogItem>? catalogItems,
    bool? isTemporary,
  }) =>
      ChatMessage(
        id: id,
        sessionId: sessionId,
        role: role,
        content: content ?? this.content,
        createdAt: createdAt,
        catalogItems: catalogItems ?? this.catalogItems,
        isTemporary: isTemporary ?? this.isTemporary,
      );

  factory ChatMessage.fromJson(Object? json) {
    final map = requireJsonMap(json, '消息');
    return ChatMessage(
      id: requireString(map, 'id'),
      sessionId: requireString(map, 'sessionId'),
      role: stringValue(map['role'], fallback: 'ASSISTANT'),
      content: stringValue(map['content']),
      createdAt: stringValue(map['createdAt']),
      catalogItems:
          jsonList(map['catalogItems']).map(AgentCatalogItem.fromJson).toList(),
    );
  }
}

class AgentCatalogItem {
  const AgentCatalogItem({
    required this.type,
    required this.id,
    required this.name,
    required this.subtitle,
    required this.summary,
    required this.attributes,
    required this.institutionId,
    required this.projectId,
    required this.canChatWithHuman,
  });

  final String type;
  final String id;
  final String name;
  final String subtitle;
  final String summary;
  final Map<String, String> attributes;
  final String? institutionId;
  final String? projectId;
  final bool canChatWithHuman;

  factory AgentCatalogItem.fromJson(Object? json) {
    final map = requireJsonMap(json, '目录项');
    final attributes = map['attributes'];
    return AgentCatalogItem(
      type: stringValue(map['type']),
      id: requireString(map, 'id'),
      name: stringValue(map['name']),
      subtitle: stringValue(map['subtitle']),
      summary: stringValue(map['summary']),
      attributes: attributes is Map
          ? attributes.map(
              (key, value) => MapEntry(key.toString(), value.toString()),
            )
          : const {},
      institutionId: nullableString(map['institutionId']),
      projectId: nullableString(map['projectId']),
      canChatWithHuman: map['canChatWithHuman'] == true,
    );
  }
}

class AgentCatalogReport {
  const AgentCatalogReport({
    required this.mode,
    required this.title,
    required this.summary,
    required this.items,
    required this.comparisonDimensions,
    required this.warnings,
  });

  final String mode;
  final String title;
  final String summary;
  final List<AgentCatalogItem> items;
  final List<String> comparisonDimensions;
  final List<String> warnings;

  factory AgentCatalogReport.fromJson(Object? json) {
    final map = requireJsonMap(json, '目录报告');
    return AgentCatalogReport(
      mode: stringValue(map['mode'], fallback: 'SUMMARY'),
      title: stringValue(map['title']),
      summary: stringValue(map['summary']),
      items: jsonList(map['items']).map(AgentCatalogItem.fromJson).toList(),
      comparisonDimensions: stringList(map['comparisonDimensions']),
      warnings: stringList(map['warnings']),
    );
  }
}

class ChatTurn {
  const ChatTurn({
    required this.message,
    required this.catalogReport,
    required this.catalogItems,
    required this.intent,
    required this.queryTarget,
    required this.nextAction,
  });

  final ChatMessage message;
  final AgentCatalogReport? catalogReport;
  final List<AgentCatalogItem> catalogItems;
  final String intent;
  final String? queryTarget;
  final String nextAction;

  factory ChatTurn.fromJson(Object? json) {
    final map = requireJsonMap(json, '聊天回复');
    final report = map['catalogReport'];
    return ChatTurn(
      message: ChatMessage.fromJson(map['message']),
      catalogReport:
          report == null ? null : AgentCatalogReport.fromJson(report),
      catalogItems:
          jsonList(map['catalogItems']).map(AgentCatalogItem.fromJson).toList(),
      intent: stringValue(map['intent'], fallback: 'GENERAL_CHAT'),
      queryTarget: nullableString(map['queryTarget']),
      nextAction: stringValue(map['nextAction'], fallback: 'NONE'),
    );
  }
}

class AgentProfile {
  const AgentProfile({
    required this.id,
    required this.city,
    required this.goals,
    required this.budgetMin,
    required this.budgetMax,
    required this.acceptableDowntimeDays,
    required this.painTolerance,
    required this.preferences,
    required this.excludedProjects,
    required this.consentVersion,
    required this.confirmedAt,
    required this.completenessScore,
    required this.missingFields,
  });

  final String? id;
  final String city;
  final List<String> goals;
  final String? budgetMin;
  final String? budgetMax;
  final int? acceptableDowntimeDays;
  final String painTolerance;
  final List<String> preferences;
  final List<String> excludedProjects;
  final String consentVersion;
  final String? confirmedAt;
  final int completenessScore;
  final List<String> missingFields;

  factory AgentProfile.fromJson(Object? json) {
    final map = requireJsonMap(json, 'Agent 档案');
    return AgentProfile(
      id: nullableString(map['id']),
      city: stringValue(map['city']),
      goals: stringList(map['goals']),
      budgetMin: decimalString(map['budgetMin']),
      budgetMax: decimalString(map['budgetMax']),
      acceptableDowntimeDays: intValue(map['acceptableDowntimeDays']),
      painTolerance: stringValue(map['painTolerance']),
      preferences: stringList(map['preferences']),
      excludedProjects: stringList(map['excludedProjects']),
      consentVersion: stringValue(map['consentVersion']),
      confirmedAt: nullableString(map['confirmedAt']),
      completenessScore: intValue(map['completenessScore']) ?? 0,
      missingFields: stringList(map['missingFields']),
    );
  }
}

class AgentProfileDraft {
  const AgentProfileDraft({
    this.city = '',
    this.goals = const [],
    this.budgetMin,
    this.budgetMax,
    this.acceptableDowntimeDays,
    this.painTolerance = '',
    this.preferences = const [],
    this.excludedProjects = const [],
    this.consentVersion = 'agent-profile-v1',
  });

  final String city;
  final List<String> goals;
  final String? budgetMin;
  final String? budgetMax;
  final int? acceptableDowntimeDays;
  final String painTolerance;
  final List<String> preferences;
  final List<String> excludedProjects;
  final String consentVersion;

  Map<String, Object?> toJson() => {
        'city': city,
        'goals': goals,
        'budgetMin': budgetMin,
        'budgetMax': budgetMax,
        'acceptableDowntimeDays': acceptableDowntimeDays,
        'painTolerance': painTolerance,
        'preferences': preferences,
        'excludedProjects': excludedProjects,
        'consentVersion': consentVersion,
      };
}

class AgentSafetyScreening {
  const AgentSafetyScreening({
    this.pregnantOrNursing = false,
    this.activeSkinCondition = false,
    this.severeAllergyHistory = false,
    this.takingRelevantMedication = false,
    this.recentProcedure = false,
    this.expectsGuaranteedResult = false,
    this.severeDistressAboutAppearance = false,
  });

  final bool pregnantOrNursing;
  final bool activeSkinCondition;
  final bool severeAllergyHistory;
  final bool takingRelevantMedication;
  final bool recentProcedure;
  final bool expectsGuaranteedResult;
  final bool severeDistressAboutAppearance;

  Map<String, Object?> toJson() => {
        'pregnantOrNursing': pregnantOrNursing,
        'activeSkinCondition': activeSkinCondition,
        'severeAllergyHistory': severeAllergyHistory,
        'takingRelevantMedication': takingRelevantMedication,
        'recentProcedure': recentProcedure,
        'expectsGuaranteedResult': expectsGuaranteedResult,
        'severeDistressAboutAppearance': severeDistressAboutAppearance,
      };
}

class AgentAssessment {
  const AgentAssessment({
    required this.id,
    required this.status,
    required this.completenessScore,
    required this.riskLevel,
    required this.riskReasons,
    required this.missingFields,
    required this.nextAction,
  });

  final String id;
  final String status;
  final int completenessScore;
  final String riskLevel;
  final List<String> riskReasons;
  final List<String> missingFields;
  final String nextAction;

  factory AgentAssessment.fromJson(Object? json) {
    final map = requireJsonMap(json, 'Agent 评估');
    return AgentAssessment(
      id: requireString(map, 'id'),
      status: stringValue(map['status']),
      completenessScore: intValue(map['completenessScore']) ?? 0,
      riskLevel: stringValue(map['riskLevel'], fallback: 'UNKNOWN'),
      riskReasons: stringList(map['riskReasons']),
      missingFields: stringList(map['missingFields']),
      nextAction: stringValue(map['nextAction']),
    );
  }
}

class AgentPlanItem {
  const AgentPlanItem({
    required this.id,
    required this.stage,
    required this.projectId,
    required this.projectName,
    required this.recommendationType,
    required this.reason,
    required this.expectedBenefit,
    required this.limitations,
    required this.risks,
    required this.alternatives,
    required this.requiredConfirmations,
    required this.confidence,
  });

  final String id;
  final String stage;
  final String? projectId;
  final String projectName;
  final String recommendationType;
  final String reason;
  final String expectedBenefit;
  final String limitations;
  final List<String> risks;
  final List<String> alternatives;
  final List<String> requiredConfirmations;
  final String confidence;

  factory AgentPlanItem.fromJson(Object? json) {
    final map = requireJsonMap(json, '方案项目');
    return AgentPlanItem(
      id: requireString(map, 'id'),
      stage: stringValue(map['stage']),
      projectId: nullableString(map['projectId']),
      projectName: stringValue(map['projectName']),
      recommendationType: stringValue(map['recommendationType']),
      reason: stringValue(map['reason']),
      expectedBenefit: stringValue(map['expectedBenefit']),
      limitations: stringValue(map['limitations']),
      risks: stringList(map['risks']),
      alternatives: stringList(map['alternatives']),
      requiredConfirmations: stringList(map['requiredConfirmations']),
      confidence: stringValue(map['confidence'], fallback: 'LOW'),
    );
  }
}

class AgentPlan {
  const AgentPlan({
    required this.id,
    required this.assessmentId,
    required this.version,
    required this.status,
    required this.summary,
    required this.totalBudgetMin,
    required this.totalBudgetMax,
    required this.items,
    required this.createdAt,
  });

  final String id;
  final String assessmentId;
  final int version;
  final String status;
  final String summary;
  final String? totalBudgetMin;
  final String? totalBudgetMax;
  final List<AgentPlanItem> items;
  final String createdAt;

  factory AgentPlan.fromJson(Object? json) {
    final map = requireJsonMap(json, 'Agent 方案');
    return AgentPlan(
      id: requireString(map, 'id'),
      assessmentId: requireString(map, 'assessmentId'),
      version: intValue(map['version']) ?? 0,
      status: stringValue(map['status']),
      summary: stringValue(map['summary']),
      totalBudgetMin: decimalString(map['totalBudgetMin']),
      totalBudgetMax: decimalString(map['totalBudgetMax']),
      items: jsonList(map['items']).map(AgentPlanItem.fromJson).toList(),
      createdAt: stringValue(map['createdAt']),
    );
  }
}

Map<String, dynamic> requireJsonMap(Object? json, String label) {
  if (json is! Map) {
    throw FormatException('$label不是 JSON 对象');
  }
  return json.cast<String, dynamic>();
}

List<Object?> jsonList(Object? value) => value is List ? value : const [];

String requireString(Map<String, dynamic> map, String key) {
  final value = stringValue(map[key]);
  if (value.isEmpty) {
    throw FormatException('响应缺少 $key');
  }
  return value;
}

String stringValue(Object? value, {String fallback = ''}) {
  final string = value?.toString() ?? '';
  return string.isEmpty ? fallback : string;
}

String? nullableString(Object? value) {
  final string = value?.toString();
  return string == null || string.isEmpty ? null : string;
}

String? decimalString(Object? value) => nullableString(value);

int? intValue(Object? value) => switch (value) {
      final int number => number,
      final num number => number.toInt(),
      final String string => int.tryParse(string),
      _ => null,
    };

List<String> stringList(Object? value) =>
    value is List ? value.map((item) => item.toString()).toList() : const [];
