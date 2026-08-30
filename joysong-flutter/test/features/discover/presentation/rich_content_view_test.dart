import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/presentation/rich_content_view.dart';

void main() {
  testWidgets('keeps a single line break between a heading and paragraph',
      (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: RichContentView(
            content: '<h1>作用原理</h1>&#x20;\n<p>原理</p>',
          ),
        ),
      ),
    );

    final text = tester.widget<SelectableText>(find.byType(SelectableText));

    expect(text.textSpan?.toPlainText(), '作用原理\n原理\n');
  });
}
