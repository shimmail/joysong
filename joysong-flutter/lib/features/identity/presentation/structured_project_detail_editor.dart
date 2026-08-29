import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/localization/localization.dart';

const _riskDisclaimer = '实际效果和恢复情况因人而异，具体方案需由专业医生面诊后确定。';
const _riskDisclaimerEn =
    'Results and recovery vary by patient. A qualified doctor must confirm the final treatment plan after an in-person assessment.';
const _maxProjectDetailHtmlLength = 20000;

const _sections = <_ProjectDetailSection>[
  _ProjectDetailSection(
    id: 'principle',
    chineseTitle: '作用原理',
    englishTitle: 'How it works',
    chineseHint: '请填写项目的治疗原理、作用层次、主要材料或设备，以及预期改善方向。',
    englishHint:
        'Describe the treatment principle, treatment depth, main materials or equipment, and intended improvements.',
  ),
  _ProjectDetailSection(
    id: 'suitable',
    chineseTitle: '适合人群',
    englishTitle: 'Suitable for',
    chineseHint: '请填写适合接受该项目的人群特征、常见需求或适应症。',
    englishHint:
        'Describe suitable patient characteristics, common needs, or indications.',
  ),
  _ProjectDetailSection(
    id: 'contraindications',
    chineseTitle: '禁忌人群',
    englishTitle: 'Contraindications',
    chineseHint: '请填写不适合接受该项目的人群，以及需要医生谨慎评估的健康情况。',
    englishHint:
        'Describe who should not receive this treatment and health conditions that require careful medical assessment.',
  ),
  _ProjectDetailSection(
    id: 'recovery',
    chineseTitle: '恢复周期',
    englishTitle: 'Recovery',
    chineseHint: '请填写治疗后的常见反应、预计恢复时间、护理要求及复诊安排。',
    englishHint:
        'Describe common post-treatment reactions, expected recovery time, aftercare, and follow-up arrangements.',
  ),
  _ProjectDetailSection(
    id: 'highlights',
    chineseTitle: '项目亮点',
    englishTitle: 'Highlights',
    chineseHint: '请客观填写项目特点、方案优势及服务特色，避免保证效果或使用绝对化表述。',
    englishHint:
        'Objectively describe treatment features, plan advantages, and service strengths without guaranteed or absolute claims.',
  ),
  _ProjectDetailSection(
    id: 'risks',
    chineseTitle: '潜在风险及副作用',
    englishTitle: 'Potential risks and side effects',
    chineseHint: '请填写常见的短期反应、低概率但需要知晓的风险，以及发生异常情况时的处理建议。',
    englishHint:
        'Describe common short-term reactions, low-probability risks patients should know, and what to do if an abnormal reaction occurs.',
    showsRiskDisclaimer: true,
  ),
];

class StructuredProjectDetailEditor extends StatefulWidget {
  const StructuredProjectDetailEditor({
    required this.controller,
    required this.primaryFieldKey,
    required this.fieldKeyPrefix,
    this.enabled = true,
    super.key,
  });

  final TextEditingController controller;
  final Key primaryFieldKey;
  final String fieldKeyPrefix;
  final bool enabled;

  @override
  State<StructuredProjectDetailEditor> createState() =>
      _StructuredProjectDetailEditorState();
}

