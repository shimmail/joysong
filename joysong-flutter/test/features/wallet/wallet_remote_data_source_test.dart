import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/wallet/data/wallet_remote_data_source.dart';

void main() {
  test('requests wallet overview through the API envelope', () async {
    final client = _FakeApiClient()
      ..responseData = {
        'currency': 'USD',
        'wallets': [_walletJson()],
      };
    final source = ApiWalletRemoteDataSource(client);

    final overview = await source.getOverview();

    expect(client.lastPath, 'wallets/me');
    expect(client.lastQuery, isEmpty);
    expect(overview.wallets.single.walletId, 101);
  });

  test('requests a wallet-specific ledger page', () async {
    final client = _FakeApiClient()
      ..responseData = {
        'content': [_ledgerJson()],
        'page': 0,
        'size': 20,
        'totalElements': 1,
        'totalPages': 1,
        'last': true,
      };
    final source = ApiWalletRemoteDataSource(client);

    final page = await source.getLedger(walletId: 101, page: 0, size: 20);

    expect(client.lastPath, 'wallets/me/ledger');
    expect(client.lastQuery, {'walletId': 101, 'page': 0, 'size': 20});
    expect(page.content.single.amountMinor, 1200);
  });

  test('propagates malformed API data', () async {
    final client = _FakeApiClient()..responseData = {'currency': 'USD'};
    final source = ApiWalletRemoteDataSource(client);

    expect(source.getOverview(), throwsFormatException);
  });
}

Map<String, Object?> _walletJson() => {
      'walletId': 101,
      'ownerType': 'DOCTOR',
      'ownerId': 'doctor-1',
      'displayName': '医生钱包',
      'ownerName': '张医生',
      'pendingMinor': 0,
      'availableMinor': 1200,
      'frozenMinor': 0,
    };

Map<String, Object?> _ledgerJson() => {
      'id': 1,
      'walletId': 101,
      'entryType': 'SETTLEMENT_CREDIT',
      'title': '诊疗收益',
      'description': '订单 JS1',
      'amountMinor': 1200,
      'pendingAfterMinor': 0,
      'availableAfterMinor': 1200,
      'frozenAfterMinor': 0,
      'currency': 'USD',
      'createdAt': '2026-08-11T10:30:00',
    };

final class _FakeApiClient extends ApiClient {
  _FakeApiClient() : super(apiRoot: Uri.parse('http://localhost/api/'));

  String? lastPath;
  Map<String, Object?> lastQuery = const {};
  Object? responseData;

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    lastPath = path;
    lastQuery = query;
    return decodeData(responseData);
  }
}
