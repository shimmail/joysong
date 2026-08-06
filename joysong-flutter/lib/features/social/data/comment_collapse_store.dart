import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

abstract interface class CommentCollapseStore {
  Future<Set<String>> readCollapsedCommentIds();

  Future<void> writeCollapsedCommentIds(Set<String> ids);
}

final class SecureCommentCollapseStore implements CommentCollapseStore {
  const SecureCommentCollapseStore({
    FlutterSecureStorage storage = const FlutterSecureStorage(),
    String storageKey = defaultStorageKey,
  })  : _storage = storage,
        _storageKey = storageKey;

  static const defaultStorageKey = 'collapsed_comments';

  final FlutterSecureStorage _storage;
  final String _storageKey;

  @override
  Future<Set<String>> readCollapsedCommentIds() async {
    final value = await _storage.read(key: _storageKey);
    if (value == null || value.isEmpty) return <String>{};
    final decoded = jsonDecode(value);
    if (decoded is! List) return <String>{};
    return decoded.whereType<String>().toSet();
  }

  @override
  Future<void> writeCollapsedCommentIds(Set<String> ids) => _storage.write(
        key: _storageKey,
        value: jsonEncode(ids.toList(growable: false)..sort()),
      );
}
