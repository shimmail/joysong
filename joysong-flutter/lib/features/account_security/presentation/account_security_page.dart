import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_deletion_strings.dart';
import 'package:joysong_flutter/features/auth/presentation/phone_country.dart';
import 'package:joysong_flutter/features/account_security/presentation/account_security_controller.dart';

class AccountSecurityPage extends StatefulWidget {
  const AccountSecurityPage({
    required this.controller,
    this.onSessionInvalidated,
    this.onDeletionBlockerAction,
    super.key,
  });

  final AccountSecurityController controller;
  final VoidCallback? onSessionInvalidated;
  final ValueChanged<String>? onDeletionBlockerAction;

  @override
  State<AccountSecurityPage> createState() => _AccountSecurityPageState();
}

class _AccountSecurityPageState extends State<AccountSecurityPage> {
  bool _sessionInvalidationNotified = false;

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_handleControllerChanged);
    widget.controller.load();
  }

  @override
  void didUpdateWidget(covariant AccountSecurityPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_handleControllerChanged);
      widget.controller.addListener(_handleControllerChanged);
      _sessionInvalidationNotified = false;
      widget.controller.load();
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_handleControllerChanged);
    super.dispose();
  }

  void _handleControllerChanged() {
    if (!widget.controller.sessionMustEnd || _sessionInvalidationNotified) {
      return;
    }
    _sessionInvalidationNotified = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) widget.onSessionInvalidated?.call();
    });
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) => Scaffold(
        appBar: AppBar(title: const Text('账号与安全')),
        body: _body(context),
      ),
    );
  }

  Widget _body(BuildContext context) {
    final controller = widget.controller;
    final deletionStrings = AccountDeletionStrings(
      Localizations.localeOf(context),
    );
    if (controller.status == AccountSecurityLoadStatus.loading ||
        controller.status == AccountSecurityLoadStatus.idle) {
      return const Center(child: CircularProgressIndicator());
    }
    if (controller.status == AccountSecurityLoadStatus.failure) {
      return _MessageState(
        message: controller.errorMessage ?? '账号安全信息加载失败',
        onRetry: () => controller.load(force: true),
      );
    }
    if (controller.status == AccountSecurityLoadStatus.deleted) {
      return _MessageState(message: deletionStrings.completed);
    }
    final profile = controller.profile!;
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
      children: [
        if (controller.errorMessage != null)
          _FeedbackBanner(
            message: controller.errorMessage!,
            isError: true,
            onClose: controller.clearFeedback,
          ),
        if (controller.successMessage != null)
          _FeedbackBanner(
            message: controller.successMessage!,
            onClose: controller.clearFeedback,
          ),
        Card(
          child: ListTile(
            leading: const Icon(Icons.phone_android_rounded),
            title: const Text('当前手机号'),
            subtitle: Text(
              profile.maskedPhone,
              key: const Key('masked-phone'),
            ),
            trailing: TextButton(
              key: const Key('phone-change-action'),
              onPressed: controller.isBusy ? null : _showPhoneChange,
              child: Text(profile.hasBoundPhone ? '更换' : '绑定'),
            ),
          ),
        ),
        const SizedBox(height: 16),
        Text('登录凭证', style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 6),
        Card(
          clipBehavior: Clip.antiAlias,
          child: Column(
            children: [
              ListTile(
                key: const Key('password-action'),
                leading: const Icon(Icons.lock_outline_rounded),
                title: Text(profile.hasPassword ? '修改密码' : '设置密码'),
                subtitle: Text(
                  profile.hasPassword
                      ? '修改后所有登录会话将失效'
                      : profile.hasBoundPhone
                          ? '通过绑定手机号验证码设置'
                          : '需要先绑定手机号',
                ),
                enabled: !controller.isBusy &&
                    (profile.hasPassword || profile.hasBoundPhone),
                onTap: profile.hasPassword
                    ? _showChangePassword
                    : _showSetPassword,
              ),
              const Divider(height: 1),
              ListTile(
                key: const Key('reset-password-action'),
                leading: const Icon(Icons.password_rounded),
                title: const Text('忘记原密码'),
                subtitle: Text(
                  profile.hasBoundPhone ? '通过当前绑定手机号重置密码' : '当前账号未绑定手机号，暂不可用',
                ),
                enabled: profile.hasBoundPhone && !controller.isBusy,
                onTap: _showResetPassword,
              ),
              const Divider(height: 1),
              const ListTile(
                key: Key('device-management-disabled'),
                leading: Icon(Icons.devices_outlined),
                title: Text('登录设备'),
                subtitle: Text('服务端尚未开放设备管理接口'),
                enabled: false,
              ),
            ],
          ),
        ),
        const SizedBox(height: 20),
        Text('危险操作', style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 6),
        Card(
          child: ListTile(
            key: const Key('delete-account-action'),
            leading: Icon(
              Icons.no_accounts_outlined,
              color: Theme.of(context).colorScheme.error,
            ),
            title: Text(
              deletionStrings.title,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
            subtitle: Text(deletionStrings.entrySubtitle),
            enabled: !controller.isBusy,
            onTap: _showDeleteAccount,
          ),
        ),
      ],
    );
  }

  Future<void> _showChangePassword() async {
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _ChangePasswordDialog(controller: widget.controller),
    );
  }

  Future<void> _showPhoneChange() async {
    widget.controller.beginPhoneChange();
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _PhoneChangeDialog(controller: widget.controller),
    );
  }

  Future<void> _showSetPassword() async {
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _SmsPasswordDialog(
        controller: widget.controller,
        isReset: false,
      ),
    );
  }

  Future<void> _showResetPassword() async {
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _SmsPasswordDialog(
        controller: widget.controller,
        isReset: true,
      ),
    );
  }

  Future<void> _showDeleteAccount() async {
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _DeleteAccountDialog(
        controller: widget.controller,
        onBlockerAction: widget.onDeletionBlockerAction,
      ),
    );
  }
}