class _StructuredProjectDetailEditorState
    extends State<StructuredProjectDetailEditor> {
  late final Map<String, TextEditingController> _sectionControllers;
  var _writingExternalValue = false;
  var _htmlLength = 0;

  @override
  void initState() {
    super.initState();
    _sectionControllers = {
      for (final section in _sections) section.id: TextEditingController(),
    };
    widget.controller.addListener(_handleExternalChange);
    _applyExternalValue(notify: false);
  }

  @override
  void didUpdateWidget(covariant StructuredProjectDetailEditor oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_handleExternalChange);
      widget.controller.addListener(_handleExternalChange);
      _applyExternalValue();
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final (index, section) in _sections.indexed) ...[
            if (index > 0) const SizedBox(height: 16),
            Row(
              children: [
                Container(
                  width: 3,
                  height: 20,
                  decoration: BoxDecoration(
                    color: scheme.primary,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    context.localized(
                      section.chineseTitle,
                      section.englishTitle,
                    ),
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w600,
                        ),
                  ),
                ),
                Icon(
                  Icons.lock_outline,
                  size: 16,
                  color: scheme.onSurfaceVariant,
                ),
              ],
            ),
            if (section.showsRiskDisclaimer) ...[
              const SizedBox(height: 6),
              Text(
                context.localized(
                  _riskDisclaimer,
                  _riskDisclaimerEn,
                ),
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: scheme.onSurfaceVariant,
                    ),
              ),
            ],
            const SizedBox(height: 8),
            TextField(
              key: index == 0
                  ? widget.primaryFieldKey
                  : Key('${widget.fieldKeyPrefix}-${section.id}'),
              controller: _sectionControllers[section.id],
              enabled: widget.enabled,
              minLines: 3,
              maxLines: 5,
              decoration: InputDecoration(
                hintText: context.localized(
                  section.chineseHint,
                  section.englishHint,
                ),
                alignLabelWithHint: true,
              ),
              onChanged: (_) => _writeCanonicalHtml(),
            ),
          ],
          if (_htmlLength > _maxProjectDetailHtmlLength) ...[
            const SizedBox(height: 8),
            Text(
              context.localized(
                '项目介绍过长，请精简后再提交（$_htmlLength/$_maxProjectDetailHtmlLength）',
                'The project introduction is too long. Shorten it before submitting ($_htmlLength/$_maxProjectDetailHtmlLength).',
              ),
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: scheme.error,
                  ),
            ),
          ],
        ],
      ),
    );
  }

  void _handleExternalChange() {
    if (_writingExternalValue) return;
    _applyExternalValue();
  }

  void _applyExternalValue({bool notify = true}) {
    final values = _ProjectDetailHtmlCodec.decode(widget.controller.text);
    for (final section in _sections) {
      _sectionControllers[section.id]!.text = values[section.id] ?? '';
    }
    _htmlLength = widget.controller.text.trim().length;
    if (notify && mounted) setState(() {});
  }

  void _writeCanonicalHtml() {
    final canonical = _canonicalHtml();
    _replaceExternalText(canonical);
    setState(() => _htmlLength = canonical.length);
  }

  String _canonicalHtml() => _ProjectDetailHtmlCodec.encode({
        for (final section in _sections)
          section.id: _sectionControllers[section.id]!.text,
      });

  void _replaceExternalText(String value) {
    if (widget.controller.text == value) return;
    _writingExternalValue = true;
    widget.controller.value = TextEditingValue(
      text: value,
      selection: TextSelection.collapsed(offset: value.length),
    );
    _writingExternalValue = false;
  }

  @override
  void dispose() {
    widget.controller.removeListener(_handleExternalChange);
    for (final controller in _sectionControllers.values) {
      controller.dispose();
    }
    super.dispose();
  }
}

final class _ProjectDetailSection {
  const _ProjectDetailSection({
    required this.id,
    required this.chineseTitle,
    required this.englishTitle,
    required this.chineseHint,
    required this.englishHint,
    this.showsRiskDisclaimer = false,
  });

  final String id;
  final String chineseTitle;
  final String englishTitle;
  final String chineseHint;
  final String englishHint;
  final bool showsRiskDisclaimer;
}

abstract final class _ProjectDetailHtmlCodec {
  static final _headingPattern = RegExp(
    r'<h1\b[^>]*>([\s\S]*?)<\/h1\s*>',
    caseSensitive: false,
  );
  static final _breakPattern = RegExp(
    r'<br\s*\/?>',
    caseSensitive: false,
  );
  static final _blockEndPattern = RegExp(
    r'<\/(?:p|div|section|li|blockquote|h[1-6])\s*>',
    caseSensitive: false,
  );
  static final _tagPattern = RegExp(
    r'</?(?:h[1-6]|p|div|section|ul|ol|li|blockquote|br|strong|em|b|i|u|a|img)\b[^>]*>',
    caseSensitive: false,
  );
  static final _extraNewlines = RegExp(r'\n{3,}');
  static final _entityPattern = RegExp(
    r'&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos|nbsp);',
    caseSensitive: false,
  );

