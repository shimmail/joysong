import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/optimized_network_image.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_institution_detail_view.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_review_section.dart';

void main() {
  testWidgets('shows complete institution review information in Chinese', (
    tester,
  ) async {
    await tester.pumpWidget(
      _testApp(
        locale: const Locale('zh'),
        reviews: const [
          {
            'id': 'review-1',
            'rating': 4,
            'content': '医生沟通耐心，服务流程也很清晰。',
            'tags': '耐心,专业',
            'images':
                'https://example.com/review-one.jpg,https://example.com/review-two.jpg',
            'userName': '王女士',
            'createdAt': '2026-08-06T12:34:00',
          },
        ],
      ),
    );

    await _scrollToReviews(tester);

    final card = find.byType(CatalogReviewCard);
    expect(card, findsOneWidget);
    expect(
      find.descendant(of: card, matching: find.text('★ 4.0')),
      findsOneWidget,
    );
    expect(find.text('医生沟通耐心，服务流程也很清晰。'), findsOneWidget);
    expect(find.text('耐心'), findsOneWidget);
    expect(find.text('专业'), findsOneWidget);
    expect(find.text('王女士'), findsOneWidget);
    expect(find.text('2026-08-06'), findsOneWidget);
    final imageList = find.descendant(
      of: card,
      matching: find.byWidgetPredicate(
        (widget) =>
            widget is ListView && widget.scrollDirection == Axis.horizontal,
      ),
    );
    expect(imageList, findsOneWidget);
    expect(
      tester.widget<ListView>(imageList).semanticChildCount,
      2,
    );
    expect(
      find.descendant(
        of: card,
        matching: find.byType(OptimizedNetworkImage),
      ),
      findsWidgets,
    );
  });

  testWidgets('shows the English empty state when there are no reviews', (
    tester,
  ) async {
    await tester.pumpWidget(
      _testApp(locale: const Locale('en'), reviews: const []),
    );

    await _scrollToReviews(tester);

    expect(find.text('Patient reviews'), findsOneWidget);
    expect(find.text('No reviews yet'), findsOneWidget);
  });

  testWidgets('shows English fallbacks for missing optional review text', (
    tester,
  ) async {
    await tester.pumpWidget(
      _testApp(
        locale: const Locale('en'),
        reviews: const [
          {
            'id': 'review-2',
            'rating': 5,
            'content': '',
            'tags': '',
            'images': '',
            'userName': '',
            'createdAt': '2026-08-07T09:00:00',
          },
        ],
      ),
    );

    await _scrollToReviews(tester);

    final card = find.byType(CatalogReviewCard);
    expect(find.text('No written review'), findsOneWidget);
    expect(find.text('Anonymous user'), findsOneWidget);
    expect(find.descendant(of: card, matching: find.text('★ 5.0')),
        findsOneWidget);
  });
}

Widget _testApp({
  required Locale locale,
  required List<Map<String, Object?>> reviews,
}) {
  final item = DiscoverItem(
    id: 'institution-1',
    type: DiscoverContentType.institution,
    title: '测试机构',
    raw: {
      'institution': const {
        'id': 'institution-1',
        'name': '测试机构',
        'rating': 4.6,
        'reviewCount': 12,
      },
      'projects': const [],
      'diaries': const [],
      'reviews': reviews,
      'doctors': const [],
    },
  );
  return MaterialApp(
    locale: locale,
    supportedLocales: const [Locale('zh'), Locale('en')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    home: Scaffold(body: CatalogInstitutionDetailView(item: item)),
  );
}

Future<void> _scrollToReviews(WidgetTester tester) async {
  final scrollable = find.byType(CustomScrollView);
  for (var index = 0; index < 6; index++) {
    await tester.drag(scrollable, const Offset(0, -500));
    await tester.pump();
  }
  await tester.pump(const Duration(milliseconds: 400));
}
