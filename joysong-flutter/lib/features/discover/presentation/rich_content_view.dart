import 'dart:convert';

import 'package:flutter/material.dart';

/// Lightweight rich-content renderer for API-provided HTML or Markdown.
///
/// Keeping this renderer in the shared Flutter layer gives Android and iOS the
/// same presentation without adding a platform WebView or another dependency.
class RichContentView extends StatelessWidget {
  const RichContentView({
    required this.content,
    this.textStyle,
    super.key,
  });

  final String content;
  final TextStyle? textStyle;

  @override
  Widget build(BuildContext context) {
    final parts = _contentParts(content);
    final baseStyle = textStyle ??
        Theme.of(context).textTheme.bodyLarge?.copyWith(height: 1.7) ??
        const TextStyle(height: 1.7);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final part in parts)
          if (part.imageUrl != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 10),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: Image.network(
                  part.imageUrl!,
                  fit: BoxFit.fitWidth,
                  errorBuilder: (_, __, ___) => const SizedBox.shrink(),
                ),
              ),
            )
          else if (part.markup.trim().isNotEmpty)
            SelectableText.rich(
              TextSpan(
                style: baseStyle.copyWith(
                  color: baseStyle.color ??
                      Theme.of(context).colorScheme.onSurface,
                ),
                children: _inlineSpans(context, part.markup, baseStyle),
              ),
            ),
      ],
    );
  }
}

final class _ContentPart {
  const _ContentPart.text(this.markup) : imageUrl = null;
  const _ContentPart.image(this.imageUrl) : markup = '';

  final String markup;
  final String? imageUrl;
}

List<_ContentPart> _contentParts(String raw) {
  var source = raw.trim();
  if (source.isEmpty) return const [];

  // Convert the Markdown constructs commonly returned by content APIs to the
  // same small HTML vocabulary consumed below.
  if (!_looksLikeHtml(source)) source = _markdownToMarkup(source);

  final parts = <_ContentPart>[];
  final imagePattern = RegExp(
    r'''<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["'][^>]*>''',
    caseSensitive: false,
  );
  var cursor = 0;
  for (final match in imagePattern.allMatches(source)) {
    if (match.start > cursor) {
      parts.add(_ContentPart.text(source.substring(cursor, match.start)));
    }
    final url = _decodeEntities(match.group(1) ?? '').trim();
    if (url.isNotEmpty) parts.add(_ContentPart.image(url));
    cursor = match.end;
  }
  if (cursor < source.length) {
    parts.add(_ContentPart.text(source.substring(cursor)));
  }
  return parts.isEmpty ? [_ContentPart.text(source)] : parts;
}

bool _looksLikeHtml(String value) =>
    RegExp(r'<\/?[a-z][^>]*>', caseSensitive: false).hasMatch(value);

String _markdownToMarkup(String value) {
  var source = value
      .replaceAllMapped(
        RegExp(r'!\[([^\]]*)\]\(([^)]+)\)'),
        (match) => '<img src="${match.group(2)}" alt="${match.group(1)}">',
      )
      .replaceAllMapped(
        RegExp(r'\[([^\]]+)\]\(([^)]+)\)'),
        (match) => '<a href="${match.group(2)}">${match.group(1)}</a>',
      )
      .replaceAllMapped(
        RegExp(r'\*\*(.+?)\*\*'),
        (match) => '<strong>${match.group(1)}</strong>',
      )
      .replaceAllMapped(
        RegExp(r'__(.+?)__'),
        (match) => '<strong>${match.group(1)}</strong>',
      )
      .replaceAllMapped(
        RegExp(r'(?<!\*)\*([^*\n]+)\*(?!\*)'),
        (match) => '<em>${match.group(1)}</em>',
      );
  final lines = source.split(RegExp(r'\r?\n'));
  return lines.map((line) {
    final trimmed = line.trimRight();
    if (trimmed.startsWith('### ')) return '<h3>${trimmed.substring(4)}</h3>';
    if (trimmed.startsWith('## ')) return '<h2>${trimmed.substring(3)}</h2>';
    if (trimmed.startsWith('# ')) return '<h1>${trimmed.substring(2)}</h1>';
    if (trimmed.startsWith('> ')) {
      return '<blockquote>${trimmed.substring(2)}</blockquote>';
    }
    if (RegExp(r'^[-*+] ').hasMatch(trimmed)) {
      return '<li>${trimmed.substring(2)}</li>';
    }
    return '$trimmed<br>';
  }).join();
}

