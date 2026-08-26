import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/institution_project_preview_body.dart';

void main() {
  testWidgets(
      'renders a display-only preview with ordered gallery and CNY price',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: const [Locale('zh'), Locale('en')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: Scaffold(
        body: ListView(children: [
          InstitutionProjectPreviewBody(
            model: InstitutionProjectPreviewModel(
              name: 'Hydrating Facial',
              institutionName: 'Joysong Clinic',
              price: 1234.5,
              currency: 'CNY',
              salesCount: 18,
              tags: ['Skin', 'Hydration'],
              slogan: 'Glow naturally',
              description: 'Gentle care for dry skin',
              detailContent: '<h2>Details</h2><p>Safe rich content</p>',
              coverImage: 'https://cdn.example.com/cover.jpg',
              images: [
                'https://cdn.example.com/cover.jpg',
                'https://cdn.example.com/gallery.jpg',
              ],
            ),
          ),
        ]),
      ),
    ));
    await tester.pump();

    expect(find.text('Hydrating Facial'), findsOneWidget);
    expect(find.text('Joysong Clinic'), findsOneWidget);
    expect(find.text('¥1234.50'), findsOneWidget);
    expect(find.text('已售 18'), findsOneWidget);
    expect(find.text('Skin'), findsOneWidget);
    expect(find.text('Glow naturally'), findsOneWidget);
    expect(find.text('Gentle care for dry skin'), findsOneWidget);
    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget is SelectableText &&
            (widget.textSpan?.toPlainText().contains('Safe rich content') ??
                false),
      ),
      findsOneWidget,
    );
    expect(find.text('1/2'), findsOneWidget);
    expect(find.textContaining('https://cdn.example.com/'), findsNothing);
    expect(find.textContaining('预约'), findsNothing);
    expect(find.textContaining('收藏'), findsNothing);
    expect(find.textContaining('医生'), findsNothing);
    expect(find.textContaining('日记'), findsNothing);
    expect(find.textContaining('评价'), findsNothing);
    expect(find.textContaining('AI'), findsNothing);
  });

  testWidgets(
      'uses the shared broken-image placeholder when gallery loading fails',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: const [Locale('zh'), Locale('en')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: Scaffold(
        body: ListView(children: [
          InstitutionProjectPreviewBody(
            model: InstitutionProjectPreviewModel(
              name: 'USD project',
              institutionName: 'Joysong Clinic',
              price: 99,
              currency: 'USD',
              salesCount: 0,
              tags: [],
              slogan: null,
              description: null,
              detailContent: null,
              coverImage: null,
              images: ['https://invalid.example.com/unavailable.jpg'],
            ),
          ),
        ]),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text(r'$99.00'), findsOneWidget);
    expect(find.byIcon(Icons.broken_image_outlined), findsOneWidget);
  });
}
