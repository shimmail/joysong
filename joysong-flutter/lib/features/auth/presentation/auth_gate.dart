import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/routing/app_router.dart';
import 'package:joysong_flutter/features/account_security/data/account_deletion_pending_store.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_repository.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_controller.dart';
import 'package:joysong_flutter/features/auth/data/google_identity_provider.dart';
import 'package:joysong_flutter/features/auth/presentation/auth_action_page.dart';
import 'package:joysong_flutter/features/auth/presentation/auth_controller.dart';
import 'package:joysong_flutter/features/auth/presentation/login_page.dart';
import 'package:joysong_flutter/features/shell/presentation/app_shell.dart';

class AuthGate extends StatelessWidget {
  const AuthGate({
    required this.controller,
    required this.agentConfig,
    this.apiClient,
    this.accountDeletionRepository,
    this.pendingAccountDeletionStore,
    this.allowPreviewData = false,
    super.key,
  });

  final AuthController controller;
  final AgentConfig agentConfig;
  final ApiClient? apiClient;
  final AccountSecurityRepository? accountDeletionRepository;
  final AccountDeletionPendingStore? pendingAccountDeletionStore;
  final bool allowPreviewData;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        return switch (controller.status) {
          AuthStatus.restoring => const _RestoringSessionPage(),
          AuthStatus.accountDeletionPending => _PendingAccountDeletionPage(
              errorMessage: controller.errorMessage,
              onRetry: controller.retryPendingAccountDeletion,
            ),
          AuthStatus.unauthenticated => LoginPage(
              onPasswordLogin: controller.loginWithPassword,
              onVerificationCodeLogin: controller.loginWithCode,
              onSendVerificationCode: controller.sendCode,
              onRegister: () => _openAuthAction(
                context,
                AuthActionMode.register,
              ),
              onForgotPassword: () => _openAuthAction(
                context,
                AuthActionMode.resetPassword,
              ),
              onGoogleLogin: () async {
                final idToken =
                    await GoogleIdentityProvider.instance.requestIdToken();
                if (idToken == null || idToken.isEmpty) return false;
                await controller.loginWithGoogle(idToken);
                // A returned token means the Google chooser completed. API
                // failures are surfaced by AuthController, not as cancellation.
                return true;
              },
              onUserAgreement: () => Navigator.of(context).pushNamed(
                AppRoutes.userAgreement,
              ),
              onPrivacyPolicy: () => Navigator.of(context).pushNamed(
                AppRoutes.privacyPolicy,
              ),
              isLoading: controller.isBusy,
              errorMessage: controller.errorMessage,
              initialPhone: controller.loginPreferences.phone,
              initialPassword: controller.loginPreferences.password,
              initialRememberPassword:
                  controller.loginPreferences.rememberPassword,
              initialAutoLogin: controller.loginPreferences.autoLogin,
              initialAgreementsAccepted:
                  controller.loginPreferences.agreementsAccepted,
            ),
          AuthStatus.authenticated => AppShell(
              key: ValueKey(controller.currentUser?.id),
              agentConfig: agentConfig,
              apiClient: apiClient,
              allowPreviewData: allowPreviewData,
              currentUserId: controller.currentUser?.id ?? '',
              accountSecurityControllerFactory:
                  accountDeletionRepository == null
                      ? null
                      : () => AccountSecurityController(
                            accountDeletionRepository!,
                            pendingDeletionStore:
                                pendingAccountDeletionStore ??
                                    SecureAccountDeletionPendingStore(),
                            googleIdTokenProvider: GoogleIdentityProvider
                                .instance.requestIdToken,
                            onDeletionConfirmed:
                                controller.completeAccountDeletion,
                            onDeletionUncertain:
                                controller.holdPendingAccountDeletion,
                          ),
              onSwitchAccount: _showAccountSwitcher,
              onProfileUpdated: controller.updateCurrentAccountProfile,
              onLogout: controller.logout,
            ),
        };
      },
    );
  }

  Future<void> _showAccountSwitcher(BuildContext context) {
    return showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (sheetContext) => _AccountSwitcherSheet(
        controller: controller,
        onAdd: () async {
          Navigator.of(sheetContext).pop();
          await GoogleIdentityProvider.instance.clearLocalSession();
          await controller.addAnotherAccount();
        },
      ),
    );
  }

  Future<void> _openAuthAction(
    BuildContext context,
    AuthActionMode mode,
  ) {
    return Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => AuthActionPage(
          mode: mode,
          onCheckPhoneRegistered: controller.checkPhoneRegistered,
          onSendCode: controller.sendCode,
          onRegister: controller.registerAccount,
          onResetPassword: controller.resetPassword,
          errorMessage: () => controller.errorMessage,
          onUserAgreement: () => Navigator.of(context).pushNamed(
            AppRoutes.userAgreement,
          ),
          onPrivacyPolicy: () => Navigator.of(context).pushNamed(
            AppRoutes.privacyPolicy,
          ),
        ),
      ),
    );
  }
}

