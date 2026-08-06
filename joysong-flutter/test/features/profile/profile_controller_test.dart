import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/profile/domain/profile_models.dart';
import 'package:joysong_flutter/features/profile/domain/profile_repository.dart';
import 'package:joysong_flutter/features/profile/presentation/profile_controller.dart';

void main() {
  test('profile update validates and maps server fields', () {
    final update = ProfileUpdate(
      nickname: ' 新昵称 ',
      gender: ProfileGender.female,
      city: ' 上海 ',
      bio: ' 你好 ',
      birthday: DateTime(1998, 3, 2),
      avatar: 'https://example.test/avatar.jpg',
    );

    expect(update.toJson(), {
      'nickname': '新昵称',
      'gender': 'FEMALE',
      'city': '上海',
      'bio': '你好',
      'birthday': '1998-03-02',
      'avatar': 'https://example.test/avatar.jpg',
    });
    expect(
      () => ProfileUpdate(
        nickname: '',
        gender: ProfileGender.unspecified,
        city: '',
        bio: '',
      ).validate(),
      throwsArgumentError,
    );
  });

  test('controller loads and saves actual profile returned by repository',
      () async {
    final repository = _FakeProfileRepository();
    final controller = ProfileController(repository);

    await controller.load();
    expect(controller.status, ProfileLoadStatus.ready);
    expect(controller.user?.nickname, '原昵称');

    final saved = await controller.save(
      const ProfileUpdate(
        nickname: '新昵称',
        gender: ProfileGender.other,
        city: '成都',
        bio: '个人简介',
      ),
    );

    expect(saved, isTrue);
    expect(repository.updateCalls, 1);
    expect(controller.user?.nickname, '新昵称');
    expect(controller.isSaving, isFalse);
  });

  test('controller exposes load failure and supports retry', () async {
    final repository = _FakeProfileRepository()..failLoad = true;
    final controller = ProfileController(repository);

    await controller.load();
    expect(controller.status, ProfileLoadStatus.failure);
    expect(controller.errorMessage, '个人资料加载失败');

    repository.failLoad = false;
    await controller.load(refresh: true);
    expect(controller.status, ProfileLoadStatus.ready);
  });

  test('phone is masked without losing international country code context', () {
    expect(maskedProfilePhone('+8613800138000'), '+86138****8000');
    expect(maskedProfilePhone(null), '未绑定手机号');
  });
}

final class _FakeProfileRepository implements ProfileRepository {
  bool failLoad = false;
  int updateCalls = 0;
  AuthUser user = _user('原昵称');

  @override
  Future<AuthUser> getProfile() async {
    if (failLoad) throw StateError('offline');
    return user;
  }

  @override
  Future<AuthUser> updateProfile(ProfileUpdate update) async {
    updateCalls++;
    user = _user(update.nickname.trim());
    return user;
  }
}

AuthUser _user(String nickname) => AuthUser(
      id: 'user-1',
      phone: '+8613800138000',
      email: null,
      nickname: nickname,
      avatar: '',
      gender: 'OTHER',
      city: '成都',
      bio: '个人简介',
      birthday: null,
      role: 'USER',
      hasPassword: true,
    );
