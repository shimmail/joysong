import 'package:flutter/foundation.dart';

@immutable
final class AccountSecurityProfile {
  const AccountSecurityProfile({
    required this.id,
    required this.phone,
    required this.email,
    required this.hasPassword,
  });

  factory AccountSecurityProfile.fromJson(Object? json) {
    if (json is! Map) {
      throw const FormatException('账号资料不是 JSON 对象');
    }
    final map = json.map((key, value) => MapEntry(key.toString(), value));
    final id = map['id']?.toString().trim() ?? '';
    if (id.isEmpty) {
      throw const FormatException('账号资料缺少 id');
    }
    final hasPassword = map['hasPassword'];
    if (hasPassword is! bool) {
      throw const FormatException('账号资料缺少有效的 hasPassword');
    }
    return AccountSecurityProfile(
      id: id,
      phone: _nullableText(map['phone']),
      email: _nullableText(map['email']),
      hasPassword: hasPassword,
    );
  }

  final String id;
  final String? phone;
  final String? email;
  final bool hasPassword;

  bool get hasBoundPhone => phone != null;

  String get maskedPhone => maskPhone(phone);

  AccountSecurityProfile copyWith({String? phone, bool? hasPassword}) {
    return AccountSecurityProfile(
      id: id,
      phone: phone ?? this.phone,
      email: email,
      hasPassword: hasPassword ?? this.hasPassword,
    );
  }
}

String maskPhone(String? value) {
  final phone = value?.trim() ?? '';
  if (phone.isEmpty) {
    return '未绑定手机号';
  }
  if (phone.length <= 7) {
    return '${phone.substring(0, 2)}***${phone.substring(phone.length - 2)}';
  }
  // Keep the country prefix and a small part of the subscriber number visible,
  // while never exposing enough digits to reconstruct the E.164 number.
  return '${phone.substring(0, 5)}******${phone.substring(phone.length - 2)}';
}

String validateNewPassword(String value) {
  if (value.length < 8 || value.length > 128) {
    throw ArgumentError.value(value, 'newPassword', '新密码长度应为 8-128 位');
  }
  return value;
}

String validateVerificationCode(String value) {
  final code = value.trim();
  if (!RegExp(r'^\d{6}$').hasMatch(code)) {
    throw ArgumentError.value(value, 'code', '请输入 6 位数字验证码');
  }
  return code;
}

String validateE164Phone(String value) {
  final phone = value.trim().replaceAll(RegExp(r'[\s()-]'), '');
  if (!RegExp(r'^\+[1-9]\d{6,14}$').hasMatch(phone)) {
    throw ArgumentError.value(value, 'phone', '手机号必须包含国际区号');
  }
  return phone;
}

String? _nullableText(Object? value) {
  final text = value?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}

enum AccountDeletionStepUpMethod {
  sms,
  google,
  none;

  static AccountDeletionStepUpMethod parse(Object? value) {
    return switch (value?.toString().trim().toUpperCase()) {
      'SMS' => AccountDeletionStepUpMethod.sms,
      'GOOGLE' || 'GOOGLE_ID_TOKEN' || 'GOOGLE_REAUTH' =>
        AccountDeletionStepUpMethod.google,
      null || '' || 'NONE' => AccountDeletionStepUpMethod.none,
      _ => throw const FormatException('注销二次验证方式无效'),
    };
  }
}

@immutable
final class AccountDeletionBlocker {
  const AccountDeletionBlocker({
    required this.type,
    required this.count,
    required this.action,
  });

  factory AccountDeletionBlocker.fromJson(Object? json) {
    if (json is! Map) {
      throw const FormatException('注销阻断项不是 JSON 对象');
    }
    final map = json.map((key, value) => MapEntry(key.toString(), value));
    final type = map['type']?.toString().trim() ?? '';
    final count = map['count'];
    if (type.isEmpty || count is! num || count < 0 || count % 1 != 0) {
      throw const FormatException('注销阻断项格式无效');
    }
    return AccountDeletionBlocker(
      type: type,
      count: count.toInt(),
      action: map['action']?.toString().trim() ?? '',
    );
  }

  final String type;
  final int count;
  final String action;
}

bool isSupportedAccountDeletionIdentityAction(String action) => const {
      'VIEW_IDENTITY_APPLICATION',
      'MANAGE_INSTITUTION_RELATIONSHIP',
      'RESOLVE_PENDING_RELATIONSHIP',
    }.contains(action.trim().toUpperCase());

@immutable
final class AccountDeletionPreflight {
  const AccountDeletionPreflight({
    required this.requestId,
    required this.eligible,
    required this.stepUpMethod,
    required this.maskedCredential,
    required this.policyVersion,
    required this.blockers,
  });

  factory AccountDeletionPreflight.fromJson(Object? json) {
    if (json is! Map) {
      throw const FormatException('注销预检结果不是 JSON 对象');
    }
    final map = json.map((key, value) => MapEntry(key.toString(), value));
    final requestId = map['requestId']?.toString().trim() ?? '';
    final policyVersion = map['policyVersion']?.toString().trim() ?? '';
    final eligible = map['eligible'];
    final rawBlockers = map['blockers'];
    if (requestId.isEmpty ||
        policyVersion.isEmpty ||
        eligible is! bool ||
        rawBlockers is! List) {
      throw const FormatException('注销预检结果缺少必要字段');
    }
    final stepUpMethod = AccountDeletionStepUpMethod.parse(map['stepUpMethod']);
    if (eligible && stepUpMethod == AccountDeletionStepUpMethod.none) {
      throw const FormatException('可注销账号缺少二次验证方式');
    }
    return AccountDeletionPreflight(
      requestId: requestId,
      eligible: eligible,
      stepUpMethod: stepUpMethod,
      maskedCredential: map['maskedCredential']?.toString().trim() ?? '',
      policyVersion: policyVersion,
      blockers: List.unmodifiable(
        rawBlockers.map(AccountDeletionBlocker.fromJson),
      ),
    );
  }

