const _allowedTags = <String>{
  'a',
  'b',
  'blockquote',
  'br',
  'del',
  'div',
  'em',
  'h1',
  'h2',
  'h3',
  'i',
  'img',
  'li',
  'ol',
  'p',
  's',
  'section',
  'strong',
  'u',
  'ul',
};

const _voidTags = <String>{'br', 'img'};

const _linkAttributes = <String>{'href', 'rel', 'target', 'title'};
const _imageAttributes = <String>{
  'alt',
  'height',
  'src',
  'title',
  'width',
};

const _unsafeSchemes = <String>{
  'about',
  'blob',
  'data',
  'file',
  'javascript',
  'vbscript',
};

/// Returns whether [translated] preserves the safe renderer-visible structure
/// of [source]. HTML is validated directly while plain text and the Markdown
/// subset supported by RichContentView are normalized before comparison.
bool preservesRichContentStructure(String source, String translated) {
  final sourceContent = _parseRenderedContent(source);
  final translatedContent = _parseRenderedContent(translated);
  return sourceContent != null &&
      translatedContent != null &&
      sourceContent.kind == translatedContent.kind &&
      _sameValues(sourceContent.markup.tags, translatedContent.markup.tags) &&
      _sameValues(sourceContent.markup.urls, translatedContent.markup.urls);
}

_ParsedRenderedContent? _parseRenderedContent(String content) {
  final source = content.trim();
  if (_looksLikeHtml(source)) {
    final markup = _parseMarkup(source);
    return markup == null
        ? null
        : _ParsedRenderedContent(_RichContentKind.html, markup);
  }

  final markdown = _markdownToMarkup(source);
  if (markdown == null) return null;
  final markup = _parseMarkup(markdown.markup);
  if (markup == null) return null;
  return _ParsedRenderedContent(
    markdown.hasMarkup ? _RichContentKind.markdown : _RichContentKind.plain,
    markup,
  );
}

bool _looksLikeHtml(String value) =>
    RegExp(r'<\/?[a-z][^>]*>', caseSensitive: false).hasMatch(value);

_NormalizedMarkdown? _markdownToMarkup(String value) {
  var hasMarkup = false;
  var ambiguous = false;
  final source = value
      .replaceAllMapped(
        RegExp(r'!\[([^\]]*)\]\(([^)]+)\)'),
        (match) {
          hasMarkup = true;
          if (match.group(2)!.contains('(')) ambiguous = true;
          return '<img src="${match.group(2)}" alt="${match.group(1)}">';
        },
      )
      .replaceAllMapped(
        RegExp(r'\[([^\]]+)\]\(([^)]+)\)'),
        (match) {
          hasMarkup = true;
          if (match.group(2)!.contains('(')) ambiguous = true;
          return '<a href="${match.group(2)}">${match.group(1)}</a>';
        },
      )
      .replaceAllMapped(
        RegExp(r'\*\*(.+?)\*\*'),
        (match) {
          hasMarkup = true;
          return '<strong>${match.group(1)}</strong>';
        },
      )
      .replaceAllMapped(
        RegExp(r'__(.+?)__'),
        (match) {
          hasMarkup = true;
          return '<strong>${match.group(1)}</strong>';
        },
      )
      .replaceAllMapped(
        RegExp(r'(?<!\*)\*([^*\n]+)\*(?!\*)'),
        (match) {
          hasMarkup = true;
          return '<em>${match.group(1)}</em>';
        },
      );
  final lines = source.split(RegExp(r'\r?\n'));
  final markup = lines.map((line) {
    final trimmed = line.trimRight();
    if (trimmed.startsWith('### ')) {
      hasMarkup = true;
      return '<h3>${trimmed.substring(4)}</h3>';
    }
    if (trimmed.startsWith('## ')) {
      hasMarkup = true;
      return '<h2>${trimmed.substring(3)}</h2>';
    }
    if (trimmed.startsWith('# ')) {
      hasMarkup = true;
      return '<h1>${trimmed.substring(2)}</h1>';
    }
    if (trimmed.startsWith('> ')) {
      hasMarkup = true;
      return '<blockquote>${trimmed.substring(2)}</blockquote>';
    }
    if (RegExp(r'^[-*+] ').hasMatch(trimmed)) {
      hasMarkup = true;
      return '<li>${trimmed.substring(2)}</li>';
    }
    return '$trimmed<br>';
  }).join();
  return ambiguous ? null : _NormalizedMarkdown(markup, hasMarkup: hasMarkup);
}

_ParsedMarkup? _parseMarkup(String markup) {
  final tags = <String>[];
  final urls = <String>[];
  final stack = <String>[];
  var cursor = 0;

  while (cursor < markup.length) {
    final start = markup.indexOf('<', cursor);
    if (start < 0) break;
    final end = _tagEnd(markup, start);
    if (end == null) return null;
    final token = markup.substring(start, end + 1);
    if (!_parseTag(token, tags, urls, stack)) return null;
    cursor = end + 1;
  }

  if (stack.isNotEmpty) return null;
  return _ParsedMarkup(tags, urls);
}

