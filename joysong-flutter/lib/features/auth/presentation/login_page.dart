import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/features/auth/presentation/login_strings.dart';
import 'package:joysong_flutter/features/auth/presentation/phone_country.dart';

enum LoginMode { password, verificationCode }

typedef PasswordLoginCallback = Future<void> Function(
  String phone,
  String password, {
  required bool rememberPassword,
  required bool autoLogin,
  required bool agreementsAccepted,
});
typedef VerificationCodeLoginCallback = Future<void> Function(
  String phone,
  String verificationCode,
);
typedef SendVerificationCodeCallback = Future<void> Function(String phone);
typedef GoogleLoginCallback = Future<bool> Function();

class LoginPage extends StatefulWidget {
  const LoginPage({
    required this.onPasswordLogin,
    required this.onVerificationCodeLogin,
    required this.onSendVerificationCode,
    required this.onRegister,
    required this.onForgotPassword,
    this.onGoogleLogin,
    this.onUserAgreement,
    this.onPrivacyPolicy,
    this.isLoading = false,
    this.errorMessage,
    this.initialPhone = '',
    this.initialPassword = '',
    this.initialRememberPassword = false,
    this.initialAutoLogin = false,
    this.initialAgreementsAccepted = false,
    this.initialMode = LoginMode.verificationCode,
    this.countryCode = '+86',
    this.verificationCodeCountdown = 60,
    super.key,
  })  : assert(verificationCodeCountdown > 0),
        assert(!initialAutoLogin || initialRememberPassword);

  final PasswordLoginCallback onPasswordLogin;
  final VerificationCodeLoginCallback onVerificationCodeLogin;
  final SendVerificationCodeCallback onSendVerificationCode;
  final VoidCallback onRegister;
  final VoidCallback onForgotPassword;
  final GoogleLoginCallback? onGoogleLogin;
  final VoidCallback? onUserAgreement;
  final VoidCallback? onPrivacyPolicy;
  final bool isLoading;
  final String? errorMessage;
  final String initialPhone;
  final String initialPassword;
  final bool initialRememberPassword;
  final bool initialAutoLogin;
  final bool initialAgreementsAccepted;
  final LoginMode initialMode;
  final String countryCode;
  final int verificationCodeCountdown;

  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _phoneController;
  late final TextEditingController _passwordController;
  final _verificationCodeController = TextEditingController();

  late LoginMode _mode;
  late PhoneCountry _selectedCountry;
  late bool _hasAcceptedAgreements;
  late bool _rememberPassword;
  late bool _autoLogin;
  bool _obscurePassword = true;
  bool _isSubmitting = false;
  bool _isSendingCode = false;
  int _countdown = 0;
  String? _localError;
  Timer? _countdownTimer;

  bool get _isBusy => widget.isLoading || _isSubmitting;

  LoginStrings get _strings =>
      AppLocaleScope.maybeOf(context)?.language == AppLanguage.english
          ? const LoginStrings.english()
          : const LoginStrings.chinese();

  String get _normalizedPhone {
    return normalizeInternationalPhone(
      _phoneController.text,
      _selectedCountry,
    );
  }

  @override
  void initState() {
    super.initState();
    final fallbackCountry = phoneCountryForDialCode(widget.countryCode);
    final initialPhone = splitInternationalPhone(
      widget.initialPhone,
      fallbackCountry: fallbackCountry,
    );
    _selectedCountry = initialPhone.country;
    _phoneController = TextEditingController(text: initialPhone.nationalNumber);
    _passwordController = TextEditingController(text: widget.initialPassword);
    _hasAcceptedAgreements = widget.initialAgreementsAccepted;
    _rememberPassword = widget.initialRememberPassword;
    _autoLogin = widget.initialAutoLogin;
    _mode = widget.initialRememberPassword
        ? LoginMode.password
        : widget.initialMode;
  }

  @override
  void dispose() {
    _countdownTimer?.cancel();
    _phoneController.dispose();
    _passwordController.dispose();
    _verificationCodeController.dispose();
    super.dispose();
  }

  String? _validatePhone(String? value) {
    final digits = (value ?? '').replaceAll(RegExp(r'\D'), '');
    if (digits.isEmpty) {
      return _strings.phoneRequired;
    }
    final internationalDigits =
        '${_selectedCountry.dialCode}$digits'.replaceAll(RegExp(r'\D'), '');
    if (digits.length < 6 || internationalDigits.length > 15) {
      return _strings.phoneInvalid;
    }
    return null;
  }