  final String requestId;
  final bool eligible;
  final AccountDeletionStepUpMethod stepUpMethod;
  final String maskedCredential;
  final String policyVersion;
  final List<AccountDeletionBlocker> blockers;
}

@immutable
final class AccountDeletionAuthorization {
  const AccountDeletionAuthorization({required this.token});

  factory AccountDeletionAuthorization.fromJson(Object? json) {
    if (json is! Map) {
      throw const FormatException('注销授权结果不是 JSON 对象');
    }
    final token = json['deletionAuthorization']?.toString().trim() ?? '';
    if (token.isEmpty) {
      throw const FormatException('注销授权结果缺少 deletionAuthorization');
    }
    return AccountDeletionAuthorization(token: token);
  }

  final String token;
}

@immutable
final class AccountDeletionConfirmation {
  const AccountDeletionConfirmation({
    required this.requestId,
    required this.outcome,
    this.blockers = const [],
  });

  factory AccountDeletionConfirmation.fromJson(Object? json) {
    if (json is! Map) {
      throw const FormatException('注销确认结果不是 JSON 对象');
    }
    final map = json.map((key, value) => MapEntry(key.toString(), value));
    final requestId = map['requestId']?.toString().trim() ?? '';
    final rawOutcome = map['outcome']?.toString().trim().toUpperCase() ?? '';
    final rawBlockers = map['blockers'];
    if (requestId.isEmpty || rawOutcome.isEmpty || rawBlockers is! List) {
      throw const FormatException('注销确认结果缺少必要字段');
    }
    final outcome = switch (rawOutcome) {
      'ERASED' => AccountDeletionOutcome.erased,
      'BLOCKED' => AccountDeletionOutcome.blocked,
      _ => throw const FormatException('注销确认结果状态无效'),
    };
    return AccountDeletionConfirmation(
      requestId: requestId,
      outcome: outcome,
      blockers: List.unmodifiable(
        rawBlockers.map(AccountDeletionBlocker.fromJson),
      ),
    );
  }

  final String requestId;
  final AccountDeletionOutcome outcome;
  final List<AccountDeletionBlocker> blockers;
}

enum AccountDeletionOutcome { erased, blocked }

@immutable
final class PendingAccountDeletion {
  const PendingAccountDeletion({
    required this.requestId,
    required this.idempotencyKey,
    required this.deletionAuthorization,
    required this.policyVersion,
    required this.userId,
  });

  factory PendingAccountDeletion.fromJson(Object? json) {
    if (json is! Map) {
      throw const FormatException('待续作注销记录不是 JSON 对象');
    }
    final map = json.map((key, value) => MapEntry(key.toString(), value));
    String requiredText(String name) {
      final value = map[name]?.toString().trim() ?? '';
      if (value.isEmpty) throw FormatException('待续作注销记录缺少 $name');
      return value;
    }

    return PendingAccountDeletion(
      requestId: requiredText('requestId'),
      idempotencyKey: requiredText('idempotencyKey'),
      deletionAuthorization: requiredText('deletionAuthorization'),
      policyVersion: requiredText('policyVersion'),
      userId: requiredText('userId'),
    );
  }

  final String requestId;
  final String idempotencyKey;
  final String deletionAuthorization;
  final String policyVersion;
  final String userId;

  Map<String, String> toJson() => {
        'requestId': requestId,
        'idempotencyKey': idempotencyKey,
        'deletionAuthorization': deletionAuthorization,
        'policyVersion': policyVersion,
        'userId': userId,
      };

  @override
  bool operator ==(Object other) =>
      other is PendingAccountDeletion &&
      requestId == other.requestId &&
      idempotencyKey == other.idempotencyKey &&
      deletionAuthorization == other.deletionAuthorization &&
      policyVersion == other.policyVersion &&
      userId == other.userId;

  @override
  int get hashCode => Object.hash(
        requestId,
        idempotencyKey,
        deletionAuthorization,
        policyVersion,
        userId,
      );
}

abstract final class AccountDeletionErrorCode {
  static const invalidConfirmation = 'ACCOUNT_DELETION_CONFIRMATION_INVALID';
  static const invalidSmsCode = 'ACCOUNT_DELETION_SMS_CODE_INVALID';
  static const googleReauthenticationCancelled =
      'ACCOUNT_DELETION_GOOGLE_REAUTH_CANCELLED';
  static const verificationFailed = 'ACCOUNT_DELETION_VERIFICATION_FAILED';
  static const blocked = 'ACCOUNT_DELETION_BLOCKED';
  static const authorizationExpired =
      'ACCOUNT_DELETION_AUTHORIZATION_EXPIRED';
  static const rateLimited = 'ACCOUNT_DELETION_SMS_RATE_LIMITED';
  static const smsDeliveryUnavailable =
      'ACCOUNT_DELETION_SMS_DELIVERY_UNAVAILABLE';
  static const disabled = 'ACCOUNT_DELETION_DISABLED';
  static const blockerUnavailable = 'ACCOUNT_DELETION_BLOCKER_UNAVAILABLE';
  static const idempotencyConflict =
      'ACCOUNT_DELETION_IDEMPOTENCY_CONFLICT';
  static const requestFailed = 'ACCOUNT_DELETION_REQUEST_FAILED';
  static const retryRequired = 'ACCOUNT_DELETION_RETRY_REQUIRED';
}