class _AccountSwitcherSheet extends StatelessWidget {
  const _AccountSwitcherSheet({required this.controller, required this.onAdd});
  final AuthController controller;
  final Future<void> Function() onAdd;

  @override
  Widget build(BuildContext context) => SafeArea(
        child: ListenableBuilder(
          listenable: controller,
          builder: (context, _) => ConstrainedBox(
            constraints: BoxConstraints(
              maxHeight: MediaQuery.sizeOf(context).height * 0.8,
            ),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                Text(_isEnglish(context) ? 'Switch account' : '切换账号',
                    style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 12),
                Flexible(
                  child: ListView(
                    key: const Key('account-switcher-list'),
                    shrinkWrap: true,
                    padding: EdgeInsets.zero,
                    children: [
                      for (final account in controller.savedAccounts)
                        ListTile(
                          key: ValueKey('saved-account-${account.userId}'),
                          leading: CircleAvatar(
                            foregroundImage: account.avatar.isEmpty
                                ? null
                                : NetworkImage(account.avatar),
                            child: account.avatar.isEmpty
                                ? const Icon(Icons.person_outline)
                                : null,
                          ),
                          title: Text(account.nickname.isEmpty
                              ? (_isEnglish(context)
                                  ? 'Joysong user'
                                  : '娇颜颂用户')
                              : account.nickname),
                          subtitle: account.identifier.isEmpty
                              ? null
                              : Text(account.identifier),
                          trailing:
                              account.userId == controller.currentUser?.id
                                  ? Icon(
                                      Icons.check_circle,
                                      color: Theme.of(context)
                                          .colorScheme
                                          .primary,
                                    )
                                  : IconButton(
                                      tooltip:
                                          _isEnglish(context) ? 'Remove' : '移除',
                                      onPressed: () => controller
                                          .removeSavedAccount(account.userId),
                                      icon: const Icon(Icons.close_rounded),
                                    ),
                          onTap: account.userId == controller.currentUser?.id
                              ? null
                              : () async {
                                  final switched = await controller
                                      .switchAccount(account.userId);
                                  if (switched && context.mounted) {
                                    Navigator.of(context).pop();
                                  }
                                },
                        ),
                    ],
                  ),
                ),
                const Divider(),
                ListTile(
                  key: const Key('add-account-button'),
                  leading: const Icon(Icons.person_add_alt_1_rounded),
                  title: Text(_isEnglish(context)
                      ? 'Add another account'
                      : '添加其他账号'),
                  onTap: onAdd,
                ),
              ]),
            ),
          ),
        ),
      );
}

class _RestoringSessionPage extends StatelessWidget {
  const _RestoringSessionPage();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: Semantics(
          label: _isEnglish(context) ? 'Restoring your session' : '正在恢复登录状态',
          child: const CircularProgressIndicator(),
        ),
      ),
    );
  }
}

class _PendingAccountDeletionPage extends StatelessWidget {
  const _PendingAccountDeletionPage({
    required this.onRetry,
    this.errorMessage,
  });

  final Future<void> Function() onRetry;
  final String? errorMessage;

  @override
  Widget build(BuildContext context) {
    final english = _isEnglish(context);
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.hourglass_top_rounded, size: 44),
                  const SizedBox(height: 16),
                  Text(
                    english
                        ? 'Account deletion is awaiting confirmation'
                        : '账号注销结果待确认',
                    style: Theme.of(context).textTheme.titleLarge,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    errorMessage ??
                        (english
                            ? 'You remain signed out. Retry the same protected request when the network is available.'
                            : '当前保持退出状态。网络恢复后将使用同一受保护请求续作。'),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 20),
                  FilledButton.icon(
                    key: const Key('retry-pending-account-deletion'),
                    onPressed: onRetry,
                    icon: const Icon(Icons.refresh_rounded),
                    label: Text(english ? 'Retry' : '重试'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

bool _isEnglish(BuildContext context) =>
    Localizations.localeOf(context).languageCode == 'en';