enum _PhoneChangeStep { verifyCurrent, verifyNew }

class _PhoneChangeDialog extends StatefulWidget {
  const _PhoneChangeDialog({required this.controller});

  final AccountSecurityController controller;

  @override
  State<_PhoneChangeDialog> createState() => _PhoneChangeDialogState();
}

class _PhoneChangeDialogState extends State<_PhoneChangeDialog> {
  final _currentCode = TextEditingController();
  final _nationalNumber = TextEditingController();
  final _newCode = TextEditingController();
  PhoneCountry _country = supportedPhoneCountries.first;
  late _PhoneChangeStep _step;
  bool _currentCodeSent = false;
  bool _newCodeSent = false;
  bool _sending = false;
  bool _submitting = false;

  bool get _hasBoundPhone => widget.controller.profile!.hasBoundPhone;

  String get _newPhone =>
      normalizeInternationalPhone(_nationalNumber.text, _country);

  @override
  void initState() {
    super.initState();
    _step = _hasBoundPhone
        ? _PhoneChangeStep.verifyCurrent
        : _PhoneChangeStep.verifyNew;
  }

  @override
  void dispose() {
    _currentCode.dispose();
    _nationalNumber.dispose();
    _newCode.dispose();
    super.dispose();
  }

  Future<void> _sendCurrentCode() async {
    if (_sending || _submitting) return;
    setState(() => _sending = true);
    final success = await widget.controller.sendCurrentPhoneChangeCode();
    if (!mounted) return;
    setState(() {
      _sending = false;
      _currentCodeSent = _currentCodeSent || success;
    });
  }

  Future<void> _verifyCurrentCode() async {
    if (_submitting || _sending) return;
    if (!RegExp(r'^\d{6}$').hasMatch(_currentCode.text)) {
      setState(() {});
      return;
    }
    setState(() => _submitting = true);
    final success = await widget.controller.verifyCurrentPhoneChangeCode(
      _currentCode.text,
    );
    if (!mounted) return;
    setState(() {
      _submitting = false;
      if (success) _step = _PhoneChangeStep.verifyNew;
    });
  }

