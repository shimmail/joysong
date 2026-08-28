import 'dart:convert';

import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';
import 'package:joysong_flutter/features/auth/data/secure_token_store.dart';

export 'package:joysong_flutter/features/account_security/domain/account_security_models.dart'
    show PendingAccountDeletion;

abstract interface class AccountDeletionPendingStore {
  Future<PendingAccountDeletion?> read();

  Future<void> save(PendingAccountDeletion pending);

  Future<void> clear();
}

final class AccountDeletionPendingCorruptedException implements Exception {
  const AccountDeletionPendingCorruptedException();

  @override
  String toString() => 'AccountDeletionPendingCorruptedException';
}

final class SecureAccountDeletionPendingStore
    implements AccountDeletionPendingStore {
  SecureAccountDeletionPendingStore({SecureKeyValueStore? storage})
      : _storage = storage ?? FlutterSecureKeyValueStore();

  static const storageKey = 'joysong.account_deletion.pending.v1';

  final SecureKeyValueStore _storage;

  @override
  Future<PendingAccountDeletion?> read() async {
    final raw = await _storage.read(storageKey);
    if (raw == null) return null;
    if (raw.trim().isEmpty) {
      throw const AccountDeletionPendingCorruptedException();
    }
    try {
      return PendingAccountDeletion.fromJson(jsonDecode(raw));
    } on FormatException {
      throw const AccountDeletionPendingCorruptedException();
    } on TypeError {
      throw const AccountDeletionPendingCorruptedException();
    }
  }

  @override
  Future<void> save(PendingAccountDeletion pending) =>
      _storage.write(storageKey, jsonEncode(pending.toJson()));

  @override
  Future<void> clear() => _storage.delete(storageKey);
}
