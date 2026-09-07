import 'package:joysong_flutter/features/auth/presentation/phone_country.dart';

final class LoginStrings {
  const LoginStrings._(this.isEnglish);

  const LoginStrings.chinese() : this._(false);
  const LoginStrings.english() : this._(true);

  final bool isEnglish;

  String get brandTitle => isEnglish ? 'JOYINGSONG' : '娇颜颂';
  String get passwordLogin => isEnglish ? 'Password sign-in' : '密码登录';
  String get codeLogin => isEnglish ? 'Code sign-in' : '验证码登录';
  String get passwordSubtitle =>
      isEnglish ? 'Sign in with your phone and password' : '使用手机号和密码登录';
  String get codeSubtitle => isEnglish
      ? 'A new account will be created after verification if needed'
      : '未注册手机号验证后将自动创建账号';
  String get phoneHint => isEnglish ? 'Phone number' : '请输入手机号';
  String get phoneRequired => isEnglish ? 'Enter your phone number' : '请输入手机号';
  String get phoneInvalid =>
      isEnglish ? 'Enter a valid phone number' : '请输入有效手机号';
  String get passwordHint => isEnglish ? 'Password' : '请输入密码';
  String get passwordRequired => isEnglish ? 'Enter your password' : '请输入密码';
  String get passwordTooShort =>
      isEnglish ? 'Password must be at least 8 characters' : '密码至少需要 8 位';
  String get showPassword => isEnglish ? 'Show password' : '显示密码';
  String get hidePassword => isEnglish ? 'Hide password' : '隐藏密码';
  String get rememberPassword => isEnglish ? 'Remember' : '记住密码';
  String get autoLogin => isEnglish ? 'Auto sign-in' : '自动登录';
  String get forgotPassword => isEnglish ? 'Forgot password?' : '忘记密码？';
  String get codeHint => isEnglish ? 'Verification code' : '请输入验证码';
  String get codeRequired =>
      isEnglish ? 'Enter the verification code' : '请输入验证码';
  String get codeInvalid =>
      isEnglish ? 'Enter a 6-digit verification code' : '请输入 6 位数字验证码';
  String get sendCode => isEnglish ? 'Get code' : '获取验证码';
  String get sendingCode => isEnglish ? 'Sending...' : '发送中...';
  String retryCode(int seconds) =>
      isEnglish ? 'Retry in ${seconds}s' : '$seconds 秒后重试';
  String get acceptAgreementError => isEnglish
      ? 'Please read and accept the User Agreement and Privacy Policy'
      : '请先阅读并同意用户协议和隐私政策';
  String get agreementPrefix =>
      isEnglish ? 'I have read and accept the ' : '我已阅读并同意';
  String get userAgreement => isEnglish ? 'User Agreement' : '《用户协议》';
  String get agreementJoiner => isEnglish ? ' and ' : '和';
  String get privacyPolicy => isEnglish ? 'Privacy Policy' : '《隐私政策》';
  String get login => isEnglish ? 'Sign in' : '登录';
  String get loggingIn => isEnglish ? 'Signing in...' : '登录中...';
  String get or => isEnglish ? 'or' : '或';
  String get googleLogin =>
      isEnglish ? 'Continue with Google' : '使用 Google 登录';
  String get googleLoginCancelled =>
      isEnglish ? 'Google sign-in was cancelled' : '已取消 Google 登录';
  String get noAccount => isEnglish ? "Don't have an account?" : '还没有账号？';
  String get registerNow => isEnglish ? 'Register' : '立即注册';
  String get countryPickerTitle =>
      isEnglish ? 'Select country or region' : '选择国家或地区';
  String get countrySearchHint =>
      isEnglish ? 'Search country or calling code' : '搜索国家或区号';
  String get countryNoResults =>
      isEnglish ? 'No matching country or region' : '没有匹配的国家或地区';
  String get close => isEnglish ? 'Close' : '关闭';
  String countryButtonSemantics(PhoneCountry country) => isEnglish
      ? 'Select country or region, current ${countryName(country)} ${country.dialCode}'
      : '选择国家或地区，当前${country.name} ${country.dialCode}';
  String get switchLanguage => isEnglish ? '切换为中文' : 'Switch to English';
  String get languageSwitchFailed =>
      isEnglish ? 'Unable to save the language preference' : '语言偏好保存失败';

  String countryName(PhoneCountry country) {
    if (!isEnglish) return country.name;
    return _englishCountryNames[country.isoCode] ?? country.isoCode;
  }
}

const _englishCountryNames = <String, String>{
  'CN': 'Mainland China',
  'HK': 'Hong Kong',
  'MO': 'Macao',
  'TW': 'Taiwan',
  'US': 'United States',
  'CA': 'Canada',
  'JP': 'Japan',
  'KR': 'South Korea',
  'SG': 'Singapore',
  'MY': 'Malaysia',
  'TH': 'Thailand',
  'VN': 'Vietnam',
  'PH': 'Philippines',
  'ID': 'Indonesia',
  'IN': 'India',
  'AE': 'United Arab Emirates',
  'GB': 'United Kingdom',
  'FR': 'France',
  'DE': 'Germany',
  'IT': 'Italy',
  'ES': 'Spain',
  'RU': 'Russia',
  'AU': 'Australia',
  'NZ': 'New Zealand',
};
