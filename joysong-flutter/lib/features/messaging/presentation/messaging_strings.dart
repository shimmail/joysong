final class MessagingStrings {
  const MessagingStrings({required this.isEnglish});

  final bool isEnglish;

  String pick(String chinese, String english) => isEnglish ? english : chinese;

  String notifications(int unreadCount) =>
      isEnglish ? 'Notifications ($unreadCount)' : '通知 ($unreadCount)';
  String get markAllRead => pick('全部已读', 'Mark all read');
  String get retry => pick('重试', 'Try again');
  String get noNotifications => pick('暂无通知', 'No notifications');
  String get unread => pick('未读', 'Unread');
  String get messages => pick('消息', 'Messages');
  String get customerService => pick('平台客服', 'Customer service');
  String get customerServiceReady =>
      pick('有问题？点击开始咨询', 'Need help? Tap to start a chat.');
  String get creatingConversation =>
      pick('正在连接客服…', 'Connecting to customer service…');
  String get startConsultation => pick('开始咨询', 'Start a chat');
  String get directMessages => pick('私信', 'Direct messages');
  String get directMessageConversation => pick('私信会话', 'Direct message');
  String get noMessages => pick('暂无消息', 'No messages');
  String get directMessageTitle => pick('私信', 'Direct message');
  String get loadEarlier => pick('加载更早消息', 'Load earlier messages');
  String get messageHint => pick('输入消息', 'Type a message');
  String get send => pick('发送', 'Send');
  String get onlineService => pick('在线客服', 'Online support');
  String get onlineServiceDescription =>
      pick('与平台客服在线沟通', 'Chat with platform support');
  String get phoneService => pick('电话客服', 'Phone support');
  String get phoneServiceDescription =>
      pick('工作时间内提供电话服务', 'Available during service hours');
  String get helpCenter => pick('帮助中心', 'Help center');
  String get helpCenterDescription =>
      pick('查看常见问题和使用指南', 'Browse FAQs and guides');
  String get serviceWelcome => pick('我们随时为你提供帮助', 'We are here to help');
  String get serviceHours =>
      pick('服务时间：每日 09:00–21:00', 'Service hours: 09:00–21:00 daily');
  String get copy => pick('复制', 'Copy');
  String get copied => pick('已复制', 'Copied');
  String get delete => pick('删除', 'Delete');
  String get deleteMessage => pick('删除这条消息？', 'Delete this message?');
  String get cancel => pick('取消', 'Cancel');
  String get imageMessage => pick('[图片]', '[Image]');
  String get sending => pick('发送中…', 'Sending…');
  String get actionFailed =>
      pick('操作失败，请稍后重试', 'Something went wrong. Try again later.');

  String localizedError(String message) {
    if (isEnglish && message == '操作失败，请稍后重试') {
      return actionFailed;
    }
    return message;
  }
}
