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
