import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/public_media_url.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/profile/domain/profile_models.dart';
import 'package:joysong_flutter/features/profile/domain/profile_repository.dart';

final class ApiProfileRepository implements ProfileRepository {
  ApiProfileRepository(this._apiClient)
      : _mediaUrlResolver = ApiPublicMediaUrlResolver(_apiClient.apiRoot);

  final ApiClient _apiClient;
  final PublicMediaUrlResolver _mediaUrlResolver;

  @override
  Future<AuthUser> getProfile() async {
    final profile = await _apiClient.get<AuthUser>(
      'user/profile',
      decodeData: (json) => AuthUser.fromJson(_profileMap(json)),
    );
    if (profile == null) {
      throw const FormatException('用户资料响应为空');
    }
    return profile;
  }

  @override
  Future<AuthUser> updateProfile(ProfileUpdate update) async {
    final profile = await _apiClient.put<AuthUser>(
      'user/profile',
      body: update.toJson(),
      decodeData: (json) => AuthUser.fromJson(_profileMap(json)),
    );
    if (profile == null) {
      throw const FormatException('用户资料响应为空');
    }
    return profile;
  }

  Map<String, dynamic> _profileMap(Object? json) => _map(
        resolvePublicMediaUrlsInJson(json, resolver: _mediaUrlResolver),
        '用户资料',
      );
}

Map<String, dynamic> _map(Object? value, String label) {
  if (value is! Map) throw FormatException('$label不是 JSON 对象');
  return value.cast<String, dynamic>();
}
