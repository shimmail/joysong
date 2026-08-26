import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/transient_message.dart';
import 'package:joysong_flutter/features/auth/presentation/login_strings.dart';
import 'package:joysong_flutter/features/auth/presentation/phone_country.dart';

enum AuthActionMode { register, resetPassword }

typedef PhoneRegistrationCheck = Future<bool> Function(String phone);
typedef AuthCodeSender = Future<void> Function(String phone);
typedef RegisterAccountAction = Future<bool> Function(
  String phone,
  String code,
  String password,
);
typedef ResetPasswordAction = Future<bool> Function(
  String phone,
  String code,
  String password,
);

class AuthActionPage extends StatefulWidget {
  const AuthActionPage({
    required this.mode,
    required this.onCheckPhoneRegistered,
    required this.onSendCode,
    this.onRegister,
    this.onResetPassword,
    this.errorMessage,
    this.onUserAgreement,
    this.onPrivacyPolicy,
    this.verificationCodeCountdown = 60,
    super.key,
  })  : assert(
          mode != AuthActionMode.register || onRegister != null,
          'Registration mode requires onRegister.',
        ),
        assert(
          mode != AuthActionMode.resetPassword || onResetPassword != null,
          'Reset-password mode requires onResetPassword.',
        );

  final AuthActionMode mode;
  final PhoneRegistrationCheck onCheckPhoneRegistered;
  final AuthCodeSender onSendCode;
  final RegisterAccountAction? onRegister;
  final ResetPasswordAction? onResetPassword;
  final String? Function()? errorMessage;
  final VoidCallback? onUserAgreement;
  final VoidCallback? onPrivacyPolicy;
  final int verificationCodeCountdown;

  @override
  State<AuthActionPage> createState() => _AuthActionPageState();
}

class _AuthActionPageState extends State<AuthActionPage> {
  final _formKey = GlobalKey<FormState>();
  final _phoneController = TextEditingController();
  final _codeController = TextEditingController();
  final _passwordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();
  PhoneCountry _country = supportedPhoneCountries.first;
  bool _accepted = false;
  bool _obscurePassword = true;
  bool _isSendingCode = false;
  bool _isSubmitting = false;
  int _countdown = 0;
  String? _localError;
  Timer? _timer;

  bool get _english =>
      AppLocaleScope.maybeOf(context)?.language == AppLanguage.english;

  _AuthActionStrings get _strings => _AuthActionStrings(_english);

  String get _phone => normalizeInternationalPhone(
        _phoneController.text,
        _country,
      );

