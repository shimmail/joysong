import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/transient_message.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';
import 'package:joysong_flutter/features/social/presentation/social_controller.dart';

Future<bool> showReportFlow({
  required BuildContext context,
  required SocialController controller,
  required ReportTargetType type,
  required String targetId,
}) async {
  final english =
      Localizations.localeOf(context).languageCode.toLowerCase() == 'en';
  await controller.loadReportedStatus(type, targetId);
  if (!context.mounted) return false;
  final submitted = await showDialog<bool>(
    context: context,
    builder: (_) => _ReportDialog(
      controller: controller,
      type: type,
      targetId: targetId,
      english: english,
      alreadyReported: controller.hasReported(type, targetId),
    ),
  );
  if (!context.mounted || submitted != true) return false;
  showTransientMessage(
    context,
    english
        ? 'Report submitted. Thank you for your feedback.'
        : '举报已提交，感谢您的反馈',
  );
  return true;
}

/// Shared report entry for every backend-supported report target.
///
/// The backend accepts diary, review, comment and user targets. Keeping the
/// reasons and validation here prevents individual pages from drifting apart.
final class ReportActionButton extends StatefulWidget {
  const ReportActionButton({
    required this.controller,
    required this.type,
    required this.targetId,
    this.iconSize = 24,
    this.compact = false,
    super.key,
  });

  final SocialController controller;
  final ReportTargetType type;
  final String targetId;
  final double iconSize;
  final bool compact;

  @override
  State<ReportActionButton> createState() => _ReportActionButtonState();
}

final class _ReportActionButtonState extends State<ReportActionButton> {
  bool _opening = false;

  bool get _english =>
      Localizations.localeOf(context).languageCode.toLowerCase() == 'en';

  Future<void> _open() async {
    if (_opening || widget.targetId.trim().isEmpty) return;
    setState(() => _opening = true);

    await showReportFlow(
      context: context,
      controller: widget.controller,
      type: widget.type,
      targetId: widget.targetId,
    );
    if (!mounted) return;
    setState(() => _opening = false);
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: widget.controller,
        builder: (context, _) {
          final reported = widget.controller.hasReported(
            widget.type,
            widget.targetId,
          );
          return IconButton(
            tooltip: reported
                ? (_english ? 'Reported' : '已举报')
                : (_english ? 'Report' : '举报'),
            onPressed:
                _opening || widget.targetId.trim().isEmpty ? null : _open,
            padding: widget.compact ? EdgeInsets.zero : null,
            constraints: widget.compact
                ? const BoxConstraints.tightFor(width: 34, height: 34)
                : null,
            visualDensity: widget.compact ? VisualDensity.compact : null,
            icon: Icon(
              Icons.warning_amber_rounded,
              size: widget.iconSize,
              color: reported
                  ? Theme.of(context).disabledColor
                  : Colors.amber.shade800,
            ),
          );
        },
      );
}

final class _ReportReason {
  const _ReportReason({
    required this.code,
    required this.zh,
    required this.en,
  });

  final String code;
  final String zh;
  final String en;

  String label(bool english) => english ? en : zh;
}

const _reportReasons = <_ReportReason>[
  _ReportReason(code: 'spam', zh: '垃圾广告', en: 'Spam / Advertising'),
  _ReportReason(code: 'porn', zh: '色情低俗', en: 'Pornographic / Vulgar'),
  _ReportReason(code: 'abuse', zh: '辱骂攻击', en: 'Verbal Abuse / Harassment'),
  _ReportReason(code: 'fake', zh: '虚假信息', en: 'False / Misleading Info'),
  _ReportReason(
    code: 'political',
    zh: '涉政敏感',
    en: 'Politically Sensitive',
  ),
  _ReportReason(
    code: 'copyright',
    zh: '侵权抄袭',
    en: 'Copyright Infringement',
  ),
  _ReportReason(code: 'other', zh: '其它', en: 'Other'),
];

final class _ReportDialog extends StatefulWidget {
  const _ReportDialog({
    required this.controller,
    required this.type,
    required this.targetId,
    required this.english,
    required this.alreadyReported,
  });

  final SocialController controller;
  final ReportTargetType type;
  final String targetId;
  final bool english;
  final bool alreadyReported;

  @override
  State<_ReportDialog> createState() => _ReportDialogState();
}

final class _ReportDialogState extends State<_ReportDialog> {
  final _descriptionController = TextEditingController();
  String? _reasonCode;
  String? _validationMessage;
  bool _submitting = false;
  late bool _alreadyReported;

