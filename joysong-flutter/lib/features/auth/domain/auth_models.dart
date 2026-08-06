class AuthTokens {
  const AuthTokens({
    required this.accessToken,
    required this.refreshToken,
    required this.tokenType,
    required this.expiresIn,
  });

  final String accessToken;
  final String refreshToken;
  final String tokenType;
  final int expiresIn;

  factory AuthTokens.fromJson(Map<String, dynamic> json) {
    // Deliberately do not fall back to the legacy `token` response field.
    final accessToken = _requiredString(json, 'accessToken');
    final refreshToken = _requiredString(json, 'refreshToken');
    final tokenType = _requiredString(json, 'tokenType');
    final rawExpiresIn = json['expiresIn'];
    if (rawExpiresIn is! num || rawExpiresIn <= 0) {
      throw const FormatException('登录响应缺少有效的 expiresIn');
    }
    return AuthTokens(
      accessToken: accessToken,
      refreshToken: refreshToken,
      tokenType: tokenType,
      expiresIn: rawExpiresIn.toInt(),
    );
  }
}

class AuthUser {
  const AuthUser({
    required this.id,
    required this.phone,
    required this.email,
    required this.nickname,
    required this.avatar,
    required this.gender,
    required this.city,
    required this.bio,
    required this.birthday,
    required this.role,
    required this.hasPassword,
  });

  final String id;
  final String? phone;
  final String? email;
  final String nickname;
  final String avatar;
  final String gender;
  final String city;
  final String bio;
  final DateTime? birthday;
  final String role;
  final bool hasPassword;

  factory AuthUser.fromJson(Map<String, dynamic> json) {
    final rawBirthday = json['birthday'];
    DateTime? birthday;
    if (rawBirthday != null && rawBirthday.toString().isNotEmpty) {
      birthday = DateTime.tryParse(rawBirthday.toString());
      if (birthday == null) {
        throw const FormatException('用户生日格式不正确');
      }
    }
    final rawHasPassword = json['hasPassword'];
    if (rawHasPassword is! bool) {
      throw const FormatException('用户信息缺少有效的 hasPassword');
    }
    return AuthUser(
      id: _requiredString(json, 'id'),
      phone: _nullableString(json['phone']),
      email: _nullableString(json['email']),
      nickname: json['nickname']?.toString() ?? '',
      avatar: json['avatar']?.toString() ?? '',
      gender: json['gender']?.toString() ?? '',
      city: json['city']?.toString() ?? '',
      bio: json['bio']?.toString() ?? '',
      birthday: birthday,
      role: json['role']?.toString() ?? 'USER',
      hasPassword: rawHasPassword,
    );
  }
}

class AuthSession {
  const AuthSession({required this.tokens, required this.user});

  final AuthTokens tokens;
  final AuthUser user;

  factory AuthSession.fromJson(Object? json) {
    final map = _jsonMap(json, '登录响应 data');
    final user = _jsonMap(map['user'], '登录响应 user');
    return AuthSession(
      tokens: AuthTokens.fromJson(map),
      user: AuthUser.fromJson(user),
    );
  }
}

Map<String, dynamic> _jsonMap(Object? value, String label) {
  if (value is! Map) {
    throw FormatException('$label 不是 JSON 对象');
  }
  try {
    return value.cast<String, dynamic>();
  } on TypeError {
    throw FormatException('$label 包含无效字段');
  }
}

String _requiredString(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! String || value.trim().isEmpty) {
    throw FormatException('响应缺少有效的 $key');
  }
  return value;
}

String? _nullableString(Object? value) {
  if (value == null) {
    return null;
  }
  return value.toString();
}
