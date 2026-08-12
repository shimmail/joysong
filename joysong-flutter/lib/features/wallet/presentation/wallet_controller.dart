import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/wallet/domain/wallet_models.dart';
import 'package:joysong_flutter/features/wallet/domain/wallet_repository.dart';

final class WalletController extends ChangeNotifier {
  WalletController(this._repository, {this.pageSize = 20});

  final WalletRepository _repository;
  final int pageSize;

  WalletOverview _overview = const WalletOverview(currency: 'USD', wallets: []);
  int? _selectedWalletId;
  List<WalletLedgerEntry> _entries = const [];
  String? _overviewErrorMessage;
  String? _ledgerErrorMessage;
  bool _isOverviewLoading = false;
  bool _isLedgerLoading = false;
  bool _isLoadingMore = false;
  bool _hasNextPage = false;
  int _ledgerPage = 0;
  int _requestVersion = 0;

  WalletOverview get overview => _overview;
  WalletAccount? get selectedWallet => _overview.wallets
      .where((wallet) => wallet.walletId == _selectedWalletId)
      .cast<WalletAccount?>()
      .firstOrNull;
  int? get selectedWalletId => _selectedWalletId;
  List<WalletLedgerEntry> get entries => _entries;
  String? get overviewErrorMessage => _overviewErrorMessage;
  String? get ledgerErrorMessage => _ledgerErrorMessage;
  bool get isOverviewLoading => _isOverviewLoading;
  bool get isLedgerLoading => _isLedgerLoading;
  bool get isLoadingMore => _isLoadingMore;
  bool get hasNextPage => _hasNextPage;

  Future<void> load() async {
    if (_isOverviewLoading) return;
    final request = ++_requestVersion;
    _isOverviewLoading = true;
    _overviewErrorMessage = null;
    notifyListeners();
    try {
      final overview = await _repository.getOverview();
      if (request != _requestVersion) return;
      _overview = overview;
      if (!overview.wallets.any((wallet) => wallet.walletId == _selectedWalletId)) {
        _selectedWalletId = overview.wallets.isEmpty ? null : overview.wallets.first.walletId;
      }
      if (_selectedWalletId == null) {
        _entries = const [];
        _hasNextPage = false;
        _ledgerErrorMessage = null;
      } else {
        await _loadFirstPage(_selectedWalletId!, request: request);
      }
    } catch (error) {
      if (request == _requestVersion) {
        _overviewErrorMessage = _message(error, '钱包加载失败');
      }
    } finally {
      if (request == _requestVersion) {
        _isOverviewLoading = false;
        notifyListeners();
      }
    }
  }

  Future<void> refresh() => load();

  Future<void> selectWallet(int walletId) async {
    if (_selectedWalletId == walletId ||
        !_overview.wallets.any((wallet) => wallet.walletId == walletId)) {
      return;
    }
    final request = ++_requestVersion;
    _isOverviewLoading = false;
    _selectedWalletId = walletId;
    _entries = const [];
    _ledgerPage = 0;
    _hasNextPage = false;
    _ledgerErrorMessage = null;
    notifyListeners();
    await _loadFirstPage(walletId, request: request);
  }

  Future<void> retryLedger() {
    final walletId = _selectedWalletId;
    if (walletId == null) return Future<void>.value();
    return _entries.isEmpty || _ledgerErrorMessage != null
        ? _loadFirstPage(walletId, request: ++_requestVersion)
        : loadNextPage();
  }

  Future<void> loadNextPage() async {
    final walletId = _selectedWalletId;
    if (walletId == null || _isLedgerLoading || _isLoadingMore || !_hasNextPage) return;
    final request = _requestVersion;
    final nextPage = _ledgerPage + 1;
    _isLoadingMore = true;
    _ledgerErrorMessage = null;
    notifyListeners();
    try {
      final page = await _repository.getLedger(
        walletId: walletId,
        page: nextPage,
        size: pageSize,
      );
      if (!_isActive(request, walletId)) return;
      final seen = _entries.map((entry) => entry.id).toSet();
      _entries = List.unmodifiable([
        ..._entries,
        ...page.content.where((entry) => seen.add(entry.id)),
      ]);
      _ledgerPage = page.page;
      _hasNextPage = !page.last;
    } catch (error) {
      if (_isActive(request, walletId)) {
        _ledgerErrorMessage = _message(error, '更多流水加载失败');
      }
    } finally {
      if (_isActive(request, walletId)) {
        _isLoadingMore = false;
        notifyListeners();
      }
    }
  }

  Future<void> _loadFirstPage(int walletId, {required int request}) async {
    _isLedgerLoading = true;
    _ledgerErrorMessage = null;
    notifyListeners();
    try {
      final page = await _repository.getLedger(
        walletId: walletId,
        page: 0,
        size: pageSize,
      );
      if (!_isActive(request, walletId)) return;
      _entries = List.unmodifiable(page.content);
      _ledgerPage = page.page;
      _hasNextPage = !page.last;
    } catch (error) {
      if (_isActive(request, walletId)) {
        _ledgerErrorMessage = _message(error, '流水加载失败');
      }
    } finally {
      if (_isActive(request, walletId)) {
        _isLedgerLoading = false;
        notifyListeners();
      }
    }
  }

  bool _isActive(int request, int walletId) =>
      request == _requestVersion && walletId == _selectedWalletId;

  String _message(Object error, String fallback) {
    if (error is ApiException && error.message.isNotEmpty) return error.message;
    if (error is FormatException && error.message.isNotEmpty) return error.message;
    return fallback;
  }
}

extension on Iterable<WalletAccount?> {
  WalletAccount? get firstOrNull => isEmpty ? null : first;
}
