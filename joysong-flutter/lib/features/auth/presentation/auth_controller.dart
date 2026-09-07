import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/account_security/data/account_deletion_pending_store.dart'
    hide PendingAccountDeletion;
import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_repository.dart';
import 'package:joysong_flutter/features/auth/data/login_preferences_store.dart';
import 'package:joysong_flutter/features/auth/data/saved_account_store.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/auth/domain/auth_repository.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_preferences.dart';

enum AuthStatus {
  restoring,
  accountDeletionPending,
  unauthenticated,
  authenticated,
}

typedef AuthMessageResolver = String Function(String chinese, String english);
typedef GoogleSessionClearer = Future<void> Function();
typedef AccountCacheClearer = Future<void> Function(String userId);

final class AuthController extends ChangeNotifier {
  AuthController(
    this._repository, {
    LoginPreferencesStore? loginPreferencesStore,
    SavedAccountStore? savedAccountStore,
    MessagingPreferencesStore? messagingPreferencesStore,
    AccountSecurityRepository? accountDeletionRepository,
    AccountDeletionPendingStore? pendingAccountDeletionStore,
    GoogleSessionClearer? googleSessionClearer,
    AccountCacheClearer? accountCacheClearer,
    AuthMessageResolver? messageResolver,
  })  : _loginPreferencesStore = loginPreferencesStore,
        _savedAccountStore = savedAccountStore,
        _messagingPreferencesStore = messagingPreferencesStore,
        _accountDeletionRepository = accountDeletionRepository,
        _pendingAccountDeletionStore = pendingAccountDeletionStore,
        _googleSessionClearer = googleSessionClearer,
        _accountCacheClearer = accountCacheClearer,
        _messageResolver = messageResolver;

  final AuthRepository _repository;
  final LoginPreferencesStore? _loginPreferencesStore;
  final SavedAccountStore? _savedAccountStore;
  final MessagingPreferencesStore? _messagingPreferencesStore;
  final AccountSecurityRepository? _accountDeletionRepository;
  final AccountDeletionPendingStore? _pendingAccountDeletionStore;
  final GoogleSessionClearer? _googleSessionClearer;
  final AccountCacheClearer? _accountCacheClearer;
  final AuthMessageResolver? _messageResolver;

  AuthStatus _status = AuthStatus.restoring;
  bool _isBusy = false;
  bool _preferencesLoaded = false;
  String? _errorMessage;
  AuthUser? _currentUser;
  LoginPreferences _loginPreferences = const LoginPreferences();
  List<SavedAccount> _savedAccounts = const [];
  Future<void>? _restoreInFlight;

  AuthStatus get status => _status;

  bool get isBusy => _isBusy;

  String? get errorMessage => _errorMessage;

  AuthUser? get currentUser => _currentUser;

  LoginPreferences get loginPreferences => _loginPreferences;

  List<SavedAccount> get savedAccounts => List.unmodifiable(_savedAccounts);

  Future<void> restoreSession() {
    final running = _restoreInFlight;
    if (running != null) return running;
    late final Future<void> operation;
    operation = _restoreSession().whenComplete(() {
      if (identical(_restoreInFlight, operation)) _restoreInFlight = null;
    });
    _restoreInFlight = operation;
    return operation;
  }

  Future<void> _restoreSession() async {
    _status = AuthStatus.restoring;
    _clearError(notify: false);
    notifyListeners();

    final canContinue = await _resumePendingAccountDeletion();
    if (!canContinue) return;
    await _loadSavedAccounts();

    AuthTokens? tokens;
    try {
      tokens = await _repository.readTokens();
    } catch (error) {
      _status = AuthStatus.unauthenticated;
      _errorMessage = _messageFor(
        error,
        fallback: _text('无法读取本地安全凭证', 'Unable to read saved sign-in credentials.'),
      );
      notifyListeners();
      return;
    }

    try {
      await _loadLoginPreferences();
    } catch (error) {
      _errorMessage = _messageFor(
        error,
        fallback: _text('无法读取登录偏好', 'Unable to read sign-in preferences.'),
      );
    }

    if (tokens != null) {
      try {
        final session = await _repository.refreshSession();
        _currentUser = session.user;
        _status = AuthStatus.authenticated;
        await _rememberSession(session);
      } catch (error) {
        _currentUser = null;
        _status = AuthStatus.unauthenticated;
        _errorMessage = _messageFor(
          error,
          fallback: _text(
            '恢复登录失败，请检查网络后重试',
            'Could not restore your session. Check your connection and try again.',
          ),
        );
      }
      notifyListeners();
      return;
    }
    if (!_loginPreferences.canAutoLogin) {
      _status = AuthStatus.unauthenticated;
      notifyListeners();
      return;
    }

    try {
      final session = await _repository.loginWithPassword(
        phone: _loginPreferences.phone,
        password: _loginPreferences.password,
      );
      _currentUser = session.user;
      _status = AuthStatus.authenticated;
    } catch (error) {
      _currentUser = null;
      _status = AuthStatus.unauthenticated;
      _errorMessage = _messageFor(
        error,
        fallback: _text(
          '自动登录失败，请重新登录',
          'Automatic sign-in failed. Please sign in again.',
        ),
      );
      await _disableAutoLoginFailClosed();
    }
    notifyListeners();
  }

