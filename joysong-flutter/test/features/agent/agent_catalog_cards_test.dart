import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_catalog_cards.dart';

void main() {
  testWidgets('status card renders operand and target guidance in Chinese',
      (tester) async {
    await _pumpCard(
      tester,
      const AgentComparisonStatusCard(request: _incompleteRequest),
    );

    expect(find.text('还需要补充对比信息'), findsOneWidget);
    expect(find.text('诊所甲'), findsOneWidget);
    expect(find.text('请选择至少两个对比对象'), findsOneWidget);
    expect(find.text('请明确要比较机构、医生、项目还是机构项目'), findsOneWidget);
  });

  testWidgets('status card renders operand and target guidance in English',
      (tester) async {
    await _pumpCard(
      tester,
      const AgentComparisonStatusCard(request: _incompleteRequest),
      locale: const Locale('en'),
    );

    expect(find.text('More comparison details needed'), findsOneWidget);
    expect(find.text('诊所甲'), findsOneWidget);
    expect(find.text('Select at least two items to compare'), findsOneWidget);
    expect(
      find.text(
        'Specify whether to compare clinics, doctors, treatments, or clinic treatments',
      ),
      findsOneWidget,
    );
  });

  testWidgets('institution and doctor tables omit price rows', (tester) async {
    await _pumpCard(tester, AgentComparisonTable(report: _institutionReport));
    expect(find.text('City'), findsOneWidget);
    expect(find.text('Price should not render'), findsNothing);

    await _pumpCard(tester, AgentComparisonTable(report: _doctorReport));
    expect(find.text('Title'), findsOneWidget);
    expect(find.text('Price should not render'), findsNothing);
  });

  testWidgets('project table shows category reference price rating and tags',
      (tester) async {
    await _pumpCard(tester, AgentComparisonTable(report: _projectReport));

    for (final row in ['Category', 'Reference price', 'Rating', 'Tags']) {
      expect(find.text(row), findsOneWidget);
    }
  });

  testWidgets(
      'institution project table shows city category prices rating reviews sales verification and tags',
      (tester) async {
    await _pumpCard(
      tester,
      AgentComparisonTable(report: _institutionProjectReport),
    );

    for (final row in [
      'City',
      'Category',
      'Clinic price',
      'Reference price',
      'Rating',
      'Review count',
      'Sales',
      'Clinic verified',
      'Tags',
    ]) {
      expect(find.text(row), findsOneWidget);
    }
  });

  testWidgets(
      'doctor table keeps one column per doctor and shows multiple practice institutions and verification summary',
      (tester) async {
    await _pumpCard(tester, AgentComparisonTable(report: _doctorReport));

    expect(find.text('Dr. One'), findsOneWidget);
    expect(find.text('Clinic A (Shanghai), Clinic B (Beijing)'), findsOneWidget);
    expect(find.text('2/2'), findsOneWidget);
  });

  testWidgets('comparison table localizes missing structured values',
      (tester) async {
    await _pumpCard(
      tester,
      AgentComparisonTable(report: _missingValueReport),
    );
    expect(find.text('暂无平台数据'), findsNWidgets(2));

    await _pumpCard(
      tester,
      AgentComparisonTable(report: _missingValueReport),
      locale: const Locale('en'),
    );
    expect(find.text('Not available'), findsNWidgets(2));
  });

  testWidgets(
      'comparison table renders at most four operand columns and remains horizontally scrollable',
      (tester) async {
    await _pumpCard(tester, AgentComparisonTable(report: _fiveItemReport));

    expect(find.text('Item 1'), findsOneWidget);
    expect(find.text('Item 4'), findsOneWidget);
    expect(find.text('Item 5'), findsNothing);
    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget is SingleChildScrollView &&
            widget.scrollDirection == Axis.horizontal,
      ),
      findsOneWidget,
    );
  });
}