  static String encode(Map<String, String> values) {
    if (values.values.every((value) => value.trim().isEmpty)) return '';
    final output = <String>[];
    for (final section in _sections) {
      output
        ..add('<h1>${section.chineseTitle}</h1>')
        ..add('<p>${_escape(values[section.id] ?? '')}</p>');
      if (section.showsRiskDisclaimer) {
        final input = output.removeLast();
        output
          ..add('<p>$_riskDisclaimer</p>')
          ..add(input);
      }
    }
    return output.join('\n');
  }

  static Map<String, String> decode(String source) {
    final value = source.trim();
    if (value.isEmpty) return const {};
    final matches = _headingPattern.allMatches(value).toList(growable: false);
    final decoded = <String, String>{};
    for (var index = 0; index < matches.length; index++) {
      final match = matches[index];
      final heading = _plainText(match.group(1) ?? '');
      final section = _sections.cast<_ProjectDetailSection?>().firstWhere(
            (candidate) =>
                candidate?.chineseTitle == heading ||
                candidate?.englishTitle == heading,
            orElse: () => null,
          );
      if (section == null) continue;
      final end =
          index + 1 < matches.length ? matches[index + 1].start : value.length;
      var text = _plainText(value.substring(match.end, end));
      if (section.showsRiskDisclaimer) {
        for (final disclaimer in const [_riskDisclaimer, _riskDisclaimerEn]) {
          if (text.startsWith(disclaimer)) {
            text = text.substring(disclaimer.length).trim();
            break;
          }
        }
      }
      decoded[section.id] = text;
    }
    if (decoded.isNotEmpty) return decoded;
    return {'principle': _plainText(value)};
  }

  static String _escape(String value) {
    final normalized =
        value.trim().replaceAll('\r\n', '\n').replaceAll('\r', '\n');
    return const HtmlEscape(HtmlEscapeMode.element)
        .convert(normalized)
        .replaceAll('\n', '<br>');
  }

  static String _plainText(String value) {
    final withoutTags = value
        .replaceAll(_breakPattern, '\n')
        .replaceAll(_blockEndPattern, '\n')
        .replaceAll(_tagPattern, '');
    return _decodeEntities(withoutTags)
        .replaceAll('\r\n', '\n')
        .replaceAll('\r', '\n')
        .replaceAll(_extraNewlines, '\n\n')
        .trim();
  }

  static String _decodeEntities(String value) =>
      value.replaceAllMapped(_entityPattern, (match) {
        final entity = match.group(1)!;
        final normalized = entity.toLowerCase();
        if (normalized.startsWith('#x')) {
          return _decodeCodePoint(
            entity.substring(2),
            radix: 16,
            fallback: match.group(0)!,
          );
        }
        if (normalized.startsWith('#')) {
          return _decodeCodePoint(
            entity.substring(1),
            fallback: match.group(0)!,
          );
        }
        return const {
              'amp': '&',
              'lt': '<',
              'gt': '>',
              'quot': '"',
              'apos': "'",
              'nbsp': ' ',
            }[normalized] ??
            match.group(0)!;
      });

  static String _decodeCodePoint(
    String value, {
    int radix = 10,
    required String fallback,
  }) {
    final codePoint = int.tryParse(value, radix: radix);
    if (codePoint == null ||
        codePoint < 0 ||
        codePoint > 0x10ffff ||
        (codePoint >= 0xd800 && codePoint <= 0xdfff)) {
      return fallback;
    }
    return String.fromCharCode(codePoint);
  }
}
