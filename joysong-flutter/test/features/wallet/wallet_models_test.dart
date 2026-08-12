import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/wallet/domain/wallet_models.dart';

void main() {
  test('parses USD wallet overview and preserves integer minor units', () {
    final overview = WalletOverview.fromJson({
      'currency': 'USD',
      'wallets': [
        {
          'walletId': 101,
          'ownerType': 'DOCTOR',
          'ownerId': 'doctor-1',
          'displayName': '医生钱包',
          'ownerName': '张医生',
          'pendingMinor': 9223372036854775807,
          'availableMinor': 123456,
          'frozenMinor': 0,
        },
      ],
    });

    expect(overview.currency, 'USD');
    expect(overview.wallets.single.pendingMinor, 9223372036854775807);
    expect(overview.wallets.single.availableMinor, 123456);
  });

  test('parses wallet ledger page with signed integer amount', () {
    final page = WalletLedgerPage.fromJson({
      'content': [
        {
          'id': 1001,
          'walletId': 101,
          'entryType': 'REFUND_REVERSAL',
          'title': '退款冲正',
          'description': '订单 JS1',
          'amountMinor': -1200,
          'pendingAfterMinor': 0,
          'availableAfterMinor': 100,
          'frozenAfterMinor': 0,
          'currency': 'USD',
          'createdAt': '2026-08-11T10:30:00',
        },
      ],
      'page': 0,
      'size': 20,
      'totalElements': 1,
      'totalPages': 1,
      'last': true,
    });

    expect(page.content.single.amountMinor, -1200);
    expect(page.last, isTrue);
  });

  test('rejects missing or non-boolean ledger last value', () {
    final page = {
      'content': const [],
      'page': 0,
      'size': 20,
      'totalElements': 0,
      'totalPages': 0,
    };

    expect(() => WalletLedgerPage.fromJson(page), throwsFormatException);
    expect(
      () => WalletLedgerPage.fromJson({...page, 'last': 'false'}),
      throwsFormatException,
    );
  });

  test('formats USD minor units without floating point rounding', () {
    const formatter = UsdMoneyFormatter();

    expect(formatter.format(123456), r'$1,234.56');
    expect(formatter.format(-1200), r'-$12.00');
    expect(formatter.format(-1), r'-$0.01');
  });
}
