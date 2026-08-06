import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_repository.dart';

enum AccountSecurityLoadStatus { idle, loading, ready, failure, deleted }

enum AccountSecurityAction {
  changePassword,
  sendCode,
  resetPassword,
  setPassword,
  sendCurrentPhoneCode,
  verifyCurrentPhoneCode,
  sendNewPhoneCode,
  completePhoneChange,
  deleteAccount,
}

final class AccountSecurityController extends ChangeNotifier {
  AccountSecurityController(this._repository);

  final AccountSecurityRepository _repository;

  AccountSecurityLoadStatus _status = AccountSecurityLoadStatus.idle;
  AccountSecurityProfile? _profile;
  AccountSecurityAction? _activeAction;
  String? _errorMessage;
  String? _successMessage;
  bool _sessionMustEnd = false;
  bool _currentPhoneVerified = false;
  String? _pendingNewPhone;

  AccountSecurityLoadStatus get status => _status;
  AccountSecurityProfile? get profile => _profile;
  AccountSecurityAction? get activeAction => _activeAction;
  String? get errorMessage => _errorMessage;
  String? get successMessage => _successMessage;
  bool get isBusy => _activeAction != null;
  bool get sessionMustEnd => _sessionMustEnd;
  bool get currentPhoneVerified => _currentPhoneVerified;
  String? get pendingNewPhone => _pendingNewPhone;

  // The server currently exposes no device/session inventory or remote logout
  // endpoint. The page must keep device management visibly disabled.
  bool get supportsDeviceManagement => false;

  Future<void> load({bool force = false}) async {
    if (_status == AccountSecurityLoadStatus.loading ||
        (!force && _status == AccountSecurityLoadStatus.ready)) {
      return;
    }
    _status = AccountSecurityLoadStatus.loading;
    _errorMessage = null;
    notifyListeners();
    try {
      _profile = await _repository.getProfile();
      _status = AccountSecurityLoadStatus.ready;
    } catch (error) {
      _status = AccountSecurityLoadStatus.failure;
      _errorMessage = _messageFor(error, '账号安全信息加载失败');
    }
    notifyListeners();
  }

  Future<bool> changePassword({
    required String oldPassword,
    required String newPassword,
  }) {
    return _runSensitive(
      AccountSecurityAction.changePassword,
      () => _repository.changePassword(
        oldPassword: oldPassword,
        newPassword: newPassword,
      ),
      successMessage: '密码已修改，请重新登录',
      endSession: true,
    );
  }

  Future<bool> sendPasswordCode() async {
    final phone = _profile?.phone;
    if (phone == null) {
      _setError('当前账号未绑定手机号');
      return false;
    }
    return _runSensitive(
      AccountSecurityAction.sendCode,
      () => _repository.sendPasswordCode(phone),
      successMessage: '验证码已发送至 ${maskPhone(phone)}',
    );
  }

  Future<bool> resetPassword({
    required String code,
    required String newPassword,
  }) async {
    final phone = _profile?.phone;
    if (phone == null) {
      _setError('当前账号未绑定手机号');
      return false;
    }
    return _runSensitive(
      AccountSecurityAction.resetPassword,
      () => _repository.resetPassword(
        phone: phone,
        code: code,
        newPassword: newPassword,
      ),
      successMessage: '密码已重置，请重新登录',
      endSession: true,
    );
  }

  Future<bool> setPassword({
    required String code,
    required String newPassword,
  }) async {
    final phone = _profile?.phone;
    if (phone == null) {
      _setError('当前账号未绑定手机号，暂时无法设置密码');
      return false;
    }
    final success = await _runSensitive(
      AccountSecurityAction.setPassword,
      () => _repository.setPassword(
        phone: phone,
        code: code,
        newPassword: newPassword,
      ),
      successMessage: '密码已设置，请重新登录',
      endSession: true,
    );
    if (success && _profile != null) {
      _profile = _profile!.copyWith(hasPassword: true);
      notifyListeners();
    }
    return success;
  }

  void beginPhoneChange() {
    if (isBusy) return;
    _currentPhoneVerified = false;
    _pendingNewPhone = null;
    _errorMessage = null;
    _successMessage = null;
    notifyListeners();
  }

