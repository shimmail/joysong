String normalizeE164Phone(String value) {
  var normalized = value.trim().replaceAll(RegExp(r'[\s()-]'), '');
  if (normalized.startsWith('00')) {
    normalized = '+${normalized.substring(2)}';
  }
  if (!RegExp(r'^\+[1-9]\d{6,14}$').hasMatch(normalized)) {
    throw ArgumentError.value(value, 'phone', '手机号必须为 E.164 格式');
  }
  return normalized;
}

class PasswordLoginRequest {
  PasswordLoginRequest({required String phone, required String password})
      : phone = normalizeE164Phone(phone),
        password = _loginPassword(password);

  final String phone;
  final String password;

  Map<String, Object> toJson() => {'phone': phone, 'password': password};
}

class CodeLoginRequest {
  CodeLoginRequest({required String phone, required String code})
      : phone = normalizeE164Phone(phone),
        code = _verificationCode(code);

  final String phone;
  final String code;

  Map<String, Object> toJson() => {'phone': phone, 'code': code};
}

class GoogleLoginRequest {
  GoogleLoginRequest({required String idToken}) : idToken = idToken.trim() {
    if (this.idToken.isEmpty || this.idToken.length > 8192) {
      throw ArgumentError.value(idToken, 'idToken', 'Google ID Token 格式不正确');
    }
  }

  final String idToken;

  Map<String, Object> toJson() => {'idToken': idToken};
}

class RegisterRequest {
  RegisterRequest({
    required String phone,
    required String code,
    required String password,
  })  : phone = normalizeE164Phone(phone),
        code = _verificationCode(code),
        password = _newPassword(password);

  final String phone;
  final String code;
  final String password;

  Map<String, Object> toJson() => {
        'phone': phone,
        'code': code,
        'password': password,
      };
}

class SendCodeRequest {
  SendCodeRequest({required String phone}) : phone = normalizeE164Phone(phone);

  final String phone;

  Map<String, Object> toJson() => {'phone': phone};
}

class ResetPasswordRequest {
  ResetPasswordRequest({
    required String phone,
    required String code,
    required String newPassword,
  })  : phone = normalizeE164Phone(phone),
        code = _verificationCode(code),
        newPassword = _newPassword(newPassword);

  final String phone;
  final String code;
  final String newPassword;

  Map<String, Object> toJson() => {
        'phone': phone,
        'code': code,
        'newPassword': newPassword,
      };
}

class RefreshTokenRequest {
  RefreshTokenRequest({required String refreshToken})
      : refreshToken = refreshToken.trim() {
    if (this.refreshToken.isEmpty || this.refreshToken.length > 512) {
      throw ArgumentError.value(
        refreshToken,
        'refreshToken',
        '刷新令牌格式不正确',
      );
    }
  }

  final String refreshToken;

  Map<String, Object> toJson() => {'refreshToken': refreshToken};
}

String _verificationCode(String value) {
  final normalized = value.trim();
  if (!RegExp(r'^\d{6}$').hasMatch(normalized)) {
    throw ArgumentError.value(value, 'code', '验证码必须为 6 位数字');
  }
  return normalized;
}

String _loginPassword(String value) {
  if (value.isEmpty || value.length > 128) {
    throw ArgumentError.value(value, 'password', '密码长度必须为 1-128 位');
  }
  return value;
}

String _newPassword(String value) {
  if (value.length < 8 || value.length > 128) {
    throw ArgumentError.value(value, 'password', '密码长度必须为 8-128 位');
  }
  return value;
}
