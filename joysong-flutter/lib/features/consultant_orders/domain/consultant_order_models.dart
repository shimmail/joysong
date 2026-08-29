enum ConsultantOrderStage {
  active('ACTIVE'),
  paused('PAUSED'),
  history('HISTORY');

  const ConsultantOrderStage(this.wireValue);

  final String wireValue;

  static ConsultantOrderStage fromWire(Object? value) => switch (value) {
        'ACTIVE' => active,
        'PAUSED' => paused,
        'HISTORY' => history,
        _ => throw const FormatException('顾问订单阶段无效'),
      };
}

final class ConsultantOrderPage {
  const ConsultantOrderPage({
    required this.items,
    required this.offset,
    required this.limit,
    required this.hasMore,
  });

  factory ConsultantOrderPage.fromJson(Object? json) {
    final map = _object(json, '顾问订单列表');
    final items = _list(map, 'items', '顾问订单列表 items');
    return ConsultantOrderPage(
      items: List.unmodifiable(items.map(ConsultantOrderSummary.fromJson)),
      offset: _integer(map, 'offset', '顾问订单列表 offset'),
      limit: _integer(map, 'limit', '顾问订单列表 limit'),
      hasMore: _boolean(map, 'hasMore', '顾问订单列表 hasMore'),
    );
  }

  final List<ConsultantOrderSummary> items;
  final int offset;
  final int limit;
  final bool hasMore;
}

final class ConsultantOrderSummary {
  const ConsultantOrderSummary({
    required this.id,
    required this.orderNo,
    required this.stage,
    required this.status,
    required this.refundStatus,
    required this.project,
    required this.institution,
    required this.customer,
    required this.appointmentTime,
    required this.updatedAt,
    required this.conversationReadable,
    required this.messageSendable,
    required this.readOnly,
  });

  factory ConsultantOrderSummary.fromJson(Object? json) {
    final map = _object(json, '顾问订单摘要');
    final messageSendable =
        _boolean(map, 'messageSendable', '顾问订单摘要 messageSendable');
    final readOnly = _boolean(map, 'readOnly', '顾问订单摘要 readOnly');
    if (readOnly != !messageSendable) {
      throw const FormatException('顾问订单只读状态不一致');
    }
    return ConsultantOrderSummary(
      id: _string(map, 'id', '顾问订单 id', nonblank: true),
      orderNo: _string(map, 'orderNo', '顾问订单号'),
      stage: ConsultantOrderStage.fromWire(map['stage']),
      status: _string(map, 'status', '顾问订单状态', nonblank: true),
      refundStatus: _string(
        map,
        'refundStatus',
        '顾问订单退款状态',
        nonblank: true,
      ),
      project: ConsultantOrderProject.fromJson(map['project']),
      institution: ConsultantOrderInstitution.fromJson(map['institution']),
      customer: ConsultantOrderCustomer.fromJson(map['customer']),
      appointmentTime: _nullableDateTime(
        map,
        'appointmentTime',
        '顾问订单预约时间',
      ),
      updatedAt: _dateTime(map, 'updatedAt', '顾问订单更新时间'),
      conversationReadable: _boolean(
        map,
        'conversationReadable',
        '顾问订单会话可读状态',
      ),
      messageSendable: messageSendable,
      readOnly: readOnly,
    );
  }

  final String id;
  final String orderNo;
  final ConsultantOrderStage stage;
  final String status;
  final String refundStatus;
  final ConsultantOrderProject project;
  final ConsultantOrderInstitution institution;
  final ConsultantOrderCustomer customer;
  final DateTime? appointmentTime;
  final DateTime updatedAt;
  final bool conversationReadable;
  final bool messageSendable;
  final bool readOnly;
}

final class ConsultantOrderDetail {
  const ConsultantOrderDetail({
    required this.summary,
    required this.doctor,
    required this.remark,
    required this.createdAt,
    required this.serviceActivatedAt,
    required this.completedAt,
    required this.conversation,
  });

  factory ConsultantOrderDetail.fromJson(Object? json) {
    final map = _object(json, '顾问订单详情');
    final summary = ConsultantOrderSummary.fromJson(map);
    final conversation =
        ConsultantOrderConversationAccess.fromJson(map['conversation']);
    if (conversation.readable != summary.conversationReadable ||
        conversation.sendable != summary.messageSendable) {
      throw const FormatException('顾问订单会话权限不一致');
    }
    return ConsultantOrderDetail(
      summary: summary,
      doctor: ConsultantOrderDoctor.fromJson(map['doctor']),
      remark: _string(map, 'remark', '顾问订单备注'),
      createdAt: _dateTime(map, 'createdAt', '顾问订单创建时间'),
      serviceActivatedAt: _dateTime(
        map,
        'serviceActivatedAt',
        '顾问订单服务激活时间',
      ),
      completedAt: _nullableDateTime(
        map,
        'completedAt',
        '顾问订单完成时间',
      ),
      conversation: conversation,
    );
  }

