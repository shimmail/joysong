import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/account_security/data/account_deletion_pending_store.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_repository.dart';

enum AccountSecurityLoadStatus { idle, loading, ready, failure, deleted }

enum AccountDeletionStage {
  idle,
  preflighting,
  blocked,
  awaitingStepUp,
  awaitingConfirmation,
  confirming,
  completed,
}

enum AccountSecurityAction {
  changePassword,
  sendCode,
  resetPassword,
  setPassword,
  sendCurrentPhoneCode,
  verifyCurrentPhoneCode,
  sendNewPhoneCode,
  completePhoneChange,
  preflightAccountDeletion,
  sendAccountDeletionSmsCode,
  verifyAccountDeletion,
  confirmAccountDeletion,
}

typedef AccountDeletionGoogleIdTokenProvider = Future<String?> Function();
typedef AccountDeletionConfirmedCallback = Future<void> Function(String userId);
typedef AccountDeletionUncertainCallback = void Function();
typedef AccountDeletionIdempotencyKeyFactory = String Function();

final class AccountSecurityController extends ChangeNotifier {
  AccountSecurityController(
    this._repository, {
    AccountDeletionPendingStore? pendingDeletionStore,
    AccountDeletionGoogleIdTokenProvider? googleIdTokenProvider,
    AccountDeletionConfirmedCallback? onDeletionConfirmed,
    AccountDeletionUncertainCallback? onDeletionUncertain,
    AccountDeletionIdempotencyKeyFactory? idempotencyKeyFactory,
  })  : _pendingDeletionStore = pendingDeletionStore,
        _googleIdTokenProvider = googleIdTokenProvider,
        _onDeletionConfirmed = onDeletionConfirmed,
        _onDeletionUncertain = onDeletionUncertain,
        _idempotencyKeyFactory =
            idempotencyKeyFactory ?? _newDeletionIdempotencyKey;

  final AccountSecurityRepository _repository;
  final AccountDeletionPendingStore? _pendingDeletionStore;
  final AccountDeletionGoogleIdTokenProvider? _googleIdTokenProvider;
  final AccountDeletionConfirmedCallback? _onDeletionConfirmed;
  final AccountDeletionUncertainCallback? _onDeletionUncertain;
  final AccountDeletionIdempotencyKeyFactory _idempotencyKeyFactory;

  AccountSecurityLoadStatus _status = AccountSecurityLoadStatus.idle;
  AccountSecurityProfile? _profile;
  AccountSecurityAction? _activeAction;
  String? _errorMessage;
  String? _successMessage;
  bool _sessionMustEnd = false;
  bool _currentPhoneVerified = false;
  String? _pendingNewPhone;
  AccountDeletionStage _deletionStage = AccountDeletionStage.idle;
  AccountDeletionPreflight? _deletionPreflight;
  AccountDeletionAuthorization? _deletionAuthorization;
  PendingAccountDeletion? _pendingDeletion;
  String? _deletionErrorCode;
  bool _disposed = false;

  AccountSecurityLoadStatus get status => _status;
  AccountSecurityProfile? get profile => _profile;
  AccountSecurityAction? get activeAction => _activeAction;
  String? get errorMessage => _errorMessage;
  String? get successMessage => _successMessage;
  bool get isBusy => _activeAction != null;
  bool get sessionMustEnd => _sessionMustEnd;
  bool get currentPhoneVerified => _currentPhoneVerified;
  String? get pendingNewPhone => _pendingNewPhone;
  AccountDeletionStage get deletionStage => _deletionStage;
  AccountDeletionPreflight? get deletionPreflight => _deletionPreflight;
  String? get deletionErrorCode => _deletionErrorCode;

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

  Future<bool> beginAccountDeletion() async {
    if (isBusy) return false;
    _activeAction = AccountSecurityAction.preflightAccountDeletion;
    _deletionStage = AccountDeletionStage.preflighting;
    _deletionPreflight = null;
    _deletionAuthorization = null;
    _pendingDeletion = null;
    _deletionErrorCode = null;
    notifyListeners();
    try {
      final preflight = await _repository.preflightAccountDeletion();
      _deletionPreflight = preflight;
      _deletionStage = !preflight.eligible || preflight.blockers.isNotEmpty
          ? AccountDeletionStage.blocked
          : AccountDeletionStage.awaitingStepUp;
      return preflight.eligible && preflight.blockers.isEmpty;
    } catch (error) {
      _deletionErrorCode = _nonTerminalDeletionErrorCode(error);
      _deletionStage = AccountDeletionStage.idle;
      return false;
    } finally {
      _activeAction = null;
      if (!_disposed) notifyListeners();
    }
  }

  Future<bool> sendAccountDeletionSmsCode() async {
    final preflight = _deletionPreflight;
    if (isBusy ||
        _deletionStage != AccountDeletionStage.awaitingStepUp ||
        preflight?.stepUpMethod != AccountDeletionStepUpMethod.sms) {
      return false;
    }
    return _runDeletionAction(
      AccountSecurityAction.sendAccountDeletionSmsCode,
      () => _repository.sendAccountDeletionSmsCode(preflight!.requestId),
    );
  }

