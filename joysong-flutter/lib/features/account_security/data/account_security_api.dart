import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
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

  Future<AccountDeletionPreflight> preflightAccountDeletion();

  Future<void> sendAccountDeletionSmsCode(String requestId);

  Future<AccountDeletionAuthorization> stepUpAccountDeletionWithSms({
    required String requestId,
    required String code,
  });

  Future<AccountDeletionAuthorization> stepUpAccountDeletionWithGoogle({
    required String requestId,
    required String idToken,
  });

  Future<AccountDeletionConfirmation> confirmAccountDeletion(
    PendingAccountDeletion pending,
  );
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
  Future<AccountDeletionPreflight> preflightAccountDeletion() async {
    final result = await _apiClient.post<AccountDeletionPreflight>(
      'user/account-deletion/preflight',
      decodeData: AccountDeletionPreflight.fromJson,
    );
    if (result == null) {
      throw const FormatException('注销预检结果为空');
    }
    return result;
  }

  @override
  Future<void> sendAccountDeletionSmsCode(String requestId) async {
    await _apiClient.post<Object?>(
      'user/account-deletion/send-sms-code',
      body: {'requestId': requestId},
      decodeData: (json) => json,
    );
  }

  @override
  Future<AccountDeletionAuthorization> stepUpAccountDeletionWithSms({
    required String requestId,
    required String code,
  }) async {
    final result = await _apiClient.post<AccountDeletionAuthorization>(
      'user/account-deletion/step-up',
      body: {'requestId': requestId, 'code': code},
      decodeData: AccountDeletionAuthorization.fromJson,
    );
    if (result == null) throw const FormatException('注销授权结果为空');
    return result;
  }

  @override
  Future<AccountDeletionAuthorization> stepUpAccountDeletionWithGoogle({
    required String requestId,
    required String idToken,
  }) async {
    final result = await _apiClient.post<AccountDeletionAuthorization>(
      'user/account-deletion/step-up',
      body: {'requestId': requestId, 'googleIdToken': idToken},
      decodeData: AccountDeletionAuthorization.fromJson,
    );
    if (result == null) throw const FormatException('注销授权结果为空');
    return result;
  }

  @override
  Future<AccountDeletionConfirmation> confirmAccountDeletion(
    PendingAccountDeletion pending,
  ) async {
    Future<AccountDeletionConfirmation?> send({required bool withBearer}) {
      return _apiClient.postIdempotentWithHeaders<AccountDeletionConfirmation>(
        'user/account-deletion/confirm',
        idempotencyKey: pending.idempotencyKey,
        headers: {
          'X-Account-Deletion-Authorization': pending.deletionAuthorization,
        },
        body: {
          'requestId': pending.requestId,
          'policyVersion': pending.policyVersion,
          'confirmation': 'DELETE',
        },
        decodeData: AccountDeletionConfirmation.fromJson,
        includeAccessToken: withBearer,
      );
    }

    AccountDeletionConfirmation? result;
    try {
      result = await send(withBearer: true);
    } on ApiException catch (error) {
      if (!error.isUnauthorized) rethrow;
      // After an erased account invalidates its JWT, the exact same terminal
      // request is queried without Bearer. The server must never treat this as
      // permission for a new mutation.
      result = await send(withBearer: false);
    }
    if (result == null) {
      throw const FormatException('注销确认结果为空');
    }
    if (result.requestId != pending.requestId) {
      throw const FormatException('注销确认 requestId 不匹配');
    }
    if (result.outcome != AccountDeletionOutcome.erased) {
      throw const FormatException('注销确认结果不是 ERASED');
    }
    return result;
  }
}