  @override
  void dispose() {
    _timer?.cancel();
    _phoneController.dispose();
    _codeController.dispose();
    _passwordController.dispose();
    _confirmPasswordController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = _strings;
    final loginStrings =
        _english ? const LoginStrings.english() : const LoginStrings.chinese();
    return Scaffold(
      appBar: AppBar(
        title: Text(
          widget.mode == AuthActionMode.register
              ? strings.registerTitle
              : strings.resetTitle,
        ),
        actions: [
          TextButton(
            key: const Key('auth-action-language-toggle'),
            onPressed: _isSubmitting ? null : _toggleLanguage,
            child: const Text('中 / EN'),
          ),
        ],
      ),
      body: SafeArea(
        child: AutofillGroup(
          child: Form(
            key: _formKey,
            child: ListView(
              padding: const EdgeInsets.fromLTRB(24, 20, 24, 32),
              children: [
                Text(
                  widget.mode == AuthActionMode.register
                      ? strings.registerSubtitle
                      : strings.resetSubtitle,
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                ),
                const SizedBox(height: 24),
                TextFormField(
                  key: const Key('auth-action-phone'),
                  controller: _phoneController,
                  enabled: !_isSubmitting,
                  keyboardType: TextInputType.phone,
                  textInputAction: TextInputAction.next,
                  autofillHints: const [AutofillHints.telephoneNumber],
                  inputFormatters: [
                    FilteringTextInputFormatter.allow(RegExp(r'[\d\s-]')),
                  ],
                  decoration: InputDecoration(
                    labelText: strings.phone,
                    prefixIconConstraints: const BoxConstraints(minWidth: 112),
                    prefixIcon: PopupMenuButton<PhoneCountry>(
                      key: const Key('auth-action-country'),
                      enabled: !_isSubmitting,
                      tooltip: loginStrings.countryPickerTitle,
                      onOpened: () =>
                          FocusManager.instance.primaryFocus?.unfocus(),
                      onSelected: (value) => setState(() => _country = value),
                      itemBuilder: (_) => [
                        for (final country in supportedPhoneCountries)
                          PopupMenuItem(
                            value: country,
                            child: Text(
                              '${country.flag} '
                              '${loginStrings.countryName(country)} '
                              '${country.dialCode}',
                            ),
                          ),
                      ],
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text('${_country.flag} ${_country.dialCode}'),
                            const Icon(Icons.arrow_drop_down),
                          ],
                        ),
                      ),
                    ),
                  ),
                  validator: (value) {
                    final digits = (value ?? '').replaceAll(RegExp(r'\D'), '');
                    if (digits.isEmpty) return strings.phoneRequired;
                    final fullDigits = '${_country.dialCode}$digits'
                        .replaceAll(RegExp(r'\D'), '');
                    if (digits.length < 6 || fullDigits.length > 15) {
                      return strings.phoneInvalid;
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 16),
                TextFormField(
                  key: const Key('auth-action-code'),
                  controller: _codeController,
                  enabled: !_isSubmitting,
                  keyboardType: TextInputType.number,
                  textInputAction: TextInputAction.next,
                  maxLength: 6,
                  inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                  decoration: InputDecoration(
                    labelText: strings.code,
                    counterText: '',
                    suffixIconConstraints: const BoxConstraints(minWidth: 104),
                    suffixIcon: TextButton(
                      key: const Key('auth-action-send-code'),
                      onPressed:
                          _isSendingCode || _isSubmitting || _countdown > 0
                              ? null
                              : _sendCode,
                      child: Text(
                        _isSendingCode
                            ? strings.sending
                            : _countdown > 0
                                ? '${_countdown}s'
                                : strings.sendCode,
                      ),
                    ),
                  ),
                  validator: (value) => RegExp(r'^\d{6}$').hasMatch(value ?? '')
                      ? null
                      : strings.codeInvalid,
                ),
                const SizedBox(height: 16),
                TextFormField(
                  key: const Key('auth-action-password'),
                  controller: _passwordController,
                  enabled: !_isSubmitting,
                  obscureText: _obscurePassword,
                  textInputAction: TextInputAction.next,
                  autofillHints: widget.mode == AuthActionMode.register
                      ? const [AutofillHints.newPassword]
                      : const [AutofillHints.password],
                  decoration: InputDecoration(
                    labelText: widget.mode == AuthActionMode.register
                        ? strings.password
                        : strings.newPassword,
                    suffixIcon: IconButton(
                      onPressed: _isSubmitting
                          ? null
                          : () => setState(
                                () => _obscurePassword = !_obscurePassword,
                              ),
                      icon: Icon(
                        _obscurePassword
                            ? Icons.visibility_outlined
                            : Icons.visibility_off_outlined,
                      ),
                    ),
                  ),
                  validator: (value) =>
                      (value ?? '').length < 8 ? strings.passwordInvalid : null,
                ),
                const SizedBox(height: 16),
                TextFormField(
                  key: const Key('auth-action-confirm-password'),
                  controller: _confirmPasswordController,
                  enabled: !_isSubmitting,
                  obscureText: true,
                  textInputAction: TextInputAction.done,
                  onFieldSubmitted: (_) => _submit(),
                  decoration: InputDecoration(
                    labelText: strings.confirmPassword,
                  ),
                  validator: (value) => value != _passwordController.text
                      ? strings.passwordMismatch
                      : null,
                ),
                if (widget.mode == AuthActionMode.register) ...[
                  const SizedBox(height: 14),
                  CheckboxListTile(
                    key: const Key('auth-action-agreement'),
                    contentPadding: EdgeInsets.zero,
                    controlAffinity: ListTileControlAffinity.leading,
                    value: _accepted,
                    onChanged: _isSubmitting
                        ? null
                        : (value) => setState(() => _accepted = value ?? false),
                    title: Wrap(
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        Text(strings.acceptPrefix),
                        _LinkText(
                          label: loginStrings.userAgreement,
                          onPressed: widget.onUserAgreement,
                        ),
                        Text(loginStrings.agreementJoiner),
                        _LinkText(
                          label: loginStrings.privacyPolicy,
                          onPressed: widget.onPrivacyPolicy,
                        ),
                      ],
                    ),
                  ),
                ],
                if (_localError != null) ...[
                  const SizedBox(height: 12),
                  Text(
                    _localError!,
                    key: const Key('auth-action-error'),
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ],
                const SizedBox(height: 24),
                FilledButton(
                  key: const Key('auth-action-submit'),
                  onPressed: _isSubmitting ? null : _submit,
                  child: _isSubmitting
                      ? const SizedBox.square(
                          dimension: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : Text(
                          widget.mode == AuthActionMode.register
                              ? strings.registerButton
                              : strings.resetButton,
                        ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _sendCode() async {
    final phoneError = _validatePhoneOnly();
    if (phoneError != null) {
      setState(() => _localError = phoneError);
      return;
    }
    setState(() {
      _isSendingCode = true;
      _localError = null;
    });
    try {
      final registered = await widget.onCheckPhoneRegistered(_phone);
      final expectsRegistered = widget.mode == AuthActionMode.resetPassword;
      if (registered != expectsRegistered) {
        setState(() {
          _localError = expectsRegistered
              ? _strings.accountNotFound
              : _strings.accountExists;
        });
        return;
      }
      await widget.onSendCode(_phone);
      if (!mounted) return;
      setState(() => _countdown = widget.verificationCodeCountdown);
      _timer?.cancel();
      _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
        if (!mounted || _countdown <= 1) {
          timer.cancel();
          if (mounted) setState(() => _countdown = 0);
          return;
        }
        setState(() => _countdown -= 1);
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _localError = widget.errorMessage?.call() ?? _strings.sendFailed;
        });
      }
    } finally {
      if (mounted) setState(() => _isSendingCode = false);
    }
  }

  String? _validatePhoneOnly() {
    final digits = _phoneController.text.replaceAll(RegExp(r'\D'), '');
    if (digits.isEmpty) return _strings.phoneRequired;
    final fullDigits =
        '${_country.dialCode}$digits'.replaceAll(RegExp(r'\D'), '');
    if (digits.length < 6 || fullDigits.length > 15) {
      return _strings.phoneInvalid;
    }
    return null;
  }

  Future<void> _submit() async {
    FocusManager.instance.primaryFocus?.unfocus();
    setState(() => _localError = null);
    if (!(_formKey.currentState?.validate() ?? false)) return;
    if (widget.mode == AuthActionMode.register && !_accepted) {
      setState(() => _localError = _strings.acceptRequired);
      return;
    }
    setState(() => _isSubmitting = true);
    final bool succeeded;
    try {
      succeeded = widget.mode == AuthActionMode.register
          ? await widget.onRegister!(
              _phone,
              _codeController.text,
              _passwordController.text,
            )
          : await widget.onResetPassword!(
              _phone,
              _codeController.text,
              _passwordController.text,
            );
    } catch (_) {
      if (mounted) {
        setState(() {
          _isSubmitting = false;
          _localError = widget.errorMessage?.call() ?? _strings.submitFailed;
        });
      }
      return;
    }
    if (!mounted) return;
    if (!succeeded) {
      setState(() {
        _isSubmitting = false;
        _localError = widget.errorMessage?.call() ?? _strings.submitFailed;
      });
      return;
    }
    if (widget.mode == AuthActionMode.resetPassword) {
      showTransientMessage(context, _strings.resetSucceeded);
    }
    Navigator.of(context).pop(true);
  }

  Future<void> _toggleLanguage() async {
    final controller = AppLocaleScope.maybeOf(context);
    if (controller == null) return;
    await controller.setLanguage(
      controller.language == AppLanguage.chinese
          ? AppLanguage.english
          : AppLanguage.chinese,
    );
  }
}

class _LinkText extends StatelessWidget {
  const _LinkText({required this.label, required this.onPressed});

  final String label;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) => InkWell(
        onTap: onPressed,
        child: Text(
          label,
          style: TextStyle(color: Theme.of(context).colorScheme.primary),
        ),
      );
}

final class _AuthActionStrings {
  const _AuthActionStrings(this.english);

  final bool english;

  String get registerTitle => english ? 'Create account' : '注册账号';
  String get resetTitle => english ? 'Reset password' : '重置密码';
  String get registerSubtitle => english
      ? 'Verify your phone number to create a Joysong account.'
      : '验证手机号后创建娇颜颂账号。';
  String get resetSubtitle => english
      ? 'Verify the registered phone number before setting a new password.'
      : '验证已注册手机号后设置新密码。';
  String get phone => english ? 'Phone number' : '手机号';
  String get phoneRequired => english ? 'Enter your phone number' : '请输入手机号';
  String get phoneInvalid =>
      english ? 'Enter a valid phone number' : '请输入有效手机号';
  String get code => english ? 'Verification code' : '验证码';
  String get codeInvalid => english ? 'Enter the 6-digit code' : '请输入 6 位验证码';
  String get sendCode => english ? 'Get code' : '获取验证码';
  String get sending => english ? 'Sending...' : '发送中…';
  String get password => english ? 'Password' : '密码';
  String get newPassword => english ? 'New password' : '新密码';
  String get confirmPassword => english ? 'Confirm password' : '确认密码';
  String get passwordInvalid =>
      english ? 'Password must be at least 8 characters' : '密码至少需要 8 位';
  String get passwordMismatch =>
      english ? 'Passwords do not match' : '两次输入的密码不一致';
  String get acceptPrefix =>
      english ? 'I have read and accept the ' : '我已阅读并同意';
  String get acceptRequired => english
      ? 'Please accept the User Agreement and Privacy Policy'
      : '请先同意用户协议和隐私政策';
  String get registerButton => english ? 'Create account' : '注册并登录';
  String get resetButton => english ? 'Reset password' : '确认重置';
  String get accountExists => english
      ? 'This phone number is already registered. Please sign in.'
      : '该手机号已注册，请直接登录';
  String get accountNotFound =>
      english ? 'No account was found for this phone number.' : '该手机号尚未注册';
  String get sendFailed => english
      ? 'Unable to send the verification code. Please try again.'
      : '验证码发送失败，请稍后重试';
  String get submitFailed => english
      ? 'The request failed. Please check your details and try again.'
      : '操作失败，请核对信息后重试';
  String get resetSucceeded =>
      english ? 'Password reset. Please sign in again.' : '密码已重置，请重新登录';
}