  /// Clears only data belonging to [userId]. Global language and theme stores
  /// are intentionally outside this controller and therefore remain intact.
  /// The caller clears the persisted pending marker only after this succeeds.
  Future<void> completeAccountDeletion(String userId) async {
    final normalizedUserId = userId.trim();
    if (normalizedUserId.isEmpty) {
      throw ArgumentError.value(userId, 'userId', 'userId 不能为空');
    }
    final deletedUser = _currentUser?.id == normalizedUserId
        ? _currentUser
        : null;
    _currentUser = null;
    _status = AuthStatus.accountDeletionPending;
    _clearError(notify: false);
    notifyListeners();

    final savedStore = _savedAccountStore;
    final savedAccounts = savedStore == null
        ? List<SavedAccount>.of(_savedAccounts)
        : await savedStore.read();
    final loginPreferencesStore = _loginPreferencesStore;
    final loginPreferences = loginPreferencesStore == null
        ? _loginPreferences
        : (await loginPreferencesStore.read() ?? const LoginPreferences())
            .normalized();
    _loginPreferences = loginPreferences;
    _preferencesLoaded = true;

    var deletedIdentifier = deletedUser?.phone ?? deletedUser?.email ?? '';
    for (final account in savedAccounts) {
      if (account.userId == normalizedUserId && deletedIdentifier.isEmpty) {
        deletedIdentifier = account.identifier.trim();
      }
    }
    final clearLoginPreferences = _loginPreferencesBelongToDeletedUser(
      loginPreferences,
      normalizedUserId,
      deletedIdentifier,
      deletedUser,
    );
    final remainingSavedAccounts = savedAccounts
        .where((account) => account.userId != normalizedUserId)
        .toList(growable: false);

    await _repository.clearLocalTokens();
    if (clearLoginPreferences) {
      await loginPreferencesStore?.clear();
      _loginPreferences = const LoginPreferences();
      _preferencesLoaded = true;
    }
    if (savedStore != null) {
      await savedStore.save(remainingSavedAccounts);
    }
    _savedAccounts = remainingSavedAccounts;
    await _messagingPreferencesStore?.clear(normalizedUserId);
    await _googleSessionClearer?.call();
    await _accountCacheClearer?.call(normalizedUserId);

    _status = AuthStatus.unauthenticated;
    _errorMessage = _text(
      '账号已注销，本机数据已清理',
      'Account deleted. Local account data was cleared.',
    );
    notifyListeners();
  }

  void holdPendingAccountDeletion() {
    _currentUser = null;
    _status = AuthStatus.accountDeletionPending;
    _errorMessage = _text(
      '注销结果待确认，请保持退出状态并重试',
      'Account deletion is awaiting confirmation. Stay signed out and retry.',
    );
    notifyListeners();
  }

  Future<void> retryPendingAccountDeletion() => restoreSession();

  Future<void> loginWithPassword(
    String phone,
    String password, {
    bool rememberPassword = false,
    bool autoLogin = false,
    bool agreementsAccepted = false,
  }) {
    return _runLogin(
      () => _repository.loginWithPassword(phone: phone, password: password),
      afterSuccess: (session) => _persistPreferences(
        LoginPreferences(
          userId: session.user.id,
          phone: phone,
          password: rememberPassword ? password : '',
          rememberPassword: rememberPassword,
          autoLogin: autoLogin,
          agreementsAccepted: agreementsAccepted,
        ),
      ),
    );
  }

  Future<void> loginWithCode(
    String phone,
    String code, {
    // The existing verification-code LoginPage callback has two positional
    // arguments and already gates submission on accepting both agreements.
    bool agreementsAccepted = true,
  }) {
    return _runLogin(
      () => _repository.loginWithCode(phone: phone, code: code),
      afterSuccess: (session) async {
        await _loadLoginPreferences();
        final sameAccount = _loginPreferences.phone == phone.trim();
        await _persistPreferences(
          LoginPreferences(
            userId: session.user.id,
            phone: phone,
            password: sameAccount ? _loginPreferences.password : '',
            rememberPassword: sameAccount && _loginPreferences.rememberPassword,
            autoLogin: false,
            agreementsAccepted: agreementsAccepted,
          ),
        );
      },
    );
  }

