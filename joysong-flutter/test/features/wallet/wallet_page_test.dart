import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/wallet/domain/wallet_models.dart';
import 'package:joysong_flutter/features/wallet/domain/wallet_repository.dart';
import 'package:joysong_flutter/features/wallet/presentation/wallet_controller.dart';
import 'package:joysong_flutter/features/wallet/presentation/wallet_page.dart';

void main() {
  for (final language in ['zh', 'en']) {
    testWidgets('long wallet owners leave room for the arrow: $language',
        (tester) async {
      await tester.binding.setSurfaceSize(const Size(320, 800));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final repository = _WalletRepository();
      final controller = WalletController(repository);
      addTearDown(controller.dispose);

      await tester.pumpWidget(MaterialApp(
        locale: Locale(language),
        supportedLocales: const [Locale('zh'), Locale('en')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context).copyWith(
            textScaler: TextScaler.linear(1.5),
          ),
          child: child!,
        ),
        home: WalletPage(controller: controller),
      ));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);

      final selector = find.byKey(const Key('wallet-owner-selector'));
      final arrow = find.descendant(
        of: selector,
        matching: find.byIcon(Icons.arrow_drop_down),
      );
      final label = find.descendant(
        of: selector,
        matching: find.textContaining(repository.wallets.first.ownerName),
      );
      expect(arrow, findsOneWidget);
      expect(label, findsOneWidget);
      expect(tester.getRect(label).right,
          lessThanOrEqualTo(tester.getRect(arrow).left));

      await tester.tap(selector);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      final secondOption =
          find.textContaining(repository.wallets.last.ownerName);
      await tester.tap(secondOption.last);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(controller.selectedWalletId, 2);
      expect(repository.lastLedgerWalletId, 2);
      // The balance card retains the full owner name outside the compact field.
      expect(
        find.descendant(
          of: find.byType(Card),
          matching: find.textContaining(repository.wallets.last.ownerName),
        ),
        findsOneWidget,
      );
    });
  }
}

class _WalletRepository implements WalletRepository {
  final wallets = [
    for (final id in [1, 2])
      WalletAccount(
        walletId: id,
        ownerType: 'INSTITUTION',
        ownerId: '$id',
        displayName: 'Institution wallet',
        ownerName: id == 1
            ? '跨境国际医疗美容机构超长收益归属名称测试'
            : 'International Aesthetic Medical Institution With A Long Name',
        pendingMinor: 100,
        availableMinor: 200,
        frozenMinor: 0,
      ),
  ];
  int? lastLedgerWalletId;

  @override
  Future<WalletOverview> getOverview() async =>
      WalletOverview(currency: 'USD', wallets: wallets);

  @override
  Future<WalletLedgerPage> getLedger({
    required int walletId,
    required int page,
    required int size,
  }) async {
    lastLedgerWalletId = walletId;
    return WalletLedgerPage(
      content: const [],
      page: page,
      size: size,
      totalElements: 0,
      totalPages: 0,
      last: true,
    );
  }
}