  Future<void> _sendNewCode() async {
    if (_sending || _submitting) return;
    setState(() => _sending = true);
    final success = await widget.controller.sendNewPhoneChangeCode(_newPhone);
    if (!mounted) return;
    setState(() {
      _sending = false;
      _newCodeSent = success;
    });
  }

  Future<void> _complete() async {
    if (_submitting || _sending || !_newCodeSent) return;
    if (!RegExp(r'^\d{6}$').hasMatch(_newCode.text)) {
      setState(() {});
      return;
    }
    setState(() => _submitting = true);
    final success = await widget.controller.completePhoneChange(
      phone: _newPhone,
      code: _newCode.text,
    );
    if (!mounted) return;
    setState(() => _submitting = false);
    if (success) Navigator.of(context).pop();
  }

  Future<void> _selectCountry() async {
    if (_sending || _submitting) return;
    FocusManager.instance.primaryFocus?.unfocus();
    final selected = await showModalBottomSheet<PhoneCountry>(
      context: context,
      showDragHandle: true,
      constraints: const BoxConstraints(maxWidth: 420),
      builder: (context) => SafeArea(
        child: ListView(
          key: const Key('phone-country-list'),
          children: [
            for (final country in supportedPhoneCountries)
              ListTile(
                title: Text('${country.flag}  ${country.name}'),
                trailing: Text(country.dialCode),
                onTap: () => Navigator.of(context).pop(country),
              ),
          ],
        ),
      ),
    );
    if (!mounted || selected == null) return;
    setState(() {
      _country = selected;
      _newCodeSent = false;
      _newCode.clear();
    });
  }

  @override
  Widget build(BuildContext context) {
    final error = widget.controller.errorMessage;
    return AlertDialog(
      title: Text(_hasBoundPhone ? '更换手机号' : '绑定手机号'),
      content: SingleChildScrollView(
        child: SizedBox(
          width: 360,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (error != null) ...[
                Text(
                  error,
                  key: const Key('phone-change-error'),
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
                const SizedBox(height: 8),
              ],
              if (_step == _PhoneChangeStep.verifyCurrent)
                _buildCurrentPhoneStep()
              else
                _buildNewPhoneStep(),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _sending || _submitting
              ? null
              : () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        if (_step == _PhoneChangeStep.verifyCurrent)
          FilledButton(
            key: const Key('verify-current-phone-submit'),
            onPressed: _currentCodeSent && !_sending && !_submitting
                ? _verifyCurrentCode
                : null,
            child: Text(_submitting ? '验证中…' : '验证当前手机号'),
          )
        else
          FilledButton(
            key: const Key('complete-phone-change-submit'),
            onPressed:
                _newCodeSent && !_sending && !_submitting ? _complete : null,
            child: Text(
              _submitting
                  ? '提交中…'
                  : _hasBoundPhone
                      ? '更换并重新登录'
                      : '绑定并重新登录',
            ),
          ),
      ],
    );
  }

  Widget _buildCurrentPhoneStep() {
    final masked = widget.controller.profile!.maskedPhone;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('先验证当前绑定手机号 $masked'),
        const SizedBox(height: 8),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: TextField(
                key: const Key('current-phone-code-field'),
                controller: _currentCode,
                keyboardType: TextInputType.number,
                inputFormatters: [
                  FilteringTextInputFormatter.digitsOnly,
                  LengthLimitingTextInputFormatter(6),
                ],
                decoration: InputDecoration(
                  labelText: '当前手机号验证码',
                  errorText: _currentCode.text.isNotEmpty &&
                          !RegExp(r'^\d{6}$').hasMatch(_currentCode.text)
                      ? '请输入 6 位验证码'
                      : null,
                ),
                onChanged: (_) => setState(() {}),
              ),
            ),
            const SizedBox(width: 8),
            TextButton(
              key: const Key('send-current-phone-code'),
              onPressed: _sending || _submitting ? null : _sendCurrentCode,
              child: Text(
                _sending
                    ? '发送中…'
                    : _currentCodeSent
                        ? '重新发送'
                        : '发送验证码',
              ),
            ),
          ],
        ),
        if (_currentCodeSent)
          const Text(
            '验证码已发送，请在有效期内完成验证',
            key: Key('current-phone-code-sent'),
          ),
      ],
    );
  }

  Widget _buildNewPhoneStep() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(_hasBoundPhone ? '当前手机号已验证，请输入新手机号' : '请输入需要绑定的手机号'),
        const SizedBox(height: 8),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextButton(
              key: const Key('phone-country-selector'),
              onPressed: _selectCountry,
              child: Text('${_country.flag} ${_country.dialCode} ▾'),
            ),
            const SizedBox(width: 6),
            Expanded(
              child: TextField(
                key: const Key('new-phone-national-field'),
                controller: _nationalNumber,
                keyboardType: TextInputType.phone,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: const InputDecoration(labelText: '手机号'),
                onChanged: (_) => setState(() {
                  _newCodeSent = false;
                  _newCode.clear();
                }),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: TextField(
                key: const Key('new-phone-code-field'),
                controller: _newCode,
                keyboardType: TextInputType.number,
                inputFormatters: [
                  FilteringTextInputFormatter.digitsOnly,
                  LengthLimitingTextInputFormatter(6),
                ],
                decoration: InputDecoration(
                  labelText: '新手机号验证码',
                  errorText: _newCode.text.isNotEmpty &&
                          !RegExp(r'^\d{6}$').hasMatch(_newCode.text)
                      ? '请输入 6 位验证码'
                      : null,
                ),
                onChanged: (_) => setState(() {}),
              ),
            ),
            const SizedBox(width: 8),
            TextButton(
              key: const Key('send-new-phone-code'),
              onPressed: _sending || _submitting ? null : _sendNewCode,
              child: Text(
                _sending
                    ? '发送中…'
                    : _newCodeSent
                        ? '重新发送'
                        : '发送验证码',
              ),
            ),
          ],
        ),
        if (_newCodeSent)
          Text(
            '验证码已发送至 ${maskPhone(_newPhone)}',
            key: const Key('new-phone-code-sent'),
          ),
      ],
    );
  }
}