Future<void> _pumpCard(
  WidgetTester tester,
  Widget child, {
  Locale locale = const Locale('zh'),
}) =>
    tester.pumpWidget(
      MaterialApp(
        locale: locale,
        supportedLocales: const [Locale('zh'), Locale('en')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: Scaffold(body: child),
      ),
    );

const _incompleteRequest = AgentComparisonRequest(
  operands: [
    AgentComparisonOperand(
      entityType: 'INSTITUTION',
      entityId: 'clinic-a',
      displayName: '诊所甲',
    ),
  ],
  targetType: null,
  dimensions: [],
  constraints: {},
  missingFields: {'OPERANDS', 'TARGET_TYPE'},
);

final _institutionReport = _report(
  items: [
    _item(
      type: 'INSTITUTION',
      id: 'clinic-a',
      name: 'Clinic A',
      attributes: const {
        'City': 'Shanghai',
        'Clinic verified': 'Verified',
        'Rating': '4.8',
        'Review count': '100',
        'Doctors': '12',
        'Specialties': 'Skin',
        'Price should not render': r'$100',
      },
    ),
  ],
  dimensions: const [
    'City',
    'Clinic verified',
    'Rating',
    'Review count',
    'Doctors',
    'Specialties',
  ],
);

final _doctorReport = _report(
  items: [
    _item(
      type: 'DOCTOR',
      id: 'doctor-1',
      name: 'Dr. One',
      attributes: const {
        'Title': 'Chief physician',
        'Doctor verified': 'Verified',
        'Credentials': 'Board certified',
        'Specialties': 'Skin',
        'Rating': '4.9',
        'Review count': '88',
        'Practice institutions': 'Clinic A (Shanghai), Clinic B (Beijing)',
        'Practice institution verification': '2/2',
        'Price should not render': r'$100',
      },
    ),
  ],
  dimensions: const [
    'Title',
    'Doctor verified',
    'Credentials',
    'Specialties',
    'Rating',
    'Review count',
    'Practice institutions',
    'Practice institution verification',
  ],
);

final _projectReport = _report(
  items: [
    _item(
      type: 'PROJECT',
      id: 'project-1',
      name: 'Project One',
      attributes: const {
        'Category': 'Skin',
        'Reference price': r'$100',
        'Rating': '4.7',
        'Tags': 'Popular',
      },
    ),
  ],
  dimensions: const ['Category', 'Reference price', 'Rating', 'Tags'],
);

final _institutionProjectReport = _report(
  items: [
    _item(
      type: 'INSTITUTION_PROJECT',
      id: 'offering-1',
      name: 'Clinic A · Project One',
      attributes: const {
        'City': 'Shanghai',
        'Category': 'Skin',
        'Clinic price': r'$90',
        'Reference price': r'$100',
        'Rating': '4.7',
        'Review count': '20',
        'Sales': '30',
        'Clinic verified': 'Verified',
        'Tags': 'Popular',
      },
    ),
  ],
  dimensions: const [
    'City',
    'Category',
    'Clinic price',
    'Reference price',
    'Rating',
    'Review count',
    'Sales',
    'Clinic verified',
    'Tags',
  ],
);

final _missingValueReport = _report(
  items: [
    _item(
      type: 'PROJECT',
      id: 'project-1',
      name: 'Project One',
      attributes: const {'Category': 'Skin', 'Reference price': '   '},
    ),
    _item(
      type: 'PROJECT',
      id: 'project-2',
      name: 'Project Two',
      attributes: const {'Category': 'Skin'},
    ),
  ],
  dimensions: const ['Reference price'],
);

final _fiveItemReport = _report(
  items: [
    for (var index = 1; index <= 5; index++)
      _item(
        type: 'PROJECT',
        id: 'project-$index',
        name: 'Item $index',
        attributes: const {'Rating': '4.8'},
      ),
  ],
  dimensions: const ['Rating'],
);

AgentCatalogReport _report({
  required List<AgentCatalogItem> items,
  required List<String> dimensions,
}) =>
    AgentCatalogReport(
      mode: 'COMPARISON',
      title: '',
      summary: '',
      items: items,
      comparisonDimensions: dimensions,
      warnings: const [],
    );

AgentCatalogItem _item({
  required String type,
  required String id,
  required String name,
  required Map<String, String> attributes,
}) =>
    AgentCatalogItem(
      type: type,
      id: id,
      name: name,
      subtitle: '',
      summary: '',
      attributes: attributes,
      institutionId: null,
      projectId: null,
      canChatWithHuman: false,
    );
