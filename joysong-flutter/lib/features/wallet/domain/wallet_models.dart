final class WalletOverview {
  const WalletOverview({required this.currency, required this.wallets});

  factory WalletOverview.fromJson(Object? json) {
    final map = _map(json, '钱包概览');
    return WalletOverview(
      currency: _text(map['currency'], 'currency'),
      wallets: _list(map['wallets'], 'wallets')
          .map(WalletAccount.fromJson)
          .toList(growable: false),
    );
  }

  final String currency;
  final List<WalletAccount> wallets;
}

final class WalletAccount {
  const WalletAccount({
    required this.walletId,
    required this.ownerType,
    required this.ownerId,
    required this.displayName,
    required this.ownerName,
    required this.pendingMinor,
    required this.availableMinor,
    required this.frozenMinor,
  });

  factory WalletAccount.fromJson(Object? json) {
    final map = _map(json, '钱包');
    return WalletAccount(
      walletId: _integer(map['walletId'], 'walletId'),
      ownerType: _text(map['ownerType'], 'ownerType'),
      ownerId: _text(map['ownerId'], 'ownerId'),
      displayName: _text(map['displayName'], 'displayName'),
      ownerName: _text(map['ownerName'], 'ownerName'),
      pendingMinor: _integer(map['pendingMinor'], 'pendingMinor'),
      availableMinor: _integer(map['availableMinor'], 'availableMinor'),
      frozenMinor: _integer(map['frozenMinor'], 'frozenMinor'),
    );
  }

  final int walletId;
  final String ownerType;
  final String ownerId;
  final String displayName;
  final String ownerName;
  final int pendingMinor;
  final int availableMinor;
  final int frozenMinor;

  String get selectorLabel => '$displayName · $ownerName';
}

final class WalletLedgerEntry {
  const WalletLedgerEntry({
    required this.id,
    required this.walletId,
    required this.entryType,
    required this.title,
    required this.description,
    required this.amountMinor,
    required this.pendingAfterMinor,
    required this.availableAfterMinor,
    required this.frozenAfterMinor,
    required this.currency,
    required this.createdAt,
  });

  factory WalletLedgerEntry.fromJson(Object? json) {
    final map = _map(json, '钱包流水');
    return WalletLedgerEntry(
      id: _integer(map['id'], 'id'),
      walletId: _integer(map['walletId'], 'walletId'),
      entryType: _text(map['entryType'], 'entryType'),
      title: _text(map['title'], 'title'),
      description: _text(map['description'], 'description'),
      amountMinor: _integer(map['amountMinor'], 'amountMinor'),
      pendingAfterMinor: _integer(map['pendingAfterMinor'], 'pendingAfterMinor'),
      availableAfterMinor: _integer(map['availableAfterMinor'], 'availableAfterMinor'),
      frozenAfterMinor: _integer(map['frozenAfterMinor'], 'frozenAfterMinor'),
      currency: _text(map['currency'], 'currency'),
      createdAt: DateTime.parse(_text(map['createdAt'], 'createdAt')),
    );
  }

  final int id;
  final int walletId;
  final String entryType;
  final String title;
  final String description;
  final int amountMinor;
  final int pendingAfterMinor;
  final int availableAfterMinor;
  final int frozenAfterMinor;
  final String currency;
  final DateTime createdAt;
}

final class WalletLedgerPage {
  const WalletLedgerPage({
    required this.content,
    required this.page,
    required this.size,
    required this.totalElements,
    required this.totalPages,
    required this.last,
  });

  factory WalletLedgerPage.fromJson(Object? json) {
    final map = _map(json, '钱包流水页');
    return WalletLedgerPage(
      content: _list(map['content'], 'content')
          .map(WalletLedgerEntry.fromJson)
          .toList(growable: false),
      page: _integer(map['page'], 'page'),
      size: _integer(map['size'], 'size'),
      totalElements: _integer(map['totalElements'], 'totalElements'),
      totalPages: _integer(map['totalPages'], 'totalPages'),
      last: map['last'] == true,
    );
  }

  final List<WalletLedgerEntry> content;
  final int page;
  final int size;
  final int totalElements;
  final int totalPages;
  final bool last;
}

final class UsdMoneyFormatter {
  const UsdMoneyFormatter();

  String format(int minor) {
    final negative = minor < 0;
    final absolute = minor.abs();
    final dollars = absolute ~/ 100;
    final cents = absolute % 100;
    final digits = dollars.toString();
    final groups = <String>[];
    for (var end = digits.length; end > 0; end -= 3) {
      final start = end > 3 ? end - 3 : 0;
      groups.add(digits.substring(start, end));
    }
    return '${negative ? '-' : ''}\$${groups.reversed.join(',')}.${cents.toString().padLeft(2, '0')}';
  }
}

Map<String, Object?> _map(Object? value, String label) {
  if (value is Map<String, Object?>) return value;
  if (value is Map) return Map<String, Object?>.from(value);
  throw FormatException('$label不是 JSON 对象');
}

List<Object?> _list(Object? value, String field) {
  if (value is List) return value;
  throw FormatException('$field不是 JSON 数组');
}

String _text(Object? value, String field) {
  final result = value?.toString().trim() ?? '';
  if (result.isEmpty) throw FormatException('响应缺少 $field');
  return result;
}

int _integer(Object? value, String field) => switch (value) {
      final int number => number,
      final num number when number == number.truncateToDouble() => number.toInt(),
      final String text => int.tryParse(text) ?? (throw FormatException('$field不是整数')),
      _ => throw FormatException('$field不是整数'),
    };
