import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/wallet/domain/wallet_models.dart';
import 'package:joysong_flutter/features/wallet/presentation/wallet_controller.dart';

class WalletPage extends StatefulWidget {
  const WalletPage({required this.controller, super.key});

  final WalletController controller;

  @override
  State<WalletPage> createState() => _WalletPageState();
}

class _WalletPageState extends State<WalletPage> {
  final _scrollController = ScrollController();
  final _money = const UsdMoneyFormatter();

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_loadMoreWhenNeeded);
    unawaited(widget.controller.load());
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  void _loadMoreWhenNeeded() {
    if (_scrollController.position.extentAfter < 240) {
      widget.controller.loadNextPage();
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: widget.controller,
        builder: (context, _) => Scaffold(
          appBar: AppBar(title: Text(context.localized('钱包', 'Wallet'))),
          body: _body(context),
        ),
      );

  Widget _body(BuildContext context) {
    final controller = widget.controller;
    if (controller.isOverviewLoading && controller.overview.wallets.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (controller.overviewErrorMessage != null &&
        controller.overview.wallets.isEmpty) {
      return _MessageState(
        message: controller.overviewErrorMessage!,
        onRetry: controller.load,
      );
    }
    if (controller.overview.wallets.isEmpty) {
      return _MessageState(
        icon: Icons.account_balance_wallet_outlined,
        message: context.localized(
          '暂无可用钱包',
          'No professional wallet is available',
        ),
        detail: context.localized(
          '完成医生、医美顾问或机构法人认证后，可在此查看收益。',
          'Complete a professional identity verification to view earnings here.',
        ),
      );
    }
    final wallet = controller.selectedWallet;
    if (wallet == null) return const SizedBox.shrink();
    return RefreshIndicator(
      onRefresh: controller.refresh,
      child: ListView(
        controller: _scrollController,
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
        children: [
          if (controller.overviewErrorMessage != null) ...[
            _OverviewRetry(
              message: context.localized(
                '钱包刷新失败，当前显示上次数据',
                'Wallet refresh failed. Showing previously loaded data.',
              ),
              onRetry: controller.refresh,
            ),
            const SizedBox(height: 12),
          ],
          if (controller.overview.wallets.length > 1) ...[
            DropdownButtonFormField<int>(
              key: const Key('wallet-owner-selector'),
              initialValue: wallet.walletId,
              decoration: InputDecoration(
                labelText: context.localized('收益归属', 'Earnings owner'),
                border: const OutlineInputBorder(),
              ),
              items: controller.overview.wallets
                  .map((account) => DropdownMenuItem(
                        value: account.walletId,
                        child: Text(_selectorLabel(context, account)),
                      ))
                  .toList(growable: false),
              onChanged: (walletId) {
                if (walletId != null) {
                  unawaited(controller.selectWallet(walletId));
                }
              },
            ),
            const SizedBox(height: 12),
          ],
          _BalanceCard(
            wallet: wallet,
            money: _money,
            selectorLabel: _selectorLabel(context, wallet),
          ),
          const SizedBox(height: 12),
          SizedBox(
            height: 46,
            child: FilledButton.tonalIcon(
              key: const Key('wallet-withdraw-button'),
              onPressed: () => ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(
                  content: Text(context.localized(
                    '提现功能即将开放',
                    'Withdrawal coming soon',
                  )),
                ),
              ),
              icon: const Icon(Icons.account_balance_outlined),
              label: Text(context.localized('提现', 'Withdraw')),
            ),
          ),
          const SizedBox(height: 20),
          Text(
            context.localized('钱包流水', 'Wallet ledger'),
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 8),
          if (controller.isLedgerLoading && controller.entries.isEmpty)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Center(child: CircularProgressIndicator()),
            )
          else if (controller.ledgerErrorMessage != null &&
              controller.entries.isEmpty)
            _InlineRetry(
              message: controller.ledgerErrorMessage!,
              onRetry: controller.retryLedger,
            )
          else if (controller.entries.isEmpty)
            _EmptyLedger(
                message: context.localized('暂无流水记录', 'No ledger entries yet'))
          else ...[
            for (final entry in controller.entries)
              _LedgerTile(entry: entry, money: _money),
            if (controller.isLoadingMore)
              const Padding(
                padding: EdgeInsets.all(16),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (controller.ledgerErrorMessage != null)
              _InlineRetry(
                message: controller.ledgerErrorMessage!,
                onRetry: controller.retryLedger,
              )
            else if (controller.hasNextPage)
              Center(
                child: TextButton(
                  key: const Key('wallet-load-more-button'),
                  onPressed: controller.loadNextPage,
                  child: Text(context.localized('加载更多', 'Load more')),
                ),
              ),
          ],
        ],
      ),
    );
  }

  String _selectorLabel(BuildContext context, WalletAccount wallet) =>
      '${_walletTypeLabel(context, wallet.ownerType)} · ${wallet.ownerName}';

  String _walletTypeLabel(BuildContext context, String ownerType) =>
      switch (ownerType) {
        'DOCTOR' => context.localized('医生钱包', 'Doctor wallet'),
        'CONSULTANT' => context.localized('顾问钱包', 'Consultant wallet'),
        'INSTITUTION' => context.localized('机构钱包', 'Institution wallet'),
        _ => context.localized('钱包', 'Wallet'),
      };
}

