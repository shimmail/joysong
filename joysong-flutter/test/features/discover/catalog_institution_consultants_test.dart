import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/presentation/catalog_institution_detail_view.dart';

void main() {
  testWidgets('emits the current institution id when consultation is tapped',
      (tester) async {
    String? selectedInstitutionId;
    await tester.pumpWidget(_testApp(
      item: _institutionItem(id: 'institution-1'),
      onConsultInstitution: (id) => selectedInstitutionId = id,
    ));

    await tester.tap(find.text('咨询机构'));
    await tester.pump();

    expect(selectedInstitutionId, 'institution-1');
  });

  testWidgets('disables consultation when the institution id is missing',
      (tester) async {
    await tester.pumpWidget(_testApp(
      item: _institutionItem(id: ''),
      onConsultInstitution: (_) {},
    ));

    final button = tester.widget<FilledButton>(
      find.widgetWithText(FilledButton, '咨询机构'),
    );
    expect(button.onPressed, isNull);
  });
}

Widget _testApp({
  required DiscoverItem item,
  required ValueChanged<String> onConsultInstitution,
}) =>
    MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: const [Locale('zh'), Locale('en')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: Scaffold(
        body: CatalogInstitutionDetailView(
          item: item,
          onConsultInstitution: onConsultInstitution,
        ),
      ),
    );

DiscoverItem _institutionItem({required String id}) => DiscoverItem(
      id: id,
      type: DiscoverContentType.institution,
      title: '测试机构',
      raw: {
        'institution': {
          'id': id,
          'name': '测试机构',
        },
        'projects': const [],
        'diaries': const [],
        'reviews': const [],
        'doctors': const [],
      },
    );
