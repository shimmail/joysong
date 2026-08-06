import 'dart:convert';

import 'package:joysong_flutter/features/auth/data/secure_token_store.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';

class SavedAccount {
  const SavedAccount({
    required this.userId,
    required this.nickname,
    required this.avatar,
    required this.identifier,
    required this.tokens,
  });
  final String userId;
  final String nickname;
  final String avatar;
  final String identifier;
  final AuthTokens tokens;
}

abstract interface class SavedAccountStore {
  Future<List<SavedAccount>> read();
  Future<void> save(List<SavedAccount> accounts);
}

final class SecureSavedAccountStore implements SavedAccountStore {
  SecureSavedAccountStore({required SecureKeyValueStore storage})
      : _storage = storage;
  static const _key = 'joysong.auth.saved_accounts.v1';
  final SecureKeyValueStore _storage;

  @override
  Future<List<SavedAccount>> read() async {
    final raw = await _storage.read(_key);
    if (raw == null || raw.isEmpty) return const [];
    try {
      final values = jsonDecode(raw) as List;
      return values.whereType<Map>().map((value) {
        final map = value.cast<String, dynamic>();
        return SavedAccount(
          userId: map['userId']?.toString() ?? '',
          nickname: map['nickname']?.toString() ?? '',
          avatar: map['avatar']?.toString() ?? '',
          identifier: map['identifier']?.toString() ?? '',
          tokens: AuthTokens.fromJson(
            (map['tokens'] as Map).cast<String, dynamic>(),
          ),
        );
      }).where((account) => account.userId.isNotEmpty).toList();
    } catch (_) {
      await _storage.delete(_key);
      return const [];
    }
  }

  @override
  Future<void> save(List<SavedAccount> accounts) => _storage.write(
        _key,
        jsonEncode(accounts.take(5).map((account) => {
              'userId': account.userId,
              'nickname': account.nickname,
              'avatar': account.avatar,
              'identifier': account.identifier,
              'tokens': {
                'accessToken': account.tokens.accessToken,
                'refreshToken': account.tokens.refreshToken,
                'tokenType': account.tokens.tokenType,
                'expiresIn': account.tokens.expiresIn,
              },
            }).toList()),
      );
}
