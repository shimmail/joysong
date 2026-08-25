import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/presentation/rich_content_view.dart';

void main() {
  testWidgets('only https mailto and tel anchors call the injected launcher', (
    tester,
  ) async {
    final launched = <Uri>[];

    for (final href in const [
      'HTTPS://example.com/privacy',
      'MAILTO:privacy@example.com',
      'TEL:+8613800000000',
    ]) {
      await tester.pumpWidget(
        _app(
          RichContentView(
            content: '<a href="$href">Open</a>',
            onLinkTap: launched.add,
          ),
        ),
      );
      _linkRecognizer(tester).onTap!();
    }

    expect(launched.map((uri) => uri.scheme), ['https', 'mailto', 'tel']);
  });

  testWidgets('unsafe and empty anchors render without becoming actionable', (
    tester,
  ) async {
    final launched = <Uri>[];

    for (final href in const [
      'http://example.com',
      'javascript:alert(1)',
      'data:text/plain,bad',
      '/relative/path',
      'https:/missing-host',
      '',
    ]) {
      await tester.pumpWidget(
        _app(
          RichContentView(
            content: '<a href="$href">Visible link</a>',
            onLinkTap: launched.add,
          ),
        ),
      );
      expect(find.text('Visible link'), findsOneWidget);
      expect(_linkRecognizers(tester), isEmpty);
    }

    expect(launched, isEmpty);
  });

  testWidgets('ordered and unordered lists preserve their markers', (
    tester,
  ) async {
    await tester.pumpWidget(
      _app(
        const RichContentView(
          content: '<ol><li>First</li><li>Second</li></ol>'
              '<ul><li>Bullet</li></ul>',
        ),
      ),
    );

    final text = tester
        .widget<SelectableText>(find.byType(SelectableText))
        .textSpan!
        .toPlainText();
    expect(text, contains('1. First'));
    expect(text, contains('2. Second'));
    expect(text, contains('• Bullet'));
  });

  testWidgets('rebuilds links without retaining obsolete recognizers', (
    tester,
  ) async {
    final firstLaunches = <Uri>[];
    final secondLaunches = <Uri>[];

    await tester.pumpWidget(
      _app(
        RichContentView(
          content: '<a href="https://first.example">First</a>',
          onLinkTap: firstLaunches.add,
        ),
      ),
    );
    final oldRecognizer = _linkRecognizer(tester);

    await tester.pumpWidget(
      _app(
        RichContentView(
          content: '<a href="https://second.example">Second</a>',
          onLinkTap: secondLaunches.add,
        ),
      ),
    );
    final newRecognizer = _linkRecognizer(tester);

    expect(newRecognizer, isNot(same(oldRecognizer)));
    newRecognizer.onTap!();
    expect(firstLaunches, isEmpty);
    expect(secondLaunches.single, Uri.parse('https://second.example'));
    expect(tester.takeException(), isNull);
  });
}

Widget _app(Widget child) => MaterialApp(home: Scaffold(body: child));

TapGestureRecognizer _linkRecognizer(WidgetTester tester) {
  return _linkRecognizers(tester).single;
}

List<TapGestureRecognizer> _linkRecognizers(WidgetTester tester) {
  final root =
      tester.widget<SelectableText>(find.byType(SelectableText)).textSpan!;
  final recognizers = <TapGestureRecognizer>[];
  root.visitChildren((span) {
    if (span is TextSpan && span.recognizer is TapGestureRecognizer) {
      recognizers.add(span.recognizer! as TapGestureRecognizer);
    }
    return true;
  });
  return recognizers;
}
