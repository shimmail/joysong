import 'dart:async';
import 'dart:convert';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

/// Lightweight rich-content renderer for API-provided HTML or Markdown.
///
/// Keeping this renderer in the shared Flutter layer gives Android and iOS the
/// same presentation without adding a platform WebView or another dependency.
class RichContentView extends StatefulWidget {
  const RichContentView({
    required this.content,
    this.textStyle,
    this.onImageTap,
    this.onLinkTap,
    super.key,
  });

  final String content;
  final TextStyle? textStyle;
  final ValueChanged<String>? onImageTap;
  final ValueChanged<Uri>? onLinkTap;

  @override
  State<RichContentView> createState() => _RichContentViewState();
}

final class _RichContentViewState extends State<RichContentView> {
  final List<TapGestureRecognizer> _linkRecognizers = [];

  @override
  Widget build(BuildContext context) {
    _disposeLinkRecognizers();
    final parts = _contentParts(widget.content);
    final baseStyle = widget.textStyle ??
        Theme.of(context).textTheme.bodyLarge?.copyWith(height: 1.7) ??
        const TextStyle(height: 1.7);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final part in parts)
          if (part.imageUrl != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 10),
              child: GestureDetector(
                onTap: widget.onImageTap == null
                    ? null
                    : () => widget.onImageTap!(part.imageUrl!),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(12),
                  child: Image.network(
                    part.imageUrl!,
                    fit: BoxFit.fitWidth,
                    errorBuilder: (_, __, ___) => const SizedBox.shrink(),
                  ),
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
                children: _inlineSpans(
                  context,
                  part.markup,
                  baseStyle,
                  _recognizerFor,
                ),
              ),
            ),
      ],
    );
  }

  TapGestureRecognizer _recognizerFor(Uri uri) {
    final recognizer = TapGestureRecognizer()
      ..onTap = () {
        final callback = widget.onLinkTap;
        if (callback != null) {
          callback(uri);
        } else {
          unawaited(_launchExternal(uri));
        }
      };
    _linkRecognizers.add(recognizer);
    return recognizer;
  }

  void _disposeLinkRecognizers() {
    for (final recognizer in _linkRecognizers) {
      recognizer.dispose();
    }
    _linkRecognizers.clear();
  }

  @override
  void dispose() {
    _disposeLinkRecognizers();
    super.dispose();
  }
}

Future<void> _launchExternal(Uri uri) async {
  await launchUrl(uri, mode: LaunchMode.externalApplication);
}

List<String> richContentImageUrls(String content) => _contentParts(content)
    .map((part) => part.imageUrl)
    .whereType<String>()
    .toList(growable: false);

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
  final source = value
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
  TapGestureRecognizer Function(Uri uri) recognizerFor,
) {
  final source = markup
      .replaceAll(RegExp(r'<\s*br\s*\/?>', caseSensitive: false), '\n')
      .replaceAll(
          RegExp(r'<\s*blockquote\b[^>]*>', caseSensitive: false), '\n“')
      .replaceAll(
          RegExp(r'<\s*\/\s*blockquote\s*>', caseSensitive: false), '”\n')
      .replaceAll(
          RegExp(r'<\s*\/?\s*(p|div|section)\b[^>]*>', caseSensitive: false),
          '\n');

  final spans = <InlineSpan>[];
  final styleStack = <_InlineStyleFrame>[
    _InlineStyleFrame(tag: '', style: baseStyle),
  ];
  final listStack = <_ListFrame>[];
  final tokenPattern = RegExp(r'<[^>]+>|[^<]+');

  for (final token in tokenPattern.allMatches(source)) {
    final value = token.group(0) ?? '';
    if (!value.startsWith('<')) {
      final decoded = _decodeEntities(value);
      if (decoded.isNotEmpty) {
        spans.add(
          TextSpan(
            text: decoded,
            style: styleStack.last.style,
            recognizer: styleStack.last.recognizer,
          ),
        );
      }
      continue;
    }

    final closing = RegExp(r'^<\s*\/\s*([a-z0-9]+)', caseSensitive: false)
        .firstMatch(value);
    if (closing != null) {
      final tag = closing.group(1)!.toLowerCase();
      if (tag == 'ul' || tag == 'ol') {
        if (listStack.isNotEmpty && listStack.last.tag == tag) {
          listStack.removeLast();
        }
        _appendNewline(spans, baseStyle);
      }
      final index = styleStack.lastIndexWhere((frame) => frame.tag == tag);
      if (index > 0) {
        styleStack.removeRange(index, styleStack.length);
      }
      if (const ['h1', 'h2', 'h3'].contains(tag)) {
        _appendNewline(spans, baseStyle);
      }
      continue;
    }

    final opening =
        RegExp(r'^<\s*([a-z0-9]+)', caseSensitive: false).firstMatch(value);
    if (opening == null) continue;
    final tag = opening.group(1)!.toLowerCase();
    if (tag == 'ul' || tag == 'ol') {
      _appendNewline(spans, baseStyle);
      listStack.add(_ListFrame(tag));
      continue;
    }
    if (tag == 'li') {
      _appendNewline(spans, baseStyle);
      final marker = listStack.isEmpty
          ? '• '
          : '${'  ' * (listStack.length - 1)}${listStack.last.nextMarker()} ';
      spans.add(TextSpan(text: marker, style: styleStack.last.style));
      continue;
    }
    final currentStyle = styleStack.last.style;
    final nextStyle = switch (tag) {
      'b' || 'strong' => currentStyle.copyWith(fontWeight: FontWeight.w700),
      'i' || 'em' => currentStyle.copyWith(fontStyle: FontStyle.italic),
      'u' => currentStyle.copyWith(decoration: TextDecoration.underline),
      's' ||
      'del' =>
        currentStyle.copyWith(decoration: TextDecoration.lineThrough),
      'a' => currentStyle.copyWith(
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
        _appendNewline(spans, baseStyle);
      }
      final uri = tag == 'a' ? _safeHref(value) : null;
      styleStack.add(
        _InlineStyleFrame(
          tag: tag,
          style: nextStyle,
          recognizer: tag == 'a'
              ? (uri == null ? null : recognizerFor(uri))
              : styleStack.last.recognizer,
        ),
      );
    }
  }
  return spans;
}

final class _InlineStyleFrame {
  const _InlineStyleFrame({
    required this.tag,
    required this.style,
    this.recognizer,
  });

  final String tag;
  final TextStyle style;
  final GestureRecognizer? recognizer;
}

final class _ListFrame {
  _ListFrame(this.tag);

  final String tag;
  int count = 0;

  String nextMarker() {
    if (tag == 'ul') return '•';
    count += 1;
    return '$count.';
  }
}

void _appendNewline(List<InlineSpan> spans, TextStyle style) {
  if (spans.isEmpty) return;
  final last = spans.last;
  if (last is TextSpan && (last.text ?? '').endsWith('\n')) return;
  spans.add(TextSpan(text: '\n', style: style));
}

Uri? _safeHref(String anchorTag) {
  final match = RegExp(
    r'''\bhref\s*=\s*(["'])(.*?)\1''',
    caseSensitive: false,
  ).firstMatch(anchorTag);
  final href = _decodeEntities(match?.group(2) ?? '').trim();
  if (href.isEmpty) return null;
  final uri = Uri.tryParse(href);
  if (uri == null) return null;
  final scheme = uri.scheme.toLowerCase();
  return switch (scheme) {
    'https' when uri.host.isNotEmpty => uri,
    'mailto' || 'tel' when uri.path.trim().isNotEmpty => uri,
    _ => null,
  };
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