  String? _validatePassword(String? value) {
    if ((value ?? '').isEmpty) {
      return _strings.passwordRequired;
    }
    if (value!.length < 8) {
      return _strings.passwordTooShort;
    }
    return null;
  }

  String? _validateVerificationCode(String? value) {
    if ((value ?? '').isEmpty) {
      return _strings.codeRequired;
    }
    if (!RegExp(r'^\d{6}$').hasMatch(value!)) {
      return _strings.codeInvalid;
    }
    return null;
  }

  String _readableError(Object error) {
    final message = error.toString();
    return message.startsWith('Exception: ')
        ? message.substring('Exception: '.length)
        : message;
  }

  Future<void> _submit() async {
    FocusManager.instance.primaryFocus?.unfocus();
    setState(() => _localError = null);

    if (!(_formKey.currentState?.validate() ?? false)) {
      return;
    }
    if (!_hasAcceptedAgreements) {
      setState(() => _localError = _strings.acceptAgreementError);
      return;
    }

    setState(() => _isSubmitting = true);
    try {
      switch (_mode) {
        case LoginMode.password:
          await widget.onPasswordLogin(
            _normalizedPhone,
            _passwordController.text,
            rememberPassword: _rememberPassword,
            autoLogin: _autoLogin,
            agreementsAccepted: _hasAcceptedAgreements,
          );
          break;
        case LoginMode.verificationCode:
          await widget.onVerificationCodeLogin(
            _normalizedPhone,
            _verificationCodeController.text,
          );
          break;
      }
    } catch (error) {
      if (mounted) {
        setState(() => _localError = _readableError(error));
      }
    } finally {
      if (mounted) {
        setState(() => _isSubmitting = false);
      }
    }
  }

  Future<void> _sendVerificationCode() async {
    if (_isBusy || _isSendingCode || _countdown > 0) {
      return;
    }

    final phoneError = _validatePhone(_phoneController.text);
    if (phoneError != null) {
      setState(() => _localError = phoneError);
      return;
    }

    FocusManager.instance.primaryFocus?.unfocus();
    setState(() {
      _isSendingCode = true;
      _localError = null;
    });
    try {
      await widget.onSendVerificationCode(_normalizedPhone);
      if (!mounted) {
        return;
      }
      setState(() => _countdown = widget.verificationCodeCountdown);
      _startCountdown();
    } catch (error) {
      if (mounted) {
        setState(() => _localError = _readableError(error));
      }
    } finally {
      if (mounted) {
        setState(() => _isSendingCode = false);
      }
    }
  }

