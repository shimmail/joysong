import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/presentation/rich_content_view.dart';
import 'package:url_launcher_platform_interface/url_launcher_platform_interface.dart';

void main() {
  late UrlLauncherPlatform originalPlatform;

  setUp(() {
    originalPlatform = UrlLauncherPlatform.instance;
  });

  tearDown(() {
    UrlLauncherPlatform.instance = originalPlatform;
  });

  testWidgets('renders h4 as separated bold block headings', (tester) async {
    await tester.pumpWidget(
      _app(
        const RichContentView(
          content: '<h4>First heading</h4><h4>Second heading</h4><p>Body</p>',
        ),
      ),
    );

    final text = tester
        .widget<SelectableText>(find.byType(SelectableText))
        .textSpan!
        .toPlainText();
    expect(text, contains('First heading\nSecond heading\nBody'));
    expect(
      _textSpan(tester, 'First heading').style?.fontWeight,
      FontWeight.w700,
    );
    expect(
      _textSpan(tester, 'Second heading').style?.fontWeight,
      FontWeight.w700,
    );
  });

  testWidgets(
    'default link launcher uses external mode and safely ignores platform failures',
    (tester) async {
      final platform = _RecordingUrlLauncherPlatform();
      UrlLauncherPlatform.instance = platform;
      const uri = 'https://example.com/privacy?source=content';

      for (final result in const [
        _LaunchResult.succeeds,
        _LaunchResult.returnsFalse,
        _LaunchResult.throwsError,
      ]) {
        platform.result = result;
        await tester.pumpWidget(
          _app(RichContentView(content: '<a href="$uri">Open</a>')),
        );
        await tester.tapAt(_glyphCenter(tester, 'Open'));
        await tester.pump();
        expect(tester.takeException(), isNull);
      }

      expect(platform.urls, [uri, uri, uri]);
      expect(
        platform.options.map((options) => options.mode),
        everyElement(PreferredLaunchMode.externalApplication),
      );
      expect(
        platform.options,
        everyElement(
          isA<LaunchOptions>()
              .having(
                (options) => options.webOnlyWindowName,
                'window name',
                isNull,
              )
              .having(
                (options) => options.webViewConfiguration.headers,
                'headers',
                isEmpty,
              ),
        ),
      );
    },
  );

  testWidgets('actual taps on https mailto and tel text call the launcher', (
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
      await tester.tapAt(_glyphCenter(tester, 'Open'));
      await tester.pump();
    }

    expect(launched.map((uri) => uri.scheme), ['https', 'mailto', 'tel']);
  });

  testWidgets('actual taps on unsafe anchors do not call the launcher', (
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
      await tester.tapAt(_glyphCenter(tester, 'Visible link'));
      await tester.pump();
    }

    expect(launched, isEmpty);
  });

  testWidgets('unsafe anchors use the surrounding normal text style', (
    tester,
  ) async {
    await tester.pumpWidget(
      _app(
        RichContentView(
          content: '<a href="javascript:alert(1)">Unsafe</a>',
          textStyle: const TextStyle(
            color: Colors.black,
            decoration: TextDecoration.none,
          ),
          onLinkTap: (_) {},
        ),
      ),
    );

    final span = _textSpan(tester, 'Unsafe');
    expect(span.style?.color, Colors.black);
    expect(span.style?.decoration, TextDecoration.none);
    expect(span.recognizer, isNull);
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

  testWidgets('owns recognizers across rebuild, replacement, and disposal', (
    tester,
  ) async {
    final firstLaunches = <Uri>[];
    final secondLaunches = <Uri>[];
    final harnessKey = GlobalKey<_LinkHarnessState>();

    await tester.pumpWidget(
      _app(
        _LinkHarness(
          key: harnessKey,
          content: '<a href="https://first.example">First</a>',
          onLinkTap: firstLaunches.add,
        ),
      ),
    );
    final oldRecognizer = _linkRecognizer(tester);

    harnessKey.currentState!.replace(
      content: '<a href="https://first.example">First</a>',
      onLinkTap: secondLaunches.add,
    );
    await tester.pump();
    expect(_linkRecognizer(tester), same(oldRecognizer));
    await tester.tapAt(_glyphCenter(tester, 'First'));
    await tester.pump();
    expect(firstLaunches, isEmpty);
    expect(secondLaunches, [Uri.parse('https://first.example')]);

    final gesture = await tester.startGesture(_glyphCenter(tester, 'First'));
    harnessKey.currentState!.replace(
      content: '<a href="https://second.example">Second</a>',
      onLinkTap: secondLaunches.add,
    );
    await tester.pump();
    await gesture.up();
    await tester.pump();
    final newRecognizer = _linkRecognizer(tester);

    expect(newRecognizer, isNot(same(oldRecognizer)));
    expect(tester.takeException(), isNull);
    await tester.tapAt(_glyphCenter(tester, 'Second'));
    await tester.pump();
    expect(secondLaunches.last, Uri.parse('https://second.example'));

    await tester.pumpWidget(const SizedBox.shrink());
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

TextSpan _textSpan(WidgetTester tester, String text) {
  final root =
      tester.widget<SelectableText>(find.byType(SelectableText)).textSpan!;
  TextSpan? result;
  root.visitChildren((span) {
    if (span is TextSpan && span.text == text) {
      result = span;
      return false;
    }
    return true;
  });
  return result!;
}

Offset _glyphCenter(WidgetTester tester, String text) {
  final editableText = find.descendant(
    of: find.byType(SelectableText),
    matching: find.byType(EditableText),
  );
  final editable = tester.state<EditableTextState>(editableText).renderEditable;
  final plainText = editable.text?.toPlainText() ?? '';
  final start = plainText.indexOf(text);
  expect(start, isNonNegative);
  final boxes = editable.getBoxesForSelection(
    TextSelection(baseOffset: start, extentOffset: start + text.length),
  );
  expect(boxes, isNotEmpty);
  final glyphRect = boxes
      .map((box) => box.toRect())
      .reduce((combined, box) => combined.expandToInclude(box));
  return editable.localToGlobal(glyphRect.center);
}

class _LinkHarness extends StatefulWidget {
  const _LinkHarness({
    required this.content,
    required this.onLinkTap,
    super.key,
  });

  final String content;
  final ValueChanged<Uri> onLinkTap;

  @override
  State<_LinkHarness> createState() => _LinkHarnessState();
}

class _LinkHarnessState extends State<_LinkHarness> {
  late String _content = widget.content;
  late ValueChanged<Uri> _onLinkTap = widget.onLinkTap;

  void replace({
    required String content,
    required ValueChanged<Uri> onLinkTap,
  }) {
    setState(() {
      _content = content;
      _onLinkTap = onLinkTap;
    });
  }

  @override
  Widget build(BuildContext context) {
    return RichContentView(content: _content, onLinkTap: _onLinkTap);
  }
}

enum _LaunchResult { succeeds, returnsFalse, throwsError }

class _RecordingUrlLauncherPlatform extends UrlLauncherPlatform {
  _LaunchResult result = _LaunchResult.succeeds;
  final urls = <String>[];
  final options = <LaunchOptions>[];

  @override
  Future<bool> launchUrl(String url, LaunchOptions launchOptions) async {
    urls.add(url);
    options.add(launchOptions);
    if (result == _LaunchResult.throwsError) {
      throw StateError('launcher unavailable');
    }
    return result == _LaunchResult.succeeds;
  }
}