class _ChangePasswordDialog extends StatefulWidget {
  const _ChangePasswordDialog({required this.controller});

  final AccountSecurityController controller;

  @override
  State<_ChangePasswordDialog> createState() => _ChangePasswordDialogState();
}

class _ChangePasswordDialogState extends State<_ChangePasswordDialog> {
  final _formKey = GlobalKey<FormState>();
  final _oldPassword = TextEditingController();
  final _newPassword = TextEditingController();
  final _confirmation = TextEditingController();
  bool _submitting = false;

  @override
  void dispose() {
    _oldPassword.dispose();
    _newPassword.dispose();
    _confirmation.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_submitting || !_formKey.currentState!.validate()) return;
    setState(() => _submitting = true);
    final success = await widget.controller.changePassword(
      oldPassword: _oldPassword.text,
      newPassword: _newPassword.text,
    );
    if (!mounted) return;
    setState(() => _submitting = false);
    if (success) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('修改密码'),
      content: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextFormField(
              key: const Key('old-password-field'),
              controller: _oldPassword,
              obscureText: true,
              autofillHints: const [AutofillHints.password],
              decoration: const InputDecoration(labelText: '原密码'),
              validator: (value) =>
                  value == null || value.isEmpty ? '请输入原密码' : null,
            ),
            TextFormField(
              key: const Key('new-password-field'),
              controller: _newPassword,
              obscureText: true,
              autofillHints: const [AutofillHints.newPassword],
              decoration: const InputDecoration(labelText: '新密码（8-128 位）'),
              validator: _passwordValidator,
            ),
            TextFormField(
              controller: _confirmation,
              obscureText: true,
              autofillHints: const [AutofillHints.newPassword],
              decoration: const InputDecoration(labelText: '确认新密码'),
              validator: (value) =>
                  value != _newPassword.text ? '两次输入的密码不一致' : null,
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _submitting ? null : () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          key: const Key('change-password-submit'),
          onPressed: _submitting ? null : _submit,
          child: Text(_submitting ? '提交中…' : '确认修改'),
        ),
      ],
    );
  }
}

