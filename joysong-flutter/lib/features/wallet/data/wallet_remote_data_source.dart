import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/wallet/domain/wallet_models.dart';

abstract interface class WalletRemoteDataSource {
  Future<WalletOverview> getOverview();

  Future<WalletLedgerPage> getLedger({
    required int walletId,
    required int page,
    required int size,
  });
}

final class ApiWalletRemoteDataSource implements WalletRemoteDataSource {
  const ApiWalletRemoteDataSource(this._apiClient);

  final ApiClient _apiClient;

  @override
  Future<WalletOverview> getOverview() async {
    final result = await _apiClient.get<WalletOverview>(
      'wallets/me',
      decodeData: WalletOverview.fromJson,
    );
    return result ?? (throw const FormatException('钱包概览 data 为空'));
  }

  @override
  Future<WalletLedgerPage> getLedger({
    required int walletId,
    required int page,
    required int size,
  }) async {
    final result = await _apiClient.get<WalletLedgerPage>(
      'wallets/me/ledger',
      query: {'walletId': walletId, 'page': page, 'size': size},
      decodeData: WalletLedgerPage.fromJson,
    );
    return result ?? (throw const FormatException('钱包流水 data 为空'));
  }
}
