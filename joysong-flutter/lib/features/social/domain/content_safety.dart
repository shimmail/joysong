final class ContentSafetyAssessment {
  const ContentSafetyAssessment(this.notices);

  final List<String> notices;
  bool get needsConfirmation => notices.isNotEmpty;
}

final class SocialContentSafety {
  const SocialContentSafety();

  static const publicationNotice = '内容发布后可能被他人查看、评论和举报，请勿填写身份证号、联系方式或认证材料。';
  static const publicUploadNotice = '日记与评价图片属于公开内容；身份证、执业证等认证材料请仅通过身份认证入口提交。';

  static final RegExp _mainlandId = RegExp(r'(?<!\d)\d{17}[\dXx](?!\d)');
  static final RegExp _phone = RegExp(r'(?<!\d)1[3-9]\d{9}(?!\d)');

  ContentSafetyAssessment assess(String content) {
    final notices = <String>[];
    if (_mainlandId.hasMatch(content)) {
      notices.add('内容疑似包含身份证号，请确认已移除敏感身份信息。');
    }
    if (_phone.hasMatch(content)) {
      notices.add('内容疑似包含手机号，公开发布可能带来隐私风险。');
    }
    if (content.contains('保证治愈') || content.contains('绝对安全')) {
      notices.add('请避免使用绝对化医疗效果描述，以真实体验为准。');
    }
    return ContentSafetyAssessment(List.unmodifiable(notices));
  }
}
