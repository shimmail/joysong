import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_detail_shared.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_project_detail_view.dart';

void main() {
  testWidgets(
      'unavailable public institution project hides doctor booking controls',
      (tester) async {
    final item = DiscoverItem.fromJson({
      'id': 'institution-project-1',
      'name': 'Hydrating Facial',
      'hasAvailableDoctors': false,
      'institutionProject': {
        'name': 'Hydrating Facial',
        'price': 99,
        'images': <String>[],
      },
      'institution': {'name': 'Joysong Clinic'},
      'doctors': [
        {'id': 'doctor-1', 'name': 'Dr. Chen'},
      ],
    }, type: DiscoverContentType.project);

    await tester.pumpWidget(MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: const [Locale('zh'), Locale('en')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: Scaffold(body: CatalogProjectDetailView(item: item)),
    ));
    await tester.pumpAndSettle();
    await tester.drag(find.byType(CustomScrollView), const Offset(0, -700));
    await tester.pumpAndSettle();

    expect(find.text('当前暂无可预约医生'), findsOneWidget);
    expect(find.text('Dr. Chen'), findsNothing);
    expect(find.byType(CatalogBottomBar), findsNothing);
  });

  testWidgets(
      'available public institution project keeps doctor booking controls',
      (tester) async {
    final item = DiscoverItem.fromJson({
      'id': 'institution-project-1',
      'name': 'Hydrating Facial',
      'hasAvailableDoctors': true,
      'institutionProject': {
        'name': 'Hydrating Facial',
        'price': 99,
        'images': <String>[],
      },
      'institution': {'name': 'Joysong Clinic'},
      'doctors': [
        {'id': 'doctor-1', 'name': 'Dr. Chen'},
      ],
    }, type: DiscoverContentType.project);

    await tester.pumpWidget(MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: const [Locale('zh'), Locale('en')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: Scaffold(body: CatalogProjectDetailView(item: item)),
    ));
    await tester.pumpAndSettle();
    await tester.drag(find.byType(CustomScrollView), const Offset(0, -700));
    await tester.pumpAndSettle();

    expect(find.text('Dr. Chen'), findsOneWidget);
    expect(find.byType(CatalogBottomBar), findsOneWidget);
  });
}
