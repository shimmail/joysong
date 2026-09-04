import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
import 'package:joysong_flutter/core/routing/app_router.dart';
import 'package:joysong_flutter/core/theme/theme_controller.dart';
import 'package:joysong_flutter/core/theme/theme_preferences_store.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/account_security/data/account_deletion_pending_store.dart';
import 'package:joysong_flutter/features/account_security/data/account_security_api.dart';
import 'package:joysong_flutter/features/account_security/data/account_security_repository_impl.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_repository.dart';
import 'package:joysong_flutter/features/auth/data/auth_remote_data_source.dart';
import 'package:joysong_flutter/features/auth/data/auth_repository_impl.dart';
import 'package:joysong_flutter/features/auth/data/google_identity_provider.dart';
import 'package:joysong_flutter/features/auth/data/login_preferences_store.dart';
import 'package:joysong_flutter/features/auth/data/secure_token_store.dart';
import 'package:joysong_flutter/features/auth/data/saved_account_store.dart';
import 'package:joysong_flutter/features/auth/domain/auth_repository.dart';
import 'package:joysong_flutter/features/auth/domain/token_store.dart';
import 'package:joysong_flutter/features/auth/presentation/auth_controller.dart';
import 'package:joysong_flutter/features/auth/presentation/auth_gate.dart';
import 'package:joysong_flutter/features/legal_documents/data/legal_document_repository_impl.dart';
import 'package:joysong_flutter/features/legal_documents/domain/legal_document_repository.dart';
import 'package:joysong_flutter/features/messaging/data/secure_messaging_preferences_store.dart';
import 'package:joysong_flutter/features/settings/data/settings_preferences_store.dart';
import 'package:joysong_flutter/features/settings/domain/settings_preferences.dart';
import 'package:joysong_flutter/features/settings/domain/settings_services.dart';
import 'package:joysong_flutter/features/settings/presentation/settings_controller.dart';
import 'package:joysong_flutter/features/social/presentation/public_diary_share_page.dart';

class JoysongApp extends StatefulWidget {
  const JoysongApp({
    required this.environment,
    this.authRepository,
    this.loginPreferencesStore,
    this.themePreferenceStore,
    this.settingsPreferenceStore,
    this.localePreferenceStore,
    this.translationRepository,
    super.key,
  });

  final AppEnvironment environment;
  final AuthRepository? authRepository;
  final LoginPreferencesStore? loginPreferencesStore;
  final ThemePreferenceStore? themePreferenceStore;
  final SettingsPreferenceStore? settingsPreferenceStore;
  final LocalePreferenceStore? localePreferenceStore;
  final TranslationRepository? translationRepository;

  @override
  State<JoysongApp> createState() => _JoysongAppState();
}

class _JoysongAppState extends State<JoysongApp> {
  late final AuthController _authController;
  late final ThemeController _themeController;
  late final SettingsController _settingsController;
  late final AppLocaleController _localeController;
  late final AutoTranslationController _autoTranslationController;
  late final LanguageTagProvider _languageTagProvider;
  late final ApiClient _legalApiClient;
  late final LegalDocumentRepository _legalDocumentRepository;
  late final String _startupRouteName;
  ApiClient? _apiClient;
  AccountSecurityRepository? _accountDeletionRepository;
  AccountDeletionPendingStore? _pendingAccountDeletionStore;

