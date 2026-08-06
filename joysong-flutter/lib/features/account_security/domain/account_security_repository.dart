import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';

abstract interface class AccountSecurityRepository {
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
