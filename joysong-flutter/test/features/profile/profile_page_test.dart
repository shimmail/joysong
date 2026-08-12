import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/profile/domain/profile_models.dart';
import 'package:joysong_flutter/features/profile/domain/profile_repository.dart';
import 'package:joysong_flutter/features/profile/presentation/profile_page.dart';

void main() {
  testWidgets('shows server profile and exposes service callbacks',
      (tester) async {
    var orderCalls = 0;
    var accountCalls = 0;
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh'), Locale('en')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: Scaffold(
          body: ProfilePage(
            profileRepository: _ProfileRepository(),
            onOrders: () => orderCalls++,
            onAccountSecurity: () => accountCalls++,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('测试用户'), findsOneWidget);
    expect(find.text('+86138****8000'), findsOneWidget);

    await tester.tap(find.text('我的订单'));
    await tester.drag(find.byType(ListView).first, const Offset(0, -900));
    await tester.pumpAndSettle();
    await tester.tap(find.text('账号与安全'));
    expect(orderCalls, 1);
    expect(accountCalls, 1);
  });

  testWidgets('edit profile saves and returns to updated header',
      (tester) async {
    final repository = _ProfileRepository();
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh'), Locale('en')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: Scaffold(body: ProfilePage(profileRepository: repository)),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('edit-profile-button')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('profile-nickname')),
      '更新用户',
    );
    await tester.drag(find.byType(ListView).last, const Offset(0, -500));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('save-profile')));
    await tester.pumpAndSettle();

    expect(find.text('更新用户'), findsOneWidget);
    expect(repository.updateCalls, 1);
  });

  testWidgets('shows Wallet for every signed-in profile', (tester) async {
    var walletCalls = 0;
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: const [Locale('zh'), Locale('en')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: Scaffold(
        body: ProfilePage(
          profileRepository: _ProfileRepository(),
          onWallet: () => walletCalls++,
        ),
      ),
    ));
    await tester.pumpAndSettle();

    await tester.scrollUntilVisible(
      find.byIcon(Icons.account_balance_wallet_outlined),
      240,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.byIcon(Icons.account_balance_wallet_outlined));

    expect(walletCalls, 1);
  });
}

final class _ProfileRepository implements ProfileRepository {
  var updateCalls = 0;
  AuthUser user = _user('测试用户');

  @override
  Future<AuthUser> getProfile() async => user;

  @override
  Future<AuthUser> updateProfile(ProfileUpdate update) async {
    updateCalls++;
    user = _user(update.nickname.trim());
    return user;
  }
}

AuthUser _user(String nickname) => AuthUser(
      id: 'user-1',
      phone: '+8613800138000',
      email: null,
      nickname: nickname,
      avatar: '',
      gender: '',
      city: '',
      bio: '',
      birthday: null,
      role: 'USER',
      hasPassword: true,
    );
