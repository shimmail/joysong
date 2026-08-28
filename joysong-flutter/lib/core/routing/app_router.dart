import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/theme/theme_controller.dart';
import 'package:joysong_flutter/features/account_security/data/account_deletion_pending_store.dart';
import 'package:joysong_flutter/features/account_security/data/account_security_api.dart';
import 'package:joysong_flutter/features/account_security/data/account_security_repository_impl.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_repository.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_controller.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_page.dart';
import 'package:joysong_flutter/features/auth/data/google_identity_provider.dart';
import 'package:joysong_flutter/features/identity/data/identity_repository_impl.dart';
import 'package:joysong_flutter/features/identity/presentation/identity_pages.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_models.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_repository.dart';
import 'package:joysong_flutter/features/legal_documents/presentation/legal_document_page.dart';
import 'package:joysong_flutter/features/settings/presentation/settings_page.dart';
import 'package:joysong_flutter/features/settings/presentation/settings_controller.dart';
import 'package:joysong_flutter/features/profile/presentation/profile_support_pages.dart';
import 'package:joysong_flutter/features/shell/presentation/app_shell.dart';

abstract final class AppRoutes {
  static const root = '/';
  static const settings = '/settings';
  static const accountSecurity = '/account-security';
  static const userAgreement = '/legal/user-agreement';
  static const privacyPolicy = '/legal/privacy-policy';
}

abstract final class AppRouter {
  static Route<void> onGenerateRoute(
    RouteSettings settings, {
    required AgentConfig agentConfig,
    required ThemeController themeController,
    required SettingsController settingsController,
    required LegalDocumentRepository legalDocumentRepository,
    ApiClient? apiClient,
    Future<void> Function()? onLogout,
    AccountSecurityRepository? accountDeletionRepository,
    AccountDeletionPendingStore? pendingAccountDeletionStore,
    Future<void> Function(String userId)? onAccountDeletionConfirmed,
    VoidCallback? onAccountDeletionUncertain,
  }) {
    return switch (settings.name) {
      AppRoutes.root => MaterialPageRoute<void>(
          builder: (_) => AppShell(agentConfig: agentConfig),
          settings: settings,
        ),
      AppRoutes.settings => MaterialPageRoute<void>(
          builder: (context) => SettingsPage(
            themeController: themeController,
            settingsController: settingsController,
            onAccountSecurity: apiClient == null
                ? null
                : () => Navigator.of(context).pushNamed(
                      AppRoutes.accountSecurity,
                    ),
            onAbout: () => Navigator.of(context).push<void>(
              MaterialPageRoute(builder: (_) => const AboutJoysongPage()),
            ),
            onPrivacyPolicy: () => Navigator.of(context).pushNamed(
              AppRoutes.privacyPolicy,
            ),
            onTermsOfService: () => Navigator.of(context).pushNamed(
              AppRoutes.userAgreement,
            ),
          ),
          settings: settings,
        ),
      AppRoutes.userAgreement => MaterialPageRoute<void>(
          builder: (_) => LegalDocumentPage(
            key: const ValueKey('legal-document-user-agreement'),
            type: LegalDocumentType.userAgreement,
            repository: legalDocumentRepository,
          ),
          settings: settings,
        ),
      AppRoutes.privacyPolicy => MaterialPageRoute<void>(
          builder: (_) => LegalDocumentPage(
            key: const ValueKey('legal-document-privacy-policy'),
            type: LegalDocumentType.privacyPolicy,
            repository: legalDocumentRepository,
          ),
          settings: settings,
        ),
      AppRoutes.accountSecurity when apiClient != null =>
        MaterialPageRoute<void>(
          builder: (_) => _AccountSecurityRoute(
            apiClient: apiClient,
            onLogout: onLogout,
            accountDeletionRepository: accountDeletionRepository,
            pendingAccountDeletionStore: pendingAccountDeletionStore,
            onAccountDeletionConfirmed: onAccountDeletionConfirmed,
            onAccountDeletionUncertain: onAccountDeletionUncertain,
          ),
          settings: settings,
        ),
      _ => MaterialPageRoute<void>(
          builder: (_) => const _NotFoundPage(),
          settings: settings,
        ),
    };
  }
}

class _AccountSecurityRoute extends StatefulWidget {
  const _AccountSecurityRoute({
    required this.apiClient,
    this.onLogout,
    this.accountDeletionRepository,
    this.pendingAccountDeletionStore,
    this.onAccountDeletionConfirmed,
    this.onAccountDeletionUncertain,
  });

  final ApiClient apiClient;
  final Future<void> Function()? onLogout;
  final AccountSecurityRepository? accountDeletionRepository;
  final AccountDeletionPendingStore? pendingAccountDeletionStore;
  final Future<void> Function(String userId)? onAccountDeletionConfirmed;
  final VoidCallback? onAccountDeletionUncertain;

  @override
  State<_AccountSecurityRoute> createState() => _AccountSecurityRouteState();
}

class _AccountSecurityRouteState extends State<_AccountSecurityRoute> {
  late final AccountSecurityController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AccountSecurityController(
      widget.accountDeletionRepository ??
          AccountSecurityRepositoryImpl(
            ApiAccountSecurityRemoteDataSource(widget.apiClient),
          ),
      pendingDeletionStore: widget.pendingAccountDeletionStore ??
          SecureAccountDeletionPendingStore(),
      googleIdTokenProvider: GoogleIdentityProvider.instance.requestIdToken,
      onDeletionConfirmed: widget.onAccountDeletionConfirmed,
      onDeletionUncertain: widget.onAccountDeletionUncertain,
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AccountSecurityPage(
      controller: _controller,
      onDeletionBlockerAction: _openDeletionBlockerAction,
      onSessionInvalidated: () {
        Navigator.of(context).popUntil((route) => route.isFirst);
        final logout = widget.onLogout;
        if (logout != null) unawaited(logout());
      },
    );
  }

  void _openDeletionBlockerAction(String action) {
    if (!isSupportedAccountDeletionIdentityAction(action)) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      unawaited(
        Navigator.of(context, rootNavigator: true).push<void>(
          MaterialPageRoute(
            builder: (_) => IdentityCenterPage(
              repository: ApiIdentityRepository(widget.apiClient),
            ),
          ),
        ),
      );
    });
  }
}

class _NotFoundPage extends StatelessWidget {
  const _NotFoundPage();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('页面不存在')),
      body: const Center(child: Text('请返回后重试')),
    );
  }
}