  @override
  void initState() {
    super.initState();
    _alreadyReported = widget.alreadyReported;
  }

  @override
  void dispose() {
    _descriptionController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final selected = _reportReasons.where(
      (reason) => reason.code == _reasonCode,
    );
    if (selected.isEmpty) {
      setState(() {
        _validationMessage =
            widget.english ? 'Please select a report reason.' : '请选择举报原因';
      });
      return;
    }

    final reason = selected.first;
    final description = _descriptionController.text.trim();
    if (reason.code == 'other' && description.isEmpty) {
      setState(() {
        _validationMessage = widget.english
            ? 'Please provide additional information for Other.'
            : '选择其它时请填写补充说明';
      });
      return;
    }

    setState(() {
      _submitting = true;
      _validationMessage = null;
    });
    final result = await widget.controller.report(
      type: widget.type,
      targetId: widget.targetId,
      reason: reason.label(widget.english),
      description: description.isEmpty ? null : description,
    );
    if (!mounted) return;

    if (result.succeeded) {
      Navigator.of(context).pop(true);
      return;
    }

    final message = result.message ?? '';
    final duplicate = message.contains('举报过') ||
        message.toLowerCase().contains('already reported');
    setState(() {
      _submitting = false;
      if (duplicate) {
        _alreadyReported = true;
      } else {
        _validationMessage = widget.english
            ? 'Unable to submit the report. Please try again.'
            : '举报提交失败，请重试';
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final english = widget.english;
    return AlertDialog(
      title: Row(
        children: [
          Icon(Icons.warning_amber_rounded, color: Colors.amber.shade800),
          const SizedBox(width: 10),
          Text(english ? 'Report' : '举报'),
        ],
      ),
      content: _alreadyReported
          ? Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(Icons.info_outline, size: 20),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    english
                        ? 'You have already reported this content.'
                        : '您已举报过该内容',
                  ),
                ),
              ],
            )
          : ConstrainedBox(
              constraints: BoxConstraints(
                maxWidth: 420,
                maxHeight: MediaQuery.sizeOf(context).height * .62,
              ),
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      english ? 'Reason' : '举报原因',
                      style: Theme.of(context).textTheme.titleSmall,
                    ),
                    const SizedBox(height: 4),
                    for (final reason in _reportReasons)
                      ListTile(
                        key: Key('report-reason-${reason.code}'),
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        leading: Icon(
                          _reasonCode == reason.code
                              ? Icons.radio_button_checked
                              : Icons.radio_button_unchecked,
                          color: _reasonCode == reason.code
                              ? Theme.of(context).colorScheme.primary
                              : null,
                        ),
                        title: Text(reason.label(english)),
                        onTap: _submitting
                            ? null
                            : () => setState(() {
                                  _reasonCode = reason.code;
                                  _validationMessage = null;
                                }),
                      ),
                    const SizedBox(height: 10),
                    TextField(
                      key: const Key('report-description'),
                      controller: _descriptionController,
                      enabled: !_submitting,
                      minLines: 3,
                      maxLines: 5,
                      maxLength: 500,
                      onChanged: (_) {
                        if (_validationMessage != null) {
                          setState(() => _validationMessage = null);
                        }
                      },
                      decoration: InputDecoration(
                        labelText: english ? 'Additional Info' : '补充说明',
                        hintText:
                            english ? 'Please describe the issue…' : '请描述具体问题…',
                        helperText: _reasonCode == 'other'
                            ? (english
                                ? 'Required when Other is selected'
                                : '选择其它时必填')
                            : (english ? 'Optional' : '选填'),
                        alignLabelWithHint: true,
                        border: const OutlineInputBorder(),
                      ),
                    ),
                    if (_validationMessage != null) ...[
                      const SizedBox(height: 4),
                      Text(
                        _validationMessage!,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
      actions: [
        if (!_alreadyReported)
          TextButton(
            onPressed: _submitting ? null : () => Navigator.of(context).pop(),
            child: Text(english ? 'Cancel' : '取消'),
          ),
        if (_alreadyReported)
          FilledButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(english ? 'Close' : '关闭'),
          )
        else
          FilledButton(
            key: const Key('report-submit'),
            onPressed: _submitting ? null : _submit,
            child: _submitting
                ? const SizedBox.square(
                    dimension: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : Text(english ? 'Submit' : '举报'),
          ),
      ],
    );
  }
}
