import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/features/auth/data/saved_account_store.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/auth/domain/auth_repository.dart';
import 'package:joysong_flutter/features/auth/presentation/auth_controller.dart';
import 'package:joysong_flutter/features/auth/presentation/auth_gate.dart';
import 'package:joysong_flutter/features/profile/presentation/profile_page.dart';

void main() {
  testWidgets('account switcher scrolls and reflects the latest profile', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(500, 600));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    final accounts = List.generate(5, (index) {
      final number = index + 1;
      return SavedAccount(
        userId: 'user-$number',
        nickname: 'User $number',
        avatar: '',
        identifier: '+86139000000$number',
        tokens: _tokens(number),
      );
    });
    final store = _MemorySavedAccountStore(accounts);
    final repository = _FakeAuthRepository(
      tokens: accounts.first.tokens,
      user: _user('user-1', 'User 1'),
    );
    final controller = AuthController(repository, savedAccountStore: store);
    await controller.restoreSession();

    await tester.pumpWidget(
      MaterialApp(
        home: AuthGate(
          controller: controller,
          agentConfig: const AgentConfig(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    final profilePage = find.byType(ProfilePage, skipOffstage: false);
    tester
        .widget<ProfilePage>(profilePage)
        .onSwitchAccount
        ?.call(tester.element(profilePage));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    final list = find.byKey(const Key('account-switcher-list'));
    expect(list, findsOneWidget);
    final scrollable = tester.state<ScrollableState>(
      find.descendant(of: list, matching: find.byType(Scrollable)),
    );
    expect(scrollable.position.maxScrollExtent, greaterThan(0));
    final addAccountButton = find.byKey(const Key('add-account-button'));
    expect(addAccountButton, findsOneWidget);
    expect(tester.getBottomRight(addAccountButton).dy, lessThanOrEqualTo(600));

    await controller.updateCurrentAccountProfile(
      _user('user-1', 'Updated nickname'),
    );
    await tester.pump();

    expect(find.text('User 1'), findsNothing);
    expect(find.text('Updated nickname'), findsOneWidget);
    expect(store.accounts.first.nickname, 'Updated nickname');
    expect(store.accounts.first.tokens.accessToken, 'access-1');
  });
}

AuthTokens _tokens(int number) => AuthTokens(
      accessToken: 'access-$number',
      refreshToken: 'refresh-$number',
      tokenType: 'Bearer',
      expiresIn: 3600,
    );

AuthUser _user(String id, String nickname) => AuthUser(
      id: id,
      phone: '+8613900000001',
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

final class _MemorySavedAccountStore implements SavedAccountStore {
  _MemorySavedAccountStore(List<SavedAccount> accounts)
      : accounts = List.of(accounts);

  List<SavedAccount> accounts;

  @override
  Future<List<SavedAccount>> read() async => List.of(accounts);

  @override
  Future<void> save(List<SavedAccount> accounts) async {
    this.accounts = List.of(accounts);
  }
}

final class _FakeAuthRepository implements AuthRepository {
  _FakeAuthRepository({required this.tokens, required this.user});

  AuthTokens tokens;
  AuthUser user;

  @override
  Future<AuthSession> refreshSession() async =>
      AuthSession(tokens: tokens, user: user);

  @override
  Future<AuthTokens?> readTokens() async => tokens;

  @override
  dynamic noSuchMethod(Invocation invocation) {
    throw UnsupportedError(invocation.memberName.toString());
  }
}