  Future<bool> verifyAccountDeletionSmsCode(String code) async {
    final preflight = _deletionPreflight;
    if (isBusy ||
        _deletionStage != AccountDeletionStage.awaitingStepUp ||
        preflight?.stepUpMethod != AccountDeletionStepUpMethod.sms) {
      return false;
    }
    String normalizedCode;
    try {
      normalizedCode = validateVerificationCode(code);
    } on ArgumentError {
      _deletionErrorCode = AccountDeletionErrorCode.invalidSmsCode;
      notifyListeners();
      return false;
    }
    return _verifyAccountDeletion(
      () => _repository.stepUpAccountDeletionWithSms(
        requestId: preflight!.requestId,
        code: normalizedCode,
      ),
    );
  }

  Future<bool> verifyAccountDeletionWithGoogle() async {
    final preflight = _deletionPreflight;
    if (isBusy ||
        _deletionStage != AccountDeletionStage.awaitingStepUp ||
        preflight?.stepUpMethod != AccountDeletionStepUpMethod.google) {
      return false;
    }
    final provider = _googleIdTokenProvider;
    if (provider == null) {
      _deletionErrorCode = AccountDeletionErrorCode.verificationFailed;
      notifyListeners();
      return false;
    }
    _activeAction = AccountSecurityAction.verifyAccountDeletion;
    _deletionErrorCode = null;
    notifyListeners();
    try {
      String? token;
      try {
        token = (await provider())?.trim();
      } catch (_) {
        _deletionErrorCode = AccountDeletionErrorCode.verificationFailed;
        return false;
      }
      if (token == null || token.isEmpty) {
        _deletionErrorCode =
            AccountDeletionErrorCode.googleReauthenticationCancelled;
        return false;
      }
      _deletionAuthorization =
          await _repository.stepUpAccountDeletionWithGoogle(
        requestId: preflight!.requestId,
        idToken: token,
      );
      _deletionStage = AccountDeletionStage.awaitingConfirmation;
      return true;
    } catch (error) {
      _deletionErrorCode = _nonTerminalDeletionErrorCode(error);
      return false;
    } finally {
      _activeAction = null;
      if (!_disposed) notifyListeners();
    }
  }

  Future<bool> confirmAccountDeletion({
    required String confirmation,
    required String userId,
  }) async {
    if (isBusy || _deletionStage != AccountDeletionStage.awaitingConfirmation) {
      return false;
    }
    if (confirmation != 'DELETE') {
      _deletionErrorCode = AccountDeletionErrorCode.invalidConfirmation;
      notifyListeners();
      return false;
    }
    final preflight = _deletionPreflight;
    final authorization = _deletionAuthorization;
    final store = _pendingDeletionStore;
    if (preflight == null || authorization == null || store == null) {
      _deletionErrorCode = AccountDeletionErrorCode.retryRequired;
      notifyListeners();
      return false;
    }

    final pending = _pendingDeletion ??
        PendingAccountDeletion(
          requestId: preflight.requestId,
          idempotencyKey: _idempotencyKeyFactory(),
          deletionAuthorization: authorization.token,
          policyVersion: preflight.policyVersion,
          userId: userId.trim(),
        );
    if (pending.userId.isEmpty) {
      _deletionErrorCode = AccountDeletionErrorCode.retryRequired;
      notifyListeners();
      return false;
    }

    _activeAction = AccountSecurityAction.confirmAccountDeletion;
    _deletionStage = AccountDeletionStage.confirming;
    _deletionErrorCode = null;
    notifyListeners();
    var pendingSaved = false;
    try {
      await store.save(pending);
      pendingSaved = true;
      _pendingDeletion = pending;
      await _repository.confirmAccountDeletion(pending);
      await _onDeletionConfirmed?.call(pending.userId);
      await store.clear();
      _pendingDeletion = null;
      _deletionStage = AccountDeletionStage.completed;
      _status = AccountSecurityLoadStatus.deleted;
      _successMessage = '账号已注销';
      _sessionMustEnd = true;
      return true;
    } catch (error) {
      final code = pendingSaved
          ? accountDeletionErrorCodeFor(error)
          : AccountDeletionErrorCode.requestFailed;
      _deletionErrorCode = code;
      final restorableCode = pendingSaved
          ? _restorableAccountDeletionTerminalCode(error)
          : null;
      if (restorableCode != null) {
        final cleared = await _clearPending(store);
        if (!cleared) {
          _deletionStage = AccountDeletionStage.awaitingConfirmation;
          _onDeletionUncertain?.call();
          return false;
        }
        _pendingDeletion = null;
        _deletionAuthorization = null;
        if (restorableCode == AccountDeletionErrorCode.authorizationExpired) {
          _deletionPreflight = null;
          _deletionStage = AccountDeletionStage.idle;
        } else {
          final blockers = _accountDeletionBlockersFrom(error);
          _deletionPreflight = AccountDeletionPreflight(
            requestId: preflight.requestId,
            eligible: false,
            stepUpMethod: preflight.stepUpMethod,
            maskedCredential: preflight.maskedCredential,
            policyVersion: preflight.policyVersion,
            blockers: blockers,
          );
          _deletionStage = AccountDeletionStage.blocked;
        }
      } else {
        _deletionStage = AccountDeletionStage.awaitingConfirmation;
        if (pendingSaved) _onDeletionUncertain?.call();
      }
      return false;
    } finally {
      _activeAction = null;
      if (!_disposed) notifyListeners();
    }
  }

