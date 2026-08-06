import 'dart:convert';

import 'package:joysong_flutter/features/auth/data/secure_token_store.dart';

final class LoginPreferences {
  const LoginPreferences({
    this.phone = '',
    this.password = '',
    this.rememberPassword = false,
    this.autoLogin = false,
    this.agreementsAccepted = false,
  });

  final String phone;
  final String password;
  final bool rememberPassword;
  final bool autoLogin;
  final bool agreementsAccepted;

  bool get canAutoLogin =>
      autoLogin &&
      rememberPassword &&
      agreementsAccepted &&
      phone.isNotEmpty &&
      password.isNotEmpty;

  LoginPreferences normalized() {
    final normalizedPhone = phone.trim();
    final shouldRemember = rememberPassword && password.isNotEmpty;
    return LoginPreferences(
      phone: normalizedPhone,
      password: shouldRemember ? password : '',
      rememberPassword: shouldRemember,
      autoLogin: autoLogin && shouldRemember && agreementsAccepted,
      agreementsAccepted: agreementsAccepted,
    );
  }

  LoginPreferences withAutoLoginDisabled() => LoginPreferences(
        phone: phone,
        password: password,
        rememberPassword: rememberPassword,
        agreementsAccepted: agreementsAccepted,
      );

  factory LoginPreferences.fromJson(Object? json) {
    if (json is! Map) {
      throw const FormatException('登录偏好不是 JSON 对象');
    }
    final map = json.cast<String, dynamic>();
    final rememberPassword = map['rememberPassword'];
    final autoLogin = map['autoLogin'];
    final agreementsAccepted = map['agreementsAccepted'];
    if (rememberPassword is! bool ||
        autoLogin is! bool ||
        agreementsAccepted is! bool) {
      throw const FormatException('登录偏好开关格式不正确');
    }
    return LoginPreferences(
      phone: map['phone']?.toString() ?? '',
      password: map['password']?.toString() ?? '',
      rememberPassword: rememberPassword,
      autoLogin: autoLogin,
      agreementsAccepted: agreementsAccepted,
    ).normalized();
  }

  Map<String, Object> toJson() {
    final value = normalized();
    return {
      'phone': value.phone,
      'password': value.password,
      'rememberPassword': value.rememberPassword,
      'autoLogin': value.autoLogin,
      'agreementsAccepted': value.agreementsAccepted,
    };
  }

  @override
  String toString() => 'LoginPreferences(phone: $phone, password: <redacted>, '
      'rememberPassword: $rememberPassword, autoLogin: $autoLogin, '
      'agreementsAccepted: $agreementsAccepted)';
}

abstract interface class LoginPreferencesStore {
  Future<LoginPreferences?> read();

  Future<void> save(LoginPreferences preferences);

  Future<void> clear();
}

final class SecureLoginPreferencesStore implements LoginPreferencesStore {
  SecureLoginPreferencesStore({SecureKeyValueStore? storage})
      : _storage = storage ?? FlutterSecureKeyValueStore();

  static const _preferencesKey = 'joysong.auth.login_preferences.v1';

  final SecureKeyValueStore _storage;

  @override
  Future<LoginPreferences?> read() async {
    final rawValue = await _storage.read(_preferencesKey);
    if (rawValue == null || rawValue.isEmpty) {
      return null;
    }
    try {
      return LoginPreferences.fromJson(jsonDecode(rawValue));
    } on FormatException {
      await clear();
      return null;
    } on TypeError {
      await clear();
      return null;
    }
  }

  @override
  Future<void> save(LoginPreferences preferences) => _storage.write(
        _preferencesKey,
        jsonEncode(preferences.normalized().toJson()),
      );

  @override
  Future<void> clear() => _storage.delete(_preferencesKey);
}
