import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/rich_translation_validator.dart';

void main() {
  group('preservesRichContentStructure accepts safe equivalent markup', () {
    test('preserves nested paragraph list link emphasis image and break tags',
        () {
      const source = '<p>护理<strong>重点</strong><br></p>'
          '<ul><li><a href="https://care.test/a?x=1&amp;y=2">步骤一</a></li>'
          '<li><em>步骤二</em><img src="https://cdn.test/a.jpg"></li></ul>'
          '<ol><li>复诊</li></ol>';
      const translated = '<p>Care <strong>essentials</strong><br/></p>'
          '<ul><li><a href="https://care.test/a?x=1&#38;y=2">Step one</a></li>'
          '<li><em>Step two</em><img src="https://cdn.test/a.jpg" /></li></ul>'
          '<ol><li>Follow-up</li></ol>';

      expect(preservesRichContentStructure(source, translated), isTrue);
    });

    test('normalizes tag case attribute spacing and void tag spelling', () {
      const source =
          "<P ><A HREF = 'https://care.test/path'>护理</A><BR><IMG SRC = 'https://cdn.test/a.jpg'></P>";
      const translated =
          '<p><a href="https://care.test/path">Care</a><br/><img src="https://cdn.test/a.jpg" /></p>';

      expect(preservesRichContentStructure(source, translated), isTrue);
    });

    test('accepts safe relative links without changing their identity', () {
      const source = '<p><a href="/care/guide#after">护理指南</a></p>';
      const translated = '<p><a href="/care/guide#after">Care guide</a></p>';

      expect(preservesRichContentStructure(source, translated), isTrue);
    });

    test('accepts renderer-supported void spacing and encoded query entities',
        () {
      const source = '<p><a href="https://care.test/a?x=1&amp;y=2" '
          'title="Care">护理</a><br /><img src="https://cdn.test/a.jpg" /></p>';
      const translated = '<p><a href="https://care.test/a?x=1&#38;y=2" '
          'title="Care">Care</a><br/><img src="https://cdn.test/a.jpg"></p>';

      expect(preservesRichContentStructure(source, translated), isTrue);
    });
  });

  group('preservesRichContentStructure rejects structural mutation', () {
    const source = '<p>护理<strong>重点</strong></p>'
        '<ul><li>第一步</li><li>第二步</li></ul>';
    final cases = <String, String>{
      'missing tag': '<p>Care<strong>point</strong></p><ul><li>One</li></ul>',
      'reordered tag':
          '<p>Care<strong>point</strong></p><ul><li>One</li></ul><li>Two</li>',
      'extra tag':
          '<p>Care<strong><em>point</em></strong></p><ul><li>One</li><li>Two</li></ul>',
      'crossed nesting':
          '<p>Care<strong>point</p></strong><ul><li>One</li><li>Two</li></ul>',
      'missing close':
          '<p>Care<strong>point</strong><ul><li>One</li><li>Two</li></ul>',
      'unexpected close':
          '<p>Care<strong>point</strong></p></p><ul><li>One</li><li>Two</li></ul>',
      'non-void self close':
          '<p>Care<strong/>point</strong></p><ul><li>One</li><li>Two</li></ul>',
      'malformed attribute':
          '<p class=care>Care<strong>point</strong></p><ul><li>One</li><li>Two</li></ul>',
      'unterminated tag':
          '<p>Care<strong>point</strong></p><ul><li>One</li><li>Two</li></ul',
    };

    for (final entry in cases.entries) {
      test('rejects ${entry.key}', () {
        expect(
          preservesRichContentStructure(source, entry.value),
          isFalse,
        );
      });
    }

    test('rejects reordered link and image URL identities', () {
      const ordered = '<p><a href="https://care.test/a">甲</a>'
          '<a href="https://care.test/b">乙</a></p>'
          '<img src="https://cdn.test/a.jpg">'
          '<img src="https://cdn.test/b.jpg">';
      const reordered = '<p><a href="https://care.test/b">A</a>'
          '<a href="https://care.test/a">B</a></p>'
          '<img src="https://cdn.test/b.jpg">'
          '<img src="https://cdn.test/a.jpg">';

      expect(
        preservesRichContentStructure(ordered, reordered),
        isFalse,
      );
    });
  });

  group('preservesRichContentStructure protects URL identity', () {
    const source = '<p><a href="https://care.test/a">护理</a></p>'
        '<img src="https://cdn.test/a.jpg">';
    final cases = <String, String>{
      'changed link': '<p><a href="https://evil.test/a">Care</a></p>'
          '<img src="https://cdn.test/a.jpg">',
      'missing link URL': '<p><a title="care">Care</a></p>'
          '<img src="https://cdn.test/a.jpg">',
      'changed image': '<p><a href="https://care.test/a">Care</a></p>'
          '<img src="https://cdn.test/b.jpg">',
      'missing image URL': '<p><a href="https://care.test/a">Care</a></p>'
          '<img alt="care">',
      'duplicate link URL':
          '<p><a href="https://care.test/a" href="https://care.test/a">Care</a></p>'
              '<img src="https://cdn.test/a.jpg">',
      'extra linked tag': '<p><a href="https://care.test/a">Care</a></p>'
          '<a href="https://care.test/a">More</a><img src="https://cdn.test/a.jpg">',
    };

    for (final entry in cases.entries) {
      test('rejects ${entry.key}', () {
        expect(
          preservesRichContentStructure(source, entry.value),
          isFalse,
        );
      });
    }
  });

  group('preservesRichContentStructure rejects executable markup', () {
    for (final tag in const [
      'script',
      'iframe',
      'object',
      'embed',
      'applet',
      'svg',
      'math',
      'style',
    ]) {
      test('rejects $tag in translated markup', () {
        expect(
          preservesRichContentStructure(
            '<p>护理</p>',
            '<p>Care</p><$tag>payload</$tag>',
          ),
          isFalse,
        );
      });

      test('rejects $tag in source markup', () {
        expect(
          preservesRichContentStructure(
            '<p>护理</p><$tag>payload</$tag>',
            '<p>Care</p><$tag>payload</$tag>',
          ),
          isFalse,
        );
      });
    }
  });

  group('preservesRichContentStructure rejects event attributes', () {
    for (final attribute in const [
      'onclick="run()"',
      'OnLoad = "run()"',
      'on\tclick="run()"',
      'o&#110;click="run()"',
      'on&#x09;click="run()"',
    ]) {
      test('rejects obfuscated $attribute', () {
        expect(
          preservesRichContentStructure(
            '<p>护理</p>',
            '<p $attribute>Care</p>',
          ),
          isFalse,
        );
      });

      test('rejects obfuscated $attribute in source markup', () {
        expect(
          preservesRichContentStructure(
            '<p $attribute>护理</p>',
            '<p $attribute>Care</p>',
          ),
          isFalse,
        );
      });
    }
  });

  group('preservesRichContentStructure rejects unsafe URL schemes', () {
    for (final url in const [
      'javascript:alert(1)',
      'JaVaScRiPt:alert(1)',
      'java&#x73;cript:alert(1)',
      'java&#9;script:alert(1)',
      'java&Tab;script:alert(1)',
      'vbscript:msgbox(1)',
      'data:text/html,payload',
      'file:///etc/passwd',
      'blob:https://care.test/id',
      'jav&#97script:alert(1)',
      'jav&#x61script:alert(1)',
      'javascript&colon:alert(1)',
    ]) {
      test('rejects unsafe translated href $url', () {
        expect(
          preservesRichContentStructure(
            '<p><a href="https://care.test/a">护理</a></p>',
            '<p><a href="$url">Care</a></p>',
          ),
          isFalse,
        );
      });

      test('rejects unsafe source href $url', () {
        expect(
          preservesRichContentStructure(
            '<p><a href="$url">护理</a></p>',
            '<p><a href="$url">Care</a></p>',
          ),
          isFalse,
        );
      });
    }

    test('rejects an unsafe image source', () {
      expect(
        preservesRichContentStructure(
          '<img src="data:image/png;base64,AAAA">',
          '<img src="data:image/png;base64,AAAA">',
        ),
        isFalse,
      );
    });

    test('rejects unsafe semicolonless references in translated image URLs',
        () {
      expect(
        preservesRichContentStructure(
          '<img src="https://cdn.test/a.jpg">',
          '<img src="jav&#97script:alert(1)">',
        ),
        isFalse,
      );
    });

    test('rejects unsafe semicolonless references in source image URLs', () {
      expect(
        preservesRichContentStructure(
          '<img src="jav&#x61script:alert(1)">',
          '<img src="jav&#x61script:alert(1)">',
        ),
        isFalse,
      );
    });

    test('rejects ambiguous raw ampersands but accepts ordinary safe queries',
        () {
      expect(
        preservesRichContentStructure(
          '<a href="https://care.test/a?x=1&y=2">护理</a>',
          '<a href="https://care.test/a?x=1&y=2">Care</a>',
        ),
        isFalse,
      );
      expect(
        preservesRichContentStructure(
          '<a href="https://care.test/a?x=1">护理</a>',
          '<a href="https://care.test/a?x=1">Care</a>',
        ),
        isTrue,
      );
    });

    test('rejects a legacy semicolonless named reference before punctuation',
        () {
      const ambiguous = '<a href="https://care.test/&copy/path">Care guide</a>';

      expect(preservesRichContentStructure(ambiguous, ambiguous), isFalse);
    });
  });

  group('preservesRichContentStructure rejects renderer-incompatible syntax',
      () {
    final cases = <(String, String, String)>[
      (
        'space after opening delimiter',
        '<img src="https://cdn.test/a.jpg">',
        '< img src="https://cdn.test/a.jpg">',
      ),
      ('space after closing delimiter', '<p>Care</p>', '<p>Care</ p>'),
      ('space before self-close end', '<br>', '<br / >'),
      ('mixed normal and spaced tags', '<p>Care<br></p>', '<p>Care< br></p>'),
      (
        'raw less-than in link title',
        '<a href="https://care.test/a" title="ab">Care</a>',
        '<a href="https://care.test/a" title="a<b">Care</a>',
      ),
      (
        'raw greater-than in link title',
        '<a href="https://care.test/a" title="ab">Care</a>',
        '<a href="https://care.test/a" title="a>b">Care</a>',
      ),
      (
        'decoded less-than in link title',
        '<a href="https://care.test/a" title="ab">Care</a>',
        '<a href="https://care.test/a" title="a&lt;b">Care</a>',
      ),
      (
        'decoded greater-than in link title',
        '<a href="https://care.test/a" title="ab">Care</a>',
        '<a href="https://care.test/a" title="a&gt;b">Care</a>',
      ),
      (
        'nul in image alt',
        '<img src="https://cdn.test/a.jpg" alt="ab">',
        '<img src="https://cdn.test/a.jpg" alt="a\u0000b">',
      ),
      (
        'control in image title',
        '<img src="https://cdn.test/a.jpg" title="ab">',
        '<img src="https://cdn.test/a.jpg" title="a\tb">',
      ),
      (
        'control as tag whitespace',
        '<img src="https://cdn.test/a.jpg">',
        '<img\tsrc="https://cdn.test/a.jpg">',
      ),
    ];

    for (final entry in cases) {
      test('rejects ${entry.$1} in translated markup', () {
        expect(preservesRichContentStructure(entry.$2, entry.$3), isFalse);
      });

      test('rejects ${entry.$1} in source markup', () {
        expect(preservesRichContentStructure(entry.$3, entry.$3), isFalse);
      });
    }
  });
}
