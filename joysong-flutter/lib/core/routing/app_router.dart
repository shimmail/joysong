import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/theme/theme_controller.dart';
import 'package:joysong_flutter/features/account_security/data/account_security_api.dart';
import 'package:joysong_flutter/features/account_security/data/account_security_repository_impl.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_controller.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_page.dart';
import 'package:joysong_flutter/features/settings/presentation/settings_page.dart';
import 'package:joysong_flutter/features/settings/presentation/settings_controller.dart';
import 'package:joysong_flutter/features/profile/presentation/profile_support_pages.dart';
import 'package:joysong_flutter/features/shell/presentation/app_shell.dart';

abstract final class AppRoutes {
  static const root = '/';
  static const settings = '/settings';
  static const accountSecurity = '/account-security';
}

abstract final class AppRouter {
  static Route<void> onGenerateRoute(
    RouteSettings settings, {
    required ThemeController themeController,
    required SettingsController settingsController,
    ApiClient? apiClient,
    Future<void> Function()? onLogout,
  }) {
    return switch (settings.name) {
      AppRoutes.root => MaterialPageRoute<void>(
          builder: (_) => const AppShell(),
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
          ),
          settings: settings,
        ),
      AppRoutes.accountSecurity when apiClient != null =>
        MaterialPageRoute<void>(
          builder: (_) => _AccountSecurityRoute(
            apiClient: apiClient,
            onLogout: onLogout,
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
  const _AccountSecurityRoute({required this.apiClient, this.onLogout});

  final ApiClient apiClient;
  final Future<void> Function()? onLogout;

  @override
  State<_AccountSecurityRoute> createState() => _AccountSecurityRouteState();
}

class _AccountSecurityRouteState extends State<_AccountSecurityRoute> {
  late final AccountSecurityController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AccountSecurityController(
      AccountSecurityRepositoryImpl(
        ApiAccountSecurityRemoteDataSource(widget.apiClient),
      ),
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
      onSessionInvalidated: () {
        Navigator.of(context).popUntil((route) => route.isFirst);
        final logout = widget.onLogout;
        if (logout != null) unawaited(logout());
      },
    );
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