class _SmsPasswordDialog extends StatefulWidget {
  const _SmsPasswordDialog({
    required this.controller,
    required this.isReset,
  });

  final AccountSecurityController controller;
  final bool isReset;

  @override
  State<_SmsPasswordDialog> createState() => _SmsPasswordDialogState();
}

class _SmsPasswordDialogState extends State<_SmsPasswordDialog> {
  final _formKey = GlobalKey<FormState>();
  final _code = TextEditingController();
  final _password = TextEditingController();
  final _confirmation = TextEditingController();
  bool _submitting = false;
  bool _sending = false;

  @override
  void dispose() {
    _code.dispose();
    _password.dispose();
    _confirmation.dispose();
    super.dispose();
  }

  Future<void> _sendCode() async {
    if (_sending || _submitting) return;
    setState(() => _sending = true);
    await widget.controller.sendPasswordCode();
    if (mounted) setState(() => _sending = false);
  }

  Future<void> _submit() async {
    if (_submitting || !_formKey.currentState!.validate()) return;
    setState(() => _submitting = true);
    final success = widget.isReset
        ? await widget.controller.resetPassword(
            code: _code.text,
            newPassword: _password.text,
          )
        : await widget.controller.setPassword(
            code: _code.text,
            newPassword: _password.text,
          );
    if (!mounted) return;
    setState(() => _submitting = false);
    if (success) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.isReset ? '短信重置密码' : '设置密码'),
      content: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('验证码将发送至 ${widget.controller.profile!.maskedPhone}'),
            const SizedBox(height: 8),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: TextFormField(
                    key: const Key('password-code-field'),
                    controller: _code,
                    keyboardType: TextInputType.number,
                    inputFormatters: [
                      FilteringTextInputFormatter.digitsOnly,
                      LengthLimitingTextInputFormatter(6),
                    ],
                    decoration: const InputDecoration(labelText: '6 位验证码'),
                    validator: (value) =>
                        RegExp(r'^\d{6}$').hasMatch(value ?? '')
                            ? null
                            : '请输入 6 位验证码',
                  ),
                ),
                const SizedBox(width: 8),
                TextButton(
                  key: const Key('send-password-code'),
                  onPressed: _sending || _submitting ? null : _sendCode,
                  child: Text(_sending ? '发送中…' : '发送验证码'),
                ),
              ],
            ),
            TextFormField(
              key: const Key('sms-new-password-field'),
              controller: _password,
              obscureText: true,
              autofillHints: const [AutofillHints.newPassword],
              decoration: const InputDecoration(labelText: '新密码（8-128 位）'),
              validator: _passwordValidator,
            ),
            TextFormField(
              controller: _confirmation,
              obscureText: true,
              autofillHints: const [AutofillHints.newPassword],
              decoration: const InputDecoration(labelText: '确认新密码'),
              validator: (value) =>
                  value != _password.text ? '两次输入的密码不一致' : null,
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _submitting ? null : () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          key: const Key('sms-password-submit'),
          onPressed: _submitting ? null : _submit,
          child: Text(_submitting ? '提交中…' : '确认'),
        ),
      ],
    );
  }
}

class _DeleteAccountDialog extends StatefulWidget {
  const _DeleteAccountDialog({
    required this.controller,
    this.onBlockerAction,
  });

  final AccountSecurityController controller;
  final ValueChanged<String>? onBlockerAction;

  @override
  State<_DeleteAccountDialog> createState() => _DeleteAccountDialogState();
}

class _DeleteAccountDialogState extends State<_DeleteAccountDialog> {
  final _confirmation = TextEditingController();
  final _smsCode = TextEditingController();
  bool _smsSent = false;

  bool get _confirmed => _confirmation.text == 'DELETE';

  @override
  void initState() {
    super.initState();
    unawaited(widget.controller.beginAccountDeletion());
  }

  @override
  void dispose() {
    _confirmation.dispose();
    _smsCode.dispose();
    super.dispose();
  }

  void _close() {
    widget.controller.resetAccountDeletionFlow();
    Navigator.of(context).pop();
  }

  Future<void> _sendSmsCode() async {
    final sent = await widget.controller.sendAccountDeletionSmsCode();
    if (mounted && sent) setState(() => _smsSent = true);
  }

