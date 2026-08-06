import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/features/auth/presentation/auth_action_page.dart';
import 'package:joysong_flutter/features/auth/presentation/auth_controller.dart';
import 'package:joysong_flutter/features/auth/presentation/login_page.dart';
import 'package:joysong_flutter/features/shell/presentation/app_shell.dart';

class AuthGate extends StatelessWidget {
  const AuthGate({
    required this.controller,
    this.apiClient,
    this.apiRoot,
    this.accessTokenProvider,
    this.languageTagProvider,
    this.allowPreviewData = false,
    super.key,
  });

  final AuthController controller;
  final ApiClient? apiClient;
  final Uri? apiRoot;
  final AccessTokenProvider? accessTokenProvider;
  final LanguageTagProvider? languageTagProvider;
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
              apiClient: apiClient,
              apiRoot: apiRoot,
              accessTokenProvider: accessTokenProvider,
              languageTagProvider: languageTagProvider,
              allowPreviewData: allowPreviewData,
              currentUserId: controller.currentUser?.id ?? '',
              onLogout: controller.logout,
            ),
        };
      },
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