  Future<void> loginWithGoogle(
    String idToken, {
    bool agreementsAccepted = true,
  }) {
    return _runLogin(
      () => _repository.loginWithGoogle(idToken: idToken),
      afterSuccess: (session) async {
        await _loadLoginPreferences();
        await _persistPreferences(LoginPreferences(
          userId: session.user.id,
          agreementsAccepted: agreementsAccepted,
        ));
      },
    );
  }

  Future<void> sendCode(String phone) async {
    _clearError();
    try {
      await _repository.sendCode(phone: phone);
    } catch (error) {
      _errorMessage = _messageFor(
        error,
        fallback: _text('验证码发送失败', 'Failed to send the verification code.'),
      );
      notifyListeners();
      rethrow;
    }
  }

  Future<bool> checkPhoneRegistered(String phone) async {
    _clearError();
    try {
      return await _repository.checkPhoneRegistered(phone: phone);
    } catch (error) {
      _errorMessage = _messageFor(
        error,
        fallback: _text(
          '无法检查手机号状态，请稍后重试',
          'Unable to check this phone number. Please try again.',
        ),
      );
      notifyListeners();
      rethrow;
    }
  }

  Future<bool> registerAccount(
    String phone,
    String code,
    String password,
  ) async {
    if (_isBusy) return false;
    _isBusy = true;
    _clearError(notify: false);
    notifyListeners();
    try {
      final session = await _repository.register(
        phone: phone,
        code: code,
        password: password,
      );
      _currentUser = session.user;
      _status = AuthStatus.authenticated;
      try {
        await _persistPreferences(
          LoginPreferences(
            userId: session.user.id,
            phone: phone,
            agreementsAccepted: true,
          ),
        );
      } catch (_) {
        _errorMessage = _text(
          '注册成功，但无法保存登录偏好',
          'Account created, but sign-in preferences could not be saved.',
        );
      }
      return true;
    } catch (error) {
      _errorMessage = _messageFor(
        error,
        fallback: _text(
          '注册失败，请核对信息后重试',
          'Registration failed. Check your details and try again.',
        ),
      );
      return false;
    } finally {
      _isBusy = false;
      notifyListeners();
    }
  }

  Future<bool> resetPassword(
    String phone,
    String code,
    String newPassword,
  ) async {
    if (_isBusy) return false;
    _isBusy = true;
    _clearError(notify: false);
    notifyListeners();
    try {
      await _repository.resetPassword(
        phone: phone,
        code: code,
        newPassword: newPassword,
      );
      return true;
    } catch (error) {
      _errorMessage = _messageFor(
        error,
        fallback: _text(
          '密码重置失败，请核对验证码后重试',
          'Password reset failed. Check the code and try again.',
        ),
      );
      return false;
    } finally {
      _isBusy = false;
      notifyListeners();
    }
  }

  Future<String?> refreshAccessToken() async {
    try {
      final session = await _repository.refreshSession();
      if (_status == AuthStatus.accountDeletionPending) {
        return session.tokens.accessToken;
      }
      _currentUser = session.user;
      await _rememberSession(session);
      if (_status != AuthStatus.authenticated) {
        _status = AuthStatus.authenticated;
        notifyListeners();
      }
      return session.tokens.accessToken;
    } catch (error) {
      if (error is ApiException &&
          error.isUnauthorized &&
          _status != AuthStatus.accountDeletionPending) {
        _currentUser = null;
        _status = AuthStatus.unauthenticated;
        _errorMessage = _text(
          '登录已过期，请重新登录',
          'Your session has expired. Please sign in again.',
        );
        notifyListeners();
      }
      rethrow;
    }
  }

  Future<void> logout() async {
    if (_isBusy) {
      return;
    }
    _isBusy = true;
    _currentUser = null;
    _status = AuthStatus.unauthenticated;
    _clearError(notify: false);
    notifyListeners();
    try {
      try {
        await _loadLoginPreferences();
        await _disableAutoLoginFailClosed();
      } catch (_) {
        _errorMessage = _text(
          '无法更新登录偏好，已清除自动登录信息',
          'Unable to update sign-in preferences. Automatic sign-in was cleared.',
        );
      }
      try {
        await _repository.logout();
      } catch (error) {
        _errorMessage = _messageFor(
          error,
          fallback: _text(
            '远端退出失败，本地登录已清除',
            'Remote sign-out failed. You have been signed out on this device.',
          ),
        );
      }
    } finally {
      _currentUser = null;
      _status = AuthStatus.unauthenticated;
      _isBusy = false;
      notifyListeners();
    }
  }

