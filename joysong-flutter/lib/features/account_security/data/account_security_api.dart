import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';

abstract interface class AccountSecurityRemoteDataSource {
  Future<AccountSecurityProfile> getProfile();

  Future<void> changePassword({
    required String oldPassword,
    required String newPassword,
  });

  Future<void> sendPasswordCode(String phone);

  Future<void> resetPassword({
    required String phone,
    required String code,
    required String newPassword,
  });

  Future<void> setPassword({
    required String phone,
    required String code,
    required String newPassword,
  });

  Future<void> sendCurrentPhoneChangeCode();

  Future<void> verifyCurrentPhoneChangeCode(String code);

  Future<void> sendNewPhoneChangeCode(String phone);

  Future<void> changePhone({required String phone, required String code});

  Future<void> sendBindingPhoneCode(String phone);

  Future<void> bindPhone({required String phone, required String code});

  Future<void> deleteAccount();
}

final class ApiAccountSecurityRemoteDataSource
    implements AccountSecurityRemoteDataSource {
  const ApiAccountSecurityRemoteDataSource(this._apiClient);

  final ApiClient _apiClient;

  @override
  Future<AccountSecurityProfile> getProfile() async {
    final profile = await _apiClient.get<AccountSecurityProfile>(
      'user/profile',
      decodeData: AccountSecurityProfile.fromJson,
    );
    if (profile == null) {
      throw const FormatException('账号资料 data 为空');
    }
    return profile;
  }

  @override
  Future<void> changePassword({
    required String oldPassword,
    required String newPassword,
  }) async {
    await _apiClient.put<Object?>(
      'user/password',
      body: {'oldPassword': oldPassword, 'newPassword': newPassword},
      decodeData: (json) => json,
    );
  }

  @override
  Future<void> sendPasswordCode(String phone) async {
    await _apiClient.post<Object?>(
      'auth/send-code',
      body: {'phone': phone},
      decodeData: (json) => json,
    );
  }

  @override
  Future<void> resetPassword({
    required String phone,
    required String code,
    required String newPassword,
  }) async {
    await _apiClient.post<Object?>(
      'auth/reset-password',
      body: {'phone': phone, 'code': code, 'newPassword': newPassword},
      decodeData: (json) => json,
    );
  }

  @override
  Future<void> setPassword({
    required String phone,
    required String code,
    required String newPassword,
  }) async {
    await _apiClient.put<Object?>(
      'user/password/set',
      body: {'phone': phone, 'code': code, 'newPassword': newPassword},
      decodeData: (json) => json,
    );
  }

  @override
  Future<void> sendCurrentPhoneChangeCode() async {
    await _apiClient.post<Object?>(
      'user/phone-change/send-current-code',
      decodeData: (json) => json,
    );
  }

  @override
  Future<void> verifyCurrentPhoneChangeCode(String code) async {
    await _apiClient.post<Object?>(
      'user/phone-change/verify-current-code',
      body: {'code': code},
      decodeData: (json) => json,
    );
  }

  @override
  Future<void> sendNewPhoneChangeCode(String phone) async {
    await _apiClient.post<Object?>(
      'user/phone-change/send-new-code',
      body: {'phone': phone},
      decodeData: (json) => json,
    );
  }

  @override
  Future<void> changePhone(
      {required String phone, required String code}) async {
    await _apiClient.put<Object?>(
      'user/phone-change',
      body: {'phone': phone, 'code': code},
      decodeData: (json) => json,
    );
  }

  @override
  Future<void> sendBindingPhoneCode(String phone) async {
    await _apiClient.post<Object?>(
      'auth/send-code',
      body: {'phone': phone},
      decodeData: (json) => json,
    );
  }

  @override
  Future<void> bindPhone({required String phone, required String code}) async {
    await _apiClient.post<Object?>(
      'user/bind-phone',
      body: {'phone': phone, 'code': code},
      decodeData: (json) => json,
    );
  }

  @override
  Future<void> deleteAccount() async {
    await _apiClient.delete<Object?>(
      'user/account',
      decodeData: (json) => json,
    );
  }
}
