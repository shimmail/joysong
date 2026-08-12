import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/wallet/domain/wallet_models.dart';
import 'package:joysong_flutter/features/wallet/domain/wallet_repository.dart';
import 'package:joysong_flutter/features/wallet/presentation/wallet_controller.dart';

void main() {
  test('keeps ordinary users in a successful empty overview', () async {
    final controller = WalletController(_FakeWalletRepository());

    await controller.load();

    expect(controller.isOverviewLoading, isFalse);
    expect(controller.overview.wallets, isEmpty);
    expect(controller.selectedWallet, isNull);
    expect(controller.overviewErrorMessage, isNull);
  });

  test('automatically selects a single wallet and loads its first page', () async {
    final repository = _FakeWalletRepository()
      ..overview = _overview(_wallet(101))
      ..pages[101] = _page(101, [_entry(1, 101)], last: true);
    final controller = WalletController(repository);

    await controller.load();

    expect(controller.selectedWalletId, 101);
    expect(controller.entries.map((entry) => entry.id), [1]);
    expect(repository.ledgerRequests, [(101, 0, 20)]);
  });

  test('switching wallets clears entries and ignores stale response', () async {
    final repository = _FakeWalletRepository()
      ..overview = _overview(_wallet(101), _wallet(202));
    final first = Completer<WalletLedgerPage>();
    repository.pageFutures[101] = first.future;
    repository.pages[202] = _page(202, [_entry(2, 202)], last: true);
    final controller = WalletController(repository);

    final loading = controller.load();
    await Future<void>.delayed(Duration.zero);
    await controller.selectWallet(202);
    expect(controller.isOverviewLoading, isFalse);
    first.complete(_page(101, [_entry(1, 101)], last: true));
    await loading;

    expect(controller.selectedWalletId, 202);
    expect(controller.entries.map((entry) => entry.id), [2]);
    expect(repository.ledgerRequests, [(101, 0, 20), (202, 0, 20)]);
  });

  test('refresh reloads active wallet and next page appends entries', () async {
    final repository = _FakeWalletRepository()
      ..overview = _overview(_wallet(101))
      ..pages[101] = _page(101, [_entry(1, 101)], last: false);
    final controller = WalletController(repository);
    await controller.load();
    repository.pages[101] = _page(101, [_entry(2, 101)], last: true);

    await controller.loadNextPage();
    await controller.refresh();

    expect(controller.entries.map((entry) => entry.id), [2]);
    expect(repository.ledgerRequests, [(101, 0, 20), (101, 1, 20), (101, 0, 20)]);
  });

  test('pagination failure retains loaded entries and exposes retry state', () async {
    final repository = _FakeWalletRepository()
      ..overview = _overview(_wallet(101))
      ..pages[101] = _page(101, [_entry(1, 101)], last: false);
    final controller = WalletController(repository);
    await controller.load();
    repository.ledgerError = StateError('offline');

    await controller.loadNextPage();

    expect(controller.entries.map((entry) => entry.id), [1]);
    expect(controller.ledgerErrorMessage, isNotNull);
    expect(controller.isLoadingMore, isFalse);
  });

  test('retries a failed first ledger page for the active wallet', () async {
    final repository = _FakeWalletRepository()
      ..overview = _overview(_wallet(101))
      ..ledgerError = StateError('offline');
    final controller = WalletController(repository);
    await controller.load();
    repository.ledgerError = null;
    repository.pages[101] = _page(101, [_entry(1, 101)], last: true);

    await controller.retryLedger();

    expect(controller.ledgerErrorMessage, isNull);
    expect(controller.entries.map((entry) => entry.id), [1]);
    expect(repository.ledgerRequests, [(101, 0, 20), (101, 0, 20)]);
  });

  test('failed refresh keeps prior ledger entries and exposes a retryable error',
      () async {
    final repository = _FakeWalletRepository()
      ..overview = _overview(_wallet(101))
      ..pages[101] = _page(101, [_entry(1, 101)], last: true);
    final controller = WalletController(repository);
    await controller.load();
    repository.ledgerError = StateError('offline');

    await controller.refresh();

    expect(controller.entries.map((entry) => entry.id), [1]);
    expect(controller.ledgerErrorMessage, isNotNull);
    expect(controller.selectedWalletId, 101);
  });
}

WalletOverview _overview(WalletAccount first, [WalletAccount? second]) =>
    WalletOverview(currency: 'USD', wallets: [first, if (second != null) second]);

WalletAccount _wallet(int id) => WalletAccount(
      walletId: id,
      ownerType: 'DOCTOR',
      ownerId: 'owner-$id',
      displayName: '医生钱包',
      ownerName: '张医生$id',
      pendingMinor: 0,
      availableMinor: 1200,
      frozenMinor: 0,
    );

WalletLedgerEntry _entry(int id, int walletId) => WalletLedgerEntry(
      id: id,
      walletId: walletId,
      entryType: 'SETTLEMENT_CREDIT',
      title: '诊疗收益',
      description: '订单 JS$id',
      amountMinor: 1200,
      pendingAfterMinor: 0,
      availableAfterMinor: 1200,
      frozenAfterMinor: 0,
      currency: 'USD',
      createdAt: DateTime(2026, 8, 11),
    );

WalletLedgerPage _page(int walletId, List<WalletLedgerEntry> entries, {required bool last}) =>
    WalletLedgerPage(
      content: entries,
      page: 0,
      size: 20,
      totalElements: entries.length,
      totalPages: last ? 1 : 2,
      last: last,
    );

final class _FakeWalletRepository implements WalletRepository {
  WalletOverview overview = const WalletOverview(currency: 'USD', wallets: []);
  final Map<int, WalletLedgerPage> pages = {};
  final Map<int, Future<WalletLedgerPage>> pageFutures = {};
  final List<(int, int, int)> ledgerRequests = [];
  Object? ledgerError;

  @override
  Future<WalletOverview> getOverview() async => overview;

  @override
  Future<WalletLedgerPage> getLedger({
    required int walletId,
    required int page,
    required int size,
  }) async {
    ledgerRequests.add((walletId, page, size));
    final future = pageFutures[walletId];
    if (future != null && page == 0) return future;
    final error = ledgerError;
    if (error != null) throw error;
    final result = pages[walletId];
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
