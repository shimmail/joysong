import 'package:joysong_flutter/core/theme/app_theme.dart';
import 'package:joysong_flutter/features/settings/domain/settings_preferences.dart';

final class SettingsStrings {
  const SettingsStrings.chinese() : isEnglish = false;

  const SettingsStrings.english() : isEnglish = true;

  final bool isEnglish;

  String pick(String chinese, String english) => isEnglish ? english : chinese;

  String get restoreFailed => pick('设置读取失败，已保留默认设置',
      'Could not load settings. Default settings are being used.');
  String get appearanceSaveFailed =>
      pick('外观模式保存失败，请稍后重试', 'Could not save appearance. Try again later.');
  String get notificationSaveFailed => pick('通知偏好保存失败，请检查网络后重试',
      'Could not save notification preferences. Check your connection and try again.');
  String get themeSaveFailed =>
      pick('主题色保存失败，请稍后重试', 'Could not save the theme color. Try again later.');
  String get themeResetFailed => pick('默认主题恢复失败，请稍后重试',
      'Could not restore the default theme. Try again later.');
  String get cacheDialogTitle => pick('清理临时缓存', 'Clear temporary cache');
  String get cacheDialogBody => pick('将清理可重新下载的图片等临时数据，不会删除账号、登录状态和个人设置。',
      'This removes temporary data such as downloadable images. Your account, sign-in state, and personal settings will not be deleted.');
  String get cancel => pick('取消', 'Cancel');
  String get confirmClear => pick('确认清理', 'Clear');
  String get cacheCleared => pick('临时缓存已清理', 'Temporary cache cleared.');
  String get cacheClearFailed =>
      pick('缓存清理失败，请稍后重试', 'Could not clear the cache. Try again later.');
  String get settings => pick('设置', 'Settings');
  String get accountSection => pick('账号', 'Account');
  String get accountSecurity => pick('账号与安全', 'Account & security');
  String get accountSecuritySubtitle =>
      pick('密码、手机号与登录设备', 'Password, phone number, and signed-in devices');
  String get accountSecurityUnavailable =>
      pick('账号与安全页面暂不可用', 'Account & security is currently unavailable.');
  String get privacySettings => pick('隐私设置', 'Privacy settings');
  String get privacySettingsSubtitle =>
      pick('管理个人信息与授权', 'Manage personal information and permissions');
  String get privacySettingsUnavailable =>
      pick('隐私设置页面暂不可用', 'Privacy settings are currently unavailable.');
  String get appearanceSection => pick('外观', 'Appearance');
  String get displayMode => pick('显示模式', 'Display mode');
  String get displayModeSubtitle => pick('可跟随手机的浅色或深色设置',
      'Use your phone’s light or dark appearance automatically');
  String get themeColor => pick('主题色', 'Theme color');
  String get themeColorSubtitle => pick('选择一个舒缓的界面颜色，设置会安全保存在本机。',
      'Choose a calm interface color. Your selection is stored securely on this device.');
  String get restoreDefaultTheme =>
      pick('恢复默认主题色', 'Restore default theme color');
  String get notificationsSection => pick('消息通知', 'Notifications');
  String get notificationFootnote => pick('这里保存应用内通知偏好；手机系统通知权限需在系统设置中管理。',
      'These preferences apply in the app. Manage system notification permission in your phone settings.');
  String get appSection => pick('应用', 'App');
  String get generalSection => pick('常用设置', 'General');
  String get appPreferences => pick('应用偏好', 'App preferences');
  String get appPreferencesSubtitle =>
      pick('通知、显示模式与主题色', 'Notifications, appearance, and theme color');
  String get language => '语言 / Language';
  String get languageDialogTitle => pick('选择语言', 'Choose language');
  String get languageUnavailable =>
      pick('语言设置暂不可用', 'Language settings are currently unavailable.');
  String get languageSaveFailed =>
      pick('语言保存失败，请稍后重试', 'Could not save the language. Try again later.');
  String get chinese => '中文';
  String get english => 'English';
  String get clearCache => pick('清理缓存', 'Clear cache');
  String get calculating => pick('正在计算…', 'Calculating…');
  String get privacyPolicy => pick('隐私政策', 'Privacy Policy');
  String get privacyPolicyUnavailable =>
      pick('隐私政策内容暂不可用', 'The Privacy Policy is currently unavailable.');
  String get terms => pick('用户协议', 'Terms of Service');
  String get termsUnavailable =>
      pick('用户协议内容暂不可用', 'The Terms of Service are currently unavailable.');
  String get about => pick('关于娇颜颂', 'About Joysong');
  String get aboutUnavailable =>
      pick('关于页面暂不可用', 'About Joysong is currently unavailable.');
  String get sizeUnavailable => pick('大小暂不可用', 'Size unavailable');
  String get receiveNotifications => pick('接收消息通知', 'Receive notifications');
  String get receiveNotificationsSubtitle =>
      pick('关闭后不再接收各类应用通知', 'Turn off to stop all app notifications');
  String get orderNotifications => pick('订单与预约', 'Orders & appointments');
  String get orderNotificationsSubtitle =>
      pick('支付、预约和退款进度', 'Payment, appointment, and refund updates');
  String get socialNotifications => pick('互动消息', 'Social activity');
  String get socialNotificationsSubtitle =>
      pick('评论、关注和私信提醒', 'Comments, follows, and direct messages');
  String get serviceNotifications => pick('服务通知', 'Service messages');
  String get serviceNotificationsSubtitle =>
      pick('平台客服和安全提醒', 'Customer service and security alerts');
  String get productNotifications => pick('产品动态', 'Product news');
  String get productNotificationsSubtitle =>
      pick('功能更新与活动信息', 'Feature updates and campaign information');
  String get themePreview => pick('主题预览', 'Theme preview');
  String get darkPreview => pick('深色界面预览', 'Dark appearance preview');
  String get lightPreview => pick('浅色界面预览', 'Light appearance preview');
  String get previewButton => pick('按钮', 'Button');

  String appearanceModeLabel(AppAppearanceMode mode) => switch (mode) {
        AppAppearanceMode.system => pick('跟随系统', 'System'),
        AppAppearanceMode.light => pick('浅色', 'Light'),
        AppAppearanceMode.dark => pick('深色', 'Dark'),
      };

  String themePresetLabel(ThemePreset preset) => switch (preset) {
        ThemePreset.neutralGray => pick('中性灰', 'Neutral gray'),
        ThemePreset.softRose => pick('柔雾粉', 'Soft rose'),
        ThemePreset.peach => pick('暖杏', 'Warm peach'),
        ThemePreset.sage => pick('薄荷绿', 'Mint green'),
        ThemePreset.mistBlue => pick('雾霾蓝', 'Mist blue'),
        ThemePreset.lavender => pick('浅薰衣草', 'Lavender'),
      };

  String themeColorSemantics(ThemePreset preset) {
    final label = themePresetLabel(preset);
    return isEnglish ? '$label theme color' : '$label主题色';
  }
}
