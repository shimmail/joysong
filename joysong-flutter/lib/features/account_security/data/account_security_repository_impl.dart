import 'package:joysong_flutter/features/account_security/data/account_security_api.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_repository.dart';

final class AccountSecurityRepositoryImpl implements AccountSecurityRepository {
  const AccountSecurityRepositoryImpl(this._remoteDataSource);

  final AccountSecurityRemoteDataSource _remoteDataSource;

  @override
  Future<AccountSecurityProfile> getProfile() => _remoteDataSource.getProfile();

  @override
  Future<void> changePassword({
    required String oldPassword,
    required String newPassword,
  }) {
    if (oldPassword.isEmpty) {
      throw ArgumentError.value(oldPassword, 'oldPassword', '请输入原密码');
    }
    return _remoteDataSource.changePassword(
      oldPassword: oldPassword,
      newPassword: validateNewPassword(newPassword),
    );
  }

  @override
  Future<void> sendPasswordCode(String phone) =>
      _remoteDataSource.sendPasswordCode(validateE164Phone(phone));

  @override
  Future<void> resetPassword({
    required String phone,
    required String code,
    required String newPassword,
  }) =>
      _remoteDataSource.resetPassword(
        phone: validateE164Phone(phone),
        code: validateVerificationCode(code),
        newPassword: validateNewPassword(newPassword),
      );

  @override
  Future<void> setPassword({
    required String phone,
    required String code,
    required String newPassword,
  }) =>
      _remoteDataSource.setPassword(
        phone: validateE164Phone(phone),
        code: validateVerificationCode(code),
        newPassword: validateNewPassword(newPassword),
      );

  @override
  Future<void> sendCurrentPhoneChangeCode() =>
      _remoteDataSource.sendCurrentPhoneChangeCode();

  @override
  Future<void> verifyCurrentPhoneChangeCode(String code) =>
      _remoteDataSource.verifyCurrentPhoneChangeCode(
        validateVerificationCode(code),
      );

  @override
  Future<void> sendNewPhoneChangeCode(String phone) =>
      _remoteDataSource.sendNewPhoneChangeCode(validateE164Phone(phone));

  @override
  Future<void> changePhone({required String phone, required String code}) =>
      _remoteDataSource.changePhone(
        phone: validateE164Phone(phone),
        code: validateVerificationCode(code),
      );

  @override
  Future<void> sendBindingPhoneCode(String phone) =>
      _remoteDataSource.sendBindingPhoneCode(validateE164Phone(phone));

  @override
  Future<void> bindPhone({required String phone, required String code}) =>
      _remoteDataSource.bindPhone(
        phone: validateE164Phone(phone),
        code: validateVerificationCode(code),
      );

  @override
  Future<AccountDeletionPreflight> preflightAccountDeletion() =>
      _remoteDataSource.preflightAccountDeletion();

  @override
  Future<void> sendAccountDeletionSmsCode(String requestId) =>
      _remoteDataSource.sendAccountDeletionSmsCode(_requiredText(
        requestId,
        'requestId',
      ));

  @override
  Future<AccountDeletionAuthorization> stepUpAccountDeletionWithSms({
    required String requestId,
    required String code,
  }) =>
      _remoteDataSource.stepUpAccountDeletionWithSms(
        requestId: _requiredText(requestId, 'requestId'),
        code: validateVerificationCode(code),
      );

  @override
  Future<AccountDeletionAuthorization> stepUpAccountDeletionWithGoogle({
    required String requestId,
    required String idToken,
  }) =>
      _remoteDataSource.stepUpAccountDeletionWithGoogle(
        requestId: _requiredText(requestId, 'requestId'),
        idToken: _requiredText(idToken, 'googleIdToken'),
      );

  @override
  Future<AccountDeletionConfirmation> confirmAccountDeletion(
    PendingAccountDeletion pending,
  ) =>
      _remoteDataSource.confirmAccountDeletion(pending);
}

String _requiredText(String value, String name) {
  final normalized = value.trim();
  if (normalized.isEmpty) {
    throw ArgumentError.value(value, name, '$name 不能为空');
  }
  return normalized;
}
