import 'package:flutter/widgets.dart';
import 'package:joysong_flutter/features/account_security/domain/account_security_models.dart';

final class AccountDeletionStrings {
  AccountDeletionStrings(Locale locale)
      : _english = locale.languageCode.toLowerCase() == 'en';

  final bool _english;

  String pick(String chinese, String english) => _english ? english : chinese;

  String get title => pick('注销账号', 'Delete account');
  String get entrySubtitle => pick(
        '永久注销，需完成二次验证',
        'Permanently delete after identity verification',
      );
  String get warning => pick(
        '注销不可撤销。可删除的资料和内容将被清理，之后使用相同手机号或邮箱会创建全新账号。',
        'Deletion cannot be undone. Deletable profile data and content will be removed. Signing up again creates a new account.',
      );
  String get checking => pick('正在检查账号状态…', 'Checking account status…');
  String get blockedTitle => pick('暂时无法注销', 'Deletion is currently blocked');
  String get retry => pick('重试', 'Retry');
  String get cancel => pick('取消', 'Cancel');
  String get close => pick('关闭', 'Close');
  String get sendCode => pick('发送验证码', 'Send code');
  String get resendCode => pick('重新发送', 'Resend code');
  String get codeSent => pick('验证码已发送', 'Verification code sent');
  String get smsCodeLabel => pick('6 位短信验证码', '6-digit SMS code');
  String get verify => pick('验证', 'Verify');
  String get googleVerify => pick('使用 Google 重新验证', 'Verify again with Google');
  String get verificationTarget => pick('验证凭证：', 'Verification target: ');
  String get irreversible => pick(
        '最后确认：请输入 DELETE。提交后无法恢复。',
        'Final confirmation: enter DELETE. This cannot be undone.',
      );
  String get confirmationLabel => 'DELETE';
  String get confirm => pick('永久注销账号', 'Permanently delete account');
  String get confirming => pick('正在注销…', 'Deleting…');
  String get completed => pick('账号已注销', 'Account deleted');
  String get action => pick('前往处理', 'Resolve');

  String blockerTitle(String type) => switch (type.toUpperCase()) {
        'IDENTITY_APPLICATION' =>
          pick('身份认证申请', 'Identity verification application'),
        'PROFESSIONAL_RELATIONSHIP' =>
          pick('职业关系', 'Professional relationship'),
        'INSTITUTION_RELATIONSHIP' =>
          pick('机构关系', 'Institution relationship'),
        'ADMIN_ACCOUNT' => pick('管理员账号', 'Administrator account'),
        _ => pick('待处理事项', 'Outstanding item'),
      };

  String blockerCount(AccountDeletionBlocker blocker) => _english
      ? '${blocker.count} item${blocker.count == 1 ? '' : 's'}'
      : '${blocker.count} 项';

  String error(String? code) => switch (code) {
        AccountDeletionErrorCode.invalidConfirmation =>
          pick('请输入 DELETE 后再确认', 'Enter DELETE to confirm.'),
        AccountDeletionErrorCode.invalidSmsCode =>
          pick('请输入 6 位数字验证码', 'Enter the 6-digit code.'),
        AccountDeletionErrorCode.googleReauthenticationCancelled => pick(
            '已取消 Google 验证，账号未发生变化',
            'Google verification was cancelled. Your account was not changed.',
          ),
        AccountDeletionErrorCode.verificationFailed => pick(
            '验证失败，请核对凭证后重试',
            'Verification failed. Check your credentials and try again.',
          ),
        AccountDeletionErrorCode.blocked => pick(
            '账号仍有待处理事项，请处理后重新预检',
            'Resolve outstanding items and run the check again.',
          ),
        AccountDeletionErrorCode.authorizationExpired => pick(
            '注销授权已失效，请重新验证',
            'The deletion authorization expired. Verify again.',
          ),
        AccountDeletionErrorCode.rateLimited => pick(
            '请求过于频繁，请稍后再发送验证码',
            'Too many requests. Wait before requesting another code.',
          ),
        AccountDeletionErrorCode.smsDeliveryUnavailable => pick(
            '短信服务暂不可用，请稍后重试',
            'SMS delivery is temporarily unavailable. Try again later.',
          ),
        AccountDeletionErrorCode.disabled => pick(
            '当前环境暂未开放账号注销',
            'Account deletion is not available in this environment.',
          ),
        AccountDeletionErrorCode.blockerUnavailable => pick(
            '暂时无法完成注销检查，请稍后重试',
            'Deletion checks are temporarily unavailable. Try again later.',
          ),
        AccountDeletionErrorCode.idempotencyConflict => pick(
            '注销请求信息不一致，请重新开始',
            'The deletion request conflicts with an earlier attempt. Start again.',
          ),
        AccountDeletionErrorCode.requestFailed => pick(
            '请求失败，账号未发生变化，请稍后重试',
            'The request failed and your account was not changed. Try again later.',
          ),
        _ => pick(
            '无法确认注销结果。已保持退出状态，请使用同一请求重试',
            'The deletion result is unknown. You remain signed out; retry the same request.',
          ),
      };
}