int? _tagEnd(String markup, int start) {
  int? quote;
  for (var index = start + 1; index < markup.length; index += 1) {
    final code = markup.codeUnitAt(index);
    if (quote != null) {
      if (code == quote) quote = null;
      if (code == 0x3c) return null;
      continue;
    }
    if (code == 0x22 || code == 0x27) {
      quote = code;
    } else if (code == 0x3c) {
      return null;
    } else if (code == 0x3e) {
      return index;
    }
  }
  return null;
}

bool _parseTag(
  String token,
  List<String> tags,
  List<String> urls,
  List<String> stack,
) {
  if (_containsControl(token)) return false;
  var cursor = 1;
  final closing = cursor < token.length && token.codeUnitAt(cursor) == 0x2f;
  if (closing) {
    cursor += 1;
  }

  final nameStart = cursor;
  while (cursor < token.length && _isTagNameCode(token.codeUnitAt(cursor))) {
    cursor += 1;
  }
  if (cursor == nameStart || !_isAsciiLetter(token.codeUnitAt(nameStart))) {
    return false;
  }
  final name = token.substring(nameStart, cursor).toLowerCase();
  if (!_allowedTags.contains(name)) return false;

  if (closing) {
    cursor = _skipWhitespace(token, cursor);
    if (cursor != token.length - 1 ||
        token.codeUnitAt(cursor) != 0x3e ||
        _voidTags.contains(name) ||
        stack.isEmpty ||
        stack.last != name) {
      return false;
    }
    stack.removeLast();
    tags.add('close:$name');
    return true;
  }

  final attributes = <String, String>{};
  var selfClosing = false;
  while (true) {
    cursor = _skipWhitespace(token, cursor);
    if (cursor >= token.length) return false;
    final code = token.codeUnitAt(cursor);
    if (code == 0x3e) {
      if (cursor != token.length - 1) return false;
      break;
    }
    if (code == 0x2f) {
      cursor += 1;
      if (cursor != token.length - 1 || token.codeUnitAt(cursor) != 0x3e) {
        return false;
      }
      selfClosing = true;
      break;
    }

    final attributeStart = cursor;
    while (cursor < token.length &&
        _isAttributeNameCode(token.codeUnitAt(cursor))) {
      cursor += 1;
    }
    if (cursor == attributeStart ||
        !_isAsciiLetter(token.codeUnitAt(attributeStart))) {
      return false;
    }
    final attribute = token.substring(attributeStart, cursor).toLowerCase();
    if (attribute.startsWith('on') || attributes.containsKey(attribute)) {
      return false;
    }
    final allowedAttributes = switch (name) {
      'a' => _linkAttributes,
      'img' => _imageAttributes,
      _ => const <String>{},
    };
    if (!allowedAttributes.contains(attribute)) return false;

    cursor = _skipWhitespace(token, cursor);
    if (cursor >= token.length || token.codeUnitAt(cursor) != 0x3d) {
      return false;
    }
    cursor = _skipWhitespace(token, cursor + 1);
    if (cursor >= token.length) return false;
    final quote = token.codeUnitAt(cursor);
    if (quote != 0x22 && quote != 0x27) return false;
    final valueStart = ++cursor;
    while (cursor < token.length && token.codeUnitAt(cursor) != quote) {
      if (token.codeUnitAt(cursor) == 0x3c) return false;
      cursor += 1;
    }
    if (cursor >= token.length) return false;
    final value = _decodeAttributeEntities(
      token.substring(valueStart, cursor),
    );
    if (value == null ||
        _containsControl(value) ||
        _containsAngle(value) ||
        _containsQuote(value)) {
      return false;
    }
    attributes[attribute] = value;
    cursor += 1;
  }

  if (selfClosing && !_voidTags.contains(name)) return false;
  if (name == 'a') {
    final href = attributes['href'];
    final canonical = href == null ? null : _canonicalUrl(href, image: false);
    if (canonical == null) return false;
    urls.add('a:$canonical');
  }
  if (name == 'img') {
    final src = attributes['src'];
    final canonical = src == null ? null : _canonicalUrl(src, image: true);
    if (canonical == null) return false;
    urls.add('img:$canonical');
  }

  if (_voidTags.contains(name)) {
    tags.add('void:$name');
  } else {
    tags.add('open:$name');
    stack.add(name);
  }
  return true;
}

