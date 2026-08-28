import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/account_security/data/account_deletion_pending_store.dart';
import 'package:joysong_flutter/features/auth/data/secure_token_store.dart';

void main() {
  test('round-trips the exact resumable deletion request in secure storage',
      () async {
    final storage = _MemorySecureStorage();
    final store = SecureAccountDeletionPendingStore(storage: storage);
    const pending = PendingAccountDeletion(
      requestId: 'request-1',
      idempotencyKey: 'delete-key-1',
      deletionAuthorization: 'delete-auth-1',
      policyVersion: 'dev-v1',
      userId: 'user-1',
    );

    await store.save(pending);

    expect(await store.read(), pending);
    expect(storage.values.length, 1);
    expect(storage.values.values.single, isNot(contains('Bearer')));
  });

  test('retains malformed non-empty pending state and fails closed', () async {
    final storage = _MemorySecureStorage()
      ..values['joysong.account_deletion.pending.v1'] = '{bad-json';
    final store = SecureAccountDeletionPendingStore(storage: storage);

    await expectLater(
      store.read(),
      throwsA(isA<AccountDeletionPendingCorruptedException>()),
    );
    expect(
      storage.values['joysong.account_deletion.pending.v1'],
      '{bad-json',
    );
  });

  test('treats a persisted whitespace marker as corrupted', () async {
    final storage = _MemorySecureStorage()
      ..values['joysong.account_deletion.pending.v1'] = '   ';
    final store = SecureAccountDeletionPendingStore(storage: storage);

    await expectLater(
      store.read(),
      throwsA(isA<AccountDeletionPendingCorruptedException>()),
    );
    expect(storage.values.values.single, '   ');
  });
}

final class _MemorySecureStorage implements SecureKeyValueStore {
  final values = <String, String>{};

  @override
  Future<void> delete(String key) async => values.remove(key);

  @override
  Future<String?> read(String key) async => values[key];

  @override
  Future<void> write(String key, String value) async => values[key] = value;
}