  Future<void> _googleLogin() async {
    if (_isBusy || widget.onGoogleLogin == null) return;
    if (!_hasAcceptedAgreements) {
      setState(() => _localError = _strings.acceptAgreementError);
      return;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    setState(() {
      _isSubmitting = true;
      _localError = null;
    });
    try {
      final completed = await widget.onGoogleLogin!();
      if (!completed && mounted) {
        setState(() => _localError = _strings.googleLoginCancelled);
      }
    } catch (error) {
      if (mounted) setState(() => _localError = _readableError(error));
    } finally {
      if (mounted) setState(() => _isSubmitting = false);
    }
  }

  void _startCountdown() {
    _countdownTimer?.cancel();
    _countdownTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      if (_countdown <= 1) {
        timer.cancel();
        setState(() => _countdown = 0);
      } else {
        setState(() => _countdown -= 1);
      }
    });
  }

  void _changeMode(LoginMode mode) {
    if (_isBusy || mode == _mode) {
      return;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    setState(() {
      _mode = mode;
      _localError = null;
    });
  }

  void _setRememberPassword(bool value) {
    setState(() {
      _rememberPassword = value;
      if (!value) {
        _autoLogin = false;
      }
    });
  }

  void _setAutoLogin(bool value) {
    setState(() {
      _autoLogin = value;
      if (value) {
        _rememberPassword = true;
      }
    });
  }

  Future<void> _showCountryPicker() async {
    if (_isBusy) {
      return;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    final selected = await showDialog<PhoneCountry>(
      context: context,
      builder: (context) => _CountryPickerDialog(
        strings: _strings,
        selectedCountry: _selectedCountry,
      ),
    );
    if (selected == null || !mounted) {
      return;
    }
    setState(() {
      _selectedCountry = selected;
      _localError = null;
    });
  }

  Future<void> _toggleLanguage() async {
    if (_isBusy) return;
    final controller = AppLocaleScope.maybeOf(context);
    if (controller == null) return;
    final target = controller.language == AppLanguage.chinese
        ? AppLanguage.english
        : AppLanguage.chinese;
    try {
      await controller.setLanguage(target);
      if (mounted) setState(() => _localError = null);
    } catch (_) {
      if (mounted) {
        setState(() => _localError = _strings.languageSwitchFailed);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final strings = _strings;
    final localeController = AppLocaleScope.maybeOf(context);
    final visibleError = widget.errorMessage ?? _localError;

    return Scaffold(
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            return SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(24, 24, 24, 24),
              child: ConstrainedBox(
                constraints: BoxConstraints(
                  minHeight: constraints.maxHeight - 48,
                  maxWidth: 460,
                ),
                child: Center(
                  child: AutofillGroup(
                    child: Form(
                      key: _formKey,
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Align(
                            alignment: Alignment.centerRight,
                            child: Tooltip(
                              message: strings.switchLanguage,
                              child: TextButton.icon(
                                key: const Key('login-language-toggle'),
                                onPressed: localeController == null || _isBusy
                                    ? null
                                    : _toggleLanguage,
                                style: _compactTextButtonStyle().copyWith(
                                  foregroundColor: WidgetStatePropertyAll(
                                    colors.onSurfaceVariant,
                                  ),
                                ),
                                icon: const Icon(
                                  Icons.language_rounded,
                                  size: 16,
                                ),
                                label: const Text('中 / EN'),
                              ),
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            strings.brandTitle,
                            textAlign: TextAlign.center,
                            style: theme.textTheme.displaySmall?.copyWith(
                              color: colors.primary,
                              fontSize: 30,
                              fontWeight: FontWeight.w600,
                              height: 1.15,
                            ),
                          ),
                          const SizedBox(height: 36),
                          _LoginModeSelector(
                            mode: _mode,
                            enabled: !_isBusy,
                            onChanged: _changeMode,
                            strings: strings,
                          ),
                          const SizedBox(height: 12),
                          Text(
                            _mode == LoginMode.password
                                ? strings.passwordSubtitle
                                : strings.codeSubtitle,
                            textAlign: TextAlign.center,
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: colors.onSurfaceVariant,
                            ),
                          ),
                          const SizedBox(height: 24),
                          TextFormField(
                            key: const Key('login-phone-field'),
                            controller: _phoneController,
                            enabled: !_isBusy,
                            keyboardType: TextInputType.phone,
                            textInputAction: TextInputAction.next,
                            autofillHints: const [
                              AutofillHints.telephoneNumber,
                            ],
                            inputFormatters: [
                              FilteringTextInputFormatter.allow(
                                RegExp(r'[\d\s-]'),
                              ),
                            ],
                            decoration: _inputDecoration(
                              context,
                              hintText: strings.phoneHint,
                              prefix: _CountryCodeButton(
                                country: _selectedCountry,
                                enabled: !_isBusy,
                                onPressed: _showCountryPicker,
                                strings: strings,
                              ),
                            ),
                            validator: _validatePhone,
                          ),
                          const SizedBox(height: 16),
                          if (_mode == LoginMode.password) ...[
                            TextFormField(
                              key: const Key('login-password-field'),
                              controller: _passwordController,
                              enabled: !_isBusy,
                              obscureText: _obscurePassword,
                              keyboardType: TextInputType.visiblePassword,
                              textInputAction: TextInputAction.done,
                              autofillHints: const [AutofillHints.password],
                              onFieldSubmitted: (_) => _submit(),
                              decoration: _inputDecoration(
                                context,
                                hintText: strings.passwordHint,
                                prefixIcon: Icons.lock_outline,
                                suffixIcon: IconButton(
                                  key: const Key('toggle-password-visibility'),
                                  tooltip: _obscurePassword
                                      ? strings.showPassword
                                      : strings.hidePassword,
                                  onPressed: _isBusy
                                      ? null
                                      : () => setState(
                                            () => _obscurePassword =
                                                !_obscurePassword,
                                          ),
                                  style: _noOverlayButtonStyle(),
                                  icon: Icon(
                                    _obscurePassword
                                        ? Icons.visibility_outlined
                                        : Icons.visibility_off_outlined,
                                    size: 20,
                                  ),
                                ),
                              ),
                              validator: _validatePassword,
                            ),
                            const SizedBox(height: 12),
                            Wrap(
                              spacing: 16,
                              runSpacing: 4,
                              crossAxisAlignment: WrapCrossAlignment.center,
                              children: [
                                _CompactCheckControl(
                                  key: const Key('remember-password-checkbox'),
                                  value: _rememberPassword,
                                  label: strings.rememberPassword,
                                  enabled: !_isBusy,
                                  onChanged: _setRememberPassword,
                                ),
                                _CompactCheckControl(
                                  key: const Key('auto-login-checkbox'),
                                  value: _autoLogin,
                                  label: strings.autoLogin,
                                  enabled: !_isBusy,
                                  onChanged: _setAutoLogin,
                                ),
                                TextButton(
                                  key: const Key('forgot-password-button'),
                                  onPressed:
                                      _isBusy ? null : widget.onForgotPassword,
                                  style: _compactTextButtonStyle(),
                                  child: Text(strings.forgotPassword),
                                ),
                              ],
                            ),
                          ] else ...[
                            TextFormField(
                              key: const Key('login-code-field'),
                              controller: _verificationCodeController,
                              enabled: !_isBusy,
                              keyboardType: TextInputType.number,
                              textInputAction: TextInputAction.done,
                              autofillHints: const [
                                AutofillHints.oneTimeCode,
                              ],
                              inputFormatters: [
                                FilteringTextInputFormatter.digitsOnly,
                                LengthLimitingTextInputFormatter(6),
                              ],
                              onFieldSubmitted: (_) => _submit(),
                              decoration: _inputDecoration(
                                context,
                                hintText: strings.codeHint,
                                prefixIcon: Icons.shield_outlined,
                                suffixIcon: TextButton(
                                  key: const Key('send-code-button'),
                                  onPressed: _isBusy ||
                                          _isSendingCode ||
                                          _countdown > 0
                                      ? null
                                      : _sendVerificationCode,
                                  style: _compactTextButtonStyle().copyWith(
                                    padding: const WidgetStatePropertyAll(
                                      EdgeInsets.symmetric(horizontal: 12),
                                    ),
                                  ),
                                  child: Text(
                                    _countdown > 0
                                        ? strings.retryCode(_countdown)
                                        : _isSendingCode
                                            ? strings.sendingCode
                                            : strings.sendCode,
                                  ),
                                ),
                              ),
                              validator: _validateVerificationCode,
                            ),
                          ],
                          const SizedBox(height: 14),
                          _AgreementRow(
                            value: _hasAcceptedAgreements,
                            enabled: !_isBusy,
                            onChanged: (value) => setState(
                              () => _hasAcceptedAgreements = value,
                            ),
                            onUserAgreement: widget.onUserAgreement,
                            onPrivacyPolicy: widget.onPrivacyPolicy,
                            strings: strings,
                          ),
                          AnimatedSwitcher(
                            duration: const Duration(milliseconds: 160),
                            child: visibleError == null
                                ? const SizedBox(height: 28)
                                : Padding(
                                    key: ValueKey(visibleError),
                                    padding: const EdgeInsets.only(top: 10),
                                    child: Text(
                                      visibleError,
                                      textAlign: TextAlign.center,
                                      style:
                                          theme.textTheme.bodySmall?.copyWith(
                                        color: colors.error,
                                      ),
                                    ),
                                  ),
                          ),
                          const SizedBox(height: 14),
                          SizedBox(
                            height: 50,
                            child: FilledButton(
                              key: const Key('login-submit-button'),
                              onPressed: _isBusy ? null : _submit,
                              style: FilledButton.styleFrom(
                                shape: const StadiumBorder(),
                                textStyle:
                                    theme.textTheme.titleMedium?.copyWith(
                                  fontWeight: FontWeight.w500,
                                ),
                              ).copyWith(
                                overlayColor: const WidgetStatePropertyAll(
                                  Colors.transparent,
                                ),
                                splashFactory: NoSplash.splashFactory,
                              ),
                              child: _isBusy
                                  ? Row(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        const SizedBox.square(
                                          dimension: 18,
                                          child: CircularProgressIndicator(
                                            strokeWidth: 2,
                                          ),
                                        ),
                                        const SizedBox(width: 10),
                                        Text(strings.loggingIn),
                                      ],
                                    )
                                  : Text(strings.login),
                            ),
                          ),
                          if (widget.onGoogleLogin != null) ...[
                            const SizedBox(height: 18),
                            Row(
                              children: [
                                const Expanded(child: Divider()),
                                Padding(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 12,
                                  ),
                                  child: Text(
                                    strings.or,
                                    style: theme.textTheme.bodySmall?.copyWith(
                                      color: colors.onSurfaceVariant,
                                    ),
                                  ),
                                ),
                                const Expanded(child: Divider()),
                              ],
                            ),
                            const SizedBox(height: 14),
                            SizedBox(
                              height: 50,
                              child: OutlinedButton.icon(
                                key: const Key('google-login-button'),
                                onPressed: _isBusy ? null : _googleLogin,
                                style: OutlinedButton.styleFrom(
                                  shape: const StadiumBorder(),
                                  foregroundColor: colors.onSurface,
                                  side: BorderSide(color: colors.outline),
                                ),
                                icon: const _GoogleMark(),
                                label: Text(strings.googleLogin),
                              ),
                            ),
                          ],
                          const SizedBox(height: 14),
                          Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Text(
                                strings.noAccount,
                                style: theme.textTheme.bodyMedium?.copyWith(
                                  color: colors.onSurfaceVariant,
                                ),
                              ),
                              TextButton(
                                key: const Key('register-button'),
                                onPressed: _isBusy ? null : widget.onRegister,
                                style: _compactTextButtonStyle(),
                                child: Text(strings.registerNow),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

class _GoogleMark extends StatelessWidget {
  const _GoogleMark();

  @override
  Widget build(BuildContext context) {
    return const SizedBox.square(
      dimension: 20,
      child: Center(
        child: Text(
          'G',
          style: TextStyle(
            color: Color(0xFF4285F4),
            fontSize: 18,
            fontWeight: FontWeight.w700,
          ),
        ),
      ),
    );
  }
}

InputDecoration _inputDecoration(
  BuildContext context, {
  String? hintText,
  IconData? prefixIcon,
  Widget? prefix,
  Widget? suffixIcon,
}) {
  assert(prefixIcon != null || prefix != null);
  final theme = Theme.of(context);
  final colors = theme.colorScheme;
  const border = OutlineInputBorder(
    borderRadius: BorderRadius.all(Radius.circular(12)),
    borderSide: BorderSide.none,
  );
  return InputDecoration(
    hintText: hintText,
    prefixIcon: prefix ?? _FieldPrefixIcon(icon: prefixIcon!),
    prefixIconConstraints: const BoxConstraints(minHeight: 56),
    suffixIcon: suffixIcon,
    filled: true,
    fillColor: colors.surface,
    isDense: true,
    contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 18),
    border: border,
    enabledBorder: border,
    focusedBorder: border,
    disabledBorder: border,
    errorBorder: border,
    focusedErrorBorder: border,
  );
}

ButtonStyle _compactTextButtonStyle() {
  return TextButton.styleFrom(
    minimumSize: Size.zero,
    padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 6),
    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
    visualDensity: VisualDensity.compact,
    splashFactory: NoSplash.splashFactory,
  ).copyWith(
    overlayColor: const WidgetStatePropertyAll(Colors.transparent),
  );
}

ButtonStyle _noOverlayButtonStyle() {
  return const ButtonStyle(
    overlayColor: WidgetStatePropertyAll(Colors.transparent),
    splashFactory: NoSplash.splashFactory,
  );
}

class _CountryPickerDialog extends StatefulWidget {
  const _CountryPickerDialog({
    required this.strings,
    required this.selectedCountry,
  });

  final LoginStrings strings;
  final PhoneCountry selectedCountry;

  @override
  State<_CountryPickerDialog> createState() => _CountryPickerDialogState();
}

class _CountryPickerDialogState extends State<_CountryPickerDialog> {
  final _searchController = TextEditingController();
  String _query = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final normalizedQuery = _query.trim().toLowerCase();
    final countries = supportedPhoneCountries.where((country) {
      if (normalizedQuery.isEmpty) return true;
      final searchable = [
        country.name,
        widget.strings.countryName(country),
        country.isoCode,
        country.dialCode,
      ].join(' ').toLowerCase();
      return searchable.contains(normalizedQuery);
    }).toList(growable: false);
    return Dialog(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420, maxHeight: 540),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 14, 18, 12),
          child: Column(
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      widget.strings.countryPickerTitle,
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                  ),
                  IconButton(
                    tooltip: widget.strings.close,
                    onPressed: () => Navigator.of(context).pop(),
                    icon: const Icon(Icons.close_rounded),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              TextField(
                key: const Key('country-search-field'),
                controller: _searchController,
                autofocus: false,
                onChanged: (value) => setState(() => _query = value),
                decoration: InputDecoration(
                  hintText: widget.strings.countrySearchHint,
                  prefixIcon: const Icon(Icons.search_rounded),
                ),
              ),
              const SizedBox(height: 8),
              Expanded(
                child: countries.isEmpty
                    ? Center(child: Text(widget.strings.countryNoResults))
                    : ListView.builder(
                        key: const Key('country-options-list'),
                        itemCount: countries.length,
                        itemBuilder: (context, index) {
                          final country = countries[index];
                          final selected =
                              country.isoCode == widget.selectedCountry.isoCode;
                          return ListTile(
                            key: Key('country-option-${country.isoCode}'),
                            leading: Text(
                              country.flag,
                              style: const TextStyle(fontSize: 20),
                            ),
                            title: Text(widget.strings.countryName(country)),
                            subtitle: Text(country.isoCode),
                            trailing: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Text(country.dialCode),
                                if (selected) ...[
                                  const SizedBox(width: 8),
                                  const Icon(Icons.check_rounded, size: 18),
                                ],
                              ],
                            ),
                            onTap: () => Navigator.of(context).pop(country),
                          );
                        },
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CountryCodeButton extends StatelessWidget {
  const _CountryCodeButton({
    required this.country,
    required this.enabled,
    required this.onPressed,
    required this.strings,
  });

  final PhoneCountry country;
  final bool enabled;
  final VoidCallback onPressed;
  final LoginStrings strings;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return SizedBox(
      width: 112,
      height: 56,
      child: Semantics(
        label: strings.countryButtonSemantics(country),
        button: true,
        enabled: enabled,
        excludeSemantics: true,
        child: TextButton(
          key: const Key('country-code-button'),
          onPressed: enabled ? onPressed : null,
          style: TextButton.styleFrom(
            padding: EdgeInsets.zero,
            minimumSize: const Size(112, 56),
            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            shape: const RoundedRectangleBorder(),
            splashFactory: NoSplash.splashFactory,
          ).copyWith(
            overlayColor: const WidgetStatePropertyAll(Colors.transparent),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              SizedBox(
                width: 26,
                height: 22,
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(
                    country.flag,
                    style: const TextStyle(fontSize: 19),
                  ),
                ),
              ),
              const SizedBox(width: 5),
              SizedBox(
                width: 38,
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(
                    country.dialCode,
                    key: const Key('selected-country-code'),
                    style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                          color: enabled
                              ? colors.onSurface
                              : colors.onSurface.withValues(alpha: 0.38),
                        ),
                  ),
                ),
              ),
              const SizedBox(width: 2),
              Icon(
                Icons.arrow_drop_down,
                size: 18,
                color: colors.onSurfaceVariant,
              ),
              const SizedBox(width: 8),
              Container(width: 1, height: 20, color: colors.outlineVariant),
            ],
          ),
        ),
      ),
    );
  }
}

class _FieldPrefixIcon extends StatelessWidget {
  const _FieldPrefixIcon({required this.icon});

  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return SizedBox(
      width: 53,
      height: 56,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.end,
        children: [
          Icon(icon, size: 20),
          const SizedBox(width: 12),
          Container(width: 1, height: 20, color: colors.outlineVariant),
        ],
      ),
    );
  }
}

class _LoginModeSelector extends StatelessWidget {
  const _LoginModeSelector({
    required this.mode,
    required this.enabled,
    required this.onChanged,
    required this.strings,
  });

  final LoginMode mode;
  final bool enabled;
  final ValueChanged<LoginMode> onChanged;
  final LoginStrings strings;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Material(
      key: const Key('login-mode-selector'),
      color: colors.surface,
      borderRadius: BorderRadius.circular(12),
      clipBehavior: Clip.antiAlias,
      child: SizedBox(
        height: 48,
        child: Row(
          children: [
            _LoginModeItem(
              label: strings.passwordLogin,
              selected: mode == LoginMode.password,
              enabled: enabled,
              onTap: () => onChanged(LoginMode.password),
            ),
            _LoginModeItem(
              label: strings.codeLogin,
              selected: mode == LoginMode.verificationCode,
              enabled: enabled,
              onTap: () => onChanged(LoginMode.verificationCode),
            ),
          ],
        ),
      ),
    );
  }
}

