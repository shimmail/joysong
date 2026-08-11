import 'dart:io';

import 'package:flutter/foundation.dart';

enum AppFlavor { development, staging, production }

enum AppPlatform { android, ios }

@immutable
class AgentConfig {
  const AgentConfig({
    this.recentMessageLimit = 20,
  });

  final int recentMessageLimit;
}

class AppEnvironment {
  const AppEnvironment({
    required this.flavor,
    required this.apiBaseUri,
    required this.agentConfig,
  });

  final AppFlavor flavor;
  final Uri apiBaseUri;
  final AgentConfig agentConfig;

  Uri get apiRoot => apiBaseUri.resolve('/api/');

  factory AppEnvironment.fromBuildDefines() {
    final platform = Platform.isAndroid ? AppPlatform.android : AppPlatform.ios;
    return AppEnvironment.resolve(
      platform: platform,
      flavorName: const String.fromEnvironment(
        'APP_ENV',
        defaultValue: 'development',
      ),
      baseUrl: const String.fromEnvironment('API_BASE_URL'),
      agentConfig: const AgentConfig(
        recentMessageLimit: 20,
      ),
    );
  }

  factory AppEnvironment.resolve({
    required AppPlatform platform,
    String flavorName = 'development',
    String baseUrl = '',
    AgentConfig agentConfig = const AgentConfig(),
  }) {
    final flavor = switch (flavorName.trim().toLowerCase()) {
      'production' || 'prod' => AppFlavor.production,
      'staging' || 'stage' => AppFlavor.staging,
      _ => AppFlavor.development,
    };
    final defaultBaseUrl = switch (platform) {
      AppPlatform.android => 'http://10.0.2.2:8080',
      AppPlatform.ios => 'http://127.0.0.1:8080',
    };
    final uri = Uri.parse(baseUrl.trim().isEmpty ? defaultBaseUrl : baseUrl);

    if (!uri.hasScheme || uri.host.isEmpty) {
      throw const FormatException('API_BASE_URL 必须是包含协议和主机的 URL');
    }
    if (flavor == AppFlavor.production && uri.scheme != 'https') {
      throw const FormatException('生产环境 API_BASE_URL 必须使用 HTTPS');
    }

    return AppEnvironment(
      flavor: flavor,
      apiBaseUri: uri,
      agentConfig: agentConfig,
    );
  }
}