List<InlineSpan> _inlineSpans(
  BuildContext context,
  String markup,
  TextStyle baseStyle,
) {
  var source = markup
      .replaceAll(RegExp(r'<\s*br\s*\/?>', caseSensitive: false), '\n')
      .replaceAll(RegExp(r'<\s*li\b[^>]*>', caseSensitive: false), '\n• ')
      .replaceAll(RegExp(r'<\s*\/\s*li\s*>', caseSensitive: false), '')
      .replaceAll(RegExp(r'<\s*blockquote\b[^>]*>', caseSensitive: false), '\n“')
      .replaceAll(RegExp(r'<\s*\/\s*blockquote\s*>', caseSensitive: false), '”\n')
      .replaceAll(RegExp(r'<\s*\/?\s*(p|div|section|ul|ol)\b[^>]*>', caseSensitive: false), '\n');

  final spans = <InlineSpan>[];
  final styleStack = <TextStyle>[baseStyle];
  final tagStack = <String>[];
  final tokenPattern = RegExp(r'<[^>]+>|[^<]+');

  for (final token in tokenPattern.allMatches(source)) {
    final value = token.group(0) ?? '';
    if (!value.startsWith('<')) {
      final decoded = _decodeEntities(value);
      if (decoded.isNotEmpty) {
        spans.add(TextSpan(text: decoded, style: styleStack.last));
      }
      continue;
    }

    final closing = RegExp(r'^<\s*\/\s*([a-z0-9]+)', caseSensitive: false)
        .firstMatch(value);
    if (closing != null) {
      final tag = closing.group(1)!.toLowerCase();
      final index = tagStack.lastIndexOf(tag);
      if (index >= 0) {
        tagStack.removeRange(index, tagStack.length);
        styleStack.removeRange(index + 1, styleStack.length);
      }
      if (const ['h1', 'h2', 'h3'].contains(tag)) {
        spans.add(const TextSpan(text: '\n'));
      }
      continue;
    }

    final opening =
        RegExp(r'^<\s*([a-z0-9]+)', caseSensitive: false).firstMatch(value);
    if (opening == null) continue;
    final tag = opening.group(1)!.toLowerCase();
    final nextStyle = switch (tag) {
      'b' || 'strong' => styleStack.last.copyWith(fontWeight: FontWeight.w700),
      'i' || 'em' => styleStack.last.copyWith(fontStyle: FontStyle.italic),
      'u' => styleStack.last.copyWith(decoration: TextDecoration.underline),
      's' ||
      'del' =>
        styleStack.last.copyWith(decoration: TextDecoration.lineThrough),
      'a' => styleStack.last.copyWith(
          color: Theme.of(context).colorScheme.primary,
          decoration: TextDecoration.underline,
        ),
      'h1' => baseStyle.copyWith(
          fontSize: 25, height: 1.35, fontWeight: FontWeight.w800),
      'h2' => baseStyle.copyWith(
          fontSize: 21, height: 1.4, fontWeight: FontWeight.w800),
      'h3' => baseStyle.copyWith(
          fontSize: 18, height: 1.45, fontWeight: FontWeight.w700),
      _ => null,
    };
    if (nextStyle != null) {
      if (const ['h1', 'h2', 'h3'].contains(tag) && spans.isNotEmpty) {
        spans.add(const TextSpan(text: '\n'));
      }
      tagStack.add(tag);
      styleStack.add(nextStyle);
    }
  }
  return spans;
}

String _decodeEntities(String value) {
  var result = value;
  const named = {
    '&nbsp;': ' ',
    '&amp;': '&',
    '&lt;': '<',
    '&gt;': '>',
    '&quot;': '"',
    '&#39;': "'",
    '&apos;': "'",
  };
  for (final entry in named.entries) {
    result = result.replaceAll(entry.key, entry.value);
  }
  result = result.replaceAllMapped(RegExp(r'&#(\d+);'), (match) {
    final code = int.tryParse(match.group(1) ?? '');
    return code == null ? match.group(0)! : String.fromCharCode(code);
  });
  result = result.replaceAllMapped(
      RegExp(r'&#x([0-9a-f]+);', caseSensitive: false), (match) {
    final code = int.tryParse(match.group(1) ?? '', radix: 16);
    return code == null ? match.group(0)! : String.fromCharCode(code);
  });
  // Decodes JSON-style escaped line breaks sometimes stored by editors.
  if (result.contains(r'\n') && !result.contains('\n')) {
    try {
      result = jsonDecode('"${result.replaceAll('"', r'\"')}"') as String;
    } catch (_) {
      // Keep the original server content when it is not a JSON string.
    }
  }
  return result.replaceAll(RegExp(r'\n{3,}'), '\n\n');
}
