import 'package:joysong_flutter/features/wallet/domain/wallet_models.dart';

abstract interface class WalletRepository {
  Future<WalletOverview> getOverview();

  Future<WalletLedgerPage> getLedger({
    required int walletId,
    required int page,
    required int size,
  });
}