  Future<bool> switchAccount(String userId) async {
    if (_isBusy) return false;
    SavedAccount? target;
    for (final account in _savedAccounts) {
      if (account.userId == userId) target = account;
    }
    if (target == null) return false;
    if (target.userId == _currentUser?.id) return true;
    _isBusy = true;
    _clearError(notify: false);
    notifyListeners();
    final previousTokens = await _repository.readTokens();
    final previousUser = _currentUser;
    try {
      await _repository.activateTokens(target.tokens);
      final session = await _repository.refreshSession();
      _currentUser = session.user;
      _status = AuthStatus.authenticated;
      await _rememberSession(session);
      return true;
    } catch (error) {
      if (previousTokens != null) {
        await _repository.activateTokens(previousTokens);
      } else {
        await _repository.clearLocalTokens();
      }
      _currentUser = previousUser;
      _status = previousUser == null
          ? AuthStatus.unauthenticated
          : AuthStatus.authenticated;
      _errorMessage = _messageFor(
        error,
        fallback: _text(
          '账号切换失败，已恢复原账号',
          'Could not switch accounts. The previous account was restored.',
        ),
      );
      return false;
    } finally {
      _isBusy = false;
      notifyListeners();
    }
  }

  Future<void> addAnotherAccount() async {
    if (_isBusy) return;
    await _repository.clearLocalTokens();
    _currentUser = null;
    _status = AuthStatus.unauthenticated;
    notifyListeners();
  }

  Future<void> removeSavedAccount(String userId) async {
    if (userId == _currentUser?.id) return;
    _savedAccounts = _savedAccounts
        .where((account) => account.userId != userId)
        .toList(growable: false);
    await _savedAccountStore?.save(_savedAccounts);
    notifyListeners();
  }

  Future<void> updateCurrentAccountProfile(AuthUser user) async {
    if (user.id != _currentUser?.id) return;

    _currentUser = user;
    _savedAccounts = _savedAccounts.map((account) {
      if (account.userId != user.id) return account;
      return SavedAccount(
        userId: account.userId,
        nickname: user.nickname,
        avatar: user.avatar,
        identifier: user.phone ?? user.email ?? account.identifier,
        tokens: account.tokens,
      );
    }).toList(growable: false);
    notifyListeners();
    await _savedAccountStore?.save(_savedAccounts);
  }

  Future<void> _runLogin(
    Future<AuthSession> Function() operation, {
    Future<void> Function(AuthSession session)? afterSuccess,
  }) async {
    if (_isBusy) {
      return;
    }
    _isBusy = true;
    _clearError(notify: false);
    notifyListeners();
    try {
      final session = await operation();
      _currentUser = session.user;
      _status = AuthStatus.authenticated;
      await _rememberSession(session);
      if (afterSuccess != null) {
        try {
          await afterSuccess(session);
        } catch (_) {
          _errorMessage = _text(
            '登录成功，但无法保存登录偏好',
            'Signed in, but sign-in preferences could not be saved.',
          );
        }
      }
    } catch (error) {
      _errorMessage = _messageFor(
        error,
        fallback:
            _text('登录失败，请稍后重试', 'Sign-in failed. Please try again later.'),
      );
    } finally {
      _isBusy = false;
      notifyListeners();
    }
  }

  Future<void> _loadLoginPreferences() async {
    if (_preferencesLoaded) {
      return;
    }
    final store = _loginPreferencesStore;
    if (store == null) {
      _preferencesLoaded = true;
      return;
    }
    try {
      _loginPreferences =
          (await store.read() ?? const LoginPreferences()).normalized();
      _preferencesLoaded = true;
    } catch (_) {
      // A value that cannot be read safely must never remain eligible for
      // automatic login on the next launch.
      try {
        await store.clear();
      } catch (_) {
        // Preserve the original read error for the caller.
      }
      _loginPreferences = const LoginPreferences();
      _preferencesLoaded = true;
      rethrow;
    }
  }

  Future<void> _loadSavedAccounts() async {
    _savedAccounts = await _savedAccountStore?.read() ?? const [];
  }

