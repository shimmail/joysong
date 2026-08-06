import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/auth/data/login_preferences_store.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/auth/domain/auth_repository.dart';

enum AuthStatus { restoring, unauthenticated, authenticated }

typedef AuthMessageResolver = String Function(String chinese, String english);

final class AuthController extends ChangeNotifier {
  AuthController(
    this._repository, {
    LoginPreferencesStore? loginPreferencesStore,
    AuthMessageResolver? messageResolver,
  })  : _loginPreferencesStore = loginPreferencesStore,
        _messageResolver = messageResolver;

  final AuthRepository _repository;
  final LoginPreferencesStore? _loginPreferencesStore;
  final AuthMessageResolver? _messageResolver;

  AuthStatus _status = AuthStatus.restoring;
  bool _isBusy = false;
  bool _preferencesLoaded = false;
  String? _errorMessage;
  AuthUser? _currentUser;
  LoginPreferences _loginPreferences = const LoginPreferences();

  AuthStatus get status => _status;

  bool get isBusy => _isBusy;

  String? get errorMessage => _errorMessage;

  AuthUser? get currentUser => _currentUser;

  LoginPreferences get loginPreferences => _loginPreferences;

  Future<void> restoreSession() async {
    _status = AuthStatus.restoring;
    _clearError(notify: false);

    AuthTokens? tokens;
    try {
      tokens = await _repository.readTokens();
    } catch (error) {
      _status = AuthStatus.unauthenticated;
      _errorMessage = _messageFor(error, fallback: '无法读取本地安全凭证');
      notifyListeners();
      return;
    }

    try {
      await _loadLoginPreferences();
    } catch (error) {
      _errorMessage = _messageFor(error, fallback: '无法读取登录偏好');
    }

    if (tokens != null) {
      try {
        final session = await _repository.refreshSession();
        _currentUser = session.user;
        _status = AuthStatus.authenticated;
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
      _errorMessage = _messageFor(error, fallback: '自动登录失败，请重新登录');
      await _disableAutoLoginFailClosed();
    }
    notifyListeners();
  }

  Future<void> loginWithPassword(
    String phone,
    String password, {
    bool rememberPassword = false,
    bool autoLogin = false,
    bool agreementsAccepted = false,
  }) {
    return _runLogin(
      () => _repository.loginWithPassword(phone: phone, password: password),
      afterSuccess: (_) => _persistPreferences(
        LoginPreferences(
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
      afterSuccess: (_) async {
        await _loadLoginPreferences();
        final sameAccount = _loginPreferences.phone == phone.trim();
        await _persistPreferences(
          LoginPreferences(
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

  Future<void> sendCode(String phone) async {
    _clearError();
    try {
      await _repository.sendCode(phone: phone);
    } catch (error) {
      _errorMessage = _messageFor(error, fallback: '验证码发送失败');
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
          LoginPreferences(phone: phone, agreementsAccepted: true),
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
      _currentUser = session.user;
      if (_status != AuthStatus.authenticated) {
        _status = AuthStatus.authenticated;
        notifyListeners();
      }
      return session.tokens.accessToken;
    } catch (error) {
      if (error is ApiException && error.isUnauthorized) {
        _currentUser = null;
        _status = AuthStatus.unauthenticated;
        _errorMessage = '登录已过期，请重新登录';
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
    _clearError(notify: false);
    notifyListeners();
    try {
      try {
        await _loadLoginPreferences();
        await _disableAutoLoginFailClosed();
      } catch (_) {
        _errorMessage = '无法更新登录偏好，已清除自动登录信息';
      }
      try {
        await _repository.logout();
      } catch (error) {
        _errorMessage = _messageFor(error, fallback: '远端退出失败，本地登录已清除');
      }
    } finally {
      _currentUser = null;
      _status = AuthStatus.unauthenticated;
      _isBusy = false;
      notifyListeners();
    }
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
      if (afterSuccess != null) {
        try {
          await afterSuccess(session);
        } catch (_) {
          _errorMessage = '登录成功，但无法保存登录偏好';
        }
      }
    } catch (error) {
      _errorMessage = _messageFor(error, fallback: '登录失败，请稍后重试');
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
    if (error is ApiException && error.message.isNotEmpty) {
      return error.message;
    }
    if (error is ArgumentError && error.message != null) {
      return error.message.toString();
    }
    return fallback;
  }

  String _text(String chinese, String english) =>
      _messageResolver?.call(chinese, english) ?? chinese;
}
