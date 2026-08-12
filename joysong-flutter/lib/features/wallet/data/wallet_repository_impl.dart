import 'package:joysong_flutter/features/wallet/data/wallet_remote_data_source.dart';
import 'package:joysong_flutter/features/wallet/domain/wallet_models.dart';
import 'package:joysong_flutter/features/wallet/domain/wallet_repository.dart';

final class WalletRepositoryImpl implements WalletRepository {
  const WalletRepositoryImpl(this._remote);

  final WalletRemoteDataSource _remote;

  @override
  Future<WalletOverview> getOverview() => _remote.getOverview();

  @override
  Future<WalletLedgerPage> getLedger({
    required int walletId,
    required int page,
    required int size,
  }) =>
      _remote.getLedger(walletId: walletId, page: page, size: size);
}