class _BalanceCard extends StatelessWidget {
  const _BalanceCard({
    required this.wallet,
    required this.money,
    required this.selectorLabel,
  });

  final WalletAccount wallet;
  final UsdMoneyFormatter money;
  final String selectorLabel;

  @override
  Widget build(BuildContext context) => Card(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(selectorLabel,
                  style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: 12),
              Text(context.localized('可用余额', 'Available balance')),
              const SizedBox(height: 4),
              Text(money.format(wallet.availableMinor),
                  style: Theme.of(context).textTheme.headlineMedium),
              const SizedBox(height: 16),
              Row(
                children: [
                  Expanded(
                      child: _MinorBalance(
                          label: context.localized('待入账', 'Pending'),
                          value: money.format(wallet.pendingMinor))),
                  Expanded(
                      child: _MinorBalance(
                          label: context.localized('冻结', 'Frozen'),
                          value: money.format(wallet.frozenMinor))),
                ],
              ),
            ],
          ),
        ),
      );
}

class _MinorBalance extends StatelessWidget {
  const _MinorBalance({required this.label, required this.value});
  final String label;
  final String value;
  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 3),
          Text(value, style: Theme.of(context).textTheme.titleSmall),
        ],
      );
}

class _LedgerTile extends StatelessWidget {
  const _LedgerTile({required this.entry, required this.money});
  final WalletLedgerEntry entry;
  final UsdMoneyFormatter money;
  String _title(BuildContext context) => switch (entry.entryType) {
        'REVERSAL' => context.localized('退款冲正', 'Refund reversal'),
        'RELEASE' => context.localized('转入可用余额', 'Transferred to available'),
        _ => context.localized('诊疗收益', 'Earnings'),
      };

  String _description(BuildContext context) => switch (entry.sourceType) {
        'REFUND' => '${context.localized('退款', 'Refund')} ${entry.sourceId}',
        _ => '${context.localized('订单', 'Order')} ${entry.sourceId}',
      };

  @override
  Widget build(BuildContext context) => ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 4),
        title: Text(_title(context)),
        subtitle: Text(_description(context)),
        trailing: Text(money.format(entry.amountMinor),
            style: Theme.of(context).textTheme.titleSmall),
      );
}

class _OverviewRetry extends StatelessWidget {
  const _OverviewRetry({required this.message, required this.onRetry});
  final String message;
  final Future<void> Function() onRetry;

  @override
  Widget build(BuildContext context) => Material(
        color: Theme.of(context).colorScheme.errorContainer,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Row(children: [
            Expanded(child: Text(message)),
            TextButton(
              key: const Key('wallet-overview-retry-button'),
              onPressed: onRetry,
              child: Text(context.localized('重试', 'Retry')),
            ),
          ]),
        ),
      );
}

class _MessageState extends StatelessWidget {
  const _MessageState(
      {this.icon = Icons.error_outline,
      required this.message,
      this.detail,
      this.onRetry});
  final IconData icon;
  final String message;
  final String? detail;
  final Future<void> Function()? onRetry;
  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Icon(icon, size: 44),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
            if (detail != null) ...[
              const SizedBox(height: 8),
              Text(detail!, textAlign: TextAlign.center)
            ],
            if (onRetry != null)
              TextButton(
                  onPressed: onRetry,
                  child: Text(context.localized('重试', 'Retry'))),
          ]),
        ),
      );
}

class _InlineRetry extends StatelessWidget {
  const _InlineRetry({required this.message, required this.onRetry});
  final String message;
  final Future<void> Function() onRetry;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.all(16),
        child: Column(children: [
          Text(message, textAlign: TextAlign.center),
          TextButton(
              key: const Key('wallet-retry-button'),
              onPressed: onRetry,
              child: Text(context.localized('重试', 'Retry'))),
        ]),
      );
}

class _EmptyLedger extends StatelessWidget {
  const _EmptyLedger({required this.message});
  final String message;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.all(28),
        child: Center(child: Text(message)),
      );
}
