import 'package:joysong_flutter/features/auth/domain/auth_models.dart';

enum ProfileGender {
  unspecified('', '不公开'),
  male('MALE', '男'),
  female('FEMALE', '女'),
  other('OTHER', '其他');

  const ProfileGender(this.wireValue, this.label);

  final String wireValue;
  final String label;

  static ProfileGender fromWireValue(String value) =>
      ProfileGender.values.firstWhere(
        (gender) => gender.wireValue == value.toUpperCase(),
        orElse: () => ProfileGender.unspecified,
      );
}

final class ProfileUpdate {
  const ProfileUpdate({
    required this.nickname,
    required this.gender,
    required this.city,
    required this.bio,
    this.birthday,
    this.avatar,
  });

  final String nickname;
  final ProfileGender gender;
  final String city;
  final String bio;
  final DateTime? birthday;
  final String? avatar;

  void validate() {
    final normalizedNickname = nickname.trim();
    if (normalizedNickname.isEmpty) {
      throw ArgumentError.value(nickname, 'nickname', '请输入昵称');
    }
    if (normalizedNickname.length > 100) {
      throw ArgumentError.value(nickname, 'nickname', '昵称不能超过 100 字');
    }
    if (city.trim().length > 100) {
      throw ArgumentError.value(city, 'city', '城市不能超过 100 字');
    }
    if (bio.trim().length > 500) {
      throw ArgumentError.value(bio, 'bio', '个人简介不能超过 500 字');
    }
    if (birthday != null && birthday!.isAfter(DateTime.now())) {
      throw ArgumentError.value(birthday, 'birthday', '生日不能晚于今天');
    }
    if (avatar != null && avatar!.length > 500) {
      throw ArgumentError.value(avatar, 'avatar', '头像地址过长');
    }
  }

  Map<String, Object?> toJson() {
    validate();
    return <String, Object?>{
      'nickname': nickname.trim(),
      'gender': gender.wireValue,
      'city': city.trim(),
      'bio': bio.trim(),
      'birthday': birthday == null ? null : _dateOnly(birthday!),
      if (avatar != null) 'avatar': avatar,
    };
  }
}

String maskedProfilePhone(String? phone) {
  final value = (phone ?? '').trim();
  if (value.isEmpty) return '未绑定手机号';
  if (value.length <= 8) return value;
  return '${value.substring(0, value.length - 8)}****${value.substring(value.length - 4)}';
}

String profileDisplayName(AuthUser? user) {
  final nickname = user?.nickname.trim() ?? '';
  if (nickname.isNotEmpty) return nickname;
  return '娇颜颂用户';
}

String _dateOnly(DateTime value) {
  final local = value.toLocal();
  return '${local.year.toString().padLeft(4, '0')}-'
      '${local.month.toString().padLeft(2, '0')}-'
      '${local.day.toString().padLeft(2, '0')}';
}