  @override
  void initState() {
    super.initState();
    final secureStorage = FlutterSecureKeyValueStore();
    final usesDefaultDependencies = widget.authRepository == null;
    _localeController = AppLocaleController(
      preferenceStore: widget.localePreferenceStore ??
          (usesDefaultDependencies
              ? const SecureLocalePreferenceStore()
              : _EphemeralLocalePreferenceStore()),
    );
    _languageTagProvider = () =>
        _localeController.language == AppLanguage.english ? 'en-US' : 'zh-CN';
    _legalApiClient = ApiClient(
      apiRoot: widget.environment.apiRoot,
      languageTagProvider: _languageTagProvider,
    );
    _legalDocumentRepository = ApiLegalDocumentRepository(_legalApiClient);
    final repository =
        widget.authRepository ?? _createAuthRepository(secureStorage);
    if (_apiClient != null) {
      _accountDeletionRepository = AccountSecurityRepositoryImpl(
        ApiAccountSecurityRemoteDataSource(_apiClient!),
      );
      _pendingAccountDeletionStore =
          SecureAccountDeletionPendingStore(storage: secureStorage);
    }
    final translationRepository = widget.translationRepository ??
        (_apiClient == null ? null : ApiTranslationRepository(_apiClient!));
    _autoTranslationController = AutoTranslationController(
      repository: translationRepository,
    );
    _authController = AuthController(
      repository,
      loginPreferencesStore: widget.loginPreferencesStore ??
          (usesDefaultDependencies
              ? SecureLoginPreferencesStore(storage: secureStorage)
              : null),
      savedAccountStore: usesDefaultDependencies
          ? SecureSavedAccountStore(storage: secureStorage)
          : null,
      messagingPreferencesStore: usesDefaultDependencies
          ? SecureMessagingPreferencesStore(storage: secureStorage)
          : null,
      accountDeletionRepository: _accountDeletionRepository,
      pendingAccountDeletionStore: _pendingAccountDeletionStore,
      googleSessionClearer: usesDefaultDependencies
          ? GoogleIdentityProvider.instance.clearLocalSession
          : null,
      accountCacheClearer: usesDefaultDependencies
          ? (_) async {
              PaintingBinding.instance.imageCache
                ..clear()
                ..clearLiveImages();
            }
          : null,
      messageResolver: (chinese, english) =>
          _localeController.language == AppLanguage.english ? english : chinese,
    );
    _themeController = ThemeController(
      preferenceStore: widget.themePreferenceStore ??
          (usesDefaultDependencies
              ? SecureThemePreferenceStore(storage: secureStorage)
              : _EphemeralThemePreferenceStore()),
    );
    _settingsController = SettingsController(
      preferenceStore: widget.settingsPreferenceStore ??
          (usesDefaultDependencies
              ? SecureSettingsPreferenceStore(storage: secureStorage)
              : _EphemeralSettingsPreferenceStore()),
      cacheMaintenance: const FlutterImageCacheMaintenance(),
    );
    _startupRouteName =
        WidgetsBinding.instance.platformDispatcher.defaultRouteName;
    _apiClient?.configureUnauthorizedHandler(
      _authController.refreshAccessToken,
    );
    unawaited(_restoreStartup());
    unawaited(_restoreTheme());
    unawaited(_restoreSettings());
  }

  AuthRepository _createAuthRepository(SecureKeyValueStore storage) {
    final TokenStore tokenStore = SecureTokenStore(storage: storage);
    final apiClient = ApiClient(
      apiRoot: widget.environment.apiRoot,
      accessTokenProvider: () async => (await tokenStore.read())?.accessToken,
      languageTagProvider: _languageTagProvider,
    );
    _apiClient = apiClient;
    return AuthRepositoryImpl(
      remoteDataSource: ApiAuthRemoteDataSource(apiClient),
      tokenStore: tokenStore,
    );
  }

  Future<void> _restoreTheme() async {
    try {
      await _themeController.restore();
    } on Object {
      // A storage failure must not prevent the app from starting. The default
      // theme remains active and the user can retry from Settings.
    }
  }

  Future<void> _restoreSettings() async {
    try {
      await _settingsController.restore();
    } on Object {
      // A local settings failure keeps the safe system/default appearance.
    }
  }

  Future<void> _restoreStartup() async {
    try {
      await _localeController.restore();
    } on Object {
      // Locale persistence is optional at startup. Chinese remains the safe
      // fallback if the platform keystore is temporarily unavailable.
    }
    await _authController.restoreSession();
  }

