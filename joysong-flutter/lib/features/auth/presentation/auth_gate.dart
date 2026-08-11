import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
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
    this.allowPreviewData = false,
    super.key,
  });

  final AuthController controller;
  final AgentConfig agentConfig;
  final ApiClient? apiClient;
  final bool allowPreviewData;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        return switch (controller.status) {
          AuthStatus.restoring => const _RestoringSessionPage(),
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
              onUserAgreement: () => _showLegalText(
                context,
                chineseTitle: '用户协议',
                englishTitle: 'Terms of Service',
              ),
              onPrivacyPolicy: () => _showLegalText(
                context,
                chineseTitle: '隐私政策',
                englishTitle: 'Privacy Policy',
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
              onSwitchAccount: _showAccountSwitcher,
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
          onUserAgreement: () => _showLegalText(
            context,
            chineseTitle: '用户协议',
            englishTitle: 'Terms of Service',
          ),
          onPrivacyPolicy: () => _showLegalText(
            context,
            chineseTitle: '隐私政策',
            englishTitle: 'Privacy Policy',
          ),
        ),
      ),
    );
  }

  Future<void> _showLegalText(
    BuildContext context, {
    required String chineseTitle,
    required String englishTitle,
  }) {
    final english = _isEnglish(context);
    return showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(english ? englishTitle : chineseTitle),
        content: Text(
          english
              ? 'The complete text will be loaded from a controlled content source before release.'
              : '正式发布前将从受控内容源加载完整文本。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(english ? 'Got it' : '知道了'),
          ),
        ],
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
          builder: (context, _) => Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              Text(_isEnglish(context) ? 'Switch account' : '切换账号',
                  style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 12),
              for (final account in controller.savedAccounts)
                ListTile(
                  leading: CircleAvatar(
                    foregroundImage: account.avatar.isEmpty
                        ? null
                        : NetworkImage(account.avatar),
                    child: account.avatar.isEmpty
                        ? const Icon(Icons.person_outline)
                        : null,
                  ),
                  title: Text(account.nickname.isEmpty
                      ? (_isEnglish(context) ? 'Joysong user' : '娇颜颂用户')
                      : account.nickname),
                  subtitle: account.identifier.isEmpty
                      ? null
                      : Text(account.identifier),
                  trailing: account.userId == controller.currentUser?.id
                      ? Icon(Icons.check_circle,
                          color: Theme.of(context).colorScheme.primary)
                      : IconButton(
                          tooltip: _isEnglish(context) ? 'Remove' : '移除',
                          onPressed: () =>
                              controller.removeSavedAccount(account.userId),
                          icon: const Icon(Icons.close_rounded),
                        ),
                  onTap: account.userId == controller.currentUser?.id
                      ? null
                      : () async {
                          final switched =
                              await controller.switchAccount(account.userId);
                          if (switched && context.mounted) {
                            Navigator.of(context).pop();
                          }
                        },
                ),
              const Divider(),
              ListTile(
                leading: const Icon(Icons.person_add_alt_1_rounded),
                title: Text(
                    _isEnglish(context) ? 'Add another account' : '添加其他账号'),
                onTap: onAdd,
              ),
            ]),
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

bool _isEnglish(BuildContext context) =>
    Localizations.localeOf(context).languageCode == 'en';