String? _canonicalUrl(String value, {required bool image}) {
  final canonical = value.trim();
  if (canonical.isEmpty || canonical.contains('\\')) return null;

  final schemeCandidate = canonical
      .replaceAll(RegExp(r'[\u0000-\u0020\u007f-\u009f\s]'), '')
      .toLowerCase();
  final match = RegExp(r'^([a-z][a-z0-9+.-]*):').firstMatch(schemeCandidate);
  if (match != null) {
    final scheme = match.group(1)!;
    if (_unsafeSchemes.contains(scheme)) return null;
    final allowed = image
        ? const <String>{'http', 'https'}
        : const <String>{'http', 'https', 'mailto', 'tel'};
    if (!allowed.contains(scheme)) return null;
  }
  return canonical;
}

String? _decodeAttributeEntities(String value) {
  if (_hasAmbiguousCharacterReference(value)) return null;

  // Keep this order aligned with RichContentView._decodeEntities. In
  // particular, named references are case-sensitive and amp may expose a
  // numeric reference that the renderer decodes in its later numeric pass.
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

  var valid = true;
  result = result.replaceAllMapped(RegExp(r'&#(\d+);'), (match) {
    final decoded = _entityCodePoint(match.group(1)!, radix: 10);
    if (decoded == null) valid = false;
    return decoded ?? '';
  });
  result = result.replaceAllMapped(
    RegExp(r'&#x([0-9a-f]+);', caseSensitive: false),
    (match) {
      final decoded = _entityCodePoint(match.group(1)!, radix: 16);
      if (decoded == null) valid = false;
      return decoded ?? '';
    },
  );
  return valid ? result : null;
}

bool _hasAmbiguousCharacterReference(String value) {
  var cursor = 0;
  while (cursor < value.length) {
    final ampersand = value.indexOf('&', cursor);
    if (ampersand < 0) return false;
    final supported = _supportedEntityPattern.matchAsPrefix(value, ampersand);
    if (supported != null) {
      cursor = supported.end;
      continue;
    }
    if (ampersand + 1 < value.length &&
        value.codeUnitAt(ampersand + 1) == 0x23) {
      return true;
    }
    if (_unsupportedNamedEntityPattern.matchAsPrefix(value, ampersand) !=
            null ||
        _semicolonlessNamedEntityPattern.matchAsPrefix(value, ampersand) !=
            null) {
      return true;
    }
    cursor = ampersand + 1;
  }
  return false;
}

final _supportedEntityPattern = RegExp(
  r'&(?:nbsp|amp|lt|gt|quot|apos|#39|#[0-9]+|#[xX][0-9a-fA-F]+);',
);
final _unsupportedNamedEntityPattern = RegExp(r'&[A-Za-z][A-Za-z0-9]*;');
final _semicolonlessNamedEntityPattern =
    RegExp(r'&[A-Za-z][A-Za-z0-9]*(?=[^A-Za-z0-9=]|$)');

String? _entityCodePoint(String digits, {required int radix}) {
  final codePoint = int.tryParse(digits, radix: radix);
  if (codePoint == null ||
      codePoint == 0 ||
      codePoint > 0x10ffff ||
      (codePoint >= 0xd800 && codePoint <= 0xdfff)) {
    return null;
  }
  return String.fromCharCode(codePoint);
}

int _skipWhitespace(String value, int cursor) {
  while (cursor < value.length && _isWhitespace(value.codeUnitAt(cursor))) {
    cursor += 1;
  }
  return cursor;
}

bool _isWhitespace(int code) => code == 0x20;

bool _containsControl(String value) => value.codeUnits
    .any((code) => code <= 0x1f || (code >= 0x7f && code <= 0x9f));

bool _containsAngle(String value) => value.contains('<') || value.contains('>');

bool _containsQuote(String value) => value.contains('"') || value.contains("'");

bool _isAsciiLetter(int code) =>
    (code >= 0x41 && code <= 0x5a) || (code >= 0x61 && code <= 0x7a);

bool _isAsciiDigit(int code) => code >= 0x30 && code <= 0x39;

bool _isTagNameCode(int code) => _isAsciiLetter(code) || _isAsciiDigit(code);

bool _isAttributeNameCode(int code) =>
    _isAsciiLetter(code) ||
    _isAsciiDigit(code) ||
    code == 0x2d ||
    code == 0x2e ||
    code == 0x3a ||
    code == 0x5f;

bool _sameValues(List<String> first, List<String> second) {
  if (first.length != second.length) return false;
  for (var index = 0; index < first.length; index += 1) {
    if (first[index] != second[index]) return false;
  }
  return true;
}

final class _ParsedMarkup {
  const _ParsedMarkup(this.tags, this.urls);

  final List<String> tags;
  final List<String> urls;
}

enum _RichContentKind { plain, markdown, html }

final class _ParsedRenderedContent {
  const _ParsedRenderedContent(this.kind, this.markup);

  final _RichContentKind kind;
  final _ParsedMarkup markup;
}

final class _NormalizedMarkdown {
  const _NormalizedMarkdown(this.markup, {required this.hasMarkup});

  final String markup;
  final bool hasMarkup;
}