class _LoginModeItem extends StatelessWidget {
  const _LoginModeItem({
    required this.label,
    required this.selected,
    required this.enabled,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Expanded(
      child: Material(
        color: selected ? colors.primary : Colors.transparent,
        borderRadius: BorderRadius.circular(12),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: enabled ? onTap : null,
          overlayColor: const WidgetStatePropertyAll(Colors.transparent),
          splashFactory: NoSplash.splashFactory,
          child: Center(
            child: Text(
              label,
              style: theme.textTheme.labelLarge?.copyWith(
                color: selected ? colors.onPrimary : colors.onSurfaceVariant,
                fontWeight: FontWeight.w500,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _CompactCheckControl extends StatelessWidget {
  const _CompactCheckControl({
    required this.value,
    required this.label,
    required this.enabled,
    required this.onChanged,
    super.key,
  });

  final bool value;
  final String label;
  final bool enabled;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Semantics(
      checked: value,
      enabled: enabled,
      label: label,
      button: true,
      child: InkWell(
        onTap: enabled ? () => onChanged(!value) : null,
        borderRadius: BorderRadius.circular(6),
        overlayColor: const WidgetStatePropertyAll(Colors.transparent),
        splashFactory: NoSplash.splashFactory,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 5),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              AnimatedContainer(
                duration: const Duration(milliseconds: 120),
                width: 18,
                height: 18,
                decoration: BoxDecoration(
                  color: value ? colors.primary : Colors.transparent,
                  borderRadius: BorderRadius.circular(4),
                  border: value
                      ? null
                      : Border.all(color: colors.outlineVariant, width: 1),
                ),
                alignment: Alignment.center,
                child: value
                    ? Icon(Icons.check, size: 14, color: colors.onPrimary)
                    : null,
              ),
              const SizedBox(width: 7),
              Text(
                label,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: enabled
                      ? colors.onSurfaceVariant
                      : colors.onSurface.withValues(alpha: 0.38),
                  height: 1,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _AgreementRow extends StatelessWidget {
  const _AgreementRow({
    required this.value,
    required this.enabled,
    required this.onChanged,
    required this.strings,
    this.onUserAgreement,
    this.onPrivacyPolicy,
  });

  final bool value;
  final bool enabled;
  final ValueChanged<bool> onChanged;
  final LoginStrings strings;
  final VoidCallback? onUserAgreement;
  final VoidCallback? onPrivacyPolicy;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final textStyle = theme.textTheme.bodySmall?.copyWith(height: 1.35);
    final linkStyle = textStyle?.copyWith(color: colors.primary);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        _CompactCheckControl(
          key: const Key('agreements-checkbox'),
          value: value,
          label: '',
          enabled: enabled,
          onChanged: onChanged,
        ),
        const SizedBox(width: 1),
        Expanded(
          child: Wrap(
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text(strings.agreementPrefix, style: textStyle),
              TextButton(
                key: const Key('user-agreement-button'),
                onPressed: enabled ? onUserAgreement : null,
                style: _compactTextButtonStyle(),
                child: Text(strings.userAgreement, style: linkStyle),
              ),
              Text(strings.agreementJoiner, style: textStyle),
              TextButton(
                key: const Key('privacy-policy-button'),
                onPressed: enabled ? onPrivacyPolicy : null,
                style: _compactTextButtonStyle(),
                child: Text(strings.privacyPolicy, style: linkStyle),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