  @override
  void dispose() {
    _authController.dispose();
    _themeController.dispose();
    _settingsController.dispose();
    _localeController.dispose();
    _autoTranslationController.dispose();
    _legalApiClient.close();
    _apiClient?.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final publicShareToken = _publicDiaryShareToken(
      _startupRouteName,
      deepLinkScheme: widget.environment.deepLinkScheme,
    );
    return AnimatedBuilder(
      animation: Listenable.merge([
        _themeController,
        _settingsController,
        _localeController,
        _authController,
      ]),
      builder: (context, _) {
        final consentEnabled =
            _localeController.language == AppLanguage.english &&
                _settingsController.aiTranslationEnabled;
        final translationEnabled = consentEnabled &&
            _authController.status == AuthStatus.authenticated;
        _autoTranslationController.synchronize(
          enabled: consentEnabled,
          authenticated: _authController.status == AuthStatus.authenticated,
          targetLanguage: 'en-US',
        );
        return AppLocaleScope(
          controller: _localeController,
          child: AutoTranslationScope(
            controller: _autoTranslationController,
            enabled: translationEnabled,
            targetLanguage: 'en-US',
            child: MaterialApp(
              onGenerateTitle: (context) =>
                  _localeController.language == AppLanguage.english
                      ? 'Joysong'
                      : '娇颜颂',
              debugShowCheckedModeBanner:
                  widget.environment.flavor == AppFlavor.development,
              locale: _localeController.language.locale,
              supportedLocales: const [Locale('zh'), Locale('en')],
              localizationsDelegates: const [
                GlobalMaterialLocalizations.delegate,
                GlobalWidgetsLocalizations.delegate,
                GlobalCupertinoLocalizations.delegate,
              ],
              theme: _themeController.lightTheme,
              darkTheme: _themeController.darkTheme,
              themeMode: _settingsController.appearanceMode.themeMode,
              home: publicShareToken != null
                  ? PublicDiarySharePage(
                      apiRoot: widget.environment.apiRoot,
                      token: publicShareToken,
                    )
                  : AuthGate(
                      controller: _authController,
                      agentConfig: widget.environment.agentConfig,
                      apiClient: _apiClient,
                      accountDeletionRepository: _accountDeletionRepository,
                      pendingAccountDeletionStore:
                          _pendingAccountDeletionStore,
                      allowPreviewData: widget.environment.allowsPreviewData,
                      passwordOnlyLogin:
                          widget.environment.usesPasswordOnlyLogin,
                    ),
              onGenerateRoute: (settings) => AppRouter.onGenerateRoute(
                settings,
                agentConfig: widget.environment.agentConfig,
                themeController: _themeController,
                settingsController: _settingsController,
                legalDocumentRepository: _legalDocumentRepository,
                apiClient: _apiClient,
                onLogout: _authController.logout,
                accountDeletionRepository: _accountDeletionRepository,
                pendingAccountDeletionStore: _pendingAccountDeletionStore,
                onAccountDeletionConfirmed:
                    _authController.completeAccountDeletion,
                onAccountDeletionUncertain:
                    _authController.holdPendingAccountDeletion,
              ),
            ),
          ),
        );
      },
    );
  }
}

String? _publicDiaryShareToken(
  String routeName, {
  String deepLinkScheme = 'joysong',
}) {
  final route = routeName.trim();
  if (route.isEmpty || route == '/') return null;
  final uri = Uri.tryParse(route);
  final candidates = <String>[
    if (uri != null) uri.path,
    if (uri != null && uri.fragment.isNotEmpty) uri.fragment,
    if (uri != null && uri.host.isNotEmpty) uri.host,
    route,
  ];
  for (final candidate in candidates) {
    final segments = candidate
        .split('/')
        .where((segment) => segment.isNotEmpty)
        .toList(growable: false);
    if (segments.length != 3) continue;
    if (segments[0] != 's' || segments[1] != 'diary') continue;
    final token = segments[2].trim();
    if (token.isNotEmpty) return token;
  }
  if (uri != null && uri.scheme == deepLinkScheme) {
    final segments = <String>[
      if (uri.host.isNotEmpty) uri.host,
      ...uri.pathSegments.where((segment) => segment.isNotEmpty),
    ];
    if (segments.length == 3 &&
        segments[0] == 's' &&
        segments[1] == 'diary' &&
        segments[2].trim().isNotEmpty) {
      return segments[2].trim();
    }
    if (segments.length == 2 &&
        segments[0] == 'diary' &&
        segments[1].trim().isNotEmpty) {
      return segments[1].trim();
    }
  }
  return null;
}

final class _EphemeralLocalePreferenceStore implements LocalePreferenceStore {
  String? _languageCode;

  @override
  Future<String?> readLanguageCode() async => _languageCode;

  @override
  Future<void> writeLanguageCode(String value) async {
    _languageCode = value;
  }
}

final class _EphemeralThemePreferenceStore implements ThemePreferenceStore {
  Color? _color;

  @override
  Future<void> clearSeedColor() async => _color = null;

  @override
  Future<Color?> readSeedColor() async => _color;

  @override
  Future<void> writeSeedColor(Color color) async => _color = color;
}

final class _EphemeralSettingsPreferenceStore
    implements SettingsPreferenceStore {
  SettingsPreferences _preferences = const SettingsPreferences();

  @override
  Future<SettingsPreferences> read() async => _preferences;

  @override
  Future<void> write(SettingsPreferences preferences) async {
    _preferences = preferences;
  }
}