  void resetAccountDeletionFlow() {
    if (isBusy) return;
    _deletionStage = AccountDeletionStage.idle;
    _deletionPreflight = null;
    _deletionAuthorization = null;
    _pendingDeletion = null;
    _deletionErrorCode = null;
    notifyListeners();
  }

  Future<bool> _verifyAccountDeletion(
    Future<AccountDeletionAuthorization> Function() operation,
  ) async {
    if (isBusy) return false;
    _activeAction = AccountSecurityAction.verifyAccountDeletion;
    _deletionErrorCode = null;
    notifyListeners();
    try {
      _deletionAuthorization = await operation();
      _deletionStage = AccountDeletionStage.awaitingConfirmation;
      return true;
    } catch (error) {
      _deletionErrorCode = _nonTerminalDeletionErrorCode(error);
      return false;
    } finally {
      _activeAction = null;
      if (!_disposed) notifyListeners();
    }
  }

  Future<bool> _runDeletionAction(
    AccountSecurityAction action,
    Future<void> Function() operation,
  ) async {
    if (isBusy) return false;
    _activeAction = action;
    _deletionErrorCode = null;
    notifyListeners();
    try {
      await operation();
      return true;
    } catch (error) {
      _deletionErrorCode = _nonTerminalDeletionErrorCode(error);
      return false;
    } finally {
      _activeAction = null;
      if (!_disposed) notifyListeners();
    }
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

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}

String accountDeletionErrorCodeFor(Object error) {
  if (error is ApiException) {
    final serverCode = error.errorCode?.trim().toUpperCase() ?? '';
    final stableCode = switch (serverCode) {
      AccountDeletionErrorCode.authorizationExpired =>
        AccountDeletionErrorCode.authorizationExpired,
      AccountDeletionErrorCode.idempotencyConflict =>
        AccountDeletionErrorCode.idempotencyConflict,
      AccountDeletionErrorCode.rateLimited =>
        AccountDeletionErrorCode.rateLimited,
      AccountDeletionErrorCode.smsDeliveryUnavailable =>
        AccountDeletionErrorCode.smsDeliveryUnavailable,
      AccountDeletionErrorCode.blockerUnavailable =>
        AccountDeletionErrorCode.blockerUnavailable,
      AccountDeletionErrorCode.disabled => AccountDeletionErrorCode.disabled,
      AccountDeletionErrorCode.blocked => AccountDeletionErrorCode.blocked,
      AccountDeletionErrorCode.verificationFailed =>
        AccountDeletionErrorCode.verificationFailed,
      _ => null,
    };
    if (stableCode != null) return stableCode;
    return switch (error.httpStatus ?? error.businessCode) {
      400 || 401 => AccountDeletionErrorCode.verificationFailed,
      429 => AccountDeletionErrorCode.rateLimited,
      503 => AccountDeletionErrorCode.blockerUnavailable,
      _ => AccountDeletionErrorCode.retryRequired,
    };
  }

  return AccountDeletionErrorCode.retryRequired;
}

String _nonTerminalDeletionErrorCode(Object error) {
  final code = accountDeletionErrorCodeFor(error);
  return code == AccountDeletionErrorCode.retryRequired
      ? AccountDeletionErrorCode.requestFailed
      : code;
}

String? _restorableAccountDeletionTerminalCode(Object error) {
  if (error is! ApiException) return null;
  return switch (error.errorCode?.trim().toUpperCase()) {
    AccountDeletionErrorCode.blocked => AccountDeletionErrorCode.blocked,
    AccountDeletionErrorCode.authorizationExpired =>
      AccountDeletionErrorCode.authorizationExpired,
    _ => null,
  };
}

List<AccountDeletionBlocker> _accountDeletionBlockersFrom(Object error) {
  if (error is! ApiException || error.data is! Map) return const [];
  final map = (error.data! as Map).map(
    (key, value) => MapEntry(key.toString(), value),
  );
  final rawBlockers = map['blockers'];
  if (rawBlockers is! List) return const [];
  try {
    return List.unmodifiable(rawBlockers.map(AccountDeletionBlocker.fromJson));
  } catch (_) {
    return const [];
  }
}

Future<bool> _clearPending(AccountDeletionPendingStore store) async {
  try {
    await store.clear();
    return true;
  } catch (_) {
    return false;
  }
}

String _newDeletionIdempotencyKey() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  return bytes.map((value) => value.toRadixString(16).padLeft(2, '0')).join();
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