  final ConsultantOrderSummary summary;
  final ConsultantOrderDoctor doctor;
  final String remark;
  final DateTime createdAt;
  final DateTime serviceActivatedAt;
  final DateTime? completedAt;
  final ConsultantOrderConversationAccess conversation;
}

final class ConsultantOrderProject {
  const ConsultantOrderProject({
    required this.id,
    required this.name,
    required this.coverImage,
  });

  factory ConsultantOrderProject.fromJson(Object? json) {
    final map = _object(json, '顾问订单项目');
    return ConsultantOrderProject(
      id: _string(map, 'id', '顾问订单项目 id', nonblank: true),
      name: _string(map, 'name', '顾问订单项目名称'),
      coverImage: _string(map, 'coverImage', '顾问订单项目封面'),
    );
  }

  final String id;
  final String name;
  final String coverImage;
}

final class ConsultantOrderInstitution {
  const ConsultantOrderInstitution({
    required this.id,
    required this.name,
  });

  factory ConsultantOrderInstitution.fromJson(Object? json) {
    final map = _object(json, '顾问订单机构');
    return ConsultantOrderInstitution(
      id: _string(map, 'id', '顾问订单机构 id', nonblank: true),
      name: _string(map, 'name', '顾问订单机构名称'),
    );
  }

  final String id;
  final String name;
}

final class ConsultantOrderCustomer {
  const ConsultantOrderCustomer({
    required this.displayName,
    required this.avatar,
  });

  factory ConsultantOrderCustomer.fromJson(Object? json) {
    final map = _object(json, '顾问订单客户');
    return ConsultantOrderCustomer(
      displayName: _string(
        map,
        'displayName',
        '顾问订单客户展示名',
        nonblank: true,
      ),
      avatar: _nullableString(map, 'avatar', '顾问订单客户头像'),
    );
  }

  final String displayName;
  final String? avatar;
}

final class ConsultantOrderDoctor {
  const ConsultantOrderDoctor({
    required this.id,
    required this.name,
  });

  factory ConsultantOrderDoctor.fromJson(Object? json) {
    final map = _object(json, '顾问订单医生');
    return ConsultantOrderDoctor(
      id: _nullableString(
        map,
        'id',
        '顾问订单医生 id',
        nonblank: true,
      ),
      name: _string(map, 'name', '顾问订单医生名称'),
    );
  }

  final String? id;
  final String name;
}

final class ConsultantOrderConversationAccess {
  const ConsultantOrderConversationAccess({
    required this.readable,
    required this.sendable,
  });

  factory ConsultantOrderConversationAccess.fromJson(Object? json) {
    final map = _object(json, '顾问订单会话权限');
    return ConsultantOrderConversationAccess(
      readable: _boolean(map, 'readable', '顾问订单会话可读状态'),
      sendable: _boolean(map, 'sendable', '顾问订单会话可发送状态'),
    );
  }

  final bool readable;
  final bool sendable;
}

Map<String, Object?> _object(Object? value, String label) {
  if (value is! Map) throw FormatException('$label 不是 JSON 对象');
  final result = <String, Object?>{};
  for (final entry in value.entries) {
    if (entry.key is! String) {
      throw FormatException('$label 包含非字符串键');
    }
    result[entry.key as String] = entry.value;
  }
  return result;
}

List<Object?> _list(
  Map<String, Object?> map,
  String key,
  String label,
) {
  final value = map[key];
  if (value is! List) throw FormatException('$label 不是 JSON 数组');
  return List<Object?>.from(value);
}

String _string(
  Map<String, Object?> map,
  String key,
  String label, {
  bool nonblank = false,
}) {
  final value = map[key];
  if (value is! String || nonblank && value.trim().isEmpty) {
    throw FormatException('$label 无效');
  }
  return value;
}

String? _nullableString(
  Map<String, Object?> map,
  String key,
  String label, {
  bool nonblank = false,
}) {
  if (!map.containsKey(key)) throw FormatException('$label 缺失');
  final value = map[key];
  if (value == null) return null;
  if (value is! String || nonblank && value.trim().isEmpty) {
    throw FormatException('$label 无效');
  }
  return value;
}

int _integer(Map<String, Object?> map, String key, String label) {
  final value = map[key];
  if (value is! int) throw FormatException('$label 无效');
  return value;
}

bool _boolean(Map<String, Object?> map, String key, String label) {
  final value = map[key];
  if (value is! bool) throw FormatException('$label 无效');
  return value;
}

DateTime _dateTime(Map<String, Object?> map, String key, String label) {
  final value = map[key];
  if (value is! String) throw FormatException('$label 无效');
  return _parseDateTime(value, label);
}

DateTime? _nullableDateTime(
  Map<String, Object?> map,
  String key,
  String label,
) {
  if (!map.containsKey(key)) throw FormatException('$label 缺失');
  final value = map[key];
  if (value == null) return null;
  if (value is! String) throw FormatException('$label 无效');
  return _parseDateTime(value, label);
}

DateTime _parseDateTime(String value, String label) {
  try {
    return DateTime.parse(value);
  } on FormatException {
    throw FormatException('$label 无效');
  }
}