  /// Returns true when ordinary session restoration may continue.
  Future<bool> _resumePendingAccountDeletion() async {
    final store = _pendingAccountDeletionStore;
    if (store == null) return true;

    PendingAccountDeletion? pending;
    try {
      pending = await store.read();
    } catch (_) {
      holdPendingAccountDeletion();
      return false;
    }
    if (pending == null) return true;

    final deletionRepository = _accountDeletionRepository;
    if (deletionRepository == null) {
      holdPendingAccountDeletion();
      return false;
    }

    _currentUser = null;
    _status = AuthStatus.accountDeletionPending;
    notifyListeners();
    try {
      await deletionRepository.confirmAccountDeletion(pending);
      await completeAccountDeletion(pending.userId);
      await store.clear();
      return false;
    } catch (error) {
      if (_accountDeletionFailureRestoresAccount(error)) {
        try {
          await store.clear();
        } catch (_) {
          holdPendingAccountDeletion();
          return false;
        }
        _status = AuthStatus.restoring;
        _errorMessage = _text(
          '注销请求已终止，请重新开始注销流程',
          'The deletion request ended. Start again.',
        );
        return true;
      }
      holdPendingAccountDeletion();
      return false;
    }
  }

  Future<void> _rememberSession(AuthSession session) async {
    final account = SavedAccount(
      userId: session.user.id,
      nickname: session.user.nickname,
      avatar: session.user.avatar,
      identifier: session.user.phone ?? session.user.email ?? '',
      tokens: session.tokens,
    );
    _savedAccounts = [
      account,
      ..._savedAccounts.where((item) => item.userId != account.userId),
    ].take(5).toList(growable: false);
    await _savedAccountStore?.save(_savedAccounts);
  }

  Future<void> _persistPreferences(LoginPreferences preferences) async {
    final normalized = preferences.normalized();
    _loginPreferences = normalized;
    _preferencesLoaded = true;
    final store = _loginPreferencesStore;
    if (store == null) {
      return;
    }
    try {
      await store.save(normalized);
    } catch (_) {
      // An earlier persisted auto-login value must not survive a failed
      // update. Clearing is safer than silently logging in with stale data.
      await store.clear();
      _loginPreferences = const LoginPreferences();
      rethrow;
    }
  }

  Future<void> _disableAutoLoginFailClosed() async {
    final disabled = _loginPreferences.withAutoLoginDisabled();
    try {
      await _persistPreferences(disabled);
    } catch (_) {
      // _persistPreferences already cleared the persisted value.
    }
  }

  void _clearError({bool notify = true}) {
    if (_errorMessage == null) {
      return;
    }
    _errorMessage = null;
    if (notify) {
      notifyListeners();
    }
  }

  String _messageFor(Object error, {required String fallback}) {
    final message = switch (error) {
      ApiException(:final message) when message.isNotEmpty => message,
      ArgumentError(:final message) when message != null => message.toString(),
      _ => fallback,
    };
    // These auth responses currently contain Chinese text rather than stable
    // error codes. Resolve known messages using the current app language.
    return switch (message) {
      '密码错误，请重试' => _text(message, 'Incorrect password. Please try again.'),
      '该手机号未注册，请先注册' => _text(message,
          'This phone number is not registered. Please sign up first.'),
      '您尚未设置密码，请使用验证码登录或通过「忘记密码」重置' => _text(
          message,
          'You have not set a password. Sign in with a verification code or reset it using "Forgot password".',
        ),
      '验证码无效或已过期' =>
        _text(message, 'The verification code is invalid or has expired.'),
      '账号不可用' => _text(message, 'This account is unavailable.'),
      '手机号已注册' => _text(message, 'This phone number is already registered.'),
      _ => RegExp(r'[\u3400-\u9fff]').hasMatch(message)
          ? _text(message, fallback)
          : message,
    };
  }

  String _text(String chinese, String english) =>
      _messageResolver?.call(chinese, english) ?? chinese;
}

bool _accountDeletionFailureRestoresAccount(Object error) {
  if (error is! ApiException) return false;
  return switch (error.errorCode?.trim().toUpperCase()) {
    AccountDeletionErrorCode.blocked ||
    AccountDeletionErrorCode.authorizationExpired => true,
    _ => false,
  };
}

bool _loginPreferencesBelongToDeletedUser(
  LoginPreferences preferences,
  String userId,
  String identifier,
  AuthUser? deletedUser,
) {
  final ownerId = preferences.userId.trim();
  if (ownerId.isNotEmpty) return ownerId == userId;

  final phone = preferences.phone.trim();
  final normalizedIdentifier = identifier.trim();
  if (phone.isNotEmpty) return phone == normalizedIdentifier;
  if (deletedUser?.phone == null && deletedUser?.id == userId) return true;
  return normalizedIdentifier.contains('@');
}