  Future<void> _verifySmsCode() async {
    await widget.controller.verifyAccountDeletionSmsCode(_smsCode.text);
  }

  Future<void> _confirm() async {
    if (!_confirmed) return;
    await widget.controller.confirmAccountDeletion(
      confirmation: _confirmation.text,
      userId: widget.controller.profile?.id ?? '',
    );
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        final strings = AccountDeletionStrings(Localizations.localeOf(context));
        final stage = widget.controller.deletionStage;
        return AlertDialog(
          title: Text(strings.title),
          content: SingleChildScrollView(
            child: SizedBox(
              width: 400,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(strings.warning),
                  const SizedBox(height: 16),
                  if (widget.controller.deletionErrorCode != null) ...[
                    Text(
                      strings.error(widget.controller.deletionErrorCode),
                      key: const Key('delete-account-error'),
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                    const SizedBox(height: 12),
                  ],
                  _content(strings, stage),
                ],
              ),
            ),
          ),
          actions: _actions(strings, stage),
        );
      },
    );
  }

  Widget _content(
    AccountDeletionStrings strings,
    AccountDeletionStage stage,
  ) {
    return switch (stage) {
      AccountDeletionStage.preflighting => Row(
          key: const Key('delete-account-preflighting'),
          children: [
            const SizedBox.square(
              dimension: 22,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            const SizedBox(width: 12),
            Expanded(child: Text(strings.checking)),
          ],
        ),
      AccountDeletionStage.blocked => _blockers(strings),
      AccountDeletionStage.awaitingStepUp => _stepUp(strings),
      AccountDeletionStage.awaitingConfirmation => Column(
          key: const Key('delete-account-final-confirmation'),
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(strings.irreversible),
            const SizedBox(height: 8),
            TextField(
              key: const Key('delete-account-confirmation'),
              controller: _confirmation,
              autocorrect: false,
              enableSuggestions: false,
              textCapitalization: TextCapitalization.characters,
              onChanged: (_) => setState(() {}),
              decoration: InputDecoration(labelText: strings.confirmationLabel),
            ),
          ],
        ),
      AccountDeletionStage.confirming => Row(
          key: const Key('delete-account-confirming'),
          children: [
            const SizedBox.square(
              dimension: 22,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            const SizedBox(width: 12),
            Text(strings.confirming),
          ],
        ),
      AccountDeletionStage.completed => Text(
          strings.completed,
          key: const Key('delete-account-completed'),
        ),
      AccountDeletionStage.idle => Text(strings.checking),
    };
  }

  Widget _blockers(AccountDeletionStrings strings) {
    final blockers = widget.controller.deletionPreflight?.blockers ?? const [];
    return Column(
      key: const Key('delete-account-blockers'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(strings.blockedTitle),
        const SizedBox(height: 8),
        for (final blocker in blockers)
          _blockerTile(strings, blocker),
      ],
    );
  }

  Widget _blockerTile(
    AccountDeletionStrings strings,
    AccountDeletionBlocker blocker,
  ) {
    final action = blocker.action.trim().toUpperCase();
    final canHandle = widget.onBlockerAction != null &&
        isSupportedAccountDeletionIdentityAction(action);
    return ListTile(
      contentPadding: EdgeInsets.zero,
      title: Text(strings.blockerTitle(blocker.type)),
      subtitle: Text(strings.blockerCount(blocker)),
      trailing: !canHandle
          ? null
          : TextButton(
              key: Key('delete-account-blocker-action-$action'),
              onPressed: () {
                widget.onBlockerAction!(action);
                _close();
              },
              child: Text(strings.action),
            ),
    );
  }

  Widget _stepUp(AccountDeletionStrings strings) {
    final preflight = widget.controller.deletionPreflight!;
    if (preflight.stepUpMethod == AccountDeletionStepUpMethod.google) {
      return Column(
        key: const Key('delete-account-google-step-up'),
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (preflight.maskedCredential.isNotEmpty)
            Text('${strings.verificationTarget}${preflight.maskedCredential}'),
          const SizedBox(height: 12),
          FilledButton.tonalIcon(
            key: const Key('delete-account-google-verify'),
            onPressed: widget.controller.isBusy
                ? null
                : widget.controller.verifyAccountDeletionWithGoogle,
            icon: const Icon(Icons.verified_user_outlined),
            label: Text(strings.googleVerify),
          ),
        ],
      );
    }
    return Column(
      key: const Key('delete-account-sms-step-up'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (preflight.maskedCredential.isNotEmpty)
          Text('${strings.verificationTarget}${preflight.maskedCredential}'),
        const SizedBox(height: 8),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: TextField(
                key: const Key('delete-account-sms-code'),
                controller: _smsCode,
                keyboardType: TextInputType.number,
                inputFormatters: [
                  FilteringTextInputFormatter.digitsOnly,
                  LengthLimitingTextInputFormatter(6),
                ],
                decoration: InputDecoration(labelText: strings.smsCodeLabel),
              ),
            ),
            const SizedBox(width: 8),
            TextButton(
              key: const Key('delete-account-send-sms'),
              onPressed: widget.controller.isBusy ? null : _sendSmsCode,
              child: Text(_smsSent ? strings.resendCode : strings.sendCode),
            ),
          ],
        ),
        if (_smsSent)
          Text(
            strings.codeSent,
            key: const Key('delete-account-sms-sent'),
          ),
      ],
    );
  }

  List<Widget> _actions(
    AccountDeletionStrings strings,
    AccountDeletionStage stage,
  ) {
    if (stage == AccountDeletionStage.preflighting ||
        stage == AccountDeletionStage.confirming) {
      return const [];
    }
    if (stage == AccountDeletionStage.completed) {
      return [TextButton(onPressed: _close, child: Text(strings.close))];
    }
    if (stage == AccountDeletionStage.blocked ||
        stage == AccountDeletionStage.idle) {
      return [
        TextButton(onPressed: _close, child: Text(strings.close)),
        FilledButton.tonal(
          key: const Key('delete-account-retry-preflight'),
          onPressed: widget.controller.isBusy
              ? null
              : widget.controller.beginAccountDeletion,
          child: Text(strings.retry),
        ),
      ];
    }
    if (stage == AccountDeletionStage.awaitingStepUp) {
      final method = widget.controller.deletionPreflight!.stepUpMethod;
      return [
        TextButton(
          onPressed: widget.controller.isBusy ? null : _close,
          child: Text(strings.cancel),
        ),
        if (method == AccountDeletionStepUpMethod.sms)
          FilledButton(
            key: const Key('delete-account-verify-sms'),
            onPressed: widget.controller.isBusy ? null : _verifySmsCode,
            child: Text(strings.verify),
          ),
      ];
    }
    return [
      TextButton(
        onPressed: widget.controller.isBusy ? null : _close,
        child: Text(strings.cancel),
      ),
      FilledButton(
        key: const Key('delete-account-submit'),
        onPressed: _confirmed && !widget.controller.isBusy ? _confirm : null,
        style: FilledButton.styleFrom(
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
        child: Text(strings.confirm),
      ),
    ];
  }
}

class _FeedbackBanner extends StatelessWidget {
  const _FeedbackBanner({
    required this.message,
    required this.onClose,
    this.isError = false,
  });

  final String message;
  final VoidCallback onClose;
  final bool isError;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return MaterialBanner(
      backgroundColor:
          isError ? colors.errorContainer : colors.primaryContainer,
      content: Text(message),
      actions: [TextButton(onPressed: onClose, child: const Text('关闭'))],
    );
  }
}

class _MessageState extends StatelessWidget {
  const _MessageState({required this.message, this.onRetry});

  final String message;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(message),
          if (onRetry != null) ...[
            const SizedBox(height: 10),
            FilledButton.tonal(onPressed: onRetry, child: const Text('重试')),
          ],
        ],
      ),
    );
  }
}

String? _passwordValidator(String? value) {
  final password = value ?? '';
  if (password.length < 8 || password.length > 128) {
    return '密码长度应为 8-128 位';
  }
  return null;
}
