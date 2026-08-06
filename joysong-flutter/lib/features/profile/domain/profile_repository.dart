import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/profile/domain/profile_models.dart';

abstract interface class ProfileRepository {
  Future<AuthUser> getProfile();

  Future<AuthUser> updateProfile(ProfileUpdate update);
}
