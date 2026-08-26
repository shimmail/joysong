import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';
import 'package:joysong_flutter/core/transient_message.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/domain/social_repository.dart';
import 'package:share_plus/share_plus.dart';

Future<void> shareDiary(
  BuildContext context,
  Diary diary, {
  required SocialRepository repository,
}) async {
  final title = diary.title.trim();
  final author = diary.authorName.trim();
  final content = diary.content.trim();
  final summary =
      content.length > 300 ? '${content.substring(0, 300)}…' : content;
  final tags = diary.tags
      .map((tag) => tag.trim())
      .where((tag) => tag.isNotEmpty)
      .map((tag) => '#$tag')
      .join(' ');
  final lines = <String>[
    context.localized('分享一篇医美日记', 'Sharing a medical aesthetics diary'),
    if (title.isNotEmpty) title,
    if (author.isNotEmpty) context.localized('作者：$author', 'By $author'),
    if (summary.isNotEmpty) summary,
    if (tags.isNotEmpty) tags,
    context.localized('来自 Joysong', 'Shared from Joysong'),
  ];
  final subject = title.isEmpty
      ? context.localized('医美日记', 'Medical aesthetics diary')
      : title;

  final renderObject = context.findRenderObject();
  final origin = renderObject is RenderBox && renderObject.hasSize
      ? renderObject.localToGlobal(Offset.zero) & renderObject.size
      : null;

  try {
    final shareUrl = await repository.createDiaryShareUrl(diary.id);
    await Share.share(
      [...lines, shareUrl].join('\n\n'),
      subject: subject,
      sharePositionOrigin: origin,
    );
  } catch (_) {
    if (!context.mounted) return;
    showTransientMessage(
      context,
      context.localized(
        '暂时无法打开分享面板，请稍后重试',
        'Unable to open the share sheet. Please try again.',
      ),
    );
  }
}