  Future<bool> sendCurrentPhoneChangeCode() async {
    final profile = _profile;
    if (profile == null || !profile.hasBoundPhone) {
      _setError('当前账号未绑定手机号');
      return false;
    }
    return _runSensitive(
      AccountSecurityAction.sendCurrentPhoneCode,
      _repository.sendCurrentPhoneChangeCode,
      successMessage: '验证码已发送至 ${profile.maskedPhone}',
    );
  }

  Future<bool> verifyCurrentPhoneChangeCode(String code) async {
    if (_profile?.hasBoundPhone != true) {
      _setError('当前账号未绑定手机号');
      return false;
    }
    final success = await _runSensitive(
      AccountSecurityAction.verifyCurrentPhoneCode,
      () => _repository.verifyCurrentPhoneChangeCode(code),
      successMessage: '当前手机号验证成功，请输入新手机号',
    );
    if (success) {
      _currentPhoneVerified = true;
      notifyListeners();
    }
    return success;
  }

  Future<bool> sendNewPhoneChangeCode(String phone) async {
    String normalized;
    try {
      normalized = validateE164Phone(phone);
    } on ArgumentError catch (error) {
      _setError(error.message?.toString() ?? '手机号格式不正确');
      return false;
    }
    final profile = _profile;
    if (profile == null) {
      _setError('账号安全信息尚未加载');
      return false;
    }
    if (profile.hasBoundPhone && !_currentPhoneVerified) {
      _setError('请先验证当前手机号');
      return false;
    }
    final success = await _runSensitive(
      AccountSecurityAction.sendNewPhoneCode,
      () => profile.hasBoundPhone
          ? _repository.sendNewPhoneChangeCode(normalized)
          : _repository.sendBindingPhoneCode(normalized),
      successMessage: '验证码已发送至 ${maskPhone(normalized)}',
    );
    if (success) {
      _pendingNewPhone = normalized;
      notifyListeners();
    }
    return success;
  }

  Future<bool> completePhoneChange({
    required String phone,
    required String code,
  }) async {
    String normalized;
    try {
      normalized = validateE164Phone(phone);
    } on ArgumentError catch (error) {
      _setError(error.message?.toString() ?? '手机号格式不正确');
      return false;
    }
    if (_pendingNewPhone != normalized) {
      _setError('请先向该手机号发送验证码');
      return false;
    }
    final profile = _profile;
    if (profile == null) {
      _setError('账号安全信息尚未加载');
      return false;
    }
    final success = await _runSensitive(
      AccountSecurityAction.completePhoneChange,
      () => profile.hasBoundPhone
          ? _repository.changePhone(phone: normalized, code: code)
          : _repository.bindPhone(phone: normalized, code: code),
      successMessage: profile.hasBoundPhone ? '手机号已更换，请重新登录' : '手机号已绑定，请重新登录',
      endSession: true,
    );
    if (success) {
      _profile = profile.copyWith(phone: normalized);
      notifyListeners();
    }
    return success;
  }

  Future<bool> deleteAccount() async {
    final success = await _runSensitive(
      AccountSecurityAction.deleteAccount,
      _repository.deleteAccount,
      successMessage: '账号已注销',
      endSession: true,
    );
    if (success) {
      _status = AccountSecurityLoadStatus.deleted;
      notifyListeners();
    }
    return success;
  }

  void clearFeedback() {
    if (_errorMessage == null && _successMessage == null) return;
    _errorMessage = null;
    _successMessage = null;
    notifyListeners();
  }

  Future<bool> _runSensitive(
    AccountSecurityAction action,
    Future<void> Function() operation, {
    required String successMessage,
    bool endSession = false,
  }) async {
    if (isBusy) return false;
    _activeAction = action;
    _errorMessage = null;
    _successMessage = null;
    notifyListeners();
    try {
      await operation();
      _successMessage = successMessage;
      _sessionMustEnd = _sessionMustEnd || endSession;
      return true;
    } catch (error) {
      _errorMessage = _messageFor(error, '操作失败，请稍后重试');
      return false;
    } finally {
      _activeAction = null;
      notifyListeners();
    }
  }

  void _setError(String message) {
    _errorMessage = message;
    _successMessage = null;
    notifyListeners();
  }
}

String _messageFor(Object error, String fallback) {
  if (error is ApiException && error.message.trim().isNotEmpty) {
    return error.message;
  }
  if (error is ArgumentError && error.message != null) {
    return error.message.toString();
  }
  if (error is FormatException && error.message.isNotEmpty) {
    return error.message;
  }
  return fallback;
}
