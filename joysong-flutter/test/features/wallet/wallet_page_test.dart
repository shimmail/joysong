import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/wallet/domain/wallet_models.dart';
import 'package:joysong_flutter/features/wallet/domain/wallet_repository.dart';
import 'package:joysong_flutter/features/wallet/presentation/wallet_controller.dart';
import 'package:joysong_flutter/features/wallet/presentation/wallet_page.dart';

void main() {
  testWidgets('shows the no-professional-identity state', (tester) async {
    await tester.pumpWidget(_walletApp(WalletController(_WalletRepository())));
    await tester.pumpAndSettle();

    expect(find.text('暂无可用钱包'), findsOneWidget);
    expect(find.byKey(const Key('wallet-owner-selector')), findsNothing);
  });

  testWidgets('shows one wallet balances and an empty ledger without selector',
      (tester) async {
    final repository = _WalletRepository()
      ..overview = _overview(_wallet(101))
      ..pages[(101, 0)] = _page(101, const [], last: true);
    await tester.pumpWidget(_walletApp(WalletController(repository)));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('wallet-owner-selector')), findsNothing);
    expect(find.text(r'$850.00'), findsOneWidget);
    expect(find.text(r'$120.00'), findsOneWidget);
    expect(find.text(r'$5.00'), findsOneWidget);
    expect(find.text('暂无流水记录'), findsOneWidget);
  });

  testWidgets('selects independent institution wallet and paginates ledger',
      (tester) async {
    final institution = _wallet(
      202,
      ownerType: 'INSTITUTION',
      displayName: 'INSTITUTION',
      ownerName: '娇颜颂医疗机构',
      available: 9900,
    );
    final repository = _WalletRepository()
      ..overview = _overview(_wallet(101), institution)
      ..pages[(101, 0)] = _page(101, [_entry(1, 101)], last: true)
      ..pages[(202, 0)] = _page(202, [_entry(2, 202)], last: false)
      ..pages[(202, 1)] = _page(202, [_entry(3, 202)], last: true);
    await tester.pumpWidget(_walletApp(WalletController(repository)));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('wallet-owner-selector')), findsOneWidget);
    await tester.tap(find.byKey(const Key('wallet-owner-selector')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('机构钱包 · 娇颜颂医疗机构').last);
    await tester.pumpAndSettle();
    expect(find.text(r'$99.00'), findsOneWidget);
    expect(find.text('诊疗收益'), findsOneWidget);
    expect(find.text('订单 JS2'), findsOneWidget);

    await tester.tap(find.byKey(const Key('wallet-load-more-button')));
    await tester.pumpAndSettle();
    expect(find.text('订单 JS3'), findsOneWidget);
  });

  testWidgets('shows ledger retry and local withdrawal coming-soon message',
      (tester) async {
    final repository = _WalletRepository()
      ..overview = _overview(_wallet(101))
      ..errors[(101, 0)] = StateError('offline');
    await tester.pumpWidget(_walletApp(WalletController(repository)));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('wallet-retry-button')), findsOneWidget);
    repository.errors.clear();
    repository.pages[(101, 0)] = _page(101, [_entry(1, 101)], last: true);
    await tester.tap(find.byKey(const Key('wallet-retry-button')));
    await tester.pumpAndSettle();
    expect(find.text('诊疗收益'), findsOneWidget);
    expect(find.text('订单 JS1'), findsOneWidget);

    await tester.tap(find.byKey(const Key('wallet-withdraw-button')));
    await tester.pump();
    expect(find.text('提现功能即将开放'), findsOneWidget);
    await tester.tap(find.byKey(const Key('wallet-withdraw-button')));
    await tester.tap(find.byKey(const Key('wallet-withdraw-button')));
    await tester.pump();
    expect(find.text('提现功能即将开放'), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(seconds: 2));
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('提现功能即将开放'), findsNothing);
    expect(repository.withdrawCalls, 0);
  });

  testWidgets('localizes the withdrawal coming-soon message in English',
      (tester) async {
    final repository = _WalletRepository()
      ..overview = _overview(_wallet(101))
      ..pages[(101, 0)] = _page(101, [_entry(1, 101)], last: true);
    await tester.pumpWidget(_walletApp(
      WalletController(repository),
      locale: const Locale('en'),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('wallet-withdraw-button')));
    await tester.pump();

    expect(find.text('Withdrawal coming soon'), findsOneWidget);
  });

  testWidgets('shows retained wallet data with refresh error and retry',
      (tester) async {
    final repository = _WalletRepository()
      ..overview = _overview(_wallet(101))
      ..pages[(101, 0)] = _page(101, [_entry(1, 101)], last: true);
    final controller = WalletController(repository);
    await tester.pumpWidget(_walletApp(controller));
    await tester.pumpAndSettle();
    repository.overviewError = StateError('offline');

    await controller.refresh();
    await tester.pumpAndSettle();

    expect(find.text(r'$850.00'), findsOneWidget);
    expect(
        find.byKey(const Key('wallet-overview-retry-button')), findsOneWidget);
  });

  testWidgets('localizes semantic wallet and ledger labels in English',
      (tester) async {
    final repository = _WalletRepository()
      ..overview = _overview(_wallet(101))
      ..pages[(101, 0)] = _page(101, [_entry(1, 101)], last: true);

    await tester.pumpWidget(_walletApp(
      WalletController(repository),
      locale: const Locale('en'),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Doctor wallet · 张医生'), findsWidgets);
    expect(find.text('Earnings'), findsOneWidget);
    expect(find.text('Order JS1'), findsOneWidget);
    expect(find.textContaining('钱包'), findsNothing);
    expect(find.textContaining('流水'), findsNothing);
    expect(find.textContaining('订单'), findsNothing);
  });
}

Widget _walletApp(WalletController controller,
        {Locale locale = const Locale('zh')}) =>
    MaterialApp(
      locale: locale,
      supportedLocales: const [Locale('zh'), Locale('en')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: WalletPage(controller: controller),
    );

WalletOverview _overview(WalletAccount first, [WalletAccount? second]) =>
    WalletOverview(
        currency: 'USD', wallets: [first, if (second != null) second]);

WalletAccount _wallet(
  int id, {
  String ownerType = 'DOCTOR',
  String displayName = 'DOCTOR',
  String ownerName = '张医生',
  int available = 85000,
}) =>
    WalletAccount(
      walletId: id,
      ownerType: ownerType,
      ownerId: 'owner-$id',
      displayName: displayName,
      ownerName: ownerName,
      pendingMinor: 12000,
      availableMinor: available,
      frozenMinor: 500,
    );

WalletLedgerEntry _entry(int id, int walletId) => WalletLedgerEntry(
      id: id,
      walletId: walletId,
      entryType: 'SETTLEMENT',
      title: 'SETTLEMENT',
      description: 'ORDER:JS$id',
      sourceType: 'ORDER',
      sourceId: 'JS$id',
      amountMinor: 1200,
      pendingAfterMinor: 0,
      availableAfterMinor: 0,
      frozenAfterMinor: 0,
      currency: 'USD',
      createdAt: DateTime(2026, 8, 11),
    );

WalletLedgerPage _page(int walletId, List<WalletLedgerEntry> content,
        {required bool last}) =>
    WalletLedgerPage(
      content: content,
      page: 0,
      size: 20,
      totalElements: content.length,
      totalPages: last ? 1 : 2,
      last: last,
    );

final class _WalletRepository implements WalletRepository {
  WalletOverview overview = const WalletOverview(currency: 'USD', wallets: []);
  final pages = <(int, int), WalletLedgerPage>{};
  final errors = <(int, int), Object>{};
  var withdrawCalls = 0;
  Object? overviewError;

  @override
  Future<WalletOverview> getOverview() async {
    final error = overviewError;
    if (error != null) throw error;
    return overview;
  }

  @override
  Future<WalletLedgerPage> getLedger({
    required int walletId,
    required int page,
    required int size,
  }) async {
    final error = errors[(walletId, page)];
    if (error != null) throw error;
    final result = pages[(walletId, page)];
    if (result == null) throw StateError('missing page');
    return WalletLedgerPage(
      content: result.content,
      page: page,
      size: result.size,
      totalElements: result.totalElements,
      totalPages: result.totalPages,
      last: result.last,
    );
  }
}
